#!/usr/bin/env python3
"""Render README showcase images from the HTML sources in this folder.

Requires Google Chrome (see CHROME) plus Playwright and Pillow.

Usage:
    python3 render.py

Writes hero.webp, android.webp, desktop.webp, and extension.webp into
docs/screenshots/ (the parent of this directory).
"""

from __future__ import annotations

import sys
import tempfile
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

SRC = Path(__file__).resolve().parent
OUT = SRC.parent
CHROME = "/usr/bin/google-chrome"
MAX_BYTES = 600 * 1024

JOBS = [
    # name, html, viewport width, height, device scale, output stem
    ("hero", "hero.html", 1600, 900, 2, "hero"),
    ("android", "android.html", 700, 800, 2, "android"),
    ("desktop", "desktop.html", 700, 800, 2, "desktop"),
    ("extension", "extension.html", 700, 800, 2, "extension"),
]


def to_webp(png_path: Path, webp_path: Path) -> None:
    im = Image.open(png_path).convert("RGB")
    for quality in (90, 86, 82, 78, 74, 70):
        im.save(webp_path, "WEBP", quality=quality, method=6)
        size = webp_path.stat().st_size
        if size <= MAX_BYTES:
            print(f"  {webp_path.name}: {im.size[0]}x{im.size[1]}  {size} bytes  q={quality}")
            return
    raise SystemExit(f"{webp_path} is still {size} bytes after q=70")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="unscroll-shots-") as tmp:
        tmp_dir = Path(tmp)
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                executable_path=CHROME,
                args=["--no-sandbox", "--disable-dev-shm-usage", "--hide-scrollbars"],
            )
            for name, html, width, height, scale, stem in JOBS:
                page = browser.new_page(
                    viewport={"width": width, "height": height},
                    device_scale_factor=scale,
                )
                page.goto((SRC / html).as_uri(), wait_until="networkidle")
                page.wait_for_timeout(200)
                png = tmp_dir / f"{stem}.png"
                page.locator("#stage").screenshot(path=str(png), type="png")
                print(f"captured {name}")
                to_webp(png, OUT / f"{stem}.webp")
                page.close()
            browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
