# Feuille de route — ONYX TV

Ce fichier distingue ce qui est **livré** de ce qui reste **à faire**.

## Livré (v1.0)

### Socle
- Navigation télécommande (rail latéral déployable).
- Chaînes en direct depuis **M3U** et **Xtream Codes**, avec **noms de catégories** (plus d'IDs).
- Guide **EPG** now/next (Xtream `short_epg` + XMLTV pour M3U) et **grille TV plein écran**
  (style Helix / TiviMate : lignes de chaînes, colonnes horaires, blocs proportionnels, ligne
  « maintenant », fiche du programme, regarder / revoir / enregistrer jusqu'à la fin).
- **Lecteur** Media3/ExoPlayer plein écran (HLS/DASH/TS/MP4) : indicateur de chargement,
  écran d'erreur avec « Réessayer », **reprise** à la dernière position, **zapping** ↑/↓ et CH±.
- **Réglages** : ajout/suppression de sources, **test de connexion** avec message explicite,
  auto-correction de l'URL serveur (`http://`), persistance locale (DataStore).
- États **chargement / vide / erreur** sur tous les écrans (plus d'échec silencieux).

### Contenus
- **Films** : catégories, compteur, badge favori, progression de reprise.
- **Séries** : catalogue `get_series`, fiche `get_series_info` (saisons/épisodes, parsing
  tolérant), « Reprendre SxEy », lecture d'épisode avec reprise.
- **Recherche** : chaînes + films + séries (une série ouvre sa fiche).
- **Favoris** (chaînes, films, séries) et **récents** avec reprise ; rangées dédiées à l'accueil.
- **Recommandations** : heuristique locale (catégories aimées, notes, contenus déjà vus écartés).

### Fonctions avancées
- **Fiche film** (get_vod_info) : bande-annonce YouTube, synopsis, note ★, acteurs, réalisateur, genre, durée.
- **Mises à jour automatiques** : catalogue chaque jour en arrière-plan (WorkManager) ; vérification
  quotidienne d'une nouvelle version de l'app, téléchargement et installation depuis l'app.
- **Reprise à la sortie** : position sauvegardée quand on quitte (Accueil/veille), option de reprise
  automatique à l'ouverture.
- **Mosaïque** : 4 chaînes lues **simultanément** (un ExoPlayer par tuile), son sur la tuile
  focalisée, OK = plein écran, repli visuel si un flux échoue.
- **DVR** : service de premier plan capturant le flux vers le stockage privé de l'app,
  liste persistée, lecture / arrêt / suppression, clôture des enregistrements interrompus.
- **Rattrapage (catch-up)** : détection `tv_archive` Xtream, URL timeshift, « ↺ Revoir ».
- **Contrôle parental** : PIN 4 chiffres, catégories verrouillées (masquées partout, PIN
  demandé dans TV en direct), verrouillage de l'application au démarrage.

## À faire (prochaines itérations)

### Priorité haute
- **Programmation d'enregistrement** depuis l'EPG (démarrage/arrêt à l'heure du programme).
- **Tests unitaires** : `M3uParser`, `XtreamClient` (dont parsing des séries), `XmltvParser`.

### Priorité moyenne
- **Chaînes Live TV Channels** (rangée système Android TV / Google TV).
- **Multi-listes** : fusion/étiquetage, tri par numéro (LCN), groupes repliables.
- **Réglages réseau** : buffer configurable, user-agent, `network security config`.
- **Profils** utilisateurs (favoris/récents séparés).

### Confort / finition
- **Marque** : police téléchargeable (ex. Sora) via `androidx.compose.ui.text.googlefonts` ;
  bannière/icône PNG haute définition ; écran de démarrage animé.
- **Sous-titres / pistes audio** : sélecteur dans le lecteur.
- **Synchronisation multi-appareils** du profil via un petit backend.

## Idées différenciantes
- Aperçu vidéo au survol dans le guide.
- Mode « une seule télécommande » optimisé (chiffres → numéro de chaîne).
