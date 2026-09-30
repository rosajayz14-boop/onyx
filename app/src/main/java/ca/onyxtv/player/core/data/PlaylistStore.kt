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

    suspend fun add(source: PlaylistSource) {
        val current = sources.first()
        save(current.filterNot { it.id == source.id } + source)
    }

    suspend fun remove(id: String) {
        save(sources.first().filterNot { it.id == id })
    }
}
