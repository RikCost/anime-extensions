package eu.kanade.tachiyomi.animeextension.all.yandexdisk

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

private val SEASON_EPISODE_REGEX = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,4})(?![0-9])""")
private val EPISODE_REGEX = Regex("""(?iu)(?:^|[^a-zа-я0-9])(?:e|ep|episode|серия|эпизод)[ ._#-]?(\d{1,4})(?![0-9])""")
private val ANIME_EPISODE_REGEX = Regex("""\s-\s(\d{1,4})(?:v\d)?(?=$|[\s.\[(])""")
private val SEASON_FOLDER_REGEX = Regex("""(?iu)(?:season|сезон)[ ._-]?(\d{1,2})|(?:^|[^a-z0-9])s(\d{1,2})(?=$|[^a-z0-9e])""")
private val QUALITY_REGEX = Regex("""(?i)(?:^|[^a-z0-9])(2160|1440|1080|720|576|540|480|360)[pi](?![a-z0-9])""")
private val UHD_REGEX = Regex("""(?i)(?:^|[^a-z0-9])(?:4k|uhd)(?![a-z0-9])""")
private val BRACKETS_REGEX = Regex("""[\[(][^\])]*[\])]""")
private val NON_WORD_REGEX = Regex("""[\W_]+""")
private val SAMPLE_REGEX = Regex("""(?i)(?:^|[-._ ])sample(?:$|[-._ ])""")
private val SEASON_NAME_REGEX = Regex("""(?iu)^(?:(?:season|сезон|s)[ ._-]?\d{1,2}(?:[^0-9].*)?|specials?|extras?|ova|ona|спешлы|бонусы|допы)$""")
private val FIELD_REGEX = Regex("""^\s*([^:\n]{2,40}?)\s*:\s*(.+?)\s*$""", RegexOption.MULTILINE)

private val IGNORED_FOLDERS = setOf("sample", "samples", "screens", "screenshots", "screenshot", "скриншоты", "proof", "@eadir", ".thumbnails")
private val INFO_TEXT_HINTS = listOf("описание", "description", "info", "about", "plot", "synopsis", "сюжет", "readme")
private val GENRE_KEYS = setOf("жанр", "жанры", "genre", "genres")
private val DIRECTOR_KEYS = setOf("режиссёр", "режиссер", "режиссеры", "режиссёры", "director", "directors", "автор", "author")
private val STUDIO_KEYS = setOf("студия", "studio", "studios", "производство", "network", "канал")

private const val MAX_TEXT_BYTES = 64 * 1024L

class EpisodeKey(val season: Int?, val episode: Int?, val key: String)

class EpisodeGroup(val key: EpisodeKey, val variants: List<DiskEntry>) {
    val primary: DiskEntry get() = variants.first()

    /** Numbered episodes first, by season and number, then the rest by path. */
    fun orderKey(): String = key.episode?.let { String.format(Locale.ROOT, "0|%04d|%06d", key.season ?: 1, it) }
        ?: "1|${primary.path.sortKey()}"
}

/** Videos with the same key are the same episode in different qualities. */
internal fun DiskEntry.episodeKey(root: String): EpisodeKey {
    val baseName = name.withoutExtension()
    val folders = path.parentPath().relativeTo(root)

    SEASON_EPISODE_REGEX.find(baseName)?.let {
        val season = it.groupValues[1].toInt()
        val episode = it.groupValues[2].toInt()
        return EpisodeKey(season, episode, "s${season}e$episode")
    }

    val season = folders.split('/').asReversed().firstNotNullOfOrNull { folder ->
        SEASON_FOLDER_REGEX.find(folder)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }
    }
    val episode = (EPISODE_REGEX.find(baseName) ?: ANIME_EPISODE_REGEX.find(baseName))?.groupValues?.get(1)?.toIntOrNull()
    if (episode != null) {
        return EpisodeKey(season, episode, "s${season ?: 1}e$episode")
    }

    val cleanName = baseName.replace(BRACKETS_REGEX, " ")
        .replace(QUALITY_REGEX, " ")
        .replace(UHD_REGEX, " ")
        .replace(NON_WORD_REGEX, " ")
        .trim()
        .lowercase()
        .ifEmpty { baseName.lowercase() }

    return EpisodeKey(season, null, "${season ?: ""}|$cleanName")
}

/** Video height read from the file name, or from the name of the folder it's in. */
internal fun DiskEntry.quality(): Int? = sequenceOf(name, path.parentPath().substringAfterLast('/')).firstNotNullOfOrNull { value ->
    QUALITY_REGEX.find(value)?.groupValues?.get(1)?.toInt() ?: if (UHD_REGEX.containsMatchIn(value)) 2160 else null
}

internal fun String.isIgnoredFolder(): Boolean = lowercase() in IGNORED_FOLDERS

internal fun DiskEntry.isSeasonFolder(): Boolean = isDir && SEASON_NAME_REGEX.matches(name.trim())

internal fun DiskEntry.isSample(): Boolean = !isDir && SAMPLE_REGEX.containsMatchIn(name.withoutExtension())

internal fun DiskEntry.isNfo(): Boolean = !isDir && name.extension() == "nfo"

/** A text file describing the title, best match first; MediaInfo dumps are skipped. */
internal fun List<DiskEntry>.findInfoText(): DiskEntry? = filter { !it.isDir && it.name.extension() == "txt" && it.size in 1..MAX_TEXT_BYTES }
    .filterNot { it.name.lowercase().startsWith("mediainfo") }
    .minByOrNull { entry ->
        val lowerName = entry.name.lowercase()
        INFO_TEXT_HINTS.indexOfFirst { lowerName.contains(it) }.let { if (it < 0) INFO_TEXT_HINTS.size else it }
    }

internal fun List<DiskEntry>.findTitleNfo(): DiskEntry? = firstOrNull { it.isNfo() && it.name.lowercase() in setOf("tvshow.nfo", "movie.nfo") }
    ?: singleOrNull { it.isNfo() && it.size in 1..MAX_TEXT_BYTES }

class TitleInfo(
    val title: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val completed: Boolean? = null,
)

class EpisodeInfo(
    val title: String? = null,
    val plot: String? = null,
    val aired: String? = null,
)

/** Kodi `tvshow.nfo` / `movie.nfo`. */
internal fun parseTitleNfo(text: String): TitleInfo? {
    val document = Jsoup.parse(text, "", Parser.xmlParser())
    val root = document.selectFirst("tvshow, movie") ?: return null

    fun all(tag: String) = root.select(tag).map { it.text() }.filter { it.isNotEmpty() }.distinct().joinToString().ifEmpty { null }

    return TitleInfo(
        title = root.selectFirst("> title")?.text()?.ifEmpty { null },
        description = root.selectFirst("> plot")?.text()?.ifEmpty { null } ?: root.selectFirst("> outline")?.text()?.ifEmpty { null },
        genre = all("genre"),
        author = all("director") ?: all("credits"),
        artist = all("studio"),
        completed = root.selectFirst("> status")?.text()?.lowercase()?.let { it == "ended" || it == "completed" },
    )
}

/** Kodi `<episodedetails>`. */
internal fun parseEpisodeNfo(text: String): EpisodeInfo? {
    val root = Jsoup.parse(text, "", Parser.xmlParser()).selectFirst("episodedetails, movie") ?: return null

    return EpisodeInfo(
        title = root.selectFirst("> title")?.text()?.ifEmpty { null },
        plot = root.selectFirst("> plot")?.text()?.ifEmpty { null },
        aired = root.selectFirst("> aired, > premiered")?.text()?.ifEmpty { null },
    )
}

/** Free-form description; "Жанр: …" / "Режиссёр: …" style lines fill the matching fields. */
internal fun parseInfoText(text: String): TitleInfo {
    val fields = FIELD_REGEX.findAll(text).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    return TitleInfo(
        description = text.trim().ifEmpty { null },
        genre = fields.entries.firstOrNull { it.key in GENRE_KEYS }?.value,
        author = fields.entries.firstOrNull { it.key in DIRECTOR_KEYS }?.value,
        artist = fields.entries.firstOrNull { it.key in STUDIO_KEYS }?.value,
    )
}

/** UTF-8 when valid, otherwise Windows-1251, which is what most Russian release notes use. */
internal fun ByteArray.decodeText(): String {
    val bytes = if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) copyOfRange(3, size) else this

    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1251"))
    }
}
