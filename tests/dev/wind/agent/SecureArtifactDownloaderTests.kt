package dev.wind.agent

import dev.wind.agent.artifact.ArtifactDownloadException
import dev.wind.agent.artifact.ArtifactHttpResponse
import dev.wind.agent.artifact.ArtifactHttpTransport
import dev.wind.agent.artifact.ArtifactNetworkPolicy
import dev.wind.agent.artifact.PinnedHttpsArtifactTransport
import dev.wind.agent.artifact.SecureArtifactDownloader
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

object SecureArtifactDownloaderTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of)
            ?: System.getProperty("vega.test.temp")?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-download-")
        try {
            storesOnlyVerifiedBytes(root)
            retriesOnlyTransientServerFailures(root)
            rejectsUnauthorizedRedirect(root)
            rejectsOversizeAndWrongDigest(root)
            acceptsExplicitLargerLimitAndGuardsStorage(root)
            cancellationCleansQuarantine(root)
            rejectsUnsafeDestinationsBeforeConnect()
            parsesHttpResponseFraming()
            rejectsMalformedHttpResponseFraming()
            if (args.contains("--live")) liveHttpsSmoke(root)
            println("SecureArtifactDownloaderTests: OK")
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

    private fun storesOnlyVerifiedBytes(root: Path) {
        val bytes = "P7 bounded artifact".toByteArray()
        val digest = sha256(bytes)
        val checked = ArrayList<String>()
        val store = VerifiedArtifactStore(root.resolve("store1").toFile(), 4096)
        val transport = ArtifactHttpTransport { uri, _ ->
            when (uri.host) {
                "source.example" -> response(302, ByteArray(0), "https://cdn.example/tool.jar")
                "cdn.example" -> response(200, bytes)
                else -> error("unexpected host")
            }
        }
        val downloader = SecureArtifactDownloader(store, root.resolve("quarantine1").toFile(), transport) {
            checked.add(it.host)
            true
        }
        val artifact = downloader.download(
            "https://source.example/artifacts/tool.jar", "test tool", digest, 4096, CancellationToken()
        )
        check(artifact.uri == "artifact://sha256/$digest")
        check(checked == listOf("source.example", "cdn.example"))
        check(store.openVerified(artifact.uri).use { it.readBytes() }.contentEquals(bytes))
        check(Files.list(root.resolve("quarantine1")).use { it.count() } == 0L)
    }

    private fun retriesOnlyTransientServerFailures(root: Path) {
        val bytes = "release archive".toByteArray()
        var attempts = 0
        var closed = 0
        val store = VerifiedArtifactStore(root.resolve("retry-store").toFile(), 4096)
        val downloader = SecureArtifactDownloader(store, root.resolve("retry-quarantine").toFile(),
            ArtifactHttpTransport { _, _ ->
                attempts++
                val status = if (attempts < 3) 500 else 200
                ArtifactHttpResponse(status, bytes.size.toLong(), null, ByteArrayInputStream(bytes)) { closed++ }
            }) { true }
        val result = downloader.download("https://source.example/x", "retry test", sha256(bytes), 4096, CancellationToken())
        check(attempts == 3 && closed == 3)
        check(store.openVerified(result.uri).use { it.readBytes() }.contentEquals(bytes))
        attempts = 0
        val alwaysFail = SecureArtifactDownloader(store, root.resolve("retry-fail").toFile(),
            ArtifactHttpTransport { _, _ -> attempts++; response(503, ByteArray(0)) }) { true }
        expectFailure("DOWNLOAD_HTTP_STATUS_503") {
            alwaysFail.download("https://source.example/x", "retry test", null, 4096, CancellationToken())
        }
        check(attempts == 3 && Files.list(root.resolve("retry-fail")).use { it.count() } == 0L)
        attempts = 0
        val noRetry = SecureArtifactDownloader(store, root.resolve("retry-404").toFile(),
            ArtifactHttpTransport { _, _ -> attempts++; response(404, ByteArray(0)) }) { true }
        expectFailure("DOWNLOAD_HTTP_STATUS_404") {
            noRetry.download("https://source.example/x", "retry test", null, 4096, CancellationToken())
        }
        check(attempts == 1)
        val token = CancellationToken()
        attempts = 0
        val cancelled = SecureArtifactDownloader(store, root.resolve("retry-cancel").toFile(),
            ArtifactHttpTransport { _, _ -> attempts++; token.cancel(); response(500, ByteArray(0)) }) { true }
        check(runCatching { cancelled.download("https://source.example/x", "retry test", null, 4096, token) }.isFailure)
        check(attempts == 1)
    }

    private fun rejectsUnauthorizedRedirect(root: Path) {
        var fetchedRedirect = false
        val transport = ArtifactHttpTransport { uri, _ ->
            if (uri.host == "unapproved.example") fetchedRedirect = true
            response(302, ByteArray(0), "https://unapproved.example/private")
        }
        val downloader = SecureArtifactDownloader(
            VerifiedArtifactStore(root.resolve("store2").toFile(), 4096),
            root.resolve("quarantine2").toFile(), transport
        ) { it.host == "source.example" }
        expectFailure("DOWNLOAD_HOP_NOT_AUTHORIZED") {
            downloader.download("https://source.example/x", "test", null, 4096, CancellationToken())
        }
        check(!fetchedRedirect)
    }

    private fun rejectsOversizeAndWrongDigest(root: Path) {
        val bytes = ByteArray(128) { it.toByte() }
        val store = VerifiedArtifactStore(root.resolve("store3").toFile(), 4096)
        val quarantine = root.resolve("quarantine3")
        val downloader = SecureArtifactDownloader(store, quarantine.toFile(),
            ArtifactHttpTransport { _, _ -> response(200, bytes) }) { true }
        expectFailure("DOWNLOAD_SIZE_LIMIT") {
            downloader.download("https://source.example/x", "test", null, 64, CancellationToken())
        }
        val unknownLength = SecureArtifactDownloader(store, quarantine.toFile(),
            ArtifactHttpTransport { _, _ ->
                ArtifactHttpResponse(200, null, null, ByteArrayInputStream(bytes)) { }
            }) { true }
        expectFailure("DOWNLOAD_SIZE_LIMIT") {
            unknownLength.download("https://source.example/x", "test", null, 64, CancellationToken())
        }
        expectFailure("DOWNLOAD_SHA256_MISMATCH") {
            downloader.download("https://source.example/x", "test", "0".repeat(64), 4096, CancellationToken())
        }
        check(Files.list(quarantine).use { it.count() } == 0L)
        val wrong = root.resolve("store3/objects/sha256/00/${"0".repeat(64)}")
        check(!Files.exists(wrong, LinkOption.NOFOLLOW_LINKS))
    }

    private fun acceptsExplicitLargerLimitAndGuardsStorage(root: Path) {
        val bytes = ByteArray(128) { it.toByte() }
        val reserve = 256L * 1024L * 1024L
        val store = VerifiedArtifactStore(root.resolve("large-limit-store").toFile())
        val quarantine = root.resolve("large-limit-quarantine")
        val transport = ArtifactHttpTransport { _, _ -> response(200, bytes) }
        val downloader = SecureArtifactDownloader(store, quarantine.toFile(), transport) { true }
        val artifact = downloader.download(
            "https://source.example/package", "large package", sha256(bytes),
            65L * 1024L * 1024L, CancellationToken()
        )
        check(store.openVerified(artifact.uri).use { it.readBytes() }.contentEquals(bytes))
        val unboundedRequest = downloader.download(
            "https://source.example/package", "caller supplied large bound", sha256(bytes),
            Long.MAX_VALUE, CancellationToken()
        )
        check(unboundedRequest == artifact)
        expectFailure("DOWNLOAD_LIMIT_INVALID") {
            downloader.download("https://source.example/package", "invalid", null, 0L, CancellationToken())
        }

        val implausibleLength = SecureArtifactDownloader(
            store, quarantine.toFile(),
            ArtifactHttpTransport { _, _ ->
                ArtifactHttpResponse(200, Long.MAX_VALUE, null, ByteArrayInputStream(bytes)) { }
            }
        ) { true }
        expectFailure("DOWNLOAD_STORAGE_LIMIT") {
            implausibleLength.download(
                "https://source.example/package", "overflow-safe space check", null,
                Long.MAX_VALUE, CancellationToken()
            )
        }

        val noPreflightSpace = SecureArtifactDownloader(
            store, quarantine.toFile(), transport, { reserve + bytes.size * 2L - 1L }
        ) { true }
        expectFailure("DOWNLOAD_STORAGE_LIMIT") {
            noPreflightSpace.download(
                "https://source.example/package", "low storage", null, 4096, CancellationToken()
            )
        }

        val unknownLength = ArtifactHttpTransport { _, _ ->
            ArtifactHttpResponse(200, null, null, object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, minOf(length, 64))
            }) { }
        }
        var spaceChecks = 0
        val spaceDropsDuringStream = SecureArtifactDownloader(
            store, quarantine.toFile(), unknownLength,
            { if (++spaceChecks == 1) Long.MAX_VALUE else reserve + 1L }
        ) { true }
        expectFailure("DOWNLOAD_STORAGE_LIMIT") {
            spaceDropsDuringStream.download(
                "https://source.example/package", "low storage", null, 4096, CancellationToken()
            )
        }
        check(Files.list(quarantine).use { it.count() } == 0L)
    }

    private fun cancellationCleansQuarantine(root: Path) {
        val bytes = ByteArray(128) { it.toByte() }
        val token = CancellationToken()
        val quarantine = root.resolve("cancel-quarantine")
        val store = VerifiedArtifactStore(root.resolve("cancel-store").toFile(), 4096)
        val transport = ArtifactHttpTransport { _, _ ->
            ArtifactHttpResponse(200, null, null, object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val read = super.read(buffer, offset, minOf(length, 64))
                    if (read > 0) token.cancel()
                    return read
                }
            }) { }
        }
        val failure = runCatching {
            SecureArtifactDownloader(store, quarantine.toFile(), transport) { true }
                .download("https://source.example/package", "cancel test", null, 4096, token)
        }.exceptionOrNull()
        check(failure is CancellationToken.CancelledException)
        check(Files.list(quarantine).use { it.count() } == 0L)
    }

    private fun rejectsUnsafeDestinationsBeforeConnect() {
        listOf(
            "http://example.com/x", "https://127.0.0.1/x", "https://metadata.google.internal/x",
            "https://user:password@example.com/x", "https://localhost/x", "https://example.com:8443/x"
        ).forEach { url -> expectFailure { ArtifactNetworkPolicy.parseUrl(url) } }
        val transport = PinnedHttpsArtifactTransport { arrayOf(InetAddress.getByName("127.0.0.1")) }
        expectFailure("DOWNLOAD_DNS_ADDRESS_BLOCKED") {
            transport.get(URI("https://public.example/tool.jar"), CancellationToken())
        }
        check(!ArtifactNetworkPolicy.isPublic(InetAddress.getByName("10.2.3.4")))
        check(!ArtifactNetworkPolicy.isPublic(InetAddress.getByName("169.254.169.254")))
        check(ArtifactNetworkPolicy.isPublic(InetAddress.getByName("8.8.8.8")))
    }

    private fun parsesHttpResponseFraming() {
        val parser = PinnedHttpsArtifactTransport()
        var closed = false
        parser.parseResponse(wire("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcEXTRA")) {
            closed = true
        }.use { response ->
            check(response.status == 200 && response.contentLength == 3L)
            check(response.body.readBytes().contentEquals("abc".toByteArray()))
            check(response.body.read(ByteArray(1), 0, 0) == 0)
        }
        check(closed)

        parser.parseResponse(wire(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "4\r\nWiki\r\n5\r\npedia\r\n0\r\nTrailer: value\r\n\r\n"
        )) { }.use { response ->
            check(response.contentLength == null)
            check(response.body.readBytes().contentEquals("Wikipedia".toByteArray()))
        }
    }

    private fun rejectsMalformedHttpResponseFraming() {
        val parser = PinnedHttpsArtifactTransport()
        expectFailure("DOWNLOAD_TRANSFER_UNSUPPORTED") {
            parser.parseResponse(wire(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n"
            )) { }
        }
        expectFailure("DOWNLOAD_HTTP_HEADER_INVALID") {
            parser.parseResponse(wire(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"
            )) { }
        }
        expectFailure("DOWNLOAD_ENCODING_UNSUPPORTED") {
            parser.parseResponse(wire("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n")) { }
        }
        check(runCatching {
            parser.parseResponse(wire("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nab")) { }
                .use { it.body.readBytes() }
        }.exceptionOrNull() is IOException)
        check(runCatching {
            parser.parseResponse(wire(
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\naXX"
            )) { }.use { it.body.readBytes() }
        }.exceptionOrNull() is IOException)
    }

    private fun wire(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.ISO_8859_1))

    private fun liveHttpsSmoke(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("live-store").toFile(), 64 * 1024)
        val artifact = SecureArtifactDownloader(store, root.resolve("live-quarantine").toFile()) {
            it.host == "repo.maven.apache.org"
        }.download(
            "https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.9.20/kotlin-stdlib-1.9.20.pom",
            "HTTPS transport smoke", null, 64 * 1024, CancellationToken()
        )
        val bytes = store.openVerified(artifact.uri).use { it.readBytes() }
        check(bytes.isNotEmpty() && bytes.size.toLong() == artifact.byteSize)
        println("P7 live HTTPS smoke: ${artifact.uri}, ${artifact.byteSize} bytes")
    }

    private fun response(status: Int, bytes: ByteArray, location: String? = null) =
        ArtifactHttpResponse(status, bytes.size.toLong(), location, ByteArrayInputStream(bytes)) { }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expectFailure(message: String? = null, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is ArtifactDownloadException && (message == null || failure.message == message)) {
            "expected $message, received $failure"
        }
    }
}
