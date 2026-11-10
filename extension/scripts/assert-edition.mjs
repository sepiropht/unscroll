import { readdir, readFile } from 'node:fs/promises';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const markers = ['PBKDF2-SHA-256', '/v1/blobs', 'AES-GCM'];

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) files.push(...(await walk(path)));
    else if (entry.isFile() && entry.name.endsWith('.js')) files.push(path);
  }
  return files;
}

const files = await walk(dist);
let blob = '';
for (const file of files) blob += await readFile(file, 'utf8');
const manifest = JSON.parse(await readFile(join(dist, 'manifest.json'), 'utf8'));
const csp = manifest.content_security_policy?.extension_pages ?? '';
const permissions = manifest.permissions ?? [];
const forbidden = ['webNavigation', 'windows', '<all_urls>'];

if (manifest.host_permissions) {
  console.error('manifest still declares host_permissions');
  process.exit(1);
}
for (const permission of permissions) {
  if (forbidden.includes(permission)) {
    console.error(`manifest permission not allowed: ${permission}`);
    process.exit(1);
  }
}
if (!csp.includes("default-src 'none'") || !csp.includes("script-src 'self'") || !csp.includes("object-src 'none'") || csp.includes('unsafe-eval')) {
  console.error('manifest CSP is not strict');
  process.exit(1);
}
if (/\beval\s*\(/.test(blob) || /new Function\s*\(/.test(blob)) {
  console.error('bundle contains eval or new Function');
  process.exit(1);
}
if (manifest.default_locale !== 'en') {
  console.error('default_locale must be en');
  process.exit(1);
}
const scripts = manifest.background?.scripts ?? [];
if (manifest.background?.service_worker !== 'background.js' || !scripts.includes('background.js')) {
  console.error('background must expose a service worker and Firefox scripts');
  process.exit(1);
}
if (!manifest.background?.preferred_environment?.includes('document')) {
  console.error('background.preferred_environment must include document');
  process.exit(1);
}
const collected = manifest.browser_specific_settings?.gecko?.data_collection_permissions?.required ?? [];
if (!collected.includes('none') || collected.length !== 1) {
  console.error('data collection must be required none');
  process.exit(1);
}
const localeDir = join(dist, '_locales');
for (const locale of ['fr', 'en', 'es', 'de', 'pt']) {
  const messages = JSON.parse(await readFile(join(localeDir, locale, 'messages.json'), 'utf8'));
  if (!messages.appName?.message || !messages.appDescription?.message) {
    console.error(`dist locale ${locale} is incomplete`);
    process.exit(1);
  }
}

if (!csp.includes('connect-src http: https:')) {
  console.error('CSP must allow only http and https connections');
  process.exit(1);
}
for (const marker of markers) {
  if (!blob.includes(marker)) {
    console.error(`bundle is missing sync marker ${marker}`);
    process.exit(1);
  }
}
const fr = JSON.parse(await readFile(join(dist, '_locales/fr/messages.json'), 'utf8'));
if (manifest.name !== '__MSG_appName__' || fr.appName?.message !== 'Unscroll') {
  console.error('manifest name must stay the single edition name');
  process.exit(1);
}
if (fr.proSync?.message !== 'Synchronisation') {
  console.error('French options sync section must be titled Synchronisation');
  process.exit(1);
}
const optional = manifest.browser_specific_settings?.gecko?.data_collection_permissions?.optional ?? [];
if (!optional.includes('browsingActivity')) {
  console.error('manifest must declare optional browsingActivity');
  process.exit(1);
}
const geckoId = manifest.browser_specific_settings?.gecko?.id;
if (geckoId !== 'unscroll@unscroll.app') {
  console.error('gecko id must be the single edition id');
  process.exit(1);
}

console.log(`sync build ok (${files.map((file) => relative(dist, file)).length} scripts)`);
