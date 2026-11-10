export type LimitPhase = 'ok' | 'approach' | 'reached'
export type LimitBarTone = '' | 'warn' | 'over'

export function limitCapMs(dailyMinutes: number, extraMinutes: number): number {
  return (dailyMinutes + extraMinutes) * 60_000
}

export function limitRatio(usedMs: number, capMs: number): number {
  if (capMs <= 0) return 0
  return usedMs / capMs
}

export function limitPhase(usedMs: number, capMs: number): LimitPhase {
  if (capMs <= 0) return 'ok'
  const ratio = usedMs / capMs
  if (ratio >= 1) return 'reached'
  if (ratio >= 0.8) return 'approach'
  return 'ok'
}

export function limitBarTone(usedMs: number, capMs: number): LimitBarTone {
  const phase = limitPhase(usedMs, capMs)
  if (phase === 'reached') return 'over'
  if (phase === 'approach') return 'warn'
  return ''
}

export function canIgnoreLimit(strict: boolean): boolean {
  return !strict
}
