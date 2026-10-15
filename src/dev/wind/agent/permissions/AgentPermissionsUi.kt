package dev.wind.agent.permissions

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import dev.wind.agent.AgentBus
import dev.wind.agent.Fa
import dev.wind.agent.Theme
import dev.wind.agent.project.ProjectStore

/** The same live editor is used from Settings, phone controls and the task viewer. */
object AgentPermissionsUi {
    private fun label(zh: String, en: String) = if (Fa.chinese) zh else en
    private fun title(cap: AgentCapability) = when(cap) {
        AgentCapability.WORKSPACE_READ -> label("工作区读取", "Workspace read")
        AgentCapability.WORKSPACE_WRITE -> label("工作区修改（含读取）", "Workspace write (includes read)")
        AgentCapability.ALL_READ -> label("所有可访问文件读取", "Read all accessible files")
        AgentCapability.ALL_WRITE -> label("所有可修改文件（含读取）", "Write all writable files")
        AgentCapability.PATH_READ -> label("额外目录读取", "Additional read directories")
        AgentCapability.PATH_WRITE -> label("额外目录修改（含读取）", "Additional writable directories")
        AgentCapability.SCREEN -> label("屏幕读取", "Screen access")
        AgentCapability.ACCESSIBILITY -> label("Accessibility 自动化", "Accessibility automation")
        AgentCapability.LOCAL_ADB -> "Local ADB"
        AgentCapability.VIRTUAL_DISPLAY -> label("后台虚拟屏幕", "Background virtual display")
        AgentCapability.RAW_ADB_SHELL -> label("Raw ADB Shell · 高级 / 高风险", "Raw ADB Shell · advanced / high risk")
        AgentCapability.ROOT -> "Root Access"
        AgentCapability.RAW_ROOT_SHELL -> label("Raw Root Shell · 高级 / 高风险", "Raw Root Shell · advanced / high risk")
    }
    fun open(context: Context) {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; val pad = Theme.dp(context, 16f); setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(context).apply { addView(body) }
        val dialog = AlertDialog.Builder(context).setTitle(label("Agent 权限", "Agent permissions")).setView(scroll).setPositiveButton(android.R.string.ok, null).create()
        fun text(value: String) { body.addView(TextView(context).apply { this.text = value; textSize = 14f; setTextColor(Theme.TEXT) }) }
        fun button(value: String, action: () -> Unit) { body.addView(Button(context).apply { this.text = value; setOnClickListener { action() } }) }
        val store = AgentPermissions.get(context)
        lateinit var render: () -> Unit
        fun scopes(action: (PermissionScope, String) -> Unit) {
            val task = AgentBus.activeTaskId
            val choices = if (task.isNullOrBlank()) arrayOf(label("全局", "Global")) else arrayOf(label("当前任务", "Current task"), label("全局", "Global"))
            AlertDialog.Builder(context).setTitle(label("生效范围", "Scope")).setItems(choices) { _, index ->
                val scope = if (!task.isNullOrBlank() && index == 0) PermissionScope.TASK else PermissionScope.GLOBAL
                if (scope == PermissionScope.TASK && AgentBus.activeTaskId != task) { Toast.makeText(context, label("任务已结束", "Task has ended"), Toast.LENGTH_SHORT).show(); return@setItems }
                action(scope, task.orEmpty()); render()
            }.setNegativeButton(android.R.string.cancel, null).show()
        }
        render = {
            body.removeAllViews()
            text(label("当前任务：", "Current task: ") + (AgentBus.activeTaskId ?: label("无运行任务；任务权限需在运行时授予", "No running task; task grants require an active task")))
            text(label("文件权限遵循 Android 实际授权，不能突破其他 App 私有目录、SELinux 或系统保护区域。写权限包含读权限。", "File access is bounded by Android permissions, app-private storage, SELinux and system protection. Write includes read."))
            button(label("本机无线 ADB 设置（配对 / 连接）", "Local wireless ADB setup (pair / connect)")) {
                dev.wind.agent.adb.AdbSettingsUi.open(context)
            }
            button(label("后台虚拟屏幕", "Background display")) {
                context.startActivity(android.content.Intent(context,dev.wind.agent.phone.PhoneDisplaySettingsActivity::class.java))
            }
            AgentCapability.values().filter { it !in setOf(AgentCapability.PATH_READ, AgentCapability.PATH_WRITE) }.forEach { cap ->
                val grants = store.snapshot(AgentBus.activeTaskId.orEmpty()).filter { it.capability == cap }
                text(title(cap) + ": " + if (grants.isEmpty()) label("关闭", "Off") else grants.joinToString { label(if(it.scope == PermissionScope.TASK) "当前任务" else "全局", it.scope.name) })
                if (AgentPermissions.isPreview(cap)) text(label("预览设置：尚未实现，开关只保存配置，不授予实际执行能力。", "Preview: implementation pending; switches save configuration only."))
                if (cap == AgentCapability.SCREEN) text(label("主屏共享需系统授权；后台副屏通过已授权的 Local ADB 读取。", "Main screen sharing requires system consent; virtual screens use authorized Local ADB."))
                if (cap == AgentCapability.ACCESSIBILITY) text(label("原有后端需系统无障碍服务；ADB 后端使用 shell UiAutomation。", "Legacy backend requires accessibility service; ADB uses shell UiAutomation."))
                if (cap == AgentCapability.VIRTUAL_DISPLAY) text(label("无需校准，还需连接 Local ADB 并选择允许应用。旧系统允许共享焦点，可能影响主屏焦点或键盘。", "No calibration; connect Local ADB and allow apps. Older systems may share focus and affect the main screen or keyboard."))
                button(label("设置 ", "Configure ") + title(cap)) { scopes { scope, task ->
                    val enabled = store.snapshot(task).any { it.capability == cap && it.scope == scope }
                    store.set(cap, scope, !enabled, taskId = task)
                } }
                grants.forEach { grant -> text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(grant.grantedAt))) }
            }
            for (cap in listOf(AgentCapability.PATH_READ, AgentCapability.PATH_WRITE)) {
                text(title(cap))
                store.snapshot(AgentBus.activeTaskId.orEmpty()).filter { it.capability == cap }.forEach { grant ->
                    text("${AgentPermissions.displayPath(context, grant.path)}\n${grant.scope} · " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(grant.grantedAt)))
                    button(label("撤销 / 修改此目录", "Revoke / edit this directory")) {
                        AlertDialog.Builder(context).setItems(arrayOf(label("撤销", "Revoke"), label("更改作用域", "Change scope"), label("切换读写", "Switch read / write"))) { _, index ->
                            if (index == 1) scopes { scope, task -> store.set(cap, grant.scope, false, grant.path, grant.taskId); store.set(cap, scope, true, grant.path, task) }
                            else { store.set(cap, grant.scope, false, grant.path, grant.taskId); if (index == 2) store.set(if (cap == AgentCapability.PATH_READ) AgentCapability.PATH_WRITE else AgentCapability.PATH_READ, grant.scope, true, grant.path, grant.taskId); render() }
                        }.show()
                    }
                }
                button(label("添加目录", "Add directory")) {
                    val edit = EditText(context).apply { hint = "/storage/emulated/0/Download/" }
                    val mounts = ProjectStore.get(context).getActiveProject()?.mounts.orEmpty().joinToString("\n") { "project://${it.alias}/ — ${it.displayName}" }
                    AlertDialog.Builder(context).setTitle(title(cap)).setMessage(label("输入绝对路径，或已挂载目录的 project:// 路径：\n", "Enter an absolute path or a mounted project:// path:\n") + mounts)
                        .setView(edit).setPositiveButton(android.R.string.ok) { _, _ ->
                            val raw = edit.text.toString().trim()
                            runCatching {
                                val need = AgentPermissionGate(context).directory(raw, cap == AgentCapability.PATH_WRITE)
                                scopes { scope, task -> store.set(cap, scope, true, need.path, task) }
                            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            button(label("修改策略：", "Write strategy: ") + store.strategy.name) {
                AlertDialog.Builder(context).setSingleChoiceItems(arrayOf(label("镜像 / 事务修改（Diff、提交、撤销）", "Mirror / transactional (diff, commit, undo)"), label("直接修改", "Direct")), store.strategy.ordinal) { d, i -> store.configure(strategy = WriteStrategy.values()[i]); d.dismiss(); render() }.show()
            }
            button("Action Policy: " + store.policy.name) {
                AlertDialog.Builder(context).setSingleChoiceItems(arrayOf(label("保守：每次改动确认", "Conservative: confirm each effect"), label("平衡：高风险与删除确认", "Balanced: confirm sensitive effects / deletion"), label("高度自主：授权内不重复确认", "Autonomous: no repeated effect approvals")), store.policy.ordinal) { d, i -> store.configure(policy = ActionPolicy.values()[i]); d.dismiss(); render() }.show()
            }
            button(label("查看权限变更记录", "View permission history")) {
                AlertDialog.Builder(context).setMessage(store.audit().joinToString("\n") { it.toString() }).setPositiveButton(android.R.string.ok, null).show()
            }
            button(label("一键撤销全部全局权限", "Revoke all global permissions")) { store.revokeAll(PermissionScope.GLOBAL); render() }
            button(label("恢复默认权限（仅工作区读取）", "Restore defaults (workspace read only)")) { store.reset(); render() }
        }
        render(); dialog.show()
    }
}
