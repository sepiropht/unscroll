# Store graphics sources

HTML used to render the Chrome Web Store screenshots and promo tiles.
The captures in `assets/` are real English product shots with example data.

```bash
python3 render.py
```

Requires Google Chrome, Playwright, and Pillow. Writes PNG files into
`store/graphics/` at the official sizes (1280×800, 440×280, 1400×560).
The 128×128 store icon is `../icon-128.png`, copied from `extension/icons/icon128.png`.
