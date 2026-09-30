# Mises à jour à distance — ONYX TV

L'application se met à jour **toute seule, à distance**, sans repasser par Downloader :

1. Vous poussez un changement (ou modifiez `VERSION`) → la CI compile et publie `tv-latest`.
2. Chaque app installée vérifie `tv-latest` **une fois par jour** à l'ouverture.
3. Si une nouvelle version existe → fenêtre **« Nouvelle version d'ONYX TV — Installer maintenant / Plus tard »**.
4. L'APK est téléchargé dans l'app puis installé par Android (comptes, favoris, réglages conservés).

## Condition indispensable : une clé de signature FIXE

Android n'installe une mise à jour **que si elle est signée avec la même clé** que la version
déjà installée. Sans clé fixe, chaque build CI utilise une clé de debug différente et la mise à
jour est refusée (« Application non installée »).

### Configuration (à faire UNE fois, 5 minutes)

1. Récupérez la clé générée (`onyx-release.jks`), le fichier `ONYX_KEYSTORE_BASE64.txt` et le
   mot de passe (fichier `A-LIRE-secrets-GitHub.txt`). **Sauvegardez-les** hors du dépôt : perdre
   la clé = impossible de mettre à jour les apps déjà installées.
2. GitHub → `https://github.com/rosajayz14-boop/onyx/settings/secrets/actions` → **New repository secret**, 4 fois :

   | Nom | Valeur |
   |---|---|
   | `ONYX_KEYSTORE_BASE64` | contenu complet de `ONYX_KEYSTORE_BASE64.txt` (une ligne) |
   | `ONYX_STORE_PW` | le mot de passe |
   | `ONYX_KEY_ALIAS` | `onyx` |
   | `ONYX_KEY_PW` | le mot de passe |

3. Relancez un build (push ou **Actions → Run workflow**). Le journal indique
   « Clé de release détectée : signature stable ».

### Transition pour les apps déjà installées (signature debug)

Les apps installées **avant** la clé fixe sont signées avec une clé de debug : la première
version signée avec la clé fixe ne pourra pas s'installer par-dessus. **Une seule fois**, ces
utilisateurs doivent désinstaller ONYX puis réinstaller via Downloader (même code). Ensuite,
toutes les mises à jour futures se feront à distance, automatiquement.

## Publier une mise à jour

- Correctif / nouveauté : poussez sur la branche → `tv-latest` mis à jour → les apps proposent
  la mise à jour dans les 24 h (ou immédiatement via Réglages → Application → Vérifier).
- Version figée : changez `VERSION` (ex. `1.1.0`) → Release `v1.1.0` créée en plus.

## Ce que voit l'utilisateur

- Bandeau « ✨ Nouvelle version disponible » sur l'accueil + fenêtre à l'ouverture.
- Réglages → Application : version installée, « Vérifier les mises à jour », « Télécharger et installer ».
- Première installation par l'app : Android demande d'autoriser ONYX TV à installer des applications
  (l'app ouvre directement le bon réglage).
