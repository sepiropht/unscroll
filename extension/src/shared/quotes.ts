import { t, type MessageKey } from './i18n.ts';
import type { Lang } from './state.ts';

const QUOTE_COUNT = 10;

function hash(text: string): number {
  let n = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    n ^= text.charCodeAt(i);
    n = Math.imul(n, 16777619);
  }
  return n >>> 0;
}

export function quoteFor(lang: Lang, host: string, dayKey: string): string {
  const index = hash(`${dayKey}:${host}`) % QUOTE_COUNT;
  const key = `quote${index}` as MessageKey;
  return t(lang, key);
}
