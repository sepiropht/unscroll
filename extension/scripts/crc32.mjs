const table = new Uint32Array(256);
for (let n = 0; n < 256; n += 1) {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  table[n] = c >>> 0;
}

/** CRC-32 (ISO 3309 / PNG / ZIP). */
export function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i += 1) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

const check = crc32(Buffer.from('123456789'));
if (check !== 0xcbf43926) {
  throw new Error(`crc32 self-check failed: ${check.toString(16)}`);
}
