import { cp, copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import { editionAlias, extensionCsp } from './vite.shared.ts';

const root = dirname(fileURLToPath(import.meta.url));

function extensionPages(): Plugin {
  return {
    name: 'extension-pages',
    transformIndexHtml(html) {
      return html.replaceAll(' crossorigin', '');
    },
    async closeBundle() {
      const dist = resolve(root, 'dist');
      await mkdir(resolve(dist, 'icons'), { recursive: true });
      for (const size of [16, 32, 48, 128]) {
        await copyFile(resolve(root, 'icons', `icon${size}.png`), resolve(dist, 'icons', `icon${size}.png`));
      }
      const manifest = JSON.parse(await readFile(resolve(root, 'manifest.json'), 'utf8')) as {
        name: string;
        description: string;
        default_locale?: string;
        action?: { default_title?: string };
        background?: { service_worker?: string; scripts?: string[]; preferred_environment?: string[] };
        browser_specific_settings?: {
          gecko?: {
            id?: string;
            strict_min_version?: string;
            data_collection_permissions?: { required: string[]; optional?: string[] };
          };
        };
        content_security_policy?: { extension_pages?: string };
      };
      manifest.default_locale = 'en';
      manifest.name = '__MSG_appName__';
      manifest.description = '__MSG_appDescription__';
      if (manifest.action) manifest.action.default_title = manifest.name;
      manifest.background = {
        service_worker: 'background.js',
        scripts: ['background.js'],
        preferred_environment: ['document'],
      };
      manifest.content_security_policy = { extension_pages: extensionCsp() };
      const gecko = manifest.browser_specific_settings?.gecko ?? {};
      gecko.id = 'unscroll@unscroll.app';
      gecko.strict_min_version = '142.0';
      gecko.data_collection_permissions = { required: ['none'], optional: ['browsingActivity'] };
      manifest.browser_specific_settings = { gecko };
      await cp(resolve(root, '_locales'), resolve(dist, '_locales'), { recursive: true });
      await writeFile(resolve(dist, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
    },
  };
}

export default defineConfig({
  base: './',
  plugins: [extensionPages()],
  resolve: { alias: editionAlias(root) },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    modulePreload: false,
    target: 'es2022',
    rollupOptions: {
      input: {
        popup: resolve(root, 'popup.html'),
        options: resolve(root, 'options.html'),
        block: resolve(root, 'block.html'),
      },
    },
  },
});
