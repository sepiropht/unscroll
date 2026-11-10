package store

import (
	"bytes"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/sepiropht/unscroll/server/internal/auth"
)

func TestMigrateLegacyAccount(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sync.db")
	token := "legacy-token-value"
	hash := auth.TokenHash(token)
	settings := []byte(`{"legacy":true}`)
	device := []byte(`{"n":2}`)
	if err := seedOldBlobs(path, map[string][]byte{
		"settings":                settings,
		"device.desktop.abcdefgh": device,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := Open(path, ""); err == nil || !strings.Contains(err.Error(), "UNSCROLL_SYNC_TOKEN_SHA256") {
		t.Fatalf("migrate without hash: %v", err)
	}

	st, err := Open(path, strings.ToUpper(hash))
	if err != nil {
		t.Fatal(err)
	}
	id, err := st.AccountIDByToken(token)
	if err != nil || id == "" {
		t.Fatalf("account %q %v", id, err)
	}
	got, err := st.Get(id, "settings")
	if err != nil || !bytes.Equal(got.Body, settings) || got.ETag != ETagValue(settings) {
		t.Fatalf("settings %v %q", err, got.Body)
	}
	list, err := st.List(id)
	if err != nil || len(list) != 2 {
		t.Fatalf("list %v %d", err, len(list))
	}
	var accounts, blobs, legacy int
	var stored string
	if err := st.db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&accounts); err != nil {
		t.Fatal(err)
	}
	if err := st.db.QueryRow(`SELECT COUNT(*) FROM blobs`).Scan(&blobs); err != nil {
		t.Fatal(err)
	}
	if err := st.db.QueryRow(`SELECT token_hash, legacy FROM accounts WHERE id = ?`, id).Scan(&stored, &legacy); err != nil {
		t.Fatal(err)
	}
	if accounts != 1 || blobs != 2 || legacy != 1 || stored != hash || stored == token {
		t.Fatalf("accounts=%d blobs=%d legacy=%d hashOK=%v", accounts, blobs, legacy, stored == hash)
	}
	if _, err := st.db.Exec(`PRAGMA wal_checkpoint(TRUNCATE)`); err != nil {
		t.Fatal(err)
	}
	st.Close()

	st, err = Open(path, hash)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	if err := st.db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&accounts); err != nil {
		t.Fatal(err)
	}
	if err := st.db.QueryRow(`SELECT COUNT(*) FROM blobs`).Scan(&blobs); err != nil {
		t.Fatal(err)
	}
	if accounts != 1 || blobs != 2 {
		t.Fatalf("reopen accounts=%d blobs=%d", accounts, blobs)
	}
	if diskContains(t, dir, token) {
		t.Fatal("raw token stored on disk")
	}
}

func TestMigrateEmptyLegacySchema(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sync.db")
	if err := seedOldBlobs(path, nil); err != nil {
		t.Fatal(err)
	}
	st, err := Open(path, "")
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	has, err := st.columnExists("blobs", "account_id")
	if err != nil || !has {
		t.Fatalf("account_id column %v %v", has, err)
	}
	var n int
	if err := st.db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("accounts %d", n)
	}
}

func TestFreshOpenBootstrapsLegacy(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "plain.db"), "")
	if err != nil {
		t.Fatal(err)
	}
	var n int
	if err := st.db.QueryRow(`SELECT COUNT(*) FROM accounts`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	st.Close()
	if n != 0 {
		t.Fatalf("accounts %d", n)
	}

	token := "bootstrap-token"
	st, err = Open(filepath.Join(dir, "legacy.db"), auth.TokenHash(token))
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	id, err := st.AccountIDByToken(token)
	if err != nil || id == "" {
		t.Fatalf("bootstrap %q %v", id, err)
	}
	legacyID, err := st.LegacyAccountByHash(auth.TokenHash(token))
	if err != nil || legacyID != id {
		t.Fatalf("legacy id %q %v", legacyID, err)
	}
}

func TestCreateAccountStoresHashOnly(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "sync.db")
	st, err := Open(path, "")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	idA, tokenA, err := st.CreateAccount(now)
	if err != nil {
		t.Fatal(err)
	}
	idB, tokenB, err := st.CreateAccount(now)
	if err != nil {
		t.Fatal(err)
	}
	if len(tokenA) < 43 || tokenA == tokenB || idA == idB {
		t.Fatal("account ids or tokens")
	}
	var stored string
	if err := st.db.QueryRow(`SELECT token_hash FROM accounts WHERE id = ?`, idA).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if stored != auth.TokenHash(tokenA) || stored == tokenA {
		t.Fatal("stored token hash")
	}
	if _, err := st.Create(idA, "settings", []byte("a"), now); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Create(idB, "settings", []byte("b"), now); err != nil {
		t.Fatal(err)
	}
	gotA, err := st.Get(idA, "settings")
	if err != nil || string(gotA.Body) != "a" {
		t.Fatalf("a %v %q", err, gotA.Body)
	}
	gotB, err := st.Get(idB, "settings")
	if err != nil || string(gotB.Body) != "b" {
		t.Fatalf("b %v %q", err, gotB.Body)
	}
	if _, err := st.db.Exec(`PRAGMA wal_checkpoint(TRUNCATE)`); err != nil {
		t.Fatal(err)
	}
	st.Close()
	if diskContains(t, dir, tokenA) || diskContains(t, dir, tokenB) {
		t.Fatal("raw token stored on disk")
	}
}

func TestDeleteAccountWipesRows(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "sync.db"), "")
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	now := time.Now()
	idA, tokA, err := st.CreateAccount(now)
	if err != nil {
		t.Fatal(err)
	}
	idB, tokB, err := st.CreateAccount(now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.Create(idA, "settings", []byte("secret"), now); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Create(idB, "settings", []byte("keep"), now); err != nil {
		t.Fatal(err)
	}
	if err := st.DeleteAccount(idA); err != nil {
		t.Fatal(err)
	}
	if id, err := st.AccountIDByToken(tokA); err != nil || id != "" {
		t.Fatalf("deleted token still resolves %q %v", id, err)
	}
	if _, err := st.Get(idA, "settings"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("wiped blob %v", err)
	}
	got, err := st.Get(idB, "settings")
	if err != nil || string(got.Body) != "keep" {
		t.Fatalf("kept %v %q", err, got.Body)
	}
	if id, err := st.AccountIDByToken(tokB); err != nil || id != idB {
		t.Fatalf("b token %q %v", id, err)
	}
	if err := st.DeleteAccount("missing"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("missing %v", err)
	}
}

func TestUpdateQuota(t *testing.T) {
	dir := t.TempDir()
	st, err := Open(filepath.Join(dir, "sync.db"), "")
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	now := time.Now()
	id, _, err := st.CreateAccount(now)
	if err != nil {
		t.Fatal(err)
	}
	body := bytes.Repeat([]byte("a"), int(MaxBytesPerAccount)-10)
	created, err := st.Create(id, "settings", body, now)
	if err != nil {
		t.Fatal(err)
	}
	bigger := bytes.Repeat([]byte("b"), int(MaxBytesPerAccount)+1)
	if _, err := st.Update(id, "settings", created.ETag, bigger, now); !errors.Is(err, ErrQuota) {
		t.Fatalf("update quota %v", err)
	}
	got, err := st.Get(id, "settings")
	if err != nil || got.Bytes != len(body) {
		t.Fatalf("unchanged %v bytes %d", err, got.Bytes)
	}
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
			id, body, ETagValue(body), len(body), "2026-11-05T11:00:00Z",
		); err != nil {
			return err
		}
	}
	return nil
}

func diskContains(t *testing.T, dir, secret string) bool {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if bytes.Contains(b, []byte(secret)) {
			return true
		}
	}
	return false
}
