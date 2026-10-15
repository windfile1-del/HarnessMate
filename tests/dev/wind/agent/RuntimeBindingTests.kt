package dev.wind.agent

import dev.wind.agent.runtime.*
import dev.wind.agent.shell.mirror.MirrorState
import org.json.JSONObject

/** Direct registry/state tests for P6.1; no Android service or fake provider shortcut. */
object RuntimeBindingTests {
    @JvmStatic
    fun main(args: Array<String>) {
        boundProviderWinsOverPreference()
        unboundTermuxIsDefault()
        generationMismatchFailsClosed()
        migrationRecordPinsIdentity()
        println("RuntimeBindingTests: OK")
    }

    private fun binding(provider: RuntimeProviderId, generation: Long = 1L) =
        RuntimeBinding(provider, "0123456789abcdef01234567", "project", generation, "instance-" + provider.name)

    private fun fake(provider: RuntimeProviderId) = object : ExecutionRuntime {
        override val providerId = provider
        /**
         * A provider must declare the bounded mirror data plane to be handed a
         * project at all; P6.3 Closure-2 makes that a binding precondition, not
         * something discovered at the first sync transaction.
         */
        private val probe = RuntimeProbeResult(provider, RuntimeProbeState.READY,
            RuntimeCapabilities(true, true, true, 2, mirrorStreamProtocolVersion = 1),
            "instance-" + provider.name)
        override fun probe() = probe
        override fun bind(projectId: String, rootGeneration: Long, workspaceId: String) =
            RuntimeBinding(provider, workspaceId, projectId, rootGeneration, probe.runtimeInstanceId)
        override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
            override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?) =
                RuntimeCommandResult(0, "", "", 0L, cleanupConfirmed = true)
            override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
            override fun verifyBinding(binding: RuntimeBinding) = binding.providerId == provider
        }
        override fun capabilities(binding: RuntimeBinding) = probe.capabilities
        override fun verifyBinding(binding: RuntimeBinding) = binding.providerId == provider &&
            binding.runtimeInstanceId == probe.runtimeInstanceId
        override fun destroyCandidate(binding: RuntimeBinding) = true
    }

    private fun registry(preference: RuntimePreference): RuntimeRegistry {
        val termux = fake(RuntimeProviderId.TERMUX)
        val companion = fake(RuntimeProviderId.VEGA_COMPANION)
        return RuntimeRegistry(
            RuntimePreflight(linkedMapOf(
                RuntimeProviderId.TERMUX to termux,
                RuntimeProviderId.VEGA_COMPANION to companion
            ))
        ) { preference }
    }

    private fun state(binding: RuntimeBinding?): MirrorState =
        MirrorState.clean("project", binding?.rootGeneration ?: 1L, "0".repeat(64)).copy(runtimeBinding = binding)

    private fun boundProviderWinsOverPreference() {
        val b = binding(RuntimeProviderId.TERMUX)
        val resolved = registry(RuntimePreference.SELF_CONTAINED)
            .resolveBoundRuntime("project", 1L, b.workspaceId, state(b))
        check(resolved.binding.providerId == RuntimeProviderId.TERMUX)
    }

    private fun unboundTermuxIsDefault() {
        val resolved = registry(RuntimePreference.TERMUX)
            .resolveBoundRuntime("project", 1L, "0123456789abcdef01234567", state(null))
        check(resolved.binding.providerId == RuntimeProviderId.TERMUX)
    }

    private fun generationMismatchFailsClosed() {
        val b = binding(RuntimeProviderId.TERMUX, 7L)
        var rejected = false
        try {
            registry(RuntimePreference.TERMUX)
                .resolveBoundRuntime("project", 8L, b.workspaceId, state(b))
        } catch (e: IllegalArgumentException) {
            rejected = e.message == "RUNTIME_BINDING_IDENTITY_MISMATCH"
        }
        check(rejected)
    }

    private fun migrationRecordPinsIdentity() {
        val from = binding(RuntimeProviderId.TERMUX)
        val to = binding(RuntimeProviderId.VEGA_COMPANION)
        val digest = "a".repeat(64)
        val migration = RuntimeMigration(from, to, digest, digest, digest, 1L,
            RuntimeMigrationPhase.CANDIDATE_VERIFIED)
        val restored = RuntimeMigration.fromJson(JSONObject(migration.toJson().toString()))
        check(restored == migration)
    }
}
