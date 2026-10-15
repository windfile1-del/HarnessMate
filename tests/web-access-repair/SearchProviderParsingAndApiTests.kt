package dev.wind.agent.testing

import dev.wind.agent.CancellationToken
import dev.wind.agent.WebHttpTransport
import dev.wind.agent.search.DuckDuckGoLiteProvider
import dev.wind.agent.search.SearchRequest
import dev.wind.agent.search.TavilySearchProvider
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

object SearchProviderParsingAndApiTests {

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting SearchProviderParsingAndApiTests...")
        testTavilyFakeApiSuccess()
        testTavilyErrorNoBodyLeakage()
        testDdgParseLiteHtmlOrderIndependence()
        testDdgParseLiteHtmlMissingSnippetDoesNotStealNext()
        testDdgParseLiteHtmlAmpersandInUddg()
        println("SearchProviderParsingAndApiTests: ALL PASS")
    }

    private fun testTavilyFakeApiSuccess() {
        val apiKey = "tvly-secret-key-987654"
        val capturedUri = AtomicReference<URI>()
        val capturedConn = AtomicReference<MockHttpConnection>()

        val fakeTransport = WebHttpTransport(openConnection = { uri ->
            capturedUri.set(uri)
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = """
                {
                    "results": [
                        {
                            "title": "Kotlin Lang",
                            "url": "https://kotlinlang.org",
                            "content": "Official Kotlin site."
                        },
                        {
                            "title": "Kotlin GitHub",
                            "url": "https://github.com/JetBrains/kotlin",
                            "content": "Source code."
                        }
                    ]
                }
                """.trimIndent().toByteArray(Charsets.UTF_8),
                responseHeaders = mapOf("Content-Type" to "application/json")
            ).also { capturedConn.set(it) }
        })

        val provider = TavilySearchProvider({ apiKey }, fakeTransport)
        check(provider.isConfigured()) { "Provider should be configured" }

        val response = provider.search(
            request = SearchRequest("kotlin testing", 5),
            token = CancellationToken(),
            timeoutMs = 5000L,
            authorizeHop = { true }
        )

        val uri = checkNotNull(capturedUri.get())
        val conn = checkNotNull(capturedConn.get())

        // 1. Verify URL has NO API key
        check(!uri.toString().contains(apiKey)) { "API key leaked in URL: $uri" }

        // 2. Verify Authorization header has Bearer key only
        val authHeader = conn.recordedRequestHeaders["Authorization"]
        check(authHeader == "Bearer $apiKey") { "Authorization header mismatch: $authHeader" }
        check(conn.recordedRequestHeaders["Accept"] == "application/json")

        // 3. Verify POST body parameters and NO key in body
        val postBodyStr = conn.recordedOutputStream.toString("UTF-8")
        check(!postBodyStr.contains(apiKey)) { "API key leaked in POST JSON body: $postBodyStr" }
        val json = JSONObject(postBodyStr)
        check(json.getString("query") == "kotlin testing")
        check(json.getInt("max_results") == 5)
        check(json.getString("search_depth") == "basic")
        check(!json.getBoolean("include_answer"))
        check(!json.getBoolean("include_raw_content"))

        // 4. Verify parsed results
        check(response.error == null) { "Expected no error, got: ${response.error}" }
        check(response.results.size == 2) { "Expected 2 hits, got: ${response.results.size}" }
        check(response.results[0].title == "Kotlin Lang")
        check(response.results[0].url == "https://kotlinlang.org")
        check(response.results[0].snippet == "Official Kotlin site.")
        check(response.results[1].title == "Kotlin GitHub")
        println("  testTavilyFakeApiSuccess: PASS")
    }

    private fun testTavilyErrorNoBodyLeakage() {
        val apiKey = "tvly-secret-key-error-test"
        val sensitiveLeak = "sensitive-server-leak-body-should-never-be-exposed"

        // 1. Test 401 error
        var transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 401,
                responseBody = """{"error": "$sensitiveLeak"}""".toByteArray(Charsets.UTF_8),
                responseHeaders = mapOf("Content-Type" to "application/json")
            )
        })
        var provider = TavilySearchProvider({ apiKey }, transport)
        var resp = provider.search(SearchRequest("test", 1), CancellationToken(), 3000L, { true })
        check(resp.results.isEmpty())
        check(resp.error != null && resp.error!!.contains("401")) { "Expected 401 error: ${resp.error}" }
        check(!resp.error!!.contains(sensitiveLeak)) { "Server body leaked in 401 error: ${resp.error}" }
        check(!resp.error!!.contains(apiKey)) { "API key leaked in 401 error: ${resp.error}" }

        // 2. Test 429 error
        transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 429,
                responseBody = """{"error": "$sensitiveLeak", "rate_limit": 0}""".toByteArray(Charsets.UTF_8),
                responseHeaders = mapOf("Content-Type" to "application/json")
            )
        })
        provider = TavilySearchProvider({ apiKey }, transport)
        resp = provider.search(SearchRequest("test", 1), CancellationToken(), 3000L, { true })
        check(resp.results.isEmpty())
        check(resp.error != null && resp.error!!.contains("429")) { "Expected 429 error: ${resp.error}" }
        check(!resp.error!!.contains(sensitiveLeak)) { "Server body leaked in 429 error: ${resp.error}" }

        // 3. Test malformed JSON error
        transport = WebHttpTransport(openConnection = { uri ->
            MockHttpConnection(
                url = uri.toURL(),
                responseCodeValue = 200,
                responseBody = "<html><body>$sensitiveLeak</body></html>".toByteArray(Charsets.UTF_8),
                responseHeaders = mapOf("Content-Type" to "application/json")
            )
        })
        provider = TavilySearchProvider({ apiKey }, transport)
        resp = provider.search(SearchRequest("test", 1), CancellationToken(), 3000L, { true })
        check(resp.results.isEmpty())
        check(resp.error == "WEB_PROVIDER_RESPONSE_INVALID") {
            "Expected WEB_PROVIDER_RESPONSE_INVALID on malformed JSON, got: ${resp.error}"
        }
        check(!resp.error!!.contains(sensitiveLeak)) { "Server body leaked in malformed JSON error" }
        println("  testTavilyErrorNoBodyLeakage: PASS")
    }

    private fun testDdgParseLiteHtmlOrderIndependence() {
        val provider = DuckDuckGoLiteProvider()

        // HTML with mixed attribute orders:
        // Result 1: class before href
        // Result 2: href before class
        val html = """
        <html><body>
        <table>
            <tr>
                <td>
                    <a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=1">Kotlin Official</a>
                </td>
            </tr>
            <tr>
                <td class="result-snippet">Official site for the Kotlin language.</td>
            </tr>
            <tr>
                <td>
                    <a href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2FJetBrains%2Fkotlin&amp;rut=2" class="result-link">Kotlin GitHub</a>
                </td>
            </tr>
            <tr>
                <td class="result-snippet">GitHub repository for JetBrains/kotlin.</td>
            </tr>
        </table>
        </body></html>
        """.trimIndent()

        val hits = provider.parseLiteHtml(html, 10)
        check(hits.size == 2) { "Expected 2 hits, got: ${hits.size}" }
        check(hits[0].title == "Kotlin Official")
        check(hits[0].url == "https://kotlinlang.org/")
        check(hits[0].snippet == "Official site for the Kotlin language.")
        check(hits[1].title == "Kotlin GitHub")
        check(hits[1].url == "https://github.com/JetBrains/kotlin")
        check(hits[1].snippet == "GitHub repository for JetBrains/kotlin.")
        println("  testDdgParseLiteHtmlOrderIndependence: PASS")
    }

    private fun testDdgParseLiteHtmlMissingSnippetDoesNotStealNext() {
        val provider = DuckDuckGoLiteProvider()

        // Result 1 has NO snippet td. Result 2 has a snippet td.
        // Result 1 must have snippet "", Result 2 must have its own snippet.
        val html = """
        <html><body>
        <table>
            <tr>
                <td>
                    <a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsite1.com">Site 1 Without Snippet</a>
                </td>
            </tr>
            <tr>
                <td>
                    <a class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fsite2.com">Site 2 With Snippet</a>
                </td>
            </tr>
            <tr>
                <td class="result-snippet">This snippet belongs strictly to Site 2.</td>
            </tr>
        </table>
        </body></html>
        """.trimIndent()

        val hits = provider.parseLiteHtml(html, 10)
        check(hits.size == 2) { "Expected 2 hits, got: ${hits.size}" }
        check(hits[0].title == "Site 1 Without Snippet")
        check(hits[0].url == "https://site1.com")
        check(hits[0].snippet == "") { "Result 1 stole snippet: '${hits[0].snippet}'" }

        check(hits[1].title == "Site 2 With Snippet")
        check(hits[1].url == "https://site2.com")
        check(hits[1].snippet == "This snippet belongs strictly to Site 2.")
        println("  testDdgParseLiteHtmlMissingSnippetDoesNotStealNext: PASS")
    }

    private fun testDdgParseLiteHtmlAmpersandInUddg() {
        val provider = DuckDuckGoLiteProvider()
        val html = """
        <a class="result-link" href="//duckduckgo.com/l/?foo=bar&amp;uddg=https%3A%2F%2Fexample.com%2Fpath%3Fa%3D1%26b%3D2&amp;baz=qux">Example With Params</a>
        <td class="result-snippet">Snippet text.</td>
        """.trimIndent()

        val hits = provider.parseLiteHtml(html, 10)
        check(hits.size == 1) { "Expected 1 hit, got ${hits.size}" }
        check(hits[0].url == "https://example.com/path?a=1&b=2") {
            "Decoded URL mismatch: ${hits[0].url}"
        }
        println("  testDdgParseLiteHtmlAmpersandInUddg: PASS")
    }
}
