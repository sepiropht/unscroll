const DEVICE_ID = /^[A-Za-z0-9_-]{8,64}$/
const BLOB_ID = /^[a-z][a-z0-9._-]{0,127}$/
const LEGACY_SNAPSHOT = '/v1/snapshot'

export function parseSyncUrl(input: string): URL | null {
  const trimmed = input.trim()
  if (!trimmed || trimmed.length > 300) return null
  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  if (url.username || url.password || url.hash || url.search) return null
  if (!url.hostname) return null
  return url
}

export function canonicalSyncBase(input: string): string | null {
  const url = parseSyncUrl(input)
  if (!url) return null
  const path = url.pathname.replace(/\/+$/, '')
  return `${url.origin}${path}`
}

export function deviceBlobId(deviceId: string): string | null {
  if (!DEVICE_ID.test(deviceId)) return null
  const id = `device.desktop.${deviceId}`
  return BLOB_ID.test(id) ? id : null
}

export const SETTINGS_BLOB_ID = 'settings'

function syncRootPath(base: URL): string {
  let path = base.pathname.replace(/\/+$/, '')
  if (path.endsWith(LEGACY_SNAPSHOT)) path = path.slice(0, -LEGACY_SNAPSHOT.length).replace(/\/+$/, '')
  if (path.endsWith('/v1/blobs')) path = path.slice(0, -'/v1/blobs'.length).replace(/\/+$/, '')
  if (path.endsWith('/v1/accounts')) path = path.slice(0, -'/v1/accounts'.length).replace(/\/+$/, '')
  return path
}

export function blobEndpoint(base: URL, id: string): string {
  return `${base.origin}${syncRootPath(base)}/v1/blobs/${id}`
}

export function accountEndpoint(base: URL): string {
  return `${base.origin}${syncRootPath(base)}/v1/accounts`
}

export function validPassphrase(passphrase: string): boolean {
  return passphrase.length >= 8 && passphrase.length <= 200
}

export function isSyncToken(token: string): boolean {
  return token.length >= 8 && token.length <= 512 && /^[\x21-\x7E]+$/.test(token)
}
