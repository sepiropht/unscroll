package billing

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"time"

	"github.com/sepiropht/unscroll/server/internal/store"
)

var (
	ErrNoCustomer    = errors.New("no Stripe customer for this account")
	ErrAlreadyActive = errors.New("subscription already active")
	ErrUnknownPlan   = errors.New("unknown plan")
	ErrExempt        = errors.New("account is exempt from billing")
)

// Service ties billing state in the store to Stripe.
type Service struct {
	cfg    Config
	store  *store.Store
	stripe *stripeClient
	// Now is the clock used for trial and grace decisions.
	Now func() time.Time
}

// New returns a billing service. cfg must have both Stripe secrets.
func New(cfg Config, st *store.Store) *Service {
	if cfg.TrialDays == 0 {
		cfg.TrialDays = DefaultTrialDays
	}
	if cfg.GraceDays == 0 {
		cfg.GraceDays = DefaultGraceDays
	}
	if cfg.MonthlyLookup == "" {
		cfg.MonthlyLookup = DefaultMonthlyLookup
	}
	if cfg.YearlyLookup == "" {
		cfg.YearlyLookup = DefaultYearlyLookup
	}
	return &Service{cfg: cfg, store: st, stripe: newStripeClient(cfg), Now: time.Now}
}

// PublicURL is the configured public base URL, or "".
func (s *Service) PublicURL() string { return s.cfg.PublicURL }

func toRecord(b store.Billing) Record {
	return Record{
		Legacy:            b.Legacy,
		TrialStartedAt:    b.TrialStartedAt,
		CustomerID:        b.CustomerID,
		SubscriptionID:    b.SubscriptionID,
		Status:            b.Status,
		PeriodEnd:         b.PeriodEnd,
		CancelAtPeriodEnd: b.CancelAtPeriodEnd,
	}
}

// Plan reports the current plan without starting the trial.
func (s *Service) Plan(accountID string) (Plan, error) {
	b, err := s.store.GetBilling(accountID)
	if err != nil {
		return Plan{}, err
	}
	return Evaluate(toRecord(b), s.Now(), s.cfg.TrialDays, s.cfg.GraceDays), nil
}

// Touch is called on every sync request. It starts the trial on first use
// and reports whether sync may proceed.
func (s *Service) Touch(accountID string) (Plan, error) {
	b, err := s.store.GetBilling(accountID)
	if err != nil {
		return Plan{}, err
	}
	if !b.Legacy && b.TrialStartedAt.IsZero() {
		now := s.Now()
		if err := s.store.StartTrial(accountID, now); err != nil {
			return Plan{}, err
		}
		b.TrialStartedAt = now.UTC().Truncate(time.Second)
	}
	return Evaluate(toRecord(b), s.Now(), s.cfg.TrialDays, s.cfg.GraceDays), nil
}

// Checkout creates a Stripe Checkout Session for plan "monthly" or "yearly".
func (s *Service) Checkout(ctx context.Context, accountID, plan, baseURL string) (string, error) {
	var lookup string
	switch plan {
	case "monthly", "":
		lookup = s.cfg.MonthlyLookup
	case "yearly":
		lookup = s.cfg.YearlyLookup
	default:
		return "", ErrUnknownPlan
	}
	p, err := s.Touch(accountID)
	if err != nil {
		return "", err
	}
	if p.State == StateFree {
		return "", ErrExempt
	}
	if p.State == StateActive || p.State == StatePastDue {
		return "", ErrAlreadyActive
	}
	b, err := s.store.GetBilling(accountID)
	if err != nil {
		return "", err
	}
	price, err := s.stripe.priceID(ctx, lookup, []string{s.cfg.MonthlyLookup, s.cfg.YearlyLookup})
	if err != nil {
		return "", err
	}
	var trialEnd time.Time
	// Keep the remaining free days: Stripe bills when the server trial ends.
	// Stripe needs trial_end at least 48 hours ahead.
	if p.State == StateTrial && p.TrialEndsAt != nil && p.TrialEndsAt.Sub(s.Now()) > 49*time.Hour {
		trialEnd = *p.TrialEndsAt
	}
	return s.stripe.createCheckout(ctx, checkoutParams{
		AccountID:    accountID,
		CustomerID:   b.CustomerID,
		PriceID:      price,
		SuccessURL:   baseURL + "/v1/billing/return?status=success",
		CancelURL:    baseURL + "/v1/billing/return?status=cancel",
		TrialEnd:     trialEnd,
		AutomaticTax: s.cfg.AutomaticTax,
	})
}

// Portal creates a Stripe Customer Portal session for cancel / manage.
func (s *Service) Portal(ctx context.Context, accountID, baseURL string) (string, error) {
	b, err := s.store.GetBilling(accountID)
	if err != nil {
		return "", err
	}
	if b.CustomerID == "" {
		return "", ErrNoCustomer
	}
	return s.stripe.createPortal(ctx, b.CustomerID, baseURL+"/v1/billing/return?status=portal")
}

// HandleWebhook verifies and applies one Stripe webhook delivery.
// A signature error should map to 400; any other error to 500 so Stripe retries.
func (s *Service) HandleWebhook(payload []byte, sigHeader string) error {
	now := s.Now()
	if err := VerifySignature(payload, sigHeader, s.cfg.WebhookSecret, now); err != nil {
		return &SignatureError{err}
	}
	var ev event
	if err := json.Unmarshal(payload, &ev); err != nil || ev.ID == "" {
		return &SignatureError{errors.New("bad event json")}
	}
	seen, err := s.store.EventSeen(ev.ID)
	if err != nil {
		return err
	}
	if seen {
		return nil
	}
	if err := s.apply(ev, now); err != nil {
		return err
	}
	return s.store.RecordEvent(ev.ID, now)
}

// SignatureError marks a webhook rejected before any processing.
type SignatureError struct{ Err error }

func (e *SignatureError) Error() string { return "webhook: " + e.Err.Error() }
func (e *SignatureError) Unwrap() error { return e.Err }

func (s *Service) apply(ev event, now time.Time) error {
	switch ev.Type {
	case "checkout.session.completed":
		var cs checkoutSession
		if err := json.Unmarshal(ev.Data.Object, &cs); err != nil {
			return err
		}
		if cs.Mode != "subscription" {
			return nil
		}
		acct := cs.ClientReferenceID
		if acct == "" {
			acct = cs.Metadata["account_id"]
		}
		ok, err := s.store.AccountExists(acct)
		if err != nil || !ok {
			return err
		}
		status := "incomplete"
		if cs.Status == "complete" && (cs.PaymentStatus == "paid" || cs.PaymentStatus == "no_payment_required") {
			status = "active"
		}
		return s.store.SetCheckout(acct, string(cs.Customer), string(cs.Subscription), status, now)

	case "customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted":
		var sub subscription
		if err := json.Unmarshal(ev.Data.Object, &sub); err != nil {
			return err
		}
		acct, err := s.accountFor(sub.Metadata["account_id"], sub.ID, string(sub.Customer))
		if err != nil || acct == "" {
			return err
		}
		status := sub.Status
		if ev.Type == "customer.subscription.deleted" && status == "" {
			status = "canceled"
		}
		return s.store.ApplySubscription(acct, string(sub.Customer), sub.ID, status,
			sub.periodEnd(), sub.CancelAtPeriodEnd, ev.Created, now)

	case "invoice.payment_failed":
		var inv invoice
		if err := json.Unmarshal(ev.Data.Object, &inv); err != nil {
			return err
		}
		return s.store.MarkPaymentFailed(inv.subscriptionID(), string(inv.Customer), now)
	}
	return nil
}

func (s *Service) accountFor(metaID, subID, customerID string) (string, error) {
	if metaID != "" {
		ok, err := s.store.AccountExists(metaID)
		if err != nil {
			return "", err
		}
		if ok {
			return metaID, nil
		}
	}
	return s.store.AccountByStripe(subID, customerID)
}

// Purge deletes the server-side ciphertext of accounts whose access ended
// more than GraceDays ago. Account rows stay so the user can subscribe and
// upload again. Local data on devices is never touched.
func (s *Service) Purge() (int, error) {
	rows, err := s.store.ListBilling()
	if err != nil {
		return 0, err
	}
	now := s.Now()
	n := 0
	for _, b := range rows {
		p := Evaluate(toRecord(b), now, s.cfg.TrialDays, s.cfg.GraceDays)
		if !p.TrialStarted || !PurgeDue(p, now) {
			continue
		}
		c, err := s.store.DeleteBlobs(b.AccountID)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return n, err
		}
		if c > 0 {
			n++
		}
	}
	return n, nil
}

// RunPurge calls Purge every interval until ctx is done.
func (s *Service) RunPurge(ctx context.Context, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if n, err := s.Purge(); err != nil {
				log.Printf("billing purge: %v", err)
			} else if n > 0 {
				log.Printf("billing purge: cleared server copy for %d lapsed accounts", n)
			}
		}
	}
}
