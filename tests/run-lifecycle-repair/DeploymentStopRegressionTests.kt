package dev.wind.agent

import dev.wind.agent.coding.*
import dev.wind.agent.runtime.*
import dev.wind.agent.shell.mirror.MirrorLockRegistry
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exercise blocked reads and cancellation RPCs, not a cooperative mock of Binder. */
object DeploymentStopRegressionTests {
    @JvmStatic fun main(args: Array<String>) {
        activeFinishDoesNotReadMirror()
        cancelledPollReleasesProjectLock()
        gradleRespectsNetworkIntent()
        println("DEPLOYMENT_STOP_REGRESSION_PASS (3 scenarios)")
    }

    private fun activeFinishDoesNotReadMirror() {
        var forbid = false
        var reads = 0
        val host = object : CodingRuntimeHost {
            override fun mirrorDigest(): String? { reads++; check(!forbid); return "source" }
            override fun now() = System.currentTimeMillis()
            override fun runCommand(command: ValidationCommand, token: CancellationToken) = ValidationRunResult(0, "", "", 1)
        }
        val runtime = CodingTaskRuntime(host)
        runtime.start("task", "project", 1, restore = false)
        runtime.beforeTool("shell_exec")
        runtime.attachProfile(ProjectProfile(ProjectKind.GENERIC, "execution://main"), ValidationPlan(
            finalChecks = listOf(ValidationCommand("check", "check", listOf("echo", "ok"))),
            confidence = Confidence.HIGH, reason = "Explicit test check"))
        runtime.runFinalVerification(CancellationToken())
        val before = reads
        forbid = true
        check(runtime.requestFinish() is FinishDecision.Allow)
        check(runtime.finishRun().verified) { "Recorded successful check was lost during completion" }
        runtime.finishWith(TaskStatus.FAILED)
        check(reads == before)
    }

    private fun cancelledPollReleasesProjectLock() {
        val pollStarted = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val cancelledRpc = CountDownLatch(1)
        val token = CancellationToken()
        val result = AtomicReference<RuntimeCommandResult>()
        val failure = AtomicReference<Throwable>()
        val binding = RuntimeBinding(RuntimeProviderId.VEGA_COMPANION, "0123456789abcdef01234567", "project", 1, "runtime")
        fun deliberatelyUninterruptible() {
            while (blocked.count > 0) try { blocked.await() } catch (_: InterruptedException) {}
        }
        val client = CompanionRuntimeClient(inProcessDispatcher = { code, _ ->
            when (code) {
                CompanionRuntimeClient.TRANSACTION_START_COMMAND -> JSONObject().put("ok", true).put("executionId", "id").toString()
                CompanionRuntimeClient.TRANSACTION_POLL_COMMAND -> {
                    pollStarted.countDown(); deliberatelyUninterruptible()
                    JSONObject().put("ok", true).put("state", "RUNNING").toString()
                }
                CompanionRuntimeClient.TRANSACTION_CANCEL -> {
                    cancelledRpc.countDown(); deliberatelyUninterruptible()
                    JSONObject().put("ok", true).toString()
                }
                else -> error("Cancellation must not await another runtime RPC: $code")
            }
        })
        val worker = Thread {
            val registration = token.watchThread()
            try {
                MirrorLockRegistry.withProject("cancelled-project") {
                    result.set(CompanionCommandExecutor(binding, client).execute(binding, RuntimeCommandSpec("echo", listOf("ok")), token))
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { registration.close() }
        }
        worker.start()
        try {
            check(pollStarted.await(3, TimeUnit.SECONDS))
            token.cancel()
            worker.join(1500)
            check(!worker.isAlive) { "Stop still waits on blocked read/cancel RPC" }
            check(failure.get() == null) { "${failure.get()}" }
            check(result.get().cancelled && !result.get().cleanupConfirmed)
            check(cancelledRpc.await(3, TimeUnit.SECONDS))
            val next = AtomicBoolean()
            val following = Thread { MirrorLockRegistry.withProject("cancelled-project") { next.set(true) } }
            following.start(); following.join(1000)
            check(next.get()) { "Cancelled scan retained project lock" }
        } finally { blocked.countDown(); worker.join(2000) }
    }

    private fun gradleRespectsNetworkIntent() {
        val binding = RuntimeBinding(RuntimeProviderId.VEGA_COMPANION, "0123456789abcdef01234567", "project", 1, "runtime")
        var offline = false
        val client = CompanionRuntimeClient(inProcessDispatcher = { code, text ->
            if (code == CompanionRuntimeClient.TRANSACTION_START_COMMAND) {
                val argv = JSONObject(text).getJSONArray("argv")
                offline = (0 until argv.length()).any { argv.getString(it) == "--offline" }
                JSONObject().put("ok", true).put("executionId", "id").toString()
            } else JSONObject().put("ok", true).put("state", "TERMINATED").put("exitCode", 0)
                .put("cleanupConfirmed", true).toString()
        })
        val executor = CompanionCommandExecutor(binding, client)
        executor.execute(binding, RuntimeCommandSpec("gradle", listOf("classes"), network = false), CancellationToken())
        check(offline)
        executor.execute(binding, RuntimeCommandSpec("gradle", listOf("classes"), network = true, networkDomains = setOf("dl.google.com")), CancellationToken())
        check(!offline)
        executor.execute(binding, RuntimeCommandSpec("gradle", listOf("classes", "--offline"), network = true, networkDomains = setOf("dl.google.com")), CancellationToken())
        check(offline)
    }
}
