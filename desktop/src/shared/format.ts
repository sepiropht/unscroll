import type { Locale } from './types'

const INTL_LOCALE: Record<Locale, string> = {
  fr: 'fr-FR',
  en: 'en-GB',
  es: 'es-ES',
  de: 'de-DE',
  pt: 'pt-PT'
}

export function formatDuration(ms: number, locale: Locale): string {
  const totalMin = Math.floor(Math.max(0, ms) / 60000)
  const hours = Math.floor(totalMin / 60)
  const minutes = totalMin % 60
  if (hours === 0) return locale === 'de' ? `${minutes} Min.` : `${minutes} min`
  const padded = String(minutes).padStart(2, '0')
  if (locale === 'de') return `${hours} Std. ${padded} Min.`
  return `${hours} h ${padded} min`
}

export function splitDuration(ms: number): { hours: number; minutes: number } {
  const totalMin = Math.floor(Math.max(0, ms) / 60000)
  return { hours: Math.floor(totalMin / 60), minutes: totalMin % 60 }
}

export function formatDayLabel(day: string, locale: Locale): string {
  const [year, month, date] = day.split('-').map(Number)
  return new Intl.DateTimeFormat(INTL_LOCALE[locale], {
    weekday: 'short',
    day: 'numeric',
    month: 'short'
  }).format(new Date(year, month - 1, date))
}

export function formatLongDay(day: string, locale: Locale): string {
  const [year, month, date] = day.split('-').map(Number)
  return new Intl.DateTimeFormat(INTL_LOCALE[locale], {
    weekday: 'long',
    day: 'numeric',
    month: 'long'
  }).format(new Date(year, month - 1, date))
}

export const REFERENCE_MS = 8 * 60 * 60 * 1000
