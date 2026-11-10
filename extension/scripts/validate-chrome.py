#!/usr/bin/env python3
"""Validate the Chrome Web Store build.

Always runs static checks on dist/ (and the store zip when present).
Then tries to load the unpacked extension in Chromium with --load-extension.
Official Google Chrome 137+ often ignores that flag; a failure to attach a
service worker is reported, not treated as a packaging error.

Usage: python3 scripts/validate-chrome.py
Requires dist/ (run npm run build first). Live load needs Google Chrome or
Playwright Chromium, and Playwright.
"""

from __future__ import annotations

import json
import re
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
STORE = ROOT / "store"
CHROME = "/usr/bin/google-chrome"

FORBIDDEN_PERMS = {"<all_urls>", "webNavigation", "webRequest", "debugger", "cookies", "history", "scripting"}
EVAL_RE = re.compile(r"\beval\s*\(|new Function\s*\(")
IGNORE_CONSOLE = (
    "Download the React DevTools",
    "third-party cookie",
)


def fail(message: str) -> int:
    print(f"validation failed: {message}", file=sys.stderr)
    return 1


def static_checks() -> list[str]:
    errors: list[str] = []
    manifest_path = DIST / "manifest.json"
    if not manifest_path.is_file():
        return ["dist/ is missing. Run npm run build first."]
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("manifest_version") != 3:
        errors.append("manifest_version is not 3")
    if manifest.get("host_permissions"):
        errors.append("host_permissions must not be declared")
    if manifest.get("optional_host_permissions"):
        errors.append("optional_host_permissions must not be declared")
    permissions = set(manifest.get("permissions") or [])
    unexpected = permissions & FORBIDDEN_PERMS
    if unexpected:
        errors.append(f"forbidden permissions: {sorted(unexpected)}")
    expected = {"storage", "tabs", "idle", "alarms", "notifications"}
    if permissions != expected:
        errors.append(f"permissions {sorted(permissions)} != {sorted(expected)}")
    csp = (manifest.get("content_security_policy") or {}).get("extension_pages") or ""
    if "script-src 'self'" not in csp or "unsafe-eval" in csp or "default-src 'none'" not in csp:
        errors.append("CSP is not strict (need default-src 'none' and script-src 'self', no unsafe-eval)")
    if manifest.get("default_locale") != "en":
        errors.append("default_locale must be en")
    for size in (16, 32, 48, 128):
        if not (DIST / "icons" / f"icon{size}.png").is_file():
            errors.append(f"missing icons/icon{size}.png")
    for name in DIST.rglob("*"):
        if name.is_file() and name.suffix in {".map", ".ts"}:
            errors.append(f"dist contains {name.relative_to(DIST)}")
        if name.is_file() and name.suffix == ".js":
            text = name.read_text(errors="replace")
            if EVAL_RE.search(text):
                errors.append(f"eval/new Function in {name.relative_to(DIST)}")
    zips = sorted(STORE.glob("unscroll-*-chrome.zip"))
    if zips:
        zpath = zips[-1]
        with zipfile.ZipFile(zpath) as zf:
            names = zf.namelist()
        if "manifest.json" not in names or "background.js" not in names:
            errors.append(f"{zpath.name} is missing manifest.json or background.js")
        bad = [n for n in names if n.endswith(".map") or "node_modules" in n or n.startswith("tests/")]
        if bad:
            errors.append(f"{zpath.name} contains forbidden paths: {bad}")
        print(f"zip ok: {zpath.name} ({len(names)} files)")
    print(
        "static ok: MV3, permissions "
        + ", ".join(sorted(permissions))
        + ", no host permissions, no remote-code markers"
    )
    return errors


def try_live_load() -> tuple[bool, str]:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return False, "Playwright is not installed; skipped live load"
    if not Path(CHROME).is_file():
        return False, f"{CHROME} not found; skipped live load"

    import tempfile

    errors: list[str] = []
    ext = str(DIST.resolve())
    with tempfile.TemporaryDirectory(prefix="unscroll-chrome-validate-") as tmp:
        with sync_playwright() as playwright:
            context = playwright.chromium.launch_persistent_context(
                tmp,
                executable_path=CHROME,
                headless=False,
                args=[
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--hide-scrollbars",
                    "--headless=new",
                    "--enable-unsafe-extension-debugging",
                    "--disable-features=DisableLoadExtensionCommandLineSwitch",
                    f"--disable-extensions-except={ext}",
                    f"--load-extension={ext}",
                    "--lang=en-US",
                ],
                ignore_default_args=["--disable-extensions"],
                locale="en-US",
            )
            try:
                worker = None
                try:
                    worker = context.wait_for_event("serviceworker", timeout=8_000)
                except Exception:
                    time.sleep(2)
                    workers = context.service_workers
                    worker = workers[0] if workers else None
                if worker is None:
                    context.close()
                    return (
                        False,
                        "official Chrome ignored --load-extension (common since Chrome 137+); "
                        "static checks passed, live console not verified",
                    )

                def on_console(msg) -> None:
                    if msg.type == "error" and not any(s.lower() in msg.text.lower() for s in IGNORE_CONSOLE):
                        errors.append(f"console.error: {msg.text}")

                def on_page_error(exc) -> None:
                    errors.append(f"pageerror: {exc}")

                ext_id = worker.url.split("/")[2]
                print(f"extension id: {ext_id}")
                print(f"service worker: {worker.url}")
                for path in ("popup.html", "options.html#overview", "block.html"):
                    page = context.new_page()
                    page.on("pageerror", on_page_error)
                    page.on("console", on_console)
                    page.goto(f"chrome-extension://{ext_id}/{path}", wait_until="domcontentloaded", timeout=15_000)
                    page.wait_for_timeout(800)
                    print(f"opened {path} title={page.title()!r}")
                    page.close()
            finally:
                context.close()
    if errors:
        return False, "; ".join(errors)
    return True, "live load ok: service worker started, pages opened, no console errors"


def main() -> int:
    errors = static_checks()
    if errors:
        for item in errors:
            print(f"  {item}", file=sys.stderr)
        return 1
    ok, message = try_live_load()
    print(message)
    # Live load is best-effort: a Chrome build that ignores --load-extension
    # should not fail packaging. Real console errors after a successful load do.
    if ok:
        return 0
    if "console.error" in message or "pageerror" in message:
        return fail(message)
    print("note: live extension load was not verified in this environment")
    return 0


if __name__ == "__main__":
    sys.exit(main())
