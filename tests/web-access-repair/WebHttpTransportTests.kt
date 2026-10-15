package dev.wind.agent.testing

import dev.wind.agent.CancellationToken
import dev.wind.agent.Web
import dev.wind.agent.WebHttpTransport
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

object WebHttpTransportTests {

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting WebHttpTransportTests...")
        testNormalHttpsHostAndFactory()
        testGetHeadersAndResponse()
        testHeaderValidation()
        testPostHeadersAndBody()
        testPostBodyLimit()
        testRedirectCredentialLeakageProtection()
        testPostRedirectBlocked()
        testSameOriginRedirectSuccess()
        testRedirectLimitsAndMissingLocation()
        testHopAuthorizationFailure()
        testGzipAndDeflateDecoding()
        testUnsupportedEncoding()
        testBodySizeBudget()
        testCharsetHeaderAndMetaDetection()
        testBinaryContentDetection()
        testUnsupportedMediaType()
        testCancellationAndDisconnect()
        testConcurrentCancellations()
        testActualDeadlineTimeout()
        testRequireSafeHttps()
        testLanLiteralOptIn()
        testHttpStatusErrors()
        println("WebHttpTransportTests: ALL PASS")
    }

    private fun testNormalHttpsHostAndFactory() {
        val factoryUris = mutableListOf<URI>()
        val mockConn = MockHttpConnection(
            url = URI("https://normal.example.com/api/search").toURL(),
            responseCodeValue = 200,
            responseBody = "{\"status\":\"ok\"}".toByteArray(Charsets.UTF_8),
            responseHeaders = mapOf("Content-Type" to "application/json")
        )
        val transport = WebHttpTransport(openConnection = { uri ->
            factoryUris.add(uri)
            mockConn
        })

        val reply = transport.request(
            url = "https://normal.example.com/api/search?q=kotlin",
            token = CancellationToken(),
            authorizeHop = { true }
        )

        check(factoryUris.size == 1) { "Expected factory call, got: ${factoryUris.size}" }
        check(factoryUris[0].host == "normal.example.com") { "Host mismatch: ${factoryUris[0].host}" }
        check(reply.text == "{\"status\":\"ok\"}") { "Unexpected reply text: ${reply.text}" }
        check(reply.contentType == "application/json") { "Unexpected content type: ${reply.contentType}" }
        check(reply.byteSize == reply.text.toByteArray(Charsets.UTF_8).size)
        println("  testNormalHttpsHostAndFactory: PASS")
    }

    private fun testGetHeadersAndResponse() {
        var capturedConn: MockHttpConnection? = null
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = "Hello World".toByteArray(Charsets.UTF_8),
                responseHeaders = mapOf("Content-Type" to "text/plain")
            ).also { capturedConn = it }
        })

        val reply = transport.request(
            url = "https://example.com/test",
            token = CancellationToken(),
            authorizeHop = { true },
            headers = mapOf(
                "Accept" to "text/plain",
                "Authorization" to "Bearer test-token",
                "X-Subscription-Token" to "sub-token-123"
            )
        )

        val conn = checkNotNull(capturedConn)
        check(conn.requestMethod == "GET") { "Expected GET, got: ${conn.requestMethod}" }
        check(conn.recordedRequestHeaders["User-Agent"] == Web.UA) { "UA mismatch" }
        check(conn.recordedRequestHeaders["Accept"] == "text/plain") { "Accept header mismatch" }
        check(conn.recordedRequestHeaders["Authorization"] == "Bearer test-token")
        check(conn.recordedRequestHeaders["X-Subscription-Token"] == "sub-token-123")
        check(conn.recordedRequestHeaders["Accept-Encoding"] == "gzip, deflate")
        check(reply.text == "Hello World")
        check(conn.disconnected.get()) { "Connection was not disconnected" }
        println("  testGetHeadersAndResponse: PASS")
    }

    private fun testHeaderValidation() {
        val transport = WebHttpTransport()

        var badHeaderNameThrown = false
        try {
            transport.request(
                url = "https://example.com/test",
                token = CancellationToken(),
                authorizeHop = { true },
                headers = mapOf("Cookie" to "session=123")
            )
        } catch (e: IllegalArgumentException) {
            badHeaderNameThrown = e.message?.contains("WEB_HEADER_INVALID") == true
        }
        check(badHeaderNameThrown) { "Expected WEB_HEADER_INVALID for invalid header name" }

        var badHeaderValueThrown = false
        try {
            transport.request(
                url = "https://example.com/test",
                token = CancellationToken(),
                authorizeHop = { true },
                headers = mapOf("Accept" to "bad\nheader")
            )
        } catch (e: IllegalArgumentException) {
            badHeaderValueThrown = e.message?.contains("WEB_HEADER_INVALID") == true
        }
        check(badHeaderValueThrown) { "Expected WEB_HEADER_INVALID for newline in header value" }
        println("  testHeaderValidation: PASS")
    }

    private fun testPostHeadersAndBody() {
        var capturedConn: MockHttpConnection? = null
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = "{\"received\":true}".toByteArray(Charsets.UTF_8),
                responseHeaders = mapOf("Content-Type" to "application/json")
            ).also { capturedConn = it }
        })

        val payload = "{\"query\":\"test search\"}"
        val reply = transport.request(
            url = "https://api.tavily.com/search",
            token = CancellationToken(),
            authorizeHop = { true },
            headers = mapOf("Accept" to "application/json"),
            jsonBody = payload
        )

        val conn = checkNotNull(capturedConn)
        check(conn.requestMethod == "POST") { "Expected POST, got ${conn.requestMethod}" }
        check(conn.doOutput) { "Expected doOutput == true" }
        check(conn.recordedRequestHeaders["Content-Type"] == "application/json; charset=utf-8")
        val writtenBody = conn.recordedOutputStream.toString("UTF-8")
        check(writtenBody == payload) { "POST payload mismatch: $writtenBody" }
        check(conn.fixedLengthStreaming == payload.toByteArray(Charsets.UTF_8).size)
        check(reply.text == "{\"received\":true}")
        println("  testPostHeadersAndBody: PASS")
    }

    private fun testPostBodyLimit() {
        val transport = WebHttpTransport()
        val hugeBody = "a".repeat(65 * 1024)
        var caught = false
        try {
            transport.request(
                url = "https://api.example.com/post",
                token = CancellationToken(),
                authorizeHop = { true },
                jsonBody = hugeBody
            )
        } catch (e: IllegalArgumentException) {
            caught = e.message?.contains("WEB_REQUEST_TOO_LARGE") == true
        }
        check(caught) { "Expected WEB_REQUEST_TOO_LARGE for body > 64KB" }
        println("  testPostBodyLimit: PASS")
    }

    private fun testRedirectCredentialLeakageProtection() {
        val step = AtomicInteger(0)
        val transport = WebHttpTransport(openConnection = { uri ->
            when (step.getAndIncrement()) {
                0 -> MockHttpConnection(
                    url = uri.toURL(),
                    responseCodeValue = 302,
                    responseHeaders = mapOf("Location" to "https://cross-origin.com/other")
                )
                else -> MockHttpConnection(
                    url = uri.toURL(),
                    responseCodeValue = 200,
                    responseBody = "OK".toByteArray()
                )
            }
        })

        // 1. Authorization header leakage test
        var authLeakageBlocked = false
        try {
            transport.request(
                url = "https://origin.com/api",
                token = CancellationToken(),
                authorizeHop = { true },
                headers = mapOf("Authorization" to "Bearer secret-token")
            )
        } catch (e: SecurityException) {
            authLeakageBlocked = e.message?.contains("WEB_CREDENTIAL_REDIRECT_BLOCKED") == true
        }
        check(authLeakageBlocked) { "Expected cross-origin Authorization redirect to be blocked" }

        // 2. X-Subscription-Token header leakage test
        step.set(0)
        var tokenLeakageBlocked = false
        try {
            transport.request(
                url = "https://origin.com/api",
                token = CancellationToken(),
                authorizeHop = { true },
                headers = mapOf("X-Subscription-Token" to "brave-key-123")
            )
        } catch (e: SecurityException) {
            tokenLeakageBlocked = e.message?.contains("WEB_CREDENTIAL_REDIRECT_BLOCKED") == true
        }
        check(tokenLeakageBlocked) { "Expected cross-origin X-Subscription-Token redirect to be blocked" }
        println("  testRedirectCredentialLeakageProtection: PASS")
    }

    private fun testPostRedirectBlocked() {
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 307,
                responseHeaders = mapOf("Location" to "https://api.tavily.com/search2")
            )
        })

        var postRedirectBlocked = false
        try {
            transport.request(
                url = "https://api.tavily.com/search",
                token = CancellationToken(),
                authorizeHop = { true },
                jsonBody = "{\"q\":\"test\"}"
            )
        } catch (e: SecurityException) {
            postRedirectBlocked = e.message?.contains("WEB_POST_REDIRECT_BLOCKED") == true
        }
        check(postRedirectBlocked) { "Expected WEB_POST_REDIRECT_BLOCKED for POST redirect" }
        println("  testPostRedirectBlocked: PASS")
    }

    private fun testSameOriginRedirectSuccess() {
        val step = AtomicInteger(0)
        val hopsSeen = mutableListOf<String>()
        val transport = WebHttpTransport(openConnection = { uri ->
            when (step.getAndIncrement()) {
                0 -> MockHttpConnection(
                    url = uri.toURL(),
                    responseCodeValue = 301,
                    responseHeaders = mapOf("Location" to "/final-page")
                )
                else -> MockHttpConnection(
                    url = uri.toURL(),
                    responseCodeValue = 200,
                    responseBody = "Redirected Content".toByteArray(Charsets.UTF_8),
                    responseHeaders = mapOf("Content-Type" to "text/plain")
                )
            }
        })

        val reply = transport.request(
            url = "https://example.com/initial",
            token = CancellationToken(),
            authorizeHop = { hop ->
                hopsSeen.add(hop.toString())
                true
            }
        )

        check(hopsSeen == listOf("https://example.com/initial", "https://example.com/final-page")) {
            "Hops mismatch: $hopsSeen"
        }
        check(reply.finalUrl == "https://example.com/final-page")
        check(reply.text == "Redirected Content")
        println("  testSameOriginRedirectSuccess: PASS")
    }

    private fun testRedirectLimitsAndMissingLocation() {
        val loopTransport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 302,
                responseHeaders = mapOf("Location" to "/loop")
            )
        })

        var limitCaught = false
        try {
            loopTransport.request(
                url = "https://example.com/loop",
                token = CancellationToken(),
                authorizeHop = { true }
            )
        } catch (e: IllegalStateException) {
            limitCaught = e.message?.contains("WEB_REDIRECT_LIMIT") == true
        }
        check(limitCaught) { "Expected WEB_REDIRECT_LIMIT after 5 redirects" }

        val missingLocTransport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 302,
                responseHeaders = emptyMap()
            )
        })
        var missingCaught = false
        try {
            missingLocTransport.request(
                url = "https://example.com/test",
                token = CancellationToken(),
                authorizeHop = { true }
            )
        } catch (e: IllegalStateException) {
            missingCaught = e.message?.contains("WEB_REDIRECT_LOCATION_MISSING") == true
        }
        check(missingCaught) { "Expected WEB_REDIRECT_LOCATION_MISSING" }
        println("  testRedirectLimitsAndMissingLocation: PASS")
    }

    private fun testHopAuthorizationFailure() {
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(url = uri.toURL(), responseCodeValue = 200)
        })

        var hopBlocked = false
        try {
            transport.request(
                url = "https://forbidden.example.com/data",
                token = CancellationToken(),
                authorizeHop = { false }
            )
        } catch (e: SecurityException) {
            hopBlocked = e.message?.startsWith("FETCH_HOP_NOT_AUTHORIZED: forbidden.example.com") == true
        }
        check(hopBlocked) { "Expected FETCH_HOP_NOT_AUTHORIZED with host" }
        println("  testHopAuthorizationFailure: PASS")
    }

    private fun testGzipAndDeflateDecoding() {
        val originalText = "Compressed content with repetitive words repetitive words"
        val gzipped = gzip(originalText.toByteArray(Charsets.UTF_8))
        val deflated = deflate(originalText.toByteArray(Charsets.UTF_8))

        val gzipTransport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = gzipped,
                responseHeaders = mapOf(
                    "Content-Type" to "text/plain",
                    "Content-Encoding" to "gzip"
                )
            )
        })
        val gzipReply = gzipTransport.request("https://example.com/gzip", CancellationToken(), { true })
        check(gzipReply.text == originalText) { "Gzip decode mismatch: ${gzipReply.text}" }

        val deflateTransport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = deflated,
                responseHeaders = mapOf(
                    "Content-Type" to "text/plain",
                    "Content-Encoding" to "deflate"
                )
            )
        })
        val deflateReply = deflateTransport.request("https://example.com/deflate", CancellationToken(), { true })
        check(deflateReply.text == originalText) { "Deflate decode mismatch: ${deflateReply.text}" }
        println("  testGzipAndDeflateDecoding: PASS")
    }

    private fun testUnsupportedEncoding() {
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = "data".toByteArray(),
                responseHeaders = mapOf(
                    "Content-Type" to "text/plain",
                    "Content-Encoding" to "br"
                )
            )
        })
        var caught = false
        try {
            transport.request("https://example.com/br", CancellationToken(), { true })
        } catch (e: IllegalStateException) {
            caught = e.message?.contains("WEB_ENCODING_UNSUPPORTED") == true
        }
        check(caught) { "Expected WEB_ENCODING_UNSUPPORTED" }
        println("  testUnsupportedEncoding: PASS")
    }

    private fun testBodySizeBudget() {
        val largeBody = ByteArray(500) { 'x'.code.toByte() }
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = largeBody,
                responseHeaders = mapOf("Content-Type" to "text/plain")
            )
        })

        var sizeExceeded = false
        try {
            transport.request(
                url = "https://example.com/large",
                token = CancellationToken(),
                authorizeHop = { true },
                maxBytes = 200
            )
        } catch (e: IllegalStateException) {
            sizeExceeded = e.message?.contains("WEB_SIZE_LIMIT") == true
        }
        check(sizeExceeded) { "Expected WEB_SIZE_LIMIT when body exceeds maxBytes" }
        println("  testBodySizeBudget: PASS")
    }

    private fun testCharsetHeaderAndMetaDetection() {
        val gbkString = "这是GBK编码中文内容"
        val gbkCharset = Charset.forName("GBK")
        val gbkBytes = gbkString.toByteArray(gbkCharset)

        // 1. From Content-Type header
        val headerTransport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = gbkBytes,
                responseHeaders = mapOf("Content-Type" to "text/html; charset=gbk")
            )
        })
        val headerReply = headerTransport.request("https://example.com/gbk", CancellationToken(), { true })
        check(headerReply.text == gbkString) { "GBK header decode failed: ${headerReply.text}" }

        // 2. From HTML meta charset
        val htmlWithMeta = "<html><head><meta charset=\"gbk\"></head><body>$gbkString</body></html>"
        val htmlBytes = htmlWithMeta.toByteArray(gbkCharset)
        val metaTransport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = htmlBytes,
                responseHeaders = mapOf("Content-Type" to "text/html")
            )
        })
        val metaReply = metaTransport.request("https://example.com/meta", CancellationToken(), { true })
        check(metaReply.text.contains(gbkString)) { "GBK meta tag decode failed: ${metaReply.text}" }
        println("  testCharsetHeaderAndMetaDetection: PASS")
    }

    private fun testBinaryContentDetection() {
        val binaryBytes = byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x00, 0x57, 0x6F) // "Hello\0Wo"
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = binaryBytes,
                responseHeaders = mapOf("Content-Type" to "text/plain")
            )
        })

        var binaryCaught = false
        try {
            transport.request("https://example.com/bin", CancellationToken(), { true })
        } catch (e: IllegalStateException) {
            binaryCaught = e.message?.contains("WEB_BINARY_CONTENT") == true
        }
        check(binaryCaught) { "Expected WEB_BINARY_CONTENT for null byte" }
        println("  testBinaryContentDetection: PASS")
    }

    private fun testUnsupportedMediaType() {
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = "image-bytes".toByteArray(),
                responseHeaders = mapOf("Content-Type" to "image/png")
            )
        })
        var caught = false
        try {
            transport.request("https://example.com/pic.png", CancellationToken(), { true })
        } catch (e: IllegalStateException) {
            caught = e.message?.contains("WEB_MEDIA_TYPE_UNSUPPORTED: image/png") == true
        }
        check(caught) { "Expected WEB_MEDIA_TYPE_UNSUPPORTED" }
        println("  testUnsupportedMediaType: PASS")
    }

    private fun testCancellationAndDisconnect() {
        val token = CancellationToken()
        var connRef: MockHttpConnection? = null
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = "Test".toByteArray(),
                onConnect = {
                    token.cancel()
                }
            ).also { connRef = it }
        })

        var cancelled = false
        try {
            transport.request("https://example.com/cancel", token, { true })
        } catch (_: CancellationToken.CancelledException) {
            cancelled = true
        }
        check(cancelled) { "Expected CancelledException" }
        check(connRef?.disconnected?.get() == true) { "Connection was not disconnected on cancel" }
        println("  testCancellationAndDisconnect: PASS")
    }

    private fun testConcurrentCancellations() {
        val count = 10
        val threads = mutableListOf<Thread>()
        val passedCount = AtomicInteger(0)
        for (i in 0 until count) {
            val token = CancellationToken()
            val transport = WebHttpTransport(openConnection = { uri ->
                MockHttpConnection(
                    url = uri.toURL(),
                    responseCodeValue = 200,
                    responseBody = "concurrent data".toByteArray(),
                    onConnect = { token.cancel() }
                )
            })
            threads += Thread {
                try {
                    transport.request("https://example.com/concurrent-$i", token, { true })
                } catch (_: CancellationToken.CancelledException) {
                    passedCount.incrementAndGet()
                } catch (_: Exception) {
                    if (token.isCancelled) {
                        passedCount.incrementAndGet()
                    }
                }
            }.apply { start() }
        }
        for (t in threads) {
            while (t.isAlive) {
                try {
                    t.join(1000)
                } catch (_: InterruptedException) {
                    Thread.interrupted()
                }
            }
        }
        Thread.interrupted()
        check(passedCount.get() == count) {
            "Expected $count concurrent cancellations, got: ${passedCount.get()}"
        }
        println("  testConcurrentCancellations: PASS")
    }

    private fun testActualDeadlineTimeout() {
        val transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                blockUntilDisconnect = true
            )
        })
        val started = System.currentTimeMillis()
        var timedOut = false
        try {
            transport.request("https://example.com/timeout", CancellationToken(), { true }, timeoutMs = 1000L)
        } catch (e: Exception) {
            timedOut = true
        }
        val elapsed = System.currentTimeMillis() - started
        check(timedOut) { "Expected request to time out when server blocks" }
        check(elapsed in 900..4000) { "Deadline elapsed unexpected time: ${elapsed}ms" }
        println("  testActualDeadlineTimeout: PASS (${elapsed}ms)")
    }

    private fun testRequireSafeHttps() {
        val transport = WebHttpTransport()

        var httpBlocked = false
        try {
            transport.request("http://insecure.example.com", CancellationToken(), { true })
        } catch (e: SecurityException) {
            httpBlocked = true
        }
        check(httpBlocked) { "Expected SecurityException for http://" }

        var metadataBlocked = false
        try {
            transport.request("https://169.254.169.254/latest/meta-data", CancellationToken(), { true })
        } catch (e: SecurityException) {
            metadataBlocked = true
        }
        check(metadataBlocked) { "Expected SecurityException for metadata IP" }
        println("  testRequireSafeHttps: PASS")
    }

    private fun testLanLiteralOptIn() {
        val savedPolicy = dev.wind.agent.NetworkPolicy.allowLocalNetwork
        try {
            dev.wind.agent.NetworkPolicy.allowLocalNetwork = false
            var blocked = false
            try {
                dev.wind.agent.NetworkPolicy.requireSafeHttps("http://192.168.1.100:8080/api")
            } catch (_: SecurityException) {
                blocked = true
            }
            check(blocked) { "Expected LAN HTTP to be blocked when allowLocalNetwork = false" }

            dev.wind.agent.NetworkPolicy.allowLocalNetwork = true
            dev.wind.agent.NetworkPolicy.requireSafeHttps("http://192.168.1.100:8080/api")
            println("  testLanLiteralOptIn: PASS")
        } finally {
            dev.wind.agent.NetworkPolicy.allowLocalNetwork = savedPolicy
        }
    }

    private fun testHttpStatusErrors() {
        fun testCode(code: Int, expectedSnippet: String) {
            val transport = WebHttpTransport(openConnection = { uri ->
                MockHttpConnection(url = uri.toURL(), responseCodeValue = code)
            })
            var caught = false
            try {
                transport.request("https://example.com/status", CancellationToken(), { true })
            } catch (e: IllegalStateException) {
                caught = e.message?.contains(expectedSnippet) == true
            }
            check(caught) { "Expected error for HTTP $code to contain '$expectedSnippet'" }
        }

        testCode(401, "WEB_HTTP_401: access denied")
        testCode(403, "WEB_HTTP_403: access denied")
        testCode(429, "WEB_HTTP_429: provider rate or quota limit")
        testCode(500, "WEB_HTTP_500")
        println("  testHttpStatusErrors: PASS")
    }
}
