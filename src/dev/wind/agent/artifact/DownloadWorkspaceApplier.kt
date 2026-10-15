package dev.wind.agent.artifact

import dev.wind.agent.CancellationToken
import dev.wind.agent.history.MutationJournal
import dev.wind.agent.history.MutationScope
import dev.wind.agent.workspace.WorkspaceFs
import dev.wind.agent.workspace.WorkspacePath
import org.json.JSONObject
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.zip.CRC32
import java.util.zip.Inflater

class DownloadWorkspaceApplier(
    private val store: VerifiedArtifactStore,
    private val journal: MutationJournal
) {
    fun apply(
        plan: DownloadApplyPlan,
        fs: WorkspaceFs,
        scope: MutationScope,
        token: CancellationToken,
        verifyAtBoundary: () -> Unit
    ): JSONObject {
        token.throwIfCancelled()
        verifyAtBoundary()
        require(fs.isConfigured()) { "WORKSPACE_NOT_CONFIGURED" }

        if (plan.mode == "file") {
            var bytesWritten = 0L
            journal.transactionalBinaryStream(
                scope = scope,
                fs = fs,
                taskId = plan.taskId,
                path = plan.relativePath,
                expectedSha256 = plan.artifactSha256,
                expectedSize = plan.totalBytes,
                overwrite = plan.overwrite
            ) { out ->
                token.throwIfCancelled()
                verifyAtBoundary()
                store.openVerified(plan.artifactUri).use { input ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        token.throwIfCancelled()
                        out.write(buf, 0, n)
                        bytesWritten += n
                    }
                    out.flush()
                }
            }
            return JSONObject()
                .put("status", "APPLIED")
                .put("plan_id", plan.id)
                .put("mode", "file")
                .put("destination", plan.relativePath)
                .put("bytes_written", bytesWritten)
                .put("files_written", 1)
        } else if (plan.mode == "zip") {
            var filesCount = 0
            var totalBytesWritten = 0L
            store.withVerifiedChannel(plan.artifactUri) { channel, _ ->
                val zipPlan = ZipDirectoryInspector().plan(channel)
                val prefixToStrip = if (plan.stripSingleRoot) findSingleRootPrefix(zipPlan.entries) else ""
                val baseTarget = WorkspacePath.sanitize(plan.relativePath)
                if (baseTarget.isNotEmpty() && !fs.exists(baseTarget)) {
                    fs.mkdir(baseTarget)
                }
                for (entry in zipPlan.entries) {
                    token.throwIfCancelled()
                    verifyAtBoundary()
                    val relativeName = if (prefixToStrip.isNotEmpty() && entry.name.startsWith(prefixToStrip)) {
                        entry.name.substring(prefixToStrip.length)
                    } else {
                        entry.name
                    }
                    if (relativeName.isEmpty() || relativeName == "/") continue
                    val targetRelative = if (baseTarget.isEmpty()) relativeName else "$baseTarget/$relativeName"
                    val cleanTarget = WorkspacePath.sanitize(targetRelative)
                    if (entry.directory) {
                        if (!fs.exists(cleanTarget)) {
                            fs.mkdir(cleanTarget)
                        }
                    } else {
                        val parent = cleanTarget.substringBeforeLast('/', "")
                        if (parent.isNotEmpty() && !fs.exists(parent)) {
                            ensureDirectories(fs, parent)
                        }
                        fs.openOutputStream(cleanTarget).use { output ->
                            extractZipEntry(channel, entry, output) { token.isCancelled }
                        }
                        filesCount++
                        totalBytesWritten += entry.uncompressedBytes
                    }
                }
            }
            return JSONObject()
                .put("status", "APPLIED")
                .put("plan_id", plan.id)
                .put("mode", "zip")
                .put("destination", plan.relativePath)
                .put("files_written", filesCount)
                .put("bytes_written", totalBytesWritten)
        } else {
            throw IllegalArgumentException("Unknown apply mode: ${plan.mode}")
        }
    }

    private fun ensureDirectories(fs: WorkspaceFs, path: String) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (!fs.exists(current)) {
                fs.mkdir(current)
            }
        }
    }

    companion object {
        internal fun findSingleRootPrefix(entries: List<ZipPayloadEntry>): String {
            val topSegments = entries.map { entry ->
                val firstSlash = entry.name.indexOf('/')
                if (firstSlash > 0) entry.name.substring(0, firstSlash + 1) else ""
            }
            if (topSegments.isEmpty() || topSegments.any { it.isEmpty() }) return ""
            val candidate = topSegments.first()
            return if (topSegments.all { it == candidate }) candidate else ""
        }

        internal fun extractZipEntry(
            channel: SeekableByteChannel,
            entry: ZipPayloadEntry,
            output: OutputStream,
            cancelled: () -> Boolean
        ) {
            val crc = CRC32()
            var written = 0L
            val input = ByteBuffer.allocate(64 * 1024)
            val decoded = ByteArray(64 * 1024)
            val inflater = if (entry.method == 8) Inflater(true) else null
            try {
                var remaining = entry.compressedBytes
                channel.position(entry.dataOffset)
                while (remaining > 0L) {
                    if (cancelled()) throw ArtifactStoreException("DOWNLOAD_APPLY_CANCELLED")
                    input.clear()
                    val chunk = minOf(remaining, input.capacity().toLong()).toInt()
                    input.limit(chunk)
                    val read = channel.read(input)
                    if (read <= 0) throw ArtifactStoreException("DOWNLOAD_APPLY_TRUNCATED")
                    remaining -= read.toLong()
                    input.flip()
                    if (entry.method == 0) {
                        val bytes = ByteArray(read)
                        input.get(bytes)
                        crc.update(bytes)
                        output.write(bytes)
                        written += read.toLong()
                    } else if (entry.method == 8) {
                        inflater?.setInput(input.array(), input.arrayOffset() + input.position(), read)
                        while (!inflater!!.needsInput()) {
                            val count = inflater.inflate(decoded)
                            if (count > 0) {
                                crc.update(decoded, 0, count)
                                output.write(decoded, 0, count)
                                written += count.toLong()
                            } else if (inflater.finished()) break
                        }
                    }
                }
                if (entry.method == 8) {
                    while (!inflater!!.finished()) {
                        val count = inflater.inflate(decoded)
                        if (count > 0) {
                            crc.update(decoded, 0, count)
                            output.write(decoded, 0, count)
                            written += count.toLong()
                        } else break
                    }
                }
                if (written != entry.uncompressedBytes) throw ArtifactStoreException("DOWNLOAD_APPLY_SIZE_MISMATCH")
                if (crc.value != entry.crc32) throw ArtifactStoreException("DOWNLOAD_APPLY_CRC_MISMATCH")
            } finally {
                inflater?.end()
            }
        }
    }
}
