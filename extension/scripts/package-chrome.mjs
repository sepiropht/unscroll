#!/usr/bin/env node
/**
 * Build a Chrome Web Store zip from dist/.
 * Excludes source maps, TypeScript, hidden files, edition.txt,
 * and anything that is not part of the built extension.
 *
 * Usage: npm run package:chrome   (builds first)
 *    or: node scripts/package-chrome.mjs   (dist/ must already exist)
 */
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { createWriteStream, readdirSync, statSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import { crc32 } from './crc32.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const pkg = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'));
const version = pkg.version;
const outDir = join(root, 'store');
const out = join(outDir, `unscroll-${version}-chrome.zip`);

const FORBIDDEN_PARTS = new Set(['node_modules', 'tests', 'src', '.git']);
const FORBIDDEN_NAMES = new Set(['edition.txt', '.env', '.env.local']);

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, name.name);
    if (name.isDirectory()) out.push(...walk(path));
    else if (name.isFile()) out.push(path);
  }
  return out;
}

function zipName(file) {
  return relative(dist, file).split('\\').join('/');
}

function allowed(file) {
  const name = zipName(file);
  const parts = name.split('/');
  if (parts.some((part) => part.startsWith('.') || FORBIDDEN_PARTS.has(part))) return false;
  if (FORBIDDEN_NAMES.has(parts[parts.length - 1])) return false;
  if (name.endsWith('.map') || name.endsWith('.ts') || name.endsWith('.log')) return false;
  return true;
}

function dosTime(date) {
  const time = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const day = ((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { time, day };
}

function u16(n) {
  const b = Buffer.alloc(2);
  b.writeUInt16LE(n, 0);
  return b;
}

function u32(n) {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0, 0);
  return b;
}

let files;
try {
  files = walk(dist).filter(allowed);
} catch {
  console.error('dist/ is missing. Run npm run build first.');
  process.exit(1);
}
if (files.length === 0) {
  console.error('dist/ is empty. Run npm run build first.');
  process.exit(1);
}
files.sort((a, b) => zipName(a).localeCompare(zipName(b)));

const names = files.map(zipName);
const required = ['manifest.json', 'background.js', 'popup.html', 'options.html', 'block.html', 'icons/icon16.png', 'icons/icon32.png', 'icons/icon48.png', 'icons/icon128.png', '_locales/en/messages.json'];
for (const need of required) {
  if (!names.includes(need)) {
    console.error(`zip is missing ${need}`);
    process.exit(1);
  }
}
if (names.some((name) => name.endsWith('.map') || name.includes('node_modules') || name.includes('tests/'))) {
  console.error('zip contains source maps, tests, or node_modules');
  process.exit(1);
}

const manifest = JSON.parse(await readFile(join(dist, 'manifest.json'), 'utf8'));
if (manifest.manifest_version !== 3) {
  console.error('manifest_version must be 3');
  process.exit(1);
}
if (manifest.host_permissions) {
  console.error('Chrome zip must not declare host_permissions');
  process.exit(1);
}
if (manifest.version !== version) {
  console.error(`manifest version ${manifest.version} does not match package.json ${version}`);
  process.exit(1);
}

await mkdir(outDir, { recursive: true });

const locals = [];
const centrals = [];
let offset = 0;
for (const file of files) {
  const data = await readFile(file);
  const info = statSync(file);
  const name = zipName(file);
  const nameBuf = Buffer.from(name, 'utf8');
  const compressed = deflateRawSync(data);
  const { time, day } = dosTime(info.mtime);
  const crc = crc32(data);
  const local = Buffer.concat([
    u32(0x04034b50),
    u16(20),
    u16(0x0800),
    u16(8),
    u16(time),
    u16(day),
    u32(crc),
    u32(compressed.length),
    u32(data.length),
    u16(nameBuf.length),
    u16(0),
    nameBuf,
    compressed,
  ]);
  const central = Buffer.concat([
    u32(0x02014b50),
    u16(20),
    u16(20),
    u16(0x0800),
    u16(8),
    u16(time),
    u16(day),
    u32(crc),
    u32(compressed.length),
    u32(data.length),
    u16(nameBuf.length),
    u16(0),
    u16(0),
    u16(0),
    u16(0),
    u32(0),
    u32(offset),
    nameBuf,
  ]);
  locals.push(local);
  centrals.push(central);
  offset += local.length;
}

const centralBuf = Buffer.concat(centrals);
const eocd = Buffer.concat([
  u32(0x06054b50),
  u16(0),
  u16(0),
  u16(files.length),
  u16(files.length),
  u32(centralBuf.length),
  u32(offset),
  u16(0),
]);

await new Promise((resolve, reject) => {
  const stream = createWriteStream(out);
  stream.on('error', reject);
  stream.on('finish', resolve);
  for (const part of locals) stream.write(part);
  stream.write(centralBuf);
  stream.end(eocd);
});

const zipStat = await stat(out);
const listing = `${names.join('\n')}\n`;
await writeFile(join(outDir, 'zip-contents.txt'), listing);
console.log(`wrote store/unscroll-${version}-chrome.zip (${zipStat.size} bytes, ${files.length} files)`);
console.log(names.map((name) => `  ${name}`).join('\n'));
