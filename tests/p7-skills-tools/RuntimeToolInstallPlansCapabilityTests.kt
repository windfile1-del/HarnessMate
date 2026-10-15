package dev.wind.agent.runtime.linux

import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.CapabilityResolver
import dev.wind.agent.capability.TaskContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Host regression test for RuntimeToolInstallPlans and CapabilityResolver installation authorization.
 *
 * Verifies:
 * 1. prepare_runtime_tools resolves Network capability with trusted GitHub release domains.
 * 2. install_runtime_tools resolves Composite ArtifactInstall requirement matching pinned catalog.
 * 3. Scope validation: mismatched task/project/generation plan is denied.
 * 4. Missing plan_id fails closed with Denied requirement.
 * 5. Non-project (empty projectId) runtime tool install assertion:
 *    - ArtifactInstallKind.RUNTIME_TOOL permits empty projectId while remaining task-bound and exact-digest authorized.
 *    - ArtifactInstallKind.PROJECT_DEPENDENCY strictly denies empty projectId with INSTALL_PROJECT_REQUIRED.
 */
object RuntimeToolInstallPlansCapabilityTests {
    private var assertions = 0

    private fun check(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError("FAILED: $message")
        }
    }

    private const val SHA_CORE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val SHA_JVM = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    private const val SHA_LSP = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff"

    @JvmStatic fun main(args: Array<String>) {
        val tempDir = File.createTempFile("tool-plan-cap-test-", "-dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val plansDir = File(tempDir, "p7-runtime-tool-plans").apply { mkdirs() }
            val task = TaskContext("task-101", "proj-alpha", 1L)

            val planId = "11111111-2222-3333-4444-555555555555"
            val validPlanJson = JSONObject()
                .put("schema", 1)
                .put("plan_id", planId)
                .put("task_id", task.taskId)
                .put("project_id", task.projectId)
                .put("generation", task.projectGeneration)
                .put("items", JSONArray()
                    .put(JSONObject().put("family", "core").put("payload_sha256", SHA_CORE).put("artifact_sha256", SHA_CORE))
                    .put(JSONObject().put("family", "linux-jvm").put("payload_sha256", SHA_JVM).put("artifact_sha256", SHA_JVM))
                )
            File(plansDir, "$planId.json").writeText(validPlanJson.toString(), StandardCharsets.UTF_8)

            val mockPlanLookup: (TaskContext, String) -> CapabilityRequirement = { reqTask, id ->
                val file = File(plansDir, "$id.json")
                if (!file.isFile) throw IllegalArgumentException("RUNTIME_TOOL_PLAN_MISSING")
                val p = JSONObject(file.readText(StandardCharsets.UTF_8))
                if (p.getString("task_id") != reqTask.taskId || p.getString("project_id") != reqTask.projectId ||
                    p.getLong("generation") != reqTask.projectGeneration) {
                    throw IllegalStateException("RUNTIME_TOOL_PLAN_SCOPE_MISMATCH")
                }
                val items = p.getJSONArray("items")
                CapabilityRequirement.Composite(reqTask.projectId, (0 until items.length()).map { i ->
                    val it = items.getJSONObject(i)
                    CapabilityRequirement.ArtifactInstall(reqTask.projectId, CapabilityRequirement.ArtifactInstallKind.RUNTIME_TOOL,
                        it.getString("artifact_sha256"), "harness-tool:${it.getString("family")}")
                })
            }

            val resolver = CapabilityResolver(
                projectLookup = { null },
                dependencyPlanLookup = null,
                downloadPlanLookup = null,
                apkPlanLookup = null,
                searchDomains = { setOf("github.com") },
                runtimeToolPlanLookup = mockPlanLookup
            )

            testPrepareRuntimeToolsNetworkRequirement(resolver, task)
            testInstallRuntimeToolsAuthorizedPlan(resolver, task, planId)
            testInstallRuntimeToolsScopeMismatch(resolver, task, plansDir)
            testInstallRuntimeToolsMissingPlan(resolver, task)
            testNonProjectRuntimeToolInstallRequirement()

            println("RuntimeToolInstallPlansCapabilityTests: OK ($assertions assertions passed)")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun testPrepareRuntimeToolsNetworkRequirement(resolver: CapabilityResolver, task: TaskContext) {
        val args = JSONObject().put("tools", JSONArray().put("git"))
        val req = resolver.resolve(task, "prepare_runtime_tools", args)
        check(req is CapabilityRequirement.Network, "prepare_runtime_tools must resolve to Network requirement")
        val net = req as CapabilityRequirement.Network
        check(net.domains.contains("github.com"), "Network domains must include github.com")
        check(net.domains.contains("release-assets.githubusercontent.com"), "Network domains must include release assets")
    }

    private fun testInstallRuntimeToolsAuthorizedPlan(resolver: CapabilityResolver, task: TaskContext, planId: String) {
        val args = JSONObject().put("plan_id", planId)
        val req = resolver.resolve(task, "install_runtime_tools", args)
        check(req is CapabilityRequirement.Composite, "install_runtime_tools must resolve to Composite requirement")
        val composite = req as CapabilityRequirement.Composite
        check(composite.requirements.size == 2, "Expected 2 composite requirements for core + jvm")
        val first = composite.requirements[0] as CapabilityRequirement.ArtifactInstall
        check(first.kind == CapabilityRequirement.ArtifactInstallKind.RUNTIME_TOOL, "Kind must be RUNTIME_TOOL")
        check(first.targetId == "harness-tool:core", "First tool must be harness-tool:core")
        check(first.artifactSha256 == SHA_CORE, "Core sha256 match")

        val second = composite.requirements[1] as CapabilityRequirement.ArtifactInstall
        check(second.targetId == "harness-tool:linux-jvm", "Second tool must be harness-tool:linux-jvm")
        check(second.artifactSha256 == SHA_JVM, "JVM sha256 match")
    }

    private fun testInstallRuntimeToolsScopeMismatch(resolver: CapabilityResolver, task: TaskContext, plansDir: File) {
        val foreignId = "22222222-3333-4444-5555-666666666666"
        val foreignPlan = JSONObject()
            .put("schema", 1)
            .put("plan_id", foreignId)
            .put("task_id", "different-task-999")
            .put("project_id", task.projectId)
            .put("generation", task.projectGeneration)
            .put("items", JSONArray())
        File(plansDir, "$foreignId.json").writeText(foreignPlan.toString(), StandardCharsets.UTF_8)

        val args = JSONObject().put("plan_id", foreignId)
        val req = resolver.resolve(task, "install_runtime_tools", args)
        check(req is CapabilityRequirement.Denied, "Mismatched task scope must resolve to Denied")
        val denied = req as CapabilityRequirement.Denied
        check(denied.reason.contains("SCOPE_MISMATCH") || denied.reason.contains("INVALID"), "Denied reason must describe failure")
    }

    private fun testInstallRuntimeToolsMissingPlan(resolver: CapabilityResolver, task: TaskContext) {
        val args = JSONObject().put("plan_id", "00000000-0000-0000-0000-000000000000")
        val req = resolver.resolve(task, "install_runtime_tools", args)
        check(req is CapabilityRequirement.Denied, "Missing plan must resolve to Denied")
    }

    private fun testNonProjectRuntimeToolInstallRequirement() {
        // 1. Non-project (empty projectId) RUNTIME_TOOL requirement is valid
        val nonProjectToolReq = CapabilityRequirement.ArtifactInstall(
            projectId = "",
            kind = CapabilityRequirement.ArtifactInstallKind.RUNTIME_TOOL,
            artifactSha256 = SHA_LSP,
            targetId = "harness-tool:linux-lsp"
        )
        check(nonProjectToolReq.projectId == "", "RUNTIME_TOOL permits empty projectId")
        check(nonProjectToolReq.kind == CapabilityRequirement.ArtifactInstallKind.RUNTIME_TOOL, "Kind match")
        check(nonProjectToolReq.artifactSha256 == SHA_LSP, "Digest match")

        // 2. PROJECT_DEPENDENCY requires non-empty projectId
        var projectDepDenied = false
        try {
            CapabilityRequirement.ArtifactInstall(
                projectId = "",
                kind = CapabilityRequirement.ArtifactInstallKind.PROJECT_DEPENDENCY,
                artifactSha256 = SHA_JVM,
                targetId = "dep-1",
                targetMountId = "mount-1"
            )
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("INSTALL_PROJECT_REQUIRED") == true) {
                projectDepDenied = true
            }
        }
        check(projectDepDenied, "PROJECT_DEPENDENCY must reject empty projectId with INSTALL_PROJECT_REQUIRED")
    }
}
