package dev.wind.agent.runtime

/**
 * Provider-neutral execution runtime. Providers own physical roots; callers only
 * use the durable binding and virtual workspace contract.
 */
interface ExecutionRuntime {
    val providerId: RuntimeProviderId

    fun probe(): RuntimeProbeResult

    fun bind(projectId: String, rootGeneration: Long, workspaceId: String): RuntimeBinding

    fun commandExecutor(binding: RuntimeBinding): RuntimeCommandExecutor

    fun capabilities(binding: RuntimeBinding): RuntimeCapabilities

    fun verifyBinding(binding: RuntimeBinding): Boolean

    /** Tear down an uncommitted candidate; never destroys the authoritative binding. */
    fun destroyCandidate(binding: RuntimeBinding): Boolean
}

data class BoundProjectRuntime(
    val runtime: ExecutionRuntime,
    val binding: RuntimeBinding,
    val capabilities: RuntimeCapabilities
)
