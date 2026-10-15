package dev.wind.agent.shell.bridge

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object SafeTarExtractor {

    fun extract(input: InputStream, destDir: File) {
        val canonicalDest = destDir.canonicalFile
        var totalBytes = 0L
        var fileCount = 0

        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) break
            if (isAllZero(header)) {
                // Check for second zero block denoting end of tar and drain stream
                try {
                    val drain = ByteArray(4096)
                    while (input.read(drain) != -1) {}
                } catch (_: Exception) {}
                break
            }

            fileCount++
            if (fileCount > BridgeLimits.MAX_FILES) {
                throw SecurityException("Archive exceeds maximum file count limit of ${BridgeLimits.MAX_FILES}")
            }

            val name = readString(header, 0, 100).trim()
            if (name.isEmpty()) continue

            // Strict security checks against archive path traversal
            if (name.startsWith("/") || name.startsWith("\\")) {
                throw SecurityException("BLOCKED_ARCHIVE_ESCAPE: Absolute path in archive entry: $name")
            }
            if (name.contains("..") || name.contains("%2e") || name.contains("%2f")) {
                throw SecurityException("BLOCKED_ARCHIVE_ESCAPE: Traversal in archive entry: $name")
            }

            val typeFlag = header[156].toInt().toChar()
            // Only allow regular files ('0' or null) and directories ('5')
            if (typeFlag != '0' && typeFlag != '\u0000' && typeFlag != '5') {
                throw SecurityException("BLOCKED_ARCHIVE_TYPE: Disallowed archive entry type '$typeFlag' for $name. Symlinks, hardlinks and special devices are forbidden.")
            }

            val size = parseOctal(header, 124, 12)
            if (size > BridgeLimits.MAX_SINGLE_FILE_BYTES) {
                throw SecurityException("Archive entry '$name' size ($size bytes) exceeds single file limit of ${BridgeLimits.MAX_SINGLE_FILE_BYTES} bytes")
            }

            val target = File(destDir, name).canonicalFile
            if (target.path != canonicalDest.path && !target.path.startsWith(canonicalDest.path + File.separator)) {
                throw SecurityException("BLOCKED_ARCHIVE_ESCAPE: Entry '$name' resolves outside target directory: ${target.path}")
            }

            val isDirectory = typeFlag == '5' || name.endsWith("/")
            if (isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) {
                    throw IllegalStateException("Failed to create directory from archive: ${target.path}")
                }
            } else {
                target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                FileOutputStream(target).use { fos ->
                    var remaining = size
                    val buf = ByteArray(4096)
                    while (remaining > 0) {
                        val toRead = Math.min(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n < 0) throw IllegalStateException("Unexpected EOF while reading entry: $name")
                        fos.write(buf, 0, n)
                        remaining -= n
                        totalBytes += n
                        if (totalBytes > BridgeLimits.MAX_PROJECT_BYTES) {
                            throw SecurityException("Archive exceeds total project limit of ${BridgeLimits.MAX_PROJECT_BYTES} bytes")
                        }
                    }
                }
                // Skip padding to 512-byte boundary
                val pad = (512 - (size % 512)).toInt() % 512
                if (pad > 0) {
                    val padBuf = ByteArray(pad)
                    readFully(input, padBuf)
                }
            }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun isAllZero(buf: ByteArray): Boolean {
        for (b in buf) if (b != 0.toByte()) return false
        return true
    }

    private fun readString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.US_ASCII)
    }

    private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Long {
        var res = 0L
        for (i in offset until offset + length) {
            val b = buf[i]
            if (b in '0'.code.toByte()..'7'.code.toByte()) {
                res = (res shl 3) + (b - '0'.code.toByte())
            } else if (b == 0.toByte() || b == ' '.code.toByte()) {
                if (res > 0) break
            }
        }
        return res
    }
}
