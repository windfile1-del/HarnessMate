package dev.wind.agent

import dev.wind.agent.artifact.ArtifactProvenance
import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.SafeArtifactZipStager
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SafeArtifactZipStagerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-stage-test-")
        try {
            val bytes = zip()
            val store = VerifiedArtifactStore(root.resolve("cas").toFile(), 1024 * 1024)
            val artifact = store.importQuarantined(ByteArrayInputStream(bytes), sha(bytes), bytes.size.toLong(),
                ArtifactProvenance("https://example.invalid/test.zip"))
            val access = TaskArtifactAccess(root.resolve("access").toFile())
            val stageRoot = Files.createDirectory(root.resolve("stage"))
            val stager = SafeArtifactZipStager(store, access, stageRoot)
            expect("ARTIFACT_TASK_ACCESS_DENIED") { stager.stage("task", "project", 1, artifact.uri) }
            access.record("task", "project", 1, artifact)
            val staged = stager.stage("task", "project", 1, artifact.uri)
            check(Files.readString(staged.resolve("dir/stored.txt")) == "stored bytes")
            check(Files.readString(staged.resolve("dir/deflated.txt")) == "deflated bytes".repeat(50))
            check(Files.isDirectory(staged.resolve("dir")))
            expect("ARTIFACT_STAGE_CANCELLED") {
                stager.stage("task", "project", 1, artifact.uri) { true }
            }
            var checks = 0
            expect("ARTIFACT_STAGE_CANCELLED") {
                stager.stage("task", "project", 1, artifact.uri) { ++checks >= 4 }
            }
            check(Files.list(stageRoot).use { it.count() } == 1L)
            val corrupted = bytes.copyOf()
            val marker = "stored bytes".toByteArray()
            val index = corrupted.indices.firstOrNull { offset ->
                offset + marker.size <= corrupted.size && marker.indices.all { corrupted[offset + it] == marker[it] }
            } ?: error("test fixture missing stored payload")
            corrupted[index] = 'X'.code.toByte()
            val damaged = store.importQuarantined(ByteArrayInputStream(corrupted), sha(corrupted),
                corrupted.size.toLong(), ArtifactProvenance("https://example.invalid/damaged.zip"))
            access.record("task", "project", 1, damaged)
            expect("ARTIFACT_STAGE_CRC_MISMATCH") {
                stager.stage("task", "project", 1, damaged.uri)
            }
            check(Files.list(stageRoot).use { it.count() } == 1L)
            println("SafeArtifactZipStagerTests: OK")
        } finally {
            Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile && !Files.isSymbolicLink(file)) file.toFile().setWritable(true)
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

    private fun zip(): ByteArray = ByteArrayOutputStream().also { sink ->
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry("dir/").apply {
                method = ZipEntry.STORED
                size = 0
                compressedSize = 0
                crc = 0
            })
            zip.closeEntry()
            val stored = "stored bytes".toByteArray()
            zip.putNextEntry(ZipEntry("dir/stored.txt").apply {
                method = ZipEntry.STORED
                size = stored.size.toLong()
                compressedSize = size
                crc = CRC32().also { it.update(stored) }.value
            })
            zip.write(stored)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("dir/deflated.txt"))
            zip.write("deflated bytes".repeat(50).toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expect(message: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is ArtifactStoreException && failure.message == message) {
            "expected $message, got $failure"
        }
    }
}
