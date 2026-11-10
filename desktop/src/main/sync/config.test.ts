import { expect, test } from 'vitest'
import { closeDatabase, openDatabase, setSettingRaw } from '../db'
import { generateRawKey } from './crypto.ts'
import { getSyncConfig, publicSyncState, readSyncCache, updateSyncConfig, writeSyncCache } from './config.ts'

test('sync stays off until a url, a token and a secret are saved', () => {
  openDatabase(':memory:')
  try {
    expect(publicSyncState()).toMatchObject({
      available: true,
      enabled: false,
      tokenSet: false,
      passphraseSet: false,
      secretKind: null
    })
    const missing = updateSyncConfig({
      enabled: true,
      baseUrl: 'https://sync.example/box',
      token: 'good-token'
    })
    expect(missing).toEqual({ ok: false, reason: 'invalid' })
    expect(getSyncConfig().enabled).toBe(false)

    const saved = updateSyncConfig({
      enabled: true,
      baseUrl: 'https://sync.example/box/',
      token: 'good-token',
      passphrase: 'secret-passphrase'
    })
    expect(saved.ok).toBe(true)
    if (!saved.ok) return
    expect(saved.state.enabled).toBe(true)
    expect(saved.state.tokenSet).toBe(true)
    expect(saved.state.passphraseSet).toBe(true)
    expect(saved.state.secretKind).toBe('passphrase')
    expect(JSON.stringify(saved.state).includes('secret-passphrase')).toBe(false)
    expect(JSON.stringify(saved.state).includes('good-token')).toBe(false)
    expect(getSyncConfig().secretKind).toBe('passphrase')
    expect(getSyncConfig().secret).toBe('secret-passphrase')

    const queried = updateSyncConfig({
      enabled: true,
      baseUrl: 'https://sync.example/box?token=1',
      token: 'good-token',
      passphrase: 'secret-passphrase'
    })
    expect(queried).toEqual({ ok: false, reason: 'invalid' })

    const refused = updateSyncConfig({
      enabled: true,
      baseUrl: 'https://sync.example/box/',
      clearPassphrase: true
    })
    expect(refused).toEqual({ ok: false, reason: 'invalid' })
    expect(getSyncConfig().secret).toBe('secret-passphrase')
    expect(getSyncConfig().enabled).toBe(true)

    setSettingRaw(
      'syncConfig',
      JSON.stringify({ enabled: true, baseUrl: 'https://sync.example/box', token: 'good-token' })
    )
    expect(getSyncConfig().enabled).toBe(false)
    expect(publicSyncState().passphraseSet).toBe(false)

    const off = updateSyncConfig({
      enabled: false,
      baseUrl: 'https://sync.example/box',
      clearPassphrase: true,
      clearToken: true
    })
    expect(off.ok).toBe(true)
    if (!off.ok) return
    expect(off.state.enabled).toBe(false)
    expect(off.state.passphraseSet).toBe(false)
    expect(off.state.tokenSet).toBe(false)
    expect(off.state.secretKind).toBeNull()
    expect(getSyncConfig().secret).toBe('')
    expect(getSyncConfig().token).toBe('')

    updateSyncConfig({
      enabled: true,
      baseUrl: 'https://sync.example/box',
      token: 'good-token',
      passphrase: 'secret-passphrase'
    })
    writeSyncCache({
      baseUrl: 'https://sync.example/box',
      settingsEtag: 'sha256-' + 'ab'.repeat(32),
      settingsRevision: 4,
      deviceEtag: 'sha256-' + 'cd'.repeat(32),
      deviceRevision: 2
    })
    expect(readSyncCache('https://sync.example/box').settingsRevision).toBe(4)
    updateSyncConfig({
      enabled: true,
      baseUrl: 'https://other.example/box',
      token: 'good-token',
      passphrase: 'secret-passphrase'
    })
    expect(readSyncCache('https://other.example/box')).toMatchObject({ settingsRevision: 0, deviceRevision: 0 })
    expect(readSyncCache('https://sync.example/box').settingsRevision).toBe(0)

    setSettingRaw(
      'syncConfig',
      JSON.stringify({
        enabled: true,
        baseUrl: 'https://sync.example/box',
        token: 'good-token',
        passphrase: 'secret-passphrase'
      })
    )
    expect(getSyncConfig()).toMatchObject({
      enabled: true,
      secretKind: 'passphrase',
      secret: 'secret-passphrase'
    })

    const rawKey = generateRawKey()
    const rawSaved = updateSyncConfig({
      enabled: true,
      baseUrl: 'https://sync.example/box',
      token: 'good-token',
      secretKind: 'raw',
      secret: rawKey
    })
    expect(rawSaved.ok).toBe(true)
    if (!rawSaved.ok) return
    expect(rawSaved.state.secretKind).toBe('raw')
    expect(rawSaved.state.passphraseSet).toBe(true)
    expect(JSON.stringify(rawSaved.state).includes(rawKey)).toBe(false)
    expect(getSyncConfig().secret).toBe(rawKey)
    expect(getSyncConfig().secretKind).toBe('raw')
  } finally {
    closeDatabase()
  }
})
