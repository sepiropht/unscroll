import type { ActiveApp } from './types'
import { execText } from './exec'
import { warnOnce } from './log'
import { parseSwayTreeText } from './parse'
import { exeOf } from './processInfo'

const SWAYMSG_TIMEOUT_MS = 1000

export async function querySway(): Promise<ActiveApp | null> {
  let stdout = ''
  try {
    stdout = await execText('swaymsg', ['-t', 'get_tree'], SWAYMSG_TIMEOUT_MS)
  } catch (error) {
    warnOnce('swaymsg get_tree failed', error)
    return null
  }
  const parsed = parseSwayTreeText(stdout)
  if (!parsed) return null
  const exePath = parsed.pid != null ? (await exeOf(parsed.pid)).path : ''
  return {
    appName: parsed.appName,
    title: parsed.title,
    exePath,
    pid: parsed.pid,
    source: 'sway'
  }
}
