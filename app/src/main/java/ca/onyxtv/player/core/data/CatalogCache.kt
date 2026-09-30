package ca.onyxtv.player.core.data

import android.content.Context
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
 * Cache disque du catalogue : l'app s'ouvre instantanément sur les données de la dernière
 * mise à jour, puis rafraîchit en arrière-plan si elles sont anciennes.
 */
class CatalogCache(context: Context) {

    private val file = File(context.cacheDir, "catalog.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun load(): CatalogSnapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(CatalogSnapshot.serializer(), file.readText()) }.getOrNull()
    }

    suspend fun save(snapshot: CatalogSnapshot) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CatalogSnapshot.serializer(), snapshot))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { runCatching { file.delete() } }
}
