import { expect, test } from 'vitest';
import { decodePairingLink, encodePairingLink, PAIRING_LINK_MAX } from '../src/edition/pairing.ts';
import { bytesToBase64Url } from '../src/edition/sync-codec.ts';

const RAW_KEY = 'ERERERERERERERERERERERERERERERERERERERERERE';

const PHRASE = {
  baseUrl: 'https://sync.example/box',
  token: 'good-token',
  mode: 'pbkdf2' as const,
  secret: 'secret-passphrase',
};

const RAW = {
  baseUrl: 'https://sync.example/box',
  token: 'good-token',
  mode: 'raw' as const,
  secret: RAW_KEY,
};

const PHRASE_GOLDEN =
  'unscroll://pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=c2VjcmV0LXBhc3NwaHJhc2U&m=pbkdf2';

// Same fixture as desktop/src/main/sync/pairing.test.ts.
const RAW_GOLDEN =
  'unscroll://pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=ERERERERERERERERERERERERERERERERERERERERERE&m=raw';

test('encode writes raw and passphrase pairing URIs', () => {
  expect(bytesToBase64Url(new Uint8Array(32).fill(0x11))).toBe(RAW_KEY);
  expect(encodePairingLink(RAW)).toBe(RAW_GOLDEN);
  expect(encodePairingLink({ ...RAW, baseUrl: 'https://sync.example/box/' })).toBe(RAW_GOLDEN);
  expect(decodePairingLink(RAW_GOLDEN)).toEqual(RAW);
  expect(decodePairingLink(RAW_GOLDEN.replace('&m=raw', ''))).toEqual(RAW);
  expect(decodePairingLink(`  \n${RAW_GOLDEN}\n`)).toEqual(RAW);
  expect(encodePairingLink(PHRASE)).toBe(PHRASE_GOLDEN);
  expect(decodePairingLink(PHRASE_GOLDEN)).toEqual(PHRASE);
  expect(decodePairingLink(PHRASE_GOLDEN.replace('&m=pbkdf2', ''))).toBeNull();
  expect(decodePairingLink(PHRASE_GOLDEN.replace('unscroll://pair?', 'UNSCROLL://Pair?'))).toEqual(PHRASE);
  expect(decodePairingLink(PHRASE_GOLDEN.replace('unscroll://pair?', 'unscroll://pair/?'))).toEqual(PHRASE);
  expect(decodePairingLink(`${PHRASE_GOLDEN}&future=1`)).toEqual(PHRASE);
  expect(PHRASE_GOLDEN.includes('secret-passphrase')).toBe(false);
  expect(RAW_GOLDEN.includes('m=raw')).toBe(true);
  const phraseK = new URL(PHRASE_GOLDEN).searchParams.get('k') ?? '';
  expect(phraseK).toMatch(/^[A-Za-z0-9_-]+$/);
  expect(PHRASE_GOLDEN.includes(`k=${phraseK}&m=pbkdf2`)).toBe(true);
});

test('a 32-byte passphrase stays a passphrase only when m=pbkdf2', () => {
  const secret = 'a'.repeat(32);
  const link = encodePairingLink({ ...PHRASE, secret });
  expect(decodePairingLink(link)).toEqual({ ...PHRASE, secret });
  const asRaw = decodePairingLink(link.replace('&m=pbkdf2', ''));
  expect(asRaw?.mode).toBe('raw');
  expect(asRaw?.secret).not.toBe(secret);
});

test('round-trips french letters, symbols and a long passphrase', () => {
  const samples = ['clé sécrète été', 'pass+word/test==', '  secret  ', '😀😀😀😀', `\uFEFFsecret!`, 'é'.repeat(200)];
  for (const secret of samples) {
    const link = encodePairingLink({ ...PHRASE, secret });
    expect(link.includes(secret)).toBe(false);
    const k = new URL(link).searchParams.get('k') ?? '';
    expect(k).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(link.endsWith(`k=${k}&m=pbkdf2`)).toBe(true);
    expect(decodePairingLink(link)).toEqual({ ...PHRASE, secret });
  }
  const token = "a+b/c=d&e~!*'()";
  const link = encodePairingLink({
    baseUrl: 'http://127.0.0.1:9/sync/',
    token,
    mode: 'pbkdf2',
    secret: 'clé sécrète été',
  });
  expect(decodePairingLink(link)).toEqual({
    baseUrl: 'http://127.0.0.1:9/sync',
    token,
    mode: 'pbkdf2',
    secret: 'clé sécrète été',
  });
});

test('rejects a bad scheme, version, host or missing field', () => {
  expect(decodePairingLink('')).toBeNull();
  expect(decodePairingLink('   ')).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN + 'x'.repeat(PAIRING_LINK_MAX))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('unscroll://', 'https://'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('://pair', '://join'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('://pair', '://pair/extra'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('://pair', '://user:pw@pair'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('://pair?', '://pair:80?'))).toBeNull();
  expect(decodePairingLink(`${RAW_GOLDEN}#frag`)).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('v=1', 'v=2'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('v=1', 'v=01'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('v=1&', ''))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace(`&k=${RAW_KEY}`, ''))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('u=https%3A%2F%2Fsync.example%2Fbox&', ''))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('t=good-token&', ''))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('v=1', 'v=1&v=1'))).toBeNull();
  expect(decodePairingLink(`${RAW_GOLDEN}&m=pbkdf2`)).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('m=raw', 'm=RAW'))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace('m=raw', 'm='))).toBeNull();
  expect(decodePairingLink('unscroll:pair?v=1&u=https%3A%2F%2Fsync.example%2Fbox&t=good-token&k=c2VjcmV0LXBhc3NwaHJhc2U&m=pbkdf2')).toBeNull();
});

test('rejects a bad url, token, padding, standard base64 and invalid utf-8', () => {
  const encodedUser = encodeURIComponent('https://user:secret@sync.example/box');
  expect(decodePairingLink(PHRASE_GOLDEN.replace('u=https%3A%2F%2Fsync.example%2Fbox', `u=${encodedUser}`))).toBeNull();
  const queried = encodeURIComponent('https://sync.example/box?token=1');
  expect(decodePairingLink(PHRASE_GOLDEN.replace('u=https%3A%2F%2Fsync.example%2Fbox', `u=${queried}`))).toBeNull();
  expect(decodePairingLink(PHRASE_GOLDEN.replace('t=good-token', 't=short'))).toBeNull();
  expect(decodePairingLink(PHRASE_GOLDEN.replace('k=c2VjcmV0LXBhc3NwaHJhc2U', 'k=c2VjcmV0LXBhc3NwaHJhc2U='))).toBeNull();

  const link = encodePairingLink({ ...PHRASE, secret: '~~~~aaaa' });
  expect(link.includes('k=fn5-fmFhYWE&m=pbkdf2')).toBe(true);
  expect(decodePairingLink(link.replace('k=fn5-fmFhYWE', 'k=fn5%2BfmFhYWE'))).toBeNull();
  expect(decodePairingLink(link.replace('k=fn5-fmFhYWE', 'k=c3ViamVjdHM%2F'))).toBeNull();

  const badK = bytesToBase64Url(new Uint8Array([0xff, 0xfe]));
  expect(decodePairingLink(link.replace('k=fn5-fmFhYWE', `k=${badK}`))).toBeNull();
  const shortK = bytesToBase64Url(new TextEncoder().encode('short'));
  expect(decodePairingLink(link.replace('k=fn5-fmFhYWE', `k=${shortK}`))).toBeNull();
  expect(decodePairingLink(RAW_GOLDEN.replace(`k=${RAW_KEY}`, 'k=c2VjcmV0LXBhc3NwaHJhc2U'))).toBeNull();
});

test('encode refuses incomplete material without echoing the secret', () => {
  const attempts = [
    { ...RAW, baseUrl: 'notaurl' },
    { ...RAW, baseUrl: 'https://user:secret@sync.example/box' },
    { ...RAW, token: 'short' },
    { ...RAW, secret: 'short' },
    { ...PHRASE, secret: 'short' },
    { ...PHRASE, secret: 'é'.repeat(201) },
  ];
  for (const input of attempts) {
    try {
      encodePairingLink(input);
      expect.fail('encode should throw');
    } catch (error) {
      expect(error).toBeInstanceOf(Error);
      expect((error as Error).message).toBe('invalid');
      expect((error as Error).message.includes('secret')).toBe(false);
      expect((error as Error).message.includes(input.secret)).toBe(false);
    }
  }
});
