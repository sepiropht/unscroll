import { ipcMain, type IpcMainInvokeEvent } from 'electron'
import { isTrustedSender } from './trusted'

export function handleIpc(
  channel: string,
  listener: (event: IpcMainInvokeEvent, ...args: unknown[]) => unknown
): void {
  ipcMain.handle(channel, (event, ...args) => {
    if (!isTrustedSender(event)) return { ok: false, reason: 'denied' }
    return listener(event, ...args)
  })
}
