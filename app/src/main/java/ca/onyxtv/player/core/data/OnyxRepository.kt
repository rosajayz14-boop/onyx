package ca.onyxtv.player.core.data

import ca.onyxtv.player.core.epg.XmltvParser
import ca.onyxtv.player.core.m3u.M3uParser
import ca.onyxtv.player.core.model.Channel
import ca.onyxtv.player.core.model.EpgProgram
import ca.onyxtv.player.core.model.MovieDetail
import ca.onyxtv.player.core.model.PlaylistSource
import ca.onyxtv.player.core.model.SeriesDetail
import ca.onyxtv.player.core.model.VodItem
import ca.onyxtv.player.core.net.Http
import ca.onyxtv.player.core.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/**
 * Dépôt central : agrège toutes les sources configurées en listes prêtes pour l'UI.
 * Réunit M3U (parsing local) et Xtream (API), plus l'EPG (avec cache mémoire).
 */
class OnyxRepository(
    val store: PlaylistStore,
    private val xt: XtreamClient = XtreamClient(),
) {

    // ---- Catalogue ----

    /**
     * Charge toutes les sources EN PARALLÈLE et renvoie un instantané complet avec un bilan
     * par source (comptes et erreur éventuelle). Une source en échec n'empêche pas les autres.
     * [onProgress] reçoit des messages d'avancement pour l'UI.
     */
    suspend fun loadCatalog(onProgress: (String) -> Unit = {}): CatalogSnapshot = withContext(Dispatchers.Default) { loadCatalogInner(onProgress) }

    private suspend fun loadCatalogInner(onProgress: (String) -> Unit): CatalogSnapshot = coroutineScope {
        val sources = store.sources.first()
        if (sources.isEmpty()) return@coroutineScope CatalogSnapshot(updatedAt = System.currentTimeMillis())

        val jobs = sources.map { source ->
            async {
                val t0 = System.currentTimeMillis()
                when (source) {
                    is PlaylistSource.M3u -> {
                        onProgress("Liste « ${source.label} »…")
                        runCatching {
                            val body = Http.get(source.url)
                            // Guide « intégré » au lien : en-tête url-tvg, ou xmltv.php dérivé d'un lien get.php Xtream.
                            if (source.epgUrl.isNullOrBlank()) {
                                val found = M3uParser.epgUrlFromHeader(body) ?: M3uParser.xmltvFromXtreamM3u(source.url)
                                if (found != null) runCatching { store.update(source.id) { s -> (s as? PlaylistSource.M3u)?.copy(epgUrl = found) ?: s } }
                            }
                            M3uParser.parse(body, source.id)
                        }
                            .fold(
                                onSuccess = { ch ->
                                    Triple(ch, emptyList<VodItem>(), SourceReport(source.id, source.label, "M3U", channels = ch.size, durationMs = System.currentTimeMillis() - t0))
                                },
                                onFailure = { e ->
                                    Triple(emptyList<Channel>(), emptyList<VodItem>(), SourceReport(source.id, source.label, "M3U", error = Http.describe(e), durationMs = System.currentTimeMillis() - t0, liveOk = false))
                                },
                            )
                    }
                    is PlaylistSource.Xtream -> {
                        onProgress("Compte « ${source.label} » : chaînes…")
                        val live = async { runCatching { xt.liveStreams(source) } }
                        val movies = async { onProgress("Compte « ${source.label} » : films…"); runCatching { xt.vodStreams(source) } }
                        val series = async { onProgress("Compte « ${source.label} » : séries…"); runCatching { xt.series(source) } }
                        val ch = live.await().getOrDefault(emptyList())
                        val mv = movies.await().getOrDefault(emptyList())
                        val sr = series.await().getOrDefault(emptyList())
                        val errors = listOfNotNull(
                            live.await().exceptionOrNull()?.let { "chaînes : ${Http.describe(it)}" },
                            movies.await().exceptionOrNull()?.let { "films : ${Http.describe(it)}" },
                            series.await().exceptionOrNull()?.let { "séries : ${Http.describe(it)}" },
                        )
                        Triple(
                            ch, mv + sr,
                            SourceReport(
                                source.id, source.label, "Xtream",
                                channels = ch.size, movies = mv.size, series = sr.size,
                                error = errors.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                                durationMs = System.currentTimeMillis() - t0,
                                liveOk = live.await().isSuccess, vodOk = movies.await().isSuccess, seriesOk = series.await().isSuccess,
                            ),
                        )
                    }
                }
            }
        }
        val results = jobs.map { it.await() }
        CatalogSnapshot(
            channels = results.flatMap { it.first },
            vod = results.flatMap { it.second },
            reports = results.map { it.third },
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** État d'un abonnement Xtream (carte de compte, alerte d'expiration). */
    data class AccountInfo(val status: String, val expiresAt: Long?, val activeCons: Int?, val maxCons: Int?) {
        val daysLeft: Long? get() = expiresAt?.let { ((it - System.currentTimeMillis()) / 86_400_000L) }
    }

    suspend fun accountInfo(src: PlaylistSource.Xtream): AccountInfo? = xt.userInfo(src)?.let {
        AccountInfo(
            status = it.status.ifBlank { if (it.auth == 1) "Active" else "?" },
            expiresAt = it.expDate?.trim()?.toLongOrNull()?.let { s -> if (s > 100_000_000_000L) s else s * 1000 },
            activeCons = it.activeCons?.trim()?.toIntOrNull(),
            maxCons = it.maxConnections?.trim()?.toIntOrNull(),
        )
    }

    /** Teste un compte Xtream et renvoie un message d'état lisible. */
    suspend fun probeXtream(src: PlaylistSource.Xtream): String = xt.probe(src)

    // ---- EPG (cache mémoire) ----

    private data class Cached<T>(val at: Long, val value: T)
    private val epgByChannel = HashMap<String, Cached<List<EpgProgram>>>()
    private val xmltvByUrl = HashMap<String, Cached<List<EpgProgram>>>()
    /** Index du guide par identifiant de chaîne NORMALISÉ (construit une fois par téléchargement). */
    private val xmltvIndexByUrl = HashMap<String, Pair<Long, Map<String, List<EpgProgram>>>>()

    /**
     * Normalise un identifiant/nom de chaîne pour l'appariement EPG : minuscules, sans accents,
     * sans domaine (« tsn1.ca » -> « tsn1 »), sans mentions de qualité (HD/4K…), alphanumérique.
     */
    private fun normChan(raw: String): String {
        var t = java.text.Normalizer.normalize(raw.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
        t = t.replace(Regex("\\.[a-z]{2,3}$"), "")
        t = t.replace(Regex("^\\s*[a-z]{2,3}\\s*[:|\\-]\\s*"), "")            // préfixe pays « CA: », « FR | »
        t = t.replace(Regex("\\b(hd|fhd|uhd|4k|sd|hevc|h265|raw|vip)\\b"), " ")
        return t.replace(Regex("[^a-z0-9]"), "")
    }

    /** Guide complet d'une URL, indexé par identifiant normalisé ; mis en cache par téléchargement. */
    private suspend fun xmltvIndex(url: String): Map<String, List<EpgProgram>> {
        val all = xmltv(url)
        val stamp = synchronized(xmltvByUrl) { xmltvByUrl[url]?.at } ?: 0L
        synchronized(xmltvIndexByUrl) { xmltvIndexByUrl[url]?.let { (at, idx) -> if (at == stamp) return idx } }
        val idx = withContext(Dispatchers.Default) { all.groupBy { normChan(it.channelId) } }
        synchronized(xmltvIndexByUrl) { xmltvIndexByUrl[url] = stamp to idx }
        return idx
    }

    /** Programmes d'une chaîne dans un index : par identifiant EPG, puis par nom (exact, puis inclusion). */
    private fun matchInIndex(idx: Map<String, List<EpgProgram>>, epgChannelId: String?, name: String): List<EpgProgram> {
        if (idx.isEmpty()) return emptyList()
        val byId = epgChannelId?.takeIf { it.isNotBlank() }?.let { normChan(it) }
        if (byId != null) idx[byId]?.takeIf { it.isNotEmpty() }?.let { return it }
        val byName = normChan(name)
        if (byName.isBlank()) return emptyList()
        idx[byName]?.takeIf { it.isNotEmpty() }?.let { return it }
        if (byName.length >= 4) {
            // Préfixe strict : « espn » ne doit PAS prendre « espn2 », « tsn1 » ne doit pas prendre « tsn10 ».
            fun prefixOk(longer: String, shorter: String) =
                longer.length > shorter.length && longer.startsWith(shorter) && !longer[shorter.length].isDigit()
            idx.entries
                .filter { (k, v) -> v.isNotEmpty() && k.length >= 4 && (prefixOk(k, byName) || prefixOk(byName, k)) }
                .minByOrNull { (k, _) -> kotlin.math.abs(k.length - byName.length) }
                ?.let { return it.value }
        }
        return emptyList()
    }

    /** État du guide, calculé au préchargement : affiché dans le Guide TV quand il est vide. */
    data class EpgStatus(val programmes: Int, val guideChannels: Int, val matched: Int, val checked: Int, val detail: String, val coverageEnd: Long = 0L, val tableWorks: Boolean = false)
    private val _epgStatus = kotlinx.coroutines.flow.MutableStateFlow<EpgStatus?>(null)
    val epgStatus: kotlinx.coroutines.flow.StateFlow<EpgStatus?> = _epgStatus

    /**
     * Adresse du guide annoncée par le M3U du compte (url-tvg). Si elle diffère de xmltv.php et
     * qu'aucun guide supplémentaire n'est réglé, on l'enregistre : c'est celle que lisent
     * TiviMate / Smarters quand ils chargent le compte comme une liste M3U.
     */
    suspend fun discoverTvgUrl(src: PlaylistSource.Xtream): String? {
        val header = Http.firstLine(xt.m3uUrl(src)) ?: return null
        val found = M3uParser.epgUrlFromHeader(header) ?: return null
        val same = found.substringBefore('?').trimEnd('/').equals(xt.xmltvUrl(src).substringBefore('?').trimEnd('/'), true)
        if (!same && src.extraEpgUrl.isNullOrBlank()) {
            runCatching { store.update(src.id) { s -> (s as? PlaylistSource.Xtream)?.copy(extraEpgUrl = found) ?: s } }
        }
        return found
    }

    /** Précharge le guide des comptes (appelé après le chargement du catalogue) : le Guide TV s'ouvre déjà rempli. */
    suspend fun prefetchEpg(channels: List<Channel> = emptyList()) {
        store.sources.first().filterIsInstance<PlaylistSource.Xtream>().forEach { runCatching { discoverTvgUrl(it) } }
        var programmes = 0; var guideChannels = 0; var matched = 0; var checked = 0; var coverageEnd = 0L
        val details = ArrayList<String>()
        epgUrls().forEach { url ->
            val idx = runCatching { xmltvIndex(url) }.getOrElse { details += "téléchargement : ${Http.describe(it)}"; emptyMap() }
            // Fin de couverture RÉELLE : on ignore les « programmes » de plusieurs jours (remplissage).
            idx.values.forEach { l -> l.forEach { pr -> if (pr.stop - pr.start <= 12 * 3_600_000L && pr.stop > coverageEnd) coverageEnd = pr.stop } }
            programmes += idx.values.sumOf { it.size }
            guideChannels += idx.size
            if (idx.isEmpty()) details += "guide vide (${maskUrl(url)})"
            val sample = channels.take(300)
            checked += sample.size
            matched += sample.count { matchInIndex(idx, it.epgChannelId, it.name).isNotEmpty() }
        }
        if (epgUrls().isEmpty()) details += "aucune adresse de guide (compte Xtream absent, liste M3U sans url-tvg)"
        // La base EPG du panneau répond-elle pour l'avenir ? (une chaîne avec identifiant EPG)
        var tableWorks = false
        store.sources.first().filterIsInstance<PlaylistSource.Xtream>().forEach { src ->
            val c = channels.firstOrNull { it.id.startsWith("xt:${src.id}:") && !it.epgChannelId.isNullOrBlank() && it.streamId != null } ?: return@forEach
            val t = runCatching { xt.simpleDataTable(src, c.streamId!!) }.getOrDefault(emptyList())
            if (t.any { it.stop > System.currentTimeMillis() }) tableWorks = true
        }
        _epgStatus.value = EpgStatus(programmes, guideChannels, matched, checked, details.joinToString(" · "), coverageEnd, tableWorks)
    }

    private fun maskUrl(url: String) = url.replace(Regex("(password=)[^&]+"), "$1•••")

    /**
     * Test complet du guide pour l'écran Réglages : adresse, réponse du serveur, programmes,
     * chaînes du guide, appariement avec les chaînes du compte, exemples non appariés.
     */
    suspend fun epgReport(channels: List<Channel>): String = withContext(Dispatchers.Default) {
        val sources = store.sources.first()
        if (sources.isEmpty()) return@withContext "Aucune source configurée."
        val sb = StringBuilder()
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        // En-tête du M3U du compte : adresse du guide « officielle » du fournisseur.
        sources.filterIsInstance<PlaylistSource.Xtream>().forEach { src ->
            val tvg = runCatching { discoverTvgUrl(src) }.getOrNull()
            sb.append("■ ${src.label} · en-tête du M3U (get.php) : url-tvg = ${tvg?.let { maskUrl(it) } ?: "absent"}\n")
        }
        val sources2 = store.sources.first()   // relu : un url-tvg découvert a pu être enregistré
        data class Entry(val label: String, val url: String?, val src: PlaylistSource)
        val entries = sources2.flatMap { src ->
            when (src) {
                is PlaylistSource.Xtream -> listOf(Entry(src.label, xt.xmltvUrl(src), src)) +
                    listOfNotNull(src.extraEpgUrl?.takeIf { it.isNotBlank() }?.let { Entry("${src.label} · guide supplémentaire", it, src) })
                is PlaylistSource.M3u -> listOf(Entry(src.label, src.epgUrl?.takeIf { it.isNotBlank() }, src))
            }
        }
        for ((label, url, src) in entries) {
            sb.append("■ $label\n")
            if (url == null) { sb.append("  Aucune adresse de guide : la liste M3U n'a pas d'en-tête url-tvg. Ajoutez l'URL XMLTV dans Réglages.\n"); continue }
            sb.append("  Adresse : ${maskUrl(url)}\n")
            val probe = ca.onyxtv.player.core.net.StreamProbe.probe(url)
            sb.append("  Réponse serveur : ${probe?.let { "HTTP ${it.code} ${it.contentType?.substringBefore(';') ?: ""} ${it.length?.let { l -> ca.onyxtv.player.core.net.StreamProbe.fmtSize(l) } ?: ""}" } ?: "injoignable (délai dépassé ?)"}\n")
            // Téléchargement brut dans un fichier : taille réelle, premiers caractères, balises.
            val tmp = java.io.File(Http.tempDir ?: java.io.File(System.getProperty("java.io.tmpdir")), "epg-test-${System.nanoTime()}.xml")
            try {
                val t0 = System.currentTimeMillis()
                val dl = runCatching { Http.getToFile(url, tmp) }
                if (dl.isFailure) { sb.append("  Téléchargement : ÉCHEC — ${dl.exceptionOrNull()?.let { Http.describe(it) }}\n"); continue }
                val secs = (System.currentTimeMillis() - t0) / 1000
                val size = tmp.length()
                val headBytes = tmp.inputStream().use { input ->
                    val buf = ByteArray(400); var n = 0
                    while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    buf.copyOf(n)
                }
                val gz = headBytes.size >= 2 && headBytes[0] == 0x1f.toByte() && headBytes[1] == 0x8b.toByte()
                val head = if (gz) "(gzip)" else String(headBytes, Charsets.UTF_8).replace(Regex("\\s+"), " ").take(220)
                sb.append("  Fichier : ${ca.onyxtv.player.core.net.StreamProbe.fmtSize(size)} en ${secs} s${if (gz) " · compressé gzip" else ""}\n")
                sb.append("  Début : ${head}\n")
                // Fin du fichier : un export complet se termine par </tv>. Sinon le serveur a coupé
                // la génération (temps limite PHP, etc.) et les jours suivants n'y sont jamais.
                // Même export avec un User-Agent Android standard ? (taille et dernière date)
                if (src is PlaylistSource.Xtream && label == src.label) runCatching {
                    val alt = java.io.File(tmp.parentFile, tmp.name + ".ua")
                    try {
                        Http.getToFileWithUa(url, alt, "Dalvik/2.1.0 (Linux; U; Android 11; AFTKA Build/RS8104)")
                        val altList = alt.inputStream().buffered().use { XmltvParser.parse(it, Long.MIN_VALUE, Long.MAX_VALUE) }
                        sb.append("  Avec un User-Agent Android standard : ${ca.onyxtv.player.core.net.StreamProbe.fmtSize(alt.length())} · ${XmltvParser.lastProgrammeTags} programmes · dernier horodatage « ${XmltvParser.lastLastStartRaw} »\n")
                        if (altList.isNotEmpty()) Unit
                    } finally { alt.delete() }
                }
                if (!gz) {
                    val tail = java.io.RandomAccessFile(tmp, "r").use { raf ->
                        val n = minOf(300L, raf.length()).toInt(); raf.seek(raf.length() - n)
                        val b = ByteArray(n); raf.readFully(b); String(b, Charsets.UTF_8)
                    }
                    val complete = tail.contains("</tv>")
                    sb.append("  Fin du fichier : " + (if (complete) "</tv> présent (export complet)" else "TRONQUÉ — pas de </tv> : « …${tail.takeLast(90).replace(Regex("\\s+"), " ")} »") + "\n")
                    if (!complete) sb.append("  → Le serveur interrompt l'export avant la fin (limite de temps/taille côté panneau) : les jours à venir ne sont jamais envoyés.\n")
                }
                val nowMs = System.currentTimeMillis()
                val windowed = runCatching { tmp.inputStream().buffered().use { XmltvParser.parse(it, nowMs - 6 * 3_600_000L, nowMs + 48 * 3_600_000L) } }.getOrDefault(emptyList())
                val tags = XmltvParser.lastProgrammeTags
                val err = XmltvParser.lastError
                val firstStart = XmltvParser.lastFirstStartRaw
                sb.append("  Balises <programme> lues : $tags · gardées (fenêtre −6 h / +48 h) : ${windowed.size} · écartées : ${XmltvParser.lastDropped}\n")
                sb.append("  Écartées car : date illisible ${XmltvParser.lastUnparsable} · trop anciennes ${XmltvParser.lastTooOld} · trop lointaines ${XmltvParser.lastTooFar}\n")
                if (XmltvParser.lastMaxStop > 0L) sb.append("  Le fichier couvre du ${fmt.format(java.util.Date(XmltvParser.lastMinStart))} au ${fmt.format(java.util.Date(XmltvParser.lastMaxStop))} · maintenant : ${fmt.format(java.util.Date(nowMs))}\n")
                if (firstStart != null) sb.append("  Premier horodatage brut : « $firstStart » · dernier : « ${XmltvParser.lastLastStartRaw} »\n")
                if (err != null) sb.append("  Erreur du parseur : $err\n")
                if (tags == 0) {
                    sb.append("  → Le fichier ne contient AUCUN programme : le panneau ne fournit pas d'EPG pour ce compte (ou renvoie un guide vide). Demandez au fournisseur si l'EPG est inclus.\n")
                } else if (windowed.isEmpty()) {
                    sb.append("  → Des programmes existent mais aucun dans les 48 h à venir : dates mal lues (format ci-dessus) ou guide périmé.\n")
                }
                // API par chaîne du panneau (base EPG) : testée sur 3 chaînes AVEC identifiant EPG (CA| d'abord).
                if (src is PlaylistSource.Xtream && label == src.label) {
                    val withId = channels.filter { it.id.startsWith("xt:${src.id}:") && !it.epgChannelId.isNullOrBlank() && it.streamId != null }
                    val mine = (withId.filter { it.name.trim().startsWith("CA", true) } + withId).distinctBy { it.id }.take(3)
                    if (mine.isEmpty()) sb.append("  Aucune chaîne du compte n'a d'identifiant EPG : l'API par chaîne ne peut rien renvoyer.\n")
                    fun describe(r: Result<List<EpgProgram>>) = r.getOrNull()?.let { l ->
                        if (l.isEmpty()) "0 programme" else "${l.size} programmes (${fmt.format(java.util.Date(l.first().start))} → ${fmt.format(java.util.Date(l.last().stop))})"
                    } ?: ("ERREUR " + r.exceptionOrNull()?.let { Http.describe(it) })
                    // Présence de ces identifiants dans le fichier brut (hors fenêtre) : comptage par balayage.
                    val rawCounts = HashMap<String, Int>()
                    if (!gz && mine.isNotEmpty()) runCatching {
                        val needles = mine.map { it.epgChannelId!! to "channel=\"${it.epgChannelId}\"" }
                        tmp.inputStream().buffered(256 * 1024).use { input ->
                            val buf = ByteArray(256 * 1024); var carry = ""
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                val text = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                                needles.forEach { (id, nd) -> var i = text.indexOf(nd); while (i >= 0) { rawCounts[id] = (rawCounts[id] ?: 0) + 1; i = text.indexOf(nd, i + nd.length) } }
                                carry = text.takeLast(200)
                            }
                        }
                    }
                    mine.forEach { c ->
                        sb.append("  « ${c.name} » [${c.epgChannelId}] · dans le fichier xmltv : ${rawCounts[c.epgChannelId] ?: 0} programme(s)\n")
                        sb.append("      get_simple_data_table : ${describe(runCatching { xt.simpleDataTable(src, c.streamId!!) })}\n")
                        sb.append("      get_short_epg : ${describe(runCatching { xt.shortEpg(src, c.streamId!!, 8) })}\n")
                    }
                }
                val list = windowed
                if (list.isEmpty()) continue
                // Le cache mémoire/disque est rafraîchi avec ce téléchargement (pas de 2e téléchargement).
                runCatching { xmltv(url, force = true) }
            } finally { tmp.delete() }
            val list = runCatching { xmltvIndex(url) }.getOrDefault(emptyMap()).values.flatten()
            if (list.isEmpty()) { sb.append("  (index vide après analyse)\n"); continue }
            val ids = list.mapTo(HashSet()) { it.channelId }
            sb.append("  Analyse : ${list.size} programmes · ${ids.size} chaînes · du ${fmt.format(java.util.Date(list.minOf { it.start }))} au ${fmt.format(java.util.Date(list.maxOf { it.stop }))}\n")
            val idx = xmltvIndex(url)
            val prefix = if (src is PlaylistSource.Xtream) "xt:${src.id}:" else "m3u:${src.id}:"
            val mine = channels.filter { it.id.startsWith(prefix) }
            val unmatched = ArrayList<Channel>()
            var matched = 0
            mine.forEach { c -> if (matchInIndex(idx, c.epgChannelId, c.name).isNotEmpty()) matched++ else if (unmatched.size < 6) unmatched += c }
            sb.append("  Chaînes appariées : $matched / ${mine.size}" + (if (mine.isEmpty()) " (aucune chaîne de cette source dans le catalogue)" else "") + "\n")
            val withEpgId = mine.count { !it.epgChannelId.isNullOrBlank() }
            sb.append("  Chaînes avec identifiant EPG fourni par le serveur : $withEpgId / ${mine.size}\n")
            if (unmatched.isNotEmpty()) sb.append("  Non appariées (exemples) : " + unmatched.joinToString(" | ") { "${it.name} [id=${it.epgChannelId ?: "∅"}]" } + "\n")
            sb.append("  Identifiants du guide (exemples) : " + ids.take(8).joinToString(", ") + "\n")
        }
        sb.toString().trimEnd()
    }

    /** Vide les caches EPG (mémoire + disque) : le prochain affichage retélécharge le guide. */
    fun clearEpg() {
        synchronized(epgByChannel) { epgByChannel.clear() }
        synchronized(xmltvByUrl) { xmltvByUrl.clear() }
        runCatching { epgDir()?.listFiles()?.forEach { it.delete() } }
    }

    private fun epgDir(): java.io.File? = Http.dataDir?.resolve("epg")?.apply { mkdirs() }
    private fun epgFile(url: String): java.io.File? = epgDir()?.resolve("epg-${url.hashCode()}.json")
    private val epgJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val epgListSer = kotlinx.serialization.builtins.ListSerializer(EpgProgram.serializer())

    /** Toutes les URL de guide des sources configurées (xmltv.php des comptes + EPG des M3U). */
    private suspend fun epgUrls(): List<String> = store.sources.first().flatMap { s ->
        when (s) {
            is PlaylistSource.Xtream -> listOfNotNull(xt.xmltvUrl(s), s.extraEpgUrl?.takeIf { it.isNotBlank() })
            is PlaylistSource.M3u -> listOfNotNull(s.epgUrl?.takeIf { it.isNotBlank() })
        }
    }

    /** Entre deux listes de programmes d'une même chaîne, celle qui va le plus loin dans le temps. */
    private fun freshest(a: List<EpgProgram>, b: List<EpgProgram>): List<EpgProgram> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val endA = a.maxOf { it.stop }; val endB = b.maxOf { it.stop }
        return if (endB > endA) b else a
    }

    /**
     * Rafraîchit le guide depuis le réseau pour toutes les sources (appelé par la tâche
     * quotidienne en arrière-plan et par « Mettre à jour le guide »). Résultat sur disque.
     */
    suspend fun refreshEpgFromNetwork() {
        synchronized(epgByChannel) { epgByChannel.clear() }
        epgUrls().forEach { url -> runCatching { xmltv(url, force = true) } }
    }

    /** Diagnostic EPG lisible pour une chaîne (Réglages → Mode diagnostic). */
    suspend fun epgDiag(channel: Channel): String {
        val sources = store.sources.first()
        val sid = channel.streamId ?: return "pas de streamId"
        val sourceId = channel.id.split(":").getOrNull(1)
        val src = sources.filterIsInstance<PlaylistSource.Xtream>().firstOrNull { it.id == sourceId }
            ?: return "source introuvable ($sourceId)"
        val short = runCatching { xt.shortEpg(src, sid, 24) }
        val xml = runCatching { xmltv(xt.xmltvUrl(src)) }
        val shortTxt = short.getOrNull()?.size?.toString() ?: ("ERR " + short.exceptionOrNull()?.let { Http.describe(it) })
        val xmlTxt = xml.getOrNull()?.size?.toString() ?: ("ERR " + xml.exceptionOrNull()?.let { Http.describe(it) })
        return "sid=$sid epgId=${channel.epgChannelId ?: "∅"} · short=$shortTxt · xmltv=$xmlTxt"
    }

    /** Guide (now/next) pour une chaîne. Xtream via short_epg ; M3U via XMLTV. Mis en cache. */
    suspend fun epg(channel: Channel): List<EpgProgram> {
        val now = System.currentTimeMillis()
        synchronized(epgByChannel) { epgByChannel[channel.id] }
            ?.takeIf { now - it.at < EPG_TTL_MS }
            ?.let { return it.value }

        val sources = store.sources.first()
        val result: List<EpgProgram> = run {
            // Xtream : 1) guide COMPLET xmltv.php du compte (intégré à l'URL, plusieurs jours),
            //    TOUJOURS consulté — même sans epg_channel_id (beaucoup de panneaux ne le renseignent
            //    pas) : appariement par identifiant EPG, sinon par NOM de chaîne normalisé.
            // 2) get_short_epg (now/next) en secours si la chaîne n'y figure pas.
            channel.streamId?.let { sid ->
                val sourceId = channel.id.split(":").getOrNull(1)
                val src = sources.filterIsInstance<PlaylistSource.Xtream>().firstOrNull { it.id == sourceId }
                    ?: return@run emptyList()
                val idx = runCatching { xmltvIndex(xt.xmltvUrl(src)) }.getOrDefault(emptyMap())
                var fromXmltv = matchInIndex(idx, channel.epgChannelId, channel.name)
                // Guide supplémentaire (URL publique) : on garde la liste la plus à jour des deux.
                src.extraEpgUrl?.takeIf { it.isNotBlank() }?.let { extra ->
                    val idx2 = runCatching { xmltvIndex(extra) }.getOrDefault(emptyMap())
                    fromXmltv = freshest(fromXmltv, matchInIndex(idx2, channel.epgChannelId, channel.name))
                }
                // Base EPG du panneau (get_simple_data_table) : souvent plus à jour que l'export xmltv.
                // On ne l'interroge que si le xmltv ne couvre pas les prochaines heures.
                val horizon = now + 3 * 3_600_000L
                var best = fromXmltv
                if (best.none { it.stop >= horizon }) {
                    val table = runCatching { xt.simpleDataTable(src, sid) }.getOrDefault(emptyList())
                    best = freshest(best, table)
                }
                if (best.isNotEmpty()) return@run best.sortedBy { it.start }
                val short = runCatching { xt.shortEpg(src, sid, limit = 24) }.getOrDefault(emptyList())
                return@run short.sortedBy { it.start }
            }
            // M3U : XMLTV de LA source de la chaîne (id « m3u:<source>:… »), sinon la première qui en a un.
            val m3uSourceId = channel.id.split(":").getOrNull(1)
            val m3uAll = sources.filterIsInstance<PlaylistSource.M3u>().filter { !it.epgUrl.isNullOrBlank() }
            val m3u = m3uAll.firstOrNull { it.id == m3uSourceId } ?: m3uAll.firstOrNull() ?: return@run emptyList()
            val idx = runCatching { xmltvIndex(m3u.epgUrl!!) }.getOrDefault(emptyMap())
            matchInIndex(idx, channel.epgChannelId, channel.name).sortedBy { it.start }
        }
        // Ne JAMAIS mettre en cache un résultat vide : au démarrage le réseau peut ne pas être
        // prêt, et un vide caché 30 min laisserait le guide désespérément vide.
        // Résultat vide : cache COURT (5 min) pour ne pas marteler le serveur à chaque rendu,
        // mais jamais 30 min (un vide au démarrage ne doit pas figer le guide).
        val at = if (result.isNotEmpty()) now else now - EPG_TTL_MS + 5 * 60_000L
        synchronized(epgByChannel) { epgByChannel[channel.id] = Cached(at, result) }
        return result
    }

    private val xmltvMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Guide XMLTV téléchargé EN FLUX vers un fichier temporaire puis parsé sur une fenêtre
     * [maintenant − 6 h ; + 36 h] : mémoire maîtrisée même avec des guides de 100 Mo.
     * Un seul téléchargement à la fois par URL (les écrans demandent l'EPG en parallèle).
     */
    private suspend fun xmltv(url: String, force: Boolean = false): List<EpgProgram> {
        val now = System.currentTimeMillis()
        if (!force) {
            synchronized(xmltvByUrl) { xmltvByUrl[url] }
                ?.takeIf { now - it.at < XMLTV_TTL_MS }
                ?.let { return it.value }
        }
        return xmltvMutex.withLock {
            if (!force) {
                synchronized(xmltvByUrl) { xmltvByUrl[url] }?.takeIf { now - it.at < XMLTV_TTL_MS }?.let { return@withLock it.value }
                // Cache disque (rempli par la mise à jour quotidienne) : ouverture instantanée.
                val disk = epgFile(url)
                if (disk != null && disk.exists() && now - disk.lastModified() < XMLTV_TTL_MS) {
                    val fromDisk = runCatching {
                        withContext(Dispatchers.IO) { epgJson.decodeFromString(epgListSer, disk.readText()) }
                    }.getOrNull()
                    if (fromDisk != null) {
                        synchronized(xmltvByUrl) { xmltvByUrl[url] = Cached(disk.lastModified(), fromDisk) }
                        return@withLock fromDisk
                    }
                }
            }
            val parsed = runCatching {
                val dir = Http.tempDir
                if (dir != null) {
                    dir.mkdirs()
                    val file = java.io.File(dir, "epg-${url.hashCode()}.xml")
                    try {
                        Http.getToFile(url, file)
                        withContext(Dispatchers.IO) {
                            file.inputStream().buffered().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 48 * 3_600_000L) }
                        }
                    } finally { file.delete() }
                } else {
                    withContext(Dispatchers.IO) {
                        Http.getBytes(url).inputStream().use { XmltvParser.parse(it, now - 6 * 3_600_000L, now + 48 * 3_600_000L) }
                    }
                }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) {
                synchronized(xmltvByUrl) { xmltvByUrl[url] = Cached(now, parsed) }
                runCatching {
                    withContext(Dispatchers.IO) {
                        epgFile(url)?.writeText(epgJson.encodeToString(epgListSer, parsed))
                    }
                }
            } else {
                // Cache NÉGATIF (10 min) : sans lui, chaque chaîne affichée relançait le
                // téléchargement complet du guide (dizaines de Mo) après un échec.
                synchronized(xmltvByUrl) { xmltvByUrl[url] = Cached(now - XMLTV_TTL_MS + 10 * 60_000L, parsed) }
            }
            parsed
        }
    }

    // ---- Rattrapage / séries ----

    /**
     * URL de rattrapage d'un programme déjà diffusé (Xtream timeshift), ou null si la chaîne
     * n'offre pas d'archive, si le programme n'est pas terminé ou s'il est trop ancien.
     */
    suspend fun catchupUrl(channel: Channel, program: EpgProgram): String? {
        if (channel.archiveDays <= 0) return null
        val streamId = channel.streamId ?: return null
        val now = System.currentTimeMillis()
        if (program.stop > now) return null
        if (program.start < now - channel.archiveDays * 86_400_000L) return null
        val sourceId = channel.id.split(":").getOrNull(1) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        val minutes = (program.durationMs / 60_000L).toInt().coerceAtLeast(1)
        return xt.timeshiftUrl(src, streamId, program.start, minutes)
    }

    /** Fiche d'un film. Null si la source n'existe plus. id = "xt:<sourceId>:vod:<streamId>" */
    suspend fun movieDetail(item: VodItem): MovieDetail? {
        val parts = item.id.split(":")
        val sourceId = parts.getOrNull(1) ?: return null
        val streamId = parts.getOrNull(3) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        val base = xt.movieInfo(src, streamId)
        if (!base.trailerUrl.isNullOrBlank()) return base
        // Repli bande-annonce : TMDB (par titre + année), si une clé API est configurée.
        val tmdbKey = ca.onyxtv.player.BuildConfig.TMDB_API_KEY
        if (tmdbKey.isNotBlank()) {
            val yt = runCatching {
                ca.onyxtv.player.core.tmdb.TmdbClient.trailerYoutubeId(tmdbKey, item.name, item.year ?: base.releaseDate)
            }.getOrNull()
            if (!yt.isNullOrBlank()) return base.copy(trailerUrl = "https://www.youtube.com/watch?v=$yt")
        }
        return base
    }

    /** Fiche complète d'une série (saisons/épisodes). Null si la source n'existe plus. */
    suspend fun seriesDetail(item: VodItem): SeriesDetail? {
        val seriesId = item.seriesId ?: return null
        // id = "xt:<sourceId>:series:<seriesId>"
        val sourceId = item.id.split(":").getOrNull(1) ?: return null
        val src = store.sources.first().filterIsInstance<PlaylistSource.Xtream>()
            .firstOrNull { it.id == sourceId } ?: return null
        val base = xt.seriesInfo(src, seriesId, item.name)
        if (!base.trailerUrl.isNullOrBlank()) return base
        // Repli bande-annonce : TMDB (séries), si une clé est configurée.
        val tmdbKey = ca.onyxtv.player.BuildConfig.TMDB_API_KEY
        if (tmdbKey.isNotBlank()) {
            val yt = runCatching {
                ca.onyxtv.player.core.tmdb.TmdbClient.tvTrailerYoutubeId(tmdbKey, item.name, item.year)
            }.getOrNull()
            if (!yt.isNullOrBlank()) return base.copy(trailerUrl = "https://www.youtube.com/watch?v=$yt")
        }
        return base
    }

    private companion object {
        const val EPG_TTL_MS = 30 * 60_000L        // now/next Xtream : 30 min
        const val XMLTV_TTL_MS = 24 * 3_600_000L   // guide XMLTV : une fois par jour
    }
}
