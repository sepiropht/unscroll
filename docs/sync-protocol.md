# Unscroll Sync Protocol v1

Canonical contract for the Android app (`android/`), the browser extension (`extension/`), and the desktop app (`desktop/`). The self-hosted server stores **opaque ciphertext only**. It never sees passphrases or plaintext.

Every Unscroll client includes sync. Sync is off until the user enables it.

## 1. Roles and blob layout

Two kinds of encrypted blobs:

| Blob id | Writer | Merge |
| --- | --- | --- |
| `settings` | Any client (shared) | Last-writer-wins on plaintext `revision` (monotonic counter) plus HTTP ETag preconditions |
| `device.<platform>.<deviceId>` | That device only | No cross-device merge; each device overwrites its own usage snapshot |

- `platform` ∈ `android` \| `desktop` \| `browser`
- `deviceId` ∈ `[A-Za-z0-9_-]{8,64}` (stable per install)
- Full blob id ∈ `^[a-z][a-z0-9._-]{0,127}$`

Clients may `GET` every blob in their own vault; they may `PUT` only `settings` and their own `device.*` blob. The server isolates vaults by account: one bearer token sees only that account's blobs. Protocol version stays **1**. The blob paths and ETag rules are unchanged.

## 2. Base URL

User-entered `BASE`:

- scheme `https:` (preferred) or `http:` (LAN only; token travels in clear)
- no userinfo, query, or fragment
- ≤ 300 characters
- trailing `/` stripped

Endpoints are under `BASE` (do not append `/v1/...` twice if the user already included a path prefix).

## 3. HTTP API

### `GET /healthz` (no auth)

`200` `application/json` → `{"ok":true,"protocol":1,"billing":false}`. `billing` is `true` only on a server with hosted billing turned on.

### Hosted billing (optional)

Only when the server enables it (see `server/README.md`). `GET /v1/billing` (auth) returns the plan; `{"enabled":false}` on self-hosted servers, and older servers answer `404`, which clients treat the same. `POST /v1/billing/checkout` and `POST /v1/billing/portal` return `{"url":"https://…stripe.com/…"}`; clients open only https URLs on `stripe.com`. Blob routes answer `402` (`subscription_required`) after the trial ends without a subscription; clients show "Trial ended - subscribe or self-host" and keep local data.

### `POST /v1/accounts` (no auth)

Create an anonymous vault. No request body.

`201` `application/json`:

```json
{"token":"<bearer>","protocol":1,"accountId":"<id>"}
```

- `token` is shown **once**. The server stores only lowercase hex SHA-256 of that string. It is 32 cryptographically random bytes, encoded as unpadded base64url (printable ASCII, no spaces).
- `accountId` identifies the row. It is not a credential.
- `429` when rate limited. Both of these apply, per client IP:
  - the general limit (60 requests / minute)
  - **5 account creations / hour**

### `DELETE /v1/accounts` (auth)

Delete the caller's account row and every blob in that vault. `204` empty body. The bearer token stops working.

### `GET /v1/blobs` (auth)

List metadata for this account's vault (one vault per token):

```json
{"blobs":[{"id":"settings","etag":"sha256-<hex>","bytes":1234,"updatedAt":"2026-11-05T11:00:00Z"}]}
```

### `GET /v1/blobs/{id}` (auth)

- `200`: body = exact stored bytes (`Content-Type: application/json`), strong `ETag`
- `404`: missing
- `401`/`403`: bad token

### `PUT /v1/blobs/{id}` (auth)

Body = envelope JSON (raw bytes stored verbatim; server does not re-encode).

Preconditions (required):

- create: `If-None-Match: *` → `412` if exists
- replace: `If-Match: "<etag>"` → `412` if changed

Success: `204` (or `200`) with new strong `ETag`. Max body **2_000_000** bytes.

Per account, `PUT` also enforces:

- at most **50** blobs
- at most **10_000_000** total stored body bytes

Over the per-account cap: `413` and response text `quota`. A single body over 2_000_000 bytes is `413` with text `too large`. Quotas are per vault, so one account cannot fill another's budget.

### `OPTIONS` (CORS, no auth)

`204` empty body. Headers:

- `Access-Control-Allow-Origin: *`
- `Access-Control-Allow-Methods: GET, PUT, POST, DELETE, OPTIONS`
- `Access-Control-Allow-Headers: authorization, content-type, if-match, if-none-match`
- `Access-Control-Expose-Headers: ETag`
- `Access-Control-Max-Age: 86400`

Same CORS headers on `GET`/`PUT`/`POST`/`DELETE` responses.

### Auth

`Authorization: Bearer <token>` on blob routes and `DELETE /v1/accounts`. `POST /v1/accounts` is unauthenticated.

- token: 8–512 printable ASCII (`0x21`–`0x7E`), no spaces
- server stores **SHA-256 hex** of the token in `accounts.token_hash` (never the raw token); compare in constant time
- blob and delete routes resolve the bearer token to one `account_id` and touch only that account
- `401` on blob routes until some account exists
- do not log the token next to request bodies

Legacy single-vault servers:

- `UNSCROLL_SYNC_TOKEN_SHA256` is **not** required for a new database that only uses `POST /v1/accounts`
- it **is** required to migrate blobs that have no `account_id`: those rows move to one legacy account whose `token_hash` is that env value
- after migration, auth prefers the `accounts` table
- digests in `UNSCROLL_SYNC_TOKENS_FILE` authenticate as that legacy account only when the legacy row exists
- a later startup with the env hash still set recreates a missing legacy row (empty vault). Deleted blobs stay deleted. Unset the env var to retire it.

### ETag

```text
etag = "sha256-" + lowercase_hex(SHA-256(stored_body_bytes))
ETag = DQUOTE + etag + DQUOTE
```

Weak tags (`W/`) are protocol errors for clients.

### Rate limit

≥ 60 requests / minute / IP and per token. `429` when exceeded (`Retry-After: 60`).

`POST /v1/accounts` is also limited to 5 creations / hour / IP (`Retry-After: 3600`). Both limits apply.

### Redirects

Clients must not follow redirects (`redirect: error` / `instanceFollowRedirects = false`) so the bearer token never leaves the configured origin.

## 4. Encrypted envelope

`PUT`/`GET` body is compact JSON:

```json
{
  "version": 1,
  "alg": "AES-GCM",
  "kdf": "PBKDF2-SHA-256",
  "iterations": 150000,
  "salt": "<base64>",
  "iv": "<base64>",
  "ciphertext": "<base64>"
}
```

| Field | Rule |
| --- | --- |
| `version` | integer `1` |
| `alg` | exactly `AES-GCM` |
| `kdf` | exactly `PBKDF2-SHA-256` |
| `iterations` | integer 1…400000; writers use **150000**; readers accept the full range |
| `salt` | 16 random bytes, std base64 |
| `iv` | 12 random bytes, std base64 |
| `ciphertext` | AES-256-GCM output with **128-bit tag appended** (WebCrypto layout) |

KDF: UTF-8 passphrase bytes (no Unicode normalization) → PBKDF2-HMAC-SHA-256 → 256-bit key.

Passphrase: 8–200 UTF-16 code units (`String.length` / JS length). Never sent to the server.

Max plaintext UTF-8: **1_500_000** bytes. Reject oversized `GET` bodies.

Fixed test vector (iterations **1000**, not for production writes):

- passphrase `secret-passphrase`
- salt 16×`0x01`, iv 12×`0x02`
- key hex `f40f4c2521c20f9adc0692938f7c66454380a8b81be4d4a9706a48c4e2002006`
- plaintext `{"version":1,"revision":1}`
- ciphertext b64 `9HA/yDguHSi5iTip16kYVpDGU6OcyFzo8c8tU7fIf9AIx/DYs5y+ULpd`

## 5. Plaintext: `settings`

```json
{
  "version": 1,
  "kind": "settings",
  "revision": 12,
  "updatedAt": "2026-11-05T12:00:00.000Z",
  "body": {
    "preferences": {},
    "limits": [],
    "categories": [],
    "schedules": [],
    "blocklist": [],
    "whitelist": []
  }
}
```

- `revision`: integer 0…1_000_000_000; each successful shared write sets `remote.revision + 1` (or `1` on create)
- `updatedAt`: ISO-8601 UTC; clocks do **not** resolve conflicts
- `body` keys are advisory; unknown keys are preserved by clients that round-trip the doc
- LWW: if `remote.revision > localApplied` and local is clean → pull; if local dirty and remote ahead → conflict (no blind PUT); if only local dirty → push with `If-Match`

Secrets never appear in `body` (no sync token, no passphrase, no PIN/password verifier required by other platforms).

## 6. Plaintext: `device.<platform>.<deviceId>`

```json
{
  "version": 1,
  "kind": "usage",
  "revision": 3,
  "updatedAt": "2026-11-05T12:00:00.000Z",
  "platform": "android",
  "deviceId": "stable-id-here",
  "snapshot": {}
}
```

`snapshot` is platform-private (Android tables, extension stats, desktop sessions). Other clients may store a read-only copy for UI but must not `PUT` another device's blob.

On pull, a client merges **only** its own snapshot into local DB (upsert rules stay local). Foreign device blobs are optional reads.

## 7. Client UI

Settings panel fields: server URL, token, passphrase, enable toggle, **Sync now** button, last status/error. Changing URL clears local revision/ETag caches for sync (not local usage data).

## 8. Server requirements

- AGPL-3.0
- SQLite under a data dir
- hashed tokens only
- opaque blob bytes + etag + updated_at, namespaced by `account_id`
- schema: `accounts(id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, created_at TEXT, legacy INTEGER)` and `blobs(account_id TEXT NOT NULL, id TEXT NOT NULL, …, PRIMARY KEY (account_id, id))`
- Dockerfile, README, Go tests
- bind localhost in production behind Caddy

## 9. Compatibility note

Earlier drafts used a single `/v1/snapshot` multi-section document. **v1 canonical is multi-blob** as above. Clients MUST speak `/v1/blobs/...`. A server MAY reject `/v1/snapshot` with `404`/`410`.
