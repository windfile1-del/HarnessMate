package dev.wind.agent.artifact

import dev.wind.agent.CancellationToken
import dev.wind.agent.build.MavenCoordinate
import dev.wind.agent.build.MavenCompileGraph
import dev.wind.agent.build.CasMavenPomSource
import dev.wind.agent.build.MavenCompileGraphPlanner
import dev.wind.agent.build.MavenPomPin
import dev.wind.agent.build.VerifiedMavenPomSource
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** One explicitly approved HTTPS Maven repository, with no mirror fallback. */
data class MavenRepository(val id: String, val baseUrl: String) {
    internal val base: URI = ArtifactNetworkPolicy.parseUrl(baseUrl)

    init {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "MAVEN_REPOSITORY_ID_INVALID" }
        val path = base.rawPath ?: ""
        require(path.startsWith('/') && path.endsWith('/') && !path.contains("//") &&
            !path.contains('%') && path.split('/').none { it == "." || it == ".." } &&
            base.rawQuery == null && base.rawFragment == null) { "MAVEN_REPOSITORY_URL_INVALID" }
    }

    fun urlFor(coordinate: MavenCoordinate, extension: String): String {
        require(extension in setOf("pom", "jar")) { "MAVEN_EXTENSION_INVALID" }
        val path = coordinate.group.replace('.', '/') + "/" + coordinate.artifact +
            "/" + coordinate.version + "/" + coordinate.artifact +
            "-" + coordinate.version + "." + extension
        return base.resolve(path).toString()
    }

    internal fun owns(uri: URI): Boolean {
        val checked = ArtifactNetworkPolicy.parseUrl(uri.toString())
        val basePort = if (base.port == -1) 443 else base.port
        val actualPort = if (checked.port == -1) 443 else checked.port
        return checked.host.lowercase(Locale.US) == base.host.lowercase(Locale.US) &&
            checked.scheme.equals(base.scheme, true) && actualPort == basePort &&
            checked.rawQuery == null && checked.rawPath.startsWith(base.rawPath)
    }
}

data class MavenDownloadedModule(
    val coordinate: MavenCoordinate,
    val repositoryId: String,
    val pomPin: MavenPomPin,
    val jar: VerifiedArtifact,
    val pomUrl: String,
    val jarUrl: String,
    val pomChecksumKind: MavenChecksumKind,
    val jarChecksumKind: MavenChecksumKind,
    val supportingPoms: List<MavenDownloadedPom> = emptyList()
)

data class MavenDownloadedPom(val pin: MavenPomPin, val url: String, val checksumKind: MavenChecksumKind)
data class MavenDownloadedGraph(val graph: MavenCompileGraph,
    val modules: List<MavenDownloadedModule>, val supportingPoms: List<MavenDownloadedPom>)

enum class MavenChecksumKind { EXPECTED_SHA256, REPOSITORY_SHA256, REPOSITORY_SHA1 }

/**
 * Downloads exact POM/JAR bytes into the CAS. The same-repository redirect
 * restriction is in addition to the caller's per-hop network capability.
 * A checksum sidecar or an independently supplied expected SHA-256 is mandatory.
 * Legacy SHA-1 sidecars require an explicit, separate policy choice and are
 * repository transport evidence only, not publisher identity.
 */
class MavenRepositoryAcquirer internal constructor(
    private val repository: MavenRepository,
    store: VerifiedArtifactStore,
    quarantineRoot: File,
    transport: ArtifactHttpTransport,
    private val allowRepositorySha1Fallback: Boolean = false,
    authorizeHop: (URI) -> Boolean
) {
    constructor(
        repository: MavenRepository,
        store: VerifiedArtifactStore,
        quarantineRoot: File,
        allowRepositorySha1Fallback: Boolean = false,
        authorizeHop: (URI) -> Boolean
    ) : this(repository, store, quarantineRoot, PinnedHttpsArtifactTransport(),
        allowRepositorySha1Fallback, authorizeHop)

    private val store = store
    private val permit: (URI) -> Boolean = { uri -> repository.owns(uri) && authorizeHop(uri) }
    private val textFetcher = AuthorizedTextFetcher(transport, permit)
    private val downloader = SecureArtifactDownloader(store, quarantineRoot, transport, { it.usableSpace }, authorizeHop = permit)

    fun acquire(
        coordinate: MavenCoordinate,
        token: CancellationToken,
        expectedPomSha256: String? = null,
        expectedJarSha256: String? = null,
        maxJarBytes: Long = 256L * 1024L * 1024L,
        expectedModelPomSha256: Map<MavenCoordinate, String> = emptyMap()
    ): MavenDownloadedModule {
        require(expectedPomSha256 == null || expectedModelPomSha256[coordinate] == null ||
            expectedPomSha256.equals(expectedModelPomSha256[coordinate], true)) {
            "MAVEN_POM_PIN_CONFLICT"
        }
        val pomUrl = repository.urlFor(coordinate, "pom")
        val jarUrl = repository.urlFor(coordinate, "jar")
        val pomChecksum = (expectedPomSha256 ?: expectedModelPomSha256[coordinate])?.let {
            Checksum(validateSha256(it), null, MavenChecksumKind.EXPECTED_SHA256)
        } ?: fetchChecksum(pomUrl, token)
        val jarChecksum = expectedJarSha256?.let {
            Checksum(validateSha256(it), null, MavenChecksumKind.EXPECTED_SHA256)
        } ?: fetchChecksum(jarUrl, token)
        token.throwIfCancelled()
        val downloadedPom = downloadPom(coordinate, pomChecksum, token)
        val pomPin = downloadedPom.pin
        val models = linkedMapOf(coordinate to downloadedPom)
        val source = VerifiedMavenPomSource { requested ->
            token.throwIfCancelled()
            val downloaded = models.getOrPut(requested) {
                downloadPom(requested, checksumForPom(requested, expectedModelPomSha256, token), token)
            }
            CasMavenPomSource(store, listOf(downloaded.pin)).get(requested)
        }
        val validatedPom = source.get(coordinate)
            ?: throw ArtifactDownloadException("MAVEN_POM_MISSING")
        val supporting = MavenCompileGraphPlanner(source).validatePom(validatedPom)
            .map { models.getValue(it.coordinate) }
        token.throwIfCancelled()
        val jar = downloader.download(jarUrl, "maven jar", jarChecksum.sha256, maxJarBytes, token)
        verifySha1IfNeeded(jar, jarChecksum, token)
        return MavenDownloadedModule(coordinate, repository.id, pomPin, jar, pomUrl, jarUrl,
            pomChecksum.kind, jarChecksum.kind, supporting)
    }

    /** Resolve and fetch the selected transitive graph; parent/BOM artifacts are POM-only. */
    fun acquireGraph(
        roots: List<MavenCoordinate>, token: CancellationToken,
        expectedPomSha256: Map<MavenCoordinate, String> = emptyMap(),
        expectedJarSha256: Map<MavenCoordinate, String> = emptyMap(),
        maxJarBytes: Long = 256L * 1024L * 1024L
    ): MavenDownloadedGraph {
        val models = linkedMapOf<MavenCoordinate, MavenDownloadedPom>()
        val source = VerifiedMavenPomSource { coordinate ->
            token.throwIfCancelled()
            val downloaded = models.getOrPut(coordinate) {
                downloadPom(coordinate, checksumForPom(coordinate, expectedPomSha256, token), token)
            }
            CasMavenPomSource(store, listOf(downloaded.pin)).get(coordinate)
        }
        val graph = MavenCompileGraphPlanner(source).plan(roots)
        val modules = graph.nodes.map { node ->
            token.throwIfCancelled()
            val pom = models.getValue(node.coordinate)
            val jarUrl = repository.urlFor(node.coordinate, "jar")
            val checksum = expectedJarSha256[node.coordinate]?.let {
                Checksum(validateSha256(it), null, MavenChecksumKind.EXPECTED_SHA256)
            } ?: fetchChecksum(jarUrl, token)
            val jar = downloader.download(jarUrl, "maven jar", checksum.sha256, maxJarBytes, token)
            verifySha1IfNeeded(jar, checksum, token)
            MavenDownloadedModule(node.coordinate, repository.id, pom.pin, jar, pom.url,
                jarUrl, pom.checksumKind, checksum.kind)
        }
        return MavenDownloadedGraph(graph, modules,
            graph.supportingPoms.map { models.getValue(it.coordinate) })
    }

    private fun checksumForPom(coordinate: MavenCoordinate, pins: Map<MavenCoordinate, String>,
        token: CancellationToken): Checksum = pins[coordinate]?.let {
        Checksum(validateSha256(it), null, MavenChecksumKind.EXPECTED_SHA256)
    } ?: fetchChecksum(repository.urlFor(coordinate, "pom"), token)

    private fun downloadPom(coordinate: MavenCoordinate, checksum: Checksum,
        token: CancellationToken): MavenDownloadedPom {
        token.throwIfCancelled()
        val url = repository.urlFor(coordinate, "pom")
        val pom = downloader.download(url, "maven pom", checksum.sha256, MAX_POM_BYTES, token)
        verifySha1IfNeeded(pom, checksum, token)
        return MavenDownloadedPom(MavenPomPin(coordinate, repository.id, pom.uri, pom.sha256,
            pom.byteSize.toInt()), url, checksum.kind)
    }

    private data class Checksum(val sha256: String?, val sha1: String?, val kind: MavenChecksumKind)

    private fun fetchChecksum(url: String, token: CancellationToken): Checksum {
        val sidecar = try {
            textFetcher.fetch("$url.sha256", "maven sha256", 128, token)
        } catch (failure: ArtifactDownloadException) {
            if (failure.message == "FETCH_HTTP_STATUS_404") {
                if (!allowRepositorySha1Fallback) {
                    throw ArtifactDownloadException("MAVEN_SHA256_REQUIRED", failure)
                }
                val legacy = try {
                    textFetcher.fetch("$url.sha1", "maven sha1", 128, token)
                } catch (missing: ArtifactDownloadException) {
                    if (missing.message == "FETCH_HTTP_STATUS_404") {
                        throw ArtifactDownloadException("MAVEN_CHECKSUM_REQUIRED", missing)
                    }
                    throw missing
                }
                val sha1 = legacy.utf8Text.trim().lowercase(Locale.US)
                if (!sha1.matches(Regex("[0-9a-f]{40}"))) {
                    throw ArtifactDownloadException("MAVEN_SHA1_INVALID")
                }
                return Checksum(null, sha1, MavenChecksumKind.REPOSITORY_SHA1)
            }
            throw failure
        }
        return Checksum(validateSha256(sidecar.utf8Text.trim()), null,
            MavenChecksumKind.REPOSITORY_SHA256)
    }

    private fun verifySha1IfNeeded(
        artifact: VerifiedArtifact,
        checksum: Checksum,
        token: CancellationToken
    ) {
        val expected = checksum.sha1 ?: return
        val digest = MessageDigest.getInstance("SHA-1")
        store.openVerified(artifact.uri).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                token.throwIfCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != expected) throw ArtifactDownloadException("MAVEN_SHA1_MISMATCH")
    }

    private fun validateSha256(raw: String): String {
        val digest = raw.lowercase(Locale.US)
        if (!digest.matches(Regex("[0-9a-f]{64}"))) {
            throw ArtifactDownloadException("MAVEN_SHA256_INVALID")
        }
        return digest
    }

    private companion object {
        const val MAX_POM_BYTES = 4L * 1024L * 1024L
    }
}
