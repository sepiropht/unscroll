import type { SettingsPlaintext, SyncPayload, SyncPlaintext, UsagePlaintext } from './types.ts';

export const SETTINGS_BLOB = 'settings';
const MAX_PLAINTEXT = 1_500_000;

export interface PbkdfEnvelope {
  version: 1;
  alg: 'AES-GCM';
  kdf: 'PBKDF2-SHA-256';
  iterations: number;
  salt: string;
  iv: string;
  ciphertext: string;
}

/** AES key is the stored 32 bytes. No salt. */
export interface RawEnvelope {
  version: 1;
  alg: 'AES-GCM';
  kdf: 'raw';
  iterations: 0;
  iv: string;
  ciphertext: string;
}

export type Envelope = PbkdfEnvelope | RawEnvelope;

/** A passphrase string, or the base64url form of a 32-byte key. */
export type SyncSecret = string | { kind: 'raw'; key: string };

export const DEFAULT_ITERATIONS = 150_000;
const MAX_ITERATIONS = 400_000;
const KEY_BYTES = 32;

/** Hosted sync server. The field stays editable for a self-hosted origin. */
export const DEFAULT_SYNC_URL = 'https://sync.unscroll.elimbi.com';

export type SyncPlan = 'push' | 'pull' | 'idle' | 'conflict';

export function normalizeSyncUrl(input: string): string | null {
  const trimmed = input.trim();
  if (!trimmed || trimmed.length > 300) return null;
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  if (url.username || url.password || url.hash || url.search) return null;
  const path = url.pathname.replace(/\/+$/, '');
  return `${url.origin}${path}`;
}

export function validDeviceId(deviceId: string): boolean {
  return /^[a-z0-9_-]{8,64}$/.test(deviceId);
}

export function usageBlobId(deviceId: string): string {
  if (!validDeviceId(deviceId)) throw new Error('sync-http');
  return `device.browser.${deviceId}`;
}

export function syncRoot(base: string): string {
  let path = base.replace(/\/+$/, '');
  if (path.endsWith('/v1/snapshot')) path = path.slice(0, -'/v1/snapshot'.length).replace(/\/+$/, '');
  if (path.endsWith('/v1/blobs')) path = path.slice(0, -'/v1/blobs'.length).replace(/\/+$/, '');
  if (path.endsWith('/v1/accounts')) path = path.slice(0, -'/v1/accounts'.length).replace(/\/+$/, '');
  return path;
}

/** `BASE/v1/blobs/{id}`. A base that already ends in `/v1/blobs` is not doubled. */
export function blobUrl(base: string, id: string): string {
  if (!/^[a-z][a-z0-9._-]{0,127}$/.test(id)) throw new Error('sync-http');
  return `${syncRoot(base)}/v1/blobs/${encodeURIComponent(id)}`;
}

/** `BASE/v1/accounts`. Null when the base URL is not a sync origin. */
export function accountUrl(base: string): string | null {
  const normalized = normalizeSyncUrl(base);
  if (!normalized) return null;
  return `${syncRoot(normalized)}/v1/accounts`;
}

export function validToken(token: string): boolean {
  return token.length >= 8 && token.length <= 512 && /^[\x21-\x7e]+$/.test(token);
}

export function validPassphrase(passphrase: string): boolean {
  return passphrase.length >= 8 && passphrase.length <= 200;
}

/** Per-blob last-writer-wins. `remoteBodyChanged` is a same-revision body this device did not write. */
export function planSync(input: {
  direction: 'auto' | 'push' | 'pull';
  localDirty: boolean;
  localRevision: number;
  remoteRevision: number | null;
  remoteBodyChanged?: boolean;
}): SyncPlan {
  if (input.direction === 'push') return 'push';
  if (input.direction === 'pull') return input.remoteRevision == null ? 'idle' : 'pull';
  if (input.remoteRevision == null) {
    return input.localDirty || input.localRevision === 0 ? 'push' : 'idle';
  }
  if (input.remoteBodyChanged === true && input.remoteRevision === input.localRevision) {
    return input.localDirty ? 'conflict' : 'pull';
  }
  if (!input.localDirty && input.remoteRevision > input.localRevision) return 'pull';
  if (input.localDirty && input.remoteRevision > input.localRevision) return 'conflict';
  if (input.localDirty) return 'push';
  return 'idle';
}

function nextRevision(local: number, remote: number | null): number {
  return Math.max(local, remote ?? 0) + 1;
}

/** Shared settings write. Unknown keys already on `body` stay. */
export function assembleSettings(input: {
  updatedAt: string;
  body: Record<string, unknown>;
  localRevision: number;
  remoteRevision: number | null;
}): SettingsPlaintext {
  return {
    version: 1,
    kind: 'settings',
    revision: nextRevision(input.localRevision, input.remoteRevision),
    updatedAt: input.updatedAt,
    body: structuredClone(input.body),
  };
}

/** This device's usage blob. `snapshot` is the extension SyncPayload. */
export function assembleUsage(input: {
  updatedAt: string;
  deviceId: string;
  snapshot: SyncPayload;
  localRevision: number;
  remoteRevision: number | null;
}): UsagePlaintext {
  return {
    version: 1,
    kind: 'usage',
    revision: nextRevision(input.localRevision, input.remoteRevision),
    updatedAt: input.updatedAt,
    platform: 'browser',
    deviceId: input.deviceId,
    snapshot: structuredClone(input.snapshot) as unknown as Record<string, unknown>,
  };
}

export function canonical(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
  if (Array.isArray(value)) return `[${value.map((item) => canonical(item)).join(',')}]`;
  const rec = value as Record<string, unknown>;
  const keys = Object.keys(rec)
    .filter((key) => rec[key] !== undefined)
    .sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${canonical(rec[key])}`).join(',')}}`;
}

export async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Strong validator for the blob ETag, without the surrounding quotes. */
export function parseEtag(header: string | null): string | null {
  if (!header) return null;
  let trimmed = header.trim();
  if (trimmed.startsWith('W/')) return null;
  if (trimmed.startsWith('"') && trimmed.endsWith('"')) trimmed = trimmed.slice(1, -1);
  const bare = trimmed.toLowerCase();
  if (!/^sha256-[0-9a-f]{64}$/.test(bare)) return null;
  return bare;
}

export async function etagFor(body: string): Promise<string> {
  return `sha256-${await sha256Hex(body)}`;
}

export function quoteEtag(etag: string): string {
  if (!/^sha256-[0-9a-f]{64}$/.test(etag)) throw new Error('sync-http');
  return `"${etag}"`;
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i] ?? 0);
  return btoa(binary);
}

export function base64ToBytes(value: string): Uint8Array {
  const binary = atob(value);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

/** Unpadded base64url. Pairing links and raw keys use this alphabet. */
export function bytesToBase64Url(bytes: Uint8Array): string {
  return bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function base64UrlToBytes(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length > 1200) return null;
  const padded = value + '='.repeat((4 - (value.length % 4)) % 4);
  try {
    const bytes = base64ToBytes(padded.replace(/-/g, '+').replace(/_/g, '/'));
    if (bytes.length === 0 || bytesToBase64Url(bytes) !== value) return null;
    return bytes;
  } catch {
    return null;
  }
}

const RAW_KEY_RE = /^[A-Za-z0-9_-]{43}$/;

export function isRawKey(value: string): boolean {
  if (!RAW_KEY_RE.test(value)) return false;
  const bytes = base64UrlToBytes(value);
  return bytes != null && bytes.length === KEY_BYTES;
}

export function generateRawKey(): string {
  return bytesToBase64Url(crypto.getRandomValues(new Uint8Array(KEY_BYTES)));
}

function asBuffer(bytes: Uint8Array): BufferSource {
  return bytes as unknown as BufferSource;
}

async function deriveKey(passphrase: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: asBuffer(salt), iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

async function importAes(bytes: Uint8Array, usage: 'encrypt' | 'decrypt'): Promise<CryptoKey> {
  return crypto.subtle.importKey('raw', asBuffer(bytes), { name: 'AES-GCM' }, false, [usage]);
}

function rawKeyBytes(key: string): Uint8Array | null {
  if (!isRawKey(key)) return null;
  return base64UrlToBytes(key);
}

async function sealRaw(plaintext: Uint8Array, key: string, iv: Uint8Array): Promise<RawEnvelope> {
  const bytes = rawKeyBytes(key);
  if (!bytes) throw new Error('sync-http');
  const cryptoKey = await importAes(bytes, 'encrypt');
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: asBuffer(iv) }, cryptoKey, asBuffer(plaintext));
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'raw',
    iterations: 0,
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(cipher)),
  };
}

export async function seal(
  plaintext: unknown,
  secret: SyncSecret,
  iterations = DEFAULT_ITERATIONS,
  material?: { salt?: Uint8Array; iv?: Uint8Array },
): Promise<Envelope> {
  const plain = new TextEncoder().encode(JSON.stringify(plaintext));
  if (plain.byteLength > MAX_PLAINTEXT) throw new Error('sync-http');
  const iv = material?.iv ?? crypto.getRandomValues(new Uint8Array(12));
  if (iv.length !== 12) throw new Error('sync-http');
  if (typeof secret !== 'string') return sealRaw(plain, secret.key, iv);
  if (!validPassphrase(secret)) throw new Error('sync-http');
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > MAX_ITERATIONS) throw new Error('sync-http');
  const salt = material?.salt ?? crypto.getRandomValues(new Uint8Array(16));
  if (salt.length !== 16) throw new Error('sync-http');
  const key = await deriveKey(secret, salt, iterations);
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv: asBuffer(iv) }, key, asBuffer(plain));
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'PBKDF2-SHA-256',
    iterations,
    salt: bytesToBase64(salt),
    iv: bytesToBase64(iv),
    ciphertext: bytesToBase64(new Uint8Array(cipher)),
  };
}

function readRaw(rec: Record<string, unknown>): RawEnvelope | null {
  if (rec.iterations != null && rec.iterations !== 0) return null;
  if (rec.salt != null && rec.salt !== '') return null;
  if (typeof rec.iv !== 'string' || typeof rec.ciphertext !== 'string') return null;
  if (rec.iv.length === 0 || rec.iv.length > 64 || rec.ciphertext.length === 0 || rec.ciphertext.length > 2_000_000) return null;
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'raw',
    iterations: 0,
    iv: rec.iv,
    ciphertext: rec.ciphertext,
  };
}

function readPbkdf(rec: Record<string, unknown>): PbkdfEnvelope | null {
  const iterations = rec.iterations;
  if (typeof iterations !== 'number' || !Number.isInteger(iterations) || iterations < 1 || iterations > MAX_ITERATIONS) return null;
  if (typeof rec.salt !== 'string' || typeof rec.iv !== 'string' || typeof rec.ciphertext !== 'string') return null;
  if (rec.salt.length === 0 || rec.salt.length > 128 || rec.iv.length === 0 || rec.iv.length > 64) return null;
  if (rec.ciphertext.length === 0 || rec.ciphertext.length > 2_000_000) return null;
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'PBKDF2-SHA-256',
    iterations,
    salt: rec.salt,
    iv: rec.iv,
    ciphertext: rec.ciphertext,
  };
}

function readEnvelope(value: unknown): Envelope | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const rec = value as Record<string, unknown>;
  if (rec.version !== 1 || rec.alg !== 'AES-GCM') return null;
  if (rec.kdf === 'raw') return readRaw(rec);
  if (rec.kdf === 'PBKDF2-SHA-256') return readPbkdf(rec);
  return null;
}

function readStamp(rec: Record<string, unknown>): { revision: number; updatedAt: string } | null {
  if (rec.version !== 1) return null;
  if (typeof rec.revision !== 'number' || !Number.isInteger(rec.revision) || rec.revision < 0 || rec.revision > 1_000_000_000) return null;
  if (typeof rec.updatedAt !== 'string' || rec.updatedAt.length === 0 || rec.updatedAt.length > 40) return null;
  return { revision: rec.revision, updatedAt: rec.updatedAt };
}

export function parseSettings(value: unknown): SettingsPlaintext | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const rec = value as Record<string, unknown>;
  const stamp = readStamp(rec);
  if (!stamp || rec.kind !== 'settings') return null;
  if (!rec.body || typeof rec.body !== 'object' || Array.isArray(rec.body)) return null;
  return { version: 1, kind: 'settings', ...stamp, body: rec.body as Record<string, unknown> };
}

export function parseUsage(value: unknown): UsagePlaintext | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const rec = value as Record<string, unknown>;
  const stamp = readStamp(rec);
  if (!stamp || rec.kind !== 'usage' || rec.platform !== 'browser') return null;
  if (typeof rec.deviceId !== 'string' || !validDeviceId(rec.deviceId)) return null;
  if (!rec.snapshot || typeof rec.snapshot !== 'object' || Array.isArray(rec.snapshot)) return null;
  return {
    version: 1,
    kind: 'usage',
    ...stamp,
    platform: 'browser',
    deviceId: rec.deviceId,
    snapshot: rec.snapshot as Record<string, unknown>,
  };
}

export function parsePlain(value: unknown, id: string): SyncPlaintext | null {
  if (id === SETTINGS_BLOB) return parseSettings(value);
  const usage = parseUsage(value);
  if (!usage || usageBlobId(usage.deviceId) !== id) return null;
  return usage;
}

async function aesKey(envelope: Envelope, secret: SyncSecret): Promise<CryptoKey> {
  if (envelope.kdf === 'raw') {
    if (typeof secret === 'string') throw new Error('bad-passphrase');
    const bytes = rawKeyBytes(secret.key);
    if (!bytes) throw new Error('bad-passphrase');
    return importAes(bytes, 'decrypt');
  }
  if (typeof secret !== 'string' || !validPassphrase(secret)) throw new Error('bad-passphrase');
  return deriveKey(secret, base64ToBytes(envelope.salt), envelope.iterations);
}

/** Decrypt. Schema checks are `parseSettings` / `parseUsage` so the fixed test vector still opens. */
export async function open(value: unknown, secret: SyncSecret): Promise<unknown> {
  const envelope = readEnvelope(value);
  if (!envelope) throw new Error('sync-http');
  try {
    const iv = base64ToBytes(envelope.iv);
    const cipher = base64ToBytes(envelope.ciphertext);
    if (iv.length !== 12 || cipher.length <= 16) throw new Error('sync-http');
    if (envelope.kdf === 'PBKDF2-SHA-256' && base64ToBytes(envelope.salt).length !== 16) throw new Error('sync-http');
    const key = await aesKey(envelope, secret);
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: asBuffer(iv) }, key, asBuffer(cipher));
    if (plain.byteLength > MAX_PLAINTEXT) throw new Error('sync-http');
    try {
      return JSON.parse(new TextDecoder().decode(plain)) as unknown;
    } catch {
      throw new Error('sync-http');
    }
  } catch (error) {
    if (error instanceof Error && (error.message === 'sync-http' || error.message === 'bad-passphrase')) throw error;
    throw new Error('bad-passphrase');
  }
}
