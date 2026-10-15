package dev.wind.agent.workspace

/**
 * Sanitizes and normalizes all paths provided by LLM models or users.
 * Strictly guarantees that no path can escape the Workspace virtual root.
 */
object WorkspacePath {

    const val SCHEME = "workspace://"

    /**
     * Sanitizes an arbitrary path string into a relative clean path.
     * Throws SecurityException on path traversal attempts or illegal characters.
     */
    fun sanitize(rawPath: String?): String {
        if (rawPath.isNullOrBlank()) return ""

        var p = rawPath.trim()
        if (p.startsWith(SCHEME, ignoreCase = true)) {
            p = p.substring(SCHEME.length)
        }

        // Block null bytes and control characters
        if (p.contains(0.toChar())) {
            throw SecurityException("Path contains null byte: $rawPath")
        }

        // Normalize backslashes to forward slashes
        p = p.replace('\\', '/')

        // Block foreign schemes and absolute system paths
        if (p.contains("://") || p.startsWith("file:", ignoreCase = true) || p.startsWith("content:", ignoreCase = true)) {
            throw SecurityException("URI schemes other than workspace:// are strictly forbidden: $rawPath")
        }
        if (p.startsWith("/")) {
            throw SecurityException("Access to system or absolute storage paths is forbidden: $rawPath")
        }

        // Disallow Windows drive letters (C:, D:, etc.)
        if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) {
            throw SecurityException("Drive letters not allowed in workspace: $rawPath")
        }

        // Split segments and validate traversal
        val parts = p.split('/')
        val stack = mutableListOf<String>()

        for (part in parts) {
            val segment = part.trim()
            if (segment.isEmpty() || segment == ".") continue

            // Check URL-encoded dots or traversal tricks (%2e, %2f)
            val decoded = decodeSegment(segment)
            if (decoded == ".." || decoded == "." || decoded.contains("..")) {
                throw SecurityException("Directory traversal attempt detected: $rawPath")
            }

            // Check Unicode variations of dot (e.g. \uFF0E fullwidth dot, \u2024 one dot leader)
            if (hasTraversalVariations(segment)) {
                throw SecurityException("Unicode directory traversal attempt detected: $rawPath")
            }

            stack.add(segment)
        }

        return stack.joinToString("/")
    }

    private fun decodeSegment(seg: String): String {
        var s = seg.lowercase()
        s = s.replace("%2e", ".").replace("%2f", "/")
        return s
    }

    private fun hasTraversalVariations(seg: String): Boolean {
        for (ch in seg) {
            if (ch == '\uFF0E' || ch == '\u2024' || ch == '\uFE52') return true
        }
        return false
    }

    /**
     * Formats a clean relative path as a full workspace virtual URI.
     */
    fun toVirtualUri(cleanRelativePath: String): String {
        return if (cleanRelativePath.isEmpty()) SCHEME else "$SCHEME$cleanRelativePath"
    }
}
