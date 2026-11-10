import { storageGet, storageSet } from '../shared/chrome-call.ts';
import { normalizeDomainInput } from '../shared/domain.ts';
import type { CategoryId, FocusLogEntry } from './types.ts';
import { isRawKey, normalizeSyncUrl, validPassphrase, validToken } from './sync-codec.ts';

export const PRO_DATA_KEY = 'proData';

export type SyncSecretKind = 'raw' | 'passphrase';

export interface SyncSettings {
  enabled: boolean;
  url: string;
  token: string;
  /**
   * Legacy mirror of `secret` when `secretKind` is `passphrase`.
   * Empty when the secret is a raw key, so the key is not stored as a passphrase.
   */
  passphrase: string;
  secretKind: SyncSecretKind;
  /** Base64url 32-byte key, or the passphrase. Never sent to the server. */
  secret: string;
  /** Stable install id. Lowercase so `device.browser.<id>` matches the blob id grammar. */
  deviceId: string;
  /** Usage blob revision last applied on this device. */
  revision: number;
  /** Settings blob revision last applied on this device. */
  settingsRevision: number;
  lastPushedHash: string;
  lastSettingsHash: string;
  lastSyncAt: string | null;
  lastError: string | null;
}

export interface ProData {
  hourly: Record<string, number[]>;
  categories: Record<string, CategoryId>;
  focusLog: FocusLogEntry[];
  sync: SyncSettings;
}

const CATEGORIES: readonly CategoryId[] = ['productive', 'social', 'entertainment', 'other'];

export function defaultSyncSettings(): SyncSettings {
  return {
    enabled: false,
    url: '',
    token: '',
    passphrase: '',
    secretKind: 'raw',
    secret: '',
    deviceId: '',
    revision: 0,
    settingsRevision: 0,
    lastPushedHash: '',
    lastSettingsHash: '',
    lastSyncAt: null,
    lastError: null,
  };
}

export function defaultProData(): ProData {
  return { hourly: {}, categories: {}, focusLog: [], sync: defaultSyncSettings() };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

function isCategory(value: unknown): value is CategoryId {
  return typeof value === 'string' && (CATEGORIES as readonly string[]).includes(value);
}

export function sanitizeHourly(value: unknown): Record<string, number[]> {
  const rec = asRecord(value);
  if (!rec) return {};
  const out: Record<string, number[]> = {};
  for (const [key, row] of Object.entries(rec)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(key) || !Array.isArray(row)) continue;
    const hours = Array.from({ length: 24 }, (_, index) => {
      const item = row[index];
      if (typeof item !== 'number' || !Number.isFinite(item) || item <= 0) return 0;
      return Math.min(86_400, Math.floor(item));
    });
    if (hours.some((hour) => hour > 0)) out[key] = hours;
  }
  return out;
}

export function sanitizeCategories(value: unknown): Record<string, CategoryId> {
  const rec = asRecord(value);
  if (!rec) return {};
  const out: Record<string, CategoryId> = {};
  for (const [key, category] of Object.entries(rec)) {
    const domain = normalizeDomainInput(key);
    if (!domain || !isCategory(category) || category === 'other') continue;
    out[domain] = category;
  }
  return out;
}

export function sanitizeFocusLog(value: unknown): FocusLogEntry[] {
  if (!Array.isArray(value)) return [];
  const out: FocusLogEntry[] = [];
  for (const item of value) {
    const rec = asRecord(item);
    if (!rec) continue;
    const startedAt = rec.startedAt;
    const endsAt = rec.endsAt;
    const minutes = rec.minutes;
    if (typeof startedAt !== 'number' || typeof endsAt !== 'number' || typeof minutes !== 'number') continue;
    if (!Number.isFinite(startedAt) || !Number.isFinite(endsAt) || endsAt < startedAt) continue;
    out.push({
      startedAt,
      endsAt,
      minutes: Math.min(240, Math.max(1, Math.round(minutes))),
      strict: rec.strict === true,
      completed: rec.completed === true,
    });
  }
  return out.slice(-180);
}

function usableSecret(kind: SyncSecretKind, secret: string): boolean {
  if (kind === 'raw') return isRawKey(secret);
  return validPassphrase(secret);
}

function readSecret(rec: Record<string, unknown>): { secretKind: SyncSecretKind; secret: string } {
  const secret = typeof rec.secret === 'string' && rec.secret.length <= 200 ? rec.secret : '';
  if (rec.secretKind === 'raw' && isRawKey(secret)) return { secretKind: 'raw', secret };
  if (rec.secretKind === 'passphrase' && validPassphrase(secret)) return { secretKind: 'passphrase', secret };
  const legacy = typeof rec.passphrase === 'string' ? rec.passphrase.slice(0, 200) : '';
  if (validPassphrase(legacy)) return { secretKind: 'passphrase', secret: legacy };
  const key = typeof rec.key === 'string' ? rec.key : '';
  if (isRawKey(key)) return { secretKind: 'raw', secret: key };
  return { secretKind: 'raw', secret: '' };
}

export function sanitizeSyncSettings(value: unknown): SyncSettings {
  const base = defaultSyncSettings();
  const rec = asRecord(value);
  if (!rec) return base;
  const rawUrl = typeof rec.url === 'string' ? rec.url.trim().slice(0, 300) : '';
  const normalizedUrl = normalizeSyncUrl(rawUrl);
  const token = typeof rec.token === 'string' ? rec.token.trim().slice(0, 512) : '';
  const stored = readSecret(rec);
  const deviceId = typeof rec.deviceId === 'string' && /^[a-z0-9_-]{8,64}$/.test(rec.deviceId) ? rec.deviceId : '';
  const revision = typeof rec.revision === 'number' && Number.isInteger(rec.revision) ? Math.max(0, rec.revision) : 0;
  const settingsRevision = typeof rec.settingsRevision === 'number' && Number.isInteger(rec.settingsRevision) ? Math.max(0, rec.settingsRevision) : 0;
  const hash = (item: unknown) => (typeof item === 'string' && /^[0-9a-f]{64}$/.test(item) ? item : '');
  const lastPushedHash = hash(rec.lastPushedHash);
  const lastSettingsHash = hash(rec.lastSettingsHash);
  const lastSyncAt = typeof rec.lastSyncAt === 'string' && rec.lastSyncAt.length <= 40 ? rec.lastSyncAt : null;
  const lastError = typeof rec.lastError === 'string' ? rec.lastError.slice(0, 80) : null;
  const cleanToken = validToken(token) ? token : '';
  const enabled = rec.enabled === true && normalizedUrl != null && cleanToken.length > 0 && usableSecret(stored.secretKind, stored.secret);
  return {
    enabled,
    url: normalizedUrl ?? rawUrl,
    token: cleanToken,
    passphrase: stored.secretKind === 'passphrase' ? stored.secret : '',
    secretKind: stored.secretKind,
    secret: stored.secret,
    deviceId,
    revision,
    settingsRevision,
    lastPushedHash,
    lastSettingsHash,
    lastSyncAt,
    lastError,
  };
}

export function sanitizeProData(value: unknown): ProData {
  const rec = asRecord(value);
  if (!rec) return defaultProData();
  return {
    hourly: sanitizeHourly(rec.hourly),
    categories: sanitizeCategories(rec.categories),
    focusLog: sanitizeFocusLog(rec.focusLog),
    sync: sanitizeSyncSettings(rec.sync),
  };
}

export async function readProData(): Promise<ProData> {
  const raw = await storageGet(PRO_DATA_KEY);
  return sanitizeProData(raw[PRO_DATA_KEY]);
}

export async function writeProData(data: ProData): Promise<void> {
  await storageSet({ [PRO_DATA_KEY]: sanitizeProData(data) });
}
