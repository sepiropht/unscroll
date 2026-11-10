import { dayKey, recentDays } from '@shared/day'
import type { AdvancedReport } from '@shared/types'
import { usageByApp, usageTotal } from './db'
import { getSettings } from './settings'

export function buildAdvancedReport(): AdvancedReport {
  const settings = getSettings()
  const today = dayKey(Date.now(), settings.dayStartHour)
  const span = recentDays(today, 14)
  const previousDays = span.slice(0, 7)
  const currentDays = span.slice(7)
  const totals = new Map<string, number>()
  const total = (day: string): number => {
    const cached = totals.get(day)
    if (cached != null) return cached
    const value = usageTotal(day)
    totals.set(day, value)
    return value
  }
  const apps = new Map<string, { thisMs: number; prevMs: number }>()
  const addApps = (days: string[], field: 'thisMs' | 'prevMs'): void => {
    for (const day of days) {
      for (const row of usageByApp(day)) {
        const entry = apps.get(row.appName) ?? { thisMs: 0, prevMs: 0 }
        entry[field] += row.durationMs
        apps.set(row.appName, entry)
      }
    }
  }
  addApps(previousDays, 'prevMs')
  addApps(currentDays, 'thisMs')
  const thisWeekMs = currentDays.reduce((sum, day) => sum + total(day), 0)
  const prevWeekMs = previousDays.reduce((sum, day) => sum + total(day), 0)
  return {
    kind: 'advanced-report-v1',
    thisWeekMs,
    prevWeekMs,
    deltaMs: thisWeekMs - prevWeekMs,
    days: currentDays.map((day, index) => ({
      day,
      thisMs: total(day),
      prevMs: total(previousDays[index] ?? day)
    })),
    apps: [...apps.entries()]
      .map(([appName, value]) => ({
        appName,
        thisMs: value.thisMs,
        prevMs: value.prevMs,
        deltaMs: value.thisMs - value.prevMs
      }))
      .sort((a, b) => Math.abs(b.deltaMs) - Math.abs(a.deltaMs))
      .slice(0, 8)
  }
}
