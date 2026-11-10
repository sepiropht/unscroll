import { accountUrl, validToken } from './sync-codec.ts';

export interface ProvisionInput {
  enabled: boolean;
  token?: string;
  passphrase?: string;
  secret?: string;
  clearToken?: boolean;
  clearPassphrase?: boolean;
}

/** Enable with no token and no typed secret creates an anonymous account. */
export function shouldProvisionAccount(current: { token: string }, input: ProvisionInput): boolean {
  if (!input.enabled) return false;
  if (input.passphrase && input.passphrase.length > 0 && !input.clearPassphrase) return false;
  if (input.secret && input.secret.length > 0 && !input.clearPassphrase) return false;
  if (input.token && input.token.trim().length > 0 && !input.clearToken) return false;
  const kept = input.clearToken ? '' : current.token;
  return !validToken(kept);
}

/**
 * `POST {base}/v1/accounts` with no body and no Authorization header.
 * The token is returned once. Callers must not log it.
 */
export async function createSyncAccount(baseUrl: string): Promise<{ ok: true; token: string } | { ok: false; reason: string }> {
  const url = accountUrl(baseUrl);
  if (!url) return { ok: false, reason: 'invalid' };
  let response: Response;
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { accept: 'application/json' },
      redirect: 'error',
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      cache: 'no-store',
      signal: AbortSignal.timeout(8_000),
    });
  } catch {
    return { ok: false, reason: 'network' };
  }
  if (response.status === 401 || response.status === 403) return { ok: false, reason: 'auth' };
  if (response.status < 200 || response.status >= 300) return { ok: false, reason: 'http' };
  let text = '';
  try {
    text = await response.text();
  } catch {
    return { ok: false, reason: 'network' };
  }
  if (text.length === 0 || text.length > 8192) return { ok: false, reason: 'schema' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(text) as unknown;
  } catch {
    return { ok: false, reason: 'schema' };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { ok: false, reason: 'schema' };
  const token = (parsed as { token?: unknown }).token;
  if (typeof token !== 'string' || !validToken(token)) return { ok: false, reason: 'schema' };
  return { ok: true, token };
}
