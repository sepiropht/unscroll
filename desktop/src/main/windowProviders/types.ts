import type { WindowSource } from '@shared/types'

export type ActiveApp = {
  appName: string
  title: string
  exePath: string
  pid: number | null
  source: Exclude<WindowSource, 'none'>
}
