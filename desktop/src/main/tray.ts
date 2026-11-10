import { Menu, Tray, nativeImage } from 'electron'
import { t } from '@shared/i18n'
import type { Locale } from '@shared/types'
import { assetPath } from './assets'

let tray: Tray | null = null

export function createTray(handlers: {
  onOpen: () => void
  onTogglePause: () => void
  onQuit: () => void
}): void {
  const image = nativeImage.createFromPath(assetPath('tray.png'))
  tray = new Tray(image.isEmpty() ? nativeImage.createFromPath(assetPath('icon.png')) : image)
  tray.on('click', handlers.onOpen)
  updateTray({
    locale: 'fr',
    totalLabel: '0 min',
    paused: false,
    onOpen: handlers.onOpen,
    onTogglePause: handlers.onTogglePause,
    onQuit: handlers.onQuit
  })
}

export function updateTray(input: {
  locale: Locale
  totalLabel: string
  paused: boolean
  onOpen: () => void
  onTogglePause: () => void
  onQuit: () => void
}): void {
  if (!tray) return
  const today = t(input.locale, 'tray.today', { total: input.totalLabel })
  tray.setToolTip(`${t(input.locale, 'app.name')} — ${today}`)
  if (process.platform === 'linux' || process.platform === 'darwin') {
    tray.setTitle(input.totalLabel)
  }
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: today, enabled: false },
      { label: t(input.locale, 'tray.open'), click: input.onOpen },
      {
        label: t(input.locale, input.paused ? 'tray.resume' : 'tray.pause'),
        click: input.onTogglePause
      },
      { type: 'separator' },
      { label: t(input.locale, 'tray.quit'), click: input.onQuit }
    ])
  )
}

export function destroyTray(): void {
  tray?.destroy()
  tray = null
}
