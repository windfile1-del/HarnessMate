package dev.wind.agent.coding

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Durable control state, behind an interface so the runtime can be tested
 * without a Context and without touching a real filesystem.
 */
interface CodingStateStore {
    fun load(taskId: String, now: Long): CodingTaskState?
    fun save(state: CodingTaskState)
}

/**
 * Durable control state for coding tasks, under the app's own filesDir.
 *
 * Not in the SAF project and not in the execution mirror: this is the harness's
 * bookkeeping about a task, and putting it inside the user's project would make
 * it something the model can edit and something the commit gate would want to
 * apply. Installed atomically with the same new/final/bak discipline P3 uses for
 * mirror state, for the same reason: a half-written control file must never be
 * readable as a whole one.
 */
class CodingTaskStore(private val context: Context) : CodingStateStore {

    private fun taskDir(taskId: String): File {
        require(taskId.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "CODING_TASK_ID_INVALID" }
        return File(File(context.filesDir, "coding_tasks"), taskId)
    }

    private fun stateFile(taskId: String): File = File(taskDir(taskId), "state.json")

    fun exists(taskId: String): Boolean = stateFile(taskId).isFile

    /**
     * Loads a task, or null when there is none.
     *
     * Corruption is NOT silently a fresh task: [CODING_STATE_CORRUPT] is thrown
     * so the caller can block rather than start writing code under the delusion
     * that nothing had been built or verified.
     */
    override fun load(taskId: String, now: Long): CodingTaskState? {
        val file = stateFile(taskId)
        val temp = File(taskDir(taskId), "state.new")
        val backup = File(taskDir(taskId), "state.bak")
        if (!file.isFile) {
            if (temp.isFile || backup.isFile) {
                throw IllegalStateException("CODING_STATE_RECOVERY_REQUIRED: a coding-task state artifact exists without its installed file")
            }
            return null
        }
        val root = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("CODING_STATE_CORRUPT: coding-task state could not be parsed", e)
        }
        val state = try {
            CodingTaskState.fromJson(root, taskId)
        } catch (e: Exception) {
            throw IllegalStateException("CODING_STATE_CORRUPT: coding-task state is not valid for this task", e)
        }
        return state.recoveredAfterInterruption(now)
    }

    /** Atomic install: write .new, fsync, move final to .bak, install .new. */
    override fun save(state: CodingTaskState) {
        val dir = taskDir(state.taskId)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("CODING_STATE_DIR_UNWRITABLE")
        }
        val file = File(dir, "state.json")
        val temp = File(dir, "state.new")
        val backup = File(dir, "state.bak")
        val payload = state.toJson().toString().toByteArray(Charsets.UTF_8)
        java.io.FileOutputStream(temp).use { out ->
            out.write(payload)
            out.flush()
            out.fd.sync()
        }
        if (!temp.isFile || temp.length() != payload.size.toLong()) {
            throw IllegalStateException("CODING_STATE_WRITE_FAILED")
        }
        if (file.isFile) {
            if (backup.exists() && !backup.delete()) {
                throw IllegalStateException("CODING_STATE_WRITE_FAILED")
            }
            if (!file.renameTo(backup)) {
                throw IllegalStateException("CODING_STATE_WRITE_FAILED")
            }
        }
        if (!temp.renameTo(file)) {
            throw IllegalStateException("CODING_STATE_WRITE_FAILED")
        }
        if (backup.isFile) {
            backup.delete()
        }
    }

    /** Removes a task directory. Only used by tests and explicit discards. */
    fun delete(taskId: String): Boolean {
        val dir = taskDir(taskId)
        val files = dir.listFiles() ?: return true
        for (item in files) {
            item.delete()
        }
        return dir.delete()
    }
}
