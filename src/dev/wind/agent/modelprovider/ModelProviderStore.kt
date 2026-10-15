package dev.wind.agent.modelprovider

import android.content.Context
import dev.wind.agent.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** All instances share a lock: model discovery, settings and refresh cannot lose each other's writes. */
class ModelProviderStore internal constructor(private val sp: android.content.SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("model_providers_v1", Context.MODE_PRIVATE))
    fun all(): List<ModelProvider> = synchronized(lock) {
        val a = JSONArray(sp.getString("providers", "[]"))
        (0 until a.length()).map { ModelProvider.parse(a.getJSONObject(it)) }
    }
    fun find(id: String) = all().firstOrNull { it.id == id }
    fun active() = find(sp.getString("active", "") ?: "")
    fun hasAccounts() = all().isNotEmpty()
    fun put(p: ModelProvider) = synchronized(lock) {
        val next = all().toMutableList(); val i = next.indexOfFirst { it.id == p.id }
        if (i < 0) next.add(p) else next[i] = p
        check(sp.edit().putString("providers", JSONArray(next.map { it.json() }).toString()).commit()) { "配置保存失败" }
    }
    fun select(id: String, model: String, effort: String = "default") = synchronized(lock) {
        val p = find(id) ?: error("供应商已删除")
        check(p.models.any { it.id == model }) { "请先读取或添加模型" }
        val next = p.copy(model = model)
        check(ReasoningCapabilities.valid(next, effort)) { "此模型不支持该推理选项" }
        put(next.copy(effort = effort))
        check(sp.edit().putString("active", id).commit()) { "选择保存失败" }
    }
    fun delete(id: String) = synchronized(lock) {
        val next = all().filter { it.id != id }
        val edit = sp.edit().putString("providers", JSONArray(next.map { it.json() }).toString()).remove("secret_$id")
        if (active()?.id == id) edit.remove("active")
        check(edit.commit()); SecureStore.clearSessionPrefix("provider_${id}_")
    }
    fun secret(id: String): JSONObject = synchronized(lock) {
        val slot = "provider_${id}_credentials"
        val encrypted = sp.getString("secret_$id", "") ?: ""
        val text = if (encrypted.isNotEmpty()) SecureStore.decrypt(encrypted, slot) else SecureStore.getSessionKey(slot)
        if (text.isNullOrBlank()) JSONObject() else JSONObject(text)
    }
    /** One encrypted token-set record makes rotating refresh updates atomic. */
    fun saveSecret(id: String, value: JSONObject) = synchronized(lock) {
        val enc = SecureStore.encrypt(value.toString(), "provider_${id}_credentials")
        val edit = sp.edit()
        if (SecureStore.encrypted(enc)) edit.putString("secret_$id", enc) else edit.remove("secret_$id")
        check(edit.commit()) { "凭据保存失败" }
    }
    fun protected(id: String) = SecureStore.encrypted(sp.getString("secret_$id", ""))
    fun configured(p: ModelProvider? = active()): Boolean = p != null && p.model.isNotBlank() &&
        secret(p.id).optString(if (p.subscription) "access_token" else "api_key").isNotBlank()
    fun hostId(): String = synchronized(lock) {
        sp.getString("host_id", null) ?: ("urn:uuid:" + UUID.randomUUID()).also {
            check(sp.edit().putString("host_id", it).commit())
        }
    }
    /** Reads only legacy local preferences; preserve the original key/router until migration succeeds. */
    fun migrateLegacy(context: Context) = migrateLegacy(context.applicationContext.getSharedPreferences("vepro_prefs", Context.MODE_PRIVATE))
    internal fun migrateLegacy(old: android.content.SharedPreferences) = synchronized(lock) {
        if (sp.getBoolean("migrated", false)) return@synchronized
        val enc = old.getString("api_key_enc", "") ?: ""
        val key = if (enc.isNotBlank()) SecureStore.decrypt(enc, SecureStore.SLOT_MODEL) else SecureStore.getSessionKey(SecureStore.SLOT_MODEL)
        val keys = mutableListOf<String>()
        if (!key.isNullOrBlank()) keys.add(key)
        val router = JSONArray(old.getString("api_keys_enc", "[]"))
        for (i in 0 until router.length()) {
            val k = SecureStore.decrypt(router.optString(i), "router_$i")
            if (!k.isNullOrBlank() && k !in keys) keys.add(k)
        }
        // Empty legacy credentials can be entered later; don't manufacture an account.
        if (keys.isEmpty()) return@synchronized
        val url = old.getString("base_url", "https://api.openai.com/v1") ?: ""
        val model = old.getString("model", "gpt-4o") ?: ""
        val proto = dev.wind.agent.LlmClient.resolveProtocol(old.getString("provider", "auto"), url, model)
        val preset = ProviderPresets.all.firstOrNull { it.url == url.trimEnd('/') && !it.id.contains("chatgpt") }?.id ?: "custom"
        // Existing fallback credentials become independent accounts rather than leaking into another endpoint.
        keys.forEachIndexed { i, k ->
            val p = ModelProvider(id = "legacy-$i", preset = preset, label = "原有 API ${i+1}", url = url, protocol = proto,
                model = model, models = listOf(DiscoveredModel(model, efforts = ReasoningCapabilities.efforts(preset, model))))
            saveSecret(p.id, JSONObject().put("api_key", k)); put(p)
            if (i == 0 && active() == null) select(p.id, model)
        }
        check(sp.edit().putBoolean("migrated", true).commit())
    }
    companion object { private val lock = Any() }
}
