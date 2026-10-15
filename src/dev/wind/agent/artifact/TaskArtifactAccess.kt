package dev.wind.agent.artifact

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Durable, task-scoped read access to objects in the shared artifact CAS. */
class TaskArtifactAccess(rootDirectory: File) {
    private val root: Path = rootDirectory.toPath().toAbsolutePath().normalize()

    init { ensureDirectory(root) }

    fun record(taskId: String, projectId: String, projectGeneration: Long, artifact: VerifiedArtifact) {
        val target = receipt(taskId, projectId, projectGeneration, artifact.uri)
        if (artifact.sha256 != artifact.uri.substringAfterLast('/')) {
            throw ArtifactStoreException("ARTIFACT_IDENTITY_MISMATCH")
        }
        try {
            ensureDirectory(target.parent)
            val staged = Files.createTempFile(target.parent, ".grant-", ".tmp")
            try {
                val content = receiptContent(taskId, projectId, projectGeneration, artifact.sha256)
                FileOutputStream(staged.toFile()).use { output ->
                    output.write(content)
                    output.fd.sync()
                }
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staged)
            }
        } catch (failure: Exception) {
            if (failure is ArtifactStoreException) throw failure
            throw ArtifactStoreException("ARTIFACT_ACCESS_RECORD_FAILED", failure)
       }
   }

    fun requireAccess(taskId: String, projectId: String, projectGeneration: Long, artifactUri: String) {
        val target = receipt(taskId, projectId, projectGeneration, artifactUri)
        try {
            ensureSafeExistingParents(target.parent)
            if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED")
            }
            val digest = artifactUri.substringAfterLast('/')
            val actual = Files.newByteChannel(target, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() > 256L) throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED")
                val buffer = ByteBuffer.allocate(channel.size().toInt())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED")
                }
                buffer.array()
            }
            if (!actual.contentEquals(receiptContent(taskId, projectId, projectGeneration, digest))) {
                throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED")
            }
        } catch (failure: Exception) {
            if (failure is ArtifactStoreException) throw failure
            throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED", failure)
        }
    }

   fun listArtifacts(taskId: String): List<String> {
        if (taskId.isBlank() || taskId == "default-task") return emptyList()
        val taskHex = hex(taskId)
        val result = mutableSetOf<String>()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        try {
            Files.list(root).use { scopes ->
                scopes.forEach { scopePath ->
                    if (Files.isDirectory(scopePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(scopePath)) {
                        val taskPath = scopePath.resolve(taskHex)
                        if (Files.isDirectory(taskPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(taskPath)) {
                            Files.list(taskPath).use { grants ->
                                grants.forEach { grantFile ->
                                    if (Files.isRegularFile(grantFile, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(grantFile)) {
                                        val name = grantFile.fileName.toString()
                                        if (name.endsWith(".grant")) {
                                            val digest = name.removeSuffix(".grant")
                                            if (digest.matches(Regex("[0-9a-f]{64}"))) {
                                                result.add("artifact://sha256/$digest")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return result.sorted()
    }

    fun hasAccess(taskId: String, artifactUri: String): Boolean {
        if (taskId.isBlank() || taskId == "default-task") return false
        val digest = ARTIFACT_URI.matchEntire(artifactUri)?.groupValues?.get(1) ?: return false
        val taskHex = hex(taskId)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false
        try {
            Files.list(root).use { scopes ->
                val found = scopes.anyMatch { scopePath ->
                    if (Files.isDirectory(scopePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(scopePath)) {
                        val taskPath = scopePath.resolve(taskHex)
                        val grantFile = taskPath.resolve("$digest.grant")
                        Files.isRegularFile(grantFile, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(grantFile)
                    } else false
                }
                if (found) return true
            }
        } catch (_: Exception) { }
        return false
    }

    fun revokeAccess(taskId: String, artifactUri: String): Boolean {
        if (taskId.isBlank() || taskId == "default-task") return false
        val digest = ARTIFACT_URI.matchEntire(artifactUri)?.groupValues?.get(1) ?: return false
        val taskHex = hex(taskId)
        var revoked = false
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false
        try {
            Files.list(root).use { scopes ->
                scopes.forEach { scopePath ->
                    if (Files.isDirectory(scopePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(scopePath)) {
                        val taskPath = scopePath.resolve(taskHex)
                        val grantFile = taskPath.resolve("$digest.grant")
                        if (Files.exists(grantFile, LinkOption.NOFOLLOW_LINKS)) {
                            try {
                                if (Files.deleteIfExists(grantFile)) revoked = true
                            } catch (_: Exception) { }
                        }
                        try {
                            Files.list(taskPath).use { if (!it.findAny().isPresent) Files.deleteIfExists(taskPath) }
                        } catch (_: Exception) { }
                    }
                }
            }
        } catch (_: Exception) { }
        return revoked
    }

    fun countActiveGrants(artifactUri: String): Int {
        val digest = ARTIFACT_URI.matchEntire(artifactUri)?.groupValues?.get(1) ?: return 0
        var count = 0
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0
        try {
            Files.list(root).use { scopes ->
                scopes.forEach { scopePath ->
                    if (Files.isDirectory(scopePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(scopePath)) {
                        Files.list(scopePath).use { tasks ->
                            tasks.forEach { taskPath ->
                                if (Files.isDirectory(taskPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(taskPath)) {
                                    val grantFile = taskPath.resolve("$digest.grant")
                                    if (Files.isRegularFile(grantFile, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(grantFile)) {
                                        count++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return count
    }

    fun revokeAllForTask(taskId: String): List<String> {
        val artifacts = listArtifacts(taskId)
        artifacts.forEach { revokeAccess(taskId, it) }
        return artifacts
    }

    private fun receipt(taskId: String, projectId: String, generation: Long, uri: String): Path {
        if (taskId.isBlank() || taskId == "default-task" || taskId.length > 256 ||
            taskId.any { it < ' ' } || projectId.length > 256 || projectId.any { it < ' ' } ||
            generation < 0L
        ) throw ArtifactStoreException("ARTIFACT_TASK_CONTEXT_INVALID")
        val digest = ARTIFACT_URI.matchEntire(uri)?.groupValues?.get(1)
            ?: throw ArtifactStoreException("ARTIFACT_URI_INVALID")
        val scope = hex("$projectId\n$generation")
        val task = hex(taskId)
        return root.resolve(scope).resolve(task).resolve("$digest.grant")
    }

    private fun receiptContent(taskId: String, projectId: String, generation: Long, digest: String): ByteArray =
        "vega-p7-task-artifact-v1\n${hex(taskId)}\n${hex("$projectId\n$generation")}\n$digest\n"
            .toByteArray(StandardCharsets.US_ASCII)

    private fun ensureDirectory(directory: Path) {
        var current = directory.root ?: throw ArtifactStoreException("ARTIFACT_ACCESS_ROOT_INVALID")
        for (segment in directory) {
            current = current.resolve(segment)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(current) }
                catch (_: java.nio.file.FileAlreadyExistsException) { }
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw ArtifactStoreException("ARTIFACT_ACCESS_PATH_UNSAFE")
            }
        }
    }

    private fun ensureSafeExistingParents(directory: Path) {
        if (!directory.startsWith(root)) throw ArtifactStoreException("ARTIFACT_ACCESS_PATH_UNSAFE")
        var current = root
        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED")
        }
        for (segment in root.relativize(directory)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw ArtifactStoreException("ARTIFACT_TASK_ACCESS_DENIED")
            }
        }
    }

    private fun hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        val ARTIFACT_URI = Regex("artifact://sha256/([0-9a-f]{64})")
    }
}
