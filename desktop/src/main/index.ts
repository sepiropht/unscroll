import { app, BrowserWindow, Menu, dialog, ipcMain, nativeTheme, session } from 'electron'
import { join } from 'node:path'
import { registerEdition, shutdownEdition } from '@edition-main'
import { IPC } from '@shared/ipc'
import type { ThemeSetting } from '@shared/types'
import { formatDuration } from '@shared/format'
import { applyAutostart } from './autostart'
import { assetPath } from './assets'
import { composeBuildId, decideSecondInstance } from './buildId'
import { windowBackground } from './chromeColor'
import {
  closeDatabase,
  deleteCategory,
  deleteLimit,
  findCategory,
  insertCategory,
  openDatabase,
  setAppCategory,
  updateCategory,
  upsertLimit
} from './db'
import { exportData } from './exportData'
import { handleIpc } from './ipcHandle'
import {
  parseAppName,
  parseAssign,
  parseCategoryId,
  parseCategorySave,
  parseExportFormat,
  parseLimitInput,
  parsePaused,
  parseSettingsPatch
} from './ipcInput'
import { isQuitting, launchedHidden, markQuitting } from './lifecycle'
import { currentOverlay, grantFromOverlay, ignoreFromOverlay } from './overlay'
import { getSettings, updateSettings } from './settings'
import { buildHistory, buildSnapshot } from './snapshot'
import { createTray, updateTray } from './tray'
import {
  applyCommandLinePrivacy,
  hardenSession,
  hardenWindow,
  installContentGuards,
  rendererWebPreferences
} from './privacy'
import { isTrustedSender } from './trusted'
import { setTrackingPaused, startTracker, stopTracker } from './tracker'

applyCommandLinePrivacy()
installContentGuards()

let mainWindow: BrowserWindow | null = null

function applyNativeTheme(theme: ThemeSetting): void {
  nativeTheme.themeSource = theme === 'system' ? 'system' : theme
}

function paintChrome(): void {
  if (!mainWindow || mainWindow.isDestroyed()) return
  mainWindow.setBackgroundColor(windowBackground(getSettings().theme, nativeTheme.shouldUseDarkColors))
}

function showMain(): void {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createMainWindow(true)
    return
  }
  mainWindow.show()
  mainWindow.focus()
}

function createMainWindow(show: boolean): void {
  const settings = getSettings()
  const win = new BrowserWindow({
    width: 1120,
    height: 780,
    minWidth: 920,
    minHeight: 640,
    show: false,
    autoHideMenuBar: true,
    backgroundColor: windowBackground(settings.theme, nativeTheme.shouldUseDarkColors),
    icon: assetPath('icon.png'),
    title: 'Unscroll',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      ...rendererWebPreferences
    }
  })
  hardenWindow(win)
  mainWindow = win
  win.on('close', (event) => {
    if (isQuitting()) return
    event.preventDefault()
    win.hide()
  })
  win.on('closed', () => {
    if (mainWindow === win) mainWindow = null
  })
  if (process.env.ELECTRON_RENDERER_URL) void win.loadURL(process.env.ELECTRON_RENDERER_URL)
  else void win.loadFile(join(__dirname, '../renderer/index.html'))
  win.once('ready-to-show', () => {
    if (show) win.show()
  })
}

function broadcast(): void {
  const snapshot = buildSnapshot()
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.send(IPC.tick, snapshot)
  updateTray({
    locale: snapshot.settings.locale,
    totalLabel: formatDuration(snapshot.todayTotalMs, snapshot.settings.locale),
    paused: snapshot.settings.trackingPaused,
    onOpen: showMain,
    onTogglePause: () => setTrackingPaused(!getSettings().trackingPaused),
    onQuit: () => app.quit()
  })
}

function registerIpc(): void {
  ipcMain.on(IPC.themeSync, (event) => {
    event.returnValue = isTrustedSender(event) ? getSettings().theme : 'system'
  })
  handleIpc(IPC.snapshot, () => buildSnapshot())
  handleIpc(IPC.history, () => buildHistory())
  handleIpc(IPC.saveLimit, (_event, input) => {
    const parsed = parseLimitInput(input)
    if (!parsed) return { ok: false as const, reason: 'invalid' }
    upsertLimit(parsed.appName, parsed.dailyMinutes, parsed.strict)
    return { ok: true as const }
  })
  handleIpc(IPC.deleteLimit, (_event, appName) => {
    const name = parseAppName(appName)
    if (!name) return { ok: false as const, reason: 'invalid' }
    deleteLimit(name)
    return { ok: true as const }
  })
  handleIpc(IPC.saveCategory, (_event, input) => {
    const parsed = parseCategorySave(input)
    if (!parsed) return { ok: false as const, reason: 'invalid' }
    if (parsed.id != null) {
      if (!findCategory(parsed.id)) return { ok: false as const, reason: 'missing' }
      updateCategory(parsed.id, parsed.name, parsed.color)
    } else {
      insertCategory(parsed.name, parsed.color)
    }
    return { ok: true as const }
  })
  handleIpc(IPC.deleteCategory, (_event, id) => {
    const categoryId = parseCategoryId(id)
    if (categoryId == null || !deleteCategory(categoryId)) return { ok: false as const, reason: 'keep' }
    return { ok: true as const }
  })
  handleIpc(IPC.setAppCategory, (_event, input) => {
    const parsed = parseAssign(input)
    if (!parsed) return { ok: false as const, reason: 'invalid' }
    if (parsed.categoryId != null && !findCategory(parsed.categoryId)) {
      return { ok: false as const, reason: 'missing' }
    }
    setAppCategory(parsed.appName, parsed.categoryId)
    return { ok: true as const }
  })
  handleIpc(IPC.updateSettings, (_event, input) => {
    const patch = parseSettingsPatch(input)
    const next = updateSettings(patch)
    if (typeof patch.autostart === 'boolean') applyAutostart(next.autostart)
    if (patch.theme) {
      applyNativeTheme(next.theme)
      paintChrome()
    }
    if (typeof patch.trackingPaused === 'boolean') return setTrackingPaused(next.trackingPaused)
    broadcast()
    return next
  })
  handleIpc(IPC.setPaused, (_event, paused) => {
    const next = parsePaused(paused)
    if (next == null) return getSettings()
    return setTrackingPaused(next)
  })
  handleIpc(IPC.exportData, (_event, format) => {
    const kind = parseExportFormat(format)
    if (!kind) return { ok: false as const, canceled: false, message: 'invalid' }
    const parent = mainWindow && !mainWindow.isDestroyed() ? mainWindow : null
    return exportData(kind, parent)
  })
  handleIpc(IPC.overlayState, () => currentOverlay())
  handleIpc(IPC.overlayGrant, () => grantFromOverlay())
  handleIpc(IPC.overlayIgnore, () => ignoreFromOverlay())
  handleIpc(IPC.overlayQuit, () => {
    app.quit()
  })
}

function shutdownRunningApp(): void {
  markQuitting()
  shutdownEdition()
  stopTracker()
  closeDatabase()
}

function staleLaunchArgs(): string[] {
  const stale = [process.execPath, ...process.argv.slice(1)]
  const appImage = process.env.APPIMAGE
  if (appImage) stale.push(appImage)
  return stale
}

let handingOver = false

function handOver(execPath: string, args: string[]): void {
  if (handingOver) return
  handingOver = true
  // The window close handler hides to the tray unless quitting is already set.
  // exit() skips before-quit, so the database has to be closed here.
  app.relaunch({ execPath, args })
  shutdownRunningApp()
  app.releaseSingleInstanceLock()
  app.exit(0)
}

const buildId = composeBuildId(app.getVersion(), __BUILD_ID__)
const gotLock = app.requestSingleInstanceLock({
  buildId,
  execPath: process.env.APPIMAGE || process.execPath,
  argv: process.argv.slice(1)
})
if (!gotLock) {
  app.quit()
} else {
  app.on('second-instance', (_event, _argv, _workingDirectory, additionalData) => {
    const decision = decideSecondInstance(buildId, additionalData, staleLaunchArgs())
    if (decision.action === 'replace') {
      handOver(decision.execPath, decision.args)
      return
    }
    if (decision.action === 'show') showMain()
  })
  app.on('before-quit', () => {
    shutdownRunningApp()
  })
  app.on('window-all-closed', () => {
    // The tray keeps the process running.
  })
  app.on('activate', () => showMain())
  app
    .whenReady()
    .then(() => {
      app.setAppUserModelId('app.unscroll.desktop')
      app.setName('Unscroll')
      Menu.setApplicationMenu(
        Menu.buildFromTemplate([{ label: 'Unscroll', submenu: [{ role: 'quit' }] }])
      )
      hardenSession(session.defaultSession)
      openDatabase(join(app.getPath('userData'), 'unscroll.sqlite'))
      applyNativeTheme(getSettings().theme)
      nativeTheme.on('updated', () => {
        if (getSettings().theme === 'system') paintChrome()
      })
      applyAutostart(getSettings().autostart)
      registerIpc()
      registerEdition()
      createTray({
        onOpen: showMain,
        onTogglePause: () => setTrackingPaused(!getSettings().trackingPaused),
        onQuit: () => app.quit()
      })
      createMainWindow(!launchedHidden())
      startTracker(broadcast)
      broadcast()
    })
    .catch((error: unknown) => {
      console.error(error)
      dialog.showErrorBox('Unscroll', error instanceof Error ? error.message : String(error))
      app.quit()
    })
}
