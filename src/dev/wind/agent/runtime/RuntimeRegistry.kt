package dev.wind.agent.runtime

import dev.wind.agent.shell.mirror.MirrorState

enum class RuntimePreference {
    TERMUX,
    SELF_CONTAINED,
    AUTO;

    companion object {
        fun parse(value: String?): RuntimePreference = when (value) {
            "self_contained" -> SELF_CONTAINED
            "auto" -> AUTO
            else -> TERMUX
        }
    }
}

/**
 * The only provider selection point. Every runtime-sensitive path must share
 * one instance for the lifetime of WorkspaceTools.
 */
class RuntimeRegistry(
    private val preflight: RuntimePreflight,
    private val preference: () -> RuntimePreference = { RuntimePreference.SELF_CONTAINED }
) {
    constructor(
        context: android.content.Context,
        preference: () -> RuntimePreference = { RuntimePreference.parse(dev.wind.agent.Prefs(context).runtimePreference()) }
    ) : this(RuntimePreflight(context.applicationContext), preference)

    fun resolveBoundRuntime(
        projectId: String,
        rootGeneration: Long,
        workspaceId: String,
        state: MirrorState?
    ): BoundProjectRuntime {
        val durable = state?.runtimeBinding
        if (durable != null) {
            require(durable.matches(projectId, rootGeneration, workspaceId)) {
                "RUNTIME_BINDING_IDENTITY_MISMATCH"
            }
            val provider = preflight.provider(durable.providerId)
                ?: throw IllegalStateException("RUNTIME_UNAVAILABLE:" + durable.providerId.name)
            require(provider.verifyBinding(durable)) { "RUNTIME_BINDING_INVALID" }
            return BoundProjectRuntime(provider, durable, requireMirrorDataPlane(provider, durable))
        }

        val providerId = selectUnboundProvider()
        val provider = preflight.provider(providerId)
            ?: throw IllegalStateException("RUNTIME_PROVIDER_UNAVAILABLE:" + providerId.name)
        val probe = preflight.probe(providerId)
        require(probe.ready) { "RUNTIME_PROVIDER_UNAVAILABLE:" + providerId.name }
        val binding = provider.bind(projectId, rootGeneration, workspaceId)
        require(binding.providerId == providerId && binding.matches(projectId, rootGeneration, workspaceId)
            && binding.runtimeInstanceId == probe.runtimeInstanceId) { "RUNTIME_BINDING_IDENTITY_MISMATCH" }
        return BoundProjectRuntime(provider, binding, requireMirrorDataPlane(provider, binding, probe.capabilities))
    }

    /**
     * Preference affects only a CLEAN/unbound project. A bound project must be
     * resolved through [resolveBoundRuntime], which never consults the flag.
     */
    fun selectUnboundProvider(): RuntimeProviderId {
        return when (preference()) {
            RuntimePreference.SELF_CONTAINED ->
                if (preflight.probe(RuntimeProviderId.VEGA_LINUX).ready) RuntimeProviderId.VEGA_LINUX
                else throw IllegalStateException("RUNTIME_PROVIDER_UNAVAILABLE:VEGA_LINUX")
            RuntimePreference.AUTO ->
                when {
                    preflight.probe(RuntimeProviderId.VEGA_LINUX).ready -> RuntimeProviderId.VEGA_LINUX
                    preflight.probe(RuntimeProviderId.VEGA_COMPANION).ready -> RuntimeProviderId.VEGA_COMPANION
                    preflight.probe(RuntimeProviderId.TERMUX).ready -> RuntimeProviderId.TERMUX
                    else -> throw IllegalStateException("RUNTIME_PROVIDER_UNAVAILABLE")
                }
            RuntimePreference.TERMUX -> RuntimeProviderId.TERMUX
        }
    }

    /** Read-only capability report uses the same durable authority as execution. */
    fun probeForProject(projectId: String, rootGeneration: Long, workspaceId: String, state: MirrorState?): RuntimeProbeResult {
        val durable = state?.runtimeBinding
        if (durable != null) {
            require(durable.matches(projectId, rootGeneration, workspaceId)) { "RUNTIME_BINDING_IDENTITY_MISMATCH" }
            val probe = preflight.probe(durable.providerId)
            if (!probe.ready) return probe
            val provider = preflight.provider(durable.providerId)
            val legacyTermux = durable.providerId == RuntimeProviderId.TERMUX && durable.runtimeInstanceId == "termux:legacy"
            if ((!legacyTermux && probe.runtimeInstanceId != durable.runtimeInstanceId) || provider?.verifyBinding(durable) != true) {
                return probe.copy(state = RuntimeProbeState.UNAVAILABLE, diagnostic = "RUNTIME_BINDING_INVALID")
            }
            return probe
        }
        val selected = when (preference()) {
            RuntimePreference.SELF_CONTAINED -> RuntimeProviderId.VEGA_LINUX
            RuntimePreference.TERMUX -> RuntimeProviderId.TERMUX
            RuntimePreference.AUTO -> {
                var first: RuntimeProbeResult? = null
                for (provider in listOf(RuntimeProviderId.VEGA_LINUX, RuntimeProviderId.VEGA_COMPANION, RuntimeProviderId.TERMUX)) {
                    val probe = preflight.probe(provider)
                    if (first == null) first = probe
                    if (probe.ready) return probe
                }
                return requireNotNull(first)
            }
        }
        return preflight.probe(selected)
    }

    fun requireBinding(
        binding: RuntimeBinding,
        projectId: String,
        rootGeneration: Long,
        workspaceId: String
    ): BoundProjectRuntime {
        require(binding.matches(projectId, rootGeneration, workspaceId)) {
            "RUNTIME_BINDING_IDENTITY_MISMATCH"
        }
        val provider = preflight.provider(binding.providerId)
            ?: throw IllegalStateException("RUNTIME_UNAVAILABLE:" + binding.providerId.name)
        require(provider.verifyBinding(binding)) { "RUNTIME_BINDING_INVALID" }
        return BoundProjectRuntime(provider, binding, requireMirrorDataPlane(provider, binding))
    }

    /**
     * A provider that cannot carry the bounded P3 mirror data plane must not be
     * handed a project, and must never be resolved into a state where a later
     * transaction discovers the gap halfway through a sync. The check is a
     * capability fact, not a preference: a bound project stays failed closed
     * rather than quietly moving to another provider.
     */
    private fun requireMirrorDataPlane(
        provider: ExecutionRuntime,
        binding: RuntimeBinding,
        known: RuntimeCapabilities? = null
    ): RuntimeCapabilities {
        val capabilities = known ?: provider.capabilities(binding)
        require(capabilities.mirrorStreamProtocolVersion >= MIN_MIRROR_STREAM_PROTOCOL_VERSION) {
            "RUNTIME_MIRROR_DATA_PLANE_UNAVAILABLE:" + binding.providerId.name
        }
        return capabilities
    }

    companion object {
        /** Mirror data-plane revision a provider must implement to execute P3. */
        const val MIN_MIRROR_STREAM_PROTOCOL_VERSION = 1
    }
}
