package server_test

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sepiropht/unscroll/server/internal/auth"
	"github.com/sepiropht/unscroll/server/internal/ratelimit"
	"github.com/sepiropht/unscroll/server/internal/server"
	"github.com/sepiropht/unscroll/server/internal/store"
)

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func startServer(t *testing.T, legacyHash string, perMinute int) (*httptest.Server, string) {
	t.Helper()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "sync.db")
	st, err := store.Open(dbPath, legacyHash)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	var v *auth.Verifier
	if legacyHash == "" {
		v = auth.Empty()
	} else {
		v, err = auth.New([]string{legacyHash})
		if err != nil {
			t.Fatal(err)
		}
	}
	if perMinute < 1 {
		perMinute = 60
	}
	h := server.New(st, v, ratelimit.New(perMinute))
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return srv, dbPath
}

func testServer(t *testing.T) (*httptest.Server, string) {
	t.Helper()
	token := "test-token-1"
	srv, _ := startServer(t, hashToken(token), 60)
	return srv, token
}

func TestHealthAndCORS(t *testing.T) {
	srv, _ := testServer(t)
	res, err := http.Get(srv.URL + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("status %d", res.StatusCode)
	}
	var body map[string]any
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body["ok"] != true || body["protocol"] != float64(1) {
		t.Fatalf("body %#v", body)
	}
	if res.Header.Get("Access-Control-Allow-Origin") != "*" {
		t.Fatal("missing cors on get")
	}

	req, _ := http.NewRequest(http.MethodOptions, srv.URL+"/v1/blobs/settings", nil)
	ores, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer ores.Body.Close()
	if ores.StatusCode != 204 {
		t.Fatalf("options %d", ores.StatusCode)
	}
	if ores.Header.Get("Access-Control-Allow-Methods") != "GET, PUT, POST, DELETE, OPTIONS" {
		t.Fatal(ores.Header.Get("Access-Control-Allow-Methods"))
	}
	if ores.Header.Get("Access-Control-Allow-Headers") != "authorization, content-type, if-match, if-none-match" {
		t.Fatal(ores.Header.Get("Access-Control-Allow-Headers"))
	}
	if ores.Header.Get("Access-Control-Expose-Headers") != "ETag" {
		t.Fatal("expose")
	}
	if ores.Header.Get("Access-Control-Max-Age") != "86400" {
		t.Fatal("max-age")
	}
	b, _ := io.ReadAll(ores.Body)
	if len(b) != 0 {
		t.Fatalf("options body %q", b)
	}

	req, _ = http.NewRequest(http.MethodOptions, srv.URL+"/v1/accounts", nil)
	ares, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer ares.Body.Close()
	if ares.StatusCode != 204 {
		t.Fatalf("account options %d", ares.StatusCode)
	}
	if ares.Header.Get("Access-Control-Allow-Methods") != "GET, PUT, POST, DELETE, OPTIONS" {
		t.Fatal(ares.Header.Get("Access-Control-Allow-Methods"))
	}
}

func TestBlobRoundTrip(t *testing.T) {
	srv, token := testServer(t)
	payload := []byte(`{"version":1,"alg":"AES-GCM"}`)
	req, _ := http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/settings", bytes.NewReader(payload))
	req.Header.Set("Authorization", "Bearer "+token)
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 412 {
		t.Fatalf("missing precondition got %d", res.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/settings", bytes.NewReader(payload))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("If-None-Match", "*")
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 204 {
		t.Fatalf("create %d", res.StatusCode)
	}
	etag := res.Header.Get("ETag")
	sum := sha256.Sum256(payload)
	want := `"sha256-` + hex.EncodeToString(sum[:]) + `"`
	if etag != want {
		t.Fatalf("etag %s want %s", etag, want)
	}

	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/settings", bytes.NewReader(payload))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("If-None-Match", "*")
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 412 {
		t.Fatalf("recreate %d", res.StatusCode)
	}

	gres, err := http.Get(srv.URL + "/v1/blobs/settings")
	if err != nil {
		t.Fatal(err)
	}
	gres.Body.Close()
	if gres.StatusCode != 401 {
		t.Fatalf("no auth %d", gres.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodGet, srv.URL+"/v1/blobs/settings", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	gres, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer gres.Body.Close()
	if gres.StatusCode != 200 {
		t.Fatalf("get %d", gres.StatusCode)
	}
	if gres.Header.Get("Content-Type") != "application/json" {
		t.Fatal(gres.Header.Get("Content-Type"))
	}
	if gres.Header.Get("ETag") != want {
		t.Fatalf("get etag %s", gres.Header.Get("ETag"))
	}
	got, _ := io.ReadAll(gres.Body)
	if !bytes.Equal(got, payload) {
		t.Fatalf("body %s", got)
	}

	req, _ = http.NewRequest(http.MethodGet, srv.URL+"/v1/blobs", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	lres, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer lres.Body.Close()
	var list struct {
		Blobs []struct {
			ID    string `json:"id"`
			ETag  string `json:"etag"`
			Bytes int    `json:"bytes"`
		} `json:"blobs"`
	}
	if err := json.NewDecoder(lres.Body).Decode(&list); err != nil {
		t.Fatal(err)
	}
	if len(list.Blobs) != 1 || list.Blobs[0].ID != "settings" || list.Blobs[0].Bytes != len(payload) {
		t.Fatalf("list %#v", list)
	}
	if list.Blobs[0].ETag != strings.Trim(want, `"`) {
		t.Fatalf("list etag %s", list.Blobs[0].ETag)
	}

	next := []byte(`{"version":1,"alg":"AES-GCM","n":2}`)
	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/settings", bytes.NewReader(next))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("If-Match", `"sha256-dead"`)
	ures, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	ures.Body.Close()
	if ures.StatusCode != 412 {
		t.Fatalf("bad match %d", ures.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/settings", bytes.NewReader(next))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("If-Match", want)
	ures, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	ures.Body.Close()
	if ures.StatusCode != 204 {
		t.Fatalf("update %d", ures.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/Bad", bytes.NewReader(payload))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("If-None-Match", "*")
	bres, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	bres.Body.Close()
	if bres.StatusCode != 400 {
		t.Fatalf("bad id %d", bres.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodGet, srv.URL+"/v1/blobs/device.android.abcd1234", nil)
	req.Header.Set("Authorization", "Bearer nope-token")
	bres, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	bres.Body.Close()
	if bres.StatusCode != 401 {
		t.Fatalf("bad token %d", bres.StatusCode)
	}

	big := bytes.Repeat([]byte("a"), 2_000_001)
	req, _ = http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/settings", bytes.NewReader(big))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("If-Match", ures.Header.Get("ETag"))
	bres, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	bres.Body.Close()
	if bres.StatusCode != 413 {
		t.Fatalf("large %d", bres.StatusCode)
	}
}

func TestRateLimit(t *testing.T) {
	srv, token := testServer(t)

	var last int
	for i := 0; i < 61; i++ {
		req, _ := http.NewRequest(http.MethodGet, srv.URL+"/v1/blobs", nil)
		req.Header.Set("Authorization", "Bearer "+token)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		last = res.StatusCode
	}
	if last != 429 {
		t.Fatalf("rate %d", last)
	}
}

func TestTokensFile(t *testing.T) {
	dir := t.TempDir()
	token := "file-token-ok"
	sum := sha256.Sum256([]byte(token))
	path := filepath.Join(dir, "tokens")
	if err := os.WriteFile(path, []byte("# comment\n"+hex.EncodeToString(sum[:])+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("UNSCROLL_SYNC_TOKEN_SHA256", "")
	t.Setenv("UNSCROLL_SYNC_TOKENS_FILE", path)
	v, err := auth.FromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if !v.Accept(token) || v.Accept("short") || v.Accept("not-the-token") {
		t.Fatal("accept")
	}
	if v.LegacyHash() != "" {
		t.Fatal("file-only verifier has no legacy hash")
	}
}

func TestFromEnvAllowsEmpty(t *testing.T) {
	t.Setenv("UNSCROLL_SYNC_TOKEN_SHA256", "")
	t.Setenv("UNSCROLL_SYNC_TOKENS_FILE", "")
	v, err := auth.FromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if v.LegacyHash() != "" || v.Accept("test-token-1") {
		t.Fatal("expected empty verifier")
	}
}

func postAccount(t *testing.T, srv *httptest.Server) (token, accountID string) {
	t.Helper()
	req, err := http.NewRequest(http.MethodPost, srv.URL+"/v1/accounts", nil)
	if err != nil {
		t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	if res.StatusCode != http.StatusCreated {
		t.Fatalf("create %d %s", res.StatusCode, raw)
	}
	if res.Header.Get("Content-Type") != "application/json" {
		t.Fatal(res.Header.Get("Content-Type"))
	}
	if res.Header.Get("Access-Control-Allow-Origin") != "*" {
		t.Fatal("missing cors")
	}
	var body struct {
		Token     string `json:"token"`
		Protocol  int    `json:"protocol"`
		AccountID string `json:"accountId"`
	}
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatal(err)
	}
	if body.Protocol != 1 || len(body.Token) < 43 || len(body.AccountID) != 32 {
		t.Fatalf("protocol=%d tokenLen=%d idLen=%d", body.Protocol, len(body.Token), len(body.AccountID))
	}
	for _, r := range body.Token {
		if r < 0x21 || r > 0x7e {
			t.Fatal("token is not printable ASCII")
		}
	}
	return body.Token, body.AccountID
}

func putBlob(t *testing.T, srv *httptest.Server, token, id string, body []byte, ifNone, ifMatch string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodPut, srv.URL+"/v1/blobs/"+id, bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	if ifNone != "" {
		req.Header.Set("If-None-Match", ifNone)
	}
	if ifMatch != "" {
		req.Header.Set("If-Match", ifMatch)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func getBlob(t *testing.T, srv *httptest.Server, token, id string) *http.Response {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, srv.URL+"/v1/blobs/"+id, nil)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func TestCreateAccountIsolate(t *testing.T) {
	srv, _ := startServer(t, "", 1000)
	res := getBlob(t, srv, "no-such-token", "settings")
	res.Body.Close()
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("before account %d", res.StatusCode)
	}

	tokA, _ := postAccount(t, srv)
	tokB, _ := postAccount(t, srv)
	if tokA == tokB {
		t.Fatal("duplicate token")
	}

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/v1/blobs", nil)
	req.Header.Set("Authorization", "Bearer "+tokA)
	lres, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	listBody, _ := io.ReadAll(lres.Body)
	lres.Body.Close()
	if lres.StatusCode != 200 || strings.TrimSpace(string(listBody)) != `{"blobs":[]}` {
		t.Fatalf("empty list %d %s", lres.StatusCode, listBody)
	}

	payload := []byte(`{"version":1,"alg":"AES-GCM"}`)
	res = putBlob(t, srv, tokA, "settings", payload, "*", "")
	if res.StatusCode != http.StatusNoContent {
		b, _ := io.ReadAll(res.Body)
		res.Body.Close()
		t.Fatalf("create %d %s", res.StatusCode, b)
	}
	sum := sha256.Sum256(payload)
	want := `"sha256-` + hex.EncodeToString(sum[:]) + `"`
	if res.Header.Get("ETag") != want {
		res.Body.Close()
		t.Fatalf("etag %s", res.Header.Get("ETag"))
	}
	res.Body.Close()

	res = getBlob(t, srv, tokA, "settings")
	got, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Equal(got, payload) || res.Header.Get("ETag") != want {
		t.Fatalf("get %d %s etag %s", res.StatusCode, got, res.Header.Get("ETag"))
	}

	res = putBlob(t, srv, tokA, "settings", []byte(`{"n":0}`), "", `"sha256-dead"`)
	res.Body.Close()
	if res.StatusCode != http.StatusPreconditionFailed {
		t.Fatalf("bad match %d", res.StatusCode)
	}
	next := []byte(`{"version":1,"n":2}`)
	res = putBlob(t, srv, tokA, "settings", next, "", want)
	newETag := res.Header.Get("ETag")
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent || newETag == "" || newETag == want {
		t.Fatalf("update %d etag %s", res.StatusCode, newETag)
	}

	res = getBlob(t, srv, tokB, "settings")
	res.Body.Close()
	if res.StatusCode != http.StatusNotFound {
		t.Fatalf("cross get %d", res.StatusCode)
	}
	res = putBlob(t, srv, tokB, "settings", []byte(`{"pwn":true}`), "", newETag)
	res.Body.Close()
	if res.StatusCode != http.StatusPreconditionFailed {
		t.Fatalf("cross match %d", res.StatusCode)
	}
	res = getBlob(t, srv, tokA, "settings")
	got, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if !bytes.Equal(got, next) {
		t.Fatalf("victim body %s", got)
	}

	own := []byte(`{"owner":"b"}`)
	res = putBlob(t, srv, tokB, "settings", own, "*", "")
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("b create %d", res.StatusCode)
	}
	res = getBlob(t, srv, tokB, "settings")
	got, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Equal(got, own) {
		t.Fatalf("b body %d %s", res.StatusCode, got)
	}
	res = getBlob(t, srv, tokA, "settings")
	got, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if !bytes.Equal(got, next) {
		t.Fatalf("a body after b %s", got)
	}
}

func TestLegacyEnvTokenAfterMigrate(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "sync.db")
	legacyToken := "legacy-token-value"
	extraToken := "legacy-extra-token"
	body := []byte(`{"legacy":true}`)
	if err := seedOldBlobs(dbPath, map[string][]byte{"settings": body}); err != nil {
		t.Fatal(err)
	}
	if _, err := store.Open(dbPath, ""); err == nil || !strings.Contains(err.Error(), "UNSCROLL_SYNC_TOKEN_SHA256") {
		t.Fatalf("migrate without hash: %v", err)
	}

	extraHash := hashToken(extraToken)
	tokPath := filepath.Join(dir, "tokens")
	if err := os.WriteFile(tokPath, []byte("# comment\n"+extraHash+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("UNSCROLL_SYNC_TOKEN_SHA256", strings.ToUpper(hashToken(legacyToken)))
	t.Setenv("UNSCROLL_SYNC_TOKENS_FILE", tokPath)
	v, err := auth.FromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if v.LegacyHash() != hashToken(legacyToken) {
		t.Fatal("legacy hash not normalized")
	}
	st, err := store.Open(dbPath, v.LegacyHash())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	srv := httptest.NewServer(server.New(st, v, ratelimit.New(1000)))
	t.Cleanup(srv.Close)

	res := getBlob(t, srv, legacyToken, "settings")
	got, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Equal(got, body) {
		t.Fatalf("legacy get %d %s", res.StatusCode, got)
	}
	res = getBlob(t, srv, extraToken, "settings")
	got, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Equal(got, body) {
		t.Fatalf("tokens-file get %d %s", res.StatusCode, got)
	}

	tok, _ := postAccount(t, srv)
	res = getBlob(t, srv, tok, "settings")
	res.Body.Close()
	if res.StatusCode != http.StatusNotFound {
		t.Fatalf("new account saw legacy blob %d", res.StatusCode)
	}
	own := []byte(`{"owner":"new"}`)
	res = putBlob(t, srv, tok, "settings", own, "*", "")
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("new put %d", res.StatusCode)
	}
	res = getBlob(t, srv, legacyToken, "settings")
	got, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if !bytes.Equal(got, body) {
		t.Fatalf("legacy body changed %s", got)
	}
	res = getBlob(t, srv, tok, "settings")
	got, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if !bytes.Equal(got, own) {
		t.Fatalf("new body %s", got)
	}
}

func TestDeleteAccountWipes(t *testing.T) {
	srv, dbPath := startServer(t, "", 1000)
	tokA, _ := postAccount(t, srv)
	tokB, _ := postAccount(t, srv)
	res := putBlob(t, srv, tokA, "settings", []byte(`{"a":1}`), "*", "")
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("a put %d", res.StatusCode)
	}
	res = putBlob(t, srv, tokB, "device.desktop.abcdefgh", []byte(`{"b":1}`), "*", "")
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("b put %d", res.StatusCode)
	}

	req, _ := http.NewRequest(http.MethodDelete, srv.URL+"/v1/accounts", nil)
	dres, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	dres.Body.Close()
	if dres.StatusCode != http.StatusUnauthorized {
		t.Fatalf("delete no auth %d", dres.StatusCode)
	}

	req, _ = http.NewRequest(http.MethodDelete, srv.URL+"/v1/accounts", nil)
	req.Header.Set("Authorization", "Bearer "+tokA)
	dres, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	wiped, _ := io.ReadAll(dres.Body)
	dres.Body.Close()
	if dres.StatusCode != http.StatusNoContent || len(wiped) != 0 {
		t.Fatalf("delete %d %q", dres.StatusCode, wiped)
	}

	res = getBlob(t, srv, tokA, "settings")
	res.Body.Close()
	if res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("a after delete %d", res.StatusCode)
	}
	req, _ = http.NewRequest(http.MethodDelete, srv.URL+"/v1/accounts", nil)
	req.Header.Set("Authorization", "Bearer "+tokA)
	dres, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	dres.Body.Close()
	if dres.StatusCode != http.StatusUnauthorized {
		t.Fatalf("second delete %d", dres.StatusCode)
	}

	res = getBlob(t, srv, tokB, "device.desktop.abcdefgh")
	got, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || !bytes.Equal(got, []byte(`{"b":1}`)) {
		t.Fatalf("b survived %d %s", res.StatusCode, got)
	}
	if n := countQuery(t, dbPath, `SELECT COUNT(*) FROM accounts`); n != 1 {
		t.Fatalf("accounts %d", n)
	}
	if n := countQuery(t, dbPath, `SELECT COUNT(*) FROM blobs`); n != 1 {
		t.Fatalf("blobs %d", n)
	}
}

func TestAccountCreateRateLimit(t *testing.T) {
	t.Run("per hour", func(t *testing.T) {
		srv, _ := startServer(t, "", 60)
		for i := 0; i < server.AccountCreatesPerHour; i++ {
			req, err := http.NewRequest(http.MethodPost, srv.URL+"/v1/accounts", nil)
			if err != nil {
				t.Fatal(err)
			}
			res, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			res.Body.Close()
			if res.StatusCode != http.StatusCreated {
				t.Fatalf("create %d status %d", i, res.StatusCode)
			}
		}
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/v1/accounts", nil)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") != "3600" {
			b, _ := io.ReadAll(res.Body)
			t.Fatalf("hourly %d retry %s %s", res.StatusCode, res.Header.Get("Retry-After"), b)
		}
	})

	t.Run("per minute", func(t *testing.T) {
		srv, _ := startServer(t, "", 2)
		for i := 0; i < 2; i++ {
			req, _ := http.NewRequest(http.MethodPost, srv.URL+"/v1/accounts", nil)
			res, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			res.Body.Close()
			if res.StatusCode != http.StatusCreated {
				t.Fatalf("create %d status %d", i, res.StatusCode)
			}
		}
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/v1/accounts", nil)
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		if res.StatusCode != http.StatusTooManyRequests || res.Header.Get("Retry-After") != "60" {
			t.Fatalf("minute %d retry %s", res.StatusCode, res.Header.Get("Retry-After"))
		}
	})
}

func TestQuotaBlobCount(t *testing.T) {
	srv, _ := startServer(t, "", 1000)
	tok, _ := postAccount(t, srv)
	for i := 0; i < store.MaxBlobsPerAccount; i++ {
		res := putBlob(t, srv, tok, fmt.Sprintf("b%d", i), []byte("x"), "*", "")
		res.Body.Close()
		if res.StatusCode != http.StatusNoContent {
			t.Fatalf("blob %d: %d", i, res.StatusCode)
		}
	}
	res := putBlob(t, srv, tok, "overflow", []byte("x"), "*", "")
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusRequestEntityTooLarge || !strings.Contains(string(b), "quota") {
		t.Fatalf("count quota %d %s", res.StatusCode, b)
	}
	etag := `"` + store.ETagValue([]byte("x")) + `"`
	res = putBlob(t, srv, tok, "b0", []byte("y"), "", etag)
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("replace at cap %d", res.StatusCode)
	}
}

func TestQuotaTotalBytes(t *testing.T) {
	srv, _ := startServer(t, "", 1000)
	tok, _ := postAccount(t, srv)
	n := int(store.MaxBytesPerAccount) / 2_000_000
	chunk := bytes.Repeat([]byte("a"), 2_000_000)
	for i := 0; i < n; i++ {
		res := putBlob(t, srv, tok, fmt.Sprintf("p%d", i), chunk, "*", "")
		res.Body.Close()
		if res.StatusCode != http.StatusNoContent {
			t.Fatalf("blob %d: %d", i, res.StatusCode)
		}
	}
	res := putBlob(t, srv, tok, "extra", []byte("a"), "*", "")
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusRequestEntityTooLarge || !strings.Contains(string(b), "quota") {
		t.Fatalf("byte quota %d %s", res.StatusCode, b)
	}
	tok2, _ := postAccount(t, srv)
	res = putBlob(t, srv, tok2, "settings", []byte("ok"), "*", "")
	res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		t.Fatalf("other account %d", res.StatusCode)
	}
}

func countQuery(t *testing.T, path, query string) int {
	t.Helper()
	db, err := sql.Open("sqlite", "file:"+path+"?mode=ro&_pragma=busy_timeout(5000)")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	var n int
	if err := db.QueryRow(query).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func seedOldBlobs(path string, blobs map[string][]byte) error {
	db, err := sql.Open("sqlite", "file:"+path)
	if err != nil {
		return err
	}
	defer db.Close()
	if _, err := db.Exec(`CREATE TABLE blobs (
  id TEXT PRIMARY KEY,
  body BLOB NOT NULL,
  etag TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  updated_at TEXT NOT NULL
)`); err != nil {
		return err
	}
	for id, body := range blobs {
		if _, err := db.Exec(
			`INSERT INTO blobs(id, body, etag, bytes, updated_at) VALUES(?,?,?,?,?)`,
			id, body, store.ETagValue(body), len(body), "2026-11-05T11:00:00Z",
		); err != nil {
			return err
		}
	}
	return nil
}
