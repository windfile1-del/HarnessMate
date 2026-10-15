package dev.wind.agent.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.wind.agent.CancellationToken
import dev.wind.agent.R
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Long-lived virtual-display owner. Inference is idle until a persistent incoming batch exists. */
class ChatGuardianService: Service() {
    private val worker=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"chat-guardian-model").apply { isDaemon=true } }
    private val modeling=AtomicBoolean(false)
    private var runtime: ChatSessionManager.Runtime?=null
    private var hub: ChatEventHub?=null
    private var attentionText: String?=null
    private var attentionIsApproval=false
    private var announcedAttention: String?=null
    private var wakeLock: PowerManager.WakeLock?=null
    private val wakeMain=Handler(Looper.getMainLooper())
    private val renewWake=object: Runnable {
        override fun run() {
            if(runtime?.token?.isCancelled!=false)return
            try {wakeLock?.acquire(600000)}catch(e: Exception){fail("后台CPU保持失败：${e.message}",true);return}
            wakeMain.postDelayed(this,60000)
        }
    }
    internal fun cpuHeld()=wakeLock?.isHeld==true
    private val store get()=ChatConversationStore.get(this)
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") { stopGuard(); return START_NOT_STICKY }
        if(intent?.action=="pause") { pause("已由用户接管"); return START_NOT_STICKY }
        val id=intent?.getStringExtra("session_id")
        if(id.isNullOrBlank()) { store.recoverProcess(); stopSelf(); return START_NOT_STICKY }
        if(runtime!=null) return START_NOT_STICKY
        try {
            channels()
            val n=notification("正在建立后台副屏",true)
            if(Build.VERSION.SDK_INT>=34) startForeground(NOTICE,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else if(Build.VERSION.SDK_INT>=29) startForeground(NOTICE,n,0) // Android 12: no Android-14-only service type.
            else startForeground(NOTICE,n)
            runtime=ChatSessionManager.begin(this,id); current=this
            wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"harness:chat-guardian").also {
                it.setReferenceCounted(false);it.acquire(600000)
            }
            wakeMain.postDelayed(renewWake,60000)
            worker.execute {
                try {
                    val r=runtime ?: return@execute
                    val lease=ChatSessionManager.open(this,r)
                    hub=ChatEventHub { observe() }
                    store.change(r.id) { it.put("event_stream_state","PUSH").put("event_stream_error",JSONObject.NULL) }
                    lease.bridge.watchEvents { e->
                        if(e.optBoolean("fatal")) fail("事件通道授权或身份失效："+e.optString("error"),reauthorize=true)
                        else if(e.has("event_stream_state")) {
                            val mode=e.getString("event_stream_state")
                            store.change(r.id) { it.put("event_stream_state",mode).put("event_stream_error",e.opt("error") ?: JSONObject.NULL) }
                            hub?.signal()
                            notice(if(mode=="PUSH") "副屏事件通知已恢复，托管状态保持不变" else "事件通知暂不可用，保留副屏并使用本地消息核对")
                        } else if(e.optString("display_session_id")==lease.id && e.optInt("display_id")==lease.identity.getInt("display_id")) hub?.signal()
                    }
                    ChatReportScheduler.schedule(this)
                    notice("副屏已建立，等待模型核对联系人后激活")
                } catch(e: Exception) { fail(e.message ?: "CHAT_START_FAILED",reauthorize=true) }
            }
        } catch(e: Exception) { store.change(id) { it.put("state","NEEDS_REAUTHORIZE").put("error",e.message) }; stopSelf() }
        return START_NOT_STICKY
    }
    internal fun activate() {
        val r=runtime ?: error("CHAT_SERVICE_NOT_READY")
        PhoneCoordinator.run(r.token) {
            val s=store.session(r.id); check(s.getString("state") in setOf("SETUP","PAUSED")) { "CHAT_NOT_PREPARED" }
            check(r.lease?.valid()==true) { "CHAT_DISPLAY_LOST" }
            val setup=JSONObject(s.toString()).also { it.remove("window"); it.remove("structural_epoch") }
            val page=ChatAppAdapter(r.lease!!,setup,this).read(r.token)
            check(page.editor.second.text.isBlank()) { "CHAT_DRAFT_PRESENT" }
            store.transaction {
                store.list("approval",r.id).filter { it.optString("state") in setOf("PENDING","APPROVED") }.forEach { store.put("approval",r.id,it.put("state","STALE")) }
                store.list("batch",r.id).filter { it.optString("state") in setOf("PENDING","MODELING","WAITING") }.forEach { store.put("batch",r.id,it.put("state","SUPERSEDED")) }
                store.change(r.id) { it.put("state","RUNNING").put("window",page.observation.getString("window")).put("structural_epoch",page.observation.getLong("structural_epoch")).put("baseline",ChatSessionKeys.json(page.turns)).put("empty_baseline_pending",page.turns.isEmpty()).put("observation_source",if(page.visual==null) "UI" else "LOCAL_OCR").put("visual_body_hash",page.bodyHash ?: JSONObject.NULL)
                    .put("message_version",it.optLong("message_version")+1).remove("error") }
            }
            store.audit(r.id,"ACTIVATED","已核对指定联系人；当前历史仅作基线，不自动回复旧消息")
        }
        clearAttention()
        notice("后台托管中；仅处理指定联系人的新增文字消息"); hub?.signal()
    }
    internal fun pause(reason: String) {
        val r=runtime ?: return
        if(store.session(r.id).optString("state")=="STOPPED") return
        store.change(r.id) { it.put("state","PAUSED").put("error",reason) }; r.modelToken?.cancel(); r.deliveryToken?.cancel()
        store.list("approval",r.id).filter { it.optString("state") in setOf("PENDING","APPROVED") }.forEach { store.put("approval",r.id,it.put("state","STALE")) }
        clearAttention()
        notice(reason)
    }
    private fun observe() {
        val r=runtime ?: return
        try {
            if(r.token.isCancelled) return
            if(r.lease?.valid()!=true) { fail("副屏、ADB或权限已失效，需要重新授权",true); return }
            if(store.session(r.id).optString("state")!="RUNNING") return
            PhoneCoordinator.run(r.token) {
                val s=store.session(r.id)
                if(s.optString("state")!="RUNNING") return@run
                val page=ChatAppAdapter(r.lease!!,s,this).read(r.token)
                store.observe(r.id,page.turns,visualHash=page.bodyHash)
                if(s.has("observation_error")) store.change(r.id) { it.remove("observation_error") }
            }
            store.list("approval",r.id).filter { it.optString("state")=="PENDING" && it.optLong("expires_at")<=System.currentTimeMillis() }.forEach { store.put("approval",r.id,it.put("state","EXPIRED")) }
            clearResolvedApprovalAttention()
            dispatch()
        } catch(e: Exception) {
            if(!r.token.isCancelled) {
                if(ChatVisualReadPolicy.transient(e.message.orEmpty().substringBefore(':'))) {
                    store.change(r.id) { it.put("observation_error",e.message) }; hub?.signal()
                } else fail(e.message ?: "CHAT_OBSERVATION_FAILED")
            }
        }
    }
    private fun dispatch() {
        val r=runtime ?: return
        if(store.session(r.id).optString("state")!="RUNNING" || !modeling.compareAndSet(false,true)) return
        worker.execute {
            try {
                val s=store.session(r.id)
                val batch=store.list("batch",r.id).firstOrNull { it.optLong("message_version")==s.optLong("message_version") &&
                    (it.optString("state")=="PENDING" || it.optString("state")=="WAITING" && it.optLong("retry_at",Long.MAX_VALUE)<=System.currentTimeMillis()) } ?: return@execute
                val version=batch.getLong("message_version")
                ChatSessionManager.authority(this,r,version)
                batch.put("state","MODELING"); store.put("batch",r.id,batch)
                val token=CancellationToken(); r.modelToken=token
                val hard=ChatDecisionPolicy.approvalReason(batch.getString("incoming"))
                val result=if(hard!=null) ChatDecisionResult(ChatDecision.ASK_USER,null,hard,batch.getString("incoming").take(600))
                    else ChatAgentDispatcher.decide(r.model,s,batch,store.list("batch",r.id).filter { it.has("summary") },token)
                token.throwIfCancelled()
                // Refresh before applying a decision so an unobserved new message cannot inherit old approval.
                PhoneCoordinator.run(r.token) { val page=ChatAppAdapter(r.lease!!,store.session(r.id),this).read(r.token); store.observe(r.id,page.turns,visualHash=page.bodyHash) }
                if(store.session(r.id).optLong("message_version")!=version) return@execute
                ChatSessionManager.authority(this,r,version)
                batch.put("summary",result.summary).put("reason",result.reason).put("reply",result.reply ?: JSONObject.NULL)
                when(result.decision) {
                    ChatDecision.ASK_USER -> {
                        store.transaction {
                            val approval=ChatApprovalCenter.draft(store.session(r.id),batch,result,System.currentTimeMillis())
                            store.put("approval",r.id,approval); store.put("batch",r.id,batch.put("state","AWAITING_USER").put("approval_id",approval.getString("id")))
                        }
                        notice("有重要事项等待你决定",alert=true,approval=true)
                    }
                    ChatDecision.IGNORE -> store.put("batch",r.id,batch.put("state","IGNORED"))
                    ChatDecision.WAIT -> {
                        val count=batch.optInt("wait_count")+1
                        store.put("batch",r.id,batch.put("state","WAITING").put("wait_count",count).put("retry_at",Long.MAX_VALUE))
                        // The next observed message supersedes this batch. No paid unchanged-context polling.
                    }
                    ChatDecision.REPLY -> send(batch,result.reply!!,null)
                }
            } catch(e: Exception) {
                if(runtime===r && store.session(r.id).optString("state")=="RUNNING") fail(e.message ?: "CHAT_MODEL_FAILED")
            } finally { r.modelToken=null; modeling.set(false); if(store.session(r.id).optString("state")=="RUNNING" && store.list("batch",r.id).any { it.optString("state")=="PENDING" }) hub?.signal() }
        }
    }
    /** This continuation sends the exact approved content. No fresh model inference is performed. */
    internal fun approve(id: String, reply: String) {
        val r=runtime ?: error("CHAT_SERVICE_NOT_READY")
        val approval=store.record(id) ?: error("CHAT_APPROVAL_NOT_FOUND")
        require(reply.isNotBlank() && reply.length<=1000)
        PhoneCoordinator.run(r.token) {
            val s=store.session(r.id)
            check(store.record(id)?.optString("state")=="PENDING") { "CHAT_APPROVAL_ALREADY_USED" }
            check(ChatApprovalCenter.valid(approval,s,System.currentTimeMillis())) { "CHAT_APPROVAL_STALE_OR_EXPIRED" }
            val page=ChatAppAdapter(r.lease!!,s,this).read(r.token); store.observe(r.id,page.turns,visualHash=page.bodyHash)
            check(ChatApprovalCenter.valid(approval,store.session(r.id),System.currentTimeMillis())) { "CHAT_APPROVAL_NEW_MESSAGE" }
            approval.put("state","APPROVED").put("approved_reply",reply)
            store.put("approval",r.id,approval)
        }
        worker.execute {
            try {
                val batch=store.record(r.id+":"+approval.getLong("message_version")) ?: error("CHAT_APPROVAL_BATCH_MISSING")
                send(batch,reply,approval)
            } catch(e: Exception) { if(runtime===r) fail(e.message ?: "CHAT_APPROVED_SEND_FAILED") }
        }
    }
    private fun send(batch: JSONObject,reply: String,approval: JSONObject?) {
        val r=runtime ?: error("CHAT_SERVICE_NOT_READY"); val version=batch.getLong("message_version")
        val delivery=CancellationToken(); r.deliveryToken=delivery
        val registration=r.token.onCancel { delivery.cancel() }
        val guard={
            delivery.throwIfCancelled()
            ChatSessionManager.authority(this,r,version)
            if(approval!=null) {
                val saved=store.record(approval.getString("id")) ?: error("CHAT_APPROVAL_MISSING")
                check(saved.optString("state")=="APPROVED" && saved.optString("approved_reply")==reply &&
                    System.currentTimeMillis()<saved.getLong("expires_at")) { "CHAT_APPROVAL_CHANGED" }
            }
        }
        guard()
        // One batch can reserve at most one delivery attempt, including failures/unknown results.
        store.transaction {
            val saved=store.record(batch.getString("id")) ?: error("CHAT_BATCH_MISSING")
            check(saved.optString("state") in setOf("MODELING","AWAITING_USER")) { "CHAT_SEND_ALREADY_ATTEMPTED" }
            store.put("batch",r.id,batch.put("state","RESERVED").put("reply",reply))
        }
        try {
            val result=PhoneCoordinator.run(delivery) { ChatAppAdapter(r.lease!!,store.session(r.id),this).send(reply,
                ChatDeliveryVerifier.pageKey(ChatSessionKeys.turns(batch.getJSONArray("context"))),delivery,guard) { state,evidence->
                store.put("batch",r.id,batch.put("state",state).put("receipt",evidence ?: JSONObject.NULL))
            } }
            store.transaction {
                store.put("batch",r.id,batch.put("state",result.getString("status")).put("receipt",result))
                if(approval!=null) store.put("approval",r.id,approval.put("state","CONSUMED"))
                store.audit(r.id,result.getString("status"),reply,result)
            }
            if(result.optString("status")!="SELF_MESSAGE_OBSERVED") fail("发送结果未知，请检查副屏并接管；不会自动重发")
            else clearResolvedApprovalAttention()
        } catch(e: Exception) {
            store.put("batch",r.id,batch.put("state","SEND_UNKNOWN").put("error",e.message)); fail("发送过程已暂停："+e.message+"；不会自动重发")
        } finally { registration.close(); r.deliveryToken=null }
    }
    private fun fail(reason: String,reauthorize: Boolean=false) {
        val r=runtime ?: return
        if(store.session(r.id).optString("state")=="STOPPED") return
        if(reauthorize) {
            store.change(r.id) { it.put("state","NEEDS_REAUTHORIZE").put("error",reason) }
            ChatSessionManager.close(r.id); hub?.close(); notice(reason,alert=true); stopSelf()
        } else { pause(reason); store.audit(r.id,"PAUSED",reason); notice(reason,alert=true) }
    }
    internal fun stopGuard() {
        clearAttention()
        val r=runtime
        if(r!=null) { store.change(r.id) { it.put("state","STOPPED") }; store.audit(r.id,"STOPPED","用户结束托管"); ChatSessionManager.close(r.id); ChatVisualBindingCenter.clear(r.id) }
        hub?.close(); stopForeground(true); stopSelf()
    }
    override fun onDestroy() {
        wakeMain.removeCallbacks(renewWake)
        runCatching {wakeLock?.let {if(it.isHeld)it.release()}};wakeLock=null
        val r=runtime
        if(r!=null) {
            val state=store.session(r.id).optString("state")
            if(state !in setOf("STOPPED","NEEDS_REAUTHORIZE")) store.change(r.id) { it.put("state","NEEDS_REAUTHORIZE").put("error","服务已结束，必须重新授权") }
            ChatSessionManager.close(r.id)
        }
        hub?.close(); worker.shutdownNow(); if(current===this) current=null; super.onDestroy()
    }
    private fun channels() { if(Build.VERSION.SDK_INT>=26) {
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"聊天托管状态",NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,"聊天托管待决事项",NotificationManager.IMPORTANCE_DEFAULT))
    } }
    private fun notification(text: String,ongoing: Boolean,alert: Boolean=false): Notification {
        val open=PendingIntent.getActivity(this,NOTICE,Intent(this,ChatGuardianActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,if(alert) ALERT_CHANNEL else CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Harness Mate · 后台聊天托管").setContentText(text.take(120))
            .setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(!alert).setVisibility(Notification.VISIBILITY_PRIVATE)
        if(ongoing) {
            val pause=PendingIntent.getService(this,NOTICE+1,Intent(this,ChatGuardianService::class.java).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop=PendingIntent.getService(this,NOTICE+2,Intent(this,ChatGuardianService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null,"接管",pause).build()).addAction(Notification.Action.Builder(null,"停止",stop).build())
        }
        return builder.build()
    }
    @Synchronized private fun clearAttention() {
        attentionText=null;attentionIsApproval=false;announcedAttention=null
        runCatching {getSystemService(NotificationManager::class.java).cancel(ALERT_NOTICE)}
    }
    @Synchronized private fun clearResolvedApprovalAttention() {
        val r=runtime ?: return
        if(attentionIsApproval && store.list("approval",r.id).none {ChatApprovalCenter.valid(it,store.session(r.id),System.currentTimeMillis())}) {
            clearAttention();notice("后台托管中；等待指定联系人新增文字消息")
        }
    }
    internal fun notifyAttentionWhenBackground() {
        val r=runtime ?: return
        val s=store.session(r.id)
        if(s.optString("state")=="STOPPED")return
        if(store.list("approval",r.id).any {ChatApprovalCenter.valid(it,s,System.currentTimeMillis())})notice("有重要事项等待你决定",true,true)
        else if(!attentionIsApproval && s.optString("state") in setOf("PAUSED","NEEDS_REAUTHORIZE"))attentionText?.let {notice(it,true)}
        else clearResolvedApprovalAttention()
    }
    @Synchronized private fun notice(text: String,alert: Boolean=false,approval: Boolean=false) {
        val notifyAlert=alert && dev.wind.agent.session.TaskNotifications.shouldAlert(this)
        if(alert){attentionText=text;attentionIsApproval=approval}
        val key=runtime?.let {it.id+":"+store.session(it.id).optLong("message_version")+":"+text}
        if(notifyAlert && key==announcedAttention)return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(if(notifyAlert) ALERT_NOTICE else NOTICE,notification(text,!notifyAlert,notifyAlert))
            if(notifyAlert)announcedAttention=key
        }
    }
    companion object {
        const val NOTICE=7301; const val ALERT_NOTICE=7302
        const val CHANNEL="chat_guardian"; const val ALERT_CHANNEL="chat_guardian_attention"
        @Volatile internal var current: ChatGuardianService?=null; private set
        internal fun signal(pkg: String) {
            val service=current ?: return; val r=service.runtime ?: return
            if(service.store.session(r.id).optString("package")==pkg) service.hub?.signal()
        }
    }
}
