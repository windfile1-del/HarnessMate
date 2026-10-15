package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import dev.wind.agent.screen.ScreenShareSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Events only schedule work. Enabled foreground chat additionally polls to cover empty accessibility trees. */
internal object ChatForegroundMonitor {
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "chat-foreground-reader").apply { isDaemon = true } }
    private val lock = Any()
    private var started = false
    private var reader: CancellationToken? = null
    private var forceCurrent = false
    private var epoch = 0L
    private var lastRead = 0L
    private var interval = 1000L
    private fun prefs(c: Context) = c.getSharedPreferences("chat_assist_monitor", Context.MODE_PRIVATE)
    fun reset(c: Context, analyseCurrent: Boolean = false) {
        synchronized(lock) { reader?.cancel(); epoch++; forceCurrent = analyseCurrent; lastRead = 0 }
        prefs(c).edit().remove("baseline").remove("binding_id").apply()
        start(c)
    }
    fun signal(c: Context, pkg: String) {
        val b = ChatAssist.binding(c)
        if (b.optString("package") == pkg && b.optString("mode") in setOf("suggest", "auto")) start(c)
    }
    fun start(c: Context) = synchronized(lock) {
        if (!started) { started = true; executor.schedule({ tick(c.applicationContext) }, 700, TimeUnit.MILLISECONDS) }
    }
    private fun tick(c: Context) {
        val b = ChatAssist.binding(c)
        if (b.optString("mode") !in setOf("auto", "suggest")) { synchronized(lock) { started = false }; return }
        val local = synchronized(lock) { CancellationToken().also { reader = it } to epoch }
        try {
            if (!ScreenShareSession.hasWindow || !PhoneBackends.available(c, AgentBus.activeTaskId.orEmpty()) || AgentBus.isBusy() && !ChatAssist.hasVisibleRun()) return
            if (System.currentTimeMillis() - lastRead < interval) return
            PhoneAutomation.tryRecovery(local.first) {
                lastRead = System.currentTimeMillis()
                val page = ChatPhoneScreen.read(c, b, local.first)
                interval = if (page.source == "ocr") 3000 else 1000
                local.first.throwIfCancelled()
                synchronized(lock) { check(epoch == local.second) { "CHAT_MONITOR_REPLACED" } }
                val id = b.getString("identity")
                val saved = if (prefs(c).getString("binding_id", "") == id)
                    runCatching { JSONArray(prefs(c).getString("baseline", "[]")) }.getOrDefault(JSONArray()) else JSONArray()
                val previous = (0 until saved.length()).map { ChatPageParser.Turn(saved.getJSONObject(it).getString("role"), saved.getJSONObject(it).getString("text")) }
                val baseline = JSONArray(page.turns.map { JSONObject().put("role", it.role).put("text", it.text) })
                prefs(c).edit().putString("binding_id", id).putString("baseline", baseline.toString()).apply()
                val force = synchronized(lock) { forceCurrent.also { forceCurrent = false } }
                val changed = if (force) listOfNotNull(page.turns.lastOrNull()?.takeIf { it.role == "peer" })
                    else ChatPageParser.appended(previous, page.turns)
                if (changed.isNotEmpty()) {
                    val text = changed.joinToString("\n") { it.text }
                    val now = System.currentTimeMillis()
                    ChatAssist.receive(c, ChatAssist.Entry("foreground:$now", page.pkg, page.title, id, text, now,
                        page.turns.map { "${it.role}: ${it.text}" }, null, "foreground"))
                } else if (force) ChatAssist.report(c, "CHAT_NO_CLEAR_INCOMING_MESSAGE: 最新可见消息未能确认为对方发送")
            }
        } catch (failure: Exception) {
            val reason = failure.message.orEmpty()
            if (!local.first.isCancelled && !reason.contains("APP_FOREGROUND") && !reason.contains("TARGET_NOT_FOREGROUND")) {
                ChatAssist.report(c, reason)
                if (reason.contains("PEER_NOT_VERIFIED")) prefs(c).edit().remove("baseline").apply()
            }
        } finally {
            synchronized(lock) { reader = null }
            executor.schedule({ tick(c) }, 700, TimeUnit.MILLISECONDS)
        }
    }
}
