# Mettre ONYX TV en ligne (APK) et l'installer via Downloader

Ce dépôt compile **automatiquement** un APK à chaque `push` grâce à GitHub Actions
(`.github/workflows/build-apk.yml`) et le publie dans une **Release** à une URL **fixe**.

## 1. L'URL de téléchargement (ne change jamais)

```
https://github.com/rosajayz14-boop/onyx/releases/download/tv-latest/onyx-tv.apk
```

- Elle pointe toujours vers la **dernière** version compilée.
- Le dépôt est **public**, donc l'APK est téléchargeable sans compte ni jeton — c'est
  ce qu'il faut pour Downloader / un navigateur TV.
- ⚠️ Si vous passez le dépôt en **privé**, cette URL cesse de fonctionner pour Downloader.

Pour déclencher/mettre à jour la compilation : poussez un commit, ou allez dans
l'onglet **Actions → Build & Release APK → Run workflow**.

## 2. Installer via Downloader (application AFTVnews)

### Option A — coller l'URL complète
1. Installez **Downloader** depuis le store de votre téléviseur (Fire TV / Google TV / Android TV).
2. Ouvrez Downloader → onglet **Home / Accueil**.
3. Dans le champ URL, saisissez l'adresse ci-dessus, puis **Go**.
4. Downloader télécharge l'APK puis propose **Install** → confirmez.
   - Fire TV : activez au préalable *My Fire TV → Developer options → Install unknown apps → Downloader = ON*.
   - Google TV / Android TV : *Paramètres → Système → À propos → (7 clics sur Build)* pour le mode dev,
     puis autorisez les **sources inconnues** pour Downloader.

### Option B — créer un **code court Downloader** (le plus simple à dicter)
Le « code » Downloader est un raccourci de type `aftv.news/123456` fourni **gratuitement**
par AFTVnews. Il faut le créer une fois (ça ne peut pas être fait automatiquement) :

1. Sur un ordinateur ou téléphone, allez sur **https://aftv.news** (page « Create a Downloader Code »).
2. Collez l'URL fixe de la section 1.
3. Le site renvoie un **code à 6 chiffres** (ex. `123456`).
4. Sur le téléviseur, dans Downloader, tapez juste ce **code** (sans `https://`) → **Go**.

Comme l'URL fixe pointe toujours vers la dernière version, **le même code ressert**
pour toutes les futures mises à jour : recréer un code n'est pas nécessaire.

## 3. Mettre à jour l'app

Poussez vos changements → Actions recompile → la Release `tv-latest` est remplacée.
Sur le téléviseur, réinstallez via le même code/URL : la mise à jour s'installe par-dessus
(si une clé de release stable est configurée, sinon désinstaller d'abord — voir §5).

## 3bis. Versions (tags Git)

En plus de `tv-latest` (toujours la dernière), vous pouvez publier des **versions figées**.
Il suffit de pousser un **tag** `vX.Y.Z` : la CI crée une Release dédiée et fixe le
`versionName` de l'app sur ce numéro.

**Deux façons de cadencer une version :**

- **Par tag Git** (depuis votre machine) :
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```
- **Sans tag, depuis GitHub** : onglet **Actions → Build & Release APK → Run workflow**,
  puis renseignez le champ **version** (ex. `1.0.0`). Le tag `v1.0.0` est créé automatiquement.

Cela produit :
- une Release **`v1.0.0`** avec l'APK `onyx-tv-1.0.0.apk` :
  `https://github.com/rosajayz14-boop/onyx/releases/download/v1.0.0/onyx-tv-1.0.0.apk`
- la mise à jour de **`tv-latest`** vers cette même version.

Le `versionCode` (entier interne qui doit toujours augmenter) est automatiquement le
numéro de build GitHub Actions, donc les mises à jour s'installent dans le bon ordre.

Supprimer un tag (et donc plus tard sa Release) :
```bash
git push --delete origin v1.0.0    # supprime le tag distant
git tag -d v1.0.0                  # supprime le tag local
# puis, si besoin, supprimer la Release dans l'onglet "Releases" de GitHub
```

## 4. Signature

**Aucune clé privée n'est stockée dans le dépôt** (ce serait une fuite de secret sur un dépôt public).

- **Par défaut** : l'APK est signé avec la clé de **debug** générée en CI. Il s'installe et
  fonctionne parfaitement via Downloader. Seule limite : la clé de debug n'étant pas stable,
  une nouvelle version peut demander de **désinstaller** l'ancienne avant de réinstaller.

- **Signature stable (recommandé à terme)** : pour que les mises à jour s'installent
  par-dessus sans désinstaller, générez une clé et ajoutez-la en **secrets GitHub Actions** :

  ```bash
  keytool -genkeypair -v -keystore onyx-release.jks -storetype PKCS12 \
    -alias onyx -keyalg RSA -keysize 2048 -validity 10000
  base64 -w0 onyx-release.jks   # copiez la sortie
  ```

  Dépôt GitHub → **Settings → Secrets and variables → Actions → New repository secret** :
  - `ONYX_KEYSTORE_BASE64` = la chaîne base64 ci-dessus
  - `ONYX_STORE_PW` = mot de passe du keystore
  - `ONYX_KEY_ALIAS` = `onyx`
  - `ONYX_KEY_PW` = mot de passe de la clé

  Au prochain build, la CI détecte le secret et signe avec votre clé (voir le workflow).
  **Ne committez jamais le fichier `.jks`** — gardez-le hors du dépôt et sauvegardez-le.

## 5. Stores officiels (optionnel, plus tard)

- **Amazon Appstore** (Fire TV) et **Google Play** (Android TV/Google TV) acceptent les apps TV,
  mais exigent une fiche, une politique de confidentialité et une revue. Le sideload via
  Downloader ne demande rien de tout cela — idéal pour diffuser tout de suite.
