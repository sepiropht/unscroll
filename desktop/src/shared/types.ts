export const LOCALES = ['en', 'fr', 'es', 'de', 'pt'] as const
export type Locale = (typeof LOCALES)[number]

const LOCALE_SET = new Set<string>(LOCALES)

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && LOCALE_SET.has(value)
}
export type ThemeSetting = 'light' | 'dark' | 'system'
export type WindowSource = 'get-windows' | 'xdotool' | 'xprop' | 'hyprland' | 'sway' | 'none'

export type WindowProviderStatus = {
  name: string
  supported: boolean
}

export type Settings = {
  theme: ThemeSetting
  locale: Locale
  idleThresholdSec: number
  breakIntervalMin: number
  dayStartHour: number
  autostart: boolean
  dailySummaryEnabled: boolean
  dailySummaryHour: number
  grantMinutes: number
  maxGrantsPerDay: number
  trackingPaused: boolean
}

export type AppUsage = {
  appName: string
  durationMs: number
  categoryId: number | null
  categoryName: string
  categoryColor: string
  automatic: boolean
}

export type CategoryRecord = {
  id: number
  name: string
  color: string
  builtinKey: string | null
  durationMs: number
}

export type LimitRecord = {
  appName: string
  dailyMinutes: number
  strict: boolean
  usedMs: number
  extraMinutes: number
  grantCount: number
}

export type TrackerView = {
  appName: string | null
  title: string | null
  source: WindowSource
  paused: boolean
  idle: boolean
  locked: boolean
  suspended: boolean
  provider: WindowProviderStatus
}

export type Snapshot = {
  now: number
  version: string
  day: string
  todayTotalMs: number
  current: TrackerView
  topApps: AppUsage[]
  hourly: { hour: number; durationMs: number }[]
  week: { day: string; durationMs: number }[]
  categories: CategoryRecord[]
  knownApps: { appName: string; durationMs: number; categoryId: number | null }[]
  limits: LimitRecord[]
  settings: Settings
}

export type HistoryDay = {
  day: string
  totalMs: number
  apps: { appName: string; durationMs: number }[]
}

export type OverlayPayload = {
  appName: string
  usedMs: number
  limitMs: number
  strict: boolean
  grantMinutes: number
  grantsUsed: number
  maxGrants: number
  locale: Locale
}

export type ExportResult =
  | { ok: true; path: string }
  | { ok: false; canceled: true }
  | { ok: false; canceled: false; message: string }

export type ActionResult = { ok: true } | { ok: false; reason: string }

export type SyncSecretKind = 'raw' | 'passphrase'

export type SyncPublicState = {
  available: boolean
  enabled: boolean
  baseUrl: string
  tokenSet: boolean
  passphraseSet: boolean
  secretKind: SyncSecretKind | null
  lastPushAt: string | null
  lastPullAt: string | null
  lastError: string | null
}

export type SyncConfigInput = {
  enabled: boolean
  baseUrl: string
  token?: string
  passphrase?: string
  secret?: string
  secretKind?: SyncSecretKind
  clearToken?: boolean
  clearPassphrase?: boolean
}

export type SyncSaveResult = { ok: true; state: SyncPublicState } | { ok: false; reason: string }

export type SyncOutcome = { ok: true; at: string; changed: boolean } | { ok: false; reason: string }

export type PairingLinkResult = { ok: true; link: string } | { ok: false; reason: string }

export type PairingApplyResult =
  | { ok: true; state: SyncPublicState; at: string; changed: boolean }
  | { ok: false; reason: string; state?: SyncPublicState }

export type AdvancedReport = {
  kind: 'advanced-report-v1'
  thisWeekMs: number
  prevWeekMs: number
  deltaMs: number
  days: { day: string; thisMs: number; prevMs: number }[]
  apps: { appName: string; thisMs: number; prevMs: number; deltaMs: number }[]
}

export type UnscrollApi = {
  getSnapshot: () => Promise<Snapshot>
  getHistory: () => Promise<HistoryDay[]>
  saveLimit: (input: { appName: string; dailyMinutes: number; strict: boolean }) => Promise<ActionResult>
  deleteLimit: (appName: string) => Promise<ActionResult>
  saveCategory: (input: { id?: number; name: string; color: string }) => Promise<ActionResult>
  deleteCategory: (id: number) => Promise<ActionResult>
  setAppCategory: (appName: string, categoryId: number | null) => Promise<ActionResult>
  updateSettings: (patch: Partial<Settings>) => Promise<Settings>
  setPaused: (paused: boolean) => Promise<Settings>
  exportData: (format: 'csv' | 'json') => Promise<ExportResult>
  getOverlayState: () => Promise<OverlayPayload | null>
  grantOverlay: () => Promise<ActionResult>
  ignoreOverlay: () => Promise<ActionResult>
  quitFromOverlay: () => Promise<void>
  getSyncState: () => Promise<SyncPublicState>
  saveSync: (input: SyncConfigInput) => Promise<SyncSaveResult>
  pushSync: () => Promise<SyncOutcome>
  pullSync: () => Promise<SyncOutcome>
  getPairingLink: () => Promise<PairingLinkResult>
  applyPairingLink: (link: string) => Promise<PairingApplyResult>
  getReport: () => Promise<AdvancedReport | null>
  onTick: (cb: (snapshot: Snapshot) => void) => () => void
  onOverlay: (cb: (payload: OverlayPayload | null) => void) => () => void
}
