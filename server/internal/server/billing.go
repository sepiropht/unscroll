package server

import (
	"database/sql"
	"encoding/json"
	"errors"
	"html"
	"io"
	"log"
	"net/http"
	"strings"

	"github.com/sepiropht/unscroll/server/internal/billing"
)

// PaymentRequiredMessage is the text clients show on 402.
const PaymentRequiredMessage = "Trial ended - subscribe or self-host"

// SetBilling enables the optional hosted billing plan. nil disables it.
func (s *Server) SetBilling(b *billing.Service) { s.billing = b }

func (s *Server) billingEnabled() bool { return s.billing != nil }

// entitled runs after auth on sync routes. It starts the trial on first use
// and writes 402 when the trial ended without an active subscription.
func (s *Server) entitled(w http.ResponseWriter, accountID string) bool {
	if s.billing == nil {
		return true
	}
	p, err := s.billing.Touch(accountID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			unauthorized(w)
			return false
		}
		http.Error(w, "store", http.StatusInternalServerError)
		return false
	}
	if p.Allowed() {
		return true
	}
	writeJSON(w, http.StatusPaymentRequired, map[string]any{
		"error":   "subscription_required",
		"message": PaymentRequiredMessage,
		"billing": p,
	})
	return false
}

func (s *Server) billingStatus(w http.ResponseWriter, r *http.Request) {
	if s.billing == nil {
		writeJSON(w, http.StatusOK, billing.Plan{Enabled: false})
		return
	}
	accountID, ok := s.authOnly(w, r)
	if !ok {
		return
	}
	p, err := s.billing.Plan(accountID)
	if err != nil {
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (s *Server) billingCheckout(w http.ResponseWriter, r *http.Request) {
	if s.billing == nil {
		http.Error(w, "billing disabled", http.StatusNotFound)
		return
	}
	accountID, ok := s.authOnly(w, r)
	if !ok {
		return
	}
	var in struct {
		Plan string `json:"plan"`
	}
	raw, _ := io.ReadAll(io.LimitReader(r.Body, 4096))
	if len(strings.TrimSpace(string(raw))) > 0 {
		if err := json.Unmarshal(raw, &in); err != nil {
			http.Error(w, "bad json", http.StatusBadRequest)
			return
		}
	}
	u, err := s.billing.Checkout(r.Context(), accountID, in.Plan, s.publicBase(r))
	switch {
	case errors.Is(err, billing.ErrUnknownPlan):
		http.Error(w, "unknown plan", http.StatusBadRequest)
		return
	case errors.Is(err, billing.ErrAlreadyActive), errors.Is(err, billing.ErrExempt):
		http.Error(w, "already active", http.StatusConflict)
		return
	case err != nil:
		log.Printf("billing checkout: %v", err)
		http.Error(w, "billing provider", http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"url": u})
}

func (s *Server) billingPortal(w http.ResponseWriter, r *http.Request) {
	if s.billing == nil {
		http.Error(w, "billing disabled", http.StatusNotFound)
		return
	}
	accountID, ok := s.authOnly(w, r)
	if !ok {
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(r.Body, 4096))
	u, err := s.billing.Portal(r.Context(), accountID, s.publicBase(r))
	if errors.Is(err, billing.ErrNoCustomer) {
		http.Error(w, "no subscription", http.StatusNotFound)
		return
	}
	if err != nil {
		log.Printf("billing portal: %v", err)
		http.Error(w, "billing provider", http.StatusBadGateway)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"url": u})
}

func (s *Server) billingWebhook(w http.ResponseWriter, r *http.Request) {
	if s.billing == nil {
		http.Error(w, "billing disabled", http.StatusNotFound)
		return
	}
	payload, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		http.Error(w, "body", http.StatusBadRequest)
		return
	}
	err = s.billing.HandleWebhook(payload, r.Header.Get("Stripe-Signature"))
	var sigErr *billing.SignatureError
	switch {
	case errors.As(err, &sigErr):
		http.Error(w, "bad signature", http.StatusBadRequest)
		return
	case err != nil:
		log.Printf("billing webhook: %v", err)
		http.Error(w, "store", http.StatusInternalServerError)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"received": true})
}

func (s *Server) billingReturn(w http.ResponseWriter, r *http.Request) {
	msg := "You can close this tab and go back to Unscroll."
	switch r.URL.Query().Get("status") {
	case "success":
		msg = "Thanks! Your subscription is being activated. You can close this tab and go back to Unscroll."
	case "cancel":
		msg = "Checkout canceled. Nothing was charged. You can close this tab."
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
	_, _ = io.WriteString(w, `<!doctype html><html lang="en"><head><meta charset="utf-8">`+
		`<meta name="viewport" content="width=device-width,initial-scale=1"><title>Unscroll</title>`+
		`<style>body{font-family:system-ui,sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem;line-height:1.5}</style>`+
		`</head><body><h1>Unscroll</h1><p>`+html.EscapeString(msg)+`</p></body></html>`)
}

// authOnly authenticates without the 402 gate, so an ended trial can still
// read its plan and open Checkout or the portal.
func (s *Server) authOnly(w http.ResponseWriter, r *http.Request) (string, bool) {
	return s.authenticate(w, r)
}

func (s *Server) publicBase(r *http.Request) string {
	// Never derived from the Host header: return URLs come from config only.
	return s.billing.PublicURL()
}
