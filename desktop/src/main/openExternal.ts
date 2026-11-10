import { shell } from 'electron'
import { safeExternalHref } from './externalUrl'

export async function openSafeExternal(value: string): Promise<boolean> {
  const href = safeExternalHref(value)
  if (!href) return false
  await shell.openExternal(href)
  return true
}
