package dev.wind.agent.webprovider

import android.content.Context
import dev.wind.agent.AgentEngine
import dev.wind.agent.CancellationToken
import dev.wind.agent.Chat
import dev.wind.agent.Message
import dev.wind.agent.model.ModelRequest
import dev.wind.agent.model.ModelStreamCallback
import org.json.JSONArray
import org.json.JSONObject

/** Uses the existing engine/service ownership and callbacks, with no webpage-controlled tools. */
object WebModelRunner {
    fun run(context: Context, chat: Chat, token: CancellationToken, callback: AgentEngine.Callback) {
        val prefs = WebProviderPrefs(context)
        val binding = chat.webBinding
        val message = Message("assistant", "").apply { streaming = true }
        synchronized(chat.messages) { chat.messages.add(message) }
        callback.onNewAssistantMessage(message)
        val user = synchronized(chat.messages) { chat.messages.lastOrNull { it.role == "user" } }
        try {
            check(binding != null && user != null) { "WEB_BINDING_OR_QUESTION_REQUIRED" }
            check(prefs.runnable(binding.providerId)) { "WEB_PROVIDER_DISABLED_OR_MANUAL_ONLY" }
            val kinds = HashSet<String>()
            if (user.screenContext != null) kinds.add("screen")
            if (user.screenContextNote != null) kinds.add("screen_text")
            user.attachments.forEach { kinds.add(if (it.kind == "image") "images" else "files") }
            user.webContexts.forEach { kinds.add(it.kind) }
            if (binding.includeHistory) kinds.add("history")
            val needsPermission = kinds.any { !prefs.allow(it) } || (kinds.isNotEmpty() && prefs.confirmSensitive())
            if (needsPermission) {
                val summary = JSONObject().put("provider", WebProviderRegistry.get(binding.providerId)?.name)
                    .put("context_types", JSONArray(kinds.sorted())).put("question", user.content.take(2000))
                    .put("attachments", JSONArray(user.attachments.map { (it.name ?: "file") + " (" + it.size + " bytes)" }))
                    .put("screen_snapshot", user.screenContext != null)
                    .put("notice", "Send only this question and the selected context to this third-party website. This does not grant tool or phone-control authority.")
                check(callback.requestApproval("web_provider_send", summary)) { "WEB_CONTEXT_SEND_DECLINED" }
            }
            token.throwIfCancelled()
            var receivedAnswer = false
            WebModelTransport(context).stream(ModelRequest(JSONArray(), chat), token, object : ModelStreamCallback {
                override fun onToken(text: String) { if (!receivedAnswer) message.content = ""; receivedAnswer = true; message.content += text; callback.onDelta(message, text) }
                override fun onSnapshot(text: String) { receivedAnswer = true; message.content = text; callback.onDelta(message, "") }
                override fun onThinking(text: String) {}
                override fun onDone(text: String?) { if (text != null) message.content = text }
                override fun onError(problem: String) {
                    message.isError = true
                    val detail = WebStrings.state(context, problem) + "\n(" + problem + ")"
                    message.content = if (!receivedAnswer || message.content.isBlank()) detail else message.content + "\n\n[" + detail + "]"
                    callback.onError(detail)
                }
                override fun isCancelled(): Boolean = callback.isCancelled()
                override fun onState(state: String) {
                    if (!receivedAnswer) {
                        message.content = WebStrings.state(context, state)
                        callback.onDelta(message, "")
                    }
                }
            })
        } catch (_: CancellationToken.CancelledException) {
            // Cancellation retains any partial answer and is not a model failure.
        } catch (failure: Throwable) {
            val text = failure.message?.takeIf { it.matches(Regex("[A-Z0-9_]{2,120}")) } ?: "WEB_PROVIDER_FAILURE"
            val detail = WebStrings.state(context, text)
            message.isError = true; message.content = detail; callback.onError(detail)
        } finally {
            message.streaming = false
            user?.webContexts?.clear()
            callback.onStepFinalized(message)
            callback.onComplete()
        }
    }
}
