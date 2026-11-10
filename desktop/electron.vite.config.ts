import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'electron-vite'
import type { Plugin } from 'vite'
import { PROD_CSP } from './src/shared/csp'

const root = fileURLToPath(new URL('.', import.meta.url))
// New on every build. package.json version stays 1.0.0, so it cannot tell two AppImages apart.
const buildId = String(Date.now())
const define = {
  __BUILD_ID__: JSON.stringify(buildId)
}
const shared = { '@shared': resolve(root, 'src/shared') }

function cspMeta(): Plugin {
  return {
    name: 'local-csp',
    apply: 'build',
    transformIndexHtml(html) {
      const meta = `    <meta http-equiv="Content-Security-Policy" content="${PROD_CSP}" />\n`
      return html.replace('<head>', `<head>\n${meta}`)
    }
  }
}

export default defineConfig({
  main: {
    define,
    resolve: {
      alias: {
        ...shared,
        '@edition-main': resolve(root, 'src/main/edition/pro.ts')
      }
    },
    build: {
      sourcemap: false,
      externalizeDeps: true,
      rollupOptions: {
        input: { index: resolve(root, 'src/main/index.ts') }
      }
    }
  },
  preload: {
    define,
    resolve: { alias: shared },
    build: {
      sourcemap: false,
      externalizeDeps: true,
      rollupOptions: {
        input: { index: resolve(root, 'src/preload/index.ts') }
      }
    }
  },
  renderer: {
    define,
    resolve: {
      alias: {
        ...shared,
        '@edition-ui': resolve(root, 'src/renderer/src/edition/pro.tsx')
      }
    },
    plugins: [react(), cspMeta()],
    build: {
      sourcemap: false,
      rollupOptions: {
        input: {
          index: resolve(root, 'src/renderer/index.html'),
          overlay: resolve(root, 'src/renderer/overlay.html')
        }
      }
    }
  }
})
