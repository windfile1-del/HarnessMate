package dev.wind.agent.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import dev.wind.agent.Fa
import dev.wind.agent.screen.ScreenShareSession

/** User initiated settings only; agents cannot grant themselves accessibility, IME, SMS or call permission. */
class PhoneControlsActivity : Activity() {
    private var dialog: AlertDialog? = null
    override fun onCreate(state: Bundle?) { super.onCreate(state); Fa.apply(this); showControls() }
    private fun label(zh: String, en: String) = if (Fa.chinese) zh else en
    private fun showControls() {
        val modes = arrayOf(label("关闭", "Off"), label("纯视觉", "Visual"), label("UI 抓取", "UI tree"), label("视觉 + UI", "Visual + UI"))
        val actions = arrayOf(
            label("操作方案：", "Mode: ") + modes[PhoneAutomation.mode(this).ordinal],
            label("启用手机自动化无障碍服务", "Enable phone automation accessibility"),
            label("视觉文字输入：启用 Vega AI 输入法", "Enable Vega AI input for visual typing"),
            label("选择输入法", "Choose keyboard"),
            label("授予直接拨号权限", "Grant direct call permission"),
            label("授予直接短信权限", "Grant direct SMS permission"),
            label("授予亮度设置权限", "Grant brightness settings permission"),
            label("Agent 权限：逐项设置", "Agent permissions: configure individually"),
            label("停止托管：关闭自动化权限", "Stop managed automation: revoke automation"),
            label("新鲜度：", "Freshness: ") + (if (PhoneAutomation.strictFreshness(this)) label("严格", "Strict") else label("容忍内容刷新", "Tolerant")),
            label("托管弹窗恢复：", "Managed popup recovery: ") + (if (PhoneAutomation.recoveryEnabled(this)) label("开启", "On") else label("关闭", "Off")),
            label("控制后端：", "Control backend: ") + PhoneBackends.preference(this),
            label("后台虚拟屏幕：", "Background display: ") + VirtualDisplays.preference(this))
        dialog = AlertDialog.Builder(this).setTitle(label("手机自动化", "Phone automation"))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> dev.wind.agent.permissions.AgentPermissionsUi.open(this)
                    1 -> openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    2 -> openSystem(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    3 -> { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); showControls() }
                    4 -> requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 201)
                    5 -> requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 202)
                    6 -> openSystem(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                    7 -> dev.wind.agent.permissions.AgentPermissionsUi.open(this)
                    8 -> {
                        val permissions = dev.wind.agent.permissions.AgentPermissions.get(this)
                        for (cap in listOf(dev.wind.agent.permissions.AgentCapability.ACCESSIBILITY, dev.wind.agent.permissions.AgentCapability.SCREEN, dev.wind.agent.permissions.AgentCapability.VIRTUAL_DISPLAY)) {
                            permissions.set(cap, dev.wind.agent.permissions.PermissionScope.GLOBAL, false)
                            dev.wind.agent.AgentBus.activeTaskId?.let { permissions.set(cap, dev.wind.agent.permissions.PermissionScope.TASK, false, taskId = it) }
                        }
                        VirtualDisplays.stopAll(); PhoneManagedRecovery.stopAll(); PhoneApprovalPolicy.clear(); showControls()
                    }
                    9 -> { PhoneAutomation.chooseFreshness(this, !PhoneAutomation.strictFreshness(this)); showControls() }
                    10 -> { PhoneAutomation.chooseRecovery(this, !PhoneAutomation.recoveryEnabled(this)); showControls() }
                    11 -> AlertDialog.Builder(this).setTitle(label("控制后端", "Control backend"))
                        .setSingleChoiceItems(arrayOf(label("自动（优先 ADB）", "Auto (prefer ADB)"), "ADB", label("原有无障碍方案", "Legacy accessibility")),
                            listOf("auto", "adb", "legacy").indexOf(PhoneBackends.preference(this))) { d, n ->
                            PhoneBackends.choose(this, listOf("auto", "adb", "legacy")[n]); d.dismiss(); showControls()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                    12 -> startActivity(Intent(this,PhoneDisplaySettingsActivity::class.java))
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }.setOnCancelListener { finish() }.create()
        dialog?.show()
    }
    private fun openSystem(intent: Intent) {
        try { startActivity(intent) } catch (error: Exception) { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
        finish()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); showControls()
    }
    override fun onStart() { super.onStart(); ScreenShareSession.controlsVisible = true; ScreenShareSession.service?.setMainVisible(ScreenShareSession.mainVisible) }
    override fun onDestroy() { dialog?.dismiss(); ScreenShareSession.controlsVisible = false; ScreenShareSession.service?.setMainVisible(ScreenShareSession.mainVisible); super.onDestroy() }
    override fun finish() { if (intent.getBooleanExtra("returnToOverlay", false)) moveTaskToBack(true); super.finish() }
    companion object {
        internal fun open(context: Context, returnToOverlay: Boolean = context !is Activity) {
            val intent = Intent(context, PhoneControlsActivity::class.java).putExtra("returnToOverlay", returnToOverlay)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
