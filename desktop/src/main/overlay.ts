import { BrowserWindow, screen } from 'electron'
import { join } from 'node:path'
import { dayKey } from '@shared/day'
import { IPC } from '@shared/ipc'
import { canIgnoreLimit, limitCapMs } from '@shared/limits'
import type { ActionResult, OverlayPayload } from '@shared/types'
import {
  addGrant,
  clearFlag,
  findLimit,
  grantSummary,
  setFlag,
  usageForApp
} from './db'
import { isQuitting } from './lifecycle'
import { hardenWindow, rendererWebPreferences } from './privacy'
import { getSettings } from './settings'

let window: BrowserWindow | null = null
let current: OverlayPayload | null = null
let allowClose = false

export function isOverlayHolding(): boolean {
  return current !== null
}

export function currentOverlay(): OverlayPayload | null {
  return current
}

function send(payload: OverlayPayload | null): void {
  if (!window || window.isDestroyed()) return
  window.webContents.send(IPC.overlayData, payload)
}

function load(target: BrowserWindow): void {
  if (process.env.ELECTRON_RENDERER_URL) {
    void target.loadURL(`${process.env.ELECTRON_RENDERER_URL}/overlay.html`)
    return
  }
  void target.loadFile(join(__dirname, '../renderer/overlay.html'))
}

function ensureWindow(): BrowserWindow {
  if (window && !window.isDestroyed()) return window
  const display = screen.getPrimaryDisplay()
  const created = new BrowserWindow({
    x: display.bounds.x,
    y: display.bounds.y,
    width: display.bounds.width,
    height: display.bounds.height,
    fullscreen: true,
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    movable: false,
    show: false,
    backgroundColor: '#0B1416',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      ...rendererWebPreferences
    }
  })
  hardenWindow(created)
  created.setAlwaysOnTop(true, 'screen-saver')
  created.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  created.on('close', (event) => {
    if (isQuitting() || allowClose) return
    if (current?.strict) {
      event.preventDefault()
      return
    }
    if (current) {
      setFlag(current.appName, dayKey(Date.now(), getSettings().dayStartHour), 'ignored')
      current = null
    }
  })
  created.on('closed', () => {
    if (window === created) window = null
  })
  load(created)
  window = created
  return created
}

export function showOverlay(payload: OverlayPayload): void {
  current = payload
  const existed = Boolean(window && !window.isDestroyed())
  const target = ensureWindow()
  const reveal = (): void => {
    if (!window || window.isDestroyed() || !current) return
    if (!window.isVisible()) {
      window.show()
      window.focus()
      window.setFullScreen(true)
    }
    send(current)
  }
  if (!existed || target.webContents.isLoading()) target.webContents.once('did-finish-load', reveal)
  else reveal()
}

export function hideOverlay(): void {
  current = null
  if (!window || window.isDestroyed()) return
  allowClose = true
  window.close()
  allowClose = false
}

export function grantFromOverlay(): ActionResult {
  if (!current) return { ok: false, reason: 'none' }
  const settings = getSettings()
  const day = dayKey(Date.now(), settings.dayStartHour)
  const before = grantSummary(current.appName, day)
  if (before.count >= settings.maxGrantsPerDay) return { ok: false, reason: 'max' }
  addGrant(current.appName, day, settings.grantMinutes, Date.now())
  clearFlag(current.appName, day, 'n100')
  const limit = findLimit(current.appName)
  if (!limit) {
    hideOverlay()
    return { ok: true }
  }
  const grants = grantSummary(current.appName, day)
  const limitMs = limitCapMs(limit.daily_minutes, grants.extraMinutes)
  const used = usageForApp(day, current.appName)
  if (used >= limitMs) {
    showOverlay({
      ...current,
      usedMs: used,
      limitMs,
      grantsUsed: grants.count,
      grantMinutes: settings.grantMinutes,
      maxGrants: settings.maxGrantsPerDay,
      locale: settings.locale
    })
    return { ok: false, reason: 'still-over' }
  }
  hideOverlay()
  return { ok: true }
}

export function ignoreFromOverlay(): ActionResult {
  if (!current) return { ok: false, reason: 'none' }
  if (!canIgnoreLimit(current.strict)) return { ok: false, reason: 'strict' }
  setFlag(current.appName, dayKey(Date.now(), getSettings().dayStartHour), 'ignored')
  hideOverlay()
  return { ok: true }
}
