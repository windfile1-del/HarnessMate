package dev.wind.agent.shell.mirror

/** Immutable snapshot of the mirror diff presented for one commit approval. */
data class ApplyPlan(
    val executionId: String,
    val changedMountIds: Set<String>,
    val createdFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int,
    val hasDestructiveChanges: Boolean,
    val diffFingerprint: String
) {
    val changedFiles: Int get() = createdFiles + modifiedFiles + deletedFiles
}
