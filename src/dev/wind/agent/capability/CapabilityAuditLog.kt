package dev.wind.agent.capability

enum class CapabilityAuditEventType {
    REQUEST,
    GRANT,
    DENY,
    REVOKE,
    EXPIRE,
    TASK_END
}

/** Authority lifecycle only; never contains tool args, file contents or secrets. */
data class CapabilityAuditEvent(
    val type: CapabilityAuditEventType,
    val taskId: String,
    val projectId: String,
    val signature: String,
    val scope: GrantScope? = null,
    val time: Long,
    val detail: String = ""
)
