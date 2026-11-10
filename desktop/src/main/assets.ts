import { app } from 'electron'
import { join } from 'node:path'

export function assetPath(name: string): string {
  if (app.isPackaged) return join(process.resourcesPath, name)
  return join(app.getAppPath(), 'resources', name)
}
