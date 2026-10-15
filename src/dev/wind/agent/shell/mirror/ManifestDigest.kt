package dev.wind.agent.shell.mirror

import org.json.JSONArray
import java.security.MessageDigest

/** Deterministic, payload-free digest for a manifest snapshot. */
object ManifestDigest {
    private val DIGEST = Regex("[0-9a-f]{64}")

    fun isDigest(value: String): Boolean = DIGEST.matches(value)

    fun of(entries: Map<String, ManifestEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.toSortedMap().forEach { (path, entry) ->
            append(digest, path)
            append(digest, if (entry.directory) "D" else "F")
            // Directory allocation size is provider metadata, not content:
            // SAF can report 4096 while the runtime reports zero.
            append(digest, if (entry.directory) "0" else entry.size.toString())
            // Provider mtimes are not portable across SAF and the Termux
            // mirror: copying a file is allowed to assign a new mtime.  The
            // mirror identity is therefore content based (path, type, size,
            // and file hash), while mtime remains useful diagnostic metadata.
            append(digest, if (entry.directory) "-" else entry.sha256 ?: "-")
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun of(manifest: WorkspaceManifest): String = of(manifest.entries)

    fun ofJsonArray(array: JSONArray?): String {
        val entries = linkedMapOf<String, ManifestEntry>()
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val path = obj.getString("path")
                val directory = obj.getBoolean("directory")
                val hash = obj.optString("sha256", "").takeIf { isDigest(it) }
                require(directory || hash != null) { "MIRROR_STATE_MISSING_HASH" }
                require(entries.put(path, ManifestEntry(
                    path,
                    directory,
                    obj.getLong("size"),
                    obj.optLong("modified", 0L),
                    hash
                )) == null) { "MIRROR_STATE_DUPLICATE_PATH" }
            }
        }
        return of(entries)
    }

    private fun append(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
}
