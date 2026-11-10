# Checklist Chrome Web Store — Unscroll

À faire dans la [console développeur Chrome](https://chrome.google.com/webstore/devconsole). Ce pack prépare l'envoi ; **il ne crée pas le compte et ne paie pas les 5 $**. C'est à toi de le faire.

## 1. Créer le compte développeur

1. Ouvre https://chrome.google.com/webstore/devconsole avec le compte Google que tu veux utiliser (celui qui restera le propriétaire).
2. Inscris-toi comme développeur. Google demande un paiement unique de **5 USD**. Pas d'abonnement. Paiement par carte, via le flux Google.
3. Vérifie l'adresse e-mail du compte. Google peut aussi demander une vérification d'identité (pièce d'identité) ou un numéro de téléphone, surtout pour une première publication : prévois-le, ça bloque parfois l'envoi.
4. Renseigne le profil développeur (nom affiché, e-mail de contact : `williamelimbi@gmail.com`).

## 2. Créer l'élément et envoyer le zip

5. Crée un **nouvel élément** Chrome.
6. Envoie le zip `extension/store/unscroll-1.0.0-chrome.zip` (version **1.0.0**, Manifest V3).
7. Si le zip est rejeté, relis le rapport. Le manifeste ne déclare pas de `host_permissions`. Les scripts sont locaux, sans code distant.

## 3. Coller la fiche

8. Langue par défaut : **English**. Colle le nom, la description courte et la description détaillée depuis `listing-en.md`.
9. Ajoute une traduction **française** depuis `listing-fr.md`.
10. Catégorie : **Productivity**.
11. Graphismes, tous dans `extension/store/graphics/` :
    - icône du store **128×128** : `icon-128.png`
    - au moins les 3 captures **1280×800** (anglais, données d'exemple) : `screenshot-01-popup.png`, `screenshot-02-home.png`, `screenshot-03-sync.png`
    - tuile promo petite **440×280** : `promo-small-440x280.png` (facultative dans le formulaire, fournie)
    - bannière marquee **1400×560** : `marquee-1400x560.png` (facultative, fournie)
12. Politique de confidentialité : **https://unscroll.elimbi.com/privacy.html**
13. Onglet confidentialité / autorisations : copie les réponses de `privacy-answers.md` (objectif unique, justification de chaque permission, aucune permission d'hôte, pas de code distant, usage des données, URL de la politique).

## 4. Contrôles avant soumission

14. Distribution : pays que tu veux (souvent « tous »). Visibilité : **Public** une fois prêt, ou **Non répertorié** pour un test.
15. Déclare que l'extension n'est pas destinée aux enfants si le formulaire le demande.
16. Relis une dernière fois le manifeste chargé par la console (permissions : `storage`, `tabs`, `idle`, `alarms`, `notifications` uniquement).
17. Soumets pour examen.

## 5. Délai d'examen typique

Pour un **premier** élément sur un **nouveau** compte, l'examen prend souvent **quelques jours**, parfois **une à trois semaines**. Google peut poser des questions sur les permissions ou demander une vérification d'identité avant de publier. Un rejet arrive par e-mail ; tu peux corriger et renvoyer. Les mises à jour suivantes sont en général plus rapides.

## Ce qui n'est pas inclus ici

- L'inscription et le paiement des 5 $ (à faire toi-même).
- La publication automatique : rien n'est envoyé tant que tu n'as pas cliqué sur « Soumettre ».
- Les clés Stripe live (chantier séparé). L'extension peut être examinée sans Cloud actif ; la sync auto-hébergée suffit pour une démo.
