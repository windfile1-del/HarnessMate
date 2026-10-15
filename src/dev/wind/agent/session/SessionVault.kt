package dev.wind.agent.session

import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONObject
import java.security.MessageDigest

/** Immutable, verified generations. A torn SAF write never replaces the last good generation. */
class SessionVault(private val fs: WorkspaceFs) {
    data class Record(val id: String, val revision: Long, val payload: String?, val checksum: String) {
        val deleted get() = payload == null
    }
    companion object {
        const val ROOT = "HarnessMate"
        const val MAX_BYTES = 64 * 1024 * 1024
        fun dataWorkspace(fs: WorkspaceFs): WorkspaceFs {
            val marked = runCatching { fs.openInputStream("vault.json").use { input ->
                val buffer = ByteArray(4096); val n = input.read(buffer)
                n > 0 && JSONObject(String(buffer,0,n,Charsets.UTF_8)).optString("kind") == "harnessmate-session-vault"
            } }.getOrDefault(false)
            val legacy = fs.getRootDisplayName() == ROOT && fs.exists("sessions") && fs.exists("skills")
            return if (marked || legacy) VaultRootFs(fs) else fs
        }
        fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,160}"))
        fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun prepare() {
        check(fs.mkdir(ROOT) && fs.mkdir("$ROOT/sessions") && fs.mkdir("$ROOT/skills") && fs.mkdir("$ROOT/skills/installed")) { "长期数据目录不可写" }
        if (!fs.exists("$ROOT/vault.json")) fs.write("$ROOT/vault.json", JSONObject().put("kind","harnessmate-session-vault").put("format",1).toString().toByteArray(Charsets.UTF_8))
    }
    fun read(path: String): String = String(readBytes(path), Charsets.UTF_8)
    fun readBytes(path: String, limit: Int = MAX_BYTES): ByteArray = fs.openInputStream(path).use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break
            check(out.size().toLong() + n <= limit) { "会话文件超过读取上限" }; out.write(buffer, 0, n)
        }; out.toByteArray()
    }
    private fun parse(path: String): Record? = runCatching {
                val j = JSONObject(read(path)); require(j.optInt("format") == 1)
                val id = j.getString("id"); require(validId(id))
                val revision = j.getLong("revision"); require(revision > 0 && revision < Long.MAX_VALUE)
                require(j.getString("kind") in listOf("deleted", "chat"))
                val payload = if (j.getString("kind") == "deleted") null else j.getString("payload")
                val digest = j.getString("checksum"); require(digest == hash(payload ?: "deleted:$id:$revision"))
                payload?.let { val c = JSONObject(it); require(c.getString("id") == id && c.getLong("updatedAt") == revision) }
                Record(id, revision, payload, digest)
    }.getOrNull()
    fun records(): Sequence<Record> = sequence {
        if (!fs.exists("$ROOT/sessions")) return@sequence
        val groups = fs.list("$ROOT/sessions").filter { !it.isDirectory && it.name.matches(Regex("[A-Za-z0-9_-]+--[0-9]+-[a-f0-9]{16}\\.json")) }
            .groupBy { it.name.substringBeforeLast("--") }
        for ((id, files) in groups) {
            var latest = -1L; val seen = mutableSetOf<String>()
            for (entry in files.sortedByDescending { it.name.substringAfterLast("--").substringBefore('-').toLongOrNull() ?: 0 }) {
                val record = parse(entry.path) ?: continue
                if (record.id != id) continue
                if (latest >= 0 && record.revision < latest) break
                latest = record.revision
                if (seen.add(record.checksum)) yield(record)
            }
        }
    }
    fun publish(id: String, revision: Long, payload: String?) {
        require(validId(id) && revision > 0 && revision < Long.MAX_VALUE); prepare()
        require(payload == null || payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES / 2)
        payload?.let { val chat = JSONObject(it); require(chat.getString("id") == id && chat.getLong("updatedAt") == revision) }
        val digest = hash(payload ?: "deleted:$id:$revision")
        val path = "$ROOT/sessions/$id--$revision-${digest.take(16)}.json"
        val record = JSONObject().put("format", 1).put("id", id).put("revision", revision)
            .put("kind", if (payload == null) "deleted" else "chat").put("checksum", digest)
        payload?.let { record.put("payload", it) }
        val text = record.toString()
        if (!fs.exists(path) || read(path) != text) fs.write(path, text.toByteArray(Charsets.UTF_8))
        check(read(path) == text) { "长期数据写入校验失败；旧副本仍保留" }
        // Keep two generations; retain same-revision conflicts for explicit recovery.
        val prefix = "$id--"
        val old = fs.list("$ROOT/sessions").filter { !it.isDirectory && it.name.startsWith(prefix) }
        val valid = old.mapNotNull { entry -> parse(entry.path)?.takeIf { it.id == id }?.let { entry to it.revision } }
        val revisions = valid.map { it.second }.distinct().sortedDescending()
        val keep = revisions.take(2).toSet()
        valid.filter { it.second !in keep }.forEach { runCatching { fs.delete(it.first.path) } }
    }
}

/** The selected tree can be the parent of HarnessMate or the existing vault itself. */
private class VaultRootFs(private val fs: WorkspaceFs) : WorkspaceFs by fs {
    private fun relative(path: String): String {
        if (path.isEmpty() || path == SessionVault.ROOT) return ""
        require(path.startsWith("${SessionVault.ROOT}/")) { "VAULT_PATH_INVALID" }
        return path.removePrefix("${SessionVault.ROOT}/")
    }
    private fun entry(e: dev.wind.agent.workspace.WorkspaceEntry) = e.copy(path = "${SessionVault.ROOT}/" + e.path)
    override fun list(path: String) = fs.list(relative(path)).map(::entry)
    override fun read(path: String) = fs.read(relative(path))
    override fun readText(path: String, charset: String) = fs.readText(relative(path), charset)
    override fun write(path: String, data: ByteArray) = fs.write(relative(path),data)
    override fun writeText(path: String, text: String, charset: String) = fs.writeText(relative(path),text,charset)
    override fun mkdir(path: String) = fs.mkdir(relative(path))
    override fun delete(path: String) = fs.delete(relative(path))
    override fun move(from: String, to: String) = fs.move(relative(from),relative(to))
    override fun exists(path: String) = fs.exists(relative(path))
    override fun stat(path: String) = fs.stat(relative(path))?.let(::entry)
    override fun search(query: String, maxResults: Int, scopePath: String) = fs.search(query,maxResults,relative(scopePath)).map(::entry)
    override fun glob(pattern: String, maxResults: Int, scopePath: String) = fs.glob(pattern,maxResults,relative(scopePath)).map(::entry)
    override fun openInputStream(path: String) = fs.openInputStream(relative(path))
    override fun openOutputStream(path: String) = fs.openOutputStream(relative(path))
}
