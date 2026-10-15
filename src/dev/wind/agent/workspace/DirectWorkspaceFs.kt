package dev.wind.agent.workspace

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Android still enforces storage access. Canonical paths prevent symlink escapes. */
class DirectWorkspaceFs(private val context: Context?, private val root: File = Environment.getExternalStorageDirectory()) : WorkspaceFs {
    private fun resolve(path: String): File {
        val base = root.canonicalFile
        val file = File(base, WorkspacePath.sanitize(path)).canonicalFile
        check(file == base || file.path.startsWith(base.path + File.separator)) { "FILE_PATH_ESCAPE" }
        val privateRoot = context?.applicationInfo?.dataDir?.let { File(it).canonicalPath }
        check(privateRoot == null || !(file.path == privateRoot || file.path.startsWith(privateRoot + "/"))) { "APP_PRIVATE_DATA_DENIED" }
        return file
    }
    fun relative(absolute: String): String {
        val file = File(absolute).canonicalFile
        val base = root.canonicalFile
        check(file == base || file.path.startsWith(base.path + File.separator)) { "ANDROID_USER_STORAGE_ONLY" }
        return file.relativeTo(base).path.replace('\\', '/').let { if (it == ".") "" else it }.also(::resolve)
    }
    private fun entry(file: File) = WorkspaceEntry(file.relativeTo(root.canonicalFile).path.replace('\\', '/'), file.name, file.isDirectory, file.length(), file.lastModified())
    override fun isConfigured() = root.isDirectory
    override fun getRootDisplayName() = root.absolutePath
    override fun list(path: String): List<WorkspaceEntry> = (resolve(path).listFiles() ?: error("ANDROID_DIRECTORY_ACCESS_DENIED")).mapNotNull { file -> runCatching { resolve(file.relativeTo(root.canonicalFile).path); entry(file) }.getOrNull() }
    override fun read(path: String): ByteArray { val f = resolve(path); require(f.length() <= SafWorkspaceFs.MAX_FILE_SIZE_BYTES); return f.readBytes() }
    override fun readText(path: String, charset: String) = String(read(path), java.nio.charset.Charset.forName(charset))
    override fun write(path: String, data: ByteArray) {
        require(data.size <= SafWorkspaceFs.MAX_FILE_SIZE_BYTES)
        val f = resolve(path); check(f.parentFile?.isDirectory == true || f.parentFile?.mkdirs() == true)
        val temp = File.createTempFile(".agent-write-", ".tmp", f.parentFile)
        try {
            FileOutputStream(temp).use { it.write(data); it.fd.sync() }
            if (!temp.renameTo(f)) {
                // Android rename replaces atomically. Windows host fixtures require a preserved pre-image.
                check(context == null && f.isFile) { "ATOMIC_WRITE_FAILED" }
                val before = File.createTempFile(".agent-before-", ".tmp", f.parentFile)
                check(before.delete() && f.renameTo(before)) { "HOST_REPLACE_STAGE_FAILED" }
                if (!temp.renameTo(f)) {
                    check(before.renameTo(f)) { "HOST_REPLACE_RECOVERY_FAILED: ${before.path}" }
                    error("ATOMIC_WRITE_FAILED")
                }
                check(before.delete()) { "HOST_REPLACE_CLEANUP_FAILED" }
            }
        } finally { temp.delete() }
    }
    override fun writeText(path: String, text: String, charset: String) = write(path, text.toByteArray(java.nio.charset.Charset.forName(charset)))
    override fun mkdir(path: String) = resolve(path).let { it.isDirectory || it.mkdirs() }
    override fun delete(path: String): Boolean { require(path.isNotBlank()) { "ROOT_DELETE_DENIED" }; return resolve(path).delete() }
    override fun move(from: String, to: String): Boolean { require(from.isNotBlank() && to.isNotBlank()); val dst = resolve(to); require(!dst.exists()); return resolve(from).renameTo(dst) }
    override fun exists(path: String) = resolve(path).exists()
    override fun stat(path: String) = resolve(path).takeIf { it.exists() }?.let(::entry)
    private fun scan(scope: String, limit: Int, match: (WorkspaceEntry) -> Boolean): List<WorkspaceEntry> {
        val out = mutableListOf<WorkspaceEntry>(); val queue = java.util.ArrayDeque<String>(); val seen = mutableSetOf<String>(); queue.add(scope)
        var visited = 0
        while (queue.isNotEmpty() && out.size < limit.coerceIn(1, 500) && visited++ < 10000) {
            check(!Thread.currentThread().isInterrupted) { "CANCELLED" }
            val current = queue.removeFirst(); if (!seen.add(resolve(current).canonicalPath)) continue
            for (e in list(current)) { if (match(e)) out.add(e); if (out.size >= limit) break; if (e.isDirectory) queue.add(e.path) }
        }
        return out
    }
    override fun search(query: String, maxResults: Int, scopePath: String) = scan(scopePath, maxResults) { it.name.contains(query, true) }
    override fun glob(pattern: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> {
        val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
        return scan(scopePath, maxResults) { regex.matches(it.path) || regex.matches(it.name) }
    }
    override fun openInputStream(path: String): InputStream = resolve(path).inputStream()
    override fun openOutputStream(path: String): OutputStream = resolve(path).outputStream()
}
