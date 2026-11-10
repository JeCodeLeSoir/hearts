# Claire Hearts

Une mini-app privée pour deux personnes : un clic envoie en direct une animation de cœur 3D à l'autre personne.

## Architecture

- **Frontend** : GitHub Pages, HTML/CSS/JavaScript et Three.js.
- **Backend** : Cloudflare Worker + Durable Object, WebSocket temps réel.
- **Accès** : mot de passe validé uniquement par le Worker, jeton HMAC valable 6 heures gardé en mémoire (jamais dans `localStorage`).
- **Capacité** : deux connexions simultanées maximum.

## 1. Déployer le Worker

Depuis le dossier `worker`, connecte Wrangler à Cloudflare, puis configure les secrets :

```powershell
npx wrangler login
npx wrangler secret put APP_PASSWORD
npx wrangler secret put SESSION_SECRET
npx wrangler deploy
```

Quand Wrangler demande `APP_PASSWORD`, saisis `Claire`. Pour `SESSION_SECRET`, utilise une longue valeur aléatoire (au moins 32 caractères). Les secrets ne doivent jamais être ajoutés au dépôt.

Dans `worker/wrangler.toml`, remplace `ALLOWED_ORIGIN` par l'origine exacte de GitHub Pages. Pour un site projet, l'origine reste par exemple `https://alice.github.io` (le chemin `/mon-repo` ne fait pas partie de l'origine).

## Site publié

- Frontend : `https://jecodelesoir.github.io/claire-hearts/`
- API : `https://claire-hearts-api.dark-aurelien-poke-worker.workers.dev`

## 2. Relier le frontend

Le frontend est déjà relié au Worker déployé. Pour utiliser un autre Worker, remplace dans `app.js` :

```js
const API_URL = "https://claire-hearts-api.dark-aurelien-poke-worker.workers.dev";
```

par la nouvelle URL affichée après `wrangler deploy`.

## 3. Publier sur GitHub Pages

Crée un dépôt GitHub, pousse ces fichiers sur la branche `main`, puis dans **Settings → Pages**, choisis **GitHub Actions** comme source. Le workflow inclus publiera le site automatiquement.

## Test local

Le frontend doit être servi par HTTP (pas ouvert en `file://`) :

```powershell
python -m http.server 8080
```

Pour un test local, ajoute temporairement `http://localhost:8080` comme `ALLOWED_ORIGIN`, puis redéploie le Worker.

## Limites connues

Le plafond de deux utilisateurs porte sur les connexions simultanées, pas sur deux identités personnelles : le mot de passe est partagé. Le compteur de cœurs est local à chaque onglet et n'est pas stocké.

## Application Android

Le dossier `android/` contient l'application native. Elle garde une connexion WebSocket via un service au premier plan et affiche une notification avec vibration lorsqu'un cœur arrive. Android affiche obligatoirement une notification permanente pendant l'écoute en arrière-plan.

L'APK de développement est généré dans `android/app/build/outputs/apk/debug/app-debug.apk`. Le mot de passe saisi est chiffré avec Android Keystore et n'est jamais inscrit dans le code ou le dépôt.
