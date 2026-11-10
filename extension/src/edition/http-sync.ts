import type { PushPrecondition, RemoteBlob, SyncPlaintext, SyncProvider } from './types.ts';
import { DEFAULT_ITERATIONS, blobUrl, etagFor, open, parseEtag, parsePlain, quoteEtag, seal, type SyncSecret } from './sync-codec.ts';

/**
 * Self-hosted sync. The only network path in the extension.
 * `redirect: 'error'` keeps the bearer token from following a redirect.
 * Credentials and referrers are omitted. The body is an encrypted envelope.
 * The server stores that envelope as opaque bytes and identifies it with a strong ETag.
 */
export class HttpSyncProvider implements SyncProvider {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
    private readonly secret: SyncSecret,
    private readonly iterations = DEFAULT_ITERATIONS,
  ) {}

  async push(id: string, plaintext: SyncPlaintext, precondition: PushPrecondition): Promise<void> {
    const envelope = await seal(plaintext, this.secret, this.iterations);
    const body = JSON.stringify(envelope);
    if (body.length > 2_000_000) throw new Error('sync-http');
    const expected = await etagFor(body);
    const extra: Record<string, string> = {};
    if (precondition.mode === 'create') extra['if-none-match'] = '*';
    else extra['if-match'] = quoteEtag(precondition.etag);
    const response = await this.request(id, 'PUT', body, extra);
    if (response.status === 412) throw new Error('sync-conflict');
    if (response.status === 401 || response.status === 403) throw new Error('sync-auth');
    if (response.status === 402) throw new Error('sync-payment');
    if (!response.ok) throw new Error('sync-http');
    if (parseEtag(response.headers.get('etag')) !== expected) throw new Error('sync-http');
  }

  async pull(id: string): Promise<RemoteBlob | null> {
    const response = await this.request(id, 'GET');
    if (response.status === 404) return null;
    if (response.status === 401 || response.status === 403) throw new Error('sync-auth');
    if (response.status === 402) throw new Error('sync-payment');
    if (!response.ok) throw new Error('sync-http');
    const type = (response.headers.get('content-type') ?? '').toLowerCase();
    if (!type.startsWith('application/json')) throw new Error('sync-http');
    const text = await response.text();
    if (text.length === 0 || text.length > 2_000_000) throw new Error('sync-http');
    const etag = await etagFor(text);
    if (parseEtag(response.headers.get('etag')) !== etag) throw new Error('sync-http');
    let body: unknown;
    try {
      body = JSON.parse(text) as unknown;
    } catch {
      throw new Error('sync-http');
    }
    const plaintext = parsePlain(await open(body, this.secret), id);
    if (!plaintext) throw new Error('sync-http');
    return { plaintext, etag };
  }

  private request(id: string, method: 'GET' | 'PUT', body?: string, extra?: Record<string, string>): Promise<Response> {
    const headers: Record<string, string> = {
      accept: 'application/json',
      authorization: `Bearer ${this.token}`,
      ...extra,
    };
    if (body !== undefined) headers['content-type'] = 'application/json';
    return fetch(blobUrl(this.baseUrl, id), {
      method,
      headers,
      body,
      redirect: 'error',
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      cache: 'no-store',
      signal: AbortSignal.timeout(8_000),
    });
  }
}
