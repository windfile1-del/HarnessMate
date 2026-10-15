package dev.wind.agent.runtime

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.termux.TermuxContract
import dev.wind.agent.shell.termux.TermuxPreflight
import dev.wind.agent.shell.termux.TermuxRunCommandClient

/**
 * P6.0 adapter: preserves the existing Termux implementation while exposing the
 * provider-neutral contract. It is not selected by itself; RuntimeRegistry in
 * P6.1 will own durable selection.
 */
class TermuxExecutionRuntime(private val context: Context) : ExecutionRuntime {
    override val providerId: RuntimeProviderId = RuntimeProviderId.TERMUX

    override fun probe(): RuntimeProbeResult {
        val status = TermuxPreflight.probe(context)
        val caps = RuntimeCapabilities(
            commandExecution = status.executionReady,
            persistentWorkspace = status.bridgeWorking,
            processGroupCancellation = status.cancellationReady,
            mirrorProtocolVersion = 2,
            // The Termux mirror is the reference bounded-streaming plane: the
            // helper protocol has always carried file payloads as streams over
            // the bridge rather than as one control document.
            mirrorStreamProtocolVersion = 1,
            helperPythonVersion = status.toolDetails["python3"]?.version ?: "",
            toolchains = status.tools,
            languageServers = emptySet(),
            networkIsolation = "POLICY_ONLY",
            supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
            runtimeVersion = status.termuxVersion
        )
        val state = when {
            !status.installed -> RuntimeProbeState.NOT_INSTALLED
            status.executionReady -> RuntimeProbeState.READY
            status.callbackWorking || status.bridgeWorking -> RuntimeProbeState.DEGRADED
            else -> RuntimeProbeState.UNAVAILABLE
        }
        return RuntimeProbeResult(
            providerId = providerId,
            state = state,
            capabilities = caps,
            runtimeInstanceId = "termux:" + status.termuxVersion.ifBlank { "unknown" },
            diagnostic = status.diagnostic,
            checkedAt = status.checkedAt
        )
    }

    override fun bind(projectId: String, rootGeneration: Long, workspaceId: String): RuntimeBinding {
        val probe = probe()
        require(probe.ready) { "TERMUX_RUNTIME_UNAVAILABLE" }
        return RuntimeBinding(
            providerId = providerId,
            workspaceId = workspaceId,
            projectId = projectId,
            rootGeneration = rootGeneration,
            runtimeInstanceId = probe.runtimeInstanceId.ifBlank { "termux:unknown" },
            protocolVersion = 2,
            layoutVersion = 1
        )
    }

    override fun commandExecutor(binding: RuntimeBinding): RuntimeCommandExecutor {
        require(binding.providerId == providerId) { "RUNTIME_PROVIDER_MISMATCH" }
        return TermuxCommandExecutor(context)
    }

    override fun capabilities(binding: RuntimeBinding): RuntimeCapabilities {
        require(verifyBinding(binding)) { "RUNTIME_BINDING_INVALID" }
        return probe().capabilities
    }

    override fun verifyBinding(binding: RuntimeBinding): Boolean {
        if (binding.providerId != providerId) return false
        val current = probe()
        // Schema-2 migration has no historical Termux package version. A
        // legacy Termux binding is still provider-authoritative while the
        // current provider is ready; Companion instance IDs are strict.
        val instanceMatches = binding.runtimeInstanceId == current.runtimeInstanceId ||
            binding.runtimeInstanceId == "termux:legacy"
        return current.ready && instanceMatches
    }

    override fun destroyCandidate(binding: RuntimeBinding): Boolean {
        // Termux candidates are never authoritative and have no separate
        // instance lifecycle. The existing mirror manager owns cleanup.
        return binding.providerId == providerId
    }
}

private class TermuxCommandExecutor(private val context: Context) : RuntimeCommandExecutor {
    private val client = TermuxRunCommandClient(context)

    override fun execute(
        binding: RuntimeBinding,
        command: RuntimeCommandSpec,
        token: CancellationToken?
    ): RuntimeCommandResult {
        require(binding.providerId == RuntimeProviderId.TERMUX) { "RUNTIME_PROVIDER_MISMATCH" }
        val started = android.os.SystemClock.uptimeMillis()
        val request = TermuxRunCommandClient.Request(
            commandPath = command.executable,
            args = command.args,
            stdin = command.stdin,
            workDir = command.workDir.ifBlank { TermuxContract.TERMUX_HOME },
            timeoutMs = command.timeoutMs,
            cancellation = token
        )
        val out = client.run(request)
        val raw = out.result
        return RuntimeCommandResult(
            exitCode = raw?.exitCode,
            stdout = raw?.stdout ?: "",
            stderr = raw?.stderr ?: "",
            elapsedMs = out.elapsedMs.takeIf { it > 0L } ?: (android.os.SystemClock.uptimeMillis() - started),
            timedOut = out.timedOut,
            cancelled = out.cancelled,
            cleanupConfirmed = out.callbackReceived && raw != null,
            executionHandle = "",
            error = out.dispatchError
        )
    }

    override fun cancel(binding: RuntimeBinding, executionHandle: String): Boolean {
        // ACTION_RUN_COMMAND has no remote cancel primitive. P6.0 reports only
        // callback-confirmed completion; Self-contained provider supplies a real
        // process-tree barrier in P6.2.
        return false
    }

    override fun verifyBinding(binding: RuntimeBinding): Boolean =
        binding.providerId == RuntimeProviderId.TERMUX
}
