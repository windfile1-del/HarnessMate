package dev.wind.agent.search

import dev.wind.agent.CancellationToken
import dev.wind.agent.WebHttpTransport
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class DuckDuckGoLiteProvider : SearchProvider {

    override val id: String = "duckduckgo_lite"
    override val displayName: String = "DuckDuckGo Lite (Free Fallback)"

    override fun isConfigured(): Boolean = true

    override fun search(request: SearchRequest, token: CancellationToken, timeoutMs: Long,
        authorizeHop: (URI) -> Boolean): SearchResponse {
        val start = System.currentTimeMillis()
        token.throwIfCancelled()

        val endpoint = "https://lite.duckduckgo.com/lite/?q=" + URLEncoder.encode(request.query, "UTF-8")
        try {
            val budget = timeoutMs.coerceIn(1_000L, 60_000L)
            val html = WebHttpTransport().request(endpoint, token, authorizeHop,
                budget, 1024 * 1024).text
            val hits = parseLiteHtml(html, request.limit)
            return SearchResponse(id, hits, System.currentTimeMillis() - start)
        } catch (c: CancellationToken.CancelledException) {
            return SearchResponse(id, emptyList(), System.currentTimeMillis() - start, "CANCELLED")
        } catch (e: Exception) {
            if (token.isCancelled) {
                return SearchResponse(id, emptyList(), System.currentTimeMillis() - start, "CANCELLED")
            }
            return SearchResponse(id, emptyList(), System.currentTimeMillis() - start, e.message ?: e.javaClass.simpleName)
        }
    }

    internal fun parseLiteHtml(html: String, limit: Int): List<SearchHit> {
        val hits = mutableListOf<SearchHit>()
        val linkPattern = Regex(
            "<a\\b(?=[^>]*class=[\"'][^\"']*\\bresult-link\\b[^\"']*[\"'])(?=[^>]*href=[\"']([^\"']*)[\"'])[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snipPattern = Regex(
            "<td[^>]+class=[\"']result-snippet[\"'][^>]*>(.*?)</td>",
            RegexOption.DOT_MATCHES_ALL
        )

        val links = linkPattern.findAll(html).toList()
        val snips = snipPattern.findAll(html).toList()

        for (i in links.indices) {
            if (hits.size >= limit) break
            val rawHref = links[i].groupValues[1]
            val href = decodeDuckHref(rawHref.replace("&amp;", "&"))
            val rawTitle = links[i].groupValues[2]
            val title = stripTags(rawTitle).trim()
            val nextLink = links.getOrNull(i + 1)?.range?.first ?: html.length
            val snippet = snips.firstOrNull { it.range.first > links[i].range.last && it.range.first < nextLink }
                ?.let { stripTags(it.groupValues[1]).trim() } ?: ""
            if (href.isNotEmpty() && title.isNotEmpty()) {
                hits.add(SearchHit(title, href, snippet))
            }
        }
        return hits
    }

    private fun decodeDuckHref(raw: String): String {
        var href = raw.trim()
        if (href.startsWith("//")) {
            href = "https:" + href
        }
        val uddg = href.indexOf("uddg=")
        if (uddg >= 0) {
            val rest = href.substring(uddg + 5)
            val amp = rest.indexOf('&')
            val part = if (amp >= 0) rest.substring(0, amp) else rest
            try {
                return URLDecoder.decode(part, "UTF-8")
            } catch (_: Exception) {
            }
        }
        return href
    }

    private fun stripTags(raw: String): String {
        var s = raw.replace(Regex("<[^>]+>"), " ")
        s = s.replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'")
             .replace("&nbsp;", " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
