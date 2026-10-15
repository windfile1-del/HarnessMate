package dev.wind.agent.history

enum class TransactionState {
    PREPARED,
    COMMITTED,
    REVERTING,
    REVERTED,
    CONFLICT
}

/**
 * Raised by an expected-before guard immediately before a canonical mutation.
 * The current path may belong to an external editor and must never be restored
 * from this transaction's older pre-image.
 */
class MutationPreconditionConflict(message: String) : IllegalStateException(message)

enum class MutationType {
    CREATE,
    MKDIR,
    EDIT,
    DELETE,
    RMDIR,
    MOVE
}

enum class BatchState {
    PREPARED,
    APPLYING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    CONFLICT
}

data class MutationBatch(
    val batchId: String,
    val rootTaskId: String,
    val recordIds: MutableList<String> = mutableListOf(),
    var state: BatchState = BatchState.PREPARED
)

data class FileFingerprint(
    val exists: Boolean,
    val isDirectory: Boolean = false,
    val size: Long = 0L,
    val sha256: String = ""
)

data class MutationRecord(
    val id: String,
    val taskId: String,
    val type: MutationType,
    val path: String,
    val targetPath: String? = null,
    val snapshotId: String? = null,
    /**
     * The workspace root this mutation happened in, as a string.
     */
    val root: String = "",
    val projectId: String = "",
    val mountId: String = "",
    val originAgent: String = "lead",
    val beforeFingerprint: FileFingerprint? = null,
    var afterFingerprint: FileFingerprint? = null,
    var state: TransactionState = TransactionState.COMMITTED,
    val timestamp: Long = System.currentTimeMillis(),
    val batchId: String? = null
)
