import { isLocale, type Settings } from '@shared/types'

const CONTROL = /[\u0000-\u001F\u007F]/

export function plainRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const proto = Object.getPrototypeOf(value) as unknown
  if (proto !== Object.prototype && proto !== null) return null
  return value as Record<string, unknown>
}

export function parseAppName(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const name = value.trim()
  if (!name || name.length > 200 || CONTROL.test(name)) return null
  return name
}

export function parseLimitInput(input: unknown): { appName: string; dailyMinutes: number; strict: boolean } | null {
  const record = plainRecord(input)
  if (!record) return null
  const appName = parseAppName(record.appName)
  if (!appName || typeof record.dailyMinutes !== 'number' || !Number.isFinite(record.dailyMinutes)) return null
  if (typeof record.strict !== 'boolean') return null
  const dailyMinutes = Math.round(record.dailyMinutes)
  if (dailyMinutes < 1 || dailyMinutes > 1440) return null
  return { appName, dailyMinutes, strict: record.strict }
}

export function parseCategorySave(input: unknown): { id?: number; name: string; color: string } | null {
  const record = plainRecord(input)
  if (!record) return null
  if (typeof record.name !== 'string') return null
  const name = record.name.trim()
  if (!name || name.length > 40 || CONTROL.test(name)) return null
  if (typeof record.color !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(record.color)) return null
  const color = record.color.toLowerCase()
  if (record.id == null) return { name, color }
  if (typeof record.id !== 'number' || !Number.isInteger(record.id) || record.id <= 0) return null
  return { id: record.id, name, color }
}

export function parseCategoryId(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) return null
  return value
}

export function parseAssign(input: unknown): { appName: string; categoryId: number | null } | null {
  const record = plainRecord(input)
  if (!record) return null
  const appName = parseAppName(record.appName)
  if (!appName) return null
  if (record.categoryId == null) return { appName, categoryId: null }
  if (typeof record.categoryId !== 'number' || !Number.isInteger(record.categoryId) || record.categoryId <= 0) {
    return null
  }
  return { appName, categoryId: record.categoryId }
}

function clampedInt(value: unknown, min: number, max: number): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined
  return Math.min(max, Math.max(min, Math.round(value)))
}

export function parseSettingsPatch(input: unknown): Partial<Settings> {
  const record = plainRecord(input)
  if (!record) return {}
  const patch: Partial<Settings> = {}
  if (record.theme === 'light' || record.theme === 'dark' || record.theme === 'system') patch.theme = record.theme
  if (isLocale(record.locale)) patch.locale = record.locale
  if (typeof record.autostart === 'boolean') patch.autostart = record.autostart
  if (typeof record.dailySummaryEnabled === 'boolean') patch.dailySummaryEnabled = record.dailySummaryEnabled
  if (typeof record.trackingPaused === 'boolean') patch.trackingPaused = record.trackingPaused
  const idle = clampedInt(record.idleThresholdSec, 10, 3600)
  if (idle != null) patch.idleThresholdSec = idle
  const breakMin = clampedInt(record.breakIntervalMin, 0, 240)
  if (breakMin != null) patch.breakIntervalMin = breakMin
  const dayStart = clampedInt(record.dayStartHour, 0, 23)
  if (dayStart != null) patch.dayStartHour = dayStart
  const summaryHour = clampedInt(record.dailySummaryHour, 0, 23)
  if (summaryHour != null) patch.dailySummaryHour = summaryHour
  const grant = clampedInt(record.grantMinutes, 1, 60)
  if (grant != null) patch.grantMinutes = grant
  const maxGrants = clampedInt(record.maxGrantsPerDay, 0, 50)
  if (maxGrants != null) patch.maxGrantsPerDay = maxGrants
  return patch
}

export function parsePaused(value: unknown): boolean | null {
  return typeof value === 'boolean' ? value : null
}

export function parseExportFormat(value: unknown): 'csv' | 'json' | null {
  return value === 'csv' || value === 'json' ? value : null
}
