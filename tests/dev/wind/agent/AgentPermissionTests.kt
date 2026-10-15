package dev.wind.agent

import dev.wind.agent.permissions.*
import dev.wind.agent.project.*
import org.json.JSONObject

object AgentPermissionTests {
    private var checks = 0
    private fun verify(value: Boolean, message: String) { checks++; check(value) { message } }
    @JvmStatic fun main(args: Array<String>) {
        var saved = ""
        val p = AgentPermissions({ saved = it })
        verify(p.allows("mount://p/main/a", true, false, "a"), "default workspace read")
        verify(!p.allows("mount://p/main/a", true, true, "a"), "default no write")
        verify(!p.allows("/storage/emulated/0/Download/a", false, false, "a"), "default no external read")
        p.set(AgentCapability.PATH_READ, PermissionScope.GLOBAL, true, "/storage/emulated/0/Docs")
        verify(p.allows("/storage/emulated/0/Docs/a.txt", false, false, "a"), "global directory read")
        verify(!p.allows("/storage/emulated/0/Docs/a.txt", false, true, "a"), "read never implies write")
        verify(!p.allows("/storage/emulated/0/Docs2/a.txt", false, false, "a"), "prefix boundary")
        p.set(AgentCapability.PATH_WRITE, PermissionScope.TASK, true, "/storage/emulated/0/Download", "task-a")
        verify(p.allows("/storage/emulated/0/Download/a.txt", false, false, "task-a"), "write implies read")
        verify(p.allows("/storage/emulated/0/Download/a.txt", false, true, "task-a"), "task write")
        verify(!p.allows("/storage/emulated/0/Download/a.txt", false, true, "task-b"), "task identity isolation")
        val restored = AgentPermissions(saved = saved)
        verify(!restored.allows("/storage/emulated/0/Download/a.txt", false, true, "task-a"), "no crash inheritance")
        verify(restored.allows("/storage/emulated/0/Docs/a.txt", false, false, "task-b"), "global survives restart")
        val rev = p.revision
        p.endTask("task-a")
        verify(p.revision > rev, "end task invalidates tickets")
        verify(!p.allows("/storage/emulated/0/Download/a.txt", false, true, "task-a"), "end task revokes")
        verify(p.allows("/storage/emulated/0/Docs/a.txt", false, false, "task-b"), "end task preserves global")
        for (cap in AgentCapability.values()) {
            val path = if (cap in setOf(AgentCapability.PATH_READ, AgentCapability.PATH_WRITE)) "/test" else ""
            val store = AgentPermissions()
            store.set(cap, PermissionScope.TASK, true, path, "task")
            verify(store.enabled(cap, "task"), "$cap supports task scope")
            verify(!store.snapshot("another").any { it.capability == cap && it.scope == PermissionScope.TASK }, "$cap task isolation")
            store.set(cap, PermissionScope.GLOBAL, true, path)
            store.endTask("task")
            verify(store.enabled(cap, "another"), "$cap global scope")
            store.set(cap, PermissionScope.GLOBAL, false, path)
            verify(!store.enabled(cap, "another"), "$cap revoke")
        }
        for (cap in listOf(AgentCapability.ROOT, AgentCapability.RAW_ROOT_SHELL)) {
            p.set(cap, PermissionScope.GLOBAL, true)
            verify(!PermissionNeed(cap).satisfied(p, "task"), "$cap preview is not executable")
        }
        for (cap in listOf(AgentCapability.LOCAL_ADB, AgentCapability.RAW_ADB_SHELL)) {
            p.set(cap, PermissionScope.GLOBAL, true)
            verify(PermissionNeed(cap).satisfied(p, "task"), "$cap delegates to the connected ADB backend")
        }
        p.set(AgentCapability.ALL_WRITE, PermissionScope.TASK, true, taskId = "write-task")
        verify(p.allows("/any/user/path", false, false, "write-task"), "all write implies read")
        verify(p.allows("/any/user/path", false, true, "write-task"), "all write covers paths")
        verify(!p.enabled(AgentCapability.ACCESSIBILITY, "write-task"), "write does not grant automation")
        p.configure(strategy = WriteStrategy.DIRECT, policy = ActionPolicy.AUTONOMOUS)
        verify(p.strategy == WriteStrategy.DIRECT && p.policy == ActionPolicy.AUTONOMOUS, "independent strategy / policy")
        p.set(AgentCapability.ACCESSIBILITY, PermissionScope.TASK, true, taskId = "phone")
        verify(!p.allows("/outside", false, true, "phone"), "automation does not grant write")
        p.revokeAll(PermissionScope.GLOBAL)
        verify(p.snapshot("phone").any { it.capability == AgentCapability.ACCESSIBILITY }, "global revoke preserves task")
        verify(p.snapshot().isEmpty(), "global all revoked")
        val corrupt = AgentPermissions(saved = "invalid")
        verify(corrupt.snapshot().isEmpty(), "corrupt persistence fails closed")
        val persistedTask = JSONObject().put("strategy", "DIRECT").put("policy", "AUTONOMOUS").put("grants", org.json.JSONArray().put(
            JSONObject().put("capability", "ALL_WRITE").put("path", "").put("scope", "TASK").put("task_id", "old").put("granted_at", 1)))
        verify(AgentPermissions(saved = persistedTask.toString()).snapshot().isEmpty(), "persisted TASK cannot be promoted to GLOBAL")
        p.reset()
        verify(p.snapshot().size == 1 && p.enabled(AgentCapability.WORKSPACE_READ), "default reset")
        verify(p.strategy == WriteStrategy.TRANSACTIONAL && p.policy == ActionPolicy.BALANCED, "reset policy")
        verify(p.audit().any { it.optString("event") == "TASK_END" }, "task-end audit")
        verify(p.audit().any { it.optString("event") == "REVOKE_ALL" }, "revoke audit")
        val m = WorkspaceMount("id-1", "docs", "content://tree/one", "Docs", executionPath = "docs")
        val primary = WorkspaceMount("primary", "main", "content://tree/main", "Main", executionPath = "main", isPrimary = true)
        val project = VegaProject("project-1", "Project", "primary", listOf(primary, m))
        val q = AgentPermissions()
        q.set(AgentCapability.PATH_WRITE, PermissionScope.GLOBAL, true, AgentPermissions.mountKey(project, m) + "/notes")
        verify(q.mountAccess(project, m, true, "t"), "mount broker knows scoped write exists")
        verify(!q.allows(AgentPermissions.mountKey(project, m) + "/secret", false, true, "t"), "mount gate constrains subtree")
        val different = project.copy(id = "project-2")
        verify(!q.mountAccess(different, m, true, "t"), "mount grant cannot transfer across projects")
        verify(!q.mountAccess(project, m.copy(id = "replacement"), true, "t"), "mount replacement does not inherit")
        verify(!q.mountAccess(project, m.copy(treeUri = "content://tree/replaced"), true, "t"), "same mount ID with replaced tree URI does not inherit directory grant")
        verify(JSONObject(saved).getJSONArray("grants").let { arr -> (0 until arr.length()).all { arr.getJSONObject(it).getString("scope") == "GLOBAL" } }, "only global grants serialized")
        var thrown = false
        try { q.set(AgentCapability.SCREEN, PermissionScope.TASK, true) } catch (_: IllegalArgumentException) { thrown = true }
        verify(thrown, "task grant needs task id")
        val gate = AgentPermissionGate(q) { project }
        var approvals = 0
        val edit = JSONObject().put("path", "project://docs/notes/file.txt")
        verify(gate.error("edit_file", edit, "t") == null, "gate accepts granted subtree")
        verify(gate.error("edit_file", JSONObject().put("path", "project://docs/private.txt"), "t") != null, "gate rejects sibling subtree")
        verify(gate.error("write_file", JSONObject().put("path", "project://docs/notes/allowed.txt").put("destination", "project://docs/private.txt"), "t") != null,
            "path aliases use the same precedence as native file tools")
        val destination = JSONObject().put("source", "project://docs/notes/file.txt").put("destination", "project://docs/private.txt")
        verify(gate.error("move_path", destination, "t") != null, "move requires both endpoints")
        val toolArgs = JSONObject().put("path", "/storage/emulated/0/Download/new.txt")
        verify(gate.authorize("write_file", toolArgs, "root") { approvals++; dev.wind.agent.capability.CapabilityDecision.Allow(dev.wind.agent.capability.GrantScope.TASK) }, "user task approval")
        verify(gate.authorize("write_file", toolArgs, "root") { approvals++; dev.wind.agent.capability.CapabilityDecision.Deny }, "granted capability skips prompt")
        verify(approvals == 1, "no repeated capability approval")
        verify(gate.error("write_file", toolArgs, "other") != null, "subagent must use same root task")
        q.endTask("root")
        verify(gate.error("write_file", toolArgs, "root") != null, "gate denies after end")
        q.beginTask("root-next")
        verify(!gate.authorize("write_file", toolArgs, "root") { dev.wind.agent.capability.CapabilityDecision.Deny }, "rejection grants nothing")
        verify(!gate.authorize("write_file", toolArgs, "root") {
            q.revokeAll(PermissionScope.GLOBAL)
            dev.wind.agent.capability.CapabilityDecision.Allow(dev.wind.agent.capability.GrantScope.TASK)
        }, "revocation while waiting invalidates pending consent")
        verify(gate.authorize("write_file", toolArgs, "root-next") { dev.wind.agent.capability.CapabilityDecision.Allow(dev.wind.agent.capability.GrantScope.APP_SESSION) }, "explicit global approval")
        verify(AgentPermissionGate(AgentPermissions(saved = saved)) { project }.error("read_file", JSONObject().put("path", "/ungranted"), "root") != null, "no arbitrary inherited access")
        val fsRoot = java.io.File(args.firstOrNull() ?: ".build-phone-robustness/data", "permission-files-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val direct = dev.wind.agent.workspace.DirectWorkspaceFs(null, fsRoot)
        verify(direct.mkdir("notes"), "create directory")
        direct.writeText("notes/a.txt", "before")
        verify(direct.readText("notes/a.txt") == "before", "direct FS read/write")
        direct.writeText("notes/a.txt", "after")
        verify(direct.readText("notes/a.txt") == "after", "atomic overwrite")
        verify(direct.move("notes/a.txt", "notes/b.txt"), "move / rename")
        verify(!direct.exists("notes/a.txt") && direct.exists("notes/b.txt"), "move endpoints")
        verify(direct.glob("*.txt", 10, "notes").size == 1, "bounded glob")
        verify(direct.search("b", 10, "notes").size == 1, "bounded search")
        var escape = false
        try { direct.readText("../outside") } catch (_: Exception) { escape = true }
        verify(escape, "traversal rejected")
        escape = false
        try { direct.relative(fsRoot.parentFile.absolutePath) } catch (_: Exception) { escape = true }
        verify(escape, "absolute external path rejected")
        verify(direct.delete("notes/b.txt") && direct.delete("notes"), "delete file and empty directory")
        verify(direct.list("").isEmpty(), "temporary staged files cleaned")
        val authority = AgentPermissions()
        authority.set(AgentCapability.WORKSPACE_WRITE, PermissionScope.GLOBAL, true)
        val oldStore = dev.wind.agent.capability.CapabilityStore.inMemory()
        val broker = dev.wind.agent.capability.CapabilityBrokerImpl(oldStore, { project }, { MountState.AVAILABLE }, agentPermissions = authority)
        val task = dev.wind.agent.capability.TaskContext("broker-task", project.id, project.generation)
        val write = dev.wind.agent.capability.CapabilityRequirement.CanonicalWrite(project.id, setOf(primary.id))
        val lease = (broker.check(task, write) as dev.wind.agent.capability.CapabilityCheck.Allowed).lease
        verify(broker.verify(lease) == lease, "configured global write grants broker proof")
        authority.set(AgentCapability.WORKSPACE_WRITE, PermissionScope.GLOBAL, false)
        var revoked = false
        try { broker.verify(lease) } catch (_: dev.wind.agent.capability.CapabilityException) { revoked = true }
        verify(revoked, "revocation invalidates existing broker lease")
        verify(!authority.grantIfCurrent(lease.envelopeRevision, task.taskId, AgentCapability.ALL_WRITE, PermissionScope.GLOBAL, ""), "stale consent cannot grant under a different revision")
        val execution = dev.wind.agent.capability.CapabilityRequirement.Execution(project.id)
        val request = broker.check(task, execution) as dev.wind.agent.capability.CapabilityCheck.NeedsApproval
        val issued = requireNotNull(broker.grant(request.request.requestId, dev.wind.agent.capability.CapabilityDecision.Allow(dev.wind.agent.capability.GrantScope.TASK)))
        verify(broker.verify(issued) == issued, "new task leases use permission revision")
        val restarted = dev.wind.agent.capability.CapabilityBrokerImpl(oldStore, { project }, { MountState.AVAILABLE }, agentPermissions = authority)
        verify(restarted.check(task, execution) is dev.wind.agent.capability.CapabilityCheck.NeedsApproval, "old broker task grants do not survive process restart")
        broker.endTask(task.taskId)
        verify(broker.check(task, execution) is dev.wind.agent.capability.CapabilityCheck.NeedsApproval, "broker end task removes grants")
        println("AgentPermissionTests PASS ($checks assertions)")
    }
}
