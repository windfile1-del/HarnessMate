package dev.wind.agent.intelligence.lsp

import dev.wind.agent.intelligence.CodePathMapper
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Turns language-server locations into model-visible virtual paths.
 *
 * Two rules, both fail-closed:
 *  1. A location is only accepted when its URI is a file:// URI whose path sits
 *     under the execution-mirror root the active session actually reported.
 *     Anything else — another scheme, another root, an unknown server — is
 *     rejected instead of guessed at.
 *  2. Free text (diagnostic messages, stderr) cannot be mapped, so every
 *     absolute host path in it is replaced, not enumerated away.
 */
class LspPathSanitizer(
    private val pathMapper: CodePathMapper
) {
    fun uriToVirtualPath(uri: String, executionRoot: String?): String? {
        if (!uri.startsWith("file://", ignoreCase = true)) return null
        val root = executionRoot?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("file", ignoreCase = true) || !parsed.rawAuthority.isNullOrEmpty()) return null
        val decodedPath = parsed.path ?: return null
        if (decodedPath.contains('\u0000')) return null
        val path = runCatching { Paths.get(decodedPath).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val rootPath = runCatching { Paths.get(root).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (!isUnder(rootPath, path) || path == rootPath) return null
        val relative = rootPath.relativize(path).toString().replace('\\', '/').trim('/')
        if (relative.isEmpty()) {
            return null
        }
        val virtual = runCatching { pathMapper.toVirtualPath(relative) }.getOrNull() ?: return null
        if (!virtual.startsWith("workspace://") && !virtual.startsWith("project://")) {
            return null
        }
        return virtual
    }

    fun virtualPathToUri(virtualPath: String, executionRoot: String?): String? {
        val root = executionRoot?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val relative = runCatching { pathMapper.toExecutionRelative(virtualPath) }.getOrNull() ?: return null
        // The execution root is a POSIX path even when this offline harness is
        // compiled on Windows.  Keep that logical path intact instead of
        // letting java.nio prepend the host drive (which would make a valid
        // Android file URI look like a relative Windows URI).
        val logicalRoot = root.replace('\\', '/').trimEnd('/')
        if (logicalRoot.startsWith("/")) {
            val candidate = (logicalRoot + "/" + relative).replace("//", "/")
            val segments = candidate.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == ".." }) return null
            return URI("file", "", "/" + segments.joinToString("/"), null).toString()
        }
        val rootPath = runCatching { Paths.get(root).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val candidate = runCatching { rootPath.resolve(relative).normalize() }.getOrNull() ?: return null
        if (!isUnder(rootPath, candidate) || candidate == rootPath) return null
        return URI("file", "", candidate.toString().replace('\\', '/'), null).toString()
    }

    /**
     * Removes any absolute host path from text the model will see.
     *
     * Enumeration of known roots is not enough — /root, /bin, /srv and every
     * future prefix would leak — so the rule is inverted: a path-looking token
     * that starts at a filesystem root is replaced unless it is a virtual path.
     */
    fun sanitizeText(text: String): String {
        if (text.isEmpty()) {
            return text
        }
        var result = text
        // Scheme names are not a security boundary.  content://, binder://,
        // termux:// and any future/custom scheme can carry a host identifier
        // or a physical path just as readily as file://.  Even the two
        // application-owned schemes are kept only after canonical validation
        // through the active project mapper.
        result = result.replace(URI_TOKEN) { match ->
            canonicalVirtualUri(match.value) ?: "<sanitized-uri>"
        }
        result = result.replace(WINDOWS_ABSOLUTE_PATH, "<sanitized-path>")
        result = result.replace(UNC_ABSOLUTE_PATH, "<sanitized-path>")
        // URI tokens were canonicalised above.  Any remaining filesystem-root
        // token, including POSIX network-path form //host/share, is untrusted.
        result = result.replace(ABSOLUTE_PATH, "<sanitized-path>")
        return result
    }

    private fun canonicalVirtualUri(token: String): String? = runCatching {
        val lower = token.lowercase()
        if (!lower.startsWith("workspace://") && !lower.startsWith("project://")) return null
        val executionRelative = pathMapper.toExecutionRelative(token)
        pathMapper.toVirtualPath(executionRelative)
    }.getOrNull()

    private companion object {
        // Any token beginning at a filesystem root is treated as sensitive;
        // the preceding-character guard prevents the slash in 3/4 and the
        // second slash in https:// from being mistaken for a host path. URI
        // tokens (including file:/data and custom:/srv) are scrubbed first, so
        // a colon can safely be treated as a scheme boundary here.
        // This is deliberately not a list of common root directories.
        val ABSOLUTE_PATH = Regex("""(?<![A-Za-z0-9_./:-])//?[^\s'\"<>]*""")
        // Match both hierarchical (scheme://, scheme:/) and opaque/nested
        // forms (jar:file:/...).  Requiring at least one non-space character
        // after ':' avoids treating ordinary prose such as "note: text" as a
        // URI while still fail-closing every path-bearing URI spelling.
        val URI_TOKEN = Regex("""(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9+.-]*):[^\s'\"<>]+""")
        val WINDOWS_ABSOLUTE_PATH = Regex("""(?<![A-Za-z0-9_])[A-Za-z]:[\\/][^\s'\"<>]*""")
        val UNC_ABSOLUTE_PATH = Regex("""(?<![A-Za-z0-9_])\\\\[^\s'\"<>]*""")

        fun isUnder(root: Path, candidate: Path): Boolean =
            candidate == root || candidate.startsWith(root)
    }
}

