package dev.wind.agent.coding

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.ShellManager
import dev.wind.agent.shell.ShellRequest
import dev.wind.agent.shell.ShellResult
import dev.wind.agent.shell.mirror.ProjectMirrorManager
import dev.wind.agent.project.ProjectManager

/**
 * The shipping [CodingRuntimeHost]: everything P4 needs from the device, routed
 * through the layers that already own it.
 *
 * Commands go through [ShellManager], which is the only path that prepares and
 * maintains the P3 mirror. The digest comes from [ProjectMirrorManager], which
 * computes it from the same ignore-aware manifest the commit gate uses — so a
 * fingerprint that says "unchanged" means the COMMITTABLE content is unchanged,
 * not that some build directory happened not to be touched.
 *
 * Nothing here spawns a process or talks to a terminal backend directly. If P4
 * ever needs to, that is a sign the abstraction is wrong.
 */
class ShellCodingHost(
    private val context: Context,
    private val shell: ShellManager = ShellManager(context),
    private val mirrors: ProjectMirrorManager = ProjectMirrorManager(context),
    private val taskId: String = "p4-validation",
    private val cancellation: CancellationToken? = null
) : CodingRuntimeHost {

    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }

    /** Test seam: a deterministic clock, used by the JVM suites. */
    fun withClock(source: () -> Long) {
        clock = source
    }

    override fun now(): Long = clock()

    override fun detectProject(projectPath: String?): ProjectProfile =
        mirrors.detectValidationProject(ProjectManager(context).router, projectPath)

    override fun documentationOnly(): Boolean =
        mirrors.hasOnlyDocumentationChanges(ProjectManager(context).router)
    override fun pendingExecutionId(): String? = mirrors.pendingExecutionId()
    override fun preparedBaselineRefreshSafe(): Boolean = mirrors.pendingExecutionId() == null
    override fun validationEnvironmentIdentity(): String? = try {
        cancellation?.throwIfCancelled()
        val status = mirrors.runtimeStatus()
        if (!status.ready) null else Digests.sha256(
            status.providerId.name + ":" + status.runtimeInstanceId + ":" +
                status.capabilities.runtimeVersion + ":" + status.capabilities.toolchains.toSortedMap().toString()
        )
    } catch (_: Exception) { null }

    /**
     * The digest of what is currently in the execution mirror.
     *
     * Read without a session in flight returns null: "no mirror" and "empty
     * mirror" must not look the same, or the first verification of a task would
     * compare against nothing and pass.
     */
    override fun mirrorDigest(): String? = try {
        cancellation?.throwIfCancelled()
        mirrors.currentMirrorDigest(ProjectManager(context).router, cancellation)
    } catch (_: Exception) {
        null
    }

    override fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult =
        runCommand(command, token, null)

    override fun runCommand(
        command: ValidationCommand,
        token: CancellationToken,
        beforeExecute: (() -> Unit)?
    ): ValidationRunResult {
        if (command.id == "documentation-diff" && command.argv == listOf("harness-documentation-diff")) {
            beforeExecute?.invoke()
            token.throwIfCancelled()
            val allowed = documentationOnly()
            return ValidationRunResult(if (allowed) 0 else 1, "Documentation diff policy (no build/test executed)",
                if (allowed) "" else "P4_DOCUMENTATION_SCOPE_CHANGED", 0L)
        }
        val request = ShellRequest(
            executable = command.argv.first(),
            args = command.argv.drop(1),
            cwd = command.cwd,
            timeoutMs = command.timeoutMs,
            network = command.network,
            networkIsolation = "POLICY_ONLY",
            validationCheck = true
        )
        val result: ShellResult = try {
            shell.executeCommand(taskId, request, token, beforeExecute = beforeExecute)
        } catch (e: Exception) {
            return ValidationRunResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                elapsedMs = 0L,
                error = e.javaClass.simpleName + ": " + (e.message ?: "command could not be started")
            )
        }
        return ValidationRunResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            elapsedMs = result.elapsedMs,
            timedOut = result.timedOut,
            cancelled = result.cancelled,
            error = result.error
        )
    }

}
