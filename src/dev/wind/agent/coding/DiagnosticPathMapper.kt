package dev.wind.agent.coding

import dev.wind.agent.isNotBlankJava
import dev.wind.agent.isBlankJava
import dev.wind.agent.trimJavaOrEmpty

/**
 * Rewrites any path a build tool printed into the project's virtual path space.
 *
 * Termux emits real absolute paths such as
 * /data/data/com.termux/files/home/.vega-safe/workspaces/<id>/<executionPath>/src/Foo.kt.
 * Handing those to the model would teach it a filesystem that only exists on one
 * device, and an edit aimed at one would be an edit aimed outside SAF. Every
 * path that leaves this class is therefore either workspace:// or project://.
 *
 * The mapper is deliberately blind to whether a path exists: it is a textual
 * rewrite of build output, and a diagnostic about a file that was just deleted
 * still has to be reportable.
 */
object DiagnosticPathMapper {

    /** Mount execution paths, longest first so nested mounts map correctly. */
    private var mounts: List<Pair<String, String>> = emptyList()
    private var remoteRoots: List<String> = emptyList()

    /**
     * Configures the mapping for one project snapshot.
     *
     * [mounts] is (executionPath, virtualPrefix) — for the primary mount the
     * prefix is "workspace://", for the others "project://<alias>/".
     */
    fun configure(mounts: List<Pair<String, String>>, remoteRoots: List<String>) {
        this.mounts = mounts
            .filter { it.first.isNotBlankJava() && it.first != "." }
            .sortedByDescending { it.first.length }
        this.remoteRoots = remoteRoots.filter { it.isNotBlankJava() }.sortedByDescending { it.length }
    }

    fun reset() {
        mounts = emptyList()
        remoteRoots = emptyList()
    }

    /** True when the mapper has at least one real mapping to apply. */
    val configured: Boolean get() = mounts.isNotEmpty() || remoteRoots.isNotEmpty()

    /**
     * Removes device paths from any free text on its way to the model.
     *
     * [map] covers the structured path field, but a diagnostic MESSAGE is free
     * text a build tool wrote: a line like a Termux permission error carries the
     * same disclosure and arrives by a different route. Scrubbing only the path
     * field is how the leak survived review.
     *
     * Known workspace roots are dropped and any remaining absolute-looking path
     * is reduced to its last segment, so the message keeps its diagnostic value
     * without describing the device layout.
     */
    fun sanitizeText(raw: String?): String {
        val value = raw ?: return ""
        if (value.isEmpty()) {
            return ""
        }
        var out = value
        for (root in remoteRoots) {
            if (out.contains(root)) {
                out = out.replace(root, "")
            }
        }
        out = ABSOLUTE.replace(out) { match ->
            val tail = match.value.substringAfterLast('/')
            if (tail.isBlankJava()) match.value else tail
        }
        return out
    }

    /** Matches an absolute POSIX path inside free text. */
    private val ABSOLUTE = Regex("""/(?:[A-Za-z0-9._@+-]+/)*[A-Za-z0-9._@+-]+""")

    /**
     * True when [text] still contains a device path.
     *
     * Used as a fail-fast assertion at the boundary where diagnostics are built,
     * so a NEW parser that forgets to sanitize is caught by a test rather than
     * leaking a device path to a model. Returns false when the mapper has no
     * configuration, since there is nothing to compare against.
     */
    fun hasDevicePath(text: String?): Boolean {
        val value = text ?: return false
        if (value.isEmpty()) {
            return false
        }
        if (remoteRoots.any { value.contains(it) }) {
            return true
        }
        return ABSOLUTE.containsMatchIn(value)
    }

    /**
     * Maps one path. A termux absolute path, a mirror-rooted path and a bare
     * project-relative path all come back as a virtual path; anything that
     * cannot be placed stays relative rather than being leaked verbatim.
     */
    fun map(raw: String?): String? {
        val value = raw?.replace('\\', '/')?.trimJavaOrEmpty() ?: ""
        if (value.isEmpty()) {
            return null
        }
        // Already virtual: keep it, and normalise a bare "//" root.
        if (value.startsWith("workspace://")) {
            return "workspace://" + value.removePrefix("workspace://").trim('/')
        }
        if (value.startsWith("project://")) {
            return value
        }
        if (value.startsWith("execution://")) {
            val rest = value.removePrefix("execution://").trim('/')
            return mapProjectRelative(rest) ?: "workspace://$rest"
        }

        val cleaned = stripNoise(value)
        for (root in remoteRoots) {
            val index = cleaned.indexOf(root)
            if (index >= 0) {
                val rest = cleaned.substring(index + root.length).trimStart('/')
                return mapProjectRelative(rest) ?: "workspace://$rest"
            }
        }
        if (looksAbsolute(cleaned)) {
            // An absolute path outside every known root is NOT echoed: it is
            // device detail, and naming the file it points at is not worth
            // disclosing the layout of the device to the model.
            val tail = cleaned.substringAfterLast('/')
            return tail.takeIf { it.isNotEmpty() }?.let { "workspace://$it" }
        }
        val relative = cleaned.trimStart('/')
        return mapProjectRelative(relative) ?: "workspace://$relative"
    }

    /** Maps a mirror-relative path through the mount table. */
    private fun mapProjectRelative(relative: String): String? {
        for ((executionPath, prefix) in mounts) {
            if (relative == executionPath) {
                return prefix
            }
            if (relative.startsWith("$executionPath/")) {
                val rest = relative.substring(executionPath.length + 1)
                return prefix + rest
            }
        }
        return null
    }

    /** Drops a trailing source annotation a compiler may have appended. */
    private fun stripNoise(value: String): String {
        var cleaned = value
        val cut = cleaned.indexOfAny(charArrayOf('\n', '\r', '\t'))
        if (cut > 0) {
            cleaned = cleaned.substring(0, cut)
        }
        // Kotlin prints "file:///data/..."; the scheme is not part of the path.
        cleaned = cleaned.removePrefix("file://")
        // "jar:file:///..." appears in dependency-resolution failures.
        val jarIndex = cleaned.indexOf("!/")
        if (jarIndex > 0) {
            cleaned = cleaned.substring(0, jarIndex)
        }
        return cleaned.trimJavaOrEmpty()
    }

    private fun looksAbsolute(value: String): Boolean =
        value.startsWith("/") || value.contains(":/")
}
