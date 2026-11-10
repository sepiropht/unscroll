import type { MessageKey } from './i18n.ts';
import type { BlockReason } from './rules.ts';
import type { ImportPatch } from './exchange.ts';
import type { SiteRow } from './stats.ts';
import type { Lang, Theme } from './state.ts';
import type { BillingPlan } from '../edition/billing.ts';

export interface Snapshot {
  language: Lang;
  theme: Theme;
  todayKey: string;
  totalSeconds: number;
  yesterdaySeconds: number;
  weekAvgSeconds: number;
  sites: SiteRow[];
  currentHost: string | null;
  currentSeconds: number;
  currentVisits: number;
  limitMinutes: number | null;
  limitUsedSeconds: number;
  limitDomain: string | null;
  focus: null | {
    remainingSeconds: number;
    strict: boolean;
    domains: string[];
    endsAt: number;
    durationMinutes: number;
  };
  snoozeUsesLeft: number;
  snoozeLimitPerDay: number;
  hasPassword: boolean;
  whitelistMode: boolean;
  storageOk: boolean;
}

export interface SiteStatus {
  blocked: boolean;
  reason: BlockReason | null;
  ruleDomain: string | null;
  host: string | null;
  usedSeconds: number;
  limitMinutes: number | null;
  detail: string | null;
  strict: boolean;
  snoozeUsesLeft: number;
  hasPassword: boolean;
  language: Lang;
  theme: Theme;
  todayKey: string;
  storageOk: boolean;
}

export type ClientMessage =
  | { type: 'snapshot' }
  | { type: 'site-status'; host: string }
  | { type: 'quick-limit'; domain: string; minutes: number | null }
  | { type: 'start-focus'; minutes: number; domains: string[]; strict: boolean }
  | { type: 'stop-focus' }
  | { type: 'snooze'; host: string; returnUrl: string }
  | { type: 'unlock'; host: string; password: string; returnUrl: string }
  | { type: 'apply-import'; data: unknown }
  | { type: 'reset' }
  | { type: 'recheck' };

export type ClientResponse =
  | { ok: true; snapshot?: Snapshot; status?: SiteStatus; provisioned?: boolean; billing?: BillingPlan }
  | { ok: false; error: string };

const MESSAGE_TYPES = new Set([
  'snapshot',
  'site-status',
  'quick-limit',
  'start-focus',
  'stop-focus',
  'snooze',
  'unlock',
  'apply-import',
  'reset',
  'recheck',
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function boundedString(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length <= max;
}

/** Reject malformed extension messages before the background reads their fields. */
export function isMessage(value: unknown): value is ClientMessage {
  if (!isRecord(value) || typeof value.type !== 'string' || !MESSAGE_TYPES.has(value.type)) return false;
  switch (value.type) {
    case 'snapshot':
    case 'stop-focus':
    case 'reset':
    case 'recheck':
      return true;
    case 'site-status':
      return boundedString(value.host, 2048);
    case 'quick-limit':
      return boundedString(value.domain, 2048) && (value.minutes === null || (typeof value.minutes === 'number' && Number.isFinite(value.minutes)));
    case 'start-focus':
      return (
        typeof value.minutes === 'number' &&
        Number.isFinite(value.minutes) &&
        (value.strict === true || value.strict === false) &&
        Array.isArray(value.domains) &&
        value.domains.length <= 80 &&
        value.domains.every((item) => boundedString(item, 2048))
      );
    case 'snooze':
      return boundedString(value.host, 2048) && boundedString(value.returnUrl, 8000);
    case 'unlock':
      return boundedString(value.host, 2048) && boundedString(value.returnUrl, 8000) && boundedString(value.password, 512);
    case 'apply-import':
      return 'data' in value;
    default:
      return false;
  }
}

const ERROR_KEYS: Record<string, MessageKey> = {
  'invalid-domain': 'invalidDomain',
  'focus-empty': 'focusEmpty',
  'invalid-minutes': 'invalidMinutes',
  'focus-strict': 'focusStrictCode',
  'snooze-exhausted': 'snoozeExhausted',
  'bad-password': 'badPassword',
  'no-password': 'badPassword',
  storage: 'storageError',
  failed: 'failed',
  unknown: 'failed',
};

export function errorMessageKey(code: string): MessageKey {
  return ERROR_KEYS[code] ?? 'failed';
}

export type { ImportPatch };
