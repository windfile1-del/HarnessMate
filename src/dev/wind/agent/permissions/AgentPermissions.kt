package dev.wind.agent.permissions

import android.content.Context
import dev.wind.agent.capability.*
import dev.wind.agent.project.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class PermissionScope { TASK, GLOBAL }
enum class AgentCapability { WORKSPACE_READ, WORKSPACE_WRITE, ALL_READ, ALL_WRITE, PATH_READ, PATH_WRITE, SCREEN, ACCESSIBILITY, LOCAL_ADB, VIRTUAL_DISPLAY, RAW_ADB_SHELL, ROOT, RAW_ROOT_SHELL }
enum class WriteStrategy { TRANSACTIONAL, DIRECT }
enum class ActionPolicy { CONSERVATIVE, BALANCED, AUTONOMOUS }
data class AgentGrant(val capability: AgentCapability, val path: String = "", val scope: PermissionScope,
    val taskId: String = "", val grantedAt: Long = System.currentTimeMillis())

/** Only global grants are persisted. Task authority cannot survive process death. */
class AgentPermissions(private val persist: (String) -> Unit = {}, saved: String? = null) {
    private val grants = mutableListOf<AgentGrant>()
    private val events = mutableListOf<JSONObject>()
    private val endedTasks = mutableSetOf<String>()
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun observeChanges(listener: () -> Unit): AutoCloseable {
        changeListeners.add(listener)
        return AutoCloseable { changeListeners.remove(listener) }
    }
    @Volatile var revision = 0L; private set
    private var globalAuthorityRevision = 0L
    private val ownerAuthorityRevisions = mutableMapOf<String, Long>()
    /** Service leases survive unrelated task completion, never global revocation. */
    @Synchronized fun authorityRevision(owner: String) = globalAuthorityRevision + (ownerAuthorityRevisions[owner] ?: 0L)
    var strategy = WriteStrategy.TRANSACTIONAL; private set
    var policy = ActionPolicy.BALANCED; private set
    init {
        if (saved != null) runCatching {
            val json = JSONObject(saved)
            strategy = WriteStrategy.valueOf(json.getString("strategy"))
            policy = ActionPolicy.valueOf(json.getString("policy"))
            val array = json.getJSONArray("grants")
            val loaded = (0 until array.length()).map { i -> val item = array.getJSONObject(i)
                require(item.getString("scope") == PermissionScope.GLOBAL.name && item.optString("task_id").isEmpty()) { "PERSISTED_TASK_PERMISSION_REJECTED" }
                AgentGrant(AgentCapability.valueOf(item.getString("capability")), item.optString("path"), PermissionScope.GLOBAL,
                    grantedAt = item.getLong("granted_at")) }
            grants.addAll(loaded)
            val history = json.optJSONArray("events") ?: JSONArray()
            for (i in 0 until history.length()) events.add(history.getJSONObject(i))
        }.onFailure { grants.clear(); strategy = WriteStrategy.TRANSACTIONAL; policy = ActionPolicy.BALANCED }
        else grants.add(AgentGrant(AgentCapability.WORKSPACE_READ, scope = PermissionScope.GLOBAL))
    }
    companion object {
        private val stores = ConcurrentHashMap<String, AgentPermissions>()
        fun get(context: Context): AgentPermissions {
            val app = context.applicationContext
            val key = app.filesDir.absolutePath
            return stores[key] ?: synchronized(stores) { stores[key] ?: run {
                val prefs = app.getSharedPreferences("agent_permissions_v1", Context.MODE_PRIVATE)
                val store = AgentPermissions({ value -> check(prefs.edit().putString("state", value).commit()) { "PERMISSION_SAVE_FAILED" } }, prefs.getString("state", null))
                if (!prefs.contains("state")) {
                    // Migrate explicit observation/Full choices without granting file write authority.
                    val phone = app.getSharedPreferences("phone_automation", Context.MODE_PRIVATE)
                    val mode = phone.getString("mode", "OFF")
                    if (mode in setOf("UI", "HYBRID")) store.set(AgentCapability.ACCESSIBILITY, PermissionScope.GLOBAL, true)
                    if (mode in setOf("VISUAL", "HYBRID")) store.set(AgentCapability.SCREEN, PermissionScope.GLOBAL, true)
                    if (phone.getString("approval_mode", "SAFE") == "FULL") store.configure(policy = ActionPolicy.AUTONOMOUS)
                    store.save()
                }
                store.also { stores[key] = it }
            } }
        }
        fun isPreview(cap: AgentCapability) = cap in setOf(AgentCapability.ROOT, AgentCapability.RAW_ROOT_SHELL)
        fun covers(root: String, path: String) = path == root.trimEnd('/') || path.startsWith(root.trimEnd('/') + "/")
        fun mountKey(project: VegaProject, mount: WorkspaceMount) = "mount://${project.id}/${mount.id}/" + java.net.URLEncoder.encode(mount.treeUri, "UTF-8")
        fun displayPath(context: Context, path: String): String {
            val project = ProjectStore.get(context).getActiveProject() ?: return path
            val mount = project.mounts.firstOrNull { covers(mountKey(project, it), path) }
            if (mount == null) {
                if (!path.startsWith("mount://")) return path
                val parts = path.removePrefix("mount://").split('/', limit = 4)
                return if (parts.size >= 3) java.net.URLDecoder.decode(parts[2], "UTF-8") + (parts.getOrNull(3)?.let { "/$it" } ?: "") + " (inactive mount)" else path
            }
            return "${mount.treeUri}" + path.removePrefix(mountKey(project, mount)) + " (project://${mount.alias}/)"
        }
    }
    @Synchronized fun snapshot(taskId: String = "") = grants.filter { it.scope == PermissionScope.GLOBAL || it.taskId == taskId }.toList()
    @Synchronized fun audit() = events.map { JSONObject(it.toString()) }
    @Synchronized fun enabled(cap: AgentCapability, taskId: String = "") = snapshot(taskId).any { it.capability == cap }
    @Synchronized fun set(cap: AgentCapability, scope: PermissionScope, enabled: Boolean, path: String = "", taskId: String = "") {
        require(scope != PermissionScope.TASK || taskId.isNotBlank()) { "ACTIVE_TASK_REQUIRED" }
        require(!enabled || scope != PermissionScope.TASK || !endedTasks.contains(taskId)) { "TASK_ALREADY_ENDED" }
        require(cap !in setOf(AgentCapability.PATH_READ, AgentCapability.PATH_WRITE) || path.isNotBlank()) { "DIRECTORY_REQUIRED" }
        grants.removeAll { it.capability == cap && it.scope == scope && it.path == path && (scope == PermissionScope.GLOBAL || it.taskId == taskId) }
        if (enabled) grants.add(AgentGrant(cap, path, scope, if (scope == PermissionScope.TASK) taskId else ""))
        changed(if (enabled) "GRANT" else "REVOKE", cap.name, path, scope.name, taskId)
    }
    @Synchronized fun configure(strategy: WriteStrategy = this.strategy, policy: ActionPolicy = this.policy) {
        this.strategy = strategy; this.policy = policy; changed("CONFIGURE", strategy.name + ":" + policy.name)
    }
    @Synchronized fun grantIfCurrent(expectedRevision: Long, taskId: String, capability: AgentCapability, scope: PermissionScope, path: String): Boolean {
        if (revision != expectedRevision || endedTasks.contains(taskId)) return false
        set(capability, scope, true, path, taskId)
        return true
    }
    @Synchronized fun beginTask(taskId: String) { endedTasks.remove(taskId) }
    @Synchronized fun taskEnded(taskId: String) = endedTasks.contains(taskId)
    @Synchronized fun endTask(taskId: String) {
        if (!endedTasks.add(taskId)) return
        grants.removeAll { it.scope == PermissionScope.TASK && it.taskId == taskId }; changed("TASK_END", taskId = taskId)
    }
    @Synchronized fun revokeAll(scope: PermissionScope) { grants.removeAll { it.scope == scope }; changed("REVOKE_ALL", scope = scope.name) }
    @Synchronized fun reset() { grants.clear(); grants.add(AgentGrant(AgentCapability.WORKSPACE_READ, scope = PermissionScope.GLOBAL)); strategy = WriteStrategy.TRANSACTIONAL; policy = ActionPolicy.BALANCED; changed("RESET") }
    private fun changed(event: String, cap: String = "", path: String = "", scope: String = "", taskId: String = "") {
        revision++
        if (taskId.isBlank() || scope == PermissionScope.GLOBAL.name) globalAuthorityRevision++
        else ownerAuthorityRevisions[taskId] = (ownerAuthorityRevisions[taskId] ?: 0L) + 1
        events.add(JSONObject().put("event", event).put("capability", cap).put("path", path).put("scope", scope).put("task_id", taskId).put("at", System.currentTimeMillis()))
        while (events.size > 200) events.removeAt(0)
        try { save() } finally { changeListeners.forEach { runCatching(it) } }
    }
    @Synchronized private fun save() {
        persist(JSONObject().put("strategy", strategy.name).put("policy", policy.name)
            .put("grants", JSONArray(grants.filter { it.scope == PermissionScope.GLOBAL }.map(::json)))
            .put("events", JSONArray(events)).toString())
    }
    private fun json(g: AgentGrant) = JSONObject().put("capability", g.capability.name).put("path", g.path).put("scope", g.scope.name).put("task_id", g.taskId).put("granted_at", g.grantedAt)
    @Synchronized fun status(taskId: String) = JSONObject().put("task_id", taskId).put("write_strategy", strategy.name).put("action_policy", policy.name)
        .put("effective_read_paths", JSONArray(snapshot(taskId).filter { it.capability in setOf(AgentCapability.PATH_READ, AgentCapability.PATH_WRITE) }.map { it.path }.distinct()))
        .put("effective_write_paths", JSONArray(snapshot(taskId).filter { it.capability == AgentCapability.PATH_WRITE }.map { it.path }.distinct()))
        .put("workspace_read", enabled(AgentCapability.WORKSPACE_READ, taskId) || enabled(AgentCapability.WORKSPACE_WRITE, taskId) || enabled(AgentCapability.ALL_READ, taskId) || enabled(AgentCapability.ALL_WRITE, taskId))
        .put("workspace_write", enabled(AgentCapability.WORKSPACE_WRITE, taskId) || enabled(AgentCapability.ALL_WRITE, taskId))
        .put("all_accessible_read", enabled(AgentCapability.ALL_READ, taskId) || enabled(AgentCapability.ALL_WRITE, taskId))
        .put("all_accessible_write", enabled(AgentCapability.ALL_WRITE, taskId))
        .put("global", JSONArray(snapshot().filter { it.scope == PermissionScope.GLOBAL }.map(::json)))
        .put("task", JSONArray(snapshot(taskId).filter { it.scope == PermissionScope.TASK }.map(::json)))
        .put("effective", JSONArray(AgentCapability.values().map { cap -> JSONObject().put("capability", cap.name)
            .put("configured", enabled(cap, taskId)).put("available", !isPreview(cap) && cap !in setOf(AgentCapability.LOCAL_ADB, AgentCapability.RAW_ADB_SHELL))
            .put("status", if (isPreview(cap)) "PREVIEW_NOT_IMPLEMENTED" else if (cap in setOf(AgentCapability.LOCAL_ADB, AgentCapability.RAW_ADB_SHELL)) "ADB_CONNECTION_REQUIRED" else "ANDROID_AUTHORITY_REQUIRED") }))
        .put("events", JSONArray(audit()))
    @Synchronized fun allows(path: String, workspace: Boolean, write: Boolean, taskId: String): Boolean = snapshot(taskId).any { g ->
        when (g.capability) {
            AgentCapability.ALL_WRITE -> true
            AgentCapability.ALL_READ -> !write
            AgentCapability.WORKSPACE_WRITE -> workspace
            AgentCapability.WORKSPACE_READ -> workspace && !write
            AgentCapability.PATH_WRITE -> covers(g.path, path)
            AgentCapability.PATH_READ -> !write && covers(g.path, path)
            else -> false
        }
    }
    @Synchronized fun mountAccess(project: VegaProject, mount: WorkspaceMount, write: Boolean, task: String): Boolean {
        val key = mountKey(project, mount)
        return allows(key, mount.isPrimary || mount.id == project.primaryMountId, write, task) || snapshot(task).any {
            it.path.startsWith(key + "/") && (it.capability == AgentCapability.PATH_WRITE || !write && it.capability == AgentCapability.PATH_READ)
        }
    }
}

data class PermissionNeed(val capability: AgentCapability, val path: String = "", val workspace: Boolean = false, val write: Boolean = false) {
    fun satisfied(store: AgentPermissions, task: String) = if (capability in setOf(AgentCapability.PATH_READ, AgentCapability.PATH_WRITE))
        store.allows(path, workspace, write, task) else store.enabled(capability, task) && !AgentPermissions.isPreview(capability)
}

/** Resolves the complete operation, including both endpoints and all mirror-exported mounts. */
class AgentPermissionGate(private val context: Context?, private val permissions: AgentPermissions? = null,
    private val projectLookup: () -> VegaProject? = { context?.let { ProjectStore.get(it).getActiveProject() } }) {
    constructor(store: AgentPermissions, project: () -> VegaProject?) : this(null, store, project)
    private val store get() = permissions ?: AgentPermissions.get(requireNotNull(context))
    fun directory(raw: String, write: Boolean): PermissionNeed { require(raw.isNotBlank()) { "DIRECTORY_REQUIRED" }; return path(raw, write) }
    private fun path(raw: String, write: Boolean): PermissionNeed {
        if (raw.startsWith("/")) return PermissionNeed(if (write) AgentCapability.PATH_WRITE else AgentCapability.PATH_READ, File(raw).canonicalPath, write = write)
        val project = projectLookup() ?: error("PROJECT_NOT_CONFIGURED")
        val parsed = ProjectPathParser.parse(raw, project)
        val mount = project.findMount(parsed.mountAlias) ?: error("MOUNT_NOT_IN_PROJECT")
        return PermissionNeed(if (write) AgentCapability.PATH_WRITE else AgentCapability.PATH_READ,
            AgentPermissions.mountKey(project, mount) + parsed.relativePath.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty(), mount.id == project.primaryMountId, write)
    }
    fun needs(name: String, a: JSONObject): List<PermissionNeed> {
        if(name=="phone_chat_guardian") return if(a.optString("mode","status") in setOf("status","pending","report","pause","takeover","stop")) emptyList()
            else listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.ACCESSIBILITY,AgentCapability.SCREEN).map { PermissionNeed(it) }
        if(name=="phone_display" && a.optString("action","status") in setOf("status","close")) return emptyList()
        if(name=="phone_display" || name.startsWith("phone_") && name !in setOf("phone_status","phone_apps","phone_operation_status") &&
            (a.has("display_session_id") || a.optString("screen")=="virtual" || a.optString("screen")!="main" && context!=null && dev.wind.agent.phone.VirtualDisplays.preference(context)=="virtual"))
            return listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.ACCESSIBILITY,AgentCapability.SCREEN).map { PermissionNeed(it) }
        if (name == "adb_status") return emptyList()
        if (name.startsWith("adb_")) {
            val plan = dev.wind.agent.adb.AdbCommands.plan(name, a)
            return (listOf(PermissionNeed(AgentCapability.LOCAL_ADB)) +
                plan.capabilities.map { PermissionNeed(it) } +
                plan.readPaths.map { path(it, false) } + plan.writePaths.map { path(it, true) }).distinct()
        }
        val reads = setOf("list_dir", "read_file", "file_info", "search_files", "glob")
        val writes = setOf("write_file", "edit_file", "delete_path", "make_dir")
        fun raw() = listOf("destination", "destination_path", "dest", "path", "file_path", "filepath", "file", "filename", "target").firstNotNullOfOrNull { a.optString(it).trim().takeIf(String::isNotBlank) } ?: ""
        if (name in reads || name in writes) {
            val paths = a.optJSONArray("paths")
            return if (name == "delete_path" && paths != null) (0 until paths.length()).map { path(paths.getString(it), true) } else listOf(path(raw(), name in writes))
        }
        if (name == "move_path") return listOf(path(a.optString("source", a.optString("from")), true), path(a.optString("destination", a.optString("to")), true))
        if (name == "prepare_download_apply") return listOf(path(raw(), false))
        if (name.startsWith("phone_") && name !in setOf("phone_status", "phone_operation_status")) {
            val mode = a.optString("mode", if (context != null && dev.wind.agent.phone.PhoneAutomation.mode(context) == dev.wind.agent.phone.PhoneAutomation.Mode.VISUAL) "visual" else "ui")
            return buildList {
                if (name in setOf("phone_observe", "phone_action", "phone_wait", "phone_launch", "phone_sequence", "phone_flow") &&
                    (a.optString("backend") == "adb" || context != null && dev.wind.agent.phone.PhoneBackends.wantsAdb(context, a, dev.wind.agent.AgentBus.activeTaskId.orEmpty()))) add(PermissionNeed(AgentCapability.LOCAL_ADB))
                if (name != "phone_observe" || mode != "visual") add(PermissionNeed(AgentCapability.ACCESSIBILITY))
                if (mode in setOf("visual", "hybrid") || a.optString("observe_after") in setOf("visual", "hybrid") || a.has("visual_id")) add(PermissionNeed(AgentCapability.SCREEN))
            }
        }
        if (context == null) return emptyList()
        val requirement = CapabilityResolver(context).resolve(TaskContext("", projectLookup()?.id ?: "", 0), name, a)
        fun forRequirement(r: CapabilityRequirement): List<PermissionNeed> = when(r) {
            is CapabilityRequirement.Composite -> r.requirements.flatMap(::forRequirement)
            is CapabilityRequirement.ProjectRead -> mountNeeds(r.mountIds, false)
            is CapabilityRequirement.CanonicalWrite -> mountNeeds(r.mountIds, true)
            is CapabilityRequirement.CanonicalDelete -> mountNeeds(r.mountIds, true)
            is CapabilityRequirement.ApplyChanges -> mountNeeds(r.changedMountIds, true)
            is CapabilityRequirement.ArtifactInstall -> r.targetMountId?.let { mountNeeds(setOf(it), true) } ?: emptyList()
            is CapabilityRequirement.MirrorRead, is CapabilityRequirement.MirrorMutation, is CapabilityRequirement.Execution -> {
                val p = projectLookup()
                val reads = mountNeeds(p?.mounts?.map { it.id }?.toSet().orEmpty(), false)
                if (r is CapabilityRequirement.MirrorMutation && p != null && name.startsWith("execution_")) {
                    val raw = a.optString(if (name == "execution_import_archive") "destination" else "path").removePrefix("execution://").replace('\\', '/').trim('/')
                    val first = raw.substringBefore('/')
                    val mount = p.findMount(first) ?: p.primaryMount() ?: error("PRIMARY_MOUNT_REQUIRED")
                    val relative = dev.wind.agent.workspace.WorkspacePath.sanitize(if (p.findMount(first) != null) raw.substringAfter('/', "") else raw.removePrefix(mount.executionPath + "/"))
                    reads + PermissionNeed(AgentCapability.PATH_WRITE, AgentPermissions.mountKey(p, mount) + relative.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty(), mount.id == p.primaryMountId, true) +
                        if (name == "execution_import_archive") listOf(path(a.getString("workspace_path"), false)) else emptyList()
                } else reads
            }
            else -> emptyList()
        }
        return forRequirement(requirement)
    }
    private fun mountNeeds(ids: Set<String>, write: Boolean): List<PermissionNeed> {
        val p = projectLookup() ?: return emptyList()
        return ids.map { id -> val m = p.findMountById(id) ?: error("MOUNT_NOT_IN_PROJECT")
            PermissionNeed(if (write) AgentCapability.PATH_WRITE else AgentCapability.PATH_READ, AgentPermissions.mountKey(p, m), m.id == p.primaryMountId, write) }
    }
    fun error(name: String, a: JSONObject, task: String): String? = needs(name, a).firstOrNull { !it.satisfied(store, task) }?.let { "AGENT_PERMISSION_REQUIRED: ${it.capability} ${it.path}" }
    fun authorize(name: String, a: JSONObject, task: String, approve: (CapabilityRequest) -> CapabilityDecision): Boolean {
        for (need in needs(name, a).distinct()) {
            if (need.satisfied(store, task)) continue
            val request = CapabilityRequest("agent-permission-" + java.util.UUID.randomUUID(), name,
                JSONObject().put("agent_permission", need.capability.name).put("path", context?.let { AgentPermissions.displayPath(it, need.path) } ?: need.path).put("reason", "This operation requires ${need.capability}; rejecting permits replanning or output to an authorized workspace."),
                TaskContext(task, "", 0), CapabilityRequirement.Internal(), "agent-permission", "${need.capability}: ${need.path}", System.currentTimeMillis(),
                allowedScopes = setOf(GrantScope.TASK, GrantScope.APP_SESSION))
            val revision = store.revision
            val decision = approve(request)
            if (decision !is CapabilityDecision.Allow || decision.scope !in request.allowedScopes) return false
            if (!store.grantIfCurrent(revision, task, need.capability, if (decision.scope == GrantScope.APP_SESSION) PermissionScope.GLOBAL else PermissionScope.TASK, need.path)) return false
        }
        return error(name, a, task) == null
    }
}
