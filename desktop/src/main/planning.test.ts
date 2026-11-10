import { dayKey, recentDays } from '@shared/day'
import {
  blockingPlannings,
  breakReminderDue,
  dailySummaryDue,
  hoursFromDayStart,
  planningBlocks,
  type Planning
} from '@shared/planning'
import { describe, expect, it } from 'vitest'

function atWeekday(weekday: number, hour: number, minute: number): Date {
  const date = new Date(2026, 5, 1, hour, minute, 0, 0)
  const delta = (weekday - date.getDay() + 7) % 7
  date.setDate(date.getDate() + delta)
  return date
}

const workday: Planning = {
  label: 'Bureau',
  appNames: ['Terminal', 'Writer'],
  days: [1, 2, 3, 4, 5],
  startMinutes: 9 * 60,
  endMinutes: 17 * 60,
  enabled: true
}

describe('plannings', () => {
  it('blocks a listed application inside a weekday window', () => {
    const tuesday = atWeekday(2, 10, 15)
    expect(tuesday.getDay()).toBe(2)
    expect(planningBlocks(workday, 'Terminal', tuesday)).toBe(true)
    expect(planningBlocks(workday, 'Terminal', atWeekday(2, 9, 0))).toBe(true)
    expect(planningBlocks(workday, 'Terminal', atWeekday(2, 16, 59))).toBe(true)
    expect(planningBlocks(workday, 'Terminal', atWeekday(2, 17, 0))).toBe(false)
    expect(planningBlocks(workday, 'Terminal', atWeekday(2, 8, 59))).toBe(false)
    expect(planningBlocks(workday, 'Jeux', tuesday)).toBe(false)
    expect(planningBlocks(workday, 'Terminal', atWeekday(6, 10, 0))).toBe(false)
    expect(planningBlocks(workday, 'Terminal', atWeekday(0, 10, 0))).toBe(false)
    expect(planningBlocks({ ...workday, enabled: false }, 'Terminal', tuesday)).toBe(false)
    expect(planningBlocks({ ...workday, appNames: [] }, 'Terminal', tuesday)).toBe(false)
  })

  it('wraps a window past midnight and returns every match', () => {
    const night: Planning = {
      label: 'Nuit',
      appNames: ['Jeux'],
      days: [0, 1, 2, 3, 4, 5, 6],
      startMinutes: 22 * 60,
      endMinutes: 6 * 60,
      enabled: true
    }
    expect(planningBlocks(night, 'Jeux', atWeekday(5, 23, 0))).toBe(true)
    expect(planningBlocks(night, 'Jeux', atWeekday(6, 5, 59))).toBe(true)
    expect(planningBlocks(night, 'Jeux', atWeekday(6, 6, 0))).toBe(false)
    expect(planningBlocks(night, 'Jeux', atWeekday(6, 12, 0))).toBe(false)
    expect(planningBlocks({ ...night, startMinutes: 8 * 60, endMinutes: 8 * 60 }, 'Jeux', atWeekday(1, 8, 0))).toBe(false)
    const at = atWeekday(2, 11, 0)
    expect(blockingPlannings([workday, { ...workday, label: 'Doublon' }, night], 'Terminal', at).map((item) => item.label)).toEqual([
      'Bureau',
      'Doublon'
    ])
  })

  it('counts the day from dayStartHour and lines hours up from there', () => {
    const early = new Date(2026, 5, 2, 3, 30, 0, 0).getTime()
    expect(dayKey(early, 4)).toBe('2026-06-01')
    expect(dayKey(early, 0)).toBe('2026-06-02')
    expect(dayKey(new Date(2026, 5, 2, 4, 0, 0, 0).getTime(), 4)).toBe('2026-06-02')
    expect(hoursFromDayStart(4)[0]).toBe(4)
    expect(hoursFromDayStart(4)[23]).toBe(3)
    expect(hoursFromDayStart(0)).toHaveLength(24)
    expect(recentDays('2026-06-07', 7)).toEqual([
      '2026-06-01',
      '2026-06-02',
      '2026-06-03',
      '2026-06-04',
      '2026-06-05',
      '2026-06-06',
      '2026-06-07'
    ])
    expect(recentDays('2026-03-01', 2)).toEqual(['2026-02-28', '2026-03-01'])
  })

  it('fires the break reminder and the daily summary on the clock', () => {
    expect(breakReminderDue(45 * 60_000 - 1, 45)).toBe(false)
    expect(breakReminderDue(45 * 60_000, 45)).toBe(true)
    expect(breakReminderDue(60 * 60_000, 0)).toBe(false)
    const morning = new Date(2026, 5, 2, 19, 59, 0, 0).getTime()
    const evening = new Date(2026, 5, 2, 20, 0, 0, 0).getTime()
    const due = {
      enabled: true,
      hour: 20,
      day: '2026-06-02',
      alreadySentDay: undefined as string | undefined
    }
    expect(dailySummaryDue({ ...due, now: morning })).toBe(false)
    expect(dailySummaryDue({ ...due, now: evening })).toBe(true)
    expect(dailySummaryDue({ ...due, now: evening, alreadySentDay: '2026-06-02' })).toBe(false)
    expect(dailySummaryDue({ ...due, now: evening, enabled: false })).toBe(false)
    expect(dailySummaryDue({ ...due, now: evening, alreadySentDay: '2026-06-01' })).toBe(true)
  })
})
