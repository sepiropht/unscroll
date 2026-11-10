import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

const root = fileURLToPath(new URL('.', import.meta.url))

export default defineConfig({
  resolve: {
    alias: {
      '@shared': resolve(root, 'src/shared')
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
    pool: 'forks',
    fileParallelism: false
  }
})
