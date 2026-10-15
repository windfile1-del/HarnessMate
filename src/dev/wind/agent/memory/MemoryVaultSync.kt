package dev.wind.agent.memory

import android.content.Context
import android.net.Uri
import dev.wind.agent.KeyedWorkQueue
import dev.wind.agent.session.SessionVault
import dev.wind.agent.session.SessionVaultSync
import dev.wind.agent.workspace.SafWorkspaceFs
import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONObject

/** Verified immutable snapshots and a separate deletion barrier, on the existing SAF tree. */
class MemoryVault(private val fs: WorkspaceFs) {
    private val root = "${SessionVault.ROOT}/memories"
    private fun read(path: String): String = fs.openInputStream(path).use { input ->
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0)break;check(out.size().toLong()+n<=12*1024*1024);out.write(buffer,0,n) };out.toString("UTF-8")
    }
    private fun decode(path: String): JSONObject? = runCatching {
        val wrapper=JSONObject(read(path));val body=wrapper.getString("body")
        require(wrapper.getString("checksum")==MemoryRules.hash(body));JSONObject(body).also {
            MemorySnapshot.validate(it)
            require(path.substringAfterLast('/').startsWith("${it.getString("namespace")}--${it.getLong("generation")}-"))
        }
    }.getOrNull()
    private fun publish(path: String, body: JSONObject) {
        val text=body.toString();val wrapper=JSONObject().put("body",text).put("checksum",MemoryRules.hash(text)).toString()
        fs.write(path,wrapper.toByteArray(Charsets.UTF_8));check(read(path)==wrapper) { "MEMORY_BACKUP_VERIFY_FAILED" }
    }
    fun backup(snapshot: JSONObject) {
        check(fs.mkdir(SessionVault.ROOT) && fs.mkdir(root) && fs.mkdir("$root/catalogs") && fs.mkdir("$root/tombstones"))
        val ns=snapshot.getString("namespace");require(MemoryRules.id(ns))
        val g=snapshot.getLong("generation");val digest=MemoryRules.hash(snapshot.toString()).take(16)
        // Publish barriers before data. Old complete snapshots cannot resurrect forgotten entries.
        val barrier=JSONObject(snapshot.toString()).put("items",org.json.JSONArray())
        publish("$root/tombstones/$ns--$g-$digest.json",barrier)
        publish("$root/catalogs/$ns--$g-$digest.json",snapshot)
        // Remove content-bearing old generations after verification; a failed deletion is reported.
        for(entry in fs.list("$root/catalogs").filter { !it.isDirectory && it.name.startsWith("$ns--") }) {
            if(entry.name != "$ns--$g-$digest.json")check(fs.delete(entry.path)) { "MEMORY_OLD_BACKUP_CLEANUP_PENDING" }
        }
        // Keep the newest verified barrier; never delete it before publishing the replacement.
        for(entry in fs.list("$root/tombstones").filter { !it.isDirectory && it.name.startsWith("$ns--") }) {
            if(entry.name != "$ns--$g-$digest.json")check(fs.delete(entry.path)) { "MEMORY_BARRIER_CLEANUP_PENDING" }
        }
    }
    fun restore(store: MemoryStore): Int {
        if(!fs.exists(root))return 0
        fun records(sub: String) = if(fs.exists("$root/$sub"))fs.list("$root/$sub").filter { !it.isDirectory && it.name.matches(Regex("[A-Za-z0-9_-]+--[0-9]+-[a-f0-9]{16}\\.json")) }.mapNotNull { decode(it.path) } else emptyList()
        val catalogs=records("catalogs");val barriers=records("tombstones")
        val namespaces=(catalogs+barriers).map { it.getString("namespace") }.distinct()
        check(namespaces.size<=1) { "MEMORY_VAULT_NAMESPACE_CONFLICT" }
        // All valid barriers first, then the latest complete catalog. Interrupted writes remain ignored.
        barriers.sortedBy { it.getLong("generation") }.forEach { store.restore(it) }
        return catalogs.maxByOrNull { it.getLong("generation") }?.let { store.restore(it) } ?: 0
    }
}

object MemoryVaultSync {
    private val queue=KeyedWorkQueue()
    private fun prefs(c: Context)=c.getSharedPreferences("harness-memory-status",Context.MODE_PRIVATE)
    fun status(c: Context)=prefs(c).getString("status","本地记忆已保存；尚未同步副本") ?: ""
    private fun vault(c: Context)=SessionVaultSync.uri(c)?.let { MemoryVault(SessionVault.dataWorkspace(SafWorkspaceFs(c,Uri.parse(it)))) }
    fun schedule(c: Context) {
        if(!SessionVaultSync.ready(c))return
        val app=c.applicationContext
        queue.submit("memory", coalesce=true) { sync(app,false) }
    }
    fun sync(c: Context, restore: Boolean): Int=SessionVaultSync.withDataLock(c) {
        if(!restore && !SessionVaultSync.ready(c))return@withDataLock 0
        val v=vault(c) ?: return@withDataLock 0
        try {
            MemoryStore(c).use { store ->
                val count=if(restore)v.restore(store) else 0
                if(store.dirty()) { val snapshot=store.export();v.backup(snapshot);store.markBackedUp(snapshot.getLong("generation")) }
                prefs(c).edit().putString("status","记忆副本已同步；旧世代清理完成").apply();count
            }
        } catch(e: Exception) {
            prefs(c).edit().putString("status","本地已保存；副本同步/清理待重试：${e.message?.take(200)}").apply()
            if(restore)throw e else 0
        }
    }
}
