# Unscroll — Fiche Chrome Web Store (français)

- **Nom** (≤45) : Unscroll
- **Description courte** (≤132, 124 caractères) :

Suivi open source et local du temps par site. Limites, blocage, concentration, et sync chiffrée de bout en bout facultative.

- **Description détaillée** (≤16 000, 3117 caractères) :

Unscroll est une extension de temps d'écran libre, open source, et locale d'abord, pour votre navigateur. Elle montre où va votre temps sur les sites que vous visitez, permet de poser des limites quotidiennes, et peut lancer une session de concentration quand vous voulez un moment plus calme. Il n'y a qu'une édition. Toutes les fonctions sont incluses. Le code est publié sous licence AGPL-3.0.

Site : https://unscroll.elimbi.com
Code et auto-hébergement : https://github.com/sepiropht/unscroll

CE QUI RESTE DANS LE NAVIGATEUR
Le temps par site, les limites, les plannings, les sessions de concentration et les réglages restent dans ce navigateur (chrome.storage.local). Il n'y a pas de compte à créer, pas de publicité, et pas de mesure d'audience ni de rapport de plantage. Rien n'est envoyé ailleurs, sauf si vous activez la sync, qui est facultative.

CE QUE VOUS POUVEZ FAIRE
• Voir le temps du jour, la liste par site, et des graphiques pour le jour, la semaine ou le mois
• Poser une limite quotidienne ou un planning pour un site
• Bloquer un site, ou n'autoriser que les sites d'une liste
• Lancer une session de concentration de 25 minutes, 50 minutes, ou d'une durée choisie
• Ouvrir une page de blocage lorsqu'une limite, un planning, une liste de blocage ou une session de concentration s'applique, avec une courte pause facultative et un mot de passe facultatif
• Exporter et importer une sauvegarde CSV ou JSON (les exports ne contiennent jamais les jetons ou clés de sync)

AUTORISATIONS
L'extension ne déclare que ce dont elle a besoin, et aucune permission d'hôte :
• storage — conserver l'usage, les limites et les réglages sur cet appareil
• tabs — lire l'adresse de l'onglet actif pour attribuer le temps à un site, et ouvrir la page de blocage lorsqu'une règle que vous avez posée s'applique
• idle — cesser de compter quand vous vous éloignez
• alarms — faire tourner le minuteur qui enregistre le temps, et la sync facultative
• notifications — prévenir avant une limite, et à propos d'une session de concentration

L'extension ne lit pas le contenu des pages, les cookies, les mots de passe, ni ce que vous tapez. Le temps dans les fenêtres privées n'est compté que si vous l'autorisez dans les réglages et dans le navigateur. Elle n'exécute pas de code distant.

SYNC FACULTATIVE
La sync est éteinte tant que vous ne l'allumez pas. Une fois allumée, l'extension chiffre la sauvegarde avec une clé qui reste sur vos appareils (AES-GCM) et n'envoie que du chiffré au serveur que vous choisissez.

Vous pouvez lancer vous-même le serveur de sync, gratuitement et en open source. Ou utiliser Unscroll Cloud, le serveur hébergé, à 2,99 €/mois ou 24 €/an, avec 14 jours d'essai et sans carte pour commencer. Après la fin d'un essai ou d'un abonnement, la copie chiffrée sur le serveur est conservée 30 jours, puis supprimée.

Les paiements Unscroll Cloud passent par Stripe. Stripe reçoit un identifiant de compte anonyme et les informations que vous saisissez dans Checkout. Les données d'usage ne vont jamais à Stripe.

Politique de confidentialité : https://unscroll.elimbi.com/privacy.html

- **Catégorie :** Productivité
