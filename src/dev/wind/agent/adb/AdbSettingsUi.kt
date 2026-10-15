package dev.wind.agent.adb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Toast
import dev.wind.agent.AgentBus
import dev.wind.agent.Fa
import dev.wind.agent.Theme
import dev.wind.agent.Ui
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** User-operated setup; connection never changes Agent grants. TCP restart requires confirmation. */
object AdbSettingsUi {
    internal fun label(zh: String, en: String) = if (Fa.chinese) zh else en

    fun open(context: Context) {
        var owner: Context = context
        while (owner is ContextWrapper && owner !is Activity) {
            val base = owner.baseContext
            if (base === owner) break
            owner = base
        }
        val activity = owner as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        Panel(activity).show()
    }

    private class Panel(private val activity: Activity) : Application.ActivityLifecycleCallbacks {
        private val handler = Handler(Looper.getMainLooper())
        private val discovery = AdbDiscovery(activity)
        private val work = AdbSetupOperation(activity)
        private val actions = ArrayList<View>()
        private var visible = true
        private var closed = false
        private var busy = false
        private val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Theme.dp(activity, 16f)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Theme.BG)
        }
        private val state = text("")
        private val progress = text("")
        private val serviceState = text("")
        private lateinit var pairingPort: EditText
        private lateinit var code: EditText
        private lateinit var connectPort: EditText
        private lateinit var dialog: AlertDialog
        private var mode = AdbConnectionMode.AUTO
        private lateinit var modes: RadioGroup
        private var tlsModeId = 0
        private val tick = object : Runnable {
            override fun run() {
                if (closed || !visible) return
                serviceState.text = AdbPairingService.lastMessage
                handler.postDelayed(this, 1_000)
            }
        }

        private fun text(value: String) = TextView(activity).apply {
            text = value; textSize = 14f; setTextColor(Theme.TEXT); typeface = Theme.ui()
            body.addView(this)
        }
        private fun field(zh: String, en: String, length: Int): EditText {
            text(label(zh, en))
            return EditText(activity).apply {
                hint = label(zh, en)
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(length))
                setSingleLine(true); setTextColor(Theme.TEXT); setHintTextColor(Theme.TEXT_MUTED)
                typeface = Theme.ui(); body.addView(this)
            }
        }
        private fun button(zh: String, en: String, tracked: Boolean = true, action: () -> Unit) {
            val view = Ui.pillButton(activity, label(zh, en), "settings", Ui.SECONDARY) {
                if (!closed && visible && (!tracked || !busy)) action()
            }
            body.addView(view)
            if (tracked) actions.add(view)
        }
        private fun setBusy(value: Boolean) {
            busy = value
            actions.forEach { it.isEnabled = !value }
        }
        private fun port(field: EditText): Int? {
            val port = field.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }
            if (port == null) progress.text = label("端口必须为 1–65535。", "Port must be 1–65535.")
            return port
        }

        fun show() {
            text(label("支持六位码 TLS 配对和传统 TCP ADB。电脑执行 adb tcpip 5555 后，直接填写连接端口 5555，无需配对端口和六位码；首次连接请在系统弹窗允许本 App 的调试密钥。电脑的授权不会自动授权本 App。连接不授予 Agent 权限，也不等于 root。", "Supports code-paired TLS and classic TCP ADB. After adb tcpip 5555, enter connection port 5555 without a pairing code. Allow this App's own debugging key in the system dialog; computer authorization does not authorize this App. Connecting grants neither Agent permission nor root."))
            button("打开开发者选项", "Open Developer options", false) {
                runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { Toast.makeText(activity, label("请手动打开系统开发者选项。", "Open Developer options manually."), Toast.LENGTH_LONG).show() }
            }
            button("进入系统设置，查找无线调试", "Find Wireless debugging in system settings", false) {
                // There is no public Android action for a standalone wireless page.
                runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { progress.text = label("厂商未提供此入口，请手动打开开发者选项。", "No supported settings entry; open Developer options manually.") }
            }
            button("无线调试未开放：查看激活指引", "Activation help for hidden wireless debugging", false) {
                val instruction = label("先打开开发者选项和 USB 调试。有无线调试开关时，由用户打开并配对即可。厂商隐藏该功能且没有现存 ADB 连接时，普通 App 无法自行启动 adbd：请先通过已授权的电脑执行 adb tcpip 5555，再回本 App 连接 5555。首次允许本 App 的系统调试弹窗后，后续通常可直接重连；重启或厂商关闭端口后可能需要再次激活。", "Enable Developer options and USB debugging. If Wireless debugging is available, enable it and pair. If the OEM hides it and no ADB connection exists, an ordinary App cannot start adbd: run adb tcpip 5555 from an authorized computer, then connect to 5555 here and allow this App's debugging key. Reconnection usually needs no computer while the port remains open; reboot or OEM shutdown may require activation again.")
                AlertDialog.Builder(activity).setMessage(instruction).setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(label("复制电脑命令", "Copy computer command")) { _, _ ->
                        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("ADB", "adb tcpip 5555"))
                    }.show()
            }
            if (Build.VERSION.SDK_INT >= 30) {
                text(label("配对弹窗中的端口和无线调试主页的连接端口不同。可以分屏查看系统设置并手动输入；配对码仅在本次操作使用。", "The pairing dialog port differs from the connection port on the Wireless debugging page. Use split screen to enter them manually. The code is used only for this operation."))
                pairingPort = field("配对端口", "Pairing port", 5)
                code = field("六位配对码", "Six-digit pairing code", 6).apply {
                    transformationMethod = PasswordTransformationMethod.getInstance()
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    setSaveEnabled(false)
                }
                button("发现本机配对端口", "Discover local pairing port") { discover(AdbDiscovery.Kind.PAIRING, pairingPort) }
                button("手动配对", "Pair manually") {
                    val port = port(pairingPort) ?: return@button
                    val value = code.text.toString().trim()
                    if (!value.matches(Regex("[0-9]{6}"))) {
                        progress.text = label("请输入六位数字配对码。", "Enter a six-digit pairing code.")
                    } else {
                        code.text.clear()
                        run(AdbSetupOperation.PAIR_TIMEOUT_MS) {
                            AdbManager.get(activity.applicationContext).pair(port, value)
                            label("配对成功。请单独发现或输入连接端口后连接。", "Paired. Discover or enter the connection port separately, then connect.")
                        }
                    }
                }
                button("开始通知配对（150 秒）", "Start notification pairing (150 seconds)") {
                    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0xADB)
                        progress.text = label("允许通知后再次点击；也可使用上方分屏手动配对。", "After allowing notifications, tap again; split-screen manual pairing is also available.")
                    } else {
                        discovery.stop()
                        val value = pairingPort.text.toString().trim()
                        val port = if (value.isEmpty()) 0 else port(pairingPort) ?: return@button
                        progress.text = AdbPairingService.startFromUi(activity, port)
                    }
                }
                button("停止通知配对", "Stop notification pairing", false) { activity.stopService(Intent(activity, AdbPairingService::class.java)) }
            } else text(label("六位码 TLS 配对需要 Android 11；传统 TCP 模式仍可用于已激活的本机端口。", "Code-paired TLS needs Android 11; classic TCP can connect to an already enabled local port."))
            modes = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL; body.addView(this) }
            for (selection in AdbConnectionMode.values()) {
                val option = RadioButton(activity).apply {
                    id = View.generateViewId(); setTextColor(Theme.TEXT)
                    text = when (selection) {
                        AdbConnectionMode.AUTO -> label("自动：5555 使用传统 TCP，其他端口使用 TLS", "Auto: TCP for 5555, TLS for other ports")
                        AdbConnectionMode.TLS -> label("TLS 配对连接（六位码）", "TLS paired connection")
                        AdbConnectionMode.TCP -> label("传统 TCP（无六位码，系统弹窗授权）", "Classic TCP (system key authorization)")
                    }
                }
                modes.addView(option); actions.add(option)
                if (selection == AdbConnectionMode.TLS) tlsModeId = option.id
                option.setOnCheckedChangeListener { _, checked -> if (checked) mode = selection }
                if (selection == AdbConnectionMode.AUTO) modes.check(option.id)
            }
            text(label("传统 TCP 不加密。仅连接本机回环地址；adbd 自身可能同时对局域网监听，请在可信网络使用。", "Classic TCP is unencrypted. This App connects only to loopback; adbd may also listen on your LAN, so use a trusted network."))
            text(label("固定连接本机 127.0.0.1，不使用系统显示的 172/192 开头地址。检测到 VPN 时，先尝试默认本机连接；仅在 Socket 未建立时尝试非 VPN 网络。若端口发现受 VPN 影响，请手动填写系统显示的端口；TLS 需先六位码配对，再填写连接端口，不会弹出传统 TCP 的密钥授权窗口。", "Connects to 127.0.0.1, not a displayed 172/192 address. With VPN, tries default loopback first, then a non-VPN network only before a socket is established. If discovery is affected, enter ports manually. TLS requires code pairing followed by the connection port; it does not show a classic TCP key dialog."))
            connectPort = field("连接端口（电脑激活可填 5555）", "Connection port (5555 after computer activation)", 5)
            if (Build.VERSION.SDK_INT >= 30) button("发现 TLS 连接端口", "Discover TLS connection port") { modes.check(tlsModeId); discover(AdbDiscovery.Kind.CONNECT, connectPort) }
            button("连接", "Connect") {
                val port = port(connectPort) ?: return@button
                val selected = mode
                run(AdbSetupOperation.CONNECT_TIMEOUT_MS) {
                    AdbManager.get(activity.applicationContext).connect(port, selected) { phase ->
                        handler.post { if (!closed && busy) progress.text = when (phase) {
                            "WAITING_DEVICE_AUTHORIZATION" -> label("等待系统授权：请允许本 App 的调试密钥（最长 60 秒）。", "Waiting for system authorization: allow this App's debugging key (up to 60 seconds).")
                            "WAITING_ADB_HANDSHAKE" -> label("端口已连通，等待 adbd 握手…", "Socket connected; waiting for adbd handshake…")
                            "RSA_AUTHENTICATING" -> label("正在验证本 App 的调试密钥…", "Authenticating this App's debugging key…")
                            "TLS_HANDSHAKE", "WAITING_ADB_CONNECT" -> label("正在建立 TLS 配对连接…", "Establishing the paired TLS connection…")
                            "SHELL_IDENTITY_PROBE" -> label("正在验证 shell 身份…", "Verifying shell identity…")
                            else -> label("正在连接本机端口 $port…", "Connecting to local port $port…")
                        } }
                    }
                    displayStatus(AdbManager.get(activity.applicationContext).status(AgentBus.activeTaskId.orEmpty()))
                }
            }
            // Keep progress beside the connection button; a top-of-page message is
            // otherwise off screen when the long setup form is scrolled down.
            body.removeView(progress); body.addView(progress)
            button("已有连接：切换 / 激活 TCP 5555", "Existing connection: activate TCP 5555") {
                AlertDialog.Builder(activity).setMessage(label("需要已经连接 ADB。将请求 adbd 切换到未加密的 TCP 5555，并断开当前连接；监听可能覆盖局域网。确认后请自行连接 5555 检查结果。没有现存连接时无法首次激活。", "Requires an existing ADB connection. Requests unencrypted TCP 5555 and disconnects this connection; adbd may listen on the LAN. Afterwards connect to 5555 to verify. This cannot bootstrap a closed daemon."))
                    .setPositiveButton(android.R.string.ok) { _, _ -> if (!closed && visible && !busy) run(10_000) {
                        val result = AdbManager.get(activity.applicationContext).restartTcpip()
                        if (result.status == "RESTART_REQUEST_ACKNOWLEDGED") label("系统已确认切换请求；请填写 5555 并连接验证。", "Daemon acknowledged the switch request; connect to 5555 to verify.")
                        else label("请求完成状态未知；请连接 5555 检查，勿自动重试。", "Request completion is unknown; check by connecting to 5555, without automatic retry.")
                    } }.setNegativeButton(android.R.string.cancel, null).show()
            }
            button("断开连接", "Disconnect") { run(8_000) { AdbManager.get(activity.applicationContext).disconnect(); label("已断开。", "Disconnected.") } }
            button("忘记本地调试身份", "Forget local debugging identity") {
                AlertDialog.Builder(activity).setMessage(label("删除本 App 的调试身份并断开连接？下次传统 TCP 需要再次系统授权，TLS 需要重新配对。系统中的已配对记录需自行移除。", "Delete this App's identity and disconnect? TCP will require system authorization again; TLS will require pairing. Remove Android's saved record yourself."))
                    .setPositiveButton(android.R.string.ok) { _, _ -> if (!closed && visible && !busy) run(8_000) { AdbManager.get(activity.applicationContext).forget(); label("已忘记本地身份。", "Local identity forgotten.") } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            button("刷新状态", "Refresh status") { refresh() }
            button("取消当前操作 / 停止发现", "Cancel operation / stop discovery", false) {
                discovery.stop(); work.cancel(); setBusy(false)
                progress.text = label("已取消；正在清理未完成连接。", "Cancelled; cleaning up any unfinished connection.")
            }
            dialog = AlertDialog.Builder(activity).setTitle(label("本机无线 ADB 设置", "Local wireless ADB setup"))
                .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton(android.R.string.cancel, null).create()
            dialog.setOnDismissListener { close() }
            activity.application.registerActivityLifecycleCallbacks(this)
            dialog.show()
            handler.post(tick)
            refresh()
        }

        private fun discover(kind: AdbDiscovery.Kind, target: EditText) {
            setBusy(true)
            progress.text = label("正在发现本机端口（最多 45 秒）…", "Discovering a local port (up to 45 seconds)…")
            discovery.start(kind, { port ->
                if (!closed && visible) { target.setText(port.toString()); setBusy(false); progress.text = label("已发现本机端口，请确认后操作。", "Local port found; confirm before proceeding.") }
            }, {
                if (!closed && visible) { setBusy(false); progress.text = label("发现结束，未获得本机端口。请手动输入。", "Discovery ended without a local port. Enter it manually.") }
            })
        }
        private fun run(timeout: Long, abortOnCancel: Boolean = true, status: Boolean = false, operation: () -> String) {
            if (closed || !visible) return
            discovery.stop(); setBusy(true)
            progress.text = label("正在处理…", "Working…")
            if (!work.start(timeout, abortOnCancel, operation = operation) { result ->
                if (!closed) {
                    setBusy(false)
                    val message = result.getOrElse { setupError(it.message.orEmpty()) }
                    if (status && result.isSuccess) { state.text = message; progress.text = "" } else progress.text = message
                }
            }) { setBusy(false); progress.text = label("其他 ADB 设置操作仍在执行或清理，请稍后重试。", "Another ADB setup operation is running or cleaning up; retry later.") }
        }
        private fun refresh() {
            val taskId = AgentBus.activeTaskId.orEmpty()
            run(8_000, abortOnCancel = false, status = true) {
                displayStatus(AdbManager.get(activity.applicationContext).status(taskId))
            }
        }
        private fun displayStatus(value: org.json.JSONObject): String {
            val phase = value.optString("connection_state")
            val stateLabel = when (phase) {
                "READY" -> label("已连接", "Connected")
                "CONNECTING" -> label("连接中", "Connecting")
                "WAITING_ADB_HANDSHAKE", "RSA_AUTHENTICATING", "TLS_HANDSHAKE", "WAITING_ADB_CONNECT" -> label("连接握手中", "Connection handshake in progress")
                "WAITING_DEVICE_AUTHORIZATION" -> label("等待系统允许调试", "Waiting for system debugging authorization")
                "PAIRED_DISCONNECTED" -> label("TLS 已配对，尚未连接", "TLS paired, disconnected")
                "PAIRING" -> label("TLS 配对中", "TLS pairing")
                else -> label("未连接", "Disconnected")
            }
            val modeLabel = when (value.optString("connection_mode")) {
                "TCP" -> label("传统 TCP（未加密）", "Classic TCP (unencrypted)")
                "TLS" -> label("TLS（加密）", "TLS (encrypted)")
                else -> label("尚未选择", "Not selected")
            }
            val error = value.optString("last_error")
            return label("状态：$stateLabel\n模式：$modeLabel\n本机端口：${value.optInt("connect_port")}\nAgent ADB 授权：${value.optBoolean("local_adb_authorized")}",
                "Status: $stateLabel\nMode: $modeLabel\nLocal port: ${value.optInt("connect_port")}\nAgent ADB authorized: ${value.optBoolean("local_adb_authorized")}") +
                "\n" + label("本机路由：${value.optString("local_route")}；VPN：${value.optBoolean("vpn_detected")}",
                    "Local route: ${value.optString("local_route")}; VPN: ${value.optBoolean("vpn_detected")}") +
                if (error.isNotBlank()) "\n" + setupError(error) else ""
        }
        private fun setupError(error: String): String = when {
            error.contains("ADB_VPN_BYPASS_BLOCKED") || error.contains("ADB_LOCAL_NETWORK_BIND_FAILED") -> label("系统或 VPN 不允许 ADB 使用非 VPN 网络，或底层网络已断开。请在 VPN 中允许本 App 绕过/直连，检查“阻止未使用 VPN 的连接”，然后重连。详情：$error", "System/VPN blocked the selected non-VPN route, or that network disappeared. Allow this App direct access in VPN settings and check block-connections-without-VPN, then reconnect. Details: $error")
            error.contains("ADB_STREAM_ID_INVALID") -> label("ADB 流会话不匹配，连接已关闭。请保留以下帧 ID 诊断用于排查：$error", "ADB stream mismatch closed the connection. Keep these frame IDs for diagnosis: $error")
            error.contains("ADB_TIMEOUT") -> label("ADB 连接超时。以下阶段可区分端口不可达、握手或系统授权未完成；VPN 严格限制也可能阻止本机连接。详情：$error", "ADB timed out. The stage below identifies socket access, handshake or authorization; strict VPN policy can also block local access. Details: $error")
            error.contains("ADB_PAIRING_REQUIRED") -> label("TLS 需要先六位码配对。电脑 adb tcpip 5555 激活的端口请选择传统 TCP。", "TLS requires pairing. For a port activated by adb tcpip, select classic TCP.")
            error.contains("ADB_CONNECT_FAILED") -> label("本机端口未连接：确认电脑 adb tcpip 5555 执行成功且端口未被重启/厂商关闭。部分系统会阻止 App 访问本机 adbd。", "Cannot connect locally: verify adb tcpip 5555 succeeded and reboot/OEM has not closed the port. Some systems block App access to local adbd.")
            error.contains("ADB_DEVICE_AUTHORIZATION") -> label("系统未允许本 App 的调试密钥或授权等待超时；解锁手机并检查允许调试弹窗，然后重试连接。", "This App's key was denied or authorization timed out. Unlock the phone and check the system debugging prompt, then reconnect.")
            error.contains("ADB_ENDPOINT_REQUIRES_TLS_MODE") -> label("该端口要求 TLS，请选择 TLS 模式并先完成配对。", "This port requires TLS; select TLS mode and pair first.")
            error.contains("ADB_ACTIVE_CONNECTION_REQUIRED") -> label("尚无 ADB 连接，无法在 App 内首次激活端口；请按激活指引完成一次外部激活。", "No existing ADB connection; follow activation help for the initial external activation.")
            else -> label("ADB 操作失败：$error", "ADB operation failed: $error")
        }
        private fun close() {
            if (closed) return
            closed = true; visible = false
            discovery.stop(); work.cancel(); handler.removeCallbacksAndMessages(null)
            if (::code.isInitialized) code.text.clear()
            activity.application.unregisterActivityLifecycleCallbacks(this)
        }
        override fun onActivityStopped(owner: Activity) {
            if (owner === activity) {
                // System RSA authorization can cover/stop this Activity. Keep the bounded
                // user-started connection alive until dismiss/destroy/cancel or its deadline.
                visible = false; discovery.stop(); if (!work.running) setBusy(false); handler.removeCallbacks(tick)
                if (::code.isInitialized) code.text.clear()
            }
        }
        override fun onActivityResumed(owner: Activity) {
            if (owner === activity && !closed) { visible = true; handler.removeCallbacks(tick); handler.post(tick) }
        }
        override fun onActivityDestroyed(owner: Activity) { if (owner === activity) { close(); if (::dialog.isInitialized) dialog.dismiss() } }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityPaused(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }
}

/** Shared operation lease prevents UI/service mutations from racing. The manager must bound
 * its socket/native calls and allow disconnect() to abort an in-flight pair/connect.
 * A timed-out operation holds the lease until BOTH its worker and cleanup have exited.
 */
internal class AdbSetupOperation(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var current: Job? = null
    val running: Boolean get() = current != null
    private class Job(val abortOnCancel: Boolean) {
        val cancelled = AtomicBoolean(false)
        val workerDone = AtomicBoolean(false)
        val cleanupDone = AtomicBoolean(false)
        lateinit var thread: Thread
        lateinit var timeout: Runnable
    }

    fun start(timeout: Long, abortOnCancel: Boolean = true, operation: () -> String, done: (Result<String>) -> Unit): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (current != null) return false
        val job = Job(abortOnCancel)
        if (!lease.compareAndSet(null, job)) return false
        current = job
        job.timeout = Runnable {
            if (current === job) { cancel(); done(Result.failure(java.util.concurrent.TimeoutException())) }
        }
        job.thread = Thread({
            val result = runCatching {
                check(!job.cancelled.get()) { "ADB_SETUP_CANCELLED" }
                operation()
            }
            // Also close after the worker returns: a native connect may have completed
            // after the initial cancellation disconnect, before observing interruption.
            if (job.cancelled.get() && job.abortOnCancel) runCatching { AdbManager.get(app).disconnect() }
            job.workerDone.set(true)
            if (job.cancelled.get()) release(job)
            handler.post {
                if (current === job && !job.cancelled.get()) {
                    current = null; handler.removeCallbacks(job.timeout); release(job); done(result)
                }
            }
        }, "vega-adb-setup").apply { isDaemon = true }
        handler.postDelayed(job.timeout, timeout)
        job.thread.start()
        return true
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val job = current ?: return
        current = null
        job.cancelled.set(true)
        handler.removeCallbacks(job.timeout)
        job.thread.interrupt()
        if (!job.abortOnCancel) { job.cleanupDone.set(true); release(job); return }
        Thread({
            try { AdbManager.get(app).disconnect() } catch (_: Exception) { /* No secrets in logs. */ }
            finally { job.cleanupDone.set(true); release(job) }
        }, "vega-adb-cleanup").apply { isDaemon = true }.start()
    }

    private fun release(job: Job) {
        if (job.workerDone.get() && (!job.cancelled.get() || job.cleanupDone.get())) lease.compareAndSet(job, null)
    }
    companion object {
        const val PAIR_TIMEOUT_MS = 35_000L
        const val CONNECT_TIMEOUT_MS = 80_000L
        private val lease = AtomicReference<Job?>(null)
    }
}
