# Unscroll for desktop

Electron app for Windows, macOS, and Linux. It samples the active window, pauses when you are idle or the screen is locked, and stores everything in a local SQLite file.

Features: today's total, top apps, hourly and weekly charts, categories, daily limits with a full-screen notice, break reminders, a daily summary, a tray icon, autostart, CSV/JSON export, and optional encrypted sync with QR pairing.

## Requirements

- Node.js 22+
- `python3`, `make`, and `g++` (to build `better-sqlite3`)
- ImageMagick (`convert`) for `npm run icons`
- Linux: `xdotool` or `xprop` is used as a fallback for the active window

## Scripts

```bash
npm install
npm run dev
npm test
npm run typecheck
npm run dist:linux      # AppImage in release/
npm run dist:linux:deb  # .deb
npm run dist:win        # NSIS installer and portable .exe
npm run dist:mac        # DMG and zip
```

Artifacts are named `unscroll-${version}-${arch}.${ext}`. There is no auto-updater. If the AppImage refuses to start because the Chromium sandbox helper is missing, run it with `--no-sandbox`.

## Security

`contextIsolation`, `sandbox`, no `nodeIntegration`, a strict CSP, IPC sender and argument checks, and no remote content. External links are limited to `https` and go through `src/main/openExternal.ts`.
