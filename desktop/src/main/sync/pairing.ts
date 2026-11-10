import { isRawKey } from './crypto.ts'
import { canonicalSyncBase, isSyncToken, validPassphrase } from './url'

export const PAIRING_LINK_MAX = 8192

export type PairingMode = 'raw' | 'pbkdf2'

export type PairingMaterial = {
  baseUrl: string
  token: string
  mode: PairingMode
  secret: string
}

// k is unpadded base64url. In raw mode it is the 32-byte key. In pbkdf2 mode
// it is the UTF-8 passphrase. Writers always emit m. A missing m means raw
// when k is exactly 32 bytes, and is rejected otherwise.
const K_RE = /^[A-Za-z0-9_-]+$/

function passphraseFromK(k: string): string | null {
  if (!K_RE.test(k) || k.length > 1200) return null
  const bytes = Buffer.from(k, 'base64url')
  if (bytes.length === 0 || bytes.toString('base64url') !== k) return null
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
  } catch {
    return null
  }
  if (Buffer.from(text, 'utf8').toString('utf8') !== text) return null
  if (!validPassphrase(text)) return null
  return text
}

export function encodePairingLink(input: PairingMaterial): string {
  if (
    typeof input.baseUrl !== 'string' ||
    typeof input.token !== 'string' ||
    typeof input.secret !== 'string' ||
    (input.mode !== 'raw' && input.mode !== 'pbkdf2')
  ) {
    throw new Error('invalid')
  }
  const baseUrl = canonicalSyncBase(input.baseUrl)
  if (!baseUrl || !isSyncToken(input.token)) throw new Error('invalid')
  let k = ''
  if (input.mode === 'raw') {
    if (!isRawKey(input.secret)) throw new Error('invalid')
    k = input.secret
  } else {
    if (!validPassphrase(input.secret)) throw new Error('invalid')
    const bytes = Buffer.from(input.secret, 'utf8')
    if (bytes.toString('utf8') !== input.secret) throw new Error('invalid')
    k = bytes.toString('base64url')
  }
  return `unscroll://pair?v=1&u=${encodeURIComponent(baseUrl)}&t=${encodeURIComponent(input.token)}&k=${k}&m=${input.mode}`
}

function sole(params: URLSearchParams, key: string): string | null {
  const values = params.getAll(key)
  if (values.length !== 1) return null
  return values[0] ?? null
}

function optionalSole(params: URLSearchParams, key: string): { ok: true; value: string | null } | { ok: false } {
  const values = params.getAll(key)
  if (values.length > 1) return { ok: false }
  if (values.length === 0) return { ok: true, value: null }
  return { ok: true, value: values[0] ?? null }
}

export function decodePairingLink(input: string): PairingMaterial | null {
  if (typeof input !== 'string' || input.length === 0 || input.length > PAIRING_LINK_MAX) return null
  // The URI alphabet has no whitespace. A wrapped paste still decodes.
  const compact = input.trim().replace(/[\t\n\r\f\v \u00a0\uFEFF]+/g, '')
  if (!compact || compact.length > PAIRING_LINK_MAX) return null
  let url: URL
  try {
    url = new URL(compact)
  } catch {
    return null
  }
  if (url.protocol !== 'unscroll:') return null
  if (url.hostname.toLowerCase() !== 'pair') return null
  if (url.pathname !== '' && url.pathname !== '/') return null
  if (url.port || url.username || url.password || url.hash) return null
  const version = sole(url.searchParams, 'v')
  const base = sole(url.searchParams, 'u')
  const token = sole(url.searchParams, 't')
  const k = sole(url.searchParams, 'k')
  const modeParam = optionalSole(url.searchParams, 'm')
  if (version !== '1' || base == null || token == null || k == null || !modeParam.ok) return null
  const baseUrl = canonicalSyncBase(base)
  if (!baseUrl || !isSyncToken(token)) return null
  const modeValue = modeParam.value
  if (modeValue == null) {
    if (!isRawKey(k)) return null
    return { baseUrl, token, mode: 'raw', secret: k }
  }
  if (modeValue === 'raw') {
    if (!isRawKey(k)) return null
    return { baseUrl, token, mode: 'raw', secret: k }
  }
  if (modeValue === 'pbkdf2') {
    const passphrase = passphraseFromK(k)
    if (passphrase == null) return null
    return { baseUrl, token, mode: 'pbkdf2', secret: passphrase }
  }
  return null
}
