package eu.kanade.tachiyomi.animeextension.ru.rutracker

import android.text.InputType
import android.webkit.WebSettings
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.applicationContext
import keiyoushi.utils.formatBytes
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
import kotlin.coroutines.cancellation.CancellationException

class RuTracker :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "RuTracker"

    override val lang = "ru"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Read on every access, so a mirror switch applies without a restart. Login cookies are bound
    // to the host, so a switch also allows an immediate new login.
    override val baseUrl: String
        get() {
            val domain = preferences.getString(PREF_DOMAIN_KEY, null).orEmpty().ifBlank { DOMAINS.first() }
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

    // Cloudflare binds cf_clearance to the client that solved the challenge. The app solves it in a
    // Chromium WebView, so the requests have to present the WebView's own User-Agent.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", webViewUserAgent)

    private val webViewUserAgent: String by lazy {
        runCatching { WebSettings.getDefaultUserAgent(applicationContext) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_USER_AGENT
    }

    override val client = network.client.newBuilder()
        .addInterceptor(::refererInterceptor)
        .addInterceptor(::authInterceptor)
        .build()

    // `headers` is built once, while the mirror can change, so the Referer follows the request host.
    private fun refererInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Referer") != null) return chain.proceed(request)

        val referer = "${request.url.scheme}://${request.url.host}/"
        return chain.proceed(request.newBuilder().header("Referer", referer).build())
    }

    private val loginLock = Any()

    // Time of the last rejected login: don't retry it on every response, but recover after a while.
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

        synchronized(loginLock) {
            if (!loginTemporarilyBlocked()) login(username, password)
        }
        response.close()
        val retry = chain.proceed(request)

        val retryPeek = runCatching { retry.peekBody(PEEK_SIZE).string() }.getOrDefault("")
        if (!retryPeek.contains(LOGGED_IN_MARKER)) lastLoginFailure = System.currentTimeMillis()
        return retry
    }

    // Sent with network.client (no auth interceptor, so no recursion). It shares the cookie jar,
    // so bb_session reaches every other request.
    private fun login(username: String, password: String) {
        val body = FormBody.Builder()
            .add("login_username", username)
            .add("login_password", password)
            .add("login", "вход")
            .add("redirect", "index.php")
            .build()
        val loginHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        runCatching {
            network.client.newCall(POST("$forumUrl/login.php", loginHeaders, body)).execute().close()
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(trackerUrl(page, sortBySeeders = true), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response)

    override suspend fun getPopularAnime(page: Int): AnimesPage = filteredPage("popular", page) { super.getPopularAnime(it) }.withCovers()

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(trackerUrl(page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = filteredPage("latest", page) { super.getLatestUpdates(it) }.withCovers()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(trackerUrl(page, query = prepareQuery(query)), headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimePage(response)

    // RuTracker ANDs every word of the query, so a natural wording often finds nothing. Before
    // showing an empty result, retry without service words and with prefix wildcards.
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val result = searchPage(page, query, filters)
        if (result.animes.isNotEmpty() || result.hasNextPage || page > 1 || !smartQuery()) return result.withCovers()

        fallbackQueries(query).forEach { candidate ->
            val retry = searchPage(page, candidate, filters)
            if (retry.animes.isNotEmpty()) return retry.withCovers()
        }
        return result.withCovers()
    }

    private suspend fun searchPage(page: Int, query: String, filters: AnimeFilterList): AnimesPage = filteredPage("search|$query", page) { super.getSearchAnime(it, query, filters) }

    // «*» makes a word a prefix match; RuTracker allows at most two of them per query.
    private fun prepareQuery(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || '*' in trimmed) return trimmed
        return when (preferences.getString(PREF_QUERY_MODE_KEY, PREF_QUERY_MODE_DEFAULT)) {
            QUERY_MODE_PREFIX_LAST -> withWildcards(trimmed.split(WHITESPACE_REGEX), 1)
            QUERY_MODE_PREFIX_ALL -> withWildcards(trimmed.split(WHITESPACE_REGEX), MAX_WILDCARDS)
            else -> trimmed
        }
    }

    private fun withWildcards(words: List<String>, count: Int): String {
        val from = (words.size - count).coerceAtLeast(0)
        return words.mapIndexed { index, word -> if (index >= from) word.withWildcard() else word }
            .joinToString(" ")
    }

    // «*» needs at least 3 characters before it and must not break the search operators.
    private fun String.withWildcard(): String = if (length >= MIN_WILDCARD_WORD_LENGTH && !endsWith("*") && none { it in QUERY_OPERATORS }) "$this*" else this

    private fun smartQuery(): Boolean = preferences.getBoolean(PREF_SMART_QUERY_KEY, PREF_SMART_QUERY_DEFAULT)

    private fun fallbackQueries(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || '*' in trimmed) return emptyList()

        val words = trimmed.split(WHITESPACE_REGEX)
        val withoutServiceWords = words.filterNot { it.lowercase(Locale.ROOT) in QUERY_STOP_WORDS }
        val result = mutableListOf<String>()
        if (withoutServiceWords.isNotEmpty() && withoutServiceWords.size < words.size) {
            result += withoutServiceWords.joinToString(" ")
        }
        result += withWildcards(withoutServiceWords.ifEmpty { words }, MAX_WILDCARDS)

        return result.map { it.trim() }
            .filter { it.isNotEmpty() && it != trimmed }
            .distinct()
    }

    private fun trackerUrl(page: Int, query: String = "", sortBySeeders: Boolean = false): String = "$forumUrl/tracker.php".toHttpUrl().newBuilder()
        // RuTracker expects the query percent-encoded in Windows-1251, not UTF-8.
        .addEncodedQueryParameter("nm", URLEncoder.encode(query, "windows-1251"))
        .setQueryParameter("start", ((page - 1) * PAGE_SIZE).toString())
        .apply {
            sectionIds().forEach { addEncodedQueryParameter("f%5B%5D", it) }

            if (sortBySeeders) {
                setQueryParameter("o", "10") // seeders
                setQueryParameter("s", "2") // descending
            } else if (query.isNotEmpty()) {
                sortField()?.let { setQueryParameter("o", it) }
                sortDirection()?.let { setQueryParameter("s", it) }
            }
        }
        .toString()

    private fun sectionIds(): List<String> = splitSetting(preferences.getString(PREF_SECTIONS_KEY, ""))

    private fun sortField(): String? = when (preferences.getString(PREF_SORT_KEY, PREF_SORT_DEFAULT)) {
        SORT_DATE -> "1"
        SORT_SEEDERS -> "10"
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

    // The app stops paging on an empty page, but the section filter can empty a whole tracker page
    // (all music, say). Such pages are skipped, and the next app page continues after them.
    private val rawPages = ConcurrentHashMap<String, Int>()

    private suspend fun filteredPage(key: String, page: Int, fetch: suspend (Int) -> AnimesPage): AnimesPage {
        if (page == 1) rawPages.keys.removeAll { it.startsWith("$key|") }

        var raw = if (page == 1) 1 else rawPages["$key|$page"] ?: page
        var result = fetch(raw)
        var skipped = 0
        while (result.animes.isEmpty() && result.hasNextPage && skipped < MAX_SKIPPED_PAGES) {
            raw++
            skipped++
            result = fetch(raw)
        }
        rawPages["$key|${page + 1}"] = raw + 1

        return result
    }

    private fun parseAnimePage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val rows = document.select("#tor-tbl tbody tr.tCenter")
        // A full raw page means more results, however many are left after the section filter.
        val hasNextPage = rows.size >= PAGE_SIZE
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
                title = link.text()
            }
        }
        return AnimesPage(animes, hasNextPage)
    }

    // The search form on the tracker page holds the whole section tree: one <optgroup> per
    // top-level category, so every forum id maps to its category without another request.
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

    // Results name the leaf subforum («История (HD Video)»), so the keywords are matched against
    // its top-level category («Кино, Видео и ТВ», «Музыка») instead.
    private fun isAllowedSection(category: String?, section: String): Boolean {
        if (!preferences.getBoolean(PREF_SECTION_FILTER_KEY, PREF_SECTION_FILTER_DEFAULT)) return true

        val name = (category ?: section).lowercase(Locale.ROOT)
        if (name.isBlank()) return true
        if (sectionKeywords(PREF_SECTION_BLOCK_KEY, DEFAULT_BLOCKED_SECTION_KEYWORDS).any { it in name }) return false

        // Without a category only the blocked list applies: the leaf name alone would hide valid releases.
        if (category == null) return true

        val allowed = sectionKeywords(PREF_SECTION_ALLOW_KEY, DEFAULT_ALLOWED_SECTION_KEYWORDS)
        return allowed.isEmpty() || allowed.any { it in name }
    }

    private fun sectionKeywords(key: String, fallback: String): List<String> = splitSetting(preferences.getString(key, fallback))
        .map { it.lowercase(Locale.ROOT) }

    // Tracker listings have no posters, so the covers are optionally read from the topic pages.
    private val coverCache = ConcurrentHashMap<String, String>()

    private suspend fun AnimesPage.withCovers(): AnimesPage = coroutineScope {
        val enabled = preferences.getBoolean(PREF_GRID_COVERS_KEY, PREF_GRID_COVERS_DEFAULT)
        if (!enabled || animes.isEmpty()) return@coroutineScope this@withCovers

        if (coverCache.size > MAX_CACHED_COVERS) coverCache.clear()
        val gate = Semaphore(COVER_CONCURRENCY)
        val enriched = animes.map { anime ->
            async {
                if (!anime.thumbnail_url.isNullOrBlank()) return@async anime

                coverCache[anime.url]?.let { cached ->
                    anime.thumbnail_url = cached
                    return@async anime
                }

                catching {
                    gate.withPermit {
                        val doc = client.get(baseUrl + anime.url, headers).use { it.asJsoup() }
                        extractThumbnail(doc, doc.selectFirst("div.post_body"))?.let { cover ->
                            coverCache[anime.url] = cover
                            anime.thumbnail_url = cover
                        }
                    }
                }
                anime
            }
        }.awaitAll()

        AnimesPage(enriched, hasNextPage)
    }

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val post = document.selectFirst("div.post_body")
        return SAnime.create().apply {
            title = document.selectFirst("h1.maintitle")!!.text()
            thumbnail_url = extractThumbnail(document, post)
            description = extractDescription(post)
            genre = document.select("td.nav.t-breadcrumb-top a, .nav.w100 a").eachText()
                .filter { it.isNotEmpty() && !it.equals("RuTracker.org", ignoreCase = true) }
                .joinToString()
                .ifEmpty { null }
            status = SAnime.COMPLETED
        }
    }

    // Post images are lazy-loaded: the real URL is in the `title` of a <var class="postImg">.
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

    // The first post is plot plus technical specs: keep the part after «Описание» and before the specs.
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

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url, headers).use { it.asJsoup() }
        // The torrent download link names the topic exactly; the topic URL is the fallback.
        val topicId = document.selectFirst("a[href*='dl.php?t=']")?.absUrl("href")?.toHttpUrl()?.queryParameter("t")
            ?: (baseUrl + anime.url).toHttpUrl().queryParameter("t")

        // A multi-file release (a whole season) becomes one episode per video file.
        if (topicId != null) {
            buildEpisodesFromTorrent(topicId)?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        // Otherwise the whole torrent is a single entry.
        val size = document.selectFirst("#tor-size-humn, span.tor-size-humn")?.text()
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

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    private suspend fun buildEpisodesFromTorrent(topicId: String): List<SEpisode>? {
        // dl.php needs the login cookie, which the client adds.
        val bytes = downloadTorrent("$forumUrl/dl.php?t=$topicId") ?: return null
        return runCatching {
            val meta = Torrent.parse(bytes)
            toEpisodes(meta.infoHashHex, meta.trackers, meta.files)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun downloadTorrent(dlUrl: String): ByteArray? = catching {
        client.get(dlUrl, headers).use { resp ->
            val bytes = resp.body.bytes()
            // A real .torrent is a bencoded dict starting with 'd'; a login page is not.
            if (bytes.firstOrNull() == 'd'.code.toByte()) bytes else null
        }
    }.getOrNull()

    private fun toEpisodes(infoHash: String, trackers: List<String>, files: List<Torrent.Entry>): List<SEpisode> {
        val trackerParams = trackers
            .filter { it.isNotBlank() }
            .joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
        val magnetBase = "magnet:?xt=urn:btih:$infoHash$trackerParams"
        return files
            .filter { it.path.substringAfterLast('.').lowercase(Locale.ROOT) in VIDEO_EXTENSIONS }
            .sortedBy { it.path.lowercase(Locale.ROOT) }
            .mapIndexed { number, file ->
                SEpisode.create().apply {
                    url = "$magnetBase&index=${file.index}"
                    name = file.path.substringAfterLast('/').trim()
                    episode_number = (number + 1).toFloat()
                    scanlator = file.length.formatBytes()
                }
            }
            .reversed()
    }

    // ============================ Video Links =============================

    // The magnet goes straight to the app's torrent server.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Video(videoUrl = episode.url, videoTitle = episode.name, initialized = true),
    ).toHosterList()

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ============================ Preferences =============================

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

        screen.addEditTextPreference(
            key = PREF_USERNAME_KEY,
            default = "",
            title = "Логин / Username",
            summary = "Введите имя пользователя RuTracker",
            getSummary = { it.ifBlank { "Введите имя пользователя RuTracker" } },
        )

        screen.addEditTextPreference(
            key = PREF_PASSWORD_KEY,
            default = "",
            title = "Пароль / Password",
            summary = "Введите пароль RuTracker",
            getSummary = { if (it.isBlank()) "Введите пароль RuTracker" else "••••••••" },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
    }

    companion object {
        private val DOMAINS = listOf(
            "https://rutracker.org",
            "https://rutracker.net",
        )

        // Only when the WebView can't report its UA (it throws while the provider updates).
        private const val FALLBACK_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 50
        private const val MAX_SKIPPED_PAGES = 5
        private const val DESC_LIMIT = 2000
        private const val COVER_CONCURRENCY = 8
        private const val MAX_CACHED_COVERS = 1000

        private val WHITESPACE_REGEX = Regex("""\s+""")

        // Anime and cartoons live in «Кино, Видео и ТВ», so these cover all video categories.
        private const val DEFAULT_ALLOWED_SECTION_KEYWORDS = "кино, документал, сериал"

        // Checked before the allowed list, so «Музыкальное видео» is dropped whatever else matches it.
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

        // A const val can't forward-reference another one, so these come before the defaults.
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

        private const val MAX_WILDCARDS = 2

        // Every word is ANDed, and these are rarely part of a release title.
        private val QUERY_STOP_WORDS = setOf(
            "с", "в", "во", "и", "на", "для", "по", "от", "до", "за", "из",
            "the", "with", "and", "for",
            "сериал", "сериалы", "фильм", "фильмы", "кино", "мультфильм", "аниме",
            "смотреть", "онлайн", "скачать", "торрент", "озвучка", "перевод",
            "русский", "русская", "русские",
        )

        private const val MIN_WILDCARD_WORD_LENGTH = 3
        private val QUERY_OPERATORS = setOf('+', '-', '|', '"', '(', ')')

        private val FORUM_ID_REGEX = Regex("[?&]f=(\\d+)")

        private val SETTING_LIST_REGEX = Regex("[,;\\n]")
    }
}

/** [runCatching] that doesn't swallow coroutine cancellation. */
private inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
