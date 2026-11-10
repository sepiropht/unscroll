import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'

const markers = [
  'aes-256-gcm',
  '/v1/snapshot',
  'advanced-report-v1',
  'PBKDF2-SHA-256',
  'Envoyer l’instantané'
]
const root = join(import.meta.dirname, '..', 'out')
const files = []

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) walk(path)
    else if (/\.(js|mjs|cjs|html|css|map)$/.test(name)) files.push(path)
  }
}

walk(root)
const bundle = files.map((file) => readFileSync(file, 'utf8')).join('\n')
const missing = markers.filter((marker) => !bundle.includes(marker))

if (missing.length > 0) {
  console.error(`Build is missing sync code: ${missing.join(', ')}`)
  process.exit(1)
}
if (bundle.includes('update.electronjs.org') || bundle.includes('electron-updater') || bundle.includes('crashReporter')) {
  console.error('Build contains an updater or crash reporter')
  process.exit(1)
}

console.log('edition: sync markers present')
