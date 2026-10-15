package dev.wind.agent.workspace

/**
 * Normalized metadata representation for a file or directory inside the Workspace.
 * Virtual paths are relative to the Workspace root (e.g. "notes/control.md").
 */
data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String? = null
)
