import { isLocale, type Settings } from '@shared/types'
import { getSettingRaw, setSettingRaw } from './db'

export const DEFAULT_SETTINGS: Settings = {
  theme: 'system',
  locale: 'en',
  idleThresholdSec: 60,
  breakIntervalMin: 45,
  dayStartHour: 0,
  autostart: false,
  dailySummaryEnabled: true,
  dailySummaryHour: 20,
  grantMinutes: 5,
  maxGrantsPerDay: 6,
  trackingPaused: false
}

function clamp(value: unknown, min: number, max: number, fallback: number): number {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.min(max, Math.max(min, Math.round(number)))
}

function readStored(): Partial<Settings> {
  const raw = getSettingRaw('settings')
  if (!raw) return {}
  try {
    const parsed = JSON.parse(raw) as Partial<Settings>
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

/** First run follows the system language when we ship it, English otherwise. */
function systemLocale(): Settings['locale'] {
  try {
    const tag = Intl.DateTimeFormat().resolvedOptions().locale.slice(0, 2).toLowerCase()
    return isLocale(tag) ? tag : DEFAULT_SETTINGS.locale
  } catch {
    return DEFAULT_SETTINGS.locale
  }
}

export function getSettings(): Settings {
  const stored = readStored()
  const theme = stored.theme
  const locale = stored.locale
  return {
    theme: theme === 'light' || theme === 'dark' || theme === 'system' ? theme : DEFAULT_SETTINGS.theme,
    locale: isLocale(locale) ? locale : systemLocale(),
    idleThresholdSec: clamp(stored.idleThresholdSec, 10, 3600, DEFAULT_SETTINGS.idleThresholdSec),
    breakIntervalMin: clamp(stored.breakIntervalMin, 0, 240, DEFAULT_SETTINGS.breakIntervalMin),
    dayStartHour: clamp(stored.dayStartHour, 0, 23, DEFAULT_SETTINGS.dayStartHour),
    autostart: typeof stored.autostart === 'boolean' ? stored.autostart : DEFAULT_SETTINGS.autostart,
    dailySummaryEnabled:
      typeof stored.dailySummaryEnabled === 'boolean'
        ? stored.dailySummaryEnabled
        : DEFAULT_SETTINGS.dailySummaryEnabled,
    dailySummaryHour: clamp(stored.dailySummaryHour, 0, 23, DEFAULT_SETTINGS.dailySummaryHour),
    grantMinutes: clamp(stored.grantMinutes, 1, 60, DEFAULT_SETTINGS.grantMinutes),
    maxGrantsPerDay: clamp(stored.maxGrantsPerDay, 0, 50, DEFAULT_SETTINGS.maxGrantsPerDay),
    trackingPaused:
      typeof stored.trackingPaused === 'boolean' ? stored.trackingPaused : DEFAULT_SETTINGS.trackingPaused
  }
}

export function updateSettings(patch: Partial<Settings>): Settings {
  const next = getSettings()
  const merged = { ...next, ...patch }
  setSettingRaw('settings', JSON.stringify(merged))
  return getSettings()
}

export function getMarker(key: string): string | undefined {
  return getSettingRaw(key)
}

export function setMarker(key: string, value: string): void {
  setSettingRaw(key, value)
}
