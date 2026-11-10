import type { ClientResponse } from '../shared/messages.ts';
import { domainListOf, normalizeDomainInput } from '../shared/domain.ts';
import {
  sanitizeFocus,
  sanitizeLimits,
  sanitizeSchedules,
  sanitizeSettings,
  sanitizeStats,
} from '../shared/state.ts';
import { createSyncAccount, shouldProvisionAccount } from './account.ts';
import { billingLink, fetchPlan } from './billing.ts';
import { HttpSyncProvider } from './http-sync.ts';
import { decodePairingLink, PAIRING_LINK_MAX } from './pairing.ts';
import {
  defaultProData,
  readProData,
  sanitizeCategories,
  sanitizeFocusLog,
  sanitizeHourly,
  sanitizeSyncSettings,
  writeProData,
  type ProData,
  type SyncSettings,
} from './pro-data.ts';
import { mountProOptions, refreshProOptions } from './pro-ui.ts';
import { addHourly } from './reports.ts';
import {
  DEFAULT_SYNC_URL,
  SETTINGS_BLOB,
  assembleSettings,
  assembleUsage,
  canonical,
  generateRawKey,
  isRawKey,
  normalizeSyncUrl,
  planSync,
  sha256Hex,
  usageBlobId,
  validDeviceId,
  validPassphrase,
  validToken,
  type SyncSecret,
} from './sync-codec.ts';
import type {
  BackgroundBridge,
  CategoryId,
  EditionModule,
  FocusLogEntry,
  OptionsMount,
  PushPrecondition,
  SettingsPlaintext,
  SyncPayload,
  SyncPlaintext,
  UsagePlaintext,
  UserDataPatch,
} from './types.ts';

export const edition: 'oss' | 'pro' = 'pro';
export const features: { sync: boolean; advancedReports: boolean } = {
  sync: true,
  advancedReports: true,
};

let bridge: BackgroundBridge | null = null;
let listening = false;

function sealSecret(sync: SyncSettings): SyncSecret | null {
  if (sync.secretKind === 'passphrase') return validPassphrase(sync.secret) ? sync.secret : null;
  return isRawKey(sync.secret) ? { kind: 'raw', key: sync.secret } : null;
}

function providerFor(sync: SyncSettings): HttpSyncProvider | null {
  if (!sync.enabled) return null;
  const base = normalizeSyncUrl(sync.url);
  const secret = sealSecret(sync);
  if (!base || !validToken(sync.token) || !secret) return null;
  return new HttpSyncProvider(base, sync.token, secret);
}

function newDeviceId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function ownedSettings(data: ProData): Record<string, unknown> {
  if (!bridge) return {};
  const state = bridge.getState();
  return {
    preferences: state.settings,
    limits: state.limits,
    schedules: state.schedules,
    blocklist: state.blocklist,
    whitelist: state.whitelist,
    categories: data.categories,
  };
}

function projectSettings(body: Record<string, unknown>): Record<string, unknown> {
  return {
    preferences: body.preferences,
    limits: body.limits,
    schedules: body.schedules,
    blocklist: body.blocklist,
    whitelist: body.whitelist,
    ...(Array.isArray(body.categories) ? {} : { categories: body.categories }),
  };
}

function settingsBody(data: ProData, remote: Record<string, unknown> | null): Record<string, unknown> {
  const body: Record<string, unknown> = remote ? structuredClone(remote) : {};
  const owned = ownedSettings(data);
  Object.assign(body, owned);
  if (remote && Array.isArray(remote.categories)) body.categories = remote.categories;
  return body;
}

function payloadFrom(data: ProData): SyncPayload | null {
  if (!bridge) return null;
  const state = bridge.getState();
  return {
    settings: state.settings,
    limits: state.limits,
    blocklist: state.blocklist,
    whitelist: state.whitelist,
    schedules: state.schedules,
    focus: state.focus,
    stats: state.stats,
    hourly: data.hourly,
    categories: data.categories,
    focusLog: data.focusLog,
  };
}

function userPatch(body: Record<string, unknown>): UserDataPatch {
  const current = bridge?.getState();
  const rec = body;
  return {
    settings: 'settings' in rec ? sanitizeSettings(rec.settings) : (current?.settings ?? sanitizeSettings(undefined)),
    limits: 'limits' in rec && Array.isArray(rec.limits) ? sanitizeLimits(rec.limits) : (current?.limits ?? []),
    blocklist: 'blocklist' in rec && Array.isArray(rec.blocklist) ? domainListOf(rec.blocklist) : (current?.blocklist ?? []),
    whitelist: 'whitelist' in rec && Array.isArray(rec.whitelist) ? domainListOf(rec.whitelist) : (current?.whitelist ?? []),
    schedules: 'schedules' in rec && Array.isArray(rec.schedules) ? sanitizeSchedules(rec.schedules) : (current?.schedules ?? []),
    focus: 'focus' in rec ? sanitizeFocus(rec.focus) : (current?.focus ?? null),
    stats: 'stats' in rec ? sanitizeStats(rec.stats) : (current?.stats ?? {}),
  };
}

function mapError(error: unknown): string {
  if (error instanceof Error) {
    if (
      error.message === 'bad-passphrase' ||
      error.message === 'sync-auth' ||
      error.message === 'sync-http' ||
      error.message === 'sync-conflict' ||
      error.message === 'sync-payment'
    ) {
      return error.message;
    }
    if (error.name === 'TimeoutError' || error.name === 'AbortError') return 'sync-network';
  }
  return 'sync-network';
}

function browserBodyChanged(localHash: string, lastPushedHash: string, remoteHash: string): boolean {
  return remoteHash !== lastPushedHash && remoteHash !== localHash;
}

async function remember(data: ProData, sync: SyncSettings): Promise<void> {
  try {
    await writeProData({ ...data, sync });
  } catch {
    // The sync result is still returned to the page. The status line updates on the next successful write.
  }
}

async function setAlarm(enabled: boolean): Promise<void> {
  await new Promise<void>((resolve) => {
    if (enabled) chrome.alarms.create('pro-sync', { periodInMinutes: 15 }, () => resolve());
    else chrome.alarms.clear('pro-sync', () => resolve());
  });
}

function isSettings(doc: SyncPlaintext | null | undefined): doc is SettingsPlaintext {
  return doc?.kind === 'settings';
}

function isUsage(doc: SyncPlaintext | null | undefined): doc is UsagePlaintext {
  return doc?.kind === 'usage';
}

async function runSync(direction: 'auto' | 'push' | 'pull', attempt = 0): Promise<ClientResponse> {
  const data = await loadPro();
  if (!data) return { ok: false, error: 'storage' };
  const provider = providerFor(data.sync);
  if (!provider) {
    const error = data.sync.enabled ? 'sync-config' : 'sync-disabled';
    await remember(data, { ...data.sync, lastError: error });
    return { ok: false, error };
  }
  let deviceId = data.sync.deviceId;
  if (!validDeviceId(deviceId)) {
    deviceId = newDeviceId();
    data.sync = { ...data.sync, deviceId };
    await remember(data, data.sync);
  }
  const payload = payloadFrom(data);
  if (!payload || !bridge) return { ok: false, error: 'sync-disabled' };
  const usageId = usageBlobId(deviceId);
  const localUsageHash = await sha256Hex(canonical(payload));
  const localSettingsHash = await sha256Hex(canonical(ownedSettings(data)));
  const usageDirty = localUsageHash !== data.sync.lastPushedHash;
  const settingsDirty = localSettingsHash !== data.sync.lastSettingsHash;
  let remoteSettings: Awaited<ReturnType<HttpSyncProvider['pull']>> = null;
  let remoteUsage: Awaited<ReturnType<HttpSyncProvider['pull']>> = null;
  try {
    remoteSettings = await provider.pull(SETTINGS_BLOB);
    remoteUsage = await provider.pull(usageId);
  } catch (error) {
    const code = mapError(error);
    await remember(data, { ...data.sync, lastError: code });
    return { ok: false, error: code };
  }
  const settingsDoc = isSettings(remoteSettings?.plaintext) ? remoteSettings.plaintext : null;
  const usageDoc = isUsage(remoteUsage?.plaintext) ? remoteUsage.plaintext : null;
  const settingsRemoteRev = settingsDoc?.revision ?? null;
  const usageRemoteRev = usageDoc?.revision ?? null;
  let settingsBodyChanged = false;
  if (settingsDoc && settingsRemoteRev === data.sync.settingsRevision) {
    const remoteHash = await sha256Hex(canonical(projectSettings(settingsDoc.body)));
    settingsBodyChanged = browserBodyChanged(localSettingsHash, data.sync.lastSettingsHash, remoteHash);
  }
  let usageBodyChanged = false;
  if (usageDoc && usageRemoteRev === data.sync.revision) {
    const remoteHash = await sha256Hex(canonical(usageDoc.snapshot));
    usageBodyChanged = browserBodyChanged(localUsageHash, data.sync.lastPushedHash, remoteHash);
  }
  if (direction === 'pull' && settingsRemoteRev == null && usageRemoteRev == null) {
    await remember(data, { ...data.sync, lastError: 'sync-empty' });
    return { ok: false, error: 'sync-empty' };
  }
  const settingsPlan = planSync({
    direction,
    localDirty: settingsDirty,
    localRevision: data.sync.settingsRevision,
    remoteRevision: settingsRemoteRev,
    remoteBodyChanged: settingsBodyChanged,
  });
  const usagePlan = planSync({
    direction,
    localDirty: usageDirty,
    localRevision: data.sync.revision,
    remoteRevision: usageRemoteRev,
    remoteBodyChanged: usageBodyChanged,
  });
  if (settingsPlan === 'conflict' || usagePlan === 'conflict') {
    await remember(data, { ...data.sync, lastError: 'sync-conflict' });
    return { ok: false, error: 'sync-conflict' };
  }
  if (settingsPlan === 'idle' && usagePlan === 'idle') {
    await remember(data, { ...data.sync, lastError: null });
    return { ok: true };
  }

  let working = data;
  const now = new Date().toISOString();
  if (usagePlan === 'pull' && usageDoc) {
    const patch = userPatch(usageDoc.snapshot);
    await bridge.replaceUserData(patch);
    const hourly = sanitizeHourly(usageDoc.snapshot.hourly);
    const categories = sanitizeCategories(usageDoc.snapshot.categories);
    const focusLog = sanitizeFocusLog(usageDoc.snapshot.focusLog);
    const fresh = await readProData();
    working = { ...fresh, hourly, categories, focusLog, sync: { ...fresh.sync, deviceId, revision: usageDoc.revision } };
  }
  if (settingsPlan === 'pull' && settingsDoc) {
    const patch = userPatch({
      settings: settingsDoc.body.preferences,
      limits: settingsDoc.body.limits,
      blocklist: settingsDoc.body.blocklist,
      whitelist: settingsDoc.body.whitelist,
      schedules: settingsDoc.body.schedules,
      focus: bridge.getState().focus,
      stats: bridge.getState().stats,
    });
    await bridge.replaceUserData(patch);
    const categories = Array.isArray(settingsDoc.body.categories) ? working.categories : sanitizeCategories(settingsDoc.body.categories);
    const fresh = await readProData();
    working = { ...fresh, hourly: working.hourly, categories, focusLog: working.focusLog, sync: { ...fresh.sync, deviceId, revision: working.sync.revision, settingsRevision: settingsDoc.revision } };
  }
  if (usagePlan === 'pull' || settingsPlan === 'pull') {
    const nextPayload = payloadFrom(working) ?? payload;
    const usageHash = usagePlan === 'pull' ? await sha256Hex(canonical(nextPayload)) : working.sync.lastPushedHash;
    const settingsHash = settingsPlan === 'pull' ? await sha256Hex(canonical(ownedSettings(working))) : working.sync.lastSettingsHash;
    working = {
      ...working,
      sync: {
        ...working.sync,
        deviceId,
        lastPushedHash: usageHash,
        lastSettingsHash: settingsHash,
        lastSyncAt: now,
        lastError: null,
      },
    };
    await writeProData(working);
  }
  if (settingsPlan !== 'push' && usagePlan !== 'push') return { ok: true };

  const pushPayload = payloadFrom(working) ?? payload;
  const updatedAt = new Date().toISOString();
  const settingsOut = assembleSettings({
    updatedAt,
    body: settingsBody(working, settingsDoc?.body ?? null),
    localRevision: working.sync.settingsRevision,
    remoteRevision: settingsRemoteRev,
  });
  const usageOut = assembleUsage({
    updatedAt,
    deviceId,
    snapshot: pushPayload,
    localRevision: working.sync.revision,
    remoteRevision: usageRemoteRev,
  });
  const settingsPre: PushPrecondition = remoteSettings ? { mode: 'match', etag: remoteSettings.etag } : { mode: 'create' };
  const usagePre: PushPrecondition = remoteUsage ? { mode: 'match', etag: remoteUsage.etag } : { mode: 'create' };
  try {
    if (settingsPlan === 'push') await provider.push(SETTINGS_BLOB, settingsOut, settingsPre);
    if (usagePlan === 'push') await provider.push(usageId, usageOut, usagePre);
  } catch (error) {
    const code = mapError(error);
    if (code === 'sync-conflict' && direction !== 'pull' && attempt < 1) return runSync(direction, attempt + 1);
    await remember(working, { ...working.sync, lastError: code });
    return { ok: false, error: code };
  }
  const fresh = await readProData();
  await writeProData({
    ...fresh,
    hourly: working.hourly,
    categories: working.categories,
    focusLog: working.focusLog,
    sync: {
      ...fresh.sync,
      deviceId,
      revision: usagePlan === 'push' ? usageOut.revision : working.sync.revision,
      settingsRevision: settingsPlan === 'push' ? settingsOut.revision : working.sync.settingsRevision,
      lastPushedHash: usagePlan === 'push' ? await sha256Hex(canonical(pushPayload)) : working.sync.lastPushedHash,
      lastSettingsHash: settingsPlan === 'push' ? await sha256Hex(canonical(ownedSettings(working))) : working.sync.lastSettingsHash,
      lastSyncAt: updatedAt,
      lastError: null,
    },
  });
  return { ok: true };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isCategory(value: unknown): value is CategoryId {
  return value === 'productive' || value === 'social' || value === 'entertainment' || value === 'other';
}

export function bindBackground(next: BackgroundBridge): void {
  bridge = next;
  if (listening) return;
  listening = true;
  chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name !== 'pro-sync' || !bridge) return;
    void bridge.enqueue(() => runSync('auto'));
  });
}

async function loadPro(): Promise<ProData | null> {
  try {
    return await readProData();
  } catch {
    return null;
  }
}

export async function onBoot(): Promise<void> {
  const data = await loadPro();
  if (data?.sync.enabled) await setAlarm(true);
}

export async function onUsage(host: string, endMs: number, seconds: number, dayStartHour: number): Promise<void> {
  if (!host || seconds <= 0) return;
  const data = await loadPro();
  if (!data) return;
  try {
    await writeProData({ ...data, hourly: addHourly(data.hourly, endMs, seconds, dayStartHour) });
  } catch {
    // Hourly detail is optional. The main counter keeps its own copy.
  }
}

export async function onFocusEnded(entry: FocusLogEntry): Promise<void> {
  const data = await loadPro();
  if (!data) return;
  try {
    await writeProData({ ...data, focusLog: [...data.focusLog, entry].slice(-180) });
  } catch {
    // The session still ends. The report can miss this one entry.
  }
}

export async function onReset(): Promise<void> {
  try {
    await writeProData(defaultProData());
  } catch {
    // The visible reset already succeeded. Pro extras stay until the next write.
  }
  await setAlarm(false);
}

const CONTROL = /[\u0000-\u001F\u007F]/;

function accountError(reason: string): string {
  if (reason === 'invalid') return 'sync-config';
  if (reason === 'auth') return 'sync-auth';
  if (reason === 'network') return 'sync-network';
  return 'sync-http';
}

function cursorReset(): Pick<SyncSettings, 'revision' | 'settingsRevision' | 'lastPushedHash' | 'lastSettingsHash' | 'lastSyncAt'> {
  return { revision: 0, settingsRevision: 0, lastPushedHash: '', lastSettingsHash: '', lastSyncAt: null };
}

async function persistSync(data: ProData, sync: SyncSettings): Promise<ClientResponse | null> {
  try {
    await writeProData({ ...data, sync });
  } catch {
    return { ok: false, error: 'storage' };
  }
  await setAlarm(sync.enabled);
  return null;
}

export async function onMessage(message: unknown): Promise<ClientResponse | null> {
  if (!isRecord(message) || typeof message.type !== 'string') return null;
  if (message.type === 'pro-category') {
    const domain = typeof message.domain === 'string' ? normalizeDomainInput(message.domain) : null;
    if (!domain || domain.length > 253) return { ok: false, error: 'invalid-domain' };
    const data = await loadPro();
    if (!data) return { ok: false, error: 'storage' };
    const categories = { ...data.categories };
    if (message.category == null) delete categories[domain];
    else if (isCategory(message.category) && message.category !== 'other') categories[domain] = message.category;
    else return { ok: false, error: 'invalid-domain' };
    try {
      await writeProData({ ...data, categories });
    } catch {
      return { ok: false, error: 'storage' };
    }
    return { ok: true };
  }
  if (message.type !== 'pro-sync') return null;
  const action = message.action;
  if (action === 'push' || action === 'pull' || action === 'now') {
    try {
      return await runSync(action === 'now' ? 'auto' : action);
    } catch {
      return { ok: false, error: 'storage' };
    }
  }
  if (action === 'pair') return applyPair(message);
  if (action === 'billing' || action === 'checkout' || action === 'portal') return billingAction(action, message);
  if (action !== 'save') return { ok: false, error: 'unknown' };
  return saveSync(message);
}

/** Hosted plan: status, or open Stripe Checkout / Customer Portal in a tab. */
async function billingAction(action: 'billing' | 'checkout' | 'portal', message: Record<string, unknown>): Promise<ClientResponse> {
  const data = await loadPro();
  if (!data) return { ok: false, error: 'storage' };
  const { url, token, enabled } = data.sync;
  if (action === 'billing') {
    if (!enabled || !token) return { ok: true, billing: { enabled: false } };
    return { ok: true, billing: await fetchPlan(url || DEFAULT_SYNC_URL, token) };
  }
  if (!token) return { ok: false, error: 'sync-config' };
  const plan = message.plan === 'yearly' ? 'yearly' : 'monthly';
  const link = await billingLink(url || DEFAULT_SYNC_URL, token, action, plan);
  if (!link.ok) return link;
  await new Promise<void>((resolve) => chrome.tabs.create({ url: link.url }, () => resolve()));
  return { ok: true };
}

async function saveSync(message: Record<string, unknown>): Promise<ClientResponse> {
  if (typeof message.url === 'string' && (message.url.length > 300 || CONTROL.test(message.url))) return { ok: false, error: 'sync-config' };
  if (typeof message.token === 'string' && (message.token.length > 512 || CONTROL.test(message.token))) return { ok: false, error: 'sync-config' };
  if (typeof message.passphrase === 'string' && (message.passphrase.length > 200 || CONTROL.test(message.passphrase))) return { ok: false, error: 'sync-config' };
  const data = await loadPro();
  if (!data) return { ok: false, error: 'storage' };
  const enabled = message.enabled === true;
  const typedUrl = typeof message.url === 'string' ? message.url.trim() : data.sync.url;
  const url = typedUrl.length > 0 ? typedUrl : DEFAULT_SYNC_URL;
  const clearToken = message.clearToken === true;
  const clearSecret = message.clearPassphrase === true;
  const typedToken = typeof message.token === 'string' ? message.token : '';
  const typedPass = typeof message.passphrase === 'string' ? message.passphrase : '';
  if (shouldProvisionAccount(data.sync, { enabled, token: typedToken, passphrase: typedPass, clearToken, clearPassphrase: clearSecret })) {
    const base = normalizeSyncUrl(url);
    if (!base) return { ok: false, error: 'sync-config' };
    const created = await createSyncAccount(base);
    if (!created.ok) return { ok: false, error: accountError(created.reason) };
    const sync = sanitizeSyncSettings({
      ...data.sync,
      enabled: true,
      url: base,
      token: created.token,
      secretKind: 'raw',
      secret: generateRawKey(),
      passphrase: '',
      lastError: null,
      ...cursorReset(),
    });
    const failed = await persistSync(data, sync);
    if (failed) return failed;
    try {
      await runSync('auto');
    } catch {
      return { ok: true, provisioned: true };
    }
    return { ok: true, provisioned: true };
  }
  let token = data.sync.token;
  if (clearToken) token = '';
  else if (typedToken.trim().length > 0) token = typedToken.trim();
  let secretKind = data.sync.secretKind;
  let secret = data.sync.secret;
  if (clearSecret) {
    secretKind = 'raw';
    secret = '';
  } else if (typedPass.length > 0) {
    secretKind = 'passphrase';
    secret = typedPass;
  }
  const base = typedUrl.length === 0 && !enabled ? '' : url;
  const normalized = base ? normalizeSyncUrl(base) : null;
  if (base && !normalized) return { ok: false, error: 'sync-config' };
  if (token && !validToken(token)) return { ok: false, error: 'sync-config' };
  if (secretKind === 'passphrase' && secret && !validPassphrase(secret)) return { ok: false, error: 'sync-config' };
  if (secretKind === 'raw' && secret && !isRawKey(secret)) return { ok: false, error: 'sync-config' };
  const materialChanged =
    normalized !== normalizeSyncUrl(data.sync.url) || token !== data.sync.token || secret !== data.sync.secret || secretKind !== data.sync.secretKind;
  const sync = sanitizeSyncSettings({
    ...data.sync,
    enabled,
    url: normalized ?? '',
    token,
    secretKind,
    secret,
    passphrase: secretKind === 'passphrase' ? secret : '',
    lastError: null,
    ...(materialChanged ? cursorReset() : {}),
  });
  if (enabled && !sync.enabled) return { ok: false, error: 'sync-config' };
  const failed = await persistSync(data, sync);
  if (failed) return failed;
  if (!sync.enabled) return { ok: true };
  try {
    return await runSync('auto');
  } catch {
    return { ok: false, error: 'storage' };
  }
}

async function applyPair(message: Record<string, unknown>): Promise<ClientResponse> {
  if (typeof message.link !== 'string' || message.link.length === 0 || message.link.length > PAIRING_LINK_MAX) {
    return { ok: false, error: 'pair-invalid' };
  }
  const decoded = decodePairingLink(message.link);
  if (!decoded) return { ok: false, error: 'pair-invalid' };
  const data = await loadPro();
  if (!data) return { ok: false, error: 'storage' };
  const secretKind = decoded.mode === 'raw' ? 'raw' : 'passphrase';
  const materialChanged =
    normalizeSyncUrl(decoded.baseUrl) !== normalizeSyncUrl(data.sync.url) ||
    decoded.token !== data.sync.token ||
    decoded.secret !== data.sync.secret ||
    secretKind !== data.sync.secretKind;
  const sync = sanitizeSyncSettings({
    ...data.sync,
    enabled: true,
    url: decoded.baseUrl,
    token: decoded.token,
    secretKind,
    secret: decoded.secret,
    passphrase: secretKind === 'passphrase' ? decoded.secret : '',
    lastError: null,
    ...(materialChanged ? cursorReset() : {}),
  });
  if (!sync.enabled) return { ok: false, error: 'pair-invalid' };
  const failed = await persistSync(data, sync);
  if (failed) return failed;
  try {
    return await runSync('auto');
  } catch {
    return { ok: false, error: 'storage' };
  }
}

export function mountOptions(host: OptionsMount): void {
  if (!features.advancedReports && !features.sync) return;
  mountProOptions(host);
}

export function refreshOptions(): void {
  void refreshProOptions();
}

const editionModule: EditionModule = {
  edition,
  bindBackground,
  onBoot,
  onUsage,
  onFocusEnded,
  onReset,
  onMessage,
  mountOptions,
  refreshOptions,
};
void editionModule;
