const warned = new Set<string>()

export function warnOnce(key: string, detail?: unknown): void {
  if (warned.has(key)) return
  warned.add(key)
  if (detail === undefined) console.warn(`[unscroll] ${key}`)
  else console.warn(`[unscroll] ${key}`, detail)
}

export function resetWarningsForTests(): void {
  warned.clear()
}
