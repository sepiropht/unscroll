import { execFile } from 'node:child_process'
import { mkdir, mkdtemp, rm } from 'node:fs/promises'
import net, { type Server, type Socket } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('node:child_process', () => ({
  execFile: vi.fn()
}))

import { queryActiveWindow, windowProviderStatus } from '../activeWindow'
import { hyprlandSocketPath } from './detect'
import { setHyprlandReconnectDelayForTests, stopHyprlandWatch, subscribeHyprlandWindow, queryHyprland } from './hyprland'
import { resetWarningsForTests } from './log'
import { querySway } from './sway'
import { resetUnsupportedNoticeForTests } from './unsupported'

const ENV_KEYS = [
  'HYPRLAND_INSTANCE_SIGNATURE',
  'SWAYSOCK',
  'XDG_CURRENT_DESKTOP',
  'XDG_SESSION_DESKTOP',
  'DESKTOP_SESSION',
  'XDG_SESSION_TYPE',
  'WAYLAND_DISPLAY',
  'XDG_RUNTIME_DIR'
] as const

type ExecCb = (error: NodeJS.ErrnoException | null, stdout?: string | Buffer) => void

const saved = new Map<string, string | undefined>()
let warn: ReturnType<typeof vi.spyOn>

function useEnv(values: Partial<Record<(typeof ENV_KEYS)[number], string>>): void {
  for (const key of ENV_KEYS) delete process.env[key]
  for (const [key, value] of Object.entries(values)) process.env[key] = value
}

function installExec(
  run: (
    file: string,
    args: readonly string[],
    timeout: number | undefined
  ) => { error?: NodeJS.ErrnoException | null; stdout?: string }
): void {
  vi.mocked(execFile).mockImplementation(((
    file: string,
    args: readonly string[] | undefined,
    options: { timeout?: number } | undefined,
    callback: ExecCb
  ) => {
    const result = run(String(file), args ?? [], options?.timeout)
    callback(result.error ?? null, result.stdout ?? '')
    return undefined
  }) as never)
}

function missing(code = 'ENOENT'): NodeJS.ErrnoException {
  return Object.assign(new Error(code), { code })
}

async function listen(path: string, onConnection: (client: Socket) => void): Promise<Server> {
  const server = net.createServer(onConnection)
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject)
    server.listen(path, () => {
      server.off('error', reject)
      resolve()
    })
  })
  return server
}

async function closeServer(server: Server | null, clients: Socket[]): Promise<void> {
  for (const client of clients) client.destroy()
  if (!server) return
  await new Promise<void>((resolve) => server.close(() => resolve()))
}

describe('wayland window providers', () => {
  beforeEach(() => {
    stopHyprlandWatch()
    for (const key of ENV_KEYS) saved.set(key, process.env[key])
    useEnv({})
    setHyprlandReconnectDelayForTests(1000)
    resetWarningsForTests()
    resetUnsupportedNoticeForTests()
    warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    vi.mocked(execFile).mockReset()
    vi.mocked(execFile).mockImplementation(((...args: unknown[]) => {
      const callback = args[args.length - 1]
      if (typeof callback === 'function') callback(missing())
      return undefined
    }) as never)
  })

  afterEach(async () => {
    stopHyprlandWatch()
    setHyprlandReconnectDelayForTests(1000)
    for (const key of ENV_KEYS) {
      const value = saved.get(key)
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
    warn.mockRestore()
  })

  it('runs hyprctl activewindow -j with a one second timeout', async () => {
    useEnv({ HYPRLAND_INSTANCE_SIGNATURE: 'abc', XDG_SESSION_TYPE: 'wayland' })
    installExec(() => ({ stdout: JSON.stringify({ class: 'firefox', title: 'Mozilla Firefox', pid: 4242 }) }))
    const win = await queryActiveWindow()
    expect(win).toMatchObject({
      appName: 'firefox',
      title: 'Mozilla Firefox',
      pid: 4242,
      source: 'hyprland'
    })
    const call = vi.mocked(execFile).mock.calls[0]
    expect(call?.[0]).toBe('hyprctl')
    expect(call?.[1]).toEqual(['activewindow', '-j'])
    expect(call?.[2]).toMatchObject({ timeout: 1000 })
    expect(windowProviderStatus()).toEqual({ name: 'hyprland', supported: true })
  })

  it('returns null when hyprctl prints {} or an empty class', async () => {
    installExec(() => ({ stdout: '{}' }))
    await expect(queryHyprland()).resolves.toBeNull()
    installExec(() => ({ stdout: JSON.stringify({ class: '', title: 'orphan', pid: 4 }) }))
    await expect(queryHyprland()).resolves.toBeNull()
    installExec(() => ({ stdout: 'not-json' }))
    await expect(queryHyprland()).resolves.toBeNull()
    installExec(() => ({ error: missing() }))
    await expect(queryHyprland()).resolves.toBeNull()
  })

  it('keeps Hyprland ahead of Sway when both are visible', async () => {
    useEnv({
      HYPRLAND_INSTANCE_SIGNATURE: 'abc',
      SWAYSOCK: '/tmp/sway.sock.fake',
      XDG_CURRENT_DESKTOP: 'Hyprland'
    })
    installExec((file) => {
      if (file !== 'hyprctl') return { error: missing('unexpected') }
      return { stdout: JSON.stringify({ class: 'kitty', title: 'vim', pid: 3 }) }
    })
    const win = await queryActiveWindow()
    expect(win).toMatchObject({ appName: 'kitty', source: 'hyprland' })
    expect(vi.mocked(execFile).mock.calls.map((call) => call[0])).toEqual(['hyprctl'])
  })

  it('runs swaymsg -t get_tree and reads the focused node', async () => {
    useEnv({ SWAYSOCK: '/tmp/sway.sock.fake', XDG_CURRENT_DESKTOP: 'sway' })
    const tree = {
      focused: false,
      nodes: [
        {
          focused: true,
          app_id: null,
          name: 'Document',
          pid: 77,
          window_properties: { class: 'libreoffice-writer' },
          nodes: [],
          floating_nodes: []
        }
      ],
      floating_nodes: []
    }
    installExec((file, args, timeout) => {
      expect(file).toBe('swaymsg')
      expect(args).toEqual(['-t', 'get_tree'])
      expect(timeout).toBe(1000)
      return { stdout: JSON.stringify(tree) }
    })
    const win = await queryActiveWindow()
    expect(win).toMatchObject({
      appName: 'libreoffice-writer',
      title: 'Document',
      pid: 77,
      source: 'sway'
    })
    expect(windowProviderStatus()).toEqual({ name: 'sway', supported: true })
    installExec(() => ({ error: missing() }))
    await expect(querySway()).resolves.toBeNull()
  })

  it('logs once and stays idle on KDE and GNOME Wayland', async () => {
    useEnv({ XDG_CURRENT_DESKTOP: 'KDE', XDG_SESSION_TYPE: 'wayland' })
    await expect(queryActiveWindow()).resolves.toBeNull()
    expect(windowProviderStatus()).toEqual({ name: 'kde', supported: false })
    await queryActiveWindow()
    expect(vi.mocked(execFile)).not.toHaveBeenCalled()
    const kdeWarnings = warn.mock.calls.filter((args: readonly unknown[]) => String(args[0]).includes('KDE'))
    expect(kdeWarnings).toHaveLength(1)
    expect(String(kdeWarnings[0]?.[0])).toContain('not supported')

    resetUnsupportedNoticeForTests()
    resetWarningsForTests()
    warn.mockClear()
    useEnv({ XDG_CURRENT_DESKTOP: 'ubuntu:GNOME', XDG_SESSION_TYPE: 'wayland' })
    await expect(queryActiveWindow()).resolves.toBeNull()
    expect(windowProviderStatus()).toEqual({ name: 'gnome', supported: false })
    expect(String(warn.mock.calls[0]?.[0])).toContain('GNOME')
    expect(vi.mocked(execFile)).not.toHaveBeenCalled()
  })

  it('reads activewindow events from socket2 and falls back to hyprctl', async () => {
    const runtime = await mkdtemp(join(tmpdir(), 'temps-hypr-'))
    const signature = 'testsig'
    const socketPath = hyprlandSocketPath({
      XDG_RUNTIME_DIR: runtime,
      HYPRLAND_INSTANCE_SIGNATURE: signature
    })
    expect(socketPath).toBe(join(runtime, 'hypr', signature, '.socket2.sock'))
    await mkdir(join(runtime, 'hypr', signature), { recursive: true })
    const clients: Socket[] = []
    let server: Server | null = null
    try {
      server = await listen(socketPath as string, (client) => {
        clients.push(client)
        client.write('workspace>>1\nactivewindowv2>>0x55\nactivewindow>>firefox,Hello, world\n')
      })
      useEnv({ XDG_RUNTIME_DIR: runtime, HYPRLAND_INSTANCE_SIGNATURE: signature, XDG_SESSION_TYPE: 'wayland' })
      installExec(() => ({ stdout: JSON.stringify({ class: 'firefox', title: 'from-poll', pid: 42 }) }))
      const seen = new Promise<void>((resolve) => {
        subscribeHyprlandWindow(() => resolve())
      })
      await Promise.race([
        seen,
        new Promise((_resolve, reject) => setTimeout(() => reject(new Error('socket event timed out')), 1000))
      ])
      const win = await queryHyprland()
      expect(win).toMatchObject({
        appName: 'firefox',
        title: 'Hello, world',
        pid: 42,
        source: 'hyprland'
      })
      const callsAfterCache = vi.mocked(execFile).mock.calls.length
      expect(await queryHyprland()).toMatchObject({ appName: 'firefox', title: 'Hello, world', pid: 42 })
      expect(vi.mocked(execFile).mock.calls.length).toBe(callsAfterCache)

      stopHyprlandWatch()
      for (const client of clients) client.destroy()
      await closeServer(server, [])
      server = null
      useEnv({ XDG_RUNTIME_DIR: runtime, HYPRLAND_INSTANCE_SIGNATURE: signature })
      installExec(() => ({ stdout: JSON.stringify({ class: 'kitty', title: 'vim', pid: 9 }) }))
      subscribeHyprlandWindow(() => {})
      const polled = await queryHyprland()
      expect(polled).toMatchObject({ appName: 'kitty', title: 'vim', pid: 9, source: 'hyprland' })
    } finally {
      stopHyprlandWatch()
      await closeServer(server, clients)
      await rm(runtime, { recursive: true, force: true })
    }
  })

  it('reconnects after socket2 drops and ignores a broken stream', async () => {
    const runtime = await mkdtemp(join(tmpdir(), 'temps-hypr-'))
    const signature = 'reconnect'
    const socketPath = hyprlandSocketPath({
      XDG_RUNTIME_DIR: runtime,
      HYPRLAND_INSTANCE_SIGNATURE: signature
    }) as string
    await mkdir(join(runtime, 'hypr', signature), { recursive: true })
    let connections = 0
    const clients: Socket[] = []
    let server: Server | null = null
    try {
      server = await listen(socketPath, (client) => {
        connections += 1
        clients.push(client)
        if (connections === 1) {
          client.write('this is not a line without newline yet')
          client.write('activewindow>>\n')
          client.destroy()
          return
        }
        client.write('???\nactivewindow>>Alacritty,vim\n')
      })
      setHyprlandReconnectDelayForTests(20)
      useEnv({ XDG_RUNTIME_DIR: runtime, HYPRLAND_INSTANCE_SIGNATURE: signature })
      installExec(() => ({ stdout: JSON.stringify({ class: 'Alacritty', title: 'from-poll', pid: 11 }) }))
      const seen = new Promise<void>((resolve) => {
        subscribeHyprlandWindow(() => resolve())
      })
      await Promise.race([
        seen,
        new Promise((_resolve, reject) => setTimeout(() => reject(new Error('reconnect timed out')), 1000))
      ])
      expect(connections).toBeGreaterThanOrEqual(2)
      const win = await queryHyprland()
      expect(win).toMatchObject({ appName: 'Alacritty', title: 'vim', source: 'hyprland' })
    } finally {
      stopHyprlandWatch()
      await closeServer(server, clients)
      await rm(runtime, { recursive: true, force: true })
    }
  })
})
