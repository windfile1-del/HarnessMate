package dev.wind.agent.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/** Local-only screenshot authorization. Neither the model nor OCR can approve this binding. */
internal object ChatVisualBindingCenter {
    data class Pending(val nonce: String,val session: String,val frame: ChatVisualFrame,val created: Long)
    @Volatile private var pending: Pending?=null
    fun get(nonce: String)=pending?.takeIf { it.nonce==nonce && System.currentTimeMillis()-it.created in 0..15*60*1000 }
    fun offer(c: Context,id: String,frame: ChatVisualFrame): JSONObject {
        check(ChatSessionManager.active?.id==id)
        val p=Pending(java.util.UUID.randomUUID().toString(),id,frame,System.currentTimeMillis()); pending=p
        val nm=c.getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel("chat_visual_binding","副屏视觉绑定",NotificationManager.IMPORTANCE_DEFAULT))
        val open=PendingIntent.getActivity(c,7311,Intent(c,ChatVisualBindingActivity::class.java).putExtra("nonce",p.nonce),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=if(Build.VERSION.SDK_INT>=26) Notification.Builder(c,"chat_visual_binding") else Notification.Builder(c)
        nm.notify(7311,b.setSmallIcon(dev.wind.agent.R.mipmap.ic_launcher).setContentTitle("请确认副屏联系人并标记空白输入框")
            .setContentText("无障碍树不可读；点击查看本地副屏截图，完成一次视觉绑定").setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
        return JSONObject().put("setup_status","VISUAL_CONFIRMATION_REQUIRED").put("activation_ready",false)
            .put("ui_content_available",false).put("visual_confirmation_required",true)
            .put("next_step","请用户点击“副屏视觉绑定”通知，核对完整联系人和单聊，标记标题与空白文字输入框并确认。确认后在副屏持续托管；不要用OCR部分姓名代替确认。")
    }
    fun refresh(c: Context,id: String): JSONObject {
        val r=ChatSessionManager.active?.takeIf { it.id==id } ?: error("CHAT_LEASE_REVOKED")
        return PhoneCoordinator.run(r.token) {
            val s=ChatConversationStore.get(c).session(id)
            check(s.optString("state") in setOf("SETUP","PAUSED")) { "CHAT_PAUSE_BEFORE_VISUAL_BINDING" }
            val frame=NativeChatDisplayPort(c,r.lease ?: error("CHAT_DISPLAY_LOST")).capture(r.token)
            check(frame.observation.optString("package")==s.getString("package")) { "CHAT_PAGE_NOT_VERIFIED" }
            offer(c,id,frame)
        }
    }
    fun accept(c: Context,nonce: String,header: ChatVisualScope.Region,composer: ChatVisualScope.Region) {
        val p=get(nonce) ?: error("CHAT_VISUAL_CONFIRMATION_EXPIRED")
        val r=ChatSessionManager.active?.takeIf { it.id==p.session } ?: error("CHAT_LEASE_REVOKED")
        ChatVisualScope.validate(header,composer)
        PhoneCoordinator.run(r.token) {
            val store=ChatConversationStore.get(c); val s=store.session(r.id)
            check(s.optString("state") in setOf("SETUP","PAUSED")) { "CHAT_PAUSE_BEFORE_VISUAL_BINDING" }
            val fresh=NativeChatDisplayPort(c,r.lease ?: error("CHAT_DISPLAY_LOST")).capture(r.token)
            VirtualDisplayScope.same(p.frame.observation,fresh.observation)
            check(p.frame.signature(composer)==fresh.signature(composer)) { "CHAT_CONFIRMATION_COMPOSER_CHANGED" }
            check(p.frame.signature(header)==fresh.signature(header)) { "CHAT_CONFIRMATION_SCREEN_CHANGED" }
            check(ChatVisualScope.composerText(fresh.lines,composer).isBlank()) { "CHAT_DRAFT_PRESENT" }
            val binding=ChatVisualScope.binding(s.getString("peer"),fresh.observation.getString("window"),
                fresh.observation.getString("display_session_id"),header,composer,fresh.signature(header),fresh.signature(composer))
            store.change(r.id) { it.put("visual_binding",binding).put("visual_fallback",true) }
            store.audit(r.id,"VISUAL_BOUND","用户核对完整联系人与单聊、确认空白输入框并标记两个区域；截图仅在内存中")
            ChatGuardianService.current?.activate() ?: error("CHAT_SERVICE_NOT_READY")
            pending=null
        }
    }
    fun clear(id: String) { if(pending?.session==id) pending=null }
}
