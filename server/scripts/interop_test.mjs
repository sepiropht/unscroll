/**
 * Live crypto interop: desktop seal ↔ extension open against the sync server.
 * Token is read from the environment or UNSCROLL_SYNC_ENV and is never printed.
 *
 *   set -a && source /path/to/unscroll-sync.env && set +a
 *   npx tsx scripts/interop_test.mjs
 */
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const BASE = process.env.UNSCROLL_SYNC_URL || 'https://sync.unscroll.elimbi.com'
const ENV_FILE = process.env.UNSCROLL_SYNC_ENV || ''
const PASSPHRASE = 'interop-live-pass'
const VECTOR_PASSPHRASE = 'secret-passphrase'
const VECTOR_CIPHERTEXT = '9HA/yDguHSi5iTip16kYVpDGU6OcyFzo8c8tU7fIf9AIx/DYs5y+ULpd'
const VECTOR_PLAIN = '{"version":1,"revision":1}'
const DEVICE_ID = 'device.desktop.interop-test-0001'
const JAVA_HOME = process.env.JAVA_HOME || ''
const ANDROID_DIR = path.resolve(
  fileURLToPath(new URL('.', import.meta.url)),
  '../../android',
)

const repoRoot = fileURLToPath(new URL('..', import.meta.url))
const desktopCryptoUrl = pathToFileURL(
  path.resolve(repoRoot, '../desktop/src/main/sync/crypto.ts'),
).href
const extensionCodecUrl = pathToFileURL(
  path.resolve(repoRoot, '../extension/src/edition/sync-codec.ts'),
).href

function fail(message) {
  console.error(`interop: ${message}`)
  process.exit(1)
}

function loadToken() {
  const fromEnv = process.env.UNSCROLL_SYNC_TOKEN
  if (fromEnv && fromEnv.length > 0) return fromEnv
  let text
  try {
    text = readFileSync(ENV_FILE, 'utf8')
  } catch {
    fail('UNSCROLL_SYNC_TOKEN is unset and the env file could not be read')
  }
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq < 0) continue
    if (line.slice(0, eq).trim() !== 'UNSCROLL_SYNC_TOKEN') continue
    let value = line.slice(eq + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    if (!value) break
    return value
  }
  fail('UNSCROLL_SYNC_TOKEN is missing')
}

function authHeaders(token, extra = {}) {
  return { Authorization: `Bearer ${token}`, ...extra }
}

async function request(token, method, id, { headers = {}, body } = {}) {
  const url = `${BASE}/v1/blobs/${id}`
  let response
  try {
    response = await fetch(url, {
      method,
      redirect: 'error',
      headers: authHeaders(token, headers),
      body,
    })
  } catch (error) {
    const name = error instanceof Error ? error.name : 'Error'
    fail(`${method} ${id} network ${name}`)
  }
  const bytes = Buffer.from(await response.arrayBuffer())
  return { status: response.status, etag: response.headers.get('etag'), bytes }
}

function quotedEtag(header) {
  if (!header) return null
  const trimmed = header.trim()
  if (trimmed.startsWith('W/')) fail('weak etag')
  return trimmed.startsWith('"') ? trimmed : `"${trimmed}"`
}

async function putBlob(token, id, body) {
  let probe = await request(token, 'GET', id)
  if (probe.status !== 200 && probe.status !== 404) fail(`GET ${id} before put status ${probe.status}`)
  const headers = { 'Content-Type': 'application/json' }
  if (probe.status === 404) headers['If-None-Match'] = '*'
  else headers['If-Match'] = quotedEtag(probe.etag)

  let put = await request(token, 'PUT', id, { headers, body })
  if (put.status === 412) {
    probe = await request(token, 'GET', id)
    if (probe.status !== 200) fail(`GET ${id} after 412 status ${probe.status}`)
    put = await request(token, 'PUT', id, {
      headers: { 'Content-Type': 'application/json', 'If-Match': quotedEtag(probe.etag) },
      body,
    })
  }
  if (put.status !== 204 && put.status !== 200) fail(`PUT ${id} status ${put.status}`)
}

async function assertVector(desktop, extension) {
  const salt = new Uint8Array(16).fill(0x01)
  const iv = new Uint8Array(12).fill(0x02)
  const sealed = await extension.seal({ version: 1, revision: 1 }, VECTOR_PASSPHRASE, 1000, { salt, iv })
  if (sealed.ciphertext !== VECTOR_CIPHERTEXT) fail('extension seal missed the shared ciphertext vector')
  const opened = await extension.open(sealed, VECTOR_PASSPHRASE)
  if (JSON.stringify(opened) !== VECTOR_PLAIN) fail('extension open missed the shared plaintext vector')
  const desktopPlain = await desktop.openSealed(sealed, VECTOR_PASSPHRASE)
  if (desktopPlain !== VECTOR_PLAIN) fail('desktop open missed the shared plaintext vector')
  console.log('vector: extension seal/open and desktop open match')
}

async function main() {
  const token = loadToken()
  if (token.length < 8) fail('token rejected by length check')

  const desktop = await import(desktopCryptoUrl)
  const extension = await import(extensionCodecUrl)
  await assertVector(desktop, extension)

  const usage = {
    version: 1,
    kind: 'usage',
    revision: 1,
    updatedAt: '2026-11-05T12:00:00.000Z',
    platform: 'desktop',
    deviceId: 'interop-test-0001',
    snapshot: { source: 'interop' },
  }
  const usagePlain = JSON.stringify(usage)
  const usageEnvelope = await desktop.seal(usagePlain, PASSPHRASE, 150000)
  const usageBody = JSON.stringify(usageEnvelope)
  await putBlob(token, DEVICE_ID, usageBody)
  const usageGet = await request(token, 'GET', DEVICE_ID)
  if (usageGet.status !== 200) fail(`GET ${DEVICE_ID} status ${usageGet.status}`)
  if (usageGet.bytes.toString('utf8') !== usageBody) fail('device blob bytes changed in transit')
  const usageOpened = await extension.open(JSON.parse(usageGet.bytes.toString('utf8')), PASSPHRASE)
  if (JSON.stringify(usageOpened) !== usagePlain) fail('extension open of device blob != desktop plaintext')
  console.log(`device blob: desktop seal → PUT/GET → extension open ok (${usageGet.bytes.length} bytes)`)

  const settings = {
    version: 1,
    kind: 'settings',
    revision: 1,
    updatedAt: '2026-11-05T12:00:00.000Z',
    body: {
      preferences: { interop: true },
      limits: [],
      categories: [],
      schedules: [],
      blocklist: [],
      whitelist: [],
    },
  }
  const settingsEnvelope = await extension.seal(settings, PASSPHRASE, 150000)
  const settingsBody = JSON.stringify(settingsEnvelope)
  await putBlob(token, 'settings', settingsBody)
  const settingsGet = await request(token, 'GET', 'settings')
  if (settingsGet.status !== 200) fail('GET settings status ' + settingsGet.status)
  if (settingsGet.bytes.toString('utf8') !== settingsBody) fail('settings blob bytes changed in transit')
  const settingsPlain = await desktop.openSealed(JSON.parse(settingsGet.bytes.toString('utf8')), PASSPHRASE)
  if (settingsPlain !== JSON.stringify(settings)) fail('desktop open of settings blob != extension plaintext')
  const digest = createHash('sha256').update(settingsGet.bytes).digest('hex')
  console.log(`settings blob: extension seal → PUT/GET → desktop open ok (sha256 ${digest.slice(0, 12)}…)`)

  if (!existsSync(JAVA_HOME)) {
    console.log('android: skipped (JDK 17 toolchain not present)')
    return
  }
  console.log('android: SyncCryptoTest')
  const gradle = spawnSync(
    './gradlew',
    [':app:testProDebugUnitTest', '--tests', 'cm.elimbi.screentime.sync.SyncCryptoTest'],
    {
      cwd: ANDROID_DIR,
      env: { ...process.env, JAVA_HOME },
      stdio: 'inherit',
    },
  )
  if (gradle.error) fail(`android spawn ${gradle.error.code || gradle.error.message}`)
  if (gradle.status !== 0) fail(`android SyncCryptoTest exit ${gradle.status}`)
  console.log('android: SyncCryptoTest passed')
}

main().catch((error) => {
  const message = error instanceof Error ? error.message : 'failed'
  fail(message)
})
