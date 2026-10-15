package dev.wind.agent.context

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Add provider-specific fields only for endpoints with a known wire contract. */
object PromptCachePolicy {
    fun officialOpenAi(url: String) = runCatching { URI(url).host.equals("api.openai.com", true) }.getOrDefault(false)
    fun officialAnthropic(url: String) = runCatching { URI(url).host.equals("api.anthropic.com", true) }.getOrDefault(false)
    fun key(scope: String, model: String) = "vega-" + ContextManager.digest(JSONArray().put(JSONObject().put("scope", scope).put("model", model))).take(48)
    fun openAi(body: JSONObject, url: String, scope: String?, model: String, stream: Boolean) {
        if (!officialOpenAi(url)) return
        if (!scope.isNullOrBlank()) body.put("prompt_cache_key", key(scope, model))
        if (stream) body.put("stream_options", JSONObject().put("include_usage", true))
    }
    fun anthropic(body: JSONObject, url: String) {
        if (!officialAnthropic(url)) return
        val system = body.opt("system")
        if (system is String && system.isNotEmpty()) body.put("system", JSONArray().put(JSONObject().put("type", "text").put("text", system)
            .put("cache_control", JSONObject().put("type", "ephemeral"))))
        val messages = body.optJSONArray("messages") ?: return
        // Cache at the last reusable message, leaving the new input uncached; bounded to two breakpoints.
        val index = messages.length() - 2
        if (index < 0) return
        val message = messages.getJSONObject(index)
        val parts = message.optJSONArray("content") ?: return
        if (parts.length() > 0) parts.getJSONObject(parts.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral"))
    }
}
