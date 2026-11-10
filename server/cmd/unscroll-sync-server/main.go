package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/sepiropht/unscroll/server/internal/auth"
	"github.com/sepiropht/unscroll/server/internal/billing"
	"github.com/sepiropht/unscroll/server/internal/ratelimit"
	"github.com/sepiropht/unscroll/server/internal/server"
	"github.com/sepiropht/unscroll/server/internal/store"
)

func main() {
	addr := os.Getenv("ADDR")
	if addr == "" {
		addr = "127.0.0.1:8781"
	}
	data := os.Getenv("DATA")
	if data == "" {
		log.Fatal("DATA is required")
	}
	if err := os.MkdirAll(data, 0o700); err != nil {
		log.Fatal(err)
	}

	verifier, err := auth.FromEnv()
	if err != nil {
		log.Fatal(err)
	}

	st, err := store.Open(filepath.Join(data, "sync.db"), verifier.LegacyHash())
	if err != nil {
		log.Fatal(err)
	}
	defer st.Close()

	h := server.New(st, verifier, ratelimit.New(60))

	// Hosted billing is optional. Self-hosted servers leave the Stripe env
	// unset and sync stays free with no trial and no limits.
	bcfg, billingOn, err := billing.ConfigFromEnv()
	if err != nil {
		log.Fatal(err)
	}
	if billingOn {
		svc := billing.New(bcfg, st)
		h.SetBilling(svc)
		go svc.RunPurge(context.Background(), time.Hour)
		log.Printf("hosted billing enabled (trial %d days, grace %d days)", bcfg.TrialDays, bcfg.GraceDays)
	}
	log.Printf("unscroll-sync-server listening on %s", addr)
	if err := http.ListenAndServe(addr, h); err != nil {
		log.Fatal(err)
	}
}
