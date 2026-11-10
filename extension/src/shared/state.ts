import { normalizeDomainInput } from './domain.ts';
import type { DayStat, StatsMap, Usage } from './stats.ts';

export const UI_LANGS = ['fr', 'en', 'es', 'de', 'pt'] as const;
export type Lang = (typeof UI_LANGS)[number];

export function isLang(value: unknown): value is Lang {
  return typeof value === 'string' && (UI_LANGS as readonly string[]).includes(value);
}
export type Theme = 'auto' | 'light' | 'dark';

export interface PasswordRecord {
  salt: string;
  hash: string;
}

export interface Settings {
  language: Lang;
  theme: Theme;
  idleThresholdSeconds: number;
  dayStartHour: number;
  excludedDomains: string[];
  showBadge: boolean;
  trackIncognito: boolean;
  snoozeLimitPerDay: number;
  password: PasswordRecord | null;
  whitelistMode: boolean;
}

export interface SiteLimit {
  id: string;
  domain: string;
  minutesPerDay: number;
  weekdayMinutes: number[] | null;
  enabled: boolean;
}

export interface Schedule {
  id: string;
  name: string;
  domains: string[];
  days: number[];
  startMinutes: number;
  endMinutes: number;
  enabled: boolean;
}

export interface FocusSession {
  startedAt: number;
  endsAt: number;
  durationMinutes: number;
  domains: string[];
  strict: boolean;
}

export interface Checkpoint {
  countHost: string | null;
  displayHost: string | null;
  lastVisitHost: string | null;
  segmentStart: number | null;
  incognito: boolean;
  focused: boolean;
  idle: 'active' | 'idle' | 'locked';
  counting: boolean;
}

export interface RuntimeState {
  snoozes: Record<string, number>;
  snoozeUses: Record<string, number>;
  unlocks: Record<string, number>;
  warned: Record<string, string[]>;
  checkpoint: Checkpoint | null;
}

export interface AppState {
  settings: Settings;
  limits: SiteLimit[];
  blocklist: string[];
  whitelist: string[];
  schedules: Schedule[];
  focus: FocusSession | null;
  runtime: RuntimeState;
  stats: StatsMap;
}

/** First run follows the browser language when we ship it, English otherwise. */
function browserLanguage(): Lang {
  try {
    const g = globalThis as { chrome?: { i18n?: { getUILanguage?: () => string } }; navigator?: { language?: string } };
    const tag = (g.chrome?.i18n?.getUILanguage?.() ?? g.navigator?.language ?? 'en').slice(0, 2).toLowerCase();
    return isLang(tag) ? tag : 'en';
  } catch {
    return 'en';
  }
}

export function defaultSettings(): Settings {
  return {
    language: browserLanguage(),
    theme: 'auto',
    idleThresholdSeconds: 60,
    dayStartHour: 0,
    excludedDomains: [],
    showBadge: true,
    trackIncognito: false,
    snoozeLimitPerDay: 3,
    password: null,
    whitelistMode: false,
  };
}

export function defaultRuntime(): RuntimeState {
  return { snoozes: {}, snoozeUses: {}, unlocks: {}, warned: {}, checkpoint: null };
}

export function defaultAppState(): AppState {
  return {
    settings: defaultSettings(),
    limits: [],
    blocklist: [],
    whitelist: [],
    schedules: [],
    focus: null,
    runtime: defaultRuntime(),
    stats: {},
  };
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

function intBetween(value: unknown, min: number, max: number, fallback: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.round(value)));
}

function domainList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string') continue;
    const domain = normalizeDomainInput(item);
    if (!domain || out.includes(domain)) continue;
    out.push(domain);
    if (out.length >= 200) break;
  }
  return out;
}

function password(value: unknown): PasswordRecord | null {
  const rec = asRecord(value);
  if (!rec) return null;
  if (typeof rec.salt !== 'string' || typeof rec.hash !== 'string') return null;
  if (!/^[0-9a-f]{16,128}$/.test(rec.salt) || !/^[0-9a-f]{64}$/.test(rec.hash)) return null;
  return { salt: rec.salt, hash: rec.hash };
}

export function sanitizeSettings(value: unknown): Settings {
  const base = defaultSettings();
  const rec = asRecord(value);
  if (!rec) return base;
  const language = isLang(rec.language) ? rec.language : base.language;
  const theme = rec.theme === 'light' || rec.theme === 'dark' || rec.theme === 'auto' ? rec.theme : 'auto';
  return {
    language,
    theme,
    idleThresholdSeconds: intBetween(rec.idleThresholdSeconds, 15, 3600, base.idleThresholdSeconds),
    dayStartHour: intBetween(rec.dayStartHour, 0, 23, base.dayStartHour),
    excludedDomains: domainList(rec.excludedDomains),
    showBadge: rec.showBadge !== false,
    trackIncognito: rec.trackIncognito === true,
    snoozeLimitPerDay: intBetween(rec.snoozeLimitPerDay, 0, 30, base.snoozeLimitPerDay),
    password: password(rec.password),
    whitelistMode: rec.whitelistMode === true,
  };
}

function weekdayMinutes(value: unknown): number[] | null {
  if (!Array.isArray(value) || value.length !== 7) return null;
  const out: number[] = [];
  for (const item of value) {
    if (typeof item !== 'number' || !Number.isFinite(item)) return null;
    out.push(Math.min(1440, Math.max(0, Math.round(item))));
  }
  return out;
}

export function sanitizeLimit(value: unknown): SiteLimit | null {
  const rec = asRecord(value);
  if (!rec || typeof rec.domain !== 'string') return null;
  const domain = normalizeDomainInput(rec.domain);
  if (!domain) return null;
  const id = typeof rec.id === 'string' && rec.id.trim() ? rec.id.trim().slice(0, 80) : `limit:${domain}`;
  return {
    id,
    domain,
    minutesPerDay: intBetween(rec.minutesPerDay, 0, 1440, 30),
    weekdayMinutes: weekdayMinutes(rec.weekdayMinutes),
    enabled: rec.enabled !== false,
  };
}

export function sanitizeLimits(value: unknown): SiteLimit[] {
  if (!Array.isArray(value)) return [];
  const out: SiteLimit[] = [];
  const seen = new Set<string>();
  for (const item of value) {
    const limit = sanitizeLimit(item);
    if (!limit || seen.has(limit.domain)) continue;
    seen.add(limit.domain);
    out.push(limit);
    if (out.length >= 200) break;
  }
  return out;
}

export function sanitizeSchedule(value: unknown): Schedule | null {
  const rec = asRecord(value);
  if (!rec) return null;
  const domains = domainList(rec.domains);
  if (domains.length === 0) return null;
  const name = typeof rec.name === 'string' ? rec.name.trim().slice(0, 80) : '';
  const daysRaw = Array.isArray(rec.days) ? rec.days : [];
  const days = [...new Set(daysRaw.filter((d): d is number => typeof d === 'number' && d >= 0 && d <= 6 && Number.isInteger(d)))];
  if (!name || days.length === 0) return null;
  const startMinutes = intBetween(rec.startMinutes, 0, 1439, 9 * 60);
  const endMinutes = intBetween(rec.endMinutes, 0, 1440, 17 * 60);
  const slug = `${name}|${startMinutes}|${endMinutes}|${domains.join(',')}`;
  const id = typeof rec.id === 'string' && rec.id.trim() ? rec.id.trim().slice(0, 80) : `sched:${slug}`;
  return {
    id,
    name,
    domains,
    days,
    startMinutes,
    endMinutes,
    enabled: rec.enabled !== false,
  };
}

export function sanitizeSchedules(value: unknown): Schedule[] {
  if (!Array.isArray(value)) return [];
  const out: Schedule[] = [];
  for (const item of value) {
    const schedule = sanitizeSchedule(item);
    if (!schedule) continue;
    out.push(schedule);
    if (out.length >= 100) break;
  }
  return out;
}

export function sanitizeFocus(value: unknown): FocusSession | null {
  if (value == null) return null;
  const rec = asRecord(value);
  if (!rec) return null;
  const domains = domainList(rec.domains);
  const startedAt = rec.startedAt;
  const endsAt = rec.endsAt;
  const durationMinutes = rec.durationMinutes;
  if (typeof startedAt !== 'number' || typeof endsAt !== 'number') return null;
  if (!Number.isFinite(startedAt) || !Number.isFinite(endsAt) || endsAt <= startedAt) return null;
  if (typeof durationMinutes !== 'number' || !Number.isFinite(durationMinutes)) return null;
  if (domains.length === 0) return null;
  return {
    startedAt,
    endsAt,
    durationMinutes: Math.min(240, Math.max(1, Math.round(durationMinutes))),
    domains,
    strict: rec.strict === true,
  };
}

function futureMap(value: unknown): Record<string, number> {
  const rec = asRecord(value);
  if (!rec) return {};
  const out: Record<string, number> = {};
  for (const [key, until] of Object.entries(rec)) {
    if (key === '__proto__' || key === 'constructor' || key === 'prototype') continue;
    const domain = normalizeDomainInput(key);
    if (!domain || typeof until !== 'number' || !Number.isFinite(until)) continue;
    out[domain] = until;
  }
  return out;
}

function countMap(value: unknown): Record<string, number> {
  const rec = asRecord(value);
  if (!rec) return {};
  const out: Record<string, number> = {};
  for (const [key, count] of Object.entries(rec)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) continue;
    if (typeof count !== 'number' || !Number.isFinite(count) || count < 0) continue;
    out[key] = Math.min(1000, Math.floor(count));
  }
  return out;
}

function warnedMap(value: unknown): Record<string, string[]> {
  const rec = asRecord(value);
  if (!rec) return {};
  const out: Record<string, string[]> = {};
  for (const [key, domains] of Object.entries(rec)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(key)) continue;
    out[key] = domainList(domains);
  }
  return out;
}

function checkpoint(value: unknown): Checkpoint | null {
  const rec = asRecord(value);
  if (!rec) return null;
  const idle = rec.idle === 'idle' || rec.idle === 'locked' || rec.idle === 'active' ? rec.idle : 'active';
  const host = (raw: unknown): string | null => {
    if (typeof raw !== 'string' || !raw) return null;
    return normalizeDomainInput(raw);
  };
  const segmentStart = typeof rec.segmentStart === 'number' && Number.isFinite(rec.segmentStart) ? rec.segmentStart : null;
  return {
    countHost: host(rec.countHost),
    displayHost: host(rec.displayHost),
    lastVisitHost: host(rec.lastVisitHost),
    segmentStart,
    incognito: rec.incognito === true,
    focused: rec.focused === true,
    idle,
    counting: rec.counting === true,
  };
}

export function sanitizeRuntime(value: unknown): RuntimeState {
  const rec = asRecord(value);
  if (!rec) return defaultRuntime();
  return {
    snoozes: futureMap(rec.snoozes),
    snoozeUses: countMap(rec.snoozeUses),
    unlocks: futureMap(rec.unlocks),
    warned: warnedMap(rec.warned),
    checkpoint: checkpoint(rec.checkpoint),
  };
}

function usage(value: unknown): Usage | null {
  const rec = asRecord(value);
  if (!rec) return null;
  if (typeof rec.seconds !== 'number' || typeof rec.visits !== 'number') return null;
  if (!Number.isFinite(rec.seconds) || !Number.isFinite(rec.visits)) return null;
  return {
    seconds: Math.max(0, Math.min(86_400 * 2, Math.floor(rec.seconds))),
    visits: Math.max(0, Math.min(100_000, Math.floor(rec.visits))),
  };
}

export function sanitizeStats(value: unknown): StatsMap {
  const rec = asRecord(value);
  if (!rec) return {};
  const out: StatsMap = {};
  for (const [day, body] of Object.entries(rec)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) continue;
    const dayRec = asRecord(body);
    if (!dayRec) continue;
    const cleaned: DayStat = {};
    for (const [host, row] of Object.entries(dayRec)) {
      if (host === '__proto__' || host === 'constructor' || host === 'prototype') continue;
      const domain = normalizeDomainInput(host);
      if (!domain) continue;
      const parsed = usage(row);
      if (!parsed || (parsed.seconds === 0 && parsed.visits === 0)) continue;
      const prev = cleaned[domain] ?? { seconds: 0, visits: 0 };
      cleaned[domain] = {
        seconds: prev.seconds + parsed.seconds,
        visits: prev.visits + parsed.visits,
      };
    }
    if (Object.keys(cleaned).length > 0) out[day] = cleaned;
  }
  return out;
}

export function normalizeState(raw: unknown): AppState {
  const rec = asRecord(raw) ?? {};
  return {
    settings: sanitizeSettings(rec.settings),
    limits: sanitizeLimits(rec.limits),
    blocklist: domainList(rec.blocklist),
    whitelist: domainList(rec.whitelist),
    schedules: sanitizeSchedules(rec.schedules),
    focus: sanitizeFocus(rec.focus),
    runtime: sanitizeRuntime(rec.runtime),
    stats: sanitizeStats(rec.stats),
  };
}
