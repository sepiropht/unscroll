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

## Release build

`versionCode` is 1 and `versionName` is 1.0.0. The target SDK is 36, which meets the Google Play requirement for new apps as of 31 August 2026.

A release build is minified with R8. Signing is optional, so a contributor can still run `./gradlew :app:assembleRelease` without a keystore; the bundle or APK is then left unsigned.

To sign a Play upload (the upload key, used with Play App Signing):

```bash
export UNSCROLL_KEYSTORE_PROPERTIES=/path/to/unscroll-keystore.properties
./gradlew :app:bundleRelease :app:assembleRelease
```

The properties file is not in the repository. It must define `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`. You can also pass `-PUNSCROLL_KEYSTORE_PROPERTIES=...` instead of the environment variable.
