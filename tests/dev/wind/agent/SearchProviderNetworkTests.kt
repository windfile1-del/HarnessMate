package dev.wind.agent

import dev.wind.agent.search.BraveSearchProvider
import dev.wind.agent.search.DuckDuckGoLiteProvider
import dev.wind.agent.search.SearchRequest
import dev.wind.agent.search.SearxngSearchProvider
import dev.wind.agent.search.TavilySearchProvider

object SearchProviderNetworkTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val request = SearchRequest("kotlin compiler", 3)
        val providers = listOf(
            DuckDuckGoLiteProvider(),
            BraveSearchProvider { "test-token" },
            SearxngSearchProvider { "https://searx.example" },
            TavilySearchProvider { "test-token" }
        )
        providers.forEach { provider ->
            val result = provider.search(request, CancellationToken(), 1_000L) { false }
            check(result.results.isEmpty() && (result.error?.startsWith("FETCH_HOP_NOT_AUTHORIZED") == true || result.error?.contains("FETCH_HOP_NOT_AUTHORIZED") == true)) {
                "${provider.id} bypassed the network authority callback: ${result.error}"
            }
        }
        println("SearchProviderNetworkTests: OK")
    }
}
