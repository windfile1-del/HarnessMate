package dev.wind.agent.shell.mirror

import java.security.MessageDigest
import dev.wind.agent.workspace.WorkspacePath

object WorkspaceIdentity {
    fun fromUri(uri: String): String {
        require(uri.isNotBlank()) { "Workspace not configured" }
        return MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun remoteRoot(workspaceId: String): String {
        require(Regex("[0-9a-f]{24}").matches(workspaceId)) { "Invalid workspace ID" }
        return "/data/data/com.termux/files/home/.vega-safe/workspaces/$workspaceId"
    }
}

data class ManifestEntry(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val modified: Long,
    val sha256: String?
) {
    init {
        require(path.isNotEmpty() && WorkspacePath.sanitize(path) == path && path.none { it < ' ' })
        require(size >= 0 && modified >= 0)
        require(sha256 == null || Regex("[0-9a-f]{64}").matches(sha256))
    }
}

data class WorkspaceManifest(
    val workspaceId: String,
    val generation: Long,
    val entries: Map<String, ManifestEntry>
) {
    init {
        WorkspaceIdentity.remoteRoot(workspaceId)
        require(generation >= 0)
        require(entries.all { (path, entry) -> path == entry.path })
    }
}
