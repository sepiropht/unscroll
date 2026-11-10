# Unscroll for Android

Screen time per app, daily limits, focus sessions, reminders, and a home-screen widget. Data stays on the phone. Sync is optional and end-to-end encrypted.

- `applicationId`: `app.unscroll`
- Min SDK 26, target SDK 36
- English by default; French, Spanish, German, and Portuguese translations in `app/src/main/res/values-*`

## Build

Requires JDK 17 and the Android SDK. Point `local.properties` at your SDK (`sdk.dir=...`) or set `ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Permissions

Each permission is asked only when you use the feature that needs it:

- **Usage access**: counts screen time per app.
- **Accessibility service** (optional): recognizes the foreground app so limits apply right away.
- **Display over other apps**: shows the block screen.
- **Notifications**: reminders, summaries, and focus status.
- **Camera** (optional): scans a pairing QR code. Decoding runs on the device (ZXing).

## Sync

Open **More → Sync**, turn it on, then **Pair a device** to show a QR code, or **Scan a QR code** / **Paste a link** on the other device. See [`../docs/pairing.md`](../docs/pairing.md).
