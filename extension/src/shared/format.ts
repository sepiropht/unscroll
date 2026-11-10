import { t } from './i18n.ts';
import type { Lang } from './state.ts';
import type { StatsMap } from './stats.ts';

const LOCALE_TAG: Record<Lang, string> = {
  fr: 'fr-FR',
  en: 'en-GB',
  es: 'es-ES',
  de: 'de-DE',
  pt: 'pt-PT',
};

export function localeTag(lang: Lang): string {
  return LOCALE_TAG[lang] ?? 'fr-FR';
}

export function formatHM(totalSeconds: number, lang: Lang): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  if (hours <= 0) return t(lang, 'durationMin', { n: minutes });
  return t(lang, 'durationHm', { h: hours, m: String(minutes).padStart(2, '0') });
}

export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const seconds = safe % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
  }
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

export function badgeText(totalSeconds: number): string {
  const minutes = Math.floor(Math.max(0, totalSeconds) / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours > 99) return '99h+';
  return `${hours}h`;
}

export function trendPercent(current: number, previous: number): {
  kind: 'up' | 'down' | 'flat' | 'new';
  percent: number;
} {
  if (previous <= 0 && current <= 0) return { kind: 'flat', percent: 0 };
  if (previous <= 0) return { kind: 'new', percent: 100 };
  const percent = Math.round(((current - previous) / previous) * 100);
  if (percent === 0) return { kind: 'flat', percent: 0 };
  return { kind: percent > 0 ? 'up' : 'down', percent };
}

function csvCell(value: string): string {
  let cell = value;
  if (/^[=+\-@]/.test(cell)) cell = `'${cell}`;
  if (/[",\r\n]/.test(cell)) return `"${cell.replaceAll('"', '""')}"`;
  return cell;
}

export function statsToCsv(stats: StatsMap): string {
  const lines = ['date,domain,seconds,visits'];
  for (const date of Object.keys(stats).sort()) {
    const day = stats[date];
    if (!day) continue;
    for (const domain of Object.keys(day).sort()) {
      const row = day[domain];
      if (!row) continue;
      lines.push([date, csvCell(domain), String(row.seconds), String(row.visits)].join(','));
    }
  }
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

export function shortDayLabel(key: string, lang: Lang, withWeekday: boolean): string {
  const [y, m, d] = key.split('-').map(Number);
  const date = new Date(y || 1970, (m || 1) - 1, d || 1, 12, 0, 0, 0);
  return new Intl.DateTimeFormat(localeTag(lang), {
    weekday: withWeekday ? 'short' : undefined,
    day: 'numeric',
    month: withWeekday ? undefined : 'short',
  }).format(date);
}

export function longDayLabel(key: string, lang: Lang): string {
  const [y, m, d] = key.split('-').map(Number);
  const date = new Date(y || 1970, (m || 1) - 1, d || 1, 12, 0, 0, 0);
  return new Intl.DateTimeFormat(localeTag(lang), {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(date);
}
