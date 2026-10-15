package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Publishes exact CAS JARs before changing the project's active lockfile. */
class MavenProjectDependencyInstaller(
    projectRoot: File,
    private val cas: VerifiedArtifactStore,
    cacheRoot: File = File(projectRoot, ".vega/maven-cache")
) {
    private val root = projectRoot.absoluteFile
    private val lockFile = MavenDependencyLockFile(root)
    private val cache = cacheRoot.absoluteFile

    fun install(lock: MavenDependencyLock, token: CancellationToken): File {
        materialize(lock, token, verifyModelGraph = lock.modelPoms.isNotEmpty())
        token.throwIfCancelled()
        return lockFile.publish(lock)
    }

    /** Prepare private cache only; a WorkspaceFs transaction owns canonical publication. */
    internal fun materialize(lock: MavenDependencyLock, token: CancellationToken,
        verifyModelGraph: Boolean = true, beforeSideEffect: () -> Unit = {}) {
        if (verifyModelGraph) {
            token.throwIfCancelled()
            val pins = lock.modules.map {
                MavenPomPin(it.coordinate, it.repositoryId, it.pomUri, it.pomSha256, it.pomBytes.toInt())
            } + lock.modelPoms.map { it.pin }
            val source = CasMavenPomSource(cas, pins)
            val graph = MavenCompileGraphPlanner(VerifiedMavenPomSource {
                token.throwIfCancelled()
                source.get(it)
            }).plan(lock.roots)
            require(graph.nodes == lock.modules.map { MavenCompileNode(it.coordinate, it.pomSha256, it.depth) } &&
                graph.supportingPoms == lock.modelPoms.map { MavenModelPom(it.pin.coordinate, it.pin.sha256) }) {
                "MAVEN_LOCK_MODEL_GRAPH_MISMATCH"
            }
        }
        token.throwIfCancelled()
        beforeSideEffect()
        ensureCache()
        lock.modules.forEach { module ->
            token.throwIfCancelled()
            val target = File(cache, "${module.jarSha256}.jar")
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                verifyCached(target, module)
                return@forEach
            }
            beforeSideEffect()
            val staged = Files.createTempFile(cache.toPath(), ".jar-", ".tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                cas.openVerified(module.jarUri).use { input ->
                    FileOutputStream(staged.toFile()).use { output ->
                        require(staged.toFile().setReadOnly()) { "MAVEN_CACHE_READ_ONLY_FAILED" }
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            token.throwIfCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            count += read
                            require(count <= module.jarBytes) { "MAVEN_CACHE_SIZE_MISMATCH" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                require(count == module.jarBytes && digest.hex() == module.jarSha256) {
                    "MAVEN_CACHE_DIGEST_MISMATCH"
                }
                token.throwIfCancelled()
                if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    verifyCached(target, module)
                } else {
                    beforeSideEffect()
                    Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    verifyCached(target, module)
                }
            } finally {
                staged.toFile().setWritable(true)
                Files.deleteIfExists(staged)
            }
        }
        token.throwIfCancelled()
    }

    private fun ensureCache() {
        require(root.canonicalFile == root && root.isDirectory) { "MAVEN_CACHE_ROOT_UNSAFE" }
        val parent = cache.parentFile ?: error("MAVEN_CACHE_PATH_UNSAFE")
        if (parent == File(root, ".vega") && !Files.exists(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(parent.toPath())
        }
        require(parent.canonicalFile == parent &&
            Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            cache.canonicalFile == cache) { "MAVEN_CACHE_PATH_UNSAFE" }
        if (!Files.exists(cache.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(cache.toPath())
        require(Files.isDirectory(cache.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(cache.toPath())) {
            "MAVEN_CACHE_PATH_UNSAFE"
        }
    }

    private fun verifyCached(file: File, module: MavenLockedModule) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            file.canonicalFile == file.absoluteFile && file.length() == module.jarBytes) {
            "MAVEN_CACHE_FILE_UNSAFE"
        }
        val actual = MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
            channel ->
            val input = java.nio.channels.Channels.newInputStream(channel)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) actual.update(buffer, 0, read)
            }
        }
        require(actual.hex() == module.jarSha256) { "MAVEN_CACHE_DIGEST_MISMATCH" }
    }
}

/** Offline classpath source. It never downloads or evaluates project scripts. */
class MavenLockedArtifactStore(
    projectRoot: File,
    cacheRoot: File = File(projectRoot, ".vega/maven-cache")
) : GraphLocalArtifactStore {
    private val root = projectRoot.absoluteFile
    private val lockFile = MavenDependencyLockFile(root)
    private val cache = cacheRoot.absoluteFile

    override val approvedRoots: List<File> get() = listOf(cache)

    override fun resolve(notation: String): LocalArtifact? = null

    override fun resolveGraph(rootNotations: List<String>): List<LocalArtifact>? {
        val lock = lockFile.read() ?: return null
        if (lock.roots.map { it.notation } != rootNotations) return null
        require(Files.isDirectory(cache.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            cache.canonicalFile == cache.absoluteFile) { "MAVEN_CACHE_PATH_UNSAFE" }
        return lock.modules.map { module ->
            val file = File(cache, "${module.jarSha256}.jar")
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.canonicalFile == file.absoluteFile && file.length() == module.jarBytes) {
                "MAVEN_CACHE_FILE_UNSAFE"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
                channel ->
                val input = java.nio.channels.Channels.newInputStream(channel)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            require(digest.hex() == module.jarSha256) { "MAVEN_CACHE_DIGEST_MISMATCH" }
            LocalArtifact(file, ClasspathEntryKind.OFFLINE_CACHE, module.coordinate.notation)
        }
    }
}

private fun MessageDigest.hex(): String = digest().joinToString("") {
    "%02x".format(it.toInt() and 255)
}
