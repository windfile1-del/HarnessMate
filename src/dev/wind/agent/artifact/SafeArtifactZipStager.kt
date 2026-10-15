package dev.wind.agent.artifact

import java.io.OutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Verified ZIP bytes to a fresh, app-private staging tree. No activation or execution authority. */
internal class SafeArtifactZipStager(
    private val store: VerifiedArtifactStore,
    private val access: TaskArtifactAccess,
    stagingRoot: Path
) {
    private val root = stagingRoot.toAbsolutePath().normalize()

    init {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root) || root.toRealPath() != root
        ) throw ArtifactStoreException("ARTIFACT_STAGE_ROOT_UNSAFE")
    }

    fun stage(
        taskId: String,
        projectId: String,
        projectGeneration: Long,
        artifactUri: String,
        cancelled: () -> Boolean = { false }
    ): Path {
        access.requireAccess(taskId, projectId, projectGeneration, artifactUri)
        return store.withVerifiedChannel(artifactUri) { channel, _ ->
            val plan = ZipDirectoryInspector().plan(channel)
            if (cancelled()) throw ArtifactStoreException("ARTIFACT_STAGE_CANCELLED")
            val directory = Files.createTempDirectory(root, "zip-stage-")
            try {
                for (entry in plan.entries) {
                    if (cancelled()) throw ArtifactStoreException("ARTIFACT_STAGE_CANCELLED")
                    val relative = if (entry.directory) entry.name.dropLast(1) else entry.name
                    val target = directory.resolve(relative).normalize()
                    if (!target.startsWith(directory) || target == directory) {
                        throw ArtifactStoreException("ARTIFACT_STAGE_PATH_UNSAFE")
                    }
                    ensureParents(directory, target.parent)
                    if (entry.directory) {
                        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(target)
                        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                            throw ArtifactStoreException("ARTIFACT_STAGE_PATH_UNSAFE")
                        }
                        verifyEmptyDirectoryEntry(entry)
                    } else {
                        Files.createFile(target)
                        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                            throw ArtifactStoreException("ARTIFACT_STAGE_PATH_UNSAFE")
                        }
                        FileOutputStream(target.toFile()).use { output ->
                            // Android 14+ DCL: make a newly opened code candidate read-only
                            // before any archive bytes are written through this FD.
                            if (!target.toFile().setReadOnly()) {
                                throw ArtifactStoreException("ARTIFACT_STAGE_READ_ONLY_FAILED")
                            }
                            extract(channel, entry, output, cancelled)
                            output.fd.sync()
                        }
                    }
                }
                directory
            } catch (failure: Exception) {
                cleanup(directory)
                throw failure
            }
        }
    }

    private fun ensureParents(base: Path, parent: Path) {
        var current = base
        for (segment in base.relativize(parent)) {
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                throw ArtifactStoreException("ARTIFACT_STAGE_PATH_UNSAFE")
            }
        }
    }

    private fun verifyEmptyDirectoryEntry(entry: ZipPayloadEntry) {
        if (entry.crc32 != 0L) throw ArtifactStoreException("ARTIFACT_STAGE_CRC_MISMATCH")
    }

    private fun extract(
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
                if (cancelled()) throw ArtifactStoreException("ARTIFACT_STAGE_CANCELLED")
                input.clear()
                input.limit(minOf(input.capacity().toLong(), remaining).toInt())
                val read = channel.read(input)
                if (read <= 0) throw ArtifactStoreException("ARTIFACT_STAGE_TRUNCATED")
                remaining -= read
                if (inflater == null) {
                    output.write(input.array(), 0, read)
                    crc.update(input.array(), 0, read)
                    written += read
                } else {
                    inflater.setInput(input.array(), 0, read)
                    while (!inflater.needsInput()) {
                        val produced = inflater.inflate(decoded)
                        if (produced == 0) {
                            if (inflater.finished() || inflater.needsInput()) break
                            throw ArtifactStoreException("ARTIFACT_STAGE_DEFLATE_INVALID")
                        }
                        written += produced
                        if (written > entry.uncompressedBytes) throw ArtifactStoreException("ARTIFACT_STAGE_SIZE_MISMATCH")
                        output.write(decoded, 0, produced)
                        crc.update(decoded, 0, produced)
                    }
                    if (inflater.finished() && (remaining != 0L || inflater.remaining != 0)) {
                        throw ArtifactStoreException("ARTIFACT_STAGE_DEFLATE_TRAILING_DATA")
                    }
                }
            }
            if (inflater != null && (!inflater.finished() || inflater.remaining != 0)) {
                throw ArtifactStoreException("ARTIFACT_STAGE_DEFLATE_INVALID")
            }
            if (written != entry.uncompressedBytes) throw ArtifactStoreException("ARTIFACT_STAGE_SIZE_MISMATCH")
            if (crc.value != entry.crc32) throw ArtifactStoreException("ARTIFACT_STAGE_CRC_MISMATCH")
        } catch (failure: java.util.zip.DataFormatException) {
            throw ArtifactStoreException("ARTIFACT_STAGE_DEFLATE_INVALID", failure)
        } finally {
            inflater?.end()
        }
    }

    private fun cleanup(directory: Path) {
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !Files.isSymbolicLink(file)) file.toFile().setWritable(true)
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
