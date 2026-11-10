export type Weekday = 0 | 1 | 2 | 3 | 4 | 5 | 6

export type Planning = {
  label: string
  appNames: string[]
  days: Weekday[]
  startMinutes: number
  endMinutes: number
  enabled: boolean
}

const DAY_MINUTES = 24 * 60

export function hoursFromDayStart(dayStartHour: number): number[] {
  return Array.from({ length: 24 }, (_, index) => (dayStartHour + index) % 24)
}

export function breakReminderDue(continuousMs: number, breakIntervalMin: number): boolean {
  if (breakIntervalMin <= 0) return false
  return continuousMs >= breakIntervalMin * 60_000
}

export function dailySummaryDue(input: {
  enabled: boolean
  hour: number
  now: number
  day: string
  alreadySentDay: string | undefined
}): boolean {
  if (!input.enabled) return false
  if (new Date(input.now).getHours() < input.hour) return false
  return input.alreadySentDay !== input.day
}

function minuteOf(at: Date): number {
  return at.getHours() * 60 + at.getMinutes()
}

function windowContains(startMinutes: number, endMinutes: number, minute: number): boolean {
  if (startMinutes < 0 || endMinutes < 0 || startMinutes > DAY_MINUTES || endMinutes > DAY_MINUTES) return false
  if (startMinutes === endMinutes) return false
  if (startMinutes < endMinutes) return minute >= startMinutes && minute < endMinutes
  return minute >= startMinutes || minute < endMinutes
}

export function planningBlocks(planning: Planning, appName: string, at: Date): boolean {
  if (!planning.enabled) return false
  if (!planning.appNames.includes(appName)) return false
  if (!planning.days.includes(at.getDay() as Weekday)) return false
  return windowContains(planning.startMinutes, planning.endMinutes, minuteOf(at))
}

export function blockingPlannings(plannings: Planning[], appName: string, at: Date): Planning[] {
  return plannings.filter((planning) => planningBlocks(planning, appName, at))
}
