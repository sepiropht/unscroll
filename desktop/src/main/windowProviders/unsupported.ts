import { warnOnce } from './log'

const LABELS = { kde: 'KDE', gnome: 'GNOME' } as const

// TODO: GNOME Wayland has no readable focused-window API for a normal client.
// A GNOME Shell extension would have to publish the focused window.
// TODO: KDE Plasma on Wayland needs a KWin script for the same reason.
// Until then this provider reports the gap and returns no window.

let logged = false

export function noteUnsupported(kind: 'kde' | 'gnome'): void {
  if (logged) return
  logged = true
  warnOnce(`Active-window detection is not supported on ${LABELS[kind]} Wayland yet.`)
}

export function resetUnsupportedNoticeForTests(): void {
  logged = false
}
