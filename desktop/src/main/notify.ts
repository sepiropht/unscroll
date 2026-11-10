import { Notification } from 'electron'
import { assetPath } from './assets'

export function notify(title: string, body: string): void {
  if (!Notification.isSupported()) return
  try {
    new Notification({ title, body, icon: assetPath('icon.png') }).show()
  } catch (error) {
    console.warn('[unscroll] notification failed', error)
  }
}
