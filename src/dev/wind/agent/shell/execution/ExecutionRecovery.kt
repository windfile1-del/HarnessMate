package dev.wind.agent.shell.execution

import android.content.Context
import dev.wind.agent.shell.ExecutionWorkspace
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-start recovery for both the legacy execution workspace and all durable
 * replacement files used by the project/mirror transaction layer.
 *
 * A crash is allowed to leave `*.new` / `*.bak` debris, but that debris must
 * never make a pending execution or WAL batch look CLEAN/non-existent on the
 * next process start. Recovery therefore runs before any Activity or Agent code
 * can mutate project topology.
 */
object ExecutionRecovery {

    fun scan(context: Context) {
        recoverDurableReplacementFiles(context)
        recoverLegacyExecutionWorkspaces(context)
    }

    /**
     * Repairs interrupted `new -> final` replacements.
     *
     * Rules are deliberately simple and deterministic:
     *  - final exists: final is authoritative; stale new/bak are cleanup only.
     *  - final missing + new exists: new is the fsynced intended state, install it.
     *  - final missing + only bak exists: restore the previous known-good state.
     *  - if a rename fails, leave the artifact in place and fail closed later;
     *    never delete the only recoverable copy.
     */
    private fun recoverReplacement(finalFile: File, newFile: File, backupFile: File? = null) {
        try {
            if (finalFile.isFile) {
                if (newFile.exists()) newFile.delete()
                if (backupFile?.exists() == true) backupFile.delete()
                return
            }

            if (newFile.isFile) {
                if (newFile.renameTo(finalFile)) {
                    if (backupFile?.exists() == true) backupFile.delete()
                    return
                }
                // Keep both candidates if installation failed. A later reader
                // must not interpret a missing final file as CLEAN/no state.
                return
            }

            if (backupFile?.isFile == true) {
                backupFile.renameTo(finalFile)
            }
        } catch (_: Exception) {
            // Recovery must never destroy the last durable copy.
        }
    }

    private fun durableReplaceJson(finalFile: File, obj: JSONObject) {
        val parent = finalFile.parentFile ?: throw IllegalStateException("Missing parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create durable-state directory")
        }
        val temp = File(parent, finalFile.name.removeSuffix(".json") + ".new")
        val backup = File(parent, finalFile.name.removeSuffix(".json") + ".bak")
        FileOutputStream(temp).use { out ->
            out.write(obj.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (backup.exists() && !backup.delete()) {
            temp.delete()
            throw IllegalStateException("Failed to remove stale backup")
        }
        val hadOld = finalFile.exists()
        if (hadOld && !finalFile.renameTo(backup)) {
            temp.delete()
            throw IllegalStateException("Failed to stage previous durable state")
        }
        if (!temp.renameTo(finalFile)) {
            if (hadOld) backup.renameTo(finalFile)
            throw IllegalStateException("Failed to install durable state")
        }
        if (backup.exists()) backup.delete()
    }

    /**
     * P1.0 sessions created before the capability-snapshot field existed must not
     * retain access to stale mirror data after an Android permission revocation.
     * Add a deliberately impossible mount snapshot so every pending legacy
     * session fails closed in ProjectMirrorManager. The user can then discard it,
     * or start a fresh session after reauthorizing capabilities.
     */
    private fun sealLegacyPendingMirrorState(projectDir: File) {
        val file = File(projectDir, "mirror_state.json")
        if (!file.isFile) return
        try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            if (obj.optInt("schemaVersion", 1) >= 2) return
            val state = obj.optString("state", "CLEAN")
            val pending = state == "BUSY" || state == "BROKEN" ||
                state == "CHANGES_PENDING" || state == "DIRTY"
            if (!pending || obj.has("sessionMounts")) return

            val sentinel = JSONArray().put(
                JSONObject()
                    .put("id", "legacy-capability-snapshot-missing")
                    .put("treeUri", "content://invalid/legacy-session")
                    .put("mode", "READ_ONLY")
                    .put("executionPath", "legacy-session")
            )
            obj.put("sessionMounts", sentinel)
            durableReplaceJson(file, obj)
        } catch (_: Exception) {
            // Leave the session untouched rather than risk corrupting recovery data.
            // ProjectMirrorManager will still treat BUSY/BROKEN as recovery-required.
        }
    }

    private fun recoverDurableReplacementFiles(context: Context) {
        // Project mirror metadata: one directory per project.
        val projectsRoot = File(context.filesDir, "projects")
        if (projectsRoot.isDirectory) {
            for (projectDir in projectsRoot.listFiles() ?: emptyArray()) {
                if (!projectDir.isDirectory) continue
                recoverReplacement(
                    File(projectDir, "mirror_state.json"),
                    File(projectDir, "mirror_state.new"),
                    File(projectDir, "mirror_state.bak")
                )
                sealLegacyPendingMirrorState(projectDir)
            }

            // Agent-requested SAF capabilities use one global durable queue.
            recoverReplacement(
                File(projectsRoot, "pending_mount_requests.json"),
                File(projectsRoot, "pending_mount_requests.new"),
                File(projectsRoot, "pending_mount_requests.bak")
            )
        }

        // Multi-root MutationJournal batches. Older builds had no .bak but did
        // leave a fully-fsynced .json.new after a crash between delete and rename.
        val batchesDir = File(context.filesDir, "journal/batches")
        if (batchesDir.isDirectory) {
            val names = mutableSetOf<String>()
            for (file in batchesDir.listFiles() ?: emptyArray()) {
                when {
                    file.name.endsWith(".json") -> names.add(file.name.removeSuffix(".json"))
                    file.name.endsWith(".json.new") -> names.add(file.name.removeSuffix(".json.new"))
                    file.name.endsWith(".json.bak") -> names.add(file.name.removeSuffix(".json.bak"))
                }
            }
            for (name in names) {
                recoverReplacement(
                    File(batchesDir, "$name.json"),
                    File(batchesDir, "$name.json.new"),
                    File(batchesDir, "$name.json.bak")
                )
            }
        }
    }

    private fun recoverLegacyExecutionWorkspaces(context: Context) {
        val root = File(context.filesDir, "execution")
        if (!root.isDirectory) return
        val now = System.currentTimeMillis()
        val dirs = root.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, "metadata.json")
            if (!metaFile.isFile) {
                dir.deleteRecursively()
                continue
            }
            try {
                val obj = JSONObject(metaFile.readText(Charsets.UTF_8))
                val createdAt = obj.optLong("createdAt", 0L)
                val stateStr = obj.optString("state", "")
                val execId = obj.optString("executionId", dir.name)
                val taskId = obj.optString("taskId", "")
                val cmd = obj.optString("command", "")
                val exitCode = if (obj.has("exitCode")) obj.getInt("exitCode") else null

                // Expired > 30 minutes (Section XXIII)
                if (now - createdAt > 30 * 60 * 1000L) {
                    dir.deleteRecursively()
                    continue
                }

                if (stateStr == ExecutionState.RUNNING.name || stateStr == ExecutionState.PREPARING.name) {
                    obj.put("state", ExecutionState.INTERRUPTED.name)
                    metaFile.writeText(obj.toString())
                    dir.deleteRecursively()
                } else if (stateStr == ExecutionState.CHANGES_PENDING.name) {
                    val ws = ExecutionWorkspace(context, execId)
                    if (ws.loadBaseline()) {
                        val diff = ws.detectDiff()
                        ShellExecutionStore.put(PendingExecution(
                            executionId = execId,
                            taskId = taskId,
                            commandSummary = cmd,
                            diff = diff,
                            workspace = ws,
                            createdAt = createdAt,
                            state = ExecutionState.CHANGES_PENDING,
                            exitCode = exitCode
                        ))
                    } else {
                        dir.deleteRecursively()
                    }
                }
            } catch (_: Exception) {
                dir.deleteRecursively()
            }
        }
    }
}
