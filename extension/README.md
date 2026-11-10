# Unscroll browser extension

Manifest V3 extension for Chrome, Edge, and Firefox 142+. It counts active time per site, and can block a site when a limit, a schedule, a block list, or a focus session says so. Data stays in `chrome.storage.local` unless you turn sync on.

Features: popup with today's total and top sites, a dashboard with charts and history, daily limits per domain, a block page with an optional allowance and password, block lists and allow-list mode, 25/50-minute or custom focus sessions, schedules, CSV/JSON export and import, and optional encrypted sync.

The interface follows the browser language (English by default; also French, Spanish, German, Portuguese) and can be changed in the dashboard. Strings live in `_locales/`.

## Build

```bash
npm install
npm test
npm run build            # output in dist/
npm run zip              # unscroll-extension.zip (Chrome, Edge, Firefox)
npm run package:chrome   # store/unscroll-<version>-chrome.zip (Chrome Web Store pack)
npm run validate:chrome  # load the build in headless Chromium and check the console
```

The Chrome Web Store listing, graphics, privacy answers, and a French upload checklist live in [`store/`](store/).

Load `dist/`:

- Chrome or Edge: Extensions → Developer mode → Load unpacked → `dist/`
- Firefox: `about:debugging` → This Firefox → Load Temporary Add-on → `dist/manifest.json`

## Permissions

`storage`, `tabs`, `idle`, `alarms` (tracking and limits), and `notifications` (limit warnings and focus). Private windows are counted only if you allow it in the settings and in the browser.
