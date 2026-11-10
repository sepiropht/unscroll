// Package billing implements the optional paid hosted sync plan.
//
// Billing is disabled unless STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET are
// both set. Self-hosted servers leave them unset and sync stays free and
// unlimited. Only the anonymous account id is ever sent to Stripe: no usage
// data, no blob content, no device names.
package billing

import (
	"errors"
	"fmt"
	"math"
	"net/http"
	"os"
	"strings"
	"time"
)

const (
	DefaultTrialDays     = 14
	DefaultGraceDays     = 30
	DefaultMonthlyLookup = "unscroll_sync_monthly"
	DefaultYearlyLookup  = "unscroll_sync_yearly"
	DefaultAPIBase       = "https://api.stripe.com"

	// Plan states reported to clients.
	StateTrial   = "trial"
	StateActive  = "active"
	StatePastDue = "past_due"
	StateEnded   = "ended"
	StateFree    = "free" // accounts exempt from billing (legacy vault)
)

// Config holds billing settings. Zero SecretKey or WebhookSecret means disabled.
type Config struct {
	SecretKey     string
	WebhookSecret string
	PublicURL     string // e.g. https://sync.example.com, used for Checkout return URLs
	MonthlyLookup string
	YearlyLookup  string
	TrialDays     int
	GraceDays     int
	AutomaticTax  bool
	APIBase       string
	HTTPClient    *http.Client
}

// ConfigFromEnv reads the billing env. ok is false when billing is disabled.
func ConfigFromEnv() (cfg Config, ok bool, err error) {
	cfg = Config{
		SecretKey:     strings.TrimSpace(os.Getenv("STRIPE_SECRET_KEY")),
		WebhookSecret: strings.TrimSpace(os.Getenv("STRIPE_WEBHOOK_SECRET")),
		PublicURL:     strings.TrimRight(strings.TrimSpace(os.Getenv("UNSCROLL_PUBLIC_URL")), "/"),
		MonthlyLookup: envOr("STRIPE_PRICE_LOOKUP_MONTHLY", DefaultMonthlyLookup),
		YearlyLookup:  envOr("STRIPE_PRICE_LOOKUP_YEARLY", DefaultYearlyLookup),
		TrialDays:     DefaultTrialDays,
		GraceDays:     DefaultGraceDays,
		AutomaticTax:  os.Getenv("STRIPE_AUTOMATIC_TAX") == "1",
	}
	if cfg.SecretKey == "" && cfg.WebhookSecret == "" {
		return cfg, false, nil
	}
	if cfg.SecretKey == "" || cfg.WebhookSecret == "" {
		return cfg, false, errors.New("billing needs both STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET (or neither)")
	}
	if cfg.PublicURL == "" {
		return cfg, false, errors.New("billing needs UNSCROLL_PUBLIC_URL (https origin of this server, for Checkout return URLs)")
	}
	if v := os.Getenv("UNSCROLL_TRIAL_DAYS"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &cfg.TrialDays); err != nil || cfg.TrialDays < 0 {
			return cfg, false, errors.New("UNSCROLL_TRIAL_DAYS: want a non-negative integer")
		}
	}
	if v := os.Getenv("UNSCROLL_GRACE_DAYS"); v != "" {
		if _, err := fmt.Sscanf(v, "%d", &cfg.GraceDays); err != nil || cfg.GraceDays < 1 {
			return cfg, false, errors.New("UNSCROLL_GRACE_DAYS: want a positive integer")
		}
	}
	return cfg, true, nil
}

func envOr(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

// Record is the subset of stored billing state that Evaluate needs.
type Record struct {
	Legacy            bool
	TrialStartedAt    time.Time
	CustomerID        string
	SubscriptionID    string
	Status            string
	PeriodEnd         time.Time
	CancelAtPeriodEnd bool
}

// Plan is what clients see from GET /v1/billing.
type Plan struct {
	Enabled           bool       `json:"enabled"`
	State             string     `json:"state,omitempty"`
	TrialDays         int        `json:"trialDays,omitempty"`
	TrialStarted      bool       `json:"trialStarted,omitempty"`
	TrialEndsAt       *time.Time `json:"trialEndsAt,omitempty"`
	DaysLeft          int        `json:"daysLeft"`
	CurrentPeriodEnd  *time.Time `json:"currentPeriodEnd,omitempty"`
	CancelAtPeriodEnd bool       `json:"cancelAtPeriodEnd,omitempty"`
	CanManage         bool       `json:"canManage,omitempty"`
	DataKeptUntil     *time.Time `json:"dataKeptUntil,omitempty"`
	Plans             []string   `json:"plans,omitempty"`
}

// Allowed reports whether sync requests may proceed in this state.
func (p Plan) Allowed() bool {
	return !p.Enabled || p.State != StateEnded
}

func subscriptionLive(status string) bool {
	switch status {
	case "active", "trialing":
		return true
	}
	return false
}

// Evaluate derives the plan state from a record at time now.
// A trial that has not started yet (no sync request so far) reports the
// full trial length.
func Evaluate(r Record, now time.Time, trialDays, graceDays int) Plan {
	p := Plan{Enabled: true, TrialDays: trialDays, Plans: []string{"monthly", "yearly"}}
	p.CanManage = r.CustomerID != ""
	if r.Legacy {
		p.State = StateFree
		p.Plans = nil
		return p
	}
	trialLen := time.Duration(trialDays) * 24 * time.Hour
	start := r.TrialStartedAt
	if start.IsZero() {
		start = now
	} else {
		p.TrialStarted = true
	}
	trialEnd := start.Add(trialLen).UTC()
	p.TrialEndsAt = &trialEnd
	if !r.PeriodEnd.IsZero() {
		pe := r.PeriodEnd.UTC()
		p.CurrentPeriodEnd = &pe
	}
	p.CancelAtPeriodEnd = r.CancelAtPeriodEnd

	switch {
	case subscriptionLive(r.Status):
		p.State = StateActive
		return p
	case r.Status == "past_due":
		// Stripe is retrying the payment. Keep sync working meanwhile.
		p.State = StatePastDue
		return p
	}
	if now.Before(trialEnd) {
		p.State = StateTrial
		p.DaysLeft = int(math.Ceil(trialEnd.Sub(now).Hours() / 24))
		return p
	}
	p.State = StateEnded
	accessEnd := trialEnd
	if !r.PeriodEnd.IsZero() && r.PeriodEnd.After(accessEnd) {
		accessEnd = r.PeriodEnd
	}
	kept := accessEnd.Add(time.Duration(graceDays) * 24 * time.Hour).UTC()
	p.DataKeptUntil = &kept
	return p
}

// PurgeDue reports whether the server copy of the ciphertext may be deleted.
// It is never due while sync is allowed, and only after the grace period.
func PurgeDue(p Plan, now time.Time) bool {
	return p.Enabled && p.State == StateEnded && p.DataKeptUntil != nil && now.After(*p.DataKeptUntil)
}
