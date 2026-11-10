# Chrome Web Store pack — Unscroll

Ready-to-upload materials for the Chrome Web Store. Nothing here has been submitted. The $5 developer registration has to be paid by the account owner.

| File | What it is |
| --- | --- |
| `unscroll-1.0.0-chrome.zip` | Production zip, Manifest V3, built with `npm run package:chrome` |
| `zip-contents.txt` | File list inside the zip |
| `listing-en.md` | English name, short and detailed descriptions, category |
| `listing-fr.md` | French translation of the listing |
| `privacy-answers.md` | Privacy tab: single purpose, permissions, no host permissions, no remote code, data use, policy URL |
| `checklist-fr.md` | Short French checklist: register, upload, paste, submit |
| `graphics/` | 128×128 icon, 1280×800 screenshots, 440×280 small promo, 1400×560 marquee |
| `graphics/src/` | HTML sources used to render the framed screenshots and promo tiles |

Build and check from `extension/`:

```bash
npm test
npm run package:chrome
npm run validate:chrome
```

Privacy policy: https://unscroll.elimbi.com/privacy.html
