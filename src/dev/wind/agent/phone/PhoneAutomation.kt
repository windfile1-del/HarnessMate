package dev.wind.agent.phone

import android.content.Context
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.wind.agent.CancellationToken
import dev.wind.agent.screen.ScreenShareSession
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Explicit user setting. Accessibility enabled by itself grants no agent operation. */
internal object PhoneAutomation {
    enum class Mode { OFF, VISUAL, UI, HYBRID }
    @Volatile var service: PhoneControlService? = null
    @Volatile var foregroundMain: WeakReference<Activity>? = null
    private val serial = ReentrantLock()

    fun mode(context: Context): Mode {
        val store = dev.wind.agent.permissions.AgentPermissions.get(context)
        val owner = dev.wind.agent.AgentBus.activeTaskId.orEmpty()
        val ui = store.enabled(dev.wind.agent.permissions.AgentCapability.ACCESSIBILITY, owner)
        val screen = store.enabled(dev.wind.agent.permissions.AgentCapability.SCREEN, owner)
        return if (ui && screen) Mode.HYBRID else if (ui) Mode.UI else if (screen) Mode.VISUAL else Mode.OFF
    }

    fun strictFreshness(context: Context): Boolean = context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE)
        .getString("freshness", "tolerant") == "strict"

    fun chooseFreshness(context: Context, strict: Boolean) {
        context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).edit()
            .putString("freshness", if (strict) "strict" else "tolerant").apply()
        service?.invalidateObservations()
    }

    fun choose(context: Context, mode: Mode) {
        PhoneManagedRecovery.stopAll()
        context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()
        val store = dev.wind.agent.permissions.AgentPermissions.get(context)
        store.set(dev.wind.agent.permissions.AgentCapability.ACCESSIBILITY, dev.wind.agent.permissions.PermissionScope.GLOBAL, mode in setOf(Mode.UI, Mode.HYBRID))
        store.set(dev.wind.agent.permissions.AgentCapability.SCREEN, dev.wind.agent.permissions.PermissionScope.GLOBAL, mode in setOf(Mode.VISUAL, Mode.HYBRID))
        PhoneApprovalPolicy.clear()
        service?.invalidateObservations()
    }

    fun requireEnabled(context: Context): PhoneControlService {
        check(mode(context) != Mode.OFF) { "PHONE_AUTOMATION_OFF: enable phone tools in desktop pet controls" }
        return service ?: error("PHONE_ACCESSIBILITY_REQUIRED: manually enable Vega phone automation")
    }

    /** Return to the app underneath Vega, including after an app-side approval. */
    fun yieldToTarget(token: CancellationToken) {
        Handler(Looper.getMainLooper()).post {
            if (!token.isCancelled) foregroundMain?.get()?.moveTaskToBack(true)
        }
        val until = SystemClock.elapsedRealtime() + 2500L
        while (ScreenShareSession.mainVisible || ScreenShareSession.controlsVisible || ScreenShareSession.appActivitiesVisible) {
            token.throwIfCancelled()
            check(SystemClock.elapsedRealtime() < until) { "PHONE_APP_FOREGROUND: close Vega settings/permission dialogs" }
            token.sleep(50)
        }
    }

    fun <T> serialized(token: CancellationToken, operation: () -> T): T {
        while (!serial.tryLock(75, TimeUnit.MILLISECONDS)) token.throwIfCancelled()
        try { token.throwIfCancelled(); return operation() } finally { serial.unlock() }
    }
    fun recoveryEnabled(context: Context) = context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE)
        .getBoolean("automatic_recovery", true)
    fun chooseRecovery(context: Context, enabled: Boolean) {
        context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).edit().putBoolean("automatic_recovery", enabled).apply()
        if (!enabled) PhoneManagedRecovery.stopAll()
    }
    internal fun tryRecovery(token: CancellationToken, operation: () -> Unit): Boolean {
        if (!serial.tryLock()) return false
        try { token.throwIfCancelled(); operation(); return true } finally { serial.unlock() }
    }
}
