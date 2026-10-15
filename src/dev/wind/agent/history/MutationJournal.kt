package dev.wind.agent.history

import android.content.Context
import dev.wind.agent.project.MountMode
import dev.wind.agent.project.MountState
import dev.wind.agent.project.ProjectStore
import dev.wind.agent.project.WorkspaceRouter
import dev.wind.agent.workspace.SafWorkspaceFs
import dev.wind.agent.workspace.WorkspaceFs
import dev.wind.agent.workspace.WorkspaceManager
import dev.wind.agent.workspace.WorkspacePath
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Durable write-ahead log for every canonical workspace mutation. */
class MutationJournal private constructor(private val context: Context) {

    private val snapshotStore = SnapshotStore(context)
    private val journalDir = File(context.filesDir, "journal").apply { if (!isDirectory) mkdirs() }
    private val batchesDir = File(journalDir, "batches").apply { if (!isDirectory) mkdirs() }
    private val memoryRecords = mutableListOf<MutationRecord>()
    private val memoryBatches = mutableListOf<MutationBatch>()

    init { loadAllFromDisk() }

    companion object {
        val MUTATION_LOCK = Any()
        @Volatile private var cached: MutationJournal? = null
        private const val KEEP_TASKS = 20

        fun get(context: Context): MutationJournal {
            cached?.let { return it }
            return synchronized(this) {
                cached ?: MutationJournal(context.applicationContext).also { cached = it }
            }
        }
    }

    private fun currentRoot(): String = try {
        WorkspaceManager.getWorkspaceUri(context)?.toString() ?: ""
    } catch (_: Exception) { "" }

    private fun directMode() = dev.wind.agent.permissions.AgentPermissions.get(context).strategy == dev.wind.agent.permissions.WriteStrategy.DIRECT

    /** Direct operations keep an audit trail but do not claim transactional undo. */
    private fun directMutation(scope: MutationScope, fs: WorkspaceFs, taskId: String, type: MutationType,
        path: String, target: String? = null, action: () -> Boolean): Boolean {
        val record = MutationRecord(java.util.UUID.randomUUID().toString(), taskId, type, path, target,
            root = scope.rootUri, projectId = scope.projectId, mountId = scope.mountId, originAgent = "direct",
            beforeFingerprint = computeFingerprint(fs, path), state = TransactionState.PREPARED)
        addRecord(record)
        try {
            val ok = action()
            record.afterFingerprint = safeFingerprint(fs, target ?: path)
            record.state = if (ok) TransactionState.COMMITTED else TransactionState.CONFLICT
            persistTaskJournal(taskId)
            return ok
        } catch (error: Exception) { record.state = TransactionState.CONFLICT; persistTaskJournal(taskId); throw error }
    }

    /** Project records resolve only through their recorded, currently-authorized RW mount. */
    fun resolveFsForScope(projectId: String, mountId: String): WorkspaceFs? {
        if (projectId.isNotEmpty() && mountId.isNotEmpty()) {
            return try {
                val store = ProjectStore.get(context)
                val mount = store.getProject(projectId)?.findMountById(mountId) ?: return null
                if (mount.mode != MountMode.READ_WRITE) return null
                val router = WorkspaceRouter(context, store)
                if (router.checkMountState(mount) != MountState.AVAILABLE) return null
                router.getFsForMount(mount)
            } catch (_: Exception) { null }
        }
        val legacy = WorkspaceManager.getFs(context)
        return if (legacy.isConfigured()) legacy else null
    }

    fun resolveFsForRecord(record: MutationRecord): WorkspaceFs {
        if (record.root.startsWith("file://") && record.projectId.isEmpty()) {
            val fs = dev.wind.agent.workspace.DirectWorkspaceFs(context)
            check(record.root == "file://" + fs.getRootDisplayName()) { "RECOVERY_ROOT_MISMATCH" }
            return fs
        }
        resolveFsForScope(record.projectId, record.mountId)?.let { return it }
        if (record.projectId.isNotEmpty()) {
            throw IllegalStateException(
                "RECOVERY_MOUNT_UNAVAILABLE: writable mount '${record.mountId}' for project '${record.projectId}' is unavailable"
            )
        }
        return WorkspaceManager.getFs(context)
    }

    /** Access failure is not equivalent to non-existence. */
    fun computeFingerprint(fs: WorkspaceFs, path: String): FileFingerprint {
        if (!fs.exists(path)) return FileFingerprint(exists = false)
        val stat = fs.stat(path) ?: throw IllegalStateException("Fingerprint stat failed: $path")
        if (stat.isDirectory) return FileFingerprint(true, true, 0L, "")
        val md = MessageDigest.getInstance("SHA-256")
        fs.openInputStream(path).use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return FileFingerprint(
            exists = true,
            isDirectory = false,
            size = stat.size,
            sha256 = md.digest().joinToString("") { "%02x".format(it) }
        )
    }

    private fun fingerprintBytes(bytes: ByteArray): FileFingerprint {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return FileFingerprint(true, false, bytes.size.toLong(), sha)
    }

    private fun sameFingerprint(expected: FileFingerprint?, actual: FileFingerprint?): Boolean {
        if (expected == null || actual == null) return false
        if (expected.exists != actual.exists || expected.isDirectory != actual.isDirectory) return false
        if (!expected.exists || expected.isDirectory) return true
        return expected.size == actual.size &&
            (expected.sha256.isEmpty() || expected.sha256 == actual.sha256)
    }

    private fun safeFingerprint(fs: WorkspaceFs, path: String): FileFingerprint? =
        try { computeFingerprint(fs, path) } catch (_: Exception) { null }

    private fun verifyRevertedOrConflict(record: MutationRecord, fs: WorkspaceFs) {
        record.state = if (sameFingerprint(record.beforeFingerprint, safeFingerprint(fs, record.path))) {
            TransactionState.REVERTED
        } else {
            TransactionState.CONFLICT
        }
    }

    private fun restorePreImage(record: MutationRecord, fs: WorkspaceFs, oldBytes: ByteArray?) {
        try {
            if (record.beforeFingerprint?.exists == true) {
                if (oldBytes != null) fs.write(record.path, oldBytes)
            } else if (fs.exists(record.path)) {
                fs.delete(record.path)
            }
        } catch (_: Exception) {
        }
        verifyRevertedOrConflict(record, fs)
        persistTaskJournal(record.taskId)
    }

    private fun markPreconditionConflict(record: MutationRecord) {
        // The expected-before guard has proved that the current path belongs
        // to an external writer. Never restore this record's older pre-image.
        record.state = TransactionState.CONFLICT
        persistTaskJournal(record.taskId)
    }

    private fun recoverRecordState(
        state: TransactionState,
        fs: WorkspaceFs?,
        path: String,
        before: FileFingerprint?,
        after: FileFingerprint?
    ): TransactionState {
        if (state != TransactionState.PREPARED && state != TransactionState.REVERTING) return state
        if (fs == null) return TransactionState.CONFLICT
        val current = safeFingerprint(fs, path) ?: return TransactionState.CONFLICT
        val matchesBefore = sameFingerprint(before, current)
        val matchesAfter = sameFingerprint(after, current)
        return if (state == TransactionState.REVERTING) {
            when {
                matchesBefore -> TransactionState.REVERTED
                matchesAfter -> TransactionState.COMMITTED
                else -> TransactionState.CONFLICT
            }
        } else {
            when {
                matchesAfter -> TransactionState.COMMITTED
                matchesBefore -> TransactionState.REVERTED
                else -> TransactionState.CONFLICT
            }
        }
    }

    fun loadAllFromDisk() = synchronized(MUTATION_LOCK) {
        memoryRecords.clear()
        memoryBatches.clear()

        for (file in journalDir.listFiles() ?: emptyArray()) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val type = try { MutationType.valueOf(obj.optString("type", "")) } catch (_: Exception) { continue }
                    var state = try {
                        TransactionState.valueOf(obj.optString("state", TransactionState.COMMITTED.name))
                    } catch (_: Exception) { TransactionState.CONFLICT }
                    val before = if (obj.has("before_exists")) FileFingerprint(
                        obj.optBoolean("before_exists", false),
                        obj.optBoolean("before_is_dir", false),
                        obj.optLong("before_size", 0L),
                        obj.optString("before_sha", "")
                    ) else null
                    val after = if (obj.has("after_exists")) FileFingerprint(
                        obj.optBoolean("after_exists", false),
                        obj.optBoolean("after_is_dir", false),
                        obj.optLong("after_size", 0L),
                        obj.optString("after_sha", "")
                    ) else null
                    val projectId = obj.optString("projectId", "")
                    val mountId = obj.optString("mountId", "")
                    state = recoverRecordState(
                        state,
                        if (projectId.isEmpty() && obj.optString("root").startsWith("file://")) dev.wind.agent.workspace.DirectWorkspaceFs(context) else resolveFsForScope(projectId, mountId),
                        obj.optString("path", ""),
                        before,
                        after
                    )
                    val record = MutationRecord(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        taskId = obj.optString("taskId", "default"),
                        type = type,
                        path = obj.optString("path", ""),
                        targetPath = obj.optString("targetPath", "").takeIf { it.isNotEmpty() },
                        snapshotId = obj.optString("snapshotId", "").takeIf { it.isNotEmpty() },
                        root = obj.optString("root", ""),
                        projectId = projectId,
                        mountId = mountId,
                        originAgent = obj.optString("originAgent", "lead"),
                        beforeFingerprint = before,
                        afterFingerprint = after,
                        state = state,
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        batchId = obj.optString("batchId", "").takeIf { it.isNotEmpty() }
                    )
                    if (memoryRecords.none { it.id == record.id }) memoryRecords.add(record)
                }
            } catch (_: Exception) {
                // Corrupt WAL is never permission to perform a recovery write.
            }
        }

        for (file in batchesDir.listFiles() ?: emptyArray()) {
            if (!file.isFile || !file.name.endsWith(".json")) continue
            try {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                val recordIds = mutableListOf<String>()
                val arr = obj.getJSONArray("recordIds")
                for (i in 0 until arr.length()) recordIds.add(arr.getString(i))
                val batch = MutationBatch(
                    obj.getString("batchId"),
                    obj.getString("rootTaskId"),
                    recordIds,
                    try { BatchState.valueOf(obj.getString("state")) } catch (_: Exception) { BatchState.CONFLICT }
                )
                memoryBatches.add(batch)
                if (batch.state == BatchState.APPLYING || batch.state == BatchState.ROLLING_BACK) {
                    try { rollbackBatch(batch) } catch (_: Exception) { /* remains ROLLING_BACK */ }
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---- Transactional WAL operations ---------------------------------------

    fun transactionalWrite(
        scope: MutationScope,
        fs: WorkspaceFs,
        taskId: String,
        path: String,
        content: String,
        overwrite: Boolean,
        originAgent: String = "lead",
        batchId: String? = null,
        action: () -> Unit
    ): Boolean = synchronized(MUTATION_LOCK) {
        val cleanPath = WorkspacePath.sanitize(path)
        val exists = fs.exists(cleanPath)
        if (exists && !overwrite) throw IllegalStateException("Destination already exists: $cleanPath")
        if (directMode()) return@synchronized directMutation(scope, fs, taskId, if (exists) MutationType.EDIT else MutationType.CREATE, cleanPath) { action(); true }
        val newBytes = content.toByteArray(Charsets.UTF_8)
        if (newBytes.size > SafWorkspaceFs.MAX_FILE_SIZE_BYTES) throw IllegalStateException("File content exceeds 16MB limit")
        val oldBytes = if (exists) {
            val stat = fs.stat(cleanPath) ?: throw IllegalStateException("Existing file disappeared: $cleanPath")
            if (stat.isDirectory) throw IllegalStateException("Cannot overwrite a directory: $cleanPath")
            if (stat.size > SafWorkspaceFs.MAX_FILE_SIZE_BYTES) throw IllegalStateException("Existing file exceeds 16MB limit")
            fs.read(cleanPath)
        } else null
        val record = MutationRecord(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            type = if (exists) MutationType.EDIT else MutationType.CREATE,
            path = cleanPath,
            snapshotId = oldBytes?.let(snapshotStore::createSnapshot),
            root = scope.rootUri.ifEmpty { currentRoot() },
            projectId = scope.projectId,
            mountId = scope.mountId,
            originAgent = originAgent,
            beforeFingerprint = computeFingerprint(fs, cleanPath),
            state = TransactionState.PREPARED,
            batchId = batchId
        )
        addRecordToWal(record, batchId)
        try { action() } catch (e: Exception) {
            if (e is MutationPreconditionConflict) markPreconditionConflict(record)
            else restorePreImage(record, fs, oldBytes)
            throw e
        }
        val after = safeFingerprint(fs, cleanPath)
        if (!sameFingerprint(fingerprintBytes(newBytes), after)) {
            restorePreImage(record, fs, oldBytes)
            throw IllegalStateException("Post-mutation verification failed: written content mismatch")
        }
        record.afterFingerprint = after
        record.state = TransactionState.COMMITTED
        persistTaskJournal(taskId)
        true
    }

    fun transactionalWrite(taskId: String, path: String, content: String, overwrite: Boolean, originAgent: String = "lead", batchId: String? = null, action: () -> Unit): Boolean =
        transactionalWrite(MutationScope("", "", currentRoot()), WorkspaceManager.getFs(context), taskId, path, content, overwrite, originAgent, batchId, action)

    fun transactionalBinaryStream(
        scope: MutationScope,
        fs: WorkspaceFs,
        taskId: String,
        path: String,
        expectedSha256: String?,
        expectedSize: Long?,
        overwrite: Boolean,
        originAgent: String = "lead",
        batchId: String? = null,
        beforeWrite: () -> Unit = {},
        streamAction: (java.io.OutputStream) -> Unit
    ): Boolean = synchronized(MUTATION_LOCK) {
        require(expectedSize == null || expectedSize >= 0) { "Invalid expected stream size" }
        require(expectedSha256 == null || expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid expected stream hash" }
        val cleanPath = WorkspacePath.sanitize(path)
        val exists = fs.exists(cleanPath)
        if (exists && !overwrite) throw IllegalStateException("Destination already exists: $cleanPath")
        val beforeFp = computeFingerprint(fs, cleanPath)
        val snapshotId = if (exists) {
            val stat = fs.stat(cleanPath) ?: throw IllegalStateException("Existing file disappeared: $cleanPath")
            if (stat.isDirectory) throw IllegalStateException("Cannot overwrite a directory: $cleanPath")
            snapshotPreImage(fs, cleanPath, beforeFp)
        } else null

        val record = MutationRecord(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            type = if (exists) MutationType.EDIT else MutationType.CREATE,
            path = cleanPath,
            snapshotId = snapshotId,
            root = scope.rootUri.ifEmpty { currentRoot() },
            projectId = scope.projectId,
            mountId = scope.mountId,
            originAgent = originAgent,
            beforeFingerprint = beforeFp,
            // A completed stream can be recognized after a crash before COMMITTED is persisted.
            // Partial/unknown contents still recover as a conflict.
            afterFingerprint = if (expectedSize != null && expectedSha256 != null)
                FileFingerprint(true, false, expectedSize, expectedSha256.lowercase()) else null,
            state = TransactionState.PREPARED,
            batchId = batchId
        )
        addRecordToWal(record, batchId)
        // A rejected guard has not touched the destination. Do not restore over an external edit.
        try {
            beforeWrite()
            if (!sameFingerprint(beforeFp, safeFingerprint(fs, cleanPath)))
                throw MutationPreconditionConflict("CANONICAL_CHANGED_DURING_APPLY: $cleanPath")
        } catch (e: Exception) {
            if (e is MutationPreconditionConflict) markPreconditionConflict(record)
            else { verifyRevertedOrConflict(record, fs); persistTaskJournal(taskId) }
            throw e
        }
        try {
            fs.openOutputStream(cleanPath).use { output ->
                streamAction(output)
            }
        } catch (e: Exception) {
            if (e is MutationPreconditionConflict) markPreconditionConflict(record)
            else restorePreImageFromSnapshot(record, fs, snapshotId)
            throw e
        }
        val after = safeFingerprint(fs, cleanPath)
        if (after == null || !after.exists) {
            restorePreImageFromSnapshot(record, fs, snapshotId)
            throw IllegalStateException("Post-mutation verification failed: file does not exist")
        }
        if (expectedSize != null && after.size != expectedSize) {
            restorePreImageFromSnapshot(record, fs, snapshotId)
            throw IllegalStateException("Post-mutation verification failed: size mismatch (expected $expectedSize, actual ${after.size})")
        }
        if (expectedSha256 != null && !expectedSha256.equals(after.sha256, ignoreCase = true)) {
            restorePreImageFromSnapshot(record, fs, snapshotId)
            throw IllegalStateException("Post-mutation verification failed: sha256 mismatch")
        }
        record.afterFingerprint = after
        record.state = TransactionState.COMMITTED
        persistTaskJournal(taskId)
        true
    }

    private fun restorePreImageFromSnapshot(record: MutationRecord, fs: WorkspaceFs, snapshotId: String?) {
        try {
            if (record.beforeFingerprint?.exists == true) {
                if (snapshotId != null) {
                    snapshotStore.openSnapshotStream(snapshotId)?.use { input ->
                        fs.openOutputStream(record.path).use { out -> input.copyTo(out) }
                    }
                }
            } else if (fs.exists(record.path)) {
                fs.delete(record.path)
            }
        } catch (_: Exception) {
        }
        verifyRevertedOrConflict(record, fs)
        persistTaskJournal(record.taskId)
    }

    private fun restoreSnapshot(record: MutationRecord, fs: WorkspaceFs): Boolean {
        val input = record.snapshotId?.let(snapshotStore::openSnapshotStream) ?: return false
        input.use { source -> fs.openOutputStream(record.path).use { source.copyTo(it, 64 * 1024) } }
        return true
    }

    private fun snapshotPreImage(fs: WorkspaceFs, path: String, before: FileFingerprint): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val id = fs.openInputStream(path).use { input ->
            java.security.DigestInputStream(input, digest).use { snapshotStore.createSnapshot(it) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (hash != before.sha256) {
            snapshotStore.deleteSnapshot(id)
            throw MutationPreconditionConflict("CANONICAL_CHANGED_DURING_SNAPSHOT: $path")
        }
        return id
    }

    fun transactionalEdit(
        scope: MutationScope,
        fs: WorkspaceFs,
        taskId: String,
        path: String,
        newContent: String,
        originAgent: String = "lead",
        batchId: String? = null,
        action: () -> Unit
    ): Boolean = synchronized(MUTATION_LOCK) {
        val cleanPath = WorkspacePath.sanitize(path)
        val stat = fs.stat(cleanPath) ?: throw IllegalStateException("File not found: $cleanPath")
        if (stat.isDirectory) throw IllegalStateException("Cannot edit a directory: $cleanPath")
        if (directMode()) return@synchronized directMutation(scope, fs, taskId, MutationType.EDIT, cleanPath) { action(); true }
        if (stat.size > SafWorkspaceFs.MAX_FILE_SIZE_BYTES) throw IllegalStateException("File size exceeds 16MB editable limit")
        val newBytes = newContent.toByteArray(Charsets.UTF_8)
        if (newBytes.size > SafWorkspaceFs.MAX_FILE_SIZE_BYTES) throw IllegalStateException("Edited content exceeds 16MB limit")
        val oldBytes = fs.read(cleanPath)
        val record = MutationRecord(
            id = UUID.randomUUID().toString(), taskId = taskId, type = MutationType.EDIT,
            path = cleanPath, snapshotId = snapshotStore.createSnapshot(oldBytes),
            root = scope.rootUri.ifEmpty { currentRoot() }, projectId = scope.projectId, mountId = scope.mountId,
            originAgent = originAgent, beforeFingerprint = computeFingerprint(fs, cleanPath),
            state = TransactionState.PREPARED, batchId = batchId
        )
        addRecordToWal(record, batchId)
        try { action() } catch (e: Exception) {
            if (e is MutationPreconditionConflict) markPreconditionConflict(record)
            else restorePreImage(record, fs, oldBytes)
            throw e
        }
        val after = safeFingerprint(fs, cleanPath)
        if (!sameFingerprint(fingerprintBytes(newBytes), after)) {
            restorePreImage(record, fs, oldBytes)
            throw IllegalStateException("Post-mutation verification failed: edited content mismatch")
        }
        record.afterFingerprint = after
        record.state = TransactionState.COMMITTED
        persistTaskJournal(taskId)
        true
    }

    fun transactionalEdit(taskId: String, path: String, newContent: String, originAgent: String = "lead", batchId: String? = null, action: () -> Unit): Boolean =
        transactionalEdit(MutationScope("", "", currentRoot()), WorkspaceManager.getFs(context), taskId, path, newContent, originAgent, batchId, action)

    fun transactionalDelete(
        scope: MutationScope,
        fs: WorkspaceFs,
        taskId: String,
        path: String,
        originAgent: String = "lead",
        batchId: String? = null,
        action: () -> Boolean
    ): Boolean = synchronized(MUTATION_LOCK) {
        val cleanPath = WorkspacePath.sanitize(path)
        if (directMode()) return@synchronized directMutation(scope, fs, taskId, MutationType.DELETE, cleanPath, action = action)
        val stat = fs.stat(cleanPath) ?: return@synchronized false
        if (stat.isDirectory) throw IllegalArgumentException("Deleting directories is prohibited in V0.1 Safe Alpha")
        val before = computeFingerprint(fs, cleanPath)
        val snapshotId = try {
            snapshotPreImage(fs, cleanPath, before)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read pre-image before deletion: ${e.message}", e)
        }
        val record = MutationRecord(
            id = UUID.randomUUID().toString(), taskId = taskId, type = MutationType.DELETE,
            path = cleanPath, snapshotId = snapshotId,
            root = scope.rootUri.ifEmpty { currentRoot() }, projectId = scope.projectId, mountId = scope.mountId,
            originAgent = originAgent, beforeFingerprint = before,
            state = TransactionState.PREPARED, batchId = batchId
        )
        addRecordToWal(record, batchId)
        val ok = try {
            if (!sameFingerprint(before, safeFingerprint(fs, cleanPath)))
                throw MutationPreconditionConflict("CANONICAL_CHANGED_DURING_DELETE: $cleanPath")
            action()
        } catch (e: Exception) {
            if (e is MutationPreconditionConflict) markPreconditionConflict(record)
            else restorePreImageFromSnapshot(record, fs, snapshotId)
            throw e
        }
        if (!ok) {
            restorePreImageFromSnapshot(record, fs, snapshotId)
            return@synchronized false
        }
        val after = safeFingerprint(fs, cleanPath)
        if (after == null || after.exists) {
            restorePreImageFromSnapshot(record, fs, snapshotId)
            throw IllegalStateException("Post-mutation verification failed: delete not verified")
        }
        record.afterFingerprint = after
        record.state = TransactionState.COMMITTED
        persistTaskJournal(taskId)
        true
    }

    fun transactionalDelete(taskId: String, path: String, originAgent: String = "lead", batchId: String? = null, action: () -> Boolean): Boolean =
        transactionalDelete(MutationScope("", "", currentRoot()), WorkspaceManager.getFs(context), taskId, path, originAgent, batchId, action)

    fun transactionalMove(
        scope: MutationScope,
        fs: WorkspaceFs,
        taskId: String,
        sourcePath: String,
        destPath: String,
        originAgent: String = "lead",
        action: () -> Boolean
    ): Boolean = synchronized(MUTATION_LOCK) {
        val cleanSrc = WorkspacePath.sanitize(sourcePath)
        val cleanDst = WorkspacePath.sanitize(destPath)
        if (cleanSrc == cleanDst) return@synchronized true
        val before = computeFingerprint(fs, cleanSrc)
        if (!before.exists) return@synchronized false
        if (fs.exists(cleanDst)) throw IllegalStateException("Move destination already exists: $cleanDst")
        if (directMode()) return@synchronized directMutation(scope, fs, taskId, MutationType.MOVE, cleanSrc, cleanDst, action)
        val record = MutationRecord(
            id = UUID.randomUUID().toString(), taskId = taskId, type = MutationType.MOVE,
            path = cleanSrc, targetPath = cleanDst,
            root = scope.rootUri.ifEmpty { currentRoot() }, projectId = scope.projectId, mountId = scope.mountId,
            originAgent = originAgent, beforeFingerprint = before, state = TransactionState.PREPARED
        )
        addRecord(record)
        val ok = try { action() } catch (e: Exception) {
            try { if (!fs.exists(cleanSrc) && fs.exists(cleanDst)) fs.move(cleanDst, cleanSrc) } catch (_: Exception) {}
            verifyRevertedOrConflict(record, fs)
            persistTaskJournal(taskId)
            throw e
        }
        if (!ok) {
            verifyRevertedOrConflict(record, fs)
            persistTaskJournal(taskId)
            return@synchronized false
        }
        val srcAfter = safeFingerprint(fs, cleanSrc)
        val dstAfter = safeFingerprint(fs, cleanDst)
        if (srcAfter == null || dstAfter == null || srcAfter.exists || !sameFingerprint(before, dstAfter)) {
            try { if (!fs.exists(cleanSrc) && fs.exists(cleanDst)) fs.move(cleanDst, cleanSrc) } catch (_: Exception) {}
            verifyRevertedOrConflict(record, fs)
            persistTaskJournal(taskId)
            throw IllegalStateException("Post-mutation verification failed: move result mismatch")
        }
        record.afterFingerprint = dstAfter
        record.state = TransactionState.COMMITTED
        persistTaskJournal(taskId)
        true
    }

    fun transactionalMove(taskId: String, sourcePath: String, destPath: String, originAgent: String = "lead", action: () -> Boolean): Boolean =
        transactionalMove(MutationScope("", "", currentRoot()), WorkspaceManager.getFs(context), taskId, sourcePath, destPath, originAgent, action)

    fun transactionalMkdir(
        scope: MutationScope,
        fs: WorkspaceFs,
        taskId: String,
        path: String,
        originAgent: String = "lead",
        batchId: String? = null,
        action: () -> Boolean
    ): Boolean = synchronized(MUTATION_LOCK) {
        val cleanPath = WorkspacePath.sanitize(path)
        if (directMode()) return@synchronized directMutation(scope, fs, taskId, MutationType.MKDIR, cleanPath, action = action)
        val before = computeFingerprint(fs, cleanPath)
        if (before.exists) {
            if (before.isDirectory) return@synchronized true
            throw IllegalStateException("Cannot create directory over file: $cleanPath")
        }
        val record = MutationRecord(
            id = UUID.randomUUID().toString(), taskId = taskId, type = MutationType.MKDIR,
            path = cleanPath, root = scope.rootUri.ifEmpty { currentRoot() },
            projectId = scope.projectId, mountId = scope.mountId, originAgent = originAgent,
            beforeFingerprint = before, state = TransactionState.PREPARED, batchId = batchId
        )
        addRecordToWal(record, batchId)
        val ok = try { action() } catch (e: Exception) {
            verifyRevertedOrConflict(record, fs)
            persistTaskJournal(taskId)
            throw e
        }
        if (!ok) {
            verifyRevertedOrConflict(record, fs)
            persistTaskJournal(taskId)
            return@synchronized false
        }
        val after = safeFingerprint(fs, cleanPath)
        if (after == null || !after.exists || !after.isDirectory) {
            try { if (fs.exists(cleanPath) && fs.list(cleanPath).isEmpty()) fs.delete(cleanPath) } catch (_: Exception) {}
            verifyRevertedOrConflict(record, fs)
            persistTaskJournal(taskId)
            throw IllegalStateException("Post-mutation verification failed: directory was not created")
        }
        record.afterFingerprint = after
        record.state = TransactionState.COMMITTED
        persistTaskJournal(taskId)
        true
    }

    fun transactionalMkdir(taskId: String, path: String, originAgent: String = "lead", batchId: String? = null, action: () -> Boolean): Boolean =
        transactionalMkdir(MutationScope("", "", currentRoot()), WorkspaceManager.getFs(context), taskId, path, originAgent, batchId, action)

    fun transactionalRmdir(scope: MutationScope, fs: WorkspaceFs, taskId: String, path: String,
        batchId: String? = null, action: () -> Boolean): Boolean = synchronized(MUTATION_LOCK) {
        val clean = WorkspacePath.sanitize(path)
        require(clean.isNotBlank() && fs.stat(clean)?.isDirectory == true && fs.list(clean).isEmpty()) { "DIRECTORY_NOT_EMPTY_OR_ROOT" }
        if (directMode()) return@synchronized directMutation(scope, fs, taskId, MutationType.RMDIR, clean, action = action)
        val record = MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.RMDIR, clean,
            root = scope.rootUri, projectId = scope.projectId, mountId = scope.mountId,
            beforeFingerprint = computeFingerprint(fs, clean), state = TransactionState.PREPARED, batchId = batchId)
        addRecordToWal(record, batchId)
        try {
            check(action() && !fs.exists(clean)) { "DIRECTORY_DELETE_FAILED" }
            record.afterFingerprint = computeFingerprint(fs, clean); record.state = TransactionState.COMMITTED; persistTaskJournal(taskId); true
        } catch (error: Exception) {
            runCatching { fs.mkdir(clean) }; verifyRevertedOrConflict(record, fs); persistTaskJournal(taskId); throw error
        }
    }

    private fun addRecordToWal(record: MutationRecord, batchId: String?) {
        addRecord(record)
        if (batchId != null) {
            val batch = memoryBatches.firstOrNull { it.batchId == batchId }
                ?: throw IllegalStateException("Mutation batch not found: $batchId")
            batch.recordIds.add(record.id)
            persistBatch(batch)
        }
    }

    // ---- Legacy direct-record APIs ------------------------------------------

    fun committedCreate(taskId: String, path: String) = synchronized(MUTATION_LOCK) {
        val fs = WorkspaceManager.getFs(context)
        addRecord(MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.CREATE, path,
            root = currentRoot(), beforeFingerprint = FileFingerprint(false),
            afterFingerprint = computeFingerprint(fs, path), state = TransactionState.COMMITTED))
    }

    fun committedMkdir(taskId: String, path: String) = synchronized(MUTATION_LOCK) {
        addRecord(MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.MKDIR, path,
            root = currentRoot(), beforeFingerprint = FileFingerprint(false),
            afterFingerprint = FileFingerprint(true, true), state = TransactionState.COMMITTED))
    }

    fun committedEdit(taskId: String, path: String, oldBytes: ByteArray) = synchronized(MUTATION_LOCK) {
        val fs = WorkspaceManager.getFs(context)
        addRecord(MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.EDIT, path,
            snapshotId = snapshotStore.createSnapshot(oldBytes), root = currentRoot(),
            beforeFingerprint = fingerprintBytes(oldBytes), afterFingerprint = computeFingerprint(fs, path),
            state = TransactionState.COMMITTED))
    }

    fun committedDelete(taskId: String, path: String, oldBytes: ByteArray) = synchronized(MUTATION_LOCK) {
        addRecord(MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.DELETE, path,
            snapshotId = snapshotStore.createSnapshot(oldBytes), root = currentRoot(),
            beforeFingerprint = fingerprintBytes(oldBytes), afterFingerprint = FileFingerprint(false),
            state = TransactionState.COMMITTED))
    }

    fun committedRmdir(taskId: String, path: String) = synchronized(MUTATION_LOCK) {
        addRecord(MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.RMDIR, path,
            root = currentRoot(), beforeFingerprint = FileFingerprint(true, true),
            afterFingerprint = FileFingerprint(false), state = TransactionState.COMMITTED))
    }

    fun committedMove(taskId: String, sourcePath: String, destPath: String) = synchronized(MUTATION_LOCK) {
        val fs = WorkspaceManager.getFs(context)
        addRecord(MutationRecord(UUID.randomUUID().toString(), taskId, MutationType.MOVE, sourcePath,
            targetPath = destPath, root = currentRoot(), afterFingerprint = computeFingerprint(fs, destPath),
            state = TransactionState.COMMITTED))
    }

    private fun addRecord(record: MutationRecord) {
        memoryRecords.add(record)
        try {
            persistTaskJournal(record.taskId)
        } catch (e: Exception) {
            memoryRecords.remove(record)
            record.snapshotId?.let(snapshotStore::deleteSnapshot)
            throw e
        }
        pruneOldJournals()
    }

    fun getTaskRecords(taskId: String): List<MutationRecord> = memoryRecords.filter { it.taskId == taskId }

    fun createBatch(batchId: String, rootTaskId: String): MutationBatch = synchronized(MUTATION_LOCK) {
        val batch = MutationBatch(batchId, rootTaskId)
        batch.state = BatchState.APPLYING
        memoryBatches.add(batch)
        try { persistBatch(batch) } catch (e: Exception) { memoryBatches.remove(batch); throw e }
        batch
    }

    fun commitBatch(batch: MutationBatch) = synchronized(MUTATION_LOCK) {
        batch.state = BatchState.COMMITTED
        persistBatch(batch)
    }

    @Deprecated("Batch rollback resolves each record's own project mount")
    fun rollbackBatch(batch: MutationBatch, fs: WorkspaceFs) = synchronized(MUTATION_LOCK) { rollbackBatch(batch) }

    fun rollbackBatch(batch: MutationBatch) = synchronized(MUTATION_LOCK) {
        batch.state = BatchState.ROLLING_BACK
        persistBatch(batch)
        var hadConflict = false
        for (recordId in batch.recordIds.reversed()) {
            val record = memoryRecords.firstOrNull { it.id == recordId }
                ?: throw IllegalStateException("Rollback record missing: $recordId")
            // PREPARED/CONFLICT records have not established a successful
            // mutation, or their path was changed by an external writer. They
            // must not be restored from an older pre-image during batch cleanup.
            if (record.state != TransactionState.COMMITTED) {
                hadConflict = hadConflict || record.state == TransactionState.CONFLICT
                continue
            }
            if (!undoRecord(record, resolveFsForRecord(record))) hadConflict = true
        }
        batch.state = if (hadConflict) BatchState.CONFLICT else BatchState.ROLLED_BACK
        persistBatch(batch)
    }

    private fun persistBatch(batch: MutationBatch) {
        val obj = JSONObject()
            .put("batchId", batch.batchId)
            .put("rootTaskId", batch.rootTaskId)
            .put("state", batch.state.name)
        val ids = JSONArray()
        batch.recordIds.forEach { ids.put(it) }
        obj.put("recordIds", ids)
        val file = File(batchesDir, "${batch.batchId}.json")
        val temp = File(batchesDir, "${batch.batchId}.json.new")
        FileOutputStream(temp).use { fos ->
            fos.write(obj.toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            temp.delete()
            throw IllegalStateException("Failed to replace mutation batch journal")
        }
        if (!temp.renameTo(file)) throw IllegalStateException("Failed to install mutation batch journal")
    }

    fun undoRecord(record: MutationRecord, fs: WorkspaceFs): Boolean {
        if (record.originAgent == "direct") return false
        if (record.state == TransactionState.REVERTED) return true
        if (record.projectId.isEmpty() && record.root.isNotEmpty() && !record.root.startsWith("file://") && record.root != currentRoot()) return false
        record.afterFingerprint?.let { expected ->
            val checkPath = if (record.type == MutationType.MOVE) record.targetPath ?: record.path else record.path
            if (!sameFingerprint(expected, safeFingerprint(fs, checkPath))) {
                record.state = TransactionState.CONFLICT
                persistTaskJournal(record.taskId)
                return false
            }
        }
        record.state = TransactionState.REVERTING
        persistTaskJournal(record.taskId)
        val ok = try {
            when (record.type) {
                MutationType.CREATE -> !fs.exists(record.path) || fs.delete(record.path)
                MutationType.MKDIR -> {
                    if (record.beforeFingerprint?.exists == true) true
                    else !fs.exists(record.path) || (fs.list(record.path).isEmpty() && fs.delete(record.path))
                }
                MutationType.EDIT, MutationType.DELETE -> {
                    restoreSnapshot(record, fs)
                }
                MutationType.RMDIR -> fs.mkdir(record.path)
                MutationType.MOVE -> record.targetPath?.let { fs.move(it, record.path) } ?: false
            }
        } catch (_: Exception) { false }
        if (ok) verifyRevertedOrConflict(record, fs) else record.state = TransactionState.CONFLICT
        persistTaskJournal(record.taskId)
        return record.state == TransactionState.REVERTED
    }

    fun canUndo(taskId: String): Boolean =
        taskId.isNotEmpty() && memoryRecords.any { it.taskId == taskId && it.originAgent != "direct" && it.state != TransactionState.REVERTED }

    fun undoTask(taskId: String): Boolean {
        return synchronized(MUTATION_LOCK) {
            val records = getTaskRecords(taskId).filter { it.originAgent != "direct" && it.state != TransactionState.REVERTED }.reversed()
            if (records.isEmpty()) return@synchronized false
            var allSucceeded = true

            for (record in records) {
                val fs = try {
                    resolveFsForRecord(record)
                } catch (_: Exception) {
                    allSucceeded = false
                    continue
                }

                record.afterFingerprint?.let { expected ->
                    val checkPath = if (record.type == MutationType.MOVE) record.targetPath ?: record.path else record.path
                    if (!sameFingerprint(expected, safeFingerprint(fs, checkPath))) {
                        record.state = TransactionState.CONFLICT
                        persistTaskJournal(taskId)
                        allSucceeded = false
                        return@let
                    }
                }
                if (record.state == TransactionState.CONFLICT) continue

                record.state = TransactionState.REVERTING
                persistTaskJournal(taskId)
                var stepOk = false
                try {
                    when (record.type) {
                        MutationType.CREATE -> {
                            stepOk = !fs.exists(record.path) || fs.delete(record.path)
                        }
                        MutationType.MKDIR -> {
                            stepOk = if (record.beforeFingerprint?.exists == true) true
                            else !fs.exists(record.path) || (fs.list(record.path).isEmpty() && fs.delete(record.path))
                        }
                        MutationType.EDIT, MutationType.DELETE -> {
                            stepOk = restoreSnapshot(record, fs)
                        }
                        MutationType.RMDIR -> stepOk = fs.mkdir(record.path)
                        MutationType.MOVE -> {
                            val target = record.targetPath
                            if (target != null) stepOk = fs.move(target, record.path)
                        }
                    }
                } catch (_: Exception) {
                    stepOk = false
                }

                if (stepOk) {
                    verifyRevertedOrConflict(record, fs)
                    stepOk = record.state == TransactionState.REVERTED
                } else {
                    record.state = TransactionState.CONFLICT
                }
                persistTaskJournal(taskId)
                if (!stepOk) allSucceeded = false
            }

            if (allSucceeded) consume(taskId)
            allSucceeded
        }
    }

    private fun consume(taskId: String) {
        val records = getTaskRecords(taskId)
        records.forEach { it.snapshotId?.let(snapshotStore::deleteSnapshot) }
        memoryRecords.removeAll { it.taskId == taskId }
        try { File(journalDir, "$taskId.json").delete() } catch (_: Exception) {}
    }

    private fun pruneOldJournals() {
        try {
            val files = (journalDir.listFiles() ?: return).filter { it.isFile && it.name.endsWith(".json") }
            if (files.size <= KEEP_TASKS) return
            for (file in files.sortedByDescending { it.lastModified() }.drop(KEEP_TASKS)) {
                val taskId = file.name.removeSuffix(".json")
                getTaskRecords(taskId).forEach { it.snapshotId?.let(snapshotStore::deleteSnapshot) }
                memoryRecords.removeAll { it.taskId == taskId }
                file.delete()
            }
        } catch (_: Exception) {}
    }

    private fun persistTaskJournal(taskId: String) {
        val array = JSONArray()
        for (record in getTaskRecords(taskId)) {
            val obj = JSONObject()
                .put("id", record.id)
                .put("taskId", record.taskId)
                .put("type", record.type.name)
                .put("path", record.path)
                .put("targetPath", record.targetPath ?: "")
                .put("snapshotId", record.snapshotId ?: "")
                .put("root", record.root)
                .put("projectId", record.projectId)
                .put("mountId", record.mountId)
                .put("originAgent", record.originAgent)
                .put("state", record.state.name)
                .put("timestamp", record.timestamp)
                .put("batchId", record.batchId ?: "")
            record.beforeFingerprint?.let {
                obj.put("before_exists", it.exists).put("before_is_dir", it.isDirectory)
                    .put("before_size", it.size).put("before_sha", it.sha256)
            }
            record.afterFingerprint?.let {
                obj.put("after_exists", it.exists).put("after_is_dir", it.isDirectory)
                    .put("after_size", it.size).put("after_sha", it.sha256)
            }
            array.put(obj)
        }

        val tmpFile = File(journalDir, "$taskId.json.tmp")
        val finalFile = File(journalDir, "$taskId.json")
        try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(array.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw IllegalStateException("Journal persistence durability failure: ${e.message}", e)
        }
    }
}
