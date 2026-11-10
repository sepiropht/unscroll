package billing

import (
	"errors"
	"strings"
	"testing"
	"time"
)

var t0 = time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)

func TestEvaluateTrial(t *testing.T) {
	// Not started: full length, sync allowed.
	p := Evaluate(Record{}, t0, 14, 30)
	if p.State != StateTrial || p.DaysLeft != 14 || p.TrialStarted || !p.Allowed() {
		t.Fatalf("fresh %+v", p)
	}
	r := Record{TrialStartedAt: t0}
	p = Evaluate(r, t0.Add(36*time.Hour), 14, 30)
	if p.State != StateTrial || p.DaysLeft != 13 || !p.TrialStarted {
		t.Fatalf("day 2 %+v", p)
	}
	p = Evaluate(r, t0.Add(14*24*time.Hour-time.Minute), 14, 30)
	if p.State != StateTrial || p.DaysLeft != 1 {
		t.Fatalf("last minute %+v", p)
	}
	p = Evaluate(r, t0.Add(14*24*time.Hour), 14, 30)
	if p.State != StateEnded || p.Allowed() || p.DaysLeft != 0 {
		t.Fatalf("ended %+v", p)
	}
	wantKept := t0.Add(44 * 24 * time.Hour)
	if p.DataKeptUntil == nil || !p.DataKeptUntil.Equal(wantKept) {
		t.Fatalf("kept %v want %v", p.DataKeptUntil, wantKept)
	}
	if PurgeDue(p, wantKept) || !PurgeDue(p, wantKept.Add(time.Second)) {
		t.Fatal("purge boundary")
	}
}

func TestEvaluateSubscription(t *testing.T) {
	old := t0.Add(-60 * 24 * time.Hour)
	for _, st := range []string{"active", "trialing"} {
		p := Evaluate(Record{TrialStartedAt: old, Status: st, CustomerID: "cus_1"}, t0, 14, 30)
		if p.State != StateActive || !p.Allowed() || !p.CanManage {
			t.Fatalf("%s %+v", st, p)
		}
	}
	p := Evaluate(Record{TrialStartedAt: old, Status: "past_due"}, t0, 14, 30)
	if p.State != StatePastDue || !p.Allowed() {
		t.Fatalf("past_due %+v", p)
	}
	// Canceled: data kept 30 days after the paid period, not after the trial.
	pe := t0.Add(-24 * time.Hour)
	p = Evaluate(Record{TrialStartedAt: old, Status: "canceled", PeriodEnd: pe}, t0, 14, 30)
	if p.State != StateEnded || !p.DataKeptUntil.Equal(pe.Add(30*24*time.Hour)) {
		t.Fatalf("canceled %+v", p)
	}
	// Canceled while still in the server trial keeps the trial.
	p = Evaluate(Record{TrialStartedAt: t0, Status: "canceled"}, t0.Add(time.Hour), 14, 30)
	if p.State != StateTrial {
		t.Fatalf("canceled in trial %+v", p)
	}
	for _, st := range []string{"unpaid", "incomplete", "incomplete_expired", "paused"} {
		p := Evaluate(Record{TrialStartedAt: old, Status: st}, t0, 14, 30)
		if p.State != StateEnded {
			t.Fatalf("%s %+v", st, p)
		}
	}
	p = Evaluate(Record{Legacy: true}, t0, 14, 30)
	if p.State != StateFree || !p.Allowed() {
		t.Fatalf("legacy %+v", p)
	}
}

func TestVerifySignature(t *testing.T) {
	payload := []byte(`{"id":"evt_1","type":"ping"}`)
	secret := "whsec_test_" + strings.Repeat("x", 8)
	h := Sign(payload, secret, t0)
	if err := VerifySignature(payload, h, secret, t0.Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	// Stripe may send several v1 signatures during secret rotation.
	multi := strings.Replace(h, "v1=", "v1="+strings.Repeat("0", 64)+",v1=", 1)
	if err := VerifySignature(payload, multi, secret, t0); err != nil {
		t.Fatalf("multi: %v", err)
	}
	cases := []struct {
		name   string
		body   []byte
		header string
		secret string
		now    time.Time
		want   error
	}{
		{"missing", payload, "", secret, t0, ErrNoSignature},
		{"no v1", payload, "t=1", secret, t0, ErrNoSignature},
		{"tampered", []byte(`{"id":"evt_2"}`), h, secret, t0, ErrBadSignature},
		{"wrong secret", payload, h, "whsec_other", t0, ErrBadSignature},
		{"stale", payload, h, secret, t0.Add(6 * time.Minute), ErrStale},
		{"future", payload, h, secret, t0.Add(-6 * time.Minute), ErrStale},
	}
	for _, c := range cases {
		if err := VerifySignature(c.body, c.header, c.secret, c.now); !errors.Is(err, c.want) {
			t.Errorf("%s: got %v want %v", c.name, err, c.want)
		}
	}
}

func TestSubscriptionPeriodEndShapes(t *testing.T) {
	var s subscription
	s.CurrentPeriodEnd = 100
	if s.periodEnd().Unix() != 100 {
		t.Fatal("top level")
	}
	s = subscription{}
	s.Items.Data = append(s.Items.Data, struct {
		CurrentPeriodEnd int64 `json:"current_period_end"`
	}{200})
	if s.periodEnd().Unix() != 200 {
		t.Fatal("item level")
	}
}

func TestConfigFromEnv(t *testing.T) {
	t.Setenv("STRIPE_SECRET_KEY", "")
	t.Setenv("STRIPE_WEBHOOK_SECRET", "")
	if _, on, err := ConfigFromEnv(); on || err != nil {
		t.Fatalf("unset: on=%v err=%v", on, err)
	}
	t.Setenv("STRIPE_SECRET_KEY", "sk_test_dummy")
	if _, on, err := ConfigFromEnv(); on || err == nil {
		t.Fatal("half config must fail")
	}
	t.Setenv("STRIPE_WEBHOOK_SECRET", "whsec_dummy")
	t.Setenv("UNSCROLL_PUBLIC_URL", "")
	if _, _, err := ConfigFromEnv(); err == nil {
		t.Fatal("public url required")
	}
	t.Setenv("UNSCROLL_PUBLIC_URL", "https://sync.example.com/")
	cfg, on, err := ConfigFromEnv()
	if !on || err != nil || cfg.PublicURL != "https://sync.example.com" || cfg.TrialDays != 14 || cfg.GraceDays != 30 ||
		cfg.MonthlyLookup != "unscroll_sync_monthly" || cfg.YearlyLookup != "unscroll_sync_yearly" || cfg.AutomaticTax {
		t.Fatalf("cfg %+v on=%v err=%v", cfg, on, err)
	}
}
