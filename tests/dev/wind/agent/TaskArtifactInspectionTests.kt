package dev.wind.agent

import dev.wind.agent.artifact.ArtifactInspector
import dev.wind.agent.artifact.ArtifactHttpResponse
import dev.wind.agent.artifact.ArtifactHttpTransport
import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.SecureArtifactDownloader
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TaskArtifactInspectionTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-inspect-")
        try {
            taskScopeAndCorruption(root)
            println("TaskArtifactInspectionTests: OK")
        } finally {
            Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun taskScopeAndCorruption(root: Path) {
        val bytes = ByteArrayOutputStream().also { sink ->
            ZipOutputStream(sink).use { zip ->
                zip.putNextEntry(ZipEntry("content.txt"))
                zip.write("task-owned".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val store = VerifiedArtifactStore(root.resolve("cas").toFile(), 4096)
        val transport = ArtifactHttpTransport { _, _ ->
            ArtifactHttpResponse(200, bytes.size.toLong(), null, ByteArrayInputStream(bytes)) { }
        }
        val artifact = SecureArtifactDownloader(store, root.resolve("quarantine").toFile(), transport) { true }
            .download("https://source.example/archive.zip", "inspect test", digest, 4096, CancellationToken())
        val accessRoot = root.resolve("access")
        val access = TaskArtifactAccess(accessRoot.toFile())
        val inspector = ArtifactInspector(store, access)
        expectDenied { inspector.inspect("task-a", "project-a", 7L, artifact.uri) }
        access.record("task-a", "project-a", 7L, artifact)
        val reopened = ArtifactInspector(store, TaskArtifactAccess(accessRoot.toFile()))
        val result = reopened.inspect("task-a", "project-a", 7L, artifact.uri)
        check(result.artifact.byteSize == bytes.size.toLong())
        check(result.artifact.sha256 == digest && result.observedFormat == "ZIP_CONTAINER")
        check(result.zipDirectory?.entryCount == 1 && result.zipDirectory.maxDepth == 1)
        check(!result.archiveInspected && !result.trustedPublisher && !result.installable)
        expectDenied { reopened.inspect("task-b", "project-a", 7L, artifact.uri) }
        expectDenied { reopened.inspect("task-a", "project-b", 7L, artifact.uri) }
        expectDenied { reopened.inspect("task-a", "project-a", 8L, artifact.uri) }
        expectDenied { reopened.inspect("default-task", "project-a", 7L, artifact.uri) }

        val grant = Files.walk(accessRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".grant") }.findFirst().orElseThrow()
        }
        Files.write(grant, "corrupt".toByteArray())
        expectDenied { reopened.inspect("task-a", "project-a", 7L, artifact.uri) }
        access.record("task-a", "project-a", 7L, artifact)
        val blob = Files.walk(root.resolve("cas")).use { paths ->
            paths.filter { it.fileName.toString() == "blob" }.findFirst().orElseThrow()
        }
        Files.write(blob, byteArrayOf(0, 1, 2, 3))
        expectDenied { reopened.inspect("task-a", "project-a", 7L, artifact.uri) }
    }

    private fun expectDenied(block: () -> Unit) {
        check(runCatching(block).exceptionOrNull() is ArtifactStoreException)
    }
}
