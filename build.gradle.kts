// Fichier de build racine — ONYX TV
// Les plugins sont déclarés ici mais appliqués dans les modules (app).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
