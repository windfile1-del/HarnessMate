package dev.wind.agent

import dev.wind.agent.artifact.ArtifactDownloadException
import dev.wind.agent.artifact.ArtifactHttpResponse
import dev.wind.agent.artifact.ArtifactHttpTransport
import dev.wind.agent.artifact.AuthorizedTextFetcher
import dev.wind.agent.artifact.PinnedHttpsArtifactTransport
import java.io.ByteArrayInputStream
import java.net.URI

object AuthorizedTextFetcherTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val visited = ArrayList<String>()
        val transport = ArtifactHttpTransport { uri, _ ->
            visited += uri.host
            if (uri.host == "origin.example") response(302, "", "https://metadata.example/pom.xml")
            else response(200, "<project>ok</project>", type = "application/xml; charset=utf-8")
        }
        val allowed = AuthorizedTextFetcher(transport) { uri: URI -> uri.host in setOf("origin.example", "metadata.example") }
        val result = allowed.fetch("https://origin.example/start", "maven metadata", 1024, CancellationToken())
        check(result.finalUrl == "https://metadata.example/pom.xml")
        check(result.utf8Text == "<project>ok</project>" && result.byteSize == 21)
        check(result.sha256.length == 64 && visited == listOf("origin.example", "metadata.example"))

        visited.clear()
        expect("FETCH_HOP_NOT_AUTHORIZED") {
            AuthorizedTextFetcher(transport) { it.host == "origin.example" }
                .fetch("https://origin.example/start", "maven metadata", 1024, CancellationToken())
        }
        check(visited == listOf("origin.example"))
        expect("FETCH_REDIRECT_LOCATION_INVALID") {
            AuthorizedTextFetcher(ArtifactHttpTransport { _, _ -> response(302, "", "http://other.example/a") }) { true }
                .fetch("https://origin.example/a", "test", 1024, CancellationToken())
        }
        expect("DOWNLOAD_DESTINATION_BLOCKED") {
            AuthorizedTextFetcher(ArtifactHttpTransport { _, _ -> response(200, "ok") }) { true }
                .fetch("https://127.0.0.1/a", "test", 1024, CancellationToken())
        }
        expect("FETCH_SIZE_LIMIT") {
            AuthorizedTextFetcher(ArtifactHttpTransport { _, _ -> response(200, "123456") }) { true }
                .fetch("https://origin.example/a", "test", 5, CancellationToken())
        }
        expect("FETCH_MEDIA_TYPE_UNSUPPORTED") {
            AuthorizedTextFetcher(ArtifactHttpTransport { _, _ -> response(200, "PK", type = "application/zip") }) { true }
                .fetch("https://origin.example/a", "test", 1024, CancellationToken())
        }
        expect("FETCH_UTF8_INVALID") {
            AuthorizedTextFetcher(ArtifactHttpTransport { _, _ ->
                ArtifactHttpResponse(200, 1, null, ByteArrayInputStream(byteArrayOf(0xff.toByte())), "text/plain") { }
            }) { true }.fetch("https://origin.example/a", "test", 1024, CancellationToken())
        }
        expect("DOWNLOAD_REQUEST_HEADER_INVALID") {
            PinnedHttpsArtifactTransport(requestHeaders = mapOf("X-Subscription-Token" to "key\r\nHost: other.example"))
        }
        val token = CancellationToken()
        token.cancel()
        check(runCatching {
            allowed.fetch("https://origin.example/start", "test", 1024, token)
        }.exceptionOrNull() is CancellationToken.CancelledException)
        println("AuthorizedTextFetcherTests: OK")
    }

    private fun response(status: Int, body: String, location: String? = null,
        type: String? = "text/plain"): ArtifactHttpResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return ArtifactHttpResponse(status, bytes.size.toLong(), location, ByteArrayInputStream(bytes), type) { }
    }

    private fun expect(code: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is ArtifactDownloadException && failure.message == code) {
            "expected $code, got $failure"
        }
    }
}
