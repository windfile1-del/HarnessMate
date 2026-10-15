package dev.wind.agent.artifact

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ApkMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String
)

object ApkManifestParser {
    fun parse(apkFile: File?, channel: SeekableByteChannel, context: Context? = null): ApkMetadata {
        if (apkFile != null && apkFile.isFile && context != null) {
            try {
                val pm = context.packageManager
                val info = pm?.getPackageArchiveInfo(apkFile.absolutePath, 0)
                if (info != null && !info.packageName.isNullOrBlank()) {
                    val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        info.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        info.versionCode.toLong()
                    }
                    val name = info.versionName ?: "1.0"
                    val label = try {
                        info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName
                    } catch (_: Exception) {
                        info.packageName
                    }
                    return ApkMetadata(info.packageName, code, name, label)
                }
            } catch (_: Exception) { }
        }

        // Fallback: parse AndroidManifest.xml directly from seekable channel
        val manifestBytes = readManifestFromZip(channel)
            ?: throw ArtifactStoreException("APK_MANIFEST_MISSING")
        return parseAxml(manifestBytes)
    }

    private fun readManifestFromZip(channel: SeekableByteChannel): ByteArray? {
        channel.position(0L)
        val plan = ZipDirectoryInspector().plan(channel)
        val entry = plan.entries.firstOrNull { it.name == "AndroidManifest.xml" && !it.directory }
            ?: return null
        val output = java.io.ByteArrayOutputStream()
        DownloadWorkspaceApplier.extractZipEntry(channel, entry, output) { false }
        return output.toByteArray()
    }

    internal fun parseAxml(bytes: ByteArray): ApkMetadata {
        if (bytes.size < 32) throw ArtifactStoreException("APK_MANIFEST_INVALID")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.short.toInt() and 0xFFFF
        if (type != 0x0003) throw ArtifactStoreException("APK_MANIFEST_INVALID")
        buf.short // header size
        buf.int // file size

        // Find StringPool chunk (type 0x0001)
        var stringPoolOffset = -1
        while (buf.hasRemaining() && buf.remaining() >= 8) {
            val chunkPos = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            buf.short // header size
            val chunkSize = buf.int
            if (chunkSize <= 0 || chunkSize > buf.remaining() + 8) break
            if (chunkType == 0x0001) {
                stringPoolOffset = chunkPos
                break
            }
            buf.position(minOf(chunkPos + chunkSize, bytes.size))
        }
        if (stringPoolOffset < 0) throw ArtifactStoreException("APK_MANIFEST_STRING_POOL_MISSING")

        buf.position(stringPoolOffset + 8)
        val stringCount = buf.int
        buf.int // styleCount
        val flags = buf.int
        val isUtf8 = (flags and (1 shl 8)) != 0
        val stringsStart = stringPoolOffset + buf.int
        buf.int // stylesStart

        val stringOffsets = IntArray(minOf(stringCount, 4096)) { buf.int }
        val strings = ArrayList<String>(stringOffsets.size)
        for (offset in stringOffsets) {
            val pos = stringsStart + offset
            if (pos >= bytes.size) {
                strings.add("")
                continue
            }
            buf.position(pos)
            if (isUtf8) {
                // UTF-8 length prefix (1 or 2 bytes for char count, 1 or 2 for byte count)
                var charLen = buf.get().toInt() and 0xFF
                if (charLen and 0x80 != 0) charLen = ((charLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                var byteLen = buf.get().toInt() and 0xFF
                if (byteLen and 0x80 != 0) byteLen = ((byteLen and 0x7F) shl 8) or (buf.get().toInt() and 0xFF)
                val strBytes = ByteArray(minOf(byteLen, buf.remaining()))
                buf.get(strBytes)
                strings.add(String(strBytes, StandardCharsets.UTF_8))
            } else {
                var charLen = buf.short.toInt() and 0xFFFF
                if (charLen and 0x8000 != 0) {
                    charLen = ((charLen and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
                }
                val charBytes = ByteArray(minOf(charLen * 2, buf.remaining()))
                buf.get(charBytes)
                strings.add(String(charBytes, StandardCharsets.UTF_16LE))
            }
        }

        val packageCandidates = strings.filter {
            it.matches(Regex("""^[a-zA-Z][a-zA-Z0-9_]*(?:.[a-zA-Z][a-zA-Z0-9_]*)+$""")) &&
                !it.startsWith("android.") && !it.startsWith("java.") &&
                !it.contains("schemas.android.com")
        }
        val packageName = packageCandidates.firstOrNull()
            ?: strings.firstOrNull { it.contains('.') && !it.contains('/') && !it.contains(':') && it.length in 3..120 }
            ?: throw ArtifactStoreException("APK_PACKAGE_NAME_NOT_FOUND")

        val versionName = strings.firstOrNull { it.matches(Regex("""^[0-9]+(?:.[0-9]+).*""")) } ?: "1.0"
        val versionCode = strings.firstOrNull { it.matches(Regex("^[0-9]{1,9}$")) }?.toLongOrNull() ?: 1L
        val label = packageName.substringAfterLast('.')

        return ApkMetadata(packageName, versionCode, versionName, label)
    }
}
