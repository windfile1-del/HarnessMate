package dev.wind.agent.skills

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import dev.wind.agent.*
import dev.wind.agent.session.SessionVaultSync

class SkillsActivity : Activity() {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private lateinit var box: LinearLayout
    private var revision = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); Theme.init(this); Theme.applyFromPrefs(this, Prefs(this))
        setTheme(if (Theme.DARK) R.style.AppTheme else R.style.AppThemeLight)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(box) }); refresh()
    }
    private fun text(value: String) = TextView(this).apply { text=value; setTextColor(Theme.TEXT); textSize=15f; setPadding(0,16,0,16) }
    private fun refresh() {
        val viewRevision = ++revision
        box.removeAllViews(); box.addView(text("Skills 安装与管理"))
        box.addView(text("选择带 name / description 元数据的 SKILL.md，或包含唯一 SKILL.md 的 ZIP。普通 Markdown 不会作为 Skill 安装。模型只读取目录元数据，按需调用 list_skills / read_skill。"))
        box.addView(Button(this).apply { text="安装 Skill"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 81)
        } })
        box.addView(text("专用目录：files/skills/builtin 与 files/skills/installed\n选择长期数据目录后，用户 Skills 同步到 HarnessMate/skills/installed。"))
        worker.execute {
            val entries = SkillStore(applicationContext).list()
            runOnUiThread { if (!isFinishing && !isDestroyed && revision == viewRevision) entries.forEach { entry ->
                box.addView(Button(this).apply { text="${if (entry.builtin) "内置" else "用户安装"} · ${entry.name}\n${entry.description}"; setOnClickListener {
                    AlertDialog.Builder(this@SkillsActivity).setTitle(entry.name).setMessage(entry.description)
                        .setPositiveButton("查看入口") { _, _ -> worker.execute {
                            val preview = runCatching { SkillStore(applicationContext).read(entry.path, 1, 80) }.getOrElse { it.message.orEmpty() }
                            runOnUiThread { if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@SkillsActivity).setTitle(entry.name).setMessage(preview).setPositiveButton("关闭", null).show() }
                        } }.apply { if (!entry.builtin) setNeutralButton("卸载") { _, _ -> worker.execute {
                            runCatching { SkillStore(applicationContext).remove(entry.id); SessionVaultSync.syncSkills(applicationContext) }
                                .onFailure { SessionVaultSync.setStatus(applicationContext, "Skill 已在本地卸载；长期目录需重新同步：${it.message}") }
                            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
                        } } }.setNegativeButton("取消", null).show()
                } })
            } }
        }
    }
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != 81 || result != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            val outcome = runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: ""
                val entry = SkillStore(applicationContext).install(contentResolver.openInputStream(uri) ?: error("无法读取文件"), name)
                runCatching { SessionVaultSync.syncSkills(applicationContext) }.onFailure { SessionVaultSync.setStatus(applicationContext, "Skill 本地安装完成，长期目录需重新同步：${it.message}") }
                "已安装 ${entry.name}；可在聊天 Skills 按钮中选择，也可由模型按需读取"
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, outcome.getOrElse { "安装失败：${it.message}" }, Toast.LENGTH_LONG).show(); refresh()
            } }
        }
    }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
