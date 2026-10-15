package dev.wind.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Every user setting, backed by SharedPreferences. Secrets go through
 * [SecureStore] so nothing sensitive is ever stored in plaintext.
 */
class Prefs(context: Context, private val modelFrozen: Boolean = false,
            private val frozenProvider: dev.wind.agent.modelprovider.ModelProvider? = null) {

    private val appContext = context.applicationContext
    val modelProviders = dev.wind.agent.modelprovider.ModelProviderStore(appContext)
    init { if (!modelFrozen) modelProviders.migrateLegacy(appContext) }
    fun selectedModelProvider() = if (modelFrozen) frozenProvider else modelProviders.active()
    fun freezeModel(): Prefs = if (modelFrozen) this else Prefs(appContext, true, selectedModelProvider())

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        if (!sp.contains("default_agent_profile")) {
            val existing = sp.all.isNotEmpty() ||
                java.io.File(appContext.filesDir, "chats").listFiles()?.any { it.name.endsWith(".json") } == true
            sp.edit().putString("default_agent_profile", if (existing) "code" else "mate").commit()
        }
        // Capture legacy unified network setting once. Subsequent toggles are independent.
        if (!sp.contains("network_features_v2")) {
            val legacy = sp.getBoolean(Tools.ToolNames.WEB_SEARCH, true)
            sp.edit().putBoolean("web_fetch_enabled", legacy).putBoolean("browser_enabled", legacy)
                .putBoolean("network_features_v2", true).commit()
        }
    }
    fun defaultAgentProfile() = dev.wind.agent.profile.AgentProfile.parse(sp.getString("default_agent_profile", "code"))
    fun setDefaultAgentProfile(value: dev.wind.agent.profile.AgentProfile) { sp.edit().putString("default_agent_profile", value.key).apply() }
    fun webFetch(): Boolean = sp.getBoolean("web_fetch_enabled", true)
    fun setWebFetch(value: Boolean) { sp.edit().putBoolean("web_fetch_enabled", value).apply() }
    fun browserEnabled(): Boolean = sp.getBoolean("browser_enabled", true)
    fun setBrowserEnabled(value: Boolean) { sp.edit().putBoolean("browser_enabled", value).apply() }

    // ---- language & appearance ----------------------------------------------
    /** Conservative explicit budget, independent of model-name guesses. */
    fun contextWindowTokens(): Int = (if (sp.contains("context_window_tokens")) sp.getInt("context_window_tokens", 65536)
        else selectedModelProvider()?.selected()?.inputTokenLimit?.takeIf { it > 0 } ?: 65536).coerceIn(8192, 2000000)
    fun contextWindowSource(): String = if (sp.contains("context_window_tokens")) "手动设置" else
        if ((selectedModelProvider()?.selected()?.inputTokenLimit ?: 0) > 0) "模型接口报告" else "默认预算（模型未报告容量）"
    fun setContextWindowTokens(value: Int) { sp.edit().putInt("context_window_tokens", value.coerceIn(8192, 2000000)).apply() }
    fun autoCompact(): Boolean = sp.getBoolean("auto_compact", true)
    fun setAutoCompact(value: Boolean) { sp.edit().putBoolean("auto_compact", value).apply() }

    /**
     * The interface language: "fa" or "en".
     *
     * Defaults to English rather than Persian. The first launch asks, and until
     * it has been answered the app has to be readable by someone who may read
     * either — English is the safer default for a screen whose only job is to
     * offer the choice.
     */
    fun language(): String {
        val stored = sp.getString("language", "zh") ?: "zh"
        return if (stored == "zh" || stored == "en" || stored == "fa") stored else "zh"
    }

    fun setLanguage(value: String?) {
        val safe = if (value == "zh" || value == "en" || value == "fa") value else "zh"
        sp.edit().putString("language", safe).apply()
    }

    fun languageChosen(): Boolean = sp.getBoolean("language_chosen", false)

    fun setLanguageChosen() {
        // commit(), not apply(): this is written immediately before the whole UI
        // is torn down and rebuilt, and it must survive an OEM killing the
        // process in that window — otherwise the chooser asks again.
        sp.edit().putBoolean("language_chosen", true).commit()
    }

    fun themeMode(): String = sp.getString("theme_mode", THEME_SYSTEM) ?: THEME_SYSTEM

    fun setThemeMode(value: String?) {
        sp.edit().putString("theme_mode", value).apply()
    }

    // ---- endpoint ----------------------------------------------------------

    fun baseUrl(): String = selectedModelProvider()?.url ?: (sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)

    fun setBaseUrl(value: String?) {
        sp.edit().putString("base_url", value).apply()
    }

    fun model(): String = selectedModelProvider()?.model ?: (sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)

    fun setModel(value: String?) {
        sp.edit().putString("model", value).apply()
    }

    fun provider(): String = selectedModelProvider()?.let { it.selected().protocol.ifBlank { it.protocol } } ?: (sp.getString("provider", PROV_AUTO) ?: PROV_AUTO)

    fun setProvider(value: String?) {
        sp.edit().putString("provider", value).apply()
    }

    /** Runtime selection preference; an existing durable binding always wins. */
    fun runtimePreference(): String {
        val stored = sp.getString("runtime_preference", RUNTIME_SELF_CONTAINED) ?: RUNTIME_SELF_CONTAINED
        return when (stored) {
            RUNTIME_TERMUX, RUNTIME_SELF_CONTAINED, RUNTIME_AUTO -> stored
            else -> RUNTIME_TERMUX
        }
    }

    fun setRuntimePreference(value: String?) {
        val safe = when (value) {
            RUNTIME_TERMUX, RUNTIME_SELF_CONTAINED, RUNTIME_AUTO -> value
            else -> RUNTIME_TERMUX
        }
        sp.edit().putString("runtime_preference", safe).apply()
    }

    // ---- credentials -------------------------------------------------------

    fun apiKey(): String {
        selectedModelProvider()?.let { p -> return modelProviders.secret(p.id).optString(if(p.subscription) "access_token" else "api_key") }
        val encrypted = sp.getString("api_key_enc", null)
        val dec = if (encrypted != null) SecureStore.decrypt(encrypted, SecureStore.SLOT_MODEL) else ""
        return if (dec.isNotEmpty()) dec else SecureStore.getSessionKey(SecureStore.SLOT_MODEL)
    }

    fun setApiKey(value: String?): Boolean {
        val raw = value ?: ""
        val enc = SecureStore.encrypt(raw, SecureStore.SLOT_MODEL)
        val edit = sp.edit()
        // Invariant 11: Only ciphertext encrypted by Keystore is written to disk.
        // Session-only keys are kept in RAM and never persisted.
        if (enc.isNotEmpty() && SecureStore.encrypted(enc)) {
            edit.putString("api_key_enc", enc)
        } else {
            edit.remove("api_key_enc")
        }
        return edit.remove("api_key").commit()
    }

    /**
     * True when the stored key is protected by the hardware keystore.
     *
     * False means it is saved but in the clear, because this device's keystore could
     * not be used — see [SecureStore]. Settings surfaces this; nothing else changes
     * behaviour on it.
     */
    fun apiKeyIsEncrypted(): Boolean = SecureStore.encrypted(sp.getString("api_key_enc", null))

    /**
     * Key Router: the user's fallback API keys, each AES-GCM encrypted at rest
     * exactly like the primary key. Capped at [MAX_ROUTER_KEYS].
     */
    fun apiKeys(): MutableList<String> {
        if (selectedModelProvider() != null || modelProviders.hasAccounts()) return mutableListOf()
        val out = mutableListOf<String>()
        val raw = sp.getString("api_keys_enc", "[]")
        try {
            val array = JSONArray(raw ?: "[]")
            for (i in 0 until array.length()) {
                val item = array.optStr(i, "")
                val decrypted = SecureStore.decrypt(item, "router_$i")
                if (decrypted.isNotBlankJava()) {
                    out.add(decrypted.trimJava())
                }
            }
        } catch (ignored: Exception) {
        }
        if (out.isEmpty()) {
            for (i in 0 until MAX_ROUTER_KEYS) {
                val sessionVal = SecureStore.getSessionKey("router_$i")
                if (sessionVal.isNotBlankJava()) {
                    out.add(sessionVal.trimJava())
                }
            }
        }
        return out
    }

    fun setApiKeys(keys: List<String>?): Boolean {
        SecureStore.clearSessionPrefix("router_")
        val array = JSONArray()
        if (keys != null) {
            for ((i, key) in keys.withIndex()) {
                if (key.isBlankJava()) {
                    continue
                }
                val enc = SecureStore.encrypt(key.trimJava(), "router_$i")
                if (enc.isNotEmpty() && SecureStore.encrypted(enc)) {
                    array.put(enc)
                }
            }
        }
        return sp.edit().putString("api_keys_enc", array.toString()).commit()
    }

    fun workspaceUri(): String? = sp.getString("vega_saf_workspace_uri", null)

    fun setWorkspaceUri(value: String?) {
        sp.edit().putString("vega_saf_workspace_uri", value).apply()
    }

    /** Adds a key; refuses blanks, duplicates and growth past the cap. */
    fun addApiKey(key: String?): Boolean {
        val candidate = key?.trimJava() ?: return false
        if (candidate.isEmpty()) {
            return false
        }
        val keys = apiKeys()
        if (keys.size >= MAX_ROUTER_KEYS || keys.contains(candidate)) {
            return false
        }
        keys.add(candidate)
        return setApiKeys(keys)
    }

    fun removeApiKey(index: Int): Boolean {
        val keys = apiKeys()
        if (index < 0 || index >= keys.size) {
            return false
        }
        keys.removeAt(index)
        val cursor = routerIndex()
        if (cursor >= keys.size) {
            setRouterIndex(0)
        } else if (index < cursor) {
            setRouterIndex(cursor - 1)
        }
        return setApiKeys(keys)
    }

    /**
     * Sticky rotation cursor: survives process restarts so a rate-limited key
     * is not retried first on the next run.
     */
    fun routerIndex(): Int = sp.getInt("router_index", 0)

    fun setRouterIndex(value: Int) {
        sp.edit().putInt("router_index", maxOf(0, value)).apply()
    }

    // ---- agent behaviour ---------------------------------------------------

    /**
     * The active run mode, always one of [MODE_ACCEPT] / [MODE_PLAN] /
     * [MODE_AUTO].
     *
     * Validated on the way out as well as in, because AUTO is the `else` branch
     * of every dispatch: an unrecognised value stored by an older build — or by
     * a corrupted preferences file — silently granted the agent unrestricted
     * tool access. Falling back to ACCEPT fails closed instead.
     */
    fun developerAutomaticModeAllowed(): Boolean = sp.getBoolean("dev_allow_auto_mode", false)

    fun setDeveloperAutomaticModeAllowed(value: Boolean) {
        sp.edit().putBoolean("dev_allow_auto_mode", value).apply()
    }

    fun mode(): String {
        val stored = sp.getString("mode", MODE_ACCEPT) ?: MODE_ACCEPT
        if (stored == MODE_AUTO && !developerAutomaticModeAllowed()) {
            return MODE_ACCEPT
        }
        return if (isValidMode(stored)) stored else MODE_ACCEPT
    }

    fun setMode(value: String?) {
        val safe = if (isValidMode(value)) value else MODE_ACCEPT
        sp.edit().putString("mode", safe).apply()
    }

    fun temperature(): Float = sp.getFloat("temp", 0.7f)

    fun setTemperature(value: Float) {
        sp.edit().putFloat("temp", value).apply()
    }

    fun maxTokens(): Int = sp.getInt("max_tokens", 10000)

    fun setMaxTokens(value: Int) {
        sp.edit().putInt("max_tokens", value).apply()
    }

    fun thinkingLevel(): String = selectedModelProvider()?.effort ?: (sp.getString("think_level", "medium") ?: "medium")

    fun setThinkingLevel(value: String?) {
        sp.edit().putString("think_level", value).apply()
    }

    fun thinkingBudgetForLevel(): Int = thinkingBudgetForLevel(effectiveThinkingLevel())

    fun thinkingEnabled(): Boolean = true

    /**
     * Response inactivity timeout in seconds — how long to wait for the NEXT
     * byte from the model before giving up. A streaming answer resets it
     * continuously, so this is not a limit on how long an answer may take.
     *
     * Clamped on read as well as on write, so a value written by an older build
     * (or hand-edited) can never hang the app or make every request fail.
     */
    fun timeoutSeconds(): Int {
        val stored = sp.getInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, stored))
    }

    fun setTimeoutSeconds(value: Int) {
        val clamped = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, value))
        sp.edit().putInt("timeout_seconds", clamped).apply()
    }

    /**
     * Dynamic Workflow: the agent plans a multi-step task up front and delegates
     * each step to a focused sub-agent, the way Claude Code does, instead of
     * carrying one ever-growing context through the whole job.
     *
     * Off by default — it deliberately spends more tokens and more steps in
     * exchange for staying coherent on long tasks.
     */
    fun dynamicWorkflow(): Boolean = sp.getBoolean("dynamic_workflow", false)

    fun setDynamicWorkflow(value: Boolean) {
        sp.edit().putBoolean("dynamic_workflow", value).apply()
    }

    /**
     * The reasoning level actually used for a run.
     *
     * With Dynamic Workflow on, effort is raised to a floor of XHIGH: the mode
     * only pays off when each sub-agent genuinely decomposes and verifies its
     * step. It is NOT forced to MAX — max is a budget-burning ceiling meant for
     * one-off deep dives, and applying it to every sub-agent in a long workflow
     * is slower and more expensive without being better. A user who has already
     * chosen MAX keeps it.
     */
    fun effectiveThinkingLevel(): String {
        val chosen = thinkingLevel()
        if (selectedModelProvider() != null) return chosen
        if (!dynamicWorkflow()) {
            return chosen
        }
        return if (chosen == "max") "max" else "xhigh"
    }

    fun webSearch(): Boolean = sp.getBoolean(Tools.ToolNames.WEB_SEARCH, true)

    fun setWebSearch(value: Boolean) {
        sp.edit().putBoolean(Tools.ToolNames.WEB_SEARCH, value).apply()
    }

    // ---- search provider settings ------------------------------------------

    fun searchProvider(): String = sp.getString("search_provider", "auto") ?: "auto"

    fun setSearchProvider(value: String) {
        require(value in setOf("auto", "brave", "tavily", "searxng", "duckduckgo")) { "SEARCH_PROVIDER_INVALID" }
        sp.edit().putString("search_provider", value).apply()
    }

    fun tavilyApiKey(): String {
        val enc = sp.getString("tavily_api_key_enc", null)
        val dec = if (enc != null) SecureStore.decrypt(enc, SecureStore.SLOT_TAVILY) else ""
        return dec.ifEmpty { SecureStore.getSessionKey(SecureStore.SLOT_TAVILY) }
    }

    fun setTavilyApiKey(value: String): Boolean {
        SecureStore.clearSessionKey(SecureStore.SLOT_TAVILY)
        val enc = SecureStore.encrypt(value.trimJava(), SecureStore.SLOT_TAVILY)
        val edit = sp.edit()
        if (enc.isNotEmpty() && SecureStore.encrypted(enc)) edit.putString("tavily_api_key_enc", enc)
        else edit.remove("tavily_api_key_enc")
        return edit.commit()
    }

    fun braveApiKey(): String {
        val enc = sp.getString("brave_api_key_enc", null)
        val dec = if (enc != null) SecureStore.decrypt(enc, SecureStore.SLOT_BRAVE) else ""
        return if (dec.isNotEmpty()) dec else SecureStore.getSessionKey(SecureStore.SLOT_BRAVE)
    }

    fun setBraveApiKey(value: String): Boolean {
        SecureStore.clearSessionKey(SecureStore.SLOT_BRAVE)
        val enc = SecureStore.encrypt(value.trimJava(), SecureStore.SLOT_BRAVE)
        val edit = sp.edit()
        if (enc.isNotEmpty() && SecureStore.encrypted(enc)) {
            edit.putString("brave_api_key_enc", enc)
        } else {
            edit.remove("brave_api_key_enc")
        }
        return edit.commit()
    }

    fun searxngBaseUrl(): String = sp.getString("searxng_base_url", "") ?: ""

    fun setSearxngBaseUrl(value: String) {
        sp.edit().putString("searxng_base_url", value.trimJava()).apply()
    }

    fun searchFallbackEnabled(): Boolean = sp.getBoolean("search_fallback_enabled", true)

    fun setSearchFallbackEnabled(value: Boolean) {
        sp.edit().putBoolean("search_fallback_enabled", value).apply()
    }

    fun searchTimeoutSeconds(): Int = sp.getInt("search_timeout_sec", 12)

    fun setSearchTimeoutSeconds(value: Int) {
        val clamped = Math.max(5, Math.min(30, value))
        sp.edit().putInt("search_timeout_sec", clamped).apply()
    }

    /**
     * Whether the model's own tools may reach private/loopback addresses.
     * Off by default: the user's configured endpoint is always allowed
     * regardless, so this only governs URLs the model itself chose.
     */
    fun allowLocalNetwork(): Boolean = sp.getBoolean("allow_local_net", false)

    fun setAllowLocalNetwork(value: Boolean) {
        sp.edit().putBoolean("allow_local_net", value).apply()
        NetworkPolicy.allowLocalNetwork = value
    }

    fun allowLanModel(): Boolean = sp.getBoolean("allow_lan_model", false)

    fun setAllowLanModel(value: Boolean) {
        sp.edit().putBoolean("allow_lan_model", value).apply()
        NetworkPolicy.allowLanModelEndpoint = value
    }

    fun systemPrompt(): String = sp.getString("sys_prompt", "") ?: ""

    fun setSystemPrompt(value: String?) {
        sp.edit().putString("sys_prompt", value).apply()
    }

    // ---- session bookkeeping ----------------------------------------------

    fun lastChatId(): String = sp.getString("last_chat", "") ?: ""

    fun setLastChatId(value: String?) {
        sp.edit().putString("last_chat", value).apply()
    }

    /** Whether we've already offered the battery-optimization exemption. */
    fun batteryPromptShown(): Boolean = sp.getBoolean("batt_prompt_shown", false)

    fun setBatteryPromptShown(value: Boolean) {
        sp.edit().putBoolean("batt_prompt_shown", value).apply()
    }

    /** Wipes every preference back to factory defaults (used by settings reset). */
    fun clearAll() {
        sp.edit().clear().commit()
    }

    // ---- derived -----------------------------------------------------------

    fun isConfigured(): Boolean = if (modelProviders.hasAccounts()) modelProviders.configured(selectedModelProvider()) else
        baseUrl().isNotBlankJava() && model().isNotBlankJava() &&
            (apiKey().isNotBlankJava() || apiKeys().isNotEmpty())

    fun isAnthropic(): Boolean =
        LlmClient.PROTOCOL_ANTHROPIC == LlmClient.resolveProtocol(provider(), baseUrl(), model())

    fun isGemini(): Boolean =
        LlmClient.PROTOCOL_GEMINI == LlmClient.resolveProtocol(provider(), baseUrl(), model())

    companion object {
        private const val FILE = "vepro_prefs"

        const val MAX_ROUTER_KEYS = 50

        /**
         * Response inactivity timeout bounds, in seconds. The floor keeps a
         * slow-but-working endpoint from being cut off mid-thought; the ceiling
         * (30 min) keeps a dead connection from pinning the run forever.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MIN_TIMEOUT_SECONDS = 10
        const val MAX_TIMEOUT_SECONDS = 1800

        const val MODE_ACCEPT = "accept"
        const val MODE_AUTO = "auto"
        const val MODE_PLAN = "plan"

        fun isValidMode(value: String?): Boolean =
            MODE_ACCEPT == value || MODE_AUTO == value || MODE_PLAN == value

        const val PROV_ANTHRO = "anthropic"
        const val PROV_AUTO = "auto"
        const val PROV_GEMINI = "gemini"
        const val PROV_OPENAI = "openai"

        const val RUNTIME_TERMUX = "termux"
        const val RUNTIME_SELF_CONTAINED = "self_contained"
        const val RUNTIME_AUTO = "auto"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val DEFAULT_MODEL = "gpt-4o"

        /**
         * Provider adapters clamp these preference budgets to each API/model's
         * supported range; keep the tiers monotonic and useful to Anthropic.
         */
        internal fun thinkingBudgetForLevel(level: String?): Int = when (level) {
            "low" -> 2048
            "medium" -> 8000
            "high" -> 16000
            "xhigh" -> 32000
            "max" -> 60000
            else -> 8000
        }
    }
}
