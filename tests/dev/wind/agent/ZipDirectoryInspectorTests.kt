package dev.wind.agent

import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.ZipDirectoryInspector
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipDirectoryInspectorTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory(
            args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir")),
            "p7-zip-directory-"
        )
        try {
            if (args.size >= 3 && args[1] == "--real") {
                Files.newByteChannel(Path.of(args[2]), setOf(StandardOpenOption.READ)).use { channel ->
                    println("Real ZIP directory: ${ZipDirectoryInspector().inspect(channel)}")
                }
            }
            check(inspect(root, zip()).entryCount == 0)
            val apk = zip("AndroidManifest.xml", "resources.arsc", "classes.dex")
            val summary = inspect(root, apk)
            check(summary.entryCount == 3 && summary.apkCandidate && !summary.jarCandidate)
            check(summary.declaredUncompressedBytes == 3L && summary.maxDepth == 1)
            val jar = inspect(root, zip("META-INF/MANIFEST.MF", "pkg/Main.class"))
            check(jar.jarCandidate && !jar.apkCandidate && jar.maxDepth == 2)

            expectRejected(root, zip("../escape"), "PATH_UNSAFE")
            expectRejected(root, zip("/absolute"), "PATH_UNSAFE")
            expectRejected(root, zip("A.txt", "a.txt"), "DUPLICATE_PATH")
            expectRejected(root, zip("caf\u00e9.txt", "cafe\u0301.txt"), "DUPLICATE_PATH")
            expectRejected(root, zip("a", "a/child"), "PATH_CONFLICT")
            expectRejected(root, zip("CON.txt"), "PATH_UNSAFE")
            expectRejected(root, zip("dir/../escape"), "PATH_UNSAFE")
            val base = zip("safe.txt")
            expectRejected(root, base.copyOf(base.size - 1), "END_RECORD_INVALID")
            expectRejected(root, base.copyOf().also { it[30] = 'X'.code.toByte() }, "LOCAL_HEADER_INVALID")
            expectRejected(root, base.copyOf().also { bytes ->
                val central = signature(bytes, 0x50, 0x4b, 0x01, 0x02)
                bytes[central + 8] = 1
            }, "ENTRY_UNSUPPORTED")
            expectRejected(root, base.copyOf().also { bytes ->
                val central = signature(bytes, 0x50, 0x4b, 0x01, 0x02)
                bytes[central + 4] = 20
                bytes[central + 5] = 3
                bytes[central + 40] = 0xff.toByte()
                bytes[central + 41] = 0xa1.toByte()
            }, "SPECIAL_ENTRY")
            expectRejected(root, base.copyOf().also { bytes ->
                val central = signature(bytes, 0x50, 0x4b, 0x01, 0x02)
                setU32(bytes, central + 20, 1L)
                setU32(bytes, central + 24, 1_001L)
            }, "EXPANSION_LIMIT")
            expectRejected(root, base.copyOf().also { bytes ->
                val end = signature(bytes, 0x50, 0x4b, 0x05, 0x06)
                bytes[end + 8] = 0xff.toByte()
                bytes[end + 9] = 0xff.toByte()
                bytes[end + 10] = 0xff.toByte()
                bytes[end + 11] = 0xff.toByte()
            }, "ZIP64_UNSUPPORTED")
            println("ZipDirectoryInspectorTests: OK")
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

    private fun inspect(root: Path, bytes: ByteArray): dev.wind.agent.artifact.ZipDirectorySummary {
        val file = Files.createTempFile(root, "archive-", ".zip")
        try {
            Files.write(file, bytes)
            Files.newByteChannel(file, setOf(StandardOpenOption.READ)).use { channel ->
                return ZipDirectoryInspector().inspect(channel)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun expectRejected(root: Path, bytes: ByteArray, code: String) {
        val failure = runCatching { inspect(root, bytes) }.exceptionOrNull()
        check(failure is ArtifactStoreException && failure.message == "ARTIFACT_ZIP_$code") {
            "expected ARTIFACT_ZIP_$code, got $failure"
        }
    }

    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { sink ->
        ZipOutputStream(sink).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(1)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun signature(bytes: ByteArray, a: Int, b: Int, c: Int, d: Int): Int =
        (0 until bytes.size - 3).first { offset ->
            (bytes[offset].toInt() and 255) == a && (bytes[offset + 1].toInt() and 255) == b &&
                (bytes[offset + 2].toInt() and 255) == c && (bytes[offset + 3].toInt() and 255) == d
        }

    private fun setU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}
