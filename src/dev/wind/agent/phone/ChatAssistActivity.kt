package dev.wind.agent.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.wind.agent.Prefs
import dev.wind.agent.MainActivity
import dev.wind.agent.screen.ScreenSharePermissionActivity

/** User-facing binding, style controls and system permission entrypoints, including notification-free binding. */
class ChatAssistActivity : Activity() {
    private val chatId get() = intent.getStringExtra("chat_id") ?: Prefs(this).lastChatId()
    override fun onCreate(state: Bundle?) { super.onCreate(state); menu() }
    private fun menu() {
        val b = ChatAssist.binding(this)
        AlertDialog.Builder(this).setTitle("聊天助手 · ${b.optString("title", "未绑定")} · ${b.optString("mode", "off")}")
            .setItems(arrayOf("开启回复建议（手动发送）", "开启自动回复托管", "背景与回复风格", "打开可见 AI 会话并发送指令", "处理当前最新消息（自动模式会发送）", "屏幕共享 / 视觉识别授权", "自动化服务与 AI 输入法", "通知访问设置（后台收信）", "重新选择聊天对象", "停止聊天助手", "查看状态", "后台副屏托管状态与审批")) { _, i ->
                when (i) {
                    0, 1 -> select(if (i == 0) "suggest" else "auto")
                    2 -> editInstructions()
                    3 -> { startActivity(Intent(this, MainActivity::class.java).putExtra("floatingChatId", b.optString("chat_id", chatId)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish() }
                    4 -> { ChatForegroundMonitor.reset(this, analyseCurrent = true); finish() }
                    5 -> { ScreenSharePermissionActivity.open(this, chatId); finish() }
                    6 -> { PhoneControlsActivity.open(this, true); finish() }
                    7 -> { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); finish() }
                    8 -> select(b.optString("mode", "suggest").takeIf { it == "auto" } ?: "suggest", rebind = true)
                    9 -> { ChatAssist.disable(this); finish() }
                    11 -> { startActivity(Intent(this, ChatGuardianActivity::class.java)); finish() }
                    10 -> AlertDialog.Builder(this).setMessage(ChatAssist.status(this).toString(2)).setPositiveButton("关闭") { _, _ -> finish() }.show()
                }
            }.setOnCancelListener { finish() }.show()
    }
    private fun select(mode: String, rebind: Boolean = false) {
        val old = ChatAssist.binding(this)
        if (!rebind && old.optString("mode") == mode && old.optString("chat_id") == chatId) {
            Toast.makeText(this, "此会话已经开启，可直接补充风格要求", Toast.LENGTH_LONG).show(); finish(); return
        }
        AlertDialog.Builder(this).setTitle(if (mode == "auto") "自动回复风险与隐私" else "回复建议隐私说明")
            .setMessage(ChatAssist.RISK + "\n前台识别无需安卓通知。节点缺失时请授权屏幕共享；视觉填字需启用 Vega AI 输入法。")
            .setPositiveButton("选择聊天并授权") { _, _ -> choose(mode) }.setNegativeButton("取消") { _, _ -> finish() }.show()
    }
    private fun choose(mode: String) {
        val entries = ChatAssist.entries()
        val labels = listOf("绑定当前前台聊天（无需通知）") + entries.map { "${it.title} · ${it.pkg}" }
        AlertDialog.Builder(this).setTitle("仅绑定指定聊天")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == 0) named(mode) else confirm(mode, entries[i - 1].pkg, entries[i - 1].title, entries[i - 1])
            }.setOnCancelListener { finish() }.show()
    }
    private fun named(mode: String) {
        AlertDialog.Builder(this).setTitle("目标应用").setItems(arrayOf("微信", "QQ", "TIM")) { _, i ->
            val pkg = arrayOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")[i]
            val field = EditText(this).apply { hint = "当前聊天顶部显示的联系人名称"; setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("请输入准确聊天名称").setView(field)
                .setPositiveButton("绑定此联系人") { _, _ -> confirm(mode, pkg, field.text.toString().trim(), null) }
                .setNegativeButton("取消") { _, _ -> finish() }.show()
        }.setOnCancelListener { finish() }.show()
    }
    private fun confirm(mode: String, pkg: String, title: String, e: ChatAssist.Entry?) {
        if (title.isBlank() || chatId.isBlank()) { Toast.makeText(this, "需要联系人名称与 AI 会话", Toast.LENGTH_LONG).show(); finish(); return }
        val field = EditText(this).apply { hint = "可选：对方背景、沟通目标、回复风格"; minLines = 3 }
        AlertDialog.Builder(this).setTitle("授权 ${title} · ${if (mode == "auto") "自动发送" else "仅建议"}")
            .setMessage(ChatAssist.RISK).setView(field).setPositiveButton("确认开启") { _, _ ->
                try {
                    ChatAssist.enableNamed(this, chatId, pkg, title, mode, field.text.toString().ifBlank { "根据对方消息回复，不做额外承诺" }, e)
                    if (mode == "suggest") ChatForegroundMonitor.reset(this, analyseCurrent = true)
                    Toast.makeText(this, "已开启，请保持目标聊天显示；任务与结果会出现在 AI 会话中", Toast.LENGTH_LONG).show()
                } catch (failure: Exception) { Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show() }
                finish()
            }.setNegativeButton("取消") { _, _ -> finish() }.show()
    }
    private fun editInstructions() {
        val b = ChatAssist.binding(this)
        if (b.optString("mode") !in setOf("auto", "suggest")) { Toast.makeText(this, "请先绑定聊天", Toast.LENGTH_LONG).show(); finish(); return }
        val field = EditText(this).apply { minLines = 4; setText(b.optString("instructions")) }
        AlertDialog.Builder(this).setTitle("联系人背景与回复风格").setView(field)
            .setPositiveButton("保存要求") { _, _ ->
                try { ChatAssist.updateInstructions(this, field.text.toString()); Toast.makeText(this, "已保存，后续回复使用新要求", Toast.LENGTH_LONG).show() }
                catch (failure: Exception) { Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show() }; finish()
            }.setNegativeButton("取消") { _, _ -> finish() }.show()
    }
    companion object { fun open(c: Context, chatId: String?) { c.startActivity(Intent(c, ChatAssistActivity::class.java).putExtra("chat_id", chatId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
