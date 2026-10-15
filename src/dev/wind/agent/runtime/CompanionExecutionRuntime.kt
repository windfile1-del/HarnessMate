package dev.wind.agent.runtime

import android.content.Context
import dev.wind.agent.CancellationToken

open class CompanionExecutionRuntime(
    private val client: CompanionRuntimeClient
) : ExecutionRuntime {
    constructor(context: Context) : this(CompanionRuntimeClient(context.applicationContext))

    /** P6.4-A endpoint selection; provider identity stays VEGA_COMPANION. */
    constructor(context: Context, embedded: Boolean) : this(
        if (embedded) EmbeddedRuntimeClient(context.applicationContext)
        else CompanionRuntimeClient(context.applicationContext)
    )

    override val providerId: RuntimeProviderId = client.expectedProviderId

    override fun probe(): RuntimeProbeResult = client.probe()

    override fun bind(projectId: String, rootGeneration: Long, workspaceId: String): RuntimeBinding {
        return client.openWorkspace(projectId, rootGeneration, workspaceId)
    }

    override fun commandExecutor(binding: RuntimeBinding): RuntimeCommandExecutor {
        require(binding.providerId == providerId) { "RUNTIME_PROVIDER_MISMATCH" }
        return CompanionCommandExecutor(binding, client)
    }

    override fun capabilities(binding: RuntimeBinding): RuntimeCapabilities {
        return probe().capabilities
    }

    override fun verifyBinding(binding: RuntimeBinding): Boolean {
        if (binding.providerId != providerId) return false
        val probe = probe()
        if (!probe.ready || probe.runtimeInstanceId != binding.runtimeInstanceId) return false
        return runCatching { client.reconcile(binding) }.getOrElse { false }
    }

    override fun destroyCandidate(binding: RuntimeBinding): Boolean {
        return true
    }
}

/** Explicit name for callers migrating from the external Companion APK. */
class EmbeddedExecutionRuntime(context: Context) : CompanionExecutionRuntime(
    EmbeddedRuntimeClient(context.applicationContext)
)

class CompanionCommandExecutor(
    private val binding: RuntimeBinding,
    private val client: CompanionRuntimeClient
) : RuntimeCommandExecutor {
    override fun execute(
        binding: RuntimeBinding,
        command: RuntimeCommandSpec,
        token: CancellationToken?
    ): RuntimeCommandResult {
        require(binding == this.binding && binding.providerId == client.expectedProviderId) { "RUNTIME_PROVIDER_MISMATCH" }
        token?.throwIfCancelled()
        val rawArgv = listOf(command.executable) + command.args
        val argv = if (!command.network && dev.wind.agent.runtime.linux.LinuxGradleCommand.handles(rawArgv) && "--offline" !in command.args)
            rawArgv + "--offline" else rawArgv
        val executionId = client.startCommand(
            binding, argv, command.env, command.timeoutMs, command.workDir, command.stdin)
        // Cancellation must reach the runtime even while this worker is stuck in poll/Binder.
        val cancellation = token?.onCancel { client.cancel(binding, executionId, 2500L) }
        try {
        val started = System.nanoTime()
        fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000L
        fun cancelledResult() = RuntimeCommandResult(null, "", "", elapsedMs(), cancelled = true,
            cleanupConfirmed = false, executionHandle = executionId, error = "RUNTIME_CANCEL_CLEANUP_PENDING")
        while (elapsedMs() < command.timeoutMs) {
            if (token != null && token.isCancelled) {
                return cancelledResult()
            }
            val poll = try { client.pollCommand(binding, executionId) } catch (failure: Exception) {
                if (token?.isCancelled == true) return cancelledResult()
                // A dead service loses its handle table. Never hide that as a
                // successful cleanup or silently relaunch the command.
                return RuntimeCommandResult(null, "", "", elapsedMs(),
                    cleanupConfirmed = false, executionHandle = executionId,
                    error = "RUNTIME_EXECUTION_AUTHORITY_LOST")
            }
            if (poll != null && poll.optString("state") == "TERMINATED") {
                val exitCode = if (poll.has("exitCode") && poll.getInt("exitCode") != -1) poll.getInt("exitCode") else null
                return RuntimeCommandResult(
                    exitCode = exitCode,
                    stdout = poll.optString("stdout", ""),
                    stderr = poll.optString("stderr", ""),
                    elapsedMs = poll.optLong("durationMs", elapsedMs()),
                    timedOut = poll.optBoolean("timedOut"),
                    cancelled = poll.optBoolean("cancelled"),
                    cleanupConfirmed = poll.optBoolean("cleanupConfirmed"),
                    executionHandle = executionId,
                    error = poll.optString("failure").takeIf { it.isNotBlank() },
                    outputTruncated = poll.optBoolean("outputTruncated")
                )
            }
            try {
                Thread.sleep(100L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                token?.cancel()
                return cancelledResult()
            }
        }
        client.cancel(binding, executionId, 2500L)
        val terminal = client.awaitCommand(binding, executionId, 1000L)
        return terminal.copy(timedOut = true)
        } finally { cancellation?.close() }
    }

    override fun cancel(binding: RuntimeBinding, executionHandle: String): Boolean {
        require(binding == this.binding && binding.providerId == client.expectedProviderId) { "RUNTIME_PROVIDER_MISMATCH" }
        return client.cancel(binding, executionHandle, 2500L)
    }

    override fun verifyBinding(binding: RuntimeBinding): Boolean {
        if (binding != this.binding || binding.providerId != client.expectedProviderId) return false
        return client.reconcile(binding)
    }
}
