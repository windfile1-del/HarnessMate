package dev.wind.agent.artifact

import java.nio.ByteBuffer

/** Bounded magic-byte classification; never an installation or trust decision. */
data class ArtifactInspection(
    val artifact: VerifiedArtifact,
    val observedFormat: String,
    val zipDirectory: ZipDirectorySummary? = null,
    val archiveInspected: Boolean = false,
    val trustedPublisher: Boolean = false,
    val installable: Boolean = false
)

class ArtifactInspector(
    private val store: VerifiedArtifactStore,
    private val access: TaskArtifactAccess
) {
    fun inspect(taskId: String, projectId: String, projectGeneration: Long, artifactUri: String): ArtifactInspection {
        access.requireAccess(taskId, projectId, projectGeneration, artifactUri)
        return store.withVerifiedChannel(artifactUri) { channel, artifact ->
            val prefix = ByteBuffer.allocate(minOf(8L, artifact.byteSize).toInt())
            channel.position(0L)
            while (prefix.hasRemaining()) {
                if (channel.read(prefix) < 0) throw ArtifactStoreException("ARTIFACT_SIZE_MISMATCH")
            }
            val format = classify(prefix.array())
            val zip = if (format == "ZIP_CONTAINER") ZipDirectoryInspector().inspect(channel) else null
            ArtifactInspection(artifact, format, zip)
        }
    }

    private fun classify(bytes: ByteArray): String = when {
        starts(bytes, 0x50, 0x4b, 0x03, 0x04) || starts(bytes, 0x50, 0x4b, 0x05, 0x06) -> "ZIP_CONTAINER"
        starts(bytes, 0x64, 0x65, 0x78, 0x0a) -> "DEX"
        starts(bytes, 0x7f, 0x45, 0x4c, 0x46) -> "ELF"
        starts(bytes, 0x25, 0x50, 0x44, 0x46) -> "PDF"
        starts(bytes, 0x1f, 0x8b) -> "GZIP"
        else -> "UNKNOWN"
    }

    private fun starts(bytes: ByteArray, vararg marker: Int): Boolean =
        bytes.size >= marker.size && marker.indices.all { (bytes[it].toInt() and 255) == marker[it] }
}
