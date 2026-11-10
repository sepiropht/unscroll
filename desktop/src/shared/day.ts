export function dayKey(ts: number, dayStartHour: number): string {
  const date = new Date(ts)
  if (date.getHours() < dayStartHour) date.setDate(date.getDate() - 1)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function recentDays(endDay: string, count: number): string[] {
  const [year, month, day] = endDay.split('-').map(Number)
  const anchor = new Date(year, month - 1, day)
  const days: string[] = []
  for (let offset = count - 1; offset >= 0; offset -= 1) {
    const date = new Date(anchor)
    date.setDate(anchor.getDate() - offset)
    const y = date.getFullYear()
    const m = String(date.getMonth() + 1).padStart(2, '0')
    const d = String(date.getDate()).padStart(2, '0')
    days.push(`${y}-${m}-${d}`)
  }
  return days
}

export function hourOf(ts: number): number {
  return new Date(ts).getHours()
}
