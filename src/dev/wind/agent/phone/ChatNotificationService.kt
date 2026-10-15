package dev.wind.agent.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class ChatNotificationService : NotificationListenerService() {
    override fun onListenerConnected() { ChatAssist.listener = this }
    override fun onListenerDisconnected() { ChatAssist.listener = null; ChatAssist.disconnected(this) }
    override fun onDestroy() { if (ChatAssist.listener === this) ChatAssist.listener = null; ChatAssist.disconnected(this); super.onDestroy() }
    override fun onNotificationPosted(notification: StatusBarNotification?) {
        if (notification == null) return
        // Guardian consumes only metadata hints; actual text and recipient are re-read on its own display.
        ChatGuardianService.signal(notification.packageName)
        // Decode only packages selected by the user; never forward other notifications.
        val b = ChatAssist.binding(this)
        if (b.optString("mode") !in setOf("auto", "suggest") || notification?.packageName != b.optString("package")) return
        runCatching { ChatAssist.decode(notification) }.getOrNull()?.let { ChatAssist.receive(this, it) }
    }
}
