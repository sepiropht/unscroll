import { readdir, readFile, stat } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateRawSync } from 'node:zlib';
import { crc32 } from './crc32.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const out = join(root, 'unscroll-extension.zip');

async function walk(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) files.push(...(await walk(path)));
    else if (entry.isFile()) files.push(path);
  }
  return files;
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
  files = await walk(dist);
} catch {
  console.error('dist/ is missing. Run npm run build first.');
  process.exit(1);
}
files = files.filter((file) => relative(dist, file).split('\\').join('/') !== 'edition.txt');
if (files.length === 0) {
  console.error('dist/ is empty. Run npm run build first.');
  process.exit(1);
}
files.sort();

const locals = [];
const centrals = [];
let offset = 0;
for (const file of files) {
  const data = await readFile(file);
  const info = await stat(file);
  const name = relative(dist, file).split('\\').join('/');
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
const names = files.map((file) => relative(dist, file).split('\\').join('/'));
if (!names.includes('manifest.json') || !names.includes('background.js') || names.includes('edition.txt')) {
  console.error('zip is missing manifest.json or background.js, or it contains edition.txt');
  process.exit(1);
}
console.log(`wrote unscroll-extension.zip (${zipStat.size} bytes, ${files.length} files)`);
