package dev.wind.agent.memory

import android.app.job.*
import android.content.*
import dev.wind.agent.*
import dev.wind.agent.profile.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** Small notifications from chat persistence; all queue scanning/database work is off the UI. */
object MemoryCoordinator {
    const val JOB_ID=7316
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"memory-background").apply {isDaemon=true;priority=Thread.MIN_PRIORITY} }
    private val scans=ConcurrentHashMap<String,String>()
    fun status(c: Context,value: String) {runCatching {c.getSharedPreferences("harness-memory-status",Context.MODE_PRIVATE).edit().putString("automatic",value.take(180)).apply()} }
    fun status(c: Context)=c.getSharedPreferences("harness-memory-status",Context.MODE_PRIVATE).getString("automatic","尚未自动补充记忆") ?: ""
    fun capture(c: Context,chat: Chat) {
        if(chat.retentionPolicy==RetentionPolicy.TEMPORARY || chat.webBinding!=null)return
        synchronized(chat.messages) {
            val m=chat.messages.lastOrNull {it.role=="user"} ?: return
            if(MemoryRules.direct(m) && m.memoryCapture==null)m.memoryCapture=MemoryCapture.capture(chat,Prefs(c).freezeModel())
        }
    }
    fun saved(c: Context,payload: String,force: Boolean=false) {
        val app=c.applicationContext
        worker.execute {
            try {
                val chat=Chat.fromJson(JSONObject(payload))
                val digest=synchronized(chat.messages) {MemoryRules.hash(chat.messages.filter {MemoryRules.direct(it)}.joinToString("|") {"${it.messageId}:${it.sourceRevision}:${MemoryRules.hash(it.content)}"})}
                val signature="$digest:${chat.runState}:${chat.memoryPolicy}:${chat.projectMemoryKey}:${chat.projectBindingId}"
                val key=app.noBackupFilesDir.canonicalPath+":"+chat.id
                if(!force && scans.put(key,signature)==signature)return@execute
                MemoryStore(app).use {db ->MemoryJobStore(db).enqueue(chat,force=force)}
                schedule(app);drain(app)
            } catch(e: Throwable) {runCatching {status(app,"自动记忆排队待重试：${e.message?.take(100)}")}}
        }
    }
    fun restored(chat: Chat) {
        synchronized(chat.messages) {chat.messages.forEach {m ->
            m.memoryCapture=m.memoryCapture?.copy(restored=true)
                ?: MemoryCapture(Prefs.MODE_PLAN,chat.agentProfile.name,"OFF",null,null,"",chat.id,true,true)
        }}
    }
    fun normalizeRestoredMetadata(payload: JSONObject) {
        val messages=payload.getJSONArray("messages")
        for(i in 0 until messages.length()) {
            val message=messages.getJSONObject(i)
            val capture=message.optJSONObject("memoryCapture") ?: continue
            if(capture.optBoolean("restoredBaseline"))message.remove("memoryCapture") else capture.put("restored",false)
        }
    }
    fun start(c: Context) {
        val app=c.applicationContext
        worker.execute {try {reconcile(app);schedule(app);drain(app)}catch(e: Throwable) {runCatching {status(app,"自动记忆恢复待重试：${e.message?.take(100)}")}}}
    }
    fun reconcile(c: Context) {
        reconcileDeletions(c)
        val store=ChatStore(c)
        for(summary in (store.listSummaries()+store.listSummaries(true)).take(2000)) {
            val chat=store.load(summary.id) ?: continue
            MemoryStore(c).use {db ->MemoryJobStore(db).enqueue(chat)}
        }
    }
    fun deleted(c: Context,id: String,forgetRelated: Boolean=false) {
        val app=c.applicationContext
        worker.execute {try {reconcileDeletions(app);MemoryVaultSync.schedule(app)}catch(e: Throwable) {status(app,"会话已删除；记忆关联清理待重试：${e.message?.take(100)}")}}
    }
    /** ChatStore fsyncs the user's deletion choice before deleting the source file. */
    fun reconcileDeletions(c: Context) {
        val dir=java.io.File(c.filesDir,"chats")
        for(file in dir.listFiles()?.filter {it.name.startsWith(".memory-delete-")} ?: emptyList()) {
            require(file.length()<=2048) {"MEMORY_DELETION_JOURNAL_INVALID"}
            val j=JSONObject(file.readText());val id=j.getString("id");val revision=j.getLong("revision")
            require(MemoryRules.id(id) && file.name==".memory-delete-$id" && revision>0)
            val tomb=java.io.File(dir,".deleted-$id").takeIf {it.isFile}?.readText()?.trim()?.toLongOrNull() ?: 0
            if(tomb<revision)continue
            MemoryStore(c).use {it.historyDeleted(id,j.optBoolean("forgetRelated"),revision)}
            val chatFile=java.io.File(dir,"$id.json")
            if(chatFile.isFile && JSONObject(chatFile.readText()).optLong("updatedAt")<=tomb)
                check(chatFile.delete() || !chatFile.exists()) {"MEMORY_DELETED_CHAT_CLEANUP_PENDING"}
            check(file.delete() || !file.exists()) {"MEMORY_DELETION_JOURNAL_PENDING"}
        }
    }
    fun schedule(c: Context) {
        val scheduler=c.getSystemService(JobScheduler::class.java)
        val next=MemoryStore(c).use {db ->MemoryJobStore(db).jobs().minOfOrNull {if(it.state=="RUNNING")it.leaseUntil else it.due}}
        if(next==null) {scheduler.cancel(JOB_ID);return}
        val job=JobInfo.Builder(JOB_ID,ComponentName(c,MemoryJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(maxOf(1000,next-System.currentTimeMillis())).setPersisted(true).build()
        check(scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS) {"MEMORY_JOB_SCHEDULE_FAILED"}
    }
    fun drain(c: Context,token: CancellationToken=CancellationToken()) {
        // One job per wakeup; no constant polling, retries or provider fallback loop.
        val result=MemoryJobRunner(c).runOne(token)
        if(result=="COMPLETED")reconcile(c)
        schedule(c)
    }
}
class MemoryJobService : JobService() {
    @Volatile private var cancellation: CancellationToken?=null
    override fun onStartJob(params: JobParameters?): Boolean {
        val token=CancellationToken();cancellation=token
        Thread({try {MemoryCoordinator.reconcile(this);MemoryCoordinator.drain(this,token)}finally {if(cancellation===token)cancellation=null;if(!token.isCancelled)jobFinished(params,false)}},"memory-job-service").apply {priority=Thread.MIN_PRIORITY;start()};return true
    }
    override fun onStopJob(params: JobParameters?): Boolean {cancellation?.cancel();return true}
}
