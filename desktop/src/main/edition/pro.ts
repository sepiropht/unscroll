import { IPC } from '@shared/ipc'
import { DEFAULT_SYNC_URL } from '@shared/sync'
import type { BillingOpenResult, PairingApplyResult, SyncConfigInput, SyncOutcome } from '@shared/types'
import { handleIpc } from '../ipcHandle'
import { openSafeExternal } from '../openExternal'
import { plainRecord } from '../ipcInput'
import { buildAdvancedReport } from '../report'
import { createSyncAccount, shouldProvisionAccount } from '../sync/account'
import { billingLink, fetchPlan } from '../sync/billing'
import { getSyncConfig, publicSyncState, readSyncCache, setSyncStatus, updateSyncConfig, writeSyncCache } from '../sync/config'
import { generateRawKey } from '../sync/crypto'
import { decodePairingLink, encodePairingLink, PAIRING_LINK_MAX } from '../sync/pairing'
import { createSyncProvider } from '../sync/pro-provider'
import { applySharedSettings, applySyncSnapshot, buildSyncSnapshot } from '../sync/snapshot'
import { canonicalSyncBase } from '../sync/url'

const CONTROL = /[\u0000-\u001F\u007F]/

function parseSyncInput(input: unknown): SyncConfigInput | null {
  const record = plainRecord(input)
  if (!record || typeof record.enabled !== 'boolean' || typeof record.baseUrl !== 'string') return null
  if (record.baseUrl.length > 300 || CONTROL.test(record.baseUrl)) return null
  if (record.token != null && typeof record.token !== 'string') return null
  if (typeof record.token === 'string' && (record.token.length > 512 || CONTROL.test(record.token))) return null
  if (record.passphrase != null && typeof record.passphrase !== 'string') return null
  if (typeof record.passphrase === 'string' && (record.passphrase.length > 200 || CONTROL.test(record.passphrase))) return null
  return {
    enabled: record.enabled,
    baseUrl: record.baseUrl,
    token: typeof record.token === 'string' ? record.token : undefined,
    passphrase: typeof record.passphrase === 'string' ? record.passphrase : undefined,
    clearToken: record.clearToken === true,
    clearPassphrase: record.clearPassphrase === true
  }
}

function failure(reason: string): SyncOutcome {
  const safe = /^(disabled|invalid|unavailable|decrypt|schema|conflict|timeout|too-large|network|http-\d{3})$/.test(reason)
    ? reason
    : 'network'
  setSyncStatus({ lastError: safe })
  return { ok: false, reason: safe }
}

export function registerEdition(): void {
  const provider = createSyncProvider(
    getSyncConfig,
    {
      applySettings: (body) => applySharedSettings(body),
      applyUsage: (snapshot) => applySyncSnapshot(snapshot),
      snapshot: () => buildSyncSnapshot()
    },
    { load: readSyncCache, save: writeSyncCache }
  )

  async function syncNow(): Promise<SyncOutcome> {
    try {
      const snapshot = buildSyncSnapshot()
      const result = await provider.push(snapshot)
      if (!result.ok) return failure(result.reason)
      setSyncStatus({ lastPushAt: result.at, lastError: null })
      return { ok: true, at: result.at, changed: true }
    } catch (error) {
      return failure(error instanceof Error ? error.message : 'network')
    }
  }

  handleIpc(IPC.syncState, () => publicSyncState())
  // Tokens and keys stay in the database. Do not log the save payload or the account response.
  handleIpc(IPC.syncSave, async (_event, input) => {
    const parsed = parseSyncInput(input)
    if (!parsed) return { ok: false as const, reason: 'invalid' }
    const baseUrl = canonicalSyncBase(parsed.baseUrl.trim() || DEFAULT_SYNC_URL)
    if (!baseUrl) return { ok: false as const, reason: 'invalid' }
    const nextInput: SyncConfigInput = { ...parsed, baseUrl }
    if (shouldProvisionAccount(getSyncConfig(), nextInput)) {
      const created = await createSyncAccount(baseUrl)
      if (!created.ok) return { ok: false as const, reason: created.reason }
      const saved = updateSyncConfig({
        enabled: true,
        baseUrl,
        token: created.token,
        secretKind: 'raw',
        secret: generateRawKey()
      })
      if (!saved.ok) return saved
      await syncNow()
      return { ok: true as const, state: publicSyncState() }
    }
    return updateSyncConfig(nextInput)
  })
  handleIpc(IPC.syncPush, () => syncNow())
  handleIpc(IPC.syncPull, async (): Promise<SyncOutcome> => {
    try {
      const result = await provider.pull()
      if (!result.ok) return failure(result.reason)
      const at = new Date().toISOString()
      if (!result.snapshot) {
        setSyncStatus({ lastPullAt: at, lastError: null })
        return { ok: true, at, changed: false }
      }
      applySyncSnapshot(result.snapshot)
      setSyncStatus({ lastPullAt: at, lastError: null })
      return { ok: true, at, changed: true }
    } catch (error) {
      return failure(error instanceof Error ? error.message : 'network')
    }
  })
  // The link carries the token and the encryption key. Do not log it.
  handleIpc(IPC.syncPairingLink, () => {
    const config = getSyncConfig()
    try {
      const link = encodePairingLink({
        baseUrl: config.baseUrl,
        token: config.token,
        mode: config.secretKind === 'passphrase' ? 'pbkdf2' : 'raw',
        secret: config.secret
      })
      return { ok: true as const, link }
    } catch {
      return { ok: false as const, reason: 'invalid' }
    }
  })
  handleIpc(IPC.syncPairingApply, async (_event, input): Promise<PairingApplyResult> => {
    if (typeof input !== 'string' || input.length === 0 || input.length > PAIRING_LINK_MAX) {
      return { ok: false, reason: 'invalid' }
    }
    const decoded = decodePairingLink(input)
    if (!decoded) return { ok: false, reason: 'invalid' }
    const saved = updateSyncConfig({
      enabled: true,
      baseUrl: decoded.baseUrl,
      token: decoded.token,
      secretKind: decoded.mode === 'raw' ? 'raw' : 'passphrase',
      secret: decoded.secret
    })
    if (!saved.ok) return { ok: false, reason: saved.reason }
    const outcome = await syncNow()
    if (!outcome.ok) return { ok: false, reason: outcome.reason, state: publicSyncState() }
    return { ok: true, state: publicSyncState(), at: outcome.at, changed: outcome.changed }
  })
  // Hosted plan. Only the bearer token goes to the sync server; the Stripe
  // page opens in the system browser.
  handleIpc(IPC.syncBilling, () => fetchPlan(getSyncConfig()))
  handleIpc(IPC.syncBillingOpen, async (_event, input): Promise<BillingOpenResult> => {
    const record = plainRecord(input)
    if (!record || (record.kind !== 'checkout' && record.kind !== 'portal')) return { ok: false, reason: 'invalid' }
    const plan = record.plan === 'yearly' ? 'yearly' : 'monthly'
    const link = await billingLink(getSyncConfig(), record.kind, plan)
    if (!link.ok) return link
    if (!('url' in link)) return { ok: false, reason: 'schema' }
    if (!(await openSafeExternal(link.url))) return { ok: false, reason: 'schema' }
    return { ok: true }
  })
  handleIpc(IPC.report, () => buildAdvancedReport())
}

export function shutdownEdition(): void {}
