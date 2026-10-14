package eu.kanade.tachiyomi.animeextension.all.yandexdisk

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.cancellation.CancellationException

private val VIDEO_EXTENSIONS = setOf("3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpg", "mpeg", "mts", "ogv", "ts", "vob", "webm", "wmv")
private val AUDIO_EXTENSIONS = setOf("aac", "ac3", "dts", "eac3", "flac", "m4a", "mka", "mp3", "ogg", "opus", "wav")
private val SUBTITLE_EXTENSIONS = setOf("ass", "srt", "ssa", "vtt")
private val IMAGE_EXTENSIONS = setOf("bmp", "gif", "jpeg", "jpg", "png", "webp")

/** Image names that are most likely the cover of a folder, best match first. */
private val COVER_NAMES = listOf("cover", "poster", "folder", "front", "thumb", "preview")

private val DIGITS_REGEX = Regex("""\d+""")
private val PUBLIC_HOST_REGEX = Regex("""(^|\.)(yadi\.sk|disk\.yandex\.[a-z.]+|disk\.360\.yandex\.[a-z.]+)$""")
private val LINK_SEPARATOR_REGEX = Regex("""[\s,;]+""")

// A comma only separates paths when the next one starts with "/": folder names may hold commas.
private val PATH_SEPARATOR_REGEX = Regex("""[\n;]+|,\s*(?=/)""")
private val ACCESS_TOKEN_REGEX = Regex("""access_token=([^&#\s]+)""")
private val PUBLIC_PATH_PREFIXES = listOf("/d/", "/i/", "/a/", "/public")

private const val NUMBER_WIDTH = 12
private const val MAX_EXTENSION_LENGTH = 4

internal fun DiskEntry.isVideo(): Boolean = !isDir && extensionOf(name) in VIDEO_EXTENSIONS

internal fun DiskEntry.isAudio(): Boolean = !isDir && extensionOf(name) in AUDIO_EXTENSIONS

internal fun DiskEntry.isSubtitle(): Boolean = !isDir && extensionOf(name) in SUBTITLE_EXTENSIONS

internal fun DiskEntry.isImage(): Boolean = !isDir && extensionOf(name) in IMAGE_EXTENSIONS

internal fun DiskEntry.displayName(): String = if (isDir) name else name.withoutExtension()

internal fun DiskEntry.coverRank(): Int = COVER_NAMES.indexOf(name.withoutExtension().lowercase()).let { if (it < 0) COVER_NAMES.size else it }

internal fun String.extension(): String = extensionOf(this)

/** A folder path on the disk, always absolute and without trailing slash ("/" is the disk root). */
internal fun String.asDiskPath(): String = trim()
    .let { if (it.startsWith("/")) it else "/$it" }
    .removeSuffix("/")
    .ifEmpty { "/" }

/** Several disk paths, one per line or separated by ";" (or by "," before the next "/"). */
internal fun String.parseDiskPaths(): List<String> = split(PATH_SEPARATOR_REGEX)
    .filter { it.isNotBlank() }
    .map { it.asDiskPath() }
    .distinct()
    .ifEmpty { listOf("/") }

internal fun String.parentPath(): String = substringBeforeLast('/', "").ifEmpty { "/" }

/** Path relative to [root], without the leading slash. */
internal fun String.relativeTo(root: String): String = if (root == "/") removePrefix("/") else removePrefix(root).removePrefix("/")

/** Digits are padded so that "ep2" sorts before "ep10". */
internal fun String.sortKey(): String = DIGITS_REGEX.replace(lowercase()) { it.value.padStart(NUMBER_WIDTH, '0') }

internal fun String.withoutExtension(): String {
    val extension = extensionOf(this)
    return if (extension.isEmpty()) this else substring(0, length - extension.length - 1)
}

/**
 * Returns the normalized public link if [this] is a link to a public Yandex Disk resource.
 * The scheme may be omitted; share parameters such as `?w=1` are dropped, except for the
 * `/public?hash=…` form, where the query is the link itself.
 */
internal fun String.asPublicLink(): String? {
    val text = trim().let { if (it.contains("://")) it else "https://$it" }
    val url = text.toHttpUrlOrNull() ?: return null
    if (!PUBLIC_HOST_REGEX.containsMatchIn(url.host)) return null
    if (url.host != "yadi.sk" && PUBLIC_PATH_PREFIXES.none { url.encodedPath.startsWith(it) }) return null

    val keepQuery = url.encodedPath.startsWith("/public")
    return url.newBuilder().fragment(null).apply { if (!keepQuery) query(null) }.build().toString().removeSuffix("/")
}

/** The OAuth token itself, or the one in a pasted `…/verification_code#access_token=…` address. */
internal fun String.toOAuthToken(): String = ACCESS_TOKEN_REGEX.find(this)?.groupValues?.get(1) ?: trim()

internal fun String.parsePublicLinks(): List<String> = split(LINK_SEPARATOR_REGEX).mapNotNull { it.asPublicLink() }.distinct()

/** [runCatching] that doesn't swallow coroutine cancellation. */
internal inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

private fun extensionOf(value: String): String {
    val extension = value.substringAfterLast('.', "")

    return if (extension.length > MAX_EXTENSION_LENGTH) "" else extension.lowercase()
}
