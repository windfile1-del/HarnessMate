package dev.wind.agent.runtime.linux

/**
 * Independent host test suite for RuntimeToolCatalog.
 *
 * Verifies:
 * 1. Fixed aliases map accurately to core (including python3, pip, pip3, npm), linux-jvm, linux-android, and linux-lsp.
 * 2. Transitive dependency planning: android-sdk / aapt2 automatically demands core + linux-jvm + linux-android.
 * 3. Unknown tools fail closed with RUNTIME_TOOL_NOT_CATALOGUED error.
 * 4. Input bounds: empty tool list or > 32 tools rejected with RUNTIME_TOOLS_REQUIRED.
 * 5. Fixed release distribution URLs: callers cannot inject arbitrary URLs, domains, or versions.
 * 6. Network domains allowlist strictly limited to GitHub release assets.
 */
object RuntimeToolCatalogTests {
    private var assertions = 0

    private fun check(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError("FAILED: $message")
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        testAliasResolution()
        testTransitiveDependencyPlanning()
        testUnknownToolsFailClosed()
        testInputBoundsValidation()
        testPinnedReleaseUrlsAndFilenames()
        testNetworkDomainsAllowlist()

        println("RuntimeToolCatalogTests: OK ($assertions assertions passed)")
    }

    private fun testAliasResolution() {
        val coreTools = listOf("python", "python3", "pip", "pip3", "java", "javac", "git", "node", "npm", "shell")
        for (tool in coreTools) {
            val planned = RuntimeToolCatalog.families(listOf(tool))
            check(planned == listOf("core"), "Tool '$tool' must resolve solely to [core]")
        }

        val jvmTools = listOf("gradle", "kotlin", "kotlinc", "linux-jvm")
        for (tool in jvmTools) {
            val planned = RuntimeToolCatalog.families(listOf(tool))
            check(planned == listOf("core", "linux-jvm"), "Tool '$tool' must resolve to [core, linux-jvm]")
        }

        val lspTools = listOf("linux-lsp", "code-intelligence", "lsp")
        for (tool in lspTools) {
            val planned = RuntimeToolCatalog.families(listOf(tool))
            check(planned == listOf("core", "linux-lsp"), "Tool '$tool' must resolve to [core, linux-lsp]")
        }
    }

    private fun testTransitiveDependencyPlanning() {
        val androidTools = listOf("linux-android", "android-build", "android-sdk", "aapt2")
        for (tool in androidTools) {
            val planned = RuntimeToolCatalog.families(listOf(tool))
            check(planned == listOf("core", "linux-jvm", "linux-android"),
                "Android tool '$tool' must transitively require [core, linux-jvm, linux-android]")
        }

        val composite = RuntimeToolCatalog.families(listOf("npm", "pip", "kotlinc", "lsp"))
        check(composite == listOf("core", "linux-jvm", "linux-lsp"),
            "Composite request must deduplicate and maintain canonical family ordering")
    }

    private fun testUnknownToolsFailClosed() {
        val evilTools = listOf("curl", "bash", "powershell", "unknown_compiler", "malicious_tool")
        for (tool in evilTools) {
            var rejected = false
            try {
                RuntimeToolCatalog.families(listOf(tool))
            } catch (e: IllegalStateException) {
                if (e.message?.contains("RUNTIME_TOOL_NOT_CATALOGUED") == true) {
                    rejected = true
                }
            }
            check(rejected, "Unknown tool '$tool' must be rejected with RUNTIME_TOOL_NOT_CATALOGUED")
        }
    }

    private fun testInputBoundsValidation() {
        var emptyRejected = false
        try {
            RuntimeToolCatalog.families(emptyList())
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("RUNTIME_TOOLS_REQUIRED") == true) {
                emptyRejected = true
            }
        }
        check(emptyRejected, "Empty tool list must be rejected")

        val oversized = (1..33).map { "git" }
        var oversizedRejected = false
        try {
            RuntimeToolCatalog.families(oversized)
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("RUNTIME_TOOLS_REQUIRED") == true) {
                oversizedRejected = true
            }
        }
        check(oversizedRejected, "Oversized tool list (> 32) must be rejected")
    }

    private fun testPinnedReleaseUrlsAndFilenames() {
        check(RuntimeToolCatalog.filename("linux-jvm") == "harnessmate-jvm-build.hm-pack", "linux-jvm pack filename match")
        check(RuntimeToolCatalog.filename("linux-android") == "harnessmate-android-build.hm-pack", "linux-android pack filename match")
        check(RuntimeToolCatalog.filename("linux-lsp") == "harnessmate-code-intelligence.hm-pack", "linux-lsp pack filename match")

        var corePackRejected = false
        try {
            RuntimeToolCatalog.filename("core")
        } catch (e: IllegalStateException) {
            corePackRejected = true
        }
        check(corePackRejected, "core family is bundled in APK and must not have an external pack filename")

        val expectedJvmUrl = "https://github.com/windfile1-del/harnessmate-tool-packs/releases/download/tool-packs-2026.10.06/harnessmate-jvm-build.hm-pack"
        check(RuntimeToolCatalog.url("linux-jvm") == expectedJvmUrl, "Release URL must be immutably pinned to official GitHub release")
    }

    private fun testNetworkDomainsAllowlist() {
        val domains = RuntimeToolCatalog.networkDomains
        check(domains.contains("github.com"), "github.com must be in network domains")
        check(domains.contains("release-assets.githubusercontent.com"), "release-assets domain must be included")
        check(domains.contains("objects.githubusercontent.com"), "objects domain must be included")
        check(!domains.contains("evil.com"), "Untrusted domain must not be in network domains")
    }
}
