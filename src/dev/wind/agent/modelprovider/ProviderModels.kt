package dev.wind.agent.modelprovider

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ProviderPreset(val id: String, val name: String, val url: String, val protocol: String)
object ProviderPresets {
    val all = listOf(
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "responses"),
        ProviderPreset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "openai"),
        ProviderPreset("opencode", "OpenCode Zen", "https://opencode.ai/zen/v1", "openai"),
        ProviderPreset("anthropic", "Anthropic", "https://api.anthropic.com/v1", "anthropic"),
        ProviderPreset("gemini", "Gemini AI Studio", "https://generativelanguage.googleapis.com/v1beta", "gemini"),
        ProviderPreset("kimi", "Kimi / Moonshot", "https://api.moonshot.cn/v1", "openai"),
        ProviderPreset("glm", "GLM / 智谱", "https://open.bigmodel.cn/api/paas/v4", "openai"),
        ProviderPreset("chatgpt", "ChatGPT 订阅 · 官方", "https://api.openai.com/v1", "responses"),
        ProviderPreset("antigravity", "Google Antigravity · OpenCodex 兼容", "https://daily-cloudcode-pa.googleapis.com", "antigravity"),
        ProviderPreset("custom", "自定义 API", "", "openai")
    )
    fun get(id: String) = all.firstOrNull { it.id == id }
}

data class DiscoveredModel(val id: String, val name: String = id, val efforts: List<String> = listOf("default"),
                           val wireIds: Map<String, String> = emptyMap(), val protocol: String = "", val inputTokenLimit: Int = 0) {
    fun json() = JSONObject().put("id", id).put("name", name).put("efforts", JSONArray(efforts))
        .put("wireIds", JSONObject().also { j -> wireIds.forEach { (k,v) -> j.put(k,v) } }).put("protocol", protocol).put("inputTokenLimit", inputTokenLimit)
    companion object {
        fun parse(j: JSONObject): DiscoveredModel {
            val a = j.optJSONArray("efforts") ?: JSONArray().put("default")
            val wires = j.optJSONObject("wireIds") ?: JSONObject()
            return DiscoveredModel(j.getString("id"), j.optString("name", j.getString("id")),
                (0 until a.length()).map { a.getString(it) }, wires.keys().asSequence().associateWith { wires.getString(it) }, j.optString("protocol"), j.optInt("inputTokenLimit").coerceAtLeast(0))
        }
    }
}

/** Metadata contains no API keys, OAuth tokens or signed authorization URLs. */
data class ModelProvider(val id: String = UUID.randomUUID().toString(), val preset: String,
    val label: String, val url: String, val protocol: String, val model: String = "", val effort: String = "default",
    val models: List<DiscoveredModel> = emptyList(), val status: String = "尚未读取模型", val fetchedAt: Long = 0,
    val clientId: String = "", val subject: String = "", val email: String = "", val project: String = "") {
    val subscription get() = preset == "chatgpt" || preset == "antigravity"
    fun selected() = models.firstOrNull { it.id == model } ?: DiscoveredModel(model, efforts = ReasoningCapabilities.efforts(preset, model, protocol))
    fun json() = JSONObject().put("id", id).put("preset", preset).put("label", label).put("url", url)
        .put("protocol", protocol).put("model", model).put("effort", effort).put("models", JSONArray(models.map { it.json() }))
        .put("status", status).put("fetchedAt", fetchedAt).put("clientId", clientId).put("subject", subject)
        .put("email", email).put("project", project)
    companion object {
        fun parse(j: JSONObject): ModelProvider {
            val a = j.optJSONArray("models") ?: JSONArray()
            return ModelProvider(j.getString("id"), j.getString("preset"), j.getString("label"), j.getString("url"),
                j.getString("protocol"), j.optString("model"), j.optString("effort", "default"),
                (0 until a.length()).map { DiscoveredModel.parse(a.getJSONObject(it)) }, j.optString("status"),
                j.optLong("fetchedAt"), j.optString("clientId"), j.optString("subject"), j.optString("email"), j.optString("project"))
        }
    }
}

/** Only advertise controls with a known wire contract. Unknown models keep the server default. */
object ReasoningCapabilities {
    fun efforts(preset: String, model: String, protocol: String = ""): List<String> {
        val m = model.lowercase()
        return when {
            preset == "custom" && protocol == "anthropic" -> efforts("anthropic",model)
            preset == "custom" && protocol == "gemini" -> efforts("gemini",model)
            preset == "custom" && protocol in listOf("openai","responses") && (m.startsWith("gpt-") || m.startsWith("o3") || m.startsWith("o4")) -> efforts("openai",model)
            preset == "opencode" -> efforts(when { m.startsWith("claude") -> "anthropic"; m.startsWith("gemini") -> "gemini"; m.startsWith("gpt") -> "openai"; m.startsWith("deepseek") -> "deepseek"; m.startsWith("glm") -> "glm"; m.startsWith("kimi") -> "kimi"; else -> "custom" },model)
            preset == "deepseek" && (m.startsWith("deepseek-v4") || m=="deepseek-flash") -> listOf("default", "none", "low", "high", "max")
            preset == "deepseek" && m == "deepseek-chat" -> listOf("default", "none", "high")
            preset == "deepseek" && m == "deepseek-reasoner" -> listOf("default")
            preset == "kimi" && m.startsWith("kimi-k2.5") -> listOf("default", "none", "high")
            preset == "glm" && m=="glm-5.3" -> listOf("default", "low", "high", "max")
            preset == "glm" && (m.startsWith("glm-4.5") || m.startsWith("glm-4.6") || m.startsWith("glm-4.7") || m.startsWith("glm-5")) -> listOf("default", "none", "high")
            preset == "anthropic" && (m.contains("fable-5") || m.contains("mythos-5") || m.contains("opus-5") || m.contains("sonnet-5") || m.contains("opus-4-7") || m.contains("opus-4-8")) -> listOf("default", "none", "low", "medium", "high", "xhigh", "max")
            preset == "anthropic" && (m.contains("4-6") || m.contains("4.6") || m.contains("4-7") || m.contains("4.7")) ->
                listOf("default", "none", "low", "medium", "high", "max")
            preset == "anthropic" && (m.contains("claude-sonnet-4") || m.contains("claude-opus-4") || m.contains("claude-3-7")) -> listOf("default", "none", "low", "medium", "high")
            (preset == "openai" || preset == "chatgpt") && m.contains("pro") && m.startsWith("gpt-5") -> if(m.startsWith("gpt-5.2")) listOf("default","medium","high","xhigh") else if(m.startsWith("gpt-5.4")) listOf("default","high","xhigh") else listOf("default","high")
            (preset == "openai" || preset == "chatgpt") && m.startsWith("gpt-5.1") && m.contains("codex") -> listOf("default","low","medium","high")
            (preset == "openai" || preset == "chatgpt") && m.startsWith("gpt-5.1") -> listOf("default", "none", "low", "medium", "high")
            (preset == "openai" || preset == "chatgpt") && (m.startsWith("gpt-5.2") || m.startsWith("gpt-5.3") || m.startsWith("gpt-5.4") || m.startsWith("gpt-5.5") || m.startsWith("gpt-5.6") || m.startsWith("gpt-6")) -> listOf("default", "none", "low", "medium", "high", "xhigh")
            (preset == "openai" || preset == "chatgpt") && (m.startsWith("o3") || m.startsWith("o4") || m.startsWith("gpt-5")) -> listOf("default", "low", "medium", "high")
            preset == "gemini" && Regex("gemini-3\\.[678]-flash").containsMatchIn(m) -> listOf("default", "low", "medium", "high")
            preset == "gemini" && m.startsWith("gemini-3") -> if (m.contains("flash")) listOf("default", "minimal", "low", "medium", "high") else listOf("default", "low", "high")
            preset == "gemini" && m.startsWith("gemini-2.5") && !m.contains("image") -> if (m.contains("flash")) listOf("default", "none", "low", "medium", "high") else listOf("default", "low", "medium", "high")
            else -> listOf("default")
        }
    }
    fun label(e: String) = when(e) { "default" -> "服务默认"; "none" -> "关闭推理"; "minimal" -> "最小"; "low" -> "低"; "medium" -> "中"; "high" -> "高"; "xhigh" -> "很高"; "max" -> "最高"; else -> e }
    fun valid(p: ModelProvider, effort: String) = effort in p.selected().efforts
    fun adaptiveClaude(model: String) = Regex("claude-(opus|sonnet)-(4[-.][678]|5)").containsMatchIn(model) || model.contains("claude-fable") || model.contains("claude-mythos")
}
