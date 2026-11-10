import type { Lang } from './state.ts';
import deJson from '../../_locales/de/messages.json' with { type: 'json' };
import enJson from '../../_locales/en/messages.json' with { type: 'json' };
import esJson from '../../_locales/es/messages.json' with { type: 'json' };
import frJson from '../../_locales/fr/messages.json' with { type: 'json' };
import ptJson from '../../_locales/pt/messages.json' with { type: 'json' };

type Entry = { message: string };
export type MessageKey = keyof typeof frJson;
type Table = Record<MessageKey, Entry>;

const catalogs: Record<Lang, Table> = {
  fr: frJson,
  en: enJson as Table,
  es: esJson as Table,
  de: deJson as Table,
  pt: ptJson as Table,
};

/** UI copy comes from `_locales`. The manifest name uses the same files via `__MSG_`. */
export function t(lang: Lang, key: MessageKey, vars?: Record<string, string | number>): string {
  const table = catalogs[lang] ?? catalogs.en;
  const template = table[key]?.message ?? catalogs.en[key]?.message ?? key;
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (_, name: string) => String(vars[name] ?? ''));
}
