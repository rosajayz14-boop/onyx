package ca.onyxtv.player.core.data

import android.content.Context
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.File

/** Bilan du chargement d'une source (affiché dans Réglages et dans les bandeaux d'erreur). */
@Serializable
data class SourceReport(
    val sourceId: String,
    val label: String,
    val type: String,             // "M3U" | "Xtream"
    val channels: Int = 0,
    val movies: Int = 0,
    val series: Int = 0,
    val error: String? = null,
    val durationMs: Long = 0,
    /** Succès PAR TYPE : un échec partiel (ex. films) conserve les données précédentes de ce type seulement. */
    val liveOk: Boolean = true,
    val vodOk: Boolean = true,
    val seriesOk: Boolean = true,
)

/** Instantané complet du catalogue, tel que mis en cache sur le disque. */
@Serializable
data class CatalogSnapshot(
    val channels: List<Channel> = emptyList(),
    val vod: List<VodItem> = emptyList(),
    val reports: List<SourceReport> = emptyList(),
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = channels.isEmpty() && vod.isEmpty()
    fun isStale(maxAgeMs: Long): Boolean = System.currentTimeMillis() - updatedAt > maxAgeMs
}

/**
 * Catalogue mémorisé sur le disque : chaînes, films et séries restent disponibles quand on
 * quitte l'application. Stocké dans l'espace PERMANENT de l'app (filesDir), jamais purgé par
 * le système contrairement au cache. L'app s'ouvre instantanément dessus, puis rafraîchit
 * en arrière-plan si les données sont anciennes.
 */
@OptIn(ExperimentalSerializationApi::class)
class CatalogCache(context: Context) {

    private val file = File(context.filesDir, "catalog.json")
    private val legacy = File(context.cacheDir, "catalog.json") // ancien emplacement (migré)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val saveMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun load(): CatalogSnapshot? = withContext(Dispatchers.IO) {
        if (!file.exists() && legacy.exists()) runCatching { legacy.copyTo(file, overwrite = true); legacy.delete() }
        if (!file.exists()) return@withContext null
        // Décodage en FLUX : un catalogue de 100 000 titres fait ~25 Mo ; en String ce serait
        // 50 Mo de plus sur un tas de 128-256 Mo (box TV).
        runCatching { file.inputStream().buffered().use { json.decodeFromStream(CatalogSnapshot.serializer(), it) } }.getOrNull()
    }

    suspend fun save(snapshot: CatalogSnapshot) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            runCatching {
                // Nom temporaire unique : le worker quotidien et l'app peuvent sauvegarder en même temps.
                val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + ".tmp")
                tmp.outputStream().buffered().use { json.encodeToStream(CatalogSnapshot.serializer(), snapshot, it) }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                tmp.delete()
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { runCatching { file.delete() } }
}
