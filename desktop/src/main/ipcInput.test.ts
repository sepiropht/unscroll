import { expect, test } from 'vitest'
import { windowBackground } from './chromeColor'
import { safeExternalHref } from './externalUrl'
import {
  parseAssign,
  parseCategoryId,
  parseCategorySave,
  parseExportFormat,
  parseLimitInput,
  parsePaused,
  parseSettingsPatch
} from './ipcInput'
import { isTrustedRendererUrl } from './ipcPolicy'

test('limit payloads keep a name, a minute count and a real boolean', () => {
  expect(parseLimitInput({ appName: '  Terminal  ', dailyMinutes: 60.4, strict: false })).toEqual({
    appName: 'Terminal',
    dailyMinutes: 60,
    strict: false
  })
  expect(parseLimitInput({ appName: 'Terminal', dailyMinutes: '60', strict: true })).toBeNull()
  expect(parseLimitInput({ appName: 'Terminal', dailyMinutes: 0, strict: false })).toBeNull()
  expect(parseLimitInput({ appName: 'bad\nname', dailyMinutes: 10, strict: false })).toBeNull()
  expect(parseLimitInput(['Terminal', 10, false])).toBeNull()
  expect(parsePaused(false)).toBe(false)
  expect(parsePaused('false')).toBeNull()
  expect(parseExportFormat('json')).toBe('json')
  expect(parseExportFormat('xml')).toBeNull()
})

test('category and settings patches reject the wrong shapes', () => {
  expect(parseCategorySave({ name: ' Lecture ', color: '#0F766E' })).toEqual({ name: 'Lecture', color: '#0f766e' })
  expect(parseCategorySave({ id: 1.5, name: 'Lecture', color: '#0F766E' })).toBeNull()
  expect(parseCategorySave({ name: 'Lecture', color: 'teal' })).toBeNull()
  expect(parseCategoryId(4)).toBe(4)
  expect(parseCategoryId(-1)).toBeNull()
  expect(parseAssign({ appName: 'Code', categoryId: null })).toEqual({ appName: 'Code', categoryId: null })
  expect(parseAssign({ appName: 'Code', categoryId: '2' })).toBeNull()
  expect(parseSettingsPatch({ theme: 'dark', idleThresholdSec: 5, autostart: 'yes', locale: 'zz' })).toEqual({
    theme: 'dark',
    idleThresholdSec: 10
  })
  expect(parseSettingsPatch(['dark'])).toEqual({})
})

test('renderer urls stay on the app pages', () => {
  const dev = 'http://127.0.0.1:5173'
  expect(isTrustedRendererUrl('file:///opt/temps/out/renderer/index.html', undefined)).toBe(true)
  expect(isTrustedRendererUrl('file:///opt/app.asar/out/renderer/overlay.html', undefined)).toBe(true)
  expect(isTrustedRendererUrl('file:///etc/passwd', undefined)).toBe(false)
  expect(isTrustedRendererUrl('https://example.com/out/renderer/index.html', undefined)).toBe(false)
  expect(isTrustedRendererUrl(`${dev}/overlay.html`, dev)).toBe(true)
  expect(isTrustedRendererUrl(`${dev}/`, dev)).toBe(true)
  expect(isTrustedRendererUrl('http://127.0.0.1:5174/', dev)).toBe(false)
  expect(isTrustedRendererUrl(`${dev}/overlay.html?next=https://evil.test`, dev)).toBe(false)
  expect(isTrustedRendererUrl('http://127.0.0.1:5173/', undefined)).toBe(false)
})

test('external links are https hosts without secrets or local targets', () => {
  expect(safeExternalHref('https://example.com/docs')).toBe('https://example.com/docs')
  expect(safeExternalHref('http://example.com')).toBeNull()
  expect(safeExternalHref('https://user:pw@example.com/')).toBeNull()
  expect(safeExternalHref('https://localhost/docs')).toBeNull()
  expect(safeExternalHref('https://127.0.0.1/docs')).toBeNull()
  expect(safeExternalHref('javascript:alert(1)')).toBeNull()
  expect(safeExternalHref('file:///etc/passwd')).toBeNull()
  expect(safeExternalHref('https://example.com/docs?q=1')).toBeNull()
})

test('the window background follows the theme and the system', () => {
  expect(windowBackground('dark', false)).toBe('#0B1416')
  expect(windowBackground('light', true)).toBe('#F8FAFC')
  expect(windowBackground('system', true)).toBe('#0B1416')
  expect(windowBackground('system', false)).toBe('#F8FAFC')
})
