# Play Console forms — Unscroll (`app.unscroll`)

Fill these in the console. The wording below is a draft you can paste. It matches the app as of version 1.0.1 (versionCode 2).

## Category and audience

- **App category:** Productivity
- **Target age:** 18 years and older. This app is **not** directed at children and is **not** in the Designed for Families program.
- **News app:** No
- **Ads:** No. Declare **No, my app does not contain ads**.
- **Store presence:** Default language English. Add a French translation of the listing (see `listing-fr.md`).

## Content rating questionnaire

Answer for a utility that measures device use and can block apps the person chose.

- Email: williamelimbi@gmail.com
- Official email of the app: williamelimbi@gmail.com
- Category: **Utility, Productivity, Communication, or other**
- Violence: **No**
- Sexual content: **No**
- Language (profanity): **No**
- Controlled substances (alcohol, tobacco, drugs): **No**
- User-generated content that is shared with others: **No** (backups and sync stay with the person's own devices or server)
- Shares the person's location: **No**
- Online dating: **No**
- Promotes gambling: **No**
- Miscellaneous (misc. suggestive, etc.): **No**
- Expected rating: **Everyone** (IARC will confirm)

## Data safety

Unscroll is local-first. The developer does not collect personal data. Optional sync sends only ciphertext to a server the person chooses. Unscroll Cloud billing goes through Stripe.

### Does your app collect or share user data?

**Collect:** Yes, but only if the person turns on optional sync, and then only an anonymous account identifier and end-to-end encrypted backup ciphertext, plus (for Unscroll Cloud) payment data handled by Stripe.

A conservative, accurate way to fill the form:

1. **Data collected by Unscroll (only when Sync is turned on)**
   - **App activity / other app performance data:** encrypted backup of measurements, limits, and settings. **Encrypted in transit** (HTTPS and end-to-end AES-GCM). **Encrypted at rest** on the server (the server stores ciphertext it cannot read). **User can request deletion** (turn sync off; the hosted copy is deleted 30 days after access ends). **Not used for advertising.** **Not sold.** **Not shared** with other developers. Purpose: **App functionality**.
   - **Device or other IDs:** the anonymous account id derived from the sync token (a hash of the token is what the server stores). Purpose: **App functionality** and, if the person subscribes to Unscroll Cloud, **Account management**. Not advertising.
2. **Data collected by Stripe, only if the person starts Unscroll Cloud checkout**
   - Payment info (card, name, email, tax address as entered in Stripe Checkout) is collected by Stripe, not by Unscroll. Unscroll sends Stripe only the anonymous account id. Declare this as **shared with Stripe** for **App functionality / Account management / Fraud prevention**, if the form asks about payment processors.
3. **Not collected**
   - Location, contacts, photos, messages, files (apart from a user-initiated JSON backup the person saves themselves), audio, health, financial info held by Unscroll, web browsing history sent to us (site time stays on the device unless the person turned on sync, and then it is ciphertext), advertising id, crash logs, diagnostics, analytics.

**Security practices**

- Data is encrypted in transit (HTTPS).
- Sync data is end-to-end encrypted. The developer cannot read it.
- Users can request deletion (uninstall, export, or turn off sync; Cloud copies are erased 30 days after access ends).
- Independent security review: **No** (unless you later arrange one).
- Committed to Play Families Policy: **No** (the app is not for children).

**Account**

- The app can be used without an account.
- Sync, if enabled with an empty token, creates an anonymous account. There is no password reset email; the token and encryption key stay on the device.

## App access / sensitive permissions — justification drafts

Paste into the corresponding Play Console declaration. Film a short demo video when the form asks for one (accessibility, and sometimes usage access).

### Usage access (`PACKAGE_USAGE_STATS`)

**Required declaration:** Yes (Restricted/sensitive app permission).

Unscroll is a screen-time app. Usage access is the system source for time spent in each app, and for knowing which app is in the foreground so a daily limit or schedule can show the pause screen. The app reads usage events on the device to show daily, weekly, and monthly totals, and to apply limits the person chose. The data stays on the device unless the person turns on optional end-to-end encrypted sync. It is not used for advertising and is not sent to the developer.

### Accessibility service

**Required declaration:** Yes. Provide the prominent disclosure text, a short demo video, and a description of why the service is used.

**Purpose of the declaration:** website time tracking only. The service is not used to detect the foreground app, to apply app limits, to perform gestures, or to send the Home action. App blocking uses usage access and the display-over-other-apps overlay.

**Do not** check that the app is an accessibility tool for people with disabilities. `android:isAccessibilityTool` is left unset (false). Unscroll is a screen-time app, not an assistive technology.

The service is optional, off by default, and requested only when the person turns on website tracking (or saves a site limit). The accessibility config is limited to known browser packages and to window state/content events. `canPerformGestures` is false. `canRetrieveWindowContent` is true only so the address bar can be read.

**In-app prominent disclosure (already implemented, English default):**

Title: Before enabling website tracking

Body: Unscroll asks you to enable an accessibility service to track time on websites.

It reads only the address bar of known browsers (Chrome, Firefox, Brave, Edge, Samsung Internet, Opera, and their variants). It does not read page content, messages, passwords, or what you type. It does not control the screen or detect which other apps you use.

Nothing is sent off the device. You can turn it off at any time in Android settings.

Consent checkbox: I have read this and agree to enable the service.

The app opens Android accessibility settings only after that checkbox is ticked.

**Play form justification:**

The accessibility service is used only for website time tracking. It is optional and off by default. It is requested only when the person turns on website tracking, after a prominent in-app disclosure and explicit consent. The service reads only the address bar of known browsers so time per site can be estimated and a site limit the person chose can be applied. It does not detect the foreground app (that uses usage access), does not perform gestures, and does not send the Home action. It does not read messages, passwords, typed input, or the content of web pages. Its configuration is restricted to supported browser packages and to the event types needed to read the address bar. Nothing the service sees is sent off the device unless the person has turned on optional end-to-end encrypted sync, which sends only ciphertext. The person can disable the service in Android settings at any time.

**Demo video should show:** turning on website tracking, the in-app disclosure and consent checkbox, enabling the service, then time being recorded for a site in a supported browser (or a site limit being applied). Do not present the service as required for ordinary app blocking.

### Query all packages (`QUERY_ALL_PACKAGES`)

**Not declared.** The app does not request this permission. A `<queries>` element for `MAIN`/`LAUNCHER` is used so launchable apps can be listed with their labels and icons. Packages that appear in usage events but have no launcher may be shown under their package name. The app does not inventory other apps for advertising or for a store.

### Display over other apps (`SYSTEM_ALERT_WINDOW`)

**Required declaration:** often yes, when the permission is used.

The overlay is used only to show the pause (block) screen over an app or site that has reached a limit the person set. It is asked when the person first creates a limit. It is not used to draw on top of other apps for any other purpose.

### Notification access (NotificationListenerService)

**Not declared.** The app does not include a notification listener and does not count notification contents or titles.

### Foreground service special use (`FOREGROUND_SERVICE_SPECIAL_USE`)

**Not declared.** Focus sessions use an ordinary notification and the same usage-access guard that applies limits. There is no special-use foreground service.

### Camera

**Not declared.** Pairing uses a QR code shown on the device and a pasted link. The app does not request the camera permission.

### Notifications, boot, battery exemption, internet, wake lock

No dedicated sensitive-permission forms. `WAKE_LOCK` and `ACCESS_NETWORK_STATE` are merged from WorkManager (widget refresh and usage persistence). `RECEIVE_BOOT_COMPLETED` reschedules focus routines and the limit guard after a reboot. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is asked only so tracking and reminders can keep running; the person can refuse. `FOREGROUND_SERVICE`, merged by WorkManager, is removed: the app does not start a foreground service.

## Privacy policy URL

https://unscroll.elimbi.com/privacy.html

Also add this URL in App content > Privacy policy, and in the Data safety section.

## App access notes (for reviewers)

Unscroll is a local-first screen-time app. To review app blocking, enable Usage access (asked at first launch) and Display over other apps (asked when the first limit is created). To review website time tracking, turn on website tracking, accept the in-app disclosure, and enable the accessibility service. To review sync, you can point the app at your own server or at the default host; no account email is required. There is no demo login. The Play upload is signed with the upload key; Play App Signing should be enabled so Google holds the app signing key.
