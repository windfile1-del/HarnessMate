package dev.wind.agent.shell

import dev.wind.agent.CancellationToken

data class ShellRequest(
    val executable: String,
    val args: List<String> = emptyList(),
    val cwd: String = "workspace://",
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 60_000L,
    val network: Boolean = false,
    /** Requested domains are enforcement metadata until a sandboxed backend exists. */
    val networkDomains: Set<String> = emptySet(),
    val networkIsolation: String = "POLICY_ONLY",
    val executionId: String = "",
    /** Internal harness checks may fail without changing the staged source. */
    val validationCheck: Boolean = false
)

data class ShellResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
    val diffSummary: String = ""
)

data class ExecutionTarget(
    val workspaceId: String,
    val projectPath: String,
    val cwdRelative: String = ""
)

interface ShellBackend {
    val id: String
    val displayName: String
    fun isAvailable(): Boolean
    fun execute(request: ShellRequest, target: ExecutionTarget, token: CancellationToken): ShellResult
    fun detectCapabilities(): Map<String, String>
}

data class CancelResult(
    val success: Boolean,
    val message: String
)

interface CancellableShellBackend : ShellBackend {
    fun cancel(executionId: String): CancelResult
}
