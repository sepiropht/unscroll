const APP_PAGES = ['/out/renderer/index.html', '/out/renderer/overlay.html']

export function isTrustedRendererUrl(url: string, devServer: string | undefined): boolean {
  if (!url || url.length > 2000) return false
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return false
  }
  if (parsed.username || parsed.password || parsed.search) return false
  if (parsed.protocol === 'file:') {
    let path = parsed.pathname
    try {
      path = decodeURIComponent(parsed.pathname)
    } catch {
      return false
    }
    return APP_PAGES.some((suffix) => path.endsWith(suffix))
  }
  if (!devServer) return false
  let dev: URL
  try {
    dev = new URL(devServer)
  } catch {
    return false
  }
  if (parsed.origin !== dev.origin) return false
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false
  return parsed.pathname === '/' || parsed.pathname === '/index.html' || parsed.pathname === '/overlay.html'
}
