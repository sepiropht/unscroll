import { shell } from 'electron'
import { safeExternalHref, safeStripeUrl } from './externalUrl'

export async function openSafeExternal(value: string): Promise<boolean> {
  // Stripe Checkout and portal links carry a fragment, so they get their own check.
  const href = safeExternalHref(value) ?? safeStripeUrl(value)
  if (!href) return false
  await shell.openExternal(href)
  return true
}
