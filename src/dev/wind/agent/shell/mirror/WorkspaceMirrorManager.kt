package dev.wind.agent.shell.mirror

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.history.MutationJournal
import dev.wind.agent.history.MutationScope
import dev.wind.agent.history.MutationPreconditionConflict
import dev.wind.agent.shell.*
import dev.wind.agent.workspace.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Legacy V0.1 single-workspace recovery adapter. The active P3 path is
 * [ProjectMirrorManager]; this class is retained only for old on-disk data
 * and must not be wired back into ShellManager.
 */
@Deprecated("Use ProjectMirrorManager for the P3 multi-root mirror")
class WorkspaceMirrorManager(private val context: Context, private val workspaceIdOverride: String? = null) {
    companion object { val LOCK = Any() }
    private var boundId: String? = null
    private fun currentId() = workspaceIdOverride ?: WorkspaceManager.getWorkspaceId(context)
    private val id: String get() {
        val current = currentId()
        check(boundId == null || boundId == current) { "WORKSPACE_CHANGED_DURING_OPERATION" }
        return current
    }
    private fun client() = TermuxMirrorClient(context, id)
    private fun stateFile() = File(context.filesDir, "mirrors/$id/state.json")

    private fun load(): JSONObject? {
        val file = stateFile()
        if (!file.exists()) return null
        require(file.length() <= 3L * MirrorSyncBridgeServer.MAX_CONTROL_BYTES) { "MIRROR_METADATA_LIMIT" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }

    private fun save(state: JSONObject) {
        val file = stateFile()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temp = File(file.parentFile, "state.new")
        val bytes = state.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 3 * MirrorSyncBridgeServer.MAX_CONTROL_BYTES)
        FileOutputStream(temp).use { it.write(bytes); it.flush(); it.fd.sync() }
        check(temp.renameTo(file)) { "Cannot persist mirror state" }
    }

    private fun entries(manifest: WorkspaceManifest): JSONArray {
        val array = JSONArray()
        for (entry in manifest.entries.values) array.put(JSONObject()
            .put("path", entry.path).put("directory", entry.directory).put("size", entry.size)
            .put("modified", entry.modified).put("sha256", entry.sha256))
        return array
    }

    private fun manifest(array: JSONArray, generation: Long = 0): WorkspaceManifest {
        val map = linkedMapOf<String, ManifestEntry>()
        require(array.length() <= WorkspaceManifestScanner.MAX_FILES)
        for (i in 0 until array.length()) {
            val e = array.getJSONObject(i)
            val entry = ManifestEntry(e.getString("path"), e.getBoolean("directory"), e.getLong("size"),
                e.optLong("modified", 0), e.optString("sha256", "").takeIf { it.matches(Regex("[0-9a-f]{64}")) })
            require(entry.directory || entry.sha256 != null) { "Missing content hash" }
            require(map.put(entry.path, entry) == null) { "Duplicate mirror path" }
        }
        return WorkspaceManifest(id, generation, map)
    }

    private fun remote(token: CancellationToken? = null) = manifest(client().call("manifest", token = token).getJSONArray("entries"))
    private fun canonical(fs: WorkspaceFs): WorkspaceManifest {
        val imported = load()?.optJSONArray("importSources")
        val excluded = if (imported == null) emptySet() else
            (0 until imported.length()).map { imported.getString(it) }.toSet()
        return WorkspaceManifestScanner.scan(fs, id, 0, excludedPaths = excluded)
    }
    private fun empty() = WorkspaceManifest(id, 0, emptyMap())
    private fun same(a: WorkspaceManifest, b: WorkspaceManifest) = WorkspaceSyncPlanner.plan(a, b).isEmpty()

    private fun prepare(fs: WorkspaceFs, token: CancellationToken? = null): JSONObject {
        val state = load() ?: JSONObject().put("baseline", JSONArray()).put("state", "CLEAN")
        check(state.optString("state") !in listOf("BUSY", "BROKEN", "APPLYING")) {
            "MIRROR_RECOVERY_REQUIRED: interrupted operation; inspect execution files before recovery"
        }
        val baseline = manifest(state.getJSONArray("baseline"))
        val now = canonical(fs)
        val current = remote(token)
        val delta = WorkspaceSyncPlanner.plan(baseline, now)
        if (!delta.isEmpty()) {
            val pendingMirrorDiff = WorkspaceSyncPlanner.plan(baseline, current)
            check(pendingMirrorDiff.isEmpty()) { "CONFLICT: SAF changed while mirror has pending changes (" + pendingMirrorDiff.created.size + " created, " + pendingMirrorDiff.modified.size + " modified, " + pendingMirrorDiff.deleted.size + " deleted); 0 files synchronized" }
            state.put("state", "BUSY")
            save(state)
            val files = mutableListOf<() -> java.io.InputStream>()
            val transfer = JSONArray()
            for (entry in delta.created + delta.modified) {
                val obj = JSONObject().put("path", entry.path).put("directory", entry.directory)
                    .put("size", entry.size).put("sha256", entry.sha256)
                if (!entry.directory) {
                    obj.put("source", files.size)
                    files.add { fs.openInputStream(entry.path) }
                }
                transfer.put(obj)
            }
            try {
                val stats = client().call("sync", JSONObject().put("entries", transfer)
                    .put("deleted", JSONArray().apply { delta.deleted.forEach { put(it) } }), files, token)
                check(same(now, remote(token))) { "MIRROR_SYNC_VERIFICATION_FAILED" }
                check(same(now, canonical(fs))) { "CANONICAL_CHANGED_DURING_SYNC" }
                state.put("baseline", entries(now)).put("transfer", stats).put("state", "CLEAN")
                state.remove("executionId")
                save(state)
            } catch (e: Exception) {
                state.put("state", "BROKEN"); save(state); throw e
            }
        } else {
            state.put("transfer", JSONObject().put("transferredFiles", 0).put("transferredBytes", 0))
            save(state)
        }
        return state
    }

    private fun pending(state: JSONObject, taskId: String, eligible: Boolean, token: CancellationToken? = null): String {
        val current = remote(token)
        val baseline = manifest(state.getJSONArray("baseline"))
        val diff = WorkspaceSyncPlanner.plan(baseline, current)
        val execId = "mirror-" + UUID.randomUUID().toString()
        state.put("expected", entries(current)).put("executionId", execId).put("taskId", taskId)
            .put("eligible", eligible).put("state", if (diff.isEmpty()) "CLEAN" else "DIRTY")
        save(state)
        return "Execution ID: $execId\n" + summary(diff) +
            "\nSAF unchanged. " + if (eligible) "Review execution:// and use shell_apply_changes with this ID." else
            "Apply blocked after failed/cancelled/timed-out command. Repair and run a successful validation first."
    }

    private fun summary(diff: SyncPlan): String = buildString {
        append("Pending mirror diff: ${diff.created.size} created, ${diff.modified.size} modified, ${diff.deleted.size} deleted\n")
        (diff.created.map { "+ ${it.path}" } + diff.modified.map { "M ${it.path}" } + diff.deleted.map { "- $it" })
            .take(200).forEach { append(it).append('\n') }
    }

    fun execute(taskId: String, request: ShellRequest, fs: WorkspaceFs, token: CancellationToken): ShellResult = synchronized(LOCK) {
        boundId = currentId()
        ShellPolicy.validateRequest(request)
        val state = prepare(fs, token)
        state.put("state", "BUSY"); save(state)
        try {
            val cwdRel = try {
                val projectManager = dev.wind.agent.project.ProjectManager(context)
                val proj = projectManager.getActiveProject()
                proj?.primaryMount()?.executionPath ?: ""
            } catch (_: Exception) { "" }
            val result = dev.wind.agent.shell.backends.TermuxBackend(context).execute(request,
                ExecutionTarget(id, WorkspaceIdentity.remoteRoot(id) + "/project", cwdRel), token)
            val eligible = result.exitCode == 0 && !result.timedOut && !result.cancelled && result.error == null
            val description = pending(state, taskId, eligible)
            result.copy(diffSummary = description + "\nSync: " + state.optJSONObject("transfer"))
        } catch (e: Exception) {
            state.put("state", "BROKEN"); save(state); throw e
        }
    }

    fun tool(name: String, args: JSONObject, fs: WorkspaceFs, taskId: String, token: CancellationToken): String = synchronized(LOCK) {
        boundId = currentId()
        val mutating = name in setOf("execution_write_file", "execution_edit_file", "execution_delete_file", "execution_make_dir", "execution_import_archive")
        if (name == "execution_prepare") return@synchronized prepare(fs, token).optJSONObject("transfer").toString()
        if (name == "execution_recover") {
            val recovery = load() ?: error("No mirror metadata")
            check(recovery.optString("state") != "APPLYING") { "Interrupted canonical apply requires journal recovery verification" }
            return@synchronized pending(recovery, taskId, false, token)
        }
        if (name == "execution_import_archive") {
            val archive = WorkspacePath.sanitize(args.getString("workspace_path"))
            require(archive.endsWith(".zip", true) || archive.endsWith(".tar", true) || archive.endsWith(".tar.gz", true))
            val existing = load() ?: JSONObject().put("baseline", JSONArray()).put("state", "CLEAN")
            val imports = existing.optJSONArray("importSources") ?: JSONArray()
            if ((0 until imports.length()).none { imports.getString(it) == archive }) imports.put(archive)
            existing.put("importSources", imports); save(existing)
        }
        val state = if (name == "execution_import_archive") prepare(fs, token) else load() ?: error("Call execution_prepare first")
        val remoteArgs = JSONObject(args.toString())
        val raw = args.optString("path", "").removePrefix("execution://")
        remoteArgs.put("path", WorkspacePath.sanitize(raw))
        val sources = mutableListOf<() -> java.io.InputStream>()
        if (name == "execution_import_archive") {
            val path = WorkspacePath.sanitize(args.getString("workspace_path"))
            val entry = fs.stat(path) ?: error("Archive not found")
            require(!entry.isDirectory && entry.size >= 0)
            val format = when {
                path.endsWith(".zip", true) -> "zip"
                path.endsWith(".tar", true) || path.endsWith(".tar.gz", true) -> "tar"
                else -> error("ARCHIVE_FORMAT_UNSUPPORTED: zip, tar, tar.gz only")
            }
            remoteArgs.put("size", entry.size).put("format", format)
                .put("destination", WorkspacePath.sanitize(args.optString("destination", "").removePrefix("execution://")))
            sources.add { fs.openInputStream(path) }
        }
        var operation = name
        if (name == "execution_edit_file") {
            val old = client().call("fetch_text", remoteArgs, token = token).getString("text")
            val edited = TextEditEngine.apply(old, args)
            remoteArgs.put("content", edited).put("overwrite", true)
            operation = "execution_write_file"
        }
        if (mutating) { state.put("state", "BUSY"); save(state) }
        try {
            val result = client().call(operation, remoteArgs, sources, token)
            result.toString() + if (mutating) "\n" + pending(state, taskId, state.optBoolean("eligible", true)) else ""
        } catch (e: Exception) {
            if (mutating) { state.put("state", "BROKEN"); save(state) }
            throw e
        }
    }

    fun apply(executionId: String, fs: WorkspaceFs, journal: MutationJournal): String = synchronized(LOCK) {
        boundId = currentId()
        val state = load() ?: error("No pending mirror")
        check(state.optString("executionId") == executionId) { "STALE_EXECUTION_ID" }
        check(state.optString("state") in listOf("CLEAN", "DIRTY") && state.optBoolean("eligible", false)) { "APPLY_NOT_ALLOWED" }
        val baseline = manifest(state.getJSONArray("baseline"))
        val expected = manifest(state.getJSONArray("expected"))
        check(same(expected, remote())) { "STALE_MIRROR_DIFF: review current execution files again" }
        val diff = WorkspaceSyncPlanner.plan(baseline, expected)
        // This legacy recovery adapter still rejects directory deletion/type changes.
        check(diff.deleted.none { baseline.entries[it]?.directory == true }) { "UNSUPPORTED_DIRECTORY_DELETE_OR_TYPE_CHANGE" }
        synchronized(MutationJournal.MUTATION_LOCK) {
            check(id == boundId)
            // Validate the entire canonical tree before the first mutation, including new descendants.
            check(same(baseline, canonical(fs))) { "CONFLICT: live SAF differs from baseline; 0 files mutated" }
            state.put("state", "APPLYING"); save(state)
            val batchId = "mirror-apply-" + UUID.randomUUID().toString()
            val taskId = state.getString("taskId")
            val batch = journal.createBatch(batchId, taskId)
            val scope = MutationScope("", "", WorkspaceManager.getWorkspaceUri(context)?.toString() ?: "")
            fun writeEntry(entry: ManifestEntry, overwrite: Boolean) {
                val hash = entry.sha256 ?: error("MIRROR_REMOTE_HASH_MISSING")
                MirrorFileTransfer.withStagedFile(context.filesDir, TermuxMirrorClientAdapter(client()),
                    entry.path, entry.size, hash) { staged ->
                    check(journal.transactionalBinaryStream(scope, fs, taskId, entry.path, hash, entry.size,
                        overwrite, batchId = batchId, beforeWrite = {
                            check(id == boundId)
                            val live = canonical(fs).entries[entry.path]
                            val before = baseline.entries[entry.path]
                            if (live?.directory != before?.directory || live?.size != before?.size || live?.sha256 != before?.sha256)
                                throw MutationPreconditionConflict("CANONICAL_CHANGED_DURING_APPLY: ${entry.path}")
                        }) { output -> staged.inputStream().use { it.copyTo(output, 64 * 1024) } })
                }
            }
            try {
                for (path in diff.deleted) {
                    check(id == boundId)
                    check(journal.transactionalDelete(scope, fs, taskId, path, batchId = batchId) { fs.delete(path) })
                }
                for (entry in diff.created) {
                    check(id == boundId)
                    if (entry.directory) check(journal.transactionalMkdir(taskId, entry.path, batchId = batchId) { fs.mkdir(entry.path) })
                    else writeEntry(entry, false)
                }
                for (entry in diff.modified) {
                    check(id == boundId)
                    writeEntry(entry, true)
                }
                check(same(expected, remote())) { "MIRROR_CHANGED_DURING_APPLY" }
                check(same(expected, canonical(fs))) { "CANONICAL_APPLY_VERIFY_FAILED" }
                journal.commitBatch(batch)
            } catch (e: Exception) {
                journal.rollbackBatch(batch, fs)
                state.put("state", "BROKEN"); save(state)
                throw IllegalStateException("APPLY_FAILED: rollback attempted; recovery verification required", e)
            }
            state.put("baseline", entries(expected)).put("state", "CLEAN")
            state.remove("executionId"); state.remove("expected")
            save(state)
        }
        "OK: Applied verified file streams through MutationJournal. Mirror and build caches retained."
    }

    fun discard(executionId: String): String = synchronized(LOCK) {
        boundId = currentId()
        val state = load() ?: error("No pending mirror")
        check(state.optString("executionId") == executionId) { "STALE_EXECUTION_ID" }
        check(state.optString("state") in listOf("CLEAN", "DIRTY")) { "MIRROR_RECOVERY_REQUIRED" }
        val current = remote()
        check(same(manifest(state.getJSONArray("expected")), current)) { "STALE_MIRROR_DIFF" }
        val fs = WorkspaceManager.getFs(context)
        val canonical = canonical(fs)
        val delta = WorkspaceSyncPlanner.plan(current, canonical)
        val sources = mutableListOf<() -> java.io.InputStream>()
        val transfer = JSONArray()
        for (entry in delta.created + delta.modified) {
            val obj = JSONObject().put("path", entry.path).put("directory", entry.directory)
                .put("size", entry.size).put("sha256", entry.sha256)
            if (!entry.directory) { obj.put("source", sources.size); sources.add { fs.openInputStream(entry.path) } }
            transfer.put(obj)
        }
        state.put("state", "BUSY"); save(state)
        try {
            client().call("sync", JSONObject().put("entries", transfer)
                .put("deleted", JSONArray().apply { delta.deleted.forEach { put(it) } }), sources)
            check(same(canonical, remote())) { "RESET_VERIFICATION_FAILED" }
            state.remove("executionId"); state.remove("expected")
            state.put("baseline", entries(canonical)).put("state", "CLEAN").put("eligible", true); save(state)
            "Pending changes discarded; mirror restored from SAF. Build caches retained."
        } catch (e: Exception) {
            state.put("state", "BROKEN"); save(state); throw e
        }
    }
}
