import { LOCALES, type Locale } from './types'

export type BuiltinKey = 'productif' | 'social' | 'divertissement' | 'autre'

export const BUILTIN_CATEGORIES: ({ key: BuiltinKey; color: string } & Record<Locale, string>)[] = [
  { key: 'productif', color: '#0F766E', fr: 'Productif', en: 'Productive', es: 'Productivo', de: 'Produktiv', pt: 'Produtivo' },
  { key: 'social', color: '#14B8A6', fr: 'Social', en: 'Social', es: 'Social', de: 'Sozial', pt: 'Social' },
  { key: 'divertissement', color: '#F59E0B', fr: 'Divertissement', en: 'Entertainment', es: 'Ocio', de: 'Unterhaltung', pt: 'Lazer' },
  { key: 'autre', color: '#64748B', fr: 'Autre', en: 'Other', es: 'Otro', de: 'Sonstiges', pt: 'Outro' }
]

const RULES: { key: BuiltinKey; words: string[] }[] = [
  {
    key: 'productif',
    words: [
      'code', 'vscode', 'vim', 'nvim', 'emacs', 'terminal', 'konsole', 'alacritty', 'kitty',
      'wezterm', 'gnome-terminal', 'libreoffice', 'soffice', 'writer', 'calc', 'idea',
      'pycharm', 'webstorm', 'goland', 'android studio', 'obsidian', 'inkscape', 'gimp', 'blender'
    ]
  },
  {
    key: 'social',
    words: [
      'slack', 'discord', 'telegram', 'signal', 'whatsapp', 'thunderbird', 'geary', 'mail',
      'zoom', 'teams', 'element', 'skype'
    ]
  },
  {
    key: 'divertissement',
    words: ['steam', 'vlc', 'spotify', 'mpv', 'rhythmbox', 'netflix', 'heroic', 'lutris', 'minecraft']
  }
]

export function guessBuiltinKey(appName: string): BuiltinKey {
  const name = appName.toLowerCase()
  for (const rule of RULES) {
    if (rule.words.some((word) => name.includes(word))) return rule.key
  }
  return 'autre'
}

export function categoryLabel(
  builtinKey: string | null,
  storedName: string,
  locale: Locale
): string {
  if (!builtinKey) return storedName
  const builtin = BUILTIN_CATEGORIES.find((item) => item.key === builtinKey)
  if (!builtin) return storedName
  const defaults = LOCALES.map((code) => builtin[code])
  if (defaults.includes(storedName)) return builtin[locale]
  return storedName
}
