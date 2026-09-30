package eu.kanade.tachiyomi.animeextension.ru.jutsu

import kotlinx.serialization.Serializable

@Serializable
class PlayerResponse(
    val success: Boolean = false,
    val data: String = "",
)
