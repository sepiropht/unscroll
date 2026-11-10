package billing

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// stripeClient is a minimal form-encoded Stripe REST client. It sends only
// account ids, price ids, and return URLs.
type stripeClient struct {
	base string
	key  string
	http *http.Client

	mu       sync.Mutex
	prices   map[string]string
	pricesAt time.Time
}

func newStripeClient(cfg Config) *stripeClient {
	base := cfg.APIBase
	if base == "" {
		base = DefaultAPIBase
	}
	hc := cfg.HTTPClient
	if hc == nil {
		hc = &http.Client{Timeout: 20 * time.Second}
	}
	return &stripeClient{base: strings.TrimRight(base, "/"), key: cfg.SecretKey, http: hc}
}

type stripeError struct {
	Status  int
	Message string
}

func (e *stripeError) Error() string { return fmt.Sprintf("stripe %d: %s", e.Status, e.Message) }

func (c *stripeClient) do(ctx context.Context, method, path string, form url.Values, out any) error {
	var body io.Reader
	u := c.base + path
	if method == http.MethodGet && form != nil {
		u += "?" + form.Encode()
	} else if form != nil {
		body = strings.NewReader(form.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, method, u, body)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+c.key)
	if body != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return err
	}
	if res.StatusCode/100 != 2 {
		var e struct {
			Error struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		_ = json.Unmarshal(raw, &e)
		return &stripeError{Status: res.StatusCode, Message: e.Error.Message}
	}
	return json.Unmarshal(raw, out)
}

// priceIDs resolves lookup keys to active price ids, cached for 10 minutes.
func (c *stripeClient) priceID(ctx context.Context, lookup string, all []string) (string, error) {
	c.mu.Lock()
	if c.prices != nil && time.Since(c.pricesAt) < 10*time.Minute {
		id, ok := c.prices[lookup]
		c.mu.Unlock()
		if ok {
			return id, nil
		}
		return "", fmt.Errorf("no active price with lookup_key %q", lookup)
	}
	c.mu.Unlock()

	q := url.Values{}
	q.Set("active", "true")
	q.Set("limit", "10")
	for _, k := range all {
		q.Add("lookup_keys[]", k)
	}
	var out struct {
		Data []struct {
			ID        string `json:"id"`
			LookupKey string `json:"lookup_key"`
		} `json:"data"`
	}
	if err := c.do(ctx, http.MethodGet, "/v1/prices", q, &out); err != nil {
		return "", err
	}
	m := map[string]string{}
	for _, p := range out.Data {
		m[p.LookupKey] = p.ID
	}
	c.mu.Lock()
	c.prices, c.pricesAt = m, time.Now()
	c.mu.Unlock()
	id, ok := m[lookup]
	if !ok {
		return "", fmt.Errorf("no active price with lookup_key %q", lookup)
	}
	return id, nil
}

type checkoutParams struct {
	AccountID    string
	CustomerID   string
	PriceID      string
	SuccessURL   string
	CancelURL    string
	TrialEnd     time.Time // zero: no Stripe-side trial
	AutomaticTax bool
}

func (c *stripeClient) createCheckout(ctx context.Context, p checkoutParams) (string, error) {
	f := url.Values{}
	f.Set("mode", "subscription")
	f.Set("line_items[0][price]", p.PriceID)
	f.Set("line_items[0][quantity]", "1")
	f.Set("client_reference_id", p.AccountID)
	f.Set("metadata[account_id]", p.AccountID)
	f.Set("subscription_data[metadata][account_id]", p.AccountID)
	f.Set("allow_promotion_codes", "true")
	if p.AutomaticTax {
		f.Set("automatic_tax[enabled]", "true")
	} else {
		f.Set("automatic_tax[enabled]", "false")
	}
	if !p.TrialEnd.IsZero() {
		f.Set("subscription_data[trial_end]", fmt.Sprint(p.TrialEnd.Unix()))
	}
	if p.CustomerID != "" {
		f.Set("customer", p.CustomerID)
	}
	f.Set("success_url", p.SuccessURL)
	f.Set("cancel_url", p.CancelURL)
	var out struct {
		URL string `json:"url"`
	}
	if err := c.do(ctx, http.MethodPost, "/v1/checkout/sessions", f, &out); err != nil {
		return "", err
	}
	if out.URL == "" {
		return "", errors.New("stripe: checkout session without url")
	}
	return out.URL, nil
}

func (c *stripeClient) createPortal(ctx context.Context, customerID, returnURL string) (string, error) {
	f := url.Values{}
	f.Set("customer", customerID)
	f.Set("return_url", returnURL)
	var out struct {
		URL string `json:"url"`
	}
	if err := c.do(ctx, http.MethodPost, "/v1/billing_portal/sessions", f, &out); err != nil {
		return "", err
	}
	if out.URL == "" {
		return "", errors.New("stripe: portal session without url")
	}
	return out.URL, nil
}
