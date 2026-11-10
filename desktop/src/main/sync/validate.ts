import type {
  SyncAppCategory,
  SyncCategory,
  SyncFlag,
  SyncGrant,
  SyncLimit,
  SyncPreferences,
  SyncSession,
  SyncSnapshot
} from './provider.ts'
import { isLocale } from '@shared/types'

const DAY = /^\d{4}-\d{2}-\d{2}$/
const COLOR = /^#[0-9a-fA-F]{6}$/
const BUILTIN = new Set(['productif', 'social', 'divertissement', 'autre'])
const FLAGS = new Set(['n80', 'n100', 'ignored'])
const MIN_TS = Date.UTC(2020, 0, 1)
const MAX_TS = Date.UTC(2100, 0, 1)

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed || trimmed.length > max) return null
  return trimmed
}

function stamp(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) return null
  if (value < MIN_TS || value > MAX_TS) return null
  return value
}

function session(value: unknown): SyncSession | null {
  if (!isRecord(value)) return null
  const appName = text(value.appName, 200)
  const title = typeof value.title === 'string' ? value.title.replace(/\s+/g, ' ').trim().slice(0, 300) : null
  const exePath = typeof value.exePath === 'string' ? value.exePath.trim().slice(0, 500) : null
  const startedAt = stamp(value.startedAt)
  const endedAt = stamp(value.endedAt)
  const durationMs = value.durationMs
  const day = typeof value.day === 'string' && DAY.test(value.day) ? value.day : null
  if (!appName || title == null || exePath == null || startedAt == null || endedAt == null || !day) return null
  if (typeof durationMs !== 'number' || !Number.isFinite(durationMs) || durationMs < 0 || durationMs > 48 * 60 * 60 * 1000) {
    return null
  }
  if (endedAt < startedAt) return null
  return { appName, title, exePath, startedAt, endedAt, durationMs: Math.round(durationMs), day }
}

function limit(value: unknown): SyncLimit | null {
  if (!isRecord(value)) return null
  const appName = text(value.appName, 200)
  const minutes = value.dailyMinutes
  if (!appName || typeof minutes !== 'number' || !Number.isFinite(minutes)) return null
  const dailyMinutes = Math.round(minutes)
  if (dailyMinutes < 1 || dailyMinutes > 1440) return null
  if (typeof value.strict !== 'boolean') return null
  return { appName, dailyMinutes, strict: value.strict }
}

function grant(value: unknown): SyncGrant | null {
  if (!isRecord(value)) return null
  const appName = text(value.appName, 200)
  const day = typeof value.day === 'string' && DAY.test(value.day) ? value.day : null
  const createdAt = stamp(value.createdAt)
  const minutes = value.minutes
  if (!appName || !day || createdAt == null || typeof minutes !== 'number' || !Number.isFinite(minutes)) return null
  const rounded = Math.round(minutes)
  if (rounded < 1 || rounded > 60) return null
  return { appName, day, minutes: rounded, createdAt }
}

function flag(value: unknown): SyncFlag | null {
  if (!isRecord(value)) return null
  const appName = text(value.appName, 200)
  const day = typeof value.day === 'string' && DAY.test(value.day) ? value.day : null
  const kind = value.kind
  if (!appName || !day || typeof kind !== 'string' || !FLAGS.has(kind)) return null
  return { appName, day, kind: kind as SyncFlag['kind'] }
}

function category(value: unknown): SyncCategory | null {
  if (!isRecord(value)) return null
  const name = text(value.name, 40)
  const remoteId = value.remoteId
  const color = typeof value.color === 'string' && COLOR.test(value.color) ? value.color : null
  if (!name || color == null || typeof remoteId !== 'number' || !Number.isSafeInteger(remoteId) || remoteId < 0) return null
  const builtin = value.builtinKey
  if (builtin != null && (typeof builtin !== 'string' || !BUILTIN.has(builtin))) return null
  return {
    remoteId,
    name,
    color,
    builtinKey: builtin == null ? null : (builtin as SyncCategory['builtinKey'])
  }
}

function appCategory(value: unknown): SyncAppCategory | null {
  if (!isRecord(value)) return null
  const appName = text(value.appName, 200)
  const remoteCategoryId = value.remoteCategoryId
  if (!appName || typeof remoteCategoryId !== 'number' || !Number.isSafeInteger(remoteCategoryId)) return null
  return { appName, remoteCategoryId }
}

function preferences(value: unknown): SyncPreferences | null {
  if (!isRecord(value)) return null
  const theme = value.theme === 'light' || value.theme === 'dark' || value.theme === 'system' ? value.theme : null
  const locale = isLocale(value.locale) ? value.locale : null
  if (!theme || !locale || typeof value.dailySummaryEnabled !== 'boolean') return null
  const num = (input: unknown, min: number, max: number): number | null => {
    if (typeof input !== 'number' || !Number.isFinite(input)) return null
    const rounded = Math.round(input)
    if (rounded < min || rounded > max) return null
    return rounded
  }
  const idleThresholdSec = num(value.idleThresholdSec, 10, 3600)
  const breakIntervalMin = num(value.breakIntervalMin, 0, 240)
  const dayStartHour = num(value.dayStartHour, 0, 23)
  const dailySummaryHour = num(value.dailySummaryHour, 0, 23)
  const grantMinutes = num(value.grantMinutes, 1, 60)
  const maxGrantsPerDay = num(value.maxGrantsPerDay, 0, 50)
  if (
    idleThresholdSec == null ||
    breakIntervalMin == null ||
    dayStartHour == null ||
    dailySummaryHour == null ||
    grantMinutes == null ||
    maxGrantsPerDay == null
  ) {
    return null
  }
  return {
    theme,
    locale,
    idleThresholdSec,
    breakIntervalMin,
    dayStartHour,
    dailySummaryEnabled: value.dailySummaryEnabled,
    dailySummaryHour,
    grantMinutes,
    maxGrantsPerDay
  }
}

function rows<T>(value: unknown, max: number, read: (item: unknown) => T | null): T[] | null {
  if (!Array.isArray(value) || value.length > max) return null
  const cleaned: T[] = []
  for (const item of value) {
    const next = read(item)
    if (next) cleaned.push(next)
  }
  return cleaned
}

export function sanitizeSnapshot(value: unknown): SyncSnapshot | null {
  if (!isRecord(value) || value.schema !== 1 || value.app !== 'temps-ecran-desktop') return null
  if (typeof value.exportedAt !== 'string' || value.exportedAt.length > 40) return null
  if (typeof value.deviceId !== 'string' || value.deviceId.length < 8 || value.deviceId.length > 80) return null
  const sessions = rows(value.sessions, 100_000, session)
  const limits = rows(value.limits, 2_000, limit)
  const grants = rows(value.grants, 20_000, grant)
  const flags = rows(value.flags, 20_000, flag)
  const categories = rows(value.categories, 200, category)
  const appCategories = rows(value.appCategories, 5_000, appCategory)
  const prefs = preferences(value.preferences)
  if (!sessions || !limits || !grants || !flags || !categories || !appCategories || !prefs) return null
  return {
    schema: 1,
    app: 'temps-ecran-desktop',
    exportedAt: value.exportedAt,
    deviceId: value.deviceId,
    sessions,
    limits,
    grants,
    flags,
    categories,
    appCategories,
    preferences: prefs
  }
}
