package dev.wind.agent.shell.mirror

import dev.wind.agent.runtime.RuntimeBinding
import dev.wind.agent.runtime.RuntimeMigration
import org.json.JSONArray
import org.json.JSONObject

/** Durable lifecycle states for one project's persistent execution mirror. */
enum class MirrorPhase {
    CLEAN,
    SYNCING,
    MUTATING,
    PENDING,
    APPLYING,
    DISCARDING,
    REBINDING,
    BROKEN
}

data class MirrorSessionMount(
    val id: String,
    val treeUri: String,
    val mode: String,
    val executionPath: String
) {
    init {
        require(id.isNotBlank() && treeUri.isNotBlank() && mode.isNotBlank())
        require(executionPath.isNotBlank() && WorkspacePathCanonical.isValid(executionPath))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("treeUri", treeUri)
        .put("mode", mode)
        .put("executionPath", executionPath)

    companion object {
        fun fromJson(root: JSONObject): MirrorSessionMount = MirrorSessionMount(
            root.getString("id"),
            root.getString("treeUri"),
            root.getString("mode"),
            root.getString("executionPath")
        )
    }
}

data class PendingExecution(
    val executionId: String,
    val taskId: String,
    val rootTaskId: String,
    val remoteDigest: String,
    val eligible: Boolean,
    val createdAt: Long
) {
    init {
        require(executionId.isNotBlank() && taskId.isNotBlank() && rootTaskId.isNotBlank())
        require(ManifestDigest.isDigest(remoteDigest) && createdAt >= 0L)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("executionId", executionId)
        .put("taskId", taskId)
        .put("rootTaskId", rootTaskId)
        .put("remoteDigest", remoteDigest)
        .put("eligible", eligible)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(root: JSONObject): PendingExecution = PendingExecution(
            root.getString("executionId"),
            root.getString("taskId"),
            root.getString("rootTaskId"),
            root.getString("remoteDigest"),
            root.getBoolean("eligible"),
            root.getLong("createdAt")
        )
    }
}

data class MirrorOperationStats(
    val operationId: String,
    val kind: String,
    val filesScanned: Int,
    val filesTransferred: Int,
    val bytesTransferred: Long,
    val created: Int,
    val modified: Int,
    val deleted: Int,
    val durationMs: Long,
    val result: String
) {
    init {
        require(operationId.isNotBlank() && kind.isNotBlank() && result.isNotBlank())
        require(filesScanned >= 0 && filesTransferred >= 0 && bytesTransferred >= 0L)
        require(created >= 0 && modified >= 0 && deleted >= 0 && durationMs >= 0L)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("operationId", operationId)
        .put("kind", kind)
        .put("filesScanned", filesScanned)
        .put("filesTransferred", filesTransferred)
        .put("bytesTransferred", bytesTransferred)
        .put("created", created)
        .put("modified", modified)
        .put("deleted", deleted)
        .put("durationMs", durationMs)
        .put("result", result)

    companion object {
        fun fromJson(root: JSONObject): MirrorOperationStats = MirrorOperationStats(
            root.getString("operationId"),
            root.getString("kind"),
            root.getInt("filesScanned"),
            root.getInt("filesTransferred"),
            root.getLong("bytesTransferred"),
            root.getInt("created"),
            root.getInt("modified"),
            root.getInt("deleted"),
            root.getLong("durationMs"),
            root.getString("result")
        )
    }
}

/** A small, schema-versioned state record. It never contains project payload. */
data class MirrorState(
    val projectId: String,
    val projectGeneration: Long,
    val topologyHash: String,
    val phase: MirrorPhase,
    val canonicalBaselineDigest: String?,
    val remoteDigest: String?,
    val sessionMounts: List<MirrorSessionMount>,
    val pending: PendingExecution?,
    val lastOperation: MirrorOperationStats?,
    /** The single authoritative execution provider for this project generation. */
    val runtimeBinding: RuntimeBinding? = null,
    /** Non-null only while a CLEAN provider rebind transaction is in flight. */
    val runtimeMigration: RuntimeMigration? = null
) {
    val schemaVersion: Int = SCHEMA_VERSION

    init {
        require(projectId.isNotBlank() && projectGeneration >= 0L)
        require(topologyHash.isNotBlank() && ManifestDigest.isDigest(topologyHash))
        require(canonicalBaselineDigest == null || ManifestDigest.isDigest(canonicalBaselineDigest))
        require(remoteDigest == null || ManifestDigest.isDigest(remoteDigest))
        require(sessionMounts.map { it.id }.distinct().size == sessionMounts.size)
        require(runtimeBinding == null || runtimeBinding.projectId == projectId) { "MIRROR_RUNTIME_PROJECT_MISMATCH" }
        require(runtimeBinding == null || runtimeBinding.rootGeneration == projectGeneration) { "MIRROR_RUNTIME_GENERATION_MISMATCH" }
        require(runtimeMigration == null || phase == MirrorPhase.REBINDING) { "MIRROR_MIGRATION_PHASE_MISMATCH" }
        require(phase != MirrorPhase.REBINDING || runtimeMigration != null) { "MIRROR_REBINDING_RECORD_MISSING" }
        // Keep the execution identity through every transitional phase so a
        // crash during sync/apply/discard can be recovered or discarded
        // deterministically. CLEAN is the only phase that must not retain it.
        require(pending == null || phase != MirrorPhase.CLEAN)
        when (phase) {
            MirrorPhase.CLEAN -> require(pending == null) { "MIRROR_CLEAN_HAS_PENDING" }
            MirrorPhase.PENDING, MirrorPhase.MUTATING, MirrorPhase.APPLYING -> {
                // These are the only phases that can authorize or apply a
                // remote-to-canonical delta. A nullable digest here would turn
                // a corrupt/replayed record into a fail-open apply.
                require(pending != null) { "MIRROR_PENDING_RECORD_MISSING" }
                require(canonicalBaselineDigest != null) { "MIRROR_BASELINE_DIGEST_MISSING" }
                require(remoteDigest != null) { "MIRROR_REMOTE_DIGEST_MISSING" }
                require(sessionMounts.isNotEmpty()) { "MIRROR_SESSION_MOUNTS_MISSING" }
                require(pending.remoteDigest == remoteDigest) { "MIRROR_PENDING_DIGEST_MISMATCH" }
            }
            else -> Unit
        }
    }

    fun toJson(): JSONObject {
        val mounts = JSONArray()
        sessionMounts.sortedBy { it.id }.forEach { mounts.put(it.toJson()) }
        return JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("projectId", projectId)
            .put("projectGeneration", projectGeneration)
            .put("topologyHash", topologyHash)
            .put("phase", phase.name)
            .put("canonicalBaselineDigest", canonicalBaselineDigest ?: JSONObject.NULL)
            .put("remoteDigest", remoteDigest ?: JSONObject.NULL)
            .put("sessionMounts", mounts)
            .put("pending", pending?.toJson() ?: JSONObject.NULL)
            .put("lastOperation", lastOperation?.toJson() ?: JSONObject.NULL)
            .put("runtimeBinding", runtimeBinding?.toJson() ?: JSONObject.NULL)
            .put("runtimeMigration", runtimeMigration?.toJson() ?: JSONObject.NULL)
    }

    companion object {
        const val SCHEMA_VERSION = 3

        /** Read the schema type/value without JSONObject's lossy coercions. */
        fun strictSchemaVersion(root: JSONObject): Int? {
            if (!root.has("schemaVersion")) return null
            val value = root.opt("schemaVersion")
            require(value is Number) { "MIRROR_STATE_SCHEMA_INVALID" }
            val number = value.toDouble()
            require(number.isFinite() && number == kotlin.math.floor(number)) {
                "MIRROR_STATE_SCHEMA_INVALID"
            }
            require(number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()) {
                "MIRROR_STATE_SCHEMA_INVALID"
            }
            return number.toInt()
        }

        fun clean(projectId: String, projectGeneration: Long, topologyHash: String): MirrorState =
            MirrorState(projectId, projectGeneration, topologyHash, MirrorPhase.CLEAN, null, null, emptyList(), null, null)

        fun fromJson(root: JSONObject, expectedProjectId: String): MirrorState {
            require(strictSchemaVersion(root) == SCHEMA_VERSION) { "Unsupported mirror state schema" }
            val projectId = root.getString("projectId")
            require(projectId == expectedProjectId) { "MIRROR_STATE_PROJECT_MISMATCH" }
            val mounts = mutableListOf<MirrorSessionMount>()
            val mountArray = root.getJSONArray("sessionMounts")
            for (i in 0 until mountArray.length()) mounts.add(MirrorSessionMount.fromJson(mountArray.getJSONObject(i)))
            val pendingValue = root.opt("pending")
            val operationValue = root.opt("lastOperation")
            val pending = if (pendingValue == null || pendingValue == JSONObject.NULL) null
            else PendingExecution.fromJson(root.getJSONObject("pending"))
            val operation = if (operationValue == null || operationValue == JSONObject.NULL) null
            else MirrorOperationStats.fromJson(root.getJSONObject("lastOperation"))
            val bindingValue = root.opt("runtimeBinding")
            val migrationValue = root.opt("runtimeMigration")
            val binding = if (bindingValue == null || bindingValue == JSONObject.NULL) null
            else RuntimeBinding.fromJson(root.getJSONObject("runtimeBinding"))
            val migration = if (migrationValue == null || migrationValue == JSONObject.NULL) null
            else RuntimeMigration.fromJson(root.getJSONObject("runtimeMigration"))
            val phase = MirrorPhase.valueOf(root.getString("phase"))
            return MirrorState(
                projectId = projectId,
                projectGeneration = root.getLong("projectGeneration"),
                topologyHash = root.getString("topologyHash"),
                phase = phase,
                canonicalBaselineDigest = optionalDigest(root, "canonicalBaselineDigest"),
                remoteDigest = optionalDigest(root, "remoteDigest"),
                sessionMounts = mounts,
                pending = pending,
                lastOperation = operation,
                runtimeBinding = binding,
                runtimeMigration = migration
            )
        }

        private fun optionalDigest(root: JSONObject, key: String): String? {
            val value = root.opt(key)
            if (value == null || value == JSONObject.NULL) return null
            require(value is String && ManifestDigest.isDigest(value)) {
                "MIRROR_STATE_INVALID_DIGEST: $key"
            }
            return value
        }
    }
}

/** Local validation without importing Android or accepting absolute paths. */
private object WorkspacePathCanonical {
    fun isValid(path: String): Boolean = try {
        path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
            path.none { it < ' ' || it in "<>\"|?*" } &&
            path.split('/').none { it.isBlank() || it == "." || it == ".." }
    } catch (_: Exception) { false }
}
