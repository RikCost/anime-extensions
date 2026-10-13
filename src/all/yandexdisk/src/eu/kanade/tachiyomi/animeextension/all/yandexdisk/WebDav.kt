package eu.kanade.tachiyomi.animeextension.all.yandexdisk

import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.text.SimpleDateFormat

const val WEBDAV_HOST = "webdav.yandex.ru"

private const val WEBDAV_ORIGIN = "https://$WEBDAV_HOST"
private const val WEBDAV_PREVIEW_SIZE = "L"

// Yandex only returns the properties a client asks for, so the interesting ones are listed here.
private val PROPFIND_BODY: RequestBody = """
    <?xml version="1.0" encoding="utf-8"?>
    <d:propfind xmlns:d="DAV:">
        <d:prop>
            <d:displayname/>
            <d:resourcetype/>
            <d:getcontentlength/>
            <d:getlastmodified/>
        </d:prop>
    </d:propfind>
""".trimIndent().toRequestBody("application/xml".toMediaType())

private val NAMESPACE_PREFIX_REGEX = Regex("""<(/?)[A-Za-z0-9]+:""")

internal fun webdavUrl(path: String): HttpUrl = WEBDAV_ORIGIN.toHttpUrl().newBuilder().apply {
    path.trim('/').takeIf { it.isNotEmpty() }?.let { addPathSegments(it) }
}.build()

internal fun webdavListUrl(path: String): HttpUrl = webdavUrl(path).newBuilder().addPathSegment("").build()

/** Yandex WebDAV renders image previews with the non-standard `?preview&size=` query. */
internal fun webdavPreviewUrl(path: String): String = webdavUrl(path).newBuilder()
    .addQueryParameter("preview", null)
    .addQueryParameter("size", WEBDAV_PREVIEW_SIZE)
    .build()
    .toString()

internal fun propfindRequest(path: String): Request = Request.Builder()
    .url(webdavListUrl(path))
    .header("Depth", "1")
    .method("PROPFIND", PROPFIND_BODY)
    .build()

internal suspend fun OkHttpClient.propfind(path: String, dateFormat: SimpleDateFormat): List<DiskEntry> =
    newCall(propfindRequest(path)).await().use { it.toPropfindEntries(path, dateFormat) }

internal fun Response.toPropfindEntries(path: String, dateFormat: SimpleDateFormat): List<DiskEntry> = when {
    isSuccessful -> parsePropfind(body.string(), path, dateFormat)
    code == 401 -> throw IOException("WebDAV: wrong login or app password.")
    code == 404 -> throw IOException("WebDAV: folder \"$path\" not found.")
    else -> throw IOException("WebDAV: HTTP $code")
}

private fun parsePropfind(xml: String, requestedPath: String, dateFormat: SimpleDateFormat): List<DiskEntry> {
    val document = Jsoup.parse(NAMESPACE_PREFIX_REGEX.replace(xml) { "<${it.groupValues[1]}" }, "", Parser.xmlParser())
    val ownPath = requestedPath.asDiskPath()

    return document.select("response").mapNotNull { response ->
        val href = response.selectFirst("href")?.text() ?: return@mapNotNull null
        val path = hrefToPath(href)
        if (path == ownPath) return@mapNotNull null

        DiskEntry(
            name = response.selectFirst("displayname")?.text()?.ifEmpty { null } ?: path.substringAfterLast('/'),
            path = path,
            isDir = response.select("resourcetype collection").isNotEmpty(),
            size = response.selectFirst("getcontentlength")?.text()?.toLongOrNull() ?: 0L,
            modified = dateFormat.tryParse(response.selectFirst("getlastmodified")?.text()),
        )
    }
}

internal fun hrefToPath(href: String): String {
    val url = if (href.startsWith("http")) href.toHttpUrl() else (WEBDAV_ORIGIN + href).toHttpUrl()

    return url.pathSegments.filter { it.isNotEmpty() }.joinToString("/", prefix = "/").asDiskPath()
}
