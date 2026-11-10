import net, { type Socket } from 'node:net'
import type { ActiveApp } from './types'
import { hyprlandSocketPath } from './detect'
import { execText } from './exec'
import { warnOnce } from './log'
import { consumeLines, parseHyprlandSocketLine, parseHyprlandWindow } from './parse'
import { exeOf } from './processInfo'

const HYPRCTL_TIMEOUT_MS = 1000
const RECONNECT_MAX_MS = 15_000
const NOTIFY_MS = 200

type PolledWindow = {
  appName: string
  title: string
  pid: number | null
  exePath: string
}

type Cache =
  | { state: 'unknown' }
  | { state: 'empty' }
  | { state: 'window'; appName: string; title: string; pid: number | null; exePath: string }

let cache: Cache = { state: 'unknown' }
let socketLive = false
let stoppedWatch = false
let generation = 0
let current: Socket | null = null
let reconnectTimer: NodeJS.Timeout | null = null
let notifyTimer: NodeJS.Timeout | null = null
let reconnectDelayMs = 1000
let nextDelayMs = 1000
const listeners = new Set<() => void>()

export function setHyprlandReconnectDelayForTests(ms: number): void {
  const delay = Math.max(0, ms)
  reconnectDelayMs = delay
  nextDelayMs = delay
}

export function stopHyprlandWatch(): void {
  stoppedWatch = true
  generation += 1
  socketLive = false
  cache = { state: 'unknown' }
  nextDelayMs = reconnectDelayMs
  if (notifyTimer) clearTimeout(notifyTimer)
  notifyTimer = null
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectTimer = null
  listeners.clear()
  const sock = current
  current = null
  if (!sock) return
  sock.removeAllListeners()
  try {
    sock.destroy()
  } catch {
    // The socket is already gone. Stopping must not throw.
  }
}

export function subscribeHyprlandWindow(listener: () => void): () => void {
  listeners.add(listener)
  stoppedWatch = false
  ensureHyprlandWatch()
  return () => {
    listeners.delete(listener)
  }
}

export function ensureHyprlandWatch(): void {
  if (stoppedWatch || current || reconnectTimer) return
  if (!hyprlandSocketPath()) return
  connect()
}

function scheduleReconnect(): void {
  if (stoppedWatch || reconnectTimer) return
  const delay = nextDelayMs
  nextDelayMs = Math.min(Math.max(nextDelayMs, reconnectDelayMs) * 2, RECONNECT_MAX_MS)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    if (stoppedWatch) return
    connect()
  }, delay)
  reconnectTimer.unref()
}

function notifyListeners(): void {
  if (notifyTimer || stoppedWatch) return
  notifyTimer = setTimeout(() => {
    notifyTimer = null
    if (stoppedWatch) return
    for (const listener of [...listeners]) {
      try {
        listener()
      } catch (error) {
        console.error('[unscroll] hyprland window listener failed', error)
      }
    }
  }, NOTIFY_MS)
  notifyTimer.unref()
}

function applySocketLine(line: string): void {
  const event = parseHyprlandSocketLine(line)
  if (event.kind === 'ignore') return
  if (event.kind === 'empty') {
    cache = { state: 'empty' }
    notifyListeners()
    return
  }
  const previous = cache
  if (previous.state === 'window' && previous.appName === event.appName) {
    cache = {
      state: 'window',
      appName: event.appName,
      title: event.title,
      pid: previous.pid,
      exePath: previous.exePath
    }
  } else {
    cache = { state: 'window', appName: event.appName, title: event.title, pid: null, exePath: '' }
  }
  notifyListeners()
}

function connect(): void {
  if (stoppedWatch || current) return
  const path = hyprlandSocketPath()
  if (!path) return
  const gen = ++generation
  let sock: Socket
  try {
    sock = net.connect(path)
  } catch (error) {
    warnOnce('hyprland socket2 failed', error)
    scheduleReconnect()
    return
  }
  current = sock
  sock.unref()
  let buffer = ''
  let settled = false
  const alive = (): boolean => !stoppedWatch && generation === gen && current === sock
  const drop = (): void => {
    if (settled) return
    settled = true
    const wasCurrent = current === sock
    if (wasCurrent) {
      current = null
      socketLive = false
    }
    sock.removeAllListeners()
    sock.on('error', () => {
      // destroy() can emit error after the real handlers are gone. Swallow it.
    })
    try {
      sock.destroy()
    } catch {
      // Already closed. Reconnect is scheduled below when this watch is still active.
    }
    if (!stoppedWatch && generation === gen && wasCurrent) scheduleReconnect()
  }
  sock.setEncoding('utf8')
  sock.on('connect', () => {
    if (!alive()) return
    socketLive = true
    nextDelayMs = reconnectDelayMs
    cache = { state: 'unknown' }
  })
  sock.on('data', (chunk: string | Buffer) => {
    if (!alive()) return
    try {
      const text = typeof chunk === 'string' ? chunk : chunk.toString('utf8')
      const consumed = consumeLines(buffer, text)
      buffer = consumed.buffer
      for (const line of consumed.lines) applySocketLine(line)
    } catch (error) {
      warnOnce('hyprland socket2 read failed', error)
    }
  })
  sock.on('error', (error) => {
    if (alive()) warnOnce('hyprland socket2 failed', error)
    drop()
  })
  sock.on('close', () => {
    drop()
  })
}

function toActive(win: PolledWindow): ActiveApp {
  return {
    appName: win.appName,
    title: win.title,
    exePath: win.exePath,
    pid: win.pid,
    source: 'hyprland'
  }
}

type Poll =
  | { ok: true; window: PolledWindow | null }
  | { ok: false }

function jsonObject(text: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(text)
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    return value as Record<string, unknown>
  } catch {
    return null
  }
}

async function pollHyprctl(): Promise<Poll> {
  let stdout = ''
  try {
    stdout = await execText('hyprctl', ['activewindow', '-j'], HYPRCTL_TIMEOUT_MS)
  } catch (error) {
    warnOnce('hyprctl activewindow failed', error)
    return { ok: false }
  }
  const record = jsonObject(stdout)
  if (!record) {
    warnOnce('hyprctl activewindow returned invalid JSON')
    return { ok: false }
  }
  const parsed = parseHyprlandWindow(record)
  if (!parsed) return { ok: true, window: null }
  const exePath = parsed.pid != null ? (await exeOf(parsed.pid)).path : ''
  return {
    ok: true,
    window: { appName: parsed.appName, title: parsed.title, pid: parsed.pid, exePath }
  }
}

export async function queryHyprland(): Promise<ActiveApp | null> {
  ensureHyprlandWatch()
  if (socketLive && cache.state === 'empty') return null
  if (socketLive && cache.state === 'window' && cache.pid != null) return toActive(cache)

  const polled = await pollHyprctl()
  const latest = cache
  if (socketLive && latest.state === 'window') {
    if (polled.ok && polled.window && polled.window.appName === latest.appName) {
      latest.pid = polled.window.pid
      latest.exePath = polled.window.exePath
    }
    return toActive(latest)
  }
  if (socketLive && latest.state === 'empty') return null
  if (polled.ok && polled.window) {
    cache = { state: 'window', ...polled.window }
    return toActive(polled.window)
  }
  if (polled.ok) cache = { state: 'empty' }
  return null
}
