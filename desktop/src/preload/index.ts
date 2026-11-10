import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron'
import { IPC } from '@shared/ipc'
import type { OverlayPayload, Settings, Snapshot, SyncConfigInput, UnscrollApi } from '@shared/types'

const api: UnscrollApi = {
  getSnapshot: () => ipcRenderer.invoke(IPC.snapshot),
  getHistory: () => ipcRenderer.invoke(IPC.history),
  saveLimit: (input) => ipcRenderer.invoke(IPC.saveLimit, input),
  deleteLimit: (appName) => ipcRenderer.invoke(IPC.deleteLimit, appName),
  saveCategory: (input) => ipcRenderer.invoke(IPC.saveCategory, input),
  deleteCategory: (id) => ipcRenderer.invoke(IPC.deleteCategory, id),
  setAppCategory: (appName, categoryId) => ipcRenderer.invoke(IPC.setAppCategory, { appName, categoryId }),
  updateSettings: (patch: Partial<Settings>) => ipcRenderer.invoke(IPC.updateSettings, patch),
  setPaused: (paused) => ipcRenderer.invoke(IPC.setPaused, paused),
  exportData: (format) => ipcRenderer.invoke(IPC.exportData, format),
  getOverlayState: () => ipcRenderer.invoke(IPC.overlayState),
  grantOverlay: () => ipcRenderer.invoke(IPC.overlayGrant),
  ignoreOverlay: () => ipcRenderer.invoke(IPC.overlayIgnore),
  quitFromOverlay: () => ipcRenderer.invoke(IPC.overlayQuit),
  getSyncState: () => ipcRenderer.invoke(IPC.syncState),
  saveSync: (input: SyncConfigInput) => ipcRenderer.invoke(IPC.syncSave, input),
  pushSync: () => ipcRenderer.invoke(IPC.syncPush),
  pullSync: () => ipcRenderer.invoke(IPC.syncPull),
  getPairingLink: () => ipcRenderer.invoke(IPC.syncPairingLink),
  applyPairingLink: (link: string) => ipcRenderer.invoke(IPC.syncPairingApply, link),
  getReport: () => ipcRenderer.invoke(IPC.report),
  onTick: (cb) => {
    const listener = (_event: IpcRendererEvent, snapshot: Snapshot) => cb(snapshot)
    ipcRenderer.on(IPC.tick, listener)
    return () => ipcRenderer.removeListener(IPC.tick, listener)
  },
  onOverlay: (cb) => {
    const listener = (_event: IpcRendererEvent, payload: OverlayPayload | null) => cb(payload)
    ipcRenderer.on(IPC.overlayData, listener)
    return () => ipcRenderer.removeListener(IPC.overlayData, listener)
  }
}

contextBridge.exposeInMainWorld('unscroll', api)

function paintTheme(theme: unknown): void {
  const root = document.documentElement
  if (!root) return
  if (theme === 'dark' || theme === 'light') root.dataset.theme = theme
  else delete root.dataset.theme
}

function applyInitialTheme(): void {
  try {
    const theme = ipcRenderer.sendSync(IPC.themeSync) as unknown
    if (document.documentElement) {
      paintTheme(theme)
      return
    }
    document.addEventListener('DOMContentLoaded', () => {
      try {
        paintTheme(theme)
      } catch {
        // A late theme failure must not surface as an unhandled preload error.
      }
    })
  } catch {
    // Theme setup must never prevent the bridge from loading.
  }
}

applyInitialTheme()
