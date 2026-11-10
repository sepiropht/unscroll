import type { WindowProviderStatus } from '@shared/types'
import { detectProvider, type ProviderKind } from './detect'
import { queryHyprland, stopHyprlandWatch, subscribeHyprlandWindow } from './hyprland'
import { querySway } from './sway'
import type { ActiveApp } from './types'
import { noteUnsupported } from './unsupported'

// First match wins: Hyprland, then Sway, then KDE/GNOME on Wayland.
// Anything else stays on the X11 get-windows/xdotool path.

export async function queryWayland(kind: Exclude<ProviderKind, 'x11'>): Promise<ActiveApp | null> {
  if (kind === 'hyprland') return queryHyprland()
  if (kind === 'sway') return querySway()
  noteUnsupported(kind)
  return null
}

export function windowProviderStatus(): WindowProviderStatus {
  const detected = detectProvider()
  if (detected.kind === 'kde' || detected.kind === 'gnome') noteUnsupported(detected.kind)
  return { name: detected.kind, supported: detected.supported }
}

export function subscribeActiveWindow(listener: () => void): () => void {
  if (detectProvider().kind !== 'hyprland') return () => {}
  return subscribeHyprlandWindow(listener)
}

export function stopWindowWatch(): void {
  stopHyprlandWatch()
}
