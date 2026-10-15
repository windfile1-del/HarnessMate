package dev.wind.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One turn in a conversation, plus anything the user attached to it. */
class Message(var role: String, content: String?) {

    var messageId: String = "msg_" + java.util.UUID.randomUUID()
    @Volatile var sourceRevision: Long = 1L
    var origin = if (role == "user") dev.wind.agent.profile.MessageOrigin.USER_DIRECT
        else if (role == "assistant") dev.wind.agent.profile.MessageOrigin.ASSISTANT
        else if (role == "tool") dev.wind.agent.profile.MessageOrigin.TOOL
        else dev.wind.agent.profile.MessageOrigin.LEGACY_UNKNOWN
    @Volatile var content: String = content ?: ""
        @Synchronized
        set(value) {
            if (field != value) {
                field = value
                sourceRevision = if (sourceRevision < Long.MAX_VALUE) sourceRevision + 1 else Long.MAX_VALUE
            }
        }
    var thinking: String? = ""
    @Volatile var streaming: Boolean = false
    @Volatile var isError: Boolean = false
    var contextOnly: Boolean = false
    var modelOnly: Boolean = false
    var modelHint: String = ""
    val memoryRefs: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    var memoryNamespace: String? = null
    var memoryEpochDependency: Long = 0L
    var memoryProjectDependency: String? = null
    @Volatile var memoryCapture: dev.wind.agent.memory.MemoryCapture? = null
    var memoryHistoryEpoch: Long = 0L
    var legacyMemoryTaint = false
    fun textForModel(): String = content + if (modelHint.isNotEmpty()) "\n\n" + modelHint else ""
    var chatAssistEventId: String? = null
    fun effectiveOrigin(): dev.wind.agent.profile.MessageOrigin = when {
        chatAssistEventId != null -> dev.wind.agent.profile.MessageOrigin.THIRD_PARTY_CHAT
        role == "tool" -> if (toolLog.firstOrNull() == "read_skill") dev.wind.agent.profile.MessageOrigin.SKILL_CONTEXT else dev.wind.agent.profile.MessageOrigin.TOOL
        role == "assistant" -> dev.wind.agent.profile.MessageOrigin.ASSISTANT
        role == "context" -> dev.wind.agent.profile.MessageOrigin.MEMORY_CONTEXT
        modelOnly -> dev.wind.agent.profile.MessageOrigin.PROGRAMMATIC
        else -> origin
    }

    /**
     * The live activity record for the whole request, attached to the FIRST
     * assistant message of a run. Null on every other message.
     */
    var trail: Trail? = null

    /** The Dynamic Workflow board for this run, when the mode is on. */
    var workflow: Workflow? = null

    /**
     * True for an intermediate assistant turn that ended in a tool call — one
     * whose prose is now shown as a phase line inside the run's [Trail] rather
     * than as a bubble of its own.
     *
     * Only ever set by a run that HAS a trail, so a chat saved by an older build
     * keeps rendering every step exactly as before instead of silently losing
     * half its history.
     */
    @Volatile var isStep: Boolean = false

    val attachments: MutableList<Attachment> = java.util.Collections.synchronizedList(mutableListOf())
    // Send-time selection, resolved by the service worker before persistence/model preparation.
    internal var requestedSkillPaths: List<String> = emptyList()
    // Transient screen context belongs to this send only. Never serialized in chat history.
    @Volatile var screenSessionId: Long = 0L
    @Volatile var screenContext: Attachment? = null
    @Volatile var screenContextNote: String? = null
    val toolLog: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    var webRequestId: String? = null
    var webOperation: String = "SEND"
    // Quoted external context is per-send and is not retained in chat JSON.
    val webContexts: MutableList<dev.wind.agent.webprovider.WebContextItem> = mutableListOf()

    /** A user-supplied file riding along with a message. */
    class Attachment(
        var name: String?,
        var path: String?,
        var mime: String?,
        var size: Long,
        var kind: String?
    ) {
        /** Populated lazily for images that get inlined into the request body. */
        var dataUri: String? = null
        /** Snapshot of attached text, so later disk changes cannot rewrite cached history. */
        var textSnapshot: String? = null
    }

    @Throws(JSONException::class)
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("role", role)
        json.put("messageId", messageId)
        json.put("sourceRevision", sourceRevision)
        json.put("origin", effectiveOrigin().name)
        json.put("content", content)
        if (memoryRefs.isNotEmpty()) json.put("memoryRefs", JSONObject(memoryRefs as Map<*, *>))
        memoryNamespace?.let { json.put("memoryNamespace",it) }
        if(memoryEpochDependency>0)json.put("memoryEpochDependency",memoryEpochDependency)
        memoryProjectDependency?.let { json.put("memoryProjectDependency",it) }
        memoryCapture?.let { json.put("memoryCapture",it.json()) }
        if(memoryHistoryEpoch>0)json.put("memoryHistoryEpoch",memoryHistoryEpoch)
        if (legacyMemoryTaint) json.put("legacyMemoryTaint",true)
        if (modelOnly) json.put("modelOnly", true)
        if (modelHint.isNotEmpty()) json.put("modelHint", modelHint)
        json.put("thinking", thinking ?: "")
        json.put("isError", isError)
        if (contextOnly) json.put("contextOnly", true)
        webRequestId?.let { json.put("webRequestId", it) }
        if (webOperation != "SEND") json.put("webOperation", webOperation)
        if (isStep) {
            json.put("isStep", true)
        }
        // Only written when there is something to write, so an ordinary message
        // costs no extra bytes in the chat file.
        trail?.let { json.put("trail", it.toJson()) }
        workflow?.let { json.put("workflow", it.toJson()) }

        val files = JSONArray()
        for (attachment in synchronized(attachments) { attachments.toList() }) {
            val item = JSONObject()
            item.put("name", attachment.name)
            item.put("path", attachment.path ?: "")
            item.put("mime", attachment.mime ?: "")
            item.put("size", attachment.size)
            item.put("kind", attachment.kind ?: "file")
            attachment.textSnapshot?.let { item.put("textSnapshot", it) }
            files.put(item)
        }
        json.put("attachments", files)
        chatAssistEventId?.let { json.put("chatAssistEventId", it) }

        val log = JSONArray()
        for (entry in synchronized(toolLog) { toolLog.toList() }) {
            log.put(entry)
        }
        json.put("toolLog", log)
        return json
    }

    companion object {
        @Throws(JSONException::class)
        fun fromJson(json: JSONObject, legacyId: String? = null): Message {
            val message = Message(json.getString("role"), json.optStr("content", ""))
            message.messageId = json.optString("messageId").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,180}")) }
                ?: legacyId ?: ("msg_" + java.util.UUID.nameUUIDFromBytes(json.toString().toByteArray(Charsets.UTF_8)))
            message.sourceRevision = json.optLong("sourceRevision", 1).coerceAtLeast(1)
            message.origin = dev.wind.agent.profile.MessageOrigin.parse(json.optString("origin"))
            message.thinking = json.optStr("thinking", "")
            message.isError = json.optBoolean("isError", false)
            message.contextOnly = json.optBoolean("contextOnly", false)
            message.modelOnly = json.optBoolean("modelOnly", false)
            message.modelHint = json.optString("modelHint")
            message.legacyMemoryTaint = json.optBoolean("legacyMemoryTaint")
            message.memoryNamespace=json.optString("memoryNamespace").takeIf { dev.wind.agent.memory.MemoryRules.id(it) }
            message.memoryEpochDependency=json.optLong("memoryEpochDependency").coerceAtLeast(0)
            message.memoryProjectDependency=json.optString("memoryProjectDependency").takeIf { dev.wind.agent.memory.MemoryRules.id(it) }
            message.memoryCapture=runCatching { json.optJSONObject("memoryCapture")?.let { dev.wind.agent.memory.MemoryCapture.parse(it) } }.getOrNull()
            message.memoryHistoryEpoch=json.optLong("memoryHistoryEpoch").coerceAtLeast(0)
            json.optJSONObject("memoryRefs")?.let { refs ->
                val keys=refs.keys();var count=0
                while(keys.hasNext() && count++<100) { val id=keys.next();val rev=refs.optLong(id)
                    if(dev.wind.agent.memory.MemoryRules.id(id) && rev>0)message.memoryRefs[id]=rev }
            }
            message.webRequestId = json.optStr("webRequestId", "").takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,180}")) }
            message.webOperation = json.optStr("webOperation", "SEND").takeIf { it in setOf("SEND", "REGENERATE", "EDIT_AND_SEND") } ?: "SEND"
            message.isStep = json.optBoolean("isStep", false)
            message.chatAssistEventId = json.optString("chatAssistEventId").takeIf { it.isNotBlank() }
            message.trail = Trail.fromJson(json.optJSONObject("trail"))
            message.workflow = Workflow.fromJson(json.optJSONObject("workflow"))

            json.optJSONArray("attachments")?.let { files ->
                for (i in 0 until files.length()) {
                    val item = files.getJSONObject(i)
                    val attachment = Attachment(
                        item.optStr("name"),
                        item.optStr("path"),
                        item.optStr("mime"),
                        item.optLong("size"),
                        item.optStr("kind", "file")
                    )
                    // an empty stored path means "no local file any more"
                    if (attachment.path?.isEmpty() == true) {
                        attachment.path = null
                    }
                    message.attachments.add(attachment)
                    attachment.textSnapshot = item.optString("textSnapshot").takeIf { item.has("textSnapshot") }
                }
            }

            json.optJSONArray("toolLog")?.let { log ->
                for (i in 0 until log.length()) {
                    message.toolLog.add(log.getString(i))
                }
            }
            return message
        }
    }
}
