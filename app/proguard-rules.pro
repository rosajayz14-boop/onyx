# Règles ProGuard/R8 — ONYX TV

# kotlinx.serialization : conserver les sérialiseurs générés
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ca.onyxtv.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ca.onyxtv.player.**$$serializer { *; }

# Media3 / ExoPlayer conserve ses propres règles via consumer rules.
