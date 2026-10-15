package dev.wind.agent.mate

import android.content.Context
import android.net.Uri
import dev.wind.agent.KeyedWorkQueue
import dev.wind.agent.memory.MemoryRules
import dev.wind.agent.session.SessionVault
import dev.wind.agent.session.SessionVaultSync
import dev.wind.agent.workspace.SafWorkspaceFs
import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONObject

/** Verified complete snapshots plus deletion barriers in the user-selected independent directory. */
class MateTaskVault(private val fs: WorkspaceFs) {
    private val root="${SessionVault.ROOT}/tasks"
    private fun read(path: String)=fs.openInputStream(path).use {input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size().toLong()+n<=MateTaskStore.MAX_BYTES+1024*1024);out.write(buffer,0,n)};out.toString("UTF-8")}
    private fun publish(path: String,data: JSONObject) {
        val body=data.toString();val envelope=JSONObject().put("body",body).put("checksum",MemoryRules.hash(body)).toString()
        fs.write(path,envelope.toByteArray(Charsets.UTF_8));check(read(path)==envelope) {"MATE_TASK_BACKUP_VERIFY_FAILED"}
    }
    private fun decode(path: String): JSONObject?=runCatching {
        val wrapper=JSONObject(read(path));val text=wrapper.getString("body");check(wrapper.getString("checksum")==MemoryRules.hash(text))
        JSONObject(text).also {j->require(j.getInt("format")==1 && MemoryRules.id(j.getString("namespace")) && j.getLong("generation")>0)
            require(path.substringAfterLast('/').startsWith("${j.getString("namespace")}--${j.getLong("generation")}-"))}
    }.getOrNull()
    fun backup(snapshot: JSONObject) {
        val ns=snapshot.getString("namespace");require(MemoryRules.id(ns));val g=snapshot.getLong("generation")
        check(fs.mkdir(SessionVault.ROOT) && fs.mkdir(root) && fs.mkdir("$root/catalogs") && fs.mkdir("$root/tombstones"))
        val file="$ns--$g-${MemoryRules.hash(snapshot.toString()).take(16)}.json"
        publish("$root/tombstones/$file",JSONObject(snapshot.toString()).put("tasks",org.json.JSONArray()))
        publish("$root/catalogs/$file",snapshot)
        for(sub in listOf("catalogs","tombstones"))for(old in fs.list("$root/$sub").filter { !it.isDirectory && it.name.startsWith("$ns--") && it.name!=file })
            check(fs.delete(old.path)) {"MATE_TASK_OLD_BACKUP_CLEANUP_PENDING"}
    }
    fun restore(store: MateTaskStore): Int {
        if(!fs.exists(root))return 0
        fun versions(sub: String)=if(fs.exists("$root/$sub"))fs.list("$root/$sub").filter { !it.isDirectory && it.name.matches(Regex("[A-Za-z0-9_-]+--[0-9]+-[a-f0-9]{16}\\.json")) }.mapNotNull {decode(it.path)} else emptyList()
        val barriers=versions("tombstones");val catalogs=versions("catalogs")
        check((barriers+catalogs).map {it.getString("namespace")}.distinct().size<=1) {"MATE_TASK_VAULT_NAMESPACE_CONFLICT"}
        barriers.sortedBy {it.getLong("generation")}.forEach {store.restore(it)}
        return catalogs.maxByOrNull {it.getLong("generation")}?.let {store.restore(it)} ?: 0
    }
}
object MateTaskVaultSync {
    private val queue=KeyedWorkQueue()
    fun schedule(c: Context) {if(!SessionVaultSync.ready(c))return;val app=c.applicationContext;queue.submit("mate-tasks",coalesce=true) {sync(app,false)}}
    fun sync(c: Context,restore: Boolean): Int=SessionVaultSync.withDataLock(c) {
        if(!restore && !SessionVaultSync.ready(c))return@withDataLock 0
        val uri=SessionVaultSync.uri(c) ?: return@withDataLock 0
        try {
            val vault=MateTaskVault(SessionVault.dataWorkspace(SafWorkspaceFs(c,Uri.parse(uri))));val repo=MateTaskCoordinator.store(c)
            val count=if(restore)vault.restore(repo) else 0
            if(repo.dirty()){val snapshot=repo.snapshot();vault.backup(snapshot);repo.backedUp(snapshot.getLong("generation"))};count
        } catch(e: Exception) {SessionVaultSync.setStatus(c,"会话本地已保存；生活任务副本待重试：${e.message?.take(160)}");if(restore)throw e else 0}
    }
}
