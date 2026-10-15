package dev.wind.agent.project

import android.content.Context
import android.content.Intent
import dev.wind.agent.capability.CapabilityApprovalKey
import dev.wind.agent.capability.CapabilityBrokerRegistry
import dev.wind.agent.capability.CapabilityCheck
import dev.wind.agent.capability.CapabilityLease
import dev.wind.agent.capability.CapabilityMode
import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.TaskContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PendingWorkspaceRequest(
    val requestId: String,
    val projectId: String,
    val alias: String,
    val mode: MountMode,
    val executionPath: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Root task that owns the expansion approval. */
    val rootTaskId: String = "",
    /** Project generation observed when the request was approved. */
    val projectGeneration: Long = -1L,
    /** Opaque exact CapabilityApprovalKey for alias/mode/path. */
    val capabilityKey: String = ""
)

/** Durable queue for Agent-requested filesystem capabilities. */
object WorkspaceAccessRequestStore {
    private val requests = ConcurrentHashMap<String, PendingWorkspaceRequest>()
    private val LOCK = Any()
    @Volatile private var storageFile: File? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        synchronized(LOCK) {
            appContext = context.applicationContext
            val f = File(context.filesDir, "projects/pending_mount_requests.json")
            storageFile = f
            recoverStoreFiles(f)
            requests.clear()
            load(f)
        }
        launchNextPending()
    }

    /** Same crash-recovery contract as ProjectStore and mirror state. */
    private fun recoverStoreFiles(file: File) {
        val parent = file.parentFile ?: return
        val temp = File(parent, "pending_mount_requests.new")
        val backup = File(parent, "pending_mount_requests.bak")
        try {
            if (file.isFile) {
                if (temp.exists()) temp.delete()
                if (backup.exists()) backup.delete()
                return
            }
            if (temp.isFile) {
                if (temp.renameTo(file)) {
                    if (backup.exists()) backup.delete()
                    return
                }
                return
            }
            if (backup.isFile) backup.renameTo(file)
        } catch (_: Exception) {
            // Keep recoverable artifacts; never replace uncertainty with an empty queue.
        }
    }

    private fun load(file: File) {
        if (!file.isFile) return
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val req = PendingWorkspaceRequest(
                    requestId = obj.getString("requestId"),
                    projectId = obj.getString("projectId"),
                    alias = obj.getString("alias"),
                    mode = MountMode.valueOf(obj.getString("mode")),
                    executionPath = obj.getString("executionPath"),
                    reason = obj.getString("reason"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    rootTaskId = obj.getString("rootTaskId"),
                    projectGeneration = obj.getLong("projectGeneration"),
                    capabilityKey = obj.getString("capabilityKey")
                )
                if (req.projectId.isNotBlank() &&
                    req.alias.matches(Regex("^[a-z][a-z0-9_-]{0,31}$")) &&
                    WorkspaceMount.isValidExecutionPath(req.executionPath) &&
                    req.rootTaskId.isNotBlank() &&
                    req.projectGeneration >= 0L &&
                    req.capabilityKey.matches(Regex("[0-9a-f]{64}")) &&
                    req.capabilityKey == CapabilityApprovalKey.expansion(
                        CapabilityRequirement.CapabilityExpansion(
                            req.projectId,
                            req.alias,
                            req.mode,
                            req.executionPath
                        )
                    )
                ) {
                    requests[req.requestId] = req
                }
            }
        } catch (_: Exception) {
            // Corrupt request metadata is non-authoritative; do not resurrect it.
        }
    }

    private fun persistLocked() {
        val file = storageFile ?: return
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create pending-request directory")
        }
        val arr = JSONArray()
        for (req in requests.values.sortedBy { it.createdAt }) {
            arr.put(
                JSONObject()
                    .put("requestId", req.requestId)
                    .put("projectId", req.projectId)
                    .put("alias", req.alias)
                    .put("mode", req.mode.name)
                    .put("executionPath", req.executionPath)
                    .put("reason", req.reason)
                    .put("createdAt", req.createdAt)
                    .put("rootTaskId", req.rootTaskId)
                    .put("projectGeneration", req.projectGeneration)
                    .put("capabilityKey", req.capabilityKey)
            )
        }

        val temp = File(file.parentFile, "pending_mount_requests.new")
        val backup = File(file.parentFile, "pending_mount_requests.bak")
        FileOutputStream(temp).use {
            it.write(arr.toString().toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }

        if (backup.exists() && !backup.delete()) {
            temp.delete()
            throw IllegalStateException("Failed to remove stale pending-request backup")
        }
        val hadOld = file.exists()
        if (hadOld && !file.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("Failed to stage previous pending-request store")
        }
        if (!temp.renameTo(file)) {
            if (hadOld) backup.renameTo(file)
            throw IllegalStateException("Failed to install pending-request store")
        }
        if (backup.exists()) backup.delete()
    }

    fun createRequest(
        projectId: String,
        alias: String,
        mode: MountMode,
        executionPath: String,
        reason: String,
        rootTaskId: String,
        projectGeneration: Long,
        capabilityKey: String,
        capabilityLease: CapabilityLease
    ): String {
        require(projectId.isNotBlank()) { "Missing project id" }
        val normalizedAlias = alias.lowercase().trim()
        require(normalizedAlias.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) { "Invalid mount alias: $alias" }
        val normalizedPath = executionPath.trim()
        require(WorkspaceMount.isValidExecutionPath(normalizedPath)) { "Invalid execution path: $executionPath" }
        require(rootTaskId.isNotBlank()) { "Missing root task id" }
        require(projectGeneration >= 0L) { "Invalid project generation" }
        val expansion = CapabilityRequirement.CapabilityExpansion(
            projectId,
            normalizedAlias,
            mode,
            normalizedPath
        )
        require(capabilityKey == CapabilityApprovalKey.expansion(expansion)) {
            "CAPABILITY_REQUEST_KEY_MISMATCH"
        }
        require(capabilityLease.taskId == rootTaskId && capabilityLease.projectId == projectId) {
            "CAPABILITY_REQUEST_LEASE_MISMATCH"
        }
        require(capabilityLease.projectGeneration == projectGeneration) {
            "CAPABILITY_REQUEST_STALE"
        }
        val brokerContext = appContext ?: throw IllegalStateException("CAPABILITY_STORE_UNAVAILABLE")
        // The durable queue is itself an authority hand-off. Reverify the
        // exact lease at this boundary before persisting or launching a picker.
        CapabilityBrokerRegistry.get(brokerContext).verify(capabilityLease, expansion)

        val req = synchronized(LOCK) {
            val existing = requests.values.firstOrNull {
                it.projectId == projectId && it.alias == normalizedAlias
            }
            if (existing != null) {
                if (existing.rootTaskId == rootTaskId &&
                    existing.projectGeneration == projectGeneration &&
                    existing.mode == mode &&
                    existing.executionPath == normalizedPath &&
                    existing.capabilityKey == capabilityKey
                ) {
                    return@synchronized existing
                }
                throw IllegalStateException("PENDING_REQUEST_CONFLICT: an expansion request for '$normalizedAlias' is already pending")
            }

            val id = "req-" + UUID.randomUUID().toString().substring(0, 8)
            val created = PendingWorkspaceRequest(
                requestId = id,
                projectId = projectId,
                alias = normalizedAlias,
                mode = mode,
                executionPath = normalizedPath,
                reason = reason.trim(),
                rootTaskId = rootTaskId,
                projectGeneration = projectGeneration,
                capabilityKey = capabilityKey
            )
            requests[id] = created
            try {
                persistLocked()
            } catch (e: Exception) {
                requests.remove(id)
                throw e
            }
            created
        }
        launchGrantUi(req.requestId)
        return req.requestId
    }

    private fun stillAuthorized(req: PendingWorkspaceRequest): Boolean {
        val context = appContext ?: return false
        val expansion = CapabilityRequirement.CapabilityExpansion(
            req.projectId,
            req.alias,
            req.mode,
            req.executionPath
        )
        if (req.capabilityKey != CapabilityApprovalKey.expansion(expansion)) return false
        val taskContext = TaskContext(
            req.rootTaskId,
            req.projectId,
            req.projectGeneration,
            CapabilityMode.ACCEPT,
            req.rootTaskId
        )
        return when (val checked = CapabilityBrokerRegistry.get(context).check(taskContext, expansion)) {
            is CapabilityCheck.Allowed -> runCatching {
                CapabilityBrokerRegistry.get(context).verify(checked.lease)
            }.isSuccess
            else -> false
        }
    }

    /** Remove a stale request rather than ever presenting an unverified picker. */
    private fun validRequest(id: String): PendingWorkspaceRequest? {
        val req = requests[id] ?: return null
        if (stillAuthorized(req)) return req
        synchronized(LOCK) {
            if (requests[id] == req) {
                requests.remove(id)
                runCatching { persistLocked() }
            }
        }
        return null
    }

    fun getRequest(id: String): PendingWorkspaceRequest? = validRequest(id)

    fun oldestPending(): PendingWorkspaceRequest? = requests.values
        .sortedBy { it.createdAt }
        .firstNotNullOfOrNull { validRequest(it.requestId) }

    fun removeRequest(id: String): PendingWorkspaceRequest? = synchronized(LOCK) {
        val res = requests.remove(id)
        if (res != null) {
            try {
                persistLocked()
            } catch (e: Exception) {
                requests[id] = res
                throw e
            }
        }
        res
    }

    fun pendingForProject(projectId: String): List<PendingWorkspaceRequest> =
        requests.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }

    /** Continue draining the durable queue after one grant/cancel completes. */
    fun launchNextPending() {
        oldestPending()?.let { launchGrantUi(it.requestId) }
    }

    fun launchGrantUi(requestId: String) {
        val context = appContext ?: return
        if (validRequest(requestId) == null) return
        try {
            val intent = Intent(context, WorkspaceGrantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        } catch (_: Exception) {
            // Background-start restrictions may defer the UI. The durable request
            // remains queued and init() will re-present it next time UI is foreground.
        }
    }
}
