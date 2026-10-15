package dev.wind.agent

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Read-only web transport. Uses Android's proxy/TLS stack; never supplies install authority. */
internal class WebHttpTransport(
    private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) {
    data class Reply(val finalUrl: String, val text: String, val contentType: String,
        val sha256: String, val byteSize: Int)

    fun request(url: String, token: CancellationToken, authorizeHop: (URI) -> Boolean,
        timeoutMs: Long = 30_000L, maxBytes: Int = 3_000_000,
        headers: Map<String, String> = emptyMap(), jsonBody: String? = null): Reply {
        require(timeoutMs in 1_000L..60_000L && maxBytes in 1..4_000_000) { "WEB_LIMIT_INVALID" }
        require(headers.all { (name, value) -> name in setOf("Accept", "Authorization", "X-Subscription-Token") &&
            value.length in 1..4096 && value.all { it.code in 0x20..0x7e } }) { "WEB_HEADER_INVALID" }
        var current = parseUrl(url)
        val origin = origin(current)
        val started = System.nanoTime()
        fun remaining(): Int {
            token.throwIfCancelled()
            val millis = timeoutMs - (System.nanoTime() - started) / 1_000_000L
            if (millis <= 0) throw IllegalStateException("WEB_TIMEOUT")
            return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        for (hop in 0..5) {
            remaining()
            if (!authorizeHop(current)) throw SecurityException("FETCH_HOP_NOT_AUTHORIZED: ${current.host}")
            // API credentials and POST bodies never cross origins or get silently replayed.
            if (origin(current) != origin && (jsonBody != null || headers.keys.any {
                    it == "Authorization" || it == "X-Subscription-Token" })) {
                throw SecurityException("WEB_CREDENTIAL_REDIRECT_BLOCKED")
            }
            val connection = openConnection(current)
            val watch = token.watchConnection(connection)
            val timedOut = AtomicBoolean(false)
            var timeout: ScheduledFuture<*>? = null
            try {
                timeout = TIMER.schedule({ timedOut.set(true); connection.disconnect() }, remaining().toLong(), TimeUnit.MILLISECONDS)
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remaining()
                connection.readTimeout = remaining()
                connection.requestMethod = if (jsonBody == null) "GET" else "POST"
                connection.setRequestProperty("User-Agent", Web.UA)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*;q=0.8")
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
                for ((name, value) in headers) connection.setRequestProperty(name, value)
                if (jsonBody != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val body = jsonBody.toByteArray(Charsets.UTF_8)
                    require(body.size <= 64 * 1024) { "WEB_REQUEST_TOO_LARGE" }
                    connection.setFixedLengthStreamingMode(body.size)
                    remaining()
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                remaining()
                if (code in setOf(301, 302, 303, 307, 308)) {
                    if (jsonBody != null) throw SecurityException("WEB_POST_REDIRECT_BLOCKED")
                    if (hop == 5) throw IllegalStateException("WEB_REDIRECT_LIMIT")
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("WEB_REDIRECT_LOCATION_MISSING")
                    current = parseUrl(current.resolve(location).toString())
                    continue
                }
                if (code !in 200..299) throw IllegalStateException(when (code) {
                    401, 403 -> "WEB_HTTP_$code: access denied; check provider API key or site permissions"
                    429, 432, 433 -> "WEB_HTTP_$code: provider rate or quota limit"
                    else -> "WEB_HTTP_$code"
                })
                val contentType = connection.contentType ?: ""
                val media = contentType.substringBefore(';').trim().lowercase(Locale.US)
                if (media.isNotEmpty() && !media.startsWith("text/") &&
                    media !in setOf("application/json", "application/xml", "application/xhtml+xml", "application/javascript") &&
                    !media.endsWith("+json") && !media.endsWith("+xml")) {
                    throw IllegalStateException("WEB_MEDIA_TYPE_UNSUPPORTED: $media")
                }
                val encoding = connection.getHeaderField("Content-Encoding")?.lowercase(Locale.US)?.trim()
                val raw = connection.inputStream
                val input = when (encoding) {
                    null, "", "identity" -> raw
                    "gzip" -> GZIPInputStream(raw)
                    "deflate" -> InflaterInputStream(raw)
                    else -> { raw.close(); throw IllegalStateException("WEB_ENCODING_UNSUPPORTED") }
                }
                val bytes = ByteArrayOutputStream(minOf(maxBytes, 8192))
                input.use {
                    val buffer = ByteArray(8192)
                    while (true) {
                        connection.readTimeout = remaining()
                        val read = it.read(buffer, 0, minOf(buffer.size, maxBytes - bytes.size() + 1))
                        remaining()
                        if (read < 0) break
                        if (bytes.size() + read > maxBytes) throw IllegalStateException("WEB_SIZE_LIMIT")
                        bytes.write(buffer, 0, read)
                    }
                }
                val content = bytes.toByteArray()
                val text = decode(content, contentType)
                if (text.contains('\u0000')) throw IllegalStateException("WEB_BINARY_CONTENT")
                val digest = MessageDigest.getInstance("SHA-256").digest(content)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                return Reply(current.toString(), text, contentType, digest, content.size)
            } catch (error: Exception) {
                token.throwIfCancelled()
                if (timedOut.get()) throw IllegalStateException("WEB_TIMEOUT", error)
                remaining()
                throw error
            } finally {
                timeout?.cancel(false)
                watch.close()
                connection.disconnect()
            }
        }
        throw IllegalStateException("WEB_REDIRECT_LIMIT")
    }

    companion object {
        private val TIMER = Executors.newScheduledThreadPool(1) { runnable ->
            Thread(runnable, "web-request-deadline").apply { isDaemon = true }
        }
        fun parseUrl(raw: String): URI {
            require(raw.length in 1..8192 && raw.none { it <= ' ' || it == '\u007f' }) { "WEB_URL_INVALID" }
            NetworkPolicy.requireSafeHttps(raw)
            val uri = URI(raw)
            // Fragments are browser-local and never part of the request or authority.
            return URI(uri.toASCIIString().substringBefore('#'))
        }
        private fun origin(uri: URI): String = "${uri.scheme.lowercase(Locale.US)}://${uri.host.lowercase(Locale.US)}:${if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80}"
        private fun decode(bytes: ByteArray, contentType: String): String {
            val declared = Regex("charset\\s*=\\s*[\"']?([\\w.-]+)", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)
            val meta = if (declared == null && (contentType.isEmpty() || contentType.contains("html", true)))
                Regex("<meta[^>]{0,400}?charset\\s*=\\s*[\"']?([\\w.-]+)", RegexOption.IGNORE_CASE)
                    .find(String(bytes, 0, minOf(bytes.size, 8192), Charsets.ISO_8859_1))?.groupValues?.get(1) else null
            val charset = try { Charset.forName(declared ?: meta ?: "UTF-8") }
                catch (_: Exception) { Charsets.UTF_8 }
            return String(bytes, charset).removePrefix("\uFEFF")
        }
    }
}
