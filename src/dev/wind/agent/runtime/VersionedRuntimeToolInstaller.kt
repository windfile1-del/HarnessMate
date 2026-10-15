package dev.wind.agent.runtime

import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.SafeArtifactZipStager
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** App-authored install intent. A caller-provided digest alone is NOT publisher trust. */
internal data class RuntimeToolInstallSpec(
    val toolId: RuntimeToolId,
    val providerId: String,
    val providerVersion: String,
    val capabilityVersion: String,
    val licenseId: String,
    val minApi: Int,
    val maxApi: Int,
    val abi: String,
    val entry: String,
    val artifactSha256: String
) {
    init {
        require(toolId != RuntimeToolId.SHELL)
        require(providerId.matches(Regex("[a-z][a-z0-9.-]{0,63}")))
        require(providerVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")))
        require(capabilityVersion.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")))
        require(licenseId.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+() -]{0,127}")))
        require(minApi in 23..99 && maxApi in minApi..99)
        require(abi == "any" || abi.matches(Regex("[a-z0-9_-]{2,32}")))
        require(entry.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) && entry != "." && entry != "..")
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun fields(): Map<String, String> = linkedMapOf(
        "schema" to "1", "tool_id" to toolId.name, "provider_id" to providerId,
        "provider_version" to providerVersion, "capability_version" to capabilityVersion,
        "license_id" to licenseId, "min_api" to minApi.toString(), "max_api" to maxApi.toString(),
        "abi" to abi, "entry" to entry
    )
}

internal data class ActiveRuntimeTool(val spec: RuntimeToolInstallSpec, val directory: Path)

/**
 * Atomic version-pointer publication boundary for an already trust-authorized tool package.
 * This class is deliberately not wired to an agent tool or the production resolver yet.
 */
internal class VersionedRuntimeToolInstaller(
    rootDirectory: Path,
    store: VerifiedArtifactStore,
    access: TaskArtifactAccess,
    private val deviceApi: Int,
    private val deviceAbi: String,
    private val stageProbe: (Path, RuntimeToolInstallSpec) -> Boolean,
    private val activeProbe: (Path, RuntimeToolInstallSpec) -> Boolean
) {
    private val root = rootDirectory.toAbsolutePath().normalize()
    private val stager: SafeArtifactZipStager

    init {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root) ||
            root.toRealPath() != root) throw ArtifactStoreException("RUNTIME_INSTALL_ROOT_UNSAFE")
        stager = SafeArtifactZipStager(store, access, ensureDirectory(root.resolve(".staging")))
    }

    fun install(taskId: String, projectId: String, generation: Long, artifactUri: String,
        spec: RuntimeToolInstallSpec, cancelled: () -> Boolean = { false }): ActiveRuntimeTool = synchronized(LOCK) {
        checkCancelled(cancelled)
        if (deviceApi !in spec.minApi..spec.maxApi || spec.abi != "any" && spec.abi != deviceAbi) {
            throw ArtifactStoreException("RUNTIME_INSTALL_INCOMPATIBLE")
        }
        if (artifactUri != "artifact://sha256/${spec.artifactSha256}") {
            throw ArtifactStoreException("RUNTIME_INSTALL_ARTIFACT_MISMATCH")
        }
        val toolRoot = ensureDirectory(root.resolve(spec.toolId.name.lowercase()))
        val previous = activeAt(toolRoot)
        val priorRecord = previous?.let { readPointer(toolRoot)?.current }
        val staged = try {
            stager.stage(taskId, projectId, generation, artifactUri, cancelled)
        } catch (failure: ArtifactStoreException) {
            if (failure.message == "ARTIFACT_STAGE_CANCELLED") {
                throw ArtifactStoreException("RUNTIME_INSTALL_CANCELLED", failure)
            }
            throw failure
        }
        var destination: Path? = null
        var published = false
        var switched = false
        try {
            checkCancelled(cancelled)
            verifyPackageManifest(staged, spec)
            requireEntry(staged, spec)
            if (!safeProbe(stageProbe, staged, spec)) throw ArtifactStoreException("RUNTIME_INSTALL_PROBE_FAILED")
            checkCancelled(cancelled)
            val metadata = spec.fields() + ("artifact_sha256" to spec.artifactSha256)
            writeNewFile(staged.resolve(INSTALL_METADATA), encode(metadata))
            val treeHash = treeSha256(staged, cancelled)
            val versionRoot = ensureDirectory(toolRoot.resolve("versions").resolve(spec.providerVersion))
            val target = versionRoot.resolve(spec.artifactSha256)
            destination = target
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!validVersion(target, spec.artifactSha256, treeHash)) {
                    throw ArtifactStoreException("RUNTIME_INSTALL_EXISTING_VERSION_INVALID")
                }
            } else {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
                published = true
            }
            checkCancelled(cancelled)
            val next = Pointer(spec.providerVersion, spec.artifactSha256, treeHash, priorRecord)
            writePointer(toolRoot, next)
            switched = true
            checkCancelled(cancelled)
            if (!safeProbe(activeProbe, target, spec)) {
                throw ArtifactStoreException("RUNTIME_INSTALL_ACTIVE_PROBE_FAILED")
            }
            checkCancelled(cancelled)
            ActiveRuntimeTool(spec, target)
        } catch (failure: Exception) {
            if (switched) restorePointer(toolRoot, priorRecord)
            if (published && destination != null && !isCurrent(toolRoot, destination)) {
                deleteTree(destination)
            }
            throw failure
        } finally {
            if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) deleteTree(staged)
        }
    }

    /** Re-validates the installed tree and probe on every resolution; never equates pointer with availability. */
    fun active(toolId: RuntimeToolId): ActiveRuntimeTool? = synchronized(LOCK) {
        val toolRoot = root.resolve(toolId.name.lowercase())
        if (!Files.isDirectory(toolRoot, LinkOption.NOFOLLOW_LINKS)) return@synchronized null
        activeAt(toolRoot)
    }

    private fun activeAt(toolRoot: Path): ActiveRuntimeTool? {
        val pointer = readPointer(toolRoot) ?: return null
        val current = resolveVersion(toolRoot, pointer.current)
        if (current != null) return current
        val previous = pointer.previous?.let { resolveVersion(toolRoot, it) }
        restorePointer(toolRoot, pointer.previous.takeIf { previous != null })
        return previous
    }

    private fun resolveVersion(toolRoot: Path, record: VersionRecord): ActiveRuntimeTool? {
        return try {
            val directory = toolRoot.resolve("versions").resolve(record.version).resolve(record.digest)
            if (!validVersion(directory, record.digest, record.treeSha256)) return null
            val metadata = parseFields(readSmall(directory.resolve(INSTALL_METADATA)))
            val spec = specFrom(metadata)
            if (spec.toolId.name.lowercase() != toolRoot.fileName.toString() ||
                spec.providerVersion != record.version || spec.artifactSha256 != record.digest ||
                deviceApi !in spec.minApi..spec.maxApi || spec.abi != "any" && spec.abi != deviceAbi ||
                !safeProbe(activeProbe, directory, spec)) null
            else ActiveRuntimeTool(spec, directory)
        } catch (_: Exception) { null }
    }

    private fun validVersion(directory: Path, digest: String, tree: String): Boolean = try {
        Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory) &&
            treeSha256(directory) == tree &&
            parseFields(readSmall(directory.resolve(INSTALL_METADATA)))["artifact_sha256"] == digest
    } catch (_: Exception) { false }

    private fun verifyPackageManifest(directory: Path, spec: RuntimeToolInstallSpec) {
        val manifest = directory.resolve(PACKAGE_MANIFEST)
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest) ||
            parseFields(readSmall(manifest)) != spec.fields()) {
            throw ArtifactStoreException("RUNTIME_INSTALL_MANIFEST_MISMATCH")
        }
    }

    private fun requireEntry(directory: Path, spec: RuntimeToolInstallSpec) {
        val file = directory.resolve(spec.entry)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) ||
            Files.size(file) == 0L || file.toFile().canWrite()) {
            throw ArtifactStoreException("RUNTIME_INSTALL_ENTRY_INVALID")
        }
    }

    private fun specFrom(fields: Map<String, String>): RuntimeToolInstallSpec {
        val spec = RuntimeToolInstallSpec(RuntimeToolId.valueOf(fields.getValue("tool_id")),
            fields.getValue("provider_id"), fields.getValue("provider_version"),
            fields.getValue("capability_version"), fields.getValue("license_id"),
            fields.getValue("min_api").toInt(), fields.getValue("max_api").toInt(),
            fields.getValue("abi"), fields.getValue("entry"), fields.getValue("artifact_sha256"))
        if (fields != spec.fields() + ("artifact_sha256" to spec.artifactSha256)) {
            throw ArtifactStoreException("RUNTIME_INSTALL_METADATA_INVALID")
        }
        return spec
    }

    private fun safeProbe(probe: (Path, RuntimeToolInstallSpec) -> Boolean, directory: Path,
        spec: RuntimeToolInstallSpec): Boolean = try { probe(directory, spec) } catch (_: Throwable) { false }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw ArtifactStoreException("RUNTIME_INSTALL_CANCELLED")
    }

    private fun treeSha256(directory: Path, cancelled: () -> Boolean = { false }): String {
        val paths = ArrayList<Path>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) throw ArtifactStoreException("RUNTIME_INSTALL_PATH_UNSAFE")
                paths.add(dir)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) {
                    throw ArtifactStoreException("RUNTIME_INSTALL_PATH_UNSAFE")
                }
                paths.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        if (paths.size > 20_000) throw ArtifactStoreException("RUNTIME_INSTALL_ENTRY_LIMIT")
        val hash = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        for (path in paths.filter { it != directory }.sortedBy { directory.relativize(it).toString() }) {
            checkCancelled(cancelled)
            val relative = directory.relativize(path).toString().replace('\\', '/')
            hash.update((if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) 0x44 else 0x46).toByte())
            hash.update(relative.toByteArray(Charsets.UTF_8))
            hash.update(0.toByte())
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_TREE_BYTES) throw ArtifactStoreException("RUNTIME_INSTALL_SIZE_LIMIT")
                        checkCancelled(cancelled)
                        hash.update(buffer, 0, count)
                    }
                }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun readPointer(toolRoot: Path): Pointer? {
        val file = toolRoot.resolve(ACTIVE_POINTER)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw ArtifactStoreException("RUNTIME_INSTALL_POINTER_UNSAFE")
        }
        val fields = parseFields(readSmall(file))
        val current = VersionRecord(fields.getValue("version"), fields.getValue("digest"),
            fields.getValue("tree"))
        val previous = if (fields.getValue("previous_version").isEmpty()) null else VersionRecord(
            fields.getValue("previous_version"), fields.getValue("previous_digest"),
            fields.getValue("previous_tree"))
        if (fields.keys != POINTER_KEYS || previous == null &&
            (fields.getValue("previous_digest").isNotEmpty() || fields.getValue("previous_tree").isNotEmpty())) {
            throw ArtifactStoreException("RUNTIME_INSTALL_POINTER_INVALID")
        }
        return Pointer(current.version, current.digest, current.treeSha256, previous)
    }

    private fun writePointer(toolRoot: Path, pointer: Pointer) {
        val previous = pointer.previous
        atomicWrite(toolRoot.resolve(ACTIVE_POINTER), encode(linkedMapOf(
            "version" to pointer.current.version, "digest" to pointer.current.digest,
            "tree" to pointer.current.treeSha256,
            "previous_version" to (previous?.version ?: ""),
            "previous_digest" to (previous?.digest ?: ""),
            "previous_tree" to (previous?.treeSha256 ?: "")
        )))
    }

    private fun restorePointer(toolRoot: Path, previous: VersionRecord?) {
        if (previous == null) Files.deleteIfExists(toolRoot.resolve(ACTIVE_POINTER))
        else writePointer(toolRoot, Pointer(previous.version, previous.digest, previous.treeSha256, null))
    }

    private fun isCurrent(toolRoot: Path, directory: Path): Boolean {
        return try {
            val current = readPointer(toolRoot)?.current ?: return false
            toolRoot.resolve("versions").resolve(current.version).resolve(current.digest) == directory
        } catch (_: Exception) { false }
    }

    private fun ensureDirectory(directory: Path): Path {
        if (!directory.startsWith(root)) throw ArtifactStoreException("RUNTIME_INSTALL_PATH_UNSAFE")
        var current = root
        for (segment in root.relativize(directory)) {
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                throw ArtifactStoreException("RUNTIME_INSTALL_PATH_UNSAFE")
            }
        }
        return directory
    }

    private fun writeNewFile(file: Path, bytes: ByteArray) {
        Files.createFile(file)
        java.io.FileOutputStream(file.toFile()).use { output ->
            if (!file.toFile().setReadOnly()) throw ArtifactStoreException("RUNTIME_INSTALL_READ_ONLY_FAILED")
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun atomicWrite(file: Path, bytes: ByteArray) {
        val staged = Files.createTempFile(file.parent, ".pointer-", ".tmp")
        try {
            java.io.FileOutputStream(staged.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staged) }
    }

    private fun parseFields(bytes: ByteArray): Map<String, String> {
        if (bytes.size > 8192 || bytes.any { it.toInt() !in 0x0a..0x0a && it.toInt() !in 0x20..0x7e }) {
            throw ArtifactStoreException("RUNTIME_INSTALL_MANIFEST_INVALID")
        }
        val fields = LinkedHashMap<String, String>()
        for (line in String(bytes, Charsets.US_ASCII).split('\n')) {
            if (line.isEmpty()) continue
            val separator = line.indexOf('=')
            if (separator <= 0 || !line.substring(0, separator).matches(Regex("[a-z][a-z0-9_]*")) ||
                fields.put(line.substring(0, separator), line.substring(separator + 1)) != null) {
                throw ArtifactStoreException("RUNTIME_INSTALL_MANIFEST_INVALID")
            }
        }
        return fields
    }

    private fun readSmall(file: Path): ByteArray {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) ||
            Files.size(file) > 8192L) throw ArtifactStoreException("RUNTIME_INSTALL_MANIFEST_INVALID")
        return Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(8193)
            var size = 0
            while (size < bytes.size) {
                val read = input.read(bytes, size, bytes.size - size)
                if (read < 0) break
                size += read
            }
            if (size > 8192) throw ArtifactStoreException("RUNTIME_INSTALL_MANIFEST_INVALID")
            bytes.copyOf(size)
        }
    }

    private fun encode(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString("", transform = { "${it.key}=${it.value}\n" }).toByteArray(Charsets.US_ASCII)

    private fun deleteTree(directory: Path) {
        if (!directory.startsWith(root) || directory == root) throw ArtifactStoreException("RUNTIME_INSTALL_PATH_UNSAFE")
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

    private data class VersionRecord(val version: String, val digest: String, val treeSha256: String) {
        init {
            if (!version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) ||
                !digest.matches(Regex("[0-9a-f]{64}")) || !treeSha256.matches(Regex("[0-9a-f]{64}"))) {
                throw ArtifactStoreException("RUNTIME_INSTALL_POINTER_INVALID")
            }
        }
    }
    private data class Pointer(val version: String, val digest: String, val treeSha256: String,
        val previous: VersionRecord?) {
        val current = VersionRecord(version, digest, treeSha256)
    }

    private companion object {
        val LOCK = Any()
        const val PACKAGE_MANIFEST = "vega-tool.properties"
        const val INSTALL_METADATA = "vega-install.properties"
        const val ACTIVE_POINTER = "active.properties"
        const val MAX_TREE_BYTES = 4L * 1024L * 1024L * 1024L + 1024L * 1024L
        val POINTER_KEYS = setOf("version", "digest", "tree", "previous_version",
            "previous_digest", "previous_tree")
    }
}
