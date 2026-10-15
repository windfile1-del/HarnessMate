package dev.wind.agent.search

import dev.wind.agent.CancellationToken
import dev.wind.agent.WebHttpTransport
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

class BraveSearchProvider(private val apiKeyProvider: () -> String) : SearchProvider {

    override val id: String = "brave"
    override val displayName: String = "Brave Search API"

    override fun isConfigured(): Boolean = apiKeyProvider().isNotBlank()

    override fun search(request: SearchRequest, token: CancellationToken, timeoutMs: Long,
        authorizeHop: (URI) -> Boolean): SearchResponse {
        val start = System.currentTimeMillis()
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isEmpty()) {
            return SearchResponse(id, emptyList(), 0L, "Brave Search API key is not configured")
        }
        token.throwIfCancelled()

        var endpoint = "https://api.search.brave.com/res/v1/web/search?q=" +
            URLEncoder.encode(request.query, "UTF-8") + "&count=" + request.limit
        if (request.freshness != null && request.freshness != SearchFreshness.ANY) {
            endpoint += "&freshness=" + when (request.freshness) {
                SearchFreshness.DAY -> "pd"
                SearchFreshness.WEEK -> "pw"
                SearchFreshness.MONTH -> "pm"
                SearchFreshness.YEAR -> "py"
                else -> ""
            }
        }
        if (!request.language.isNullOrBlank()) {
            endpoint += "&search_lang=" + URLEncoder.encode(request.language, "UTF-8")
        }

        try {
            val budget = timeoutMs.coerceIn(1_000L, 60_000L)
            val text = WebHttpTransport().request(endpoint, token, authorizeHop,
                budget, 1024 * 1024, mapOf("Accept" to "application/json", "X-Subscription-Token" to apiKey)).text
            val json = JSONObject(text)
            val hits = mutableListOf<SearchHit>()
            val web = json.optJSONObject("web")
            val array = web?.optJSONArray("results")
            if (array != null) {
                for (i in 0 until minOf(array.length(), request.limit)) {
                    val item = array.optJSONObject(i) ?: continue
                    val title = item.optString("title", "").trim()
                    val link = item.optString("url", "").trim()
                    val desc = item.optString("description", "").trim()
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
