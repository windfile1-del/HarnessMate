package dev.wind.agent.shell.mirror

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded reverse stream captured by the trusted Kotlin bridge. */
data class MirrorSink(
    val id: String,
    val maxBytes: Long,
    val expectedSize: Long = -1L,
    val expectedSha256: String? = null,
    /** Caller-owned disk/output stream; null retains the bounded control-plane capture. */
    val destination: OutputStream? = null
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "MIRROR_SINK_ID_INVALID" }
        require(maxBytes >= 0 && (destination != null || maxBytes <= 16L * 1024 * 1024)) { "MIRROR_SINK_LIMIT" }
        require(expectedSize in -1L..maxBytes) { "MIRROR_SINK_EXPECTED_SIZE" }
        require(expectedSha256 == null || expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
            "MIRROR_SINK_EXPECTED_HASH"
        }
    }
}

private class SinkCapture(private val spec: MirrorSink) {
    private val bytes = if (spec.destination == null) ByteArrayOutputStream() else null
    private val output = spec.destination ?: bytes!!
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L

    fun readFrom(input: InputStream, length: Long) {
        require(length <= spec.maxBytes) { "MIRROR_SINK_LIMIT" }
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0L) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, want)
            require(read > 0) { "MIRROR_SINK_TRUNCATED" }
            count += read
            require(count <= spec.maxBytes) { "MIRROR_SINK_LIMIT" }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            remaining -= read
        }
        require(spec.expectedSize < 0L || count == spec.expectedSize) { "MIRROR_SINK_SIZE_MISMATCH" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(spec.expectedSha256 == null || actual == spec.expectedSha256) { "MIRROR_SINK_HASH_MISMATCH" }
    }

    fun bytes(): ByteArray = bytes?.toByteArray() ?: error("MIRROR_SINK_IS_STREAMED")
}

/**
 * One trusted helper, one short-lived capability. Forward sources are GET
 * streams; reverse changed-file streams are POST sinks. Neither direction is
 * spooled to the Termux or Vega filesystem.
 */
class MirrorSyncBridgeServer(
    private val helper: ByteArray,
    private val control: ByteArray,
    private val source: (Int) -> InputStream,
    private val cancelled: () -> Boolean,
    sinks: List<MirrorSink> = emptyList()
) : Closeable {
    companion object {
        const val MAX_CONTROL_BYTES = 4 * 1024 * 1024
        const val MAX_SINK_BYTES = 16 * 1024 * 1024
        private val RANDOM = SecureRandom()
    }

    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val secret = ByteArray(32).also(RANDOM::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val sinkSpecs = sinks.associateBy { it.id }
    private val sinkCaptures = ConcurrentHashMap<String, SinkCapture>()

    val url = "http://127.0.0.1:${server.localPort}/$secret"

    @Volatile var result: ByteArray? = null
        private set
    @Volatile var failure: Exception? = null
        private set
    @Volatile private var active: Socket? = null

    init {
        require(control.size <= MAX_CONTROL_BYTES) { "MIRROR_CONTROL_LIMIT" }
        require(sinkSpecs.size == sinks.size) { "MIRROR_DUPLICATE_SINK" }
        server.soTimeout = 1000
        Thread({
            while (!closed.get()) {
                try {
                    server.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 30_000
                        handle(socket)
                    }
                } catch (_: SocketTimeoutException) {
                } catch (e: Exception) {
                    if (!closed.get()) failure = e
                } finally {
                    active = null
                }
            }
        }, "vega-mirror-bridge").apply { isDaemon = true; start() }
    }

    fun sinkBytes(id: String): ByteArray = sinkCaptures[id]?.bytes()
        ?: throw IllegalStateException("MIRROR_SINK_MISSING: $id")

    fun requireSink(id: String) {
        check(sinkCaptures.containsKey(id)) { "MIRROR_SINK_MISSING: $id" }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val lines = readHeaders(input)
        val request = lines.first().split(' ')
        require(request.size == 3 && request[2] == "HTTP/1.1")
        val route = request[1]
        if (!route.startsWith("/$secret/")) {
            writeResponse(output, 403, ByteArray(0))
        } else if (request[0] == "GET") {
            val stream = when (val suffix = route.removePrefix("/$secret/")) {
                "helper" -> ByteArrayInputStreamCompat(helper)
                "control" -> ByteArrayInputStreamCompat(control)
                "cancel" -> ByteArrayInputStreamCompat(if (cancelled()) "1".toByteArray() else "0".toByteArray())
                else -> {
                    require(suffix.matches(Regex("file/[0-9]+"))) { "Unknown endpoint" }
                    source(suffix.substringAfter('/').toInt())
                }
            }
            stream.use {
                output.write("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                it.copyTo(output, 64 * 1024)
            }
        } else {
            require(request[0] == "POST") { "Unsupported method" }
            require(lines.none { it.startsWith("Transfer-Encoding:", true) })
            val length = contentLength(lines)
            val suffix = route.removePrefix("/$secret/")
            when {
                suffix == "result" -> {
                    require(result == null && length in 0..MAX_CONTROL_BYTES) { "MIRROR_RESULT_LIMIT" }
                    val bytes = ByteArray(length.toInt())
                    DataInputStream(input).readFully(bytes)
                    result = bytes
                }
                suffix.startsWith("sink/") -> {
                    val id = suffix.removePrefix("sink/")
                    val spec = sinkSpecs[id] ?: throw IllegalArgumentException("MIRROR_SINK_UNKNOWN")
                    require(!sinkCaptures.containsKey(id)) { "MIRROR_SINK_REPLAY" }
                    require(length >= 0 && length <= spec.maxBytes) { "MIRROR_SINK_LIMIT" }
                    val capture = SinkCapture(spec)
                    capture.readFrom(input, length)
                    sinkCaptures[id] = capture
                }
                else -> throw IllegalArgumentException("Unknown POST endpoint")
            }
            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
        }
        output.flush()
        socket.shutdownOutput()
    }

    private fun readHeaders(input: InputStream): List<String> {
        val head = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            require(b >= 0 && head.size() < 8192) { "Invalid HTTP header" }
            head.write(b)
            val data = head.toByteArray()
            if (data.size >= 4 && data[data.size - 4] == 13.toByte() &&
                data[data.size - 3] == 10.toByte() && data[data.size - 2] == 13.toByte() &&
                data[data.size - 1] == 10.toByte()) break
        }
        return head.toString("US-ASCII").split("\r\n").filter { it.isNotEmpty() }
    }

    private fun contentLength(lines: List<String>): Long {
        val lengths = lines.filter { it.startsWith("Content-Length:", true) }
        require(lengths.size == 1)
        return lengths.single().substringAfter(':').trim().toLong().also { require(it >= 0L) }
    }

    private fun writeResponse(output: OutputStream, code: Int, body: ByteArray) {
        output.write("HTTP/1.1 $code Forbidden\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
    }

    override fun close() {
        closed.set(true)
        server.close()
        active?.close()
    }

    /** Avoid exposing java.io.ByteArrayInputStream in the security-sensitive route switch. */
    private class ByteArrayInputStreamCompat(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes)
}
