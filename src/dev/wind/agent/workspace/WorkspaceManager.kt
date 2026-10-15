package dev.wind.agent.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.wind.agent.Prefs

/**
 * Low-level SAF grant helpers plus the legacy primary-workspace compatibility view.
 * ProjectManager is the only owner of project topology.
 */
object WorkspaceManager {

    @Volatile
    private var cachedFs: WorkspaceFs? = null

    fun hasWorkspace(context: Context): Boolean {
        val uriStr = Prefs(context).workspaceUri() ?: return false
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return false }
        return try {
            val match = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            match != null && match.isReadPermission && match.isWritePermission
        } catch (_: Exception) {
            false
        }
    }

    fun getWorkspaceUri(context: Context): Uri? {
        val uriStr = Prefs(context).workspaceUri() ?: return null
        return try { Uri.parse(uriStr) } catch (_: Exception) { null }
    }

    fun getWorkspaceId(context: Context): String =
        dev.wind.agent.shell.mirror.WorkspaceIdentity.fromUri(
            getWorkspaceUri(context)?.toString() ?: throw IllegalStateException("Workspace not configured")
        )

    /**
     * Legacy compatibility entry point. All topology mutation is delegated to
     * ProjectManager so this method can never release a grant behind ProjectStore.
     */
    @Deprecated("Use ProjectManager.selectPrimaryWorkspace")
    fun setWorkspaceUri(context: Context, uri: Uri) {
        val displayName = try {
            SafWorkspaceFs(context.applicationContext, uri).getRootDisplayName()
        } catch (_: Exception) {
            "Workspace"
        }
        dev.wind.agent.project.ProjectManager(context).selectPrimaryWorkspace(uri, displayName)
    }

    /**
     * Updates ONLY the compatibility pointer/cache after ProjectStore has committed.
     * It deliberately does not acquire/release permissions or mutate ProjectStore.
     */
    fun updateLegacyWorkspacePointer(context: Context, uri: Uri?) {
        Prefs(context).setWorkspaceUri(uri?.toString())
        cachedFs = uri?.let { SafWorkspaceFs(context.applicationContext, it) }
    }

    fun takePersistablePermission(context: Context, uri: Uri) {
        takePersistablePermission(context, uri, write = true)
    }

    fun takePersistablePermission(context: Context, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            val mode = if (write) "read/write" else "read-only"
            throw SecurityException("Failed to grant persistable $mode access to selected directory: ${e.message}")
        }
    }

    fun releasePersistablePermission(context: Context, uri: Uri) {
        releasePersistablePermission(context, uri, write = true)
    }

    fun releasePersistablePermission(context: Context, uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            context.contentResolver.releasePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Releasing a capability is cleanup. Callers already removed it from
            // ProjectStore, so a provider refusing release must not resurrect it.
        }
    }

    /** Legacy pointer cleanup. Never release a URI still referenced by any project. */
    fun clearWorkspace(context: Context) {
        val uri = getWorkspaceUri(context)
        if (uri != null) {
            val referenced = try {
                dev.wind.agent.project.ProjectStore.get(context).getAllProjects()
                    .any { p -> p.mounts.any { it.treeUri == uri.toString() } }
            } catch (_: Exception) {
                true // fail closed: unknown ownership means do not release capability
            }
            if (!referenced) releasePersistablePermission(context, uri, write = true)
        }
        updateLegacyWorkspacePointer(context, null)
    }

    fun getFs(context: Context): WorkspaceFs {
        cachedFs?.let { cached ->
            val current = getWorkspaceUri(context)
            if (current != null && hasWorkspace(context)) return cached
            cachedFs = null
        }
        val uri = getWorkspaceUri(context)
        return if (uri != null && hasWorkspace(context)) {
            SafWorkspaceFs(context.applicationContext, uri).also { cachedFs = it }
        } else {
            NoOpWorkspaceFs
        }
    }

    fun createOpenDocumentTreeIntent(write: Boolean = true): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
    }

    private object NoOpWorkspaceFs : WorkspaceFs {
        override fun isConfigured(): Boolean = false
        override fun getRootDisplayName(): String = "No Workspace Configured"
        override fun list(path: String): List<WorkspaceEntry> = emptyList()
        override fun read(path: String): ByteArray = throw IllegalStateException("Workspace not configured")
        override fun readText(path: String, charset: String): String = throw IllegalStateException("Workspace not configured")
        override fun write(path: String, data: ByteArray) = throw IllegalStateException("Workspace not configured")
        override fun writeText(path: String, text: String, charset: String) = throw IllegalStateException("Workspace not configured")
        override fun mkdir(path: String): Boolean = false
        override fun delete(path: String): Boolean = false
        override fun move(from: String, to: String): Boolean = false
        override fun exists(path: String): Boolean = false
        override fun stat(path: String): WorkspaceEntry? = null
        override fun search(query: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> = emptyList()
        override fun glob(pattern: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> = emptyList()
        override fun openInputStream(path: String): java.io.InputStream = throw IllegalStateException("Workspace not configured")
        override fun openOutputStream(path: String): java.io.OutputStream = throw IllegalStateException("Workspace not configured")
    }
}
