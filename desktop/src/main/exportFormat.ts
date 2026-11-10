export type CsvSession = {
  day: string
  app_name: string
  title: string
  exe_path: string
  started_at: number
  ended_at: number
  duration_ms: number
}

export function csvEscape(value: string): string {
  if (/[",\n\r]/.test(value)) return `"${value.replace(/"/g, '""')}"`
  return value
}

export function sessionsToCsv(rows: CsvSession[]): string {
  const lines = ['jour,application,titre,chemin,debut,fin,duree_secondes']
  for (const row of rows) {
    lines.push(
      [
        row.day,
        csvEscape(row.app_name),
        csvEscape(row.title),
        csvEscape(row.exe_path),
        new Date(row.started_at).toISOString(),
        new Date(row.ended_at).toISOString(),
        String(Math.round(row.duration_ms / 1000))
      ].join(',')
    )
  }
  return `\uFEFF${lines.join('\n')}\n`
}

export function redactSettings(rows: { key: string; value: string }[]): { key: string; value: string }[] {
  return rows.map((row) => {
    if (row.key !== 'syncConfig') return row
    try {
      const parsed = JSON.parse(row.value) as { token?: string; passphrase?: string; secret?: string; key?: string }
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { key: row.key, value: '{}' }
      return {
        key: row.key,
        value: JSON.stringify({ ...parsed, token: '', passphrase: '', secret: '', key: '' })
      }
    } catch {
      return { key: row.key, value: '{}' }
    }
  })
}

export function toExportJson(input: {
  version: string
  exportedAt: string
  sessions: unknown
  limits: unknown
  categories: unknown
  appCategories: unknown
  settings: { key: string; value: string }[]
}): string {
  return JSON.stringify(
    {
      app: 'temps-ecran-desktop',
      version: input.version,
      exportedAt: input.exportedAt,
      sessions: input.sessions,
      limits: input.limits,
      categories: input.categories,
      appCategories: input.appCategories,
      settings: redactSettings(input.settings)
    },
    null,
    2
  )
}
