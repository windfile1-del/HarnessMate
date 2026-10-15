package dev.wind.agent.webprovider

import android.content.Context

/** Separate from API credentials. Nothing in this store contains browser login information. */
class WebProviderPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("vega_web_providers_v1", Context.MODE_PRIVATE)
    fun enabled(): Boolean = sp.getBoolean("enabled", false)
    fun usedCompatibility(): Boolean = sp.getBoolean("used_compatibility", false)
    fun usedCompatibility(value: Boolean) { sp.edit().putBoolean("used_compatibility", value).commit() }
    fun setEnabled(value: Boolean) { sp.edit().putBoolean("enabled", value).commit() }
    fun selected(): String = sp.getString("selected", "") ?: ""
    fun select(id: String) { require(id.isEmpty() || WebProviderRegistry.get(id) != null); sp.edit().putString("selected", id).commit() }
    fun acknowledged(id: String): Boolean = sp.getBoolean("ack_$id", false)
    fun acknowledge(id: String) { sp.edit().putBoolean("ack_$id", true).commit() }
    fun disabled(id: String): Boolean = sp.getBoolean("disabled_$id", false)
    fun disable(id: String, value: Boolean) { sp.edit().putBoolean("disabled_$id", value).commit() }
    fun favorite(id: String): Boolean = sp.getBoolean("favorite_$id", false)
    fun favorite(id: String, value: Boolean) { sp.edit().putBoolean("favorite_$id", value).commit() }
    fun hidden(id: String): Boolean = sp.getBoolean("hidden_$id", false)
    fun hide(id: String, value: Boolean) { sp.edit().putBoolean("hidden_$id", value).commit() }
    fun rank(id: String): Int = sp.getInt("rank_$id", 0)
    fun rank(id: String, value: Int) { sp.edit().putInt("rank_$id", value.coerceIn(-1000, 1000)).commit() }
    fun recent(id: String): Long = sp.getLong("recent_$id", 0L)
    fun markRecent(id: String) { sp.edit().putLong("recent_$id", System.currentTimeMillis()).apply() }
    fun status(id: String): String = if (disabled(id)) "DISABLED" else sp.getString("status_$id", "UNVERIFIED") ?: "UNVERIFIED"
    fun status(id: String, state: String) { sp.edit().putString("status_$id", state.take(80)).apply() }
    fun allow(kind: String): Boolean = sp.getBoolean("allow_$kind", false)
    fun allow(kind: String, value: Boolean) { sp.edit().putBoolean("allow_$kind", value).commit() }
    fun confirmSensitive(): Boolean = sp.getBoolean("confirm_sensitive", true)
    fun confirmSensitive(value: Boolean) { sp.edit().putBoolean("confirm_sensitive", value).commit() }
    fun contextLimit(): Int = sp.getInt("context_limit", 24000).coerceIn(4000, 80000)
    fun contextLimit(value: Int) { sp.edit().putInt("context_limit", value.coerceIn(4000, 80000)).commit() }
    fun profile(id: String): String {
        require(WebProviderRegistry.get(id) != null)
        return "vega_web_${id}_${sp.getLong("epoch_$id", 0L)}"
    }
    fun rotateProfile(id: String): String {
        val old = profile(id)
        sp.edit().putLong("epoch_$id", sp.getLong("epoch_$id", 0L) + 1L).putString("status_$id", "NOT_LOGGED_IN").commit()
        return old
    }
    fun runnable(id: String): Boolean {
        val p = WebProviderRegistry.get(id) ?: return false
        return enabled() && acknowledged(id) && !disabled(id) && p.configured && !p.manualOnly
    }
    fun ordered(query: String, showHidden: Boolean): List<WebProviderDefinition> = WebProviderRegistry.all.filter {
        (showHidden || !hidden(it.id)) && (query.isBlank() || (it.name + " " + it.company + " " + it.id).contains(query, true))
    }.sortedWith(compareByDescending<WebProviderDefinition> { favorite(it.id) }
        .thenByDescending { rank(it.id) }.thenByDescending { recent(it.id) }.thenBy { it.name })
}
