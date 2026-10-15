package dev.wind.agent

import dev.wind.agent.runtime.RuntimeProcessSupervisor
import dev.wind.agent.runtime.RuntimeWorkspace
import java.io.File
import java.nio.file.Files

/** Directly exercises the P6.2 production process supervisor on the JVM. */
object RuntimeCompanionTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory("vega-runtime-test").toFile()
        try {
            workspaceBindingAndScrub(root)
            completesCommand(root)
            timesOutAndProvesCleanup(root)
            cancellationProvesCleanup(root)
            reservedEnvironmentIsRejected(root)
            asyncCommandLifecycleAndBindingScope(root)
            detachedBackgroundProcessDoesNotReportCleanSuccess(root)
            println("RuntimeCompanionTests: OK")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun workspaceBindingAndScrub(root: File) {
        val workspace = RuntimeWorkspace(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = workspace.open("project-a", 4L, workspaceId)
        check(binding.runtimeInstanceId == workspace.runtimeInstanceId())
        check(workspace.requireBound("project-a", 4L, workspaceId,
            binding.runtimeInstanceId).sameIdentity(binding))
        check(File(workspace.workspaceDirectory(workspaceId), "project").isDirectory)
        check(RuntimeWorkspace(root).runtimeInstanceId() == binding.runtimeInstanceId)

        var generationRejected = false
        try {
            workspace.open("project-a", 5L, workspaceId)
        } catch (error: IllegalStateException) {
            generationRejected = error.message == "RUNTIME_BINDING_GENERATION_CONFLICT"
        }
        check(generationRejected)

        val physical = workspace.scrub("/root/private /bin/tool C:\\Users\\wind\\secret")
        check(!physical.contains("/root/private") && !physical.contains("/bin/tool")
            && !physical.contains("C:\\Users\\wind\\secret"))
        val fileUri = workspace.scrub("file:///data/user/0/dev.wind.agent.runtime/files/test.txt")
        check(!fileUri.contains("/data/user/0") && !fileUri.contains("dev.wind.agent.runtime"))
            { "file URI leaked: $fileUri" }
        val internal = workspace.scrub(
            workspace.workspaceDirectory(workspaceId).absolutePath + "\\project")
        check(!internal.contains(workspace.workspaceDirectory(workspaceId).absolutePath))
            { "physical runtime path leaked: $internal" }
    }

    private fun shell(): List<String> {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        return if (windows) listOf("cmd.exe", "/c") else listOf("/bin/sh", "-c")
    }

    private fun longCommand(): List<String> {
        return if (shell().first() == "cmd.exe") {
            listOf("powershell.exe", "-NoProfile", "-NonInteractive",
                "-Command", "Start-Sleep -Seconds 8")
        } else {
            shell() + listOf("sleep 8")
        }
    }

    private fun completesCommand(root: File) {
        val supervisor = RuntimeProcessSupervisor()
        try {
            val command = shell() + listOf(
                if (shell().first() == "cmd.exe") "echo hello" else "printf 'hello'"
            )
            val result = supervisor.await(
                supervisor.start(command, root, emptyMap(), 2000L), 4000L)
            check(result.exitCode == 0 && result.stdout.trim() == "hello")
            check(result.cleanupConfirmed && result.success())
        } finally {
            supervisor.close()
        }
    }

    private fun timesOutAndProvesCleanup(root: File) {
        val supervisor = RuntimeProcessSupervisor()
        try {
            val result = supervisor.await(
                supervisor.start(longCommand(), root, emptyMap(), 100L), 2000L)
            // The whole state is reported, not just the boolean that failed: a
            // bare "Check failed" here has cost a debugging round trip more than
            // once, and the four fields are what distinguish "the deadline did
            // not fire" from "the tree was not proven dead".
            check(result.timedOut) {
                "deadline did not fire: exit=${result.exitCode} cancelled=${result.cancelled} " +
                    "cleanup=${result.cleanupConfirmed} duration=${result.durationMs} " +
                    "failure=[${result.failure}] stdout=[${result.stdout}] stderr=[${result.stderr}]"
            }
            check(result.cleanupConfirmed) {
                "cleanup not proven: exit=${result.exitCode} timedOut=${result.timedOut} " +
                    "failure=[${result.failure}] stderr=[${result.stderr}]"
            }
            check(!result.success()) { "a timed-out execution reported success" }
        } finally {
            supervisor.close()
        }
    }

    private fun cancellationProvesCleanup(root: File) {
        val supervisor = RuntimeProcessSupervisor()
        try {
            val execution = supervisor.start(longCommand(), root, emptyMap(), 30000L)
            Thread.sleep(100L)
            val result = supervisor.cancelAndAwait(execution.id, 2000L)
            check(result != null && result.cancelled && result.cleanupConfirmed)
        } finally {
            supervisor.close()
        }
    }

    private fun reservedEnvironmentIsRejected(root: File) {
        val supervisor = RuntimeProcessSupervisor()
        try {
            var rejected = false
            try {
                supervisor.start(shell() + listOf("true"), root,
                    mapOf("VEGA_RUNTIME_FORGED" to "1"), 1000L)
            } catch (error: IllegalArgumentException) {
                rejected = error.message == "RUNTIME_ENV_RESERVED"
            }
            check(rejected)
        } finally {
            supervisor.close()
        }
    }

    private fun asyncCommandLifecycleAndBindingScope(root: File) {
        val supervisor = RuntimeProcessSupervisor()
        try {
            val scopeA = RuntimeProcessSupervisor.BindingScope(
                "proj-a", 1L, "0123456789abcdef01234567", "inst-1")
            val scopeB = RuntimeProcessSupervisor.BindingScope(
                "proj-b", 1L, "fedcba9876543210fedcba98", "inst-1")

            val execution = supervisor.start(scopeA, longCommand(), root, emptyMap(), 30000L)
            check(!execution.id.isEmpty())
            Thread.sleep(100L)

            var mismatchRejected = false
            try {
                supervisor.getExecution(execution.id, scopeB)
            } catch (error: IllegalArgumentException) {
                mismatchRejected = error.message == "RUNTIME_EXECUTION_BINDING_MISMATCH"
            }
            check(mismatchRejected) { "scope B accessed scope A execution" }

            var nullScopeRejected = false
            try {
                supervisor.cancelAndAwait(execution.id, null, 1000L)
            } catch (error: IllegalArgumentException) {
                nullScopeRejected = error.message == "RUNTIME_EXECUTION_BINDING_MISMATCH"
            }
            check(nullScopeRejected) { "null scope was accepted for scoped execution" }

            var cancelMismatchRejected = false
            try {
                supervisor.cancelAndAwait(execution.id, scopeB, 1000L)
            } catch (error: IllegalArgumentException) {
                cancelMismatchRejected = error.message == "RUNTIME_EXECUTION_BINDING_MISMATCH"
            }
            check(cancelMismatchRejected) { "scope B cancelled scope A execution" }

            val cancelResult = supervisor.cancelAndAwait(execution.id, scopeA, 2500L)
            check(cancelResult != null && cancelResult.cancelled && cancelResult.cleanupConfirmed)
            check(supervisor.getResult(execution.id, scopeA) != null)
        } finally {
            supervisor.close()
        }
    }

    private fun detachedBackgroundProcessDoesNotReportCleanSuccess(root: File) {
        val supervisor = RuntimeProcessSupervisor()
        try {
            val windows = System.getProperty("os.name").lowercase().contains("win")
            val command = if (windows) {
                listOf("cmd.exe", "/c",
                    "start /min powershell.exe -NoProfile -Command Start-Sleep 5 & ping -n 2 127.0.0.1 > nul")
            } else {
                listOf("/bin/sh", "-c", "sleep 5 & sleep 0.2; exit 0")
            }
            val execution = supervisor.start(command, root, emptyMap(), 3000L)
            val result = supervisor.await(execution, 4000L)
            if (RuntimeProcessSupervisor.supportsProcessGroupCancellation()) {
                check(!result.cleanupConfirmed) { "detached background child was falsely confirmed clean" }
                check(!result.success()) { "surviving background child was reported as clean success" }
                val cleanupResult = supervisor.cancelAndAwait(execution.id, 2500L)
                check(cleanupResult != null && cleanupResult.cleanupConfirmed)
                    { "explicit cleanup failed to terminate background child" }
            }
        } finally {
            supervisor.close()
        }
    }
}
