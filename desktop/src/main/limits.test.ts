import { canIgnoreLimit, limitBarTone, limitCapMs, limitPhase, limitRatio } from '@shared/limits'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { addGrant, closeDatabase, deleteLimit, findLimit, grantSummary, openDatabase, upsertLimit } from './db'

describe('daily limits', () => {
  beforeEach(() => {
    openDatabase(':memory:')
  })

  afterEach(() => {
    closeDatabase()
  })

  it('warns from 80 percent and counts the cap as reached', () => {
    const cap = limitCapMs(10, 0)
    expect(cap).toBe(600_000)
    expect(limitPhase(479_999, cap)).toBe('ok')
    expect(limitPhase(480_000, cap)).toBe('approach')
    expect(limitPhase(599_999, cap)).toBe('approach')
    expect(limitPhase(600_000, cap)).toBe('reached')
    expect(limitPhase(600_000, 0)).toBe('ok')
    expect(limitRatio(480_000, cap)).toBeCloseTo(0.8)
    expect(limitRatio(1, 0)).toBe(0)
    expect(limitBarTone(480_000, cap)).toBe('warn')
    expect(limitBarTone(600_000, cap)).toBe('over')
    expect(limitBarTone(1, cap)).toBe('')
    expect(canIgnoreLimit(true)).toBe(false)
    expect(canIgnoreLimit(false)).toBe(true)
  })

  it('stores a strict limit and adds extra minutes only for that day', () => {
    upsertLimit('Terminal', 30, true)
    expect(findLimit('Terminal')).toEqual({ app_name: 'Terminal', daily_minutes: 30, strict: 1 })
    upsertLimit('Terminal', 20, false)
    expect(findLimit('Terminal')).toEqual({ app_name: 'Terminal', daily_minutes: 20, strict: 0 })
    addGrant('Terminal', '2026-06-02', 5, 1_700_000_000_000)
    addGrant('Terminal', '2026-06-02', 5, 1_700_000_060_000)
    addGrant('Terminal', '2026-06-03', 15, 1_700_086_400_000)
    expect(grantSummary('Terminal', '2026-06-02')).toEqual({ count: 2, extraMinutes: 10 })
    expect(grantSummary('Writer', '2026-06-02')).toEqual({ count: 0, extraMinutes: 0 })
    const cap = limitCapMs(20, grantSummary('Terminal', '2026-06-02').extraMinutes)
    expect(cap).toBe(30 * 60_000)
    expect(limitPhase(cap, cap)).toBe('reached')
    deleteLimit('Terminal')
    expect(findLimit('Terminal')).toBeUndefined()
    expect(grantSummary('Terminal', '2026-06-02').extraMinutes).toBe(10)
  })
})
