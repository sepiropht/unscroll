import { execFile } from 'node:child_process'

export function execText(file: string, args: string[], timeoutMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(
      file,
      args,
      { timeout: timeoutMs, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024, windowsHide: true },
      (error, stdout) => {
        if (error) {
          reject(error)
          return
        }
        resolve(stdout)
      }
    )
  })
}
