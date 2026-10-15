package dev.wind.agent.shell.mirror

data class SyncPlan(
    val created: List<ManifestEntry>,
    val modified: List<ManifestEntry>,
    val deleted: List<String>
) {
    fun isEmpty(): Boolean = created.isEmpty() && modified.isEmpty() && deleted.isEmpty()
}

/** Pure planning only. Deletions must precede writes when a path changes type. */
object WorkspaceSyncPlanner {
    fun plan(before: WorkspaceManifest, now: WorkspaceManifest): SyncPlan {
        require(before.workspaceId == now.workspaceId) { "Workspace identity mismatch" }
        require(now.generation >= before.generation) { "Stale manifest generation" }
        val old = before.entries.filterKeys { !WorkspaceIgnorePolicy.isIgnored(it) }
        val current = now.entries.filterKeys { !WorkspaceIgnorePolicy.isIgnored(it) }
        val created = mutableListOf<ManifestEntry>()
        val modified = mutableListOf<ManifestEntry>()
        val deleted = old.keys.filter { it !in current }.toMutableSet()
        for ((path, entry) in current) {
            val previous = old[path]
            when {
                previous == null -> created.add(entry)
                previous.directory != entry.directory -> {
                    deleted.add(path)
                    created.add(entry)
                }
                !entry.directory && !sameContent(previous, entry) -> modified.add(entry)
            }
        }
        return SyncPlan(
            created.sortedWith(compareBy<ManifestEntry> { it.path.count { c -> c == '/' } }.thenBy { it.path }),
            modified.sortedBy { it.path },
            deleted.sortedWith(compareByDescending<String> { it.count { c -> c == '/' } }.thenBy { it })
        )
    }

    private fun sameContent(before: ManifestEntry, now: ManifestEntry): Boolean {
        if (before.size != now.size) return false
        // Unknown provider timestamps are never evidence of unchanged content.
        // Callers may reuse a hash only after independently establishing provider reliability.
        if (before.sha256 != null && now.sha256 != null) return before.sha256 == now.sha256
        return false
    }
}
