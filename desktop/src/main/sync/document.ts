import { createHash } from 'node:crypto'
import type { SyncAppCategory, SyncCategory, SyncLimit, SyncPreferences, SyncSnapshot } from './provider.ts'
import { sanitizeSnapshot } from './validate.ts'

const MAX_REVISION = 1_000_000_000
const SECRET_KEYS = new Set(['token', 'passphrase', 'password', 'pin', 'secret', 'authorization', 'synctoken'])

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

export function revisionOf(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isInteger(value)) return null
  if (value < 0 || value > MAX_REVISION) return null
  return value
}

function stable(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map((item) => stable(item)).join(',')}]`
  if (isRecord(value)) {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${stable(value[key])}`)
      .join(',')}}`
  }
  return JSON.stringify(value) ?? 'null'
}

export function ownedDigest(value: unknown): string {
  return createHash('sha256').update(stable(value)).digest('hex')
}

export type SettingsOwned = {
  preferences: SyncPreferences | null
  limits: SyncLimit[]
  categories: SyncCategory[]
  appCategories: SyncAppCategory[]
}

export function ownedFromSnapshot(snapshot: SyncSnapshot): SettingsOwned {
  return {
    preferences: snapshot.preferences,
    limits: snapshot.limits,
    categories: snapshot.categories,
    appCategories: snapshot.appCategories
  }
}

function copyBody(remote: Record<string, unknown> | null): Record<string, unknown> {
  if (!remote) return { schedules: [], blocklist: [], whitelist: [] }
  const next: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(remote)) {
    if (SECRET_KEYS.has(key.toLowerCase())) continue
    next[key] = value
  }
  if (!Array.isArray(next.schedules)) next.schedules = []
  if (!Array.isArray(next.blocklist)) next.blocklist = []
  if (!Array.isArray(next.whitelist)) next.whitelist = []
  return next
}

export function settingsDocument(
  snapshot: SyncSnapshot,
  remoteBody: Record<string, unknown> | null,
  revision: number,
  updatedAt: string
): Record<string, unknown> {
  const owned = ownedFromSnapshot(snapshot)
  return {
    version: 1,
    kind: 'settings',
    revision,
    updatedAt,
    body: {
      ...copyBody(remoteBody),
      preferences: owned.preferences,
      limits: owned.limits,
      categories: owned.categories,
      appCategories: owned.appCategories
    }
  }
}

export type SettingsRead = {
  revision: number
  updatedAt: string
  body: Record<string, unknown>
}

export function readSettingsDocument(value: unknown): SettingsRead | null {
  if (!isRecord(value)) return null
  if (value.version !== 1 || value.kind !== 'settings') return null
  const revision = revisionOf(value.revision)
  if (revision == null || typeof value.updatedAt !== 'string' || value.updatedAt.length > 40) return null
  if (!isRecord(value.body)) return null
  return { revision, updatedAt: value.updatedAt, body: value.body }
}

export function usageDocument(snapshot: SyncSnapshot, revision: number): Record<string, unknown> {
  return {
    version: 1,
    kind: 'usage',
    revision,
    updatedAt: snapshot.exportedAt,
    platform: 'desktop',
    deviceId: snapshot.deviceId,
    snapshot
  }
}

export function readUsageDocument(value: unknown, deviceId: string): { revision: number; snapshot: SyncSnapshot } | null {
  if (!isRecord(value)) return null
  if (value.version !== 1 || value.kind !== 'usage') return null
  if (value.platform !== 'desktop' || value.deviceId !== deviceId) return null
  const revision = revisionOf(value.revision)
  if (revision == null || typeof value.updatedAt !== 'string') return null
  const snapshot = sanitizeSnapshot(value.snapshot)
  if (!snapshot || snapshot.deviceId !== deviceId) return null
  return { revision, snapshot }
}
