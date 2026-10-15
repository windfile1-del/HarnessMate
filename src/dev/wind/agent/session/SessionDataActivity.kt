package dev.wind.agent.session

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.widget.*
import dev.wind.agent.*

/** User-owned SAF data directory, recoverable archive, and background task controls. */
class SessionDataActivity : Activity() {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var directory: Button
    private var revision = 0
    private var syncing = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); Theme.init(this); Theme.applyFromPrefs(this, Prefs(this))
        setTheme(if (Theme.DARK) R.style.AppTheme else R.style.AppThemeLight)
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG) }; list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        scroll.addView(list); setContentView(scroll); refresh()
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }
    private fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(Theme.TEXT); textSize = 15f; setPadding(0,16,0,16) }
    private fun refresh() {
        val viewRevision = ++revision
        list.removeAllViews()
        list.addView(label("会话归档与长期数据"))
        list.addView(label("选择本机独立目录，数据保存在 HarnessMate 子目录。重装后授权原父目录或直接选择已有 HarnessMate 数据目录，即可自动恢复会话与用户 Skills。请保留目录中的文件。"))
        status = label((if (syncing) "正在同步与恢复，请稍候…" else SessionVaultSync.status(this)) + "\n" + (SessionVaultSync.uri(this) ?: "未选择目录")); list.addView(status)
        directory = button("选择 / 重新授权长期数据目录") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).putExtra(Intent.EXTRA_LOCAL_ONLY, true), 71)
        }; directory.isEnabled = !syncing; list.addView(directory)
        list.addView(button("立即同步并自动恢复") { sync() })
        val power = getSystemService(POWER_SERVICE) as? PowerManager
        val exempt = Build.VERSION.SDK_INT < 23 || power?.isIgnoringBatteryOptimizations(packageName) == true
        val notifications = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val enabled = Build.VERSION.SDK_INT < 24 || notifications.areNotificationsEnabled()
        list.addView(label("后台运行：${if (exempt) "已允许不受电池优化限制" else "请允许不受电池优化限制，以免锁屏暂停网络"}\n任务通知：${if (enabled) "已开启；只在后台或锁屏时提醒" else "未开启，请授权通知"}\n运行任务使用前台服务和屏幕关闭时的唤醒锁；系统或厂商强制终止后会保留检查点，继续前会重新检查授权。"))
        list.addView(button("后台电池设置") {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        list.addView(button("开启任务通知") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 73)
            else if (Build.VERSION.SDK_INT >= 26) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        list.addView(button("Skills 安装与管理") { startActivity(Intent(this, dev.wind.agent.skills.SkillsActivity::class.java)) })
        list.addView(label("已归档会话（点击恢复或查看）"))
        worker.execute {
            val entries = ChatStore(applicationContext).listSummaries(archived = true)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && revision == viewRevision) {
                    if (entries.isEmpty()) list.addView(label("暂无归档会话"))
                    entries.forEach { entry -> list.addView(button(entry.title.ifBlank { "未命名会话" }) {
                        AlertDialog.Builder(this).setTitle(entry.title.ifBlank { "归档会话" })
                            .setItems(arrayOf("恢复到会话列表", "打开归档会话")) { _, index ->
                                if (index == 0) worker.execute {
                                    val store = ChatStore(applicationContext)
                                    (AgentBus.liveChatFor(entry.id) ?: store.load(entry.id))?.let { it.archived = false; store.saveNow(it) }
                                    runOnUiThread { refresh() }
                                } else startActivity(Intent(this, MainActivity::class.java).putExtra("floatingChatId", entry.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            }.setNegativeButton("取消", null).show()
                    }) }
                }
            }
        }
    }
    private fun sync(tree: Uri? = null) {
        if (syncing) return
        syncing = true
        directory.isEnabled = false; status.text = "正在同步与恢复，请稍候…"
        worker.execute {
            runCatching { tree?.let { SessionVaultSync.configure(applicationContext, it) }; SessionVaultSync.restoreAndSync(applicationContext) }
                .onFailure { SessionVaultSync.setStatus(applicationContext, "目录同步失败：${it.message}") }
            runOnUiThread { syncing = false; if (!isFinishing && !isDestroyed) refresh() }
        }
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 71 && result == RESULT_OK) data?.data?.let { sync(it) }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); if (code == 73) refresh()
    }
    override fun onResume() { super.onResume(); if (::list.isInitialized) refresh() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
