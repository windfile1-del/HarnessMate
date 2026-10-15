package dev.wind.agent.memory

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.wind.agent.*
import dev.wind.agent.profile.*
import org.json.*
import java.util.UUID

/** Queue, lease, watermark and reservations use the same private SQLite transaction lane. */
class MemoryJobStore(private val store: MemoryStore) {
    private val db get()=store.database
    // Filter before LIMIT: hundreds of stopped jobs must not hide a new live lease.
    fun jobs(): List<MemoryJob> = db.rawQuery("SELECT body FROM jobs WHERE state IN ('QUEUED','RUNNING','RETRY','WAITING') ORDER BY due,id LIMIT 64",null).use { c -> buildList {while(c.moveToNext())add(MemoryJob.parse(JSONObject(c.getString(0))))} }
    fun get(id: String)=db.rawQuery("SELECT body FROM jobs WHERE id=?",arrayOf(id)).use { if(it.moveToFirst())MemoryJob.parse(JSONObject(it.getString(0))) else null }
    private fun put(job: MemoryJob) {db.insertWithOnConflict("jobs",null,ContentValues().apply {put("id",job.id);put("state",job.state);put("due",job.due);put("body",job.json().toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun offset(chat: String,m: Message): Int = db.rawQuery("SELECT revision,digest,offset FROM memory_marks WHERE chat=? AND message=?",arrayOf(chat,m.messageId)).use {
        if(it.moveToFirst() && it.getLong(0)==m.sourceRevision && it.getString(1)==MemoryRules.hash(m.content))it.getInt(2) else 0
    }
    fun mark(chat: String,s: MemoryInput) {
        db.insertWithOnConflict("memory_marks",null,ContentValues().apply {put("chat",chat);put("message",s.messageId);put("revision",s.revision);put("digest",s.digest);put("offset",s.end)},SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun baseline(chat: Chat)=store.transaction {
        synchronized(chat.messages) { for(m in chat.messages.filter { MemoryRules.direct(it) })mark(chat.id,MemoryInput(m.messageId,m.sourceRevision,MemoryRules.hash(m.content),0,m.content.length,m.content, m.memoryCapture ?: MemoryCapture("plan",chat.agentProfile.name,"OFF",null,null,"",chat.id,true))) }
    }
    fun enqueue(chat: Chat,now: Long=System.currentTimeMillis(),force: Boolean=false): MemoryJob? = store.transaction {
        if(chat.retentionPolicy==RetentionPolicy.TEMPORARY || chat.webBinding!=null || chat.memoryPolicy!=MemorySessionPolicy.NORMAL || !store.policy("use_saved") || !store.policy("auto_write"))return@transaction null
        val source=synchronized(chat.messages) { chat.messages.filter { MemoryRules.direct(it) && it.memoryCapture!=null }.toList() }
        val inputs=mutableListOf<MemoryInput>()
        for(m in source) synchronized(m) {
            val capture=m.memoryCapture!!
            if(capture.restored || capture.policy!="NORMAL" || capture.project!=chat.projectMemoryKey.takeIf { it==chat.projectBindingId } || capture.providerId==null)return@synchronized
            val start=offset(chat.id,m);if(start>=m.content.length)return@synchronized
            val digest=MemoryRules.hash(m.content)
            val redactions=store.redactions(chat.id)[m.messageId] ?: emptyList()
            if(MemoryRules.secret(m.content) || MemoryRules.refused(m.content)) {mark(chat.id,MemoryInput(m.messageId,m.sourceRevision,digest,start,m.content.length,"",capture,m.content.length));return@synchronized}
            if(inputs.size>=8)return@synchronized
            if(inputs.isNotEmpty() && inputs.first().capture!=capture)return@synchronized
            fun boundary(end: Int)=if(end>start && end<m.content.length && Character.isHighSurrogate(m.content[end-1]) && Character.isLowSurrogate(m.content[end]))end-1 else end
            fun item(end: Int)=MemoryInput(m.messageId,m.sourceRevision,digest,start,m.content.length,MemoryRedactions.slice(m.content,redactions,start,end),capture,end)
            var low=start;var high=minOf(m.content.length,start+4000)
            // Find the largest original span whose actual escaped JSON fits. Offsets remain
            // in raw history; Unicode pairs and forgotten spans never get split/reintroduced.
            while(low<high) {
                val middle=low+(high-low+1)/2;val end=boundary(middle)
                val candidate=if(end>start)item(end) else null
                if(candidate==null || inputs.sumOf {it.text.length}+candidate.text.length<=MemoryExtractor.SOURCE_CHAR_LIMIT && MemoryExtractor.sourceReservation(inputs+candidate)<=MemoryExtractor.SOURCE_REQUEST_LIMIT)low=middle else high=middle-1
            }
            val end=boundary(low);if(end<=start)return@synchronized
            val item=item(end)
            if(item.text.isBlank()){mark(chat.id,item);return@synchronized}
            inputs.add(item)
        }
        if(inputs.isEmpty())return@transaction null
        val digest=MemoryRules.hash(JSONArray(inputs.map { it.json() }).toString())
        val id=MemoryRules.hash("${chat.id}:$digest:${store.namespace()}:${store.epoch()}")
        get(id)?.let { existing ->
            if(force && existing.state=="QUEUED" && existing.due>now)return@transaction existing.copy(due=now).also(::put)
            return@transaction existing
        }
        if(jobs().size>=64)return@transaction null
        val versions=store.list().filter { MemoryRules.compatible(it,inputs.first().capture.project,inputs.first().capture.task,now) || it.status=="CANDIDATE" && (it.scope==MemoryScope.PERSONAL || it.scopeKey==inputs.first().capture.project) }
            .associate { MemoryJob.key(it.scope,it.scopeKey,it.key) to it.revision }
        val due=if(force || inputs.size>=8 || chat.runState in listOf("completed","failed","stopped","interrupted"))now else now+120000
        MemoryJob(id,chat.id,store.namespace(),store.epoch(),inputs,versions,now,due).also(::put)
    }
    fun claim(now: Long=System.currentTimeMillis()): MemoryJob? = store.transaction {
        val all=jobs()
        if(all.any { it.state=="RUNNING" && it.leaseUntil>now && it.leaseUntil<=now+15*60*1000 })return@transaction null
        val job=all.firstOrNull { it.state in listOf("QUEUED","RETRY","WAITING") && it.due<=now || it.state=="RUNNING" && (it.leaseUntil<=now || it.leaseUntil>now+15*60*1000) } ?: return@transaction null
        if(job.epoch!=store.epoch() || job.namespace!=store.namespace() || !store.policy("use_saved") || !store.policy("auto_write")) {put(job.copy(state="CANCELLED",error="POLICY_CHANGED"));return@transaction null}
        job.copy(state="RUNNING",lease=UUID.randomUUID().toString(),leaseUntil=now+5*60*1000,attempts=job.attempts+1).also(::put)
    }
    fun checkLease(job: MemoryJob) {
        val current=get(job.id);check(current?.state=="RUNNING" && current.lease==job.lease && current.leaseUntil>System.currentTimeMillis()) {"MEMORY_JOB_LEASE_LOST"}
        check(job.epoch==store.epoch() && job.namespace==store.namespace() && store.policy("use_saved") && store.policy("auto_write")) {"MEMORY_JOB_POLICY_CHANGED"}
    }
    fun finish(job: MemoryJob,state: String,error: String="",delay: Long=0) = store.transaction {
        val current=get(job.id) ?: return@transaction
        if(current.lease!=job.lease)return@transaction
        put(current.copy(state=state,error=error.take(160),due=System.currentTimeMillis()+delay,lease="",leaseUntil=0))
    }
    fun completed(job: MemoryJob) {
        job.inputs.forEach { mark(job.chatId,it) };db.delete("jobs","id=?",arrayOf(job.id))
    }
    /** OS cancellation and exhausted daily allowance are waits, not provider failures. */
    fun defer(job: MemoryJob,error: String,delay: Long)=store.transaction {
        val current=get(job.id) ?: return@transaction
        if(current.lease!=job.lease || current.state!="RUNNING")return@transaction
        put(current.copy(state="WAITING",error=error.take(160),due=System.currentTimeMillis()+delay.coerceAtLeast(1000),lease="",leaseUntil=0,attempts=(current.attempts-1).coerceAtLeast(0)))
    }
    fun untilNextBudgetDay(): Long {
        val now=System.currentTimeMillis()
        val day=maxOf(now/86400000,meta("usageDayFloor",0))
        return ((day+1)*86400000-now+1000).coerceAtLeast(1000)
    }
    private fun meta(key: String,default: Long)=db.rawQuery("SELECT v FROM meta WHERE k=?",arrayOf(key)).use { if(it.moveToFirst())it.getString(0).toLong() else default }
    private fun setMeta(k: String,v: Long) {db.insertWithOnConflict("meta",null,ContentValues().apply {put("k",k);put("v",v.toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun limits()=Triple(meta("autoCalls",4).toInt(),meta("autoInput",30000).toInt(),meta("autoOutput",4000).toInt())
    fun setLimits(calls: Int,input: Int,output: Int)=store.transaction {
        require(calls in 1..16 && input in 6000..120000 && output in 1000..16000);setMeta("autoCalls",calls.toLong());setMeta("autoInput",input.toLong());setMeta("autoOutput",output.toLong())
    }
    fun reserve(job: MemoryJob,input: Int,output: Int): String = store.transaction {
        checkLease(job);require(input in 1..6000 && output in 1..1000)
        val today=maxOf(System.currentTimeMillis()/86400000,meta("usageDayFloor",0));setMeta("usageDayFloor",today)
        val u=db.rawQuery("SELECT body FROM usage WHERE day=?",arrayOf(today.toString())).use {if(it.moveToFirst())JSONObject(it.getString(0)) else JSONObject()}
        val limits=limits();check(u.optInt("calls")<limits.first && u.optLong("inputReserved")+input<=limits.second && u.optLong("outputReserved")+output<=limits.third && !u.optBoolean("halted")) {"MEMORY_DAILY_BUDGET_EXHAUSTED"}
        u.put("calls",u.optInt("calls")+1).put("inputReserved",u.optLong("inputReserved")+input).put("outputReserved",u.optLong("outputReserved")+output)
        db.insertWithOnConflict("usage",null,ContentValues().apply {put("day",today.toString());put("body",u.toString())},SQLiteDatabase.CONFLICT_REPLACE)
        val id=UUID.randomUUID().toString();db.insert("memory_calls",null,ContentValues().apply {put("id",id);put("day",today);put("body",JSONObject().put("job",job.id).put("inputReserved",input).put("outputReserved",output).put("state","RESERVED").toString())});id
    }
    fun settle(call: String,usage: dev.wind.agent.context.TokenUsage?,state: String)=store.transaction {
        val pair=db.rawQuery("SELECT day,body FROM memory_calls WHERE id=?",arrayOf(call)).use {if(it.moveToFirst())it.getLong(0) to JSONObject(it.getString(1)) else null} ?: error("MEMORY_CALL_NOT_RESERVED")
        val b=pair.second;check(b.getString("state")=="RESERVED")
        b.put("state",state).put("usageSource",if(usage==null)"RESERVED_ESTIMATE" else "PROVIDER_REPORTED")
        b.put("input",usage?.inputTokens ?: b.getLong("inputReserved")).put("output",usage?.outputTokens ?: b.getLong("outputReserved")).put("cached",usage?.cachedTokens ?: 0).put("cacheKnown",usage?.cacheKnown ?: false)
        db.update("memory_calls",ContentValues().apply {put("body",b.toString())},"id=?",arrayOf(call))
        if(usage!=null && (usage.inputTokens>b.getLong("inputReserved") || usage.outputTokens>b.getLong("outputReserved"))) {
            val day=pair.first.toString();val u=db.rawQuery("SELECT body FROM usage WHERE day=?",arrayOf(day)).use {it.moveToFirst();JSONObject(it.getString(0))}.put("halted",true)
            db.update("usage",ContentValues().apply {put("body",u.toString())},"day=?",arrayOf(day))
        }
    }
    fun usage(): JSONObject {
        val day=maxOf(System.currentTimeMillis()/86400000,meta("usageDayFloor",0)).toString()
        val summary=db.rawQuery("SELECT body FROM usage WHERE day=?",arrayOf(day)).use {if(it.moveToFirst())JSONObject(it.getString(0)) else JSONObject().put("calls",0).put("inputReserved",0).put("outputReserved",0)}
        val calls=JSONArray();db.rawQuery("SELECT body FROM memory_calls WHERE day=? ORDER BY id LIMIT 32",arrayOf(day)).use {c->while(c.moveToNext())calls.put(JSONObject(c.getString(0)))}
        return summary.put("receipts",calls)
    }
}
