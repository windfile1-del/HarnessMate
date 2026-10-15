package dev.wind.agent.intelligence.search

import dev.wind.agent.intelligence.*
import dev.wind.agent.intelligence.index.CodeIndexBackend

class StructuralSemanticSearch(
    private val indexBackend: CodeIndexBackend
) {
    fun search(
        query: String,
        revision: SourceRevision,
        limit: Int = 20
    ): List<SemanticSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val effectiveLimit = limit.coerceIn(1, CodeIntelLimits.MAX_SEMANTIC_RESULTS + 1)
        return indexBackend.semanticSearch(trimmed, effectiveLimit, revision)
    }
}

