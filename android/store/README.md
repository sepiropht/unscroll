# Play Store pack for Unscroll Android

This folder is ready to paste into Google Play Console for `app.unscroll` version 1.0.0 (versionCode 1).

- `listing-en.md` / `listing-fr.md` — title, short description, full description
- `play-forms.md` — Data safety, content rating, ads, audience, and permission justifications
- `play-checklist.md` — what to do in the console, including the 12-tester / 14-day closed-test rule for personal accounts
- `graphics/` — 512×512 icon (from the app icon), 1024×500 feature graphic, and six 1080×1920 phone screenshots in English with fictional example data

The screenshots follow the app’s typography and colors. They were composed for the listing (no private usage data). A device smoke test of the signed APK was not run here: no emulator AVD was available on the build machine, and the developer’s phone was left untouched.

The upload keystore is not in this folder and must not be committed.
