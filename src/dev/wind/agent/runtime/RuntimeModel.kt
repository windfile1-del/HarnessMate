package dev.wind.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable identity and capability model shared by every execution provider.
 *
 * A runtime provider is an implementation detail; the binding is durable
 * authority for one project generation and is never selected independently by
 * shell, mirror, or code-intelligence callers.
 */
enum class RuntimeProviderId {
    TERMUX,
    VEGA_COMPANION,
    VEGA_LINUX
}

data class RuntimeBinding(
    val providerId: RuntimeProviderId,
    val workspaceId: String,
    val projectId: String,
    val rootGeneration: Long,
    val runtimeInstanceId: String,
    val protocolVersion: Int = 1,
    val layoutVersion: Int = 1
) {
    init {
        require(workspaceId.matches(Regex("[0-9a-f]{24}"))) { "RUNTIME_WORKSPACE_ID_INVALID" }
        require(projectId.isNotBlank()) { "RUNTIME_PROJECT_ID_INVALID" }
        require(rootGeneration >= 0L) { "RUNTIME_ROOT_GENERATION_INVALID" }
        require(runtimeInstanceId.isNotBlank()) { "RUNTIME_INSTANCE_ID_INVALID" }
        require(protocolVersion > 0 && layoutVersion > 0) { "RUNTIME_PROTOCOL_INVALID" }
    }

    fun matches(projectId: String, rootGeneration: Long, workspaceId: String): Boolean =
        this.projectId == projectId && this.rootGeneration == rootGeneration && this.workspaceId == workspaceId

    fun toJson(): JSONObject = JSONObject()
        .put("providerId", providerId.name)
        .put("workspaceId", workspaceId)
        .put("projectId", projectId)
        .put("rootGeneration", rootGeneration)
        .put("runtimeInstanceId", runtimeInstanceId)
        .put("protocolVersion", protocolVersion)
        .put("layoutVersion", layoutVersion)

    companion object {
        fun fromJson(value: JSONObject): RuntimeBinding = RuntimeBinding(
            providerId = RuntimeProviderId.valueOf(value.getString("providerId")),
            workspaceId = value.getString("workspaceId"),
            projectId = value.getString("projectId"),
            rootGeneration = value.getLong("rootGeneration"),
            runtimeInstanceId = value.getString("runtimeInstanceId"),
            protocolVersion = value.optInt("protocolVersion", 1),
            layoutVersion = value.optInt("layoutVersion", 1)
        )
    }
}

/** Facts reported by a provider probe; no field implies automatic installation. */
data class RuntimeCapabilities(
    val commandExecution: Boolean,
    val persistentWorkspace: Boolean,
    val processGroupCancellation: Boolean,
    val mirrorProtocolVersion: Int,
    /**
     * The bounded-streaming / transactional mirror data plane. Zero means the
     * provider exposes only the legacy single-document JSON mirror calls, and a
     * P3 execution must not be attempted against it: discovering the gap at the
     * first transaction would leave half a sync behind.
     */
    val mirrorStreamProtocolVersion: Int = 0,
    val helperPythonVersion: String = "",
    val toolchains: Map<String, String> = emptyMap(),
    val languageServers: Set<String> = emptySet(),
    val networkIsolation: String = "POLICY_ONLY",
    val supportedAbis: Set<String> = emptySet(),
    val runtimeVersion: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("commandExecution", commandExecution)
        .put("persistentWorkspace", persistentWorkspace)
        .put("processGroupCancellation", processGroupCancellation)
        .put("mirrorProtocolVersion", mirrorProtocolVersion)
        .put("mirrorStreamProtocolVersion", mirrorStreamProtocolVersion)
        .put("helperPythonVersion", helperPythonVersion)
        .put("toolchains", JSONObject().apply { toolchains.toSortedMap().forEach { (k, v) -> put(k, v) } })
        .put("languageServers", JSONArray().apply { languageServers.toList().sorted().forEach { put(it) } })
        .put("networkIsolation", networkIsolation)
        .put("supportedAbis", JSONArray().apply { supportedAbis.toList().sorted().forEach { put(it) } })
        .put("runtimeVersion", runtimeVersion)
}

enum class RuntimeProbeState {
    NOT_INSTALLED,
    UNAVAILABLE,
    DEGRADED,
    READY
}

data class RuntimeProbeResult(
    val providerId: RuntimeProviderId,
    val state: RuntimeProbeState,
    val capabilities: RuntimeCapabilities,
    val runtimeInstanceId: String = "",
    val diagnostic: String = "",
    val checkedAt: Long = System.currentTimeMillis()
) {
    val ready: Boolean
        get() = state == RuntimeProbeState.READY
}


enum class RuntimeMigrationPhase {
    PREPARING,
    CANDIDATE_SYNCING,
    CANDIDATE_VERIFIED,
    COMMITTING
}

data class RuntimeMigration(
    val fromBinding: RuntimeBinding,
    val candidateBinding: RuntimeBinding,
    val expectedCanonicalDigest: String,
    val expectedSourceRuntimeDigest: String,
    val candidateDigest: String? = null,
    val startedAt: Long,
    val phase: RuntimeMigrationPhase
) {
    init {
        require(expectedCanonicalDigest.matches(Regex("[0-9a-f]{64}"))) {
            "RUNTIME_MIGRATION_CANONICAL_DIGEST_INVALID"
        }
        require(expectedSourceRuntimeDigest.matches(Regex("[0-9a-f]{64}"))) {
            "RUNTIME_MIGRATION_SOURCE_DIGEST_INVALID"
        }
        require(candidateDigest == null || candidateDigest.matches(Regex("[0-9a-f]{64}"))) {
            "RUNTIME_MIGRATION_CANDIDATE_DIGEST_INVALID"
        }
        require(startedAt >= 0L) { "RUNTIME_MIGRATION_TIMESTAMP_INVALID" }
        require(fromBinding.projectId == candidateBinding.projectId &&
            fromBinding.rootGeneration == candidateBinding.rootGeneration &&
            fromBinding.workspaceId == candidateBinding.workspaceId) {
            "RUNTIME_MIGRATION_IDENTITY_MISMATCH"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("fromBinding", fromBinding.toJson())
        .put("candidateBinding", candidateBinding.toJson())
        .put("expectedCanonicalDigest", expectedCanonicalDigest)
        .put("expectedSourceRuntimeDigest", expectedSourceRuntimeDigest)
        .put("candidateDigest", candidateDigest ?: JSONObject.NULL)
        .put("startedAt", startedAt)
        .put("phase", phase.name)

    companion object {
        fun fromJson(value: JSONObject): RuntimeMigration = RuntimeMigration(
            fromBinding = RuntimeBinding.fromJson(value.getJSONObject("fromBinding")),
            candidateBinding = RuntimeBinding.fromJson(value.getJSONObject("candidateBinding")),
            expectedCanonicalDigest = value.getString("expectedCanonicalDigest"),
            expectedSourceRuntimeDigest = value.getString("expectedSourceRuntimeDigest"),
            candidateDigest = value.optString("candidateDigest", "").takeIf { it.isNotBlank() },
            startedAt = value.getLong("startedAt"),
            phase = RuntimeMigrationPhase.valueOf(value.getString("phase"))
        )
    }
}
