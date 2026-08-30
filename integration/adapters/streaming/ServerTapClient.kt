package com.tv49eastz.integration.adapters.streaming

import com.tv49eastz.core.model.Channel
import com.tv49eastz.core.model.StreamSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Concrete, fixed-origin HTTP transport for the server-tap.
 *
 * The tap origin is deployment configuration. Callers cannot supply an upstream URL,
 * path, host, or redirect target. This client only performs read-only GET requests and
 * validates the tap's HLS entry point before publishing a canonical StreamSource.
 *
 * Call from an IO dispatcher/thread; this implementation intentionally uses the
 * Android-compatible HttpURLConnection API and does not add a networking dependency.
 */
class ServerTapClient(
    tapBaseUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
    private val maxPlaylistBytes: Int = 1_048_576
) {
    private val baseUri = normalizeAndValidateBaseUri(tapBaseUrl)

    /** Returns true only when the configured tap answers its health endpoint. */
    fun isHealthy(): Boolean = getText("/health").let { response ->
        response.code in 200..299 && response.body.contains("\"ok\":true")
    }

    /**
     * Resolves the fixed tap HLS entry point into a canonical source.
     * The returned URI is the tap URI, never the private FadCam upstream URI.
     */
    fun resolveLiveSource(channel: Channel, providerId: String = "server-tap"): StreamSource {
        val response = getText("/live.m3u8")
        require(response.code in 200..299) { "server-tap returned HTTP ${response.code}" }
        require(response.body.startsWith("#EXTM3U")) { "server-tap did not return an HLS playlist" }

        return StreamSource(
            id = "server-tap:${channel.id}",
            providerId = providerId,
            channelId = channel.id,
            kind = StreamSource.Kind.HLS,
            uri = buildTapUrl("/live.m3u8"),
            mimeType = "application/vnd.apple.mpegurl",
            priority = 0,
            enabled = true
        )
    }

    private fun getText(path: String): Response {
        val url = URL(buildTapUrl(path))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/json,text/plain")
            setRequestProperty("User-Agent", "tv49eastz-server-tap-client/1")
        }

        return try {
            val code = connection.responseCode
            require(code !in 300..399) { "server-tap redirects are not permitted" }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBounded(maxPlaylistBytes) } ?: ""
            Response(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildTapUrl(path: String): String =
        baseUri.resolve(path.removePrefix("/")).toString()

    private data class Response(val code: Int, val body: String)

    private fun java.io.InputStream.readBounded(limit: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("server-tap response exceeds configured limit")
            bytes.write(buffer, 0, read)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        private fun normalizeAndValidateBaseUri(raw: String): URI {
            val uri = try { URI(raw.trim()) } catch (e: Exception) {
                throw IllegalArgumentException("invalid server-tap URL", e)
            }
            require(uri.scheme == "http" || uri.scheme == "https") { "server-tap URL must use HTTP(S)" }
            require(!uri.userInfo.isNullOrBlank()) { "server-tap URL must not contain userinfo" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "server-tap URL must not contain query/fragment" }
            require(!uri.host.isNullOrBlank()) { "server-tap URL must contain a host" }
            return URI(uri.scheme, null, uri.host, if (uri.port >= 0) uri.port else -1,
                if (uri.path.isNullOrBlank()) "/" else uri.path.trimEnd('/') + "/", null, null)
        }
    }
}
