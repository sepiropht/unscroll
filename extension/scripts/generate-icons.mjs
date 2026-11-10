import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { inflateSync } from 'node:zlib';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { crc32 } from './crc32.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const svgPath = join(root, 'icons', 'icon.svg');
const outDir = join(root, 'icons');

function adler32(buf) {
  const MOD = 65521;
  let a = 1;
  let b = 0;
  for (let i = 0; i < buf.length; i += 1) {
    a += buf[i];
    if (a >= MOD) a -= MOD;
    b += a;
    if (b >= MOD) b -= MOD;
  }
  return ((b << 16) | a) >>> 0;
}

/** zlib wrapper around uncompressed deflate stored-blocks. No compression library. */
function zlibStore(data) {
  const chunks = [Buffer.from([0x78, 0x01])];
  const max = 65535;
  if (data.length === 0) {
    const header = Buffer.alloc(5);
    header[0] = 0x01;
    header.writeUInt16LE(0, 1);
    header.writeUInt16LE(0xffff, 3);
    chunks.push(header);
  }
  for (let i = 0; i < data.length; i += max) {
    const end = Math.min(data.length, i + max);
    const block = data.subarray(i, end);
    const header = Buffer.alloc(5);
    header[0] = end >= data.length ? 0x01 : 0x00;
    header.writeUInt16LE(block.length, 1);
    header.writeUInt16LE(block.length ^ 0xffff, 3);
    chunks.push(header, block);
  }
  const sum = Buffer.alloc(4);
  sum.writeUInt32BE(adler32(data), 0);
  chunks.push(sum);
  return Buffer.concat(chunks);
}

function chunk(type, data) {
  const typeBuf = Buffer.from(type);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

function encodePNG(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y += 1) {
    const dest = y * (stride + 1);
    raw[dest] = 0;
    for (let x = 0; x < stride; x += 1) raw[dest + 1 + x] = rgba[y * stride + x];
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', zlibStore(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function parseColor(hex) {
  let h = hex.trim();
  if (h.startsWith('#')) h = h.slice(1);
  if (h.length === 3) h = h.split('').map((c) => c + c).join('');
  if (!/^[0-9a-fA-F]{6}$/.test(h)) return null;
  const n = Number.parseInt(h, 16);
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 255 };
}

function attr(source, name) {
  const match = new RegExp(`${name}="([^"]*)"`).exec(source);
  return match ? match[1] : null;
}

function parsePoints(text) {
  const nums = text.trim().split(/[\s,]+/).map(Number);
  const pts = [];
  for (let i = 0; i + 1 < nums.length; i += 2) pts.push([nums[i], nums[i + 1]]);
  return pts;
}

function parseSvg(text) {
  const viewMatch = /viewBox="([^"]+)"/.exec(text);
  const viewBox = viewMatch
    ? viewMatch[1].trim().split(/[\s,]+/).map(Number)
    : [0, 0, 128, 128];
  const shapes = [];
  const re = /<(circle|rect|polygon)\b([^>]*)\/?>/g;
  let match = re.exec(text);
  while (match) {
    const kind = match[1];
    const body = match[2];
    const fill = parseColor(attr(body, 'fill') ?? '');
    if (fill) {
      if (kind === 'circle') {
        shapes.push({
          kind,
          fill,
          cx: Number(attr(body, 'cx')),
          cy: Number(attr(body, 'cy')),
          r: Number(attr(body, 'r')),
        });
      } else if (kind === 'rect') {
        shapes.push({
          kind,
          fill,
          x: Number(attr(body, 'x') ?? '0'),
          y: Number(attr(body, 'y') ?? '0'),
          w: Number(attr(body, 'width')),
          h: Number(attr(body, 'height')),
          rx: Number(attr(body, 'rx') ?? '0'),
        });
      } else if (kind === 'polygon') {
        shapes.push({ kind, fill, points: parsePoints(attr(body, 'points') ?? '') });
      }
    }
    match = re.exec(text);
  }
  if (shapes.length === 0) throw new Error('SVG has no supported shapes');
  return { viewBox, shapes };
}

function inCircle(x, y, shape) {
  const dx = x - shape.cx;
  const dy = y - shape.cy;
  return dx * dx + dy * dy <= shape.r * shape.r;
}

function inRoundRect(x, y, shape) {
  const r = Math.max(0, Math.min(shape.rx, shape.w / 2, shape.h / 2));
  if (r <= 0) {
    return x >= shape.x && y >= shape.y && x <= shape.x + shape.w && y <= shape.y + shape.h;
  }
  const left = shape.x + r;
  const right = shape.x + shape.w - r;
  const top = shape.y + r;
  const bottom = shape.y + shape.h - r;
  const nx = x < left ? left : x > right ? right : x;
  const ny = y < top ? top : y > bottom ? bottom : y;
  const dx = x - nx;
  const dy = y - ny;
  return dx * dx + dy * dy <= r * r + 0.01;
}

function inPoly(x, y, points) {
  let inside = false;
  for (let i = 0, j = points.length - 1; i < points.length; j = i, i += 1) {
    const xi = points[i][0];
    const yi = points[i][1];
    const xj = points[j][0];
    const yj = points[j][1];
    const intersect = (yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi;
    if (intersect) inside = !inside;
  }
  return inside;
}

function hit(shape, x, y) {
  if (shape.kind === 'circle') return inCircle(x, y, shape);
  if (shape.kind === 'rect') return inRoundRect(x, y, shape);
  if (shape.kind === 'polygon') return inPoly(x, y, shape.points);
  return false;
}

function over(dst, src) {
  if (src.a >= 255) return src;
  const sa = src.a / 255;
  const da = dst.a / 255;
  const outA = sa + da * (1 - sa);
  if (outA <= 0) return { r: 0, g: 0, b: 0, a: 0 };
  return {
    r: (src.r * sa + dst.r * da * (1 - sa)) / outA,
    g: (src.g * sa + dst.g * da * (1 - sa)) / outA,
    b: (src.b * sa + dst.b * da * (1 - sa)) / outA,
    a: outA * 255,
  };
}

function raster(scene, size) {
  const [minX, minY, vbW, vbH] = scene.viewBox;
  const samples = size <= 16 ? 3 : 4;
  const img = new Uint8Array(size * size * 4);
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      const n = samples * samples;
      for (let sy = 0; sy < samples; sy += 1) {
        for (let sx = 0; sx < samples; sx += 1) {
          const px = minX + ((x + (sx + 0.5) / samples) / size) * vbW;
          const py = minY + ((y + (sy + 0.5) / samples) / size) * vbH;
          let color = { r: 0, g: 0, b: 0, a: 0 };
          for (const shape of scene.shapes) {
            if (hit(shape, px, py)) color = over(color, shape.fill);
          }
          r += color.r;
          g += color.g;
          b += color.b;
          a += color.a;
        }
      }
      const o = (y * size + x) * 4;
      img[o] = Math.round(r / n);
      img[o + 1] = Math.round(g / n);
      img[o + 2] = Math.round(b / n);
      img[o + 3] = Math.round(a / n);
    }
  }
  return img;
}

function near(r, g, b, tr, tg, tb) {
  return Math.abs(r - tr) < 28 && Math.abs(g - tg) < 36 && Math.abs(b - tb) < 36;
}

function verifyPng(png, size) {
  if (png[0] !== 137 || png.toString('ascii', 1, 4) !== 'PNG') throw new Error('bad signature');
  let offset = 8;
  const idats = [];
  let width = 0;
  let height = 0;
  while (offset + 8 <= png.length) {
    const len = png.readUInt32BE(offset);
    const type = png.toString('ascii', offset + 4, offset + 8);
    const data = png.subarray(offset + 8, offset + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
    }
    if (type === 'IDAT') idats.push(data);
    offset += 12 + len;
    if (type === 'IEND') break;
  }
  if (width !== size || height !== size) throw new Error(`IHDR ${width}x${height} != ${size}`);
  const raw = inflateSync(Buffer.concat(idats));
  const stride = size * 4;
  if (raw.length !== (stride + 1) * size) throw new Error('inflated length mismatch');
  let teal = 0;
  let amber = 0;
  let white = 0;
  for (let y = 0; y < size; y += 1) {
    if (raw[y * (stride + 1)] !== 0) throw new Error('unexpected filter');
    for (let x = 0; x < size; x += 1) {
      const o = y * (stride + 1) + 1 + x * 4;
      const r = raw[o];
      const g = raw[o + 1];
      const b = raw[o + 2];
      const a = raw[o + 3];
      if (a < 200) continue;
      if (near(r, g, b, 15, 118, 110)) teal += 1;
      if (near(r, g, b, 245, 158, 11)) amber += 1;
      if (near(r, g, b, 248, 250, 252)) white += 1;
    }
  }
  if (teal < 8 || amber < 2 || white < 2) {
    throw new Error(`icon ${size} missing palette pixels teal=${teal} amber=${amber} white=${white}`);
  }
}

const scene = parseSvg(await readFile(svgPath, 'utf8'));
await mkdir(outDir, { recursive: true });
for (const size of [16, 32, 48, 128]) {
  const rgba = raster(scene, size);
  const png = encodePNG(size, size, rgba);
  verifyPng(png, size);
  const file = join(outDir, `icon${size}.png`);
  await writeFile(file, png);
  console.log(`wrote icons/icon${size}.png (${png.length} bytes)`);
}
