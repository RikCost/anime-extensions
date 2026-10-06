package eu.kanade.tachiyomi.animeextension.ru.jutsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class PlayerResponse(
    val status: Boolean = false,
    val data: PlayerData? = null,
)

@Serializable
class PlayerData(
    val name: String = "",
    val kind: String = "",
    val src: String = "",
    val label: String = "",
)

@Serializable
class KodikFormData(
    val d: String = "",
    @SerialName("d_sign") val dSign: String = "",
    val pd: String = "",
    @SerialName("pd_sign") val pdSign: String = "",
    val ref: String = "",
    @SerialName("ref_sign") val refSign: String = "",
)

@Serializable
class KodikVideoInfo(val src: String)

@Serializable
class KodikVideoQuality(
    @SerialName("360") val ugly: List<KodikVideoInfo> = emptyList(),
    @SerialName("480") val bad: List<KodikVideoInfo> = emptyList(),
    @SerialName("720") val good: List<KodikVideoInfo> = emptyList(),
    @SerialName("1080") val full: List<KodikVideoInfo> = emptyList(),
)

@Serializable
class KodikData(val links: KodikVideoQuality)

// "all" is season -> episode -> translation -> file for serials, translation -> file otherwise.
@Serializable
class AllohaFileList(
    val type: String,
    val active: AllohaFile? = null,
    val all: JsonElement,
)

@Serializable
class AllohaFile(
    val id: Long,
    val translation: String,
)

@Serializable
class AllohaStreams(
    val hlsSource: List<AllohaSource> = emptyList(),
    val tracks: List<AllohaTrack> = emptyList(),
)

@Serializable
class AllohaSource(
    val label: String,
    val quality: Map<String, String>,
    val default: Boolean = false,
)

@Serializable
class AllohaTrack(
    val label: String,
    val src: String,
)
