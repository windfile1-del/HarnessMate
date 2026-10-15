package dev.wind.agent.shell.bridge

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

object TarArchiveWriter {

    fun writeDirectory(sourceDir: File, out: OutputStream, maxBytes: Long = BridgeLimits.MAX_PROJECT_BYTES): Long {
        var totalBytes = 0L
        val prefixLen = sourceDir.absolutePath.length + 1

        fun writeEntry(file: File) {
            val rel = file.absolutePath.substring(prefixLen).replace('\\', '/')
            val isDir = file.isDirectory
            val entryPath = if (isDir && !rel.endsWith('/')) "$rel/" else rel

            val header = ByteArray(512)
            writeOctalString(header, 0, 100, entryPath)
            writeOctalNumber(header, 100, 8, if (isDir) 0x1ED else 0x1A4) // 0755 / 0644
            writeOctalNumber(header, 108, 8, 0) // uid
            writeOctalNumber(header, 116, 8, 0) // gid
            val len = if (isDir) 0L else file.length()
            writeOctalNumber(header, 124, 12, len)
            writeOctalNumber(header, 136, 12, file.lastModified() / 1000L)
            header[156] = if (isDir) '5'.code.toByte() else '0'.code.toByte()
            System.arraycopy("ustar\u000000".toByteArray(Charsets.US_ASCII), 0, header, 257, 8)

            // Checksum over header with checksum field treated as 8 spaces
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            var chk = 0
            for (b in header) chk += (b.toInt() and 0xFF)
            writeOctalNumber(header, 148, 7, chk.toLong())
            header[155] = ' '.code.toByte()

            out.write(header)
            totalBytes += 512

            if (!isDir && len > 0) {
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(4096)
                    var n: Int
                    var readSum = 0L
                    while (fis.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        totalBytes += n
                        readSum += n
                        if (totalBytes > maxBytes) {
                            throw IllegalStateException("Archive exceeds maximum size limit of $maxBytes bytes")
                        }
                    }
                    val pad = (512 - (readSum % 512)).toInt() % 512
                    if (pad > 0) {
                        out.write(ByteArray(pad))
                        totalBytes += pad
                    }
                }
            }
        }

        fun walk(dir: File) {
            val list = dir.listFiles() ?: return
            for (f in list.sortedBy { it.name }) {
                writeEntry(f)
                if (f.isDirectory) {
                    walk(f)
                }
            }
        }

        walk(sourceDir)
        // Two 512-byte blocks of zeros denote EOF in standard tar
        out.write(ByteArray(1024))
        totalBytes += 1024
        out.flush()
        return totalBytes
    }

    private fun writeOctalString(buf: ByteArray, offset: Int, length: Int, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        val copyLen = Math.min(bytes.size, length - 1)
        System.arraycopy(bytes, 0, buf, offset, copyLen)
    }

    private fun writeOctalNumber(buf: ByteArray, offset: Int, length: Int, value: Long) {
        val s = java.lang.Long.toOctalString(value)
        val pad = length - 1 - s.length
        for (i in 0 until pad) buf[offset + i] = '0'.code.toByte()
        val sBytes = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(sBytes, 0, buf, offset + Math.max(0, pad), sBytes.size)
        buf[offset + length - 1] = 0
    }
}
