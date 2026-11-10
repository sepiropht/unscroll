import { readFileSync, readdirSync, statSync } from 'node:fs'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from 'vitest'
import { PROD_CSP } from '@shared/csp'

const root = fileURLToPath(new URL('../..', import.meta.url))
const textExt = new Set(['.ts', '.tsx', '.js', '.mjs', '.css', '.html', '.svg', '.md', '.json', '.yml'])

function filesUnder(dir: string): string[] {
  const found: string[] = []
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry)
    const info = statSync(path)
    if (info.isDirectory()) {
      if (entry === 'node_modules' || entry === 'out' || entry === 'release') continue
      found.push(...filesUnder(path))
      continue
    }
    if (!textExt.has(extname(path)) || path.endsWith('.test.ts')) continue
    found.push(path)
  }
  return found
}

test('production CSP keeps scripts local and blocks frames', () => {
  expect(PROD_CSP).toContain("script-src 'self'")
  expect(PROD_CSP).not.toMatch(/script-src[^;]*unsafe/)
  expect(PROD_CSP).not.toContain('unsafe-eval')
  expect(PROD_CSP).not.toContain('http:')
  expect(PROD_CSP).not.toContain('*')
  expect(PROD_CSP).toContain("frame-src 'none'")
  expect(PROD_CSP).toContain("object-src 'none'")
})

test('renderer preferences isolate and sandbox the page', () => {
  const privacy = readFileSync(join(root, 'src/main/privacy.ts'), 'utf8')
  expect(privacy).toContain('contextIsolation: true')
  expect(privacy).toContain('nodeIntegration: false')
  expect(privacy).toContain('sandbox: true')
  expect(privacy).toContain('webviewTag: false')
  expect(privacy).not.toContain('nodeIntegration: true')
  expect(privacy).not.toContain('contextIsolation: false')
  expect(privacy).not.toContain('sandbox: false')
})

test('shell.openExternal exists only behind the https filter', () => {
  const allowed = join(root, 'src/main/openExternal.ts')
  const gate = readFileSync(allowed, 'utf8')
  const callAt = gate.indexOf('shell.openExternal')
  expect(callAt).toBeGreaterThan(gate.indexOf('safeExternalHref'))
  expect(gate.match(/shell\.openExternal/g)).toHaveLength(1)
  for (const file of filesUnder(join(root, 'src'))) {
    if (file === allowed) continue
    const text = readFileSync(file, 'utf8')
    expect(text.includes('shell.openExternal'), file).toBe(false)
    expect(text.includes('.openExternal('), file).toBe(false)
  }
})

