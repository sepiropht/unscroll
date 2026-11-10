import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  closeDatabase,
  insertFinishedSession,
  insertSession,
  knownApps,
  openDatabase,
  updateSession,
  usageByApp,
  usageByHour,
  usageForApp,
  usageForDays,
  usageTotal
} from './db'

function finished(input: {
  appName: string
  durationMs: number
  day: string
  startedAt: number
  title?: string
}): void {
  insertFinishedSession({
    appName: input.appName,
    title: input.title ?? '',
    exePath: '/usr/bin/local-app',
    startedAt: input.startedAt,
    endedAt: input.startedAt + input.durationMs,
    durationMs: input.durationMs,
    day: input.day
  })
}

describe('time aggregated by application', () => {
  beforeEach(() => {
    openDatabase(':memory:')
  })

  afterEach(() => {
    closeDatabase()
  })

  it('sums every session of an application and ranks that day', () => {
    const morning = new Date(2026, 5, 2, 9, 0, 0, 0).getTime()
    finished({ appName: 'Terminal', title: 'shell', durationMs: 1_000, day: '2026-06-02', startedAt: morning })
    finished({ appName: 'Terminal', title: 'build', durationMs: 2_500, day: '2026-06-02', startedAt: morning + 60_000 })
    finished({ appName: 'Writer', durationMs: 4_000, day: '2026-06-02', startedAt: morning + 120_000 })
    finished({
      appName: 'Terminal',
      durationMs: 9_000,
      day: '2026-06-03',
      startedAt: new Date(2026, 5, 3, 9, 0, 0, 0).getTime()
    })

    expect(usageByApp('2026-06-02')).toEqual([
      { appName: 'Writer', durationMs: 4_000 },
      { appName: 'Terminal', durationMs: 3_500 }
    ])
    expect(usageForApp('2026-06-02', 'Terminal')).toBe(3_500)
    expect(usageForApp('2026-06-02', 'Absente')).toBe(0)
    expect(usageTotal('2026-06-02')).toBe(7_500)
    expect(usageTotal('2026-06-01')).toBe(0)
    expect(usageByApp('2026-06-03')).toEqual([{ appName: 'Terminal', durationMs: 9_000 }])
  })

  it('groups the local hour and keeps each day separate', () => {
    const nine = new Date(2026, 5, 2, 9, 15, 0, 0).getTime()
    const fifteen = new Date(2026, 5, 2, 15, 0, 0, 0).getTime()
    finished({ appName: 'Terminal', durationMs: 2_000, day: '2026-06-02', startedAt: nine })
    finished({ appName: 'Writer', durationMs: 3_000, day: '2026-06-02', startedAt: fifteen })
    const id = insertSession({
      appName: 'Terminal',
      title: 'live',
      exePath: '/usr/bin/local-app',
      startedAt: nine + 1_000,
      day: '2026-06-02'
    })
    updateSession(id, nine + 4_000, 3_000)

    expect(usageByHour('2026-06-02').sort((a, b) => a.hour - b.hour)).toEqual([
      { hour: 9, durationMs: 5_000 },
      { hour: 15, durationMs: 3_000 }
    ])
    expect(usageForDays([])).toEqual([])
    expect(usageForDays(['2026-06-02', '2026-06-01']).sort((a, b) => a.day.localeCompare(b.day))).toEqual([
      { day: '2026-06-02', durationMs: 8_000 }
    ])
    expect(knownApps()).toEqual([
      { appName: 'Terminal', durationMs: 5_000 },
      { appName: 'Writer', durationMs: 3_000 }
    ])
  })
})
