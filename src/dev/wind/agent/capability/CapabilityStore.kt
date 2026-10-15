package dev.wind.agent.capability

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Durable TASK-scope capability storage. ONCE and APP_SESSION grants are kept
 * by the broker in memory and therefore cannot survive a process restart.
 */
class CapabilityStore private constructor(
    private val rootDir: File?,
    private val now: () -> Long
) {
    constructor(rootDir: File) : this(rootDir, { System.currentTimeMillis() })

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "capabilities"),
        { System.currentTimeMillis() }
    )

    companion object {
        fun inMemory(now: () -> Long = { System.currentTimeMillis() }): CapabilityStore =
            CapabilityStore(null, now)
    }

    private val lock = Any()
    private val memory = LinkedHashMap<String, TaskCapabilityEnvelope>()
    private val auditMemory = ArrayList<CapabilityAuditEvent>()

    fun load(taskId: String): TaskCapabilityEnvelope? = synchronized(lock) {
        val safe = safeId(taskId)
        if (rootDir == null) return@synchronized memory[safe]
        val file = taskFile(safe)
        recoverAtomic(file)
        if (!file.isFile) return@synchronized null
        return@synchronized try {
            fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            val corrupt = File(file.parentFile, file.name + ".corrupt." + now())
            file.renameTo(corrupt)
            // A rejected .new file must not allow a broader stale .bak to be
            // promoted on the next load. Leave a rejection marker until the
            // next successful save explicitly replaces the state.
            File(file.parentFile, file.name + ".new").delete()
            val backup = File(file.parentFile, file.name + ".bak")
            if (backup.exists() && !backup.delete()) {
                backup.renameTo(File(file.parentFile, file.name + ".bak.corrupt." + now()))
            }
            runCatching { rejectionMarker(file).writeText("rejected", Charsets.UTF_8) }
            null
        }
    }

    fun save(envelope: TaskCapabilityEnvelope) = synchronized(lock) {
        val safe = safeId(envelope.rootTaskId.ifBlank { envelope.taskId })
        if (rootDir == null) {
            memory[safe] = envelope
            return@synchronized
        }
        val dir = File(rootDir, "tasks")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("CAPABILITY_STORE_UNAVAILABLE: cannot create task store")
        }
        val file = File(dir, "$safe.json")
        val temp = File(dir, "$safe.json.new")
        val backup = File(dir, "$safe.json.bak")
        FileOutputStream(temp).use { output ->
            output.write(toJson(envelope).toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (backup.exists()) backup.delete()
        val hadOld = file.isFile
        if (hadOld && !file.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("CAPABILITY_STORE_UNAVAILABLE: cannot stage task envelope")
        }
        if (!temp.renameTo(file)) {
            if (hadOld) backup.renameTo(file)
            throw IllegalStateException("CAPABILITY_STORE_UNAVAILABLE: cannot install task envelope")
        }
        if (backup.exists()) backup.delete()
        // Keep the fail-closed marker until the replacement is fully installed;
        // a crash or failed rename must not reopen stale .new/.bak recovery.
        rejectionMarker(file).delete()
    }

    fun remove(taskId: String) = synchronized(lock) {
        val safe = safeId(taskId)
        memory.remove(safe)
        if (rootDir != null) {
            val file = taskFile(safe)
            file.delete()
            File(file.parentFile, file.name + ".new").delete()
            File(file.parentFile, file.name + ".bak").delete()
            rejectionMarker(file).delete()
        }
    }

    fun audit(event: CapabilityAuditEvent) = synchronized(lock) {
        // Detail is intentionally constrained to broker-generated enum/status
        // text. Never append raw args, paths, contents, or credentials here.
        val safe = event.copy(detail = event.detail.take(160))
        auditMemory.add(safe)
        if (rootDir == null) return@synchronized
        if (!rootDir.isDirectory) rootDir.mkdirs()
        val auditDir = File(rootDir, "audit")
        if (!auditDir.isDirectory) auditDir.mkdirs()
        val file = File(auditDir, "events.jsonl")
        FileOutputStream(file, true).use { output ->
            output.write(auditJson(safe).toString().toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
    }

    fun auditSnapshot(): List<CapabilityAuditEvent> = synchronized(lock) { auditMemory.toList() }

    private fun taskFile(safeTaskId: String): File = File(File(rootDir, "tasks"), "$safeTaskId.json")

    private fun rejectionMarker(file: File): File = File(file.parentFile, file.name + ".reject")

    private fun recoverAtomic(file: File) {
        val temp = File(file.parentFile, file.name + ".new")
        val backup = File(file.parentFile, file.name + ".bak")
        if (rejectionMarker(file).isFile) {
            temp.delete()
            backup.delete()
            return
        }
        if (!file.isFile) {
            when {
                temp.isFile -> temp.renameTo(file)
                backup.isFile -> backup.renameTo(file)
            }
        } else {
            temp.delete()
            backup.delete()
        }
    }

    private fun safeId(raw: String): String {
        val clean = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (clean.isEmpty()) "task" else clean.take(180)
    }

    private fun toJson(envelope: TaskCapabilityEnvelope): JSONObject {
        val mounts = JSONArray()
        envelope.mountGrants.values.sortedBy { it.mountId }.forEach { grant ->
            mounts.put(
                JSONObject()
                    .put("mountId", grant.mountId)
                    .put("read", grant.read)
                    .put("canonicalWrite", grant.canonicalWrite)
                    .put("canonicalDelete", grant.canonicalDelete)
            )
        }
        val domains = JSONArray()
        envelope.networkGrant.normalized().domains.sorted().forEach { domains.put(it) }
        val secrets = JSONArray()
        envelope.secretRefs.sorted().forEach { secrets.put(it) }
        val applyKeys = JSONArray()
        envelope.approvedApplyKeys.sorted().forEach { applyKeys.put(it) }
        val expansionKeys = JSONArray()
        envelope.approvedExpansionKeys.sorted().forEach { expansionKeys.put(it) }
        val installKeys = JSONArray()
        envelope.approvedArtifactInstallKeys.sorted().forEach { installKeys.put(it) }
        return JSONObject()
            .put("schemaVersion", 3)
            .put("taskId", envelope.taskId)
            .put("rootTaskId", envelope.rootTaskId)
            .put("projectId", envelope.projectId)
            .put("projectGeneration", envelope.projectGeneration)
            .put("revision", envelope.revision)
            .put("issuedAt", envelope.issuedAt)
            .put("expiresAt", envelope.expiresAt)
            .put("mirrorAccess", envelope.mirrorAccess.name)
            .put("executionAccess", envelope.executionAccess.name)
            .put("networkMode", envelope.networkGrant.mode.name)
            .put("domains", domains)
            .put("mountGrants", mounts)
            .put("secretRefs", secrets)
            .put("approvedApplyKeys", applyKeys)
            .put("approvedExpansionKeys", expansionKeys)
            .put("approvedArtifactInstallKeys", installKeys)
    }

    private fun fromJson(root: JSONObject): TaskCapabilityEnvelope {
        check(root.optInt("schemaVersion", -1) == 3) { "Unsupported capability envelope schema" }
        val taskId = root.optString("taskId", "").trim()
        val rootTaskId = root.optString("rootTaskId", "").trim()
        val projectId = root.optString("projectId", "").trim()
        check(taskId.isNotEmpty() && rootTaskId.isNotEmpty() && projectId.isNotEmpty()) {
            "Capability envelope identity is incomplete"
        }
        val projectGeneration = strictLong(root, "projectGeneration")
        val revision = strictLong(root, "revision")
        val issuedAt = strictLong(root, "issuedAt")
        val expiresAt = strictLong(root, "expiresAt")
        check(projectGeneration >= 0L && revision >= 0L && issuedAt >= 0L && expiresAt > 0L) {
            "Capability envelope numeric metadata is invalid"
        }
        val mounts = linkedMapOf<String, MountGrant>()
        val mountArray = root.optJSONArray("mountGrants") ?: JSONArray()
        for (i in 0 until mountArray.length()) {
            val item = mountArray.getJSONObject(i)
            val id = item.optString("mountId", "").trim()
            if (id.isNotEmpty()) {
                mounts[id] = MountGrant(
                    mountId = id,
                    read = item.optBoolean("read", false),
                    canonicalWrite = item.optBoolean("canonicalWrite", false),
                    canonicalDelete = item.optBoolean("canonicalDelete", false)
                )
            }
        }
        val domains = linkedSetOf<String>()
        val domainArray = root.optJSONArray("domains") ?: JSONArray()
        for (i in 0 until domainArray.length()) {
            NetworkGrant.normalizeDomain(domainArray.optString(i, ""))?.let { domains.add(it) }
        }
        val secrets = linkedSetOf<String>()
        val secretArray = root.optJSONArray("secretRefs") ?: JSONArray()
        for (i in 0 until secretArray.length()) {
            val ref = secretArray.optString(i, "").trim()
            if (ref.isNotEmpty()) secrets.add(ref)
        }
        val applyKeys = linkedSetOf<String>()
        val applyKeyArray = root.optJSONArray("approvedApplyKeys") ?: JSONArray()
        for (i in 0 until applyKeyArray.length()) {
            val value = applyKeyArray.optString(i, "").trim().lowercase()
            if (value.matches(Regex("[0-9a-f]{64}"))) applyKeys.add(value)
        }
        val expansionKeys = linkedSetOf<String>()
        val expansionKeyArray = root.optJSONArray("approvedExpansionKeys") ?: JSONArray()
        for (i in 0 until expansionKeyArray.length()) {
            val value = expansionKeyArray.optString(i, "").trim().lowercase()
            if (value.matches(Regex("[0-9a-f]{64}"))) expansionKeys.add(value)
        }
        val installKeys = linkedSetOf<String>()
        val installKeyArray = root.optJSONArray("approvedArtifactInstallKeys") ?: JSONArray()
        for (i in 0 until installKeyArray.length()) {
            val value = installKeyArray.optString(i, "").trim().lowercase()
            if (value.matches(Regex("[0-9a-f]{64}"))) installKeys.add(value)
        }
        val mode = try {
            NetworkMode.valueOf(root.optString("networkMode", NetworkMode.OFF.name))
        } catch (_: Exception) {
            NetworkMode.OFF
        }
        val mirror = try {
            MirrorAccess.valueOf(root.optString("mirrorAccess", MirrorAccess.NONE.name))
        } catch (_: Exception) {
            MirrorAccess.NONE
        }
        val execution = try {
            ExecutionAccess.valueOf(root.optString("executionAccess", ExecutionAccess.NONE.name))
        } catch (_: Exception) {
            ExecutionAccess.NONE
        }
        return TaskCapabilityEnvelope(
            taskId = taskId,
            rootTaskId = root.optString("rootTaskId", taskId),
            projectId = projectId,
            projectGeneration = projectGeneration,
            revision = revision,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            mountGrants = mounts,
            mirrorAccess = mirror,
            executionAccess = execution,
            networkGrant = NetworkGrant(mode, domains).normalized(),
            secretRefs = secrets,
            approvedApplyKeys = applyKeys,
            approvedExpansionKeys = expansionKeys,
            approvedArtifactInstallKeys = installKeys
        )
    }

    /** Authority metadata must be a JSON number; permissive optLong fallbacks fail open. */
    private fun strictLong(root: JSONObject, name: String): Long {
        check(root.has(name) && root.opt(name) is Number) {
            "Capability envelope numeric field is invalid: $name"
        }
        return root.getLong(name)
    }

    private fun auditJson(event: CapabilityAuditEvent): JSONObject = JSONObject()
        .put("event", event.type.name)
        .put("taskId", event.taskId)
        .put("projectId", event.projectId)
        .put("signature", event.signature)
        .put("scope", event.scope?.name ?: "")
        .put("time", event.time)
        .put("detail", event.detail)
}
