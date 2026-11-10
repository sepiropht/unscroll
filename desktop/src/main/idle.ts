/**
 * powerMonitor.getSystemIdleTime() is seconds since the last input.
 * On Wayland it often stays at 0 for the whole session. Zero means the
 * session still looks active, so tracking must continue. Only a positive
 * finite reading at or above a positive threshold pauses the counter.
 */
export function shouldPauseForIdle(idleSec: unknown, thresholdSec: unknown): boolean {
  if (typeof idleSec !== 'number' || typeof thresholdSec !== 'number') return false
  if (!Number.isFinite(idleSec) || !Number.isFinite(thresholdSec)) return false
  if (idleSec <= 0 || thresholdSec <= 0) return false
  return idleSec >= thresholdSec
}
