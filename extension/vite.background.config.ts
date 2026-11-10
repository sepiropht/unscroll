import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import { editionAlias } from './vite.shared.ts';

const root = dirname(fileURLToPath(import.meta.url));

// Classic IIFE so the same file is a Chrome service worker and a Firefox background script.
export default defineConfig({
  resolve: { alias: editionAlias(root) },
  build: {
    outDir: 'dist',
    emptyOutDir: false,
    sourcemap: false,
    target: 'es2022',
    rollupOptions: {
      input: resolve(root, 'src/background/index.ts'),
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'background.js',
        name: 'UnscrollBackground',
      },
    },
  },
});
