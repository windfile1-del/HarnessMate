package dev.wind.agent.intelligence.lsp

import dev.wind.agent.CancellationToken
import dev.wind.agent.intelligence.CodeIntelLimits
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

object LspProtocol {
    fun writeMessage(output: OutputStream, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        if (payload.size > CodeIntelLimits.MAX_LSP_FRAME_BYTES) {
            throw IllegalArgumentException("LSP_FRAME_TOO_LARGE: Frame size ${payload.size} exceeds ${CodeIntelLimits.MAX_LSP_FRAME_BYTES}")
        }
        val header = "Content-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(payload)
        output.flush()
    }

    fun readMessage(input: InputStream): JSONObject? {
        return readMessage(input, 30_000L, null)
    }

    /** Deadline/cancellation-aware reader for transports that expose streams. */
    fun readMessage(input: InputStream, timeoutMs: Long, token: CancellationToken?): JSONObject? {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        var contentLength = -1
        val headerLine = StringBuilder()

        while (true) {
            val c = readByte(input, deadline, token) ?: return null
            if (c == '\n'.code) {
                val line = headerLine.toString().trim()
                if (line.isEmpty()) {
                    break
                }
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    val lenStr = line.substring("Content-Length:".length).trim()
                    contentLength = lenStr.toIntOrNull() ?: throw IllegalArgumentException("MALFORMED_CONTENT_LENGTH")
                }
                headerLine.clear()
            } else if (c != '\r'.code) {
                headerLine.append(c.toChar())
            }
        }

        if (contentLength < 0) throw IllegalStateException("MISSING_CONTENT_LENGTH")
        if (contentLength > CodeIntelLimits.MAX_LSP_FRAME_BYTES) {
            throw IllegalArgumentException("LSP_FRAME_TOO_LARGE: Content-Length $contentLength exceeds ${CodeIntelLimits.MAX_LSP_FRAME_BYTES}")
        }

        val buffer = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val count = readInto(input, buffer, totalRead, contentLength - totalRead, deadline, token)
            if (count <= 0) throw IllegalStateException("UNEXPECTED_EOF_IN_LSP_FRAME")
            totalRead += count
        }

        return JSONObject(String(buffer, Charsets.UTF_8))
    }

    private fun readByte(input: InputStream, deadline: Long, token: CancellationToken?): Int? {
        while (System.nanoTime() < deadline) {
            if (token?.isCancelled == true) return null
            if (input.available() > 0) {
                val value = input.read()
                return if (value < 0) null else value
            }
            Thread.sleep(1L)
        }
        return null
    }

    private fun readInto(
        input: InputStream,
        target: ByteArray,
        offset: Int,
        length: Int,
        deadline: Long,
        token: CancellationToken?
    ): Int {
        var total = 0
        while (total < length && System.nanoTime() < deadline) {
            if (token?.isCancelled == true) return 0
            if (input.available() <= 0) {
                Thread.sleep(1L)
                continue
            }
            val count = input.read(target, offset + total, length - total)
            if (count < 0) return total
            total += count
        }
        return total
    }
}

