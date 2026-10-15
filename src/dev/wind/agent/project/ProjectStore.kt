package dev.wind.agent.project

import android.content.Context
import dev.wind.agent.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Application-scoped durable source of truth for Vega projects.
 *
 * Important persistence rule: memory is updated only AFTER the replacement file
 * is safely installed. A failed disk write must never leave the process believing
 * a project configuration that cannot survive process death.
 */
class ProjectStore private constructor(private val context: Context) {

    private val storeFile = File(context.filesDir, "projects/projects.json")
    private val tempFile = File(context.filesDir, "projects/projects.json.new")
    private val backupFile = File(context.filesDir, "projects/projects.json.bak")

    @Volatile
    private var cachedProjects: MutableList<VegaProject>? = null

    @Volatile
    private var activeProjectId: String? = null

    companion object {
        @Volatile
        private var instance: ProjectStore? = null

        fun get(context: Context): ProjectStore {
            return instance ?: synchronized(this) {
                instance ?: ProjectStore(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        loadOrMigrate()
    }

    @Synchronized
    fun getActiveProject(): VegaProject? {
        val list = cachedProjects ?: return null
        val targetId = activeProjectId
        return if (targetId != null) list.firstOrNull { it.id == targetId } else null
    }

    @Synchronized
    fun getProject(projectId: String): VegaProject? =
        cachedProjects?.firstOrNull { it.id == projectId }

    @Synchronized
    fun setActiveProject(projectId: String) {
        val list = cachedProjects ?: mutableListOf()
        require(list.any { it.id == projectId }) { "Project not found: $projectId" }
        persistState(list, projectId)
        activeProjectId = projectId
    }

    @Synchronized
    fun getAllProjects(): List<VegaProject> = cachedProjects?.toList() ?: emptyList()

    /**
     * Saves one project and increments its generation exactly once.
     * The returned object is the exact durable value now present in the store.
     */
    @Synchronized
    fun saveProject(project: VegaProject): VegaProject {
        val oldList = cachedProjects?.toMutableList() ?: mutableListOf()
        val next = project.copy(generation = project.generation + 1)
        val idx = oldList.indexOfFirst { it.id == project.id }
        if (idx >= 0) oldList[idx] = next else oldList.add(next)
        val nextActive = activeProjectId ?: next.id

        // Persist first. Do not publish a new in-memory project until disk agrees.
        persistState(oldList, nextActive)
        cachedProjects = oldList
        activeProjectId = nextActive
        return next
    }

    @Synchronized
    fun deleteProject(projectId: String) {
        val oldList = cachedProjects?.toMutableList() ?: return
        if (oldList.none { it.id == projectId }) return
        oldList.removeAll { it.id == projectId }
        val nextActive = if (activeProjectId == projectId) oldList.firstOrNull()?.id else activeProjectId
        persistState(oldList, nextActive)
        cachedProjects = oldList
        activeProjectId = nextActive
    }

    private fun buildJson(list: List<VegaProject>, activeId: String?): JSONObject {
        val rootObj = JSONObject()
            .put("schemaVersion", 1)
            .put("activeProjectId", activeId ?: "")

        val projArr = JSONArray()
        for (p in list) {
            val pObj = JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("primaryMountId", p.primaryMountId)
                .put("generation", p.generation)
                .put("createdAt", p.createdAt)

            val mArr = JSONArray()
            for (m in p.mounts) {
                mArr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("alias", m.alias)
                        .put("treeUri", m.treeUri)
                        .put("displayName", m.displayName)
                        .put("mode", m.mode.name)
                        .put("executionPath", m.executionPath)
                        .put("isPrimary", m.isPrimary)
                )
            }
            pObj.put("mounts", mArr)
            projArr.put(pObj)
        }
        rootObj.put("projects", projArr)
        return rootObj
    }

    /**
     * Crash-safe replacement using new -> old backup -> new install.
     * If installation fails, the old file is restored before the exception escapes.
     */
    private fun persistState(list: List<VegaProject>, activeId: String?) {
        val parent = storeFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create project store directory")
        }

        val bytes = buildJson(list, activeId).toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tempFile).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }

        if (backupFile.exists() && !backupFile.delete()) {
            throw IllegalStateException("Failed to remove stale project store backup")
        }

        val hadOld = storeFile.exists()
        if (hadOld && !storeFile.renameTo(backupFile)) {
            tempFile.delete()
            throw IllegalStateException("Failed to stage previous project store")
        }

        if (!tempFile.renameTo(storeFile)) {
            if (hadOld) backupFile.renameTo(storeFile)
            throw IllegalStateException("Failed to install new project store")
        }

        // The new store is now authoritative. The backup is cleanup only.
        if (backupFile.exists()) backupFile.delete()
    }

    @Synchronized
    private fun loadOrMigrate() {
        val parent = storeFile.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()

        // Recover the strongest valid candidate after an interrupted replacement.
        if (!storeFile.isFile) {
            when {
                tempFile.isFile -> tempFile.renameTo(storeFile)
                backupFile.isFile -> backupFile.renameTo(storeFile)
            }
        } else {
            // Main store exists, therefore .new is uncommitted debris and .bak is obsolete.
            if (tempFile.exists()) tempFile.delete()
            if (backupFile.exists()) backupFile.delete()
        }

        if (storeFile.isFile) {
            try {
                val root = JSONObject(storeFile.readText(Charsets.UTF_8))
                val persistedActive = root.optString("activeProjectId").takeIf { it.isNotBlank() }
                val projArr = root.optJSONArray("projects") ?: JSONArray()
                val list = mutableListOf<VegaProject>()
                for (i in 0 until projArr.length()) {
                    val pObj = projArr.getJSONObject(i)
                    val mArr = pObj.optJSONArray("mounts") ?: JSONArray()
                    val mounts = mutableListOf<WorkspaceMount>()
                    for (j in 0 until mArr.length()) {
                        val mObj = mArr.getJSONObject(j)
                        mounts.add(
                            WorkspaceMount(
                                id = mObj.getString("id"),
                                alias = mObj.getString("alias"),
                                treeUri = mObj.getString("treeUri"),
                                displayName = mObj.getString("displayName"),
                                mode = MountMode.valueOf(mObj.optString("mode", MountMode.READ_WRITE.name)),
                                executionPath = mObj.getString("executionPath"),
                                isPrimary = mObj.optBoolean("isPrimary", false)
                            )
                        )
                    }
                    list.add(
                        VegaProject(
                            id = pObj.getString("id"),
                            name = pObj.getString("name"),
                            primaryMountId = pObj.getString("primaryMountId"),
                            mounts = mounts,
                            generation = pObj.optLong("generation", 0L),
                            createdAt = pObj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                cachedProjects = list
                activeProjectId = persistedActive?.takeIf { id -> list.any { it.id == id } }
                    ?: list.firstOrNull()?.id
                return
            } catch (_: Exception) {
                val corrupt = File(storeFile.parentFile, "projects.json.corrupt." + System.currentTimeMillis())
                storeFile.renameTo(corrupt)
            }
        }

        // One-time migration from the old single-workspace preference.
        val legacyUri = Prefs(context).workspaceUri()
        if (!legacyUri.isNullOrBlank()) {
            val mountId = UUID.randomUUID().toString()
            val projId = UUID.randomUUID().toString()
            val mount = WorkspaceMount(
                id = mountId,
                alias = "main",
                treeUri = legacyUri,
                displayName = "Primary Workspace",
                mode = MountMode.READ_WRITE,
                executionPath = "main",
                isPrimary = true
            )
            val defaultProj = VegaProject(
                id = projId,
                name = "Default Project",
                primaryMountId = mountId,
                mounts = listOf(mount)
            )
            val initial = mutableListOf(defaultProj)
            persistState(initial, projId)
            cachedProjects = initial
            activeProjectId = projId
        } else {
            cachedProjects = mutableListOf()
            activeProjectId = null
        }
    }
}
