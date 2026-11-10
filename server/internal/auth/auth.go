package auth

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strings"
	"unicode"
)

// Verifier checks bootstrap bearer tokens against SHA-256 hex digests.
// Raw tokens are never stored. Account tokens live in the database;
// this verifier is only the legacy env / tokens-file bootstrap.
type Verifier struct {
	legacyHash string
	hashes     []string
}

// FromEnv loads optional UNSCROLL_SYNC_TOKEN_SHA256 and UNSCROLL_SYNC_TOKENS_FILE.
// Both may be unset: new installs create accounts with POST /v1/accounts.
// The env hash bootstraps the single pre-account vault. It is not required
// when that database has no blobs to migrate.
func FromEnv() (*Verifier, error) {
	var legacy string
	var hashes []string
	if v := strings.TrimSpace(os.Getenv("UNSCROLL_SYNC_TOKEN_SHA256")); v != "" {
		h, err := normalizeHash(v)
		if err != nil {
			return nil, fmt.Errorf("UNSCROLL_SYNC_TOKEN_SHA256: %w", err)
		}
		legacy = h
		hashes = append(hashes, h)
	}
	if path := strings.TrimSpace(os.Getenv("UNSCROLL_SYNC_TOKENS_FILE")); path != "" {
		more, err := hashesFromFile(path)
		if err != nil {
			return nil, err
		}
		hashes = append(hashes, more...)
	}
	return &Verifier{legacyHash: legacy, hashes: unique(hashes)}, nil
}

// Empty is a verifier with no bootstrap tokens.
func Empty() *Verifier { return &Verifier{} }

// LegacyHash is the normalized UNSCROLL_SYNC_TOKEN_SHA256 value, or empty.
func (v *Verifier) LegacyHash() string {
	if v == nil {
		return ""
	}
	return v.legacyHash
}

// New builds a verifier from already-normalized or raw hex digests.
func New(hexHashes []string) (*Verifier, error) {
	var hashes []string
	for _, h := range hexHashes {
		n, err := normalizeHash(h)
		if err != nil {
			return nil, err
		}
		hashes = append(hashes, n)
	}
	if len(hashes) == 0 {
		return nil, errors.New("no token hashes")
	}
	return &Verifier{hashes: unique(hashes)}, nil
}

func hashesFromFile(path string) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("UNSCROLL_SYNC_TOKENS_FILE: %w", err)
	}
	defer f.Close()
	var out []string
	sc := bufio.NewScanner(f)
	lineNo := 0
	for sc.Scan() {
		lineNo++
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		h, err := normalizeHash(line)
		if err != nil {
			return nil, fmt.Errorf("UNSCROLL_SYNC_TOKENS_FILE:%d: %w", lineNo, err)
		}
		out = append(out, h)
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

func normalizeHash(s string) (string, error) {
	s = strings.ToLower(strings.TrimSpace(s))
	if len(s) != sha256.Size*2 {
		return "", errors.New("want 64 hex chars")
	}
	if _, err := hex.DecodeString(s); err != nil {
		return "", errors.New("want hex SHA-256")
	}
	return s, nil
}

func unique(in []string) []string {
	seen := map[string]struct{}{}
	var out []string
	for _, h := range in {
		if _, ok := seen[h]; ok {
			continue
		}
		seen[h] = struct{}{}
		out = append(out, h)
	}
	return out
}

// TokenHash returns lowercase hex SHA-256 of a raw bearer token.
func TokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// NewToken returns a bearer token with 32 bytes of cryptographic randomness,
// encoded as unpadded base64url (printable ASCII, no spaces).
func NewToken() (string, error) {
	var buf [32]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf[:]), nil
}

// ValidToken reports whether token is 8–512 printable ASCII bytes and has no spaces.
func ValidToken(token string) bool { return validToken(token) }

// NormalizeHash returns lowercase hex SHA-256 or an error.
func NormalizeHash(s string) (string, error) { return normalizeHash(s) }

// Accept reports whether the raw bearer token matches a configured digest.
// Comparison does not stop at the first hit.
func (v *Verifier) Accept(token string) bool {
	if v == nil || !validToken(token) {
		return false
	}
	got := TokenHash(token)
	ok := 0
	for _, h := range v.hashes {
		ok |= subtle.ConstantTimeCompare([]byte(got), []byte(h))
	}
	return ok == 1
}

func validToken(token string) bool {
	if len(token) < 8 || len(token) > 512 {
		return false
	}
	for _, r := range token {
		if r > unicode.MaxASCII || r < 0x21 || r > 0x7E {
			return false
		}
	}
	return true
}
