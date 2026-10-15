package dev.wind.agent.artifact

import dev.wind.agent.CancellationToken
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Exact bounded metadata fetch. Returned text is untrusted data, never artifact/install authority. */
data class FetchedText(
    val finalUrl: String,
    val utf8Text: String,
    val sha256: String,
    val byteSize: Int
)

class AuthorizedTextFetcher internal constructor(
    private val transport: ArtifactHttpTransport,
    private val authorizeHop: (URI) -> Boolean
) {
    constructor(authorizeHop: (URI) -> Boolean) : this(PinnedHttpsArtifactTransport(), authorizeHop)

    fun fetch(url: String, purpose: String, maxBytes: Int, token: CancellationToken,
        timeoutMillis: Long = 60_000L): FetchedText {
        if (purpose.isBlank() || purpose.length > 160 || purpose.any { it < ' ' }) {
            throw ArtifactDownloadException("FETCH_PURPOSE_INVALID")
        }
        if (maxBytes !in 1..MAX_TEXT_BYTES) throw ArtifactDownloadException("FETCH_LIMIT_INVALID")
        if (timeoutMillis !in 1_000L..60_000L) throw ArtifactDownloadException("FETCH_TIMEOUT_INVALID")
        var current = ArtifactNetworkPolicy.parseUrl(url)
        val started = System.nanoTime()
        for (hop in 0..MAX_REDIRECTS) {
            token.throwIfCancelled()
            checkDeadline(started, timeoutMillis)
            if (!authorizeHop(current)) throw ArtifactDownloadException("FETCH_HOP_NOT_AUTHORIZED")
            transport.get(current, token).use { reply ->
                if (reply.status in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) throw ArtifactDownloadException("FETCH_REDIRECT_LIMIT")
                    val location = reply.location ?: throw ArtifactDownloadException("FETCH_REDIRECT_LOCATION_MISSING")
                    current = try {
                        ArtifactNetworkPolicy.parseUrl(current.resolve(location).toString())
                    } catch (failure: Exception) {
                        throw ArtifactDownloadException("FETCH_REDIRECT_LOCATION_INVALID", failure)
                    }
                } else {
                    if (reply.status != 200) throw ArtifactDownloadException("FETCH_HTTP_STATUS_${reply.status}")
                    if (reply.contentLength != null && reply.contentLength > maxBytes) {
                        throw ArtifactDownloadException("FETCH_SIZE_LIMIT")
                    }
                    val mediaType = reply.contentType?.substringBefore(';')?.trim()?.lowercase()
                    if (mediaType != null && mediaType !in TEXT_MEDIA_TYPES &&
                        !mediaType.endsWith("+json") && !mediaType.endsWith("+xml") &&
                        mediaType != "application/octet-stream" && !mediaType.startsWith("text/")) {
                        throw ArtifactDownloadException("FETCH_MEDIA_TYPE_UNSUPPORTED")
                    }
                    val bytes = ByteArrayOutputStream(minOf(maxBytes, 8192))
                    val buffer = ByteArray(8192)
                    while (true) {
                        token.throwIfCancelled()
                        checkDeadline(started, timeoutMillis)
                        val read = reply.body.read(buffer, 0, minOf(buffer.size, maxBytes - bytes.size() + 1))
                        if (read < 0) break
                        if (read == 0) continue
                        if (bytes.size() + read > maxBytes) throw ArtifactDownloadException("FETCH_SIZE_LIMIT")
                        bytes.write(buffer, 0, read)
                    }
                    if (reply.contentLength != null && bytes.size().toLong() != reply.contentLength) {
                        throw ArtifactDownloadException("FETCH_SIZE_MISMATCH")
                    }
                    val content = bytes.toByteArray()
                    val text = try {
                        StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(content)).toString().removePrefix("\uFEFF")
                    } catch (failure: Exception) {
                        throw ArtifactDownloadException("FETCH_UTF8_INVALID", failure)
                    }
                    if (text.any { it == '\u0000' }) throw ArtifactDownloadException("FETCH_BINARY_CONTENT")
                    val digest = MessageDigest.getInstance("SHA-256").digest(content)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    return FetchedText(current.toString(), text, digest, content.size)
                }
            }
        }
        throw ArtifactDownloadException("FETCH_REDIRECT_LIMIT")
    }

    private fun checkDeadline(started: Long, timeoutMillis: Long) {
        if (System.nanoTime() - started > timeoutMillis * 1_000_000L) {
            throw ArtifactDownloadException("FETCH_TIMEOUT")
        }
    }

    private companion object {
        const val MAX_TEXT_BYTES = 4 * 1024 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val TEXT_MEDIA_TYPES = setOf(
            "text/plain", "text/html", "text/xml", "text/css", "text/markdown",
            "application/json", "application/xml", "application/xhtml+xml",
            "application/x-pom+xml", "application/javascript"
        )
    }
}
