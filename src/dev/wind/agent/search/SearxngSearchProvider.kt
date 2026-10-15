package dev.wind.agent.search

import dev.wind.agent.CancellationToken
import dev.wind.agent.WebHttpTransport
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

class SearxngSearchProvider(private val baseUrlProvider: () -> String) : SearchProvider {

    override val id: String = "searxng"
    override val displayName: String = "SearXNG"

    override fun isConfigured(): Boolean = baseUrlProvider().trim().isNotEmpty()

    override fun search(request: SearchRequest, token: CancellationToken, timeoutMs: Long,
        authorizeHop: (URI) -> Boolean): SearchResponse {
        val start = System.currentTimeMillis()
        var base = baseUrlProvider().trim()
        if (base.isEmpty()) {
            return SearchResponse(id, emptyList(), 0L, "SearXNG base URL is not configured")
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length - 1)
        }
        token.throwIfCancelled()

        var endpoint = base + "/search?q=" + URLEncoder.encode(request.query, "UTF-8") + "&format=json"
        if (request.freshness != null && request.freshness != SearchFreshness.ANY) {
            endpoint += "&time_range=" + when (request.freshness) {
                SearchFreshness.DAY -> "day"
                SearchFreshness.WEEK -> "week"
                SearchFreshness.MONTH -> "month"
                SearchFreshness.YEAR -> "year"
                else -> ""
            }
        }
        if (!request.language.isNullOrBlank()) {
            endpoint += "&language=" + URLEncoder.encode(request.language, "UTF-8")
        }

        try {
            val budget = timeoutMs.coerceIn(1_000L, 60_000L)
            val text = WebHttpTransport().request(endpoint, token, authorizeHop,
                budget, 1024 * 1024, mapOf("Accept" to "application/json")).text
            val json = JSONObject(text)
            val hits = mutableListOf<SearchHit>()
            val array = json.optJSONArray("results")
            if (array != null) {
                for (i in 0 until array.length()) {
                    if (hits.size >= request.limit) break
                    val item = array.optJSONObject(i) ?: continue
                    val title = item.optString("title", "").trim()
                    val link = item.optString("url", "").trim()
                    val desc = item.optString("content", "").trim()
                    if (link.isNotEmpty()) {
                        hits.add(SearchHit(title = if (title.isEmpty()) link else title, url = link, snippet = desc))
                    }
                }
            }
            return SearchResponse(id, hits, System.currentTimeMillis() - start)
        } catch (c: CancellationToken.CancelledException) {
            return SearchResponse(id, emptyList(), System.currentTimeMillis() - start, "CANCELLED")
        } catch (e: Exception) {
            if (token.isCancelled) {
                return SearchResponse(id, emptyList(), System.currentTimeMillis() - start, "CANCELLED")
            }
            return SearchResponse(id, emptyList(), System.currentTimeMillis() - start,
                if (e is org.json.JSONException) "WEB_PROVIDER_RESPONSE_INVALID" else e.message ?: e.javaClass.simpleName)
        }
    }
}
