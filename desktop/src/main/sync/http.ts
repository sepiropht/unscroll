import http from 'node:http'
import https from 'node:https'
import { isSyncToken } from './url.ts'

const DEFAULT_TIMEOUT_MS = 15_000
const DEFAULT_MAX_BYTES = 2_000_000

export type SyncHttpResult = {
  status: number
  body: string
  etag: string | null
  weakEtag: boolean
}

function parseEtag(raw: string | string[] | undefined): { etag: string | null; weak: boolean } {
  const value = Array.isArray(raw) ? raw[0] : raw
  if (!value) return { etag: null, weak: false }
  const trimmed = value.trim()
  if (/^W\//i.test(trimmed)) return { etag: null, weak: true }
  const quoted = trimmed.match(/^"(sha256-[a-f0-9]{64})"$/)
  if (quoted) return { etag: quoted[1], weak: false }
  return { etag: null, weak: false }
}

export function syncRequest(options: {
  method: 'GET' | 'PUT' | 'POST'
  url: string
  token?: string
  body?: string
  ifMatch?: string
  ifNoneMatch?: string
  timeoutMs?: number
  maxBytes?: number
}): Promise<SyncHttpResult> {
  const sendsToken = options.method !== 'POST' || (options.token != null && options.token.length > 0)
  if (sendsToken && !isSyncToken(options.token ?? '')) return Promise.reject(new Error('invalid'))
  let target: URL
  try {
    target = new URL(options.url)
  } catch {
    return Promise.reject(new Error('invalid'))
  }
  if (target.protocol !== 'https:' && target.protocol !== 'http:') return Promise.reject(new Error('invalid'))
  const lib = target.protocol === 'https:' ? https : http
  const payload = options.body == null ? null : Buffer.from(options.body)
  if (payload && payload.length > DEFAULT_MAX_BYTES) return Promise.reject(new Error('too-large'))
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (sendsToken && options.token) headers.Authorization = `Bearer ${options.token}`
  if (payload) {
    headers['Content-Type'] = 'application/json'
    headers['Content-Length'] = String(payload.length)
  }
  if (options.ifMatch) headers['If-Match'] = `"${options.ifMatch}"`
  if (options.ifNoneMatch) headers['If-None-Match'] = options.ifNoneMatch

  return new Promise((resolve, reject) => {
    let settled = false
    const fail = (reason: string): void => {
      if (settled) return
      settled = true
      reject(new Error(reason))
    }
    const ok = (status: number, body: string, etag: string | null, weakEtag: boolean): void => {
      if (settled) return
      settled = true
      resolve({ status, body, etag, weakEtag })
    }
    const req = lib.request(
      {
        protocol: target.protocol,
        hostname: target.hostname,
        port: target.port || undefined,
        path: `${target.pathname}${target.search}`,
        method: options.method,
        headers,
        timeout: timeoutMs
      },
      (res) => {
        const status = res.statusCode ?? 0
        const tag = parseEtag(res.headers.etag)
        if (status >= 300 && status < 400) {
          res.resume()
          ok(status, '', tag.etag, tag.weak)
          return
        }
        const chunks: Buffer[] = []
        let size = 0
        res.on('data', (chunk: Buffer) => {
          size += chunk.length
          if (size > maxBytes) {
            fail('too-large')
            req.destroy()
            return
          }
          chunks.push(chunk)
        })
        res.on('end', () => {
          ok(status, Buffer.concat(chunks).toString('utf8'), tag.etag, tag.weak)
        })
        res.on('error', () => fail('network'))
      }
    )
    req.on('timeout', () => {
      req.destroy()
      fail('timeout')
    })
    req.on('error', () => fail('network'))
    if (payload) req.end(payload)
    else req.end()
  })
}
