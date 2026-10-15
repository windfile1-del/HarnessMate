package dev.wind.agent.project

import android.content.Context
import android.net.Uri
import dev.wind.agent.workspace.SafWorkspaceFs
import dev.wind.agent.workspace.WorkspaceFs
import java.util.concurrent.ConcurrentHashMap

data class ResolvedProjectPath(
    val projectId: String,
    val mount: WorkspaceMount,
    val fs: WorkspaceFs,
    val relativePath: String
)

open class WorkspaceRouter(
    private val context: Context,
    private val projectStore: ProjectStore = ProjectStore.get(context)
) {
    private val fsCache = ConcurrentHashMap<String, WorkspaceFs>()

    fun getActiveProject(): VegaProject {
        return projectStore.getActiveProject()
            ?: throw IllegalStateException("No active project configured")
    }

    open fun checkMountState(mount: WorkspaceMount): MountState {
        return try {
            val uri = Uri.parse(mount.treeUri)
            val perms = context.contentResolver.persistedUriPermissions
            val match = perms?.firstOrNull { it.uri == uri }
            if (match == null || !match.isReadPermission || (mount.mode == MountMode.READ_WRITE && !match.isWritePermission)) {
                MountState.PERMISSION_REVOKED
            } else {
                MountState.AVAILABLE
            }
        } catch (_: Exception) {
            MountState.INVALID
        }
    }

    fun resolve(rawPath: String?, write: Boolean = false): ResolvedProjectPath {
        if (rawPath?.startsWith("/") == true) {
            val fs = dev.wind.agent.workspace.DirectWorkspaceFs(context)
            val relative = fs.relative(rawPath)
            val mount = WorkspaceMount("android-user-storage", "storage", "file://" + fs.getRootDisplayName(), fs.getRootDisplayName(), executionPath = "storage")
            return ResolvedProjectPath("", mount, fs, relative)
        }
        val project = getActiveProject()
        val projectPath = ProjectPathParser.parse(rawPath, project)
        val mount = project.findMount(projectPath.mountAlias)
            ?: throw IllegalArgumentException("Mount alias not found: ${projectPath.mountAlias}")

        val state = checkMountState(mount)
        when (state) {
            MountState.AVAILABLE -> Unit
            MountState.PERMISSION_REVOKED ->
                throw SecurityException("MOUNT_PERMISSION_REVOKED: Permission for mount '${mount.alias}' was revoked by system or user. Please reauthorize this folder.")
            MountState.INVALID ->
                throw SecurityException("MOUNT_STATE_INVALID: Could not verify permission for mount '${mount.alias}'. Access denied (fail-close).")
        }

        if (write && mount.mode == MountMode.READ_ONLY) {
            throw SecurityException("READ_ONLY_MOUNT: Cannot write to read-only mount '${mount.alias}' (${projectPath.toVirtualUri()})")
        }

        val fs = getFsForMount(mount)
        return ResolvedProjectPath(
            projectId = project.id,
            mount = mount,
            fs = fs,
            relativePath = projectPath.relativePath
        )
    }

    open fun getFsForMount(mount: WorkspaceMount): WorkspaceFs {
        val cached = fsCache[mount.id]
        if (cached != null) return cached
        return synchronized(fsCache) {
            fsCache[mount.id] ?: SafWorkspaceFs(context.applicationContext, Uri.parse(mount.treeUri)).also {
                fsCache[mount.id] = it
            }
        }
    }

    fun readableMounts(): List<Pair<WorkspaceMount, WorkspaceFs>> {
        val project = getActiveProject()
        val permissions = dev.wind.agent.permissions.AgentPermissions.get(context)
        return project.mounts.filter { checkMountState(it) == MountState.AVAILABLE &&
            permissions.allows(dev.wind.agent.permissions.AgentPermissions.mountKey(project, it), it.id == project.primaryMountId, false, dev.wind.agent.AgentBus.activeTaskId.orEmpty()) }.map { it to getFsForMount(it) }
    }

    fun writableMounts(): List<Pair<WorkspaceMount, WorkspaceFs>> {
        val project = getActiveProject()
        return project.mounts.filter { it.mode == MountMode.READ_WRITE && checkMountState(it) == MountState.AVAILABLE }.map { it to getFsForMount(it) }
    }
}
