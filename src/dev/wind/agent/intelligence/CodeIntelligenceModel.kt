package dev.wind.agent.intelligence

import org.json.JSONArray
import org.json.JSONObject

data class SourceRevision(
    val projectId: String,
    val rootGeneration: Long,
    val sourceDigest: String,
    val mutationEpoch: Int
) {
    fun toToken(): String {
        return "$projectId:$rootGeneration:$sourceDigest:$mutationEpoch"
    }

    fun toSnapshotId(): CodeSnapshotId {
        return CodeSnapshotId(
            projectId = projectId,
            rootGeneration = rootGeneration,
            sourceDigest = sourceDigest
        )
    }
}

data class CodeSnapshotId(
    val projectId: String,
    val rootGeneration: Long,
    val sourceDigest: String
) {
    fun toKey(): String = "$projectId@$rootGeneration:$sourceDigest"
}

data class CodeIndexBinding(
    val snapshot: CodeSnapshotId,
    val mutationEpoch: Int,
    val boundAt: Long = System.currentTimeMillis()
)

enum class CodeLanguage(val identifier: String) {
    KOTLIN("kotlin"),
    JAVA("java"),
    PYTHON("python"),
    TYPESCRIPT("typescript"),
    JAVASCRIPT("javascript"),
    UNKNOWN("unknown");

    companion object {
        fun fromPath(path: String): CodeLanguage {
            val lower = path.lowercase()
            return when {
                lower.endsWith(".kt") || lower.endsWith(".kts") -> KOTLIN
                lower.endsWith(".java") -> JAVA
                lower.endsWith(".py") -> PYTHON
                lower.endsWith(".ts") || lower.endsWith(".tsx") -> TYPESCRIPT
                lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".mjs") || lower.endsWith(".cjs") -> JAVASCRIPT
                else -> UNKNOWN
            }
        }
    }
}

enum class SymbolKind(val wireName: String) {
    CLASS("class"),
    INTERFACE("interface"),
    FUNCTION("function"),
    METHOD("method"),
    VARIABLE("variable"),
    FIELD("field"),
    CONSTANT("constant"),
    ENUM("enum"),
    TYPE("type"),
    MODULE("module"),
    PACKAGE("package"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(name: String): SymbolKind {
            val lower = name.trim().lowercase()
            return entries.firstOrNull { it.wireName == lower } ?: UNKNOWN
        }
    }
}

enum class IntelligenceConfidence {
    EXACT,       // LSP
    STRUCTURAL,  // Tree-sitter / AST
    LEXICAL      // Lightweight Lexer / Parser
}

data class CodePosition(
    val line: Int,    // 1-based
    val column: Int   // 1-based
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("line", line)
        put("column", column)
    }

    companion object {
        fun fromJson(json: JSONObject?): CodePosition {
            if (json == null) return CodePosition(1, 1)
            val line = json.optInt("line", 1)
            val column = json.optInt("column", 1)
            return CodePosition(maxOf(1, line), maxOf(1, column))
        }
    }
}

data class CodeRange(
    val start: CodePosition,
    val end: CodePosition
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("start", start.toJson())
        put("end", end.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject?): CodeRange {
            if (json == null) return CodeRange(CodePosition(1, 1), CodePosition(1, 1))
            return CodeRange(
                start = CodePosition.fromJson(json.optJSONObject("start")),
                end = CodePosition.fromJson(json.optJSONObject("end"))
            )
        }
    }
}

data class SymbolLocation(
    val path: String,                   // workspace:// or project://
    val range: CodeRange,
    val symbol: String,
    val kind: SymbolKind,
    val confidence: IntelligenceConfidence,
    val containerName: String? = null,
    val signature: String? = null,
    val snippet: String? = null,
    val symbolId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("range", range.toJson())
        put("symbol", symbol)
        put("kind", kind.wireName)
        put("confidence", confidence.name)
        if (containerName != null) put("container_name", containerName)
        if (signature != null) put("signature", signature)
        if (snippet != null) put("snippet", snippet)
        if (symbolId != null) put("symbol_id", symbolId)
    }
}

data class ReferenceLocation(
    val path: String,                   // workspace:// or project://
    val range: CodeRange,
    val symbol: String,
    val targetSymbolId: String? = null,
    val refKind: String = "reference",
    val confidence: IntelligenceConfidence,
    val snippet: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("range", range.toJson())
        put("symbol", symbol)
        put("ref_kind", refKind)
        put("confidence", confidence.name)
        if (targetSymbolId != null) put("target_symbol_id", targetSymbolId)
        if (snippet != null) put("snippet", snippet)
    }
}

enum class DependencyNodeKind {
    MODULE,
    FILE,
    PACKAGE,
    EXTERNAL_DEPENDENCY
}

enum class DependencyEdgeKind {
    IMPORTS,
    DEPENDS_ON,
    DECLARES
}

data class DependencyNode(
    val id: String,
    val kind: DependencyNodeKind,
    val label: String,
    val path: String? = null,
    val resolved: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("label", label)
        if (path != null) put("path", path)
        put("resolved", resolved)
    }
}

data class DependencyEdge(
    val sourceId: String,
    val targetId: String,
    val kind: DependencyEdgeKind,
    val label: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source_id", sourceId)
        put("target_id", targetId)
        put("kind", kind.name)
        if (label != null) put("label", label)
    }
}

data class DependencyGraph(
    val nodes: List<DependencyNode>,
    val edges: List<DependencyEdge>,
    val truncated: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        val nodesArr = JSONArray()
        nodes.forEach { nodesArr.put(it.toJson()) }
        val edgesArr = JSONArray()
        edges.forEach { edgesArr.put(it.toJson()) }
        put("nodes", nodesArr)
        put("edges", edgesArr)
        put("truncated", truncated)
    }
}

data class SemanticSearchResult(
    val path: String,
    val range: CodeRange,
    val symbol: String,
    val kind: SymbolKind,
    val score: Double,
    val reasons: List<String>,
    val snippet: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("range", range.toJson())
        put("symbol", symbol)
        put("kind", kind.wireName)
        put("score", score)
        val reasonsArr = JSONArray()
        reasons.forEach { reasonsArr.put(it) }
        put("reasons", reasonsArr)
        if (snippet != null) put("snippet", snippet)
    }
}

data class CursorPayload(
    val queryFingerprint: String,
    val sourceDigest: String,
    val mutationEpoch: Int,
    val offset: Int
) {
    fun encode(): String {
        return "$queryFingerprint:$sourceDigest:$mutationEpoch:$offset"
    }

    companion object {
        fun decode(encoded: String?): CursorPayload? {
            if (encoded.isNullOrBlank()) return null
            val parts = encoded.split(":")
            if (parts.size != 4) return null
            if (parts[0].isBlank()) return null
            val epoch = parts[2].toIntOrNull() ?: return null
            val offset = parts[3].toIntOrNull() ?: return null
            if (epoch < 0 || offset < 0) return null
            return CursorPayload(
                queryFingerprint = parts[0],
                sourceDigest = parts[1],
                mutationEpoch = epoch,
                offset = offset
            )
        }
    }
}

