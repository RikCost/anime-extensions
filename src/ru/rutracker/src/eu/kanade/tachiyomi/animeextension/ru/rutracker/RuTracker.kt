package eu.kanade.tachiyomi.animeextension.ru.rutracker

import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
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
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "RuTracker"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy {
        // AndroidX persists an EditText default as soon as the settings screen is opened, so a
        // stale allow list from an earlier build would override the new default. Drop it once
        // (a list the user actually typed himself is left alone).
        val storedAllowList = getString(PREF_SECTION_ALLOW_KEY, null)
        if (storedAllowList == LEGACY_DEFAULT_ALLOWED_SECTION_KEYWORDS ||
            storedAllowList == LEGACY_EMPTY_ALLOWED_SECTION_KEYWORDS
        ) {
            edit().remove(PREF_SECTION_ALLOW_KEY).apply()
        }
    }

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(trackerUrl(page, query = prepareQuery(query)), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimePage(response)

    // RuTracker matches whole words (plus Russian word forms) and ANDs every word, so a natural
    // query such as «Вторая мировая война Том Хэнкс» finds nothing while «Вторая мировая война
    // с Томом Хэнксом» finds the series: the title spells the name «Томом Хэнксом» and the word
    // «Хэнкс» is not in the morphology dictionary, and any extra word («сериал») is simply absent
    // from the title. ``*`` makes a word a prefix match, but RuTracker allows at most two of them
    // per query, so only the last two words are wildcarded.
    private fun prepareQuery(query: String): String {
        val trimmed = query.trim()
        // Already prepared (a retry candidate) — leave it as is.
        if (trimmed.isEmpty() || '*' in trimmed) return trimmed
        return when (preferences.getString(PREF_QUERY_MODE_KEY, PREF_QUERY_MODE_DEFAULT)) {
            QUERY_MODE_PREFIX_LAST -> withWildcards(trimmed.split(WHITESPACE_REGEX), 1)
            QUERY_MODE_PREFIX_ALL -> withWildcards(trimmed.split(WHITESPACE_REGEX), MAX_WILDCARDS)
            else -> trimmed
        }
    }

    // RuTracker accepts at most two "*" per query, so wildcard the last [count] words.
    private fun withWildcards(words: List<String>, count: Int): String {
        val from = (words.size - count).coerceAtLeast(0)
        return words.mapIndexed { index, word -> if (index >= from) word.withWildcard() else word }
            .joinToString(" ")
    }

    // "*" only works after at least 3 characters and must not break RuTracker's search
    // operators (+, -, |, "phrase").
    private fun String.withWildcard(): String = if (length >= MIN_WILDCARD_WORD_LENGTH && !endsWith("*") && none { it in QUERY_OPERATORS }) "$this*" else this

    private fun smartQuery(): Boolean = preferences.getBoolean(PREF_SMART_QUERY_KEY, PREF_SMART_QUERY_DEFAULT)

    // Retry candidates for a query that returned nothing: first without service words (they are
    // ANDed like the rest, so «сериал Вторая мировая война…» matches nothing while «Вторая
    // мировая война…» works), then with prefix wildcards on the last two words.
    private fun fallbackQueries(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || '*' in trimmed) return emptyList()

        val words = trimmed.split(WHITESPACE_REGEX)
        val withoutServiceWords = words.filterNot { it.lowercase(Locale.ROOT) in QUERY_STOP_WORDS }
        val result = mutableListOf<String>()
        if (withoutServiceWords.isNotEmpty() && withoutServiceWords.size < words.size) {
            result += withoutServiceWords.joinToString(" ")
        }

        val base = withoutServiceWords.ifEmpty { words }
        result += withWildcards(base, MAX_WILDCARDS)

        return result.map { it.trim() }
            .filter { it.isNotEmpty() && it != trimmed }
            .distinct()
    }

    private fun trackerUrl(page: Int, query: String = "", sortBySeeders: Boolean = false): String = "$forumUrl/tracker.php".toHttpUrl().newBuilder()
        // RuTracker expects the search term percent-encoded in Windows-1251, not UTF-8,
        // otherwise Cyrillic queries return nothing. addEncodedQueryParameter keeps our
        // cp1251 percent-encoding verbatim (okhttp's addQueryParameter only does UTF-8).
        .addEncodedQueryParameter("nm", URLEncoder.encode(query, "windows-1251"))
        .setQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
        .apply {
            // f[] restricts the search to the sections picked in the settings; it may be
            // repeated, and an empty list keeps the tracker-wide search.
            sectionIds().forEach { addEncodedQueryParameter("f%5B%5D", it) }

            if (sortBySeeders) {
                setQueryParameter("o", "10") // o=10 → sort by seeders
                setQueryParameter("s", "2") // s=2 → descending
            } else if (query.isNotEmpty()) {
                sortField()?.let { setQueryParameter("o", it) }
                sortDirection()?.let { setQueryParameter("s", it) }
            }
        }
        .toString()

    private fun sectionIds(): List<String> = splitSetting(preferences.getString(PREF_SECTIONS_KEY, ""))

    private fun sortField(): String? = when (preferences.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT)) {
        SORT_DATE -> "1" // o=1 → registered (date added)
        SORT_SEEDERS -> "10" // o=10 → seeders
        else -> null
    }

    private fun sortDirection(): String? = when (preferences.getString(PREF_SORT_DIRECTION_KEY, PREF_SORT_DIRECTION_DEFAULT)) {
        SORT_ASC -> "1"
        SORT_DESC -> "2"
        else -> null
    }

    private fun splitSetting(value: String?): List<String> = value.orEmpty()
        .split(SETTING_LIST_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private fun parseAnimePage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val rows = document.select("#tor-tbl tbody tr.tCenter")
        // Base "has next" on the raw page (a full page means more results), not on the
        // count left after category filtering.
        val hasNextPage = rows.size >= PAGE_SIZE
        // The tracker page carries the whole section tree in its own search form:
        // <select name="f[]"> with one <optgroup label="Кино, Видео и ТВ"> per category.
        val categories = sectionCategories(document)

        val animes = rows.mapNotNull { row ->
            val sectionCell = row.selectFirst("td.f-name-col")
            val section = sectionCell?.text().orEmpty()
            val sectionId = sectionCell?.selectFirst("a[href]")?.let { forumId(it.attr("abs:href")) }
            if (!isAllowedSection(sectionId?.let { categories[it] }, section)) return@mapNotNull null

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

    // The tracker page carries the whole section tree in its own search form:
    // <select name="f[]"> with one <optgroup label="Кино, Видео и ТВ"> per category. That maps
    // every forum id to its top-level category without an extra request.
    private fun sectionCategories(document: Document): Map<String, String> {
        val select = document.selectFirst("select#fs-main")
            ?: document.selectFirst("select[name=f\\[\\]]")
            ?: return emptyMap()

        val categories = mutableMapOf<String, String>()
        select.select("optgroup").forEach { group ->
            val category = group.attr("label").trim()
            if (category.isNotEmpty()) {
                group.select("option[value]").forEach { option -> categories[option.attr("value")] = category }
            }
        }
        return categories
    }

    private fun forumId(href: String): String? = FORUM_ID_REGEX.find(href)?.groupValues?.get(1)

    // RuTracker lists the *leaf* subforum in the results — «История (HD Video)», «Аркады»,
    // «Песни», «Компьютерные» — so keywords can be matched against neither the allow nor the
    // blocked list («документал» and «музык» would never fire) and everything leaked through.
    // The section tree above maps the row to its top-level category instead, and the keyword
    // lists are matched against that: «Кино, Видео и ТВ», «Сериалы», «Документалистика и юмор»
    // are kept, while «Музыка», «Книги и журналы», «Игры», «Программы и Дизайн» are dropped.
    private fun isAllowedSection(category: String?, section: String): Boolean {
        if (!preferences.getBoolean(PREF_SECTION_FILTER_KEY, PREF_SECTION_FILTER_DEFAULT)) return true

        val name = (category ?: section).lowercase(Locale.ROOT)
        if (name.isBlank()) return true
        if (sectionKeywords(PREF_SECTION_BLOCK_KEY, DEFAULT_BLOCKED_SECTION_KEYWORDS).any { it in name }) return false

        // Without the section tree (or for a forum it does not list) only the blocked list is
        // applied — guessing from the leaf name would silently hide valid releases.
        if (category == null) return true

        val allowed = sectionKeywords(PREF_SECTION_ALLOW_KEY, DEFAULT_ALLOWED_SECTION_KEYWORDS)
        return allowed.isEmpty() || allowed.any { it in name }
    }

    private fun sectionKeywords(key: String, fallback: String): List<String> = splitSetting(preferences.getString(key, fallback))
        .map { it.lowercase(Locale.ROOT) }

    // ─── Grid covers ────────────────────────────────────────────────────────────
    // Tracker listings have no posters, so (optionally) fetch each topic's cover in parallel
    // to show thumbnails right in the browse grid instead of only after opening a title.

    override suspend fun getPopularAnime(page: Int): AnimesPage = super.getPopularAnime(page).withCovers()

    override suspend fun getLatestUpdates(page: Int): AnimesPage = super.getLatestUpdates(page).withCovers()

    // An empty result may just mean that RuTracker did not like the wording, so retry with a
    // cleaned up query before showing «ничего не найдено».
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val result = super.getSearchAnime(page, query, filters)
        if (result.animes.isNotEmpty() || page > 1 || !smartQuery()) return result.withCovers()

        fallbackQueries(query).forEach { candidate ->
            val retry = super.getSearchAnime(page, candidate, filters)
            if (retry.animes.isNotEmpty()) return retry.withCovers()
        }
        return result.withCovers()
    }

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

        // ─── Поиск ───────────────────────────────────────────────────────────────
        screen.addSwitchPreference(
            key = PREF_SMART_QUERY_KEY,
            default = PREF_SMART_QUERY_DEFAULT,
            title = "Умный подбор запроса",
            summary = "Если поиск ничего не нашёл, повторить его без служебных слов (сериал, «с», " +
                "«онлайн») и с «*» на двух последних словах. RuTracker ищет строго по словоформам, " +
                "поэтому «Вторая мировая война Том Хэнкс» без этого не находит сериал " +
                "«Вторая мировая война с Томом Хэнксом».",
        )

        screen.addListPreference(
            key = PREF_QUERY_MODE_KEY,
            default = PREF_QUERY_MODE_DEFAULT,
            title = "Поиск по части слова (*)",
            summary = "%s\nRuTracker ищет целые слова и разрешает не более двух «*» на запрос. " +
                "Со «*» слово ищется по началу (нужно минимум 3 буквы до *), так «Handcrafte*» " +
                "найдёт «Handcrafted».",
            entries = listOf(
                "Точно как на сайте",
                "Добавлять * к последнему слову",
                "Добавлять * к двум последним словам",
            ),
            entryValues = listOf(QUERY_MODE_EXACT, QUERY_MODE_PREFIX_LAST, QUERY_MODE_PREFIX_ALL),
        )

        screen.addEditTextPreference(
            key = PREF_SECTIONS_KEY,
            default = "",
            title = "Искать только в разделах (ID)",
            summary = "Все разделы",
            getSummary = { if (it.isBlank()) "Все разделы" else "Разделы: $it" },
            dialogMessage = "ID разделов через запятую (по одному f[] на каждый).\n" +
                "Пусто — поиск по всем разделам трекера.\n\n" +
                "Частые ID: 7 — Фильмы, 22 — Наше кино, 33 — Аниме, 46 — Документальные фильмы " +
                "и телепередачи, 124 — Арт-хаус и авторское кино, 314 — Документальные (HD Video), " +
                "2198 — HD Video, 4 — Мультфильмы, 921 — Мультсериалы.",
        )

        screen.addListPreference(
            key = PREF_SORT_KEY,
            default = PREF_SORT_DEFAULT,
            title = "Сортировка результатов поиска",
            summary = "%s",
            entries = listOf("Как на сайте", "По дате добавления", "По сидам"),
            entryValues = listOf(PREF_SORT_DEFAULT, SORT_DATE, SORT_SEEDERS),
        )

        screen.addListPreference(
            key = PREF_SORT_DIRECTION_KEY,
            default = PREF_SORT_DIRECTION_DEFAULT,
            title = "Направление сортировки",
            summary = "%s",
            entries = listOf("По убыванию", "По возрастанию"),
            entryValues = listOf(SORT_DESC, SORT_ASC),
        )

        screen.addSwitchPreference(
            key = PREF_SECTION_FILTER_KEY,
            default = PREF_SECTION_FILTER_DEFAULT,
            title = "Фильтр по разделам (результаты)",
            summary = "Оставлять кино, сериалы и документалистику, убирать музыку, книги, игры, " +
                "программы и прочее. Категория раздачи берётся из дерева разделов на странице " +
                "трекера; выключите, если что-то нужное всё ещё скрывается.",
        )

        screen.addEditTextPreference(
            key = PREF_SECTION_ALLOW_KEY,
            default = DEFAULT_ALLOWED_SECTION_KEYWORDS,
            title = "Разрешённые разделы (слова)",
            summary = DEFAULT_ALLOWED_SECTION_KEYWORDS,
            getSummary = { it.ifBlank { "Пусто — оставлять все разделы (кроме исключаемых)" } },
            dialogMessage = "Ключевые слова через запятую. Сравниваются с КАТЕГОРИЕЙ раздачи " +
                "(«Кино, Видео и ТВ», «Сериалы», «Документалистика и юмор», «Музыка»…), а не " +
                "с листовым подфорумом («История (HD Video)»).\n\n" +
                "Пустое поле отключает проверку — останется только список исключений. " +
                "Все категории трекера: Новости, Кино, Видео и ТВ, Сериалы, Документалистика " +
                "и юмор, Спорт, Книги и журналы, Обучение иностранным языкам, Обучающие видео, " +
                "Аудиокниги, Авто и мото, Музыка, Популярная музыка, Джазовая и Блюзовая музыка, " +
                "Рок-музыка, Электронная музыка, Hi-Res форматы, оцифровки, Музыкальное видео, " +
                "Игры, Программы и Дизайн, Мобильные устройства, Apple, Разное.",
        )

        screen.addEditTextPreference(
            key = PREF_SECTION_BLOCK_KEY,
            default = DEFAULT_BLOCKED_SECTION_KEYWORDS,
            title = "Исключаемые разделы (слова)",
            summary = DEFAULT_BLOCKED_SECTION_KEYWORDS,
            getSummary = { it.ifBlank { "Пусто — ничего не исключать" } },
            dialogMessage = "Ключевые слова через запятую. Сравниваются с категорией раздачи " +
                "и проверяются раньше разрешённых (например «музык» убирает «Музыкальное видео»).",
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

        // Matched against the *category* of a result («Кино, Видео и ТВ», «Сериалы»,
        // «Документалистика и юмор»), taken from the section tree on the tracker page. Anime and
        // cartoons live inside «Кино, Видео и ТВ», so those three words cover all video content.
        private const val DEFAULT_ALLOWED_SECTION_KEYWORDS = "кино, документал, сериал"

        // Defaults shipped by the previous builds of these settings; kept only to clean them up
        // on preference load.
        private const val LEGACY_DEFAULT_ALLOWED_SECTION_KEYWORDS =
            "кино, фильм, сериал, документал, теленовелл, мультсериал, мультфил, передач, шоу, аниме"
        private const val LEGACY_EMPTY_ALLOWED_SECTION_KEYWORDS = ""

        // Categories to drop even if a keyword above accidentally matches (e.g. «Музыкальное
        // видео» passes the allow list only for «кино», but «музык» stops it).
        private const val DEFAULT_BLOCKED_SECTION_KEYWORDS =
            "игр, софт, программ, прошивк, приложени, " +
                "книг, журнал, газет, литератур, проза, поэзи, фольклор, " +
                "комикс, манга, учебник, справочник, энциклопед, обучени, " +
                "аудиокниг, аудио, подкаст, радиоспектакл, музык, дискограф, саундтрек, " +
                "спорт, обои, картинк, фотограф, порно, хентай, эротик"

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

        // Search behaviour. The values are stored as strings so the defaults can be shown as
        // the current entry of the corresponding ListPreference. The constants they refer to
        // must be declared above them: a 'const val' cannot forward-reference another one.
        private const val QUERY_MODE_EXACT = "exact"
        private const val QUERY_MODE_PREFIX_LAST = "prefix_last"
        private const val QUERY_MODE_PREFIX_ALL = "prefix_all"

        private const val SORT_SITE = "site"
        private const val SORT_DATE = "date"
        private const val SORT_SEEDERS = "seeders"
        private const val SORT_DESC = "desc"
        private const val SORT_ASC = "asc"

        private const val PREF_QUERY_MODE_KEY = "query_mode"
        private const val PREF_QUERY_MODE_DEFAULT = QUERY_MODE_EXACT
        private const val PREF_SECTIONS_KEY = "sections"
        private const val PREF_SORT_KEY = "sort"
        private const val PREF_SORT_DEFAULT = SORT_SITE
        private const val PREF_SORT_DIRECTION_KEY = "sort_direction"
        private const val PREF_SORT_DIRECTION_DEFAULT = SORT_DESC
        private const val PREF_SECTION_FILTER_KEY = "section_filter"
        private const val PREF_SECTION_FILTER_DEFAULT = true
        private const val PREF_SECTION_ALLOW_KEY = "section_allow"
        private const val PREF_SECTION_BLOCK_KEY = "section_block"
        private const val PREF_SMART_QUERY_KEY = "smart_query"
        private const val PREF_SMART_QUERY_DEFAULT = true

        // RuTracker rejects a query with more than two "*".
        private const val MAX_WILDCARDS = 2

        // Words that make RuTracker return nothing because every word is ANDed and these are not
        // in the title: «сериал Вторая мировая война…» finds nothing, «Вторая мировая война…» does.
        private val QUERY_STOP_WORDS = setOf(
            "с", "в", "во", "и", "на", "для", "по", "от", "до", "за", "из",
            "the", "with", "and", "for",
            "сериал", "сериалы", "фильм", "фильмы", "кино", "мультфильм", "аниме",
            "смотреть", "онлайн", "скачать", "торрент", "озвучка", "перевод",
            "русский", "русская", "русские",
        )

        // "*" only works after at least 3 characters before it.
        private const val MIN_WILDCARD_WORD_LENGTH = 3
        private val QUERY_OPERATORS = setOf('+', '-', '|', '"', '(', ')')

        // Section links look like tracker.php?f=2166&amp;nm=… — the forum id maps to a category.
        private val FORUM_ID_REGEX = Regex("[?&]f=(\\d+)")

        // Settings that hold a list are entered as comma, semicolon or newline separated text.
        private val SETTING_LIST_REGEX = Regex("[,;\\n]")
    }
}
