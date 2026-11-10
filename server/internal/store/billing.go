package store

import (
	"database/sql"
	"errors"
	"time"
)

// Billing is the hosted-billing state of one account. It holds no usage data:
// only the trial start and the Stripe customer / subscription references.
type Billing struct {
	AccountID         string
	Legacy            bool
	TrialStartedAt    time.Time // zero until the first sync request
	CustomerID        string
	SubscriptionID    string
	Status            string // Stripe subscription status, or "" when none
	PeriodEnd         time.Time
	CancelAtPeriodEnd bool
	SubEventCreated   int64
	UpdatedAt         time.Time
}

func (s *Store) migrateBilling() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS billing (
  account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
  trial_started_at TEXT NOT NULL DEFAULT '',
  customer_id TEXT NOT NULL DEFAULT '',
  subscription_id TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT '',
  period_end TEXT NOT NULL DEFAULT '',
  cancel_at_period_end INTEGER NOT NULL DEFAULT 0,
  sub_event_created INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS billing_customer ON billing(customer_id);
CREATE INDEX IF NOT EXISTS billing_subscription ON billing(subscription_id);
CREATE TABLE IF NOT EXISTS stripe_events (
  id TEXT PRIMARY KEY,
  received_at TEXT NOT NULL
);`)
	return err
}

// GetBilling loads the billing row for accountID. A missing billing row
// yields zero values. A missing account yields sql.ErrNoRows.
func (s *Store) GetBilling(accountID string) (Billing, error) {
	var b Billing
	var legacy, cancel int
	var trial, periodEnd, updated string
	err := s.db.QueryRow(`
SELECT a.id, a.legacy,
  COALESCE(b.trial_started_at, ''), COALESCE(b.customer_id, ''), COALESCE(b.subscription_id, ''),
  COALESCE(b.status, ''), COALESCE(b.period_end, ''), COALESCE(b.cancel_at_period_end, 0),
  COALESCE(b.sub_event_created, 0), COALESCE(b.updated_at, '')
FROM accounts a LEFT JOIN billing b ON b.account_id = a.id
WHERE a.id = ?`, accountID).Scan(
		&b.AccountID, &legacy, &trial, &b.CustomerID, &b.SubscriptionID,
		&b.Status, &periodEnd, &cancel, &b.SubEventCreated, &updated,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return Billing{}, sql.ErrNoRows
	}
	if err != nil {
		return Billing{}, err
	}
	b.Legacy = legacy == 1
	b.CancelAtPeriodEnd = cancel == 1
	b.TrialStartedAt = parseTimeOrZero(trial)
	b.PeriodEnd = parseTimeOrZero(periodEnd)
	b.UpdatedAt = parseTimeOrZero(updated)
	return b, nil
}

// StartTrial records now as the trial start unless a start already exists.
func (s *Store) StartTrial(accountID string, now time.Time) error {
	_, err := s.db.Exec(`
INSERT INTO billing(account_id, trial_started_at, updated_at) VALUES(?, ?, ?)
ON CONFLICT(account_id) DO UPDATE SET
  trial_started_at = CASE WHEN billing.trial_started_at = '' THEN excluded.trial_started_at ELSE billing.trial_started_at END`,
		accountID, formatTime(now), formatTime(now))
	return err
}

// SetCheckout links a completed Checkout Session to accountID.
// status is only written when no subscription status is known yet, so a
// later-arriving checkout event never overrides a subscription event.
func (s *Store) SetCheckout(accountID, customerID, subscriptionID, status string, now time.Time) error {
	_, err := s.db.Exec(`
INSERT INTO billing(account_id, customer_id, subscription_id, status, updated_at) VALUES(?, ?, ?, ?, ?)
ON CONFLICT(account_id) DO UPDATE SET
  customer_id = CASE WHEN excluded.customer_id <> '' THEN excluded.customer_id ELSE billing.customer_id END,
  subscription_id = CASE WHEN excluded.subscription_id <> '' THEN excluded.subscription_id ELSE billing.subscription_id END,
  status = CASE WHEN billing.status = '' OR billing.subscription_id <> excluded.subscription_id THEN excluded.status ELSE billing.status END,
  updated_at = excluded.updated_at`,
		accountID, customerID, subscriptionID, status, formatTime(now))
	return err
}

// ApplySubscription stores the subscription state from a Stripe event.
// Events older than the last applied one for this account are ignored.
func (s *Store) ApplySubscription(accountID, customerID, subscriptionID, status string, periodEnd time.Time, cancelAtPeriodEnd bool, eventCreated int64, now time.Time) error {
	pe := ""
	if !periodEnd.IsZero() {
		pe = formatTime(periodEnd)
	}
	cancel := 0
	if cancelAtPeriodEnd {
		cancel = 1
	}
	_, err := s.db.Exec(`
INSERT INTO billing(account_id, customer_id, subscription_id, status, period_end, cancel_at_period_end, sub_event_created, updated_at)
VALUES(?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(account_id) DO UPDATE SET
  customer_id = CASE WHEN excluded.customer_id <> '' THEN excluded.customer_id ELSE billing.customer_id END,
  subscription_id = excluded.subscription_id,
  status = excluded.status,
  period_end = excluded.period_end,
  cancel_at_period_end = excluded.cancel_at_period_end,
  sub_event_created = excluded.sub_event_created,
  updated_at = excluded.updated_at
WHERE excluded.sub_event_created >= billing.sub_event_created
  AND NOT (billing.subscription_id <> '' AND billing.subscription_id <> excluded.subscription_id
           AND billing.status IN ('active', 'trialing', 'past_due') AND excluded.status NOT IN ('active', 'trialing', 'past_due'))`,
		accountID, customerID, subscriptionID, status, pe, cancel, eventCreated, formatTime(now))
	return err
}

// MarkPaymentFailed flags an active subscription as past_due after a failed
// invoice. The next subscription event carries the authoritative status.
func (s *Store) MarkPaymentFailed(subscriptionID, customerID string, now time.Time) error {
	if subscriptionID != "" {
		_, err := s.db.Exec(`UPDATE billing SET status = 'past_due', updated_at = ?
WHERE subscription_id = ? AND status IN ('active', 'trialing')`, formatTime(now), subscriptionID)
		return err
	}
	if customerID != "" {
		_, err := s.db.Exec(`UPDATE billing SET status = 'past_due', updated_at = ?
WHERE customer_id = ? AND status IN ('active', 'trialing')`, formatTime(now), customerID)
		return err
	}
	return nil
}

// AccountByStripe finds the account linked to a subscription or customer id.
func (s *Store) AccountByStripe(subscriptionID, customerID string) (string, error) {
	var id string
	if subscriptionID != "" {
		err := s.db.QueryRow(`SELECT account_id FROM billing WHERE subscription_id = ?`, subscriptionID).Scan(&id)
		if err == nil {
			return id, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return "", err
		}
	}
	if customerID != "" {
		err := s.db.QueryRow(`SELECT account_id FROM billing WHERE customer_id = ? ORDER BY updated_at DESC LIMIT 1`, customerID).Scan(&id)
		if err == nil {
			return id, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return "", err
		}
	}
	return "", nil
}

// AccountExists reports whether id is a live account.
func (s *Store) AccountExists(id string) (bool, error) {
	if id == "" {
		return false, nil
	}
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM accounts WHERE id = ?`, id).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// EventSeen reports whether a Stripe event id was already processed.
func (s *Store) EventSeen(id string) (bool, error) {
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM stripe_events WHERE id = ?`, id).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

// RecordEvent marks a Stripe event id processed.
func (s *Store) RecordEvent(id string, now time.Time) error {
	_, err := s.db.Exec(`INSERT OR IGNORE INTO stripe_events(id, received_at) VALUES(?, ?)`, id, formatTime(now))
	return err
}

// ListBilling returns every billing row with a started trial or a subscription.
func (s *Store) ListBilling() ([]Billing, error) {
	rows, err := s.db.Query(`SELECT b.account_id FROM billing b JOIN accounts a ON a.id = b.account_id WHERE a.legacy = 0`)
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := make([]Billing, 0, len(ids))
	for _, id := range ids {
		b, err := s.GetBilling(id)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return nil, err
		}
		out = append(out, b)
	}
	return out, nil
}

// DeleteBlobs removes every blob in an account but keeps the account row, so
// the user can subscribe later and upload again from their devices.
func (s *Store) DeleteBlobs(accountID string) (int64, error) {
	res, err := s.db.Exec(`DELETE FROM blobs WHERE account_id = ?`, accountID)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

func parseTimeOrZero(v string) time.Time {
	if v == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339, v)
	if err != nil {
		return time.Time{}
	}
	return t
}
