export function clampHour(hour: number): number {
  if (!Number.isFinite(hour)) return 0;
  return Math.min(23, Math.max(0, Math.floor(hour)));
}

export function clampIdle(seconds: number): number {
  if (!Number.isFinite(seconds)) return 60;
  return Math.min(3600, Math.max(15, Math.round(seconds)));
}

export function dateKey(when: Date): string {
  const y = when.getFullYear();
  const m = String(when.getMonth() + 1).padStart(2, '0');
  const d = String(when.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Calendar date of the logical day that contains `when`. */
export function logicalDateKey(when: Date, dayStartHour: number): string {
  const shifted = new Date(when.getTime() - clampHour(dayStartHour) * 3_600_000);
  return dateKey(shifted);
}

/**
 * Real timestamp when the logical day containing `when` ends.
 * A day starting at 04:00 runs until 04:00 the next calendar morning.
 */
export function logicalDayEndMs(when: Date, dayStartHour: number): number {
  const start = clampHour(dayStartHour);
  const logical = new Date(when.getTime() - start * 3_600_000);
  const nextMidnight = new Date(logical.getFullYear(), logical.getMonth(), logical.getDate() + 1, 0, 0, 0, 0);
  return nextMidnight.getTime() + start * 3_600_000;
}

export function shiftDateKey(key: string, days: number): string {
  const [y, m, d] = key.split('-').map(Number);
  const dt = new Date(y || 1970, (m || 1) - 1, (d || 1) + days, 12, 0, 0, 0);
  return dateKey(dt);
}

/** Inclusive range ending at `endKey`, oldest first. */
export function dateRange(endKey: string, count: number): string[] {
  const keys: string[] = [];
  const n = Math.max(0, Math.floor(count));
  for (let i = n - 1; i >= 0; i -= 1) keys.push(shiftDateKey(endKey, -i));
  return keys;
}

export function minutesOfDay(when: Date): number {
  return when.getHours() * 60 + when.getMinutes();
}

/**
 * Seconds to keep from an open segment that ends at `now`.
 * A gap longer than `cap` (a sleep, a closed laptop) is not back-filled:
 * only `cap` seconds are counted, and the next segment starts at `now`.
 */
export function clipSegment(
  segmentStart: number,
  now: number,
  cap = 180,
): { seconds: number; endMs: number; nextSegmentStart: number } | null {
  const available = Math.floor((now - segmentStart) / 1000);
  if (available <= 0) return null;
  const seconds = Math.min(cap, available);
  const endMs = segmentStart + seconds * 1000;
  return { seconds, endMs, nextSegmentStart: available > cap ? now : endMs };
}

/**
 * Split a whole-second span that ends at `endMs` across logical days.
 * Sub-second leftovers stay uncounted; the two sides always sum to `seconds`.
 */
export function allocateSeconds(
  endMs: number,
  seconds: number,
  dayStartHour: number,
): { key: string; seconds: number }[] {
  if (!Number.isFinite(seconds) || seconds <= 0 || !Number.isFinite(endMs)) return [];
  const whole = Math.floor(seconds);
  if (whole <= 0) return [];
  const startMs = endMs - whole * 1000;
  const startKey = logicalDateKey(new Date(startMs), dayStartHour);
  const endKey = logicalDateKey(new Date(endMs - 1), dayStartHour);
  if (startKey === endKey) return [{ key: endKey, seconds: whole }];
  const boundary = logicalDayEndMs(new Date(startMs), dayStartHour);
  let before = Math.floor((boundary - startMs) / 1000);
  if (before < 0) before = 0;
  if (before > whole) before = whole;
  const after = whole - before;
  const parts: { key: string; seconds: number }[] = [];
  if (before > 0) parts.push({ key: startKey, seconds: before });
  if (after > 0) parts.push({ key: endKey, seconds: after });
  return parts;
}

export function parseClock(value: string): number | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const h = Number(match[1]);
  const m = Number(match[2]);
  if (h > 23 || m > 59) return null;
  return h * 60 + m;
}

export function formatClock(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}
