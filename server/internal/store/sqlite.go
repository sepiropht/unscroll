package store

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/sepiropht/unscroll/server/internal/auth"

	_ "modernc.org/sqlite"
)

const (
	// MaxBlobsPerAccount is the per-vault blob count cap enforced on create.
	MaxBlobsPerAccount = 50
	// MaxBytesPerAccount is the per-vault sum of stored body sizes.
	MaxBytesPerAccount int64 = 10_000_000

	zeroHash = "0000000000000000000000000000000000000000000000000000000000000000"
)

var (
	ErrExists       = errors.New("exists")
	ErrPrecondition = errors.New("precondition")
	ErrQuota        = errors.New("quota")
)

// Blob is one opaque ciphertext object.
type Blob struct {
	ID        string
	Body      []byte
	ETag      string
	Bytes     int
	UpdatedAt time.Time
}

// Store is a SQLite vault of opaque blobs, namespaced by account.
type Store struct {
	db *sql.DB
}

// Open creates or opens the database at path.
// legacyTokenHash is the hex SHA-256 of the historical single-vault bearer
// token. It is required when blobs still use the pre-account schema and
// contain rows. Empty is valid for a new database.
func Open(path, legacyTokenHash string) (*Store, error) {
	if strings.TrimSpace(legacyTokenHash) != "" {
		h, err := auth.NormalizeHash(legacyTokenHash)
		if err != nil {
			return nil, fmt.Errorf("legacy token hash: %w", err)
		}
		legacyTokenHash = h
	} else {
		legacyTokenHash = ""
	}
	dsn := "file:" + path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	s := &Store{db: db}
	if err := s.migrate(legacyTokenHash); err != nil {
		db.Close()
		return nil, err
	}
	if err := s.migrateBilling(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate(legacyHash string) error {
	if _, err := s.db.Exec(`PRAGMA foreign_keys = ON`); err != nil {
		return err
	}
	if _, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  legacy INTEGER NOT NULL DEFAULT 0
)`); err != nil {
		return err
	}

	exists, err := s.tableExists("blobs")
	if err != nil {
		return err
	}
	if !exists {
		if err := s.createBlobs(); err != nil {
			return err
		}
		return s.ensureLegacy(legacyHash)
	}
	has, err := s.columnExists("blobs", "account_id")
	if err != nil {
		return err
	}
	if has {
		return s.ensureLegacy(legacyHash)
	}

	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM blobs`).Scan(&n); err != nil {
		return err
	}
	if n > 0 && legacyHash == "" {
		return errors.New("UNSCROLL_SYNC_TOKEN_SHA256 required to migrate existing blobs")
	}
	if err := s.rewriteBlobs(legacyHash, n); err != nil {
		return err
	}
	return s.ensureLegacy(legacyHash)
}

func (s *Store) createBlobs() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS blobs (
  account_id TEXT NOT NULL,
  id TEXT NOT NULL,
  body BLOB NOT NULL,
  etag TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (account_id, id),
  FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
)`)
	return err
}

// rewriteBlobs rebuilds the single-vault blobs table into the per-account schema.
// When n > 0, legacyHash owns every existing row.
func (s *Store) rewriteBlobs(legacyHash string, n int) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`
CREATE TABLE blobs_new (
  account_id TEXT NOT NULL,
  id TEXT NOT NULL,
  body BLOB NOT NULL,
  etag TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (account_id, id),
  FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
)`); err != nil {
		return err
	}
	if n > 0 {
		id, err := newAccountID()
		if err != nil {
			return err
		}
		if _, err := tx.Exec(
			`INSERT INTO accounts(id, token_hash, created_at, legacy) VALUES(?,?,?,1)`,
			id, legacyHash, formatTime(time.Now()),
		); err != nil {
			return err
		}
		if _, err := tx.Exec(
			`INSERT INTO blobs_new(account_id, id, body, etag, bytes, updated_at)
			 SELECT ?, id, body, etag, bytes, updated_at FROM blobs`, id,
		); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`DROP TABLE blobs`); err != nil {
		return err
	}
	if _, err := tx.Exec(`ALTER TABLE blobs_new RENAME TO blobs`); err != nil {
		return err
	}
	return tx.Commit()
}

// ensureLegacy inserts the bootstrap account for the env token hash when missing.
// Restarting with the env hash set recreates the row after DELETE; blobs stay gone.
func (s *Store) ensureLegacy(hash string) error {
	if hash == "" {
		return nil
	}
	res, err := s.db.Exec(`UPDATE accounts SET legacy = 1 WHERE token_hash = ?`, hash)
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	id, err := newAccountID()
	if err != nil {
		return err
	}
	_, err = s.db.Exec(
		`INSERT INTO accounts(id, token_hash, created_at, legacy) VALUES(?,?,?,1)`,
		id, hash, formatTime(time.Now()),
	)
	return err
}

func (s *Store) tableExists(name string) (bool, error) {
	var n int
	err := s.db.QueryRow(
		`SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?`, name,
	).Scan(&n)
	return n > 0, err
}

func (s *Store) columnExists(table, column string) (bool, error) {
	rows, err := s.db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return false, err
	}
	defer rows.Close()
	for rows.Next() {
		var cid, notnull, pk int
		var name, ctype string
		var dflt sql.NullString
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return false, err
		}
		if name == column {
			return true, nil
		}
	}
	return false, rows.Err()
}

// CreateAccount inserts an anonymous account and returns its id and raw token.
// Only the SHA-256 hex of the token is stored.
func (s *Store) CreateAccount(now time.Time) (id, token string, err error) {
	for attempt := 0; attempt < 3; attempt++ {
		token, err = auth.NewToken()
		if err != nil {
			return "", "", err
		}
		id, err = newAccountID()
		if err != nil {
			return "", "", err
		}
		_, err = s.db.Exec(
			`INSERT INTO accounts(id, token_hash, created_at, legacy) VALUES(?,?,?,0)`,
			id, auth.TokenHash(token), formatTime(now),
		)
		if err == nil {
			return id, token, nil
		}
		if !isUnique(err) {
			return "", "", err
		}
	}
	return "", "", errors.New("account create conflict")
}

// DeleteAccount removes the account row and every blob in that vault.
func (s *Store) DeleteAccount(id string) error {
	return s.withTx(func(tx *sql.Tx) error {
		if _, err := tx.Exec(`DELETE FROM blobs WHERE account_id = ?`, id); err != nil {
			return err
		}
		if _, err := tx.Exec(`DELETE FROM billing WHERE account_id = ?`, id); err != nil {
			return err
		}
		res, err := tx.Exec(`DELETE FROM accounts WHERE id = ?`, id)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if n == 0 {
			return sql.ErrNoRows
		}
		return nil
	})
}

// AccountIDByToken resolves a raw bearer token to an account id.
// An empty id with a nil error means no matching account.
// The stored digest is compared in constant time.
func (s *Store) AccountIDByToken(token string) (string, error) {
	if !auth.ValidToken(token) {
		return "", nil
	}
	got := auth.TokenHash(token)
	var id, stored string
	err := s.db.QueryRow(
		`SELECT id, token_hash FROM accounts WHERE token_hash = ?`, got,
	).Scan(&id, &stored)
	if errors.Is(err, sql.ErrNoRows) {
		subtle.ConstantTimeCompare([]byte(got), []byte(zeroHash))
		return "", nil
	}
	if err != nil {
		return "", err
	}
	if subtle.ConstantTimeCompare([]byte(got), []byte(stored)) != 1 {
		return "", nil
	}
	return id, nil
}

// LegacyAccountByHash returns the legacy account created from hash.
// An empty id means that bootstrap row is absent. hash is compared in constant time.
func (s *Store) LegacyAccountByHash(hash string) (string, error) {
	hash = strings.ToLower(strings.TrimSpace(hash))
	if hash == "" {
		return "", nil
	}
	var id, stored string
	var legacy int
	err := s.db.QueryRow(
		`SELECT id, token_hash, legacy FROM accounts WHERE token_hash = ?`, hash,
	).Scan(&id, &stored, &legacy)
	if errors.Is(err, sql.ErrNoRows) {
		subtle.ConstantTimeCompare([]byte(hash), []byte(zeroHash))
		return "", nil
	}
	if err != nil {
		return "", err
	}
	if subtle.ConstantTimeCompare([]byte(hash), []byte(stored)) != 1 || legacy != 1 {
		return "", nil
	}
	return id, nil
}

// ETagValue is sha256-<lowercase hex> of body bytes (no quotes).
func ETagValue(body []byte) string {
	sum := sha256.Sum256(body)
	return "sha256-" + hex.EncodeToString(sum[:])
}

// Create inserts id for accountID if that pair is absent.
func (s *Store) Create(accountID, id string, body []byte, now time.Time) (Blob, error) {
	b := makeBlob(id, body, now)
	err := s.withTx(func(tx *sql.Tx) error {
		var exists int
		qerr := tx.QueryRow(
			`SELECT 1 FROM blobs WHERE account_id = ? AND id = ?`, accountID, id,
		).Scan(&exists)
		if qerr == nil {
			return ErrExists
		}
		if !errors.Is(qerr, sql.ErrNoRows) {
			return qerr
		}
		if qerr = enforceCreateQuota(tx, accountID, b.Bytes); qerr != nil {
			return qerr
		}
		_, qerr = tx.Exec(
			`INSERT INTO blobs(account_id, id, body, etag, bytes, updated_at) VALUES(?,?,?,?,?,?)`,
			accountID, b.ID, b.Body, b.ETag, b.Bytes, formatTime(b.UpdatedAt),
		)
		if isUnique(qerr) {
			return ErrExists
		}
		return qerr
	})
	if err != nil {
		return Blob{}, err
	}
	return b, nil
}

// Update replaces one account's blob when the current etag matches.
func (s *Store) Update(accountID, id, matchETag string, body []byte, now time.Time) (Blob, error) {
	b := makeBlob(id, body, now)
	err := s.withTx(func(tx *sql.Tx) error {
		var old int
		qerr := tx.QueryRow(
			`SELECT bytes FROM blobs WHERE account_id = ? AND id = ? AND etag = ?`,
			accountID, id, matchETag,
		).Scan(&old)
		if errors.Is(qerr, sql.ErrNoRows) {
			return ErrPrecondition
		}
		if qerr != nil {
			return qerr
		}
		_, total, qerr := quotaTotals(tx, accountID)
		if qerr != nil {
			return qerr
		}
		if total-int64(old)+int64(b.Bytes) > MaxBytesPerAccount {
			return ErrQuota
		}
		res, qerr := tx.Exec(
			`UPDATE blobs SET body = ?, etag = ?, bytes = ?, updated_at = ? WHERE account_id = ? AND id = ? AND etag = ?`,
			b.Body, b.ETag, b.Bytes, formatTime(b.UpdatedAt), accountID, id, matchETag,
		)
		if qerr != nil {
			return qerr
		}
		n, qerr := res.RowsAffected()
		if qerr != nil {
			return qerr
		}
		if n != 1 {
			return ErrPrecondition
		}
		return nil
	})
	if err != nil {
		return Blob{}, err
	}
	return b, nil
}

// Get loads one blob in an account.
func (s *Store) Get(accountID, id string) (Blob, error) {
	var b Blob
	var updated string
	err := s.db.QueryRow(
		`SELECT id, body, etag, bytes, updated_at FROM blobs WHERE account_id = ? AND id = ?`,
		accountID, id,
	).Scan(&b.ID, &b.Body, &b.ETag, &b.Bytes, &updated)
	if errors.Is(err, sql.ErrNoRows) {
		return Blob{}, sql.ErrNoRows
	}
	if err != nil {
		return Blob{}, err
	}
	t, err := time.Parse(time.RFC3339, updated)
	if err != nil {
		return Blob{}, err
	}
	b.UpdatedAt = t
	return b, nil
}

// List returns one account's blob metadata, ordered by id.
func (s *Store) List(accountID string) ([]Blob, error) {
	rows, err := s.db.Query(
		`SELECT id, etag, bytes, updated_at FROM blobs WHERE account_id = ? ORDER BY id`,
		accountID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Blob
	for rows.Next() {
		var b Blob
		var updated string
		if err := rows.Scan(&b.ID, &b.ETag, &b.Bytes, &updated); err != nil {
			return nil, err
		}
		t, err := time.Parse(time.RFC3339, updated)
		if err != nil {
			return nil, err
		}
		b.UpdatedAt = t
		out = append(out, b)
	}
	return out, rows.Err()
}

func quotaTotals(tx *sql.Tx, accountID string) (count int, total int64, err error) {
	err = tx.QueryRow(
		`SELECT COUNT(*), COALESCE(SUM(bytes), 0) FROM blobs WHERE account_id = ?`,
		accountID,
	).Scan(&count, &total)
	return count, total, err
}

func enforceCreateQuota(tx *sql.Tx, accountID string, newBytes int) error {
	count, total, err := quotaTotals(tx, accountID)
	if err != nil {
		return err
	}
	if count >= MaxBlobsPerAccount {
		return ErrQuota
	}
	if total+int64(newBytes) > MaxBytesPerAccount {
		return ErrQuota
	}
	return nil
}

func (s *Store) withTx(fn func(*sql.Tx) error) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := fn(tx); err != nil {
		return err
	}
	return tx.Commit()
}

func newAccountID() (string, error) {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf[:]), nil
}

func makeBlob(id string, body []byte, now time.Time) Blob {
	if body == nil {
		body = []byte{}
	}
	cp := append([]byte(nil), body...)
	return Blob{
		ID:        id,
		Body:      cp,
		ETag:      ETagValue(cp),
		Bytes:     len(cp),
		UpdatedAt: now.UTC().Truncate(time.Second),
	}
}

func formatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}

func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE")
}
