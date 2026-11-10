import type { SyncConfigInput } from '@shared/types'
import { syncRequest } from './http.ts'
import type { SyncRuntimeConfig } from './pro-provider.ts'
import { accountEndpoint, isSyncToken, parseSyncUrl } from './url.ts'

const SAFE_REASON = /^(invalid|schema|timeout|too-large|network|http-\d{3})$/

function safeReason(reason: string): string {
  return SAFE_REASON.test(reason) ? reason : 'network'
}

export function shouldProvisionAccount(current: Pick<SyncRuntimeConfig, 'token'>, input: SyncConfigInput): boolean {
  if (!input.enabled) return false
  if (input.passphrase && input.passphrase.length > 0 && !input.clearPassphrase) return false
  if (input.secret && input.secret.length > 0 && !input.clearPassphrase) return false
  if (input.token && input.token.trim().length > 0 && !input.clearToken) return false
  const kept = input.clearToken ? '' : current.token
  return !isSyncToken(kept)
}

export async function createSyncAccount(
  baseUrl: string
): Promise<{ ok: true; token: string } | { ok: false; reason: string }> {
  const base = parseSyncUrl(baseUrl)
  if (!base) return { ok: false, reason: 'invalid' }
  let response
  try {
    response = await syncRequest({
      method: 'POST',
      url: accountEndpoint(base),
      maxBytes: 8192
    })
  } catch (error) {
    const reason = error instanceof Error ? error.message : 'network'
    return { ok: false, reason: safeReason(reason) }
  }
  if (response.status < 200 || response.status >= 300) return { ok: false, reason: `http-${response.status}` }
  let parsed: unknown
  try {
    parsed = JSON.parse(response.body) as unknown
  } catch {
    return { ok: false, reason: 'schema' }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { ok: false, reason: 'schema' }
  const token = (parsed as { token?: unknown }).token
  if (typeof token !== 'string' || !isSyncToken(token)) return { ok: false, reason: 'schema' }
  return { ok: true, token }
}
