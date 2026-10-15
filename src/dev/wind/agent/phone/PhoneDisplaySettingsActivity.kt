package dev.wind.agent.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.wind.agent.AgentBus
import dev.wind.agent.Fa
import dev.wind.agent.CancellationToken

/** User-owned configuration; compatibility inspection never creates or controls a display. */
class PhoneDisplaySettingsActivity : Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private var statusText: TextView?=null
    private var inspection: CancellationToken?=null
    private var inspectionMessage=""
    private val refresh=object: Runnable {
        override fun run() { if(!isFinishing && !isDestroyed) { updateStatus(); ui.postDelayed(this,1000) } }
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); Fa.apply(this); render() }
    private fun label(zh: String,en: String)=if(Fa.chinese) zh else en
    private fun act(block: () -> Unit) { try { block() } catch(e: Exception) { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() } }
    private fun render() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        box.addView(TextView(this).apply {
            text=label("后台虚拟屏幕\n无需校准。授权并连接 ADB、选择允许应用与后台目标后，AI 可直接创建副屏。缺少独立焦点接口时使用共享焦点，主屏可能被抢焦点或键盘受影响。默认目标可随时切换，每次工具调用也可选择主屏或副屏；应用名单修改仍需结束任务。", "Background display\nNo calibration required. Grant access, connect ADB, choose apps and target, then AI can create the display. Shared focus is used when isolation APIs are absent; main-screen focus or keyboard may be affected. Change the default target at any time; tools may select either screen per call. App-list changes require an idle task.")
        })
        statusText=TextView(this).also { box.addView(it) }
        fun button(text: String,action: () -> Unit) { box.addView(Button(this).apply { this.text=text; setOnClickListener { act(action) } }) }
        button(label("1. Agent 权限（四项独立授权）", "1. Agent permissions (four grants)")) {
            dev.wind.agent.permissions.AgentPermissionsUi.open(this)
        }
        button(label("2. 本机无线 ADB：配对与连接", "2. Local ADB: pair and connect")) {
            dev.wind.agent.adb.AdbSettingsUi.open(this)
        }
        button(label("默认显示目标：", "Default target: ")+VirtualDisplays.preference(this)) {
            AlertDialog.Builder(this).setTitle(label("显示目标", "Display target"))
                .setSingleChoiceItems(arrayOf(label("主屏", "Main screen"),label("后台副屏", "Background screen")),if(VirtualDisplays.preference(this)=="virtual") 1 else 0) { d,n ->
                    act { VirtualDisplays.choose(this,if(n==1) "virtual" else "main"); d.dismiss(); render() }
                }.setNegativeButton(android.R.string.cancel,null).show()
        }
        button(label("检查设备兼容性（可选，不创建副屏）", "Check device APIs (optional, no display creation)")) {
            check(AgentBus.activeTaskId.isNullOrBlank()) { "PHONE_DISPLAY_CHANGE_REQUIRES_IDLE_TASK" }
            check(inspection==null) { "VIRTUAL_DISPLAY_API_CHECK_RUNNING" }
            val token=CancellationToken(); inspection=token; inspectionMessage=label("正在检测……", "Checking…"); updateStatus()
            Thread({
                val result=runCatching { VirtualDisplays.inspectPlatform(applicationContext,token) }
                runOnUiThread {
                    if(inspection===token) {
                        inspection=null
                        inspectionMessage=if(token.isCancelled) label("检测已取消", "Check cancelled") else result.fold(
                            { label("基础接口检测完成；无需校准。", "API check complete; no calibration required.") },
                            { (it.message ?: "VIRTUAL_DISPLAY_API_CHECK_FAILED")+"\n"+VirtualDisplayReadiness.guidance(it.message.orEmpty()) })
                        if(!isFinishing && !isDestroyed) updateStatus()
                    }
                }
            },"virtual-display-api-check").start()
        }
        button(label("3. 选择允许应用（最多 16 个）", "3. Allowed apps (up to 16)")) { allowlist() }
        button(label("详细就绪检查与诊断", "Readiness and diagnostics")) {
            AlertDialog.Builder(this).setMessage(VirtualDisplays.status(this,AgentBus.activeTaskId.orEmpty()).toString(2)).setPositiveButton(android.R.string.ok,null).show()
        }
        button(label("只读预览（每秒最多一帧）", "Read-only preview (max 1 fps)")) {
            check(VirtualDisplays.preview(AgentBus.activeTaskId.orEmpty())!=null) { "VIRTUAL_DISPLAY_CREATE_REQUIRED" }
            startActivity(Intent(this,PhoneDisplayPreviewActivity::class.java))
        }
        button(label("立即关闭副屏", "Close display now")) { VirtualDisplays.stopAll(); render() }
        button(label("返回设置", "Back")) { finish() }
        setContentView(ScrollView(this).apply { addView(box) }); updateStatus()
    }
    private fun updateStatus() {
        val state=VirtualDisplays.status(this,AgentBus.activeTaskId.orEmpty())
        val readiness=state.getJSONObject("readiness"); val blocks=readiness.getJSONArray("blockers")
        val adb=state.getJSONObject("adb"); val platform=state.optJSONObject("platform_apis")
        val report=StringBuilder(label("副屏功能版本：", "Display feature revision: ")).append(state.getInt("feature_revision"))
            .append(label("\n无需校准 · 允许共享焦点\n会话：", "\nNo calibration · shared focus allowed\nSession: ")).append(state.getString("status"))
            .append("\nADB: ").append(adb.optString("connection_state")).append(" / ").append(adb.optString("setup_stage"))
            .append(label("\n允许应用：", "\nAllowed apps: ")).append(state.getJSONArray("allowed_packages").length())
            .append(label("\n基础接口：", "\nRequired APIs: ")).append(if(platform==null) "NOT_CHECKED" else if(platform.optBoolean("supported")) "API_READY" else "UNAVAILABLE")
        state.optJSONObject("display")?.let { report.append("\n").append(it.optString("focus_mode")) }
        platform?.optJSONObject("authority")?.let { authority ->
            report.append("\nBridge: ").append(authority.optString("package")).append(" / UID ").append(authority.optInt("uid",-1))
            authority.optJSONObject("permissions")?.let { permissions ->
                report.append("\nADD_TRUSTED_DISPLAY: ").append(permissions.optString("ADD_TRUSTED_DISPLAY"))
                    .append("\nINTERNAL_SYSTEM_WINDOW: ").append(permissions.optString("INTERNAL_SYSTEM_WINDOW"))
            }
        }
        state.optJSONObject("display")?.let { report.append("\nIME: ").append(it.optString("ime_policy")).append(" / ").append(it.optString("ime_configuration")) }
        state.optJSONObject("last_creation_failure")?.let { report.append("\n").append(it.optString("error")).append(" / ").append(it.optString("failure_stage")) }
        state.optJSONObject("bridge")?.optJSONObject("close_reason")?.let { report.append("\nBridge: ").append(it.optString("stage")).append(" / ").append(it.optString("code")) }
        if(inspectionMessage.isNotBlank()) report.append("\n").append(inspectionMessage)
        platform?.optJSONArray("missing_focus_apis")?.takeIf { it.length()>0 }?.let { report.append(label("\n独立焦点接口不完整，将使用共享焦点。", "\nFocus APIs incomplete; shared focus will be used.")) }
        for(i in 0 until blocks.length()) report.append("\n• ").append(blocks.getJSONObject(i).getString("user_action"))
        if(readiness.optBoolean("setup_ready")) report.append(label("\n配置已就绪；在聊天中先 phone_display create，再 phone_launch。", "\nSetup ready; in chat call phone_display create, then phone_launch."))
        statusText?.text=report.toString()
    }
    private fun allowlist() {
        check(AgentBus.activeTaskId.isNullOrBlank()) { "PHONE_DISPLAY_CHANGE_REQUIRES_IDLE_TASK" }
        val apps=packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .distinctBy { it.activityInfo.packageName }.filter { runCatching { VirtualDisplayScope.packageName(it.activityInfo.packageName) }.isSuccess }
            .sortedBy { it.activityInfo.packageName }
        val chosen=VirtualDisplays.allowed(this).toMutableSet()
        AlertDialog.Builder(this).setTitle(label("允许应用", "Allowed apps"))
            .setMultiChoiceItems(apps.map { "${it.loadLabel(packageManager)} (${it.activityInfo.packageName})" }.toTypedArray(),apps.map { it.activityInfo.packageName in chosen }.toBooleanArray()) { _,n,on ->
                if(on) chosen.add(apps[n].activityInfo.packageName) else chosen.remove(apps[n].activityInfo.packageName)
            }.setPositiveButton(android.R.string.ok) { _,_ -> act { VirtualDisplays.allow(this,chosen); render() } }
            .setNegativeButton(android.R.string.cancel,null).show()
    }
    override fun onResume() { super.onResume(); render(); ui.removeCallbacks(refresh); ui.post(refresh) }
    override fun onPause() { ui.removeCallbacks(refresh); inspection?.cancel(); super.onPause() }
}
