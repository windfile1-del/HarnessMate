package dev.wind.agent.artifact

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** A caller-supplied provenance chain. URLs are recorded as origins only. */
data class ArtifactProvenance(
    val sourceUrl: String,
    val redirectUrls: List<String> = emptyList()
)

/** Opaque content identity returned only after import bytes pass SHA-256 verification. */
data class VerifiedArtifact(
    val uri: String,
    val sha256: String,
    val byteSize: Long
)

class ArtifactStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A local, content-addressed store for already-quarantined input. It does not
 * download, authorize, install, or activate artifacts. The caller must supply a
 * non-secret provenance chain, expected digest, and per-import byte limit.
 *
 * `appPrivateRoot` must be a private directory owned by the application (for
 * example `Context.noBackupFilesDir/verified-artifacts`). First publication
 * stages blob and metadata together in one sibling directory and atomically
 * renames that directory into its digest path.
 */
class VerifiedArtifactStore private constructor(
    appPrivateRoot: Path,
    private val hardMaxArtifactBytes: Long,
    private val publishDirectory: (Path, Path) -> Unit,
    private val replaceMetadata: (Path, Path) -> Unit
) {
    constructor(
        appPrivateRoot: java.io.File,
        hardMaxArtifactBytes: Long = DEFAULT_HARD_MAX_ARTIFACT_BYTES
    ) : this(
        appPrivateRoot.toPath(),
        hardMaxArtifactBytes,
        { staged, target -> Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE) },
        { staged, target ->
            Files.move(
                staged,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    )

    /** Test seam for deterministic failure before a first object's atomic commit. */
    internal constructor(
        appPrivateRoot: java.io.File,
        hardMaxArtifactBytes: Long,
        beforeFirstPublish: () -> Unit
    ) : this(
        appPrivateRoot.toPath(),
        hardMaxArtifactBytes,
        { staged, target ->
            beforeFirstPublish()
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
        },
        { staged, target ->
            Files.move(
                staged,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    )

    private val root: Path
    private val realRoot: Path

    init {
        require(hardMaxArtifactBytes > 0L) {
            "hardMaxArtifactBytes must be positive"
        }
        val absolute = appPrivateRoot.toAbsolutePath().normalize()
        ensureDirectoryTreeWithoutLinks(absolute)
        root = absolute
        realRoot = absolute.toRealPath()
        if (!realRoot.isAbsolute) throw ArtifactStoreException("ARTIFACT_ROOT_NOT_ABSOLUTE")
    }

    /**
     * Stream a quarantine object into this store. The input remains owned by the
     * caller. Bytes over either limit are rejected before they are written.
     */
    fun importQuarantined(
        quarantine: InputStream,
        expectedSha256: String,
        maxBytes: Long,
        provenance: ArtifactProvenance
    ): VerifiedArtifact = synchronized(PROCESS_STORE_LOCK) {
        val digest = normalizeDigest(expectedSha256)
        if (maxBytes < 0 || maxBytes > hardMaxArtifactBytes) {
            throw ArtifactStoreException("ARTIFACT_LIMIT_INVALID")
        }
        val chain = sanitizeProvenance(provenance)
        val shard = shardPath(digest)
        ensureDirectoryTreeWithoutLinks(shard)
        requireContained(shard)

        val staging = Files.createTempDirectory(shard, ".stage-")
        requireContained(staging)
        try {
            val blob = staging.resolve(BLOB_NAME)
            val (actualDigest, actualSize) = writeAndDigest(quarantine, blob, maxBytes)
            if (actualDigest != digest) throw ArtifactStoreException("ARTIFACT_SHA256_MISMATCH")

            val now = System.currentTimeMillis()
            val stagedMetadata = Metadata(
                sha256 = digest,
                byteSize = actualSize,
                firstSeenEpochMillis = now,
                lastVerifiedEpochMillis = now,
                provenanceChains = listOf(chain)
            )
            writeMetadata(staging.resolve(METADATA_NAME), stagedMetadata)

            val objectDirectory = objectPath(digest)
            if (existsNoFollow(objectDirectory)) {
                val existing = readAndVerifyExisting(digest)
                val merged = mergeMetadata(existing, chain, now)
                atomicReplaceMetadata(objectDirectory, merged)
                deleteTree(staging)
                return@synchronized VerifiedArtifact(uri(digest), digest, existing.byteSize)
            }

            try {
                publishDirectory(staging, objectDirectory)
            } catch (publishFailure: Exception) {
                // A concurrent importer may have won the same digest. Re-open
                // and verify its object before accepting it or merging metadata.
                if (!existsNoFollow(objectDirectory)) throw publishFailure
                val existing = readAndVerifyExisting(digest)
                val merged = mergeMetadata(existing, chain, now)
                atomicReplaceMetadata(objectDirectory, merged)
                deleteTree(staging)
                return@synchronized VerifiedArtifact(uri(digest), digest, existing.byteSize)
            }
            VerifiedArtifact(uri(digest), digest, actualSize)
        } catch (failure: Exception) {
            deleteTreeQuietly(staging)
            if (failure is ArtifactStoreException) throw failure
            throw ArtifactStoreException("ARTIFACT_IMPORT_FAILED", failure)
        }
    }

    /** Parse and verify an artifact URI before opening any store path. */
    fun openVerified(artifactUri: String): InputStream = synchronized(PROCESS_STORE_LOCK) {
        val digest = parseArtifactUri(artifactUri)
        val objectDirectory = objectPath(digest)
        requireContained(objectDirectory)
        if (!existsNoFollow(objectDirectory)) throw ArtifactStoreException("ARTIFACT_NOT_FOUND")
        requireSafeDirectory(objectDirectory)

        val metadataPath = objectDirectory.resolve(METADATA_NAME)
        requireSafeRegularFile(metadataPath)
        val metadata = readMetadata(metadataPath, digest)
        val blob = objectDirectory.resolve(BLOB_NAME)
        requireSafeRegularFile(blob)

        val channel = openAndVerifyBlob(blob, metadata)
        try {
            val updated = metadata.copy(
                lastVerifiedEpochMillis = maxOf(
                    metadata.firstSeenEpochMillis,
                    metadata.lastVerifiedEpochMillis,
                    System.currentTimeMillis()
                )
            )
            atomicReplaceMetadata(objectDirectory, updated)
            channel.position(0L)
            Channels.newInputStream(channel)
        } catch (failure: Exception) {
            try { channel.close() } catch (_: Exception) { }
            if (failure is ArtifactStoreException) throw failure
            throw ArtifactStoreException("ARTIFACT_METADATA_UPDATE_FAILED", failure)
        }
    }

    fun getArtifact(artifactUri: String): VerifiedArtifact? = synchronized(PROCESS_STORE_LOCK) {
        val digest = try { parseArtifactUri(artifactUri) } catch (_: Exception) { return null }
        val objectDirectory = objectPath(digest)
        if (!existsNoFollow(objectDirectory)) return null
        return try {
            val metadataPath = objectDirectory.resolve(METADATA_NAME)
            val metadata = readMetadata(metadataPath, digest)
            VerifiedArtifact(uri(digest), digest, metadata.byteSize)
        } catch (_: Exception) {
            null
        }
    }

    fun getArtifactFile(artifactUri: String): java.io.File? = synchronized(PROCESS_STORE_LOCK) {
        val digest = try { parseArtifactUri(artifactUri) } catch (_: Exception) { return null }
        val blob = objectPath(digest).resolve(BLOB_NAME)
        if (existsNoFollow(blob) && java.nio.file.Files.isRegularFile(blob, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            blob.toFile()
        } else null
    }

    fun deleteArtifact(artifactUri: String): Boolean = synchronized(PROCESS_STORE_LOCK) {
        val digest = try { parseArtifactUri(artifactUri) } catch (_: Exception) { return false }
        val objectDirectory = objectPath(digest)
        if (!existsNoFollow(objectDirectory)) return false
        requireContained(objectDirectory)
        deleteTree(objectDirectory)
        val shard = shardPath(digest)
        if (existsNoFollow(shard)) {
            try {
                Files.deleteIfExists(shard)
            } catch (_: Exception) { }
        }
        true
    }

    fun listArtifacts(): List<VerifiedArtifact> = synchronized(PROCESS_STORE_LOCK) {
        val objectsRoot = root.resolve("objects").resolve("sha256")
        if (!existsNoFollow(objectsRoot)) return emptyList()
        val result = mutableListOf<VerifiedArtifact>()
        try {
            Files.list(objectsRoot).use { shards ->
                shards.forEach { shard ->
                    if (Files.isDirectory(shard, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(shard)) {
                        Files.list(shard).use { objs ->
                            objs.forEach { obj ->
                                if (Files.isDirectory(obj, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(obj)) {
                                    val digest = obj.fileName.toString()
                                    if (SHA256_HEX.matches(digest)) {
                                        try {
                                            val meta = readMetadata(obj.resolve(METADATA_NAME), digest)
                                            result.add(VerifiedArtifact(uri(digest), digest, meta.byteSize))
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return result.sortedBy { it.uri }
    }

    /** Keep the verified file descriptor private while a bounded inspector reads it. */
    internal fun <T> withVerifiedChannel(
        artifactUri: String,
        inspect: (SeekableByteChannel, VerifiedArtifact) -> T
    ): T = synchronized(PROCESS_STORE_LOCK) {
        val digest = parseArtifactUri(artifactUri)
        val objectDirectory = objectPath(digest)
        requireSafeDirectory(objectDirectory)
        val metadataPath = objectDirectory.resolve(METADATA_NAME)
        requireSafeRegularFile(metadataPath)
        val metadata = readMetadata(metadataPath, digest)
        val blob = objectDirectory.resolve(BLOB_NAME)
        requireSafeRegularFile(blob)
        openAndVerifyBlob(blob, metadata).use { channel ->
            inspect(channel, VerifiedArtifact(uri(digest), digest, metadata.byteSize))
        }
    }

    private fun readAndVerifyExisting(digest: String): Metadata {
        val directory = objectPath(digest)
        requireContained(directory)
        requireSafeDirectory(directory)
        val metadataPath = directory.resolve(METADATA_NAME)
        val blob = directory.resolve(BLOB_NAME)
        requireSafeRegularFile(metadataPath)
        requireSafeRegularFile(blob)
        val metadata = readMetadata(metadataPath, digest)
        openAndVerifyBlob(blob, metadata).use { }
        return metadata
    }

    private fun openAndVerifyBlob(blob: Path, metadata: Metadata): SeekableByteChannel {
        requireSafeRegularFile(blob)
        val channel = try {
            Files.newByteChannel(
                blob,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            )
        } catch (failure: Exception) {
            throw ArtifactStoreException("ARTIFACT_BLOB_OPEN_FAILED", failure)
        }
        try {
            if (channel.size() != metadata.byteSize) throw ArtifactStoreException("ARTIFACT_SIZE_MISMATCH")
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(IO_BUFFER_BYTES)
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer.array(), 0, count)
            }
            if (digest.hex() != metadata.sha256) throw ArtifactStoreException("ARTIFACT_SHA256_MISMATCH")
            channel.position(0L)
            return channel
        } catch (failure: Exception) {
            try { channel.close() } catch (_: Exception) { }
            if (failure is ArtifactStoreException) throw failure
            throw ArtifactStoreException("ARTIFACT_BLOB_VERIFY_FAILED", failure)
        }
    }

    private fun writeAndDigest(input: InputStream, target: Path, maxBytes: Long): Pair<String, Long> {
        requireContained(target)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        Files.newOutputStream(
            target,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { output ->
            val buffer = ByteArray(IO_BUFFER_BYTES)
            while (true) {
                val remaining = maxBytes - total
                val request = if (remaining >= buffer.size) buffer.size else (remaining + 1L).toInt()
                val count = input.read(buffer, 0, request)
                if (count < 0) break
                if (count == 0) {
                    val one = input.read()
                    if (one < 0) break
                    if (remaining == 0L) throw ArtifactStoreException("ARTIFACT_SIZE_LIMIT_EXCEEDED")
                    output.write(one)
                    digest.update(one.toByte())
                    total += 1L
                    continue
                }
                if (count.toLong() > remaining) throw ArtifactStoreException("ARTIFACT_SIZE_LIMIT_EXCEEDED")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                total += count.toLong()
            }
        }
        return digest.hex() to total
    }

    private fun atomicReplaceMetadata(objectDirectory: Path, metadata: Metadata) {
        requireSafeDirectory(objectDirectory)
        val target = objectDirectory.resolve(METADATA_NAME)
        requireSafeRegularFile(target)
        val temporary = objectDirectory.resolve(".metadata-${UUID.randomUUID()}.tmp")
        try {
            writeMetadata(temporary, metadata)
            replaceMetadata(temporary, target)
        } catch (failure: Exception) {
            deleteTreeQuietly(temporary)
            throw ArtifactStoreException("ARTIFACT_METADATA_UPDATE_FAILED", failure)
        }
    }

    private fun writeMetadata(path: Path, metadata: Metadata) {
        if (metadata.provenanceChains.isEmpty() || metadata.provenanceChains.size > MAX_PROVENANCE_CHAINS) {
            throw ArtifactStoreException("ARTIFACT_PROVENANCE_LIMIT")
        }
        if (metadata.byteSize < 0 || metadata.byteSize > hardMaxArtifactBytes) {
            throw ArtifactStoreException("ARTIFACT_METADATA_SIZE_INVALID")
        }
        val properties = Properties().apply {
            setProperty("format", METADATA_FORMAT)
            setProperty("status", VERIFIED_STATUS)
            setProperty("sha256", metadata.sha256)
            setProperty("byteSize", metadata.byteSize.toString())
            setProperty("firstSeenEpochMillis", metadata.firstSeenEpochMillis.toString())
            setProperty("lastVerifiedEpochMillis", metadata.lastVerifiedEpochMillis.toString())
            setProperty("provenanceCount", metadata.provenanceChains.size.toString())
            metadata.provenanceChains.forEachIndexed { index, chain ->
                setProperty("provenance.$index", chain)
            }
        }
        val options: Array<OpenOption> = arrayOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        )
        Files.newOutputStream(path, *options).use { output -> properties.store(output, null) }
        if (Files.size(path) > MAX_METADATA_BYTES) throw ArtifactStoreException("ARTIFACT_METADATA_TOO_LARGE")
    }

    private fun readMetadata(path: Path, expectedDigest: String): Metadata {
        val bytes = readBoundedMetadata(path)
        val properties = Properties()
        try {
            ByteArrayInputStream(bytes).use(properties::load)
        } catch (failure: Exception) {
            throw ArtifactStoreException("ARTIFACT_METADATA_INVALID", failure)
        }
        if (properties.getProperty("format") != METADATA_FORMAT ||
            properties.getProperty("status") != VERIFIED_STATUS ||
            properties.getProperty("sha256") != expectedDigest
        ) throw ArtifactStoreException("ARTIFACT_METADATA_MISMATCH")

        val size = properties.getProperty("byteSize")?.toLongOrNull()
            ?: throw ArtifactStoreException("ARTIFACT_METADATA_SIZE_INVALID")
        if (size < 0 || size > hardMaxArtifactBytes) throw ArtifactStoreException("ARTIFACT_METADATA_SIZE_INVALID")
        val firstSeen = properties.getProperty("firstSeenEpochMillis")?.toLongOrNull()
            ?: throw ArtifactStoreException("ARTIFACT_METADATA_TIME_INVALID")
        val lastVerified = properties.getProperty("lastVerifiedEpochMillis")?.toLongOrNull()
            ?: throw ArtifactStoreException("ARTIFACT_METADATA_TIME_INVALID")
        if (firstSeen <= 0 || lastVerified < firstSeen) throw ArtifactStoreException("ARTIFACT_METADATA_TIME_INVALID")
        val count = properties.getProperty("provenanceCount")?.toIntOrNull()
            ?: throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID")
        if (count !in 1..MAX_PROVENANCE_CHAINS) throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID")
        val chains = (0 until count).map { index ->
            val chain = properties.getProperty("provenance.$index")
                ?: throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID")
            validateStoredProvenance(chain)
            chain
        }
        return Metadata(expectedDigest, size, firstSeen, lastVerified, chains)
    }

    private fun readBoundedMetadata(path: Path): ByteArray {
        requireSafeRegularFile(path)
        val result = ByteArrayOutputStream()
        try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size().toLong() + count > MAX_METADATA_BYTES) {
                        throw ArtifactStoreException("ARTIFACT_METADATA_TOO_LARGE")
                    }
                    result.write(buffer, 0, count)
                }
            }
        } catch (failure: Exception) {
            if (failure is ArtifactStoreException) throw failure
            throw ArtifactStoreException("ARTIFACT_METADATA_READ_FAILED", failure)
        }
        return result.toByteArray()
    }

    private fun mergeMetadata(existing: Metadata, chain: String, now: Long): Metadata {
        if (existing.byteSize < 0) throw ArtifactStoreException("ARTIFACT_METADATA_SIZE_INVALID")
        val chains = if (chain in existing.provenanceChains) existing.provenanceChains
        else existing.provenanceChains + chain
        if (chains.size > MAX_PROVENANCE_CHAINS) throw ArtifactStoreException("ARTIFACT_PROVENANCE_LIMIT")
        return existing.copy(
            lastVerifiedEpochMillis = maxOf(existing.firstSeenEpochMillis, existing.lastVerifiedEpochMillis, now),
            provenanceChains = chains
        )
    }

    private fun validateStoredProvenance(chain: String) {
        if (chain.length > MAX_PROVENANCE_CHAIN_CHARS) throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID")
        val origins = chain.split(" -> ")
        if (origins.isEmpty() || origins.size > MAX_REDIRECTS + 1) {
            throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID")
        }
        origins.forEach { origin ->
            val canonical = try { safeOrigin(origin) } catch (failure: Exception) {
                throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID", failure)
            }
            if (canonical != origin) throw ArtifactStoreException("ARTIFACT_METADATA_PROVENANCE_INVALID")
        }
    }

    private fun sanitizeProvenance(provenance: ArtifactProvenance): String {
        if (provenance.redirectUrls.size > MAX_REDIRECTS) throw ArtifactStoreException("ARTIFACT_REDIRECT_LIMIT")
        val origins = (listOf(provenance.sourceUrl) + provenance.redirectUrls).map(::safeOrigin)
        val chain = origins.joinToString(" -> ")
        if (chain.length > MAX_PROVENANCE_CHAIN_CHARS) throw ArtifactStoreException("ARTIFACT_PROVENANCE_TOO_LARGE")
        return chain
    }

    private fun safeOrigin(raw: String): String {
        val parsed = URI(raw)
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme == "execution") {
            require(!parsed.isOpaque && parsed.host != null && parsed.userInfo == null
                && parsed.query == null && parsed.fragment == null && parsed.port == -1
                && parsed.path.startsWith("/") && parsed.normalize() == parsed
                && parsed.path.split('/').none { it == ".." || it == "." }
                && raw.length <= MAX_PROVENANCE_CHAIN_CHARS) { "invalid execution artifact provenance" }
            return parsed.toASCIIString()
        }
        if (scheme != "http" && scheme != "https") throw IllegalArgumentException("unsupported provenance scheme")
        if (parsed.isOpaque) throw IllegalArgumentException("opaque provenance URI")
        val host = parsed.host?.removePrefix("[")?.removeSuffix("]")?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("provenance URI needs a host")
        if (host.length > MAX_PROVENANCE_HOST_CHARS || parsed.port !in -1..65535 || parsed.port == 0) {
            throw IllegalArgumentException("invalid provenance authority")
        }
        val formattedHost = if (host.contains(':')) "[$host]" else host
        val port = if (parsed.port >= 0) ":${parsed.port}" else ""
        return "$scheme://$formattedHost$port"
    }

    private fun shardPath(digest: String): Path = root.resolve("objects").resolve("sha256").resolve(digest.substring(0, 2))
    private fun objectPath(digest: String): Path = shardPath(digest).resolve(digest)

    private fun ensureDirectoryTreeWithoutLinks(directory: Path) {
        val absolute = directory.toAbsolutePath().normalize()
        if (!absolute.isAbsolute) throw ArtifactStoreException("ARTIFACT_PATH_NOT_ABSOLUTE")
        var current = absolute.root ?: throw ArtifactStoreException("ARTIFACT_PATH_NOT_ABSOLUTE")
        for (segment in absolute) {
            current = current.resolve(segment)
            if (!existsNoFollow(current)) {
                try {
                    Files.createDirectory(current)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    // Recheck the winner below; a symlink is never accepted.
                }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw ArtifactStoreException("ARTIFACT_PATH_UNSAFE")
            }
        }
    }

    private fun requireContained(path: Path) {
        val absolute = path.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) throw ArtifactStoreException("ARTIFACT_PATH_ESCAPE")
        var current = root
        if (Files.isSymbolicLink(current)) throw ArtifactStoreException("ARTIFACT_PATH_UNSAFE")
        val relative = root.relativize(absolute)
        for (segment in relative) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw ArtifactStoreException("ARTIFACT_PATH_UNSAFE")
            if (existsNoFollow(current)) {
                val real = current.toRealPath()
                if (!real.startsWith(realRoot)) throw ArtifactStoreException("ARTIFACT_PATH_ESCAPE")
            }
        }
    }

    private fun requireSafeDirectory(path: Path) {
        requireContained(path)
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArtifactStoreException("ARTIFACT_PATH_UNSAFE")
        }
    }

    private fun requireSafeRegularFile(path: Path) {
        requireContained(path)
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ArtifactStoreException("ARTIFACT_PATH_UNSAFE")
        }
        val real = path.toRealPath()
        if (!real.startsWith(realRoot)) throw ArtifactStoreException("ARTIFACT_PATH_ESCAPE")
    }

    private fun existsNoFollow(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun deleteTree(path: Path) {
        if (!existsNoFollow(path)) return
        requireContained(path)
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteTreeQuietly(path: Path) {
        try { deleteTree(path) } catch (_: Exception) { }
    }

    private fun normalizeDigest(raw: String): String {
        if (!SHA256_HEX.matches(raw)) throw ArtifactStoreException("ARTIFACT_SHA256_INVALID")
        return raw.lowercase(Locale.ROOT)
    }

    private fun parseArtifactUri(value: String): String {
        val match = ARTIFACT_URI.matchEntire(value) ?: throw ArtifactStoreException("ARTIFACT_URI_INVALID")
        return match.groupValues[1]
    }

    private fun uri(digest: String): String = "artifact://sha256/$digest"

    private data class Metadata(
        val sha256: String,
        val byteSize: Long,
        val firstSeenEpochMillis: Long,
        val lastVerifiedEpochMillis: Long,
        val provenanceChains: List<String>
    )

    companion object {
        const val DEFAULT_HARD_MAX_ARTIFACT_BYTES: Long = Long.MAX_VALUE
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val MAX_METADATA_BYTES = 64 * 1024
        private const val MAX_PROVENANCE_CHAINS = 8
        private const val MAX_REDIRECTS = 6
        private const val MAX_PROVENANCE_HOST_CHARS = 253
        private const val MAX_PROVENANCE_CHAIN_CHARS = 2048
        private const val BLOB_NAME = "blob"
        private const val METADATA_NAME = "metadata.properties"
        private const val METADATA_FORMAT = "vega-artifact-metadata-v1"
        private const val VERIFIED_STATUS = "VERIFIED"
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        private val ARTIFACT_URI = Regex("artifact://sha256/([0-9a-f]{64})")
        private val PROCESS_STORE_LOCK = Any()
    }
}

private fun MessageDigest.hex(): String = digest().joinToString("") { byte ->
    val value = byte.toInt() and 0xff
    "0123456789abcdef"[(value ushr 4)] .toString() + "0123456789abcdef"[value and 0x0f]
}
