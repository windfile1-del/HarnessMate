package dev.wind.agent.session

import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Only user-uploaded att_* cache files. Never exports arbitrary app-private paths or screen captures. */
class SessionAttachments(private val fs: WorkspaceFs, cache: File) {
    private val cache = cache.canonicalFile
    private val vault = SessionVault(fs)
    private val root = "${SessionVault.ROOT}/attachments"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun safeName(name: String) = name.startsWith("att_") && name.length <= 255 &&
        name.none { it < ' ' || it == '/' || it == '\\' } && name !in listOf(".", "..")
    fun backup(payload: String) {
        val chat = JSONObject(payload); val messages = chat.optJSONArray("messages") ?: return
        if(chat.optString("retentionPolicy")=="TEMPORARY")return
        val revision = chat.getLong("updatedAt")
        val known = if (fs.exists(root)) fs.list(root).filter { it.name.startsWith("map-") && it.name.endsWith(".json") }
            .mapNotNull { runCatching { JSONObject(String(vault.readBytes(it.path, 16384), Charsets.UTF_8)).takeIf { j -> j.optInt("format") == 1 && safeName(j.optString("name")) && j.optString("sha256").matches(Regex("[a-f0-9]{64}")) } }.getOrNull() }
            .groupBy { it.optString("name") }.mapValues { it.value.maxByOrNull { j -> j.optLong("revision") }!! }
            else emptyMap()
        for (i in 0 until messages.length()) {
            val attachments = messages.optJSONObject(i)?.optJSONArray("attachments") ?: continue
            for (a in 0 until attachments.length()) {
                val path = attachments.optJSONObject(a)?.optString("path").orEmpty()
                if (path.isEmpty()) continue
                val file = File(path).canonicalFile
                if (file.parentFile != cache || !safeName(file.name)) continue
                if (!file.isFile) {
                    check(known.containsKey(file.name)) { "缓存附件已缺失，文字会话已备份：${file.name}" }
                    continue
                }
                check(file.length() <= SessionVault.MAX_BYTES) { "附件 ${file.name} 超过长期备份单文件上限" }
                val bytes = file.readBytes(); val digest = hash(bytes)
                check(fs.mkdir(SessionVault.ROOT) && fs.mkdir(root))
                val blob = "$root/blob-$digest"
                if (!fs.exists(blob)) fs.write(blob, bytes)
                check(hash(vault.readBytes(blob)) == digest) { "长期附件写入校验失败" }
                if (known[file.name]?.optString("sha256") == digest && known[file.name]?.optLong("size") == bytes.size.toLong()) continue
                val metadata = JSONObject().put("format",1).put("name",file.name).put("revision",revision)
                    .put("sha256",digest).put("size",bytes.size).toString()
                val pointer = "$root/map-${SessionVault.hash(file.name).take(16)}-$revision-${digest.take(16)}.json"
                if (!fs.exists(pointer) || vault.read(pointer) != metadata) fs.write(pointer,metadata.toByteArray(Charsets.UTF_8))
                check(vault.read(pointer)==metadata) { "长期附件索引写入校验失败" }
            }
        }
    }
    fun restore() {
        if (!fs.exists(root)) return
        cache.mkdirs()
        val restored = mutableSetOf<String>()
        for (entry in fs.list(root).filter { it.name.startsWith("map-") && it.name.endsWith(".json") }
            .sortedByDescending { it.name.removePrefix("map-").substringAfter('-').substringBefore('-').toLongOrNull() ?: 0 }) {
            val record = runCatching { JSONObject(String(vault.readBytes(entry.path, 16384), Charsets.UTF_8)) }.getOrNull() ?: continue
            val name = record.optString("name"); val digest = record.optString("sha256")
            if (record.optInt("format")!=1 || !safeName(name) || !digest.matches(Regex("[a-f0-9]{64}")) || name in restored) continue
            val bytes = runCatching { vault.readBytes("$root/blob-$digest") }.getOrNull() ?: continue
            if (bytes.size.toLong()!=record.optLong("size") || hash(bytes)!=digest) continue
            val target = File(cache,name).canonicalFile; require(target.parentFile==cache)
            if (target.exists()) {
                check(target.length() <= SessionVault.MAX_BYTES && hash(target.readBytes())==digest) { "附件缓存存在同名冲突，原文件保留：$name" }
            } else {
                val temp = File(cache,".restore-${System.nanoTime()}")
                try { temp.outputStream().use { out -> out.write(bytes); out.fd.sync() }; check(temp.renameTo(target)) }
                finally { temp.delete() }
            }
            restored.add(name)
        }
    }
}
