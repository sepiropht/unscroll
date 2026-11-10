import { expect, test } from 'vitest'
import { BUILTIN_CATEGORIES, categoryLabel } from './categories'
import { messageKeys, t } from './i18n'
import { LOCALES } from './types'

test('every locale covers every UI string and keeps placeholders', () => {
  expect(LOCALES[0]).toBe('en')
  for (const locale of LOCALES) {
    for (const key of messageKeys) {
      const text = t(locale, key)
      expect(text.length).toBeGreaterThan(0)
      for (const token of t('en', key).match(/\{[a-z]+\}/g) ?? []) {
        expect(text).toContain(token)
      }
    }
  }
  expect(t('fr', 'app.name')).toBe('Unscroll')
  expect(t('es', 'nav.today')).toBe('Hoy')
  expect(t('de', 'nav.settings')).toBe('Einstellungen')
  expect(t('pt', 'settings.title')).toBe('Definições')
})

test('builtin category names follow the locale unless renamed', () => {
  for (const category of BUILTIN_CATEGORIES) {
    for (const locale of LOCALES) {
      expect(categoryLabel(category.key, category.fr, locale)).toBe(category[locale])
    }
    expect(categoryLabel(category.key, 'Travail', 'es')).toBe('Travail')
  }
})
