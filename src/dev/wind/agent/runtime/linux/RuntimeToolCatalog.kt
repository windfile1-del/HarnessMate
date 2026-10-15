package dev.wind.agent.runtime.linux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** App-authored provider map. A model cannot choose a URL, version, digest, or activation directory. */
object RuntimeToolCatalog {
    const val REPOSITORY = "windfile1-del/harnessmate-tool-packs"
    const val RELEASE = "tool-packs-2026.10.06"
    val networkDomains = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val aliases = mapOf(
        "core" to "core", "python" to "core", "python3" to "core", "pip" to "core", "pip3" to "core", "java" to "core", "javac" to "core", "git" to "core", "node" to "core", "npm" to "core", "shell" to "core",
        "linux-jvm" to "linux-jvm", "gradle" to "linux-jvm", "kotlin" to "linux-jvm", "kotlinc" to "linux-jvm",
        "linux-android" to "linux-android", "android-build" to "linux-android", "android-sdk" to "linux-android", "aapt2" to "linux-android",
        "linux-lsp" to "linux-lsp", "code-intelligence" to "linux-lsp", "lsp" to "linux-lsp")
    fun families(tools: List<String>): List<String> {
        require(tools.isNotEmpty() && tools.size <= 32) { "RUNTIME_TOOLS_REQUIRED" }
        val requested = tools.map { aliases[it.lowercase()] ?: error("RUNTIME_TOOL_NOT_CATALOGUED: $it") }.toSet()
        return listOf("core", "linux-jvm", "linux-android", "linux-lsp").filter {
            it == "core" || it in requested || it == "linux-jvm" && "linux-android" in requested
        }
    }
    fun filename(family: String): String = when (family) {
        "linux-jvm" -> "harnessmate-jvm-build.hm-pack"
        "linux-android" -> "harnessmate-android-build.hm-pack"
        "linux-lsp" -> "harnessmate-code-intelligence.hm-pack"
        else -> error("RUNTIME_TOOL_PACK_ID_INVALID")
    }
    fun url(family: String): String = "https://github.com/$REPOSITORY/releases/download/$RELEASE/${filename(family)}"
    fun manifest(context: Context, family: String): JSONObject {
        val asset = if (family == "core") "embedded/linux-runtime/arm64-v8a/manifest.json" else "embedded/$family/manifest.json"
        return context.assets.open(asset).use { input ->
            JSONObject(String(ToolPackArchive.readBounded(input, ToolPackArchive.MAX_METADATA_BYTES), Charsets.UTF_8))
        }
    }
    fun downloadManifest(context: Context, family: String): JSONObject {
        require(family != "core") { "RUNTIME_TOOL_PACK_ID_INVALID" }
        val value = context.assets.open("embedded/$family/download.json").use {
            JSONObject(String(ToolPackArchive.readBounded(it, ToolPackArchive.MAX_METADATA_BYTES), Charsets.UTF_8))
        }
        val payload = manifest(context, family)
        require(value.getString("family") == family && value.getString("filename") == filename(family) &&
            value.getString("url") == url(family) && value.getString("payloadSha256") == payload.getString("sha256") &&
            value.getString("sha256").matches(Regex("[0-9a-f]{64}")) &&
            value.getLong("bytes") in (payload.getLong("compressedBytes") + 1)..(payload.getLong("compressedBytes") + 65536)) {
            "RUNTIME_TOOL_DOWNLOAD_CATALOG_INVALID"
        }
        return value
    }
    private fun findInstalled(context: Context, family: String): Boolean = when (family) {
        "core" -> LinuxRuntimeInstaller.findInstalled(context) != null
        "linux-jvm" -> LinuxJvmInstaller.findInstalled(context) != null
        "linux-android" -> LinuxAndroidInstaller.findInstalled(context) != null
        "linux-lsp" -> LinuxLspInstaller.findInstalled(context) != null
        else -> false
    }
    fun isInstalled(context: Context, family: String): Boolean = runCatching {
        findInstalled(context, family) && (family=="core" || LinuxToolPackStore.hasPayload(context,family))
    }.getOrDefault(false)
    private fun status(context: Context, family: String): JSONObject = try {
        val installed = findInstalled(context, family)
        JSONObject().put("installed", installed).put("state", if (installed) "INSTALLED" else "NOT_INSTALLED")
    } catch (error: Exception) {
        JSONObject().put("installed", false).put("state", "BROKEN").put("error", error.message ?: error.javaClass.simpleName)
    }
    fun json(context: Context): JSONObject = JSONObject().put("repository", REPOSITORY).put("release", RELEASE)
        .put("tools", JSONArray(listOf("core", "linux-jvm", "linux-android", "linux-lsp").map { family ->
            val metadata = manifest(context, family)
            val publisherVerified=family!="core" && runCatching { LinuxToolPackStore.hasPayload(context,family) }.getOrDefault(false)
            val result = status(context, family).put("publisher_verified",publisherVerified).put("ready",isInstalled(context,family))
                .put("family", family).put("aliases", JSONArray(aliases.filterValues { it == family }.keys.toList()))
                .put("payload_sha256", metadata.getString("sha256")).put("publisher_trust_required",family != "core")
                .put("source", if (family == "core") "bundled_apk" else url(family))
                .put("dependencies", JSONArray(if (family == "linux-android") listOf("core", "linux-jvm") else if (family != "core") listOf("core") else emptyList<String>()))
            if (family != "core") {
                val download = downloadManifest(context, family)
                result.put("download_sha256", download.getString("sha256")).put("download_bytes", download.getLong("bytes"))
                    .put("hash_usage", "download_sha256 verifies the .hm-pack ZIP; payload_sha256 verifies tools.tar.gz inside it. Prefer ensure_runtime_tools.")
            }
            result
        }))
}
