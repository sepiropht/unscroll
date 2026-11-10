import { app, type BrowserWindow, type Session, type WebContents } from 'electron'
import { DEV_CSP, PROD_CSP } from '@shared/csp'

const hardened = new WeakSet<WebContents>()

export function applyCommandLinePrivacy(): void {
  app.commandLine.appendSwitch('disable-component-update')
  if (!process.env.ELECTRON_RENDERER_URL) app.commandLine.appendSwitch('disable-background-networking')
  app.commandLine.appendSwitch(
    'disable-features',
    [
      'AutofillServerCommunication',
      'Translate',
      'TranslateUI',
      'MediaRouter',
      'OptimizationHints',
      'InterestFeedContentSuggestions',
      'CertificateTransparencyComponentUpdater',
      'AcceptCHFrame'
    ].join(',')
  )
}

function devOriginAllowed(url: string): boolean {
  const dev = process.env.ELECTRON_RENDERER_URL
  if (!dev) return false
  if (url.startsWith(dev)) return true
  try {
    const origin = new URL(dev)
    const target = new URL(url)
    return (
      target.hostname === origin.hostname &&
      target.port === origin.port &&
      (target.protocol === 'ws:' ||
        target.protocol === 'wss:' ||
        target.protocol === 'http:' ||
        target.protocol === 'https:')
    )
  } catch {
    return false
  }
}

function allowedRequest(url: string): boolean {
  if (url.startsWith('file:') || url.startsWith('devtools:') || url.startsWith('chrome-devtools:')) return true
  return devOriginAllowed(url)
}

export function hardenSession(ses: Session): void {
  ses.setSpellCheckerEnabled(false)
  ses.setPermissionRequestHandler((_contents, _permission, callback) => {
    callback(false)
  })
  ses.setPermissionCheckHandler(() => false)
  ses.setDevicePermissionHandler(() => false)
  ses.setDisplayMediaRequestHandler((_request, callback) => {
    callback(null)
  })
  ses.webRequest.onBeforeRequest((details, callback) => {
    if (allowedRequest(details.url)) {
      callback({})
      return
    }
    callback({ cancel: true })
  })
  ses.webRequest.onHeadersReceived((details, callback) => {
    const responseHeaders = { ...(details.responseHeaders ?? {}) }
    responseHeaders['Content-Security-Policy'] = [app.isPackaged ? PROD_CSP : DEV_CSP]
    callback({ responseHeaders })
  })
}

function blockNavigation(event: { preventDefault: () => void }, url: string): void {
  if (!allowedRequest(url)) event.preventDefault()
}

export function hardenContents(contents: WebContents): void {
  if (hardened.has(contents)) return
  hardened.add(contents)
  contents.setWindowOpenHandler(() => ({ action: 'deny' }))
  contents.on('will-navigate', (event, url) => {
    blockNavigation(event, url)
  })
  contents.on('will-redirect', (event, url) => {
    blockNavigation(event, url)
  })
  contents.on('will-frame-navigate', (event) => {
    blockNavigation(event, event.url)
  })
  contents.on('will-attach-webview', (event) => {
    event.preventDefault()
  })
}

export function hardenWindow(win: BrowserWindow): void {
  hardenContents(win.webContents)
}

export function installContentGuards(): void {
  app.on('web-contents-created', (_event, contents) => {
    hardenContents(contents)
  })
}

export const rendererWebPreferences = {
  contextIsolation: true,
  nodeIntegration: false,
  sandbox: true,
  webSecurity: true,
  allowRunningInsecureContent: false,
  experimentalFeatures: false,
  nodeIntegrationInWorker: false,
  nodeIntegrationInSubFrames: false,
  webviewTag: false,
  navigateOnDragDrop: false,
  spellcheck: false
} as const
