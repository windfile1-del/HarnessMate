package dev.wind.agent

import dev.wind.agent.artifact.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object QuarantineDownloadTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-general-download-").toFile()
        try {
            urlAndRepositoryRequests()
            arbitraryBinaryAndLargeDownload(root.toPath())
            repositorySnapshotAndTaskIsolation(root.toPath())
            cancellationAndRedirectAuthorization(root.toPath())
            check(ToolRegistry.spec("download_github_repository")?.let { it.enabled && it.mutating } == true)
            if (args.contains("--live-github")) liveGithub(root.toPath())
            if (args.contains("--live-files")) liveFiles(root.toPath())
            println("QuarantineDownloadTests: OK")
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun urlAndRepositoryRequests() {
        check(QuarantineDownloadPolicy.parseUrl("http://downloads.example:8080/tool.bin").port == 8080)
        check(QuarantineDownloadPolicy.parseUrl("https://8.8.8.8:9443/file.dat").port == 9443)
        check(QuarantineDownloadPolicy.parseUrl("https://files.example/文件.zip").rawPath.contains("%"))
        for (url in listOf("file:///tmp/input", "ftp://files.example/x", "http://user:password@files.example/x", "https://files.example:0/x")) {
            check(runCatching { QuarantineDownloadPolicy.parseUrl(url) }.exceptionOrNull() is ArtifactDownloadException)
        }
        // Keep the existing install/Maven network boundary unchanged.
        check(runCatching { ArtifactNetworkPolicy.parseUrl("http://downloads.example/tool.jar") }.isFailure)
        val proxyAddress = InetAddress.getByName("198.18.0.199")
        check(!PinnedHttpsArtifactTransport().addressAllowed(proxyAddress))
        check(PinnedHttpsArtifactTransport(quarantineOnly = true).addressAllowed(proxyAddress))
        check(PinnedHttpsArtifactTransport(quarantineOnly = true).addressAllowed(InetAddress.getByName("192.168.1.2")))
        check(!PinnedHttpsArtifactTransport(quarantineOnly = true).addressAllowed(InetAddress.getByName("0.0.0.0")))
        (javax.net.ssl.SSLSocketFactory.getDefault().createSocket() as javax.net.ssl.SSLSocket).use { socket ->
            check(PinnedHttpsArtifactTransport().configureHostnameVerification(socket))
            check(socket.sslParameters.endpointIdentificationAlgorithm == "HTTPS")
        }
        val head = GithubRepositoryDownload.parse("https://github.com/octocat/Hello-World.git/")
        check(head.repositoryUrl == "https://github.com/octocat/Hello-World")
        check(head.downloadUrl == "https://codeload.github.com/octocat/Hello-World/zip/HEAD")
        check(GithubRepositoryDownload.parse(head.repositoryUrl, "feature/compiler").downloadUrl.endsWith("feature%2Fcompiler"))
        check(GithubRepositoryDownload.parse(head.repositoryUrl, "v1.0.0").ref == "v1.0.0")
        for (bad in listOf("https://github.com.evil.example/o/r", "https://github.com/o/r/tree/main", "https://github.com/o/r?token=secret")) {
            check(runCatching { GithubRepositoryDownload.parse(bad) }.isFailure)
        }
        for (bad in listOf("", "../main", "a/../b", "a\r\nb", "/main", "a//b")) {
            check(runCatching { GithubRepositoryDownload.parse(head.repositoryUrl, bad) }.isFailure)
        }
    }

    private fun arbitraryBinaryAndLargeDownload(root: Path) {
        val length = 20L * 1024L * 1024L + 13L
        val store = VerifiedArtifactStore(root.resolve("large-store").toFile())
        val quarantine = root.resolve("large-quarantine")
        val progress = ArrayList<Long>()
        val transport = ArtifactHttpTransport { _, _ ->
            var remaining = length
            val body = object : InputStream() {
                override fun read(): Int = if (remaining == 0L) -1 else { remaining--; 0xab }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    if (count == 0) return 0
                    if (remaining == 0L) return -1
                    val read = minOf(count.toLong(), remaining).toInt()
                    java.util.Arrays.fill(buffer, offset, offset + read, 0xab.toByte())
                    remaining -= read
                    return read
                }
            }
            ArtifactHttpResponse(200, length, null, body) { body.close() }
        }
        val downloader = SecureArtifactDownloader(store, quarantine.toFile(), transport,
            parseDownloadUrl = QuarantineDownloadPolicy::parseUrl) { true }
        val artifact = downloader.download("http://downloads.example:8080/anything.unknown", "binary download",
            null, Long.MAX_VALUE, CancellationToken()) { count, total ->
            check(total == length)
            progress.add(count)
        }
        check(artifact.byteSize == length && length > 16L * 1024L * 1024L)
        check(progress.first() == 0L && progress.last() == length && progress.zipWithNext().all { it.first <= it.second })
        store.openVerified(artifact.uri).use { input ->
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                check((0 until read).all { buffer[it] == 0xab.toByte() })
                count += read
            }
            check(count == length)
        }
        check(Files.list(quarantine).use { it.count() } == 0L)
    }

    private fun repositorySnapshotAndTaskIsolation(root: Path) {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("Hello-World-HEAD/README"))
                zip.write("downloaded source, never executed".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val request = GithubRepositoryDownload.parse("https://github.com/octocat/Hello-World")
        val store = VerifiedArtifactStore(root.resolve("repo-store").toFile())
        val access = TaskArtifactAccess(root.resolve("repo-access").toFile())
        val downloader = SecureArtifactDownloader(store, root.resolve("repo-quarantine").toFile(),
            ArtifactHttpTransport { uri, _ ->
                check(uri.toString() == request.downloadUrl)
                ArtifactHttpResponse(200, bytes.size.toLong(), null, ByteArrayInputStream(bytes)) { }
            }, parseDownloadUrl = QuarantineDownloadPolicy::parseUrl) { it.host == "codeload.github.com" }
        val artifact = downloader.download(request.downloadUrl, "repository source", null, Long.MAX_VALUE, CancellationToken())
        access.record("task", "project", 7L, artifact)
        access.requireAccess("task", "project", 7L, artifact.uri)
        check(runCatching { access.requireAccess("other-task", "project", 7L, artifact.uri) }.isFailure)
        check(store.openVerified(artifact.uri).use { it.readBytes() }.contentEquals(bytes))
        check(!root.resolve("Hello-World-HEAD").toFile().exists())
    }

    private fun cancellationAndRedirectAuthorization(root: Path) {
        var contacted = 0
        val store = VerifiedArtifactStore(root.resolve("redirect-store").toFile())
        val denied = SecureArtifactDownloader(store, root.resolve("redirect-quarantine").toFile(),
            ArtifactHttpTransport { _, _ ->
                contacted++
                ArtifactHttpResponse(302, 0L, "http://other.example:8080/file", ByteArrayInputStream(ByteArray(0))) { }
            }, parseDownloadUrl = QuarantineDownloadPolicy::parseUrl) { it.host == "source.example" }
        check(runCatching { denied.download("https://source.example/x", "redirect", null, Long.MAX_VALUE, CancellationToken()) }
            .exceptionOrNull()?.message == "DOWNLOAD_HOP_NOT_AUTHORIZED")
        check(contacted == 1)
        val token = CancellationToken()
        val cancelled = SecureArtifactDownloader(store, root.resolve("cancel-quarantine").toFile(),
            ArtifactHttpTransport { _, _ ->
                ArtifactHttpResponse(200, 3L, null, ByteArrayInputStream(byteArrayOf(1, 2, 3))) { }
            }, parseDownloadUrl = QuarantineDownloadPolicy::parseUrl) { true }
        check(runCatching { cancelled.download("http://source.example/file", "cancel", null, Long.MAX_VALUE, token) { _, _ -> token.cancel() } }
            .exceptionOrNull() is CancellationToken.CancelledException)
        check(Files.list(root.resolve("cancel-quarantine")).use { it.count() } == 0L)
    }

    private fun liveGithub(root: Path) {
        val request = GithubRepositoryDownload.parse("https://github.com/octocat/Hello-World")
        val store = VerifiedArtifactStore(root.resolve("live-store").toFile())
        val artifact = SecureArtifactDownloader.quarantine(store, root.resolve("live-quarantine").toFile()) {
            it.host == "codeload.github.com"
        }.download(request.downloadUrl, "live GitHub source snapshot", null, Long.MAX_VALUE, CancellationToken())
        check(store.openVerified(artifact.uri).use { it.read() == 'P'.code && it.read() == 'K'.code })
        println("Live GitHub source ZIP: ${artifact.uri}, ${artifact.byteSize} bytes")
    }

    private fun liveFiles(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("live-files-store").toFile())
        val downloader = SecureArtifactDownloader.quarantine(store, root.resolve("live-files-quarantine").toFile()) {
            it.host in setOf("raw.githubusercontent.com", "example.com", "www.example.com")
        }
        for (url in listOf("https://raw.githubusercontent.com/octocat/Hello-World/master/README", "http://example.com/")) {
            val artifact = downloader.download(url, "live HTTP/HTTPS file download", null, Long.MAX_VALUE, CancellationToken())
            check(artifact.byteSize > 0L)
            check(store.openVerified(artifact.uri).use { it.readBytes().size.toLong() } == artifact.byteSize)
            println("Live file $url: ${artifact.uri}, ${artifact.byteSize} bytes")
        }
    }
}
