package server

import (
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/sepiropht/unscroll/server/internal/auth"
	"github.com/sepiropht/unscroll/server/internal/billing"
	"github.com/sepiropht/unscroll/server/internal/ratelimit"
	"github.com/sepiropht/unscroll/server/internal/store"
)

const (
	maxBody = 2_000_000
	// AccountCreatesPerHour is the per-IP cap on POST /v1/accounts.
	// The per-minute limiter also applies to that route.
	AccountCreatesPerHour = 5

	corsAllowMethods = "GET, PUT, POST, DELETE, OPTIONS"
)

var idPattern = regexp.MustCompile(`^[a-z][a-z0-9._-]{0,127}$`)

// Server is the sync HTTP API.
type Server struct {
	store       *store.Store
	auth        *auth.Verifier
	limit       *ratelimit.Limiter
	createLimit *ratelimit.Limiter
	now         func() time.Time
	mux         *http.ServeMux
	billing     *billing.Service
}

type accountCreated struct {
	Token     string `json:"token"`
	Protocol  int    `json:"protocol"`
	AccountID string `json:"accountId"`
}

// New wires store, bearer bootstrap hashes, and the per-minute rate limit.
func New(st *store.Store, verifier *auth.Verifier, lim *ratelimit.Limiter) *Server {
	if verifier == nil {
		verifier = auth.Empty()
	}
	if lim == nil {
		lim = ratelimit.New(60)
	}
	s := &Server{
		store:       st,
		auth:        verifier,
		limit:       lim,
		createLimit: ratelimit.NewWindow(AccountCreatesPerHour, time.Hour),
		now:         time.Now,
		mux:         http.NewServeMux(),
	}
	s.mux.HandleFunc("GET /healthz", s.health)
	s.mux.HandleFunc("POST /v1/accounts", s.createAccount)
	s.mux.HandleFunc("DELETE /v1/accounts", s.deleteAccount)
	s.mux.HandleFunc("GET /v1/blobs", s.list)
	s.mux.HandleFunc("GET /v1/blobs/{id}", s.get)
	s.mux.HandleFunc("PUT /v1/blobs/{id}", s.put)
	s.mux.HandleFunc("GET /v1/billing", s.billingStatus)
	s.mux.HandleFunc("POST /v1/billing/checkout", s.billingCheckout)
	s.mux.HandleFunc("POST /v1/billing/portal", s.billingPortal)
	s.mux.HandleFunc("POST /v1/billing/webhook", s.billingWebhook)
	s.mux.HandleFunc("GET /v1/billing/return", s.billingReturn)
	s.mux.HandleFunc("OPTIONS /v1/billing", s.options)
	s.mux.HandleFunc("OPTIONS /v1/billing/checkout", s.options)
	s.mux.HandleFunc("OPTIONS /v1/billing/portal", s.options)
	s.mux.HandleFunc("OPTIONS /", s.options)
	s.mux.HandleFunc("OPTIONS /healthz", s.options)
	s.mux.HandleFunc("OPTIONS /v1/accounts", s.options)
	s.mux.HandleFunc("OPTIONS /v1/blobs", s.options)
	s.mux.HandleFunc("OPTIONS /v1/blobs/{id}", s.options)
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	cors(w)
	s.mux.ServeHTTP(w, r)
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "protocol": 1, "billing": s.billingEnabled()})
}

func (s *Server) options(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) createAccount(w http.ResponseWriter, r *http.Request) {
	if !s.allowIP(w, r) {
		return
	}
	if !s.createLimit.Allow("ip:"+clientIP(r), s.now()) {
		rejectRate(w, "3600")
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, 1<<20))
	id, token, err := s.store.CreateAccount(s.now())
	if err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusCreated, accountCreated{
		Token:     token,
		Protocol:  1,
		AccountID: id,
	})
}

func (s *Server) deleteAccount(w http.ResponseWriter, r *http.Request) {
	accountID, ok := s.authenticate(w, r)
	if !ok {
		return
	}
	if err := s.store.DeleteAccount(accountID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			unauthorized(w)
			return
		}
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	accountID, ok := s.gate(w, r)
	if !ok {
		return
	}
	blobs, err := s.store.List(accountID)
	if err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	type item struct {
		ID        string `json:"id"`
		ETag      string `json:"etag"`
		Bytes     int    `json:"bytes"`
		UpdatedAt string `json:"updatedAt"`
	}
	out := struct {
		Blobs []item `json:"blobs"`
	}{Blobs: []item{}}
	for _, b := range blobs {
		out.Blobs = append(out.Blobs, item{
			ID:        b.ID,
			ETag:      b.ETag,
			Bytes:     b.Bytes,
			UpdatedAt: b.UpdatedAt.UTC().Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) get(w http.ResponseWriter, r *http.Request) {
	accountID, ok := s.gate(w, r)
	if !ok {
		return
	}
	id := r.PathValue("id")
	if !idPattern.MatchString(id) {
		http.Error(w, "bad id", http.StatusBadRequest)
		return
	}
	b, err := s.store.Get(accountID, id)
	if errors.Is(err, sql.ErrNoRows) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	w.Header().Set("ETag", quoteETag(b.ETag))
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(b.Body)
}

func (s *Server) put(w http.ResponseWriter, r *http.Request) {
	accountID, ok := s.gate(w, r)
	if !ok {
		return
	}
	id := r.PathValue("id")
	if !idPattern.MatchString(id) {
		http.Error(w, "bad id", http.StatusBadRequest)
		return
	}
	if r.ContentLength > maxBody {
		http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody+1))
	if err != nil {
		http.Error(w, "body", http.StatusBadRequest)
		return
	}
	if len(body) > maxBody {
		http.Error(w, "too large", http.StatusRequestEntityTooLarge)
		return
	}

	none := r.Header.Get("If-None-Match")
	match := r.Header.Get("If-Match")
	if none != "" && match != "" {
		http.Error(w, "precondition", http.StatusPreconditionFailed)
		return
	}
	now := s.now()
	switch {
	case none != "":
		if strings.TrimSpace(none) != "*" {
			http.Error(w, "precondition", http.StatusPreconditionFailed)
			return
		}
		b, err := s.store.Create(accountID, id, body, now)
		if err != nil {
			writePutErr(w, err)
			return
		}
		w.Header().Set("ETag", quoteETag(b.ETag))
		w.WriteHeader(http.StatusNoContent)
	case match != "":
		etag, ok := parseMatch(match)
		if !ok {
			http.Error(w, "precondition", http.StatusPreconditionFailed)
			return
		}
		b, err := s.store.Update(accountID, id, etag, body, now)
		if err != nil {
			writePutErr(w, err)
			return
		}
		w.Header().Set("ETag", quoteETag(b.ETag))
		w.WriteHeader(http.StatusNoContent)
	default:
		http.Error(w, "precondition required", http.StatusPreconditionFailed)
	}
}

func writePutErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrExists):
		http.Error(w, "exists", http.StatusPreconditionFailed)
	case errors.Is(err, store.ErrPrecondition):
		http.Error(w, "precondition", http.StatusPreconditionFailed)
	case errors.Is(err, store.ErrQuota):
		http.Error(w, "quota", http.StatusRequestEntityTooLarge)
	default:
		http.Error(w, "store", http.StatusInternalServerError)
	}
}

// resolveAccount prefers the accounts table. Bootstrap env and tokens-file
// digests map to the legacy account only when that row still exists.
func (s *Server) resolveAccount(token string) (string, error) {
	id, err := s.store.AccountIDByToken(token)
	if err != nil {
		return "", err
	}
	matchedBootstrap := s.auth != nil && s.auth.Accept(token)
	if id != "" {
		return id, nil
	}
	if !matchedBootstrap || s.auth == nil || s.auth.LegacyHash() == "" {
		return "", nil
	}
	return s.store.LegacyAccountByHash(s.auth.LegacyHash())
}

// gate authenticates a sync request and applies the hosted billing check.
func (s *Server) gate(w http.ResponseWriter, r *http.Request) (string, bool) {
	accountID, ok := s.authenticate(w, r)
	if !ok || !s.entitled(w, accountID) {
		return "", false
	}
	return accountID, true
}

func (s *Server) authenticate(w http.ResponseWriter, r *http.Request) (string, bool) {
	if !s.allowIP(w, r) {
		return "", false
	}
	token, ok := bearer(r)
	if !ok {
		unauthorized(w)
		return "", false
	}
	accountID, err := s.resolveAccount(token)
	if err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return "", false
	}
	if accountID == "" {
		unauthorized(w)
		return "", false
	}
	if !s.limit.Allow("tok:"+auth.TokenHash(token), s.now()) {
		rejectRate(w, "60")
		return "", false
	}
	return accountID, true
}

func (s *Server) allowIP(w http.ResponseWriter, r *http.Request) bool {
	if !s.limit.Allow("ip:"+clientIP(r), s.now()) {
		rejectRate(w, "60")
		return false
	}
	return true
}

func rejectRate(w http.ResponseWriter, retryAfter string) {
	w.Header().Set("Retry-After", retryAfter)
	http.Error(w, "rate limit", http.StatusTooManyRequests)
}

func unauthorized(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", "Bearer")
	http.Error(w, "unauthorized", http.StatusUnauthorized)
}

func bearer(r *http.Request) (string, bool) {
	h := r.Header.Get("Authorization")
	const p = "Bearer "
	if len(h) <= len(p) || !strings.EqualFold(h[:len(p)], p) {
		return "", false
	}
	tok := strings.TrimSpace(h[len(p):])
	if tok == "" || strings.Contains(tok, " ") {
		return "", false
	}
	return tok, true
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func cors(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Access-Control-Allow-Origin", "*")
	h.Set("Access-Control-Allow-Methods", corsAllowMethods)
	h.Set("Access-Control-Allow-Headers", "authorization, content-type, if-match, if-none-match")
	h.Set("Access-Control-Expose-Headers", "ETag")
	h.Set("Access-Control-Max-Age", "86400")
}

func quoteETag(etag string) string {
	return `"` + etag + `"`
}

// parseMatch accepts a single strong quoted etag. Weak tags fail.
func parseMatch(v string) (string, bool) {
	v = strings.TrimSpace(v)
	if v == "" || strings.HasPrefix(v, "W/") || strings.HasPrefix(v, "w/") {
		return "", false
	}
	if len(v) >= 2 && v[0] == '"' && v[len(v)-1] == '"' {
		v = v[1 : len(v)-1]
	}
	if !strings.HasPrefix(v, "sha256-") || strings.ContainsAny(v, `",`) {
		return "", false
	}
	return v, true
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}
