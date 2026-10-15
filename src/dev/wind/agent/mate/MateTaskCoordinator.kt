package dev.wind.agent.mate

import android.content.Context
import android.content.Intent
import dev.wind.agent.*
import dev.wind.agent.memory.MemoryRules
import dev.wind.agent.profile.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Coordinates existing executors. A logical ID never replaces the run's capability identity. */
object MateTaskCoordinator {
    private val io=Executors.newSingleThreadExecutor {r->Thread(r,"mate-task-events").apply {isDaemon=true}}
    fun store(c: Context,temporary: Boolean=false)=MateTaskStore(File(c.noBackupFilesDir,if(temporary)"mate-tasks-temporary" else "mate-tasks"),!temporary)
    fun capture(chat: Chat) {
        if(chat.agentProfile!=AgentProfile.MATE || chat.webBinding!=null)return
        val last=synchronized(chat.messages) {chat.messages.lastOrNull {it.role=="user"}} ?: return
        if(last.content.trim() in setOf("/context","/compact"))return
        val newInput=MemoryRules.direct(last) && last.messageId!=chat.mateTaskInputMessageId
        if(newInput)chat.mateTaskInputMessageId=last.messageId
        if(chat.mateTaskId==null && MemoryRules.direct(last) || chat.mateTaskState in setOf("COMPLETED","FAILED","CANCELLED") && newInput) {
            chat.mateTaskId="mate_"+UUID.nameUUIDFromBytes((chat.id+":"+last.messageId).toByteArray(Charsets.UTF_8))
            chat.mateTaskState="CREATED"
        }
    }
    fun begin(c: Context,chat: Chat,execution: String): MateTask? {
        capture(chat);val id=chat.mateTaskId ?: return null
        if(chat.agentProfile!=AgentProfile.MATE || chat.webBinding!=null)return null
        val repo=store(c,chat.retentionPolicy==RetentionPolicy.TEMPORARY)
        val source=synchronized(chat.messages) {chat.messages.lastOrNull {MemoryRules.direct(it)}} ?: return null
        val goal=if(MemoryRules.secret(source.content))"此目标包含敏感信息，请参考原会话" else source.content.take(2000).ifBlank {"处理用户提供的资料"}
        val task=repo.get(id) ?: repo.create(id,chat.id,source.messageId,goal)
        check(task.chatId==chat.id && !task.terminal) {"MATE_TASK_NOT_RESUMABLE"}
        check(task.state!=MateTaskState.UNKNOWN) {"MATE_UNCERTAIN_ACTION_REQUIRES_USER_REVIEW"}
        return repo.update(id) {it.copy(executionId=execution,state=MateTaskState.RUNNING,needsObservation=true)}.also {chat.mateTaskState=it.state.name}
    }
    fun waiting(c: Context,chat: Chat): Boolean {
        val task=chat.mateTaskId?.let {store(c,chat.retentionPolicy==RetentionPolicy.TEMPORARY).get(it)} ?: return false
        return task.state in setOf(MateTaskState.WAITING_FOR_EVENT,MateTaskState.WAITING_FOR_USER,MateTaskState.UNKNOWN,MateTaskState.PAUSED,MateTaskState.NEEDS_REAUTHORIZE)
    }
    fun record(c: Context,chat: Chat,execution: String,tool: String,result: String,args: JSONObject=JSONObject()) {
        if(chat.webBinding!=null || tool=="mate_task" || !MemoryRules.id(chat.mateTaskId ?: ""))return
        val repo=store(c,chat.retentionPolicy==RetentionPolicy.TEMPORARY);val id=chat.mateTaskId!!
        if(repo.get(id)?.executionId!=execution)return
        val nativeMetadata=tool.startsWith("phone_") || tool.startsWith("browser_") || tool.startsWith("adb_") || tool in setOf("publish_execution_artifact","mate_document")
        val data=if(nativeMetadata)runCatching {JSONObject(result)}.getOrNull() else null
        val status=data?.optString("status")?.ifBlank {data.optString("state")}?.take(100) ?: if(result.startsWith("ERROR") || result.startsWith("BLOCKED"))"FAILED" else "LOCAL_RESULT"
        val unknown=status.contains("UNKNOWN") || data?.optString("error")?.contains("UNKNOWN")==true
        val takeover=status in setOf("HUMAN_TAKEOVER","HUMAN_ACTION_REQUIRED","NEEDS_USER_APPROVAL","UPLOAD_REQUIRES_TAKEOVER","USER_START_REQUIRED") || data?.optString("state")=="HUMAN_TAKEOVER"
        val lost=status in setOf("NEEDS_REAUTHORIZE","CLOSED_OR_LOST","RESTORE_REQUIRED")
        val ref=when {
            tool=="phone_chat_guardian" && data?.optString("session_id")?.isNotBlank()==true -> MateResource("guardian",data.getString("session_id"),when(status){"STOPPED"->"RELEASED";"NEEDS_REAUTHORIZE"->"NEEDS_REAUTHORIZE";"PAUSED","SETUP","STARTING","USER_START_REQUIRED"->"PAUSED";else->"ACTIVE"})
            tool.startsWith("browser_") && data?.optString("session_id")?.isNotBlank()==true ->MateResource("browser",data.getString("session_id"),if(status=="CLOSED")"RELEASED" else if(takeover)"PAUSED" else "ACTIVE")
            tool.startsWith("phone_") && tool!="phone_chat_guardian" && data?.optString("display_session_id")?.isNotBlank()==true ->MateResource("display",data.getString("display_session_id"),if(status=="CLOSED")"RELEASED" else "ACTIVE")
            else->null
        }
        val taskBefore=repo.get(id)!!
        val observedOnly=tool=="phone_chat_guardian" && args.optString("mode","status") in setOf("status","report","pending") || tool=="browser_session" && args.optString("mode","list") in setOf("status","list")
        val scopedRef=ref?.takeIf {!observedOnly || taskBefore.resources.any {it.kind==ref.kind && it.id==ref.id}}
        val fields=listOf("status","state","error","submitted","business_success_verified","recipient_delivery","file_selection","upload_completion","content_uri","filename","mime","bytes","sha256")
        val quote=if(data==null)if(MemoryRules.secret(result))"结果包含敏感信息，参见原会话" else result.take(1200) else fields.filter {data.has(it)}.joinToString("; ") {"$it=${data.opt(it)}"}.take(1200)
        val evidence=ObservationEvidence("ev_"+UUID.randomUUID(),tool,status.ifBlank {"OBSERVED"},System.currentTimeMillis(),quote,MemoryRules.hash(result))
        repo.update(id) {task ->
            check(task.executionId==execution && task.chatId==chat.id) {"MATE_EXECUTION_CHANGED"}
            if(task.terminal)task else {
                val resources=if(scopedRef==null)task.resources else (task.resources.filterNot {it.kind==scopedRef.kind && it.id==scopedRef.id}+scopedRef).takeLast(12)
                task.copy(resources=resources,evidence=(task.evidence+evidence).takeLast(24),continuous=task.continuous || scopedRef?.kind=="guardian" && scopedRef.state!="RELEASED",
                    needsObservation=if(tool in setOf("phone_observe","browser_observe") && !result.startsWith("ERROR"))false else task.needsObservation,
                    state=when{task.state==MateTaskState.UNKNOWN || unknown->MateTaskState.UNKNOWN;takeover->MateTaskState.WAITING_FOR_USER;lost->MateTaskState.NEEDS_REAUTHORIZE;scopedRef?.kind=="guardian" && scopedRef.state=="ACTIVE"->MateTaskState.WAITING_FOR_EVENT;scopedRef?.kind=="guardian" && scopedRef.state=="RELEASED"->MateTaskState.RUNNING;else->task.state},progress="${tool}: ${status.ifBlank {"已取得结果"}}")
            }
        }.also {chat.mateTaskState=it.state.name}
        if(chat.retentionPolicy==RetentionPolicy.PERSISTENT)MateTaskVaultSync.schedule(c)
    }
    fun finish(c: Context,chat: Chat,execution: String,failed: Boolean,stopped: Boolean) {
        val id=chat.mateTaskId ?: return;val repo=store(c,chat.retentionPolicy==RetentionPolicy.TEMPORARY)
        if(repo.get(id)?.executionId!=execution)return
        repo.update(id) {task ->
            val holding=task.state in setOf(MateTaskState.WAITING_FOR_EVENT,MateTaskState.WAITING_FOR_USER,MateTaskState.UNKNOWN,MateTaskState.PAUSED,MateTaskState.NEEDS_REAUTHORIZE)
            task.copy(executionId="",state=when {task.terminal || task.state==MateTaskState.UNKNOWN->task.state;stopped->MateTaskState.PAUSED;failed->MateTaskState.FAILED;holding->task.state;task.continuous && task.resources.any {it.kind=="guardian" && it.state!="RELEASED"}->MateTaskState.WAITING_FOR_EVENT;else->MateTaskState.COMPLETED},
                outcome=if(!holding && !stopped && !failed)"已交付本轮结果；具体业务核验见证据" else task.outcome,
                resources=task.resources.map {if(it.kind=="display" || it.kind=="browser" && !holding)it.copy(state="RELEASED") else it})
        }.also {chat.mateTaskState=it.state.name}
        if(chat.retentionPolicy==RetentionPolicy.PERSISTENT){MateTaskVaultSync.schedule(c);MateWakeJobs.schedule(c)}
    }
    fun tools(c: Context,chat: Chat,execution: String,args: JSONObject,readOnly: Boolean,allowedTask: String?=null): String {
        val repo=store(c,chat.retentionPolicy==RetentionPolicy.TEMPORARY);val mode=args.optString("mode","status")
        if(mode=="list")return JSONObject().put("tasks",org.json.JSONArray(repo.list(chat.id).take(20).map {it.json()})).toString()
        val id=args.optString("task_id").ifBlank {chat.mateTaskId ?: error("MATE_TASK_REQUIRED")}
        val task=repo.get(id) ?: error("MATE_TASK_NOT_FOUND");check(task.chatId==chat.id || readOnly && task.id==allowedTask) {"MATE_TASK_OUTSIDE_CHAT"}
        if(mode=="status")return task.json().toString()
        check(!readOnly && Prefs(c).mode()!=Prefs.MODE_PLAN && id==chat.mateTaskId && task.executionId==execution) {"MATE_TASK_WRITE_NOT_ALLOWED"}
        val changed=repo.update(id,args.optLong("expected_revision",task.revision)) {old ->
            check(!old.terminal && old.state!=MateTaskState.UNKNOWN) {"MATE_TASK_NOT_RESUMABLE"}
            when(mode) {
                "note" -> {check(!MemoryRules.secret(args.getString("note"))) {"MATE_NOTE_CONTAINS_SECRET"};old.copy(progress=args.getString("note").take(1000))}
                "wait" -> {
                    val wake=args.optLong("wake_at")
                    if(wake>0) {
                        check(chat.retentionPolicy==RetentionPolicy.PERSISTENT) {"MATE_SAVE_SESSION_BEFORE_SCHEDULE"}
                        val source=synchronized(chat.messages) {chat.messages.lastOrNull {it.role=="user"}}
                        check(source!=null && MemoryRules.direct(source) && Regex("提醒|检查|查询|通知|帮我|到时|再|remind|check|notify",RegexOption.IGNORE_CASE).containsMatchIn(source.content) &&
                            Regex("明天|后天|今晚|今天|小时|分钟|\\d{1,2}[:：点]|上午|下午|晚上|tomorrow|hour|minute|at \\d",RegexOption.IGNORE_CASE).containsMatchIn(source.content)) {"MATE_DIRECT_USER_CHECK_TIME_REQUIRED"}
                        check(wake>System.currentTimeMillis() && wake-System.currentTimeMillis()<=31L*86400000) {"MATE_WAKE_TIME_OUT_OF_RANGE"}
                    }
                    old.copy(state=MateTaskState.WAITING_FOR_EVENT,progress=args.getString("reason").take(1000),wakeAt=wake,plannedWakeAt=wake)
                }
                "pause" ->old.copy(state=MateTaskState.PAUSED,progress=args.optString("reason","暂停等待用户").take(1000))
                "finish" -> {
                    check(old.resources.none {it.kind=="guardian" && it.state!="RELEASED"}) {"MATE_CONTINUOUS_GOAL_STILL_ACTIVE"}
                    old.copy(state=MateTaskState.COMPLETED,outcome=args.getString("outcome").take(1000))
                }
                else->error("MATE_TASK_MODE_INVALID")
            }
        };chat.mateTaskState=changed.state.name;if(chat.retentionPolicy==RetentionPolicy.PERSISTENT){MateTaskVaultSync.schedule(c);MateWakeJobs.schedule(c)};return changed.json().toString()
    }
    fun guardian(c: Context,session: String,record: JSONObject) {
        val copy=JSONObject(record.toString());io.execute {
            runCatching {
                // The SQLite transaction monitor completes before reading. Rolled-back puts are not events.
                val current=dev.wind.agent.phone.ChatConversationStore.get(c).record(copy.getString("id")) ?: return@runCatching
                val repo=store(c);val task=repo.list().firstOrNull {t->t.resources.any {it.kind=="guardian" && it.id==session && it.state!="RELEASED"}} ?: return@runCatching
                val status=current.optString("state");val state=when(status) {
                    "SEND_UNKNOWN"->MateTaskState.UNKNOWN;"NEEDS_REAUTHORIZE"->MateTaskState.NEEDS_REAUTHORIZE;"AWAITING_USER","PENDING_APPROVAL"->MateTaskState.WAITING_FOR_USER
                    "STOPPED"->MateTaskState.PAUSED;"PAUSED","SETUP"->MateTaskState.PAUSED;else->MateTaskState.WAITING_FOR_EVENT
                }
                val identity=current.optString("id")+":"+status+":"+MemoryRules.hash(current.toString())
                if(repo.event(task.id,"guardian_$session",identity.take(240),current.optLong("message_version"),state,"托管事件：$status"))MateTaskVaultSync.schedule(c)
            }
        }
    }
    fun start(c: Context) {val app=c.applicationContext;io.execute {runCatching {
        val repo=store(app);repo.list().filter { !it.terminal && !AgentBus.isRunningFor(it.chatId) }.forEach {t->repo.update(t.id) {it.recovered()}}
        MateTaskVaultSync.schedule(app)
        MateWakeJobs.schedule(app)
    }}}
    fun awaitRecovery() {io.submit {}.get(10,java.util.concurrent.TimeUnit.SECONDS)}
    fun pauseByUser(c: Context,id: String,stop: Boolean=false) {
        val app=c.applicationContext;io.execute {
            try {
            val persistent=store(app);val repo=if(persistent.get(id)!=null)persistent else store(app,true)
            val task=repo.get(id) ?: return@execute
            if(task.terminal)return@execute
            val live=AgentBus.liveChatFor(task.chatId)
            if(live?.mateTaskId==id)AgentBus.runningIds().filter {AgentBus.chatId(it)==task.chatId}.forEach {AgentBus.requestCancel(it,true)}
            for(ref in task.resources.filter {it.kind=="guardian" && it.state!="RELEASED"}) {
                val session=dev.wind.agent.phone.ChatConversationStore.get(app).session(ref.id)
                check(session.optString("chat_id")==task.chatId) {"MATE_GUARDIAN_CHAT_MISMATCH"}
                val runtime=dev.wind.agent.phone.ChatSessionManager.active
                if(runtime?.id==ref.id) {
                    if(stop)dev.wind.agent.phone.ChatGuardianService.current?.stopGuard() else dev.wind.agent.phone.ChatGuardianService.current?.pause("用户从生活任务中心暂停")
                } else if(stop)dev.wind.agent.phone.ChatConversationStore.get(app).change(ref.id) {it.put("state","STOPPED")}
            }
            val changed=repo.update(id) {it.copy(state=if(stop)MateTaskState.CANCELLED else if(it.state==MateTaskState.UNKNOWN)MateTaskState.UNKNOWN else MateTaskState.PAUSED,
                executionId="",needsObservation=true,wakeAt=0,resources=it.resources.map {ref->ref.copy(state=if(stop)"RELEASED" else if(ref.kind=="guardian")"PAUSED" else "NEEDS_REAUTHORIZE")})}
            live?.takeIf {it.mateTaskId==id}?.let {it.mateTaskState=changed.state.name;ChatStore(app).save(it)}
            if(repo===persistent)MateTaskVaultSync.schedule(app)
            } catch(e: Throwable) {dev.wind.agent.session.TaskNotifications.event(id,"生活任务暂停/停止需要处理",e.message?.take(160) ?: "请回到任务中心核对状态")}
        }
    }
    fun assertResourceOwner(c: Context,chat: String,execution: String,kind: String,resource: String) {
        val tasks=store(c).list(chat);val current=tasks.firstOrNull {it.executionId==execution} ?: return
        check(tasks.none {it.id!=current.id && it.resources.any {r->r.kind==kind && r.id==resource && r.state in setOf("ACTIVE","PAUSED")}}) {"MATE_RESOURCE_OWNED_BY_ANOTHER_TASK"}
    }
    fun reviewPhoneUnknownByUser(c: Context,id: String,revision: Long) {
        val app=c.applicationContext;io.execute {try {
            val repo=store(app);val task=repo.get(id) ?: error("生活任务不存在")
            check(task.state==MateTaskState.UNKNOWN && task.revision==revision) {"任务已变化，请重新核对"}
            check(task.resources.none {it.kind=="browser" && it.state!="RELEASED"}) {"请在原浏览器核对并交还控制"}
            repo.update(id,revision) {it.copy(state=MateTaskState.PAUSED,needsObservation=true,executionId="",wakeAt=0,
                notes=(it.notes+"用户已在原App核对旧动作；旧未知请求保持记录，不重放。继续前重新观察。").takeLast(32))}
            MateTaskVaultSync.schedule(app)
        }catch(e: Throwable){dev.wind.agent.session.TaskNotifications.event(id,"未知动作核对未完成",e.message?.take(160) ?: "请重新核对")}} }
    fun browserReturned(c: Context,chat: String,session: String,reviewed: Boolean=false) {
        val app=c.applicationContext;io.execute {
            val repo=store(app);val task=repo.list(chat).firstOrNull {t-> !t.terminal && t.resources.any {it.kind=="browser" && it.id==session}} ?: return@execute
            if(task.state==MateTaskState.UNKNOWN && !reviewed)return@execute
            repo.update(task.id) {it.copy(state=MateTaskState.PREPARING,needsObservation=true)}
            if(!AgentBus.isRunningFor(chat))resumeByUser(c,task.id)
        }
    }
    fun resumeByUser(c: Context,id: String,expectedRevision: Long?=null,cancellation: CancellationToken?=null) {
        val app=c.applicationContext;io.execute {try {
            if(cancellation?.isCancelled==true)return@execute
            val persistent=store(app);val repo=if(persistent.get(id)!=null)persistent else store(app,true)
            val task=repo.get(id) ?: error("生活任务不存在")
            if(expectedRevision!=null && (task.revision!=expectedRevision || task.state!=MateTaskState.PREPARING))return@execute
            check(!task.terminal && task.state!=MateTaskState.UNKNOWN) {"请先在原页面核对结果未知的操作"}
            check(!AgentBus.isRunningFor(task.chatId)) {"该会话仍在执行"}
            val chat=ChatStore(app).load(task.chatId) ?: error("来源会话已删除或尚未恢复")
            chat.agentProfile=AgentProfile.MATE
            chat.mateTaskId=id;chat.mateTaskState="PREPARING";repo.update(id) {it.copy(state=MateTaskState.PREPARING,needsObservation=true,executionId="",wakeAt=0)}
            synchronized(chat.messages) {chat.messages.add(Message("user","继续该生活任务。先重新观察页面与资源，沿用目标和已核对进度；不要重复结果未知的发送或提交。旧批准、租约和页面快照不作为当前授权。").apply {modelOnly=true;origin=MessageOrigin.PROGRAMMATIC})}
            ChatStore(app).saveNow(chat)
            cancellation?.throwIfCancelled()
            val run=AgentBus.beginStarting(chat.id,chat);check(run!=0L) {"会话已启动"}
            try {AgentService.start(c,chat.id,run)}catch(e: Throwable) {AgentBus.finish(run);repo.update(id) {it.copy(state=MateTaskState.WAITING_FOR_USER,progress="请从任务中心或原会话继续，系统限制后台启动")};throw e}
        } catch(e: Throwable) {dev.wind.agent.session.TaskNotifications.event("mate_$id","生活任务需要你处理",e.message?.take(160) ?: "请回到任务中心")}} }
}
