package ca.onyxtv.player.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ca.onyxtv.player.core.model.PlaylistSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "onyx_playlists")

/** Persiste les sources (M3U / Xtream) configurées par l'utilisateur via DataStore. */
class PlaylistStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val key = stringPreferencesKey("sources_json")
    private val serializer = ListSerializer(PlaylistSource.serializer())

    val sources: Flow<List<PlaylistSource>> = context.dataStore.data.map { prefs ->
        val raw = prefs[key] ?: "[]"
        runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    suspend fun save(list: List<PlaylistSource>) {
        val raw = json.encodeToString(serializer, list)
        context.dataStore.edit { it[key] = raw }
    }

    // Lecture-modification-écriture DANS la transaction (deux ajouts rapprochés ne s'écrasent plus).
    private suspend fun modify(transform: (List<PlaylistSource>) -> List<PlaylistSource>) {
        context.dataStore.edit { p ->
            val current = runCatching { p[key]?.let { json.decodeFromString(serializer, it) } }.getOrNull().orEmpty()
            p[key] = json.encodeToString(serializer, transform(current))
        }
    }

    suspend fun add(source: PlaylistSource) = modify { cur -> cur.filterNot { it.id == source.id } + source }

    /** Modifie une source en place (ex. format des flux live). */
    suspend fun update(id: String, transform: (PlaylistSource) -> PlaylistSource) =
        modify { cur -> cur.map { if (it.id == id) transform(it) else it } }

    suspend fun remove(id: String) = modify { cur -> cur.filterNot { it.id == id } }
}
