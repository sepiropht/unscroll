import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from 'vitest'

const root = fileURLToPath(new URL('../..', import.meta.url))

function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '')
}

test('preload exposes the bridge before it touches the document', () => {
  const source = stripComments(readFileSync(join(root, 'src/preload/index.ts'), 'utf8'))
  const exposeAt = source.indexOf('exposeInMainWorld')
  const documentAt = source.search(/\bdocument\b/)
  const datasetAt = source.indexOf('.dataset')
  const guardsNull =
    /if\s*\(\s*!root\s*\)\s*return/.test(source) ||
    /if\s*\(\s*document\.documentElement\s*\)/.test(source) ||
    /document\.documentElement\?\./.test(source)

  expect(exposeAt).toBeGreaterThanOrEqual(0)
  expect(exposeAt < documentAt || guardsNull).toBe(true)
  expect(datasetAt).toBeGreaterThan(exposeAt)
  expect(source.slice(0, datasetAt)).toMatch(/if\s*\(\s*!root\s*\)/)
  expect(source).toContain('DOMContentLoaded')
  expect(source).toContain('sendSync')
  expect(source).toMatch(/try\s*\{[\s\S]*sendSync/)
})
