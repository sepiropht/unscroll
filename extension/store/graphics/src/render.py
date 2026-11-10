#!/usr/bin/env python3
"""Render Chrome Web Store graphics from the HTML sources in this folder."""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

SRC = Path(__file__).resolve().parent
OUT = SRC.parent
CHROME = "/usr/bin/google-chrome"

JOBS = [
    # html, viewport, output name
    ("screenshot-popup.html", 1280, 800, "screenshot-01-popup.png"),
    ("screenshot-home.html", 1280, 800, "screenshot-02-home.png"),
    ("screenshot-sync.html", 1280, 800, "screenshot-03-sync.png"),
    ("promo-small.html", 440, 280, "promo-small-440x280.png"),
    ("marquee.html", 1400, 560, "marquee-1400x560.png"),
]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(
            executable_path=CHROME,
            args=["--no-sandbox", "--disable-dev-shm-usage", "--hide-scrollbars"],
        )
        for html, width, height, name in JOBS:
            page = browser.new_page(
                viewport={"width": width, "height": height},
                device_scale_factor=1,
            )
            page.goto((SRC / html).as_uri(), wait_until="networkidle")
            page.wait_for_timeout(200)
            dest = OUT / name
            page.locator("#stage").screenshot(path=str(dest), type="png")
            im = Image.open(dest)
            if im.size != (width, height):
                raise SystemExit(f"{name} is {im.size}, expected {(width, height)}")
            print(f"wrote {name}: {im.size}  {dest.stat().st_size} bytes")
            page.close()
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
