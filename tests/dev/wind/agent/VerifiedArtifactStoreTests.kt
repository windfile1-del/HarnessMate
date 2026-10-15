package dev.wind.agent

import dev.wind.agent.artifact.ArtifactProvenance
import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Focused JVM contract tests for the isolated P7-B local CAS slice. */
object VerifiedArtifactStoreTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of)
            ?: System.getProperty("vega.test.temp")?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        Files.createDirectories(base)
        val fixtureRoot = base.toAbsolutePath().normalize().toRealPath()
        successfulImportAndRead(fixtureRoot)
        acceptsLargeCallerBound(fixtureRoot)
        rejectsDigestAndSizeFailures(fixtureRoot)
        deduplicatesAndKeepsSanitizedProvenance(fixtureRoot)
        rejectsCorruptedObjectsAndUnsafeUris(fixtureRoot)
        rejectsSymlinkedRootsAndObjects(fixtureRoot)
        rollsBackFailedAtomicPublication(fixtureRoot)
        println("VerifiedArtifactStoreTests: OK")
    }

    private fun successfulImportAndRead(base: Path) = withFixture(base, "success") { root ->
        val payload = "verified local artifact\nwith streamed bytes\n".toByteArray()
        val digest = sha256(payload)
        val store = store(root)
        val imported = store.importQuarantined(
            ByteArrayInputStream(payload), digest, payload.size.toLong(),
            ArtifactProvenance("https://user:secret@example.test/release/tool.zip?token=do-not-store")
        )
        check(imported.sha256 == digest)
        check(imported.byteSize == payload.size.toLong())
        check(imported.uri == "artifact://sha256/$digest")
        check(store.openVerified(imported.uri).use { it.readBytes() }.contentEquals(payload))

        val metadata = objectDirectory(root, digest).resolve("metadata.properties")
        val text = String(Files.readAllBytes(metadata), StandardCharsets.UTF_8)
        check("status=VERIFIED" in text) { "metadata does not declare verified state" }
        check("sha256=$digest" in text)
        check("byteSize=${payload.size}" in text)
        check("https\\://example.test" in text) { "expected sanitized origin in metadata: $text" }
        check("secret" !in text && "token" !in text && "release/tool.zip" !in text) {
            "metadata retained URL credentials, query or path"
        }
    }

    private fun acceptsLargeCallerBound(base: Path) = withFixture(base, "large-bound") { root ->
        val payload = "small test bytes with a large caller bound".toByteArray()
        val store = VerifiedArtifactStore(root.toFile())
        val imported = store.importQuarantined(
            ByteArrayInputStream(payload), sha256(payload), Long.MAX_VALUE, provenance()
        )
        check(store.openVerified(imported.uri).use { it.readBytes() }.contentEquals(payload))
    }

    private fun rejectsDigestAndSizeFailures(base: Path) = withFixture(base, "limits") { root ->
        val payload = ByteArray(4097) { (it % 251).toByte() }
        val digest = sha256(payload)
        val store = store(root)
        expectStoreFailure {
            store.importQuarantined(ByteArrayInputStream(payload), "0".repeat(64), payload.size.toLong(), provenance())
        }
        check(!Files.exists(objectDirectory(root, "0".repeat(64)), LinkOption.NOFOLLOW_LINKS))

        expectStoreFailure {
            store.importQuarantined(ByteArrayInputStream(payload), digest, 1024L, provenance())
        }
        check(!Files.exists(objectDirectory(root, digest), LinkOption.NOFOLLOW_LINKS))

        expectStoreFailure {
            store.importQuarantined(ByteArrayInputStream(payload), digest, -1L, provenance())
        }
        expectStoreFailure {
            store.importQuarantined(ByteArrayInputStream(payload), digest, 1024L * 1024L + 1L, provenance())
        }
        check(!Files.exists(objectDirectory(root, digest), LinkOption.NOFOLLOW_LINKS))
    }

    private fun deduplicatesAndKeepsSanitizedProvenance(base: Path) = withFixture(base, "dedupe") { root ->
        val payload = "identical bytes share one CAS object".toByteArray()
        val digest = sha256(payload)
        val store = store(root)
        val first = store.importQuarantined(
            ByteArrayInputStream(payload), digest, payload.size.toLong(),
            ArtifactProvenance(
                "https://one.example/repo/archive?access_token=secret",
                listOf("https://redirect.example/download?sig=private")
            )
        )
        val second = store.importQuarantined(
            ByteArrayInputStream(payload), digest, payload.size.toLong(),
            ArtifactProvenance("https://two.example/path/secret-name?key=private")
        )
        check(first.uri == second.uri)
        check(first.byteSize == second.byteSize)
        val directory = objectDirectory(root, digest)
        check(Files.isDirectory(directory))
        check(Files.list(directory).use { entries -> entries.filter { it.fileName.toString() == "blob" }.count() } == 1L)
        val metadata = String(Files.readAllBytes(directory.resolve("metadata.properties")), StandardCharsets.UTF_8)
        check("https\\://one.example" in metadata)
        check("https\\://redirect.example" in metadata)
        check("https\\://two.example" in metadata)
        check("access_token" !in metadata && "sig" !in metadata && "private" !in metadata && "secret-name" !in metadata)
    }

    private fun rejectsCorruptedObjectsAndUnsafeUris(base: Path) = withFixture(base, "corrupt") { root ->
        val payload = "original immutable bytes".toByteArray()
        val digest = sha256(payload)
        val store = store(root)
        val ref = store.importQuarantined(
            ByteArrayInputStream(payload), digest, payload.size.toLong(), provenance()
        )
        Files.write(objectDirectory(root, digest).resolve("blob"), "mutated".toByteArray())
        expectStoreFailure { store.openVerified(ref.uri) }
        expectStoreFailure {
            store.importQuarantined(ByteArrayInputStream(payload), digest, payload.size.toLong(), provenance())
        }

        listOf(
            "artifact://sha256/../${digest}",
            "artifact://sha256/${digest}?path=../../outside",
            "artifact://sha256/${digest.uppercase()}",
            "file:///tmp/$digest"
        ).forEach { unsafe -> expectStoreFailure { store.openVerified(unsafe) } }
    }

    private fun rejectsSymlinkedRootsAndObjects(base: Path) = withFixture(base, "symlinks") { root ->
        val target = Files.createDirectory(root.resolve("outside"))
        val rootLink = root.resolve("root-link")
        if (tryCreateSymlink(rootLink, target)) {
            expectStoreFailure { store(rootLink.resolve("cas")) }
        }

        val payload = "symlink must never be followed".toByteArray()
        val digest = sha256(payload)
        val store = store(root.resolve("store"))
        val ref = store.importQuarantined(
            ByteArrayInputStream(payload), digest, payload.size.toLong(), provenance()
        )
        val blob = objectDirectory(root.resolve("store"), digest).resolve("blob")
        val outsideBlob = target.resolve("outside-blob")
        Files.write(outsideBlob, payload)
        Files.delete(blob)
        if (tryCreateSymlink(blob, outsideBlob)) {
            expectStoreFailure { store.openVerified(ref.uri) }
            check(Files.readAllBytes(outsideBlob).contentEquals(payload))
        } else {
            // This host does not grant symlink creation; root link case and
            // path parser checks above still run on every platform.
            println("VerifiedArtifactStoreTests: symlink creation unavailable; symlink cases skipped")
        }
    }

    private fun rollsBackFailedAtomicPublication(base: Path) = withFixture(base, "publish-failure") { root ->
        val payload = "must not become visible after failed publish".toByteArray()
        val digest = sha256(payload)
        val store = VerifiedArtifactStore(root.toFile(), 1024L) {
            throw IOException("injected failure before atomic directory rename")
        }
        expectStoreFailure {
            store.importQuarantined(ByteArrayInputStream(payload), digest, payload.size.toLong(), provenance())
        }
        check(!Files.exists(objectDirectory(root, digest), LinkOption.NOFOLLOW_LINKS)) {
            "failed atomic publication exposed a digest directory"
        }
        val shard = root.resolve("objects/sha256/${digest.substring(0, 2)}")
        check(Files.list(shard).use { entries -> entries.findAny().isEmpty }) {
            "failed publication left visible staging data in the shard"
        }
        expectStoreFailure { store.openVerified("artifact://sha256/$digest") }
    }

    private fun tryCreateSymlink(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun store(root: Path): VerifiedArtifactStore = VerifiedArtifactStore(root.toFile(), 1024L * 1024L)

    private fun provenance() = ArtifactProvenance("https://packages.example.test/api/artifact.zip")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun objectDirectory(root: Path, digest: String): Path =
        root.resolve("objects/sha256/${digest.substring(0, 2)}/$digest")

    private fun expectStoreFailure(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure is ArtifactStoreException) {
            "expected fail-closed ArtifactStoreException, got ${failure?.javaClass?.name ?: "success"}: $failure"
        }
    }

    private inline fun withFixture(base: Path, label: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(base, "p7-cas-$label-")
        try {
            block(directory)
        } finally {
            deleteFixture(directory, base)
        }
    }

    private fun deleteFixture(directory: Path, allowedParent: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        check(normalized.startsWith(allowedParent.toAbsolutePath().normalize())) {
            "refusing test cleanup outside its configured D-workspace temp root"
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(normalized, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
