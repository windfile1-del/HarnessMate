package dev.wind.agent.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.wind.agent.CancellationToken
import dev.wind.agent.Prefs
import dev.wind.agent.SecureStore
import dev.wind.agent.search.SearchProviderManager
import dev.wind.agent.search.SearchRequest
import java.io.File

object SearchPrefsAndManagerTests {

    private class TestSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = (values[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = (values[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (values[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = Editor(values)

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private var cleared = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor { staged[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { staged[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { staged[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { staged[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { staged[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { staged[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { staged[key] = null; return this }
            override fun clear(): SharedPreferences.Editor { cleared = true; return this }
            override fun commit(): Boolean {
                if (cleared) target.clear()
                staged.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }

    private class TestContext : ContextWrapper(null) {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test-prefs-" + System.nanoTime()).also { it.mkdirs() }
        val sp = TestSharedPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = sp
        override fun getFilesDir(): File = tempDir
        override fun getNoBackupFilesDir(): File = tempDir
        override fun getPackageName(): String = "dev.wind.agent"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting SearchPrefsAndManagerTests...")
        testBraveAndTavilyKeyIndependence()
        testClearingSessionKeyRemovesKey()
        testManagerConfigDomainsFallbackOnOff()
        testExplicitUnconfiguredTavilyErrorNoDdgFallback()
        println("SearchPrefsAndManagerTests: ALL PASS")
    }

    private fun testBraveAndTavilyKeyIndependence() {
        val ctx = TestContext()
        val prefs = Prefs(ctx)

        prefs.setBraveApiKey("brave-token-abc")
        prefs.setTavilyApiKey("tavily-token-xyz")

        check(prefs.braveApiKey() == "brave-token-abc") { "Brave key mismatch: ${prefs.braveApiKey()}" }
        check(prefs.tavilyApiKey() == "tavily-token-xyz") { "Tavily key mismatch: ${prefs.tavilyApiKey()}" }

        // Changing Brave key does not touch Tavily key
        prefs.setBraveApiKey("brave-token-modified")
        check(prefs.braveApiKey() == "brave-token-modified")
        check(prefs.tavilyApiKey() == "tavily-token-xyz") { "Tavily key was modified by Brave update" }
        println("  testBraveAndTavilyKeyIndependence: PASS")
    }

    private fun testClearingSessionKeyRemovesKey() {
        val ctx = TestContext()
        val prefs = Prefs(ctx)

        // Set initial keys
        prefs.setTavilyApiKey("initial-tavily-key")
        check(prefs.tavilyApiKey() == "initial-tavily-key")

        // Clear key
        prefs.setTavilyApiKey("")
        check(prefs.tavilyApiKey().isEmpty()) { "Tavily key was not cleared: '${prefs.tavilyApiKey()}'" }

        // Test Brave clearing
        prefs.setBraveApiKey("initial-brave-key")
        check(prefs.braveApiKey() == "initial-brave-key")
        prefs.setBraveApiKey("")
        check(prefs.braveApiKey().isEmpty()) { "Brave key was not cleared: '${prefs.braveApiKey()}'" }
        println("  testClearingSessionKeyRemovesKey: PASS")
    }

    private fun testManagerConfigDomainsFallbackOnOff() {
        val ctx = TestContext()
        val prefs = Prefs(ctx)
        prefs.setBraveApiKey("key-b")
        prefs.setTavilyApiKey("key-t")
        prefs.setSearchProvider("brave")

        val manager = SearchProviderManager(prefs)

        // 1. Fallback ON (default):
        prefs.setSearchFallbackEnabled(true)
        val domainsFallbackOn = manager.networkDomains()
        check(domainsFallbackOn.contains("api.search.brave.com"))
        check(domainsFallbackOn.contains("api.tavily.com"))
        check(domainsFallbackOn.contains("lite.duckduckgo.com"))

        // 2. Fallback OFF:
        prefs.setSearchFallbackEnabled(false)
        val domainsFallbackOff = manager.networkDomains()
        check(domainsFallbackOff == setOf("api.search.brave.com")) {
            "When fallback is off, only primary domain should be returned: $domainsFallbackOff"
        }
        println("  testManagerConfigDomainsFallbackOnOff: PASS")
    }

    private fun testExplicitUnconfiguredTavilyErrorNoDdgFallback() {
        val ctx = TestContext()
        val prefs = Prefs(ctx)
        // Explicitly choose Tavily as search provider, but leave key blank
        prefs.setSearchProvider("tavily")
        prefs.setTavilyApiKey("")
        ctx.sp.values["search_fallback"] = true // Even if fallback enabled

        val manager = SearchProviderManager(prefs)

        // networkDomains() must be empty for explicit unconfigured provider
        check(manager.networkDomains().isEmpty()) {
            "Explicit unconfigured provider must have empty networkDomains: ${manager.networkDomains()}"
        }

        val outcome = manager.search(SearchRequest("query test", 5), CancellationToken())

        check(outcome.results.isEmpty()) { "Expected 0 results for unconfigured explicit provider" }
        check(outcome.text.contains("tavily: WEB_PROVIDER_CONFIGURED_UNAVAILABLE; configure this provider in Settings")) {
            "Expected clear unconfigured error, got: ${outcome.text}"
        }
        check(!outcome.text.contains("duckduckgo")) {
            "Explicit unconfigured provider must NOT fall back to DDG: ${outcome.text}"
        }
        println("  testExplicitUnconfiguredTavilyErrorNoDdgFallback: PASS")
    }
}
