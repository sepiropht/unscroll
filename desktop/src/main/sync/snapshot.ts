import { randomUUID } from 'node:crypto'
import type { Settings } from '@shared/types'
import {
  addGrant,
  findCategoryByBuiltin,
  findCategoryByName,
  findSessionByKey,
  getSettingRaw,
  hasIdenticalGrant,
  insertCategory,
  insertFinishedSession,
  listAppCategories,
  listCategories,
  listFlags,
  listGrants,
  listLimits,
  listSessions,
  setAppCategory,
  setFlag,
  setSettingRaw,
  updateCategory,
  updateSession,
  upsertLimit,
  withTransaction
} from '../db'
import { getSettings, updateSettings } from '../settings'
import type { SyncPreferences, SyncSnapshot } from './provider'
import { sanitizeSnapshot } from './validate'

function deviceId(): string {
  const existing = getSettingRaw('deviceId')
  if (existing && /^[0-9a-f-]{16,80}$/i.test(existing)) return existing
  const created = randomUUID()
  setSettingRaw('deviceId', created)
  return created
}

function preferencesOf(settings: Settings): SyncPreferences {
  return {
    theme: settings.theme,
    locale: settings.locale,
    idleThresholdSec: settings.idleThresholdSec,
    breakIntervalMin: settings.breakIntervalMin,
    dayStartHour: settings.dayStartHour,
    dailySummaryEnabled: settings.dailySummaryEnabled,
    dailySummaryHour: settings.dailySummaryHour,
    grantMinutes: settings.grantMinutes,
    maxGrantsPerDay: settings.maxGrantsPerDay
  }
}

export function buildSyncSnapshot(): SyncSnapshot {
  const settings = getSettings()
  return {
    schema: 1,
    app: 'temps-ecran-desktop',
    exportedAt: new Date().toISOString(),
    deviceId: deviceId(),
    sessions: listSessions().map((row) => ({
      appName: row.app_name,
      title: row.title,
      exePath: row.exe_path,
      startedAt: row.started_at,
      endedAt: row.ended_at,
      durationMs: row.duration_ms,
      day: row.day
    })),
    limits: listLimits().map((row) => ({
      appName: row.app_name,
      dailyMinutes: row.daily_minutes,
      strict: row.strict === 1
    })),
    grants: listGrants().map((row) => ({
      appName: row.app_name,
      day: row.day,
      minutes: row.minutes,
      createdAt: row.created_at
    })),
    flags: listFlags().flatMap((row) => {
      if (row.kind !== 'n80' && row.kind !== 'n100' && row.kind !== 'ignored') return []
      return [{ appName: row.app_name, day: row.day, kind: row.kind }]
    }),
    categories: listCategories().map((row) => ({
      remoteId: row.id,
      name: row.name,
      color: row.color,
      builtinKey:
        row.builtin_key === 'productif' ||
        row.builtin_key === 'social' ||
        row.builtin_key === 'divertissement' ||
        row.builtin_key === 'autre'
          ? row.builtin_key
          : null
    })),
    appCategories: listAppCategories().map((row) => ({
      appName: row.appName,
      remoteCategoryId: row.categoryId
    })),
    preferences: preferencesOf(settings)
  }
}

export function applySyncSnapshot(snapshot: SyncSnapshot): void {
  withTransaction(() => {
    for (const session of snapshot.sessions) {
      const existing = findSessionByKey(session)
      if (!existing) {
        insertFinishedSession(session)
        continue
      }
      if (session.durationMs > existing.durationMs) updateSession(existing.id, session.endedAt, session.durationMs)
    }
    for (const limit of snapshot.limits) upsertLimit(limit.appName, limit.dailyMinutes, limit.strict)
    for (const grant of snapshot.grants) {
      if (!hasIdenticalGrant(grant.appName, grant.day, grant.minutes, grant.createdAt)) {
        addGrant(grant.appName, grant.day, grant.minutes, grant.createdAt)
      }
    }
    for (const flag of snapshot.flags) setFlag(flag.appName, flag.day, flag.kind)
    const idMap = new Map<number, number>()
    for (const category of snapshot.categories) {
      const builtin = category.builtinKey ? findCategoryByBuiltin(category.builtinKey) : undefined
      const named = builtin ?? findCategoryByName(category.name)
      if (!named) {
        idMap.set(category.remoteId, insertCategory(category.name, category.color))
        continue
      }
      if (!named.builtin_key) updateCategory(named.id, category.name, category.color)
      idMap.set(category.remoteId, named.id)
    }
    for (const link of snapshot.appCategories) {
      const localId = idMap.get(link.remoteCategoryId)
      if (localId != null) setAppCategory(link.appName, localId)
    }
    updateSettings(snapshot.preferences)
  })
}

export function applySharedSettings(body: Record<string, unknown>): void {
  const current = preferencesOf(getSettings())
  const shell = {
    schema: 1 as const,
    app: 'temps-ecran-desktop' as const,
    exportedAt: new Date().toISOString(),
    deviceId: 'sync-settings',
    sessions: [],
    grants: [],
    flags: []
  }
  const withRemotePrefs = sanitizeSnapshot({
    ...shell,
    limits: Array.isArray(body.limits) ? body.limits : [],
    categories: Array.isArray(body.categories) ? body.categories : [],
    appCategories: Array.isArray(body.appCategories) ? body.appCategories : [],
    preferences: body.preferences
  })
  const parsed =
    withRemotePrefs ??
    sanitizeSnapshot({
      ...shell,
      limits: Array.isArray(body.limits) ? body.limits : [],
      categories: Array.isArray(body.categories) ? body.categories : [],
      appCategories: Array.isArray(body.appCategories) ? body.appCategories : [],
      preferences: current
    })
  if (!parsed) return
  withTransaction(() => {
    for (const limit of parsed.limits) upsertLimit(limit.appName, limit.dailyMinutes, limit.strict)
    const idMap = new Map<number, number>()
    for (const category of parsed.categories) {
      const builtin = category.builtinKey ? findCategoryByBuiltin(category.builtinKey) : undefined
      const named = builtin ?? findCategoryByName(category.name)
      if (!named) {
        idMap.set(category.remoteId, insertCategory(category.name, category.color))
        continue
      }
      if (!named.builtin_key) updateCategory(named.id, category.name, category.color)
      idMap.set(category.remoteId, named.id)
    }
    for (const link of parsed.appCategories) {
      const localId = idMap.get(link.remoteCategoryId)
      if (localId != null) setAppCategory(link.appName, localId)
    }
    if (withRemotePrefs) updateSettings(withRemotePrefs.preferences)
  })
}
