package ca.onyxtv.player.dvr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dvrDataStore by preferencesDataStore(name = "onyx_dvr")

/** État d'un enregistrement. */
enum class RecordingStatus { RECORDING, DONE, STOPPED, FAILED }

/** Un enregistrement (en cours ou terminé) stocké dans le dossier privé de l'app. */
@Serializable
data class RecordingInfo(
    val id: String,
    val channelId: String,
    val channelName: String,
    val filePath: String,
    val startedAt: Long,
    val plannedMinutes: Int,
    val endedAt: Long? = null,
    val sizeBytes: Long = 0,
    val status: RecordingStatus = RecordingStatus.RECORDING,
    val error: String? = null,
) {
    val durationMs: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
}

/** Persistance de la liste des enregistrements (DataStore), partagée entre le service et l'UI. */
class RecordingStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("recordings_json")
    private val serializer = ListSerializer(RecordingInfo.serializer())

    val recordings: Flow<List<RecordingInfo>> = context.dvrDataStore.data.map { prefs ->
        val raw = prefs[key] ?: "[]"
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
            .sortedByDescending { it.startedAt }
    }

    // Toutes les écritures lisent la liste DANS la transaction edit{} : deux enregistrements
    // parallèles (progression toutes les 5 s) n'écrasent plus l'état final l'un de l'autre.
    private suspend fun modify(transform: (List<RecordingInfo>) -> List<RecordingInfo>) {
        context.dvrDataStore.edit { p ->
            val current = runCatching { p[key]?.let { json.decodeFromString(serializer, it) } }.getOrNull().orEmpty()
            p[key] = json.encodeToString(serializer, transform(current))
        }
    }

    suspend fun upsert(info: RecordingInfo) = modify { cur -> cur.filterNot { it.id == info.id } + info }

    suspend fun update(id: String, transform: (RecordingInfo) -> RecordingInfo) =
        modify { cur -> cur.map { if (it.id == id) transform(it) else it } }

    /** Supprime l'entrée et le fichier associé. */
    suspend fun delete(id: String) {
        recordings.first().firstOrNull { it.id == id }?.let { runCatching { File(it.filePath).delete() } }
        modify { cur -> cur.filterNot { it.id == id } }
    }

    /** Au démarrage : tout ce qui est encore marqué RECORDING sans service actif est un arrêt brutal. */
    suspend fun markInterrupted() = modify { cur ->
        cur.map {
            if (it.status == RecordingStatus.RECORDING)
                it.copy(status = RecordingStatus.STOPPED, endedAt = it.endedAt ?: System.currentTimeMillis())
            else it
        }
    }

    companion object {
        fun recordingsDir(context: Context): File =
            (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).apply { mkdirs() }
    }
}
