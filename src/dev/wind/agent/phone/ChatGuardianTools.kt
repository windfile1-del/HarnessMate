package dev.wind.agent.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import dev.wind.agent.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Model-callable business tool. User consent scopes background authority, never an ordinary eternal task. */
internal object ChatGuardianTools {
    private val initialized=AtomicBoolean(false)
    const val RISK="在独立后台虚拟副屏中托管指定微信/QQ联系人，新增文字消息及可见上下文会发送给当前API模型，普通闲聊可自动以你的身份回复。重要事项需单独审批。只授权此联系人和此AI会话；可以随时接管或停止。副屏可能影响设备焦点/键盘；服务、ADB或权限失效后需重新授权。无障碍树不可读时，在此副屏使用本地截图和OCR；须用户看图核对完整联系人并标记空白输入框后绑定，截图不上传模型。输入可能使用共享剪贴板。当前版本不自动切换联系人，不处理语音、图片或群聊。"
    fun request(c: Context,args: JSONObject,token: CancellationToken): JSONObject {
        val store=ChatConversationStore.get(c)
        if(initialized.compareAndSet(false,true) && ChatSessionManager.active==null) store.recoverProcess()
        val chatId=AgentBus.activeChatId ?: error("CHAT_AI_SESSION_REQUIRED")
        val mode=args.optString("mode","status")
        if(mode in setOf("prepare","start"))check(AgentBus.liveChatFor(chatId)?.retentionPolicy!=dev.wind.agent.profile.RetentionPolicy.TEMPORARY) {"CHAT_PERSIST_SESSION_BEFORE_GUARDIAN: 持续托管需先保存此临时会话"}
        if(mode in setOf("prepare","start")) return prepare(c,args,chatId,token,mode=="start")
        val r=ChatSessionManager.active
        val id=args.optString("session_id").ifBlank { r?.id ?: store.list("session").firstOrNull { it.optString("chat_id")==chatId }?.optString("id").orEmpty() }
        if(id.isBlank()) return JSONObject().put("state","OFF").put("screen","virtual").put("single_contact",true)
        val s=store.session(id); check(s.optString("chat_id")==chatId) { "CHAT_AI_SESSION_MISMATCH" }
        when(mode) {
            "status" -> return status(c,id)
            "report" -> return ChatReportScheduler.report(c,id)
            "pending" -> return JSONObject().put("session_id",id).put("approvals",JSONArray(store.list("approval",id).filter { it.optString("state")=="PENDING" }))
            "stop" -> {
                if(r?.id==id) ChatGuardianService.current?.stopGuard()
                store.change(id) { it.put("state","STOPPED") }; return status(c,id)
            }
            "pause", "takeover" -> { check(r?.id==id); ChatGuardianService.current?.pause("已接管；模型可在暂停状态查看或操作此副屏"); return status(c,id) }
            "activate", "resume" -> {
                check(r?.id==id) { "CHAT_NEW_USER_AUTHORIZATION_REQUIRED" }
                if(args.optBoolean("rebind_visual")) return status(c,id).also { out->
                    val binding=ChatVisualBindingCenter.refresh(c,id); binding.keys().forEach { key->out.put(key,binding.get(key)) }
                }
                return activate(c,id)
            }
            "configure" -> {
                check(r?.id==id && s.optString("state") in setOf("SETUP","PAUSED")) { "CHAT_PAUSE_BEFORE_CONFIGURE" }
                require(args.has("instructions") || args.has("report_hour"))
                val instructions=args.optString("instructions",s.getString("instructions"))
                val hour=args.optInt("report_hour",s.optInt("report_hour",21))
                require(instructions.length in 1..4000 && hour in -1..23)
                val review=JSONObject().put("session_id",id).put("peer",s.getString("peer")).put("instructions",instructions).put("report_hour",hour)
                if(!AgentBus.awaitApprovalDetailed("phone_chat_guardian_configure",review,token).approved) return JSONObject().put("state","CONSENT_DECLINED")
                token.throwIfCancelled()
                check(ChatSessionManager.active?.id==id && store.session(id).optString("state") in setOf("SETUP","PAUSED")) { "CHAT_SESSION_CHANGED" }
                store.change(id) { it.put("instructions",instructions).put("report_hour",hour).put("message_version",it.optLong("message_version")+1) }
                store.audit(id,"CONFIGURED","用户确认更新背景、风格或报告时间；保持暂停直到重新激活")
                return status(c,id)
            }
            "approve" -> {
                val a=store.record(args.getString("approval_id")) ?: error("CHAT_APPROVAL_NOT_FOUND")
                check(a.optString("session_id")==id && ChatApprovalCenter.valid(a,s,System.currentTimeMillis())) { "CHAT_APPROVAL_STALE_OR_EXPIRED" }
                val reply=args.optString("reply").ifBlank { if(a.isNull("reply")) "" else a.optString("reply") }
                require(reply.isNotBlank() && reply.length<=1000) { "CHAT_EXACT_APPROVED_REPLY_REQUIRED" }
                val reviewed=JSONObject(a.toString()).put("exact_reply",reply)
                if(!AgentBus.awaitApprovalDetailed("phone_chat_guardian_approve",reviewed,token).approved) return JSONObject().put("state","CONSENT_DECLINED")
                token.throwIfCancelled(); ChatGuardianService.current?.approve(a.getString("id"),reply) ?: error("CHAT_SERVICE_NOT_READY")
                return JSONObject().put("state","APPROVED_QUEUED").put("session_id",id).put("business_success_verified",false)
            }
            else -> error("CHAT_GUARDIAN_MODE_INVALID")
        }
    }
    private fun prepare(c: Context,args: JSONObject,chatId: String,token: CancellationToken,activate: Boolean): JSONObject {
        require(!args.has("screen") || args.optString("screen")=="virtual") { "CHAT_GUARDIAN_VIRTUAL_ONLY" }
        check(ChatSessionManager.active==null) { "CHAT_GUARDIAN_ALREADY_ACTIVE: use status/pause/stop" }
        ChatSessionManager.checkPermissions(c)
        val pkg=args.getString("package"); val peer=args.getString("peer").trim()
        require(pkg in ChatAssist.packages && peer.length in 1..160) { "CHAT_PEER_INVALID" }
        check(pkg in VirtualDisplays.allowed(c)) { "CHAT_PACKAGE_NOT_ALLOWED" }
        check(VirtualDisplays.preview(AgentBus.activeTaskId.orEmpty())==null) { "CHAT_CLOSE_TASK_DISPLAY_FIRST: prepare uses an independent display" }
        check(ChatAssist.binding(c).optString("mode")!="auto") { "CHAT_STOP_LEGACY_AUTO_FIRST" }
        val instructions=args.optString("instructions","自然简洁地交流，仅处理普通闲聊，不做额外承诺")
        require(instructions.length in 1..4000)
        val hour=args.optInt("report_hour",21); require(hour in -1..23)
        val review=JSONObject().put("package",pkg).put("peer",peer).put("instructions",instructions).put("report_hour",hour).put("privacy_risk",RISK).put("AI_session",chatId)
        if(!AgentBus.awaitApprovalDetailed("phone_chat_guardian_start",review,token).approved) return JSONObject().put("state","CONSENT_DECLINED")
        token.throwIfCancelled(); ChatSessionManager.checkPermissions(c)
        val id=java.util.UUID.randomUUID().toString(); val store=ChatConversationStore.get(c)
        val s=JSONObject().put("id",id).put("chat_id",chatId).put("package",pkg).put("peer",peer).put("instructions",instructions)
            .put("model_key",ChatSessionManager.modelKey(Prefs(c).freezeModel())).put("state","STARTING").put("message_version",0)
            .put("report_hour",hour).put("created_at",System.currentTimeMillis()).put("screen","virtual")
        store.put("session",id,s)
        try { launch(c,id) } catch(e: Exception) {
            store.change(id) { it.put("state","USER_START_REQUIRED").put("error",e.message) }
            postStart(c,id)
            return status(c,id).put("next_step","系统限制后台启动，请点击托管通知继续开启；然后查询 status。")
        }
        val deadline=android.os.SystemClock.elapsedRealtime()+25000
        while(store.session(id).optString("state")=="STARTING" && android.os.SystemClock.elapsedRealtime()<deadline) { token.sleep(100); token.throwIfCancelled() }
        if(activate && store.session(id).optString("state")=="SETUP") return activate(c,id)
        return status(c,id).put("next_step","SETUP 时用 display_session_id + screen=virtual 导航并核对联系人，随后调用 activate。托管自行运行，无需让普通任务常驻。")
    }
    private fun activate(c: Context,id: String): JSONObject {
        try {
            ChatGuardianService.current?.activate() ?: error("CHAT_SERVICE_NOT_READY")
            return status(c,id).put("activation_ready",true)
        } catch(e: Exception) {
            if(e is ChatVisualBindingRequired) return status(c,id).also { out->
                val binding=ChatVisualBindingCenter.offer(c,id,e.frame)
                binding.keys().forEach { out.put(it,binding.get(it)) }
            }
            val code=e.message.orEmpty()
            val diagnostic=ChatSetupReadiness.diagnostic(code) ?: throw e
            return status(c,id).put("activation_ready",false).put("setup_status",diagnostic.first)
                .put("setup_error",code).put("expected_peer",ChatConversationStore.get(c).session(id).getString("peer"))
                .put("main_screen_fallback",false).put("next_step",diagnostic.second)
        }
    }
    internal fun launch(c: Context,id: String) {
        val intent=Intent(c,ChatGuardianService::class.java).putExtra("session_id",id)
        if(Build.VERSION.SDK_INT>=26) c.startForegroundService(intent) else c.startService(intent)
    }
    internal fun status(c: Context,id: String): JSONObject {
        val store=ChatConversationStore.get(c); val s=store.session(id)
        val batches=store.list("batch",id,1000)
        return JSONObject().put("session_id",id).put("state",s.optString("state")).put("package",s.optString("package")).put("peer",s.optString("peer"))
            .put("screen","virtual").put("display_session_id",s.opt("display_session_id") ?: JSONObject.NULL).put("display_id",s.opt("display_id") ?: JSONObject.NULL)
            .put("message_version",s.optLong("message_version")).put("pending_approvals",store.list("approval",id,1000).count { ChatApprovalCenter.valid(it,s,System.currentTimeMillis()) })
            .put("self_messages_observed",batches.count { it.optString("state")=="SELF_MESSAGE_OBSERVED" }).put("send_unknown",batches.count { it.optString("state")=="SEND_UNKNOWN" })
            .put("error",s.opt("error") ?: JSONObject.NULL).put("recipient_delivery","UNKNOWN").put("single_contact",true)
            .put("observation_error",s.opt("observation_error") ?: JSONObject.NULL)
            .put("observation_source",s.optString("observation_source","NOT_ACTIVATED")).put("visual_bound",s.optJSONObject("visual_binding")?.optBoolean("user_confirmed")==true)
            .put("display_alive",ChatSessionManager.active?.takeIf { it.id==id }?.lease?.valid()==true)
            .put("event_stream_state",s.optString("event_stream_state","NOT_STARTED"))
            .put("event_stream_error",s.opt("event_stream_error") ?: JSONObject.NULL)
            .put("local_reconcile_interval_ms",ChatEventStreamPolicy.POLL_INTERVAL_MS)
            .put("foreground_service_running",ChatSessionManager.active?.id==id)
            .put("cpu_wake_lock_held",ChatSessionManager.active?.id==id && ChatGuardianService.current?.cpuHeld()==true)
    }
    private fun postStart(c: Context,id: String) {
        val nm=c.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(ChatGuardianService.ALERT_CHANNEL,"聊天托管待决事项",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(c,7303,Intent(c,ChatGuardianActivity::class.java).putExtra("start_session_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=if(Build.VERSION.SDK_INT>=26) Notification.Builder(c,ChatGuardianService.ALERT_CHANNEL) else Notification.Builder(c)
        nm.notify(7303,b.setSmallIcon(dev.wind.agent.R.mipmap.ic_launcher).setContentTitle("点击继续开启后台聊天托管")
            .setContentText("指定联系人授权已保存；需要你在可见界面启动服务").setContentIntent(open).setAutoCancel(true).build())
    }
}
