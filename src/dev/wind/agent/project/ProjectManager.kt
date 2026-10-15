package dev.wind.agent.project

import android.content.Context
import android.net.Uri
import dev.wind.agent.workspace.SafWorkspaceFs
import dev.wind.agent.workspace.WorkspaceManager
import dev.wind.agent.capability.CapabilityBrokerRegistry
import dev.wind.agent.capability.CapabilityCheck
import dev.wind.agent.capability.CapabilityLease
import dev.wind.agent.capability.CapabilityMode
import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.CapabilityApprovalKey
import dev.wind.agent.capability.TaskContext
import dev.wind.agent.shell.mirror.MirrorPhase
import dev.wind.agent.shell.mirror.MirrorState
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owns project topology and SAF capability lifecycle. */
class ProjectManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val store = ProjectStore.get(appContext)
    val router = WorkspaceRouter(appContext, store)

    fun getActiveProject(): VegaProject? = store.getActiveProject()

    private fun mirrorStateFile(projectId: String): File =
        File(appContext.filesDir, "projects/$projectId/mirror_state.json")

    private fun mirrorStateArtifact(projectId: String, suffix: String): File =
        File(appContext.filesDir, "projects/$projectId/mirror_state.$suffix")

    /** Topology is immutable while execution state might contain uncommitted work. */
    private fun ensureTopologyMutable(projectId: String) {
        val file = mirrorStateFile(projectId)
        if (!file.exists()) {
            val recoveryArtifact = listOf("new", "bak")
                .map { mirrorStateArtifact(projectId, it) }
                .firstOrNull { it.exists() }
            if (recoveryArtifact != null) {
                throw IllegalStateException(
                    "MIRROR_STATE_RECOVERY_REQUIRED: ${recoveryArtifact.name} exists while the final state is missing"
                )
            }
            return
        }
        val state = try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            // P3 uses the schema-versioned phase field. Keep reading the old
            // state alias only for pre-P3 files during migration.
            val schema = MirrorState.strictSchemaVersion(obj)
            if (schema == MirrorState.SCHEMA_VERSION) {
                require(obj.has("phase")) { "MIRROR_STATE_PHASE_MISSING" }
                val phase = obj.getString("phase")
                MirrorPhase.valueOf(phase).name
            } else if (schema == null || schema == 1) {
                obj.optString("phase", obj.optString("state", "CLEAN"))
            } else {
                throw IllegalStateException("MIRROR_STATE_SCHEMA_UNSUPPORTED")
            }
        } catch (e: Exception) {
            throw IllegalStateException("MIRROR_STATE_INVALID: Cannot safely modify project topology", e)
        }
        if (state != "CLEAN" && state != "CLEANED") {
            throw IllegalStateException(
                "PENDING_SESSION_LOCKED: Cannot modify project topology while execution state is $state. Apply/discard or recover first."
            )
        }
    }

    /**
     * CLEAN mirror metadata is only a cache. Removing it is an optimization; failure
     * must not make an already durable ProjectStore mutation look rolled back.
     * ProjectMirrorManager also fingerprints generation + treeUri and will reset safely.
     */
    private fun invalidateCleanMirrorState(projectId: String) {
        try { mirrorStateFile(projectId).delete() } catch (_: Exception) {}
    }

    private fun displayName(uri: Uri, fallback: String = "Workspace"): String = try {
        SafWorkspaceFs(appContext, uri).getRootDisplayName().takeIf { it.isNotBlank() } ?: fallback
    } catch (_: Exception) {
        fallback
    }

    /** Fresh install creates the first Project; later selections replace its primary mount. */
    fun selectPrimaryWorkspace(newUri: Uri, newDisplayName: String = displayName(newUri)): WorkspaceMount {
        return if (getActiveProject() == null) {
            createProject("Default Project", newUri, newDisplayName).primaryMount()
                ?: throw IllegalStateException("Failed to create primary workspace")
        } else {
            replacePrimaryMount(newUri, newDisplayName)
        }
    }

    fun createProject(name: String, primaryUri: Uri, primaryDisplayName: String): VegaProject {
        WorkspaceManager.takePersistablePermission(appContext, primaryUri, write = true)
        var committed = false
        try {
            val mountId = UUID.randomUUID().toString()
            val projectId = UUID.randomUUID().toString()
            val primary = WorkspaceMount(
                id = mountId,
                alias = "main",
                treeUri = primaryUri.toString(),
                displayName = primaryDisplayName,
                mode = MountMode.READ_WRITE,
                executionPath = "main",
                isPrimary = true
            )
            val saved = store.saveProject(
                VegaProject(
                    id = projectId,
                    name = name.ifBlank { "Project" },
                    primaryMountId = mountId,
                    mounts = listOf(primary)
                )
            )
            if (store.getActiveProject()?.id != saved.id) store.setActiveProject(saved.id)
            committed = true

            // Compatibility state is downstream from ProjectStore and may never
            // invalidate the committed project if it fails.
            try { WorkspaceManager.updateLegacyWorkspacePointer(appContext, primaryUri) } catch (_: Exception) {}
            return saved
        } finally {
            if (!committed && store.getAllProjects().none { p -> p.mounts.any { it.treeUri == primaryUri.toString() } }) {
                WorkspaceManager.releasePersistablePermission(appContext, primaryUri, write = true)
            }
        }
    }

    fun replacePrimaryMount(
        newUri: Uri,
        newDisplayName: String,
        beforeSideEffect: (() -> Unit)? = null,
        beforeCommit: (() -> Unit)? = null
    ): WorkspaceMount {
        val project = getActiveProject() ?: return selectPrimaryWorkspace(newUri, newDisplayName)
        ensureTopologyMutable(project.id)
        val oldPrimary = project.primaryMount()
            ?: throw IllegalStateException("PRIMARY_MOUNT_INVALID: active project has no primary mount")
        if (oldPrimary.treeUri == newUri.toString()) {
            // Reauthorization can legitimately return the same tree URI. The
            // topology is unchanged, but the persisted SAF grant may have been
            // revoked, so reacquire it before reporting success. Do not save the
            // project or bump generation for this idempotent repair.
            beforeSideEffect?.invoke()
            WorkspaceManager.takePersistablePermission(appContext, newUri, write = true)
            try { WorkspaceManager.updateLegacyWorkspacePointer(appContext, newUri) } catch (_: Exception) {}
            return oldPrimary
        }

        beforeSideEffect?.invoke()
        WorkspaceManager.takePersistablePermission(appContext, newUri, write = true)
        var committed = false
        try {
            val updatedPrimary = oldPrimary.copy(
                treeUri = newUri.toString(),
                displayName = newDisplayName,
                mode = MountMode.READ_WRITE,
                isPrimary = true
            )
            beforeCommit?.invoke()
            val saved = store.saveProject(
                project.copy(mounts = project.mounts.map { if (it.id == oldPrimary.id) updatedPrimary else it })
            )
            committed = true

            invalidateCleanMirrorState(saved.id)
            try { WorkspaceManager.updateLegacyWorkspacePointer(appContext, newUri) } catch (_: Exception) {}

            val oldUri = oldPrimary.treeUri
            if (store.getAllProjects().none { p -> p.mounts.any { it.treeUri == oldUri } }) {
                WorkspaceManager.releasePersistablePermission(appContext, Uri.parse(oldUri), write = true)
            }
            return saved.primaryMount() ?: throw IllegalStateException("PRIMARY_MOUNT_INVALID after replacement")
        } finally {
            if (!committed && store.getAllProjects().none { p -> p.mounts.any { it.treeUri == newUri.toString() } }) {
                WorkspaceManager.releasePersistablePermission(appContext, newUri, write = true)
            }
        }
    }

    fun addMount(
        projectId: String,
        alias: String,
        treeUri: Uri,
        displayName: String,
        mode: MountMode,
        executionPath: String,
        beforeSideEffect: (() -> Unit)? = null,
        beforeCommit: (() -> Unit)? = null
    ): WorkspaceMount {
        ensureTopologyMutable(projectId)
        val project = store.getProject(projectId) ?: throw IllegalArgumentException("Project not found: $projectId")
        val normalizedAlias = alias.lowercase().trim()
        require(project.findMount(normalizedAlias) == null) { "Mount alias already exists: $normalizedAlias" }

        beforeSideEffect?.invoke()
        WorkspaceManager.takePersistablePermission(appContext, treeUri, write = mode == MountMode.READ_WRITE)
        var committed = false
        try {
            val mount = WorkspaceMount(
                id = UUID.randomUUID().toString(),
                alias = normalizedAlias,
                treeUri = treeUri.toString(),
                displayName = displayName,
                mode = mode,
                executionPath = executionPath.trim(),
                isPrimary = false
            )
            beforeCommit?.invoke()
            val saved = store.saveProject(project.copy(mounts = project.mounts + mount))
            committed = true
            invalidateCleanMirrorState(saved.id)
            return saved.findMount(normalizedAlias) ?: throw IllegalStateException("New mount was not persisted")
        } finally {
            if (!committed && store.getAllProjects().none { p -> p.mounts.any { it.treeUri == treeUri.toString() } }) {
                WorkspaceManager.releasePersistablePermission(appContext, treeUri, write = mode == MountMode.READ_WRITE)
            }
        }
    }

    /** Re-grants a revoked/invalid capability without changing its logical identity. */
    fun reauthorizeMount(
        projectId: String,
        mountId: String,
        treeUri: Uri,
        displayName: String,
        beforeSideEffect: (() -> Unit)? = null,
        beforeCommit: (() -> Unit)? = null
    ): WorkspaceMount {
        ensureTopologyMutable(projectId)
        val project = store.getProject(projectId) ?: throw IllegalArgumentException("Project not found: $projectId")
        val old = project.findMountById(mountId) ?: throw IllegalArgumentException("Mount not found: $mountId")
        if (old.isPrimary) return replacePrimaryMount(treeUri, displayName, beforeSideEffect, beforeCommit)

        beforeSideEffect?.invoke()
        WorkspaceManager.takePersistablePermission(appContext, treeUri, write = old.mode == MountMode.READ_WRITE)
        var committed = false
        try {
            val updated = old.copy(treeUri = treeUri.toString(), displayName = displayName)
            beforeCommit?.invoke()
            val saved = store.saveProject(project.copy(mounts = project.mounts.map { if (it.id == old.id) updated else it }))
            committed = true
            invalidateCleanMirrorState(saved.id)

            if (old.treeUri != treeUri.toString() &&
                store.getAllProjects().none { p -> p.mounts.any { it.treeUri == old.treeUri } }
            ) {
                WorkspaceManager.releasePersistablePermission(
                    appContext,
                    Uri.parse(old.treeUri),
                    write = old.mode == MountMode.READ_WRITE
                )
            }
            return saved.findMountById(old.id) ?: throw IllegalStateException("Reauthorized mount disappeared")
        } finally {
            if (!committed && store.getAllProjects().none { p -> p.mounts.any { it.treeUri == treeUri.toString() } }) {
                WorkspaceManager.releasePersistablePermission(appContext, treeUri, write = old.mode == MountMode.READ_WRITE)
            }
        }
    }

    /**
     * Re-read and re-verify the exact expansion immediately before a picker
     * result can mutate project topology.  The first check protects the
     * Activity hand-off; this second check closes the result-to-mutation race
     * where a revoke or generation change could otherwise land in between.
     */
    private data class VerifiedWorkspaceExpansion(
        val project: VegaProject,
        val requirement: CapabilityRequirement.CapabilityExpansion,
        val lease: CapabilityLease
    )

    private fun verifiedWorkspaceExpansion(request: PendingWorkspaceRequest): VerifiedWorkspaceExpansion {
        val project = store.getProject(request.projectId)
            ?: throw IllegalArgumentException("Project not found: ${request.projectId}")
        val expansion = CapabilityRequirement.CapabilityExpansion(
            project.id,
            request.alias,
            request.mode,
            request.executionPath
        )
        if (request.rootTaskId.isBlank() || request.projectGeneration != project.generation ||
            request.capabilityKey != CapabilityApprovalKey.expansion(expansion)
        ) {
            throw SecurityException("CAPABILITY_REQUEST_STALE: project generation or expansion identity changed")
        }
        val taskContext = TaskContext(
            request.rootTaskId,
            project.id,
            project.generation,
            CapabilityMode.ACCEPT,
            request.rootTaskId
        )
        val broker = CapabilityBrokerRegistry.get(appContext)
        val lease = when (val checked = broker.check(taskContext, expansion)) {
            is CapabilityCheck.Allowed -> checked.lease.also { broker.verify(it, expansion) }
            is CapabilityCheck.NeedsApproval -> throw SecurityException("CAPABILITY_REQUEST_STALE: expansion approval is no longer active")
            is CapabilityCheck.Denied -> throw SecurityException("CAPABILITY_REQUEST_DENIED: ${checked.reason}")
        }
        return VerifiedWorkspaceExpansion(project, expansion, lease)
    }

    /** Grant one durable Agent request; a revoked alias is reauthorized in place. */
    fun grantWorkspaceRequest(request: PendingWorkspaceRequest, treeUri: Uri, selectedDisplayName: String): WorkspaceMount {
        val first = verifiedWorkspaceExpansion(request)

        // The picker result is an authority hand-off. Re-fetch the topology
        // and perform one last exact lease check directly before add/reauthorize
        // so revocation and generation changes fail closed at the mutation
        // boundary rather than after SAF permission is taken.
        val latest = verifiedWorkspaceExpansion(request)
        if (latest.project.id != first.project.id || latest.project.generation != first.project.generation) {
            throw SecurityException("CAPABILITY_REQUEST_STALE: project changed during picker hand-off")
        }
        val latestExisting = latest.project.findMount(request.alias)
        val broker = CapabilityBrokerRegistry.get(appContext)
        val reverifyBeforeMutation: () -> Unit = {
            val current = store.getProject(request.projectId)
            if (current == null || current.generation != latest.project.generation) {
                throw SecurityException("CAPABILITY_REQUEST_STALE: project changed before workspace mutation")
            }
            broker.verify(latest.lease, latest.requirement)
        }
        return if (latestExisting != null) {
            if (router.checkMountState(latestExisting) == MountState.AVAILABLE) latestExisting
            else reauthorizeMount(
                latest.project.id,
                latestExisting.id,
                treeUri,
                selectedDisplayName,
                beforeSideEffect = reverifyBeforeMutation,
                beforeCommit = reverifyBeforeMutation
            )
        } else {
            addMount(
                latest.project.id,
                request.alias,
                treeUri,
                selectedDisplayName,
                request.mode,
                request.executionPath,
                beforeSideEffect = reverifyBeforeMutation,
                beforeCommit = reverifyBeforeMutation
            )
        }
    }

    fun removeMount(projectId: String, mountId: String) {
        ensureTopologyMutable(projectId)
        val project = store.getProject(projectId) ?: return
        val target = project.findMountById(mountId) ?: return
        require(!target.isPrimary) { "Cannot remove primary mount of project" }

        val saved = store.saveProject(project.copy(mounts = project.mounts.filterNot { it.id == mountId }))
        invalidateCleanMirrorState(saved.id)
        if (store.getAllProjects().none { p -> p.mounts.any { it.treeUri == target.treeUri } }) {
            WorkspaceManager.releasePersistablePermission(
                appContext,
                Uri.parse(target.treeUri),
                write = target.mode == MountMode.READ_WRITE
            )
        }
    }
}
