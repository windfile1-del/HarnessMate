package dev.wind.agent.runtime

import android.content.Context

/**
 * Single probe facade for all providers. It only reports facts and never
 * installs packages, downloads toolchains, or silently falls back a bound
 * project to another provider.
 */
class RuntimePreflight(
    private val providers: Map<RuntimeProviderId, ExecutionRuntime>
) {
    /**
     * The embedded process is the default P6.4-A endpoint.  Passing
     * `embeddedRuntime = false` keeps the P6.3 external Companion fallback
     * available for diagnostics and migration tests.
     */
    constructor(context: Context, embeddedRuntime: Boolean = true) : this(
        linkedMapOf(
            RuntimeProviderId.VEGA_LINUX to LinuxExecutionRuntime(context.applicationContext),
            RuntimeProviderId.TERMUX to TermuxExecutionRuntime(context.applicationContext),
            RuntimeProviderId.VEGA_COMPANION to CompanionExecutionRuntime(
                context.applicationContext,
                embeddedRuntime
            )
        )
    )

    fun probe(providerId: RuntimeProviderId): RuntimeProbeResult {
        val provider = providers[providerId]
            ?: return RuntimeProbeResult(
                providerId = providerId,
                state = RuntimeProbeState.NOT_INSTALLED,
                capabilities = RuntimeCapabilities(false, false, false, 0),
                diagnostic = "RUNTIME_PROVIDER_NOT_INSTALLED"
            )
        return runCatching { provider.probe() }.getOrElse {
            RuntimeProbeResult(
                providerId = providerId,
                state = RuntimeProbeState.UNAVAILABLE,
                capabilities = RuntimeCapabilities(false, false, false, 0),
                diagnostic = "RUNTIME_PROBE_FAILED:" + (it.message ?: it.javaClass.simpleName)
            )
        }
    }

    fun probeAll(): Map<RuntimeProviderId, RuntimeProbeResult> =
        providers.keys.associateWith { probe(it) }

    fun provider(providerId: RuntimeProviderId): ExecutionRuntime? = providers[providerId]
}
