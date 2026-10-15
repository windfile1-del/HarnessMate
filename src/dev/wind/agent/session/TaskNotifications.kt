package dev.wind.agent.session

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import dev.wind.agent.AgentBus
import dev.wind.agent.MainActivity
import dev.wind.agent.R

/** Events are separate from the always-silent foreground-service notification. */
object TaskNotifications : Application.ActivityLifecycleCallbacks {
    private var app: Application? = null
    private val resumed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Activity, Boolean>())
    const val CHANNEL = "harness-task-events-v1"
    fun shouldAlert(inside: Boolean, interactive: Boolean, locked: Boolean) = !inside || !interactive || locked
    fun initialize(application: Application) { app = application; application.registerActivityLifecycleCallbacks(this) }
    private fun background(c: Context): Boolean {
        val power = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = c.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return shouldAlert(resumed.isNotEmpty(), power?.isInteractive ?: false, keyguard?.isKeyguardLocked ?: true)
    }
    fun shouldAlert(context: Context): Boolean=background(context)
    private fun id(chatId: String) = 2000 + (chatId.hashCode() and 0x0fffffff)
    fun clear(chatId: String) { app?.let { (it.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(id(chatId)) } }
    fun event(chatId: String, title: String, detail: String = "点击返回会话查看") {
        val c = app ?: return
        if (!background(c)) return
        runCatching {
            val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务完成与授权", NotificationManager.IMPORTANCE_HIGH))
            val open = Intent(c, MainActivity::class.java).putExtra("floatingChatId", chatId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(c, id(chatId), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL) else Notification.Builder(c)
            manager.notify(id(chatId), builder.setSmallIcon(R.mipmap.ic_stat_vepro).setContentTitle(title)
                .setContentText(detail).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_SOUND).build())
        }
    }
    fun approval(runId: Long) { AgentBus.chatId(runId)?.let { event(it, "Harness Mate 需要你的授权") } }
    override fun onActivityResumed(activity: Activity) { resumed.add(activity) }
    override fun onActivityPaused(activity: Activity) {
        resumed.remove(activity)
        // Defer rotation/app-internal transitions; still notify a waiter when user leaves later.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (app?.let { background(it) } == true) {
                AgentBus.pendingApprovalRunIds().forEach { approval(it) }
                dev.wind.agent.phone.ChatGuardianService.current?.notifyAttentionWhenBackground()
            }
        }, 350L)
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) { resumed.remove(a) }
}
