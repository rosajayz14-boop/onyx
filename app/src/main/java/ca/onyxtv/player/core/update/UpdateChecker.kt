package ca.onyxtv.player.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ca.onyxtv.player.BuildConfig
import ca.onyxtv.player.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Nouvelle version disponible sur la Release « tv-latest ». */
data class UpdateInfo(
    val label: String,       // ex. « ONYX TV — dernière version (1.0.1) »
    val commit: String,      // SHA du build publié
    val publishedAt: String, // date ISO de l'APK
    val sizeBytes: Long,
)

/**
 * Vérification et installation des mises à jour de l'application depuis la Release GitHub.
 * Compare le commit du build courant (BuildConfig.GIT_SHA, injecté par la CI) avec celui
 * indiqué dans la note de la Release « tv-latest ».
 */
object UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }
    private val commitRegex = Regex("commit ([0-9a-f]{7,40})")

    val currentCommit: String get() = BuildConfig.GIT_SHA
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Renvoie la mise à jour disponible, ou null si l'app est à jour (ou build local). */
    suspend fun check(): UpdateInfo? {
        if (currentCommit == "dev") return null
        val body = Http.get(BuildConfig.UPDATE_API_URL)
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val notes = (root["body"] as? JsonPrimitive)?.content.orEmpty()
        val commit = commitRegex.find(notes)?.groupValues?.get(1) ?: return null
        if (commit.startsWith(currentCommit) || currentCommit.startsWith(commit)) return null
        // Ne proposer QUE les builds plus récents (le titre contient « build N », N = versionCode) :
        // sinon une publication plus ancienne serait proposée comme « mise à jour » (rétrogradation).
        val name = (root["name"] as? JsonPrimitive)?.content.orEmpty()
        val remoteBuild = Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
        if (remoteBuild != null && remoteBuild <= BuildConfig.VERSION_CODE) return null
        val asset = (root["assets"] as? JsonArray)?.firstOrNull() as? JsonObject
        return UpdateInfo(
            label = (root["name"] as? JsonPrimitive)?.content ?: "Nouvelle version",
            commit = commit,
            publishedAt = (asset?.get("updated_at") as? JsonPrimitive)?.content.orEmpty(),
            sizeBytes = (asset?.get("size") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
        )
    }

    /** Télécharge l'APK dans le cache privé ; [onProgress] reçoit 0f..1f. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, "onyx-update.apk")
        val request = Request.Builder().url(BuildConfig.UPDATE_APK_URL).header("User-Agent", "ONYX-TV/1.0 (Android TV)").build()
        Http.bulk.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Réponse vide")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** L'installation d'APK par l'app doit être autorisée (Android 8+). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Ouvre le réglage système « Installer des applications inconnues » pour ONYX. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(i) }
        }
    }

    /**
     * L'APK téléchargé est-il signé avec la même clé que l'app installée ? Sinon Android refuse
     * l'installation (« Application non installée ») sans explication : on prévient avant.
     * null = impossible à déterminer (on laisse l'installateur trancher).
     */
    @Suppress("DEPRECATION")
    fun sameSigner(context: Context, file: File): Boolean? = runCatching {
        val pm = context.packageManager
        fun sigs(info: android.content.pm.PackageInfo?): Set<String>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else info?.signatures?.map { it.toCharsString() }?.toSet()
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                   else android.content.pm.PackageManager.GET_SIGNATURES
        val a = sigs(pm.getPackageArchiveInfo(file.absolutePath, flag)) ?: return@runCatching null
        val b = sigs(pm.getPackageInfo(context.packageName, flag)) ?: return@runCatching null
        a == b
    }.getOrNull()

    /** Lance l'installateur système sur l'APK téléchargé. */
    fun install(context: Context, file: File) {
        val i = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            i.setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            // Avant Android 7 (Fire OS 5), l'installateur n'accepte que file:// — copie dans un dossier lisible.
            val pub = File(context.externalCacheDir ?: context.cacheDir, "onyx-update.apk")
            file.copyTo(pub, overwrite = true)
            pub.setReadable(true, false)
            i.setDataAndType(Uri.fromFile(pub), "application/vnd.android.package-archive")
        }
        context.startActivity(i)
    }
}
