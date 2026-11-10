import { domainMatches } from './domain.ts';
import { allocateSeconds, logicalDateKey } from './time.ts';

export interface Usage {
  seconds: number;
  visits: number;
}

export type DayStat = Record<string, Usage>;
export type StatsMap = Record<string, DayStat>;

export interface SiteRow {
  domain: string;
  seconds: number;
  visits: number;
}

export function emptyUsage(): Usage {
  return { seconds: 0, visits: 0 };
}

export function rankSites(day: DayStat | undefined): SiteRow[] {
  if (!day) return [];
  const rows: SiteRow[] = [];
  for (const [domain, usage] of Object.entries(day)) {
    if (!usage || (usage.seconds <= 0 && usage.visits <= 0)) continue;
    rows.push({ domain, seconds: usage.seconds, visits: usage.visits });
  }
  rows.sort((a, b) => b.seconds - a.seconds || b.visits - a.visits || a.domain.localeCompare(b.domain));
  return rows;
}

export function sumDay(day: DayStat | undefined): number {
  if (!day) return 0;
  let total = 0;
  for (const usage of Object.values(day)) total += usage?.seconds ?? 0;
  return total;
}

export function sumVisits(day: DayStat | undefined): number {
  if (!day) return 0;
  let total = 0;
  for (const usage of Object.values(day)) total += usage?.visits ?? 0;
  return total;
}

export function mergeDays(stats: StatsMap, keys: readonly string[]): DayStat {
  const out: DayStat = {};
  for (const key of keys) {
    const day = stats[key];
    if (!day) continue;
    for (const [host, usage] of Object.entries(day)) {
      const prev = out[host] ?? emptyUsage();
      out[host] = {
        seconds: prev.seconds + (usage?.seconds ?? 0),
        visits: prev.visits + (usage?.visits ?? 0),
      };
    }
  }
  return out;
}

export function sumKeys(stats: StatsMap, keys: readonly string[]): number {
  return sumDay(mergeDays(stats, keys));
}

export function secondsMatching(day: DayStat | undefined, rule: string): number {
  if (!day) return 0;
  let total = 0;
  for (const [host, usage] of Object.entries(day)) {
    if (domainMatches(host, rule)) total += usage?.seconds ?? 0;
  }
  return total;
}

export function hostUsage(day: DayStat | undefined, host: string): Usage {
  return day?.[host] ?? emptyUsage();
}

/**
 * Add whole seconds and visits for one host.
 * Seconds that cross a logical-day boundary are split.
 * Visits land on the first day of the span only.
 */
export function recordUsage(
  stats: StatsMap,
  host: string,
  whenMs: number,
  seconds: number,
  visits: number,
  dayStartHour: number,
): StatsMap {
  const parts = seconds > 0 ? allocateSeconds(whenMs, seconds, dayStartHour) : [];
  if (parts.length === 0 && visits > 0) {
    parts.push({ key: logicalDateKey(new Date(whenMs), dayStartHour), seconds: 0 });
  }
  let nextStats = stats;
  for (const part of parts) {
    const day = { ...(nextStats[part.key] ?? {}) };
    const prev = day[host] ?? emptyUsage();
    const next: Usage = {
      seconds: prev.seconds + part.seconds,
      visits: prev.visits + (part === parts[0] ? visits : 0),
    };
    if (next.seconds === 0 && next.visits === 0) continue;
    day[host] = next;
    nextStats = { ...nextStats, [part.key]: day };
  }
  return nextStats;
}
