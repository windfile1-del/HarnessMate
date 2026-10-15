package dev.wind.agent.browser

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** The model receives typed commands, never JavaScript, cookies or a raw debugging connection. */
object BrowserProtocol {
    val networkScopes = setOf(dev.wind.agent.capability.GrantScope.TASK, dev.wind.agent.capability.GrantScope.APP_SESSION)
    val tools = setOf("browser_session", "browser_navigate", "browser_observe", "browser_action", "browser_wait", "browser_tabs", "browser_takeover", "browser_upload", "browser_video")
    private val actions = setOf("click", "fill", "select", "press", "scroll")
    fun isMutating(name: String, a: JSONObject): Boolean = when (name) {
        "browser_session" -> a.optString("mode", "list") !in setOf("list", "status")
        "browser_tabs" -> a.optString("mode", "list") != "list"
        "browser_observe", "browser_wait", "browser_video" -> false
        else -> name in tools
    }
    fun publicUrl(raw: String): String {
        require(raw.length in 1..8192 && raw.none { it.isWhitespace() || it.code < 32 }) { "BROWSER_URL_INVALID" }
        val u = URI(raw)
        val host = u.host?.lowercase()?.trimEnd('.') ?: error("BROWSER_URL_INVALID")
        require(u.scheme.equals("https", true) && u.rawUserInfo == null && (u.port == -1 || u.port == 443)) { "BROWSER_PUBLIC_HTTPS_REQUIRED" }
        require(host.matches(Regex("[a-z0-9.-]+")) && host.contains('.') && !host.startsWith('.') && !host.contains("..")) { "BROWSER_PUBLIC_HOST_REQUIRED" }
        require(host !in setOf("localhost", "metadata.google.internal", "metadata.goog", "instance-data") &&
            !listOf(".localhost", ".local", ".internal", ".lan", ".home", ".test", ".invalid").any(host::endsWith)) { "BROWSER_PRIVATE_HOST_BLOCKED" }
        // Reject numeric addresses entirely, including alternate integer/hex encodings.
        require(!host.matches(Regex("[0-9.]+")) && !host.split('.').all { it.matches(Regex("(?:0x[0-9a-f]+|[0-9]+)")) }) { "BROWSER_IP_LITERAL_BLOCKED" }
        return u.toASCIIString()
    }
    fun host(raw: String): String = URI(publicUrl(raw)).host.lowercase().trimEnd('.')
    fun origin(raw: String): String = "https://" + host(raw)
    /** Location shown to the model excludes authentication query values and fragments. */
    fun displayUrl(raw: String?): String {
        if (raw == null || raw == "about:blank") return "about:blank"
        return try {
            val u = URI(raw)
            val secret = Regex("(?i)(?:token|secret|password|passwd|authorization|access_token|id_token|code|session|sid|csrf|key|credential)")
            val query = u.rawQuery?.split('&')?.filterNot { secret.containsMatchIn(it.substringBefore('=')) }?.joinToString("&")?.takeIf { it.isNotEmpty() }
            URI(u.scheme, null, u.host, u.port, u.path, null, null).toASCIIString() + (query?.let { "?$it" } ?: "")
        } catch (_: Exception) { "[location unavailable]" }
    }
    fun storedLocation(raw: String?): String = try {
        val u = URI(raw ?: ""); URI(u.scheme, null, u.host, u.port, u.path, null, null).toASCIIString()
    } catch (_: Exception) { "about:blank" }
    fun validate(name: String, a: JSONObject) {
        require(name in tools) { "BROWSER_TOOL_UNKNOWN" }
        if (a.has("session_id")) require(a.getString("session_id").matches(Regex("br_[a-f0-9]{32}"))) { "BROWSER_SESSION_ID_INVALID" }
        if (a.has("tab_id")) require(a.getString("tab_id").matches(Regex("tab_[a-f0-9]{16}"))) { "BROWSER_TAB_ID_INVALID" }
        if (a.has("url")) publicUrl(a.getString("url"))
        require(a.optLong("timeout_ms", 5000) in 100..20000) { "BROWSER_TIMEOUT_INVALID" }
        require(a.optInt("max_elements", 120) in 1..200 && a.optInt("max_chars", 12000) in 256..24000) { "BROWSER_OBSERVATION_LIMIT_INVALID" }
        when (name) {
            "browser_session" -> require(a.optString("mode", "list") in setOf("list", "status", "open", "close", "resume", "authorize")) { "BROWSER_SESSION_MODE_INVALID" }
            "browser_tabs" -> require(a.optString("mode", "list") in setOf("list", "new", "switch", "close")) { "BROWSER_TAB_MODE_INVALID" }
            "browser_navigate" -> require(a.has("url")) { "BROWSER_URL_REQUIRED" }
            "browser_takeover" -> require(a.optString("mode", "open") == "open") { "BROWSER_TAKEOVER_USER_RESUME_ONLY" }
            "browser_wait" -> require(a.optString("until", "ready") in setOf("ready", "text", "gone", "url")) { "BROWSER_WAIT_CONDITION_INVALID" }
            "browser_action", "browser_upload" -> {
                require(a.getString("request_id").matches(Regex("[a-zA-Z0-9_-]{8,96}"))) { "BROWSER_REQUEST_ID_REQUIRED" }
                require(a.getString("snapshot_id").matches(Regex("snap_[a-f0-9]{32}"))) { "BROWSER_SNAPSHOT_REQUIRED" }
                if (name == "browser_action") {
                    val action = a.getString("action"); require(action in actions) { "BROWSER_ACTION_INVALID" }
                    if (action != "scroll") require(a.getString("ref").matches(Regex("e[0-9]{1,5}"))) { "BROWSER_ELEMENT_REF_REQUIRED" }
                    if (action in setOf("fill", "select")) require(a.getString("value").length <= 16000) { "BROWSER_INPUT_TOO_LONG" }
                    if (action == "press") require(a.getString("key") in setOf("Enter", "Tab", "Escape")) { "BROWSER_KEY_UNSUPPORTED" }
                    if (action == "scroll") require(a.optString("direction", "down") in setOf("up", "down", "left", "right")) { "BROWSER_SCROLL_DIRECTION_INVALID" }
                } else require(a.getString("ref").matches(Regex("e[0-9]{1,5}"))) { "BROWSER_ELEMENT_REF_REQUIRED" }
            }
        }
    }
    fun digest(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun checkOwner(sessionChat:String,sessionOwner:String?,chat:String,owner:String,ended:Boolean,takeover:Boolean) {
        check(sessionChat==chat){ "BROWSER_CHAT_MISMATCH" }
        check(sessionOwner==owner && !ended){ "BROWSER_SESSION_REBIND_REQUIRED" }
        check(!takeover){ "BROWSER_HUMAN_TAKEOVER" }
    }
    fun fingerprint(a: JSONObject): String = digest(listOf("session_id", "tab_id", "snapshot_id", "ref", "action", "value", "key", "direction", "artifact_uri", "filename", "mime").joinToString("\u0000") { a.optString(it) })
    fun approvalFingerprint(session:String,tab:String,url:String?,a:JSONObject,p:JSONObject):String = digest(session+"\u0000"+tab+"\u0000"+url.orEmpty()+"\u0000"+
        listOf("action","value","key","artifact_uri","filename","mime").joinToString("\u0000"){a.optString(it)}+"\u0000"+
        listOf("name","role","frame_id","href","form_fields").joinToString("\u0000"){p.opt(it)?.toString().orEmpty()})
    fun domains(a: JSONObject): Set<String> {
        val domains = linkedSetOf<String>()
        if (a.has("url")) domains.add(host(a.getString("url")))
        if (a.has("navigation_url")) domains.add(host(a.getString("navigation_url")))
        val list = a.optJSONArray("network_domains") ?: JSONArray()
        require(list.length() <= 32) { "BROWSER_TOO_MANY_DOMAINS" }
        for (i in 0 until list.length()) domains.add(host("https://" + list.getString(i)))
        return domains
    }
}

/** Durable at-most-once receipts. No input values, credentials or page bodies are persisted. */
class BrowserReceipts(private val persist: (JSONObject) -> Unit = {}, saved: JSONObject? = null) {
    private val entries = LinkedHashMap<String, JSONObject>()
    var unresolved: String? = null
        private set
    init {
        val list = saved?.optJSONArray("receipts") ?: JSONArray()
        for (i in 0 until minOf(list.length(), 128)) {
            val r = JSONObject(list.getJSONObject(i).toString())
            if (r.optString("status") in setOf("PREPARED", "SUBMITTING")) r.put("status", "UNKNOWN").put("submitted", JSONObject.NULL)
            entries[r.getString("request_id")] = r
            if (r.optString("status") == "UNKNOWN") unresolved = r.getString("request_id")
        }
    }
    @Synchronized fun replay(id: String, fingerprint: String): JSONObject? {
        val old = entries[id] ?: return null
        check(old.getString("fingerprint") == fingerprint) { "BROWSER_REQUEST_ID_REUSED" }
        return JSONObject(old.toString()).put("replayed", true)
    }
    @Synchronized fun begin(id: String, fingerprint: String) {
        check(unresolved == null) { "BROWSER_UNKNOWN_ACTION_REQUIRES_USER_REVIEW" }
        check(!entries.containsKey(id)) { "BROWSER_REQUEST_ALREADY_EXISTS" }
        check(entries.size < 128) { "BROWSER_ACTION_HISTORY_LIMIT_USE_NEW_SESSION" }
        entries[id] = JSONObject().put("request_id", id).put("fingerprint", fingerprint).put("status", "PREPARED").put("submitted", false)
        save()
    }
    @Synchronized fun submitting(id: String) {
        entries.getValue(id).put("status", "SUBMITTING").put("submitted", JSONObject.NULL); unresolved = id; save()
    }
    @Synchronized fun finish(id: String, status: String, submitted: Boolean?): JSONObject {
        require(status in setOf("DISPATCHED", "VERIFIED_VALUE", "NOT_SUBMITTED", "UNKNOWN"))
        val r = entries.getValue(id).put("status", status).put("submitted", submitted ?: JSONObject.NULL)
        if (status == "UNKNOWN") unresolved = id else if (unresolved == id) unresolved = null
        save(); return JSONObject(r.toString())
    }
    @Synchronized fun reviewedByUser() { unresolved?.let { entries[it]?.put("status", "USER_REVIEWED"); unresolved = null; save() } }
    @Synchronized fun json(): JSONObject = JSONObject().put("receipts", JSONArray(entries.values.map { JSONObject(it.toString()) }))
        .put("unknown_request_id", unresolved ?: JSONObject.NULL)
    private fun save() = persist(json())
}
