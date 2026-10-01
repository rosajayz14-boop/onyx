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
    private const val IMG = "https://image.tmdb.org/t/p/w342"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Un titre tendance TMDB (film ou série) : titre, année, affiche. */
    data class Trending(val title: String, val year: String?, val posterUrl: String?, val tv: Boolean)

    /** Tendances de la semaine (films puis séries), en français. Vide sans clé ou en cas d'erreur. */
    suspend fun trendingWeek(apiKey: String): List<Trending> {
        if (apiKey.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = ArrayList<Trending>()
            for (kind in listOf("movie", "tv")) {
                runCatching {
                    val root = json.parseToJsonElement(
                        Http.get("$BASE/trending/$kind/week?api_key=$apiKey&language=fr-FR")
                    ).jsonObject
                    (root["results"] as? JsonArray)?.map { it.jsonObject }?.forEach { o ->
                        val title = (o["title"] ?: o["name"])?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val date = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.content
                        val poster = o["poster_path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
                        out += Trending(title, date?.take(4)?.takeIf { it.length == 4 }, poster?.let { IMG + it }, tv = kind == "tv")
                    }
                }
            }
            out
        }
    }

    /** Identifiant YouTube de la bande-annonce d'un FILM (titre + année), ou null. */
    suspend fun trailerYoutubeId(apiKey: String, title: String, year: String?): String? =
        trailerFor(apiKey, title, year, tv = false)

    /** Identifiant YouTube de la bande-annonce d'une SÉRIE (titre + année), ou null. */
    suspend fun tvTrailerYoutubeId(apiKey: String, title: String, year: String?): String? =
        trailerFor(apiKey, title, year, tv = true)

    private suspend fun trailerFor(apiKey: String, title: String, year: String?, tv: Boolean): String? {
        if (apiKey.isBlank() || title.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val q = java.net.URLEncoder.encode(title, "UTF-8")
                val y = year?.filter { it.isDigit() }?.take(4)?.takeIf { it.length == 4 }
                val kind = if (tv) "tv" else "movie"
                val yKey = if (tv) "first_air_date_year" else "year"
                val search = json.parseToJsonElement(
                    Http.get("$BASE/search/$kind?api_key=$apiKey&language=fr-FR&include_adult=false&query=$q" + (y?.let { "&$yKey=$it" } ?: ""))
                ).jsonObject
                val results = search["results"] as? JsonArray ?: return@runCatching null
                val movieId = results.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.int
                    ?: return@runCatching null
                // Vidéos : on cherche une bande-annonce YouTube (fr puis en), sinon toute vidéo YouTube.
                for (lang in listOf("fr-FR", "en-US")) {
                    val vids = json.parseToJsonElement(
                        Http.get("$BASE/$kind/$movieId/videos?api_key=$apiKey&language=$lang")
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
