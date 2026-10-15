package dev.wind.agent.artifact

import dev.wind.agent.CancellationToken
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/** One HTTP response. Closing it also closes the underlying network connection. */
internal class ArtifactHttpResponse(
    val status: Int,
    val contentLength: Long?,
    val location: String?,
    val body: InputStream,
    val contentType: String? = null,
    private val closeConnection: () -> Unit
) : Closeable {
    override fun close() = closeConnection()
}

internal fun interface ArtifactHttpTransport {
    fun get(uri: URI, token: CancellationToken): ArtifactHttpResponse
}

class ArtifactDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A P7 download enters an app-private quarantine file before the CAS sees it.
 * The network grant is checked for the initial URL and independently for each
 * redirect. No downloaded path is returned to the agent.
 */
class SecureArtifactDownloader internal constructor(
    private val store: VerifiedArtifactStore,
    private val quarantineRoot: File,
    private val transport: ArtifactHttpTransport,
    private val freeSpaceBytes: (File) -> Long = { it.usableSpace },
    private val parseDownloadUrl: (String) -> URI = ArtifactNetworkPolicy::parseUrl,
    private val authorizeHop: (URI) -> Boolean
) {
    constructor(
        store: VerifiedArtifactStore,
        quarantineRoot: File,
        authorizeHop: (URI) -> Boolean
    ) : this(store, quarantineRoot, PinnedHttpsArtifactTransport(), { it.usableSpace }, ArtifactNetworkPolicy::parseUrl, authorizeHop)

    fun download(
        url: String,
        purpose: String,
        expectedSha256: String?,
        maxBytes: Long,
        token: CancellationToken,
        onProgress: (receivedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): VerifiedArtifact {
        if (purpose.isBlank() || purpose.length > 160 || purpose.any { it < ' ' }) {
            throw ArtifactDownloadException("DOWNLOAD_PURPOSE_INVALID")
        }
        if (maxBytes <= 0L) throw ArtifactDownloadException("DOWNLOAD_LIMIT_INVALID")
        val expected = expectedSha256?.lowercase()
        if (expected != null && !expected.matches(Regex("[0-9a-f]{64}"))) {
            throw ArtifactDownloadException("DOWNLOAD_SHA256_INVALID")
        }
        val canonicalRoot = quarantineRoot.canonicalFile
        if (canonicalRoot != quarantineRoot.absoluteFile ||
            (canonicalRoot.exists() && !canonicalRoot.isDirectory)
        ) throw ArtifactDownloadException("DOWNLOAD_QUARANTINE_ROOT_UNSAFE")
        Files.createDirectories(canonicalRoot.toPath())

        var current = parseDownloadUrl(url)
        val redirects = ArrayList<String>()
        val started = System.nanoTime()
        val wallBudget = MIN_WALL_NANOS +
            minOf((MAX_WALL_NANOS - MIN_WALL_NANOS) / 1_000_000_000L,
                maxBytes / MIN_BYTES_PER_SECOND) * 1_000_000_000L
        for (hop in 0..MAX_REDIRECTS) {
            token.throwIfCancelled()
            checkDeadline(started, wallBudget)
            val response = getWithRetry(current, token, started, wallBudget)
            response.use { reply ->
                if (reply.status in REDIRECT_CODES) {
                    if (hop == MAX_REDIRECTS) throw ArtifactDownloadException("DOWNLOAD_REDIRECT_LIMIT")
                    val location = reply.location ?: throw ArtifactDownloadException("DOWNLOAD_REDIRECT_LOCATION_MISSING")
                    val next = try { current.resolve(location) } catch (_: Exception) {
                        throw ArtifactDownloadException("DOWNLOAD_REDIRECT_LOCATION_INVALID")
                    }
                    current = parseDownloadUrl(next.toString())
                    redirects.add(current.toString())
                } else {
                    if (reply.status != 200) throw ArtifactDownloadException("DOWNLOAD_HTTP_STATUS_${reply.status}")
                    if (reply.contentLength != null && reply.contentLength > maxBytes) {
                        throw ArtifactDownloadException("DOWNLOAD_SIZE_LIMIT")
                    }
                    // Importing into the CAS copies the quarantined file once
                    // more before atomic publication. Leave room for both copies
                    // and a fixed reserve for the rest of the application.
                    if (reply.contentLength != null) {
                        if (reply.contentLength > (Long.MAX_VALUE - STORAGE_RESERVE_BYTES) / 2L) {
                            throw ArtifactDownloadException("DOWNLOAD_STORAGE_LIMIT")
                        }
                        requireFreeSpace(canonicalRoot, reply.contentLength * 2L)
                    }
                    val staged = Files.createTempFile(canonicalRoot.toPath(), ".p7-download-", ".part")
                    try {
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        var reportedAt = System.nanoTime()
                        onProgress(0L, reply.contentLength)
                        Files.newOutputStream(staged).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                token.throwIfCancelled()
                                checkDeadline(started, wallBudget)
                                val read = reply.body.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                if (read.toLong() > maxBytes - count) {
                                    throw ArtifactDownloadException("DOWNLOAD_SIZE_LIMIT")
                                }
                                count += read.toLong()
                                requireFreeSpace(canonicalRoot, count)
                                hash.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                if (System.nanoTime() - reportedAt >= 250_000_000L) {
                                    onProgress(count, reply.contentLength)
                                    reportedAt = System.nanoTime()
                                }
                            }
                        }
                        if (reply.contentLength != null && count != reply.contentLength) {
                            throw ArtifactDownloadException("DOWNLOAD_LENGTH_MISMATCH")
                        }
                        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        if (expected != null && actual != expected) {
                            throw ArtifactDownloadException("DOWNLOAD_SHA256_MISMATCH")
                        }
                        token.throwIfCancelled()
                        onProgress(count, reply.contentLength)
                        checkDeadline(started, wallBudget)
                        requireFreeSpace(canonicalRoot, count)
                        Files.newInputStream(staged).use { quarantined ->
                            return store.importQuarantined(
                                quarantined, actual, maxBytes,
                                ArtifactProvenance(url, redirects)
                            )
                        }
                    } finally {
                        Files.deleteIfExists(staged)
                    }
                }
            }
        }
        throw ArtifactDownloadException("DOWNLOAD_REDIRECT_LIMIT")
    }

    private fun requireFreeSpace(root: File, additionalBytes: Long) {
        if (additionalBytes < 0L || additionalBytes > Long.MAX_VALUE - STORAGE_RESERVE_BYTES ||
            freeSpaceBytes(root) < STORAGE_RESERVE_BYTES + additionalBytes
        ) {
            throw ArtifactDownloadException("DOWNLOAD_STORAGE_LIMIT")
        }
    }

    /** Retry only transient server responses before any bytes reach quarantine. */
    private fun getWithRetry(uri: URI, token: CancellationToken, started: Long, budget: Long): ArtifactHttpResponse {
        for (attempt in 0..2) {
            token.throwIfCancelled()
            checkDeadline(started, budget)
            if (!authorizeHop(uri)) throw ArtifactDownloadException("DOWNLOAD_HOP_NOT_AUTHORIZED")
            val response = transport.get(uri, token)
            if (response.status !in setOf(500, 502, 503, 504) || attempt == 2) return response
            response.close()
            // Short slices let Stop interrupt backoff even outside an agent worker.
            repeat((attempt + 1) * 4) {
                token.throwIfCancelled()
                Thread.sleep(50)
            }
        }
        error("unreachable")
    }

    private fun checkDeadline(started: Long, wallBudget: Long) {
        if (System.nanoTime() - started > wallBudget) {
            throw ArtifactDownloadException("DOWNLOAD_TIMEOUT")
        }
    }

    companion object {
        /** Fixed runtime-pack HTTPS authorities may use the device VPN's virtual DNS routes.
         * Approval is checked on every hop; transport validates names before DNS/TLS.
         * Package trust remains the APK manifest/payload checks, not the returned IP.
         */
        fun runtimeToolPacks(store: VerifiedArtifactStore, quarantineRoot: File,
            approvedHosts: Set<String>, authorizeHop: (URI) -> Boolean): SecureArtifactDownloader {
            require(approvedHosts.isNotEmpty()) { "DOWNLOAD_APPROVED_HOSTS_REQUIRED" }
            val transport = PinnedHttpsArtifactTransport(vpnCompatibleHttpsHosts = approvedHosts)
            return SecureArtifactDownloader(store, quarantineRoot, transport, { it.usableSpace },
                { transport.validateDestination(URI(it)) }, authorizeHop)
        }

        /** HTTP and arbitrary public ports are accepted only for inert quarantine downloads. */
        fun quarantine(store: VerifiedArtifactStore, quarantineRoot: File, authorizeHop: (URI) -> Boolean): SecureArtifactDownloader =
            SecureArtifactDownloader(store, quarantineRoot, PinnedHttpsArtifactTransport(quarantineOnly = true),
                { it.usableSpace }, QuarantineDownloadPolicy::parseUrl, authorizeHop)

        private const val MAX_REDIRECTS = 5
        private const val MIN_WALL_NANOS = 120L * 1_000_000_000L
        private const val MAX_WALL_NANOS = 30L * 60L * 1_000_000_000L
        private const val MIN_BYTES_PER_SECOND = 1024L * 1024L
        private const val STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
