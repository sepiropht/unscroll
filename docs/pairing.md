# Unscroll pairing link (protocol v1)

Android, the browser extension, and the desktop app share one pairing URI. One open-source edition. The Android app includes the sync client and pairs with `unscroll://pair`.

## Accounts

The default server is `https://sync.unscroll.elimbi.com`. A client that enables sync with no token calls `POST {base}/v1/accounts` with no body and no `Authorization` header. The server answers `201` with a bearer token once and stores only the SHA-256 hex of that token. The client then generates a random **32-byte** key and keeps it locally. New envelopes from that account use the raw key. The server stores ciphertext only.

Each token is one account. Blob ids (`settings`, `device.<platform>.<deviceId>`) are unique inside that account, so two people on the same server do not share a vault.

Passphrase mode remains for vaults that were created before raw keys. Those envelopes still use PBKDF2-SHA-256 and a per-envelope salt. A raw key cannot open them, and a passphrase cannot open a raw envelope. New auto-created accounts write `kdf: "raw"` only.

## Crypto in the link

| `m` | `k` | Local secret |
| --- | --- | --- |
| `raw` (default for new accounts) | unpadded base64url of the **32-byte key** | stored as that key |
| `pbkdf2` | unpadded base64url of the UTF-8 passphrase | stored as that passphrase |

Writers always emit `m`. Readers treat a missing `m` as `raw` when `k` decodes to exactly 32 bytes, and reject it otherwise. A passphrase whose UTF-8 is 32 bytes must be sent with `m=pbkdf2`, or a reader will take those bytes as a raw key.

Passphrase rules stay the ones in the sync protocol: 8–200 UTF-16 code units, no Unicode normalization.

## URI

```text
unscroll://pair?v=1&u=<percent-encoded base URL>&t=<percent-encoded bearer token>&k=<base64url material>&m=raw|pbkdf2
```

| Param | Rule |
| --- | --- |
| `v` | exactly `1`. Any other version is rejected. |
| `u` | Sync base URL, `encodeURIComponent` of the normalized base. `https` is preferred. `http` is allowed. No userinfo, query, or fragment. No trailing slash. At most 300 characters. |
| `t` | Bearer token, `encodeURIComponent`. 8–512 printable ASCII (`0x21`–`0x7E`), no spaces. |
| `k` | Standard base64url, alphabet `A–Z a–z 0–9 - _`, **no** `=` padding. Put the raw base64url in the query value. Do not percent-encode it. For `m=raw` it is the 32-byte key (43 characters). For `m=pbkdf2` it is the UTF-8 passphrase. |
| `m` | `raw` or `pbkdf2`. Writers always send it. |

Writers emit the parameters in the order `v`, `u`, `t`, `k`, `m`. Readers accept any order. Unknown query keys are ignored. A duplicated `v`, `u`, `t`, `k`, or `m` is rejected. The host is `pair` (`unscroll://pair?...`). A hash, a userinfo, a port, or an extra path is rejected.

A reader trims the paste and drops wrapping whitespace. The URI alphabet has none, so a line-wrapped paste still decodes. Anything that is not this URI, including a bad scheme or a bad length, decodes to nothing.

Example, raw key of 32×`0x11` (not a key to use):

```text
unscroll://pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=ERERERERERERERERERERERERERERERERERERERERERE&m=raw
```

Example, legacy passphrase `secret-passphrase`:

```text
unscroll://pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=c2VjcmV0LXBhc3NwaHJhc2U&m=pbkdf2
```

The same `k` without `m=pbkdf2` does not decode: it is not 32 bytes.

## After a successful paste or scan

1. Save sync config with `enabled=true`, the normalized URL, the token, and the raw key or the passphrase.
2. Run the same action as **Sync now**.
3. Show success or the sync error. Do not log the token, the key, the passphrase, or the link.

Changing the base URL still clears local sync revision and ETag caches. Local usage data stays.

## Security

The QR code and the link contain the base URL, the bearer token, and the encryption key (or the passphrase). Anyone who obtains them can read and write the vault. Treat them like a password. Do not send them in chat, email, or a screenshot you do not control. Do not put them in logs.

## Android

The app registers an intent filter for `unscroll://pair`. A view intent, a scanned code, or a pasted link opens **Sync**, saves the account, and sync runs.

**Scan a QR code** uses the phone camera (`CAMERA`, not required to install). Decoding is ZXing embedded in the app. It does not use a Google account and it does not upload the picture.

**Pair a device** draws the QR on the phone with the same library. It does not call a QR web service. The screen shows the link, a copy button, and the full-access warning. **Paste a link** joins another device’s vault.

The envelope format is [sync-protocol.md](./sync-protocol.md).

## Desktop and browser

The desktop app and the extension draw the QR code locally (`qrcode` package) and show the same copyable `unscroll://pair` link. Neither calls a QR web service.
