import type { ThemeSetting } from '@shared/types'

export function windowBackground(theme: ThemeSetting, systemDark: boolean): string {
  const dark = theme === 'dark' || (theme === 'system' && systemDark)
  return dark ? '#0B1416' : '#F8FAFC'
}
