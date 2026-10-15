package dev.wind.agent.artifact

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Metadata-only ZIP preflight. It never extracts or trusts entry payloads. */
data class ZipDirectorySummary(
    val entryCount: Int,
    val declaredUncompressedBytes: Long,
    val maxDepth: Int,
    val apkCandidate: Boolean,
    val jarCandidate: Boolean
)

internal data class ZipPayloadEntry(
    val name: String,
    val directory: Boolean,
    val method: Int,
    val crc32: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val dataOffset: Long
)

internal data class ZipPayloadPlan(val summary: ZipDirectorySummary, val entries: List<ZipPayloadEntry>)

internal class ZipDirectoryInspector {
    fun inspect(channel: SeekableByteChannel): ZipDirectorySummary = plan(channel).summary

    fun plan(channel: SeekableByteChannel): ZipPayloadPlan {
        val fileSize = channel.size()
        if (fileSize < EOCD_BYTES) fail("TRUNCATED")
        val tailSize = minOf(fileSize, EOCD_BYTES + 65_535L).toInt()
        val tailStart = fileSize - tailSize
        val tail = readAt(channel, tailStart, tailSize, fileSize)
        val endIndex = (tailSize - EOCD_BYTES downTo 0).firstOrNull { index ->
            u32(tail, index) == EOCD_SIGNATURE &&
                tailStart + index + EOCD_BYTES + u16(tail, index + 20) == fileSize
        } ?: fail("END_RECORD_INVALID")
        val endOffset = tailStart + endIndex
        if (u16(tail, endIndex + 4) != 0 || u16(tail, endIndex + 6) != 0 ||
            u16(tail, endIndex + 8) != u16(tail, endIndex + 10)
        ) fail("MULTIDISK_UNSUPPORTED")
        val count = u16(tail, endIndex + 10)
        val centralSize = u32(tail, endIndex + 12)
        val centralOffset = u32(tail, endIndex + 16)
        if (count == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
            fail("ZIP64_UNSUPPORTED")
        }
        if (count > MAX_ENTRIES || centralSize > MAX_CENTRAL_BYTES ||
            centralOffset + centralSize != endOffset ||
            (count == 0 && (centralSize != 0L || centralOffset != 0L))
        ) fail("DIRECTORY_BOUNDS_INVALID")

        val names = HashSet<String>(count * 2)
        val filePaths = HashSet<String>(count * 2)
        val directoryPaths = HashSet<String>(count * 2)
        val extents = ArrayList<Pair<Long, Long>>(count)
        val entries = ArrayList<ZipPayloadEntry>(count)
        var cursor = centralOffset
        var uncompressedTotal = 0L
        var maxDepth = 0
        var androidManifest = false
        var resourcesArsc = false
        var jarManifest = false
        var classEntry = false
        repeat(count) {
            val header = readAt(channel, cursor, CENTRAL_BYTES, fileSize)
            if (u32(header, 0) != CENTRAL_SIGNATURE) fail("DIRECTORY_ENTRY_INVALID")
            val flags = u16(header, 8)
            val method = u16(header, 10)
            if (flags and ALLOWED_FLAGS.inv() != 0 || method !in listOf(0, 8)) fail("ENTRY_UNSUPPORTED")
            val compressed = u32(header, 20)
            val crc32 = u32(header, 16)
            val uncompressed = u32(header, 24)
            val nameLength = u16(header, 28)
            val extraLength = u16(header, 30)
            val commentLength = u16(header, 32)
            val disk = u16(header, 34)
            val external = u32(header, 38)
            val localOffset = u32(header, 42)
            if (disk != 0 || compressed == 0xffffffffL || uncompressed == 0xffffffffL ||
                localOffset == 0xffffffffL
            ) fail("ENTRY_UNSUPPORTED")
            if (nameLength !in 1..MAX_NAME_BYTES) fail("NAME_LIMIT")
            val next = cursor + CENTRAL_BYTES + nameLength + extraLength + commentLength
            if (next > endOffset) fail("DIRECTORY_BOUNDS_INVALID")
            val rawName = readAt(channel, cursor + CENTRAL_BYTES, nameLength, fileSize)
            val name = decodeName(rawName)
            val depth = safeDepth(name)
            val key = Normalizer.normalize(name, Normalizer.Form.NFC)
            val typeBits = (external ushr 16).toInt() and 0xf000
            val directory = name.endsWith('/') || typeBits == 0x4000 || (external and 0x10L != 0L)
            if (directory) {
                directoryPaths.add(key.removeSuffix("/"))
            } else {
                if (!names.add(key)) fail("DUPLICATE_PATH")
                filePaths.add(key)
            }
            if (typeBits != 0 && typeBits != 0x8000 && typeBits != 0x4000) fail("SPECIAL_ENTRY")
            if (typeBits == 0x4000 && !directory || external and 0x10L != 0L && !directory) fail("SPECIAL_ENTRY")
            if (directory && (compressed != 0L || uncompressed != 0L)) fail("ENTRY_UNSUPPORTED")
            if (method == 0 && compressed != uncompressed) fail("ENTRY_SIZE_INVALID")
            if (uncompressed > 0L &&
                (compressed == 0L || uncompressed > compressed * MAX_EXPANSION_RATIO)
            ) fail("EXPANSION_LIMIT")
            uncompressedTotal += uncompressed
            if (uncompressedTotal > MAX_UNCOMPRESSED_BYTES) fail("UNCOMPRESSED_LIMIT")

            val local = readAt(channel, localOffset, LOCAL_BYTES, fileSize)
            if (u32(local, 0) != LOCAL_SIGNATURE ||
                u16(local, 8) != method || u16(local, 26) != nameLength
            ) fail("LOCAL_HEADER_INVALID")
            val localExtra = u16(local, 28)
            val dataStart = localOffset + LOCAL_BYTES + nameLength + localExtra
            val dataEnd = dataStart + compressed
            if (dataEnd > fileSize || !readAt(channel, localOffset + LOCAL_BYTES, nameLength, fileSize)
                    .contentEquals(rawName)
            ) fail("LOCAL_HEADER_INVALID")
            if (!directory && compressed > 0L) {
                extents += localOffset to dataEnd
            }
            entries += ZipPayloadEntry(name, directory, method, crc32, compressed, uncompressed, dataStart)
            maxDepth = maxOf(maxDepth, depth)
            androidManifest = androidManifest || name == "AndroidManifest.xml"
            resourcesArsc = resourcesArsc || name == "resources.arsc"
            jarManifest = jarManifest || name == "META-INF/MANIFEST.MF"
            classEntry = classEntry || name.endsWith(".class")
            cursor = next
        }
        if (cursor != endOffset) fail("DIRECTORY_BOUNDS_INVALID")
        if (filePaths.any { it in directoryPaths }) fail("PATH_CONFLICT")
        for (name in filePaths) {
            val path = name.removeSuffix("/")
            for (index in path.indices) {
                if (path[index] == '/' && path.substring(0, index) in filePaths) fail("PATH_CONFLICT")
            }
        }
        val sorted = extents.sortedBy { it.first }
        if (sorted.isNotEmpty() && sorted.first().first < 0L) fail("PREFIX_UNSUPPORTED")
        for (index in 0 until sorted.size - 1) {
            if (sorted[index].second > sorted[index + 1].first) fail("ENTRY_OVERLAP")
        }
        return ZipPayloadPlan(
            ZipDirectorySummary(count, uncompressedTotal, maxDepth,
                androidManifest && resourcesArsc, jarManifest && classEntry), entries
        )
    }

    private fun decodeName(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        fail("NAME_ENCODING_INVALID")
    }

    private fun safeDepth(name: String): Int {
        if (name.startsWith('/') || name.contains('\\') || name.contains(':') ||
            name.any { it < ' ' || it == '\u007f' }
        ) fail("PATH_UNSAFE")
        val path = if (name.endsWith('/')) name.dropLast(1) else name
        val segments = path.split('/')
        if (segments.isEmpty() || segments.size > MAX_DEPTH || segments.any {
                it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.startsWith(' ') ||
                    it.endsWith(' ') || it.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_DEVICE_NAMES
            }
        ) fail("PATH_UNSAFE")
        return segments.size
    }

    private fun readAt(channel: SeekableByteChannel, offset: Long, count: Int, size: Long): ByteArray {
        if (offset < 0L || count < 0 || offset + count > size) fail("TRUNCATED")
        val buffer = ByteBuffer.allocate(count)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) fail("TRUNCATED")
        }
        return buffer.array()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16))

    private fun fail(reason: String): Nothing = throw ArtifactStoreException("ARTIFACT_ZIP_$reason")

    private companion object {
        const val EOCD_BYTES = 22
        const val CENTRAL_BYTES = 46
        const val LOCAL_BYTES = 30
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val LOCAL_SIGNATURE = 0x04034b50L
        const val ALLOWED_FLAGS = 0x080e
        const val MAX_ENTRIES = 100_000
        const val MAX_NAME_BYTES = 4096
        const val MAX_DEPTH = 64
        const val MAX_CENTRAL_BYTES = 64L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L
        const val MAX_EXPANSION_RATIO = 1000L
        val WINDOWS_DEVICE_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") }
    }
}
