package dev.wind.agent.runtime

import dev.wind.agent.CancellationToken

/** Provider-neutral command description. Paths are virtual/relative, never host paths. */
data class RuntimeCommandSpec(
    val executable: String,
    val args: List<String> = emptyList(),
    val stdin: String? = null,
    val workDir: String = "",
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 60_000L,
    val network: Boolean = false,
    val networkDomains: Set<String> = emptySet(),
    /** Optional logical capability; executable remains for compatibility. */
    val toolId: RuntimeToolId? = null
) {
    init {
        require(executable.isNotBlank()) { "RUNTIME_EXECUTABLE_MISSING" }
        require(timeoutMs in 1L..RuntimeProcessSupervisor.MAX_TIMEOUT_MS) { "RUNTIME_TIMEOUT_INVALID" }
        require(env.keys.none { it.startsWith("VEGA_RUNTIME_") || it.startsWith("VEGA_INTERNAL_") }) {
            "RUNTIME_RESERVED_ENV"
        }
    }
}

data class RuntimeCommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val cleanupConfirmed: Boolean = false,
    val executionHandle: String = "",
    val error: String? = null,
    val outputTruncated: Boolean = false
) {
    val terminal: Boolean
        get() = !timedOut && !cancelled || cleanupConfirmed
}

interface RuntimeCommandExecutor {
    fun execute(
        binding: RuntimeBinding,
        command: RuntimeCommandSpec,
        token: CancellationToken? = null
    ): RuntimeCommandResult

    /**
     * A successful cancellation means the provider proved that its complete
     * process tree is gone. A lost callback is not proof of STOPPED.
     */
    fun cancel(binding: RuntimeBinding, executionHandle: String): Boolean

    fun verifyBinding(binding: RuntimeBinding): Boolean
}
