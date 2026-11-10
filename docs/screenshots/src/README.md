# README showcase images

HTML sources for the framed screenshots in the repository README. The
captures in `assets/` are real English product shots with example data
only.

| Asset | Source | Size |
| --- | --- | --- |
| `assets/android-home.png` | Android home screen | 1080×2400 |
| `assets/desktop-home.png` | Desktop home | 1280×1222 |
| `assets/extension-popup.png` | Browser extension popup | 400×720 |

## Regenerate

Needs Python 3, Pillow, Playwright, and Google Chrome (path set as
`CHROME` in `render.py`, default `/usr/bin/google-chrome`).

```bash
cd docs/screenshots/src
python3 render.py
```

This overwrites `../hero.webp`, `../android.webp`, `../desktop.webp`,
and `../extension.webp`.
