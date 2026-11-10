import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const locales = ['fr', 'en', 'es', 'de', 'pt'];
const required = ['appName', 'appDescription', 'appNamePro', 'appDescriptionPro', 'durationMin', 'durationHm'];

function placeholders(message) {
  return [...message.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
}

const tables = new Map();
for (const locale of locales) {
  const path = join(root, '_locales', locale, 'messages.json');
  const data = JSON.parse(await readFile(path, 'utf8'));
  tables.set(locale, data);
}

const base = tables.get('en');
const baseKeys = Object.keys(base).sort();
const problems = [];

for (const key of required) {
  if (!base[key]?.message) problems.push(`en is missing ${key}`);
}

for (const locale of locales) {
  const table = tables.get(locale);
  const keys = Object.keys(table).sort();
  if (keys.join('\n') !== baseKeys.join('\n')) {
    const missing = baseKeys.filter((key) => !table[key]);
    const extra = keys.filter((key) => !base[key]);
    problems.push(`${locale} keys differ (missing ${missing.join(', ') || 'none'}; extra ${extra.join(', ') || 'none'})`);
  }
  for (const [key, entry] of Object.entries(table)) {
    if (!/^[A-Za-z0-9_@]+$/.test(key) || key.startsWith('@@')) problems.push(`${locale} invalid key ${key}`);
    if (!entry || typeof entry.message !== 'string' || entry.message.trim() === '') {
      problems.push(`${locale} ${key} has an empty message`);
      continue;
    }
    if (entry.message.includes('$')) problems.push(`${locale} ${key} contains $`);
    const here = placeholders(entry.message).join(',');
    const there = placeholders(base[key]?.message ?? '').join(',');
    if (here !== there) problems.push(`${locale} ${key} placeholders ${here} != en ${there}`);
  }
}

const manifest = JSON.parse(await readFile(join(root, 'manifest.json'), 'utf8'));
const refs = JSON.stringify(manifest).match(/__MSG_([A-Za-z0-9_]+)__/g) ?? [];
for (const ref of refs) {
  const key = ref.slice(6, -2);
  if (!base[key]) problems.push(`manifest references missing message ${key}`);
}
if (manifest.default_locale !== 'en') problems.push('default_locale must be en');

if (problems.length > 0) {
  for (const problem of problems) console.error(problem);
  process.exit(1);
}
console.log(`locales ok (${locales.length} languages, ${baseKeys.length} messages)`);
