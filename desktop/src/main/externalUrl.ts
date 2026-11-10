const BLOCKED_HOSTS = new Set(['localhost', '127.0.0.1', '0.0.0.0', '::1', '[::1]'])

export function safeExternalHref(value: string): string | null {
  if (typeof value !== 'string' || value.length === 0 || value.length > 2000) return null
  if (value !== value.trim() || /[\u0000-\u001F\u007F\\]/.test(value)) return null
  let parsed: URL
  try {
    parsed = new URL(value)
  } catch {
    return null
  }
  if (parsed.protocol !== 'https:') return null
  if (parsed.username || parsed.password || parsed.search || parsed.hash) return null
  const host = parsed.hostname.toLowerCase()
  if (!host || BLOCKED_HOSTS.has(host)) return null
  if (host.endsWith('.local') || host.endsWith('.localhost')) return null
  if (!host.includes('.') || /^\d{1,3}(\.\d{1,3}){3}$/.test(host)) return null
  return parsed.href
}
