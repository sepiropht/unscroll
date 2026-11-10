import Database from 'better-sqlite3'
import { BUILTIN_CATEGORIES } from '@shared/categories'

export type CategoryRow = {
  id: number
  name: string
  color: string
  builtin_key: string | null
}

export type LimitRow = {
  app_name: string
  daily_minutes: number
  strict: number
}

let database: Database.Database | null = null

function db(): Database.Database {
  if (!database) throw new Error('Database is not open')
  return database
}

export function openDatabase(file: string): void {
  database = new Database(file)
  database.pragma('journal_mode = WAL')
  database.pragma('foreign_keys = ON')
  database.exec(`
    CREATE TABLE IF NOT EXISTS sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      app_name TEXT NOT NULL,
      title TEXT NOT NULL DEFAULT '',
      exe_path TEXT NOT NULL DEFAULT '',
      started_at INTEGER NOT NULL,
      ended_at INTEGER NOT NULL,
      duration_ms INTEGER NOT NULL,
      day TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_sessions_day ON sessions(day);
    CREATE INDEX IF NOT EXISTS idx_sessions_app_day ON sessions(app_name, day);

    CREATE TABLE IF NOT EXISTS limits (
      app_name TEXT PRIMARY KEY,
      daily_minutes INTEGER NOT NULL,
      strict INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS limit_grants (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      app_name TEXT NOT NULL,
      day TEXT NOT NULL,
      minutes INTEGER NOT NULL,
      created_at INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS limit_flags (
      app_name TEXT NOT NULL,
      day TEXT NOT NULL,
      kind TEXT NOT NULL,
      PRIMARY KEY (app_name, day, kind)
    );

    CREATE TABLE IF NOT EXISTS categories (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      color TEXT NOT NULL,
      builtin_key TEXT UNIQUE
    );

    CREATE TABLE IF NOT EXISTS app_categories (
      app_name TEXT PRIMARY KEY,
      category_id INTEGER NOT NULL REFERENCES categories(id)
    );

    CREATE TABLE IF NOT EXISTS settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
  `)
  seedCategories()
}

export function closeDatabase(): void {
  database?.close()
  database = null
}

function seedCategories(): void {
  const count = db().prepare('SELECT COUNT(*) AS n FROM categories').get() as { n: number }
  if (count.n > 0) return
  const insert = db().prepare(
    'INSERT INTO categories (name, color, builtin_key) VALUES (?, ?, ?)'
  )
  const tx = db().transaction(() => {
    for (const category of BUILTIN_CATEGORIES) {
      insert.run(category.fr, category.color, category.key)
    }
  })
  tx()
}

export function insertSession(input: {
  appName: string
  title: string
  exePath: string
  startedAt: number
  day: string
}): number {
  const result = db()
    .prepare(
      `INSERT INTO sessions (app_name, title, exe_path, started_at, ended_at, duration_ms, day)
       VALUES (?, ?, ?, ?, ?, 0, ?)`
    )
    .run(input.appName, input.title, input.exePath, input.startedAt, input.startedAt, input.day)
  return Number(result.lastInsertRowid)
}

export function updateSession(id: number, endedAt: number, durationMs: number): void {
  db().prepare('UPDATE sessions SET ended_at = ?, duration_ms = ? WHERE id = ?').run(endedAt, durationMs, id)
}

export function usageForApp(day: string, appName: string): number {
  const row = db()
    .prepare(
      'SELECT COALESCE(SUM(duration_ms), 0) AS total FROM sessions WHERE day = ? AND app_name = ?'
    )
    .get(day, appName) as { total: number }
  return row.total
}

export function usageTotal(day: string): number {
  const row = db()
    .prepare('SELECT COALESCE(SUM(duration_ms), 0) AS total FROM sessions WHERE day = ?')
    .get(day) as { total: number }
  return row.total
}

export function usageByApp(day: string): { appName: string; durationMs: number }[] {
  const rows = db()
    .prepare(
      `SELECT app_name AS appName, SUM(duration_ms) AS durationMs
       FROM sessions WHERE day = ? GROUP BY app_name ORDER BY durationMs DESC`
    )
    .all(day) as { appName: string; durationMs: number }[]
  return rows
}

export function usageByHour(day: string): { hour: number; durationMs: number }[] {
  const rows = db()
    .prepare(
      `SELECT CAST(strftime('%H', started_at / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour,
              SUM(duration_ms) AS durationMs
       FROM sessions WHERE day = ? GROUP BY hour`
    )
    .all(day) as { hour: number; durationMs: number }[]
  return rows
}

export function usageForDays(days: string[]): { day: string; durationMs: number }[] {
  if (days.length === 0) return []
  const marks = days.map(() => '?').join(', ')
  const rows = db()
    .prepare(
      `SELECT day, SUM(duration_ms) AS durationMs FROM sessions
       WHERE day IN (${marks}) GROUP BY day`
    )
    .all(...days) as { day: string; durationMs: number }[]
  return rows
}

export function historyDays(limit: number): { day: string; durationMs: number }[] {
  return db()
    .prepare(
      `SELECT day, SUM(duration_ms) AS durationMs FROM sessions
       GROUP BY day ORDER BY day DESC LIMIT ?`
    )
    .all(limit) as { day: string; durationMs: number }[]
}

export function knownApps(): { appName: string; durationMs: number }[] {
  return db()
    .prepare(
      `SELECT app_name AS appName, SUM(duration_ms) AS durationMs
       FROM sessions GROUP BY app_name ORDER BY durationMs DESC`
    )
    .all() as { appName: string; durationMs: number }[]
}

export function listSessions(): {
  day: string
  app_name: string
  title: string
  exe_path: string
  started_at: number
  ended_at: number
  duration_ms: number
}[] {
  return db()
    .prepare(
      `SELECT day, app_name, title, exe_path, started_at, ended_at, duration_ms
       FROM sessions ORDER BY started_at ASC`
    )
    .all() as {
    day: string
    app_name: string
    title: string
    exe_path: string
    started_at: number
    ended_at: number
    duration_ms: number
  }[]
}

export function listCategories(): CategoryRow[] {
  return db()
    .prepare('SELECT id, name, color, builtin_key FROM categories ORDER BY id ASC')
    .all() as CategoryRow[]
}

export function findCategory(id: number): CategoryRow | undefined {
  return db()
    .prepare('SELECT id, name, color, builtin_key FROM categories WHERE id = ?')
    .get(id) as CategoryRow | undefined
}

export function insertCategory(name: string, color: string): number {
  const result = db()
    .prepare('INSERT INTO categories (name, color, builtin_key) VALUES (?, ?, NULL)')
    .run(name, color)
  return Number(result.lastInsertRowid)
}

export function updateCategory(id: number, name: string, color: string): void {
  db().prepare('UPDATE categories SET name = ?, color = ? WHERE id = ?').run(name, color, id)
}

export function deleteCategory(id: number): boolean {
  const row = findCategory(id)
  if (!row || row.builtin_key === 'autre') return false
  const autre = db()
    .prepare("SELECT id FROM categories WHERE builtin_key = 'autre'")
    .get() as { id: number } | undefined
  const tx = db().transaction(() => {
    if (autre) {
      db().prepare('UPDATE app_categories SET category_id = ? WHERE category_id = ?').run(autre.id, id)
    } else {
      db().prepare('DELETE FROM app_categories WHERE category_id = ?').run(id)
    }
    db().prepare('DELETE FROM categories WHERE id = ?').run(id)
  })
  tx()
  return true
}

export function listAppCategories(): { appName: string; categoryId: number }[] {
  return db()
    .prepare('SELECT app_name AS appName, category_id AS categoryId FROM app_categories')
    .all() as { appName: string; categoryId: number }[]
}

export function setAppCategory(appName: string, categoryId: number | null): void {
  if (categoryId == null) {
    db().prepare('DELETE FROM app_categories WHERE app_name = ?').run(appName)
    return
  }
  db()
    .prepare(
      `INSERT INTO app_categories (app_name, category_id) VALUES (?, ?)
       ON CONFLICT(app_name) DO UPDATE SET category_id = excluded.category_id`
    )
    .run(appName, categoryId)
}

export function listLimits(): LimitRow[] {
  return db()
    .prepare('SELECT app_name, daily_minutes, strict FROM limits ORDER BY app_name COLLATE NOCASE')
    .all() as LimitRow[]
}

export function findLimit(appName: string): LimitRow | undefined {
  return db()
    .prepare('SELECT app_name, daily_minutes, strict FROM limits WHERE app_name = ?')
    .get(appName) as LimitRow | undefined
}

export function upsertLimit(appName: string, dailyMinutes: number, strict: boolean): void {
  db()
    .prepare(
      `INSERT INTO limits (app_name, daily_minutes, strict) VALUES (?, ?, ?)
       ON CONFLICT(app_name) DO UPDATE SET daily_minutes = excluded.daily_minutes, strict = excluded.strict`
    )
    .run(appName, dailyMinutes, strict ? 1 : 0)
}

export function deleteLimit(appName: string): void {
  db().prepare('DELETE FROM limits WHERE app_name = ?').run(appName)
}

export function grantSummary(appName: string, day: string): { count: number; extraMinutes: number } {
  const row = db()
    .prepare(
      `SELECT COUNT(*) AS count, COALESCE(SUM(minutes), 0) AS extraMinutes
       FROM limit_grants WHERE app_name = ? AND day = ?`
    )
    .get(appName, day) as { count: number; extraMinutes: number }
  return row
}

export function addGrant(appName: string, day: string, minutes: number, createdAt: number): void {
  db()
    .prepare('INSERT INTO limit_grants (app_name, day, minutes, created_at) VALUES (?, ?, ?, ?)')
    .run(appName, day, minutes, createdAt)
}

export function hasFlag(appName: string, day: string, kind: string): boolean {
  const row = db()
    .prepare('SELECT 1 AS ok FROM limit_flags WHERE app_name = ? AND day = ? AND kind = ?')
    .get(appName, day, kind) as { ok: number } | undefined
  return Boolean(row)
}

export function setFlag(appName: string, day: string, kind: string): void {
  db()
    .prepare('INSERT OR IGNORE INTO limit_flags (app_name, day, kind) VALUES (?, ?, ?)')
    .run(appName, day, kind)
}

export function clearFlag(appName: string, day: string, kind: string): void {
  db().prepare('DELETE FROM limit_flags WHERE app_name = ? AND day = ? AND kind = ?').run(appName, day, kind)
}

export function getSettingRaw(key: string): string | undefined {
  const row = db().prepare('SELECT value FROM settings WHERE key = ?').get(key) as
    | { value: string }
    | undefined
  return row?.value
}

export function setSettingRaw(key: string, value: string): void {
  db()
    .prepare(
      `INSERT INTO settings (key, value) VALUES (?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value`
    )
    .run(key, value)
}

export function allSettings(): { key: string; value: string }[] {
  return db().prepare('SELECT key, value FROM settings').all() as { key: string; value: string }[]
}

export function withTransaction(work: () => void): void {
  db().transaction(work)()
}

export function findSessionByKey(input: {
  appName: string
  title: string
  exePath: string
  startedAt: number
  day: string
}): { id: number; durationMs: number } | undefined {
  return db()
    .prepare(
      `SELECT id, duration_ms AS durationMs FROM sessions
       WHERE app_name = ? AND title = ? AND exe_path = ? AND started_at = ? AND day = ?
       LIMIT 1`
    )
    .get(input.appName, input.title, input.exePath, input.startedAt, input.day) as
    | { id: number; durationMs: number }
    | undefined
}

export function insertFinishedSession(input: {
  appName: string
  title: string
  exePath: string
  startedAt: number
  endedAt: number
  durationMs: number
  day: string
}): void {
  db()
    .prepare(
      `INSERT INTO sessions (app_name, title, exe_path, started_at, ended_at, duration_ms, day)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
    .run(input.appName, input.title, input.exePath, input.startedAt, input.endedAt, input.durationMs, input.day)
}

export function listGrants(): { app_name: string; day: string; minutes: number; created_at: number }[] {
  return db()
    .prepare('SELECT app_name, day, minutes, created_at FROM limit_grants ORDER BY created_at ASC')
    .all() as { app_name: string; day: string; minutes: number; created_at: number }[]
}

export function hasIdenticalGrant(appName: string, day: string, minutes: number, createdAt: number): boolean {
  const row = db()
    .prepare(
      `SELECT 1 AS ok FROM limit_grants
       WHERE app_name = ? AND day = ? AND minutes = ? AND created_at = ?`
    )
    .get(appName, day, minutes, createdAt) as { ok: number } | undefined
  return Boolean(row)
}

export function listFlags(): { app_name: string; day: string; kind: string }[] {
  return db()
    .prepare('SELECT app_name, day, kind FROM limit_flags ORDER BY day ASC')
    .all() as { app_name: string; day: string; kind: string }[]
}

export function findCategoryByBuiltin(key: string): CategoryRow | undefined {
  return db()
    .prepare('SELECT id, name, color, builtin_key FROM categories WHERE builtin_key = ?')
    .get(key) as CategoryRow | undefined
}

export function findCategoryByName(name: string): CategoryRow | undefined {
  return db()
    .prepare('SELECT id, name, color, builtin_key FROM categories WHERE name = ? COLLATE NOCASE LIMIT 1')
    .get(name) as CategoryRow | undefined
}
