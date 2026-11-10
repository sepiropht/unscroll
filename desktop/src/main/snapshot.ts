import { app } from 'electron'
import { categoryLabel, guessBuiltinKey } from '@shared/categories'
import { dayKey, recentDays } from '@shared/day'
import { hoursFromDayStart } from '@shared/planning'
import type { AppUsage, CategoryRecord, HistoryDay, LimitRecord, Snapshot } from '@shared/types'
import {
  grantSummary,
  historyDays,
  knownApps,
  listAppCategories,
  listCategories,
  listLimits,
  usageByApp,
  usageByHour,
  usageForApp,
  usageForDays,
  usageTotal
} from './db'
import { getSettings } from './settings'
import { getTrackerState } from './tracker'

export function buildSnapshot(): Snapshot {
  const settings = getSettings()
  const now = Date.now()
  const day = dayKey(now, settings.dayStartHour)
  const categories = listCategories()
  const assignments = new Map(listAppCategories().map((row) => [row.appName, row.categoryId]))
  const byId = new Map(categories.map((row) => [row.id, row]))

  const resolve = (appName: string): Pick<AppUsage, 'categoryId' | 'categoryName' | 'categoryColor' | 'automatic'> => {
    const assigned = assignments.get(appName)
    const explicit = assigned != null ? byId.get(assigned) : undefined
    if (explicit) {
      return {
        categoryId: explicit.id,
        categoryName: categoryLabel(explicit.builtin_key, explicit.name, settings.locale),
        categoryColor: explicit.color,
        automatic: false
      }
    }
    const builtin =
      categories.find((row) => row.builtin_key === guessBuiltinKey(appName)) ??
      categories.find((row) => row.builtin_key === 'autre')
    return {
      categoryId: null,
      categoryName: builtin
        ? categoryLabel(builtin.builtin_key, builtin.name, settings.locale)
        : categoryLabel('autre', 'Autre', settings.locale),
      categoryColor: builtin?.color ?? '#64748B',
      automatic: true
    }
  }

  const apps = usageByApp(day)
  const topApps: AppUsage[] = apps.slice(0, 8).map((appRow) => ({
    appName: appRow.appName,
    durationMs: appRow.durationMs,
    ...resolve(appRow.appName)
  }))

  const categoryTotals = new Map<number, number>()
  for (const appRow of apps) {
    const assigned = assignments.get(appRow.appName)
    const explicit = assigned != null ? byId.get(assigned) : undefined
    const builtin = categories.find((row) => row.builtin_key === guessBuiltinKey(appRow.appName))
    const id = explicit?.id ?? builtin?.id
    if (id == null) continue
    categoryTotals.set(id, (categoryTotals.get(id) ?? 0) + appRow.durationMs)
  }

  const hourMap = new Map(usageByHour(day).map((row) => [row.hour, row.durationMs]))
  const hourly = hoursFromDayStart(settings.dayStartHour).map((hour) => ({
    hour,
    durationMs: hourMap.get(hour) ?? 0
  }))

  const days = recentDays(day, 7)
  const weekMap = new Map(usageForDays(days).map((row) => [row.day, row.durationMs]))
  const week = days.map((item) => ({ day: item, durationMs: weekMap.get(item) ?? 0 }))

  const categoryRecords: CategoryRecord[] = categories.map((row) => ({
    id: row.id,
    name: row.name,
    color: row.color,
    builtinKey: row.builtin_key,
    durationMs: categoryTotals.get(row.id) ?? 0
  }))

  const limits: LimitRecord[] = listLimits().map((row) => {
    const grants = grantSummary(row.app_name, day)
    return {
      appName: row.app_name,
      dailyMinutes: row.daily_minutes,
      strict: row.strict === 1,
      usedMs: usageForApp(day, row.app_name),
      extraMinutes: grants.extraMinutes,
      grantCount: grants.count
    }
  })

  return {
    now,
    version: app.getVersion(),
    day,
    todayTotalMs: usageTotal(day),
    current: getTrackerState(),
    topApps,
    hourly,
    week,
    categories: categoryRecords,
    knownApps: knownApps().map((row) => ({
      appName: row.appName,
      durationMs: row.durationMs,
      categoryId: assignments.get(row.appName) ?? null
    })),
    limits,
    settings
  }
}

export function buildHistory(): HistoryDay[] {
  return historyDays(60).map((row) => ({
    day: row.day,
    totalMs: row.durationMs,
    apps: usageByApp(row.day).slice(0, 12)
  }))
}
