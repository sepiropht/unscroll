import { readFile, readlink } from 'node:fs/promises'

export async function exeOf(pid: number): Promise<{ path: string; comm: string }> {
  const [path, comm] = await Promise.all([
    readlink(`/proc/${pid}/exe`).catch(() => ''),
    readFile(`/proc/${pid}/comm`, 'utf8')
      .then((text) => text.trim())
      .catch(() => '')
  ])
  return { path, comm }
}
