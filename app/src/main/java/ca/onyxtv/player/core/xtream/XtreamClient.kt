package ca.onyxtv.player.core.xtream

import android.util.Base64
import ca.onyxtv.player.core.model.Category
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.Episode
import ca.onyxtv.player.core.model.MediaKind
import ca.onyxtv.player.core.model.MovieDetail
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.SeriesDetail
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.core.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client de l'API Xtream Codes (player_api.php).
 * Construit aussi les URLs de flux live/VOD.
 */
class XtreamClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true          // tolère stream_id renvoyé en chaîne
        coerceInputValues = true
    },
) {

    /**
     * Base normalisée du serveur : ajoute http:// si le schéma manque (erreur de saisie
     * la plus fréquente) et retire le / final. Ex. "mon.tv:8080/" -> "http://mon.tv:8080".
     */
    private fun base(src: PlaylistSource.Xtream): String {
        var s = src.server.trim()
        // URL collée depuis un courriel du fournisseur : on ne garde que le serveur.
        s = s.replace(Regex("/(player_api|get|xmltv|panel_api)\\.php.*$", RegexOption.IGNORE_CASE), "")
        s = s.trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return s
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun api(src: PlaylistSource.Xtream, action: String, extra: String = ""): String =
        "${base(src)}/player_api.php?username=${enc(src.username)}&password=${enc(src.password)}&action=$action$extra"

    /** Guide complet XMLTV du compte (même identifiants), comme TiviMate / Helix. */
    fun xmltvUrl(src: PlaylistSource.Xtream): String =
        "${base(src)}/xmltv.php?username=${enc(src.username)}&password=${enc(src.password)}"

    /** Liste M3U du compte : son en-tête `url-tvg` indique l'adresse du guide que les autres apps utilisent. */
    fun m3uUrl(src: PlaylistSource.Xtream): String =
        "${base(src)}/get.php?username=${enc(src.username)}&password=${enc(src.password)}&type=m3u_plus&output=ts"

    /** URL d'authentification (sans action) : renvoie user_info / server_info. */
    private fun authUrl(src: PlaylistSource.Xtream): String =
        "${base(src)}/player_api.php?username=${enc(src.username)}&password=${enc(src.password)}"

    /**
     * Teste le compte et renvoie un message clair pour l'UI.
     * Lève une exception réseau (IOException) si le serveur est injoignable.
     */
    suspend fun probe(src: PlaylistSource.Xtream): String {
        val body = Http.get(authUrl(src))
        val info = runCatching { json.decodeFromString<XtAuthResponse>(body).userInfo }.getOrNull()
            ?: return "Réponse inattendue du serveur (vérifiez l'URL du serveur)."
        return when {
            info.auth == 0 || info.status.equals("Disabled", true) ->
                "Identifiants refusés par le serveur (auth=${info.auth}, statut « ${info.status} »)."
            info.status.isNotBlank() && !info.status.equals("Active", true) ->
                "Compte « ${info.status} » — vérifiez la validité de l'abonnement."
            else -> {
                val exp = info.expDate?.toLongOrNull()?.let { java.text.SimpleDateFormat("dd/MM/yyyy").format(java.util.Date(it * 1000)) }
                "Connecté ✓" + (exp?.let { " — expire le $it" } ?: "")
            }
        }
    }

    /** Infos du compte (statut, expiration, connexions) ; null si la réponse n'est pas lisible. */
    suspend fun userInfo(src: PlaylistSource.Xtream): XtUserInfo? =
        runCatching { json.decodeFromString<XtAuthResponse>(Http.get(authUrl(src))).userInfo }.getOrNull()

    suspend fun serverInfo(src: PlaylistSource.Xtream): XtServerInfo? =
        runCatching { json.decodeFromString<XtAuthResponse>(Http.get(authUrl(src))).serverInfo }.getOrNull()

    /**
     * Base « réelle » du panneau d'après server_info (protocole://url:port), ou null si elle est
     * absente ou identique à l'adresse saisie. Utile quand l'adresse saisie est un relais dont
     * l'export xmltv.php n'est plus mis à jour.
     */
    fun realBase(src: PlaylistSource.Xtream, info: XtServerInfo?): String? {
        val host = info?.url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val https = info.protocol?.equals("https", true) == true
        val port = (if (https) info.httpsPort ?: info.port else info.port)?.trim()?.takeIf { it.isNotBlank() }
        val bare = host.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        val defaultPort = (https && port == "443") || (!https && port == "80")
        val b = (if (https) "https://" else "http://") + bare + (if (port != null && !defaultPort && !bare.contains(':')) ":$port" else "")
        return b.takeIf { !it.equals(base(src), true) }
    }

    /** URL xmltv.php sur une autre base (serveur réel du panneau). */
    fun xmltvUrlAt(src: PlaylistSource.Xtream, baseUrl: String): String =
        "${baseUrl.trimEnd('/')}/xmltv.php?username=${enc(src.username)}&password=${enc(src.password)}"

    fun liveUrl(src: PlaylistSource.Xtream, streamId: Long): String =
        "${base(src)}/live/${enc(src.username)}/${enc(src.password)}/$streamId.${src.liveExtension}"

    fun movieUrl(src: PlaylistSource.Xtream, streamId: Long, ext: String?): String =
        "${base(src)}/movie/${enc(src.username)}/${enc(src.password)}/$streamId.${ext ?: "mp4"}"

    suspend fun liveCategories(src: PlaylistSource.Xtream): List<Category> =
        getList<XtCategory>(api(src, "get_live_categories"))
            .map { Category(it.categoryId, it.categoryName, MediaKind.LIVE) }

    suspend fun vodCategories(src: PlaylistSource.Xtream): List<Category> =
        getList<XtCategory>(api(src, "get_vod_categories"))
            .map { Category(it.categoryId, it.categoryName, MediaKind.MOVIE) }

    /** Table id → nom de catégorie (vide si l'appel échoue : on retombe sur l'id). */
    private suspend fun categoryNames(fetch: suspend () -> List<Category>): Map<String, String> =
        runCatching { fetch() }.getOrDefault(emptyList()).associate { it.id to it.name }

    suspend fun liveStreams(src: PlaylistSource.Xtream, categoryId: String? = null): List<Channel> {
        val extra = categoryId?.let { "&category_id=$it" }.orEmpty()
        val names = categoryNames { liveCategories(src) }
        return getList<XtLiveStream>(api(src, "get_live_streams", extra)).map { s ->
            Channel(
                id = "xt:${src.id}:live:${s.streamId}",
                streamId = s.streamId.toString(),
                number = s.num,
                name = s.name,
                logoUrl = s.streamIcon,
                groupTitle = s.categoryId?.let { names[it] ?: it },
                epgChannelId = s.epgChannelId,
                url = liveUrl(src, s.streamId),
                kind = MediaKind.LIVE,
                archiveDays = if ((s.tvArchive ?: 0) == 1) (s.tvArchiveDuration?.takeIf { it > 0 } ?: 1) else 0,
            )
        }
    }

    /**
     * URL de rattrapage (timeshift) Xtream : .../timeshift/user/pass/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.ts
     * L'horodatage est exprimé en heure locale, comme le font les lecteurs du marché.
     */
    fun timeshiftUrl(src: PlaylistSource.Xtream, streamId: String, startMs: Long, durationMin: Int): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US).format(java.util.Date(startMs))
        return "${base(src)}/timeshift/${enc(src.username)}/${enc(src.password)}/$durationMin/$stamp/$streamId.ts"
    }

    suspend fun vodStreams(src: PlaylistSource.Xtream, categoryId: String? = null): List<VodItem> {
        val extra = categoryId?.let { "&category_id=$it" }.orEmpty()
        val names = categoryNames { vodCategories(src) }
        return getList<XtVodStream>(api(src, "get_vod_streams", extra)).map { v ->
            VodItem(
                id = "xt:${src.id}:vod:${v.streamId}",
                name = v.name,
                posterUrl = v.streamIcon,
                category = v.categoryId?.let { names[it] ?: it },
                year = v.year,
                rating = v.rating,
                url = movieUrl(src, v.streamId, v.containerExtension),
                kind = MediaKind.MOVIE,
            )
        }
    }

    fun episodeUrl(src: PlaylistSource.Xtream, episodeId: String, ext: String?): String =
        "${base(src)}/series/${enc(src.username)}/${enc(src.password)}/$episodeId.${ext ?: "mp4"}"

    suspend fun seriesCategories(src: PlaylistSource.Xtream): List<Category> =
        getList<XtCategory>(api(src, "get_series_categories"))
            .map { Category(it.categoryId, it.categoryName, MediaKind.SERIES) }

    /** Catalogue des séries (fiches), sans les épisodes (chargés à la demande). */
    suspend fun series(src: PlaylistSource.Xtream): List<VodItem> {
        val names = categoryNames { seriesCategories(src) }
        return getList<XtSeries>(api(src, "get_series")).map { s ->
            VodItem(
                id = "xt:${src.id}:series:${s.seriesId}",
                name = s.name,
                posterUrl = s.cover,
                category = s.categoryId?.let { names[it] ?: it },
                year = s.year ?: s.releaseDate?.take(4),
                rating = s.rating,
                url = "",
                kind = MediaKind.SERIES,
                seriesId = s.seriesId.toString(),
                plot = s.plot,
            )
        }
    }

    /** Fiche d'une série : saisons et épisodes prêts à lire. */
    suspend fun seriesInfo(src: PlaylistSource.Xtream, seriesId: String, fallbackName: String): SeriesDetail {
        val r = getOne<XtSeriesInfo>(api(src, "get_series_info", "&series_id=$seriesId"))
        val infoObj = r.info as? JsonObject   // null si le panneau renvoie "info": []
        val infoCover = infoObj?.str("cover")
        val seasons = LinkedHashMap<Int, MutableList<Episode>>()

        fun addEpisode(el: JsonElement, seasonHint: Int?) {
            val o = el as? JsonObject ?: return
            val id = o.str("id") ?: return
            val season = o.int("season") ?: seasonHint ?: 1
            val number = o.int("episode_num") ?: (seasons[season]?.size?.plus(1) ?: 1)
            val info = o["info"] as? JsonObject
            seasons.getOrPut(season) { ArrayList() }.add(Episode(
                id = "xt:${src.id}:ep:$id",
                title = o.str("title")?.takeIf { it.isNotBlank() } ?: "Épisode $number",
                season = season,
                number = number,
                url = episodeUrl(src, id, o.str("container_extension")),
                plot = info?.str("plot"),
                imageUrl = info?.str("movie_image") ?: infoCover,
                durationSecs = info?.int("duration_secs"),
            ))
        }

        when (val eps = r.episodes) {
            is JsonObject -> eps.forEach { (key, value) ->
                val hint = key.toIntOrNull()
                when (value) {
                    is JsonArray -> value.forEach { addEpisode(it, hint) }
                    is JsonObject -> value.values.forEach { addEpisode(it, hint) }   // {"1": {"0": {...}, "1": {...}}} (tableaux PHP associatifs)
                    else -> Unit
                }
            }
            is JsonArray -> eps.forEach { entry ->
                when (entry) {
                    is JsonArray -> entry.forEach { addEpisode(it, null) }
                    else -> addEpisode(entry, null)
                }
            }
            else -> Unit
        }

        return SeriesDetail(
            name = infoObj?.str("name")?.takeIf { it.isNotBlank() } ?: fallbackName,
            plot = infoObj?.str("plot"),
            coverUrl = infoCover,
            trailerUrl = infoObj?.str("youtube_trailer")?.trim()?.takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("http", true)) it else "https://www.youtube.com/watch?v=$it"
            },
            seasons = seasons.mapValues { (_, v) -> v.sortedBy { it.number } },
        )
    }

    /** Fiche d'un film : résumé, casting, note, bande-annonce (get_vod_info). */
    suspend fun movieInfo(src: PlaylistSource.Xtream, streamId: String): MovieDetail = withContext(Dispatchers.IO) {
        movieInfoBlocking(src, streamId)
    }

    private suspend fun movieInfoBlocking(src: PlaylistSource.Xtream, streamId: String): MovieDetail {
        val root = json.parseToJsonElement(Http.get(api(src, "get_vod_info", "&vod_id=$streamId"))) as? JsonObject
            ?: return MovieDetail()
        val info = root["info"] as? JsonObject ?: return MovieDetail()
        val rating10 = info.str("rating")?.toFloatOrNull()?.takeIf { it > 0f }
            ?: info.str("rating_5based")?.toFloatOrNull()?.takeIf { it > 0f }?.times(2f)
        // Le lien de bande-annonce se trouve selon les panneaux dans info, movie_data ou à la racine.
        val movieData = root["movie_data"] as? JsonObject
        val trailerRaw = sequenceOf(
            info.str("youtube_trailer"), info.str("trailer"),
            movieData?.str("youtube_trailer"), movieData?.str("trailer"),
            root.str("youtube_trailer"),
        ).firstOrNull { !it.isNullOrBlank() }
        val trailer = trailerRaw?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http", true)) it else "https://www.youtube.com/watch?v=$it"
        }
        val backdrop = (info["backdrop_path"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            ?: info.str("movie_image")
        return MovieDetail(
            plot = info.str("plot") ?: info.str("description"),
            cast = info.str("cast") ?: info.str("actors"),
            director = info.str("director"),
            genre = info.str("genre"),
            releaseDate = info.str("releasedate") ?: info.str("release_date"),
            rating = rating10?.coerceIn(0f, 10f),
            duration = info.str("duration"),
            trailerUrl = trailer,
            backdropUrl = backdrop,
        )
    }

    // Accès tolérant aux primitives JSON (chaîne ou nombre, selon les panneaux).
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it != "null" }

    private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

    /**
     * EPG court (now/next…) pour une chaîne. Décodage TOLÉRANT : les panneaux varient énormément.
     * Horodatages acceptés en nombre OU en texte (start_timestamp / stop_timestamp), avec repli sur
     * les dates « start » / « end » (yyyy-MM-dd HH:mm:ss). Titres/descriptions en Base64 OU en clair.
     */
    suspend fun shortEpg(src: PlaylistSource.Xtream, streamId: String, limit: Int = 8): List<EpgProgram> =
        epgListings(src, "get_short_epg", "&stream_id=$streamId&limit=$limit", streamId)

    /**
     * EPG COMPLET d'une chaîne (passé + jours à venir) lu directement dans la base du panneau :
     * c'est ce qu'utilisent les lecteurs du marché quand l'export xmltv.php est tronqué/périmé.
     */
    suspend fun simpleDataTable(src: PlaylistSource.Xtream, streamId: String): List<EpgProgram> =
        epgListings(src, "get_simple_data_table", "&stream_id=$streamId", streamId)

    /** Réponse BRUTE (début) de l'API EPG par chaîne — pour le test du guide. */
    suspend fun epgRaw(src: PlaylistSource.Xtream, action: String, streamId: String): String = withContext(Dispatchers.IO) {
        runCatching { Http.get(api(src, action, "&stream_id=$streamId")).replace(Regex("\\s+"), " ").take(160) }
            .getOrElse { "ERREUR " + Http.describe(it) }
    }

    /**
     * Variantes de la même requête get_short_epg (test du guide) : GET avec notre User-Agent,
     * GET avec celui de Smarters, POST formulaire comme Smarters. Chaque ligne : variante → début brut.
     */
    suspend fun epgRawVariants(src: PlaylistSource.Xtream, streamId: String): List<String> = withContext(Dispatchers.IO) {
        val url = api(src, "get_short_epg", "&stream_id=$streamId&limit=4")
        val form = mapOf("username" to src.username, "password" to src.password, "action" to "get_short_epg", "stream_id" to streamId, "limit" to "4")
        fun short(r: Result<String>) = r.map { it.replace(Regex("\\s+"), " ").take(140) }.getOrElse { "ERREUR " + Http.describe(it) }
        listOf(
            "GET UA Android : " + short(runCatching { Http.getWithUa(url, Http.UA) }),
            "GET UA ONYX : " + short(runCatching { Http.getWithUa(url, Http.UA_ONYX) }),
            "GET UA Smarters : " + short(runCatching { Http.getWithUa(url, Http.UA_SMARTERS) }),
            "POST formulaire (Smarters) : " + short(runCatching { Http.postForm("${base(src)}/player_api.php", form, Http.UA_SMARTERS) }),
        )
    }

    private suspend fun epgListings(src: PlaylistSource.Xtream, action: String, extra: String, streamId: String): List<EpgProgram> =
        withContext(Dispatchers.IO) {
            val root = runCatching { json.parseToJsonElement(Http.get(api(src, action, extra))) }.getOrNull()
            val listings = ((root as? JsonObject)?.get("epg_listings") as? JsonArray)
                ?: (root as? JsonArray)   // certains panneaux renvoient directement un tableau
                ?: return@withContext emptyList()
            listings.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val start = epgMillis(o, "start_timestamp", "start") ?: return@mapNotNull null
                val stop = epgMillis(o, "stop_timestamp", "end") ?: return@mapNotNull null
                if (stop <= start) return@mapNotNull null
                EpgProgram(
                    channelId = streamId,
                    title = decodeB64(o.str("title") ?: "").ifBlank { "Programme" },
                    description = decodeB64(o.str("description") ?: "").ifBlank { null },
                    start = start,
                    stop = stop,
                )
            }.sortedBy { it.start }
        }

    // SimpleDateFormat n'est pas thread-safe et shortEpg tourne en parallèle pour plusieurs chaînes.
    private fun epgDateFmt() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

    /** Millisecondes depuis l'horodatage unix (nombre/texte) ou, à défaut, la date « yyyy-MM-dd HH:mm:ss ». */
    private fun epgMillis(o: JsonObject, tsKey: String, dateKey: String): Long? {
        // Certains panneaux renvoient déjà des millisecondes (13 chiffres) : ne pas re-multiplier,
        // sinon les programmes tombent en l'an 50 000 et sortent de la fenêtre du guide.
        o.str(tsKey)?.trim()?.toLongOrNull()?.let { if (it > 0) return if (it > 100_000_000_000L) it else it * 1000 }
        val d = o.str(dateKey)?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { epgDateFmt().parse(d)?.time }.getOrNull()
    }

    private val b64Alphabet = Regex("^[A-Za-z0-9+/=\\s]+$")

    /** Les panneaux encodent titres/descriptions en base64, mais pas tous : un titre en clair
     *  comme « News » se « décode » en octets illisibles. On ne garde le décodage que s'il est plausible. */
    private fun decodeB64(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || !b64Alphabet.matches(t) || t.replace(Regex("\\s"), "").length % 4 != 0) return s
        return runCatching {
            val decoded = String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' } || decoded.contains('\uFFFD')) s else decoded
        }.getOrDefault(s)
    }

    /**
     * Décode une liste élément par élément : un seul enregistrement mal formé (champ d'un
     * type inattendu, panneau exotique) est ignoré au lieu de faire disparaître tout le
     * catalogue. Une réponse qui n'est pas un tableau (ex. erreur d'auth en objet) lève.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getList(url: String): List<T> = withContext(Dispatchers.IO) {
        getListBlocking<T>(url)
    }

    /** Décodage TOUJOURS hors du fil principal (voir getList) : un catalogue peut faire 50 Mo. */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend inline fun <reified T> getListBlocking(url: String): List<T> {
        val dir = Http.tempDir
        if (dir == null) {
            // Pas de dossier temporaire (tests) : chemin mémoire.
            val body = Http.get(url)
            return tolerantList(runCatching { json.parseToJsonElement(body) }.getOrElse { throw IllegalStateException("réponse illisible du serveur") })
        }
        dir.mkdirs()
        val file = File(dir, "xt-${url.hashCode()}-${System.nanoTime()}.json")
        try {
            Http.getToFile(url, file)
            // 1) Chemin rapide et économe : décodage en flux depuis le fichier, sans arbre JSON
            //    ni gros String en mémoire — c'est ce qui compte avec 50 000 titres sur une box TV.
            val strict = runCatching { file.inputStream().buffered().use { json.decodeFromStream<List<T>>(it) } }
            strict.getOrNull()?.let { return it }
            // 2) Repli tolérant (rare) : élément par élément, un enregistrement mal formé est ignoré.
            val root = runCatching { file.inputStream().buffered().use { json.decodeFromStream<JsonElement>(it) } }
                .getOrElse { throw IllegalStateException("réponse illisible du serveur") }
            return tolerantList(root)
        } finally {
            file.delete()
        }
    }

    private inline fun <reified T> tolerantList(root: JsonElement): List<T> {
        val array = root as? JsonArray
            ?: throw IllegalStateException(
                if (root is JsonObject && root.containsKey("user_info")) "identifiants refusés" else "réponse inattendue du serveur"
            )
        return array.mapNotNull { el -> runCatching { json.decodeFromJsonElement<T>(el) }.getOrNull() }
    }

    private suspend inline fun <reified T> getOne(url: String): T = withContext(Dispatchers.IO) {
        json.decodeFromString<T>(Http.get(url))
    }
}
