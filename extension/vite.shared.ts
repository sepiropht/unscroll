import { resolve } from 'node:path';

/** Strict extension-page CSP. Sync may connect only to an http(s) URL the user typed. */
export function extensionCsp(): string {
  return [
    "default-src 'none'",
    "script-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-src 'none'",
    "frame-ancestors 'none'",
    "img-src 'self'",
    "font-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    'connect-src http: https:',
  ].join('; ');
}

export function editionAlias(root: string): Record<string, string> {
  return {
    '@edition': resolve(root, 'src/edition/pro.ts'),
  };
}
