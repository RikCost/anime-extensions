package eu.kanade.tachiyomi.animeextension.ru.yummyanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
class YummyAnimeSource : AnimeHttpSource() {

    override val name = "YummyAnime"

    override val baseUrl = "https://ru.yummyani.me"

    private val apiUrl = "https://api.yani.tv"

    override val lang = "ru"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Anikku/1.0")
        .add("Accept", "application/json")

    // Popular Anime
    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET("$apiUrl/anime/catalog?limit=20&offset=$offset", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        val jsonObject = json.parseToJsonElement(responseString).jsonObject
        val data = jsonObject["response"]?.jsonObject?.get("data")?.jsonArray ?: return AnimesPage(emptyList(), false)

        val animes = data.map { element ->
            val anime = element.jsonObject
            SAnime.create().apply {
                title = anime["title"]?.jsonPrimitive?.content ?: ""
                url = anime["anime_url"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = anime["poster"]?.jsonObject?.get("big")?.jsonPrimitive?.content?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }
            }
        }

        return AnimesPage(animes, animes.size == 20)
    }

    // Latest Anime
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // Search Anime
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$apiUrl/search?q=$query", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        val jsonObject = json.parseToJsonElement(responseString).jsonObject
        val data = jsonObject["response"]?.jsonArray ?: return AnimesPage(emptyList(), false)

        val animes = data.map { element ->
            val anime = element.jsonObject
            SAnime.create().apply {
                title = anime["title"]?.jsonPrimitive?.content ?: ""
                url = anime["anime_url"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = anime["poster"]?.jsonObject?.get("big")?.jsonPrimitive?.content?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }
            }
        }

        return AnimesPage(animes, false)
    }

    // Anime Details
    override fun animeDetailsRequest(anime: SAnime): Request = GET("$apiUrl/anime/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        val jsonObject = json.parseToJsonElement(responseString).jsonObject["response"]?.jsonObject ?: return SAnime.create()

        return SAnime.create().apply {
            title = jsonObject["title"]?.jsonPrimitive?.content ?: ""
            description = jsonObject["description"]?.jsonPrimitive?.content
            genre = jsonObject["genres"]?.jsonArray?.joinToString { it.jsonObject["title"]?.jsonPrimitive?.content ?: "" }
            status = when (jsonObject["anime_status"]?.jsonObject?.get("value")?.jsonPrimitive?.content) {
                "0" -> SAnime.COMPLETED
                "1" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            author = jsonObject["studios"]?.jsonArray?.joinToString { it.jsonObject["title"]?.jsonPrimitive?.content ?: "" }
            thumbnail_url = jsonObject["poster"]?.jsonObject?.get("huge")?.jsonPrimitive?.content?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
        }
    }

    // Episodes
    override fun episodeListRequest(anime: SAnime): Request {
        // We need the ID to get videos. animeDetailsParse should have it or we can get it from the URL
        // For simplicity, we'll fetch details again or assume the URL can be used
        return GET("$apiUrl/anime/${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val animeData = json.parseToJsonElement(responseString).jsonObject["response"]?.jsonObject ?: return emptyList()
        val animeId = animeData["anime_id"]?.jsonPrimitive?.content ?: return emptyList()

        val videosResponse = client.newCall(GET("$apiUrl/anime/$animeId/videos", headers)).execute()
        val videosString = videosResponse.body.string()
        val videosArray = json.parseToJsonElement(videosString).jsonObject["response"]?.jsonArray ?: return emptyList()

        return videosArray.map { element ->
            val video = element.jsonObject
            SEpisode.create().apply {
                val num = video["number"]?.jsonPrimitive?.content ?: ""
                val dub = video["data"]?.jsonObject?.get("dubbing")?.jsonPrimitive?.content ?: ""
                name = "Серия $num ($dub)"
                episode_number = num.toFloatOrNull() ?: 0f
                url = video["iframe_url"]?.jsonPrimitive?.content ?: ""
                date_upload = (video["date"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 1000
            }
        }.reversed()
    }

    // Video URLs
    override fun videoListParse(response: Response): List<Video> {
        // The URL in SEpisode is already the iframe URL
        val iframeUrl = response.request.url.toString()
        val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

        // In a real extension, we would use a script to extract the actual video file URL from Kodik/Alloha
        // For this example, we return the iframe as the video source (Anikku/Aniyomi usually handles iframes via external players or specific extractors)
        return listOf(Video(finalUrl, "Default", finalUrl))
    }

    override fun List<Video>.sort(): List<Video> = this
}
