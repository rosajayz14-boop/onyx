package ca.onyxtv.player.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
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
    /** Série d'origine pour un épisode (rangée « Continuer la série »). */
    val seriesId: String? = null,
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
    /** Aperçu vidéo de la chaîne sélectionnée dans TV en direct. */
    val livePreview: Boolean = true,
    /** Sous-titres : taille (×), fond sombre, couleur jaune. */
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = true,
    val subtitleYellow: Boolean = false,
    /** Chaînes et catégories masquées par l'utilisateur (menu appui long). */
    val hiddenChannelIds: Set<String> = emptySet(),
    val hiddenCategories: Set<String> = emptySet(),
    /** Demander le profil à l'ouverture quand il y en a plusieurs. */
    val askProfileAtStart: Boolean = true,
)

/** Profil utilisateur : favoris, récents, rappels et contrôle parental séparés. */
@Serializable
data class Profile(val id: String, val name: String)

/** Rappel de programme (dialogue + notification) ou enregistrement programmé depuis le guide. */
@Serializable
data class Reminder(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val stop: Long,
    val record: Boolean = false,
)

fun reminderId(channelId: String, start: Long, record: Boolean) = "$channelId:$start:${if (record) "rec" else "rem"}"

/**
 * Données utilisateur locales : favoris (ids de chaînes/contenus), récents (reprise),
 * contrôle parental et préférences. Persistées via DataStore, comme les sources.
 */
class UserStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Profils : les données ci-dessous sont rangées sous une clé propre au profil ACTIF.
    // Le profil par défaut ("") garde les clés historiques : rien ne change pour un utilisateur existant.
    private val activeKey = stringPreferencesKey("profile_active")
    private val profilesKey = stringPreferencesKey("profiles_json")
    private val profileSer = ListSerializer(Profile.serializer())
    private fun pid(p: Preferences): String = p[activeKey] ?: ""
    private fun keyFor(p: Preferences, base: String) = pid(p).let { if (it.isEmpty()) stringPreferencesKey(base) else stringPreferencesKey("$base:$it") }
    private fun fav(p: Preferences) = keyFor(p, "favorites_json")
    private fun rec(p: Preferences) = keyFor(p, "recents_json")
    private fun par(p: Preferences) = keyFor(p, "parental_json")
    private fun rem(p: Preferences) = keyFor(p, "reminders_json")

    val activeProfile: Flow<String> = context.userDataStore.data.map { pid(it) }
    /** Tous les profils, le principal ("") en premier. */
    val profiles: Flow<List<Profile>> = context.userDataStore.data.map { p ->
        val extra = runCatching { p[profilesKey]?.let { json.decodeFromString(profileSer, it) } }.getOrNull().orEmpty()
        listOf(Profile("", "Principal")) + extra
    }
    suspend fun setActiveProfile(id: String) { context.userDataStore.edit { it[activeKey] = id } }
    suspend fun addProfile(name: String): Profile {
        val prof = Profile(java.util.UUID.randomUUID().toString().take(8), name.trim().ifBlank { "Profil" })
        context.userDataStore.edit { p ->
            val cur = runCatching { p[profilesKey]?.let { json.decodeFromString(profileSer, it) } }.getOrNull().orEmpty()
            p[profilesKey] = json.encodeToString(profileSer, cur + prof)
        }
        return prof
    }
    suspend fun removeProfile(id: String) {
        if (id.isEmpty()) return
        context.userDataStore.edit { p ->
            val cur = runCatching { p[profilesKey]?.let { json.decodeFromString(profileSer, it) } }.getOrNull().orEmpty()
            p[profilesKey] = json.encodeToString(profileSer, cur.filterNot { it.id == id })
            listOf("favorites_json", "recents_json", "parental_json", "reminders_json").forEach { p.remove(stringPreferencesKey("$it:$id")) }
            if (p[activeKey] == id) p[activeKey] = ""
        }
    }
    private val reminderSer = ListSerializer(Reminder.serializer())

    val reminders: Flow<List<Reminder>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[rem(prefs)] ?: "[]"
        runCatching { json.decodeFromString(reminderSer, raw) }.getOrDefault(emptyList()).sortedBy { it.start }
    }

    suspend fun addReminder(r: Reminder) {
        context.userDataStore.edit { p ->
            val cur = runCatching { p[rem(p)]?.let { json.decodeFromString(reminderSer, it) } }.getOrNull().orEmpty()
            p[rem(p)] = json.encodeToString(reminderSer, cur.filterNot { it.id == r.id } + r)
        }
    }

    suspend fun removeReminder(id: String) {
        context.userDataStore.edit { p ->
            val cur = runCatching { p[rem(p)]?.let { json.decodeFromString(reminderSer, it) } }.getOrNull().orEmpty()
            p[rem(p)] = json.encodeToString(reminderSer, cur.filterNot { it.id == id })
        }
    }

    /** Déplace un favori vers le haut (delta < 0) ou le bas : l'ordre du Set (LinkedHashSet) est conservé. */
    suspend fun moveFavorite(id: String, delta: Int) {
        context.userDataStore.edit { p ->
            val cur = runCatching { p[fav(p)]?.let { json.decodeFromString(favSer, it) } }.getOrNull().orEmpty().toMutableList()
            val i = cur.indexOf(id)
            val j = i + delta
            if (i >= 0 && j in cur.indices) { cur[i] = cur[j].also { cur[j] = cur[i] } }
            p[fav(p)] = json.encodeToString(favSer, LinkedHashSet(cur))
        }
    }
    private val prefsKey = stringPreferencesKey("prefs_json")

    val prefs: Flow<AppPrefs> = context.userDataStore.data.map { prefs ->
        val raw = prefs[prefsKey] ?: return@map AppPrefs()
        runCatching { json.decodeFromString(AppPrefs.serializer(), raw) }.getOrDefault(AppPrefs())
    }

    suspend fun updatePrefs(transform: (AppPrefs) -> AppPrefs) {
        context.userDataStore.edit { p ->
            val cur = runCatching { p[prefsKey]?.let { json.decodeFromString(AppPrefs.serializer(), it) } }.getOrNull() ?: AppPrefs()
            p[prefsKey] = json.encodeToString(AppPrefs.serializer(), transform(cur))
        }
    }
    private val favSer = SetSerializer(String.serializer())
    private val recentSer = ListSerializer(RecentItem.serializer())

    val parental: Flow<ParentalSettings> = context.userDataStore.data.map { prefs ->
        val raw = prefs[par(prefs)] ?: return@map ParentalSettings()
        runCatching { json.decodeFromString(ParentalSettings.serializer(), raw) }.getOrDefault(ParentalSettings())
    }

    suspend fun updateParental(transform: (ParentalSettings) -> ParentalSettings) {
        context.userDataStore.edit { p ->
            val cur = runCatching { p[par(p)]?.let { json.decodeFromString(ParentalSettings.serializer(), it) } }.getOrNull() ?: ParentalSettings()
            p[par(p)] = json.encodeToString(ParentalSettings.serializer(), transform(cur))
        }
    }

    val favorites: Flow<Set<String>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[fav(prefs)] ?: "[]"
        runCatching { json.decodeFromString(favSer, raw) }.getOrDefault(emptySet())
    }

    val recents: Flow<List<RecentItem>> = context.userDataStore.data.map { prefs ->
        val raw = prefs[rec(prefs)] ?: "[]"
        runCatching { json.decodeFromString(recentSer, raw) }.getOrDefault(emptyList())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun toggleFavorite(id: String) {
        context.userDataStore.edit { p ->
            val current = runCatching { p[fav(p)]?.let { json.decodeFromString(favSer, it) } }.getOrNull().orEmpty()
            val next = if (id in current) current - id else current + id
            p[fav(p)] = json.encodeToString(favSer, next)
        }
    }

    /** Ajoute/met à jour un récent (par id) et conserve les [MAX_RECENTS] plus récents. */
    suspend fun recordRecent(item: RecentItem) {
        // Lecture-modification-écriture DANS la transaction (évite de perdre une écriture
        // concurrente : progression toutes les 5 s + favoris + sortie du lecteur).
        context.userDataStore.edit { p ->
            val current = runCatching { p[rec(p)]?.let { json.decodeFromString(recentSer, it) } }.getOrNull().orEmpty()
                .filterNot { it.id == item.id }
            val all = listOf(item.copy(updatedAt = System.currentTimeMillis())) + current
            // Plafonds SÉPARÉS : le zapping (direct) n'efface plus les points de reprise (films/séries).
            val (live, vod) = all.partition { it.live }
            val next = (live.take(MAX_RECENTS_LIVE) + vod.take(MAX_RECENTS_VOD)).sortedByDescending { it.updatedAt }
            p[rec(p)] = json.encodeToString(recentSer, next)
        }
    }

    suspend fun removeRecent(id: String) {
        context.userDataStore.edit { p ->
            val current = runCatching { p[rec(p)]?.let { json.decodeFromString(recentSer, it) } }.getOrNull().orEmpty()
            p[rec(p)] = json.encodeToString(recentSer, current.filterNot { it.id == id })
        }
    }

    private companion object { const val MAX_RECENTS_LIVE = 20; const val MAX_RECENTS_VOD = 60 }
}
