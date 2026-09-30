# Feuille de route — ONYX TV

Ce fichier distingue ce qui est **livré** de ce qui reste à **brancher**. Le socle
(lecture, sources M3U/Xtream, EPG, navigation TV) est fonctionnel ; les points ci-dessous
sont les prochaines étapes pour dépasser les lecteurs existants.

## Livré (v0.1)
- Navigation télécommande (rail latéral déployable).
- Chaînes en direct depuis **M3U** et **Xtream Codes**.
- Guide **EPG** now/next (Xtream `short_epg` + XMLTV pour M3U).
- **VOD** (films/séries Xtream) en grille d'affiches.
- **Recherche** chaînes + contenus.
- **Lecteur** Media3/ExoPlayer plein écran (HLS/DASH/TS/MP4).
- **Réglages** : ajout/suppression de sources, persistance locale (DataStore).
- Écrans **Mosaïque** et **DVR** (structure + point d'entrée).

## À brancher (prochaines itérations)

### Priorité haute
- **Favoris & récents** : table locale (Room) + rangées « Reprendre » / « Favoris ».
- **Reprise de lecture** : mémoriser la position par contenu.
- **Séries** : épisodes/saisons Xtream (`get_series_info`) + navigation dédiée.
- **EPG plein écran** : grille temporelle complète (au-delà du now/next).

### Priorité moyenne
- **Mosaïque réelle** : un `ExoPlayer` par tuile (2×2), gestion mémoire/décodeurs,
  bascule audio sur la tuile focalisée.
- **DVR** : service de fond capturant le flux vers le stockage + gestion de l'espace ;
  intégration catch-up des fournisseurs.
- **Recommandations « IA »** : reco basée sur l'historique (heuristique locale, puis
  modèle si souhaité).
- **Contrôle parental** : PIN, profils avec restrictions (déjà prévu dans la maquette).

### Confort / finition
- **Marque** : intégrer une police téléchargeable (ex. Sora) via
  `androidx.compose.ui.text.googlefonts` ; bannière/icône PNG haute définition.
- **Chaînes Live TV Channels** (intégration à la rangée système Android TV).
- **Multi-listes** : fusion/étiquetage, tri par numéro (LCN), groupes repliables.
- **Réglages réseau** : buffer configurable, user-agent, `network security config`.
- **Tests** : unitaires pour `M3uParser` / `XtreamClient` / `XmltvParser`.

## Idées différenciantes (inspirées du marché, à notre sauce)
- Aperçu vidéo au survol dans le guide.
- « Zapping » latéral pendant la lecture (déjà maquetté).
- Mode « une seule télécommande » optimisé (raccourcis chiffres → numéro de chaîne).
- Synchronisation multi-appareils du profil (favoris, reprise) via un petit backend.
