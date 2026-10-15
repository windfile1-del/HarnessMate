package dev.wind.agent.search

import dev.wind.agent.CancellationToken
import dev.wind.agent.WebHttpTransport
import java.net.URI
import org.json.JSONObject

/** Native Android adapter for Tavily's documented search API; no Node runtime dependency. */
class TavilySearchProvider internal constructor(private val apiKeyProvider: () -> String,
    private val transport: WebHttpTransport) : SearchProvider {
    constructor(apiKeyProvider: () -> String) : this(apiKeyProvider, WebHttpTransport())
    override val id = "tavily"
    override val displayName = "Tavily"
    override fun isConfigured() = apiKeyProvider().isNotBlank()

    override fun search(request: SearchRequest, token: CancellationToken, timeoutMs: Long,
        authorizeHop: (URI) -> Boolean): SearchResponse {
        val started = System.currentTimeMillis()
        val key = apiKeyProvider().trim()
        if (key.isEmpty()) return SearchResponse(id, emptyList(), 0L, "WEB_PROVIDER_CONFIGURED_UNAVAILABLE: Tavily API key is not configured")
        return try {
            val body = JSONObject().put("query", request.query).put("max_results", request.limit.coerceIn(1, 20))
                .put("search_depth", "basic").put("include_answer", false).put("include_raw_content", false)
            request.freshness?.takeIf { it != SearchFreshness.ANY }?.let { body.put("time_range", it.key) }
            request.language?.takeIf { it.isNotBlank() }?.let { body.put("language", it) }
            val text = transport.request("https://api.tavily.com/search", token, authorizeHop,
                timeoutMs.coerceIn(1_000L, 60_000L), 1024 * 1024,
                mapOf("Accept" to "application/json", "Authorization" to "Bearer $key"), body.toString()).text
            val array = JSONObject(text).optJSONArray("results")
                ?: return SearchResponse(id, emptyList(), System.currentTimeMillis() - started, "WEB_PROVIDER_RESPONSE_INVALID")
            val hits = ArrayList<SearchHit>()
            for (i in 0 until minOf(array.length(), request.limit)) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url", "").trim()
                if (url.isNotEmpty()) hits += SearchHit(item.optString("title", url), url, item.optString("content", ""))
            }
            SearchResponse(id, hits, System.currentTimeMillis() - started)
        } catch (_: CancellationToken.CancelledException) {
            SearchResponse(id, emptyList(), System.currentTimeMillis() - started, "CANCELLED")
        } catch (failure: Exception) {
            // Server bodies are never echoed: they may contain credentials/query payloads.
            SearchResponse(id, emptyList(), System.currentTimeMillis() - started,
                if (token.isCancelled) "CANCELLED" else if (failure is org.json.JSONException)
                    "WEB_PROVIDER_RESPONSE_INVALID" else failure.message ?: failure.javaClass.simpleName)
        }
    }
}
