package server_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/sepiropht/unscroll/server/internal/auth"
	"github.com/sepiropht/unscroll/server/internal/billing"
	"github.com/sepiropht/unscroll/server/internal/ratelimit"
	"github.com/sepiropht/unscroll/server/internal/server"
	"github.com/sepiropht/unscroll/server/internal/store"
)

const testWebhookSecret = "whsec_unit_test_only"

// fakeStripe records form posts and answers like the Stripe API.
type fakeStripe struct {
	mu       sync.Mutex
	checkout []url.Values
	portal   []url.Values
	priceQ   []url.Values
}

func (f *fakeStripe) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer sk_test_") {
		w.WriteHeader(401)
		return
	}
	_ = r.ParseForm()
	w.Header().Set("Content-Type", "application/json")
	switch r.URL.Path {
	case "/v1/prices":
		f.priceQ = append(f.priceQ, r.URL.Query())
		io.WriteString(w, `{"object":"list","data":[
{"id":"price_month_fixture","object":"price","lookup_key":"unscroll_sync_monthly","unit_amount":299,"currency":"eur"},
{"id":"price_year_fixture","object":"price","lookup_key":"unscroll_sync_yearly","unit_amount":2400,"currency":"eur"}]}`)
	case "/v1/checkout/sessions":
		f.checkout = append(f.checkout, r.PostForm)
		io.WriteString(w, `{"id":"cs_test_fixture","object":"checkout.session","url":"https://checkout.stripe.com/c/pay/cs_test_fixture"}`)
	case "/v1/billing_portal/sessions":
		f.portal = append(f.portal, r.PostForm)
		io.WriteString(w, `{"id":"bps_fixture","object":"billing_portal.session","url":"https://billing.stripe.com/p/session/fixture"}`)
	default:
		w.WriteHeader(404)
	}
}

type billingEnv struct {
	srv    *httptest.Server
	svc    *billing.Service
	stripe *fakeStripe
	st     *store.Store
	now    time.Time
	mu     sync.Mutex
}

func (e *billingEnv) advance(d time.Duration) {
	e.mu.Lock()
	e.now = e.now.Add(d)
	e.mu.Unlock()
}

func (e *billingEnv) clock() time.Time {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.now
}

func startBilling(t *testing.T, legacyHash string) *billingEnv {
	t.Helper()
	st, err := store.Open(filepath.Join(t.TempDir(), "sync.db"), legacyHash)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	v := auth.Empty()
	if legacyHash != "" {
		v, _ = auth.New([]string{legacyHash})
	}
	fs := &fakeStripe{}
	api := httptest.NewServer(fs)
	t.Cleanup(api.Close)
	env := &billingEnv{stripe: fs, st: st, now: time.Now().UTC().Truncate(time.Second)}
	svc := billing.New(billing.Config{
		SecretKey:     "sk_test_unit",
		WebhookSecret: testWebhookSecret,
		PublicURL:     "https://sync.example.test",
		APIBase:       api.URL,
	}, st)
	svc.Now = env.clock
	h := server.New(st, v, ratelimit.New(1000))
	h.SetBilling(svc)
	env.svc = svc
	env.srv = httptest.NewServer(h)
	t.Cleanup(env.srv.Close)
	return env
}

func newAccount(t *testing.T, base string) (token, id string) {
	t.Helper()
	res, err := http.Post(base+"/v1/accounts", "", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var out struct {
		Token     string `json:"token"`
		AccountID string `json:"accountId"`
	}
	if res.StatusCode != 201 {
		t.Fatalf("create %d", res.StatusCode)
	}
	json.NewDecoder(res.Body).Decode(&out)
	return out.Token, out.AccountID
}

func call(t *testing.T, method, u, token string, body []byte, hdr map[string]string) (*http.Response, []byte) {
	t.Helper()
	req, _ := http.NewRequest(method, u, bytes.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res, b
}

func putSettings(t *testing.T, base, token string) int {
	t.Helper()
	res, _ := call(t, "GET", base+"/v1/blobs/settings", token, nil, nil)
	hdr := map[string]string{"If-None-Match": "*"}
	if res.StatusCode == 200 {
		hdr = map[string]string{"If-Match": res.Header.Get("ETag")}
	} else if res.StatusCode != 404 {
		return res.StatusCode
	}
	res, _ = call(t, "PUT", base+"/v1/blobs/settings", token, []byte(fmt.Sprintf(`{"v":1,"n":%d}`, time.Now().UnixNano())), hdr)
	return res.StatusCode
}

func plan(t *testing.T, base, token string) billing.Plan {
	t.Helper()
	res, b := call(t, "GET", base+"/v1/billing", token, nil, nil)
	if res.StatusCode != 200 {
		t.Fatalf("billing status %d %s", res.StatusCode, b)
	}
	var p billing.Plan
	if err := json.Unmarshal(b, &p); err != nil {
		t.Fatal(err)
	}
	return p
}

func (e *billingEnv) webhook(t *testing.T, payload string) int {
	t.Helper()
	sig := billing.Sign([]byte(payload), testWebhookSecret, e.clock())
	res, _ := call(t, "POST", e.srv.URL+"/v1/billing/webhook", "", []byte(payload), map[string]string{"Stripe-Signature": sig})
	return res.StatusCode
}

func subEvent(evtID, typ string, created int64, acct, subID, status string, periodEnd int64) string {
	return fmt.Sprintf(`{"id":%q,"object":"event","api_version":"2025-03-31.basil","created":%d,"type":%q,"livemode":false,
"data":{"object":{"id":%q,"object":"subscription","customer":"cus_fixture","status":%q,"cancel_at_period_end":false,
"metadata":{"account_id":%q},"items":{"object":"list","data":[{"id":"si_fixture","object":"subscription_item","current_period_end":%d}]}}}}`,
		evtID, created, typ, subID, status, acct, periodEnd)
}

func TestBillingDisabledByDefault(t *testing.T) {
	srv, _ := startServer(t, "", 60)
	res, b := call(t, "GET", srv.URL+"/healthz", "", nil, nil)
	if res.StatusCode != 200 || !strings.Contains(string(b), `"billing":false`) {
		t.Fatalf("healthz %s", b)
	}
	res, b = call(t, "GET", srv.URL+"/v1/billing", "", nil, nil)
	if res.StatusCode != 200 || strings.TrimSpace(string(b)) != `{"enabled":false,"daysLeft":0}` {
		t.Fatalf("billing %d %s", res.StatusCode, b)
	}
	tok, _ := newAccount(t, srv.URL)
	if c := putSettings(t, srv.URL, tok); c != 204 {
		t.Fatalf("put %d", c)
	}
	res, _ = call(t, "POST", srv.URL+"/v1/billing/webhook", "", []byte(`{}`), nil)
	if res.StatusCode != 404 {
		t.Fatalf("webhook when disabled %d", res.StatusCode)
	}
}

func TestTrialStartsOnFirstSyncAndEndsWith402(t *testing.T) {
	e := startBilling(t, "")
	base := e.srv.URL
	res, b := call(t, "GET", base+"/healthz", "", nil, nil)
	if !strings.Contains(string(b), `"billing":true`) {
		t.Fatalf("healthz %s", b)
	}
	tok, _ := newAccount(t, base)
	p := plan(t, base, tok)
	if !p.Enabled || p.State != "trial" || p.TrialStarted || p.DaysLeft != 14 {
		t.Fatalf("before use %+v", p)
	}
	// Reading the plan does not start the clock.
	e.advance(5 * 24 * time.Hour)
	if p = plan(t, base, tok); p.TrialStarted || p.DaysLeft != 14 {
		t.Fatalf("plan read started trial %+v", p)
	}
	if c := putSettings(t, base, tok); c != 204 {
		t.Fatalf("first put %d", c)
	}
	if p = plan(t, base, tok); !p.TrialStarted || p.DaysLeft != 14 {
		t.Fatalf("after use %+v", p)
	}
	e.advance(13 * 24 * time.Hour)
	if p = plan(t, base, tok); p.State != "trial" || p.DaysLeft != 1 {
		t.Fatalf("day 13 %+v", p)
	}
	if c := putSettings(t, base, tok); c != 204 {
		t.Fatalf("day 13 put %d", c)
	}
	e.advance(24 * time.Hour)
	res, b = call(t, "GET", base+"/v1/blobs", tok, nil, nil)
	if res.StatusCode != http.StatusPaymentRequired {
		t.Fatalf("after trial %d", res.StatusCode)
	}
	var body struct {
		Error   string       `json:"error"`
		Message string       `json:"message"`
		Billing billing.Plan `json:"billing"`
	}
	json.Unmarshal(b, &body)
	if body.Error != "subscription_required" || body.Message != "Trial ended - subscribe or self-host" ||
		body.Billing.State != "ended" || body.Billing.DataKeptUntil == nil {
		t.Fatalf("402 body %s", b)
	}
	if c := putSettings(t, base, tok); c != 402 {
		t.Fatalf("put after trial %d", c)
	}
	// Plan, checkout and account deletion stay reachable.
	if p = plan(t, base, tok); p.State != "ended" {
		t.Fatalf("plan %+v", p)
	}
	// Server copy is kept during the grace period, then purged; the account stays.
	e.advance(29 * 24 * time.Hour)
	if n, err := e.svc.Purge(); err != nil || n != 0 {
		t.Fatalf("purge in grace n=%d err=%v", n, err)
	}
	e.advance(2 * 24 * time.Hour)
	if n, err := e.svc.Purge(); err != nil || n != 1 {
		t.Fatalf("purge after grace n=%d err=%v", n, err)
	}
	res, _ = call(t, "DELETE", base+"/v1/accounts", tok, nil, nil)
	if res.StatusCode != 204 {
		t.Fatalf("delete %d", res.StatusCode)
	}
}

func TestCheckoutParamsAndPrivacy(t *testing.T) {
	e := startBilling(t, "")
	base := e.srv.URL
	tok, acct := newAccount(t, base)
	if c := putSettings(t, base, tok); c != 204 {
		t.Fatal(c)
	}
	res, b := call(t, "POST", base+"/v1/billing/checkout", tok, []byte(`{"plan":"yearly"}`), nil)
	if res.StatusCode != 200 || !strings.Contains(string(b), "checkout.stripe.com") {
		t.Fatalf("checkout %d %s", res.StatusCode, b)
	}
	if len(e.stripe.priceQ) != 1 || len(e.stripe.priceQ[0]["lookup_keys[]"]) != 2 {
		t.Fatalf("price lookup %v", e.stripe.priceQ)
	}
	f := e.stripe.checkout[0]
	want := map[string]string{
		"mode":                                    "subscription",
		"line_items[0][price]":                    "price_year_fixture",
		"line_items[0][quantity]":                 "1",
		"client_reference_id":                     acct,
		"metadata[account_id]":                    acct,
		"subscription_data[metadata][account_id]": acct,
		"allow_promotion_codes":                   "true",
		"automatic_tax[enabled]":                  "false",
		"success_url":                             "https://sync.example.test/v1/billing/return?status=success",
		"cancel_url":                              "https://sync.example.test/v1/billing/return?status=cancel",
	}
	for k, v := range want {
		if f.Get(k) != v {
			t.Errorf("%s = %q want %q", k, f.Get(k), v)
		}
	}
	if f.Get("subscription_data[trial_end]") == "" {
		t.Error("remaining trial days should carry over to Stripe")
	}
	// Only billing fields leave the server: no blob, device, or usage data.
	for k := range f {
		if !(strings.HasPrefix(k, "line_items") || strings.HasPrefix(k, "metadata[account_id]") ||
			strings.HasPrefix(k, "subscription_data[") || strings.HasPrefix(k, "automatic_tax") ||
			k == "mode" || k == "client_reference_id" || k == "allow_promotion_codes" || k == "success_url" || k == "cancel_url" || k == "customer") {
			t.Errorf("unexpected field sent to Stripe: %s", k)
		}
	}
	res, _ = call(t, "POST", base+"/v1/billing/checkout", tok, []byte(`{"plan":"weekly"}`), nil)
	if res.StatusCode != 400 {
		t.Fatalf("bad plan %d", res.StatusCode)
	}
	// Near the end of the trial no Stripe trial is requested (Stripe needs 48h).
	e.advance(13 * 24 * time.Hour)
	call(t, "POST", base+"/v1/billing/checkout", tok, nil, nil)
	if last := e.stripe.checkout[len(e.stripe.checkout)-1]; last.Get("subscription_data[trial_end]") != "" || last.Get("line_items[0][price]") != "price_month_fixture" {
		t.Fatalf("late checkout %v", last)
	}
	// Portal needs a Stripe customer.
	res, _ = call(t, "POST", base+"/v1/billing/portal", tok, nil, nil)
	if res.StatusCode != 404 {
		t.Fatalf("portal without customer %d", res.StatusCode)
	}
	res, _ = call(t, "POST", base+"/v1/billing/checkout", "", nil, nil)
	if res.StatusCode != 401 {
		t.Fatalf("checkout without auth %d", res.StatusCode)
	}
}

func TestWebhookLifecycle(t *testing.T) {
	e := startBilling(t, "")
	base := e.srv.URL
	tok, acct := newAccount(t, base)
	putSettings(t, base, tok)
	e.advance(15 * 24 * time.Hour)
	if c := putSettings(t, base, tok); c != 402 {
		t.Fatalf("ended %d", c)
	}
	now := e.clock().Unix()

	// Signature checks.
	completed := fmt.Sprintf(`{"id":"evt_cs_1","object":"event","created":%d,"type":"checkout.session.completed","livemode":false,
"data":{"object":{"id":"cs_test_fixture","object":"checkout.session","mode":"subscription","status":"complete","payment_status":"paid",
"client_reference_id":%q,"customer":"cus_fixture","subscription":"sub_fixture","metadata":{"account_id":%q}}}}`, now, acct, acct)
	res, _ := call(t, "POST", base+"/v1/billing/webhook", "", []byte(completed), map[string]string{"Stripe-Signature": "t=1,v1=00"})
	if res.StatusCode != 400 {
		t.Fatalf("bad sig %d", res.StatusCode)
	}
	res, _ = call(t, "POST", base+"/v1/billing/webhook", "", []byte(completed), nil)
	if res.StatusCode != 400 {
		t.Fatalf("no sig %d", res.StatusCode)
	}
	if c := putSettings(t, base, tok); c != 402 {
		t.Fatalf("unsigned event must not unlock %d", c)
	}

	if c := e.webhook(t, completed); c != 200 {
		t.Fatalf("completed %d", c)
	}
	if c := e.webhook(t, completed); c != 200 {
		t.Fatalf("duplicate %d", c)
	}
	if c := putSettings(t, base, tok); c != 204 {
		t.Fatalf("after checkout %d", c)
	}
	p := plan(t, base, tok)
	if p.State != "active" || !p.CanManage {
		t.Fatalf("plan %+v", p)
	}
	res, b := call(t, "POST", base+"/v1/billing/portal", tok, nil, nil)
	if res.StatusCode != 200 || !strings.Contains(string(b), "billing.stripe.com") || e.stripe.portal[0].Get("customer") != "cus_fixture" {
		t.Fatalf("portal %d %s", res.StatusCode, b)
	}
	res, _ = call(t, "POST", base+"/v1/billing/checkout", tok, nil, nil)
	if res.StatusCode != 409 {
		t.Fatalf("checkout while active %d", res.StatusCode)
	}

	pe := now + 30*86400
	if c := e.webhook(t, subEvent("evt_sub_1", "customer.subscription.created", now+1, acct, "sub_fixture", "active", pe)); c != 200 {
		t.Fatal(c)
	}
	if p = plan(t, base, tok); p.CurrentPeriodEnd == nil || p.CurrentPeriodEnd.Unix() != pe {
		t.Fatalf("period end %+v", p)
	}

	// Payment failure keeps sync during Stripe retries.
	inv := fmt.Sprintf(`{"id":"evt_inv_1","object":"event","created":%d,"type":"invoice.payment_failed",
"data":{"object":{"id":"in_fixture","object":"invoice","customer":"cus_fixture",
"parent":{"type":"subscription_details","subscription_details":{"subscription":"sub_fixture"}}}}}`, now+2)
	if c := e.webhook(t, inv); c != 200 {
		t.Fatal(c)
	}
	if p = plan(t, base, tok); p.State != "past_due" {
		t.Fatalf("after failed invoice %+v", p)
	}
	if c := putSettings(t, base, tok); c != 204 {
		t.Fatalf("past_due put %d", c)
	}

	// An older event delivered late must not override newer state.
	if c := e.webhook(t, subEvent("evt_sub_old", "customer.subscription.updated", now-100, acct, "sub_fixture", "incomplete", pe)); c != 200 {
		t.Fatal(c)
	}
	if p = plan(t, base, tok); p.State != "past_due" {
		t.Fatalf("stale event applied %+v", p)
	}

	if c := e.webhook(t, subEvent("evt_sub_2", "customer.subscription.updated", now+3, acct, "sub_fixture", "active", pe)); c != 200 {
		t.Fatal(c)
	}
	if p = plan(t, base, tok); p.State != "active" {
		t.Fatalf("recovered %+v", p)
	}

	// Cancel: access ends, server copy kept for the grace period.
	if c := e.webhook(t, subEvent("evt_sub_3", "customer.subscription.deleted", now+4, acct, "sub_fixture", "canceled", now+5)); c != 200 {
		t.Fatal(c)
	}
	if c := putSettings(t, base, tok); c != 402 {
		t.Fatalf("after cancel %d", c)
	}
	if n, _ := e.svc.Purge(); n != 0 {
		t.Fatal("purged during grace")
	}
	res, _ = call(t, "GET", base+"/v1/billing", tok, nil, nil)
	if res.StatusCode != 200 {
		t.Fatal(res.StatusCode)
	}
}

func TestWebhookSubscriptionBeforeCheckout(t *testing.T) {
	// Stripe does not guarantee order: the subscription event may come first.
	e := startBilling(t, "")
	tok, acct := newAccount(t, e.srv.URL)
	putSettings(t, e.srv.URL, tok)
	e.advance(20 * 24 * time.Hour)
	now := e.clock().Unix()
	if c := e.webhook(t, subEvent("evt_a", "customer.subscription.created", now, acct, "sub_x", "active", now+86400)); c != 200 {
		t.Fatal(c)
	}
	completed := fmt.Sprintf(`{"id":"evt_b","created":%d,"type":"checkout.session.completed",
"data":{"object":{"mode":"subscription","status":"complete","payment_status":"unpaid","client_reference_id":%q,"customer":"cus_fixture","subscription":"sub_x"}}}`, now+1, acct)
	if c := e.webhook(t, completed); c != 200 {
		t.Fatal(c)
	}
	if p := plan(t, e.srv.URL, tok); p.State != "active" {
		t.Fatalf("checkout overrode subscription status %+v", p)
	}
	// Unknown account and unhandled types are acknowledged, not retried.
	if c := e.webhook(t, subEvent("evt_c", "customer.subscription.updated", now+2, "nope", "sub_unknown", "active", 0)); c != 200 {
		t.Fatal(c)
	}
	if c := e.webhook(t, fmt.Sprintf(`{"id":"evt_d","created":%d,"type":"customer.created","data":{"object":{}}}`, now)); c != 200 {
		t.Fatal(c)
	}
}

func TestLegacyAccountIsExempt(t *testing.T) {
	token := "legacy-token-1"
	e := startBilling(t, hashToken(token))
	if c := putSettings(t, e.srv.URL, token); c != 204 {
		t.Fatal(c)
	}
	e.advance(400 * 24 * time.Hour)
	if c := putSettings(t, e.srv.URL, token); c != 204 {
		t.Fatalf("legacy after a year %d", c)
	}
	if p := plan(t, e.srv.URL, token); p.State != "free" {
		t.Fatalf("legacy plan %+v", p)
	}
}
