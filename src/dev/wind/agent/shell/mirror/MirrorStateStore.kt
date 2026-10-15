package dev.wind.agent.shell.mirror

import android.content.Context
import dev.wind.agent.project.ProjectIdentity
import dev.wind.agent.runtime.RuntimeBinding
import java.io.File
import java.io.FileOutputStream

/** Crash-safe, schema-versioned metadata store for a single project mirror. */
class MirrorStateStore(private val context: Context, private val projectId: String) {
    companion object { const val MAX_STATE_BYTES = 1024 * 1024 }

    init {
        require(projectId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "MIRROR_PROJECT_ID_INVALID" }
    }

    private val file = File(context.filesDir, "projects/$projectId/mirror_state.json")
    private val temp = File(file.parentFile, "mirror_state.new")
    private val backup = File(file.parentFile, "mirror_state.bak")

    fun load(currentGeneration: Long, currentTopologyHash: String): MirrorState? {
        val candidate = try {
            recoverCandidate()
        } catch (_: Exception) {
            // A recovery artifact that cannot be installed is not equivalent
            // to an absent state file. Return BROKEN so the manager can never
            // recreate a CLEAN session around an uncertain mirror.
            return broken(currentGeneration, currentTopologyHash, "MIRROR_STATE_RECOVERY_FAILED")
                .also { runCatching { save(it) } }
        } ?: return null
        val root = try {
            require(candidate.length() <= MAX_STATE_BYTES) { "MIRROR_METADATA_LIMIT" }
            org.json.JSONObject(candidate.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            quarantine(candidate)
            return broken(currentGeneration, currentTopologyHash, "MIRROR_STATE_CORRUPT").also { runCatching { save(it) } }
        }
        return try {
            val schema = MirrorState.strictSchemaVersion(root)
            when {
                schema == MirrorState.SCHEMA_VERSION -> MirrorState.fromJson(root, projectId)
                schema == 2 -> migrateV2(root).also { save(it) }
                schema == null || schema == 1 -> migrateLegacy(root, currentGeneration, currentTopologyHash).also { save(it) }
                else -> throw IllegalArgumentException("MIRROR_STATE_SCHEMA_UNSUPPORTED")
            }
        } catch (_: Exception) {
            quarantine(candidate)
            broken(currentGeneration, currentTopologyHash, "MIRROR_STATE_INVALID").also { runCatching { save(it) } }
        }
    }

    /**
     * Reads the durable binding from the raw state document.
     *
     * This is not a provider authority. The mirror state machine decides which
     * provider owns a project generation, and it also decides what a missing,
     * corrupt or out-of-phase state means; callers that need to know must ask
     * [ProjectMirrorManager.requireRuntimeBindingForCodeIntelligence] instead of
     * interpreting this file themselves. It remains only for migration and
     * diagnostics, where "what does the document say" is the question.
     */
    @Deprecated("Not a provider authority; resolve through ProjectMirrorManager instead")
    fun loadBinding(): RuntimeBinding? {
        val candidate = try { recoverCandidate() } catch (_: Exception) { null } ?: return null
        return runCatching {
            val root = org.json.JSONObject(candidate.readText(Charsets.UTF_8))
            val b = root.optJSONObject("runtimeBinding") ?: return@runCatching null
            RuntimeBinding.fromJson(b)
        }.getOrNull()
    }

    fun save(state: MirrorState) {
        require(state.projectId == projectId) { "MIRROR_STATE_PROJECT_MISMATCH" }
        val bytes = state.toJson().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STATE_BYTES) { "MIRROR_METADATA_LIMIT" }
        val parent = file.parentFile
        require(parent == null || parent.isDirectory || parent.mkdirs()) { "Failed to create mirror-state directory" }
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (backup.exists()) backup.delete()
        val hadOld = file.isFile
        if (hadOld && !file.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("Failed to stage previous mirror state")
        }
        if (!temp.renameTo(file)) {
            if (hadOld) backup.renameTo(file)
            throw IllegalStateException("Failed to install mirror state")
        }
        if (backup.exists()) backup.delete()
    }

    private fun recoverCandidate(): File? {
        if (file.exists() && !file.isFile) {
            throw IllegalStateException("MIRROR_STATE_RECOVERY_FAILED")
        }
        if (file.isFile) {
            // A valid final file is authoritative; debris is never resurrected.
            if (temp.exists()) temp.delete()
            if (backup.exists()) backup.delete()
            return file
        }
        if (temp.exists()) {
            if (!temp.isFile || !temp.renameTo(file)) {
                throw IllegalStateException("MIRROR_STATE_RECOVERY_FAILED")
            }
            return file
        }
        if (backup.exists()) {
            if (!backup.isFile || !backup.renameTo(file)) {
                throw IllegalStateException("MIRROR_STATE_RECOVERY_FAILED")
            }
            return file
        }
        return null
    }

    private fun quarantine(candidate: File) {
        try {
            val corrupt = File(candidate.parentFile, candidate.name + ".corrupt." + System.currentTimeMillis())
            candidate.renameTo(corrupt)
            temp.delete()
            backup.delete()
        } catch (_: Exception) {}
    }

    private fun broken(generation: Long, topologyHash: String, reason: String): MirrorState {
        val safeTopology = if (ManifestDigest.isDigest(topologyHash)) topologyHash else "0".repeat(64)
        return MirrorState(
            projectId,
            generation.coerceAtLeast(0L),
            safeTopology,
            MirrorPhase.BROKEN,
            null,
            null,
            emptyList(),
            null,
            MirrorOperationStats("state", "recovery", 0, 0, 0, 0, 0, 0, 0, reason)
        )
    }

    /**
     * Convert the P3 schema-2 record to schema 3 without changing any mirror
     * payload or lifecycle phase. Schema 2 predates provider selection, so the
     * only safe authoritative provider is the existing Termux mirror.
     */
    private fun migrateV2(root: org.json.JSONObject): MirrorState {
        val generation = root.getLong("projectGeneration")
        val binding = RuntimeBinding(
            providerId = dev.wind.agent.runtime.RuntimeProviderId.TERMUX,
            workspaceId = ProjectIdentity.remoteId(projectId),
            projectId = projectId,
            rootGeneration = generation,
            runtimeInstanceId = "termux:legacy",
            protocolVersion = 2,
            layoutVersion = 1
        )
        val migrated = org.json.JSONObject(root.toString())
            .put("schemaVersion", MirrorState.SCHEMA_VERSION)
            .put("runtimeBinding", binding.toJson())
            .put("runtimeMigration", org.json.JSONObject.NULL)
        return MirrorState.fromJson(migrated, projectId)
    }

    /** Convert the pre-P3 JSON state without ever treating an uncertain pending run as CLEAN. */
    private fun migrateLegacy(root: org.json.JSONObject, generation: Long, topologyHash: String): MirrorState {
        val oldTopology = root.optString("topologyHash", "")
        val oldState = root.optString("state", "CLEAN")
        val baseline = if (root.has("baselineCanonical")) ManifestDigest.ofJsonArray(root.optJSONArray("baselineCanonical")) else null
        val remote = if (root.has("expectedRemote")) ManifestDigest.ofJsonArray(root.optJSONArray("expectedRemote")) else null
        val mounts = mutableListOf<MirrorSessionMount>()
        root.optJSONArray("sessionMounts")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                mounts.add(MirrorSessionMount(
                    item.getString("id"),
                    item.getString("treeUri"),
                    item.getString("mode"),
                    item.getString("executionPath")
                ))
            }
        }
        val executionId = root.optString("executionId", "")
        val taskId = root.optString("taskId", root.optString("rootTaskId", ""))
        val pending = if (executionId.isNotBlank() && taskId.isNotBlank() && remote != null) {
            PendingExecution(executionId, taskId, root.optString("rootTaskId", taskId), remote,
                root.optBoolean("eligible", false), System.currentTimeMillis())
        } else null
        val uncertain = oldState != "CLEAN" || pending != null
        if (uncertain && (baseline == null || remote == null || oldTopology != topologyHash)) {
            return broken(generation, topologyHash, "MIRROR_LEGACY_PENDING_UNPROVABLE")
        }
        return MirrorState(
            projectId,
            generation,
            topologyHash,
            if (uncertain) MirrorPhase.PENDING else MirrorPhase.CLEAN,
            baseline,
            remote,
            mounts,
            pending,
            null
        )
    }
}
