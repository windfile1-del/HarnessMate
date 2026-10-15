package dev.wind.agent.shell.execution

import dev.wind.agent.shell.ExecutionDiff
import dev.wind.agent.shell.ExecutionWorkspace
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

enum class ExecutionState {
    PREPARING,
    RUNNING,
    RESULT_RECEIVED,
    CHANGES_PENDING,
    APPLYING,
    APPLIED,
    DISCARDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    INTERRUPTED
}

data class PendingExecution(
    val executionId: String,
    val taskId: String,
    val commandSummary: String,
    val diff: ExecutionDiff,
    val workspace: ExecutionWorkspace,
    val createdAt: Long = System.currentTimeMillis(),
    var state: ExecutionState = ExecutionState.CHANGES_PENDING,
    val exitCode: Int? = 0
)

/** Legacy V0.1 pending-execution registry; not used by the P3 project mirror. */
@Deprecated("The active execution path uses ProjectMirrorManager")
object ShellExecutionStore {

    private val pending = ConcurrentHashMap<String, PendingExecution>()
    private const val MAX_PENDING = 20
    private const val TTL_MS = 30 * 60 * 1000L // 30 minutes TTL

    fun put(execution: PendingExecution) {
        pruneExpired()
        pending[execution.executionId] = execution
        persistMetadata(execution)
    }

    fun persistMetadata(p: PendingExecution) {
        val obj = org.json.JSONObject()
        obj.put("executionId", p.executionId)
            obj.put("taskId", p.taskId)
            obj.put("command", p.commandSummary)
            obj.put("createdAt", p.createdAt)
            obj.put("state", p.state.name)
            if (p.exitCode != null) obj.put("exitCode", p.exitCode)
        val metaFile = File(p.workspace.stagingDir.parentFile, "metadata.json")
        metaFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        FileOutputStream(metaFile).use { fos ->
            fos.write(obj.toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    fun get(executionId: String): PendingExecution? = pending[executionId]

    fun remove(executionId: String): PendingExecution? = pending.remove(executionId)

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.createdAt > TTL_MS) {
                entry.value.workspace.cleanup()
                it.remove()
            }
        }
        if (pending.size > MAX_PENDING) {
            val oldest = pending.values.sortedBy { it.createdAt }.take(pending.size - MAX_PENDING)
            for (p in oldest) {
                p.workspace.cleanup()
                pending.remove(p.executionId)
            }
        }
    }
}
