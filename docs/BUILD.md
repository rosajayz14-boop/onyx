# Compilation & installation — ONYX TV

## Prérequis
- **Android Studio** (Ladybug 2024.2 ou plus récent)
- **JDK 17** (fourni avec Android Studio)
- **SDK Android 35** (installé via le SDK Manager)
- Un appareil : Android TV, Fire TV, Google TV Streamer, ou l'émulateur **Android TV (1080p)**

## Ouvrir le projet
1. Android Studio → *Open* → choisir le dossier `onyx-tv-androidtv/`.
2. Laisser Gradle synchroniser. Si une version (AGP/Kotlin/bibliothèque) est signalée,
   accepter la mise à niveau proposée ou ajuster `gradle/libs.versions.toml`.
   > Le wrapper Gradle est configuré pour la 8.11.1 ; si `gradlew` est absent, exécuter
   > `gradle wrapper` une fois, ou lancer les tâches depuis Android Studio.

## Compiler
```bash
# APK de debug
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# APK de release (nécessite une signature, voir plus bas)
./gradlew assembleRelease
```

## Installer sur un téléviseur / boîtier

### Activer le débogage
Sur l'appareil : *Paramètres → Système → À propos → Numéro de build* (7 clics) pour
activer le mode développeur, puis *Options pour développeurs → Débogage USB / ADB*.

### Installer par le réseau
```bash
adb connect <IP_DE_L_APPAREIL>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
L'app apparaît dans la rangée « Vos applications » de l'écran d'accueil
(catégorie `LEANBACK_LAUNCHER`).

### Fire TV
Même principe : activer *Applications de sources inconnues* + *Débogage ADB*
dans *My Fire TV → Options du développeur*, puis `adb connect` / `adb install`.

## Signature (release)
Créer un keystore puis configurer `signingConfigs` dans `app/build.gradle.kts` :
```bash
keytool -genkey -v -keystore onyx.keystore -alias onyx -keyalg RSA -keysize 2048 -validity 10000
```
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../onyx.keystore")
            storePassword = System.getenv("ONYX_STORE_PW")
            keyAlias = "onyx"
            keyPassword = System.getenv("ONYX_KEY_PW")
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
}
```

## Dépannage
- **Un flux ne se lit pas** : essayer `liveExtension = "m3u8"` (HLS) au lieu de `"ts"`
  pour les comptes Xtream (`PlaylistSource.Xtream`).
- **Trafic HTTP bloqué** : `usesCleartextTraffic="true"` est déjà activé pour les
  serveurs en `http://`. Pour restreindre, définir une *network security config*.
- **Versions de bibliothèques** : l'écosystème Android bouge vite ; en cas d'erreur de
  résolution, ajuster les versions dans `gradle/libs.versions.toml`.
