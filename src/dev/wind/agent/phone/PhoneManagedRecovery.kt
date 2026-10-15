package dev.wind.agent.phone

import android.content.Context
import android.os.SystemClock
import dev.wind.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Separate Full-mode task worker; event callbacks signal only, READ tools never call recovery. */
internal object PhoneManagedRecovery {
    private class Session(val context: Context, val owner: String, val parent: CancellationToken) {
        val token = CancellationToken()
        val registration = parent.onCancel { token.cancel() }
        val budget = PhoneRecoveryBudget()
        var active = false
    }
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "phone-popup-recovery").apply { isDaemon = true } }
    private var session: Session? = null
    private var pending: ScheduledFuture<*>? = null
    private val history = java.util.ArrayDeque<JSONObject>()
    @Synchronized fun activate(context: Context, owner: String, token: CancellationToken) {
        if (PhoneApprovalPolicy.mode(context) != PhoneApprovalPolicy.Mode.FULL || !PhoneAutomation.recoveryEnabled(context)) return
        if (session?.owner != owner || session?.parent !== token || session?.token?.isCancelled == true) {
            stopAll(); session = Session(context.applicationContext, owner, token)
        }
        session?.active = true; signal()
    }
    @Synchronized fun endTask(owner: String) { if (session?.owner == owner) stopAll() }
    @Synchronized fun stopAll() {
        pending?.cancel(false); pending = null
        session?.let { it.token.cancel(); it.registration.close() }; session = null
    }
    @Synchronized fun signal() {
        val current = session ?: return
        if (!current.active || current.token.isCancelled) return
        pending?.cancel(false)
        pending = worker.schedule({ recover(current) }, 350, TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun record(owner: String, result: JSONObject) {
        history.addLast(JSONObject().put("owner", owner).put("at", SystemClock.elapsedRealtime()).put("result", result))
        while (history.size > 10) history.removeFirst()
    }
    @Synchronized fun status(owner: String) = JSONObject().put("active", session?.let { it.owner == owner && !it.token.isCancelled } == true)
        .put("attempts", session?.takeIf { it.owner == owner }?.budget?.attempts ?: 0)
        .put("history", JSONArray(history.filter { it.optString("owner") == owner }.map { JSONObject(it.toString()) }))
    private fun recover(current: Session) {
        val context = current.context
        if (current.token.isCancelled || PhoneApprovalPolicy.mode(context) != PhoneApprovalPolicy.Mode.FULL ||
            !PhoneAutomation.recoveryEnabled(context) || PhoneAutomation.mode(context) == PhoneAutomation.Mode.OFF) return
        try {
            if (!PhoneAutomation.tryRecovery(current.token) {
                val result = PhoneTools(context, current.owner).recoverPending(current.token, current.budget)
                if (result.optString("status") != "NONE") record(current.owner, result)
            }) {
                synchronized(this) { if (session === current) signal() }
            }
        } catch (error: Exception) {
            if (!current.token.isCancelled) record(current.owner, JSONObject().put("status", "PAUSED")
                .put("reason", error.message ?: error.javaClass.simpleName))
        }
    }
}
