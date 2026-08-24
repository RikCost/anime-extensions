package eu.kanade.tachiyomi.animeextension.ru.rutracker

import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class RuTracker :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "RuTracker"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Read on every access so a domain switch takes effect immediately, without an app restart.
    // Switching hosts also resets the login backoff: cookies (cf_clearance / bb_session) are
    // host-bound and don't carry over, so the source must solve + log in again.
    override val baseUrl: String
        get() {
            val domain = preferences.getString(PREF_DOMAIN_KEY, null).orEmpty()
                .ifBlank { DOMAINS.first() }
            if (domain != currentDomain) {
                currentDomain = domain
                lastLoginFailure = 0L
            }
            return domain
        }

    @Volatile
    private var currentDomain: String? = null

    private val forumUrl: String
        get() = "$baseUrl/forum"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // RuTracker sits behind Cloudflare: the CloudflareInterceptor solves challenges via WebView
    // and stores cf_clearance in the shared cookie jar. The auth interceptor must run after it
    // so it never mistakes a challenge page for a logged-out response.
    override val client = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .addInterceptor(::authInterceptor)
        .build()

    // Same Cloudflare handling but without the auth interceptor — used for the login POST
    // itself so it also passes the challenge, while the shared cookie jar keeps bb_session.
    private val authClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    private val loginLock = Any()

    // Timestamp of the last rejected login, so we stop re-trying it on every response but
    // still recover after a while (e.g. when the first attempt raced the Cloudflare solve).
    @Volatile
    private var lastLoginFailure: Long = 0L

    private fun loginTemporarilyBlocked(): Boolean = System.currentTimeMillis() - lastLoginFailure < LOGIN_RETRY_INTERVAL_MS

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("text/html", ignoreCase = true)) return response

        if (loginTemporarilyBlocked()) return response

        val username = preferences.getString(PREF_USERNAME_KEY, "").orEmpty()
        val password = preferences.getString(PREF_PASSWORD_KEY, "").orEmpty()
        if (username.isBlank() || password.isBlank()) return response

        val peek = runCatching { response.peekBody(PEEK_SIZE).string() }.getOrDefault("")
        if (peek.contains(LOGGED_IN_MARKER)) return response

        // Not authenticated → log in once and retry the original request.
        synchronized(loginLock) {
            if (!loginTemporarilyBlocked()) login(username, password)
        }
        response.close()
        val retry = chain.proceed(request)

        val retryPeek = runCatching { retry.peekBody(PEEK_SIZE).string() }.getOrDefault("")
        if (!retryPeek.contains(LOGGED_IN_MARKER)) lastLoginFailure = System.currentTimeMillis()
        return retry
    }

    private fun login(username: String, password: String) {
        val body = FormBody.Builder()
            .add("login_username", username)
            .add("login_password", password)
            .add("login", "вход")
            .add("redirect", "index.php")
            .build()
        // authClient (no auth interceptor) avoids recursion; it still carries the
        // Cloudflare handling and shares the cookie jar, so bb_session lands where
        // all the other requests can use it.
        runCatching {
            authClient.newCall(POST("$forumUrl/login.php", headers, body)).execute().close()
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

    private fun trackerUrl(page: Int, query: String = "", sortBySeeders: Boolean = false): String = "$forumUrl/tracker.php".toHttpUrl().newBuilder()
        // RuTracker expects the search term percent-encoded in Windows-1251, not UTF-8,
        // otherwise Cyrillic queries return nothing. addEncodedQueryParameter keeps our
        // cp1251 percent-encoding verbatim (okhttp's addQueryParameter only does UTF-8).
        .addEncodedQueryParameter("nm", URLEncoder.encode(query, "windows-1251"))
        .setQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
        .apply {
            if (sortBySeeders) {
                setQueryParameter("o", "10") // o=10 → sort by seeders
                setQueryParameter("s", "2") // s=2 → descending
            }
        }
        .toString()

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

                // Serve previously resolved covers from memory instead of re-fetching topic pages.
                val cached = coverCache[anime.url]
                if (cached != null) {
                    anime.thumbnail_url = cached
                    return@async anime
                }

                runCatching {
                    gate.withPermit {
                        val doc = client.newCall(GET(baseUrl + anime.url, headers))
                            .awaitSuccess().use { it.asJsoup() }
                        extractThumbnail(doc, doc.selectFirst("div.post_body"))?.let { cover ->
                            coverCache[anime.url] = cover
                            anime.thumbnail_url = cover
                        }
                        anime
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
            ?: document.selectFirst("""a[href^="magnet:"]""")?.attr("href")
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

        // Download the .torrent ourselves (authenticated — dl.php requires the login cookie,
        // generic helpers fetch without it and just waste a full download) and parse the bencode.
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
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            default = DOMAINS.first(),
            title = "Зеркало / Domain",
            summary = "%s\nПри смене зеркала нужно заново войти в аккаунт.",
            entries = DOMAINS,
            entryValues = DOMAINS,
        )

        screen.addSwitchPreference(
            key = PREF_GRID_COVERS_KEY,
            default = PREF_GRID_COVERS_DEFAULT,
            title = "Обложки в списке",
            summary = "Подгружать постеры прямо в сетку поиска и «Последние». " +
                "Медленнее и создаёт больше запросов к трекеру — можно отключить.",
        )

        val username = preferences.getString(PREF_USERNAME_KEY, "").orEmpty()
        screen.addEditTextPreference(
            key = PREF_USERNAME_KEY,
            default = "",
            title = "Логин / Username",
            summary = username.ifBlank { "Введите имя пользователя RuTracker" },
            getSummary = { it.ifBlank { "Введите имя пользователя RuTracker" } },
        )

        val password = preferences.getString(PREF_PASSWORD_KEY, "").orEmpty()
        screen.addEditTextPreference(
            key = PREF_PASSWORD_KEY,
            default = "",
            title = "Пароль / Password",
            summary = "•".repeat(password.length).ifBlank { "Введите пароль RuTracker" },
            getSummary = { "•".repeat(it.length).ifBlank { "Введите пароль RuTracker" } },
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
    }

    companion object {
        private val DOMAINS = listOf(
            "https://rutracker.org",
            "https://rutracker.net",
        )

        private const val PAGE_SIZE = 50
        private const val DESC_LIMIT = 2000
        private const val COVER_CONCURRENCY = 8

        // In-memory cover cache: topic url → resolved thumbnail url.
        private val coverCache = ConcurrentHashMap<String, String>()

        private val WHITESPACE_REGEX = Regex("""\s+""")

        // Sections to keep — movies, series, documentaries, TV/entertainment shows, anime,
        // cartoons (roots match all declensions/subforums).
        private val ALLOWED_SECTION_KEYWORDS = listOf(
            "кино",
            "фильм",
            "сериал",
            "документал",
            "теленовелл",
            "мультсериал",
            "мультфил",
            "передач",
            "шоу",
            "аниме",
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
        private const val PEEK_SIZE = 65_536L
        private const val LOGGED_IN_MARKER = "logged-in-username"
        private const val LOGIN_RETRY_INTERVAL_MS = 60_000L

        private const val PREF_DOMAIN_KEY = "domain"
        private const val PREF_USERNAME_KEY = "username"
        private const val PREF_PASSWORD_KEY = "password"
        private const val PREF_GRID_COVERS_KEY = "grid_covers"
        private const val PREF_GRID_COVERS_DEFAULT = false
    }
}
