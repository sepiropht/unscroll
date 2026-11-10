import { normalizeSyncUrl, syncRoot } from './sync-codec.ts';

/**
 * Hosted sync plan. Only servers that enable billing report one; self-hosted
 * servers answer `{ enabled: false }` (or 404 on older versions) and the UI
 * shows nothing. These calls carry the bearer token only: no usage data.
 */
export type BillingState = 'trial' | 'active' | 'past_due' | 'ended' | 'free';

export interface BillingPlan {
  enabled: boolean;
  state?: BillingState;
  daysLeft?: number;
  canManage?: boolean;
  cancelAtPeriodEnd?: boolean;
  dataKeptUntil?: string;
}

const STATES = new Set<string>(['trial', 'active', 'past_due', 'ended', 'free']);

function root(base: string): string | null {
  const normalized = normalizeSyncUrl(base);
  if (!normalized) return null;
  return syncRoot(normalized);
}

/** Parse GET /v1/billing. Anything unexpected means "no billing". */
export function parsePlan(value: unknown): BillingPlan {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { enabled: false };
  const v = value as Record<string, unknown>;
  if (v.enabled !== true || typeof v.state !== 'string' || !STATES.has(v.state)) return { enabled: false };
  return {
    enabled: true,
    state: v.state as BillingState,
    daysLeft: typeof v.daysLeft === 'number' && Number.isFinite(v.daysLeft) ? Math.max(0, Math.round(v.daysLeft)) : 0,
    canManage: v.canManage === true,
    cancelAtPeriodEnd: v.cancelAtPeriodEnd === true,
    dataKeptUntil: typeof v.dataKeptUntil === 'string' ? v.dataKeptUntil : undefined,
  };
}

/** Only Stripe-hosted https pages may be opened from a server answer. */
export function safeBillingUrl(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 4000) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:') return null;
    if (url.hostname !== 'stripe.com' && !url.hostname.endsWith('.stripe.com')) return null;
    return url.toString();
  } catch {
    return null;
  }
}

function init(method: 'GET' | 'POST', token: string, body?: string): RequestInit {
  const headers: Record<string, string> = { accept: 'application/json', authorization: `Bearer ${token}` };
  if (body !== undefined) headers['content-type'] = 'application/json';
  return {
    method,
    headers,
    body,
    redirect: 'error',
    credentials: 'omit',
    referrerPolicy: 'no-referrer',
    cache: 'no-store',
    signal: AbortSignal.timeout(8_000),
  };
}

export async function fetchPlan(base: string, token: string): Promise<BillingPlan> {
  const r = root(base);
  if (!r || !token) return { enabled: false };
  try {
    const response = await fetch(`${r}/v1/billing`, init('GET', token));
    if (!response.ok) return { enabled: false };
    return parsePlan(await response.json());
  } catch {
    return { enabled: false };
  }
}

/** POST checkout or portal; resolves to the Stripe page to open, or an error code. */
export async function billingLink(
  base: string,
  token: string,
  kind: 'checkout' | 'portal',
  plan: 'monthly' | 'yearly' = 'monthly',
): Promise<{ ok: true; url: string } | { ok: false; error: string }> {
  const r = root(base);
  if (!r || !token) return { ok: false, error: 'sync-config' };
  let response: Response;
  try {
    const body = kind === 'checkout' ? JSON.stringify({ plan }) : undefined;
    response = await fetch(`${r}/v1/billing/${kind}`, init('POST', token, body));
  } catch {
    return { ok: false, error: 'sync-network' };
  }
  if (response.status === 401 || response.status === 403) return { ok: false, error: 'sync-auth' };
  if (!response.ok) return { ok: false, error: 'sync-http' };
  try {
    const url = safeBillingUrl(((await response.json()) as { url?: unknown }).url);
    return url ? { ok: true, url } : { ok: false, error: 'sync-http' };
  } catch {
    return { ok: false, error: 'sync-http' };
  }
}
