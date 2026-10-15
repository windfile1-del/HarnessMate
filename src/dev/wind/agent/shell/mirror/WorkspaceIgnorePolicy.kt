package dev.wind.agent.shell.mirror

import org.json.JSONArray

/** Shared by canonical enumeration and mirror diff; never apply ignored cache files. */
object WorkspaceIgnorePolicy {
    val ignoredSegments: Set<String> = setOf(
        ".git", ".gradle", "build", "out", "target", "node_modules",
        "dist", ".idea", ".build-", "__pycache__", ".harnessmate-venv", ".harnessmate-package-cache"
    )
    val ignoredSuffixes: Set<String> = setOf(".class", ".apk", ".tmp")

    fun isIgnored(path: String): Boolean = path.replace('\\', '/').trim('/').split('/').any { segment ->
        segment in ignoredSegments || ignoredSuffixes.any { segment.endsWith(it) }
    }

    /**
     * The policy as the mirror protocols carry it.
     *
     * Sorted, so the same policy always serializes to the same bytes and a
     * digest over it is stable. Both providers are handed this and nothing else:
     * a second hard-coded list on the far side is how two mirror roots end up
     * disagreeing about the same project.
     */
    fun sortedSegmentsJson(): JSONArray = JSONArray().apply {
        ignoredSegments.sorted().forEach { put(it) }
    }

    fun sortedSuffixesJson(): JSONArray = JSONArray().apply {
        ignoredSuffixes.sorted().forEach { put(it) }
    }
}

