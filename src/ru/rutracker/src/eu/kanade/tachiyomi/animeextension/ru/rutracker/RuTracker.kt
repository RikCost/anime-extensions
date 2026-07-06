package eu.kanade.tachiyomi.animeextension.ru.rutracker

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class RuTracker :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "RuTracker"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, DOMAINS.first()) ?: DOMAINS.first()
    }

    private val forumUrl by lazy { "$baseUrl/forum" }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // RuTracker requires an authenticated session (bb_session cookie) to expose magnet links
    // and to search. The interceptor logs in on demand using the stored credentials and retries.
    override val client = network.client.newBuilder()
        .addInterceptor(::authInterceptor)
        .build()

    private val loginLock = Any()

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return response

        val username = preferences.getString(PREF_USERNAME_KEY, "").orEmpty()
        val password = preferences.getString(PREF_PASSWORD_KEY, "").orEmpty()
        if (username.isBlank() || password.isBlank()) return response

        val peek = response.peekBody(PEEK_SIZE).string()
        if (peek.contains(LOGGED_IN_MARKER)) return response

        // Not authenticated → log in once and retry the original request.
        synchronized(loginLock) { login(username, password) }
        response.close()
        return chain.proceed(request)
    }

    private fun login(username: String, password: String) {
        val body = FormBody.Builder()
            .add("login_username", username)
            .add("login_password", password)
            .add("login", "вход")
            .add("redirect", "index.php")
            .build()
        // Use the base client (without this interceptor) to avoid recursion; the shared cookie
        // jar keeps the resulting bb_session for subsequent authenticated requests.
        runCatching {
            network.client.newCall(POST("$forumUrl/login.php", headers, body)).execute().close()
        }
    }

    // ─── Popular ─────────────────────────────────────────────────────────────────

    override fun popularAnimeRequest(page: Int): Request = GET(trackerUrl(page, sortBySeeders = true), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response)

    // ─── Latest ────────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = GET(trackerUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response)

    // ─── Search ────────────────────────────────────────────────────────────────

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(trackerUrl(page, query = query), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimePage(response)

    private fun trackerUrl(page: Int, query: String = "", sortBySeeders: Boolean = false): String {
        // RuTracker expects the search term percent-encoded in Windows-1251, not UTF-8, otherwise
        // Cyrillic queries return nothing. okhttp's addQueryParameter only does UTF-8, so build it manually.
        val nm = URLEncoder.encode(query, "windows-1251")
        val start = (page - 1) * PAGE_SIZE
        return buildString {
            append(forumUrl).append("/tracker.php?nm=").append(nm).append("&start=").append(start)
            // o=10 → sort by seeders, s=2 → descending.
            if (sortBySeeders) append("&o=10&s=2")
        }
    }

    private fun parseAnimePage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val rows = document.select("#tor-tbl tbody tr.tCenter")
        // Base "has next" on the raw page (a full page means more results), not on the
        // count left after category filtering.
        val hasNextPage = rows.size >= PAGE_SIZE

        val animes = rows.mapNotNull { row ->
            val section = row.selectFirst("td.f-name-col")?.text().orEmpty()
            if (!isAllowedSection(section)) return@mapNotNull null

            val link = row.selectFirst("div.t-title a.tLink")
                ?: row.selectFirst("a.tLink")
                ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.text().trim()
            }
        }
        return AnimesPage(animes, hasNextPage)
    }

    // Keep only movie / series / documentary sections; drop games, software, books,
    // magazines, audiobooks, music, sport, etc.
    private fun isAllowedSection(section: String): Boolean {
        val name = section.lowercase()
        if (BLOCKED_SECTION_KEYWORDS.any { it in name }) return false
        return ALLOWED_SECTION_KEYWORDS.any { it in name }
    }

    // ─── Grid covers ────────────────────────────────────────────────────────────
    // Tracker listings have no posters, so (optionally) fetch each topic's cover in parallel
    // to show thumbnails right in the browse grid instead of only after opening a title.

    override suspend fun getPopularAnime(page: Int): AnimesPage = super.getPopularAnime(page).withCovers()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = super.getLatestUpdates(page).withCovers()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = super.getSearchAnime(page, query, filters).withCovers()

    private suspend fun AnimesPage.withCovers(): AnimesPage = coroutineScope {
        val enabled = preferences.getBoolean(PREF_GRID_COVERS_KEY, PREF_GRID_COVERS_DEFAULT)
        if (!enabled || animes.isEmpty()) return@coroutineScope this@withCovers

        val gate = Semaphore(COVER_CONCURRENCY)
        val enriched = animes.map { anime ->
            async {
                if (!anime.thumbnail_url.isNullOrBlank()) return@async anime
                runCatching {
                    gate.withPermit {
                        val doc = client.newCall(GET(baseUrl + anime.url, headers))
                            .awaitSuccess().use { it.asJsoup() }
                        anime.apply {
                            thumbnail_url = extractThumbnail(doc, doc.selectFirst("div.post_body"))
                        }
                    }
                }.getOrDefault(anime)
            }
        }.awaitAll()

        AnimesPage(enriched, hasNextPage)
    }

    // ─── Details ──────────────────────────────────────────────────────────────────

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val post = document.selectFirst("div.post_body")
        return SAnime.create().apply {
            title = document.selectFirst("h1.maintitle")?.text()?.trim().orEmpty()
            thumbnail_url = extractThumbnail(document, post)
            description = extractDescription(post)
            genre = document.select("td.nav.t-breadcrumb-top a, .nav.w100 a").eachText()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("RuTracker.org", ignoreCase = true) }
                .joinToString()
                .ifBlank { null }
            status = SAnime.COMPLETED
        }
    }

    // RuTracker lazy-loads post images: the real URL lives in the `title` attribute of a
    // <var class="postImg"> placeholder (or in an <img>'s src). Try the cover, then any image.
    private fun extractThumbnail(document: Document, post: Element?): String? {
        val candidates = mutableListOf<String>()
        post?.selectFirst("var.postImg.img-right, var.postImg")?.let { candidates += it.attr("title") }
        post?.select("var.postImg")?.forEach { candidates += it.attr("title") }
        post?.select("img.postImg")?.forEach {
            candidates += it.attr("title")
            candidates += it.absUrl("src")
        }
        document.selectFirst("meta[property=og:image]")?.attr("content")?.let { candidates += it }
        return candidates.firstNotNullOfOrNull { it.normalizeImageUrl() }
    }

    private fun String?.normalizeImageUrl(): String? {
        val url = this?.trim().orEmpty()
        return when {
            url.isBlank() -> null
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> null
        }
    }

    // The first post is a wall of BBCode (plot + technical specs). Pull the plot out when a
    // "Описание"/"О фильме" marker is present, and stop before the technical fields.
    private fun extractDescription(post: Element?): String? {
        val text = post?.wholeText()?.replace(WHITESPACE_REGEX, " ")?.trim().orEmpty()
        if (text.isBlank()) return null

        val startMarker = DESC_START_MARKERS
            .mapNotNull { m -> text.indexOf(m, ignoreCase = true).takeIf { it >= 0 }?.let { it + m.length } }
            .minOrNull()
        var plot = if (startMarker != null) text.substring(startMarker).trimStart(' ', ':', '—', '-') else text

        val end = DESC_END_MARKERS
            .mapNotNull { m -> plot.indexOf(m, ignoreCase = true).takeIf { it in 1..DESC_LIMIT } }
            .minOrNull()
        if (end != null) plot = plot.substring(0, end)

        return plot.trim().take(DESC_LIMIT).ifBlank { null }
    }

    // ─── Episodes ─────────────────────────────────────────────────────────────────

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val size = document.selectFirst("#tor-size-humn, span.tor-size-humn")?.text()?.trim()
        val topicId = response.request.url.queryParameter("t")

        // Preferred: split a (multi-file) release — e.g. a whole season — into one playable
        // episode per video file, so each can be opened straight from the title.
        topicId?.let { buildEpisodesFromTorrent(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // Fallback: hand the whole torrent to the player as a single entry.
        val magnet = document.selectFirst("a.magnet-link")?.attr("href")
            ?: document.selectFirst("a[href^=magnet:]")?.attr("href")
            ?: return emptyList()

        return listOf(
            SEpisode.create().apply {
                url = magnet
                name = "Торрент"
                episode_number = 1f
                if (!size.isNullOrBlank()) scanlator = size
            },
        )
    }

    private fun buildEpisodesFromTorrent(topicId: String): List<SEpisode>? {
        val dlUrl = "$forumUrl/dl.php?t=$topicId"

        // Strategy A: let the runtime helper fetch dl.php (works only if it carries the login
        // cookie — RuTracker downloads require it, so this often fails and we fall through).
        runCatching {
            val torrent = TorrentUtils.getTorrentInfo(dlUrl, "torrent")
            val files = torrent.files.map { Triple(it.indexFile, it.path, it.size) }
            toEpisodes(torrent.hash, torrent.trackers, files)
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }

        // Strategy B: download the .torrent ourselves (authenticated) and parse the bencode
        // directly — no dependency on how the runtime helper fetches or handles auth.
        val bytes = downloadTorrent(dlUrl) ?: return null
        return runCatching {
            val meta = RuTrackerTorrent.parse(bytes)
            val files = meta.files.map { Triple(it.index, it.path, it.length) }
            toEpisodes(meta.infoHashHex, meta.trackers, files)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun downloadTorrent(dlUrl: String): ByteArray? = runCatching {
        client.newCall(GET(dlUrl, headers)).execute().use { resp ->
            val bytes = resp.body.bytes()
            // A real .torrent is a bencoded dict starting with 'd'; an HTML login page is not.
            if (resp.isSuccessful && bytes.firstOrNull() == 'd'.code.toByte()) bytes else null
        }
    }.getOrNull()

    private fun toEpisodes(
        infoHash: String,
        trackers: List<String>,
        files: List<Triple<Int, String, Long>>,
    ): List<SEpisode> {
        val trackerParams = trackers
            .filter { it.isNotBlank() }
            .joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        val magnetBase = "magnet:?xt=urn:btih:$infoHash$trackerParams"
        return files
            .filter { (_, path, _) -> path.substringAfterLast('.').lowercase(Locale.ROOT) in VIDEO_EXTENSIONS }
            .sortedBy { (_, path, _) -> path.lowercase(Locale.ROOT) }
            .mapIndexed { number, (index, path, size) ->
                SEpisode.create().apply {
                    url = "$magnetBase&index=$index"
                    name = path.substringAfterLast('/').trim()
                    episode_number = (number + 1).toFloat()
                    scanlator = readableSize(size)
                }
            }
    }

    private fun readableSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit])
    }

    // ─── Videos ───────────────────────────────────────────────────────────────────

    // The magnet is handed straight to the built-in torrent server.
    override suspend fun getVideoList(episode: SEpisode): List<Video> = listOf(Video(episode.url, episode.name, episode.url))

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ─── Preferences ────────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Зеркало / Domain"
            entries = DOMAINS.toTypedArray()
            entryValues = DOMAINS.toTypedArray()
            setDefaultValue(DOMAINS.first())
            summary = "%s\nПерезапустите приложение после изменения."
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_GRID_COVERS_KEY
            title = "Обложки в списке"
            summary = "Подгружать постеры прямо в сетку поиска и «Последние». " +
                "Медленнее и создаёт больше запросов к трекеру — можно отключить."
            setDefaultValue(PREF_GRID_COVERS_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME_KEY
            title = "Логин / Username"
            summary = preferences.getString(PREF_USERNAME_KEY, "")
                ?.takeIf { it.isNotBlank() } ?: "Введите имя пользователя RuTracker"
            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as String).takeIf { it.isNotBlank() } ?: "Введите имя пользователя RuTracker"
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD_KEY
            title = "Пароль / Password"
            summary = "•".repeat(preferences.getString(PREF_PASSWORD_KEY, "").orEmpty().length)
                .ifBlank { "Введите пароль RuTracker" }
            setOnBindEditTextListener {
                it.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { _, newValue ->
                summary = "•".repeat((newValue as String).length).ifBlank { "Введите пароль RuTracker" }
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private val DOMAINS = listOf(
            "https://rutracker.org",
            "https://rutracker.net",
            "https://rutracker.nl",
        )

        private const val PAGE_SIZE = 50
        private const val DESC_LIMIT = 2000
        private const val COVER_CONCURRENCY = 5

        private val WHITESPACE_REGEX = Regex("""\s+""")

        // Sections to keep — movies, series, documentaries (roots match all declensions/subforums).
        private val ALLOWED_SECTION_KEYWORDS = listOf(
            "кино",
            "фильм",
            "сериал",
            "документал",
            "теленовелл",
            "мультсериал",
        )

        // Sections to drop even if a keyword above accidentally matches (e.g. "документальная
        // литература" / "документальная проза" would otherwise pass on the "документал" root).
        private val BLOCKED_SECTION_KEYWORDS = listOf(
            "игр", "софт", "программ", "прошивк", "приложени",
            "книг", "журнал", "газет", "литератур", "проза", "поэзи", "фольклор",
            "комикс", "манга", "учебник", "справочник", "энциклопед", "обучени",
            "аудиокниг", "аудио", "подкаст", "радиоспектакл", "музык", "дискограф", "саундтрек",
            "спорт", "обои", "картинк", "фотограф", "порно", "хентай", "эротик",
        )

        private val DESC_START_MARKERS = listOf("Описание:", "Описание", "О фильме:", "Сюжет:", "Аннотация:")
        private val DESC_END_MARKERS = listOf(
            "Качество:", "Качество видео:", "Формат:", "Формат видео:", "Видео:",
            "Аудио:", "Звук:", "Продолжительность:", "Перевод:", "Субтитры:", "Релиз:",
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts",
            "mpg", "mpeg", "vob", "ogv", "m4v", "3gp",
        )
        private const val PEEK_SIZE = 250_000L
        private const val LOGGED_IN_MARKER = "logged-in-username"

        private const val PREF_DOMAIN_KEY = "domain"
        private const val PREF_USERNAME_KEY = "username"
        private const val PREF_PASSWORD_KEY = "password"
        private const val PREF_GRID_COVERS_KEY = "grid_covers"
        private const val PREF_GRID_COVERS_DEFAULT = true
    }
}
