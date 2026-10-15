package dev.wind.agent.project

import android.net.Uri

enum class MountMode {
    READ_WRITE,
    READ_ONLY
}

enum class MountState {
    AVAILABLE,
    PERMISSION_REVOKED,
    INVALID
}

data class WorkspaceMount(
    val id: String,
    val alias: String,
    val treeUri: String,
    val displayName: String,
    val mode: MountMode = MountMode.READ_WRITE,
    val executionPath: String,
    val isPrimary: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Mount ID cannot be blank" }
        require(alias.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) { "Invalid mount alias: $alias" }
        require(treeUri.isNotBlank()) { "Mount tree URI cannot be blank" }
        require(isValidExecutionPath(executionPath)) { "Invalid execution path: $executionPath" }
    }

    val parsedUri: Uri get() = Uri.parse(treeUri)

    companion object {
        fun isValidExecutionPath(path: String): Boolean {
            if (path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":")) return false
            val segments = path.split('/')
            if (segments.isEmpty() || segments.any { it.isEmpty() || it == "." || it == ".." }) return false
            for (seg in segments) {
                if (seg.any { it < ' ' || it in "<>\"|?*" }) return false
            }
            return true
        }
    }
}

data class VegaProject(
    val id: String,
    val name: String,
    val primaryMountId: String,
    val mounts: List<WorkspaceMount> = emptyList(),
    val generation: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(id.isNotBlank()) { "Project ID cannot be blank" }
        require(name.isNotBlank()) { "Project name cannot be blank" }
        require(mounts.isNotEmpty()) { "Project must contain a primary mount" }

        val mountIds = mounts.map { it.id }
        require(mountIds.size == mountIds.toSet().size) { "Duplicate mount IDs detected" }

        val aliases = mounts.map { it.alias.lowercase() }
        require(aliases.size == aliases.toSet().size) { "Duplicate mount aliases detected" }

        val primaryById = mounts.filter { it.id == primaryMountId }
        require(primaryById.size == 1) { "PRIMARY_MOUNT_INVALID: primaryMountId does not identify exactly one mount" }
        val flaggedPrimary = mounts.filter { it.isPrimary }
        require(flaggedPrimary.size == 1 && flaggedPrimary.single().id == primaryMountId) {
            "PRIMARY_MOUNT_INVALID: isPrimary flag and primaryMountId disagree"
        }

        // executionPath values are disjoint so ordinary POSIX relative paths cannot
        // accidentally cross from one capability into another through an overlap.
        for (i in mounts.indices) {
            for (j in i + 1 until mounts.size) {
                val p1 = mounts[i].executionPath.trim('/').lowercase()
                val p2 = mounts[j].executionPath.trim('/').lowercase()
                if (p1 == p2 || p1.startsWith("$p2/") || p2.startsWith("$p1/")) {
                    throw IllegalArgumentException("EXECUTION_PATH_OVERLAP: $p1 overlaps with $p2")
                }
            }
        }
    }

    /** Fail closed: never reinterpret an arbitrary secondary mount as primary. */
    fun primaryMount(): WorkspaceMount? = mounts.firstOrNull { it.id == primaryMountId && it.isPrimary }

    fun findMount(alias: String): WorkspaceMount? =
        mounts.firstOrNull { it.alias.equals(alias, ignoreCase = true) }

    fun findMountById(mountId: String): WorkspaceMount? = mounts.firstOrNull { it.id == mountId }
}
