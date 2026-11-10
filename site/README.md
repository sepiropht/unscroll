# Unscroll website

Static site for [Unscroll](https://github.com/sepiropht/unscroll), a free, open-source, local-first screen-time app. This is the page served at [unscroll.elimbi.com](https://unscroll.elimbi.com).

## Pages

- `index.html` — product, screenshots, self-hosting, and pricing
- `cloud.html` — Unscroll Cloud (hosted sync)

English is the default language. The EN/FR control switches every `data-i18n` string. The choice is stored in `localStorage` under `unscroll.lang`.

## View the page

Open `index.html` in a browser, or serve this directory:

```bash
python3 -m http.server 8080
```

Then visit `http://127.0.0.1:8080/`. Paths are relative, so the same files also work under a subpath when served from that directory.

## Files

| File | Role |
| --- | --- |
| `index.html` | Home page |
| `cloud.html` | Unscroll Cloud |
| `styles.css` | Layout, dark glass theme, mockups, screenshot frames |
| `main.js` | English/French dictionary, language toggle, scroll reveal |
| `favicon.svg` | Ring mark |
| `images/` | Real product screenshots (WebP) |
| `README.md` | This note |

No build step. No CDN, web fonts, trackers, or analytics. Product illustrations in the Apps section are CSS and SVG; the screenshots section uses captures of the current apps.

The project is a single edition under AGPL-3.0. Self-hosted sync is free. Unscroll Cloud is optional hosted sync.
