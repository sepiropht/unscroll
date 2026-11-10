import { bestMatchingDomain } from '../shared/domain.ts';
import type { StatsMap } from '../shared/stats.ts';
import { sumDay } from '../shared/stats.ts';
import { dateRange, logicalDateKey } from '../shared/time.ts';
import type { CategoryId, FocusLogEntry } from './types.ts';

const KEEP_DAYS = 120;

export interface AdvancedReport {
  dayKey: string;
  hours: number[];
  busiestHour: number | null;
  categories: { id: CategoryId; seconds: number }[];
  weekdayAverages: { day: number; seconds: number }[];
  focus: { sessions: number; minutes: number; strict: number };
  rangeSeconds: number;
}

export function emptyHours(): number[] {
  return Array.from({ length: 24 }, () => 0);
}

export function addHourly(
  hourly: Record<string, number[]>,
  endMs: number,
  seconds: number,
  dayStartHour: number,
): Record<string, number[]> {
  if (!Number.isFinite(seconds) || seconds <= 0 || !Number.isFinite(endMs)) return hourly;
  const whole = Math.floor(seconds);
  if (whole <= 0) return hourly;
  let cursor = endMs - whole * 1000;
  const end = endMs;
  const next: Record<string, number[]> = { ...hourly };
  while (cursor < end) {
    const date = new Date(cursor);
    const hourEnd = new Date(date.getFullYear(), date.getMonth(), date.getDate(), date.getHours() + 1, 0, 0, 0).getTime();
    const sliceEnd = Math.min(end, hourEnd);
    const slice = Math.round((sliceEnd - cursor) / 1000);
    if (slice > 0) {
      const key = logicalDateKey(new Date(cursor), dayStartHour);
      const row = next[key] ? next[key].slice() : emptyHours();
      const hour = date.getHours();
      row[hour] = Math.min(86_400, (row[hour] ?? 0) + slice);
      next[key] = row;
    }
    if (sliceEnd <= cursor) break;
    cursor = sliceEnd;
  }
  return pruneHourly(next);
}

export function pruneHourly(hourly: Record<string, number[]>): Record<string, number[]> {
  const keys = Object.keys(hourly).sort();
  if (keys.length <= KEEP_DAYS) return hourly;
  const keep = new Set(keys.slice(keys.length - KEEP_DAYS));
  const next: Record<string, number[]> = {};
  for (const key of keys) {
    if (keep.has(key)) next[key] = hourly[key] ?? emptyHours();
  }
  return next;
}

export function categoryFor(host: string, categories: Record<string, CategoryId>): CategoryId {
  const rule = bestMatchingDomain(host, Object.keys(categories));
  if (!rule) return 'other';
  return categories[rule] ?? 'other';
}

function categoryTotals(stats: StatsMap, keys: readonly string[], categories: Record<string, CategoryId>): Record<CategoryId, number> {
  const totals: Record<CategoryId, number> = { productive: 0, social: 0, entertainment: 0, other: 0 };
  for (const key of keys) {
    const day = stats[key];
    if (!day) continue;
    for (const [host, usage] of Object.entries(day)) {
      totals[categoryFor(host, categories)] += usage?.seconds ?? 0;
    }
  }
  return totals;
}

export function buildAdvancedReport(input: {
  stats: StatsMap;
  hourly: Record<string, number[]>;
  categories: Record<string, CategoryId>;
  focusLog: readonly FocusLogEntry[];
  dayKey: string;
  dayStartHour: number;
}): AdvancedReport {
  const hours = (input.hourly[input.dayKey] ?? emptyHours()).slice(0, 24);
  while (hours.length < 24) hours.push(0);
  let busiestHour: number | null = null;
  let busiest = 0;
  hours.forEach((seconds, hour) => {
    if (seconds > busiest) {
      busiest = seconds;
      busiestHour = hour;
    }
  });
  const weekKeys = dateRange(input.dayKey, 7);
  const monthKeys = dateRange(input.dayKey, 28);
  const weekSet = new Set(weekKeys);
  const totals = categoryTotals(input.stats, weekKeys, input.categories);
  const buckets = Array.from({ length: 7 }, () => ({ seconds: 0, count: 0 }));
  for (const key of monthKeys) {
    const [year, month, day] = key.split('-').map(Number);
    const date = new Date(year || 1970, (month || 1) - 1, day || 1, 12, 0, 0, 0);
    const weekday = date.getDay();
    const bucket = buckets[weekday];
    if (!bucket) continue;
    bucket.seconds += sumDay(input.stats[key]);
    bucket.count += 1;
  }
  let sessions = 0;
  let minutes = 0;
  let strict = 0;
  for (const entry of input.focusLog) {
    if (!weekSet.has(logicalDateKey(new Date(entry.startedAt), input.dayStartHour))) continue;
    sessions += 1;
    const elapsed = Math.max(0, Math.round((entry.endsAt - entry.startedAt) / 60_000));
    minutes += elapsed;
    if (entry.strict) strict += 1;
  }
  const order: CategoryId[] = ['productive', 'social', 'entertainment', 'other'];
  return {
    dayKey: input.dayKey,
    hours,
    busiestHour: busiest > 0 ? busiestHour : null,
    categories: order.map((id) => ({ id, seconds: totals[id] })),
    weekdayAverages: buckets.map((bucket, day) => ({
      day,
      seconds: bucket.count > 0 ? Math.round(bucket.seconds / bucket.count) : 0,
    })),
    focus: { sessions, minutes, strict },
    rangeSeconds: weekKeys.reduce((sum, key) => sum + sumDay(input.stats[key]), 0),
  };
}

function csvCell(value: string): string {
  let cell = value;
  if (/^[=+\-@]/.test(cell)) cell = `'${cell}`;
  if (/[",\r\n]/.test(cell)) return `"${cell.replaceAll('"', '""')}"`;
  return cell;
}

export function reportToCsv(report: AdvancedReport, labels: Record<CategoryId, string>): string {
  const lines = ['section,key,seconds'];
  report.hours.forEach((seconds, hour) => {
    lines.push(['hour', String(hour), String(seconds)].join(','));
  });
  for (const row of report.categories) {
    lines.push(['category', csvCell(labels[row.id] ?? row.id), String(row.seconds)].join(','));
  }
  for (const row of report.weekdayAverages) {
    lines.push(['weekday', String(row.day), String(row.seconds)].join(','));
  }
  lines.push(['focus', 'sessions', String(report.focus.sessions)].join(','));
  lines.push(['focus', 'minutes', String(report.focus.minutes)].join(','));
  lines.push(['focus', 'strict', String(report.focus.strict)].join(','));
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}
