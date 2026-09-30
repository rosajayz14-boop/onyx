# Guide — installer ONYX TV via Downloader (code aftv.news)

Ce guide explique comment diffuser l'APK et l'installer sur un téléviseur avec l'application
**Downloader** (éditeur AFTVnews), y compris la création du **code court** `aftv.news`.

---

## L'adresse de l'APK

URL fixe (toujours la dernière version) :

```
https://github.com/rosajayz14-boop/onyx/releases/download/tv-latest/onyx-tv.apk
```

Version figée (exemple après un tag `v1.0.0`) :

```
https://github.com/rosajayz14-boop/onyx/releases/download/v1.0.0/onyx-tv-1.0.0.apk
```

---

## Étape 1 — Préparer le téléviseur (sources inconnues)

**Fire TV / Fire TV Stick**
1. *Paramètres → My Fire TV → Options du développeur*.
2. Activez **Applications de sources inconnues** (ou **Installer des applications inconnues → Downloader = Activé**).
3. Si *Options du développeur* est masqué : *My Fire TV → À propos → Fire TV Stick* et cliquez 7 fois.

**Google TV / Android TV (Chromecast, Nvidia Shield, box, TV Sony/TCL/Philips…)**
1. *Paramètres → Système → À propos*, descendez sur **Version/Build** et cliquez **7 fois**
   (« Vous êtes maintenant développeur »).
2. *Paramètres → Système → Options pour les développeurs* → activez si besoin.
3. Lors de l'installation, la TV proposera d'autoriser **Downloader** à installer des apps
   inconnues → **Autoriser**.

---

## Étape 2 — Installer Downloader

Sur l'écran d'accueil, cherchez **Downloader** dans le store (Amazon Appstore / Google Play TV)
et installez-le. Icône orange avec une flèche blanche.

---

## Étape 3a — Méthode simple : coller l'URL

1. Ouvrez **Downloader** → onglet **Home / Accueil**.
2. Dans le champ **URL**, tapez l'adresse de l'APK (section du haut) puis **GO / Aller**.
3. Downloader télécharge le fichier, puis affiche l'écran d'installation Android → **Installer**.
4. À la fin : **Terminé** (gardez l'APK pour réinstaller) ou **Supprimer** (libère l'espace).

> Astuce : pour éviter de taper une longue URL à la télécommande, utilisez le **code court** ci-dessous.

---

## Étape 3b — Méthode recommandée : le **code court** `aftv.news`

Le « code » Downloader est un raccourci numérique fourni **gratuitement** par AFTVnews.
Il transforme la longue URL en un **code à 6 chiffres** facile à saisir et à partager.

> ⚠️ Cette création se fait sur le **site web d'AFTVnews** (protégé contre les robots) :
> elle ne peut pas être automatisée, mais elle prend 30 secondes.

1. Sur un **ordinateur ou un téléphone**, ouvrez **https://aftv.news**
   (page « **Create a Downloader Code** » / « Créer un code Downloader »).
2. Dans **Enter a URL**, collez l'URL fixe de l'APK :
   `https://github.com/rosajayz14-boop/onyx/releases/download/tv-latest/onyx-tv.apk`
3. (Optionnel) donnez un titre, ex. « ONYX TV ».
4. Cliquez **Create Code / Créer**. Le site affiche un **code**, par ex. `123456`,
   associé à l'adresse `aftv.news/123456`.
5. Sur la TV, dans **Downloader**, tapez **seulement le code** (`123456`) dans le champ URL → **GO**.
   Downloader résout le code, télécharge l'APK et propose **Installer**.

### Vérifier que le code fonctionne AVANT de toucher à la TV

Ouvrez dans le navigateur d'un PC/téléphone : `https://aftv.news/VOTRECODE`
(ex. `https://aftv.news/123456`). Le navigateur doit **rediriger et lancer le
téléchargement de `onyx-tv.apk`**. Si oui, le code marchera dans Downloader. Sinon,
voir la checklist ci-dessous.

### Pourquoi un code « ne marche pas » — checklist

Comment Downloader résout un code : quand vous tapez un nombre seul, il va chercher
`https://aftv.news/<nombre>` qui **redirige** vers l'URL longue que vous avez enregistrée.
Donc si le code échoue, c'est presque toujours l'un de ces points :

1. **Vous avez tapé autre chose que les chiffres.** Dans Downloader, saisissez UNIQUEMENT
   le nombre (`123456`) — pas `http://`, pas `aftv.news/…`, pas d'espace.
2. **Le code pointe vers la mauvaise URL.** Il doit contenir le lien du **fichier .apk**
   (`…/releases/download/tv-latest/onyx-tv.apk`), **pas** la page « Releases ». Recréez le
   code en recollant exactement cette URL.
3. **Faute de frappe à la création.** Copiez-collez l'URL, ne la tapez pas à la main.
4. **Le dépôt GitHub est passé en privé.** Un lien privé renvoie 404 pour Downloader :
   le dépôt doit rester **public**.
5. **Sources inconnues non autorisées** (Étape 1) : le téléchargement marche mais
   l'installation est refusée. Ce n'est pas un problème de code.
6. **aftv.news momentanément indisponible / code très récent** : réessayez après 1–2 min.

### Alternative sans aftv.news (raccourci « maison »)

Downloader suit les redirections HTTP. N'importe quel raccourcisseur qui fait une
redirection 301/302 vers l'URL de l'APK fonctionne donc aussi (ex. **is.gd**, **tinyurl**).
Vous tapez alors l'URL courte complète (ex. `is.gd/onyxtv`) dans Downloader. Seul le
**code purement numérique** est spécifique à aftv.news.

**Bon à savoir**
- Comme l'URL `tv-latest` pointe toujours vers la dernière version, **le même code
  ressert pour toutes les futures mises à jour** : inutile d'en recréer un.
- Vous pouvez aussi créer un code pour une **version figée** (URL `v1.0.0…`) si vous
  voulez distribuer une version précise qui ne bougera pas.

---

## Étape 4 — Lancer l'app

ONYX apparaît dans la rangée **« Vos applications »** de l'écran d'accueil TV
(catégorie `LEANBACK_LAUNCHER`). Au premier lancement :
**Réglages → Ajouter une liste M3U** ou **Ajouter un compte Xtream**.

---

## Mettre à jour plus tard

- Poussez du code (ou lancez *Actions → Run workflow*) : `tv-latest` est recompilée.
- Sur la TV, ressaisissez le **même code / la même URL** dans Downloader et réinstallez.
- Si une **clé de signature stable** est configurée (voir `DISTRIBUTION.md` §5), la mise à
  jour s'installe par-dessus ; sinon, désinstallez l'ancienne version d'abord.

---

## Problèmes fréquents

| Symptôme | Cause / solution |
|---|---|
| « Analyse du paquet » / installation bloquée | Sources inconnues non autorisées pour Downloader (Étape 1). |
| « Application non installée » | Une version signée avec une **autre clé** est déjà installée → désinstallez-la d'abord. |
| Downloader dit « impossible de télécharger » | Vérifiez que le dépôt GitHub est **public** et l'URL exacte. |
| L'app n'apparaît pas sur l'accueil | Cherchez-la dans *Paramètres → Applications* ; sur Google TV elle est aussi dans « Applications ». |
| Un flux ne se lit pas | Compte Xtream : essayez l'extension `m3u8` (HLS) au lieu de `ts` (voir `BUILD.md`). |
