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

    suspend fun upsert(info: RecordingInfo) {
        val next = recordings.first().filterNot { it.id == info.id } + info
        context.dvrDataStore.edit { it[key] = json.encodeToString(serializer, next) }
    }

    suspend fun update(id: String, transform: (RecordingInfo) -> RecordingInfo) {
        val current = recordings.first()
        val target = current.firstOrNull { it.id == id } ?: return
        upsert(transform(target))
    }

    /** Supprime l'entrée et le fichier associé. */
    suspend fun delete(id: String) {
        val current = recordings.first()
        current.firstOrNull { it.id == id }?.let { runCatching { File(it.filePath).delete() } }
        val next = current.filterNot { it.id == id }
        context.dvrDataStore.edit { it[key] = json.encodeToString(serializer, next) }
    }

    /** Au démarrage : tout ce qui est encore marqué RECORDING sans service actif est un arrêt brutal. */
    suspend fun markInterrupted() {
        val current = recordings.first()
        if (current.none { it.status == RecordingStatus.RECORDING }) return
        val next = current.map {
            if (it.status == RecordingStatus.RECORDING)
                it.copy(status = RecordingStatus.STOPPED, endedAt = it.endedAt ?: System.currentTimeMillis())
            else it
        }
        context.dvrDataStore.edit { it[key] = json.encodeToString(serializer, next) }
    }

    companion object {
        fun recordingsDir(context: Context): File =
            (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).apply { mkdirs() }
    }
}
