import type { SyncSecretKind } from '@shared/types'
import type { SyncCacheState } from './config.ts'
import { openSealed, rawKeyFromB64, seal, type RawSealSecret } from './crypto.ts'
import {
  ownedDigest,
  ownedFromSnapshot,
  readSettingsDocument,
  readUsageDocument,
  settingsDocument,
  usageDocument,
  type SettingsRead
} from './document.ts'
import { syncRequest, type SyncHttpResult } from './http.ts'
import type { SyncProvider, SyncPullResult, SyncSnapshot, SyncTransferResult } from './provider.ts'
import { blobEndpoint, deviceBlobId, isSyncToken, parseSyncUrl, SETTINGS_BLOB_ID, validPassphrase } from './url.ts'

export type SyncRuntimeConfig = {
  enabled: boolean
  baseUrl: string
  token: string
  secretKind: SyncSecretKind
  secret: string
}

export type SyncBridge = {
  applySettings: (body: Record<string, unknown>) => void
  applyUsage: (snapshot: SyncSnapshot) => void
  snapshot: () => SyncSnapshot
}

export type SyncCacheStore = {
  load: (baseUrl: string) => SyncCacheState
  save: (state: SyncCacheState) => void
}

function emptyCache(baseUrl: string): SyncCacheState {
  return { baseUrl, settingsEtag: null, settingsRevision: 0, deviceEtag: null, deviceRevision: 0 }
}

function memoryCache(): SyncCacheStore {
  let state = emptyCache('')
  return {
    load: (baseUrl) => (state.baseUrl === baseUrl ? state : emptyCache(baseUrl)),
    save: (next) => {
      state = next
    }
  }
}

function sealSecret(config: SyncRuntimeConfig): string | RawSealSecret | null {
  if (config.secretKind === 'raw') {
    const key = rawKeyFromB64(config.secret)
    if (!key) return null
    return { kind: 'raw', key }
  }
  if (!validPassphrase(config.secret)) return null
  return config.secret
}

function ready(config: SyncRuntimeConfig): { base: URL; token: string; secret: string | RawSealSecret } | { reason: string } {
  if (!config.enabled) return { reason: 'disabled' }
  const base = parseSyncUrl(config.baseUrl)
  const secret = sealSecret(config)
  if (!base || !isSyncToken(config.token) || !secret) return { reason: 'invalid' }
  return { base, token: config.token, secret }
}

function asReason(error: unknown): string {
  const reason = error instanceof Error ? error.message : 'network'
  if (
    reason === 'schema' ||
    reason === 'decrypt' ||
    reason === 'too-large' ||
    reason === 'invalid' ||
    reason === 'conflict'
  ) {
    return reason
  }
  return 'network'
}

function canonicalBase(base: URL): string {
  return `${base.origin}${base.pathname.replace(/\/+$/, '')}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function projectedDigest(body: Record<string, unknown>): string {
  return ownedDigest({
    preferences: body.preferences ?? null,
    limits: Array.isArray(body.limits) ? body.limits : [],
    categories: Array.isArray(body.categories) ? body.categories : [],
    appCategories: Array.isArray(body.appCategories) ? body.appCategories : []
  })
}

async function readPlain(
  body: string,
  secret: string | RawSealSecret
): Promise<{ ok: true; value: unknown } | { ok: false; reason: string }> {
  let parsed: unknown
  try {
    parsed = JSON.parse(body) as unknown
  } catch {
    return { ok: false, reason: 'schema' }
  }
  try {
    const json = await openSealed(parsed, secret)
    try {
      return { ok: true, value: JSON.parse(json) as unknown }
    } catch {
      return { ok: false, reason: 'schema' }
    }
  } catch (error) {
    return { ok: false, reason: asReason(error) }
  }
}

async function getBlob(
  url: string,
  token: string
): Promise<{ missing: true } | { missing: false; body: string; etag: string } | { error: string }> {
  let response: SyncHttpResult
  try {
    response = await syncRequest({ method: 'GET', url, token })
  } catch (error) {
    return { error: asReason(error) }
  }
  if (response.status === 404) return { missing: true }
  if (response.status < 200 || response.status >= 300) return { error: `http-${response.status}` }
  if (response.weakEtag || !response.etag) return { error: 'schema' }
  return { missing: false, body: response.body, etag: response.etag }
}

async function putBlob(
  url: string,
  token: string,
  body: string,
  precondition: { ifMatch?: string; ifNoneMatch?: string }
): Promise<{ ok: true; etag: string } | { ok: false; reason: string }> {
  let response: SyncHttpResult
  try {
    response = await syncRequest({
      method: 'PUT',
      url,
      token,
      body,
      ifMatch: precondition.ifMatch,
      ifNoneMatch: precondition.ifNoneMatch
    })
  } catch (error) {
    return { ok: false, reason: asReason(error) }
  }
  if (response.status === 412) return { ok: false, reason: 'http-412' }
  if (response.status < 200 || response.status >= 300) return { ok: false, reason: `http-${response.status}` }
  if (response.weakEtag || !response.etag) return { ok: false, reason: 'schema' }
  return { ok: true, etag: response.etag }
}

export function createSyncProvider(
  getConfig: () => SyncRuntimeConfig,
  bridge?: SyncBridge,
  store: SyncCacheStore = memoryCache()
): SyncProvider {
  let rememberedDevice = ''

  async function openBlob(
    target: { base: URL; token: string; secret: string | RawSealSecret },
    id: string
  ): Promise<{ missing: true } | { missing: false; plain: unknown; etag: string } | { error: string }> {
    const fetched = await getBlob(blobEndpoint(target.base, id), target.token)
    if ('error' in fetched || fetched.missing) return fetched
    const opened = await readPlain(fetched.body, target.secret)
    if (!opened.ok) return { error: opened.reason }
    return { missing: false, plain: opened.value, etag: fetched.etag }
  }

  return {
    async push(snapshot: SyncSnapshot): Promise<SyncTransferResult> {
      const target = ready(getConfig())
      if ('reason' in target) return { ok: false, reason: target.reason }
      const deviceId = deviceBlobId(snapshot.deviceId)
      if (!deviceId) return { ok: false, reason: 'invalid' }
      rememberedDevice = snapshot.deviceId
      const baseKey = canonicalBase(target.base)
      const cache = store.load(baseKey)

      const remoteSettings = await openBlob(target, SETTINGS_BLOB_ID)
      if ('error' in remoteSettings) return { ok: false, reason: remoteSettings.error }
      let settingsRead: SettingsRead | null = null
      let settingsEtag: string | null = null
      if (!remoteSettings.missing) {
        settingsRead = readSettingsDocument(remoteSettings.plain)
        if (!settingsRead || !isRecord(settingsRead.body)) return { ok: false, reason: 'schema' }
        settingsEtag = remoteSettings.etag
      }

      const applied = cache.settingsRevision
      const localDigest = ownedDigest(ownedFromSnapshot(snapshot))
      const remoteDigest = settingsRead ? projectedDigest(settingsRead.body) : null
      if (settingsRead && settingsRead.revision > applied && applied > 0 && remoteDigest !== localDigest) {
        return { ok: false, reason: 'conflict' }
      }

      const takeRemoteSettings = Boolean(settingsRead && settingsRead.revision > applied && remoteDigest !== localDigest)
      if (takeRemoteSettings && settingsRead) bridge?.applySettings(settingsRead.body)

      const remoteUsage = await openBlob(target, deviceId)
      if ('error' in remoteUsage) return { ok: false, reason: remoteUsage.error }
      let usageRevision = 0
      let usageEtag: string | null = null
      if (!remoteUsage.missing) {
        const usage = readUsageDocument(remoteUsage.plain, snapshot.deviceId)
        if (!usage) return { ok: false, reason: 'schema' }
        usageRevision = usage.revision
        usageEtag = remoteUsage.etag
        bridge?.applyUsage(usage.snapshot)
      }

      const current = bridge ? bridge.snapshot() : snapshot
      if (!deviceBlobId(current.deviceId) || current.deviceId !== snapshot.deviceId) return { ok: false, reason: 'invalid' }

      const currentDigest = ownedDigest(ownedFromSnapshot(current))
      const pushSettings =
        !settingsRead ||
        (!takeRemoteSettings && settingsRead.revision <= applied && remoteDigest !== currentDigest)
      let nextSettingsRevision = settingsRead ? settingsRead.revision : 0
      if (pushSettings) {
        const revision = settingsRead ? settingsRead.revision + 1 : 1
        if (revision > 1_000_000_000) return { ok: false, reason: 'schema' }
        let body: string
        try {
          const plain = JSON.stringify(
            settingsDocument(current, settingsRead ? settingsRead.body : null, revision, current.exportedAt)
          )
          body = JSON.stringify(await seal(plain, target.secret))
        } catch (error) {
          return { ok: false, reason: asReason(error) }
        }
        const wrote = await putBlob(
          blobEndpoint(target.base, SETTINGS_BLOB_ID),
          target.token,
          body,
          settingsEtag ? { ifMatch: settingsEtag } : { ifNoneMatch: '*' }
        )
        if (!wrote.ok) return wrote
        settingsEtag = wrote.etag
        nextSettingsRevision = revision
      }

      const deviceRevision = usageRevision > 0 ? usageRevision + 1 : 1
      if (deviceRevision > 1_000_000_000) return { ok: false, reason: 'schema' }
      let usageBody: string
      try {
        usageBody = JSON.stringify(await seal(JSON.stringify(usageDocument(current, deviceRevision)), target.secret))
      } catch (error) {
        return { ok: false, reason: asReason(error) }
      }
      const wroteUsage = await putBlob(
        blobEndpoint(target.base, deviceId),
        target.token,
        usageBody,
        usageEtag ? { ifMatch: usageEtag } : { ifNoneMatch: '*' }
      )
      if (!wroteUsage.ok) return wroteUsage
      store.save({
        baseUrl: baseKey,
        settingsEtag,
        settingsRevision: nextSettingsRevision,
        deviceEtag: wroteUsage.etag,
        deviceRevision
      })
      return { ok: true, at: current.exportedAt }
    },
    async pull(): Promise<SyncPullResult> {
      const target = ready(getConfig())
      if ('reason' in target) return { ok: false, reason: target.reason }
      const device = bridge?.snapshot().deviceId || rememberedDevice
      const id = device ? deviceBlobId(device) : null
      if (!id || !device) return { ok: true, snapshot: null }
      const remoteUsage = await openBlob(target, id)
      if ('error' in remoteUsage) return { ok: false, reason: remoteUsage.error }
      if (remoteUsage.missing) return { ok: true, snapshot: null }
      const usage = readUsageDocument(remoteUsage.plain, device)
      if (!usage) return { ok: false, reason: 'schema' }
      return { ok: true, snapshot: usage.snapshot }
    }
  }
}
