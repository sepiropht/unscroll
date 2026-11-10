import type { Locale } from '@shared/types'

export const SYNC_SCHEMA = 1 as const

export type SyncSession = {
  appName: string
  title: string
  exePath: string
  startedAt: number
  endedAt: number
  durationMs: number
  day: string
}

export type SyncLimit = {
  appName: string
  dailyMinutes: number
  strict: boolean
}

export type SyncGrant = {
  appName: string
  day: string
  minutes: number
  createdAt: number
}

export type SyncFlag = {
  appName: string
  day: string
  kind: 'n80' | 'n100' | 'ignored'
}

export type SyncCategory = {
  remoteId: number
  name: string
  color: string
  builtinKey: 'productif' | 'social' | 'divertissement' | 'autre' | null
}

export type SyncAppCategory = {
  appName: string
  remoteCategoryId: number
}

export type SyncPreferences = {
  theme: 'light' | 'dark' | 'system'
  locale: Locale
  idleThresholdSec: number
  breakIntervalMin: number
  dayStartHour: number
  dailySummaryEnabled: boolean
  dailySummaryHour: number
  grantMinutes: number
  maxGrantsPerDay: number
}

export type SyncSnapshot = {
  schema: typeof SYNC_SCHEMA
  app: 'temps-ecran-desktop'
  exportedAt: string
  deviceId: string
  sessions: SyncSession[]
  limits: SyncLimit[]
  grants: SyncGrant[]
  flags: SyncFlag[]
  categories: SyncCategory[]
  appCategories: SyncAppCategory[]
  preferences: SyncPreferences
}

export type SyncTransferResult = { ok: true; at: string } | { ok: false; reason: string }

export type SyncPullResult =
  | { ok: true; snapshot: SyncSnapshot | null }
  | { ok: false; reason: string }

export interface SyncProvider {
  push(snapshot: SyncSnapshot): Promise<SyncTransferResult>
  pull(): Promise<SyncPullResult>
}
