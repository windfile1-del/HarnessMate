package dev.wind.agent.capability

/**
 * Short-lived proof returned by CapabilityBroker.check(). A tool must verify it
 * immediately before its irreversible side effect; revision changes invalidate
 * all older leases (including after revoke or project generation changes).
 */
data class CapabilityLease(
    val taskId: String,
    val projectId: String,
    val envelopeRevision: Long,
    val expiresAt: Long,
    val grantId: String,
    val signature: String,
    val scope: GrantScope,
    val projectGeneration: Long
)
