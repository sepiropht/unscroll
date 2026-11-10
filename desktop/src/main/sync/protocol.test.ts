import http from 'node:http'
import { createHash, webcrypto } from 'node:crypto'
import { expect, test } from 'vitest'
import { DEFAULT_ITERATIONS, openSealed, seal, type SyncEnvelope } from './crypto.ts'
import { readSettingsDocument, readUsageDocument, settingsDocument, usageDocument } from './document.ts'
import { syncRequest } from './http.ts'
import type { SyncSnapshot } from './provider.ts'
import { createSyncProvider, type SyncBridge, type SyncRuntimeConfig } from './pro-provider.ts'
import { accountEndpoint, blobEndpoint, deviceBlobId, isSyncToken, parseSyncUrl, validPassphrase } from './url.ts'
import { sanitizeSnapshot } from './validate.ts'

const started = Date.parse('2026-06-02T10:00:00.000Z')
const PASSPHRASE = 'secret-passphrase'

const KNOWN_ENVELOPE = {
  version: 1 as const,
  alg: 'AES-GCM' as const,
  kdf: 'PBKDF2-SHA-256' as const,
  iterations: 1000,
  salt: 'AQEBAQEBAQEBAQEBAQEBAQ==',
  iv: 'AgICAgICAgICAgIC',
  ciphertext: '9HA/yDguHSi5iTip16kYVpDGU6OcyFzo8c8tU7fIf9AIx/DYs5y+ULpd'
}

function sample(appName = 'SecretApp'): SyncSnapshot {
  return {
    schema: 1,
    app: 'temps-ecran-desktop',
    exportedAt: '2026-06-02T12:00:00.000Z',
    deviceId: 'device-local-0001',
    sessions: [
      {
        appName,
        title: 'notes',
        exePath: '/usr/bin/secret',
        startedAt: started,
        endedAt: started + 60_000,
        durationMs: 60_000,
        day: '2026-06-02'
      }
    ],
    limits: [],
    grants: [],
    flags: [],
    categories: [],
    appCategories: [],
    preferences: {
      theme: 'system',
      locale: 'fr',
      idleThresholdSec: 60,
      breakIntervalMin: 45,
      dayStartHour: 0,
      dailySummaryEnabled: true,
      dailySummaryHour: 20,
      grantMinutes: 5,
      maxGrantsPerDay: 6
    }
  }
}

function listen(handler: http.RequestListener): Promise<{ url: string; close: () => Promise<void>; hits: () => number }> {
  const server = http.createServer(handler)
  let count = 0
  server.on('request', () => {
    count += 1
  })
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (!address || typeof address === 'string') throw new Error('port')
      resolve({
        url: `http://127.0.0.1:${address.port}`,
        hits: () => count,
        close: () =>
          new Promise((done, reject) => {
            server.closeAllConnections()
            server.close((error) => (error ? reject(error) : done()))
          })
      })
    })
  })
}

async function readBody(req: http.IncomingMessage): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks).toString('utf8')
}

function assertPbkdf(envelope: SyncEnvelope): asserts envelope is Extract<SyncEnvelope, { kdf: 'PBKDF2-SHA-256' }> {
  if (envelope.kdf !== 'PBKDF2-SHA-256') throw new Error('kdf')
}

test('seal hides the snapshot and opens it with the same passphrase', async () => {
  const sealed = await seal('{"appName":"SecretApp"}', PASSPHRASE, 1000)
  expect(JSON.stringify(sealed).includes('SecretApp')).toBe(false)
  expect(JSON.stringify(sealed).includes(PASSPHRASE)).toBe(false)
  expect(sealed.kdf).toBe('PBKDF2-SHA-256')
  expect(sealed.alg).toBe('AES-GCM')
  expect(Object.keys(sealed)).toEqual(['version', 'alg', 'kdf', 'iterations', 'salt', 'iv', 'ciphertext'])
  expect(await openSealed(sealed, PASSPHRASE)).toBe('{"appName":"SecretApp"}')
  await expect(openSealed(sealed, 'other-passphrase')).rejects.toThrow(/decrypt/)
  await expect(seal('x', 'short')).rejects.toThrow(/invalid/)
  await expect(openSealed({ ...sealed, alg: 'AES-256-GCM' }, PASSPHRASE)).rejects.toThrow(/schema/)
})

test('raw key seals with AES-GCM and no salt', async () => {
  const key = Buffer.alloc(32, 0x11)
  const plain = '{"appName":"SecretApp"}'
  const sealed = await seal(plain, { kind: 'raw', key })
  const encoded = key.toString('base64url')
  expect(JSON.stringify(sealed).includes('SecretApp')).toBe(false)
  expect(JSON.stringify(sealed).includes(encoded)).toBe(false)
  expect(sealed.kdf).toBe('raw')
  expect(sealed.iterations).toBe(0)
  expect(Object.keys(sealed)).toEqual(['version', 'alg', 'kdf', 'iterations', 'iv', 'ciphertext'])
  expect(await openSealed(sealed, { kind: 'raw', key })).toBe(plain)
  await expect(openSealed(sealed, { kind: 'raw', key: Buffer.alloc(32, 0x22) })).rejects.toThrow(/decrypt/)
  await expect(openSealed(sealed, PASSPHRASE)).rejects.toThrow(/decrypt/)
  const pbkdf = await seal(plain, PASSPHRASE, 1000)
  await expect(openSealed(pbkdf, { kind: 'raw', key })).rejects.toThrow(/decrypt/)
  const omitted = { ...sealed } as Record<string, unknown>
  delete omitted.iterations
  expect(await openSealed(omitted, { kind: 'raw', key })).toBe(plain)
  expect(await openSealed({ ...sealed, salt: '' }, { kind: 'raw', key })).toBe(plain)
  await expect(openSealed({ ...sealed, salt: 'AQEBAQEBAQEBAQEBAQEBAQ==' }, { kind: 'raw', key })).rejects.toThrow(/schema/)
  await expect(seal('x', { kind: 'raw', key: Buffer.alloc(16) })).rejects.toThrow(/invalid/)
})

test('known envelope matches the shared test vector', async () => {
  expect(await openSealed(KNOWN_ENVELOPE, PASSPHRASE)).toBe('{"version":1,"revision":1}')
  expect(DEFAULT_ITERATIONS).toBe(150_000)
})

test('AES-GCM envelope opens in WebCrypto and the other way around', async () => {
  const iterations = 1000
  const plain = '{"version":1,"revision":3}'
  const sealed = await seal(plain, PASSPHRASE, iterations)
  assertPbkdf(sealed)
  const material = await webcrypto.subtle.importKey('raw', new TextEncoder().encode(PASSPHRASE), 'PBKDF2', false, ['deriveKey'])
  const key = await webcrypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: Uint8Array.from(Buffer.from(sealed.salt, 'base64')), iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
  const decoded = await webcrypto.subtle.decrypt(
    { name: 'AES-GCM', iv: Uint8Array.from(Buffer.from(sealed.iv, 'base64')) },
    key,
    Uint8Array.from(Buffer.from(sealed.ciphertext, 'base64'))
  )
  expect(new TextDecoder().decode(decoded)).toBe(plain)

  const otherSalt = webcrypto.getRandomValues(new Uint8Array(16))
  const otherIv = webcrypto.getRandomValues(new Uint8Array(12))
  const otherKey = await webcrypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: otherSalt, iterations, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  )
  const cipher = await webcrypto.subtle.encrypt(
    { name: 'AES-GCM', iv: otherIv },
    otherKey,
    new TextEncoder().encode(plain)
  )
  expect(
    await openSealed(
      {
        version: 1,
        alg: 'AES-GCM',
        kdf: 'PBKDF2-SHA-256',
        iterations,
        salt: Buffer.from(otherSalt).toString('base64'),
        iv: Buffer.from(otherIv).toString('base64'),
        ciphertext: Buffer.from(cipher).toString('base64')
      },
      PASSPHRASE
    )
  ).toBe(plain)
})

test('raw AES-GCM envelope opens in WebCrypto and the other way around', async () => {
  const plain = '{"version":1,"revision":3}'
  const keyBytes = Buffer.alloc(32, 0x3c)
  const sealed = await seal(plain, { kind: 'raw', key: keyBytes })
  const cryptoKey = await webcrypto.subtle.importKey('raw', Uint8Array.from(keyBytes), { name: 'AES-GCM' }, false, ['decrypt'])
  const decoded = await webcrypto.subtle.decrypt(
    { name: 'AES-GCM', iv: Uint8Array.from(Buffer.from(sealed.iv, 'base64')) },
    cryptoKey,
    Uint8Array.from(Buffer.from(sealed.ciphertext, 'base64'))
  )
  expect(new TextDecoder().decode(decoded)).toBe(plain)

  const otherIv = webcrypto.getRandomValues(new Uint8Array(12))
  const otherKey = await webcrypto.subtle.importKey('raw', Uint8Array.from(keyBytes), { name: 'AES-GCM' }, false, ['encrypt'])
  const cipher = await webcrypto.subtle.encrypt({ name: 'AES-GCM', iv: otherIv }, otherKey, new TextEncoder().encode(plain))
  expect(
    await openSealed(
      {
        version: 1,
        alg: 'AES-GCM',
        kdf: 'raw',
        iv: Buffer.from(otherIv).toString('base64'),
        ciphertext: Buffer.from(cipher).toString('base64')
      },
      { kind: 'raw', key: keyBytes }
    )
  ).toBe(plain)
})

test('sync URL stays on http(s) without embedded secrets', () => {
  expect(parseSyncUrl('https://sync.example/temps')?.hostname).toBe('sync.example')
  expect(parseSyncUrl('http://127.0.0.1:8787')?.hostname).toBe('127.0.0.1')
  expect(parseSyncUrl('file:///tmp/snapshot')).toBeNull()
  expect(parseSyncUrl('https://user:secret@sync.example')).toBeNull()
  expect(parseSyncUrl('https://sync.example/v1/snapshot?token=1')).toBeNull()
  expect(parseSyncUrl('javascript:alert(1)')).toBeNull()
  const base = parseSyncUrl('https://sync.example/temps/')
  expect(base).toBeTruthy()
  expect(blobEndpoint(base as URL, 'settings')).toBe('https://sync.example/temps/v1/blobs/settings')
  expect(accountEndpoint(base as URL)).toBe('https://sync.example/temps/v1/accounts')
  expect(accountEndpoint(parseSyncUrl('https://sync.example/box/v1/accounts/') as URL)).toBe(
    'https://sync.example/box/v1/accounts'
  )
  const exact = parseSyncUrl('https://sync.example/box/v1/snapshot')
  expect(blobEndpoint(exact as URL, 'device.desktop.device-local-0001')).toBe(
    'https://sync.example/box/v1/blobs/device.desktop.device-local-0001'
  )
  expect(deviceBlobId('device-local-0001')).toBe('device.desktop.device-local-0001')
  expect(deviceBlobId('bad')).toBeNull()
  expect(isSyncToken('short')).toBe(false)
  expect(isSyncToken('good-token')).toBe(true)
  expect(isSyncToken('bad token')).toBe(false)
  expect(isSyncToken('line\nbreak1')).toBe(false)
  expect(validPassphrase('short')).toBe(false)
  expect(validPassphrase(PASSPHRASE)).toBe(true)
  expect(validPassphrase('phrase avec espaces')).toBe(true)
})

test('sanitize keeps a valid snapshot and drops a bad row', () => {
  const raw = sample()
  raw.sessions.push({ ...raw.sessions[0], appName: '', day: 'nope' })
  raw.limits.push({ appName: 'Terminal', dailyMinutes: 30, strict: true })
  raw.limits.push({ appName: 'Terminal', dailyMinutes: 0, strict: true })
  const cleaned = sanitizeSnapshot(raw)
  expect(cleaned?.sessions.length).toBe(1)
  expect(cleaned?.sessions[0].appName).toBe('SecretApp')
  expect(cleaned?.limits).toEqual([{ appName: 'Terminal', dailyMinutes: 30, strict: true }])
  expect(sanitizeSnapshot({ ...raw, schema: 2 })).toBeNull()
})

test('settings and usage plaintext follow the multi-blob kinds', () => {
  const remoteBody = { schedules: [{ name: 'keep' }], hourly: { '2026-06-02': [1] }, limits: [] }
  const settings = settingsDocument(sample(), remoteBody, 5, '2026-06-02T12:00:00.000Z')
  expect(settings.kind).toBe('settings')
  expect(settings.revision).toBe(5)
  const body = settings.body as { schedules: { name: string }[]; hourly: unknown; preferences: { locale: string } }
  expect(body.schedules[0]?.name).toBe('keep')
  expect(body.hourly).toEqual({ '2026-06-02': [1] })
  expect(body.preferences.locale).toBe('fr')
  expect(JSON.stringify(settings).includes(PASSPHRASE)).toBe(false)
  expect(readSettingsDocument(settings)?.revision).toBe(5)
  expect(readSettingsDocument({ version: 1, kind: 'usage' })).toBeNull()

  const usage = usageDocument(sample(), 3)
  expect(usage.kind).toBe('usage')
  expect(usage.platform).toBe('desktop')
  const read = readUsageDocument(usage, 'device-local-0001')
  expect(read?.snapshot.sessions[0]?.appName).toBe('SecretApp')
  expect(read?.snapshot.schema).toBe(1)
  expect(readUsageDocument(usage, 'other-device-1')).toBeNull()
})

test('disabled sync does not open a socket', async () => {
  const server = await listen((_req, res) => {
    res.statusCode = 500
    res.end('no')
  })
  try {
    const config: SyncRuntimeConfig = {
      enabled: false,
      baseUrl: server.url,
      token: 'good-token',
      secretKind: 'passphrase',
      secret: PASSPHRASE
    }
    const provider = createSyncProvider(() => config)
    const result = await provider.push(sample())
    expect(result).toEqual({ ok: false, reason: 'disabled' })
    expect(server.hits()).toBe(0)
  } finally {
    await server.close()
  }
})

function etagFor(body: string): string {
  return `sha256-${createHash('sha256').update(body).digest('hex')}`
}

function blobServer(initial: Record<string, string> = {}): http.RequestListener {
  const blobs = new Map(Object.entries(initial))
  return async (req, res) => {
    const id = req.url ?? ''
    if (!id.startsWith('/v1/blobs/')) {
      res.statusCode = 404
      res.end('missing')
      return
    }
    if (req.headers.authorization !== 'Bearer good-token') {
      res.statusCode = 401
      res.end('no')
      return
    }
    const current = blobs.get(id)
    if (req.method === 'GET') {
      if (current == null) {
        res.statusCode = 404
        res.end()
        return
      }
      res.setHeader('Content-Type', 'application/json')
      res.setHeader('ETag', `"${etagFor(current)}"`)
      res.end(current)
      return
    }
    if (req.method !== 'PUT') {
      res.statusCode = 405
      res.end()
      return
    }
    const body = await readBody(req)
    if (!current) {
      if (req.headers['if-none-match'] !== '*') {
        res.statusCode = 428
        res.end()
        return
      }
    } else if (req.headers['if-match'] !== `"${etagFor(current)}"`) {
      res.statusCode = 412
      res.end()
      return
    }
    blobs.set(id, body)
    res.setHeader('ETag', `"${etagFor(body)}"`)
    res.statusCode = 204
    res.end()
  }
}

function bridgeWithDevice(deviceId: string): SyncBridge {
  const snapshot = sample()
  snapshot.deviceId = deviceId
  return {
    applySettings: () => {},
    applyUsage: () => {},
    snapshot: () => snapshot
  }
}

test('push sends ciphertext to the configured host and pull decrypts it', async () => {
  let leaked = false
  const seen: string[] = []
  const handle = blobServer()
  const server = await listen(async (req, res) => {
    if (JSON.stringify(req.headers).includes(PASSPHRASE)) leaked = true
    seen.push(`${req.method} ${req.url} ${req.headers['if-none-match'] ?? ''} ${req.headers['if-match'] ?? ''}`)
    await handle(req, res)
  })
  try {
    const config: SyncRuntimeConfig = {
      enabled: true,
      baseUrl: server.url,
      token: 'good-token',
      secretKind: 'passphrase',
      secret: PASSPHRASE
    }
    const provider = createSyncProvider(() => config)
    const pushed = await provider.push(sample())
    expect(pushed.ok).toBe(true)
    expect(leaked).toBe(false)
    expect(seen.some((line) => line.startsWith('PUT /v1/blobs/settings') && line.includes('*'))).toBe(true)
    expect(seen.some((line) => line.startsWith('PUT /v1/blobs/device.desktop.device-local-0001') && line.includes('*'))).toBe(true)
    const pulled = await provider.pull()
    expect(pulled.ok).toBe(true)
    if (!pulled.ok) return
    expect(pulled.snapshot?.sessions[0].appName).toBe('SecretApp')
    expect(pulled.snapshot?.schema).toBe(1)
    const bridge = bridgeWithDevice('device-local-0001')
    const wrongToken = createSyncProvider(() => ({ ...config, token: 'other-token' }), bridge)
    expect(await wrongToken.pull()).toEqual({ ok: false, reason: 'http-401' })
    const wrongPhrase = createSyncProvider(() => ({ ...config, secret: 'other-passphrase' }), bridge)
    expect(await wrongPhrase.pull()).toEqual({ ok: false, reason: 'decrypt' })
  } finally {
    await server.close()
  }
})

test('push writes usage and settings blobs and leaves a foreign blob untouched', async () => {
  const foreignId = '/v1/blobs/device.desktop.other-device-1'
  const foreignBody = '{"untouched":true}'
  const handle = blobServer({ [foreignId]: foreignBody })
  const server = await listen(handle)
  try {
    const provider = createSyncProvider(() => ({
      enabled: true,
      baseUrl: server.url,
      token: 'good-token',
      secretKind: 'passphrase',
      secret: PASSPHRASE
    }))
    const pushed = await provider.push(sample())
    expect(pushed.ok).toBe(true)

    const settings = await syncRequest({ method: 'GET', url: `${server.url}/v1/blobs/settings`, token: 'good-token' })
    const usage = await syncRequest({
      method: 'GET',
      url: `${server.url}/v1/blobs/device.desktop.device-local-0001`,
      token: 'good-token'
    })
    const foreign = await syncRequest({ method: 'GET', url: `${server.url}${foreignId}`, token: 'good-token' })
    expect(settings.status).toBe(200)
    expect(usage.status).toBe(200)
    const settingsPlain = JSON.parse(await openSealed(JSON.parse(settings.body) as unknown, PASSPHRASE)) as { kind: string }
    const usagePlain = JSON.parse(await openSealed(JSON.parse(usage.body) as unknown, PASSPHRASE)) as { kind: string }
    expect(settingsPlain.kind).toBe('settings')
    expect(usagePlain.kind).toBe('usage')
    expect(foreign.status).toBe(200)
    expect(foreign.body).toBe(foreignBody)

    const pulled = await provider.pull()
    expect(pulled.ok).toBe(true)
    if (pulled.ok) expect(pulled.snapshot?.sessions[0].appName).toBe('SecretApp')
  } finally {
    await server.close()
  }

  const locked = JSON.stringify({ schema: 1, alg: 'AES-256-GCM', iv: 'aa', ciphertext: 'bb' })
  const methods: string[] = []
  const guard = await listen(async (req, res) => {
    methods.push(req.method ?? '')
    const device = (req.url ?? '').includes('/v1/blobs/device.desktop.device-local-0001')
    if (!device) {
      res.statusCode = 404
      res.end()
      return
    }
    if (req.headers.authorization !== 'Bearer good-token') {
      res.statusCode = 401
      res.end('no')
      return
    }
    res.setHeader('Content-Type', 'application/json')
    res.setHeader('ETag', '"locked"')
    res.end(locked)
  })
  try {
    const provider = createSyncProvider(() => ({
      enabled: true,
      baseUrl: guard.url,
      token: 'good-token',
      secretKind: 'passphrase',
      secret: PASSPHRASE
    }))
    expect(await provider.push(sample())).toEqual({ ok: false, reason: 'schema' })
    expect(methods.every((method) => method === 'GET')).toBe(true)
    expect(methods).toContain('GET')
    expect(locked.includes('AES-256-GCM')).toBe(true)
  } finally {
    await guard.close()
  }
})

test('raw key push stores a raw envelope and pull opens it', async () => {
  const key = Buffer.alloc(32, 7)
  const secret = key.toString('base64url')
  const server = await listen(blobServer())
  try {
    const provider = createSyncProvider(() => ({
      enabled: true,
      baseUrl: server.url,
      token: 'good-token',
      secretKind: 'raw',
      secret
    }))
    expect((await provider.push(sample())).ok).toBe(true)
    const settings = await syncRequest({ method: 'GET', url: `${server.url}/v1/blobs/settings`, token: 'good-token' })
    expect(settings.status).toBe(200)
    expect(settings.body.includes(secret)).toBe(false)
    const envelope = JSON.parse(settings.body) as { kdf?: string; salt?: string; iterations?: number }
    expect(envelope.kdf).toBe('raw')
    expect(envelope.salt).toBeUndefined()
    expect(envelope.iterations).toBe(0)
    const plain = await openSealed(JSON.parse(settings.body) as unknown, { kind: 'raw', key })
    expect(plain.includes('settings')).toBe(true)
    const pulled = await provider.pull()
    expect(pulled.ok).toBe(true)
    if (pulled.ok) expect(pulled.snapshot?.sessions[0].appName).toBe('SecretApp')
  } finally {
    await server.close()
  }
})

test('redirects are not followed', async () => {
  const bait = await listen((_req, res) => {
    res.statusCode = 200
    res.end('stolen')
  })
  const server = await listen((_req, res) => {
    res.statusCode = 302
    res.setHeader('Location', `${bait.url}/steal`)
    res.end()
  })
  try {
    const response = await syncRequest({
      method: 'GET',
      url: `${server.url}/v1/snapshot`,
      token: 'good-token'
    })
    expect(response.status).toBe(302)
    expect(response.body).toBe('')
    expect(bait.hits()).toBe(0)
  } finally {
    await server.close()
    await bait.close()
  }
})
