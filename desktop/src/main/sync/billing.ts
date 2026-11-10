import type { BillingOpenResult, BillingPlan } from '@shared/types'
import { safeStripeUrl } from '../externalUrl'
import { syncRequest } from './http'
import { isSyncToken, parseSyncUrl } from './url'

/**
 * Hosted sync plan. Only a server with billing turned on reports one.
 * Self-hosted servers answer `{ enabled: false }` (older ones 404) and the
 * app shows no plan. These calls carry the bearer token only, no usage data.
 */
export { safeStripeUrl }

const STATES = new Set(['trial', 'active', 'past_due', 'ended', 'free'])

export function parsePlan(value: unknown): BillingPlan {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { enabled: false }
  const v = value as Record<string, unknown>
  if (v.enabled !== true || typeof v.state !== 'string' || !STATES.has(v.state)) return { enabled: false }
  return {
    enabled: true,
    state: v.state as BillingPlan['state'],
    daysLeft: typeof v.daysLeft === 'number' && Number.isFinite(v.daysLeft) ? Math.max(0, Math.round(v.daysLeft)) : 0,
    canManage: v.canManage === true,
    cancelAtPeriodEnd: v.cancelAtPeriodEnd === true
  }
}

export function billingEndpoint(baseUrl: string, path: '' | '/checkout' | '/portal'): string | null {
  const base = parseSyncUrl(baseUrl)
  if (!base) return null
  let root = base.pathname.replace(/\/+$/, '')
  for (const suffix of ['/v1/snapshot', '/v1/blobs', '/v1/accounts']) {
    if (root.endsWith(suffix)) root = root.slice(0, -suffix.length).replace(/\/+$/, '')
  }
  return `${base.origin}${root}/v1/billing${path}`
}

export async function fetchPlan(config: { enabled: boolean; baseUrl: string; token: string }): Promise<BillingPlan> {
  if (!config.enabled || !isSyncToken(config.token)) return { enabled: false }
  const url = billingEndpoint(config.baseUrl, '')
  if (!url) return { enabled: false }
  try {
    const res = await syncRequest({ method: 'GET', url, token: config.token, timeoutMs: 8000, maxBytes: 16384 })
    if (res.status !== 200) return { enabled: false }
    return parsePlan(JSON.parse(res.body))
  } catch {
    return { enabled: false }
  }
}

export async function billingLink(
  config: { baseUrl: string; token: string },
  kind: 'checkout' | 'portal',
  plan: 'monthly' | 'yearly'
): Promise<{ ok: true; url: string } | BillingOpenResult> {
  if (!isSyncToken(config.token)) return { ok: false, reason: 'invalid' }
  const url = billingEndpoint(config.baseUrl, kind === 'checkout' ? '/checkout' : '/portal')
  if (!url) return { ok: false, reason: 'invalid' }
  let res
  try {
    res = await syncRequest({
      method: 'POST',
      url,
      token: config.token,
      body: kind === 'checkout' ? JSON.stringify({ plan }) : undefined,
      timeoutMs: 15000,
      maxBytes: 16384
    })
  } catch {
    return { ok: false, reason: 'network' }
  }
  if (res.status !== 200) return { ok: false, reason: `http-${res.status}` }
  try {
    const link = safeStripeUrl((JSON.parse(res.body) as { url?: unknown }).url)
    return link ? { ok: true, url: link } : { ok: false, reason: 'schema' }
  } catch {
    return { ok: false, reason: 'schema' }
  }
}
