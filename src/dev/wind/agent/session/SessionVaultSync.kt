package dev.wind.agent.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.wind.agent.ChatStore
import dev.wind.agent.KeyedWorkQueue
import dev.wind.agent.workspace.SafWorkspaceFs
import java.io.File

/** Independent backup lane: a slow documents provider cannot block a model or a local save. */
object SessionVaultSync {
    private val queue = KeyedWorkQueue()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val dataLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    fun <T> withDataLock(c: Context,action: ()->T): T=synchronized(dataLocks.getOrPut(c.applicationContext.filesDir.absolutePath){Any()}){action()}
    private fun prefs(c: Context) = c.getSharedPreferences("session-vault", Context.MODE_PRIVATE)
    fun uri(c: Context): String? = runCatching { prefs(c).getString("tree", null) }.getOrNull()
    fun ready(c: Context)=uri(c)!=null && !prefs(c).getBoolean("restore_required",false)
    fun status(c: Context): String = runCatching { prefs(c).getString("status", "尚未选择长期数据目录") ?: "" }.getOrDefault("目录状态不可用")
    fun setStatus(c: Context, text: String) { runCatching { prefs(c).edit().putString("status", text.take(500)).apply() } }
    fun vault(c: Context): SessionVault? = uri(c)?.let { SessionVault(SessionVault.dataWorkspace(SafWorkspaceFs(c, Uri.parse(it)))) }
    fun configure(c: Context, tree: Uri)=withDataLock(c) {
        c.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val candidate = SessionVault(SessionVault.dataWorkspace(SafWorkspaceFs(c, tree))); candidate.prepare()
        check(prefs(c).edit().putString("tree", tree.toString()).putBoolean("restore_required",true).commit())
    }
    fun publish(c: Context, id: String, revision: Long, payload: String?) {
        if(payload!=null && org.json.JSONObject(payload).optString("retentionPolicy")=="TEMPORARY")return
        if (!ready(c)) return
        val app = c.applicationContext
        setStatus(app, "正在同步长期会话副本")
        pending[id] = revision
        queue.submit(id, coalesce = payload != null) {
            try {
                withDataLock(app) {
                    if(ready(app)) {
                        vault(app)?.publish(id, revision, payload)
                        if (payload != null) SessionAttachments(SessionVault.dataWorkspace(SafWorkspaceFs(app, Uri.parse(uri(app)!!))), app.cacheDir).backup(payload)
                    }
                }
                failures.remove(id)
            }
            catch (e: Exception) { failures[id] = e.message.orEmpty() }
            finally {
                pending.remove(id, revision)
                setStatus(app, when {
                    failures.isNotEmpty() -> "本地已保存；${failures.size} 个长期副本同步失败：${failures.values.firstOrNull()}"
                    pending.isNotEmpty() -> "正在同步 ${pending.size} 个长期会话副本"
                    else -> "长期会话副本已同步"
                })
            }
        }
    }
    fun restoreAndSync(c: Context): Int=withDataLock(c) {
        val store = ChatStore(c); val vault = vault(c) ?: return@withDataLock 0
        check(prefs(c).edit().putBoolean("restore_required",true).commit())
        vault.prepare()
        // Adopt durable namespaces before restored deletion/save hooks can dirty fresh stores.
        dev.wind.agent.memory.MemoryVaultSync.sync(c,restore=true)
        dev.wind.agent.mate.MateTaskVaultSync.sync(c,restore=true)
        SessionAttachments(SessionVault.dataWorkspace(SafWorkspaceFs(c, Uri.parse(uri(c)!!))), c.cacheDir).restore()
        var restored = 0
        for (record in vault.records()) restored += store.restoreRecord(record)
        check(ChatStore.flushPendingWrites(10000)) { "本地会话保存尚未完成，请稍后重试同步" }
        for (summary in store.listSummaries() + store.listSummaries(archived = true)) {
            store.load(summary.id)?.let { chat -> val json = chat.toJson(); val payload = json.toString()
                vault.publish(chat.id, json.getLong("updatedAt"), payload)
                SessionAttachments(SessionVault.dataWorkspace(SafWorkspaceFs(c, Uri.parse(uri(c)!!))), c.cacheDir).backup(payload)
            }
        }
        File(c.filesDir, "chats").listFiles()?.filter { it.name.startsWith(".deleted-") }?.forEach { f ->
            val id = f.name.removePrefix(".deleted-"); f.readText().trim().toLongOrNull()?.let { vault.publish(id, it, null) }
        }
        syncSkills(c, restore = true)
        check(prefs(c).edit().putBoolean("restore_required",false).commit())
        dev.wind.agent.memory.MemoryVaultSync.sync(c,restore=false)
        dev.wind.agent.mate.MateTaskVaultSync.sync(c,restore=false)
        failures.clear()
        setStatus(c, "已恢复 $restored 个会话；长期数据已同步")
        restored
    }
    private val skillLock = Any()
    fun syncSkills(c: Context, restore: Boolean = false) = synchronized(skillLock) {
        val v = vault(c) ?: return@synchronized; val fs = SessionVault.dataWorkspace(SafWorkspaceFs(c, Uri.parse(uri(c)!!))); v.prepare()
        dev.wind.agent.skills.SkillStore(c).list()
        val local = File(c.filesDir, "skills/installed").apply { mkdirs() }
        if (restore) fs.list("${SessionVault.ROOT}/skills/installed").filter { !it.isDirectory && it.name.matches(Regex("\\.deleted-skill-[0-9a-f-]{36}")) }.forEach { marker ->
            File(local, marker.name).writeText("deleted")
            File(local, marker.name.removePrefix(".deleted-")).takeIf { it.isDirectory }?.deleteRecursively()
        }
        local.listFiles()?.filter { it.name.startsWith(".deleted-skill-") }?.forEach { marker ->
            fs.write("${SessionVault.ROOT}/skills/installed/${marker.name}", "deleted".toByteArray())
        }
        if (restore) fs.list("${SessionVault.ROOT}/skills/installed").filter { it.isDirectory && it.name.matches(Regex("skill-[0-9a-f-]{36}")) }.forEach { dir ->
            val target = File(local, dir.name)
            if (!target.exists() && !File(local, ".deleted-${dir.name}").exists()) {
                val stage = File(local, ".restore-${dir.name}").apply { mkdirs() }; var count = 0; var total = 0L
                fun copy(path: String, to: File, depth: Int = 0) {
                    require(depth <= 128) { "SKILL_DIRECTORY_DEPTH_LIMIT" }
                    fs.list(path).forEach { entry ->
                        require(entry.name.isNotBlank() && entry.name !in listOf(".", "..") && !entry.name.contains('/') && !entry.name.contains('\\'))
                        val file = File(to, entry.name)
                        if (entry.isDirectory) { file.mkdirs(); copy(entry.path, file, depth + 1) }
                        else { require(++count <= 513); val data = v.readBytes(entry.path, dev.wind.agent.skills.SkillStore.MAX_FILE_BYTES); total += data.size
                            require(data.size <= dev.wind.agent.skills.SkillStore.MAX_FILE_BYTES && total <= dev.wind.agent.skills.SkillStore.MAX_TOTAL_BYTES + 16384); file.writeBytes(data) }
                    }
                }
                try { copy(dir.path, stage); check(File(stage,"entry.json").isFile); check(stage.renameTo(target)) }
                finally { if (stage.exists()) stage.deleteRecursively() }
            }
        }
        local.listFiles()?.filter { it.isDirectory && !File(local, ".deleted-${it.name}").exists() && it.name.matches(Regex("skill-[0-9a-f-]{36}")) }?.forEach { dir ->
            fun copy(from: File, path: String) {
                check(fs.mkdir(path)); from.listFiles()?.forEach { f ->
                    if (f.isDirectory) copy(f, "$path/${f.name}") else fs.write("$path/${f.name}", f.readBytes())
                }
            }; copy(dir, "${SessionVault.ROOT}/skills/installed/${dir.name}")
        }
        check(fs.mkdir("${SessionVault.ROOT}/skills/builtin"))
        c.assets.open("skills/HarnessMate.md").use { fs.write("${SessionVault.ROOT}/skills/builtin/SKILL.md", it.readBytes()) }
    }
}
