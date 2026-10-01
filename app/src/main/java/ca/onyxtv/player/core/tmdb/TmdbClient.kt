package ca.onyxtv.player.core.tmdb

import ca.onyxtv.player.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Movie Database (TMDB) : recherche la bande-annonce officielle d'un film par titre + année
 * quand la source IPTV n'en fournit pas. Nécessite une clé API gratuite (BuildConfig.TMDB_API_KEY).
 * Sans clé, toutes les méthodes renvoient null (fonctionnalité simplement inactive).
 */
object TmdbClient {
    private const val BASE = "https://api.themoviedb.org/3"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Identifiant YouTube de la bande-annonce (titre + année), ou null. */
    suspend fun trailerYoutubeId(apiKey: String, title: String, year: String?): String? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val q = java.net.URLEncoder.encode(title, "UTF-8")
                val y = year?.filter { it.isDigit() }?.take(4)?.takeIf { it.length == 4 }
                val yParam = y?.let { "&year=$it" } ?: ""
                val search = json.parseToJsonElement(
                    Http.get("$BASE/search/movie?api_key=$apiKey&language=fr-FR&include_adult=false&query=$q$yParam")
                ).jsonObject
                val results = search["results"] as? JsonArray ?: return@runCatching null
                val movieId = results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.int
                    ?: return@runCatching null
                // Vidéos : on cherche une bande-annonce YouTube (fr puis en), sinon toute vidéo YouTube.
                for (lang in listOf("fr-FR", "en-US")) {
                    val vids = json.parseToJsonElement(
                        Http.get("$BASE/movie/$movieId/videos?api_key=$apiKey&language=$lang")
                    ).jsonObject
                    val arr = (vids["results"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
                    fun site(o: JsonObject) = o["site"]?.jsonPrimitive?.content ?: ""
                    fun type(o: JsonObject) = o["type"]?.jsonPrimitive?.content ?: ""
                    fun key(o: JsonObject) = o["key"]?.jsonPrimitive?.content
                    val best = arr.firstOrNull { site(it).equals("YouTube", true) && type(it).equals("Trailer", true) }
                        ?: arr.firstOrNull { site(it).equals("YouTube", true) && type(it).contains("Teaser", true) }
                        ?: arr.firstOrNull { site(it).equals("YouTube", true) }
                    key(best ?: continue)?.let { return@runCatching it }
                }
                null
            }.getOrNull()
        }
    }
}
