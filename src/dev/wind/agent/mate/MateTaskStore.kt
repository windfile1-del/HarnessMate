package dev.wind.agent.mate

import dev.wind.agent.memory.MemoryRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One fsynced atomic catalog commits task data and event watermarks together; no capability is stored. */
class MateTaskStore(private val root: File,private val persistent: Boolean=true) {
    companion object {
        private val locks=ConcurrentHashMap<String,Any>()
        private val ephemeral=ConcurrentHashMap<String,String>()
        const val MAX_BYTES=16*1024*1024
    }
    private val key=root.canonicalPath
    private val lock=locks.computeIfAbsent(key) {Any()}
    private val file=File(root,"catalog-v1.json")
    private fun initial()=JSONObject().put("format",1).put("namespace","mate_"+UUID.randomUUID()).put("generation",1).put("dirty",true).put("tasks",JSONArray()).put("tombstones",JSONObject())
    private fun read(): JSONObject {
        val text=if(persistent)file.takeIf {it.isFile}?.let {require(it.length()<=MAX_BYTES);it.readText()} else ephemeral[key]
        return if(text==null)initial().also(::write) else JSONObject(text).also(::validate)
    }
    private fun write(data: JSONObject) {
        validate(data);val text=data.toString();val bytes=text.toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_BYTES) {"MATE_TASK_CATALOG_FULL"}
        if(!persistent) {ephemeral[key]=text;return}
        check(root.mkdirs() || root.isDirectory)
        val tmp=File(root,"catalog-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use {it.write(bytes);it.fd.sync()}
            if(!tmp.renameTo(file)) {
                // Windows host verification needs replace-existing; Android rename succeeds first.
                // Reflection keeps java.nio.file out of the API23 class linkage.
                val files=Class.forName("java.nio.file.Files");val path=Class.forName("java.nio.file.Path")
                val option=Class.forName("java.nio.file.CopyOption");val standard=Class.forName("java.nio.file.StandardCopyOption")
                val flags=java.lang.reflect.Array.newInstance(option,2)
                java.lang.reflect.Array.set(flags,0,standard.getField("ATOMIC_MOVE").get(null));java.lang.reflect.Array.set(flags,1,standard.getField("REPLACE_EXISTING").get(null))
                val toPath=File::class.java.getMethod("toPath")
                files.getMethod("move",path,path,flags.javaClass).invoke(null,toPath.invoke(tmp),toPath.invoke(file),flags)
            }
        } finally {tmp.delete()}
    }
    private fun validate(data: JSONObject) {
        require(data.getInt("format")==1 && MemoryRules.id(data.getString("namespace")) && data.getLong("generation")>0)
        val tasks=data.getJSONArray("tasks");val tombs=data.getJSONObject("tombstones");require(tasks.length()<=1000 && tombs.length()<=20000)
        val ids=HashSet<String>();for(i in 0 until tasks.length())require(ids.add(MateTask.parse(tasks.getJSONObject(i)).id))
        val keys=tombs.keys();while(keys.hasNext()){val id=keys.next();require(MemoryRules.id(id) && tombs.getLong(id)>0)}
    }
    private fun records(data: JSONObject): List<MateTask> {val a=data.getJSONArray("tasks");return (0 until a.length()).map {MateTask.parse(a.getJSONObject(it))}}
    fun list(chat: String?=null)=synchronized(lock) {records(read()).filter {chat==null || it.chatId==chat}.sortedByDescending {it.updatedAt}}
    fun get(id: String)=synchronized(lock) {records(read()).firstOrNull {it.id==id}}
    fun snapshot(): JSONObject=synchronized(lock) {JSONObject(read().toString()).apply {remove("dirty")}}
    fun dirty()=synchronized(lock) {read().optBoolean("dirty")}
    fun forgetTemporaryChat(chat: String)=synchronized(lock) {
        check(!persistent) {"MATE_PERSISTENT_DELETE_REQUIRES_TOMBSTONE"}
        val data=read();write(data.put("tasks",JSONArray(records(data).filterNot {it.chatId==chat}.map {it.json()})).put("dirty",false))
    }
    fun backedUp(generation: Long)=synchronized(lock) {val d=read();if(d.getLong("generation")==generation)write(d.put("dirty",false))}
    fun update(id: String,expected: Long?=null,action: (MateTask)->MateTask): MateTask=synchronized(lock) {
        val data=read();val all=records(data).toMutableList();val index=all.indexOfFirst {it.id==id};check(index>=0) {"MATE_TASK_NOT_FOUND"}
        val old=all[index];check(expected==null || old.revision==expected) {"MATE_TASK_CHANGED"}
        val proposed=action(old);require(proposed.id==old.id && proposed.chatId==old.chatId && proposed.createdAt==old.createdAt)
        if(proposed==old)return@synchronized old
        val changed=proposed.copy(revision=Math.addExact(old.revision,1),updatedAt=maxOf(System.currentTimeMillis(),old.updatedAt+1));MateTask.parse(changed.json())
        for(resource in changed.resources.filter {it.state in setOf("ACTIVE","PAUSED")})check(all.none {other->other.id!=id && other.resources.any {it.kind==resource.kind && it.id==resource.id && it.state in setOf("ACTIVE","PAUSED")} }) {"MATE_RESOURCE_OWNED_BY_ANOTHER_TASK"}
        all[index]=changed;write(data.put("tasks",JSONArray(all.map {it.json()})).put("generation",Math.addExact(data.getLong("generation"),1)).put("dirty",true));changed
    }
    fun create(id: String,chat: String,source: String,goal: String): MateTask=synchronized(lock) {
        val data=read();val all=records(data);all.firstOrNull {it.id==id}?.let {check(it.chatId==chat);return@synchronized it}
        check(!data.getJSONObject("tombstones").has(id)) {"MATE_TASK_DELETED"};check(all.size<1000) {"MATE_TASK_LIMIT"}
        val now=System.currentTimeMillis();val task=MateTask(id,chat,1,goal.take(2000),source,MateTaskState.CREATED,now,now)
        MateTask.parse(task.json());write(data.put("tasks",JSONArray((all+task).map {it.json()})).put("generation",Math.addExact(data.getLong("generation"),1)).put("dirty",true));task
    }
    fun remove(id: String)=synchronized(lock) {
        val d=read();val task=records(d).firstOrNull {it.id==id} ?: return@synchronized
        check(task.terminal || task.state==MateTaskState.NEEDS_REAUTHORIZE) {"MATE_STOP_BEFORE_DELETE"}
        val g=Math.addExact(d.getLong("generation"),1);d.getJSONObject("tombstones").put(id,g)
        write(d.put("tasks",JSONArray(records(d).filterNot {it.id==id}.map {it.json()})).put("generation",g).put("dirty",true))
    }
    fun event(id: String,source: String,eventId: String,version: Long,state: MateTaskState,note: String): Boolean {
        require(source.length in 1..160 && eventId.length in 1..240 && version>=0)
        val digest=MemoryRules.hash("$source:$eventId:$version");var accepted=false
        update(id) {task ->
            if(task.terminal || digest in task.seenEvents || version<(task.watermarks[source] ?: 0) && state!=MateTaskState.UNKNOWN)task else {
                accepted=true
                val marks=(task.watermarks+(source to maxOf(version,task.watermarks[source] ?: 0))).entries.toList().takeLast(64).associate {it.key to it.value}
                task.copy(state=if(task.state==MateTaskState.UNKNOWN && state!=MateTaskState.CANCELLED)MateTaskState.UNKNOWN else state,
                    watermarks=marks,seenEvents=(task.seenEvents+digest).takeLast(128),notes=(task.notes+note.take(500)).takeLast(32),progress=note.take(1000),needsObservation=true)
            }
        };return accepted
    }
    fun recoverProcess() {list().filterNot {it.terminal}.forEach {t->update(t.id) {it.recovered()}}}
    fun restore(snapshot: JSONObject): Int=synchronized(lock) {
        validate(snapshot);val local=read();val existing=records(local);val incoming=records(snapshot);var count=0
        val localNs=local.getString("namespace");val remoteNs=snapshot.getString("namespace")
        check(localNs==remoteNs || existing.isEmpty() && local.getJSONObject("tombstones").length()==0) {"MATE_TASK_NAMESPACE_CONFLICT"}
        val tombs=local.getJSONObject("tombstones");val remote=snapshot.getJSONObject("tombstones");val keys=remote.keys()
        while(keys.hasNext()){val id=keys.next();tombs.put(id,maxOf(tombs.optLong(id),remote.getLong(id)))}
        val result=existing.filterNot {tombs.has(it.id)}.associateBy {it.id}.toMutableMap()
        for(task in incoming)if(!tombs.has(task.id)) {
            val old=result[task.id]
            if(old==null || task.revision>old.revision) {result[task.id]=task.recovered();count++}
            else if(task.revision==old.revision && task.recovered()!=old.recovered())error("MATE_TASK_RESTORE_CONFLICT")
        }
        write(local.put("namespace",remoteNs).put("generation",Math.addExact(maxOf(local.getLong("generation"),snapshot.getLong("generation")),1))
            .put("tasks",JSONArray(result.values.map {it.json()})).put("dirty",true));count
    }
}
