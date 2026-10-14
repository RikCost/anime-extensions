package eu.kanade.tachiyomi.animeextension.all.yandexdisk

import android.content.SharedPreferences
import android.net.Uri
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.utils.Source
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.formatBytes
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.CacheControl
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@Suppress("unused")
class YandexDisk : Source() {

    override val name = "Yandex Disk"

    override val lang = "all"

    override val baseUrl = API_BASE

    override val supportsLatest = false

    // A disk has nothing to recommend, and related-by-search would scan the whole library with
    // every word of the title.
    override val supportsRelatedAnimes = false

    override val disableRelatedAnimesBySearch = true

    // SimpleDateFormat isn't thread-safe and folders are listed in parallel, so each listing gets its own.
    private fun apiDateFormat() = SimpleDateFormat(API_DATE_PATTERN, Locale.ROOT)

    // WebDAV reports dates in RFC 1123 format, with English day and month names.
    private fun httpDateFormat() = SimpleDateFormat(HTTP_DATE_PATTERN, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    private fun airedDateFormat() = SimpleDateFormat(AIRED_DATE_PATTERN, Locale.ROOT)

    override val client = network.client.newBuilder()
        .addInterceptor(::coverInterceptor)
        .addInterceptor(::previewInterceptor)
        .addInterceptor(::authInterceptor)
        .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val publicEntries = if (page == 1) publicLinkEntries() else emptyList()

        if (!hasPrivateAccess()) {
            if (publicEntries.isEmpty()) throw IOException(MISSING_CREDENTIALS)
            return AnimesPage(publicEntries.map { it.toSAnime() }, false)
        }

        val roots = rootPaths()
        val (entries, hasNextPage) = if (roots.size == 1) {
            listEntries(roots.single(), null, page, CacheControl.FORCE_NETWORK)
        } else {
            // Paging across several folders isn't worth it: they're all listed on the first page.
            (if (page == 1) listRoots(roots) else emptyList()) to false
        }
        val animes = (publicEntries + entries.toTitles()).map { it.toSAnime() }

        return AnimesPage(animes, hasNextPage)
    }

    /** Entries of every library folder; one that can't be listed doesn't hide the others. */
    private suspend fun listRoots(roots: List<String>): List<DiskEntry> {
        val results = coroutineScope {
            roots.map { root -> async { catching { listAll(root, null, CacheControl.FORCE_NETWORK) } } }.awaitAll()
        }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!

        return results.flatMap { it.getOrDefault(emptyList()) }
    }

    // =============================== Search ===============================

    // Results of the last search; later pages are cut from it. Keyed by the query, so that two
    // searches running at once (global search) can't serve each other's pages.
    @Volatile
    private var searchCache: Pair<String, List<DiskEntry>> = "" to emptyList()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        query.asPublicLink()?.let { link ->
            return AnimesPage(publicTitles(link).map { it.toSAnime() }, false)
        }

        if (query.isBlank()) return getPopularAnime(page)

        val trimmed = query.trim()
        val searchResults = searchCache.takeIf { page > 1 && it.first == trimmed }?.second
            ?: searchTitles(trimmed).also { searchCache = trimmed to it }

        val startIndex = (page - 1) * PAGE_SIZE
        val pageEntries = searchResults.drop(startIndex).take(PAGE_SIZE)

        return AnimesPage(pageEntries.map { it.toSAnime() }, startIndex + pageEntries.size < searchResults.size)
    }

    private suspend fun searchTitles(query: String): List<DiskEntry> {
        val entries = publicLinkEntries().toMutableList()
        if (hasPrivateAccess()) {
            for (root in rootPaths()) {
                var listPage = 1
                while (entries.size < SEARCH_SCAN_LIMIT) {
                    val (items, hasNextPage) = listEntries(root, null, listPage, CacheControl.FORCE_NETWORK)
                    entries += items.toTitles()
                    if (!hasNextPage) break
                    listPage++
                }
            }
        }

        return entries.filter { it.name.contains(query, true) }
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val ref = anime.url.parseAs<EntryRef>()
        val folder = if (ref.isDir) ref.path else ref.path.parentPath()
        val children = catching { listAll(folder, ref.publicKey) }.getOrDefault(emptyList())
        val info = catching { readTitleInfo(ref, children) }.getOrNull()
        val file = if (ref.isDir) null else DiskEntry(name = ref.path.substringAfterLast('/'), path = ref.path, isDir = false, publicKey = ref.publicKey)

        return anime.apply {
            info?.title?.let { title = it }
            thumbnail_url = if (file == null) {
                coverUrl(ref.path, ref.publicKey)
            } else {
                children.firstOrNull { it.isImage() && it.name.withoutExtension().equals(file.name.withoutExtension(), true) }
                    ?.let { thumbnailUrl(it) }
                    ?: thumbnailUrl(file)
            }
            description = info?.description ?: buildString {
                ref.publicKey?.let { append("Public link: ").append(it).append('\n') }
                if (ref.publicKey == null || ref.path != "/") append("Path: ").append(ref.path)
            }.trim()
            genre = info?.genre
            author = info?.author
            artist = info?.artist
            info?.completed?.let { status = if (it) SAnime.COMPLETED else SAnime.ONGOING }
            update_strategy = if (ref.isDir) AnimeUpdateStrategy.ALWAYS_UPDATE else AnimeUpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    /**
     * Title metadata is optional. A Kodi `tvshow.nfo`/`movie.nfo` wins over a plain text file; for a
     * folder, its first subfolders are searched too, so release folders with the notes inside work.
     * A single video uses the `.nfo`/`.txt` with the same name next to it.
     */
    private suspend fun readTitleInfo(ref: EntryRef, children: List<DiskEntry>): TitleInfo? {
        val baseName = ref.path.substringAfterLast('/').withoutExtension()
        val subfolders = if (ref.isDir) children.filter { it.isDir && !it.name.isIgnoredFolder() }.take(MAX_INFO_SUBFOLDERS) else emptyList()

        for (index in -1 until subfolders.size) {
            val entries = when {
                index >= 0 -> listAll(subfolders[index].path, ref.publicKey)
                ref.isDir -> children
                else -> children.filter { it.name.withoutExtension().equals(baseName, true) }
            }

            val nfo = if (ref.isDir) entries.findTitleNfo() else entries.firstOrNull { it.isNfo() }
            val nfoInfo = nfo?.let { catching { parseTitleNfo(readText(it)) }.getOrNull() }
            val text = entries.findInfoText()?.let { catching { parseInfoText(readText(it)) }.getOrNull() }
            if (nfoInfo == null && text == null) continue

            return TitleInfo(
                title = nfoInfo?.title,
                description = nfoInfo?.description ?: text?.description,
                genre = nfoInfo?.genre ?: text?.genre,
                author = nfoInfo?.author ?: text?.author,
                artist = nfoInfo?.artist ?: text?.artist,
                completed = nfoInfo?.completed,
            )
        }

        return null
    }

    override fun getAnimeUrl(anime: SAnime): String = webUrl(anime.url.parseAs<EntryRef>())

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val ref = anime.url.parseAs<EntryRef>()
        if (!ref.isDir) {
            val name = ref.path.substringAfterLast('/').ifEmpty { anime.title }
            val entry = DiskEntry(name = name, path = ref.path, isDir = false, publicKey = ref.publicKey)
            return listOf(
                SEpisode.create().apply {
                    this.name = entry.displayName().ifEmpty { anime.title }
                    url = entry.toRef().toJsonString()
                    episode_number = 1F
                },
            )
        }

        val files = collectFiles(ref.path, ref.publicKey, preferences.includeSubfolders, CacheControl.FORCE_NETWORK)
        val groups = groupEpisodes(files, ref.path)
        val (infos, previews) = coroutineScope {
            val infos = async { readEpisodeInfos(groups, files) }
            val previews = async { episodePreviews(groups) }
            infos.await() to previews.await()
        }
        val singleSeason = groups.mapNotNull { it.key.season }.distinct().size <= 1
        val duplicateNames = groups.groupingBy { it.primary.displayName() }.eachCount().filterValues { it > 1 }.keys
        val airedDateFormat = airedDateFormat()

        val episodes = groups.mapIndexed { index, group ->
            val primary = group.primary
            val info = infos[group.key.key]
            val episodeNumber = group.key.episode

            SEpisode.create().apply {
                name = when {
                    info?.title != null && episodeNumber != null && singleSeason -> "$episodeNumber. ${info.title}"
                    info?.title != null && episodeNumber != null -> "S${group.key.season ?: 1}E$episodeNumber. ${info.title}"
                    info?.title != null -> info.title
                    primary.displayName() in duplicateNames -> primary.path.relativeTo(ref.path).withoutExtension().replace("/", " / ")
                    else -> primary.displayName()
                }
                // Keyed by the episode, not by the file: the best quality, and with it the
                // file played first, changes when another quality of the episode is added.
                url = EntryRef(
                    path = ref.path,
                    isDir = false,
                    publicKey = ref.publicKey,
                    root = ref.path,
                    key = group.key.key,
                ).toJsonString()
                episode_number = (if (singleSeason) episodeNumber?.toFloat() else null) ?: (index + 1).toFloat()
                date_upload = info?.aired?.let { airedDateFormat.tryParse(it) }?.takeIf { it > 0 } ?: primary.modified
                summary = info?.plot
                scanlator = group.variants.mapNotNull { it.quality() }.distinct().joinToString { "${it}p" }
                    .ifEmpty { primary.size.takeIf { it > 0 }?.formatBytes() }
                preview_url = previews[group.key.key]
            }
        }

        val extras = if (preferences.onlyVideoFiles) {
            emptyList()
        } else {
            files.filter { !it.isVideo() && !it.isSample() }
                .sortedBy { it.path.sortKey() }
                .map { file ->
                    SEpisode.create().apply {
                        name = file.path.relativeTo(ref.path).replace("/", " / ")
                        url = file.toRef().toJsonString()
                        date_upload = file.modified
                        scanlator = file.size.takeIf { it > 0 }?.formatBytes()
                    }
                }
        }

        return (episodes + extras).reversed()
    }

    override fun getEpisodeUrl(episode: SEpisode): String {
        val ref = episode.url.parseAs<EntryRef>()
        val folder = if (ref.key != null) ref.path else ref.path.parentPath()
        return webUrl(EntryRef(folder, true, ref.publicKey))
    }

    /** One episode per key; the same episode in other folders or qualities becomes another video. */
    private fun groupEpisodes(files: List<DiskEntry>, root: String): List<EpisodeGroup> = files
        .filter { it.isVideo() && !it.isSample() }
        .groupBy { it.episodeKey(root).key }
        .values
        .map { variants ->
            val sorted = variants.sortedWith(compareByDescending<DiskEntry> { it.quality() ?: 0 }.thenBy { it.path.sortKey() })
            EpisodeGroup(sorted.first().episodeKey(root), sorted)
        }
        .sortedBy { it.orderKey() }

    private suspend fun readEpisodeInfos(groups: List<EpisodeGroup>, files: List<DiskEntry>): Map<String, EpisodeInfo> {
        val nfoFiles = files.filter { it.isNfo() && it.size in 1..MAX_NFO_BYTES }
            .associateBy { it.path.withoutExtension().lowercase() }
        if (nfoFiles.isEmpty()) return emptyMap()

        val wanted = groups.mapNotNull { group ->
            group.variants.firstNotNullOfOrNull { nfoFiles[it.path.withoutExtension().lowercase()] }?.let { group.key.key to it }
        }
        if (wanted.size > MAX_EPISODE_NFOS) return emptyMap()

        val semaphore = Semaphore(NFO_CONCURRENCY)
        return coroutineScope {
            wanted.map { (key, nfo) ->
                async { semaphore.withPermit { catching { parseEpisodeNfo(readText(nfo)) }.getOrNull()?.let { key to it } } }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /**
     * Episode previews as local files. The app loads them with its own client, without the OAuth
     * header, and never replaces a stored preview URL, so the signed Yandex links would break as
     * soon as they expire. The file name is stable, so a refresh restores a file the system
     * removed from the cache.
     */
    private suspend fun episodePreviews(groups: List<EpisodeGroup>): Map<String, String> {
        if (groups.size > MAX_EPISODE_PREVIEWS) return emptyMap()

        val dir = File(context.cacheDir, PREVIEW_DIR).apply { mkdirs() }
        trimPreviews(dir)
        val semaphore = Semaphore(PREVIEW_CONCURRENCY)

        return coroutineScope {
            groups.mapNotNull { group ->
                val entry = group.variants.firstOrNull { it.preview != null } ?: return@mapNotNull null
                async {
                    semaphore.withPermit {
                        val file = File(dir, previewFileName(entry))
                        val ready = file.exists() || catching { downloadPreview(entry.preview!!, entry.publicKey, file) }.isSuccess
                        if (ready) group.key.key to Uri.fromFile(file).toString() else null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private suspend fun downloadPreview(url: String, publicKey: String?, file: File) {
        val previewHeaders = if (publicKey == null) {
            headers.newBuilder().apply { oauthHeader()?.let { set("Authorization", it) } }.build()
        } else {
            headers
        }

        client.get(url = url, headers = previewHeaders, cacheControl = CacheControl.FORCE_NETWORK).use { response ->
            if (response.body.contentType()?.type != "image") throw IOException("Not an image: $url")

            // Written aside and renamed, so an interrupted download never leaves a broken file behind.
            val partial = File(file.parentFile, "${file.name}.part")
            partial.outputStream().use { response.body.byteStream().copyTo(it) }
            if (!partial.renameTo(file)) {
                partial.delete()
                throw IOException("Can't save the preview")
            }
        }
    }

    /** Drops the oldest previews once there are too many; a refresh of their titles fetches them again. */
    private fun trimPreviews(dir: File) {
        val files = dir.listFiles()?.takeIf { it.size > MAX_STORED_PREVIEWS } ?: return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_STORED_PREVIEWS * 3 / 4).forEach { it.delete() }
    }

    private fun previewFileName(entry: DiskEntry): String {
        val digest = MessageDigest.getInstance("MD5").digest("${entry.publicKey.orEmpty()}|${entry.path}".toByteArray())
        return digest.joinToString("", postfix = ".jpg") { "%02x".format(it) }
    }

    // Folder listings of the last minutes. Details, the episode list, the hoster list and the
    // sidecar search all walk the same folders, mostly right after one another.
    private val listingCache = ConcurrentHashMap<String, Pair<Long, List<DiskEntry>>>()

    /** All entries of a folder. [CacheControl.FORCE_NETWORK] skips the listing cache, but refills it. */
    private suspend fun listAll(path: String, publicKey: String?, cacheControl: CacheControl = DEFAULT_CACHE): List<DiskEntry> {
        // The access method is part of the key: WebDAV and REST listings of the same path differ.
        val cacheKey = "${publicKey ?: preferences.accessMethod}|$path"
        val now = System.currentTimeMillis()
        if (cacheControl != CacheControl.FORCE_NETWORK) {
            listingCache[cacheKey]?.takeIf { now - it.first < LISTING_CACHE_MILLIS }?.let { return it.second }
        }

        val entries = mutableListOf<DiskEntry>()
        var page = 1
        while (entries.size < MAX_FILES) {
            val (items, hasNextPage) = listEntries(path, publicKey, page, cacheControl)
            entries += items
            if (!hasNextPage) break
            page++
        }

        if (listingCache.size >= MAX_CACHED_LISTINGS) listingCache.entries.removeAll { now - it.value.first >= LISTING_CACHE_MILLIS }
        if (listingCache.size >= MAX_CACHED_LISTINGS) listingCache.clear()
        listingCache[cacheKey] = now to entries

        return entries
    }

    private suspend fun collectFiles(
        path: String,
        publicKey: String?,
        recursive: Boolean,
        cacheControl: CacheControl = DEFAULT_CACHE,
        maxFolders: Int = MAX_SCANNED_FOLDERS,
    ): List<DiskEntry> {
        val files = mutableListOf<DiskEntry>()
        var level = listOf(path)
        var scanned = 0
        val semaphore = Semaphore(LIST_CONCURRENCY)

        // Breadth-first, one level at a time: the folders of a level are listed in parallel.
        while (level.isNotEmpty() && scanned < maxFolders && files.size < MAX_FILES) {
            val batch = level.take(maxFolders - scanned)
            scanned += batch.size

            val listings = coroutineScope {
                batch.map { folder -> async { semaphore.withPermit { listAll(folder, publicKey, cacheControl) } } }.awaitAll()
            }

            level = buildList {
                listings.flatten().forEach { entry ->
                    when {
                        !entry.isDir -> files.add(entry)
                        recursive && !entry.name.isIgnoredFolder() -> add(entry.path)
                    }
                }
            }
        }

        return files
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val ref = episode.url.parseAs<EntryRef>()
        val file = DiskEntry(name = ref.path.substringAfterLast('/'), path = ref.path, isDir = false, publicKey = ref.publicKey)
        // Episodes saved before keys were added point to a file; their key is derived from it.
        val variants = ref.root?.let { root -> findVariants(root, ref.publicKey, ref.key ?: file.episodeKey(root).key) }
            ?: if (ref.key == null) listOf(file) else throw IOException("Episode not found. Refresh the episode list.")

        val loadTracks = preferences.externalTracks && !usesWebDav(ref.publicKey)

        val videos = coroutineScope {
            variants.map { variant ->
                async {
                    val sidecars = if (loadTracks) {
                        catching { findSidecars(variant.toRef(), recursive = ref.root != null) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                    val title = buildList {
                        variant.quality()?.let { add("${it}p") }
                        variant.size.takeIf { it > 0 }?.let { add(it.formatBytes()) }
                        sidecars.count { it.isAudio }.takeIf { it > 0 }?.let { add("audio: $it") }
                        sidecars.count { !it.isAudio }.takeIf { it > 0 }?.let { add("subs: $it") }
                    }.joinToString(" • ").ifEmpty { episode.scanlator?.takeIf { it.isNotEmpty() } ?: "Original" }

                    val video = Video(
                        videoTitle = title,
                        resolution = variant.quality(),
                        internalData = VideoData(variant.path, ref.publicKey, sidecars).toJsonString(),
                    )

                    // Download links live for hours, so they're fetched right away: the app hides
                    // errors thrown from resolveVideo, while errors thrown here are shown.
                    catching { resolveVideo(video) }
                }
            }.awaitAll()
        }

        val resolved = videos.mapNotNull { it.getOrNull() }
        if (resolved.isEmpty()) {
            throw videos.firstNotNullOfOrNull { it.exceptionOrNull() } ?: IOException("No video files found")
        }

        return resolved.toHosterList()
    }

    private suspend fun findVariants(root: String, publicKey: String?, key: String): List<DiskEntry>? {
        val files = collectFiles(root, publicKey, preferences.includeSubfolders)
        return groupEpisodes(files, root).firstOrNull { it.key.key == key }?.variants
    }

    override suspend fun resolveVideo(video: Video): Video {
        val data = video.internalData.parseAs<VideoData>()

        if (usesWebDav(data.publicKey)) {
            return video.copy(
                videoUrl = webdavUrl(data.path).toString(),
                headers = webdavHeaders(),
                initialized = true,
            )
        }

        return coroutineScope {
            val videoUrl = async { downloadUrl(data.path, data.publicKey) }
            val tracks = data.sidecars.map { sidecar ->
                async {
                    catching { sidecar to Track(downloadUrl(sidecar.path, data.publicKey), sidecar.label) }.getOrNull()
                }
            }.awaitAll().filterNotNull()

            video.copy(
                videoUrl = videoUrl.await(),
                audioTracks = tracks.filter { it.first.isAudio }.map { it.second },
                subtitleTracks = tracks.filter { !it.first.isAudio }.map { it.second },
                initialized = true,
            )
        }
    }

    /**
     * Anime releases often keep dubs and subtitles next to the video, in folders such as
     * "RUS Sound/[Studio]/" or "Subs/". Files whose name starts with the video name are matched.
     */
    private suspend fun findSidecars(ref: EntryRef, recursive: Boolean): List<Sidecar> {
        val folder = ref.path.parentPath()
        val videoName = ref.path.substringAfterLast('/').withoutExtension()

        return collectFiles(folder, ref.publicKey, recursive, maxFolders = MAX_SIDECAR_FOLDERS)
            .filter { it.isAudio() || it.isSubtitle() }
            .mapNotNull { file ->
                val baseName = file.name.withoutExtension()
                val suffix = when {
                    baseName.equals(videoName, true) -> ""
                    baseName.startsWith("$videoName.", true) -> baseName.substring(videoName.length + 1)
                    else -> return@mapNotNull null
                }
                val subFolder = file.path.parentPath().relativeTo(folder)
                val label = listOf(subFolder, suffix).filter { it.isNotEmpty() }.joinToString(" ")
                    .ifEmpty { file.name.extension().uppercase() }

                Sidecar(path = file.path, label = label, isAudio = file.isAudio())
            }
            .sortedBy { it.label.sortKey() }
    }

    // ============================= Utilities ==============================

    private fun DiskEntry.isListable() = isDir || isVideo() || !preferences.onlyVideoFiles

    /** Entries of a library folder as titles: every folder is its own title. */
    private fun List<DiskEntry>.toTitles(): List<DiskEntry> = filter { entry ->
        if (entry.isDir) !entry.name.isIgnoredFolder() else entry.isListable() && !entry.isSample()
    }

    /**
     * A public link is one title when it's a file, holds videos itself or holds only seasons.
     * Otherwise it's a library and every entry in it is a title.
     */
    private suspend fun publicTitles(link: String): List<DiskEntry> {
        val root = publicRoot(link)
        if (!root.isDir) return listOf(root)

        val children = listAll("/", link)
        val folders = children.filter { it.isDir && !it.name.isIgnoredFolder() }

        return when {
            folders.isEmpty() || children.any { it.isVideo() && !it.isSample() } -> listOf(root)
            folders.all { it.isSeasonFolder() } -> listOf(root)
            else -> children.toTitles()
        }
    }

    private fun DiskEntry.toSAnime(): SAnime = SAnime.create().apply {
        title = displayName()
        url = toRef().toJsonString()
        thumbnail_url = if (isDir) coverUrl(path, publicKey) else thumbnailUrl(this@toSAnime)
        description = if (isDir) null else size.takeIf { it > 0 }?.formatBytes()
        initialized = false
    }

    private fun usesWebDav(publicKey: String?) = publicKey == null && preferences.accessMethod == WEBDAV_VALUE

    private suspend fun listEntries(
        path: String,
        publicKey: String?,
        page: Int,
        cacheControl: CacheControl = DEFAULT_CACHE,
    ): Pair<List<DiskEntry>, Boolean> {
        if (usesWebDav(publicKey)) {
            val entries = if (page == 1) client.propfind(path, httpDateFormat()) else emptyList()
            return entries.sortedWith(compareBy({ !it.isDir }, { it.name.sortKey() })) to false
        }

        val offset = (page - 1) * PAGE_SIZE
        val url = resourcesUrl(path, publicKey).newBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("sort", "name")
            .addQueryParameter("preview_size", PREVIEW_SIZE)
            .addQueryParameter("fields", LISTING_FIELDS)
            .build()

        val embedded = apiGet(url, cacheControl).parseAs<ResourceDto>().embedded ?: return emptyList<DiskEntry>() to false
        val dateFormat = apiDateFormat()
        val entries = embedded.items.map { it.toDiskEntry(dateFormat, publicKey) }
        val hasNextPage = embedded.total?.let { offset + entries.size < it } ?: (entries.size == PAGE_SIZE)

        return entries to hasNextPage
    }

    private fun resourcesUrl(path: String, publicKey: String?): HttpUrl = if (publicKey == null) {
        apiUrl("v1/disk/resources").addQueryParameter("path", path).build()
    } else {
        apiUrl(PUBLIC_RESOURCES_PATH).apply {
            addQueryParameter("public_key", publicKey)
            if (path != "/") addQueryParameter("path", path)
        }.build()
    }

    private suspend fun publicRoot(link: String): DiskEntry {
        val url = resourcesUrl("/", link).newBuilder().addQueryParameter("limit", "0").build()
        val resource = apiGet(url).parseAs<ResourceDto>()

        return resource.toDiskEntry(apiDateFormat(), link).let {
            DiskEntry(name = it.name, path = "/", isDir = it.isDir, size = it.size, modified = it.modified, publicKey = link)
        }
    }

    private suspend fun publicLinkEntries(): List<DiskEntry> {
        val links = preferences.publicLinks.parsePublicLinks()
        val errors = mutableListOf<Throwable>()
        val entries = links.mapNotNull { link ->
            catching { publicTitles(link) }.onFailure { errors += it }.getOrNull()
        }.flatten()

        if (entries.isEmpty() && errors.isNotEmpty() && !hasPrivateAccess()) throw errors.first()

        return entries
    }

    private suspend fun downloadUrl(path: String, publicKey: String?, cacheControl: CacheControl = CacheControl.FORCE_NETWORK): String {
        val url = if (publicKey == null) {
            apiUrl("v1/disk/resources/download").addQueryParameter("path", path).build()
        } else {
            apiUrl("$PUBLIC_RESOURCES_PATH/download").apply {
                addQueryParameter("public_key", publicKey)
                if (path != "/") addQueryParameter("path", path)
            }.build()
        }

        return apiGet(url, cacheControl).parseAs<DownloadDto>().href
    }

    private suspend fun readText(entry: DiskEntry): String {
        val url = if (usesWebDav(entry.publicKey)) {
            webdavUrl(entry.path)
        } else {
            downloadUrl(entry.path, entry.publicKey, DEFAULT_CACHE).toHttpUrl()
        }

        return client.get(url = url, headers = headers, cacheControl = DEFAULT_CACHE).use { it.body.bytes() }.decodeText()
    }

    private suspend fun apiGet(url: HttpUrl, cacheControl: CacheControl = DEFAULT_CACHE): Response {
        val response = client.get(url = url, headers = headers, cacheControl = cacheControl, ensureSuccess = false)
        if (response.isSuccessful) return response

        val error = response.use { runCatching { it.parseAs<ErrorDto>() }.getOrNull() }
        val message = when (response.code) {
            401 -> "Invalid or expired OAuth token."
            403 -> "Access denied. Check the token permissions (cloud_api:disk.read)."
            404 -> "Not found: ${url.queryParameter("path") ?: url.queryParameter("public_key")}"
            429 -> "Too many requests, try again later."
            else -> error?.description ?: error?.message ?: "HTTP ${response.code}"
        }

        throw IOException("Yandex Disk: $message")
    }

    /**
     * A stable thumbnail URL. Yandex preview links are signed and expire, so the cover points to the
     * resource metadata instead, and [previewInterceptor] swaps it for a fresh preview link.
     */
    private fun thumbnailUrl(entry: DiskEntry): String? {
        if (entry.isDir) return null

        if (usesWebDav(entry.publicKey)) {
            return if (entry.isImage()) webdavPreviewUrl(entry.path) else null
        }

        if (!entry.isImage() && !entry.isVideo()) return null

        return resourcesUrl(entry.path, entry.publicKey).newBuilder()
            .addQueryParameter("fields", "preview")
            .addQueryParameter("preview_size", PREVIEW_SIZE)
            .fragment(PREVIEW_FRAGMENT)
            .build()
            .toString()
    }

    /** Stable cover URL of a folder, resolved by [coverInterceptor] when the image is loaded. */
    private fun coverUrl(path: String, publicKey: String?): String = folderListUrl(path, publicKey)
        .newBuilder()
        .fragment(COVER_FRAGMENT)
        .build()
        .toString()

    private fun folderListUrl(path: String, publicKey: String?): HttpUrl = if (usesWebDav(publicKey)) {
        webdavListUrl(path)
    } else {
        resourcesUrl(path, publicKey).newBuilder()
            .addQueryParameter("limit", COVER_SCAN_LIMIT.toString())
            .addQueryParameter("sort", "name")
            .addQueryParameter("fields", LISTING_FIELDS)
            .build()
    }

    /**
     * Cover of a folder: an image (poster/cover/folder… first), then an image in a subfolder,
     * then the preview of the first video. Sample and screenshot folders are skipped.
     */
    private fun coverInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.fragment != COVER_FRAGMENT) return chain.proceed(request)

        val folderUrl = request.url.newBuilder().fragment(null).build()
        val entries = listForCover(chain, folderUrl)
        val subEntries by lazy {
            entries.filter { it.isDir && !it.name.isIgnoredFolder() }
                .take(MAX_COVER_SUBFOLDERS)
                .flatMap { runCatching { listForCover(chain, folderListUrl(it.path, it.publicKey)) }.getOrDefault(emptyList()) }
        }

        val cover = entries.filter { it.isImage() }.minByOrNull { it.coverRank() }?.let { thumbnailUrl(it) }
            ?: subEntries.filter { it.isImage() }.minByOrNull { it.coverRank() }?.let { thumbnailUrl(it) }
            ?: (entries + subEntries).firstOrNull { it.isVideo() && !it.isSample() }?.let { thumbnailUrl(it) }
            ?: throw IOException("No cover")

        return chain.proceed(request.newBuilder().url(cover).build())
    }

    private fun listForCover(chain: Interceptor.Chain, folderUrl: HttpUrl): List<DiskEntry> {
        val dateFormat = if (folderUrl.host == WEBDAV_HOST) httpDateFormat() else apiDateFormat()

        if (folderUrl.host == WEBDAV_HOST) {
            val path = hrefToPath(folderUrl.encodedPath)
            return chain.proceed(propfindRequest(path)).use { it.toPropfindEntries(path, dateFormat) }
        }

        val publicKey = folderUrl.queryParameter("public_key")
        val request = Request.Builder().url(folderUrl).headers(headers).build()

        return chain.proceed(request).use { response ->
            if (!response.isSuccessful) return@use emptyList<DiskEntry>()
            response.parseAs<ResourceDto>().embedded?.items.orEmpty().map { it.toDiskEntry(dateFormat, publicKey) }
        }
    }

    private fun previewInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.fragment != PREVIEW_FRAGMENT) return chain.proceed(request)

        val metaUrl = request.url.newBuilder().fragment(null).build()
        val preview = chain.proceed(request.newBuilder().url(metaUrl).build()).use { response ->
            if (response.isSuccessful) response.parseAs<PreviewDto>().preview else null
        } ?: throw IOException("No preview available")

        val previewRequest = request.newBuilder().url(preview).apply {
            if (!metaUrl.isPublicApi()) oauthHeader()?.let { header("Authorization", it) }
        }.build()

        return chain.proceed(previewRequest)
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)

        val authorization = when (request.url.host) {
            WEBDAV_HOST -> webdavCredentials()
            API_HOST -> if (request.url.isPublicApi()) null else oauthHeader() ?: throw IOException(MISSING_CREDENTIALS)
            else -> null
        } ?: return chain.proceed(request)

        return chain.proceed(request.newBuilder().header("Authorization", authorization).build())
    }

    private fun HttpUrl.isPublicApi() = encodedPath.startsWith("/$PUBLIC_RESOURCES_PATH")

    private fun apiUrl(path: String) = baseUrl.toHttpUrl().newBuilder().addPathSegments(path)

    private fun rootPaths(): List<String> = preferences.rootPath.parseDiskPaths()

    private fun webUrl(ref: EntryRef): String {
        ref.publicKey?.let { return it }

        val path = ref.path.trim('/')
        return WEB_DISK_URL.toHttpUrl().newBuilder().apply {
            if (path.isNotEmpty()) addPathSegments(path)
        }.build().toString()
    }

    private fun hasPrivateAccess(): Boolean = when (preferences.accessMethod) {
        WEBDAV_VALUE -> preferences.webdavLogin.isNotBlank() && preferences.webdavPassword.isNotBlank()
        else -> preferences.oauthToken.isNotBlank()
    }

    private fun oauthHeader(): String? = preferences.oauthToken.toOAuthToken().takeIf { it.isNotEmpty() }?.let { "OAuth $it" }

    private fun webdavCredentials(): String {
        val login = preferences.webdavLogin.trim()
        val password = preferences.webdavPassword.trim()
        if (login.isEmpty() || password.isEmpty()) {
            throw IOException("Enter your Yandex login and WebDAV app password in the extension settings.")
        }

        return Credentials.basic(login, password)
    }

    private fun webdavHeaders(): Headers = headers.newBuilder()
        .set("Authorization", webdavCredentials())
        .build()

    // ============================ Preferences =============================

    private val SharedPreferences.accessMethod by preferences.delegate(ACCESS_METHOD_KEY, ACCESS_METHOD_DEFAULT)
    private val SharedPreferences.oauthToken by preferences.delegate(OAUTH_TOKEN_KEY, OAUTH_TOKEN_DEFAULT)
    private val SharedPreferences.webdavLogin by preferences.delegate(WEBDAV_LOGIN_KEY, WEBDAV_LOGIN_DEFAULT)
    private val SharedPreferences.webdavPassword by preferences.delegate(WEBDAV_PASSWORD_KEY, WEBDAV_PASSWORD_DEFAULT)
    private val SharedPreferences.rootPath by preferences.delegate(ROOT_PATH_KEY, ROOT_PATH_DEFAULT)
    private val SharedPreferences.publicLinks by preferences.delegate(PUBLIC_LINKS_KEY, PUBLIC_LINKS_DEFAULT)
    private val SharedPreferences.onlyVideoFiles by preferences.delegate(ONLY_VIDEO_KEY, ONLY_VIDEO_DEFAULT)
    private val SharedPreferences.includeSubfolders by preferences.delegate(SUBFOLDERS_KEY, SUBFOLDERS_DEFAULT)
    private val SharedPreferences.externalTracks by preferences.delegate(EXTERNAL_TRACKS_KEY, EXTERNAL_TRACKS_DEFAULT)

    private fun masked(value: String): String = if (value.isBlank()) "Not set" else "•".repeat(value.length.coerceAtMost(12))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addHelpPreference()

        screen.addListPreference(
            key = ACCESS_METHOD_KEY,
            default = ACCESS_METHOD_DEFAULT,
            title = "Access method",
            summary = "%s",
            entries = listOf("OAuth token (REST API)", "WebDAV (login + app password)"),
            entryValues = listOf(OAUTH_VALUE, WEBDAV_VALUE),
        )

        screen.addEditTextPreference(
            key = OAUTH_TOKEN_KEY,
            default = OAUTH_TOKEN_DEFAULT,
            title = "OAuth token",
            summary = "Paste the token, or the whole page address after signing in. How to get one: see \"$HELP_TITLE\"",
            getSummary = ::masked,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        screen.addEditTextPreference(
            key = WEBDAV_LOGIN_KEY,
            default = WEBDAV_LOGIN_DEFAULT,
            title = "WebDAV login",
            summary = "Yandex account, used in WebDAV mode",
            getSummary = { it.ifBlank { "Not set" } },
        )

        screen.addEditTextPreference(
            key = WEBDAV_PASSWORD_KEY,
            default = WEBDAV_PASSWORD_DEFAULT,
            title = "WebDAV app password",
            summary = "App password for \"Files (WebDAV)\", created on id.yandex.ru",
            getSummary = ::masked,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )

        screen.addEditTextPreference(
            key = ROOT_PATH_KEY,
            default = ROOT_PATH_DEFAULT,
            title = "Folders to browse",
            summary = "Disk path, e.g. /Anime. Several: one per line or separated by \";\"",
            getSummary = { it.parseDiskPaths().joinToString() },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )

        screen.addEditTextPreference(
            key = PUBLIC_LINKS_KEY,
            default = PUBLIC_LINKS_DEFAULT,
            title = "Public links",
            summary = "Shared folders or files (disk.yandex.ru/d/…), one per line. Work without a token",
            getSummary = { value ->
                val count = value.parsePublicLinks().size
                if (count == 0) "Shared folders or files (disk.yandex.ru/d/…), one per line. Work without a token" else "Links: $count"
            },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI,
        )

        screen.addSwitchPreference(
            key = ONLY_VIDEO_KEY,
            default = ONLY_VIDEO_DEFAULT,
            title = "Only show video files",
            summary = "Hide subtitles, covers and archives from the lists",
        )

        screen.addSwitchPreference(
            key = SUBFOLDERS_KEY,
            default = SUBFOLDERS_DEFAULT,
            title = "Include subfolders",
            summary = "Collect episodes from nested folders (seasons, specials)",
        )

        screen.addSwitchPreference(
            key = EXTERNAL_TRACKS_KEY,
            default = EXTERNAL_TRACKS_DEFAULT,
            title = "External audio and subtitles",
            summary = "Attach .mka/.ass/.srt files named like the episode (REST API and public links only)",
        )
    }

    companion object {
        private const val API_BASE = "https://cloud-api.yandex.net"
        private val API_HOST = API_BASE.toHttpUrl().host
        private const val WEB_DISK_URL = "https://disk.yandex.ru/client/disk"
        private const val PUBLIC_RESOURCES_PATH = "v1/disk/public/resources"

        private const val PAGE_SIZE = 100
        private const val SEARCH_SCAN_LIMIT = 1000
        private const val MAX_FILES = 5000
        private const val MAX_SCANNED_FOLDERS = 200
        private const val MAX_SIDECAR_FOLDERS = 30
        private const val MAX_INFO_SUBFOLDERS = 3
        private const val MAX_COVER_SUBFOLDERS = 3
        private const val COVER_SCAN_LIMIT = 200
        private const val MAX_EPISODE_NFOS = 200
        private const val MAX_NFO_BYTES = 64 * 1024L
        private const val NFO_CONCURRENCY = 6
        private const val LIST_CONCURRENCY = 4
        private const val PREVIEW_CONCURRENCY = 4
        private const val MAX_STORED_PREVIEWS = 2000
        private const val MAX_EPISODE_PREVIEWS = 300
        private const val PREVIEW_DIR = "yandexdisk_previews"
        private const val LISTING_CACHE_MILLIS = 5 * 60 * 1000L
        private const val MAX_CACHED_LISTINGS = 500

        // Only what DiskEntry needs: a full listing also carries hashes, EXIF, sizes of every
        // preview and more, which makes big folders several times larger.
        private val LISTING_FIELDS = listOf(
            "name", "path", "type",
            "_embedded.items.name", "_embedded.items.path", "_embedded.items.type",
            "_embedded.items.size", "_embedded.items.modified", "_embedded.items.preview",
            "_embedded.total",
        ).joinToString(",")

        private const val PREVIEW_SIZE = "L"
        private const val PREVIEW_FRAGMENT = "yandexdisk-preview"
        private const val COVER_FRAGMENT = "yandexdisk-cover"
        private val DEFAULT_CACHE = CacheControl.Builder().maxAge(10.minutes).build()

        private const val MISSING_CREDENTIALS = "Set an OAuth token, WebDAV credentials or public links in the extension settings."

        private const val API_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"
        private const val HTTP_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz"
        private const val AIRED_DATE_PATTERN = "yyyy-MM-dd"

        private const val OAUTH_VALUE = "oauth"
        private const val WEBDAV_VALUE = "webdav"

        private const val ACCESS_METHOD_KEY = "access_method"
        private const val ACCESS_METHOD_DEFAULT = OAUTH_VALUE

        private const val OAUTH_TOKEN_KEY = "oauth_token"
        private const val OAUTH_TOKEN_DEFAULT = ""

        private const val WEBDAV_LOGIN_KEY = "webdav_login"
        private const val WEBDAV_LOGIN_DEFAULT = ""

        private const val WEBDAV_PASSWORD_KEY = "webdav_password"
        private const val WEBDAV_PASSWORD_DEFAULT = ""

        private const val ROOT_PATH_KEY = "root_path"
        private const val ROOT_PATH_DEFAULT = "/"

        private const val PUBLIC_LINKS_KEY = "public_links"
        private const val PUBLIC_LINKS_DEFAULT = ""

        private const val ONLY_VIDEO_KEY = "only_video_files"
        private const val ONLY_VIDEO_DEFAULT = true

        private const val SUBFOLDERS_KEY = "include_subfolders"
        private const val SUBFOLDERS_DEFAULT = true

        private const val EXTERNAL_TRACKS_KEY = "external_tracks"
        private const val EXTERNAL_TRACKS_DEFAULT = true
    }
}
