import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { detectProvider } from './windowProviders/detect'
import { queryWayland, stopWindowWatch, subscribeActiveWindow, windowProviderStatus } from './windowProviders/chain'
import { exeOf } from './windowProviders/processInfo'
import type { ActiveApp } from './windowProviders/types'

export type { ActiveApp }
export { stopWindowWatch, subscribeActiveWindow, windowProviderStatus }

const exec = promisify(execFile)

function text(result: { stdout: string | Buffer }): string {
  return typeof result.stdout === 'string' ? result.stdout : result.stdout.toString('utf8')
}

type GetWindowsModule = {
  activeWindow: (options?: {
    accessibilityPermission?: boolean
    screenRecordingPermission?: boolean
  }) => Promise<
    | {
        title?: string
        owner?: { name?: string; path?: string; processId?: number }
      }
    | undefined
  >
}

let mode: 'get-windows' | 'fallback' = 'get-windows'
let xdotoolMissing = false
let softMisses = 0

function clean(value: string | undefined): string {
  return (value ?? '').replace(/\s+/g, ' ').trim().slice(0, 300)
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), ms)
    promise.then(
      (value) => {
        clearTimeout(timer)
        resolve(value)
      },
      (error: unknown) => {
        clearTimeout(timer)
        reject(error)
      }
    )
  })
}

async function loadGetWindows(): Promise<GetWindowsModule> {
  const importer = new Function('specifier', 'return import(specifier)') as (
    specifier: string
  ) => Promise<GetWindowsModule>
  return importer('get-windows')
}

async function fromGetWindows(): Promise<ActiveApp | null> {
  const mod = await withTimeout(loadGetWindows(), 2000)
  const win = await withTimeout(
    mod.activeWindow({ accessibilityPermission: false, screenRecordingPermission: false }),
    2000
  )
  if (!win?.owner?.name) return null
  return {
    appName: clean(win.owner.name) || 'Inconnu',
    title: clean(win.title),
    exePath: win.owner.path ?? '',
    pid: typeof win.owner.processId === 'number' ? win.owner.processId : null,
    source: 'get-windows'
  }
}

function decodeOctal(input: string): string {
  return input.replace(/(?:\\[0-7]{3})+/g, (match) => {
    const octets = match.match(/[0-7]{3}/g) ?? []
    const bytes = Uint8Array.from(octets.map((octet) => Number.parseInt(octet, 8)))
    try {
      return new TextDecoder('utf-8').decode(bytes)
    } catch {
      return match
    }
  })
}

function quotedStrings(value: string): string[] {
  const found: string[] = []
  const pattern = /"((?:[^"\\]|\\.)*)"/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(value))) {
    found.push(
      decodeOctal(match[1])
        .replace(/\\"/g, '"')
        .replace(/\\\\/g, '\\')
        .replace(/\\n/g, '\n')
    )
  }
  return found
}

function prop(props: Record<string, string>, prefix: string): string {
  const key = Object.keys(props).find((item) => item.startsWith(prefix))
  return key ? props[key] : ''
}

function parseProps(stdout: string): Record<string, string> {
  const props: Record<string, string> = {}
  for (const row of stdout.split('\n')) {
    const eq = row.indexOf('=')
    if (eq === -1) continue
    props[row.slice(0, eq).trim()] = row.slice(eq + 1).trim()
  }
  return props
}

function activeId(stdout: string): number | null {
  const match = stdout.match(/#\s*0x([0-9a-fA-F]+)/)
  if (!match) return null
  const id = Number.parseInt(match[1], 16)
  if (!Number.isSafeInteger(id) || id <= 0) return null
  return id
}

async function fromXdotool(): Promise<ActiveApp | null> {
  if (xdotoolMissing || process.platform !== 'linux') return null
  try {
    const idRaw = text(await exec('xdotool', ['getactivewindow'], { timeout: 1500, encoding: 'utf8' })).trim()
    if (!/^\d+$/.test(idRaw) || idRaw === '0') return null
    const [titleRaw, pidRaw, classRaw] = await Promise.all([
      exec('xdotool', ['getwindowname', idRaw], { timeout: 1500, encoding: 'utf8' })
        .then((result) => text(result).trim())
        .catch(() => ''),
      exec('xdotool', ['getwindowpid', idRaw], { timeout: 1500, encoding: 'utf8' })
        .then((result) => text(result).trim())
        .catch(() => ''),
      exec('xdotool', ['getwindowclassname', idRaw], { timeout: 1500, encoding: 'utf8' })
        .then((result) => text(result).trim())
        .catch(() => '')
    ])
    const pid = Number(pidRaw)
    const proc = Number.isInteger(pid) && pid > 0 ? await exeOf(pid) : { path: '', comm: '' }
    const appName = clean(classRaw) || clean(proc.comm) || clean(titleRaw) || 'Inconnu'
    return {
      appName,
      title: clean(titleRaw),
      exePath: proc.path,
      pid: Number.isInteger(pid) && pid > 0 ? pid : null,
      source: 'xdotool'
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (code === 'ENOENT') xdotoolMissing = true
    return null
  }
}

async function fromXprop(): Promise<ActiveApp | null> {
  if (process.platform !== 'linux') return null
  try {
    const root = await exec('xprop', ['-root', '_NET_ACTIVE_WINDOW'], { timeout: 1500, encoding: 'utf8' })
    const id = activeId(text(root))
    if (!id) return null
    const details = await exec(
      'xprop',
      ['-id', String(id), 'WM_CLASS', 'WM_NAME', '_NET_WM_NAME', '_NET_WM_PID'],
      { timeout: 1500, encoding: 'utf8', env: { ...process.env, LC_ALL: 'C.UTF-8' } }
    )
    const props = parseProps(text(details))
    const wmClass = quotedStrings(prop(props, 'WM_CLASS'))
    const title = quotedStrings(prop(props, '_NET_WM_NAME'))[0] ?? quotedStrings(prop(props, 'WM_NAME'))[0] ?? ''
    const pid = Number.parseInt(prop(props, '_NET_WM_PID'), 10)
    const proc = Number.isInteger(pid) && pid > 0 ? await exeOf(pid) : { path: '', comm: '' }
    const appName = clean(wmClass[wmClass.length - 1]) || clean(proc.comm) || clean(title) || 'Inconnu'
    return {
      appName,
      title: clean(title),
      exePath: proc.path,
      pid: Number.isInteger(pid) && pid > 0 ? pid : null,
      source: 'xprop'
    }
  } catch {
    return null
  }
}

async function linuxFallback(): Promise<ActiveApp | null> {
  return (await fromXdotool()) ?? (await fromXprop())
}

async function queryX11(): Promise<ActiveApp | null> {
  if (mode === 'fallback') return linuxFallback()
  try {
    const found = await fromGetWindows()
    if (found) {
      softMisses = 0
      return found
    }
    if (process.platform === 'linux') {
      const fallback = await linuxFallback()
      if (fallback) {
        softMisses += 1
        if (softMisses >= 3) {
          mode = 'fallback'
          console.warn('[unscroll] get-windows returned no window; using xdotool/xprop')
        }
        return fallback
      }
    }
    return null
  } catch (error) {
    mode = 'fallback'
    console.warn('[unscroll] get-windows failed; using xdotool/xprop', error)
    return linuxFallback()
  }
}

export async function queryActiveWindow(): Promise<ActiveApp | null> {
  const detected = detectProvider()
  if (detected.kind === 'x11') return queryX11()
  return queryWayland(detected.kind)
}
