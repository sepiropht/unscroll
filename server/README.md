# Unscroll sync server

Self-hosted sync for every Unscroll client. The server stores **opaque ciphertext blobs** only (AGPL-3.0). It never sees the encryption key, passphrases, or plaintext. Contract: [../docs/sync-protocol.md](../docs/sync-protocol.md). Pairing: [../docs/pairing.md](../docs/pairing.md).

## Sync

Sync is the same for every user. The server is a blind store: one anonymous account per vault, ciphertext only.

### Accounts and keys

1. The client calls `POST /v1/accounts` with no credentials and no body.
2. The server returns a per-account bearer token once (`201`, field `token`). It stores only the SHA-256 hex of that token, never the raw token.
3. The client generates a local **32-byte** end-to-end encryption key and keeps it on the device.
4. Reads and writes are opaque ciphertext. The server never sees the key, the passphrase, or the plaintext.

Enabling sync in the app does steps 1–3. The user does not create an account by hand.

### Hosted convenience

The default public URL in the app is [https://sync.unscroll.elimbi.com](https://sync.unscroll.elimbi.com). That hosted service may later be a paid convenience (€2.99/month or €24/year, with a 14-day trial). Self-hosting this server stays free. This repository does not implement billing.

Self-hosters run this server and change that URL in the app to their own origin (scheme + host, no token in the URL).

### Pair a device

Both devices end up on the same account (same URL, bearer token, and encryption key).

1. On device A, enable sync. The app creates the account and the 32-byte key.
2. On device A, open **Pair a device** and show the QR code.
3. On device B, scan that QR code, or paste the `unscroll://pair` link.

**Warning.** The QR code and the `unscroll://pair` link grant full vault access. They carry the server URL, the bearer token, and the encryption key. Treat them like a password. Do not screenshot them or share them casually.

### Self-host

#### Build

```bash
go test ./...
go build -o bin/unscroll-sync-server ./cmd/unscroll-sync-server
```

#### Environment

New install. Clients create a vault with `POST /v1/accounts`. No token env is required.

```bash
export DATA=/var/lib/unscroll-sync
export ADDR=127.0.0.1:8781
mkdir -p "$DATA"
./unscroll-sync-server
```

Upgrade from the single shared vault. Set the existing token hash so current blobs stay on that token. This is required when the database already has blobs and no `account_id` column.

```bash
export UNSCROLL_SYNC_TOKEN_SHA256="$(printf '%s' "$TOKEN" | sha256sum | awk '{print $1}')"
```

Bind localhost and put TLS in front (see [Caddy](#caddy)). Do not log bearer tokens. Do not commit tokens, token hashes, or env files.

| Env | Meaning |
| --- | --- |
| `ADDR` | Listen address. Default `127.0.0.1:8781`. |
| `DATA` | Directory for the SQLite database (`sync.db`). Required. |
| `UNSCROLL_SYNC_TOKEN_SHA256` | Optional lowercase hex SHA-256 of the legacy vault bearer token. Required only to migrate blobs that predate accounts. On startup the server ensures that legacy account row exists. |
| `UNSCROLL_SYNC_TOKENS_FILE` | Optional file of extra SHA-256 hex digests, one per line (`#` comments allowed). When the legacy account row exists, these digests authenticate as that account. |

#### systemd (user unit)

Install the binary, put `DATA` and `ADDR` in an env file (mode `0600`), then:

```ini
# ~/.config/systemd/user/unscroll-sync.service
[Unit]
Description=Unscroll sync server

[Service]
ExecStart=/usr/local/bin/unscroll-sync-server
EnvironmentFile=%h/.config/unscroll-sync.env
WorkingDirectory=%h/.local/share/unscroll-sync
Restart=on-failure

[Install]
WantedBy=default.target
```

`DATA` in the env file is what selects the database directory. `WorkingDirectory` is only the process cwd.

```bash
systemctl --user daemon-reload
systemctl --user enable --now unscroll-sync.service
```

#### Docker

Image build, volume, and the legacy hash flag are in [Docker](#docker).

#### Caddy

Leave the server on `127.0.0.1:8781`. Caddy terminates TLS and proxies to it:

```caddy
sync.example.com {
	reverse_proxy 127.0.0.1:8781
}
```

Point the app at `https://sync.example.com`. Do not publish port 8781 on the public internet.

## HTTP

Each account is one vault. Blob ids are unique per account. A token sees only its own blobs.

- `GET /healthz` — no auth, `{"ok":true,"protocol":1}`
- `POST /v1/accounts` — no auth, no body. Creates an anonymous account. `201` `{"token":"<raw>","protocol":1,"accountId":"<id>"}`. The raw token is shown once; the server stores only its SHA-256 hex. Generation is 32 random bytes, base64url. Limited to **5 creations per hour per IP**, and the per-minute limit below also applies.
- `DELETE /v1/accounts` — Bearer auth. Deletes that account and its blobs. `204`.
- `GET /v1/blobs` — list `{id, etag, bytes, updatedAt}` for the caller's vault
- `GET /v1/blobs/{id}` — raw stored bytes, strong `ETag`
- `PUT /v1/blobs/{id}` — `If-None-Match: *` to create, `If-Match: "<etag>"` to replace; else `412`. Max body 2_000_000 bytes. Per account: at most 50 blobs and 10_000_000 total body bytes (`413`, body `quota`). Success `204` with new `ETag`
- `OPTIONS` — CORS preflight (`Access-Control-Allow-Origin: *`, methods `GET, PUT, POST, DELETE, OPTIONS`)

`Authorization: Bearer <token>` on blob routes and `DELETE /v1/accounts`. Lookup is the `accounts` table (constant-time compare of the SHA-256 hex). The env hash and tokens file are only a bootstrap for the legacy account. ETag value is `"sha256-"` plus lowercase hex SHA-256 of the stored body. Blob ids match `^[a-z][a-z0-9._-]{0,127}$`. Rate limit: 60 requests per minute per client IP and per token (`429`).

If `UNSCROLL_SYNC_TOKEN_SHA256` is still set, a restart recreates the legacy account row after `DELETE`. Deleted blobs are not restored. Unset the env var to retire that vault.

## Docker

```bash
docker build -t unscroll-sync-server .
docker run --rm -p 127.0.0.1:8781:8781 \
  -e ADDR=0.0.0.0:8781 \
  -v unscroll-sync:/data unscroll-sync-server
```

Add `-e UNSCROLL_SYNC_TOKEN_SHA256=...` only for the legacy vault or to migrate an existing database. Do not bake a real token or hash into the image.

## Interop

`scripts/interop_test.mjs` checks the shared AES-GCM test vector, then seals a desktop usage blob and a settings blob and reads them back from a live server (`UNSCROLL_SYNC_URL`, default `https://sync.unscroll.elimbi.com`). Desktop seal/open comes from `../desktop/src/main/sync/crypto.ts`; extension seal/open comes from `../extension/src/edition/sync-codec.ts`. When `JAVA_HOME` points to a JDK 17, the script also runs the Android `SyncCryptoTest`.

The script needs `UNSCROLL_SYNC_TOKEN` in the environment, or `UNSCROLL_SYNC_ENV` pointing to an env file that holds it. Do not print or commit that token.

```bash
set -a && source /path/to/unscroll-sync.env && set +a
npx tsx scripts/interop_test.mjs
```
