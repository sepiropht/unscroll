import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const svg = resolve(root, 'resources/icon.svg')
const sizes = [16, 24, 32, 48, 64, 128, 256, 512, 1024]
const icoSizes = [16, 24, 32, 48, 64, 128, 256]
// PNG payloads inside ICNS. Retina types repeat a size under another OSType.
const icnsTypes = [
  ['icp4', 16],
  ['icp5', 32],
  ['icp6', 64],
  ['ic07', 128],
  ['ic08', 256],
  ['ic09', 512],
  ['ic10', 1024],
  ['ic11', 32],
  ['ic12', 64],
  ['ic13', 256],
  ['ic14', 512]
]

function pngSize(buf) {
  if (buf.length < 24 || buf[0] !== 0x89 || buf.toString('ascii', 1, 4) !== 'PNG') {
    throw new Error('raster is not a PNG')
  }
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
}

function raster(size) {
  const buf = execFileSync(
    'convert',
    ['-background', 'none', '-resize', `${size}x${size}`, svg, 'png:-'],
    { maxBuffer: 8 * 1024 * 1024 }
  )
  const got = pngSize(buf)
  if (got.width !== size || got.height !== size) {
    throw new Error(`raster ${size} is ${got.width}x${got.height}`)
  }
  return buf
}

function writeIco(pngs) {
  const header = Buffer.alloc(6)
  header.writeUInt16LE(0, 0)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(pngs.length, 4)
  const entries = Buffer.alloc(16 * pngs.length)
  let offset = 6 + entries.length
  const images = []
  pngs.forEach((png, index) => {
    const base = index * 16
    const dim = png.size >= 256 ? 0 : png.size
    entries.writeUInt8(dim, base)
    entries.writeUInt8(dim, base + 1)
    entries.writeUInt16LE(1, base + 4)
    entries.writeUInt16LE(32, base + 6)
    entries.writeUInt32LE(png.buffer.length, base + 8)
    entries.writeUInt32LE(offset, base + 12)
    offset += png.buffer.length
    images.push(png.buffer)
  })
  return Buffer.concat([header, entries, ...images])
}

function writeIcns(bySize) {
  const chunks = []
  for (const [type, size] of icnsTypes) {
    const png = bySize.get(size)
    if (!png) throw new Error(`icns missing ${size}`)
    const header = Buffer.alloc(8)
    header.write(type, 0, 4, 'ascii')
    header.writeUInt32BE(8 + png.length, 4)
    chunks.push(header, png)
  }
  const body = Buffer.concat(chunks)
  const fileHeader = Buffer.alloc(8)
  fileHeader.write('icns', 0, 4, 'ascii')
  fileHeader.writeUInt32BE(8 + body.length, 4)
  return Buffer.concat([fileHeader, body])
}

const bySize = new Map(sizes.map((size) => [size, raster(size)]))
const iconDir = resolve(root, 'resources/icons')
mkdirSync(iconDir, { recursive: true })

for (const size of sizes) {
  const file = resolve(iconDir, `${size}x${size}.png`)
  writeFileSync(file, bySize.get(size))
  console.log(`resources/icons/${size}x${size}.png`)
}

writeFileSync(resolve(root, 'resources/icon.png'), bySize.get(512))
writeFileSync(resolve(root, 'resources/tray.png'), bySize.get(32))
console.log('resources/icon.png 512x512')
console.log('resources/tray.png 32x32')

const ico = writeIco(icoSizes.map((size) => ({ size, buffer: bySize.get(size) })))
if (ico.readUInt16LE(2) !== 1 || ico.readUInt16LE(4) !== icoSizes.length) {
  throw new Error('icon.ico header is invalid')
}
writeFileSync(resolve(root, 'resources/icon.ico'), ico)
console.log(`resources/icon.ico ${ico.length} bytes, ${icoSizes.length} images`)

const icns = writeIcns(bySize)
if (icns.toString('ascii', 0, 4) !== 'icns' || icns.readUInt32BE(4) !== icns.length) {
  throw new Error('icon.icns header is invalid')
}
writeFileSync(resolve(root, 'resources/icon.icns'), icns)
console.log(`resources/icon.icns ${icns.length} bytes, ${icnsTypes.length} images`)
