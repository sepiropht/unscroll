import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSyncAccount, shouldProvisionAccount } from '../src/edition/account.ts';
import { HttpSyncProvider } from '../src/edition/http-sync.ts';
import { defaultSyncSettings, sanitizeSyncSettings } from '../src/edition/pro-data.ts';
import {
  DEFAULT_ITERATIONS,
  DEFAULT_SYNC_URL,
  accountUrl,
  assembleSettings,
  assembleUsage,
  base64ToBytes,
  blobUrl,
  bytesToBase64Url,
  canonical,
  etagFor,
  generateRawKey,
  isRawKey,
  normalizeSyncUrl,
  open,
  parseEtag,
  parseSettings,
  planSync,
  quoteEtag,
  seal,
  sha256Hex,
  validPassphrase,
  validToken,
} from '../src/edition/sync-codec.ts';
import type { SyncPayload, UsagePlaintext } from '../src/edition/types.ts';
import { defaultAppState } from '../src/shared/state.ts';

const PASSPHRASE = 'secret-passphrase';
const TOKEN = 'local-token';
const BASE = 'https://sync.example.com/box/';

function browserBody(marker = 'videos.example'): SyncPayload {
  const state = defaultAppState();
  return {
    settings: state.settings,
    limits: [],
    blocklist: [marker],
    whitelist: [],
    schedules: [],
    focus: null,
    stats: {},
    hourly: {},
    categories: {},
    focusLog: [],
  };
}

const DEVICE = 'abcdef1234567890';

function usageDoc(snapshot: Record<string, unknown> = browserBody() as unknown as Record<string, unknown>, revision = 1): UsagePlaintext {
  return {
    version: 1,
    kind: 'usage',
    revision,
    updatedAt: '2026-01-02T12:00:00.000Z',
    platform: 'browser',
    deviceId: DEVICE,
    snapshot,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('synchro, édition et réglage', () => {
  it('reste désactivée tant que la configuration est incomplète', () => {
    expect(defaultSyncSettings().enabled).toBe(false);
    expect(
      sanitizeSyncSettings({
        enabled: true,
        url: 'https://sync.example.com/box',
        token: 'court',
        passphrase: PASSPHRASE,
      }).enabled,
    ).toBe(false);
    const armed = sanitizeSyncSettings({
      enabled: true,
      url: 'https://sync.example.com/box/',
      token: TOKEN,
      passphrase: PASSPHRASE,
    });
    expect(armed.enabled).toBe(true);
    expect(armed.url).toBe('https://sync.example.com/box');
    expect(armed.secretKind).toBe('passphrase');
    expect(armed.secret).toBe(PASSPHRASE);
    expect(armed.passphrase).toBe(PASSPHRASE);
    expect(armed.revision).toBe(0);
    const key = bytesToBase64Url(new Uint8Array(32).fill(0x11));
    const raw = sanitizeSyncSettings({
      enabled: true,
      url: 'https://sync.example.com/box/',
      token: TOKEN,
      secretKind: 'raw',
      secret: key,
      passphrase: PASSPHRASE,
    });
    expect(raw.enabled).toBe(true);
    expect(raw.secretKind).toBe('raw');
    expect(raw.secret).toBe(key);
    expect(raw.passphrase).toBe('');
  });

  it('n’accepte qu’une origine http(s) sans secret dans l’URL', () => {
    expect(normalizeSyncUrl('https://user:pw@sync.example.com/box')).toBeNull();
    expect(normalizeSyncUrl('https://sync.example.com/box?token=1')).toBeNull();
    expect(normalizeSyncUrl('javascript:alert(1)')).toBeNull();
    expect(blobUrl('https://sync.example.com/box', 'settings')).toBe('https://sync.example.com/box/v1/blobs/settings');
    expect(blobUrl('https://sync.example.com/box/v1/blobs', `device.browser.${DEVICE}`)).toBe(
      `https://sync.example.com/box/v1/blobs/device.browser.${DEVICE}`,
    );
    expect(DEFAULT_SYNC_URL).toBe('https://sync.unscroll.elimbi.com');
    expect(accountUrl(`${DEFAULT_SYNC_URL}/`)).toBe(`${DEFAULT_SYNC_URL}/v1/accounts`);
    expect(accountUrl('https://sync.example.com/v1/accounts')).toBe('https://sync.example.com/v1/accounts');
    expect(validToken('local token')).toBe(false);
    expect(validToken(TOKEN)).toBe(true);
    expect(validPassphrase('court')).toBe(false);
    expect(validPassphrase(PASSPHRASE)).toBe(true);
  });
});

describe('plan et assemblage', () => {
  it('laisse passer, tire, pousse ou signale le conflit selon la section navigateur', () => {
    expect(planSync({ direction: 'auto', localDirty: false, localRevision: 2, remoteRevision: 2 })).toBe('idle');
    expect(planSync({ direction: 'auto', localDirty: false, localRevision: 2, remoteRevision: 4 })).toBe('pull');
    expect(planSync({ direction: 'auto', localDirty: true, localRevision: 2, remoteRevision: 4 })).toBe('conflict');
    expect(planSync({ direction: 'auto', localDirty: true, localRevision: 4, remoteRevision: 2 })).toBe('push');
    expect(planSync({ direction: 'auto', localDirty: false, localRevision: 0, remoteRevision: null })).toBe('push');
    expect(planSync({ direction: 'pull', localDirty: true, localRevision: 1, remoteRevision: null })).toBe('idle');
    expect(planSync({ direction: 'push', localDirty: false, localRevision: 1, remoteRevision: 9 })).toBe('push');
    expect(
      planSync({ direction: 'auto', localDirty: true, localRevision: 2, remoteRevision: 2, remoteBodyChanged: true }),
    ).toBe('conflict');
    expect(
      planSync({ direction: 'auto', localDirty: false, localRevision: 2, remoteRevision: 2, remoteBodyChanged: true }),
    ).toBe('pull');
  });

  it('avance settings et usage, et garde les clés inconnues du body partagé', () => {
    const remoteBody = { preferences: { language: 'fr' }, androidNote: 'phone', categories: ['keep-array'] };
    const next = assembleSettings({
      updatedAt: '2026-01-03T00:00:00.000Z',
      body: { ...remoteBody, preferences: { language: 'en' }, limits: [] },
      localRevision: 3,
      remoteRevision: 3,
    });
    expect(next.revision).toBe(4);
    expect(next.kind).toBe('settings');
    expect(next.body.androidNote).toBe('phone');
    expect(next.body.categories).toEqual(['keep-array']);
    remoteBody.androidNote = 'mutated';
    expect(next.body.androidNote).toBe('phone');
    const usage = assembleUsage({
      updatedAt: '2026-01-03T00:00:00.000Z',
      deviceId: DEVICE,
      snapshot: browserBody(),
      localRevision: 3,
      remoteRevision: 3,
    });
    expect(usage.revision).toBe(4);
    expect(usage.platform).toBe('browser');
    expect(usage.snapshot.blocklist).toEqual(['videos.example']);
  });
});

describe('chiffre AES-GCM', () => {
  it('scelle et rouvre un instantané, et garde le snapshot usage', async () => {
    const source = usageDoc();
    source.snapshot.note = 'café';
    const envelope = await seal(source, 'café-secret', 1_000);
    expect(envelope).toMatchObject({ version: 1, alg: 'AES-GCM', kdf: 'PBKDF2-SHA-256', iterations: 1_000 });
    if (envelope.kdf !== 'PBKDF2-SHA-256') throw new Error('kdf');
    expect(base64ToBytes(envelope.salt)).toHaveLength(16);
    expect(base64ToBytes(envelope.iv)).toHaveLength(12);
    expect(base64ToBytes(envelope.ciphertext).length).toBe(new TextEncoder().encode(JSON.stringify(source)).length + 16);
    expect(JSON.stringify(envelope)).not.toContain('café-secret');
    expect(JSON.stringify(envelope)).not.toContain('videos.example');
    const opened = (await open(envelope, 'café-secret')) as UsagePlaintext;
    expect(opened.snapshot.blocklist).toEqual(['videos.example']);
    expect(opened.snapshot.note).toBe('café');
    const again = await seal(source, 'café-secret', 1_000);
    if (again.kdf !== 'PBKDF2-SHA-256') throw new Error('kdf');
    expect(again.salt).not.toBe(envelope.salt);
    expect(again.iv).not.toBe(envelope.iv);
    expect(again.ciphertext).not.toBe(envelope.ciphertext);
  });

  it('reproduit le vecteur fixe et utilise 150000 itérations par défaut', async () => {
    const salt = new Uint8Array(16).fill(1);
    const iv = new Uint8Array(12).fill(2);
    const vector = await seal({ version: 1, revision: 1 }, PASSPHRASE, 1_000, { salt, iv });
    expect(vector.ciphertext).toBe('9HA/yDguHSi5iTip16kYVpDGU6OcyFzo8c8tU7fIf9AIx/DYs5y+ULpd');
    expect(await open(vector, PASSPHRASE)).toEqual({ version: 1, revision: 1 });
    expect(DEFAULT_ITERATIONS).toBe(150_000);
    const envelope = await seal(usageDoc({ marker: true }), PASSPHRASE);
    expect(envelope.iterations).toBe(150_000);
    expect(((await open(envelope, PASSPHRASE)) as UsagePlaintext).snapshot.marker).toBe(true);
  }, 20_000);

  it('refuse une mauvaise phrase, un blob altéré ou une version inconnue', async () => {
    const envelope = await seal(usageDoc(), PASSPHRASE, 1_000);
    await expect(open(envelope, 'autre-phrase-secrete')).rejects.toThrow('bad-passphrase');
    const flipped = `${envelope.ciphertext.slice(0, -2)}${envelope.ciphertext.endsWith('A') ? 'B' : 'A'}=`;
    await expect(open({ ...envelope, ciphertext: flipped }, PASSPHRASE)).rejects.toThrow('bad-passphrase');
    await expect(open({ ...envelope, version: 2 }, PASSPHRASE)).rejects.toThrow('sync-http');
    await expect(open({ ...envelope, iterations: 500_000 }, PASSPHRASE)).rejects.toThrow('sync-http');
    expect(parseSettings({ version: 2, kind: 'settings', revision: 1, updatedAt: '2026-01-02T00:00:00.000Z', body: {} })).toBeNull();
    expect(canonical({ b: 1, a: 'x', c: undefined })).toBe('{"a":"x","b":1}');
    expect(await sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });

  it('scelle avec une clé brute de 32 octets et refuse l’autre mode', async () => {
    const key = bytesToBase64Url(new Uint8Array(32).fill(0x11));
    expect(isRawKey(key)).toBe(true);
    const plain = { version: 1, revision: 1 };
    const envelope = await seal(plain, { kind: 'raw', key }, 150_000, { iv: new Uint8Array(12).fill(2) });
    expect(envelope.kdf).toBe('raw');
    expect(envelope.iterations).toBe(0);
    expect(Object.keys(envelope)).toEqual(['version', 'alg', 'kdf', 'iterations', 'iv', 'ciphertext']);
    expect(JSON.stringify(envelope)).not.toContain(key);
    expect(await open(envelope, { kind: 'raw', key })).toEqual(plain);
    await expect(open(envelope, PASSPHRASE)).rejects.toThrow('bad-passphrase');
    const other = bytesToBase64Url(new Uint8Array(32).fill(0x22));
    await expect(open(envelope, { kind: 'raw', key: other })).rejects.toThrow('bad-passphrase');
    const pbkdf = await seal(plain, PASSPHRASE, 1_000);
    await expect(open(pbkdf, { kind: 'raw', key })).rejects.toThrow('bad-passphrase');
    const omitted = { ...envelope } as Record<string, unknown>;
    delete omitted.iterations;
    expect(await open(omitted, { kind: 'raw', key })).toEqual(plain);
    expect(await open({ ...envelope, salt: '' }, { kind: 'raw', key })).toEqual(plain);
    await expect(open({ ...envelope, salt: 'AQEBAQEBAQEBAQEBAQEBAQ==' }, { kind: 'raw', key })).rejects.toThrow('sync-http');
    const first = generateRawKey();
    const second = generateRawKey();
    expect(isRawKey(first)).toBe(true);
    expect(first).not.toBe(second);
  });
});

describe('serveur auto-hébergé', () => {
  it('envoie et reçoit un blob chiffré, avec jeton et ETag', async () => {
    const store = new Map<string, string>();
    const seen: { url: string; init: RequestInit }[] = [];
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const initValue = init ?? {};
      seen.push({ url, init: initValue });
      const headers = new Headers(initValue.headers);
      if (headers.get('authorization') !== `Bearer ${TOKEN}`) return new Response('no', { status: 401 });
      const currentBody = store.get(url) ?? null;
      if ((initValue.method ?? 'GET') === 'GET') {
        if (currentBody == null) return new Response('', { status: 404 });
        return new Response(currentBody, {
          status: 200,
          headers: { etag: `"${await etagFor(currentBody)}"`, 'content-type': 'application/json' },
        });
      }
      const text = String(initValue.body ?? '');
      const current = currentBody == null ? null : `"${await etagFor(currentBody)}"`;
      if (headers.get('if-none-match') === '*' && currentBody != null) return new Response('', { status: 412 });
      if (headers.get('if-match') && headers.get('if-match') !== current) return new Response('', { status: 412 });
      store.set(url, text);
      return new Response(null, { status: 204, headers: { etag: `"${await etagFor(text)}"` } });
    });

    const writer = new HttpSyncProvider(BASE, TOKEN, PASSPHRASE, 1_000);
    const usageId = `device.browser.${DEVICE}`;
    expect(await writer.pull(usageId)).toBeNull();
    const created = assembleUsage({
      updatedAt: '2026-01-02T12:00:00.000Z',
      deviceId: DEVICE,
      snapshot: browserBody('videos.example'),
      localRevision: 0,
      remoteRevision: null,
    });
    const shared = assembleSettings({
      updatedAt: '2026-01-02T12:00:00.000Z',
      body: { preferences: { language: 'fr' }, androidNote: 'phone' },
      localRevision: 0,
      remoteRevision: null,
    });
    await writer.push('settings', shared, { mode: 'create' });
    await writer.push(usageId, created, { mode: 'create' });
    const puts = seen.filter((call) => call.init.method === 'PUT');
    expect(puts.map((call) => call.url)).toEqual([
      'https://sync.example.com/box/v1/blobs/settings',
      `https://sync.example.com/box/v1/blobs/${usageId}`,
    ]);
    const put = puts[1];
    expect(put?.init.redirect).toBe('error');
    expect(put?.init.credentials).toBe('omit');
    expect(put?.init.referrerPolicy).toBe('no-referrer');
    expect(put?.init.cache).toBe('no-store');
    expect(new Headers(put?.init.headers).get('authorization')).toBe(`Bearer ${TOKEN}`);
    expect(new Headers(put?.init.headers).get('if-none-match')).toBe('*');
    expect(put?.url.includes(TOKEN)).toBe(false);
    expect(String(put?.init.body)).not.toContain(PASSPHRASE);
    expect(String(put?.init.body)).not.toContain('videos.example');
    expect(String(put?.init.body)).toContain('"alg":"AES-GCM"');

    const reader = new HttpSyncProvider(BASE, TOKEN, PASSPHRASE, 1_000);
    const remote = await reader.pull(usageId);
    expect(remote?.plaintext.kind).toBe('usage');
    if (remote?.plaintext.kind === 'usage') expect(remote.plaintext.snapshot.blocklist).toEqual(['videos.example']);
    const sharedRemote = await reader.pull('settings');
    if (sharedRemote?.plaintext.kind === 'settings') expect(sharedRemote.plaintext.body.androidNote).toBe('phone');
    expect(remote?.etag).toBe(await etagFor(store.get(blobUrl(BASE, usageId)) ?? ''));

    const stale = remote?.etag ?? '';
    const newer = assembleUsage({
      updatedAt: '2026-01-04T00:00:00.000Z',
      deviceId: DEVICE,
      snapshot: browserBody('mail.example'),
      localRevision: 1,
      remoteRevision: 1,
    });
    await reader.push(usageId, newer, { mode: 'match', etag: stale });
    await expect(writer.push(usageId, created, { mode: 'match', etag: stale })).rejects.toThrow('sync-conflict');
    const stored = store.get(blobUrl(BASE, usageId));
    const opened = stored ? ((await open(JSON.parse(stored) as unknown, PASSPHRASE)) as UsagePlaintext) : null;
    expect(opened?.snapshot.blocklist).toEqual(['mail.example']);
  });

  it('refuse un jeton faux et un ETag qui ne correspond pas aux octets', async () => {
    vi.stubGlobal('fetch', async (_input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      if (headers.get('authorization') !== `Bearer ${TOKEN}`) return new Response('', { status: 403 });
      const body = '{"version":1}';
      return new Response(body, {
        status: 200,
        headers: { etag: '"sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"', 'content-type': 'application/json' },
      });
    });
    const denied = new HttpSyncProvider(BASE, 'bad-token', PASSPHRASE, 1_000);
    await expect(denied.pull('settings')).rejects.toThrow('sync-auth');
    const client = new HttpSyncProvider(BASE, TOKEN, PASSPHRASE, 1_000);
    await expect(client.pull('settings')).rejects.toThrow('sync-http');
    expect(parseEtag('W/"sha256-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"')).toBeNull();
    expect(() => quoteEtag('pas-un-etag')).toThrow('sync-http');
  });
});

describe('compte anonyme', () => {
  it('crée un compte quand l’activation n’a ni jeton ni secret', () => {
    const empty = { token: '' };
    expect(shouldProvisionAccount(empty, { enabled: true })).toBe(true);
    expect(shouldProvisionAccount(empty, { enabled: false })).toBe(false);
    expect(shouldProvisionAccount({ token: 'good-token' }, { enabled: true })).toBe(false);
    expect(shouldProvisionAccount(empty, { enabled: true, token: 'good-token' })).toBe(false);
    expect(shouldProvisionAccount(empty, { enabled: true, passphrase: PASSPHRASE })).toBe(false);
    expect(shouldProvisionAccount({ token: 'good-token' }, { enabled: true, clearToken: true })).toBe(true);
  });

  it('envoie POST /v1/accounts sans corps et sans Authorization', async () => {
    let method = '';
    let authorization: string | null = 'present';
    let sawBody = false;
    vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
      method = init?.method ?? 'GET';
      authorization = new Headers(init?.headers).get('authorization');
      sawBody = init?.body != null;
      expect(String(input)).toBe(`${DEFAULT_SYNC_URL}/v1/accounts`);
      expect(init?.redirect).toBe('error');
      expect(init?.credentials).toBe('omit');
      return new Response(JSON.stringify({ token: 'good-token', protocol: 1, accountId: 'acc' }), {
        status: 201,
        headers: { 'content-type': 'application/json' },
      });
    });
    const created = await createSyncAccount(`${DEFAULT_SYNC_URL}/`);
    expect(created.ok).toBe(true);
    if (!created.ok) return;
    expect(created.token).toBe('good-token');
    expect(method).toBe('POST');
    expect(authorization).toBeNull();
    expect(sawBody).toBe(false);
  });

  it('refuse une redirection et un corps sans jeton', async () => {
    vi.stubGlobal('fetch', async () => {
      throw new TypeError('redirect');
    });
    expect(await createSyncAccount(DEFAULT_SYNC_URL)).toEqual({ ok: false, reason: 'network' });
    vi.stubGlobal('fetch', async () => new Response('{"protocol":1}', { status: 201, headers: { 'content-type': 'application/json' } }));
    expect(await createSyncAccount(DEFAULT_SYNC_URL)).toEqual({ ok: false, reason: 'schema' });
    expect(await createSyncAccount('notaurl')).toEqual({ ok: false, reason: 'invalid' });
  });
});
