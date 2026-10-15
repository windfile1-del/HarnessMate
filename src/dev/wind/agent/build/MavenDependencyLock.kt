package dev.wind.agent.build

import dev.wind.agent.artifact.MavenChecksumKind
import dev.wind.agent.artifact.MavenDownloadedModule
import dev.wind.agent.artifact.MavenDownloadedPom
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

data class MavenLockedModule(
    val coordinate: MavenCoordinate,
    val depth: Int,
    val repositoryId: String,
    val pomSha256: String,
    val pomBytes: Long,
    val jarSha256: String,
    val jarBytes: Long,
    val pomChecksumKind: MavenChecksumKind,
    val jarChecksumKind: MavenChecksumKind,
    val pomUrl: String,
    val jarUrl: String
) {
    init {
        require(depth in 0..4096) { "MAVEN_LOCK_DEPTH_INVALID" }
        require(repositoryId.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "MAVEN_LOCK_REPOSITORY_INVALID" }
        require(listOf(pomSha256, jarSha256).all { it.matches(SHA256) } &&
            pomBytes in 1..4L * 1024L * 1024L &&
            jarBytes in 1..1024L * 1024L * 1024L) { "MAVEN_LOCK_ARTIFACT_INVALID" }
        require(validUrl(pomUrl, ".pom") && validUrl(jarUrl, ".jar") &&
            pomUrl.removeSuffix(".pom") == jarUrl.removeSuffix(".jar")) {
            "MAVEN_LOCK_URL_INVALID"
        }
    }

    val pomUri: String get() = "artifact://sha256/$pomSha256"
    val jarUri: String get() = "artifact://sha256/$jarSha256"

    private fun validUrl(raw: String, extension: String): Boolean = try {
        val uri = URI(raw)
        raw.length <= 4096 && raw.all { it.code in 0x21..0x7e } &&
            uri.scheme == "https" && uri.host != null && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.endsWith(extension)
    } catch (_: Exception) { false }

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}

/** Canonical, fully pinned compile graph. This is data, not install authority. */
data class MavenDependencyLock(
    val roots: List<MavenCoordinate>,
    val modules: List<MavenLockedModule>,
    val modelPoms: List<MavenLockedPom> = emptyList()
) {
    init {
        require(roots.isNotEmpty() && roots.size <= 128 && roots.distinct().size == roots.size) {
            "MAVEN_LOCK_ROOTS_INVALID"
        }
        require(modules.isNotEmpty() && modules.size <= 1024 &&
            modules.map { it.coordinate.module }.distinct().size == modules.size &&
            roots.all { root -> modules.any { it.coordinate == root && it.depth == 0 } }) {
            "MAVEN_LOCK_GRAPH_INVALID"
        }
        require(modules.size + modelPoms.size <= 2048 &&
            modelPoms.map { it.pin.coordinate }.distinct().size == modelPoms.size &&
            modelPoms.none { pom -> modules.any { it.coordinate == pom.pin.coordinate } }) {
            "MAVEN_LOCK_MODEL_INPUTS_INVALID"
        }
    }

    fun encode(): ByteArray {
        val lines = mutableListOf(if (modelPoms.isEmpty()) HEADER else MODEL_HEADER)
        roots.forEach { lines += "root\t${it.notation}" }
        modules.forEach { module ->
            lines += listOf("module", module.coordinate.notation, module.depth.toString(),
                module.repositoryId, module.pomSha256, module.pomBytes.toString(),
                module.jarSha256, module.jarBytes.toString(), module.pomChecksumKind.name,
                module.jarChecksumKind.name, module.pomUrl, module.jarUrl).joinToString("\t")
        }
        modelPoms.forEach { pom ->
            lines += listOf("modelPom", pom.pin.coordinate.notation, pom.pin.repositoryId,
                pom.pin.sha256, pom.pin.byteSize.toString(), pom.checksumKind.name, pom.url).joinToString("\t")
        }
        return (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
    }

    val fingerprint: String get() = sha256(encode())

    companion object {
        private const val HEADER = "vega-maven-lock-v1"
        private const val MODEL_HEADER = "vega-maven-lock-v2"
        private const val MAX_LOCK_BYTES = 1024 * 1024

        fun fromGraph(
            roots: List<MavenCoordinate>,
            graph: MavenCompileGraph,
            downloads: List<MavenDownloadedModule>,
            modelDownloads: List<MavenDownloadedPom> = downloads.flatMap { it.supportingPoms }
        ): MavenDependencyLock {
            require(downloads.size == graph.nodes.size &&
                downloads.map { it.coordinate }.distinct().size == downloads.size) {
                "MAVEN_LOCK_DOWNLOADS_INCOMPLETE"
            }
            val byCoordinate = downloads.associateBy { it.coordinate }
            val modules = graph.nodes.map { node ->
                val downloaded = byCoordinate[node.coordinate]
                    ?: error("MAVEN_LOCK_DOWNLOAD_MISSING:${node.coordinate.notation}")
                require(downloaded.pomPin.coordinate == node.coordinate &&
                    downloaded.pomPin.sha256 == node.pomSha256 &&
                    downloaded.pomPin.artifactUri == "artifact://sha256/${node.pomSha256}" &&
                    downloaded.pomPin.repositoryId == downloaded.repositoryId &&
                    downloaded.jar.uri == "artifact://sha256/${downloaded.jar.sha256}") {
                    "MAVEN_LOCK_IDENTITY_MISMATCH"
                }
                MavenLockedModule(node.coordinate, node.depth, downloaded.repositoryId,
                    node.pomSha256, downloaded.pomPin.byteSize.toLong(),
                    downloaded.jar.sha256, downloaded.jar.byteSize,
                    downloaded.pomChecksumKind, downloaded.jarChecksumKind,
                    downloaded.pomUrl, downloaded.jarUrl)
            }
            val byModel = modelDownloads.groupBy { it.pin.coordinate }
            val models = graph.supportingPoms.map { input ->
                val records = byModel[input.coordinate] ?: error("MAVEN_LOCK_MODEL_INPUT_MISSING:${input.coordinate.notation}")
                require(records.distinct().size == 1) { "MAVEN_LOCK_MODEL_INPUT_CONFLICT" }
                val downloaded = records.first()
                require(downloaded.pin.sha256 == input.sha256) { "MAVEN_LOCK_MODEL_INPUT_MISMATCH" }
                MavenLockedPom(downloaded.pin, downloaded.checksumKind, downloaded.url)
            }
            return MavenDependencyLock(roots, modules, models)
        }

        fun decode(bytes: ByteArray): MavenDependencyLock {
            require(bytes.size in 1..MAX_LOCK_BYTES) { "MAVEN_LOCK_SIZE_INVALID" }
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            require(text.endsWith('\n')) { "MAVEN_LOCK_FORMAT_INVALID" }
            val lines = text.dropLast(1).split('\n')
            require(lines.firstOrNull() in listOf(HEADER, MODEL_HEADER)) { "MAVEN_LOCK_FORMAT_INVALID" }
            val roots = mutableListOf<MavenCoordinate>()
            val modules = mutableListOf<MavenLockedModule>()
            val models = mutableListOf<MavenLockedPom>()
            var inModules = false
            var inModels = false
            lines.drop(1).forEach { line ->
                val fields = line.split('\t')
                when (fields.firstOrNull()) {
                    "root" -> {
                        require(!inModules && fields.size == 2) { "MAVEN_LOCK_FORMAT_INVALID" }
                        roots += MavenCoordinate.parse(fields[1])
                    }
                    "module" -> {
                        require(!inModels && fields.size == 12) { "MAVEN_LOCK_FORMAT_INVALID" }
                        inModules = true
                        modules += MavenLockedModule(MavenCoordinate.parse(fields[1]),
                            fields[2].toInt(), fields[3], fields[4], fields[5].toLong(),
                            fields[6], fields[7].toLong(), MavenChecksumKind.valueOf(fields[8]),
                            MavenChecksumKind.valueOf(fields[9]), fields[10], fields[11])
                    }
                    "modelPom" -> {
                        require(lines.first() == MODEL_HEADER && inModules && fields.size == 7) {
                            "MAVEN_LOCK_FORMAT_INVALID"
                        }
                        inModels = true
                        models += MavenLockedPom(MavenPomPin(MavenCoordinate.parse(fields[1]), fields[2],
                            "artifact://sha256/${fields[3]}", fields[3], fields[4].toInt()),
                            MavenChecksumKind.valueOf(fields[5]), fields[6])
                    }
                    else -> error("MAVEN_LOCK_FORMAT_INVALID")
                }
            }
            val lock = MavenDependencyLock(roots, modules, models)
            require(lock.encode().contentEquals(bytes)) { "MAVEN_LOCK_NONCANONICAL" }
            return lock
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Exact parent/BOM bytes used in resolution; not a classpath or executable artifact. */
data class MavenLockedPom(val pin: MavenPomPin, val checksumKind: MavenChecksumKind, val url: String) {
    init {
        val uri = URI(url)
        val coordinate = pin.coordinate
        val suffix = "/${coordinate.group.replace('.', '/')}/${coordinate.artifact}/${coordinate.version}/" +
            "${coordinate.artifact}-${coordinate.version}.pom"
        require(url.length <= 4096 && url.all { it.code in 0x21..0x7e } && uri.scheme == "https" &&
            uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.endsWith(suffix)) { "MAVEN_LOCK_MODEL_URL_INVALID" }
    }
}

/** Atomic project-local lock publication. The project root is a trusted boundary. */
class MavenDependencyLockFile(projectRoot: File) {
    private val root = projectRoot.absoluteFile
    private val lockDirectory = File(root, ".vega")
    private val lockFile = File(lockDirectory, "maven-dependencies.lock")

    init {
        require(root.canonicalFile == root && root.isDirectory &&
            !Files.isSymbolicLink(root.toPath())) { "MAVEN_LOCK_PROJECT_ROOT_UNSAFE" }
    }

    fun publish(lock: MavenDependencyLock): File {
        ensureDirectory()
        val bytes = lock.encode()
        require(bytes.size in 1..1024 * 1024) { "MAVEN_LOCK_SIZE_INVALID" }
        val target = lockFile.toPath()
        require(!Files.isSymbolicLink(target)) { "MAVEN_LOCK_PATH_UNSAFE" }
        val staged = Files.createTempFile(lockDirectory.toPath(), ".maven-lock-", ".tmp")
        try {
            FileOutputStream(staged.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return lockFile
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    fun read(): MavenDependencyLock? {
        if (!Files.exists(lockDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isDirectory(lockDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "MAVEN_LOCK_PATH_UNSAFE"
        }
        if (!Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val target = lockFile.toPath()
        require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "MAVEN_LOCK_PATH_UNSAFE" }
        val bytes = Files.newByteChannel(target, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() in 1..1024L * 1024L) { "MAVEN_LOCK_SIZE_INVALID" }
            val input = java.nio.channels.Channels.newInputStream(channel)
            val buffer = ByteArray(channel.size().toInt() + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read > 0) count += read
            }
            require(count in 1..1024 * 1024) { "MAVEN_LOCK_SIZE_INVALID" }
            buffer.copyOf(count)
        }
        return MavenDependencyLock.decode(bytes)
    }

    private fun ensureDirectory() {
        val path = lockDirectory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)) { "MAVEN_LOCK_PATH_UNSAFE" }
    }
}
