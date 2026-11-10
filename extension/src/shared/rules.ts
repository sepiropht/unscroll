import { bestMatchingDomain, domainMatches, isExcludedHost } from './domain.ts';
import type { DayStat, StatsMap } from './stats.ts';
import { hostUsage, secondsMatching } from './stats.ts';
import type { FocusSession, RuntimeState, Schedule, Settings, SiteLimit } from './state.ts';
import { logicalDateKey } from './time.ts';

export type BlockReason = 'limit' | 'blocklist' | 'whitelist' | 'schedule' | 'focus';

export interface Decision {
  block: boolean;
  reason: BlockReason | null;
  host: string | null;
  ruleDomain: string | null;
  usedSeconds: number;
  limitMinutes: number | null;
  detail: string | null;
  strict: boolean;
}

export interface LimitSnapshot {
  limit: SiteLimit;
  usedSeconds: number;
  minutes: number;
  remainingSeconds: number;
}

export function isExcluded(host: string, settings: Settings): boolean {
  return isExcludedHost(host, settings.excludedDomains);
}

export function limitMinutesFor(limit: SiteLimit, when: Date, dayStartHour: number): number {
  const logical = new Date(when.getTime() - Math.min(23, Math.max(0, Math.floor(dayStartHour))) * 3_600_000);
  const weekday = logical.getDay();
  const custom = limit.weekdayMinutes?.[weekday];
  if (typeof custom === 'number' && Number.isFinite(custom)) return Math.max(0, custom);
  return Math.max(0, limit.minutesPerDay);
}

export function scheduleIsActive(schedule: Schedule, when: Date): boolean {
  if (!schedule.enabled || schedule.days.length === 0 || schedule.domains.length === 0) return false;
  const mins = when.getHours() * 60 + when.getMinutes();
  const day = when.getDay();
  const start = schedule.startMinutes;
  const end = schedule.endMinutes;
  if (end > start) return schedule.days.includes(day) && mins >= start && mins < end;
  if (end < start) {
    if (schedule.days.includes(day) && mins >= start) return true;
    const prev = (day + 6) % 7;
    if (schedule.days.includes(prev) && mins < end) return true;
  }
  return false;
}

export function focusRunning(focus: FocusSession | null, nowMs: number): focus is FocusSession {
  return !!focus && focus.endsAt > nowMs && focus.domains.length > 0;
}

export function hasBypass(host: string, runtime: RuntimeState, nowMs: number): boolean {
  for (const [rule, until] of Object.entries(runtime.snoozes)) {
    if (until > nowMs && domainMatches(host, rule)) return true;
  }
  for (const [rule, until] of Object.entries(runtime.unlocks)) {
    if (until > nowMs && domainMatches(host, rule)) return true;
  }
  return false;
}

export function matchingLimits(host: string, limits: readonly SiteLimit[]): SiteLimit[] {
  return limits.filter((limit) => limit.enabled && domainMatches(host, limit.domain));
}

export function describeLimit(
  host: string,
  limits: readonly SiteLimit[],
  day: DayStat | undefined,
  when: Date,
  dayStartHour: number,
): LimitSnapshot | null {
  let best: LimitSnapshot | null = null;
  for (const limit of matchingLimits(host, limits)) {
    const minutes = limitMinutesFor(limit, when, dayStartHour);
    const usedSeconds = secondsMatching(day, limit.domain);
    const remainingSeconds = minutes * 60 - usedSeconds;
    if (!best || remainingSeconds < best.remainingSeconds || (remainingSeconds === best.remainingSeconds && limit.domain.length > best.limit.domain.length)) {
      best = { limit, usedSeconds, minutes, remainingSeconds };
    }
  }
  return best;
}

function allow(host: string | null, usedSeconds = 0): Decision {
  return {
    block: false,
    reason: null,
    host,
    ruleDomain: null,
    usedSeconds,
    limitMinutes: null,
    detail: null,
    strict: false,
  };
}

export function evaluate(input: {
  host: string | null;
  now: Date;
  settings: Settings;
  limits: readonly SiteLimit[];
  blocklist: readonly string[];
  whitelist: readonly string[];
  schedules: readonly Schedule[];
  focus: FocusSession | null;
  stats: StatsMap;
  runtime: RuntimeState;
}): Decision {
  const { host, now, settings } = input;
  if (!host) return allow(null);
  if (isExcluded(host, settings)) return allow(host);
  const dayKey = logicalDateKey(now, settings.dayStartHour);
  const own = hostUsage(input.stats[dayKey], host).seconds;
  const bypass = hasBypass(host, input.runtime, now.getTime());

  if (focusRunning(input.focus, now.getTime())) {
    const rule = bestMatchingDomain(host, input.focus.domains);
    if (rule) {
      if (input.focus.strict || !bypass) {
        return {
          block: true,
          reason: 'focus',
          host,
          ruleDomain: rule,
          usedSeconds: own,
          limitMinutes: null,
          detail: String(input.focus.durationMinutes),
          strict: input.focus.strict,
        };
      }
    }
  }

  const blocked = bestMatchingDomain(host, input.blocklist);
  if (blocked && !bypass) {
    return {
      block: true,
      reason: 'blocklist',
      host,
      ruleDomain: blocked,
      usedSeconds: own,
      limitMinutes: null,
      detail: blocked,
      strict: false,
    };
  }

  if (settings.whitelistMode && !bypass) {
    const allowed = bestMatchingDomain(host, input.whitelist);
    if (!allowed) {
      return {
        block: true,
        reason: 'whitelist',
        host,
        ruleDomain: host,
        usedSeconds: own,
        limitMinutes: null,
        detail: null,
        strict: false,
      };
    }
  }

  for (const schedule of input.schedules) {
    if (!scheduleIsActive(schedule, now)) continue;
    const rule = bestMatchingDomain(host, schedule.domains);
    if (!rule || bypass) continue;
    return {
      block: true,
      reason: 'schedule',
      host,
      ruleDomain: rule,
      usedSeconds: own,
      limitMinutes: null,
      detail: schedule.name,
      strict: false,
    };
  }

  const tight = describeLimit(host, input.limits, input.stats[dayKey], now, settings.dayStartHour);
  if (tight && tight.remainingSeconds <= 0 && !bypass) {
    return {
      block: true,
      reason: 'limit',
      host,
      ruleDomain: tight.limit.domain,
      usedSeconds: tight.usedSeconds,
      limitMinutes: tight.minutes,
      detail: tight.limit.domain,
      strict: false,
    };
  }

  return allow(host, tight ? tight.usedSeconds : own);
}

export function nearestWarning(
  host: string,
  limits: readonly SiteLimit[],
  day: DayStat | undefined,
  when: Date,
  dayStartHour: number,
): { rule: string; remainingSeconds: number } | null {
  const tight = describeLimit(host, limits, day, when, dayStartHour);
  if (!tight) return null;
  if (tight.remainingSeconds <= 0 || tight.remainingSeconds > 60) return null;
  return { rule: tight.limit.domain, remainingSeconds: tight.remainingSeconds };
}
