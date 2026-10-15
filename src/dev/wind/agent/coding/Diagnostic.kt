package dev.wind.agent.coding

import org.json.JSONArray
import org.json.JSONObject
import dev.wind.agent.isNotBlankJava

enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO
}

enum class DiagnosticCategory {
    COMPILE,
    TEST_FAILURE,
    BUILD_CONFIG,
    DEPENDENCY,
    TOOLCHAIN,
    TIMEOUT,
    PERMISSION,
    NETWORK_REQUIRED,
    UNKNOWN
}

/** Which part of the pipeline produced a diagnostic; kept for triage. */
enum class DiagnosticSource {
    GRADLE,
    KOTLIN,
    JAVAC,
    TEST,
    GENERIC
}

/**
 * One structured problem, with no build-tool paths in it.
 *
 * [path] is always the VIRTUAL project path (workspace://...), never a Termux
 * path like /data/data/com.termux/files/home/.vega-safe/... — the model must
 * never learn where the mirror physically lives, both because it is platform
 * detail the architecture keeps below the model, and because an edit aimed at a
 * physical path is an edit aimed outside the sandbox.
 */
data class Diagnostic(
    val id: String,
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val path: String?,
    val line: Int?,
    val column: Int?,
    val code: String?,
    val message: String,
    val source: DiagnosticSource
) {
    init {
        require(message.isNotBlankJava()) { "DIAGNOSTIC_MESSAGE_MISSING" }
        require(line == null || line > 0)
        require(column == null || column > 0)
    }

    /** Identity used for "same error as last time" detection. */
    fun key(): String = (path ?: "-") + "|" + (line ?: -1) + "|" + (code ?: "-") + "|" + message

    fun oneLine(): String {
        val sb = StringBuilder()
        sb.append(if (path != null) path else "(project)")
        if (line != null) {
            sb.append(':').append(line)
            if (column != null) {
                sb.append(':').append(column)
            }
        }
        sb.append("  ").append(message)
        code?.let { sb.append("  [").append(it).append(']') }
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("severity", severity.name)
        .put("category", category.name)
        .put("path", path ?: JSONObject.NULL)
        .put("line", line ?: -1)
        .put("column", column ?: -1)
        .put("code", code ?: JSONObject.NULL)
        .put("message", message)
        .put("source", source.name)

    companion object {
        fun fromJson(root: JSONObject): Diagnostic = Diagnostic(
            id = root.optString("id", ""),
            severity = DiagnosticSeverity.valueOf(root.optString("severity", "ERROR")),
            category = DiagnosticCategory.valueOf(root.optString("category", "UNKNOWN")),
            path = root.optString("path", "").takeIf { it.isNotEmpty() },
            line = root.optInt("line", -1).takeIf { it > 0 },
            column = root.optInt("column", -1).takeIf { it > 0 },
            code = root.optString("code", "").takeIf { it.isNotEmpty() },
            message = root.getString("message"),
            source = DiagnosticSource.valueOf(root.optString("source", "GENERIC"))
        )
    }
}

/** What one validation run produced, in the form the model is shown. */
data class DiagnosticReport(
    val errors: List<Diagnostic>,
    val warnings: List<Diagnostic>,
    val rawTail: String,
    val truncated: Boolean
) {
    val ok: Boolean get() = errors.isEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("errors", JSONArray().apply { errors.forEach { put(it.toJson()) } })
        .put("warnings", JSONArray().apply { warnings.forEach { put(it.toJson()) } })
        .put("rawTail", rawTail)
        .put("truncated", truncated)
}
