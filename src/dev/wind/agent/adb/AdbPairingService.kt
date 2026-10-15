package dev.wind.agent.adb

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import dev.wind.agent.BaseAgentService
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import dev.wind.agent.Fa
import dev.wind.agent.SettingsActivity
import java.util.UUID

/** User-started, non-sticky, short foreground session; never enables debugging itself. */
class AdbPairingService : BaseAgentService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var discovery: AdbDiscovery
    private lateinit var work: AdbSetupOperation
    private var session: String? = null
    private var expiresAt = 0L
    private var pairingPort = 0
    private var connectPort = 0
    private var paired = false
    private var busy = false
    private var foreground = false
    private var ending = false
    private val pendingIntents = LinkedHashMap<String, PendingIntent>()
    private val deadline = Runnable { finish(label("通知配对已超时，请重新从设置页开始。", "Notification pairing expired. Start again from Settings.")) }

    override fun onCreate() {
        super.onCreate()
        Fa.apply(this)
        discovery = AdbDiscovery(this)
        work = AdbSetupOperation(this)
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == START) {
            // Repeated taps cannot extend the shortService budget or interrupt an active request.
            if (session != null) return START_NOT_STICKY
            if (Build.VERSION.SDK_INT < 30 || !notificationsAvailable(this)) { stopSelf(); return START_NOT_STICKY }
            session = UUID.randomUUID().toString()
            expiresAt = SystemClock.elapsedRealtime() + SESSION_TIMEOUT_MS
            pairingPort = intent.getIntExtra(PORT, 0).takeIf { it in 1..65535 } ?: 0
            update(label("请打开系统配对码弹窗，再在通知中输入配对码；未发现端口时输入“端口 六位码”。", "Open the system pairing-code dialog, then reply here with the code, or 'port six-digit-code' if no port was found."))
            if (ending) return START_NOT_STICKY
            handler.postDelayed(deadline, SESSION_TIMEOUT_MS)
            discovery.start(AdbDiscovery.Kind.PAIRING, { port ->
                if (!ending && !busy && !paired) {
                    pairingPort = port
                    update(label("本机配对端口：$port。请输入六位配对码。", "Local pairing port: $port. Reply with the six-digit code."))
                }
            }, { if (!ending && !busy && !paired) update(label("可手动输入“配对端口 六位配对码”。", "You can enter 'pairing-port six-digit-code' manually.")) })
            return START_NOT_STICKY
        }
        // Stale RemoteInput intents cannot revive a session after timeout/process death.
        val token = session
        if (ending || token == null || intent == null || intent.getStringExtra(SESSION) != token ||
            SystemClock.elapsedRealtime() >= expiresAt) {
            if (token == null) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            STOP -> finish(label("通知配对已取消。", "Notification pairing cancelled."))
            PAIR -> if (!busy && !paired) {
                val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT)?.toString()?.trim().orEmpty()
                val parts = reply.split(Regex("\\s+"))
                val port = if (parts.size == 2) parts[0].toIntOrNull() ?: 0 else pairingPort
                val code = parts.lastOrNull().orEmpty()
                if (parts.size !in 1..2 || port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
                    update(label("需要有效配对端口和六位码：输入“端口 六位码”。", "A valid pairing port and six-digit code are required: enter 'port code'."))
                } else pair(port, code)
            }
            CONNECT -> if (!busy && paired) {
                val port = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(INPUT)?.toString()?.trim()?.toIntOrNull()
                if (port == null || port !in 1..65535) update(label("请输入无线调试主页的连接端口（1–65535）。", "Enter the connection port from the Wireless debugging page (1–65535)."))
                else connect(port)
            }
            CONNECT_FOUND -> if (!busy && paired && connectPort in 1..65535) connect(connectPort)
        }
        return START_NOT_STICKY
    }

    private fun pair(port: Int, code: String) {
        discovery.stop()
        busy = true
        update(label("正在配对…", "Pairing…"))
        if (!work.start(AdbSetupOperation.PAIR_TIMEOUT_MS, operation = {
            AdbManager.get(applicationContext).pair(port, code)
        }) { result ->
            if (!ending) {
                busy = false
                if (result.isSuccess) {
                    paired = true; pairingPort = 0; connectPort = 0
                    update(label("配对成功。正在单独发现连接端口，也可手动输入。", "Paired. Discovering the connection port separately; manual input is available."))
                    discovery.start(AdbDiscovery.Kind.CONNECT, { found ->
                        if (!ending && !busy) { connectPort = found; update(label("连接端口：$found。点击连接或手动输入端口。", "Connection port: $found. Tap Connect or enter a port manually.")) }
                    }, { if (!ending && !busy) update(label("配对成功；请手动输入无线调试主页的连接端口。", "Paired; enter the connection port from the Wireless debugging page.")) })
                } else finish(label("配对失败或超时，请从设置页重新开始。", "Pairing failed or timed out. Start again from Settings."))
            }
        }) { busy = false; update(label("其他 ADB 操作正在执行或清理，请稍后重试。", "Another ADB operation is running or cleaning up; retry later.")) }
    }

    private fun connect(port: Int) {
        discovery.stop(); busy = true
        update(label("正在连接…", "Connecting…"))
        if (!work.start(AdbSetupOperation.CONNECT_TIMEOUT_MS, operation = {
            AdbManager.get(applicationContext).connect(port, AdbConnectionMode.TLS)
            "CONNECTED"
        }) { result ->
            if (!ending) {
                busy = false
                finish(if (result.isSuccess) label("ADB 已连接（shell 权限，非 root）。", "ADB connected (shell access, not root).")
                    else label("连接失败或超时；配对端口不能用于连接，请检查连接端口。", "Connection failed or timed out. Check the connection port; the pairing port cannot be used."))
            }
        }) { busy = false; update(label("其他 ADB 操作正在执行或清理，请稍后重试。", "Another ADB operation is running or cleaning up; retry later.")) }
    }

    private fun actionIntent(action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(this, AdbPairingService::class.java).setAction(action)
            .setData(Uri.parse("vega-adb://setup/${session}/$action")).putExtra(SESSION, session)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
            else if (!mutable) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, action.hashCode(), intent, flags).also { pendingIntents[action] = it }
    }
    private fun inputAction(action: String, zh: String, en: String): Notification.Action {
        val input = RemoteInput.Builder(INPUT).setLabel(label(zh, en)).build()
        return Notification.Action.Builder(android.R.drawable.ic_menu_edit, label(zh, en), actionIntent(action, true))
            .addRemoteInput(input).build()
    }
    private fun update(message: String) {
        if (ending) return
        lastMessage = message
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, label("ADB 配对", "ADB pairing"), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            Intent(this, SettingsActivity::class.java).putExtra(OPEN_PANEL, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(label("本机无线 ADB 配对", "Local wireless ADB pairing"))
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setTimeoutAfter((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        if (!busy) {
            if (!paired) builder.addAction(inputAction(PAIR, "输入配对码 / 端口 配对码", "Enter code / port code"))
            else {
                if (connectPort in 1..65535) builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_send,
                    label("连接 $connectPort", "Connect $connectPort"), actionIntent(CONNECT_FOUND, false)).build())
                builder.addAction(inputAction(CONNECT, "输入连接端口", "Enter connection port"))
            }
        }
        builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
            label("取消", "Cancel"), actionIntent(STOP, false)).build())
        try {
            if (!foreground) {
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
                else startForeground(NOTIFICATION_ID, builder.build())
                foreground = true
            } else manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) { finish(label("通知不可用，请使用设置页分屏手动配对。", "Notifications unavailable. Use split-screen manual pairing in Settings.")) }
    }

    private fun finish(message: String) {
        if (ending) return
        ending = true; lastMessage = message
        cleanup()
        stopSelf()
    }
    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        discovery.stop(); work.cancel()
        pendingIntents.values.forEach { runCatching { it.cancel() } }; pendingIntents.clear()
        session = null
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
    }
    override fun handleFgsTimeout(startId: Int) { finish(label("通知配对已超时。", "Notification pairing timed out.")) }
    override fun onTaskRemoved(rootIntent: Intent?) { finish(label("通知配对已取消。", "Notification pairing cancelled.")) }
    override fun onDestroy() { ending = true; cleanup(); super.onDestroy() }

    companion object {
        const val OPEN_PANEL = "dev.wind.agent.adb.OPEN_PANEL"
        private const val START = "dev.wind.agent.adb.START"
        private const val PAIR = "dev.wind.agent.adb.PAIR"
        private const val CONNECT = "dev.wind.agent.adb.CONNECT"
        private const val CONNECT_FOUND = "dev.wind.agent.adb.CONNECT_FOUND"
        private const val STOP = "dev.wind.agent.adb.STOP"
        private const val SESSION = "session"
        private const val PORT = "port"
        private const val INPUT = "adb_setup_input"
        private const val CHANNEL = "adb_pairing"
        private const val NOTIFICATION_ID = 0xADB
        const val SESSION_TIMEOUT_MS = 150_000L
        @Volatile var lastMessage: String = ""
            private set
        private fun label(zh: String, en: String) = AdbSettingsUi.label(zh, en)

        private fun notificationsAvailable(context: android.content.Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            return manager.areNotificationsEnabled() && (manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
        }

        /** Call only from a visible Activity button; notification reply never starts a new session. */
        fun startFromUi(activity: Activity, pairingPort: Int = 0): String {
            if (Build.VERSION.SDK_INT < 30) return label("需要 Android 11 及以上。", "Requires Android 11+.")
            if (!notificationsAvailable(activity)) return label("请允许配对通知，或使用分屏手动配对。", "Allow pairing notifications, or use split-screen manual pairing.")
            return try {
                activity.startForegroundService(Intent(activity, AdbPairingService::class.java).setAction(START).putExtra(PORT, pairingPort))
                label("已请求通知配对。请自行打开无线调试的配对码弹窗，查看通知。", "Notification pairing requested. Open the Wireless debugging pairing-code dialog yourself and check the notification.")
            } catch (_: Exception) { label("无法启动通知配对，请使用分屏手动配对。", "Could not start notification pairing. Use split-screen manual pairing.") }
        }
    }
}
