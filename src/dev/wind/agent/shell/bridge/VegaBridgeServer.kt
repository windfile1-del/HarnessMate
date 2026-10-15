package dev.wind.agent.shell.bridge

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
class VegaBridgeServer(
    val executionId: String,
    private val sourceStagingDir: File,
    private val returnedDestDir: File
) : AutoCloseable {

    val token: String = generateSecureToken()
    private val serverSocket: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort

    private val running = AtomicBoolean(true)
    private val pulled = AtomicBoolean(false)
    private val pushed = AtomicBoolean(false)
    @Volatile var pushError: Throwable? = null

    init {
        serverSocket.soTimeout = 0
        startListenerThread()
    }

    private fun startListenerThread() {
        val thread = Thread({
            while (running.get()) {
                try {
                    val client = serverSocket.accept()
                    client.soTimeout = BridgeLimits.TRANSFER_TIMEOUT_MS
                    Thread({
                        handleConnection(client)
                    }, "vega-bridge-worker-$executionId").start()
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "vega-bridge-$executionId")
        thread.isDaemon = true
        thread.start()
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                val rawIn = s.getInputStream()
                // Read HTTP header bytes strictly up to CRLF-CRLF without buffering any body bytes
                val headerBytes = ByteArrayOutputStream()
                while (true) {
                    val b = rawIn.read()
                    if (b < 0) return
                    headerBytes.write(b)
                    val buf = headerBytes.toByteArray()
                    val len = buf.size
                    if (len >= 4 && buf[len - 4] == 13.toByte() && buf[len - 3] == 10.toByte() &&
                        buf[len - 2] == 13.toByte() && buf[len - 1] == 10.toByte()) {
                        break
                    } else if (len >= 2 && buf[len - 2] == 10.toByte() && buf[len - 1] == 10.toByte()) {
                        break
                    }
                }

                val headerText = String(headerBytes.toByteArray(), Charsets.US_ASCII)
                val lines = headerText.split("\r?\n".toRegex())
                if (lines.isEmpty()) return
                val reqParts = lines[0].split(" ")
                if (reqParts.size < 2) return
                val method = reqParts[0].uppercase()
                val path = reqParts[1]

                val headers = mutableMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val colon = lines[i].indexOf(':')
                    if (colon > 0) {
                        headers[lines[i].substring(0, colon).trim().lowercase()] = lines[i].substring(colon + 1).trim()
                    }
                }

                val expectedPath = "/v1/project/$token"
                if (path != expectedPath) {
                    sendHttpError(s.getOutputStream(), 403, "Forbidden: Invalid Bridge Token")
                    return
                }

                if (method == "GET") {
                    if (!pulled.compareAndSet(false, true)) {
                        sendHttpError(s.getOutputStream(), 410, "Gone: Project has already been pulled")
                        return
                    }
                    val out = s.getOutputStream()
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/x-tar\r\nConnection: close\r\n\r\n"
                    out.write(header.toByteArray(Charsets.US_ASCII))
                    TarArchiveWriter.writeDirectory(sourceStagingDir, out)
                    out.flush()
                    try { s.shutdownOutput() } catch (_: Exception) {}
                } else if (method == "PUT") {
                    if (!pushed.compareAndSet(false, true)) {
                        sendHttpError(s.getOutputStream(), 409, "Conflict: Project has already been returned")
                        return
                    }
                    if (headers["expect"]?.contains("100-continue") == true) {
                        val contResp = "HTTP/1.1 100 Continue\r\n\r\n"
                        s.getOutputStream().write(contResp.toByteArray(Charsets.US_ASCII))
                        s.getOutputStream().flush()
                    }
                    if (!returnedDestDir.isDirectory) returnedDestDir.mkdirs()

                    val bodyIn: InputStream = if (headers["transfer-encoding"]?.contains("chunked") == true) {
                        ChunkedInputStream(rawIn)
                    } else if (headers.containsKey("content-length")) {
                        val cl = headers["content-length"]!!.toLong()
                        BoundedInputStream(rawIn, cl)
                    } else {
                        rawIn
                    }

                    SafeTarExtractor.extract(bodyIn, returnedDestDir)

                    // Drain any remaining bytes so Linux/Android TCP stack does not send RST on close (curl 56)
                    try {
                        val drain = ByteArray(4096)
                        while (bodyIn.read(drain) != -1) {}
                    } catch (_: Exception) {}

                    val okResp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nOK"
                    s.getOutputStream().write(okResp.toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                    try { s.shutdownOutput() } catch (_: Exception) {}
                } else {
                    sendHttpError(s.getOutputStream(), 405, "Method Not Allowed")
                }
            }
        } catch (t: Throwable) {
            pushError = t
        }
    }

    private fun sendHttpError(out: OutputStream, code: Int, msg: String) {
        val resp = "HTTP/1.1 $code $msg\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n$msg"
        try { out.write(resp.toByteArray(Charsets.US_ASCII)); out.flush() } catch (_: Exception) {}
    }

    override fun close() {
        running.set(false)
        try { serverSocket.close() } catch (_: Exception) {}
    }

    companion object {
        private val RANDOM = SecureRandom()

        fun generateSecureToken(): String {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

class BoundedInputStream(private val raw: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = raw.read()
        if (b >= 0) remaining--
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = Math.min(len.toLong(), remaining).toInt()
        val n = raw.read(b, off, toRead)
        if (n > 0) remaining -= n
        return n
    }
}

class ChunkedInputStream(private val raw: InputStream) : InputStream() {
    private var currentChunkRemaining = 0L
    private var eof = false

    override fun read(): Int {
        val buf = ByteArray(1)
        val n = read(buf, 0, 1)
        return if (n <= 0) -1 else buf[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (eof) return -1
        if (currentChunkRemaining <= 0) {
            nextChunk()
            if (eof) return -1
        }
        val toRead = Math.min(len.toLong(), currentChunkRemaining).toInt()
        val n = raw.read(b, off, toRead)
        if (n <= 0) {
            eof = true
            return -1
        }
        currentChunkRemaining -= n
        return n
    }

    private fun nextChunk() {
        var line = readLine()
        if (line.isEmpty()) {
            line = readLine()
        }
        val hex = line.split(";")[0].trim()
        val size = try { hex.toLong(16) } catch (_: Exception) { 0L }
        if (size <= 0) {
            eof = true
            while (readLine().isNotEmpty()) {}
        } else {
            currentChunkRemaining = size
        }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = raw.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
