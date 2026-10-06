package eu.kanade.tachiyomi.animeextension.ru.jutsu

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parseAs
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.security.MessageDigest

/**
 * Alloha player (`*.thealloha.club`).
 *
 * The iframe page carries the whole translation tree in `fileList`, and the streams of one
 * translation are returned by `POST /bnsi/movies/<fileId>`. That request is signed with the
 * `Borth` header: `<fingerprint sha256>|<payload>`, where the payload is the content of
 * `<meta name="viewporti">` run through three fixed character permutations. The payload is
 * single-use, so every stream request needs a freshly loaded iframe page.
 */
class AllohaExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {

    private val pageHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$siteUrl/")
            .build()
    }

    // The player sends a sha256 of its browser fingerprint; the server cannot verify it,
    // it only has to look like one.
    private val fingerprint by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(headers["User-Agent"].orEmpty().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * One hoster per distinct audio track. A translation in `fileList` is really a file with
     * several muxed audio tracks, and the same track shows up in several files, so every file
     * is fetched up front and the tracks are merged by language and name.
     */
    suspend fun hostersFromUrl(iframeUrl: String, episode: Int?): List<Hoster> {
        val url = iframeUrl.toHttpUrl()
        val token = url.queryParameter("token") ?: return emptyList()
        val origin = "${url.scheme}://${url.host}"

        val fileList = fetchPage(iframeUrl).fileList ?: return emptyList()
        val streams = fileList.filesFor(episode).parallelCatchingMapNotNull { file ->
            fetchStreams(iframeUrl, origin, token, file.id)
        }

        // The CDN rejects playlist, segment and subtitle requests without the player's Origin.
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()

        // Subtitles are timed to the episode, not to an audio track: offer all of them everywhere.
        val subtitles = streams.flatMap { it.tracks }
            .distinctBy { it.label }
            .map { Track(it.src, it.label) }

        return streams.flatMap { it.hlsSource }
            .map { AudioTrack.from(it.label) to it }
            .distinctBy { (track, _) -> track.key }
            .sortedBy { (track, _) -> track.order }
            .map { (track, source) ->
                val videos = source.quality.entries
                    .sortedByDescending { it.key.toIntOrNull() ?: 0 }
                    .map { (quality, streamUrl) ->
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = "${track.title} (${quality}p Alloha)",
                            headers = videoHeaders,
                            subtitleTracks = subtitles,
                            initialized = true,
                        )
                    }
                Hoster(hosterName = "${track.title} (Alloha)", videoList = videos)
            }
    }

    private suspend fun fetchStreams(iframeUrl: String, origin: String, token: String, fileId: Long): AllohaStreams? {
        val payload = fetchPage(iframeUrl).signature ?: return null

        val apiHeaders = headers.newBuilder()
            .set("Referer", iframeUrl)
            .set("Origin", origin)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Borth", "$fingerprint|$payload")
            .build()

        val body = FormBody.Builder()
            .add("token", token)
            .add("av1", "false")
            .add("autoplay", "0")
            .add("audio", "")
            .add("subtitle", "")
            .build()

        val response = client.post("$origin/bnsi/movies/$fileId", apiHeaders, body, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.parseAs<AllohaStreams>()
    }

    /**
     * Audio labels look like "(Russian) Mega-Anime", "(Russian) Russian (Mega-Anime)",
     * "(Ukrainian) AC-3 20 (192 kb/s) - двоголосий закадровий | QTV" or
     * "(Japanese) DTS-HD MA 20 (1 670 kb/s) - 元の".
     */
    private class AudioTrack(val language: String, val name: String) {
        val key = "$language|${name.lowercase()}"

        val title = when (val prefix = LANGUAGE_NAMES[language]) {
            null -> "$language: $name"
            "" -> name
            else -> "$prefix: $name"
        }

        val order = LANGUAGE_ORDER.indexOf(language).takeIf { it >= 0 } ?: LANGUAGE_ORDER.size

        companion object {
            fun from(label: String): AudioTrack {
                val match = LABEL_REGEX.matchEntire(label.trim())
                val language = match?.groupValues?.get(1) ?: ""
                // Every Japanese track is the original audio, whatever the release calls it.
                if (language == "Japanese") return AudioTrack(language, "Оригинал")

                var name = (match?.groupValues?.get(2) ?: label).substringAfterLast(" - ").trim()
                name = name.substringAfter("$language (", "").removeSuffix(")").ifEmpty { name }
                if (" | " in name) {
                    name = "${name.substringAfterLast(" | ")} (${name.substringBeforeLast(" | ")})"
                }
                return AudioTrack(language, name)
            }
        }
    }

    private class AllohaPage(val fileList: AllohaFileList?, val signature: String?)

    private suspend fun fetchPage(iframeUrl: String): AllohaPage {
        // Never cached: the signature in the page is only valid for a single request.
        val html = client.get(iframeUrl, pageHeaders, CacheControl.FORCE_NETWORK).bodyString()

        val signature = Jsoup.parse(html).selectFirst("meta[name=viewporti]")
            ?.attr("content")
            ?.takeIf { it.isNotEmpty() }
            ?.let { primeStepShuffle(trailingZerosShuffle(bitLengthShuffle(it))) }

        val fileList = FILE_LIST_REGEX.find(html)?.groupValues?.get(1)
            ?.replace("\\'", "'")
            ?.let { runCatching { it.parseAs<AllohaFileList>() }.getOrNull() }

        return AllohaPage(fileList, signature)
    }

    private fun AllohaFileList.filesFor(episode: Int?): List<AllohaFile> = if (type == "serial") {
        val seasons = runCatching { all.parseAs<Map<String, Map<String, Map<String, AllohaFile>>>>() }
            .getOrNull().orEmpty()
        val key = (episode ?: 1).toString()
        seasons.entries
            .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            .firstNotNullOfOrNull { it.value[key] }
            ?.values?.toList()
            .orEmpty()
    } else {
        runCatching { all.parseAs<Map<String, AllohaFile>>().values.toList() }
            .getOrNull()
            ?: listOfNotNull(active)
    }

    // ─── Signature permutations (ported from the player's bundle) ────────────────────

    // Characters are bucketed by the bit length of their index; buckets are laid out from
    // the longest bit length down to zero.
    private fun bitLengthShuffle(s: String): String = bucketShuffle(s, descending = true) { i ->
        32 - Integer.numberOfLeadingZeros(i)
    }

    // Characters are bucketed by the number of trailing zero bits of their index (index 0
    // goes to the last bucket); buckets are laid out in ascending order.
    private fun trailingZerosShuffle(s: String): String {
        val k = bitsFor(s.length)
        return bucketShuffle(s, descending = false) { i ->
            if (i == 0) k else Integer.numberOfTrailingZeros(i)
        }
    }

    private inline fun bucketShuffle(s: String, descending: Boolean, bucketOf: (Int) -> Int): String {
        val n = s.length
        if (n <= 1) return s
        val k = bitsFor(n)

        val counts = IntArray(k + 1)
        for (i in 0 until n) counts[bucketOf(i)]++

        val starts = IntArray(k + 1)
        var pos = 0
        for (w in if (descending) k downTo 0 else 0..k) {
            starts[w] = pos
            pos += counts[w]
        }

        return buildString(n) {
            for (i in 0 until n) append(s[starts[bucketOf(i)]++])
        }
    }

    private fun bitsFor(n: Int): Int {
        var k = 0
        while (1 shl k < n) k++
        return k
    }

    // Walks the indices with a step of 2 modulo the smallest prime above the length; the
    // i-th character of the input goes to the i-th visited index.
    private fun primeStepShuffle(s: String): String {
        val n = s.length
        if (n <= 1) return s
        var p = maxOf(2, n + 1)
        while (!p.isPrime()) p++

        val out = CharArray(n)
        val used = BooleanArray(n)
        var step = 0
        var i = 0
        while (i < n) {
            step = (step + 2) % p
            if (step < n && !used[step]) {
                used[step] = true
                out[step] = s[i++]
            }
        }
        return String(out)
    }

    private fun Int.isPrime(): Boolean {
        if (this < 2) return false
        if (this % 2 == 0) return this == 2
        var d = 3
        while (d * d <= this) {
            if (this % d == 0) return false
            d += 2
        }
        return true
    }

    companion object {
        // Russian dubs get no prefix, like the Kodik ones next to them.
        private val LANGUAGE_NAMES = mapOf(
            "Russian" to "",
            "Ukrainian" to "Украинский",
            "English" to "Английский",
            "Japanese" to "Японский",
        )
        private val LANGUAGE_ORDER = listOf("Russian", "Ukrainian", "English")

        private val LABEL_REGEX = Regex("""\((\w+)\)\s*(.*)""")
        private val FILE_LIST_REGEX = Regex("""fileList\s*=\s*JSON\.parse\('(.+?)'\);""")
    }
}
