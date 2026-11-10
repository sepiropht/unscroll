import { app, BrowserWindow, dialog } from 'electron'
import { writeFileSync } from 'node:fs'
import { dayKey } from '@shared/day'
import { t } from '@shared/i18n'
import type { ExportResult } from '@shared/types'
import { allSettings, listAppCategories, listCategories, listLimits, listSessions } from './db'
import { sessionsToCsv, toExportJson } from './exportFormat'
import { getSettings } from './settings'

function toCsv(): string {
  return sessionsToCsv(listSessions())
}

function toJson(): string {
  return toExportJson({
    version: app.getVersion(),
    exportedAt: new Date().toISOString(),
    sessions: listSessions(),
    limits: listLimits(),
    categories: listCategories(),
    appCategories: listAppCategories(),
    settings: allSettings()
  })
}

export async function exportData(format: 'csv' | 'json', parent: BrowserWindow | null): Promise<ExportResult> {
  const settings = getSettings()
  const stamp = dayKey(Date.now(), settings.dayStartHour)
  const options = {
    title: t(settings.locale, format === 'csv' ? 'export.csv' : 'export.json'),
    defaultPath: `unscroll-${stamp}.${format}`,
    filters:
      format === 'csv'
        ? [{ name: 'CSV', extensions: ['csv'] }]
        : [{ name: 'JSON', extensions: ['json'] }]
  }
  const result = parent ? await dialog.showSaveDialog(parent, options) : await dialog.showSaveDialog(options)
  if (result.canceled || !result.filePath) return { ok: false, canceled: true }
  try {
    writeFileSync(result.filePath, format === 'csv' ? toCsv() : toJson(), 'utf8')
    return { ok: true, path: result.filePath }
  } catch (error) {
    return { ok: false, canceled: false, message: error instanceof Error ? error.message : 'export' }
  }
}
