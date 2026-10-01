package ca.onyxtv.player.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.userDataStore by preferencesDataStore(name = "onyx_user")

/** Élément « récent » : dernier contenu regardé, avec position pour la reprise. */
@Serializable
data class RecentItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val imageUrl: String? = null,
    val live: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Progression 0f..1f (0 si inconnue ou direct). */
    val progress: Float
        get() = if (live || durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Reprise pertinente : on a avancé mais pas fini. */
    val resumable: Boolean get() = !live && durationMs > 0 && progress in 0.02f..0.95f

    /** Contenu vu jusqu'au bout (ou presque). */
    val finished: Boolean get() = !live && durationMs > 0 && progress > 0.95f
}

/** Contrôle parental : PIN à 4 chiffres, catégories verrouillées, verrouillage au démarrage. */
@Serializable
data class ParentalSettings(
    val pin: String? = null,
    val lockedGroups: Set<String> = emptySet(),
    val lockAtStart: Boolean = false,
) {
    val enabled: Boolean get() = !pin.isNullOrBlank()
}

/** Préférences générales de l'application. */
@Serializable
data class AppPrefs(
    /** Relance automatiquement la dernière lecture à l'ouverture de l'app. */
    val resumeOnStart: Boolean = false,
    /** Dernière vérification de mise à jour de l'application (epoch ms). */
    val lastUpdateCheck: Long = 0,
    /** Pas de recul / d'avance avec ◀ / ▶ (secondes). */
    val seekBackSeconds: Int = 10,
    val seekForwardSeconds: Int = 30,
    /** Affiche un cadre de diagnostic (touches reçues, focus) pour le dépannage. */
    val diagnostics: Boolean = false,
    /** Commit de la mise à jour écartée par « Plus tard » (l'écran plein ne revient pas pour ce build). */
    val dismissedUpdateCommit: String = "",
)

/**
 * Données utilisateur locales : favoris (ids de chaînes/contenus), récents (reprise),
 * contrôle parental et préférences. Persistées via DataStore, comme les sources.
 */
class UserStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val favKey = stringPreferencesKey("favorites_json")
    private val recentKey = stringPreferencesKey("recents_json")
    private val parentalKey = stringPreferencesKey("parental_json")
    private val prefsKey = stringPreferencesKey("prefs_json")

    val prefs: Flow<AppPrefs> = context.userDataStore.data.map { prefs ->
        val raw = prefs[prefsKey] ?: return@map AppPrefs()
        runCatching { json.decodeFromString(AppPrefs.serializer(), raw) }.getOrDefault(AppPrefs())
    }

    suspend fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        val next = transform(prefs.first())
        context.userDataStore.edit { it[prefsKey] = json.encodeToString(AppPrefs.serializer(), next) }
    }
    private val favSer = SetSerializer(String.serializer())
    private val recentSer = ListSerializer(RecentItem.serializer())

    val parental: Flow<ParentalSettings> = context.userDataStore.data.map { prefs ->
        val raw = prefs[parentalKey] ?: return@map ParentalSettings()
        runCatching { json.decodeFromString(ParentalSettings.serializer(), raw) }.getOrDefault(ParentalSettings())
    }

    suspend fun updateParental(transform: (ParentalSettings) -> ParentalSettings) {
        val next = transform(parental.first())
        context.userDataStore.edit { it[parentalKey] = json.encodeToString(ParentalSettings.serializer(), next) }
    }

    val favorites: Flow<Set<String>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[favKey] ?: "[]"
        runCatching { json.decodeFromString(favSer, raw) }.getOrDefault(emptySet())
    }

    val recents: Flow<List<RecentItem>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[recentKey] ?: "[]"
        runCatching { json.decodeFromString(recentSer, raw) }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun toggleFavorite(id: String) {
        val current = favorites.first()
        val next = if (id in current) current - id else current + id
        context.userDataStore.edit { it[favKey] = json.encodeToString(favSer, next) }
    }

    /** Ajoute/met à jour un récent (par id) et conserve les [MAX_RECENTS] plus récents. */
    suspend fun recordRecent(item: RecentItem) {
        // Lecture-modification-écriture DANS la transaction (évite de perdre une écriture
        // concurrente : progression toutes les 5 s + favoris + sortie du lecteur).
        context.userDataStore.edit { p ->
            val current = runCatching { p[recentKey]?.let { json.decodeFromString(recentSer, it) } }.getOrNull().orEmpty()
                .filterNot { it.id == item.id }
            val all = listOf(item.copy(updatedAt = System.currentTimeMillis())) + current
            // Plafonds SÉPARÉS : le zapping (direct) n'efface plus les points de reprise (films/séries).
            val (live, vod) = all.partition { it.live }
            val next = (live.take(MAX_RECENTS_LIVE) + vod.take(MAX_RECENTS_VOD)).sortedByDescending { it.updatedAt }
            p[recentKey] = json.encodeToString(recentSer, next)
        }
    }

    suspend fun removeRecent(id: String) {
        val next = recents.first().filterNot { it.id == id }
        context.userDataStore.edit { it[recentKey] = json.encodeToString(recentSer, next) }
    }

    private companion object { const val MAX_RECENTS_LIVE = 20; const val MAX_RECENTS_VOD = 60 }
}
