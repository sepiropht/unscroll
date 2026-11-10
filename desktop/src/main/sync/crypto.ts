import { createCipheriv, createDecipheriv, pbkdf2, randomBytes } from 'node:crypto'
import { validPassphrase } from './url.ts'

const ALGO = 'aes-256-gcm'
const IV_BYTES = 12
const TAG_BYTES = 16
const SALT_BYTES = 16
const KEY_BYTES = 32
const MAX_PLAINTEXT = 1_500_000

export const DEFAULT_ITERATIONS = 150_000
export const MAX_ITERATIONS = 400_000

export type RawSealSecret = { kind: 'raw'; key: Buffer }

export type PbkdfEnvelope = {
  version: 1
  alg: 'AES-GCM'
  kdf: 'PBKDF2-SHA-256'
  iterations: number
  salt: string
  iv: string
  ciphertext: string
}

export type RawEnvelope = {
  version: 1
  alg: 'AES-GCM'
  kdf: 'raw'
  iterations: 0
  iv: string
  ciphertext: string
}

export type SyncEnvelope = PbkdfEnvelope | RawEnvelope

const RAW_KEY_RE = /^[A-Za-z0-9_-]{43}$/

export function isRawKey(value: string): boolean {
  if (!RAW_KEY_RE.test(value)) return false
  const bytes = Buffer.from(value, 'base64url')
  return bytes.length === KEY_BYTES && bytes.toString('base64url') === value
}

export function rawKeyFromB64(value: string): Buffer | null {
  if (!isRawKey(value)) return null
  return Buffer.from(value, 'base64url')
}

export function generateRawKey(): string {
  return randomBytes(KEY_BYTES).toString('base64url')
}

function deriveKey(passphrase: string, salt: Buffer, iterations: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    pbkdf2(passphrase, salt, iterations, KEY_BYTES, 'sha256', (error, key) => {
      if (error) reject(error)
      else resolve(key)
    })
  })
}

function decodeB64(value: string, maxChars: number): Buffer | null {
  if (value.length === 0 || value.length > maxChars || value.length % 4 !== 0) return null
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null
  const decoded = Buffer.from(value, 'base64')
  if (decoded.length === 0) return null
  return decoded
}

function readRaw(record: Record<string, unknown>): RawEnvelope | null {
  if (record.iterations != null && record.iterations !== 0) return null
  if (record.salt != null && record.salt !== '') return null
  if (typeof record.iv !== 'string' || typeof record.ciphertext !== 'string') return null
  const iv = decodeB64(record.iv, 64)
  const ciphertext = decodeB64(record.ciphertext, 2_000_000)
  if (!iv || !ciphertext) return null
  if (iv.length !== IV_BYTES || ciphertext.length <= TAG_BYTES) return null
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'raw',
    iterations: 0,
    iv: record.iv,
    ciphertext: record.ciphertext
  }
}

function readPbkdf(record: Record<string, unknown>): PbkdfEnvelope | null {
  const iterations = record.iterations
  if (typeof iterations !== 'number' || !Number.isInteger(iterations) || iterations < 1 || iterations > MAX_ITERATIONS) {
    return null
  }
  if (typeof record.salt !== 'string' || typeof record.iv !== 'string' || typeof record.ciphertext !== 'string') return null
  const salt = decodeB64(record.salt, 128)
  const iv = decodeB64(record.iv, 64)
  const ciphertext = decodeB64(record.ciphertext, 2_000_000)
  if (!salt || !iv || !ciphertext) return null
  if (salt.length !== SALT_BYTES || iv.length !== IV_BYTES || ciphertext.length <= TAG_BYTES) return null
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'PBKDF2-SHA-256',
    iterations,
    salt: record.salt,
    iv: record.iv,
    ciphertext: record.ciphertext
  }
}

export function readEnvelope(value: unknown): SyncEnvelope | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  if (record.version !== 1 || record.alg !== 'AES-GCM') return null
  if (record.kdf === 'raw') return readRaw(record)
  if (record.kdf === 'PBKDF2-SHA-256') return readPbkdf(record)
  return null
}

function sealRaw(plaintext: string, key: Buffer): RawEnvelope {
  if (!Buffer.isBuffer(key) || key.length !== KEY_BYTES) throw new Error('invalid')
  const plain = Buffer.from(plaintext, 'utf8')
  if (plain.length > MAX_PLAINTEXT) throw new Error('too-large')
  const iv = randomBytes(IV_BYTES)
  const cipher = createCipheriv(ALGO, key, iv)
  const encrypted = Buffer.concat([cipher.update(plain), cipher.final()])
  const tag = cipher.getAuthTag()
  // WebCrypto appends the 16-byte tag. The extension and Android read that layout.
  // Raw mode uses the 32-byte key as-is: no salt and no PBKDF2.
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'raw',
    iterations: 0,
    iv: iv.toString('base64'),
    ciphertext: Buffer.concat([encrypted, tag]).toString('base64')
  }
}

export async function seal(
  plaintext: string,
  secret: string | RawSealSecret,
  iterations = DEFAULT_ITERATIONS
): Promise<SyncEnvelope> {
  if (typeof secret !== 'string') return sealRaw(plaintext, secret.key)
  if (!validPassphrase(secret)) throw new Error('invalid')
  if (!Number.isInteger(iterations) || iterations < 1 || iterations > MAX_ITERATIONS) throw new Error('invalid')
  const plain = Buffer.from(plaintext, 'utf8')
  if (plain.length > MAX_PLAINTEXT) throw new Error('too-large')
  const salt = randomBytes(SALT_BYTES)
  const iv = randomBytes(IV_BYTES)
  const key = await deriveKey(secret, salt, iterations)
  const cipher = createCipheriv(ALGO, key, iv)
  const encrypted = Buffer.concat([cipher.update(plain), cipher.final()])
  const tag = cipher.getAuthTag()
  return {
    version: 1,
    alg: 'AES-GCM',
    kdf: 'PBKDF2-SHA-256',
    iterations,
    salt: salt.toString('base64'),
    iv: iv.toString('base64'),
    ciphertext: Buffer.concat([encrypted, tag]).toString('base64')
  }
}

function decrypt(key: Buffer, iv: Buffer, data: Buffer): string {
  const tag = data.subarray(data.length - TAG_BYTES)
  const body = data.subarray(0, data.length - TAG_BYTES)
  const decipher = createDecipheriv(ALGO, key, iv)
  decipher.setAuthTag(tag)
  return Buffer.concat([decipher.update(body), decipher.final()]).toString('utf8')
}

export async function openSealed(value: unknown, secret: string | RawSealSecret): Promise<string> {
  const envelope = readEnvelope(value)
  if (!envelope) throw new Error('schema')
  const iv = Buffer.from(envelope.iv, 'base64')
  const data = Buffer.from(envelope.ciphertext, 'base64')
  try {
    if (envelope.kdf === 'raw') {
      if (typeof secret === 'string' || !Buffer.isBuffer(secret.key) || secret.key.length !== KEY_BYTES) {
        throw new Error('decrypt')
      }
      return decrypt(secret.key, iv, data)
    }
    if (typeof secret !== 'string' || !validPassphrase(secret)) throw new Error('decrypt')
    const key = await deriveKey(secret, Buffer.from(envelope.salt, 'base64'), envelope.iterations)
    return decrypt(key, iv, data)
  } catch (error) {
    if (error instanceof Error && error.message === 'decrypt') throw error
    throw new Error('decrypt')
  }
}
