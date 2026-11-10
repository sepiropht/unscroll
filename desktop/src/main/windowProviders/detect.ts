export type ProviderKind = 'hyprland' | 'sway' | 'kde' | 'gnome' | 'x11'

export type DetectedProvider = {
  kind: ProviderKind
  supported: boolean
}

export function desktopName(env: NodeJS.ProcessEnv): string {
  return [env.XDG_CURRENT_DESKTOP, env.XDG_SESSION_DESKTOP, env.DESKTOP_SESSION]
    .filter((part): part is string => typeof part === 'string' && part.trim().length > 0)
    .join(':')
    .toLowerCase()
}

export function isWaylandSession(env: NodeJS.ProcessEnv): boolean {
  const session = (env.XDG_SESSION_TYPE ?? '').toLowerCase()
  if (session === 'wayland') return true
  if (session === 'x11' || session === 'tty') return false
  return typeof env.WAYLAND_DISPLAY === 'string' && env.WAYLAND_DISPLAY.length > 0
}

export function hyprlandSocketPath(env: NodeJS.ProcessEnv = process.env): string | null {
  const runtime = (env.XDG_RUNTIME_DIR ?? '').trim()
  const signature = (env.HYPRLAND_INSTANCE_SIGNATURE ?? '').trim()
  if (!runtime || !signature) return null
  if (runtime.includes('\0') || !/^[A-Za-z0-9_-]+$/.test(signature)) return null
  return `${runtime}/hypr/${signature}/.socket2.sock`
}

function swaySelected(env: NodeJS.ProcessEnv): boolean {
  if ((env.SWAYSOCK ?? '').trim()) return true
  return desktopName(env).includes('sway')
}

function kdeDesktop(env: NodeJS.ProcessEnv): boolean {
  const name = desktopName(env)
  return name.includes('kde') || name.includes('plasma')
}

function gnomeDesktop(env: NodeJS.ProcessEnv): boolean {
  return desktopName(env).includes('gnome')
}

export function detectProvider(env: NodeJS.ProcessEnv = process.env): DetectedProvider {
  if ((env.HYPRLAND_INSTANCE_SIGNATURE ?? '').trim()) return { kind: 'hyprland', supported: true }
  if (swaySelected(env)) return { kind: 'sway', supported: true }
  if (isWaylandSession(env) && kdeDesktop(env)) return { kind: 'kde', supported: false }
  if (isWaylandSession(env) && gnomeDesktop(env)) return { kind: 'gnome', supported: false }
  return { kind: 'x11', supported: true }
}
