package eu.kanade.tachiyomi.animeextension.ru.jutsu

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import keiyoushi.utils.bodyString
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Alloha player (`*.thealloha.club`).
 *
 * The iframe page carries the whole translation tree in `fileList`, and the streams of one
 * translation are returned by `POST /bnsi/movies/<fileId>`, signed with a single-use value
 * hidden in the page. The server tells real browsers from HTTP clients — OkHttp gets a
 * normal-looking answer whose stream links all fail with 403 — so the request has to come
 * from a browser network stack.
 *
 * A hidden [WebView] therefore loads the player the way jutsu.tv embeds it, and lets the
 * player sign and send the request itself. A small hook, prepended to the one player script
 * that is not covered by Subresource Integrity, points that request at the wanted file and
 * hands the response back through a JavaScript interface.
 */
class AllohaExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val siteUrl: String,
) {

    private val context: Application by injectLazy()

    private val pageHeaders by lazy {
        headers.newBuilder()
            .set("Referer", "$siteUrl/")
            .build()
    }

    /**
     * One hoster per distinct audio track. A translation in `fileList` is really a file with
     * several muxed audio tracks, and the same track shows up in several files, so every file
     * is fetched up front and the tracks are merged by language and name.
     */
    suspend fun hostersFromUrl(iframeUrl: String, episode: Int?): List<Hoster> {
        val url = iframeUrl.toHttpUrl()
        val origin = "${url.scheme}://${url.host}"

        val fileList = fetchFileList(iframeUrl) ?: return emptyList()
        val streams = fetchStreams(iframeUrl, fileList.filesFor(episode).map { it.id })
        if (streams.isEmpty()) return emptyList()

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

    private suspend fun fetchFileList(iframeUrl: String): AllohaFileList? {
        val html = client.get(iframeUrl, pageHeaders).bodyString()
        return FILE_LIST_REGEX.find(html)?.groupValues?.get(1)
            ?.replace("\\'", "'")
            ?.let { runCatching { it.parseAs<AllohaFileList>() }.getOrNull() }
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

    // ─── WebView ─────────────────────────────────────────────────────────────────────

    /**
     * Loads the player once per file in a single hidden WebView. Every load issues a fresh
     * signature, which the player spends on the one request the hook redirects.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun fetchStreams(iframeUrl: String, fileIds: List<Long>): List<AllohaStreams> {
        if (fileIds.isEmpty()) return emptyList()

        val results = ConcurrentHashMap<Long, CompletableDeferred<String>>()
        // Read from the WebView's network thread when the hooked script is requested.
        val wantedId = AtomicLong()
        val bridge = Bridge(results)

        val webView = withContext(Dispatchers.Main) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                headers["User-Agent"]?.let { settings.userAgentString = it }
                addJavascriptInterface(bridge, BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (!request.url.path.orEmpty().endsWith(HOOKED_SCRIPT)) return null
                        val original = runCatching {
                            client.newCall(GET(request.url.toString(), headers)).execute().bodyString()
                        }.getOrDefault("")
                        val script = hookScript(wantedId.get()) + "\n" + original
                        return WebResourceResponse(
                            "application/javascript",
                            "utf-8",
                            ByteArrayInputStream(script.toByteArray()),
                        )
                    }
                }
            }
        }

        // Embedded like on the site: the page refuses to render outside an iframe, and the
        // server only serves it with the site as the referrer.
        val wrapper = """<html><body><iframe src="$iframeUrl" width="640" height="360"></iframe></body></html>"""

        try {
            return fileIds.mapNotNull { fileId ->
                val result = CompletableDeferred<String>()
                results[fileId] = result
                wantedId.set(fileId)
                withContext(Dispatchers.Main) {
                    webView.loadDataWithBaseURL("$siteUrl/", wrapper, "text/html", "utf-8", null)
                }
                withTimeoutOrNull(LOAD_TIMEOUT_MS) { result.await() }
                    ?.let { runCatching { it.parseAs<AllohaStreams>() }.getOrNull() }
                    ?.takeIf { it.hlsSource.isNotEmpty() }
            }
        } finally {
            withContext(Dispatchers.Main) {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private class Bridge(private val results: Map<Long, CompletableDeferred<String>>) {
        @JavascriptInterface
        fun onResult(fileId: String, json: String) {
            fileId.toLongOrNull()?.let { results[it]?.complete(json) }
        }
    }

    // Rewrites the player's stream request to the wanted file and reports the answer.
    private fun hookScript(fileId: Long) = """
        (function () {
            var open = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function (method, url) {
                if (typeof url === 'string' && url.indexOf('/bnsi/movies/') !== -1) {
                    arguments[1] = url.replace(/\/bnsi\/movies\/\d+/, '/bnsi/movies/$fileId');
                    this.addEventListener('load', function () {
                        try { $BRIDGE_NAME.onResult('$fileId', this.responseText); } catch (e) {}
                    });
                }
                return open.apply(this, arguments);
            };
        })();
    """.trimIndent()

    // ─── Audio track labels ──────────────────────────────────────────────────────────

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

    companion object {
        private const val BRIDGE_NAME = "JutsuAllohaBridge"

        // Loaded synchronously before the player bundle and, unlike it, not pinned by an
        // integrity hash, so a hook prepended to it runs before the player starts.
        private const val HOOKED_SCRIPT = "rmp-vast.min.js"

        private const val LOAD_TIMEOUT_MS = 20_000L

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
