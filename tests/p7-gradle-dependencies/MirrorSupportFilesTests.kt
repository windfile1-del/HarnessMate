package dev.wind.agent

import android.content.Context
import dev.wind.agent.project.*
import dev.wind.agent.runtime.*
import dev.wind.agent.shell.ShellRequest
import dev.wind.agent.shell.mirror.*
import dev.wind.agent.workspace.*
import org.json.JSONObject
import java.io.File

/** Real RuntimeWorkspace and mirror WAL; command executor is a controlled boundary fixture. */
object MirrorSupportFilesTests {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).canonicalFile.apply { mkdirs() }
        var checks = 0
        fun verify(ok: Boolean, reason: String) { checks++; check(ok) { reason } }
        val contextClass = Class.forName("dev.wind.agent.CompanionMirrorIntegrationTests\$TestContext")
        val context = contextClass.getDeclaredConstructor(File::class.java).apply { isAccessible = true }
            .newInstance(File(root, "host").apply { mkdirs() }) as Context
        val factory = CompanionMirrorIntegrationTests.javaClass.getDeclaredMethod("createInProcessClient", File::class.java, RuntimeProviderId::class.java)
            .apply { isAccessible = true }
        val client = factory.invoke(CompanionMirrorIntegrationTests, File(root, "provider"), RuntimeProviderId.VEGA_COMPANION) as CompanionRuntimeClient
        val fs = FakeWorkspaceFs("support-canonical").apply { writeText("source.txt", "canonical") }
        val mount = WorkspaceMount("support-mount", "main", "content://fixture/main", "Main", MountMode.READ_WRITE, "main", true)
        val store = ProjectStore.get(context)
        store.saveProject(VegaProject("support-project", "Support", mount.id, listOf(mount), 1L))
        store.setActiveProject("support-project")
        val router = object : WorkspaceRouter(context, store) {
            override fun checkMountState(mount: WorkspaceMount) = MountState.AVAILABLE
            override fun getFsForMount(mount: WorkspaceMount): WorkspaceFs = fs
        }
        var dispatched = 0
        val runtime = object : ExecutionRuntime by CompanionExecutionRuntime(client) {
            override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
                override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?): RuntimeCommandResult {
                    dispatched++
                    return RuntimeCommandResult(1, "", "fixture assertion failure", 1L, cleanupConfirmed = true)
                }
                override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
                override fun verifyBinding(binding: RuntimeBinding) = true
            }
        }
        val registry = RuntimeRegistry(RuntimePreflight(mapOf(RuntimeProviderId.VEGA_COMPANION to runtime))) { RuntimePreference.AUTO }
        val manager = ProjectMirrorManager(context, store, registry, mirrorClientFactory = { _, b -> CompanionMirrorClient(b, client) })
        val token = CancellationToken()
        verify(manager.tool("execution_prepare", JSONObject(), router, "task", token).startsWith("OK:"), "prepare")
        manager.tool("execution_write_file", JSONObject().put("path", "execution://main/source.txt").put("content", "pending user edit"), router, "task", token)
        val path = ".harnessmate-package-cache/gradle/init/fixture.gradle"
        val script = "// app-authored fixture\n"
        var leaseChecks = 0
        val result = manager.execute("task", ShellRequest("java", listOf("-version"), "execution://main", validationCheck = true),
            router, token, beforeExecute = { leaseChecks++ }, preparedFiles = mapOf(path to script))
        verify(result.exitCode == 1 && dispatched == 1, "nonzero validation fixture dispatched once")
        verify(leaseChecks >= 3, "lease rechecked around support staging and dispatch")
        verify(manager.readMirrorText("main/$path") == script, "support staged relative to command cwd")
        verify(manager.readMirrorText("main/source.txt") == "pending user edit", "prior source edit retained")
        val id = result.diffSummary!!.substringAfter("Execution ID: ").substringBefore('\n')
        val plan = manager.previewApply(id, router)
        verify(plan.changedFiles == 1 && plan.modifiedFiles == 1, "cache helpers excluded and eligibility preserved on unchanged failing check")
        verify(fs.readText("source.txt") == "canonical", "no implicit canonical write")
        val invalid = runCatching { manager.execute("task", ShellRequest("java", listOf("-version"), "execution://main"),
            router, token, preparedFiles = mapOf("source.txt" to "bad")) }.exceptionOrNull()
        verify(invalid?.message == "EXECUTION_SUPPORT_PATH_INVALID" && dispatched == 1, "invalid helper refused before command")
        verify(manager.previewApply(id, router).changedFiles == 1, "invalid staging leaves pending operation intact")
        println("MirrorSupportFilesTests PASS ($checks checks)")
    }
}
