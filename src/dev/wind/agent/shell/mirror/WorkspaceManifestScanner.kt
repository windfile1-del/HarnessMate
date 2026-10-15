package dev.wind.agent.shell.mirror

import dev.wind.agent.workspace.WorkspaceFs
import java.security.MessageDigest

/** Enumerates metadata and hashes streams without keeping project payload on Vega disk. */
object WorkspaceManifestScanner {
    const val MAX_FILES = 200_000

    fun scan(
        fs: WorkspaceFs,
        workspaceId: String,
        generation: Long,
        previous: WorkspaceManifest? = null,
        trustModificationTimes: Boolean = false,
        excludedPaths: Set<String> = emptySet()
    ): WorkspaceManifest {
        require(fs.isConfigured()) { "Workspace not configured" }
        require(previous == null || previous.workspaceId == workspaceId)
        val entries = linkedMapOf<String, ManifestEntry>()
        val pending = java.util.ArrayDeque<String>()
        pending.add("")
        while (pending.isNotEmpty()) {
            checkInterrupted()
            val parent = pending.removeFirst()
            for (item in fs.list(parent)) {
                checkInterrupted()
                // Validate before filtering; providers cannot return foreign paths or cycles.
                val metadata = ManifestEntry(item.path, item.isDirectory, if (item.isDirectory) 0L else item.size.coerceAtLeast(0),
                    item.lastModified.coerceAtLeast(0), null)
                require(item.path.substringBeforeLast('/', "") == parent) { "Invalid provider child path" }
                if (WorkspaceIgnorePolicy.isIgnored(item.path) || item.path in excludedPaths) continue
                require(item.path !in entries) { "Duplicate provider path" }
                require(entries.size < MAX_FILES) { "WORKSPACE_FILE_LIMIT" }
                if (item.isDirectory) {
                    entries[item.path] = metadata
                    pending.add(item.path)
                    continue
                }
                val old = previous?.entries?.get(item.path)
                val reuse = trustModificationTimes && item.lastModified > 0 && item.size >= 0 &&
                    old != null && !old.directory && old.modified == item.lastModified && old.size == item.size &&
                    old.sha256 != null
                if (reuse) {
                    entries[item.path] = metadata.copy(sha256 = old!!.sha256)
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    fs.openInputStream(item.path).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkInterrupted()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            check(size <= Long.MAX_VALUE - count) { "File size overflow" }
                            size += count
                            digest.update(buffer, 0, count)
                        }
                    }
                    val after = fs.stat(item.path) ?: error("Workspace changed during scan")
                    require(!after.isDirectory && after.size == item.size && after.lastModified == item.lastModified &&
                        (item.size < 0 || item.size == size)) { "Workspace changed during scan" }
                    entries[item.path] = metadata.copy(size = size,
                        sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                }
            }
        }
        return WorkspaceManifest(workspaceId, generation, entries.toMap())
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("WORKSPACE_SCAN_CANCELLED")
    }
}
