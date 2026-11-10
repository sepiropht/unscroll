/* Unscroll landing: EN/FR dictionary, language toggle, scroll reveal. */
"use strict";

var STORAGE_KEY = "unscroll.lang";

var STRINGS = {
  en: {
    "meta.title": "Unscroll — open-source, local-first screen time",
    "meta.description": "Unscroll is a free, open-source, local-first screen-time app for Android, desktop, and the browser. Self-host end-to-end encrypted sync, or use Unscroll Cloud.",
    "skip": "Skip to content",
    "nav.primary": "Primary",
    "nav.apps": "Apps",
    "nav.features": "Features",
    "nav.selfhost": "Self-host",
    "nav.cloud": "Cloud",
    "nav.faq": "FAQ",
    "lang.group": "Language",
    "hero.eyebrow": "Open source · AGPL-3.0",
    "hero.title.lead": "The scroll",
    "hero.title.em": "can wait.",
    "hero.tagline": "A free, open-source screen-time app for Android, desktop, and the browser. Local-first, no trackers, no ads. Self-host encrypted sync, or use Unscroll Cloud.",
    "hero.cta.github": "View on GitHub",
    "hero.cta.selfhost": "Self-host it",
    "hero.note": "One edition. Every feature is free. The only paid option is hosted sync.",
    "hero.dial.sample": "Sample day",
    "hero.dial.time": "3h 23m",
    "hero.dial.label": "on the device today",
    "hero.legend.usage": "Apps and sites · 3h 23m",
    "hero.legend.sleep": "Sleep window · 8h",
    "hero.legend.rest": "Away from the screen · the rest",
    "products.eyebrow": "The three apps",
    "products.title": "The same limits, on every screen.",
    "products.lead": "One public monorepo. Get the apps from GitHub.",
    "badge.oss": "AGPL-3.0",
    "product.android.name": "Android",
    "product.android.platform": "Android 8.0+",
    "product.android.body": "Daily caps and blocks for the apps on your phone, and rings for the time spent today.",
    "product.desktop.name": "Desktop",
    "product.desktop.platform": "Linux AppImage. Windows and macOS from source.",
    "product.desktop.body": "Limits for desktop apps, focus sessions, and a week of charts stored on the machine.",
    "product.browser.name": "Browser extension",
    "product.browser.platform": "Chrome, Edge, and Firefox",
    "product.browser.body": "Site limits in the browser you already use. When the time is up, the page is replaced by a block screen.",
    "product.source": "Source on GitHub",
    "mock.sample": "Sample",
    "mock.phone.today": "Today",
    "mock.phone.apps": "Apps",
    "mock.phone.sites": "Sites",
    "mock.phone.focus": "Focus",
    "mock.phone.appsTime": "2h 10m",
    "mock.phone.sitesTime": "48m",
    "mock.phone.focusTime": "25m",
    "mock.desktop.title": "This week",
    "mock.desktop.aria": "Sample bar chart of hours from Monday to Sunday. Thursday is the longest bar.",
    "mock.desktop.sessions": "Focus sessions",
    "mock.desktop.limits": "Limits reached",
    "day.mon": "Mon",
    "day.tue": "Tue",
    "day.wed": "Wed",
    "day.thu": "Thu",
    "day.fri": "Fri",
    "day.sat": "Sat",
    "day.sun": "Sun",
    "mock.browser.blocked": "Blocked",
    "mock.browser.reason": "Daily limit reached for this site",
    "mock.browser.until": "Available again tomorrow",
    "shots.eyebrow": "In the apps",
    "shots.title": "The real thing.",
    "shots.lead": "Current English screenshots of Unscroll on Android, in the browser, and on the desktop.",
    "shots.android.title": "Android",
    "shots.android.caption": "Today’s total, hourly chart, and limits — on the phone.",
    "shots.android.alt": "Unscroll for Android, English: today’s screen time on the home screen.",
    "shots.extension.title": "Browser extension",
    "shots.extension.caption": "Site limits in a popup, in the browser you already use.",
    "shots.extension.alt": "Unscroll browser extension popup in English, showing today’s time, top sites, and a focus button.",
    "shots.desktop.title": "Desktop",
    "shots.desktop.caption": "The week at a glance, on your computer.",
    "shots.desktop.alt": "Unscroll for desktop in English: today’s total, app list, hourly chart, last seven days, and a category split.",
    "features.eyebrow": "What it does",
    "features.title": "Limits you can keep.",
    "features.lead": "Limits, focus, sleep, and stats. Everything stays on your device.",
    "feature.limits.title": "Daily limits and blocking",
    "feature.limits.body": "Set a cap for an app or a site. When the time is gone, Unscroll blocks it until the next day, or until a schedule says it may open.",
    "feature.focus.title": "Focus mode",
    "feature.focus.body": "Start a session with a short allow-list. Everything else stays closed until the session ends.",
    "feature.sleep.title": "Sleep mode and schedules",
    "feature.sleep.body": "Choose a nightly window. The apps and sites you name stay closed inside it. Weekdays and weekends can use different hours.",
    "feature.stats.title": "Detailed stats",
    "feature.stats.body": "Time by app, site, and day. Rings for today, bars for the week. The history stays on the device.",
    "feature.reminders.title": "Reminders",
    "feature.reminders.body": "A nudge before a limit, before sleep, or when a session runs long. Reminders are scheduled on the device. They are not messages from a server.",
    "selfhost.eyebrow": "Self-host",
    "selfhost.title": "Run the sync server yourself.",
    "selfhost.lead": "Free, forever. A small Go server, ready for Docker. It stores ciphertext only — it never sees your key or your data.",
    "selfhost.follow": "Keep the server on localhost and put TLS in front. In each app, open Sync and point it at your own https origin.",
    "selfhost.doc.server": "Server README",
    "selfhost.doc.protocol": "Sync protocol",
    "selfhost.doc.source": "Server source",
    "privacy.eyebrow": "Why open source",
    "privacy.title": "Read the code. Run it yourself.",
    "privacy.lead": "One edition, AGPL-3.0, every feature free. No feature gating. Your usage stays on the device unless you turn sync on.",
    "privacy.oss.title": "Public source",
    "privacy.oss.body": "The Android app, desktop app, browser extension, and sync server live in one monorepo. You can audit every line.",
    "privacy.trackers.title": "No trackers, no ads",
    "privacy.trackers.body": "No analytics SDK, no crash-report vendor, and no third-party pixels. This page is static files, with no beacons.",
    "privacy.data.title": "Local-first",
    "privacy.data.body": "Usage, limits, and schedules are stored locally. You can use Unscroll without an account.",
    "privacy.sync.title": "End-to-end encrypted sync",
    "privacy.sync.body": "Optional sync encrypts data on the device. The server stores opaque ciphertext and never sees the key. Self-host it, or use Unscroll Cloud.",
    "pricing.eyebrow": "Sync",
    "pricing.title": "Your server, or ours.",
    "pricing.lead": "The apps stay free and complete either way. You only pay if you want hosted sync.",
    "pricing.self.name": "Self-hosted",
    "pricing.self.kicker": "Your server",
    "pricing.self.price": "Free forever",
    "pricing.self.summary": "Run the Go server with Docker or a binary. Unlimited, no trial, no plan screen.",
    "pricing.self.cta": "Self-host it",
    "pricing.cloud.name": "Unscroll Cloud",
    "pricing.cloud.kicker": "Hosted sync",
    "pricing.cloud.price": "€2.99/mo or €24/yr",
    "pricing.cloud.trial": "14-day free trial. No card needed to start.",
    "pricing.cloud.summary": "Hosted sync if you don’t want to run a server. Subscribe from the app, via Stripe.",
    "pricing.cloud.cta": "Unscroll Cloud",
    "faq.eyebrow": "Questions",
    "faq.title": "Short answers.",
    "faq.q1": "Does Unscroll need an account?",
    "faq.a1": "No. Limits and history live on the device. Sync, if you turn it on, creates an anonymous account — no email, no password.",
    "faq.q2": "What leaves the device?",
    "faq.a2": "Nothing, unless you turn sync on. Sync encrypts data on the device and sends only ciphertext to the server you chose — yours, or Unscroll Cloud.",
    "faq.q3": "Which platforms does it run on?",
    "faq.a3": "An Android app (8.0+), a desktop app (Linux AppImage; Windows and macOS can be built from source), and a browser extension for Chrome, Edge, and Firefox.",
    "faq.q4": "Can it block both apps and websites?",
    "faq.a4": "Yes. The phone and desktop apps block apps. The extension blocks sites. Focus and sleep can include both.",
    "faq.q5": "What does the AGPL cover?",
    "faq.a5": "The whole project is under the GNU AGPL v3. If you run a modified version as a service for other people, you share that version’s source under the same license.",
    "faq.q6": "How do I get the apps?",
    "faq.a6": "From the public repository on GitHub. There is one edition, with every feature included.",
    "faq.q7": "Is sync paid?",
    "faq.a7": "Self-hosting is free. Unscroll Cloud, the hosted option, is €2.99/month or €24/year, with a 14-day free trial.",
    "footer.nav": "Footer",
    "footer.tag": "Open-source, local-first screen time. No trackers on this page.",
    "footer.note": "Licensed under AGPL-3.0.",
    "cloud.meta.title": "Unscroll Cloud — hosted sync",
    "cloud.meta.description": "Unscroll Cloud is hosted, end-to-end encrypted sync for people who don’t want to run a server. €2.99/month or €24/year, with a 14-day free trial. No card needed to start.",
    "cloud.hero.eyebrow": "Hosted sync",
    "cloud.hero.title": "Cloud",
    "cloud.hero.tagline": "Encrypted sync between your devices, if you would rather not run a server. The apps stay free and complete.",
    "cloud.cta.download": "Get the apps",
    "cloud.cta.selfhost": "Prefer to self-host?",
    "cloud.pricing.eyebrow": "Pricing",
    "cloud.pricing.title": "The only paid part of Unscroll.",
    "cloud.pricing.lead": "You pay for hosting, not for features. Self-hosting stays free.",
    "cloud.pricing.summary": "Subscribe from the app when you are ready, via Stripe.",
    "cloud.what.eyebrow": "What you get",
    "cloud.what.title": "The same sync, hosted for you.",
    "cloud.what.e2e.title": "End-to-end encrypted",
    "cloud.what.e2e.body": "Each device encrypts with a key that never leaves your devices. The server stores opaque ciphertext only.",
    "cloud.what.host.title": "A server you don’t run",
    "cloud.what.host.body": "The default server in the apps is already Unscroll Cloud. Pair a device with a QR code, as with a self-hosted server.",
    "cloud.what.trial.title": "14-day trial, no card",
    "cloud.what.trial.body": "The trial starts the first time a device syncs. You can subscribe later, or switch to self-hosting at any time.",
    "cloud.what.apps.title": "The apps stay free",
    "cloud.what.apps.body": "One edition, no feature gating. Limits, focus, sleep, and stats do not depend on a subscription.",
    "cloud.privacy.eyebrow": "Privacy",
    "cloud.privacy.title": "Encrypted data, an anonymous id.",
    "cloud.privacy.store.title": "Ciphertext only",
    "cloud.privacy.store.body": "The hosted server never sees your encryption key, your passphrase, or your usage in the clear.",
    "cloud.privacy.stripe.title": "What Stripe sees",
    "cloud.privacy.stripe.body": "Only an anonymous account id, plus what you type in Checkout. Usage data is never sent to Stripe.",
    "cloud.privacy.grace.title": "When access ends",
    "cloud.privacy.grace.body": "The encrypted server copy is kept for 30 days, then deleted. Data on your devices is never touched, and you can still export it.",
    "cloud.privacy.account.title": "No email account",
    "cloud.privacy.account.body": "Turning sync on creates an anonymous account. No email, no password.",
    "cloud.start.eyebrow": "How to start",
    "cloud.start.title": "The trial lives in the app.",
    "cloud.start.lead": "There is no checkout on this site. You start from Settings > Sync > Unscroll Cloud.",
    "cloud.start.s1": "Get the Android app, the desktop app, or the browser extension from the public repository.",
    "cloud.start.s2": "Open Settings, then Sync, and turn sync on.",
    "cloud.start.s3": "Choose Unscroll Cloud — it is the default server URL.",
    "cloud.start.s4": "The 14-day trial starts on the first sync. No card is required. Subscribe later with Settings > Sync > Unscroll Cloud > Subscribe.",
    "cloud.faq.title": "About Unscroll Cloud.",
    "cloud.faq.q1": "Do I need Cloud to use Unscroll?",
    "cloud.faq.a1": "No. Every feature works on the device for free. Sync is optional, and self-hosting the sync server is free.",
    "cloud.faq.q2": "Do I need a card for the trial?",
    "cloud.faq.a2": "No. The trial starts the first time a device syncs, with no card.",
    "cloud.faq.q3": "What happens when the trial ends?",
    "cloud.faq.a3": "You can subscribe or self-host. Your data stays on your devices and can still be exported. The encrypted server copy is kept for 30 days, then deleted.",
    "cloud.faq.q4": "What does Stripe see?",
    "cloud.faq.a4": "Only an anonymous account id and what you type in Checkout. Usage data never goes to Stripe, and end-to-end encryption is unchanged.",
    "cloud.faq.q5": "Can I switch to self-hosting?",
    "cloud.faq.a5": "Yes. Run your own server and point Sync at your origin. The apps and the encryption stay the same.",
    "cloud.faq.q6": "Is Cloud a different edition?",
    "cloud.faq.a6": "No. There is one edition, AGPL-3.0, with every feature included. Cloud only hosts the sync server."
  },
  fr: {
    "meta.title": "Unscroll — temps d’écran open source, local-first",
    "meta.description": "Unscroll est une appli de temps d’écran libre, open source et local-first pour Android, l’ordinateur et le navigateur. Auto-hébergez la sync chiffrée, ou utilisez Unscroll Cloud.",
    "skip": "Aller au contenu",
    "nav.primary": "Principal",
    "nav.apps": "Applis",
    "nav.features": "Fonctions",
    "nav.selfhost": "Auto-hébergement",
    "nav.cloud": "Cloud",
    "nav.faq": "FAQ",
    "lang.group": "Langue",
    "hero.eyebrow": "Open source · AGPL-3.0",
    "hero.title.lead": "Le défilement",
    "hero.title.em": "peut attendre.",
    "hero.tagline": "Une appli de temps d’écran libre et open source pour Android, l’ordinateur et le navigateur. Local-first, sans trackers, sans publicité. Auto-hébergez la sync chiffrée, ou utilisez Unscroll Cloud.",
    "hero.cta.github": "Voir sur GitHub",
    "hero.cta.selfhost": "L’auto-héberger",
    "hero.note": "Une seule édition. Toutes les fonctions sont gratuites. Seule la sync hébergée est payante.",
    "hero.dial.sample": "Journée d’exemple",
    "hero.dial.time": "3 h 23",
    "hero.dial.label": "sur l’appareil aujourd’hui",
    "hero.legend.usage": "Applis et sites · 3 h 23",
    "hero.legend.sleep": "Fenêtre de sommeil · 8 h",
    "hero.legend.rest": "Loin de l’écran · le reste",
    "products.eyebrow": "Les trois applis",
    "products.title": "Les mêmes limites, sur chaque écran.",
    "products.lead": "Un monorepo public. Récupérez les applis sur GitHub.",
    "badge.oss": "AGPL-3.0",
    "product.android.name": "Android",
    "product.android.platform": "Android 8.0+",
    "product.android.body": "Plafonds et blocages pour les applis du téléphone, et des anneaux pour le temps passé aujourd’hui.",
    "product.desktop.name": "Ordinateur",
    "product.desktop.platform": "AppImage Linux. Windows et macOS depuis les sources.",
    "product.desktop.body": "Limites pour les applis de bureau, sessions Focus, et une semaine de graphiques stockés sur la machine.",
    "product.browser.name": "Extension navigateur",
    "product.browser.platform": "Chrome, Edge et Firefox",
    "product.browser.body": "Des limites de sites dans le navigateur que vous utilisez déjà. Quand le temps est écoulé, une page de blocage remplace le site.",
    "product.source": "Sources sur GitHub",
    "mock.sample": "Exemple",
    "mock.phone.today": "Aujourd’hui",
    "mock.phone.apps": "Applis",
    "mock.phone.sites": "Sites",
    "mock.phone.focus": "Focus",
    "mock.phone.appsTime": "2 h 10",
    "mock.phone.sitesTime": "48 min",
    "mock.phone.focusTime": "25 min",
    "mock.desktop.title": "Cette semaine",
    "mock.desktop.aria": "Exemple de graphique en barres des heures, du lundi au dimanche. Le jeudi est la barre la plus haute.",
    "mock.desktop.sessions": "Sessions Focus",
    "mock.desktop.limits": "Limites atteintes",
    "day.mon": "Lun",
    "day.tue": "Mar",
    "day.wed": "Mer",
    "day.thu": "Jeu",
    "day.fri": "Ven",
    "day.sat": "Sam",
    "day.sun": "Dim",
    "mock.browser.blocked": "Bloqué",
    "mock.browser.reason": "Limite quotidienne atteinte pour ce site",
    "mock.browser.until": "De nouveau disponible demain",
    "shots.eyebrow": "Dans les applis",
    "shots.title": "Le vrai écran.",
    "shots.lead": "Captures actuelles, en anglais, d’Unscroll sur Android, dans le navigateur et sur l’ordinateur.",
    "shots.android.title": "Android",
    "shots.android.caption": "Le total du jour, le graphique horaire et les limites — sur le téléphone.",
    "shots.android.alt": "Unscroll pour Android, en anglais : le temps d’écran du jour sur l’écran d’accueil.",
    "shots.extension.title": "Extension navigateur",
    "shots.extension.caption": "Des limites de sites dans une popup, dans le navigateur que vous utilisez déjà.",
    "shots.extension.alt": "Popup de l’extension Unscroll en anglais, avec le temps du jour, les sites les plus visités et un bouton Focus.",
    "shots.desktop.title": "Ordinateur",
    "shots.desktop.caption": "La semaine en un coup d’œil, sur l’ordinateur.",
    "shots.desktop.alt": "Unscroll pour ordinateur en anglais : total du jour, liste d’applis, graphique horaire, sept derniers jours et répartition par catégorie.",
    "features.eyebrow": "Ce qu’elle fait",
    "features.title": "Des limites que l’on tient.",
    "features.lead": "Limites, Focus, sommeil et stats. Tout reste sur l’appareil.",
    "feature.limits.title": "Limites quotidiennes et blocage",
    "feature.limits.body": "Fixez un plafond pour une appli ou un site. Quand le temps est écoulé, Unscroll le bloque jusqu’au lendemain, ou jusqu’à ce qu’un planning l’autorise.",
    "feature.focus.title": "Mode Focus",
    "feature.focus.body": "Lancez une session avec une courte liste d’autorisations. Tout le reste reste fermé jusqu’à la fin de la session.",
    "feature.sleep.title": "Mode sommeil et plannings",
    "feature.sleep.body": "Choisissez une fenêtre nocturne. Les applis et sites que vous nommez restent fermés pendant celle-ci. Semaine et week-end peuvent avoir des horaires différents.",
    "feature.stats.title": "Statistiques détaillées",
    "feature.stats.body": "Le temps par appli, site et jour. Des anneaux pour aujourd’hui, des barres pour la semaine. L’historique reste sur l’appareil.",
    "feature.reminders.title": "Rappels",
    "feature.reminders.body": "Un rappel avant une limite, avant le sommeil, ou quand une session s’éternise. Les rappels sont planifiés sur l’appareil. Ce ne sont pas des messages d’un serveur.",
    "selfhost.eyebrow": "Auto-hébergement",
    "selfhost.title": "Hébergez le serveur de sync vous-même.",
    "selfhost.lead": "Gratuit, pour toujours. Un petit serveur Go, prêt pour Docker. Il ne stocke que du chiffré — il ne voit ni votre clé, ni vos données.",
    "selfhost.follow": "Laissez le serveur sur localhost et placez le TLS devant. Dans chaque appli, ouvrez Sync et indiquez votre origine https.",
    "selfhost.doc.server": "README du serveur",
    "selfhost.doc.protocol": "Protocole de sync",
    "selfhost.doc.source": "Sources du serveur",
    "privacy.eyebrow": "Pourquoi c’est open source",
    "privacy.title": "Lisez le code. Faites-le tourner vous-même.",
    "privacy.lead": "Une seule édition, AGPL-3.0, toutes les fonctions gratuites. Pas de verrouillage. Votre usage reste sur l’appareil, sauf si vous activez la sync.",
    "privacy.oss.title": "Sources publiques",
    "privacy.oss.body": "L’appli Android, l’appli bureau, l’extension et le serveur de sync vivent dans un seul monorepo. Vous pouvez tout auditer.",
    "privacy.trackers.title": "Sans trackers, sans pub",
    "privacy.trackers.body": "Pas de SDK d’analytics, pas d’éditeur de rapports de plantage, pas de pixel tiers. Cette page est faite de fichiers statiques, sans balise.",
    "privacy.data.title": "Local-first",
    "privacy.data.body": "L’usage, les limites et les plannings sont stockés localement. Vous pouvez utiliser Unscroll sans compte.",
    "privacy.sync.title": "Sync chiffrée de bout en bout",
    "privacy.sync.body": "La sync, optionnelle, chiffre les données sur l’appareil. Le serveur ne stocke que du chiffré opaque et ne voit jamais la clé. Auto-hébergez-le, ou utilisez Unscroll Cloud.",
    "pricing.eyebrow": "Sync",
    "pricing.title": "Votre serveur, ou le nôtre.",
    "pricing.lead": "Les applis restent gratuites et complètes dans les deux cas. Vous ne payez que si vous voulez une sync hébergée.",
    "pricing.self.name": "Auto-hébergé",
    "pricing.self.kicker": "Votre serveur",
    "pricing.self.price": "Gratuit, pour toujours",
    "pricing.self.summary": "Lancez le serveur Go avec Docker ou un binaire. Illimité, sans essai, sans écran d’abonnement.",
    "pricing.self.cta": "L’auto-héberger",
    "pricing.cloud.name": "Unscroll Cloud",
    "pricing.cloud.kicker": "Sync hébergée",
    "pricing.cloud.price": "2,99 €/mois ou 24 €/an",
    "pricing.cloud.trial": "Essai gratuit de 14 jours. Pas de carte pour commencer.",
    "pricing.cloud.summary": "La sync hébergée si vous ne voulez pas gérer un serveur. L’abonnement se lance depuis l’appli, via Stripe.",
    "pricing.cloud.cta": "Unscroll Cloud",
    "faq.eyebrow": "Questions",
    "faq.title": "Réponses courtes.",
    "faq.q1": "Unscroll a-t-il besoin d’un compte ?",
    "faq.a1": "Non. Les limites et l’historique vivent sur l’appareil. La sync, si vous l’activez, crée un compte anonyme — pas d’e-mail, pas de mot de passe.",
    "faq.q2": "Qu’est-ce qui quitte l’appareil ?",
    "faq.a2": "Rien, sauf si vous activez la sync. Celle-ci chiffre les données sur l’appareil et n’envoie que du chiffré au serveur que vous avez choisi — le vôtre, ou Unscroll Cloud.",
    "faq.q3": "Sur quelles plateformes ça tourne ?",
    "faq.a3": "Une appli Android (8.0+), une appli bureau (AppImage Linux ; Windows et macOS peuvent être compilés depuis les sources), et une extension pour Chrome, Edge et Firefox.",
    "faq.q4": "Peut-elle bloquer les applis et les sites ?",
    "faq.a4": "Oui. Le téléphone et l’ordinateur bloquent les applis. L’extension bloque les sites. Focus et sommeil peuvent inclure les deux.",
    "faq.q5": "Que couvre la licence AGPL ?",
    "faq.a5": "Tout le projet est sous GNU AGPL v3. Si vous faites tourner une version modifiée comme service pour d’autres personnes, vous en partagez les sources sous la même licence.",
    "faq.q6": "Comment obtenir les applis ?",
    "faq.a6": "Depuis le dépôt public sur GitHub. Il n’y a qu’une édition, avec toutes les fonctions.",
    "faq.q7": "La sync est-elle payante ?",
    "faq.a7": "L’auto-hébergement est gratuit. Unscroll Cloud, l’option hébergée, coûte 2,99 €/mois ou 24 €/an, avec un essai gratuit de 14 jours.",
    "footer.nav": "Pied de page",
    "footer.tag": "Temps d’écran open source et local-first. Pas de trackers sur cette page.",
    "footer.note": "Licence AGPL-3.0.",
    "cloud.meta.title": "Unscroll Cloud — sync hébergée",
    "cloud.meta.description": "Unscroll Cloud est une sync hébergée, chiffrée de bout en bout, pour celles et ceux qui ne veulent pas gérer un serveur. 2,99 €/mois ou 24 €/an, essai gratuit de 14 jours. Pas de carte pour commencer.",
    "cloud.hero.eyebrow": "Sync hébergée",
    "cloud.hero.title": "Cloud",
    "cloud.hero.tagline": "Une sync chiffrée entre vos appareils, si vous préférez ne pas gérer un serveur. Les applis restent gratuites et complètes.",
    "cloud.cta.download": "Obtenir les applis",
    "cloud.cta.selfhost": "Vous préférez l’auto-hébergement ?",
    "cloud.pricing.eyebrow": "Tarifs",
    "cloud.pricing.title": "La seule partie payante d’Unscroll.",
    "cloud.pricing.lead": "Vous payez l’hébergement, pas les fonctions. L’auto-hébergement reste gratuit.",
    "cloud.pricing.summary": "Abonnez-vous depuis l’appli quand vous êtes prêt, via Stripe.",
    "cloud.what.eyebrow": "Ce que vous obtenez",
    "cloud.what.title": "La même sync, hébergée pour vous.",
    "cloud.what.e2e.title": "Chiffrée de bout en bout",
    "cloud.what.e2e.body": "Chaque appareil chiffre avec une clé qui ne quitte pas vos appareils. Le serveur ne stocke que du chiffré opaque.",
    "cloud.what.host.title": "Un serveur que vous ne gérez pas",
    "cloud.what.host.body": "Le serveur par défaut dans les applis est déjà Unscroll Cloud. Appariez un appareil avec un QR code, comme avec un serveur auto-hébergé.",
    "cloud.what.trial.title": "Essai de 14 jours, sans carte",
    "cloud.what.trial.body": "L’essai commence à la première sync d’un appareil. Vous pouvez vous abonner plus tard, ou passer à l’auto-hébergement à tout moment.",
    "cloud.what.apps.title": "Les applis restent gratuites",
    "cloud.what.apps.body": "Une seule édition, sans verrouillage de fonctions. Limites, Focus, sommeil et stats ne dépendent pas d’un abonnement.",
    "cloud.privacy.eyebrow": "Confidentialité",
    "cloud.privacy.title": "Des données chiffrées, un identifiant anonyme.",
    "cloud.privacy.store.title": "Chiffré uniquement",
    "cloud.privacy.store.body": "Le serveur hébergé ne voit jamais votre clé de chiffrement, votre phrase secrète, ni votre usage en clair.",
    "cloud.privacy.stripe.title": "Ce que Stripe voit",
    "cloud.privacy.stripe.body": "Seulement un identifiant de compte anonyme, plus ce que vous saisissez dans Checkout. Les données d’usage ne sont jamais envoyées à Stripe.",
    "cloud.privacy.grace.title": "Quand l’accès se termine",
    "cloud.privacy.grace.body": "La copie chiffrée sur le serveur est conservée 30 jours, puis supprimée. Les données sur vos appareils ne sont jamais touchées, et vous pouvez toujours les exporter.",
    "cloud.privacy.account.title": "Pas de compte e-mail",
    "cloud.privacy.account.body": "Activer la sync crée un compte anonyme. Pas d’e-mail, pas de mot de passe.",
    "cloud.start.eyebrow": "Comment commencer",
    "cloud.start.title": "L’essai se lance dans l’appli.",
    "cloud.start.lead": "Il n’y a pas de paiement sur ce site. Vous commencez depuis Réglages > Sync > Unscroll Cloud.",
    "cloud.start.s1": "Récupérez l’appli Android, l’appli bureau ou l’extension depuis le dépôt public.",
    "cloud.start.s2": "Ouvrez Réglages, puis Sync, et activez la sync.",
    "cloud.start.s3": "Choisissez Unscroll Cloud — c’est l’URL du serveur par défaut.",
    "cloud.start.s4": "L’essai de 14 jours commence à la première sync. Aucune carte n’est demandée. Abonnez-vous plus tard via Réglages > Sync > Unscroll Cloud > S’abonner.",
    "cloud.faq.title": "À propos d’Unscroll Cloud.",
    "cloud.faq.q1": "Faut-il Cloud pour utiliser Unscroll ?",
    "cloud.faq.a1": "Non. Toutes les fonctions marchent sur l’appareil, gratuitement. La sync est optionnelle, et l’auto-hébergement du serveur est gratuit.",
    "cloud.faq.q2": "Faut-il une carte pour l’essai ?",
    "cloud.faq.a2": "Non. L’essai commence à la première sync d’un appareil, sans carte.",
    "cloud.faq.q3": "Que se passe-t-il à la fin de l’essai ?",
    "cloud.faq.a3": "Vous pouvez vous abonner ou auto-héberger. Vos données restent sur vos appareils et peuvent encore être exportées. La copie chiffrée sur le serveur est conservée 30 jours, puis supprimée.",
    "cloud.faq.q4": "Que voit Stripe ?",
    "cloud.faq.a4": "Seulement un identifiant de compte anonyme et ce que vous saisissez dans Checkout. Les données d’usage ne vont jamais à Stripe, et le chiffrement de bout en bout ne change pas.",
    "cloud.faq.q5": "Puis-je passer à l’auto-hébergement ?",
    "cloud.faq.a5": "Oui. Lancez votre propre serveur et pointez Sync vers votre origine. Les applis et le chiffrement restent les mêmes.",
    "cloud.faq.q6": "Cloud est-il une autre édition ?",
    "cloud.faq.a6": "Non. Il n’y a qu’une édition, AGPL-3.0, avec toutes les fonctions. Cloud n’héberge que le serveur de sync."
  }
};

function readLang() {
  try {
    var stored = localStorage.getItem(STORAGE_KEY);
    if (stored === "en" || stored === "fr") return stored;
  } catch (err) {
    return "en";
  }
  return "en";
}

function writeLang(lang) {
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch (err) {
    /* Storage can be blocked. The page still switches for this visit. */
  }
}

function applyLang(lang, persist) {
  var chosen = STRINGS[lang] ? lang : "en";
  var dict = STRINGS[chosen];
  document.documentElement.lang = chosen;

  document.querySelectorAll("[data-i18n]").forEach(function (el) {
    var key = el.getAttribute("data-i18n");
    if (Object.prototype.hasOwnProperty.call(dict, key)) {
      el.textContent = dict[key];
    }
  });

  document.querySelectorAll("[data-i18n-aria]").forEach(function (el) {
    var key = el.getAttribute("data-i18n-aria");
    if (Object.prototype.hasOwnProperty.call(dict, key)) {
      el.setAttribute("aria-label", dict[key]);
    }
  });

  document.querySelectorAll("[data-i18n-content]").forEach(function (el) {
    var key = el.getAttribute("data-i18n-content");
    if (Object.prototype.hasOwnProperty.call(dict, key)) {
      el.setAttribute("content", dict[key]);
    }
  });

  document.querySelectorAll("[data-i18n-alt]").forEach(function (el) {
    var key = el.getAttribute("data-i18n-alt");
    if (Object.prototype.hasOwnProperty.call(dict, key)) {
      el.setAttribute("alt", dict[key]);
    }
  });

  if (dict["meta.title"]) document.title = dict["meta.title"];

  document.querySelectorAll("[data-lang]").forEach(function (btn) {
    btn.setAttribute("aria-pressed", btn.getAttribute("data-lang") === chosen ? "true" : "false");
  });

  if (persist) writeLang(chosen);
}

function setupLang() {
  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var btn = target.closest("[data-lang]");
    if (!btn) return;
    var lang = btn.getAttribute("data-lang");
    if (lang !== "en" && lang !== "fr") return;
    applyLang(lang, true);
  });
  applyLang(readLang(), false);
}

function setupReveal() {
  var nodes = Array.prototype.slice.call(document.querySelectorAll("[data-reveal]"));
  if (!nodes.length) return;

  if (prefersReducedMotion() || !("IntersectionObserver" in window)) {
    nodes.forEach(function (el) { el.classList.add("is-visible"); });
    return;
  }

  var viewH = window.innerHeight || document.documentElement.clientHeight;
  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      entry.target.classList.add("is-visible");
      observer.unobserve(entry.target);
    });
  }, { threshold: 0.16, rootMargin: "0px 0px -8% 0px" });

  nodes.forEach(function (el) {
    var rect = el.getBoundingClientRect();
    if (rect.top < viewH * 0.92 && rect.bottom > 0) {
      el.classList.add("is-visible");
    } else {
      observer.observe(el);
    }
  });
}

function setupAnchorFocus() {
  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    var link = target.closest('a[href^="#"]');
    if (!link) return;
    var id = link.getAttribute("href").slice(1);
    if (!id) return;
    var dest = document.getElementById(id);
    if (!dest) return;
    window.setTimeout(function () {
      dest.focus({ preventScroll: true });
    }, prefersReducedMotion() ? 0 : 450);
  });
}

setupLang();
setupReveal();
document.documentElement.classList.add("js");
setupAnchorFocus();
