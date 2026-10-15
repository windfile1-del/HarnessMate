package dev.wind.agent.runtime.linux

import dev.wind.agent.capability.*
import dev.wind.agent.ToolEffect
import dev.wind.agent.ToolRegistry
import org.json.JSONObject

object RuntimeToolAuthorityTests {
    @JvmStatic fun main(args: Array<String>) {
        var checks = 0
        fun verify(value: Boolean) { check(value); checks++ }
        val task = TaskContext("tool-task", "", 0)
        val pinned = CapabilityRequirement.ArtifactInstall("", CapabilityRequirement.ArtifactInstallKind.RUNTIME_TOOL,
            "a".repeat(64), "harness-tool:linux-jvm")
        val resolver = CapabilityResolver(projectLookup = { null }, dependencyPlanLookup = null,
            runtimeToolPlanLookup = { current, id ->
                require(current == task && id == "issued-plan") { "PLAN_SCOPE_MISMATCH" }; pinned
            })
        verify(resolver.resolve(task, "install_runtime_tools", JSONObject().put("plan_id", "issued-plan")
            .put("artifact_sha256", "b".repeat(64)).put("target", "arbitrary")) == pinned)
        verify(resolver.resolve(task, "install_runtime_tools", JSONObject().put("plan_id", "forged")) is CapabilityRequirement.Denied)
        verify(resolver.resolve(TaskContext("other-task", "", 0), "install_runtime_tools", JSONObject().put("plan_id", "issued-plan")) is CapabilityRequirement.Denied)
        verify(CapabilityResolver { null }.resolve(task, "install_runtime_tools", JSONObject().put("plan_id", "issued-plan")) is CapabilityRequirement.Denied)
        verify(resolver.resolve(task, "ensure_runtime_tools", JSONObject().put("tools", org.json.JSONArray(listOf("gradle")))) ==
            CapabilityRequirement.Network("", RuntimeToolCatalog.networkDomains))
        verify(resolver.resolve(task, "ensure_runtime_tools", JSONObject().put("tools", org.json.JSONArray(listOf("unknown-binary")))) is CapabilityRequirement.Denied)
        verify(resolver.resolve(task, "read_skill", JSONObject().put("path", "skills://builtin/HarnessMate.md")) is CapabilityRequirement.Internal)
        verify(ToolRegistry.spec("install_runtime_tools")!!.effect == ToolEffect.INTERNAL)
        verify(ToolRegistry.spec("read_skill")!!.effect == ToolEffect.INTERNAL)
        var projectRequired = false
        try { CapabilityRequirement.ArtifactInstall("", CapabilityRequirement.ArtifactInstallKind.PROJECT_DEPENDENCY,
            "a".repeat(64), "dependency", "main") } catch (_: IllegalArgumentException) { projectRequired = true }
        verify(projectRequired)
        val broker = CapabilityBrokerImpl(CapabilityStore.inMemory(), { null }, { dev.wind.agent.project.MountState.AVAILABLE })
        val pending = broker.check(task, pinned)
        verify(pending is CapabilityCheck.NeedsApproval)
        val lease = broker.grant((pending as CapabilityCheck.NeedsApproval).request.requestId, CapabilityDecision.Allow(GrantScope.TASK))!!
        verify(broker.verify(lease, pinned) == lease)
        var differentDigestDenied = false
        try { broker.verify(lease, pinned.copy(artifactSha256 = "b".repeat(64))) } catch (_: CapabilityException) { differentDigestDenied = true }
        verify(differentDigestDenied)
        broker.revokeTask(task.taskId, CapabilityRevocation.ALL)
        var revokedDenied = false
        try { broker.verify(lease, pinned) } catch (_: CapabilityException) { revokedDenied = true }
        verify(revokedDenied)
        println("RuntimeToolAuthorityTests: OK ($checks assertions passed)")
    }
}
