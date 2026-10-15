package dev.wind.agent.capability

/**
 * Legacy network names retained for source compatibility with the V0.1
 * skeleton. P2 authority is expressed by [NetworkMode] and [NetworkGrant].
 */
enum class NetworkCapability {
    DISABLED,
    DEPENDENCY_ONLY,
    APPROVED_DOMAINS,
    FULL
}

/** Network authority granted to one task. */
enum class NetworkMode {
    OFF,
    APPROVED_DOMAINS,
    FULL_TASK,

    /** Reserved for P7; deliberately not implemented by P2. */
    DEPENDENCY_ONLY
}

data class NetworkGrant(
    val mode: NetworkMode = NetworkMode.OFF,
    val domains: Set<String> = emptySet()
) {
    fun normalized(): NetworkGrant = copy(
        domains = domains.mapNotNull { normalizeDomain(it) }.toSortedSet()
    )

    companion object {
        fun normalizeDomain(raw: String?): String? {
            val value = raw?.trim()?.lowercase()?.trimEnd('.') ?: return null
            if (value.isEmpty() || value.any { it <= ' ' || it == '/' || it == ':' || it == '@' }) {
                return null
            }
            return value
        }
    }
}

/** Whether the task may observe or mutate its recoverable execution mirror. */
enum class MirrorAccess {
    NONE,
    READ,
    MUTATE
}

/** Whether commands may run in the execution backend. */
enum class ExecutionAccess {
    NONE,
    COMMANDS
}

/** Lifetime of a capability grant. Denial is represented by CapabilityDecision. */
enum class GrantScope {
    ONCE,
    TASK,
    APP_SESSION
}

/** A mount grant is intentionally keyed by the stable mountId, never by alias. */
data class MountGrant(
    val mountId: String,
    val read: Boolean = true,
    val canonicalWrite: Boolean = false,
    val canonicalDelete: Boolean = false
)

/**
 * The authority envelope attached to one root task.
 *
 * Physical SAF authority is still enforced by WorkspaceRouter. This object is
 * only the task-level upper bound and therefore can never grant access to a
 * mount which is absent from the project or currently revoked.
 */
data class TaskCapabilityEnvelope(
    val taskId: String,
    val projectId: String = "",
    val mountGrants: Map<String, MountGrant> = emptyMap(),
    val mirrorAccess: MirrorAccess = MirrorAccess.NONE,
    val executionAccess: ExecutionAccess = ExecutionAccess.NONE,
    val networkGrant: NetworkGrant = NetworkGrant(),
    val secretRefs: Set<String> = emptySet(),
    val revision: Long = 0L,
    val issuedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = Long.MAX_VALUE,
    val projectGeneration: Long = 0L,
    val rootTaskId: String = taskId,
    /** Apply approval keys bind the complete execution plan, not only its diff. */
    val approvedApplyKeys: Set<String> = emptySet(),
    /** Expansion keys bind alias, requested mode, and execution path. */
    val approvedExpansionKeys: Set<String> = emptySet(),
    /** Exact artifact/install target approvals; never a generic write grant. */
    val approvedArtifactInstallKeys: Set<String> = emptySet()
) {
    fun canRead(mountId: String): Boolean {
        val grant = mountGrants[mountId] ?: return false
        return grant.read || grant.canonicalWrite || grant.canonicalDelete
    }

    fun canWrite(mountId: String): Boolean = mountGrants[mountId]?.canonicalWrite == true

    fun canDelete(mountId: String): Boolean = mountGrants[mountId]?.canonicalDelete == true

    /** An expiry timestamp is exclusive: at exactly expiresAt the grant is dead. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt <= now

    fun hasApplyApproval(requirement: CapabilityRequirement.ApplyChanges): Boolean =
        requirement.diffFingerprint.isNotBlank() &&
            approvedApplyKeys.contains(CapabilityApprovalKey.apply(requirement))

    fun hasExpansionApproval(requirement: CapabilityRequirement.CapabilityExpansion): Boolean =
        requirement.alias.isNotBlank() &&
            approvedExpansionKeys.contains(CapabilityApprovalKey.expansion(requirement))

    fun hasArtifactInstallApproval(requirement: CapabilityRequirement.ArtifactInstall): Boolean =
        approvedArtifactInstallKeys.contains(CapabilityApprovalKey.artifactInstall(requirement))

    /** Compatibility view for the old alias-named skeleton API. */
    @Deprecated("P2 grants use mountId; this view is only for old callers")
    val readMounts: Set<String> get() = mountGrants.values.filter { it.read }.map { it.mountId }.toSet()

    /** Compatibility view for the old alias-named skeleton API. */
    @Deprecated("P2 grants use mountId; this view is only for old callers")
    val writeMounts: Set<String> get() = mountGrants.values.filter { it.canonicalWrite }.map { it.mountId }.toSet()

    /** Compatibility view for the old execution boolean. */
    @Deprecated("Use executionAccess")
    val executionAllowed: Boolean get() = executionAccess == ExecutionAccess.COMMANDS

    /** Compatibility view for the old network enum. */
    @Deprecated("Use networkGrant")
    val networkPolicy: NetworkCapability
        get() = when (networkGrant.mode) {
            NetworkMode.OFF -> NetworkCapability.DISABLED
            NetworkMode.APPROVED_DOMAINS -> NetworkCapability.APPROVED_DOMAINS
            NetworkMode.FULL_TASK -> NetworkCapability.FULL
            NetworkMode.DEPENDENCY_ONLY -> NetworkCapability.DEPENDENCY_ONLY
        }

    /** Compatibility view for old callers; secrets are references only. */
    @Deprecated("Use secretRefs; plaintext secrets are never part of a capability")
    val grantedSecrets: Set<String> get() = secretRefs

    /**
     * Legacy constructor retained so old integrations can compile while their
     * grants are migrated. Values are treated as opaque stable identities; new
     * code must resolve aliases to mountIds before constructing an envelope.
     */
    @Deprecated("Use the P2 mountId-based constructor")
    constructor(
        taskId: String,
        readMounts: Set<String>,
        writeMounts: Set<String>,
        executionAllowed: Boolean,
        networkPolicy: NetworkCapability,
        allowedDomains: Set<String>,
        grantedSecrets: Set<String>,
        expiresAt: Long
    ) : this(
        taskId = taskId,
        projectId = "",
        mountGrants = (readMounts + writeMounts).associateWith { id ->
            MountGrant(id, read = true, canonicalWrite = writeMounts.contains(id))
        },
        mirrorAccess = if (executionAllowed) MirrorAccess.MUTATE else MirrorAccess.NONE,
        executionAccess = if (executionAllowed) ExecutionAccess.COMMANDS else ExecutionAccess.NONE,
        networkGrant = NetworkGrant(
            mode = when (networkPolicy) {
                NetworkCapability.DISABLED -> NetworkMode.OFF
                NetworkCapability.DEPENDENCY_ONLY -> NetworkMode.DEPENDENCY_ONLY
                NetworkCapability.APPROVED_DOMAINS -> NetworkMode.APPROVED_DOMAINS
                NetworkCapability.FULL -> NetworkMode.FULL_TASK
            },
            domains = allowedDomains
        ),
        secretRefs = grantedSecrets,
        expiresAt = expiresAt
    )
}
