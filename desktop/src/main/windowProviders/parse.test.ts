import { describe, expect, it } from 'vitest'
import { detectProvider } from './detect'
import {
  consumeLines,
  parseHyprlandSocketLine,
  parseHyprlandText,
  parseHyprlandWindow,
  parseSwayTree,
  parseSwayTreeText
} from './parse'

const hyprlandWindow = {
  address: '0x1',
  class: 'firefox',
  title: 'Mozilla Firefox',
  initialClass: 'firefox',
  pid: 4242
}

describe('hyprctl activewindow JSON', () => {
  it('maps class, title and pid', () => {
    expect(parseHyprlandWindow(hyprlandWindow)).toEqual({
      appName: 'firefox',
      title: 'Mozilla Firefox',
      pid: 4242
    })
    expect(parseHyprlandText(JSON.stringify({ class: '  Fire   Fox  ', title: ' Hello ', pid: '42' }))).toEqual({
      appName: 'Fire Fox',
      title: 'Hello',
      pid: 42
    })
  })

  it('returns null for an empty object, an empty class, or junk', () => {
    expect(parseHyprlandWindow({})).toBeNull()
    expect(parseHyprlandText('{}')).toBeNull()
    expect(parseHyprlandWindow({ class: '', title: 'x', pid: 4 })).toBeNull()
    expect(parseHyprlandWindow({ class: '   ', title: 'x', pid: 4 })).toBeNull()
    expect(parseHyprlandWindow({ title: 'only a title', pid: 4 })).toBeNull()
    expect(parseHyprlandWindow({ class: 'kitty', title: 'vim', pid: 0 })).toEqual({
      appName: 'kitty',
      title: 'vim',
      pid: null
    })
    expect(parseHyprlandWindow({ class: 'kitty', title: 'vim', pid: -1 })).toMatchObject({ pid: null })
    expect(parseHyprlandText('not-json')).toBeNull()
    expect(parseHyprlandText('')).toBeNull()
    expect(parseHyprlandWindow([])).toBeNull()
    expect(parseHyprlandWindow(null)).toBeNull()
  })
})

describe('hyprland socket2 lines', () => {
  it('reads activewindow>>class,title and keeps commas in the title', () => {
    expect(parseHyprlandSocketLine('activewindow>>firefox,Hello, world')).toEqual({
      kind: 'window',
      appName: 'firefox',
      title: 'Hello, world'
    })
    expect(parseHyprlandSocketLine('activewindow>>kitty,')).toEqual({
      kind: 'window',
      appName: 'kitty',
      title: ''
    })
  })

  it('treats an empty class as no window and ignores other events', () => {
    expect(parseHyprlandSocketLine('activewindow>>')).toEqual({ kind: 'empty' })
    expect(parseHyprlandSocketLine('activewindow>>,title')).toEqual({ kind: 'empty' })
    expect(parseHyprlandSocketLine('activewindow>>   ,title')).toEqual({ kind: 'empty' })
    expect(parseHyprlandSocketLine('activewindowv2>>0x55')).toEqual({ kind: 'ignore' })
    expect(parseHyprlandSocketLine('workspace>>1')).toEqual({ kind: 'ignore' })
    expect(parseHyprlandSocketLine('')).toEqual({ kind: 'ignore' })
  })

  it('reassembles a line that arrives in two chunks', () => {
    const first = consumeLines('', 'activewin')
    expect(first.lines).toEqual([])
    const second = consumeLines(first.buffer, 'dow>>firefox,Hi\nworkspace>>2\nactivewindow>>')
    expect(second.lines).toEqual(['activewindow>>firefox,Hi', 'workspace>>2'])
    expect(second.buffer).toBe('activewindow>>')
  })
})

describe('swaymsg get_tree JSON', () => {
  const nested = {
    name: 'root',
    focused: false,
    nodes: [
      {
        type: 'workspace',
        focused: false,
        nodes: [
          {
            focused: true,
            app_id: 'org.wezfurlong.wezterm',
            name: 'shell',
            pid: 88,
            window_properties: { class: 'ignored', title: 'nope' },
            nodes: [],
            floating_nodes: []
          }
        ],
        floating_nodes: []
      }
    ],
    floating_nodes: [{ focused: false, app_id: 'mpv', name: 'video', pid: 5, nodes: [], floating_nodes: [] }]
  }

  it('uses the focused node, prefers app_id, and takes name as the title', () => {
    expect(parseSwayTree(nested)).toEqual({
      appName: 'org.wezfurlong.wezterm',
      title: 'shell',
      pid: 88
    })
  })

  it('reads an XWayland class and a floating window', () => {
    expect(
      parseSwayTree({
        focused: false,
        nodes: [],
        floating_nodes: [
          {
            focused: true,
            app_id: null,
            name: 'Document',
            pid: 77,
            window_properties: { class: 'libreoffice-writer', title: 'other' },
            nodes: [],
            floating_nodes: []
          }
        ]
      })
    ).toEqual({ appName: 'libreoffice-writer', title: 'Document', pid: 77 })
  })

  it('skips a focused container that has no class and returns null when nothing matches', () => {
    expect(
      parseSwayTree({
        focused: true,
        name: 'workspace',
        nodes: [{ focused: true, app_id: 'foot', name: 'term', pid: 9, nodes: [], floating_nodes: [] }],
        floating_nodes: []
      })
    ).toEqual({ appName: 'foot', title: 'term', pid: 9 })
    expect(parseSwayTree({ focused: true, name: 'empty', nodes: [], floating_nodes: [] })).toBeNull()
    expect(parseSwayTreeText('nope')).toBeNull()
    expect(parseSwayTree(null)).toBeNull()
  })
})

describe('provider selection', () => {
  it('prefers Hyprland, then Sway, then reports KDE and GNOME Wayland as unsupported', () => {
    expect(
      detectProvider({
        HYPRLAND_INSTANCE_SIGNATURE: 'abc',
        SWAYSOCK: '/tmp/sway.sock',
        XDG_CURRENT_DESKTOP: 'Hyprland',
        XDG_SESSION_TYPE: 'wayland'
      })
    ).toEqual({ kind: 'hyprland', supported: true })

    expect(detectProvider({ SWAYSOCK: '/tmp/sway.sock', XDG_SESSION_TYPE: 'wayland' })).toEqual({
      kind: 'sway',
      supported: true
    })
    expect(detectProvider({ XDG_CURRENT_DESKTOP: 'Sway' })).toEqual({ kind: 'sway', supported: true })

    expect(detectProvider({ XDG_CURRENT_DESKTOP: 'KDE', XDG_SESSION_TYPE: 'wayland' })).toEqual({
      kind: 'kde',
      supported: false
    })
    expect(detectProvider({ XDG_CURRENT_DESKTOP: 'plasma', WAYLAND_DISPLAY: 'wayland-0' })).toEqual({
      kind: 'kde',
      supported: false
    })
    expect(detectProvider({ XDG_CURRENT_DESKTOP: 'ubuntu:GNOME', XDG_SESSION_TYPE: 'wayland' })).toEqual({
      kind: 'gnome',
      supported: false
    })
    expect(detectProvider({ XDG_CURRENT_DESKTOP: 'KDE', XDG_SESSION_TYPE: 'x11' })).toEqual({
      kind: 'x11',
      supported: true
    })
    expect(detectProvider({ XDG_CURRENT_DESKTOP: 'GNOME', XDG_SESSION_TYPE: 'x11', WAYLAND_DISPLAY: 'wayland-0' })).toEqual({
      kind: 'x11',
      supported: true
    })
    expect(detectProvider({})).toEqual({ kind: 'x11', supported: true })
  })
})
