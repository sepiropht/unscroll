# Unscroll — Chrome Web Store listing (English)

- **Name** (≤45): Unscroll
- **Short description** (≤132, 121 chars):

Open-source, local-first site time tracker. Limits, blocking, focus, and optional end-to-end encrypted sync. No trackers.

- **Detailed description** (≤16,000, 2586 chars):

Unscroll is a free, open-source, local-first screen-time extension for your browser. It shows where your time goes on the sites you visit, lets you set daily limits, and can start a focus session when you want a quieter stretch of time. There is one edition. Every feature is included. The source is published under the AGPL-3.0 license.

Website: https://unscroll.elimbi.com
Source and self-hosting: https://github.com/sepiropht/unscroll

WHAT STAYS IN THE BROWSER
Time per site, limits, schedules, focus sessions, and settings stay in this browser (chrome.storage.local). There is no account to create, no advertising, and no audience or crash reporting. Nothing is sent anywhere unless you turn on optional sync.

WHAT YOU CAN DO
• See today's time, a per-site list, and charts for the day, week, or month
• Set a daily limit or a schedule for a site
• Block a site, or use an allow-list so only chosen sites stay open
• Start a 25-minute, 50-minute, or custom focus session
• Open a block page when a limit, a schedule, a block list, or a focus session applies, with an optional short allowance and an optional password
• Export and import a CSV or JSON backup (exports never contain sync tokens or keys)

PERMISSIONS
The extension declares only what it needs, and no host permissions:
• storage — keep usage, limits, and settings on this device
• tabs — read the address of the active tab so time can be attributed to a site, and open the block page when a rule you set applies
• idle — pause counting when you step away
• alarms — run the periodic timer that records time, and optional sync
• notifications — warn you before a limit, and when a focus session starts or ends

The extension does not read page content, cookies, passwords, or what you type. Time in private windows is counted only if you allow it in the settings and in the browser. It does not run remote code.

SYNC, OPTIONAL
Sync is off until you turn it on. When it is on, the extension encrypts the backup with a key that stays on your devices (AES-GCM) and sends only ciphertext to the server you choose.

You can run the open-source sync server yourself, for free. Or you can use Unscroll Cloud, the hosted server, at €2.99/month or €24/year, with a 14-day trial and no card required to start. After a trial or a subscription ends, the encrypted server copy is kept for 30 days and then deleted.

Unscroll Cloud payments go through Stripe. Stripe receives an anonymous account id and the details you enter in Checkout. Usage data never goes to Stripe.

Privacy policy: https://unscroll.elimbi.com/privacy.html

- **Default language:** English
- **Also add a French translation** (see `listing-fr.md`)
- **Category:** Productivity
- **Visibility:** Public (or Unlisted while you test the listing)
