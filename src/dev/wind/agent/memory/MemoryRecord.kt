package dev.wind.agent.memory

import dev.wind.agent.Message
import dev.wind.agent.profile.MessageOrigin
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

enum class MemoryScope { PERSONAL, PROJECT, TASK }
data class MemorySource(val chatId: String, val messageId: String, val revision: Long, val digest: String,
                        val start: Int = -1, val end: Int = -1, val spanDigest: String = "", val rolling: Long = 0L) {
    val hasSpan get()=start>=0 && end>start
    fun legacyIdentity()=MemoryRules.hash("$chatId:$messageId:legacy-forgotten")
    fun fingerprint() = if(hasSpan)MemoryRules.hash("$chatId:$messageId:evidence:$spanDigest") else MemoryRules.hash("$chatId:$messageId:$revision:$digest")
    fun json() = JSONObject().put("chatId", chatId).put("messageId", messageId).put("revision", revision).put("digest", digest).also {
        if(hasSpan)it.put("start",start).put("end",end).put("spanDigest",spanDigest).put("rolling",rolling)
    }
    companion object {
        fun from(chatId: String, message: Message, evidence: String? = null) = synchronized(message) {
            val content=message.content
            val base=MemorySource(chatId,message.messageId,message.sourceRevision,MemoryRules.hash(content))
            if(evidence==null)base else {
                val start=content.indexOf(evidence);require(start>=0 && evidence.isNotBlank() && evidence.length<=2000) { "MEMORY_EVIDENCE_NOT_IN_USER_MESSAGE" }
                base.copy(start=start,end=start+evidence.length,spanDigest=MemoryRules.hash(evidence),rolling=MemoryRedactions.rolling(evidence))
            }
        }
        fun parse(j: JSONObject) = MemorySource(j.getString("chatId"), j.getString("messageId"), j.getLong("revision"), j.getString("digest"),
            j.optInt("start",-1),j.optInt("end",-1),j.optString("spanDigest"),j.optLong("rolling")).also {
            require(MemoryRules.id(it.chatId) && MemoryRules.id(it.messageId) && it.revision > 0 && it.digest.matches(Regex("[a-f0-9]{64}")))
            require(if(it.hasSpan)it.end.toLong()-it.start in 1L..2000L && it.end<Int.MAX_VALUE && it.spanDigest.matches(Regex("[a-f0-9]{64}")) && j.has("rolling") else it.start == -1 && it.end == -1 && it.spanDigest.isEmpty())
        }
    }
}
data class MemoryRecord(
    val id: String, val namespace: String, val scope: MemoryScope, val scopeKey: String,
    val key: String, val summary: String, val revision: Long, val generation: Long,
    val status: String, val affinity: String, val updatedAt: Long, val expiresAt: Long = 0,
    val sources: List<MemorySource> = emptyList(), val origin: String = "user_managed"
) {
    fun json() = JSONObject().put("id", id).put("namespace", namespace).put("scope", scope.name).put("scopeKey", scopeKey)
        .put("key", key).put("summary", summary).put("revision", revision).put("generation", generation).put("status", status)
        .put("affinity", affinity).put("updatedAt", updatedAt).put("expiresAt", expiresAt).put("origin", origin)
        .put("sources", JSONArray(sources.map { it.json() }))
    companion object {
        fun parse(j: JSONObject): MemoryRecord {
            val a = j.getJSONArray("sources"); require(a.length() <= 16)
            return MemoryRecord(j.getString("id"), j.getString("namespace"), MemoryScope.valueOf(j.getString("scope")), j.getString("scopeKey"),
                j.getString("key"), j.getString("summary"), j.getLong("revision"), j.getLong("generation"), j.getString("status"),
                j.getString("affinity"), j.getLong("updatedAt"), j.optLong("expiresAt"), (0 until a.length()).map { MemorySource.parse(a.getJSONObject(it)) }, j.optString("origin", "imported")).also {
                require(MemoryRules.id(it.id) && MemoryRules.id(it.namespace) && it.revision in 1 until Long.MAX_VALUE && it.generation in 1 until Long.MAX_VALUE)
                MemoryRules.validate(it.scope, it.scopeKey, it.key, it.summary)
                require(it.status in listOf("ACTIVE", "CANDIDATE") && it.affinity in listOf("GENERAL", "CODE", "MATE") && it.expiresAt >= 0)
            }
        }
    }
}

/** Content rules are local policy. A model confidence score never grants trust or authority. */
object MemoryRules {
    const val MAX_RECORDS = 10000
    /** Scope qualifiers belong to the user's clause, even when a model quotes only its value. */
    fun evidenceContext(text: String,start: Int,end: Int): String {
        require(start>=0 && end>start && end<=text.length)
        val separators="\n。！？!?；;，,".toCharArray()
        val left=text.lastIndexOfAny(separators,start-1)+1
        val right=text.indexOfAny(separators,end).takeIf {it>=0} ?: text.length
        return text.substring(left,right)
    }
    fun projectFact(kind: String,evidence: String) = kind=="project" ||
        Regex("(?:这个|本|当前|该|我们(?:的)?)项目(?:\\s|的|使用|采用|用|偏好|要求|配置|构建|开发)|(?:^|[。；;\\n])\\s*项目\\s*(?:使用|采用|用|要求|配置|构建|开发)|\\b(?:our|this|current|the)\\s+project\\b",RegexOption.IGNORE_CASE).containsMatchIn(evidence)
    fun id(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,160}"))
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun validate(scope: MemoryScope, scopeKey: String, key: String, text: String) {
        require(if (scope == MemoryScope.PERSONAL) scopeKey == "" else id(scopeKey)) { "MEMORY_SCOPE_BINDING_REQUIRED" }
        require(key.matches(Regex("[a-z0-9][a-z0-9._-]{0,95}"))) { "MEMORY_KEY_INVALID" }
        require(text.isNotBlank() && text.length <= 2000 && !text.contains('\u0000')) { "MEMORY_TEXT_LIMIT" }
        require(!secret(text)) { "MEMORY_SECRET_REJECTED" }
    }
    fun secret(text: String): Boolean = Regex("(?i)(sk-[a-z0-9_-]{16,}|-----BEGIN .*PRIVATE KEY|bearer\\s+[a-z0-9._-]{16,}|(?:api[_ -]?key|password|passwd|密码|验证码|cookie|access[_ -]?token)\\s*(?:[:：=]|是|为|(?:is)\\b)\\s*\\S+)").containsMatchIn(text)
    fun sensitive(text: String) = Regex("住址|身份证|病史|诊断|过敏|用药|疾病|银行卡|账户余额|健康|medical|allerg|medication|home address|bank account", RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun refused(text: String) = Regex("(?:不要|别|无需|不用|不必|不想|不允许).{0,10}(?:记住|保存|记下来)|(?:do not|don't|never)\\s+(?:remember|save)",RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun explicit(text: String) = Regex("记住|记下来|长期保存|保存.*偏好|remember|save.*preference", RegexOption.IGNORE_CASE).containsMatchIn(text) && !refused(text)
    fun temporary(text: String) = Regex("这次|本次|此次|今天|明天|本周|此次行程|这趟|本趟|for this (?:task|trip)|this time|today|tomorrow",RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun quotedOrOtherPerson(text: String) = Regex("他说|她说|对方|朋友的|用户说|客户说|引文|引用|例句|[「『“\"]|(?:he|she|they) said|quote",RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun direct(m: Message) = m.role == "user" && !m.modelOnly && !m.contextOnly && m.effectiveOrigin() == MessageOrigin.USER_DIRECT
    fun terms(text: String): Set<String> {
        val normalized = text.lowercase(Locale.ROOT).take(12000)
        val out = linkedSetOf<String>()
        Regex("[a-z0-9_]+|[\\p{IsHan}]+").findAll(normalized).forEach { m ->
            val s = m.value
            if (s.first().code < 128) { if (s.length > 1) out.add(s.take(80)) }
            else { for (n in 2..3) if (s.length >= n) for (i in 0..s.length-n) out.add(s.substring(i, i+n)); if(s.length==1)out.add(s) }
        }
        return out.take(512).toSet()
    }
    fun compatible(r: MemoryRecord, project: String?, task: String?, now: Long) = r.status == "ACTIVE" &&
        (r.expiresAt == 0L || r.expiresAt > now) && when(r.scope) {
            MemoryScope.PERSONAL -> true
            MemoryScope.PROJECT -> project != null && r.scopeKey == project
            MemoryScope.TASK -> task != null && r.scopeKey == task
        }
}
