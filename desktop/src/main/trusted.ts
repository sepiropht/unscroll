import type { IpcMainEvent, IpcMainInvokeEvent } from 'electron'
import { isTrustedRendererUrl } from './ipcPolicy'

export function isTrustedSender(event: IpcMainEvent | IpcMainInvokeEvent): boolean {
  const frame = event.senderFrame
  if (!frame || frame.parent != null) return false
  const url = frame.url || event.sender.getURL()
  return isTrustedRendererUrl(url, process.env.ELECTRON_RENDERER_URL)
}
