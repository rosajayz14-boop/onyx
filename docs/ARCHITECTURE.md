# Architecture — ONYX TV

## Vue d'ensemble

ONYX suit une architecture simple **UI (Compose) → ViewModel → Repository → Sources**.
Une seule activité (`MainActivity`) héberge toute l'UI Compose ; la navigation entre
écrans est un simple état (`Dest`) dans `OnyxScaffold`.

```
        ┌─────────────────────────────────────────────┐
        │                 UI (Compose)                 │
        │  OnyxScaffold ─ Home / Live / VOD / Mosaic…  │
        └───────────────┬───────────────┬─────────────┘
                        │ collectAsState │ onPlay(PlayTarget)
                        ▼               ▼
                ┌───────────────┐   ┌──────────────┐
                │ OnyxViewModel │   │ PlayerScreen │  (ExoPlayer)
                └───────┬───────┘   └──────────────┘
                        │
                        ▼
                ┌───────────────┐
                │ OnyxRepository│  agrège toutes les sources
                └───┬───────┬───┘
        ┌───────────┘       └────────────┐
        ▼                                ▼
┌───────────────┐                ┌────────────────┐
│  M3uParser    │                │ XtreamClient   │  player_api.php
│  XmltvParser  │                │ (OkHttp+JSON)  │
└───────────────┘                └────────────────┘
        ▲
        │ persistance
┌───────────────┐
│ PlaylistStore │  DataStore (liste des sources en JSON)
└───────────────┘
```

## Couches

### 1. `core/model`
Modèles immuables partagés : `Channel`, `VodItem`, `EpgProgram`, `Category`, et
`PlaylistSource` (sealed : `M3u` ou `Xtream`, sérialisable pour DataStore).

### 2. `core` (sources)
- **`m3u/M3uParser`** — parse le texte M3U : `#EXTINF` + attributs `tvg-id`,
  `tvg-logo`, `group-title`, nom affiché, puis l'URL du flux.
- **`xtream/XtreamClient`** — appelle `player_api.php`
  (`get_live_streams`, `get_vod_streams`, `get_short_epg`…), construit les URLs
  de flux (`/live/user/pass/{id}.ts`, `/movie/...`) et décode l'EPG (Base64).
- **`epg/XmltvParser`** — parseur XMLTV (pull parser) pour les listes M3U.
- **`net/Http`** — un client OkHttp partagé (timeouts, User-Agent).

### 3. `core/data`
- **`PlaylistStore`** — persiste les sources via DataStore (JSON kotlinx).
- **`OnyxRepository`** — agrège toutes les sources en listes prêtes pour l'UI
  (`channels()`, `vod()`, `epg(channel)`), avec `runCatching` pour l'isolation des pannes.

### 4. `viewmodel/OnyxViewModel`
`AndroidViewModel` qui expose un `StateFlow<OnyxUiState>` (chargement, chaînes, VOD,
erreur) et la liste des sources. Actions : `refresh`, `addM3u`, `addXtream`,
`removeSource`, `epgFor`.

### 5. `ui`
- **`theme`** — palette Onyx (sombre/aurore), typographie, `OnyxTheme`.
- **`components`** — `MediaCard`, `Rail`, `Thumbnail`, `EmptyState` réutilisables.
- **écrans** — `HomeScreen`, `LiveTvScreen` (+ panneau EPG), `VodScreen`,
  `MosaicScreen`, `DvrScreen`, `SettingsScreen`, `SearchScreen`.
- **`OnyxScaffold`** — `NavigationDrawer` (rail latéral déployable) + routage +
  superposition du lecteur.

### 6. `player/PlayerScreen`
Enveloppe Media3/ExoPlayer dans un `AndroidView(PlayerView)`, gère le cycle de vie
(prepare/release) et le retour (`BackHandler`).

## Décisions clés
- **Compose for TV** plutôt que Leanback : moderne, focus D-pad géré par les composants.
- **Une seule activité** : navigation par état, pas de graphe complexe pour ce périmètre.
- **Sources découplées** : ajouter un nouveau type de source = un parseur + un cas dans
  `OnyxRepository` et `PlaylistSource`.
