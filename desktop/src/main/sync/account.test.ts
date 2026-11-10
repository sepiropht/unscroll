import http from 'node:http'
import { expect, test } from 'vitest'
import { DEFAULT_SYNC_URL } from '@shared/sync'
import { createSyncAccount, shouldProvisionAccount } from './account.ts'

function listen(handler: http.RequestListener): Promise<{ url: string; close: () => Promise<void> }> {
  const server = http.createServer(handler)
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      if (!address || typeof address === 'string') throw new Error('port')
      resolve({
        url: `http://127.0.0.1:${address.port}`,
        close: () =>
          new Promise((done, reject) => {
            server.closeAllConnections()
            server.close((error) => (error ? reject(error) : done()))
          })
      })
    })
  })
}

test('default sync URL is the hosted server', () => {
  expect(DEFAULT_SYNC_URL).toBe('https://sync.unscroll.elimbi.com')
})

test('an empty enable provisions, a saved token or a typed secret does not', () => {
  const empty = { token: '' }
  expect(shouldProvisionAccount(empty, { enabled: true, baseUrl: DEFAULT_SYNC_URL })).toBe(true)
  expect(shouldProvisionAccount(empty, { enabled: false, baseUrl: DEFAULT_SYNC_URL })).toBe(false)
  expect(shouldProvisionAccount({ token: 'good-token' }, { enabled: true, baseUrl: DEFAULT_SYNC_URL })).toBe(false)
  expect(
    shouldProvisionAccount(empty, { enabled: true, baseUrl: DEFAULT_SYNC_URL, token: 'good-token' })
  ).toBe(false)
  expect(
    shouldProvisionAccount(empty, { enabled: true, baseUrl: DEFAULT_SYNC_URL, passphrase: 'secret-passphrase' })
  ).toBe(false)
  expect(
    shouldProvisionAccount({ token: 'good-token' }, { enabled: true, baseUrl: DEFAULT_SYNC_URL, clearToken: true })
  ).toBe(true)
})

test('account create posts no body and no authorization', async () => {
  let method = ''
  let authorization = ''
  let body = ''
  const server = await listen(async (req, res) => {
    method = req.method ?? ''
    authorization = typeof req.headers.authorization === 'string' ? req.headers.authorization : ''
    const chunks: Buffer[] = []
    for await (const chunk of req) chunks.push(Buffer.from(chunk))
    body = Buffer.concat(chunks).toString('utf8')
    if (req.url !== '/v1/accounts') {
      res.statusCode = 404
      res.end()
      return
    }
    res.statusCode = 201
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify({ token: 'good-token', protocol: 1, accountId: 'acc' }))
  })
  try {
    const created = await createSyncAccount(`${server.url}/`)
    expect(created.ok).toBe(true)
    if (!created.ok) return
    expect(created.token).toBe('good-token')
    expect(method).toBe('POST')
    expect(authorization).toBe('')
    expect(body).toBe('')
    expect(created.token.length).toBeGreaterThanOrEqual(8)
  } finally {
    await server.close()
  }
})

test('account create rejects a redirect and a body without a token', async () => {
  const redirect = await listen((_req, res) => {
    res.statusCode = 302
    res.setHeader('Location', 'http://127.0.0.1/steal')
    res.end('{"token":"good-token"}')
  })
  const empty = await listen((_req, res) => {
    res.statusCode = 201
    res.end('{"protocol":1}')
  })
  try {
    expect(await createSyncAccount(redirect.url)).toEqual({ ok: false, reason: 'http-302' })
    expect(await createSyncAccount(empty.url)).toEqual({ ok: false, reason: 'schema' })
    expect(await createSyncAccount('notaurl')).toEqual({ ok: false, reason: 'invalid' })
  } finally {
    await redirect.close()
    await empty.close()
  }
})
