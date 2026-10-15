package dev.wind.agent.search

import dev.wind.agent.CancellationToken
import java.net.URI

enum class SearchFreshness(val key: String) {
    ANY("any"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year")
}

data class SearchRequest(
    val query: String,
    val limit: Int = 8,
    val language: String? = null,
    val freshness: SearchFreshness? = null
)

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String
)

data class SearchResponse(
    val provider: String,
    val results: List<SearchHit>,
    val elapsedMs: Long,
    val error: String? = null
)

interface SearchProvider {
    val id: String
    val displayName: String
    fun isConfigured(): Boolean
    fun search(request: SearchRequest, token: CancellationToken, timeoutMs: Long = 8000L,
        authorizeHop: (URI) -> Boolean = { false }): SearchResponse
}

class SearchDeadline(
    val totalBudgetMs: Long,
    val startMs: Long = System.currentTimeMillis()
) {
    fun remainingMs(): Long {
        val elapsed = System.currentTimeMillis() - startMs
        return Math.max(0L, totalBudgetMs - elapsed)
    }

    fun isExpired(): Boolean = remainingMs() <= 0L
}
