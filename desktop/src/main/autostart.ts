import { app } from 'electron'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { assetPath } from './assets'

const DESKTOP_NAME = 'unscroll.desktop'

function quote(value: string): string {
  return `"${value.replace(/"/g, '')}"`
}

function launchCommand(): string {
  if (app.isPackaged) return `${quote(process.execPath)} --hidden`
  const entry = process.argv[1] ? join(process.cwd(), process.argv[1]) : process.cwd()
  return `${quote(process.execPath)} ${quote(entry)} --hidden`
}

function desktopFile(): string {
  return join(homedir(), '.config', 'autostart', DESKTOP_NAME)
}

export function applyAutostart(enabled: boolean): void {
  try {
    app.setLoginItemSettings({
      openAtLogin: enabled,
      args: enabled ? ['--hidden'] : []
    })
  } catch (error) {
    console.warn('[unscroll] setLoginItemSettings failed', error)
  }

  if (process.platform !== 'linux') return
  const file = desktopFile()
  if (!enabled) {
    rmSync(file, { force: true })
    return
  }
  mkdirSync(join(homedir(), '.config', 'autostart'), { recursive: true })
  const body = `[Desktop Entry]
Type=Application
Version=1.0
Name=Unscroll
Comment=Local-first screen time tracking
Exec=${launchCommand()}
Icon=${assetPath('icon.png')}
Terminal=false
Categories=Utility;
X-GNOME-Autostart-enabled=true
StartupNotify=false
`
  writeFileSync(file, body, 'utf8')
}
