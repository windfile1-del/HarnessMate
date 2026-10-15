package dev.wind.agent.artifact

import dev.wind.agent.CancellationToken
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * HTTPS over a socket connected to the vetted DNS answer. This avoids the
 * second, uncontrolled hostname lookup performed by URL.openConnection().
 * HTTP/1.1 framing is intentionally narrow: no proxy, compression or cookies.
 */
internal class PinnedHttpsArtifactTransport(
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val dnsTimeoutMillis: Int = 10_000,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val quarantineOnly: Boolean = false,
    vpnCompatibleHttpsHosts: Set<String> = emptySet(),
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    private val brokerAuthorization: Boolean = false
) : ArtifactHttpTransport {
    /** Preserve the original DNS-test seam's trailing-lambda call shape. */
    constructor(resolve: (String) -> Array<InetAddress>) : this(requestHeaders = emptyMap(), resolve = resolve)
    private val vpnHttpsHosts = vpnCompatibleHttpsHosts.map { it.lowercase(Locale.US) }.toSet()
    init {
        require(!quarantineOnly || vpnHttpsHosts.isEmpty()) { "DOWNLOAD_ROUTE_POLICY_INVALID" }
        vpnHttpsHosts.forEach { ArtifactNetworkPolicy.parseUrl("https://$it/") }
        if (dnsTimeoutMillis !in 1_000..15_000 || connectTimeoutMillis !in 1_000..15_000 ||
            readTimeoutMillis !in 1_000..15_000) {
            throw ArtifactDownloadException("DOWNLOAD_TIMEOUT_INVALID")
        }
        if (requestHeaders.any { (name, value) ->
                name !in (if(brokerAuthorization) setOf("Accept", "X-Subscription-Token", "Authorization") else setOf("Accept", "X-Subscription-Token")) ||
                    value.isEmpty() || value.length > (if(brokerAuthorization && name=="Authorization") 8192 else 4096) ||
                    value.any { it.code !in (if(brokerAuthorization && name=="Authorization") 0x20..0x7e else 0x21..0x7e) }
            }) throw ArtifactDownloadException("DOWNLOAD_REQUEST_HEADER_INVALID")
    }

    override fun get(uri: URI, token: CancellationToken): ArtifactHttpResponse {
        val checked = validateDestination(uri)
        val host = checked.host.lowercase(Locale.US)
        val https = checked.scheme.equals("https", ignoreCase = true)
        val port = if (checked.port >= 0) checked.port else if (https) 443 else 80
        val addresses = try {
            val future = DNS_EXECUTOR.submit<Array<InetAddress>> { resolve(host) }
            try { future.get(dnsTimeoutMillis.toLong(), TimeUnit.MILLISECONDS) }
            finally { future.cancel(true) }
        } catch (failure: Exception) {
            throw ArtifactDownloadException("DOWNLOAD_DNS_FAILED", failure)
        }
        if (addresses.isEmpty() || addresses.any { !addressAllowed(it, host) }) {
            val diagnostic = if (host in vpnHttpsHosts) ": host=$host; route=APP_APPROVED_HTTPS; blocked=" +
                addresses.filter { !addressAllowed(it, host) }.joinToString(",") { it.hostAddress.orEmpty() } else ""
            throw ArtifactDownloadException("DOWNLOAD_DNS_ADDRESS_BLOCKED$diagnostic")
        }
        token.throwIfCancelled()

        val raw = Socket()
        var tls: SSLSocket? = null
        val watch = token.onCancel {
            try { tls?.close() } catch (_: Exception) { }
            try { raw.close() } catch (_: Exception) { }
        }
        try {
            raw.connect(InetSocketAddress(addresses[0], port), connectTimeoutMillis)
            raw.soTimeout = readTimeoutMillis
            token.throwIfCancelled()
            if (https) {
                tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
                tls.soTimeout = readTimeoutMillis
                val handshakeChecksHost = configureHostnameVerification(tls)
                tls.startHandshake()
                if (!handshakeChecksHost && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                    throw ArtifactDownloadException("DOWNLOAD_TLS_HOST_MISMATCH")
                }
            }
            val connection = tls ?: raw
            token.throwIfCancelled()
            val target = (checked.rawPath?.ifEmpty { "/" } ?: "/") +
                (checked.rawQuery?.let { "?$it" } ?: "")
            val authority = (if (host.contains(':') && !host.startsWith('[')) "[$host]" else host) +
                (if (port == (if (https) 443 else 80)) "" else ":$port")
            val request = "GET $target HTTP/1.1\r\nHost: $authority\r\n" +
                "User-Agent: VegaSafe-P7/1\r\nAccept-Encoding: identity\r\n" +
                requestHeaders.entries.sortedBy { it.key }.joinToString("") { (name, value) ->
                    "$name: $value\r\n"
                } + "Connection: close\r\n\r\n"
            connection.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
            connection.outputStream.flush()
            return parseResponse(BufferedInputStream(connection.inputStream)) {
                watch.close()
                connection.close()
            }
        } catch (failure: Exception) {
            watch.close()
            try { tls?.close() } catch (_: Exception) { }
            try { raw.close() } catch (_: Exception) { }
            throw failure
        }
    }

    /** General isolated downloads also work with LAN servers and proxy Fake-IP DNS. */
    internal fun validateDestination(uri: URI): URI {
        val checked = if (quarantineOnly) QuarantineDownloadPolicy.parseUrl(uri.toString())
            else ArtifactNetworkPolicy.parseUrl(uri.toString())
        if (vpnHttpsHosts.isNotEmpty() && checked.host.lowercase(Locale.US) !in vpnHttpsHosts)
            throw ArtifactDownloadException("DOWNLOAD_HOST_NOT_APPROVED")
        return checked
    }

    /** VPN DNS maps names to virtual routes, not necessarily public server addresses.
     * Only app-approved HTTPS names opt in: TLS still authenticates the original hostname.
     * Generic artifact destinations retain their strict IP policy.
     */
    internal fun addressAllowed(address: InetAddress, host: String? = null): Boolean {
        if (quarantineOnly) return !address.isAnyLocalAddress && !address.isMulticastAddress
        if (host?.lowercase(Locale.US) !in vpnHttpsHosts) return ArtifactNetworkPolicy.isPublic(address)
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress)
            return false
        val bytes = address.address
        return when (bytes.size) {
            4 -> (bytes[0].toInt() and 255) in 1..223
            16 -> true
            else -> false
        }
    }

    /** JVM HTTPS verification belongs in the TLS handshake; API23 retains its platform verifier. */
    internal fun configureHostnameVerification(socket: SSLSocket): Boolean {
        val parameters = socket.sslParameters
        val setter = try {
            parameters.javaClass.getMethod("setEndpointIdentificationAlgorithm", String::class.java)
        } catch (_: NoSuchMethodException) {
            return false
        }
        try {
            setter.invoke(parameters, "HTTPS")
            socket.sslParameters = parameters
        } catch (failure: ReflectiveOperationException) {
            throw ArtifactDownloadException("DOWNLOAD_TLS_CONFIG_FAILED", failure)
        }
        return true
    }

    /** Parse untrusted HTTP framing without opening a socket (also used by tests). */
    internal fun parseResponse(source: InputStream, closeConnection: () -> Unit): ArtifactHttpResponse {
        val input = if (source is BufferedInputStream) source else BufferedInputStream(source)
        val statusLine = readLine(input, 4096)
        val status = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*|)")
            .matchEntire(statusLine)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw ArtifactDownloadException("DOWNLOAD_HTTP_STATUS_INVALID")
        val headers = LinkedHashMap<String, String>()
        var headerBytes = statusLine.length + 2
        var headerCount = 0
        while (true) {
            val line = readLine(input, 4096)
            headerBytes += line.length + 2
            if (headerBytes > 24 * 1024 || ++headerCount > 80) {
                throw ArtifactDownloadException("DOWNLOAD_HTTP_HEADERS_TOO_LARGE")
            }
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0 || line[0] == ' ' || line[0] == '\t') {
                throw ArtifactDownloadException("DOWNLOAD_HTTP_HEADER_INVALID")
            }
            val name = line.substring(0, colon).lowercase(Locale.US)
            if (!name.matches(Regex("[a-z0-9-]+")) || headers.containsKey(name)) {
                throw ArtifactDownloadException("DOWNLOAD_HTTP_HEADER_INVALID")
            }
            headers[name] = line.substring(colon + 1).trim()
        }
        val encoding = headers["content-encoding"]
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
            throw ArtifactDownloadException("DOWNLOAD_ENCODING_UNSUPPORTED")
        }
        val contentLength = headers["content-length"]?.let {
            if (!it.matches(Regex("[0-9]{1,18}"))) throw ArtifactDownloadException("DOWNLOAD_LENGTH_INVALID")
            it.toLongOrNull() ?: throw ArtifactDownloadException("DOWNLOAD_LENGTH_INVALID")
        }
        val transfer = headers["transfer-encoding"]?.lowercase(Locale.US)
        if (transfer != null && (transfer != "chunked" || contentLength != null)) {
            throw ArtifactDownloadException("DOWNLOAD_TRANSFER_UNSUPPORTED")
        }
        val body: InputStream = when {
            transfer == "chunked" -> ChunkedBody(input)
            contentLength != null -> FixedBody(input, contentLength)
            else -> input
        }
        return ArtifactHttpResponse(status, contentLength, headers["location"], body,
            headers["content-type"], closeConnection)
    }

    private class FixedBody(private val input: InputStream, length: Long) : InputStream() {
        private var remaining = length
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = input.read()
            if (value < 0) throw IOException("DOWNLOAD_BODY_TRUNCATED")
            remaining--
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException()
            }
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read < 0) throw IOException("DOWNLOAD_BODY_TRUNCATED")
            remaining -= read
            return read
        }
    }

    private class ChunkedBody(private val input: InputStream) : InputStream() {
        private var remaining = 0L
        private var ended = false
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException()
            }
            if (length == 0) return 0
            if (ended) return -1
            if (remaining == 0L) {
                val size = readLine(input, 1024).substringBefore(';')
                if (!size.matches(Regex("[0-9a-fA-F]{1,16}"))) throw IOException("DOWNLOAD_CHUNK_INVALID")
                remaining = size.toLongOrNull(16) ?: throw IOException("DOWNLOAD_CHUNK_INVALID")
                if (remaining == 0L) {
                    var trailerBytes = 0
                    while (true) {
                        val trailer = readLine(input, 1024)
                        trailerBytes += trailer.length + 2
                        if (trailerBytes > 8192) throw IOException("DOWNLOAD_TRAILER_TOO_LARGE")
                        if (trailer.isEmpty()) break
                    }
                    ended = true
                    return -1
                }
            }
            val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read < 0) throw IOException("DOWNLOAD_BODY_TRUNCATED")
            remaining -= read
            if (remaining == 0L && (input.read() != '\r'.code || input.read() != '\n'.code)) {
                throw IOException("DOWNLOAD_CHUNK_TERMINATOR_INVALID")
            }
            return read
        }
    }

    private companion object {
        val DNS_EXECUTOR = Executors.newSingleThreadExecutor { job ->
            Thread(job, "vega-p7-dns").apply { isDaemon = true }
        }

        fun readLine(input: InputStream, maximum: Int): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size <= maximum) {
                val value = input.read()
                if (value < 0) throw IOException("DOWNLOAD_HTTP_TRUNCATED")
                if (value == '\r'.code) {
                    if (input.read() != '\n'.code) throw IOException("DOWNLOAD_HTTP_LINE_INVALID")
                    return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
                }
                if (value == '\n'.code || value < 0x20 && value != '\t'.code) {
                    throw IOException("DOWNLOAD_HTTP_LINE_INVALID")
                }
                bytes.add(value.toByte())
            }
            throw IOException("DOWNLOAD_HTTP_LINE_TOO_LONG")
        }
    }
}
