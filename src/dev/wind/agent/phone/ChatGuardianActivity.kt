package dev.wind.agent.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject

/** User approval/takeover surface only. New guardians are established by the model tool. */
class ChatGuardianActivity: Activity() {
    private val store get()=ChatConversationStore.get(this)
    private var id=""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        id=intent.getStringExtra("start_session_id") ?: intent.getStringExtra("session_id") ?: ChatSessionManager.active?.id ?: store.list("session").firstOrNull()?.optString("id").orEmpty()
        if(id.isBlank()) { showText("尚未托管","请在AI会话中要求模型调用 phone_chat_guardian，指定微信/QQ联系人。"); return }
        val s=store.session(id)
        if(intent.hasExtra("start_session_id") && s.optString("state")=="USER_START_REQUIRED") {
            AlertDialog.Builder(this).setTitle("继续开启后台副屏托管").setMessage("${s.optString("peer")}\n"+ChatGuardianTools.RISK)
                .setPositiveButton("继续开启") { _,_->store.change(id) { it.put("state","STARTING") }; try { ChatGuardianTools.launch(this,id); finish() } catch(e:Exception) { store.change(id) { it.put("state","NEEDS_REAUTHORIZE").put("error",e.message) }; showText("启动失败",e.message.orEmpty()) } }
                .setNegativeButton("取消") { _,_->store.change(id) { it.put("state","STOPPED") }; finish() }.show()
        } else menu()
    }
    private fun menu() {
        val s=store.session(id)
        AlertDialog.Builder(this).setTitle("后台副屏托管 · ${s.optString("peer")}").setMessage("状态：${s.optString("state")}\n${s.optString("error")}")
            .setItems(arrayOf("查看待决事项","接管并暂停","停止托管","查看日报和原始引用","副屏只读预览","查看详细状态","重新确认副屏联系人和输入框")) { _,i->
                when(i) {
                    0 -> approvals()
                    1 -> { if(ChatSessionManager.active?.id==id) ChatGuardianService.current?.pause("用户接管，已暂停自动回复"); finish() }
                    2 -> { if(ChatSessionManager.active?.id==id) ChatGuardianService.current?.stopGuard(); store.change(id) { it.put("state","STOPPED") }; finish() }
                    3 -> showText("过去24小时聊天汇报",ChatReportScheduler.report(this,id).toString(2))
                    4 -> { if(ChatSessionManager.active?.id==id) startActivity(Intent(this,PhoneDisplayPreviewActivity::class.java).putExtra("guardian_id",id)); finish() }
                    5 -> showText("托管状态",ChatGuardianTools.status(this,id).toString(2))
                    6 -> Thread({
                        var result="已生成本地预览，请点击“副屏视觉绑定”通知"
                        try { ChatVisualBindingCenter.refresh(this,id) } catch(e:Exception) { result=e.message.orEmpty() }
                        runOnUiThread { Toast.makeText(this,result,Toast.LENGTH_LONG).show(); finish() }
                    },"chat-refresh-visual-binding").start()
                }
            }.setOnCancelListener { finish() }.show()
    }
    private fun approvals() {
        val items=store.list("approval",id).filter { ChatApprovalCenter.valid(it,store.session(id),System.currentTimeMillis()) }
        if(items.isEmpty()) { showText("待决事项","没有有效待决事项；过期或新消息已使旧审批失效。"); return }
        AlertDialog.Builder(this).setTitle("待决事项").setItems(items.map { it.optString("summary") }.toTypedArray()) { _,i->review(items[i]) }.setOnCancelListener { finish() }.show()
    }
    private fun review(a: JSONObject) {
        val field=EditText(this).apply { minLines=3; hint="输入你批准的确切回复"; if(!a.isNull("reply")) setText(a.optString("reply")) }
        AlertDialog.Builder(this).setTitle("批准确切回复 · ${a.optString("peer")}").setMessage("对方：${a.optString("incoming")}\n依据：${a.optString("reason")}\n新消息或过期会使本审批失效。")
            .setView(field).setPositiveButton("批准发送此文字") { _,_->
                val reply=field.text.toString()
                if(reply.isBlank() || reply.length>1000) { showText("无法发送","回复须为1至1000字"); return@setPositiveButton }
                Thread({
                    var result="已批准排队；请在状态中核对发送结果"
                    try { check(ChatSessionManager.active?.id==id); ChatGuardianService.current?.approve(a.getString("id"),reply) ?: error("托管已结束，请重新授权") }
                    catch(e:Exception) { result=e.message.orEmpty() }
                    runOnUiThread { Toast.makeText(this,result,Toast.LENGTH_LONG).show(); finish() }
                },"chat-user-approval").start()
            }.setNeutralButton("由我接管") { _,_->if(ChatSessionManager.active?.id==id) ChatGuardianService.current?.pause("用户选择亲自回复"); finish() }
            .setNegativeButton("拒绝并跳过") { _,_->store.put("approval",id,a.put("state","REJECTED")); finish() }.show()
    }
    private fun showText(title: String,text: String) {
        val view=android.widget.TextView(this).apply { this.text=text; setPadding(24,16,24,16); setTextIsSelectable(true) }
        val scroll=android.widget.ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭") { _,_->finish() }.show()
    }
}
