# Checklist Play Console — Unscroll

À faire dans Google Play Console pour l'application `com.elimbi.unscroll`. Rien ici n'est déjà publié.

## Avant de commencer

1. Connecte-toi à Play Console avec le compte développeur Google.
2. Vérifie le type de compte : **personnel** ou **organisation**. Si le compte est personnel (surtout s'il a été créé après fin 2023), Google impose un **test fermé avec au moins 12 testeurs pendant 14 jours** avant toute publication en production. Coche ce point dès la création de l'app, c'est le plus long.
3. Crée l'application : nom **Unscroll**, langue par défaut **Anglais**, application **gratuite**, déclaration **pas de publicité**.

## Fiche et contenus

4. Fiche Play : copie `android/store/listing-en.md` (nom, courte, longue). Ajoute la traduction française depuis `listing-fr.md`.
5. Catégorie : **Productivité**. Public cible : **18 ans et plus**, pas d'enfants, pas Designed for Families.
6. Graphismes : icône 512×512, bannière 1024×500, et les captures téléphone 1080×1920, tous dans `android/store/graphics/` (et aussi dans le dossier remis avec l'AAB).
7. Politique de confidentialité : **https://unscroll.elimbi.com/privacy.html**
8. Questionnaire de classement du contenu : réponses dans `android/store/play-forms.md` (utilitaire, pas de violence, etc.).
9. Sécurité des données : suis `play-forms.md`. Pas de pub, pas de vente de données, sync facultative chiffrée de bout en bout, Stripe seulement pour Cloud.
10. Déclaration **pas de publicité**.

## Déclarations d'autorisations

11. Accès aux données d'usage, affichage par-dessus les autres apps, et service d'accessibilité **uniquement pour le suivi du temps par site** : textes à coller dans `play-forms.md`. Ne pas déclarer QUERY_ALL_PACKAGES, l'accès aux notifications, ni un service de premier plan « special use » : ils ne sont plus demandés.
12. Pour l'accessibilité : déclarer uniquement le suivi du temps sur les sites (lecture de la barre d'adresse des navigateurs connus). **Ne pas** déclarer Unscroll comme outil d'accessibilité pour les personnes handicapées. Tourner une courte vidéo de démo : activation du suivi des sites, divulgation dans l'app, consentement, puis le temps d'un site (ou une limite de site) qui s'applique.
13. Le fichier AAB à envoyer est `app-release.aab` (versionCode **3**, versionName **1.0.2**, applicationId **com.elimbi.unscroll**, targetSdk **36**). Active **Play App Signing** : la clé d'upload est déjà celle qui a signé ce fichier. Ne perds pas le keystore (copie hors ligne à faire).

## Test et envoi

14. Crée une version en **test fermé**, uploade l'AAB, remplis les notes pour les relecteurs (voir « App access notes » dans `play-forms.md`).
15. Si le compte est personnel : invite au moins 12 testeurs, attends qu'ils opt-in, et laisse tourner **14 jours** avant de demander la production.
16. Si le compte est une organisation et que la règle des 12 testeurs ne s'applique pas, tu peux quand même commencer par un test fermé, puis demander la production.
17. Ne passe en production qu'après l'examen des déclarations et, le cas échéant, les 14 jours de test fermé.

## Ce qui n'est pas encore à faire ici

- Les clés Stripe **live** et la commande Caddy pour le billing hébergé : chantier séparé. L'app peut être examinée sans l'abonnement Cloud actif ; la sync auto-hébergée suffit pour la démo.
