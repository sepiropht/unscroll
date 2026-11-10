# Chrome Web Store privacy tab — Unscroll

Paste these answers in the developer console. They match the extension as of version 1.0.0. Privacy policy: https://unscroll.elimbi.com/privacy.html

## Single purpose

Unscroll is a screen-time extension. It measures time spent on websites in this browser, shows that time in a popup and a dashboard, and applies daily limits, schedules, block lists, and focus sessions that the person sets. That is its only purpose.

## Permissions (justification for each)

The extension declares five permissions and **no host permissions**.

- **storage** — Required. Usage, limits, schedules, focus sessions, settings, and (if sync is turned on) the local encryption key and account token are stored in `chrome.storage.local`. The extension cannot work without local storage.
- **tabs** — Required. The extension reads the URL of the active tab to attribute time to a site, and it may replace that tab with the extension's block page when a limit, a schedule, a block list, or a focus session the person set applies. It also opens the dashboard tab. It does not read page content, cookies, or form data.
- **idle** — Required. Counting pauses when the person is idle, using the idle threshold in the settings, so time away from the keyboard is not added to a site.
- **alarms** — Required. A one-minute alarm is the heartbeat that records time and applies limits. An optional alarm (every 15 minutes) runs end-to-end encrypted sync only when the person has turned sync on.
- **notifications** — Required for warnings the person can receive: approaching a daily limit, and the start or end of a focus session. No other notifications are sent.

**Incognito:** the manifest uses `spanning` so private windows can be counted if, and only if, the person enables that in both the extension settings and the browser. The default is off.

## Host permissions

None. The extension does not declare `host_permissions`, `optional_host_permissions`, or `<all_urls>`. It does not inject content scripts into websites.

Optional sync uses `fetch` to an `http:` or `https:` URL the person types (the default is `https://sync.unscroll.elimbi.com`). That is allowed by the extension's Content Security Policy (`connect-src http: https:`). The payload is end-to-end encrypted ciphertext. CORS applies, because there is no host permission.

## Remote code

**No.** The extension does not load or execute remote JavaScript, WASM, or plugins. The Content Security Policy is `script-src 'self'`; `eval` and `new Function` are not used. All interface and background scripts are in the package.

## Data use

- **What is collected, and where:** time per site, visit counts, limits, schedules, focus sessions, and settings are stored locally in this browser. If the person turns on optional sync, the extension also stores a local encryption key and an account token, and it sends only ciphertext plus an anonymous account identifier to the server the person chose.
- **Not collected by the developer:** the developer does not receive usage data, browsing history, or contact details from the extension.
- **Not sold, not used for advertising, not used for creditworthiness or lending.**
- **Not transferred** except: (1) optional end-to-end encrypted sync to a server the person chooses (self-hosted or Unscroll Cloud), and (2) if the person starts Unscroll Cloud checkout, Stripe receives an anonymous account id and whatever they enter in Checkout. Usage data never goes to Stripe.
- **User control:** the person can export or delete local data in the dashboard, turn sync off, or uninstall the extension. A hosted encrypted copy is deleted 30 days after access ends.

### Certification checkboxes (typical wording)

Check the certifications that apply:

- I do not sell or transfer user data to third parties, except as described (optional sync ciphertext to the person's chosen server; Stripe, only for Unscroll Cloud checkout).
- I do not use or transfer user data for purposes unrelated to this item's single purpose.
- I do not use or transfer user data to determine creditworthiness or for lending purposes.

Select that the extension handles user data that stays on the device (browsing activity / site time), and that a privacy policy is provided.

## Privacy policy URL

https://unscroll.elimbi.com/privacy.html

The policy covers the extension (local storage, the five permissions, no host permissions, no remote code, optional end-to-end encrypted sync, and Unscroll Cloud / Stripe).
