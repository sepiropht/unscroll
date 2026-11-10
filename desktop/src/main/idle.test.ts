import { describe, expect, it } from 'vitest'
import { shouldPauseForIdle } from './idle'

describe('idle readings', () => {
  it('keeps tracking when Wayland reports 0', () => {
    expect(shouldPauseForIdle(0, 60)).toBe(false)
    expect(shouldPauseForIdle(0, 0)).toBe(false)
    expect(shouldPauseForIdle(0, 10)).toBe(false)
    expect(shouldPauseForIdle(-1, 60)).toBe(false)
    expect(shouldPauseForIdle(Number.NaN, 60)).toBe(false)
    expect(shouldPauseForIdle(undefined, 60)).toBe(false)
    expect(shouldPauseForIdle(59, 60)).toBe(false)
    expect(shouldPauseForIdle(100, 0)).toBe(false)
    expect(shouldPauseForIdle(100, Number.NaN)).toBe(false)
  })

  it('pauses only for a positive reading at or above the threshold', () => {
    expect(shouldPauseForIdle(60, 60)).toBe(true)
    expect(shouldPauseForIdle(61, 60)).toBe(true)
    expect(shouldPauseForIdle(10, 10)).toBe(true)
    expect(shouldPauseForIdle(9, 10)).toBe(false)
  })
})
