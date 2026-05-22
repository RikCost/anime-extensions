package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class YummyAnime : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        YummyAnimeSource(),
    )
}
