package dev.wind.agent.webprovider

import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

enum class WebOperation { SEND, REGENERATE, EDIT_AND_SEND }
enum class WebRequestState { PREPARED, SUBMITTING, SUBMITTED, GENERATING, COMPLETE, STOPPED, UNKNOWN, FAILED }

data class WebCapabilities(
    val text: Boolean = true,
    val images: Boolean = false,
    val files: Boolean = false,
    val regenerate: Boolean = false,
    val edit: Boolean = false,
    val stop: Boolean = true,
    val search: Boolean = false
)

data class WebProviderDefinition(
    val id: String,
    val name: String,
    val company: String,
    val home: String,
    val hosts: Set<String>,
    val authHosts: Set<String>,
    val adapter: String,
    val capabilities: WebCapabilities,
    val manualOnly: Boolean = false,
    val note: String = "",
    val configured: Boolean = true
) {
    val origins: Set<String> get() = hosts.map { "https://$it" }.toSet()
    fun owns(url: String?): Boolean = WebUrlPolicy.matches(url, hosts)
    fun canNavigate(url: String?): Boolean = WebUrlPolicy.matches(url, hosts + authHosts)
}

object WebUrlPolicy {
    /** Only human-approved HTTPS navigation may leave a provider's known login hosts. */
    fun publicHttps(url: String?): Boolean = try {
        val u = URI(url ?: ""); val host = u.host?.lowercase(Locale.ROOT) ?: ""
        u.scheme.equals("https", true) && u.rawUserInfo == null && (u.port == -1 || u.port == 443) &&
            host.contains('.') && host.matches(Regex("[a-z0-9.-]+")) && !host.matches(Regex("[0-9.]+")) &&
            !host.endsWith(".local") && !host.endsWith(".localhost") && !host.endsWith(".internal")
    } catch (_: Exception) { false }
    /** Exact HTTPS hosts only; never substring matching, credentials or arbitrary ports. */
    fun matches(url: String?, hosts: Set<String>): Boolean = try {
        val u = URI(url ?: "")
        u.scheme.equals("https", true) && u.rawUserInfo == null &&
            (u.port == -1 || u.port == 443) && hosts.contains(u.host?.lowercase(Locale.ROOT))
    } catch (_: Exception) { false }

    fun sessionUrl(url: String?, provider: WebProviderDefinition): String? {
        if (url == null || url.length > 8192 || !provider.owns(url)) return null
        val u = URI(url!!)
        val path = u.rawPath ?: "/"
        // Authentication material and transient challenge parameters never enter chat history.
        val query = u.rawQuery?.split('&')?.filter {
            val key = it.substringBefore('=').lowercase(Locale.ROOT)
            key in setOf("chat", "chat_id", "conversation", "conversation_id", "session_id", "id")
        }?.joinToString("&")?.ifBlank { null }
        return "https://" + u.host.lowercase(Locale.ROOT) + path + (query?.let { "?$it" } ?: "")
    }
}

object WebProviderRegistry {
    // Candidates are explicitly unverified. No claimed backend version or native tool calling.
    val all = listOf(
        WebProviderDefinition("fixture", "本地 Web Provider 测试页", "Harness", "https://web-provider.vega.invalid/fixture",
            setOf("web-provider.vega.invalid"), emptySet(), "fixture", WebCapabilities(images = true, files = true, regenerate = true),
            note = "仅测试浏览器链路，不连接第三方模型。"),
        WebProviderDefinition("deepseek", "DeepSeek", "DeepSeek", "https://chat.deepseek.com/",
            setOf("chat.deepseek.com"), setOf("accounts.deepseek.com"), "deepseek", WebCapabilities(images = true, files = true, regenerate = true)),
        WebProviderDefinition("glm", "GLM / 智谱清言", "智谱", "https://chatglm.cn/",
            setOf("chatglm.cn", "www.chatglm.cn"), emptySet(), "glm", WebCapabilities(images = true, files = true, regenerate = true)),
        WebProviderDefinition("kimi", "Kimi", "月之暗面", "https://www.kimi.com/",
            setOf("www.kimi.com", "kimi.com", "kimi.moonshot.cn"), emptySet(), "kimi", WebCapabilities(images = true, files = true, regenerate = true)),
        WebProviderDefinition("doubao", "豆包", "字节跳动", "https://www.doubao.com/chat/",
            setOf("www.doubao.com", "doubao.com"), setOf("sso.douyin.com", "passport.doubao.com"), "doubao", WebCapabilities(images = true, files = true, regenerate = true)),
        WebProviderDefinition("yuanbao", "腾讯元宝", "腾讯", "https://yuanbao.tencent.com/chat/",
            setOf("yuanbao.tencent.com"), setOf("xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com", "open.weixin.qq.com"), "yuanbao", WebCapabilities(images = true, files = true, regenerate = true)),
        WebProviderDefinition("hunyuan", "腾讯混元", "腾讯", "https://hunyuan.tencent.com/",
            setOf("hunyuan.tencent.com"), setOf("xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com", "open.weixin.qq.com"), "hunyuan", WebCapabilities(),
            note = "混元与元宝是独立入口；聊天入口和账号功能需要实机确认。"),
        WebProviderDefinition("gemini", "Gemini", "Google", "https://gemini.google.com/app",
            setOf("gemini.google.com"), setOf("accounts.google.com"), "gemini", WebCapabilities(images = true, files = true, regenerate = true),
            note = "Google 可能拒绝内嵌浏览器登录。外部浏览器登录不会自动导入此处。"),
        WebProviderDefinition("chatgpt", "ChatGPT", "OpenAI", "https://chatgpt.com/",
            setOf("chatgpt.com"), setOf("auth.openai.com", "auth0.openai.com", "accounts.google.com", "login.microsoftonline.com", "appleid.apple.com"), "chatgpt", WebCapabilities(),
            note = "实验性 DOM 适配；登录、验证码及服务限制需在原网页手动处理。订阅账户也可通过账户管理使用官方接入。"),
        WebProviderDefinition("claude", "Claude", "Anthropic", "https://claude.ai/",
            setOf("claude.ai"), setOf("accounts.google.com"), "claude", WebCapabilities(),
            note = "实验性 DOM 适配；仅在服务允许的范围内使用。登录和验证码由用户完成，网页变化可能影响适配。"),
        WebProviderDefinition("grok", "Grok", "xAI", "https://grok.com/",
            setOf("grok.com", "www.grok.com"), setOf("accounts.x.ai", "accounts.google.com", "appleid.apple.com"), "grok", WebCapabilities(images = true, files = true, regenerate = true)),
        WebProviderDefinition("meta", "Meta AI", "Meta", "https://www.meta.ai/",
            setOf("www.meta.ai", "meta.ai"), setOf("www.facebook.com", "www.instagram.com", "auth.meta.com"), "meta", WebCapabilities(images = true)),
        WebProviderDefinition("muse", "Muse（入口待确认）", "待确认", "", emptySet(), emptySet(), "muse", WebCapabilities(text = false),
            manualOnly = true, configured = false, note = "尚未确定 Muse 所指服务；不将其与 Meta AI 合并。")
    )
    fun get(id: String?): WebProviderDefinition? = all.firstOrNull { it.id == id }
    const val ADAPTER_VERSION = "web-dom-2026.10.03.1"
}

data class WebConversationBinding(
    val providerId: String,
    val profileName: String,
    @Volatile var remoteUrl: String? = null,
    @Volatile var remoteId: String? = null,
    var adapterVersion: String = WebProviderRegistry.ADAPTER_VERSION,
    var updatedAt: Long = 0L,
    var includeHistory: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("providerId", providerId).put("profileName", profileName)
        .put("remoteUrl", remoteUrl).put("remoteId", remoteId).put("adapterVersion", adapterVersion).put("updatedAt", updatedAt).put("includeHistory", includeHistory)
    companion object {
        fun fromJson(json: JSONObject?): WebConversationBinding? {
            if (json == null) return null
            val provider = WebProviderRegistry.get(json.optString("providerId")) ?: return null
            val profile = json.optString("profileName")
            if (!profile.matches(Regex("vega_web_[a-z0-9_]{1,80}"))) return null
            return WebConversationBinding(provider.id, profile,
                WebUrlPolicy.sessionUrl(json.optString("remoteUrl"), provider),
                json.optString("remoteId").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,180}")) },
                json.optString("adapterVersion", WebProviderRegistry.ADAPTER_VERSION), json.optLong("updatedAt"), json.optBoolean("includeHistory"))
        }
    }
}

/** One durable send intent. UNKNOWN can be reconciled but must never be automatically re-sent. */
data class WebSendReceipt(
    val requestId: String, val chatId: String, val providerId: String, val profileName: String,
    val operation: WebOperation, val promptDigest: String,
    var state: WebRequestState = WebRequestState.PREPARED,
    var remoteUrl: String? = null, var responseId: String? = null,
    var answer: String = "", var diagnostic: String = "", var updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().put("requestId", requestId).put("chatId", chatId)
        .put("providerId", providerId).put("profileName", profileName).put("operation", operation.name)
        .put("promptDigest", promptDigest).put("state", state.name).put("remoteUrl", remoteUrl)
        .put("responseId", responseId).put("answer", answer).put("diagnostic", diagnostic).put("updatedAt", updatedAt)
    companion object {
        fun fromJson(json: JSONObject): WebSendReceipt = WebSendReceipt(
            json.getString("requestId"), json.getString("chatId"), json.getString("providerId"), json.getString("profileName"),
            WebOperation.valueOf(json.getString("operation")), json.getString("promptDigest"),
            WebRequestState.valueOf(json.getString("state")), json.optString("remoteUrl").takeIf { it.isNotBlank() },
            json.optString("responseId").takeIf { it.isNotBlank() }, json.optString("answer"),
            json.optString("diagnostic"), json.optLong("updatedAt"))
    }
}

object WebDigests {
    fun of(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class WebContextItem(val kind: String, val label: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("label", label).put("text", text)
    companion object {
        val allowedKinds = setOf("selected_text", "clipboard", "screen_text", "search", "mcp", "file_text")
        fun fromJson(json: JSONObject): WebContextItem? {
            val kind = json.optString("kind")
            if (kind !in allowedKinds) return null
            return WebContextItem(kind, json.optString("label").take(120), json.optString("text").take(24000))
        }
        fun list(json: JSONArray?): List<WebContextItem> = (0 until minOf(json?.length() ?: 0, 12))
            .mapNotNull { fromJson(json!!.optJSONObject(it) ?: JSONObject()) }
    }
}
