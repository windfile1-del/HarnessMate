package dev.wind.agent.capability

import android.content.Context
import dev.wind.agent.project.MountMode
import dev.wind.agent.project.MountState
import dev.wind.agent.project.ProjectStore
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.WorkspaceMount
import dev.wind.agent.project.WorkspaceRouter
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Capability broker contract used by AgentEngine and tool integrations. */
interface CapabilityBroker {
    fun check(context: TaskContext, requirement: CapabilityRequirement): CapabilityCheck =
        CapabilityCheck.Denied("CAPABILITY_BROKER_UNAVAILABLE")

    fun grant(requestId: String, decision: CapabilityDecision): CapabilityLease? = null

    fun revoke(grantId: String): Boolean = false

    /** Revokes durable/one-shot grants owned by one root task. */
    fun revokeTask(taskId: String, kind: CapabilityRevocation): Boolean = false

    fun endTask(taskId: String) {}

    @Throws(CapabilityException::class)
    fun verify(lease: CapabilityLease): CapabilityLease =
        throw CapabilityDeniedException("CAPABILITY_BROKER_UNAVAILABLE")

    /** Verify the lease and bind it to the requirement at the current caller. */
    @Throws(CapabilityException::class)
    fun verify(lease: CapabilityLease, requirement: CapabilityRequirement): CapabilityLease =
        verify(lease)

    fun consume(lease: CapabilityLease): Boolean = false

    /** V0.1 compatibility surface; all new callers use check/grant/verify. */
    @Deprecated("Use check with a mountId-based requirement")
    fun getEnvelope(taskId: String): TaskCapabilityEnvelope =
        TaskCapabilityEnvelope(taskId = taskId)

    /** V0.1 compatibility surface; alias is resolved only by the compatibility adapter. */
    @Deprecated("Use a CapabilityRequest and explicit grant")
    fun requestMountAccess(taskId: String, alias: String, write: Boolean): Boolean = false

    /** V0.1 compatibility surface. */
    @Deprecated("Use a Network requirement and explicit grant")
    fun requestNetwork(taskId: String, policy: NetworkCapability, domains: Set<String>): Boolean = false

    fun getEnvelope(context: TaskContext): TaskCapabilityEnvelope = getEnvelope(context.rootTaskId)
}

/** Process-scoped broker registry so secondary commit gates see ONCE/session state. */
object CapabilityBrokerRegistry {
    private val brokers = ConcurrentHashMap<String, CapabilityBrokerImpl>()

    fun get(context: Context): CapabilityBrokerImpl {
        val app = context.applicationContext
        val key = try { app.filesDir.absolutePath } catch (_: Exception) { app.packageName }
        return brokers[key] ?: synchronized(brokers) {
            brokers[key] ?: CapabilityBrokerImpl(app).also { brokers[key] = it }
        }
    }

    fun clearForTests() {
        brokers.clear()
    }
}

/** Targeted revocation actions exposed by the active-capabilities viewer. */
enum class CapabilityRevocation {
    MIRROR,
    EXECUTION,
    CANONICAL_WRITE,
    CANONICAL_DELETE,
    NETWORK,
    ALL
}

/**
 * Persistent, fail-closed implementation of [CapabilityBroker]. The broker is
 * intentionally independent of Android UI: a UI only decides a
 * [CapabilityRequest], while this class owns all authority and lease checks.
 */
class CapabilityBrokerImpl(
    private val store: CapabilityStore,
    private val projectLookup: (String) -> VegaProject?,
    private val mountState: (WorkspaceMount) -> MountState = { MountState.AVAILABLE },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val agentPermissions: dev.wind.agent.permissions.AgentPermissions? = null
) : CapabilityBroker {

    constructor(context: Context) : this(
        CapabilityStore(context),
        { projectId -> ProjectStore.get(context).getProject(projectId) },
        { mount -> WorkspaceRouter(context).checkMountState(mount) },
        agentPermissions = dev.wind.agent.permissions.AgentPermissions.get(context)
    )

    private data class LeaseRecord(
        val lease: CapabilityLease,
        val context: TaskContext,
        val requirement: CapabilityRequirement,
        /** Non-null only for an ONCE grant; the map key is the request id. */
        val onceKey: String? = null,
        /** Non-null for APP_SESSION grants; keyed by project + capability signature. */
        val sessionKey: String? = null,
        var consumed: Boolean = false
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, CapabilityRequest>()
    private val leases = LinkedHashMap<String, LeaseRecord>()
    private val appSession = ConcurrentHashMap<String, TaskCapabilityEnvelope>()
    private val once = ConcurrentHashMap<String, TaskCapabilityEnvelope>()
    private val liveTasks = mutableSetOf<String>()

    override fun check(context: TaskContext, requirement: CapabilityRequirement): CapabilityCheck = synchronized(lock) {
        if (requirement is CapabilityRequirement.Denied) {
            return@synchronized CapabilityCheck.Denied(requirement.reason)
        }
        if (requirement.projectId.isNotBlank() &&
            context.projectId.isNotBlank() && requirement.projectId != context.projectId
        ) {
            return@synchronized CapabilityCheck.Denied("PROJECT_CONTEXT_MISMATCH")
        }

        val project = requirement.projectId.takeIf { it.isNotBlank() }?.let(projectLookup)
        val physical = physicalCheck(requirement, project)
        if (physical != null) return@synchronized CapabilityCheck.Denied(physical)

        val envelope = effectiveEnvelope(context, project)
        val sideEffect = requiresAuthority(requirement)
        if (context.mode == CapabilityMode.PLAN && sideEffect) {
            return@synchronized CapabilityCheck.Denied("PLAN_MODE_CAPABILITY_DENIED")
        }
        if (!sideEffect || !envelope.isExpired(clock())) {
            if (permissionSatisfied(context, envelope, requirement)) {
                return@synchronized allowed(context, requirement, envelope)
            }
        }

        val request = requestFor(context, requirement, "")
        pending[request.requestId] = request
        store.audit(
            CapabilityAuditEvent(
                CapabilityAuditEventType.REQUEST,
                context.rootTaskId,
                context.projectId,
                request.signature,
                time = clock(),
                detail = request.summary
            )
        )
        CapabilityCheck.NeedsApproval(request)
    }

    override fun grant(requestId: String, decision: CapabilityDecision): CapabilityLease? = synchronized(lock) {
        val request = pending.remove(requestId) ?: return@synchronized null
        if (decision is CapabilityDecision.Deny) {
            store.audit(
                CapabilityAuditEvent(
                    CapabilityAuditEventType.DENY,
                    request.context.rootTaskId,
                    request.context.projectId,
                    request.signature,
                    time = clock(),
                    detail = "user_denied"
                )
            )
            return@synchronized null
        }
        val scope = (decision as CapabilityDecision.Allow).scope
        if (!request.allowedScopes.contains(scope)) return@synchronized null
        val project = request.context.projectId.takeIf { it.isNotBlank() }?.let(projectLookup)
        val generation = project?.generation ?: request.context.projectGeneration
        // Never copy a session or one-time grant into a durable TASK envelope.
        // Scope is authority lifetime, not merely a UI label: a task grant must
        // not make an APP_SESSION permission survive a process restart.
        val base = when (scope) {
            GrantScope.ONCE -> TaskCapabilityEnvelope(
                taskId = request.context.rootTaskId,
                rootTaskId = request.context.rootTaskId,
                projectId = request.context.projectId,
                projectGeneration = generation
            )
            GrantScope.TASK -> store.load(request.context.rootTaskId)
                ?.takeIf { (agentPermissions == null || liveTasks.contains(request.context.rootTaskId)) && it.projectId == request.context.projectId && it.projectGeneration == generation }
                ?: TaskCapabilityEnvelope(
                    taskId = request.context.rootTaskId,
                    rootTaskId = request.context.rootTaskId,
                    projectId = request.context.projectId,
                    projectGeneration = generation
                )
            GrantScope.APP_SESSION -> appSession[sessionKey(request.context, request.signature)]
                ?.takeIf { it.projectId == request.context.projectId && it.projectGeneration == generation }
                ?: TaskCapabilityEnvelope(
                    taskId = request.context.rootTaskId,
                    rootTaskId = request.context.rootTaskId,
                    projectId = request.context.projectId,
                    projectGeneration = generation
                )
        }
        val issued = clock()
        val expiry = when (scope) {
            GrantScope.ONCE, GrantScope.APP_SESSION -> Long.MAX_VALUE
            GrantScope.TASK -> issued + TASK_GRANT_TTL_MS
        }
        val updated = mergeGrant(
            base.copy(
                taskId = request.context.rootTaskId,
                rootTaskId = request.context.rootTaskId,
                projectId = request.context.projectId,
                projectGeneration = generation,
                issuedAt = issued,
                expiresAt = expiry,
                revision = maxOf(base.revision, (agentPermissions?.revision ?: 0L) * 1000000L) + 1L
            ),
            request.requirement
        )
        when (scope) {
            GrantScope.ONCE -> once[request.requestId] = updated
            GrantScope.TASK -> { liveTasks.add(request.context.rootTaskId); store.save(updated) }
            GrantScope.APP_SESSION -> appSession[sessionKey(request.context, request.signature)] = updated
        }
        val lease = CapabilityLease(
            taskId = request.context.rootTaskId,
            projectId = request.context.projectId,
            envelopeRevision = updated.revision,
            expiresAt = updated.expiresAt,
            grantId = UUID.randomUUID().toString(),
            signature = request.signature,
            scope = scope,
            projectGeneration = updated.projectGeneration
        )
        leases[lease.grantId] = LeaseRecord(
            lease,
            request.context,
            request.requirement,
            onceKey = request.requestId.takeIf { scope == GrantScope.ONCE },
            sessionKey = sessionKey(request.context, request.signature).takeIf { scope == GrantScope.APP_SESSION }
        )
        store.audit(
            CapabilityAuditEvent(
                CapabilityAuditEventType.GRANT,
                request.context.rootTaskId,
                request.context.projectId,
                request.signature,
                scope,
                clock(),
                "granted"
            )
        )
        lease
    }

    override fun revoke(grantId: String): Boolean = synchronized(lock) {
        val record = leases.remove(grantId) ?: return@synchronized false
        val project = record.context.projectId.takeIf { it.isNotBlank() }?.let(projectLookup)
        val base = when (record.lease.scope) {
            GrantScope.ONCE -> record.onceKey?.let { once.remove(it) }
                ?: once.values.firstOrNull { it.rootTaskId == record.context.rootTaskId }
            GrantScope.TASK -> store.load(record.context.rootTaskId)
            GrantScope.APP_SESSION -> record.sessionKey?.let { appSession[it] }
        } ?: effectiveEnvelope(record.context, project)
        val revised = removeGrant(base.copy(revision = base.revision + 1L), record.requirement)
        when (record.lease.scope) {
            GrantScope.ONCE -> record.onceKey?.let { once.remove(it) }
                ?: once.entries.firstOrNull { it.value.rootTaskId == record.context.rootTaskId }?.key?.let { once.remove(it) }
            GrantScope.TASK -> store.save(revised)
            GrantScope.APP_SESSION -> record.sessionKey?.let { appSession[it] = revised }
        }
        store.audit(
            CapabilityAuditEvent(
                CapabilityAuditEventType.REVOKE,
                record.context.rootTaskId,
                record.context.projectId,
                record.lease.signature,
                record.lease.scope,
                clock(),
                "revoked"
            )
        )
        true
    }

    override fun revokeTask(taskId: String, kind: CapabilityRevocation): Boolean = synchronized(lock) {
        val root = taskId.trim()
        if (root.isEmpty()) return@synchronized false
        val current = store.load(root)
        // APP_SESSION grants are intentionally not written to the task store.
        // Recover the project from any live request/lease/one-shot belonging to
        // this task so the active-capabilities viewer can revoke a session grant
        // too, instead of reporting success while the next call still passes.
        val projectId = current?.projectId
            ?: leases.values.firstOrNull { it.context.rootTaskId == root }?.context?.projectId
            ?: pending.values.firstOrNull { it.context.rootTaskId == root }?.context?.projectId
            ?: once.values.firstOrNull { it.rootTaskId == root }?.projectId
            ?: ""
        if (current == null && projectId.isBlank() && kind != CapabilityRevocation.ALL) return@synchronized false
        if (current != null) {
            val revised = revokeEnvelope(current, kind)
            if (kind == CapabilityRevocation.ALL) store.remove(root) else store.save(revised)
        }
        once.entries.filter { it.value.rootTaskId == root }.map { it.key }.forEach { once.remove(it) }
        leases.keys.filter { leases[it]?.context?.rootTaskId == root }.forEach { leases.remove(it) }

        // APP_SESSION is keyed by project + capability signature and is shared
        // by tasks in that project. A targeted revoke therefore removes the
        // matching capability from the project session as well. Revision bumps
        // invalidate any proof that another task may currently hold; removing
        // the in-memory records keeps the fail-closed behavior immediate.
        if (projectId.isNotBlank()) {
            val sessionEntries = appSession.entries
                .filter { it.value.projectId == projectId }
                .toList()
            sessionEntries.forEach { entry ->
                val revised = revokeEnvelope(entry.value, kind)
                if (kind == CapabilityRevocation.ALL || !hasAuthority(revised)) {
                    appSession.remove(entry.key)
                } else {
                    appSession[entry.key] = revised
                }
            }
            leases.keys
                .filter {
                    val record = leases[it] ?: return@filter false
                    record.lease.scope == GrantScope.APP_SESSION &&
                        record.context.projectId == projectId
                }
                .forEach { leases.remove(it) }
        }
        store.audit(
            CapabilityAuditEvent(
                CapabilityAuditEventType.REVOKE,
                root,
                projectId,
                "task-revoke:${kind.name.lowercase()}",
                GrantScope.TASK,
                clock(),
                "revoked"
            )
        )
        true
    }

    private fun revokeEnvelope(
        envelope: TaskCapabilityEnvelope,
        kind: CapabilityRevocation
    ): TaskCapabilityEnvelope = when (kind) {
        CapabilityRevocation.MIRROR -> envelope.copy(mirrorAccess = MirrorAccess.NONE)
        CapabilityRevocation.EXECUTION -> envelope.copy(
            executionAccess = ExecutionAccess.NONE,
            mirrorAccess = MirrorAccess.NONE
        )
        CapabilityRevocation.CANONICAL_WRITE -> envelope.copy(
            mountGrants = envelope.mountGrants.mapValues { (_, grant) -> grant.copy(canonicalWrite = false) },
            // Apply approvals are canonical writes with an exact plan. A
            // targeted write revoke must invalidate them too; otherwise the
            // opaque key would become an unrevokable write capability.
            approvedApplyKeys = emptySet(),
            approvedArtifactInstallKeys = emptySet()
        )
        CapabilityRevocation.CANONICAL_DELETE -> envelope.copy(
            mountGrants = envelope.mountGrants.mapValues { (_, grant) -> grant.copy(canonicalDelete = false) },
            // Clear all exact Apply keys conservatively because the key is
            // opaque and may represent a destructive plan.
            approvedApplyKeys = emptySet(),
            approvedArtifactInstallKeys = emptySet()
        )
        CapabilityRevocation.NETWORK -> envelope.copy(networkGrant = NetworkGrant())
        CapabilityRevocation.ALL -> envelope.copy(
            mountGrants = emptyMap(),
            mirrorAccess = MirrorAccess.NONE,
            executionAccess = ExecutionAccess.NONE,
            networkGrant = NetworkGrant(),
            secretRefs = emptySet(),
            approvedApplyKeys = emptySet(),
            approvedExpansionKeys = emptySet(),
            approvedArtifactInstallKeys = emptySet()
        )
    }.copy(revision = envelope.revision + 1L)

    private fun hasAuthority(envelope: TaskCapabilityEnvelope): Boolean =
        envelope.mountGrants.values.any { it.canonicalWrite || it.canonicalDelete } ||
            envelope.mirrorAccess != MirrorAccess.NONE ||
            envelope.executionAccess != ExecutionAccess.NONE ||
            envelope.networkGrant.mode != NetworkMode.OFF ||
            envelope.approvedApplyKeys.isNotEmpty() ||
            envelope.approvedExpansionKeys.isNotEmpty() ||
            envelope.approvedArtifactInstallKeys.isNotEmpty()

    override fun endTask(taskId: String) = synchronized(lock) {
        val root = taskId.trim()
        if (root.isEmpty()) return@synchronized
        liveTasks.remove(root)
        val projectId = store.load(root)?.projectId ?: ""
        store.remove(root)
        once.entries.filter { it.value.rootTaskId == root }.map { it.key }.forEach { once.remove(it) }
        leases.keys.filter { leases[it]?.lease?.taskId == root }.forEach { leases.remove(it) }
        pending.keys.filter { pending[it]?.context?.rootTaskId == root }.forEach { pending.remove(it) }
        store.audit(
            CapabilityAuditEvent(
                CapabilityAuditEventType.TASK_END,
                root,
                projectId,
                "task:$root",
                time = clock(),
                detail = "ended"
            )
        )
    }

    override fun verify(lease: CapabilityLease): CapabilityLease = synchronized(lock) {
        val record = leases[lease.grantId]
            ?: throw CapabilityRevokedException("CAPABILITY_REVOKED: lease is no longer active")
        if (record.consumed) throw CapabilityRevokedException("CAPABILITY_CONSUMED: one-time lease was already used")
        if (lease.taskId != record.lease.taskId || lease.signature != record.lease.signature) {
            throw CapabilityRevokedException("CAPABILITY_LEASE_MISMATCH")
        }
        val project = record.context.projectId.takeIf { it.isNotBlank() }?.let(projectLookup)
        if (project != null && project.generation != lease.projectGeneration) {
            throw CapabilityRevokedException("CAPABILITY_STALE: project generation changed")
        }
        if (lease.expiresAt <= clock()) {
            store.audit(
                CapabilityAuditEvent(
                    CapabilityAuditEventType.EXPIRE,
                    lease.taskId,
                    lease.projectId,
                    lease.signature,
                    lease.scope,
                    clock(),
                    "expired"
                )
            )
            throw CapabilityExpiredException("CAPABILITY_EXPIRED: lease is no longer valid")
        }
        val current = effectiveEnvelope(record.context, project)
        if (current.revision != lease.envelopeRevision || current.isExpired(clock())) {
            throw CapabilityRevokedException("CAPABILITY_REVOKED: envelope revision changed")
        }
        val physical = physicalCheck(record.requirement, project)
        if (physical != null || !permissionSatisfied(record.context, current, record.requirement)) {
            throw CapabilityRevokedException(physical ?: "CAPABILITY_REVOKED: requirement is no longer satisfied")
        }
        lease
    }

    override fun verify(
        lease: CapabilityLease,
        requirement: CapabilityRequirement
    ): CapabilityLease = synchronized(lock) {
        verify(lease)
        val record = leases[lease.grantId]
            ?: throw CapabilityRevokedException("CAPABILITY_REVOKED: lease is no longer active")
        if (record.requirement != requirement) {
            throw CapabilityRevokedException("CAPABILITY_LEASE_MISMATCH: requirement changed")
        }
        lease
    }

    override fun consume(lease: CapabilityLease): Boolean = synchronized(lock) {
        val record = try { verify(lease); leases[lease.grantId] } catch (_: Exception) { null }
            ?: return@synchronized false
        if (record.lease.scope == GrantScope.ONCE) {
            record.consumed = true
            leases.remove(lease.grantId)
            record.onceKey?.let { once.remove(it) }
        }
        true
    }

    override fun getEnvelope(taskId: String): TaskCapabilityEnvelope = synchronized(lock) {
        val stored = store.load(taskId)
        if (stored != null) return@synchronized stored
        TaskCapabilityEnvelope(taskId = taskId, rootTaskId = taskId)
    }

    override fun getEnvelope(context: TaskContext): TaskCapabilityEnvelope = synchronized(lock) {
        effectiveEnvelope(context, context.projectId.takeIf { it.isNotBlank() }?.let(projectLookup))
    }

    override fun requestMountAccess(taskId: String, alias: String, write: Boolean): Boolean = synchronized(lock) {
        val stored = store.load(taskId) ?: return@synchronized false
        val project = stored.projectId.takeIf { it.isNotBlank() }?.let(projectLookup) ?: return@synchronized false
        val mount = project.findMount(alias) ?: return@synchronized false
        val context = TaskContext(taskId, project.id, project.generation, CapabilityMode.ACCEPT, taskId)
        val req = if (write) CapabilityRequirement.CanonicalWrite(project.id, setOf(mount.id))
        else CapabilityRequirement.ProjectRead(project.id, setOf(mount.id))
        val checked = check(context, req)
        if (checked is CapabilityCheck.Allowed) return@synchronized true
        if (checked is CapabilityCheck.NeedsApproval) return@synchronized grant(
            checked.request.requestId,
            CapabilityDecision.Allow(GrantScope.TASK)
        ) != null
        false
    }

    override fun requestNetwork(taskId: String, policy: NetworkCapability, domains: Set<String>): Boolean = synchronized(lock) {
        val stored = store.load(taskId) ?: return@synchronized false
        val mode = when (policy) {
            NetworkCapability.DISABLED -> NetworkMode.OFF
            NetworkCapability.DEPENDENCY_ONLY -> NetworkMode.DEPENDENCY_ONLY
            NetworkCapability.APPROVED_DOMAINS -> NetworkMode.APPROVED_DOMAINS
            NetworkCapability.FULL -> NetworkMode.FULL_TASK
        }
        // DEPENDENCY_ONLY is reserved for a later phase and is fail-closed in
        // P2; the legacy adapter must not turn it into domain authority.
        if (mode == NetworkMode.OFF || mode == NetworkMode.DEPENDENCY_ONLY) return@synchronized false
        val project = stored.projectId.takeIf { it.isNotBlank() }?.let(projectLookup)
        val context = TaskContext(taskId, stored.projectId, project?.generation ?: stored.projectGeneration, CapabilityMode.ACCEPT, taskId)
        val requestedDomains = if (mode == NetworkMode.FULL_TASK) emptySet() else domains
        if (mode == NetworkMode.APPROVED_DOMAINS && requestedDomains.isEmpty()) {
            return@synchronized false
        }
        val req = CapabilityRequirement.Network(stored.projectId, requestedDomains, execution = false)
        val checked = check(context, req)
        if (checked is CapabilityCheck.Allowed) return@synchronized true
        if (checked is CapabilityCheck.NeedsApproval) {
            val lease = grant(checked.request.requestId, CapabilityDecision.Allow(GrantScope.TASK))
            return@synchronized lease != null
        }
        false
    }

    private fun allowed(
        context: TaskContext,
        requirement: CapabilityRequirement,
        envelope: TaskCapabilityEnvelope
    ): CapabilityCheck.Allowed {
        val lease = CapabilityLease(
            taskId = context.rootTaskId,
            projectId = context.projectId,
            envelopeRevision = envelope.revision,
            expiresAt = envelope.expiresAt,
            grantId = "baseline-" + UUID.randomUUID().toString(),
            signature = signature(requirement),
            scope = GrantScope.APP_SESSION,
            projectGeneration = envelope.projectGeneration
        )
        leases[lease.grantId] = LeaseRecord(lease, context, requirement)
        return CapabilityCheck.Allowed(lease)
    }

    private fun requestFor(context: TaskContext, requirement: CapabilityRequirement, toolName: String): CapabilityRequest {
        val signature = signature(requirement)
        val requestId = "cap-" + UUID.randomUUID().toString()
        val summary = summary(requirement)
        val scopes = when {
            requirement is CapabilityRequirement.CapabilityExpansion -> setOf(GrantScope.TASK)
            containsArtifactInstall(requirement) -> setOf(GrantScope.ONCE, GrantScope.TASK)
            requirement is CapabilityRequirement.CanonicalDelete -> setOf(GrantScope.ONCE, GrantScope.TASK)
            requirement is CapabilityRequirement.ApplyChanges && requirement.hasDestructiveChanges ->
                setOf(GrantScope.ONCE, GrantScope.TASK)
            else -> setOf(GrantScope.ONCE, GrantScope.TASK, GrantScope.APP_SESSION)
        }
        return CapabilityRequest(
            requestId,
            toolName.ifBlank { effectName(requirement) },
            null,
            context,
            requirement,
            signature,
            summary,
            clock(),
            scopes
        )
    }

    private fun containsArtifactInstall(requirement: CapabilityRequirement): Boolean = when (requirement) {
        is CapabilityRequirement.ArtifactInstall -> true
        is CapabilityRequirement.Composite -> requirement.requirements.any(::containsArtifactInstall)
        else -> false
    }

    private fun effectName(requirement: CapabilityRequirement): String = when (requirement) {
        is CapabilityRequirement.ProjectRead -> "project_read"
        is CapabilityRequirement.MirrorRead -> "mirror_read"
        is CapabilityRequirement.MirrorMutation -> "mirror_mutate"
        is CapabilityRequirement.Execution -> "execution"
        is CapabilityRequirement.CanonicalWrite -> "canonical_write"
        is CapabilityRequirement.CanonicalDelete -> "canonical_delete"
        is CapabilityRequirement.Network -> "network"
        is CapabilityRequirement.ArtifactInstall -> "artifact_install"
        is CapabilityRequirement.ApplyChanges -> "shell_apply_changes"
        is CapabilityRequirement.CapabilityExpansion -> "capability_expansion"
        is CapabilityRequirement.Composite -> "coding_task_capability"
        is CapabilityRequirement.Internal -> "internal"
        is CapabilityRequirement.Denied -> "denied"
    }

    private fun summary(requirement: CapabilityRequirement): String = when (requirement) {
        is CapabilityRequirement.ProjectRead -> "Read project mounts: " + requirement.mountIds.sorted().joinToString(", ")
        is CapabilityRequirement.MirrorRead -> "Read the recoverable execution mirror"
        is CapabilityRequirement.MirrorMutation -> "Modify the recoverable execution mirror"
        is CapabilityRequirement.Execution -> "Run commands in the isolated execution workspace"
        is CapabilityRequirement.CanonicalWrite -> "Write canonical mount(s): " + requirement.mountIds.sorted().joinToString(", ")
        is CapabilityRequirement.CanonicalDelete -> "Delete canonical mount file(s): " + requirement.mountIds.sorted().joinToString(", ")
        is CapabilityRequirement.Network -> if (requirement.domains.isEmpty()) "Use task network access" else "Access domains: " + requirement.domains.sorted().joinToString(", ")
        is CapabilityRequirement.ArtifactInstall -> "Install ${requirement.kind.name.lowercase()} to ${requirement.targetId} from SHA-256 ${requirement.artifactSha256}"
        is CapabilityRequirement.ApplyChanges -> "Apply ${if (requirement.hasDestructiveChanges) "destructive " else "pending "}changes from execution mirror"
        is CapabilityRequirement.CapabilityExpansion -> "Request an additional project mount"
        is CapabilityRequirement.Composite -> requirement.requirements.joinToString("; ") { summary(it) }
        is CapabilityRequirement.Internal -> "Internal agent operation"
        is CapabilityRequirement.Denied -> requirement.reason
    }

    private fun physicalCheck(requirement: CapabilityRequirement, project: VegaProject?): String? {
        if (requirement is CapabilityRequirement.Denied) return requirement.reason
        if (project == null && requirement.projectId.isNotBlank() &&
            requirement !is CapabilityRequirement.Network && requirement !is CapabilityRequirement.Internal &&
            !(requirement is CapabilityRequirement.ArtifactInstall && requirement.kind == CapabilityRequirement.ArtifactInstallKind.ANDROID_PACKAGE)
        ) return "PROJECT_NOT_CONFIGURED"
        return when (requirement) {
            is CapabilityRequirement.ProjectRead -> checkMounts(project, requirement.mountIds, write = false, delete = false)
            is CapabilityRequirement.CanonicalWrite -> checkMounts(project, requirement.mountIds, write = true, delete = false)
            is CapabilityRequirement.CanonicalDelete -> checkMounts(project, requirement.mountIds, write = true, delete = true)
            is CapabilityRequirement.ArtifactInstall -> {
                val mountId = requirement.targetMountId
                if (mountId == null) null
                else checkMounts(project, setOf(mountId), write = true, delete = false)
            }
            is CapabilityRequirement.ApplyChanges -> if (requirement.changedMountIds.isEmpty()) null
            else checkMounts(project, requirement.changedMountIds, write = true, delete = requirement.hasDestructiveChanges)
            is CapabilityRequirement.Composite -> requirement.requirements.firstNotNullOfOrNull { physicalCheck(it, project) }
            else -> null
        }
    }

    private fun checkMounts(project: VegaProject?, ids: Set<String>, write: Boolean, delete: Boolean): String? {
        if (project == null) return if (ids.isEmpty()) null else "PROJECT_NOT_CONFIGURED"
        if (ids.isEmpty()) return "MOUNT_ID_REQUIRED"
        for (id in ids) {
            val mount = project.findMountById(id) ?: return "MOUNT_NOT_IN_PROJECT: $id"
            if (mountState(mount) != MountState.AVAILABLE) return "SAF_PERMISSION_REVOKED: ${mount.alias}"
            if (write && mount.mode == MountMode.READ_ONLY) return "READ_ONLY_MOUNT: ${mount.alias}"
            if (delete && mount.mode == MountMode.READ_ONLY) return "READ_ONLY_MOUNT: ${mount.alias}"
        }
        return null
    }

    private fun requiresAuthority(requirement: CapabilityRequirement): Boolean = when (requirement) {
        is CapabilityRequirement.ProjectRead, is CapabilityRequirement.Internal -> false
        is CapabilityRequirement.Composite -> requirement.requirements.any { requiresAuthority(it) }
        else -> true
    }

    private fun satisfied(envelope: TaskCapabilityEnvelope, requirement: CapabilityRequirement): Boolean = when (requirement) {
        is CapabilityRequirement.ProjectRead -> requirement.mountIds.all { envelope.canRead(it) }
        is CapabilityRequirement.MirrorRead -> envelope.mirrorAccess == MirrorAccess.READ || envelope.mirrorAccess == MirrorAccess.MUTATE
        is CapabilityRequirement.MirrorMutation -> envelope.mirrorAccess == MirrorAccess.MUTATE
        is CapabilityRequirement.Execution -> envelope.executionAccess == ExecutionAccess.COMMANDS
        is CapabilityRequirement.CanonicalWrite -> requirement.mountIds.isNotEmpty() && requirement.mountIds.all(envelope::canWrite)
        is CapabilityRequirement.CanonicalDelete -> requirement.mountIds.isNotEmpty() && requirement.mountIds.all(envelope::canDelete)
        is CapabilityRequirement.ArtifactInstall -> envelope.hasArtifactInstallApproval(requirement)
        is CapabilityRequirement.Network -> {
            val grant = envelope.networkGrant.normalized()
            val domains = requirement.domains.mapNotNull { NetworkGrant.normalizeDomain(it) }
            val networkOk = when (grant.mode) {
                NetworkMode.FULL_TASK -> true
                NetworkMode.APPROVED_DOMAINS -> domains.isNotEmpty() && domains.all { requested ->
                    grant.domains.any { approved -> requested == approved || requested.endsWith(".$approved") }
                }
                NetworkMode.OFF, NetworkMode.DEPENDENCY_ONLY -> false
            }
            networkOk && (!requirement.execution || envelope.executionAccess == ExecutionAccess.COMMANDS)
        }
        is CapabilityRequirement.ApplyChanges ->
            requirement.diffFingerprint.isNotBlank() && envelope.hasApplyApproval(requirement)
        is CapabilityRequirement.CapabilityExpansion -> envelope.hasExpansionApproval(requirement)
        is CapabilityRequirement.Composite -> requirement.requirements.all { satisfied(envelope, it) }
        is CapabilityRequirement.Internal -> true
        is CapabilityRequirement.Denied -> false
    }

    private fun permissionSatisfied(context: TaskContext, envelope: TaskCapabilityEnvelope, requirement: CapabilityRequirement): Boolean {
        val permissions = agentPermissions ?: return satisfied(envelope, requirement)
        if (requirement is CapabilityRequirement.Composite) return requirement.requirements.all { permissionSatisfied(context, envelope, it) }
        if (requirement is CapabilityRequirement.ApplyChanges && requirement.diffFingerprint.isNotBlank() && requirement.changedMountIds.isNotEmpty()) {
            val project = projectLookup(context.projectId)
            val allowed = project != null && requirement.changedMountIds.all { id -> project.findMountById(id)?.let {
                permissions.allows(dev.wind.agent.permissions.AgentPermissions.mountKey(project, it), it.id == project.primaryMountId, true, context.rootTaskId)
            } == true }
            if (allowed && (permissions.policy == dev.wind.agent.permissions.ActionPolicy.AUTONOMOUS ||
                    permissions.policy == dev.wind.agent.permissions.ActionPolicy.BALANCED && !requirement.hasDestructiveChanges)) return true
        }
        return satisfied(envelope, requirement)
    }

    private fun effectiveEnvelope(context: TaskContext, project: VegaProject?): TaskCapabilityEnvelope {
        val root = context.rootTaskId
        val baseline = baseline(context, project)
        val stored = store.load(root)
        val task = stored?.takeIf {
            (agentPermissions == null || liveTasks.contains(root)) && it.projectId == context.projectId &&
                it.projectGeneration == (project?.generation ?: context.projectGeneration) &&
                !it.isExpired(clock())
        }
        val currentGeneration = project?.generation ?: context.projectGeneration
        val sessions = appSession.values.filter {
            it.projectId == context.projectId &&
                it.projectGeneration == currentGeneration &&
                !it.isExpired(clock())
        }
        val oneShot = once.values.filter {
            it.rootTaskId == root &&
                it.projectId == context.projectId &&
                it.projectGeneration == currentGeneration &&
                !it.isExpired(clock())
        }
        return mergeEnvelopes(baseline, task, *sessions.toTypedArray(), *oneShot.toTypedArray())
    }

    private fun baseline(context: TaskContext, project: VegaProject?): TaskCapabilityEnvelope {
        val mounts = linkedMapOf<String, MountGrant>()
        project?.mounts?.forEach { mount ->
            if (mountState(mount) == MountState.AVAILABLE) {
                val read = agentPermissions?.mountAccess(project, mount, false, context.rootTaskId) ?: true
                val write = mount.mode == MountMode.READ_WRITE && agentPermissions?.mountAccess(project, mount, true, context.rootTaskId) == true
                mounts[mount.id] = MountGrant(mount.id, read, write, write)
            }
        }
        return TaskCapabilityEnvelope(
            taskId = context.rootTaskId,
            rootTaskId = context.rootTaskId,
            projectId = context.projectId,
            mountGrants = mounts,
            revision = (agentPermissions?.revision ?: 0L) * 1000000L,
            mirrorAccess = if (agentPermissions != null && mounts.isNotEmpty() && mounts.values.all { it.read } && mounts.values.any { it.canonicalWrite }) MirrorAccess.MUTATE
                else if (agentPermissions != null && mounts.isNotEmpty() && mounts.values.all { it.read }) MirrorAccess.READ else MirrorAccess.NONE,
            projectGeneration = project?.generation ?: context.projectGeneration
        )
    }

    private fun mergeEnvelopes(vararg values: TaskCapabilityEnvelope?): TaskCapabilityEnvelope {
        val nonNull = values.filterNotNull()
        if (nonNull.isEmpty()) return TaskCapabilityEnvelope("")
        val first = nonNull.first()
        val mounts = linkedMapOf<String, MountGrant>()
        nonNull.flatMap { it.mountGrants.values }.forEach { grant ->
            val old = mounts[grant.mountId]
            mounts[grant.mountId] = MountGrant(
                grant.mountId,
                read = (old?.read == true) || grant.read,
                canonicalWrite = (old?.canonicalWrite == true) || grant.canonicalWrite,
                canonicalDelete = (old?.canonicalDelete == true) || grant.canonicalDelete
            )
        }
        val network = nonNull.map { it.networkGrant.normalized() }
        val mode = when {
            network.any { it.mode == NetworkMode.FULL_TASK } -> NetworkMode.FULL_TASK
            network.any { it.mode == NetworkMode.APPROVED_DOMAINS } -> NetworkMode.APPROVED_DOMAINS
            network.any { it.mode == NetworkMode.DEPENDENCY_ONLY } -> NetworkMode.DEPENDENCY_ONLY
            else -> NetworkMode.OFF
        }
        val nonBaseline = nonNull.filter { it.revision > 0L || it.expiresAt != Long.MAX_VALUE }
        val mergedExpiry = if (nonBaseline.isEmpty()) Long.MAX_VALUE else nonBaseline.maxOf { it.expiresAt }
        return first.copy(
            mountGrants = mounts,
            mirrorAccess = if (nonNull.any { it.mirrorAccess == MirrorAccess.MUTATE }) MirrorAccess.MUTATE
            else if (nonNull.any { it.mirrorAccess == MirrorAccess.READ }) MirrorAccess.READ else MirrorAccess.NONE,
            executionAccess = if (nonNull.any { it.executionAccess == ExecutionAccess.COMMANDS }) ExecutionAccess.COMMANDS else ExecutionAccess.NONE,
            networkGrant = NetworkGrant(mode, network.flatMap { it.domains }.toSet()),
            secretRefs = nonNull.flatMap { it.secretRefs }.toSet(),
            revision = nonNull.maxOf { it.revision },
            issuedAt = nonNull.maxOf { it.issuedAt },
            expiresAt = mergedExpiry,
            approvedApplyKeys = nonNull.flatMap { it.approvedApplyKeys }.toSet(),
            approvedExpansionKeys = nonNull.flatMap { it.approvedExpansionKeys }.toSet(),
            approvedArtifactInstallKeys = nonNull.flatMap { it.approvedArtifactInstallKeys }.toSet()
        )
    }

    private fun mergeGrant(envelope: TaskCapabilityEnvelope, requirement: CapabilityRequirement): TaskCapabilityEnvelope = when (requirement) {
        is CapabilityRequirement.ProjectRead -> envelope.copy(mountGrants = envelope.mountGrants + requirement.mountIds.associateWith { MountGrant(it, true) })
        is CapabilityRequirement.CanonicalWrite -> envelope.copy(mountGrants = grantMounts(envelope, requirement.mountIds) { it.copy(read = true, canonicalWrite = true) })
        is CapabilityRequirement.CanonicalDelete -> envelope.copy(mountGrants = grantMounts(envelope, requirement.mountIds) { it.copy(read = true, canonicalDelete = true) })
        is CapabilityRequirement.MirrorRead -> envelope.copy(mirrorAccess = if (envelope.mirrorAccess == MirrorAccess.NONE) MirrorAccess.READ else envelope.mirrorAccess)
        is CapabilityRequirement.MirrorMutation -> envelope.copy(mirrorAccess = MirrorAccess.MUTATE)
        is CapabilityRequirement.Execution -> envelope.copy(mirrorAccess = MirrorAccess.MUTATE, executionAccess = ExecutionAccess.COMMANDS)
        is CapabilityRequirement.Network -> {
            val domains = requirement.domains.mapNotNull { NetworkGrant.normalizeDomain(it) }.toSet()
            envelope.copy(networkGrant = NetworkGrant(if (domains.isEmpty()) NetworkMode.FULL_TASK else NetworkMode.APPROVED_DOMAINS, domains))
        }
        // Apply authority is exact-plan authority only. It must never become a
        // reusable generic canonical write/delete grant on the affected mounts.
        is CapabilityRequirement.ApplyChanges -> envelope.copy(
            approvedApplyKeys = envelope.approvedApplyKeys + CapabilityApprovalKey.apply(requirement)
        )
        is CapabilityRequirement.CapabilityExpansion -> envelope.copy(
            approvedExpansionKeys = envelope.approvedExpansionKeys + CapabilityApprovalKey.expansion(requirement)
        )
        is CapabilityRequirement.ArtifactInstall -> envelope.copy(
            approvedArtifactInstallKeys = envelope.approvedArtifactInstallKeys + CapabilityApprovalKey.artifactInstall(requirement)
        )
        is CapabilityRequirement.Composite -> requirement.requirements.fold(envelope) { current, child -> mergeGrant(current, child) }
        is CapabilityRequirement.Internal, is CapabilityRequirement.Denied -> envelope
    }

    private fun grantMounts(
        envelope: TaskCapabilityEnvelope,
        ids: Set<String>,
        update: (MountGrant) -> MountGrant
    ): Map<String, MountGrant> {
        val result = envelope.mountGrants.toMutableMap()
        ids.forEach { id -> result[id] = update(result[id] ?: MountGrant(id, read = true)) }
        return result
    }

    private fun removeGrant(envelope: TaskCapabilityEnvelope, requirement: CapabilityRequirement): TaskCapabilityEnvelope = when (requirement) {
        is CapabilityRequirement.CanonicalWrite -> envelope.copy(mountGrants = envelope.mountGrants.mapValues { (id, grant) -> if (requirement.mountIds.contains(id)) grant.copy(canonicalWrite = false) else grant })
        is CapabilityRequirement.CanonicalDelete -> envelope.copy(mountGrants = envelope.mountGrants.mapValues { (id, grant) -> if (requirement.mountIds.contains(id)) grant.copy(canonicalDelete = false) else grant })
        is CapabilityRequirement.MirrorRead, is CapabilityRequirement.MirrorMutation -> envelope.copy(mirrorAccess = MirrorAccess.NONE)
        is CapabilityRequirement.Execution -> envelope.copy(executionAccess = ExecutionAccess.NONE, mirrorAccess = MirrorAccess.NONE)
        is CapabilityRequirement.Network -> envelope.copy(networkGrant = NetworkGrant())
        is CapabilityRequirement.ApplyChanges -> envelope.copy(
            approvedApplyKeys = envelope.approvedApplyKeys - CapabilityApprovalKey.apply(requirement)
        )
        is CapabilityRequirement.CapabilityExpansion -> envelope.copy(
            approvedExpansionKeys = envelope.approvedExpansionKeys - CapabilityApprovalKey.expansion(requirement)
        )
        is CapabilityRequirement.ArtifactInstall -> envelope.copy(
            approvedArtifactInstallKeys = envelope.approvedArtifactInstallKeys - CapabilityApprovalKey.artifactInstall(requirement)
        )
        is CapabilityRequirement.Composite -> requirement.requirements.fold(envelope) { current, child -> removeGrant(current, child) }
        else -> envelope
    }

    /** APP_SESSION is process-scoped and shared by tasks in one project only. */
    private fun sessionKey(context: TaskContext, signature: String): String =
        context.projectId + "|" + signature

    private fun signature(requirement: CapabilityRequirement): String {
        val raw = canonical(requirement)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return "cap:" + digest.take(16).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun canonical(requirement: CapabilityRequirement): String = when (requirement) {
        is CapabilityRequirement.ProjectRead -> "read|${requirement.projectId}|${requirement.mountIds.sorted().joinToString(",")}"
        is CapabilityRequirement.MirrorRead -> "mirror-read|${requirement.projectId}"
        is CapabilityRequirement.MirrorMutation -> "mirror-mutate|${requirement.projectId}"
        is CapabilityRequirement.Execution -> "execute|${requirement.projectId}"
        is CapabilityRequirement.CanonicalWrite -> "write|${requirement.projectId}|${requirement.mountIds.sorted().joinToString(",")}"
        is CapabilityRequirement.CanonicalDelete -> "delete|${requirement.projectId}|${requirement.mountIds.sorted().joinToString(",")}"
        is CapabilityRequirement.Network -> "network|${requirement.projectId}|${requirement.domains.map { it.lowercase() }.sorted().joinToString(",")}|${requirement.execution}"
        is CapabilityRequirement.ArtifactInstall -> "artifact-install|${requirement.projectId}|${requirement.kind.name}|${requirement.artifactSha256}|${requirement.targetId}|${requirement.targetMountId ?: ""}"
        is CapabilityRequirement.ApplyChanges -> "apply|${requirement.projectId}|${requirement.executionId}|${requirement.changedMountIds.sorted().joinToString(",")}|${requirement.diffFingerprint}|${requirement.hasDestructiveChanges}"
        is CapabilityRequirement.CapabilityExpansion -> "expand|${requirement.projectId}|${requirement.alias.lowercase()}|${requirement.requestedMode.name}|${requirement.executionPath}"
        is CapabilityRequirement.Composite -> "composite|${requirement.requirements.joinToString(";") { canonical(it) }}"
        is CapabilityRequirement.Internal -> "internal|${requirement.projectId}"
        is CapabilityRequirement.Denied -> "denied|${requirement.projectId}|${requirement.reason}"
    }

    companion object {
        const val TASK_GRANT_TTL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
