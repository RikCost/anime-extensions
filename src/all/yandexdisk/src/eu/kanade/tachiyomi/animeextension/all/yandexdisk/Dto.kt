package eu.kanade.tachiyomi.animeextension.all.yandexdisk

import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat

@Serializable
class ResourceDto(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    val modified: String? = null,
    val preview: String? = null,
    @SerialName("_embedded") val embedded: EmbeddedDto? = null,
) {
    fun toDiskEntry(dateFormat: SimpleDateFormat, publicKey: String?): DiskEntry = DiskEntry(
        name = name,
        path = path,
        isDir = type == "dir",
        size = size,
        modified = dateFormat.tryParse(modified),
        publicKey = publicKey,
        preview = preview,
    )
}

@Serializable
class EmbeddedDto(
    val items: List<ResourceDto> = emptyList(),
    val total: Int? = null,
)

@Serializable
class PreviewDto(val preview: String? = null)

@Serializable
class DownloadDto(val href: String)

@Serializable
class ErrorDto(
    val message: String? = null,
    val description: String? = null,
)

/**
 * Stored as the `url` of an anime or an episode. Only stable fields are kept, so that renaming
 * nothing but the size or modification date of a file doesn't create a duplicate episode.
 */
@Serializable
class EntryRef(
    val path: String,
    val isDir: Boolean,
    val publicKey: String? = null,
    /** Folder of the title an episode belongs to, used to find the other qualities of the episode. */
    val root: String? = null,
    /**
     * Episode key within [root] (see [EpisodeKey]). Set for episodes of a folder title, whose [path]
     * is then the title folder: the file behind an episode changes when another quality is added.
     */
    val key: String? = null,
)

/** External audio or subtitle file that belongs to an episode. */
@Serializable
class Sidecar(
    val path: String,
    val label: String,
    val isAudio: Boolean,
)

@Serializable
class VideoData(
    val path: String,
    val publicKey: String? = null,
    val sidecars: List<Sidecar> = emptyList(),
)

/** An entry of a private disk (when [publicKey] is null) or of a public resource. */
class DiskEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val publicKey: String? = null,
    /** Signed preview link from the REST API; it expires, so it's only used where nothing else works. */
    val preview: String? = null,
) {
    fun toRef() = EntryRef(path = path, isDir = isDir, publicKey = publicKey)
}
