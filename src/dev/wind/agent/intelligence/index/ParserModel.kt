package dev.wind.agent.intelligence.index

import dev.wind.agent.intelligence.CodeLanguage
import dev.wind.agent.intelligence.CodePosition
import dev.wind.agent.intelligence.CodeRange
import dev.wind.agent.intelligence.IntelligenceConfidence
import dev.wind.agent.intelligence.SymbolKind

data class ParsedSymbol(
    val name: String,
    val qualifiedName: String,
    val kind: SymbolKind,
    val range: CodeRange,
    val containerName: String? = null,
    val signature: String? = null,
    val docComment: String? = null,
    val confidence: IntelligenceConfidence = IntelligenceConfidence.LEXICAL
)

data class ParsedReference(
    val name: String,
    val range: CodeRange,
    val targetSymbolId: String? = null,
    val refKind: String = "reference",
    val confidence: IntelligenceConfidence = IntelligenceConfidence.LEXICAL
)

data class ParsedImport(
    val moduleName: String,
    val importedName: String? = null,
    val alias: String? = null,
    val isWildcard: Boolean = false
)

data class FileParseResult(
    val relativePath: String,
    val language: CodeLanguage,
    val sha256: String,
    val symbols: List<ParsedSymbol>,
    val references: List<ParsedReference>,
    val imports: List<ParsedImport>,
    val terms: Map<String, Double> = emptyMap()
)
