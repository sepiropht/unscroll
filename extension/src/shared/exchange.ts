import {
  sanitizeFocus,
  sanitizeLimits,
  sanitizeSchedules,
  sanitizeSettings,
  sanitizeStats,
  type AppState,
  type FocusSession,
  type Settings,
  type SiteLimit,
  type Schedule,
} from './state.ts';
import { domainListOf } from './domain.ts';
import type { StatsMap } from './stats.ts';

export interface ExportFile {
  version: 1;
  exportedAt: string;
  settings: Settings;
  limits: SiteLimit[];
  blocklist: string[];
  whitelist: string[];
  schedules: Schedule[];
  focus: FocusSession | null;
  stats: StatsMap;
}

export interface ImportPatch {
  settings?: Settings;
  limits?: SiteLimit[];
  blocklist?: string[];
  whitelist?: string[];
  schedules?: Schedule[];
  focus?: FocusSession | null;
  stats?: StatsMap;
}

export function buildExport(state: AppState, now = new Date()): ExportFile {
  return {
    version: 1,
    exportedAt: now.toISOString(),
    settings: state.settings,
    limits: state.limits,
    blocklist: state.blocklist,
    whitelist: state.whitelist,
    schedules: state.schedules,
    focus: state.focus,
    stats: state.stats,
  };
}

function present(rec: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(rec, key);
}

export function parseImport(data: unknown): { ok: true; patch: ImportPatch } | { ok: false; error: string } {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return { ok: false, error: 'import-shape' };
  const rec = data as Record<string, unknown>;
  if (rec.version !== 1) return { ok: false, error: 'import-version' };
  const patch: ImportPatch = {};
  if (present(rec, 'settings')) {
    if (!rec.settings || typeof rec.settings !== 'object' || Array.isArray(rec.settings)) {
      return { ok: false, error: 'import-shape' };
    }
    patch.settings = sanitizeSettings(rec.settings);
  }
  if (present(rec, 'limits')) {
    if (!Array.isArray(rec.limits)) return { ok: false, error: 'import-shape' };
    patch.limits = sanitizeLimits(rec.limits);
  }
  if (present(rec, 'blocklist')) {
    if (!Array.isArray(rec.blocklist)) return { ok: false, error: 'import-shape' };
    patch.blocklist = domainListOf(rec.blocklist);
  }
  if (present(rec, 'whitelist')) {
    if (!Array.isArray(rec.whitelist)) return { ok: false, error: 'import-shape' };
    patch.whitelist = domainListOf(rec.whitelist);
  }
  if (present(rec, 'schedules')) {
    if (!Array.isArray(rec.schedules)) return { ok: false, error: 'import-shape' };
    patch.schedules = sanitizeSchedules(rec.schedules);
  }
  if (present(rec, 'focus')) {
    if (rec.focus !== null && (typeof rec.focus !== 'object' || Array.isArray(rec.focus))) {
      return { ok: false, error: 'import-shape' };
    }
    patch.focus = sanitizeFocus(rec.focus);
  }
  if (present(rec, 'stats')) {
    if (!rec.stats || typeof rec.stats !== 'object' || Array.isArray(rec.stats)) {
      return { ok: false, error: 'import-shape' };
    }
    patch.stats = sanitizeStats(rec.stats);
  }
  if (Object.keys(patch).length === 0) return { ok: false, error: 'import-empty' };
  return { ok: true, patch };
}
