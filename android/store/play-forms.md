# Play Console forms — Unscroll (`app.unscroll`)

Fill these in the console. The wording below is a draft you can paste. It matches the app as of version 1.0.0 (versionCode 1).

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

Paste into the corresponding Play Console declaration. Film a short demo video when the form asks for one (accessibility, and sometimes usage access or notification access).

### Usage access (`PACKAGE_USAGE_STATS`)

**Required declaration:** Yes (Restricted/sensitive app permission).

Unscroll is a screen-time app. Usage access is the system source for time spent in each app. The app reads usage events on the device to show daily, weekly, and monthly totals, and to apply daily limits the person chose. The data stays on the device unless the person turns on optional end-to-end encrypted sync. It is not used for advertising and is not sent to the developer.

### Accessibility service

**Required declaration:** Yes. Provide the prominent disclosure text, a short demo video, and a description of why the service is used.

**Do not** check that the app is an accessibility tool for people with disabilities. `android:isAccessibilityTool` is left unset (false). Unscroll is a screen-time and app-blocking app, not an assistive technology.

**In-app prominent disclosure (already implemented, English default):**

Title: Before enabling accessibility

Body: Unscroll asks you to enable an accessibility service.

It recognizes the foreground app so your limits can be applied, and reads the address bar of common browsers to estimate time per site.

Split screen and a floating window do not bypass a limit. If the app stays visible beside another, the pause screen covers both. A floating window is opened full screen, with a tap, so the limit applies.

It does not read your messages, passwords, or what you type. Nothing is sent off the device.

You can turn it off at any time in Android settings.

Consent checkbox: I have read this and agree to enable the service.

The app opens Android accessibility settings only after that checkbox is ticked, including when a limit is saved and the service is not yet on.

**Play form justification:**

The accessibility service is optional and off until the person enables it. It is used to (1) recognize the foreground app so a daily limit or schedule can be applied as soon as that app is visible, including in split screen; (2) read only the address bar of known browsers so time per site can be estimated and a site limit can be applied; (3) perform a single tap gesture on a floating window so it can be opened full screen and the limit still applies; and (4) send the Home action when the person leaves a blocked app. It does not read messages, passwords, or typed input. It does not scrape the web content of pages. Nothing the service sees is sent off the device unless the person has turned on optional end-to-end encrypted sync, which sends only ciphertext. The person can disable the service in Android settings at any time.

**Demo video should show:** the in-app disclosure and consent checkbox, enabling the service, a limit being applied when a sample app is opened, and (optionally) the address bar being used only to show a site name or to block it.

### Query all packages (`QUERY_ALL_PACKAGES`)

**Required declaration:** Yes.

Unscroll needs to resolve the label and icon of every package that appears in usage events, and to know which installed packages are system apps so they can be excluded by default. A `<queries>` element for `MAIN`/`LAUNCHER` is also declared and covers launchable apps, but it is not enough: usage events include non-launcher packages (system services, input methods, work apps). Without `QUERY_ALL_PACKAGES`, those entries would appear as raw package names and default exclusions would be incomplete. The app does not inventory other apps for advertising or for a store. The list never leaves the device unless the person turns on optional end-to-end encrypted sync.

### Display over other apps (`SYSTEM_ALERT_WINDOW`)

**Required declaration:** often yes, when the permission is used.

The overlay is used only to show the pause (block) screen over an app or site that has reached a limit the person set, including in split screen. It is not used to draw on top of other apps for any other purpose.

### Notification access (NotificationListenerService)

**Required declaration:** Yes, if the service is declared (it is).

The notification listener is optional. When the person enables it, Unscroll stores only a count of notifications per app. It does not read or store the title, the text, or the content of notifications. The count stays on the device unless the person turns on optional end-to-end encrypted sync.

### Foreground service special use (`FOREGROUND_SERVICE_SPECIAL_USE`)

**Required declaration:** Yes (special-use foreground service).

The service runs only during a focus session the person started. It keeps a countdown timer and an ongoing notification for that session. Manifest property: "Countdown timer for a focus session the person started." It is not used to track the person in the background outside that session.

### Camera

No restricted-permission form. Declare in Data safety that the camera is used on the device to scan a pairing QR code and that images are not collected or sent. The feature is optional (`android.hardware.camera` required=false).

### Notifications, boot, battery exemption, internet, wake lock

No dedicated sensitive-permission forms. `WAKE_LOCK` and `ACCESS_NETWORK_STATE` are merged from AndroidX libraries. `RECEIVE_BOOT_COMPLETED` reschedules focus routines after a reboot. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is asked only so tracking and reminders can keep running; the person can refuse.

## Privacy policy URL

https://unscroll.elimbi.com/privacy.html

Also add this URL in App content > Privacy policy, and in the Data safety section.

## App access notes (for reviewers)

Unscroll is a local-first screen-time app. To review blocking and site time, enable Usage access, then (after the in-app disclosure) the accessibility service, and Display over other apps. To review notification counts, enable the notification listener. To review sync, you can point the app at your own server or at the default host; no account email is required. There is no demo login. The Play upload is signed with the upload key; Play App Signing should be enabled so Google holds the app signing key.
