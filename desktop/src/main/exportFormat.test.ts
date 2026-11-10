import { describe, expect, it } from 'vitest'
import {
  allSettings,
  closeDatabase,
  insertFinishedSession,
  listAppCategories,
  listCategories,
  listLimits,
  listSessions,
  openDatabase,
  setSettingRaw,
  upsertLimit
} from './db'
import { csvEscape, sessionsToCsv, toExportJson, type CsvSession } from './exportFormat'

const started = Date.parse('2026-06-02T07:15:00.000Z')

function row(patch: Partial<CsvSession> = {}): CsvSession {
  return {
    day: '2026-06-02',
    app_name: 'Terminal',
    title: 'notes',
    exe_path: '/usr/bin/local-app',
    started_at: started,
    ended_at: started + 1_500,
    duration_ms: 1_500,
    ...patch
  }
}

describe('csv and json export', () => {
  it('quotes special fields, rounds seconds, and keeps a header', () => {
    expect(csvEscape('plain')).toBe('plain')
    expect(csvEscape('say "hi", now')).toBe('"say ""hi"", now"')
    expect(sessionsToCsv([row({ exe_path: 'line\npath' })]).includes('"line\npath"')).toBe(true)
    const csv = sessionsToCsv([
      row({ title: 'say "hi", now', app_name: 'App, une', duration_ms: 1_499 }),
      row({ app_name: 'Writer', started_at: started + 5_000, ended_at: started + 6_000, duration_ms: 1_000 })
    ])
    expect(csv.charCodeAt(0)).toBe(0xfeff)
    const lines = csv.slice(1).trimEnd().split('\n')
    expect(lines).toEqual([
      'jour,application,titre,chemin,debut,fin,duree_secondes',
      `2026-06-02,"App, une","say ""hi"", now",/usr/bin/local-app,${new Date(started).toISOString()},${new Date(started + 1_500).toISOString()},1`,
      `2026-06-02,Writer,notes,/usr/bin/local-app,${new Date(started + 5_000).toISOString()},${new Date(started + 6_000).toISOString()},1`
    ])
    expect(sessionsToCsv([])).toBe('\uFEFFjour,application,titre,chemin,debut,fin,duree_secondes\n')
  })

  it('writes the local rows and removes the sync token', () => {
    openDatabase(':memory:')
    try {
      insertFinishedSession({
        appName: 'Terminal',
        title: 'secret-title',
        exePath: '/usr/bin/local-app',
        startedAt: started,
        endedAt: started + 2_000,
        durationMs: 2_000,
        day: '2026-06-02'
      })
      upsertLimit('Terminal', 25, true)
      setSettingRaw('localeNote', 'fr')
      setSettingRaw(
        'syncConfig',
        JSON.stringify({
          baseUrl: 'http://127.0.0.1:9',
          token: 'secret-token-1',
          passphrase: 'phrase-secrete-9',
          secret: 'raw-key-should-not-export',
          key: 'also-secret',
          enabled: true
        })
      )
      setSettingRaw('syncConfigBroken', '{')
      const settings = allSettings()
      const json = toExportJson({
        version: '1.0.0',
        exportedAt: '2026-06-02T12:00:00.000Z',
        sessions: listSessions(),
        limits: listLimits(),
        categories: listCategories(),
        appCategories: listAppCategories(),
        settings
      })
      expect(json.includes('secret-token-1')).toBe(false)
      expect(json.includes('phrase-secrete-9')).toBe(false)
      expect(json.includes('raw-key-should-not-export')).toBe(false)
      expect(json.includes('also-secret')).toBe(false)
      expect(json.includes('secret-title')).toBe(true)
      const parsed = JSON.parse(json) as {
        app: string
        version: string
        exportedAt: string
        sessions: { app_name: string; duration_ms: number }[]
        limits: { app_name: string; daily_minutes: number; strict: number }[]
        categories: { builtin_key: string | null }[]
        settings: { key: string; value: string }[]
      }
      expect(parsed.app).toBe('temps-ecran-desktop')
      expect(parsed.version).toBe('1.0.0')
      expect(parsed.exportedAt).toBe('2026-06-02T12:00:00.000Z')
      expect(parsed.sessions).toEqual([
        expect.objectContaining({ app_name: 'Terminal', duration_ms: 2_000, title: 'secret-title' })
      ])
      expect(parsed.limits).toEqual([{ app_name: 'Terminal', daily_minutes: 25, strict: 1 }])
      expect(parsed.categories.map((item) => item.builtin_key).sort()).toEqual([
        'autre',
        'divertissement',
        'productif',
        'social'
      ])
      const sync = parsed.settings.find((item) => item.key === 'syncConfig')
      expect(JSON.parse(sync?.value ?? '{}')).toEqual({
        baseUrl: 'http://127.0.0.1:9',
        token: '',
        passphrase: '',
        secret: '',
        key: '',
        enabled: true
      })
      expect(parsed.settings.find((item) => item.key === 'localeNote')?.value).toBe('fr')
      expect(parsed.settings.find((item) => item.key === 'syncConfigBroken')?.value).toBe('{')
      const csv = sessionsToCsv(listSessions())
      expect(csv.includes('Terminal')).toBe(true)
      expect(csv.includes('secret-token-1')).toBe(false)
      expect(csv.includes(',2\n')).toBe(true)
    } finally {
      closeDatabase()
    }
  })
})
