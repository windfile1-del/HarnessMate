package dev.wind.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A single conversation. [messages] is guarded by its own monitor because the
 * agent worker thread appends to it while the UI thread renders it.
 */
class Chat(
    var id: String,
    var title: String,
    var createdAt: Long
) {
    var updatedAt: Long = createdAt
    @Volatile var agentProfile = dev.wind.agent.profile.AgentProfile.CODE
    @Volatile var memoryPolicy = dev.wind.agent.profile.MemorySessionPolicy.NORMAL
    @Volatile var projectMemoryKey: String? = null
    @Volatile var projectBindingId: String? = null
    @Volatile var retentionPolicy = dev.wind.agent.profile.RetentionPolicy.PERSISTENT
    @Volatile var mateTaskId: String? = null
    @Volatile var mateTaskState: String = ""
    @Volatile var mateTaskInputMessageId: String? = null

    /**
     * Kept at the top of the drawer, above every other conversation.
     *
     * Ordering is by pin first and recency second, so pinning is a promise that a
     * chat stays findable however much is started after it — which is the only
     * reason to pin anything.
     */
    var pinned: Boolean = false

    var archived: Boolean = false
    @Volatile var runState: String = "idle"
    val selectedSkillPaths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile var webBinding: dev.wind.agent.webprovider.WebConversationBinding? = null
    val webBindings: MutableMap<String, dev.wind.agent.webprovider.WebConversationBinding> = linkedMapOf()

    val messages: MutableList<Message> = mutableListOf()
    var contextState = dev.wind.agent.context.ContextState()
    /** Transient bounded-by-history render cache; no model responses or grants are cached. */
    val contextRenderCache = dev.wind.agent.context.ContextRenderCache()

    /** Snapshot taken under the list monitor, safe to iterate off-thread. */
    private fun messageSnapshot(): List<Message> = synchronized(messages) { messages.toList() }

    /** Names the chat after its first non-empty user turn. */
    fun autoTitle() {
        for (message in messageSnapshot()) {
            if (message.role == "user" && !message.modelOnly && message.content.isNotBlankJava()) {
                var candidate = message.content.trimJava().replace(Regex("\\s+"), " ")
                if (candidate.length > 42) {
                    candidate = candidate.substring(0, 42) + "…"
                }
                title = candidate
                return
            }
        }
    }

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        if (pinned) {
            json.put("pinned", true)
        }
        json.put("title", title)
        json.put("createdAt", createdAt)
        json.put("updatedAt", updatedAt)
        json.put("runState", runState)
        json.put("agentProfile", agentProfile.key)
        json.put("memoryPolicy", memoryPolicy.name)
        json.put("retentionPolicy", retentionPolicy.name)
        mateTaskId?.let {json.put("mateTaskId",it);json.put("mateTaskState",mateTaskState);mateTaskInputMessageId?.let {source->json.put("mateTaskInputMessageId",source)}}
        projectMemoryKey?.let { json.put("projectMemoryKey", it) }
        projectBindingId?.let { json.put("projectBindingId", it) }
        if (archived) json.put("archived", true)
        json.put("selectedSkillPaths", JSONArray(synchronized(selectedSkillPaths) { selectedSkillPaths.toList() }))
        webBinding?.let { json.put("webBinding", it.toJson()) }
        if (webBindings.isNotEmpty()) json.put("webBindings", JSONArray(webBindings.values.map { it.toJson() }))

        val array = JSONArray()
        json.put("contextState", contextState.json())
        for (message in messageSnapshot()) {
            array.put(message.toJson())
        }
        json.put("messages", array)
        return json
    }

    companion object {
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject): Chat {
            val chat = Chat(
                json.getString("id"),
                // Keep the stored value verbatim (often ""): the UI substitutes
                // the placeholder at render time via Fa.isPlaceholderTitle.
                json.optStr("title", ""),
                json.optLong("createdAt", 0L)
            )
            chat.updatedAt = json.optLong("updatedAt", chat.createdAt)
            chat.pinned = json.optBoolean("pinned", false)
            chat.archived = json.optBoolean("archived", false)
            chat.runState = json.optString("runState", "idle")
            chat.agentProfile = dev.wind.agent.profile.AgentProfile.parse(json.optString("agentProfile"))
            chat.memoryPolicy = runCatching { dev.wind.agent.profile.MemorySessionPolicy.valueOf(json.optString("memoryPolicy")) }
                .getOrDefault(dev.wind.agent.profile.MemorySessionPolicy.NORMAL)
            chat.retentionPolicy = runCatching { dev.wind.agent.profile.RetentionPolicy.valueOf(json.optString("retentionPolicy")) }
                .getOrDefault(dev.wind.agent.profile.RetentionPolicy.PERSISTENT)
            chat.mateTaskId=json.optString("mateTaskId").takeIf {it.matches(Regex("mate_[A-Za-z0-9_-]{1,140}"))}
            chat.mateTaskState=json.optString("mateTaskState").takeIf {it in dev.wind.agent.mate.MateTaskState.entries.map {s->s.name}} ?: ""
            chat.mateTaskInputMessageId=json.optString("mateTaskInputMessageId").takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,160}"))}
            chat.projectMemoryKey = json.optString("projectMemoryKey").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
            chat.projectBindingId = json.optString("projectBindingId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
            json.optJSONArray("selectedSkillPaths")?.let { paths ->
                for (i in 0 until minOf(paths.length(), 20)) {
                    val path = paths.optString(i, "")
                    if (path.startsWith("skills://") && path.length <= 512) chat.selectedSkillPaths.add(path)
                }
            }
            chat.contextState = dev.wind.agent.context.ContextState.parse(json.optJSONObject("contextState"))
            chat.webBinding = dev.wind.agent.webprovider.WebConversationBinding.fromJson(json.optJSONObject("webBinding"))
            json.optJSONArray("webBindings")?.let { bindings ->
                for (i in 0 until minOf(bindings.length(), 20)) {
                    dev.wind.agent.webprovider.WebConversationBinding.fromJson(bindings.optJSONObject(i))?.let { chat.webBindings[it.providerId] = it }
                }
            }
            chat.webBinding?.let { chat.webBindings[it.providerId] = it }
            json.optJSONArray("messages")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val legacyId = "msg_" + java.util.UUID.nameUUIDFromBytes(
                        (chat.id + "\u0000" + i + "\u0000" + item.optString("role") + "\u0000" + item.optString("content")).toByteArray(Charsets.UTF_8))
                    chat.messages.add(Message.fromJson(item, legacyId))
                }
            }
            return chat
        }
    }
}
