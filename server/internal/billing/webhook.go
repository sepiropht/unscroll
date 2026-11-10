package billing

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"
)

// SignatureTolerance is how old a signed webhook timestamp may be.
const SignatureTolerance = 5 * time.Minute

var (
	ErrNoSignature  = errors.New("missing Stripe-Signature")
	ErrBadSignature = errors.New("signature mismatch")
	ErrStale        = errors.New("signature timestamp outside tolerance")
)

// Sign returns a Stripe-Signature header value for payload. Used by tests and
// local tooling; production signatures come from Stripe.
func Sign(payload []byte, secret string, t time.Time) string {
	ts := strconv.FormatInt(t.Unix(), 10)
	return "t=" + ts + ",v1=" + computeSig(payload, secret, ts)
}

func computeSig(payload []byte, secret, ts string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(ts))
	mac.Write([]byte("."))
	mac.Write(payload)
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifySignature checks a Stripe-Signature header (scheme v1, HMAC-SHA256).
func VerifySignature(payload []byte, header, secret string, now time.Time) error {
	if header == "" {
		return ErrNoSignature
	}
	var ts string
	var sigs []string
	for _, part := range strings.Split(header, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		switch k {
		case "t":
			ts = v
		case "v1":
			sigs = append(sigs, v)
		}
	}
	if ts == "" || len(sigs) == 0 {
		return ErrNoSignature
	}
	sec, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return ErrNoSignature
	}
	age := now.Sub(time.Unix(sec, 0))
	if age > SignatureTolerance || age < -SignatureTolerance {
		return ErrStale
	}
	want := computeSig(payload, secret, ts)
	for _, s := range sigs {
		if hmac.Equal([]byte(s), []byte(want)) {
			return nil
		}
	}
	return ErrBadSignature
}

type event struct {
	ID      string `json:"id"`
	Type    string `json:"type"`
	Created int64  `json:"created"`
	Data    struct {
		Object json.RawMessage `json:"object"`
	} `json:"data"`
}

// stripeRef is a field that is either an id string or an expanded object.
type stripeRef string

func (r *stripeRef) UnmarshalJSON(b []byte) error {
	if len(b) == 0 || string(b) == "null" {
		*r = ""
		return nil
	}
	if b[0] == '"' {
		var s string
		if err := json.Unmarshal(b, &s); err != nil {
			return err
		}
		*r = stripeRef(s)
		return nil
	}
	var o struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(b, &o); err != nil {
		return err
	}
	*r = stripeRef(o.ID)
	return nil
}

type checkoutSession struct {
	ID                string            `json:"id"`
	Mode              string            `json:"mode"`
	Status            string            `json:"status"`
	PaymentStatus     string            `json:"payment_status"`
	ClientReferenceID string            `json:"client_reference_id"`
	Customer          stripeRef         `json:"customer"`
	Subscription      stripeRef         `json:"subscription"`
	Metadata          map[string]string `json:"metadata"`
}

type subscription struct {
	ID                string            `json:"id"`
	Status            string            `json:"status"`
	Customer          stripeRef         `json:"customer"`
	CancelAtPeriodEnd bool              `json:"cancel_at_period_end"`
	CurrentPeriodEnd  int64             `json:"current_period_end"`
	Metadata          map[string]string `json:"metadata"`
	Items             struct {
		Data []struct {
			CurrentPeriodEnd int64 `json:"current_period_end"`
		} `json:"data"`
	} `json:"items"`
}

// periodEnd handles both API shapes: the top-level field (older versions)
// and the per-item field (2025-03-31 and later).
func (s subscription) periodEnd() time.Time {
	end := s.CurrentPeriodEnd
	for _, it := range s.Items.Data {
		if it.CurrentPeriodEnd > end {
			end = it.CurrentPeriodEnd
		}
	}
	if end == 0 {
		return time.Time{}
	}
	return time.Unix(end, 0).UTC()
}

type invoice struct {
	Customer     stripeRef `json:"customer"`
	Subscription stripeRef `json:"subscription"`
	Parent       struct {
		SubscriptionDetails struct {
			Subscription stripeRef `json:"subscription"`
		} `json:"subscription_details"`
	} `json:"parent"`
}

func (i invoice) subscriptionID() string {
	if i.Subscription != "" {
		return string(i.Subscription)
	}
	return string(i.Parent.SubscriptionDetails.Subscription)
}
