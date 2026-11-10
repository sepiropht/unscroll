import { powerMonitor } from 'electron'
import { dayKey, hourOf } from '@shared/day'
import { formatDuration } from '@shared/format'
import { t } from '@shared/i18n'
import { limitCapMs, limitPhase } from '@shared/limits'
import { breakReminderDue, dailySummaryDue } from '@shared/planning'
import type { Settings, TrackerView, WindowSource } from '@shared/types'
import { shouldPauseForIdle } from './idle'
import { queryActiveWindow, stopWindowWatch, subscribeActiveWindow, windowProviderStatus, type ActiveApp } from './activeWindow'
import { findLimit, grantSummary, hasFlag, insertSession, setFlag, updateSession, usageForApp, usageTotal } from './db'
import { getMarker, getSettings, setMarker, updateSettings } from './settings'
import { notify } from './notify'
import { hideOverlay, isOverlayHolding, showOverlay } from './overlay'

const SAMPLE_MS = 1500

type OpenSession = {
  id: number
  appName: string
  title: string
  exePath: string
  startedAt: number
  day: string
  hour: number
}

let open: OpenSession | null = null
let timer: NodeJS.Timeout | null = null
let stopped = false
let paused = false
let idle = false
let locked = false
let suspended = false
let currentApp: string | null = null
let currentTitle: string | null = null
let source: WindowSource = 'none'
let continuousMs = 0
let lastTick = Date.now()
let onUpdate: (() => void) | null = null
let tickInFlight = false
let rerunSoon = false
let unsubscribeWindow: (() => void) | null = null

export function getTrackerState(): TrackerView {
  return {
    appName: currentApp,
    title: currentTitle,
    source,
    paused,
    idle,
    locked,
    suspended,
    provider: windowProviderStatus()
  }
}

export function startTracker(update: () => void): void {
  onUpdate = update
  paused = getSettings().trackingPaused
  powerMonitor.on('lock-screen', () => {
    locked = true
    closeOpen(Date.now())
    continuousMs = 0
  })
  powerMonitor.on('unlock-screen', () => {
    locked = false
  })
  powerMonitor.on('suspend', () => {
    suspended = true
    closeOpen(Date.now())
    continuousMs = 0
  })
  powerMonitor.on('resume', () => {
    suspended = false
  })
  stopped = false
  lastTick = Date.now()
  unsubscribeWindow?.()
  unsubscribeWindow = subscribeActiveWindow(wakeSoon)
  void loop()
}

export function stopTracker(): void {
  stopped = true
  rerunSoon = false
  unsubscribeWindow?.()
  unsubscribeWindow = null
  stopWindowWatch()
  if (timer) clearTimeout(timer)
  timer = null
  closeOpen(Date.now())
}

export function setTrackingPaused(next: boolean): Settings {
  paused = next
  if (next) {
    closeOpen(Date.now())
    continuousMs = 0
    hideOverlay()
  }
  const settings = updateSettings({ trackingPaused: next })
  onUpdate?.()
  return settings
}

function closeOpen(end: number): void {
  if (!open) return
  updateSession(open.id, Math.max(end, open.startedAt), Math.max(0, end - open.startedAt))
  open = null
}

function isOurs(win: ActiveApp): boolean {
  if (win.pid != null && win.pid === process.pid) return true
  const name = win.appName.toLowerCase()
  return name === 'electron' || name === 'unscroll' || name === 'temps-ecran-desktop'
}

function arm(delay: number): void {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    timer = null
    void loop()
  }, delay)
}

function wakeSoon(): void {
  if (stopped || paused || locked || suspended) return
  if (tickInFlight) {
    rerunSoon = true
    return
  }
  arm(0)
}

async function loop(): Promise<void> {
  if (stopped) return
  if (tickInFlight) {
    rerunSoon = true
    return
  }
  tickInFlight = true
  try {
    await tick()
  } catch (error) {
    console.error('[unscroll] tick failed', error)
  } finally {
    tickInFlight = false
  }
  if (stopped) return
  const delay = rerunSoon ? 0 : SAMPLE_MS
  rerunSoon = false
  arm(delay)
}

async function tick(): Promise<void> {
  const now = Date.now()
  const delta = Math.min(Math.max(0, now - lastTick), 15_000)
  lastTick = now
  const settings = getSettings()
  if (paused || locked || suspended) {
    idle = false
    closeOpen(now)
    currentApp = null
    currentTitle = null
    continuousMs = 0
    onUpdate?.()
    return
  }

  // Wayland often leaves getSystemIdleTime() at 0. Zero means the session
  // still looks active: it must not pause tracking or fail the tick.
  let idleSec = 0
  try {
    const reading = powerMonitor.getSystemIdleTime()
    if (typeof reading === 'number' && Number.isFinite(reading)) idleSec = reading
  } catch {
    idleSec = 0
  }
  if (shouldPauseForIdle(idleSec, settings.idleThresholdSec)) {
    idle = true
    if (open) closeOpen(Math.max(open.startedAt, now - idleSec * 1000))
    currentApp = null
    currentTitle = null
    continuousMs = 0
    hideOverlay()
    maybeSummary(now, settings)
    onUpdate?.()
    return
  }
  idle = false

  const found = await queryActiveWindow()
  if (stopped) return
  const win = found && isOurs(found) ? { ...found, appName: 'Unscroll' } : found
  if (win && isOurs(found as ActiveApp) && isOverlayHolding()) {
    closeOpen(now)
    currentApp = win.appName
    currentTitle = win.title
    source = win.source
    continuousMs += delta
    maybeBreak(settings)
    maybeSummary(now, settings)
    onUpdate?.()
    return
  }

  if (!win) {
    closeOpen(now)
    currentApp = null
    currentTitle = null
    source = 'none'
    continuousMs += delta
    hideOverlay()
    maybeBreak(settings)
    maybeSummary(now, settings)
    onUpdate?.()
    return
  }

  source = win.source
  currentApp = win.appName
  currentTitle = win.title
  const day = dayKey(now, settings.dayStartHour)
  const hour = hourOf(now)
  if (open && open.appName === win.appName && open.title === win.title && open.day === day && open.hour === hour) {
    updateSession(open.id, now, now - open.startedAt)
  } else {
    closeOpen(now)
    const id = insertSession({
      appName: win.appName,
      title: win.title,
      exePath: win.exePath,
      startedAt: now,
      day
    })
    open = { id, appName: win.appName, title: win.title, exePath: win.exePath, startedAt: now, day, hour }
  }

  continuousMs += delta
  enforceLimit(win.appName, day, settings)
  maybeBreak(settings)
  maybeSummary(now, settings)
  onUpdate?.()
}

function enforceLimit(appName: string, day: string, settings: Settings): void {
  const limit = findLimit(appName)
  if (!limit) {
    hideOverlay()
    return
  }
  const grants = grantSummary(appName, day)
  const limitMs = limitCapMs(limit.daily_minutes, grants.extraMinutes)
  if (limitMs <= 0) return
  const used = usageForApp(day, appName)
  const phase = limitPhase(used, limitMs)
  if (phase === 'approach' && !hasFlag(appName, day, 'n80')) {
    setFlag(appName, day, 'n80')
    notify(
      t(settings.locale, 'notify.approachTitle', { app: appName }),
      t(settings.locale, 'notify.approachBody', {
        used: formatDuration(used, settings.locale),
        limit: formatDuration(limitMs, settings.locale)
      })
    )
  }
  if (phase === 'reached') {
    if (!hasFlag(appName, day, 'n80')) setFlag(appName, day, 'n80')
    if (!hasFlag(appName, day, 'n100')) {
      setFlag(appName, day, 'n100')
      notify(
        t(settings.locale, 'notify.reachedTitle'),
        t(settings.locale, 'notify.reachedBody', { app: appName })
      )
    }
    if (!hasFlag(appName, day, 'ignored')) {
      showOverlay({
        appName,
        usedMs: used,
        limitMs,
        strict: limit.strict === 1,
        grantMinutes: settings.grantMinutes,
        grantsUsed: grants.count,
        maxGrants: settings.maxGrantsPerDay,
        locale: settings.locale
      })
      return
    }
  }
  hideOverlay()
}

function maybeBreak(settings: Settings): void {
  if (!breakReminderDue(continuousMs, settings.breakIntervalMin)) return
  continuousMs = 0
  notify(
    t(settings.locale, 'notify.breakTitle'),
    t(settings.locale, 'notify.breakBody', { minutes: settings.breakIntervalMin })
  )
}

function maybeSummary(now: number, settings: Settings): void {
  const day = dayKey(now, settings.dayStartHour)
  if (
    !dailySummaryDue({
      enabled: settings.dailySummaryEnabled,
      hour: settings.dailySummaryHour,
      now,
      day,
      alreadySentDay: getMarker('summarySentDay')
    })
  ) {
    return
  }
  setMarker('summarySentDay', day)
  notify(
    t(settings.locale, 'notify.summaryTitle'),
    t(settings.locale, 'notify.summaryBody', { total: formatDuration(usageTotal(day), settings.locale) })
  )
}
