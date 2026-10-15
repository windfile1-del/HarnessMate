package dev.wind.agent.artifact

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files

/** Snapshot a binary build output without changing canonical files or using a text channel. */
class ExecutionArtifactPublisher(private val scratch: File, private val store: VerifiedArtifactStore) {
    fun publish(input: InputStream, expectedSize: Long, origin: String, verify: () -> Unit): VerifiedArtifact {
        require(expectedSize > 0) { "EXECUTION_ARTIFACT_EMPTY" }
        require(scratch.canonicalFile == scratch.absoluteFile && scratch.isDirectory) { "EXECUTION_ARTIFACT_SCRATCH_INVALID" }
        val stage = Files.createTempFile(scratch.toPath(), ".execution-artifact-", ".tmp").toFile()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            input.use { source ->
                FileOutputStream(stage).use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        verify()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        check(count.toLong() <= expectedSize - bytes) { "EXECUTION_ARTIFACT_SIZE_CHANGED" }
                        bytes += count
                        hash.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                    out.fd.sync()
                }
            }
            check(bytes == expectedSize) { "EXECUTION_ARTIFACT_TRUNCATED" }
            verify()
            val digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            return stage.inputStream().use { store.importQuarantined(it, digest, expectedSize, ArtifactProvenance(origin)) }
        } finally { Files.deleteIfExists(stage.toPath()) }
    }
}
