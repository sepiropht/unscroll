export type ParsedWindow = {
  appName: string
  title: string
  pid: number | null
}

export type HyprlandSocketEvent =
  | { kind: 'ignore' }
  | { kind: 'empty' }
  | { kind: 'window'; appName: string; title: string }

const TEXT_LIMIT = 300

export function cleanWindowText(value: unknown): string {
  if (typeof value !== 'string') return ''
  return value.replace(/\s+/g, ' ').trim().slice(0, TEXT_LIMIT)
}

export function positivePid(value: unknown): number | null {
  const pid =
    typeof value === 'number'
      ? value
      : typeof value === 'string' && value.trim() !== ''
        ? Number(value)
        : Number.NaN
  if (!Number.isInteger(pid) || pid <= 0) return null
  return pid
}

export function parseHyprlandWindow(value: unknown): ParsedWindow | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  const appName = cleanWindowText(record.class)
  if (!appName) return null
  return {
    appName,
    title: cleanWindowText(record.title),
    pid: positivePid(record.pid)
  }
}

export function parseHyprlandText(text: string): ParsedWindow | null {
  try {
    return parseHyprlandWindow(JSON.parse(text))
  } catch {
    return null
  }
}

/**
 * socket2 lines look like `activewindow>>class,title`.
 * The title may contain commas, so only the first comma separates the fields.
 * `activewindowv2>>` is a different event and is ignored.
 * An empty class (`activewindow>>` or `activewindow>>,title`) means no window.
 */
export function parseHyprlandSocketLine(line: string): HyprlandSocketEvent {
  const trimmed = line.replace(/^\uFEFF/, '').trim()
  if (!trimmed.startsWith('activewindow>>')) return { kind: 'ignore' }
  const payload = trimmed.slice('activewindow>>'.length)
  const comma = payload.indexOf(',')
  const classRaw = comma === -1 ? payload : payload.slice(0, comma)
  const titleRaw = comma === -1 ? '' : payload.slice(comma + 1)
  const appName = cleanWindowText(classRaw)
  if (!appName) return { kind: 'empty' }
  return { kind: 'window', appName, title: cleanWindowText(titleRaw) }
}

export function consumeLines(
  buffer: string,
  chunk: string,
  maxBuffer = 100_000
): { buffer: string; lines: string[] } {
  let next = buffer + chunk
  if (next.length > maxBuffer) next = next.slice(next.length - maxBuffer)
  const lines: string[] = []
  for (;;) {
    const nl = next.indexOf('\n')
    if (nl === -1) break
    lines.push(next.slice(0, nl))
    next = next.slice(nl + 1)
  }
  return { buffer: next, lines }
}

function windowClass(value: unknown): string {
  if (!value || typeof value !== 'object') return ''
  return cleanWindowText((value as Record<string, unknown>).class)
}

function walkSway(value: unknown, found: ParsedWindow[]): void {
  if (!value || typeof value !== 'object') return
  const node = value as Record<string, unknown>
  if (node.focused === true) {
    const appName = cleanWindowText(node.app_id) || windowClass(node.window_properties)
    if (appName) {
      found.push({
        appName,
        title: cleanWindowText(node.name),
        pid: positivePid(node.pid)
      })
    }
  }
  if (Array.isArray(node.nodes)) {
    for (const child of node.nodes) walkSway(child, found)
  }
  if (Array.isArray(node.floating_nodes)) {
    for (const child of node.floating_nodes) walkSway(child, found)
  }
}

export function parseSwayTree(value: unknown): ParsedWindow | null {
  const found: ParsedWindow[] = []
  walkSway(value, found)
  return found[0] ?? null
}

export function parseSwayTreeText(text: string): ParsedWindow | null {
  try {
    return parseSwayTree(JSON.parse(text))
  } catch {
    return null
  }
}
