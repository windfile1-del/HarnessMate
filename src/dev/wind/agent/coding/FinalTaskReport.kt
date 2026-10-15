package dev.wind.agent.coding

import org.json.JSONArray
import org.json.JSONObject
import dev.wind.agent.isNotBlankJava

enum class TaskStatus {
    SUCCESS,
    APPLIED_UNVERIFIED,
    UNVERIFIED_NOT_APPLIED,
    NO_CHANGES,
    VERIFIED_NOT_APPLIED,
    BLOCKED,
    FAILED,
    CANCELLED
}

/**
 * What actually happened, assembled by the harness rather than remembered by
 * the model.
 *
 * A model's closing paragraph is a summary of its own beliefs; this is a
 * summary of observed facts — which checks ran and passed, how many files the
 * mirror diff actually contains, whether the commit went through. When the two
 * disagree, this one is right, and it is also what the UI renders.
 */
data class FinalTaskReport(
    val status: TaskStatus,
    val changedFiles: Int,
    val createdFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int,
    val validations: List<ValidationSummary>,
    val unresolvedDiagnostics: List<Diagnostic>,
    val diffFingerprint: String?,
    val applied: Boolean,
    val note: String = "",
    val verified: Boolean = false,
    val countsKnown: Boolean = true,
    val canonicalOperations: List<String> = emptyList()
) {
    init {
        require(changedFiles >= 0 && createdFiles >= 0 && modifiedFiles >= 0 && deletedFiles >= 0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("changedFiles", changedFiles)
        .put("createdFiles", createdFiles)
        .put("modifiedFiles", modifiedFiles)
        .put("deletedFiles", deletedFiles)
        .put("validations", JSONArray().apply { validations.forEach { put(it.toJson()) } })
        .put("unresolvedDiagnostics", JSONArray().apply { unresolvedDiagnostics.forEach { put(it.toJson()) } })
        .put("diffFingerprint", diffFingerprint ?: JSONObject.NULL)
        .put("applied", applied)
        .put("verified", verified)
        .put("countsKnown", countsKnown)
        .put("canonicalOperations", JSONArray().apply { canonicalOperations.forEach { put(it) } })
        .put("note", note)

    /** The factual half of the closing message, before any prose. */
    fun summaryLines(): List<String> {
        val lines = mutableListOf<String>()
        if (countsKnown) lines.add(
            "Files: " + changedFiles + " changed (" + createdFiles + " created, " +
                modifiedFiles + " modified, " + deletedFiles + " deleted)"
        )
        else lines.add("Changed-file counts have not been inspected.")
        lines.add("Outcome: " + status.name)
        lines.add(if (verified) "Current revision verified." else "Current revision not verified; no passing final verification claimed.")
        for (validation in validations) {
            lines.add((if (validation.ok) "PASS " else "FAIL ") + validation.check + " (observed check result)")
        }
        diffFingerprint?.let { lines.add("Diff: " + it) }
        lines.add(if (applied) "Applied to the project." else "Not applied.")
        if (canonicalOperations.isNotEmpty()) lines.add("Direct workspace operations recorded: " + canonicalOperations.joinToString(", "))
        if (unresolvedDiagnostics.isNotEmpty()) {
            lines.add("Unresolved: " + unresolvedDiagnostics.size + " diagnostic(s)")
        }
        if (note.isNotBlankJava()) {
            lines.add(note)
        }
        return lines
    }
}
