import type { SyncConfigInput, SyncPublicState, SyncSaveResult, SyncSecretKind } from '@shared/types'
import { getSettingRaw, setSettingRaw } from '../db'
import { isRawKey } from './crypto.ts'
import type { SyncRuntimeConfig } from './pro-provider'
import { canonicalSyncBase, isSyncToken, parseSyncUrl, validPassphrase } from './url'

const CONFIG_KEY = 'syncConfig'
const STATUS_KEY = 'syncStatus'
const CACHE_KEY = 'syncCache'

export type SyncCacheState = {
  baseUrl: string
  settingsEtag: string | null
  settingsRevision: number
  deviceEtag: string | null
  deviceRevision: number
}

function emptyCache(baseUrl: string): SyncCacheState {
  return { baseUrl, settingsEtag: null, settingsRevision: 0, deviceEtag: null, deviceRevision: 0 }
}

function shortText(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 && value.length < 120 ? value : null
}

export function readSyncCache(baseUrl: string): SyncCacheState {
  const canonical = canonicalSyncBase(baseUrl) ?? ''
  const parsed = readJson(CACHE_KEY)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return emptyCache(canonical)
  const record = parsed as Record<string, unknown>
  if (record.baseUrl !== canonical) return emptyCache(canonical)
  const revision = (value: unknown): number =>
    typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 1_000_000_000 ? value : 0
  return {
    baseUrl: canonical,
    settingsEtag: shortText(record.settingsEtag),
    settingsRevision: revision(record.settingsRevision),
    deviceEtag: shortText(record.deviceEtag),
    deviceRevision: revision(record.deviceRevision)
  }
}

export function writeSyncCache(state: SyncCacheState): void {
  setSettingRaw(CACHE_KEY, JSON.stringify(state))
}

export function clearSyncCache(): void {
  setSettingRaw(CACHE_KEY, '')
}

type Status = {
  lastPushAt: string | null
  lastPullAt: string | null
  lastError: string | null
}

function readJson(key: string): unknown {
  const raw = getSettingRaw(key)
  if (!raw) return null
  try {
    return JSON.parse(raw) as unknown
  } catch {
    return null
  }
}

function usableSecret(kind: SyncSecretKind, secret: string): boolean {
  if (kind === 'raw') return isRawKey(secret)
  return validPassphrase(secret)
}

function readSecret(record: Record<string, unknown>): { secretKind: SyncSecretKind; secret: string } {
  const secret = typeof record.secret === 'string' ? record.secret : ''
  if (record.secretKind === 'raw' && isRawKey(secret)) return { secretKind: 'raw', secret }
  if (record.secretKind === 'passphrase' && secret.length <= 200 && validPassphrase(secret)) {
    return { secretKind: 'passphrase', secret }
  }
  const legacy = typeof record.passphrase === 'string' ? record.passphrase : ''
  if (legacy.length <= 200 && validPassphrase(legacy)) return { secretKind: 'passphrase', secret: legacy }
  const key = typeof record.key === 'string' ? record.key : ''
  if (isRawKey(key)) return { secretKind: 'raw', secret: key }
  return { secretKind: 'raw', secret: '' }
}

export function getSyncConfig(): SyncRuntimeConfig {
  const parsed = readJson(CONFIG_KEY)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { enabled: false, baseUrl: '', token: '', secretKind: 'raw', secret: '' }
  }
  const record = parsed as Record<string, unknown>
  const baseUrl = typeof record.baseUrl === 'string' ? record.baseUrl : ''
  const token = typeof record.token === 'string' ? record.token : ''
  const stored = readSecret(record)
  const enabled =
    record.enabled === true && Boolean(parseSyncUrl(baseUrl)) && isSyncToken(token) && usableSecret(stored.secretKind, stored.secret)
  return { enabled, baseUrl, token, secretKind: stored.secretKind, secret: stored.secret }
}

function getStatus(): Status {
  const parsed = readJson(STATUS_KEY)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { lastPushAt: null, lastPullAt: null, lastError: null }
  }
  const record = parsed as Record<string, unknown>
  const text = (value: unknown): string | null => (typeof value === 'string' && value.length < 80 ? value : null)
  return {
    lastPushAt: text(record.lastPushAt),
    lastPullAt: text(record.lastPullAt),
    lastError: text(record.lastError)
  }
}

export function setSyncStatus(patch: Partial<Status>): void {
  const next = { ...getStatus(), ...patch }
  setSettingRaw(STATUS_KEY, JSON.stringify(next))
}

export function publicSyncState(): SyncPublicState {
  const config = getSyncConfig()
  const status = getStatus()
  return {
    available: true,
    enabled: config.enabled,
    baseUrl: config.baseUrl,
    tokenSet: isSyncToken(config.token),
    passphraseSet: usableSecret(config.secretKind, config.secret),
    secretKind: usableSecret(config.secretKind, config.secret) ? config.secretKind : null,
    lastPushAt: status.lastPushAt,
    lastPullAt: status.lastPullAt,
    lastError: status.lastError
  }
}

export function updateSyncConfig(input: SyncConfigInput): SyncSaveResult {
  const current = getSyncConfig()
  const baseUrl = input.baseUrl.trim()
  let token = current.token
  if (input.clearToken) token = ''
  else if (input.token && input.token.length > 0) token = input.token.trim()
  let secretKind = current.secretKind
  let secret = current.secret
  if (input.clearPassphrase) {
    secretKind = 'raw'
    secret = ''
  } else if (input.passphrase && input.passphrase.length > 0) {
    secretKind = 'passphrase'
    secret = input.passphrase
  } else if (
    (input.secretKind === 'raw' || input.secretKind === 'passphrase') &&
    typeof input.secret === 'string' &&
    input.secret.length > 0
  ) {
    secretKind = input.secretKind
    secret = input.secret
  }
  if (secretKind === 'passphrase' && secret.length > 200) return { ok: false, reason: 'invalid' }
  if (baseUrl && !parseSyncUrl(baseUrl)) return { ok: false, reason: 'invalid' }
  if (token && !isSyncToken(token)) return { ok: false, reason: 'invalid' }
  if (secret && !usableSecret(secretKind, secret)) return { ok: false, reason: 'invalid' }
  const canEnable = Boolean(parseSyncUrl(baseUrl)) && isSyncToken(token) && usableSecret(secretKind, secret)
  if (input.enabled && !canEnable) return { ok: false, reason: 'invalid' }
  const next: SyncRuntimeConfig = { enabled: input.enabled && canEnable, baseUrl, token, secretKind, secret }
  const previousBase = canonicalSyncBase(current.baseUrl)
  const nextBase = canonicalSyncBase(baseUrl)
  setSettingRaw(CONFIG_KEY, JSON.stringify(next))
  if (previousBase !== nextBase) clearSyncCache()
  if (!next.enabled) setSyncStatus({ lastError: null })
  return { ok: true, state: publicSyncState() }
}
