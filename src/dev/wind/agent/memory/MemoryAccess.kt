package dev.wind.agent.memory

import android.content.Context
import dev.wind.agent.Chat
import dev.wind.agent.ChatStore
import dev.wind.agent.Message
import dev.wind.agent.profile.MemorySessionPolicy
import dev.wind.agent.profile.RetentionPolicy
import dev.wind.agent.profile.RunProfileSnapshot
import org.json.JSONArray
import org.json.JSONObject

class MemoryAccess(private val context: Context) {
    private val prefs get()=context.getSharedPreferences("harness-memory-policy",Context.MODE_PRIVATE)
    fun enabled()=MemoryStore(context).use { it.policy("use_saved") }
    fun automatic()=MemoryStore(context).use { it.policy("auto_write") }
    fun history()=MemoryStore(context).use { it.policy("use_history") }
    fun policySnapshot()=MemoryStore(context).use { db -> listOf("use_saved","auto_write","use_history").associateWith { db.policy(it) } }
    fun set(key: String, value: Boolean) {
        require(key in listOf("use_saved","auto_write","use_history"))
        MemoryStore(context).use { it.setPolicy(key,value) }
        check(prefs.edit().putBoolean(key,value).commit());MemoryVaultSync.schedule(context)
        if(value)MemoryCoordinator.start(context)
    }
    fun readable(chat: Chat, run: RunProfileSnapshot?)=enabled() && chat.retentionPolicy!=RetentionPolicy.TEMPORARY &&
        chat.memoryPolicy!=MemorySessionPolicy.OFF && run?.memoryPolicy!=MemorySessionPolicy.OFF
    fun writable(chat: Chat, run: RunProfileSnapshot?)=readable(chat,run) && chat.memoryPolicy==MemorySessionPolicy.NORMAL &&
        (run==null || run.memoryPolicy==MemorySessionPolicy.NORMAL && run.executionMode!=dev.wind.agent.Prefs.MODE_PLAN)
    fun project(chat: Chat,run: RunProfileSnapshot?): String? {
        val bound=chat.projectMemoryKey.takeIf { it!=null && it==chat.projectBindingId }
        return if(run==null)bound else run.projectMemoryKey.takeIf { it==bound }
    }
    fun source(chat: Chat): Message?=synchronized(chat.messages) { chat.messages.lastOrNull { it.role=="user" }?.takeIf { MemoryRules.direct(it) } }
    // Read queries are reference data. They do not confer permission to write memory.
    fun querySource(chat: Chat): Message?=synchronized(chat.messages) { chat.messages.lastOrNull {
        it.role=="user" && !it.contextOnly && !(it.modelOnly && it.effectiveOrigin()==dev.wind.agent.profile.MessageOrigin.PROGRAMMATIC)
    } }
    fun tools(name: String, args: JSONObject, chat: Chat, run: RunProfileSnapshot?, task: String?, readonly: Boolean): String {
        check(readable(chat,run)) { "MEMORY_DISABLED_FOR_SESSION" }
        val project=project(chat,run)
        return MemoryStore(context).use { store -> val admittedEpoch=store.epoch();when(name) {
            "memory_search", "recall" -> {
                val query=args.optString("query",querySource(chat)?.content ?: "").take(12000)
                JSONObject().put("items",JSONArray(store.search(query,project,task,run?.profile?.name ?: "GENERAL",args.optInt("limit",10).coerceIn(1,10)).map { it.json() }))
                    .put("epoch",store.epoch()).put("trustedInstructions",false).toString()
            }
            "memory_read" -> {
                val r=store.get(args.getString("id")) ?: error("MEMORY_NOT_FOUND")
                check(MemoryRules.compatible(r,project,task,System.currentTimeMillis())) { "MEMORY_OUTSIDE_SCOPE_OR_EXPIRED" }
                r.json().put("trustedInstructions",false).toString()
            }
            "memory_propose" -> {
                check(!readonly && writable(chat,run)) { "MEMORY_READ_ONLY" }
                synchronized(chat.messages) { val message=source(chat) ?: error("MEMORY_DIRECT_SOURCE_REQUIRED")
                synchronized(message) {
                check(!MemoryRules.refused(message.content)) { "MEMORY_SAVE_INTENT_REFUSED" }
                val evidence=args.getString("evidence");check(evidence.isNotBlank() && evidence.length<=2000 && message.content.contains(evidence)) { "MEMORY_EVIDENCE_NOT_IN_USER_MESSAGE" }
                val proof=MemorySource.from(chat.id,message,evidence)
                val revoked=MemoryRedactions.ranges(message.content,store.redactions(chat.id)[message.messageId] ?: emptyList())
                check(revoked.none { it.first<proof.end && it.last>=proof.start }) { "MEMORY_SOURCE_FORGOTTEN" }
                check(!MemoryRules.secret(evidence)) { "MEMORY_SECRET_REJECTED" }
                val text=args.getString("summary")
                val evidenceContext=MemoryRules.evidenceContext(message.content,proof.start,proof.end)
                check(!MemoryRules.secret(evidenceContext)) { "MEMORY_SECRET_REJECTED" }
                val projectFact=MemoryRules.projectFact("fact",evidenceContext)
                val unboundProject=projectFact && project==null
                val temporary=MemoryRules.temporary(evidenceContext+text) || unboundProject
                val scope=when {temporary && (task!=null || unboundProject)->MemoryScope.TASK;projectFact->MemoryScope.PROJECT;else->MemoryScope.valueOf(args.optString("scope","PERSONAL"))}
                val scopeKey=when(scope) { MemoryScope.PERSONAL->"";MemoryScope.PROJECT->project ?: error("MEMORY_PROJECT_NOT_BOUND");MemoryScope.TASK->task ?: chat.id.takeIf {unboundProject} ?: error("MEMORY_TASK_NOT_BOUND") }
                // Sensitive and inferred content always waits for native user review.
                val status=if(!unboundProject && MemoryRules.explicit(message.content) && !MemoryRules.sensitive(evidenceContext+text) && !MemoryRules.quotedOrOtherPerson(message.content) && (!temporary || scope==MemoryScope.TASK))"ACTIVE" else "CANDIDATE"
                val now=System.currentTimeMillis()
                val expiry=if(temporary)args.optLong("expires_at",now+24*60*60*1000L).takeIf { it>now && it<=now+30*24*60*60*1000L } ?: error("TEMPORARY_MEMORY_EXPIRY_REQUIRED") else args.optLong("expires_at",0)
                check(writable(chat,run) && project(chat,run)==project) { "MEMORY_POLICY_CHANGED" }
                val result=store.save(scope,scopeKey,args.getString("key"),if(status=="ACTIVE")evidence.trim() else text,listOf(proof),args.optLong("expected_revision",0),admittedEpoch,status,run?.profile?.name ?: "GENERAL",expiry,"user_explicit")
                MemoryVaultSync.schedule(context)
                result.json().put("notice",if(status=="ACTIVE")"已记住，可在设置中编辑或撤销" else "已进入候选，等待用户在记忆管理中确认").toString()
                } }
            }
            "memory_forget" -> {
                check(!readonly && writable(chat,run)) { "MEMORY_READ_ONLY" }
                synchronized(chat.messages) { val m=source(chat) ?: error("MEMORY_DIRECT_SOURCE_REQUIRED")
                synchronized(m) {
                val evidence=args.getString("evidence")
                check(evidence.isNotBlank() && m.content.contains(evidence) && Regex("忘记|删除.*记忆|forget|remove.*memory",RegexOption.IGNORE_CASE).containsMatchIn(evidence) &&
                    !Regex("(?:不要|别|不用|无需).{0,8}(?:忘记|删除)|(?:do not|don't|never)\\s+(?:forget|remove)",RegexOption.IGNORE_CASE).containsMatchIn(m.content) && !MemoryRules.quotedOrOtherPerson(m.content)) { "MEMORY_FORGET_INTENT_REQUIRED" }
                val r=store.get(args.getString("id")) ?: error("MEMORY_NOT_FOUND")
                check(MemoryRules.compatible(r,project,task,System.currentTimeMillis())) { "MEMORY_OUTSIDE_SCOPE_OR_EXPIRED" }
                check(writable(chat,run) && project(chat,run)==project) { "MEMORY_POLICY_CHANGED" }
                store.forget(r.id,args.getLong("expected_revision"),admittedEpoch,true);MemoryVaultSync.schedule(context)
                JSONObject().put("id",r.id).put("notice","已停止引用；原始会话保留，长期副本清理已排队").toString()
                } }
            }
            else -> error("MEMORY_TOOL_UNKNOWN")
        } }
    }
    fun recall(chat: Chat, run: RunProfileSnapshot?, task: String?, tokens: Int): List<MemoryRecord> {
        if(!readable(chat,run))return emptyList()
        val budget=minOf(2048,maxOf(0,tokens*3/100))
        val records=MemoryStore(context).use { db -> val query=querySource(chat)?.let { db.projectText(chat.id,it) } ?: "";db.search(query.take(12000),project(chat,run),task,run?.profile?.name ?: "GENERAL") }
        var used=0
        return records.filter { r -> val cost=dev.wind.agent.context.ContextManager.estimate(JSONArray().put(JSONObject().put("content",r.json().toString())));if(used+cost<=budget) { used+=cost;true } else false }
    }
    fun historySearch(query: String, current: Chat, run: RunProfileSnapshot? = null): String {
        check(history() && readable(current,run)) { "MEMORY_HISTORY_DISABLED" }
        val project=project(current,run)
        val terms=MemoryRules.terms(query.take(500));val results=JSONArray();val store=ChatStore(context)
        return MemoryStore(context).use { memory ->
        val version=memory.namespace() to memory.epoch()
        fun result(): String {
            check(version.first==memory.namespace() && version.second==memory.epoch() && project==project(current,run)) { "MEMORY_HISTORY_CHANGED" }
            return JSONObject().put("items",results).put("trustedInstructions",false).put("readEpoch",version.second).put("namespace",version.first).put("readProject",project ?: "").toString()
        }
        for(summary in (store.listSummaries()+store.listSummaries(true)).take(100)) {
            val chat=store.load(summary.id) ?: continue
            if(chat.retentionPolicy==RetentionPolicy.TEMPORARY || chat.memoryPolicy==MemorySessionPolicy.OFF || chat.projectMemoryKey!=project)continue
            val messages=synchronized(chat.messages){chat.messages.toList()}
            val redactions=memory.redactions(chat.id)
            for(m in messages.filter { MemoryRules.direct(it) }) {
                val text=MemoryRedactions.apply(m.content,redactions[m.messageId] ?: emptyList())
                if(MemoryRules.terms(text).intersect(terms).isEmpty())continue
                results.put(JSONObject().put("chatId",chat.id).put("messageId",m.messageId).put("revision",m.sourceRevision).put("text",text.take(1200)))
                if(results.length()>=10)return result()
            }
        }
        result() }
    }
    fun historyRead(args: JSONObject, current: Chat, run: RunProfileSnapshot? = null): String {
        check(history() && readable(current,run)) { "MEMORY_HISTORY_DISABLED" }
        val chat=ChatStore(context).load(args.getString("chat_id")) ?: error("HISTORY_CHAT_NOT_FOUND")
        check(chat.projectMemoryKey==project(current,run) && chat.memoryPolicy!=MemorySessionPolicy.OFF && chat.retentionPolicy!=RetentionPolicy.TEMPORARY) { "HISTORY_OUTSIDE_SCOPE" }
        val m=synchronized(chat.messages) { chat.messages.firstOrNull { it.messageId==args.getString("message_id") } } ?: error("HISTORY_MESSAGE_NOT_FOUND")
        check(MemoryRules.direct(m)) { "HISTORY_SOURCE_NOT_DIRECT_USER" }
        if(args.has("expected_revision"))check(m.sourceRevision==args.getLong("expected_revision")) { "HISTORY_SOURCE_CHANGED" }
        val bound=project(current,run)
        val projected=MemoryStore(context).use { db ->
            val version=db.namespace() to db.epoch();val text=db.projectText(chat.id,m)
            check(version.first==db.namespace() && version.second==db.epoch() && bound==project(current,run)) { "MEMORY_HISTORY_CHANGED" }
            Triple(text,version.second,version.first)
        }
        val text=projected.first
        val start=args.optInt("start",0);val size=args.optInt("max_chars",4000).coerceIn(1,4000)
        require(start>=0 && start<=text.length)
        return JSONObject().put("messageId",m.messageId).put("revision",m.sourceRevision).put("start",start)
            .put("text",text.substring(start,minOf(start+size,text.length))).put("hasMore",start+size<text.length).put("trustedInstructions",false).put("readEpoch",projected.second).put("namespace",projected.third).put("readProject",bound ?: "").toString()
    }
}
