plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Clé de signature de release OPTIONNELLE. Aucune clé n'est stockée dans le dépôt.
// - Sans clé fournie : l'APK release est signé avec la clé de debug (installe et
//   fonctionne parfaitement en sideload / Downloader ; les mises à jour peuvent
//   demander une désinstallation car la clé de debug n'est pas stable).
// - Avec une clé fournie via variables d'environnement (idéalement un secret CI
//   décodé au build) : signature stable, les mises à jour s'installent par-dessus.
//     ONYX_STORE_FILE, ONYX_STORE_PW, ONYX_KEY_ALIAS, ONYX_KEY_PW
val onyxStoreFile: String? = System.getenv("ONYX_STORE_FILE")
val hasReleaseKeystore = onyxStoreFile != null && file(onyxStoreFile).exists()

android {
    namespace = "ca.onyxtv.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.onyxtv.player"
        minSdk = 21          // Android TV / Fire TV (Leanback) — base compatible
        targetSdk = 35
        // Version : surchargée par la CI depuis le tag Git (ONYX_VERSION_NAME) et le
        // numéro de build (ONYX_VERSION_CODE). Valeurs par défaut pour un build local.
        versionCode = System.getenv("ONYX_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ONYX_VERSION_NAME") ?: "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // Signature de release, uniquement si une clé est fournie (voir en-tête du fichier).
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(onyxStoreFile!!)
                storePassword = System.getenv("ONYX_STORE_PW")
                keyAlias = System.getenv("ONYX_KEY_ALIAS")
                keyPassword = System.getenv("ONYX_KEY_PW")
            }
        }
    }

    buildTypes {
        release {
            // Minification désactivée pour garantir un APK de sideload qui fonctionne
            // sans surprise R8 (sérialisation Xtream, OkHttp, Media3). À réactiver avec
            // des règles ProGuard validées si vous distribuez une version « store ».
            isMinifyEnabled = false
            isShrinkResources = false
            // Clé de release si fournie, sinon repli sur la clé de debug pour que
            // l'APK reste installable sans configuration.
            signingConfig = if (hasReleaseKeystore)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose (BOM aligne toutes les versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3) // OutlinedTextField pour la saisie (Réglages/Recherche)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Compose for TV (composants adaptés à la télécommande)
    implementation(libs.androidx.tv.material)

    // Lecture vidéo : Media3 / ExoPlayer (HLS + DASH + progressif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    // Stockage local des listes et préférences
    implementation(libs.androidx.datastore.preferences)

    // Réseau (Xtream/M3U/EPG), images, sérialisation JSON
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
}
