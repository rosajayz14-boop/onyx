# ONYX TV

Lecteur multimédia **original** pour Android TV, Fire TV et Google TV Streamer 4K.
Écrit en Kotlin + Jetpack Compose for TV + Media3 (ExoPlayer). Aucune dépendance à
une autre application : ONYX s'inspire des standards du marché (guide EPG, favoris,
multi-vue, VOD) mais tout le code, le design et la marque sont à vous.

> ⚖️ **Note importante.** ONYX est un *lecteur* neutre, au même titre que VLC ou Kodi.
> L'application ne fournit **aucun contenu** : l'utilisateur branche ses propres sources
> **légales** (liste M3U ou compte Xtream Codes fourni par son abonnement). Assurez-vous
> de ne diffuser que des flux dont vous détenez les droits.

---

## Fonctionnalités

| Domaine | État | Détails |
|---|---|---|
| Chaînes en direct | ✅ | M3U/M3U8 + Xtream Codes, **catégories** (noms), compteurs, ★ favoris |
| Guide EPG (now/next) | ✅ | Xtream `short_epg` + XMLTV pour M3U |
| **Rattrapage (catch-up)** | ✅ | « ↺ Revoir » sur les programmes passés des chaînes archivées (timeshift Xtream) |
| Films | ✅ | Catalogue Xtream, filtre par catégorie, reprise, favoris |
| **Séries** | ✅ | Saisons / épisodes (`get_series_info`), fiche, « Reprendre SxEy » |
| Lecteur plein écran | ✅ | Media3/ExoPlayer — HLS, DASH, TS, MP4 ; **erreurs + Réessayer**, chargement, **zapping ↑↓ / CH±** |
| **Favoris / Récents / Reprise** | ✅ | Rangées « Reprendre » (barre de progression) et « Mes favoris » sur l'accueil |
| Recherche | ✅ | Chaînes + films + séries |
| Mosaïque multi-écran | ✅ | **4 chaînes lues simultanément** (2×2), son sur la tuile focalisée, OK = plein écran |
| Enregistrements / DVR | ✅ | Service de premier plan → fichiers locaux ; lire / arrêter / supprimer |
| Réglages | ✅ | Sources M3U / Xtream avec **test de connexion** explicite, auto-correction `http://` |
| **Contrôle parental** | ✅ | PIN 4 chiffres, catégories verrouillées, verrouillage au démarrage |
| Recommandations | ✅ | Heuristique locale (catégories des favoris / récents, notes) |
| Navigation télécommande | ✅ | Rail latéral déployable (façon Google TV) |

Distribution : voir `docs/DISTRIBUTION.md` (APK auto-compilé, URL fixe) et `docs/DOWNLOADER.md`
(installation via Downloader / code aftv.news).

---

## Pile technique

- **Langage** : Kotlin 2.1
- **UI** : Jetpack Compose + `androidx.tv:tv-material` (Compose for TV)
- **Lecture** : `androidx.media3` (ExoPlayer, HLS, DASH)
- **Réseau** : OkHttp + kotlinx.serialization (JSON Xtream)
- **Stockage** : DataStore (sources & préférences)
- **Images** : Coil
- **min SDK** : 21 (compatible Fire TV / anciens boîtiers) · **target/compile SDK** : 35

---

## Démarrage rapide

```bash
# Ouvrir le dossier dans Android Studio (Ladybug ou plus récent), puis :
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Installation sur un appareil TV (voir `docs/BUILD.md` pour le détail) :

```bash
adb connect <IP_DU_TELEVISEUR>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Au premier lancement : **Réglages → Ajouter une liste M3U** ou **Ajouter un compte Xtream**.

---

## Structure du projet

```
app/src/main/java/ca/onyxtv/player/
├── OnyxApp.kt              Application
├── MainActivity.kt         Activité unique (Compose)
├── core/
│   ├── model/              Modèles (Channel, VodItem, EpgProgram, PlaylistSource)
│   ├── m3u/                Parseur M3U/M3U8
│   ├── xtream/             Client Xtream Codes (player_api.php)
│   ├── epg/                Parseur XMLTV
│   ├── net/                Client HTTP (OkHttp)
│   └── data/               DataStore + dépôt agrégateur (OnyxRepository)
├── player/                 Lecteur ExoPlayer plein écran
├── viewmodel/              OnyxViewModel (état global)
└── ui/
    ├── theme/              Palette, typo, thème
    ├── components/         Cartes, rails, vignettes réutilisables
    ├── home/ live/ vod/ mosaic/ dvr/ settings/ search/
    └── OnyxScaffold.kt     Navigation (rail latéral) + routage des écrans
```

Voir `docs/ARCHITECTURE.md` pour le détail et `docs/ROADMAP.md` pour la suite.
