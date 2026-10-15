package dev.wind.agent.shell

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.history.MutationJournal

/** Project-scoped shell facade. No legacy single-root WorkspaceFs is consulted. */
class ShellManager(
    private val context: Context,
    private val projectMirrors: dev.wind.agent.shell.mirror.ProjectMirrorManager = dev.wind.agent.shell.mirror.ProjectMirrorManager(context)
) {

    fun detectCapabilities(): String = buildString {
        val probe = try { projectMirrors.runtimeStatus() } catch (failure: Exception) {
            append("Execution Environment: unavailable\n")
            append("diagnostic: ").append(failure.message ?: failure.javaClass.simpleName).append('\n')
            return@buildString
        }
        append("Execution Environment: ").append(probe.providerId.name).append('\n')
        append("backend: ").append(probe.providerId.name).append('\n')
        append("preflight: ").append(probe.state.name).append('\n')
        append("run_command: ").append(probe.ready && probe.capabilities.commandExecution).append('\n')
        append("diagnostic: ").append(probe.diagnostic.ifBlank { "none" }).append('\n')
        probe.capabilities.toolchains.toSortedMap().forEach { (tool, version) -> append("$tool: $version\n") }
        if (probe.providerId == dev.wind.agent.runtime.RuntimeProviderId.VEGA_LINUX) {
            append("Command timeout: 1..${dev.wind.agent.runtime.RuntimeProcessSupervisor.MAX_TIMEOUT_MS} ms; build/test deadlines are passed through.\n")
            if (probe.capabilities.toolchains.containsKey("kotlin"))
                append("Kotlin version above is the bundled Gradle JVM plugin; standalone kotlin/kotlinc commands are not added to PATH.\n")
        }
        append("workspace:// is canonical SAF; execution:// is the persistent mirror.\n")
        append("Only shell_apply_changes writes mirror changes to SAF through MutationJournal.\n")
        append("Mirror protocol: ").append(probe.capabilities.mirrorProtocolVersion)
            .append("; stream protocol: ").append(probe.capabilities.mirrorStreamProtocolVersion).append('\n')
        if (probe.providerId == dev.wind.agent.runtime.RuntimeProviderId.TERMUX)
            append("Termux mirror RPC requires python3; packages are never installed automatically.\n")
        else append("Mirror RPC: built-in runtime service; no external Termux required.\n")
        append("Network isolation: ").append(probe.capabilities.networkIsolation).append(" (not OS-level HARD isolation).\n")
        append("Interpreter -c/-e/--eval command strings are disallowed; write a script with execution_write_file and pass its relative path to shell_exec.\n")
    }

    fun executeCommand(
        taskId: String,
        request: ShellRequest,
        token: CancellationToken,
        beforeExecute: (() -> Unit)? = null,
        afterPrepare: (() -> Unit)? = null,
        preparedFiles: Map<String, String> = emptyMap()
    ): ShellResult {
        val projectManager = dev.wind.agent.project.ProjectManager(context)
        return projectMirrors.execute(
            taskId,
            request,
            projectManager.router,
            token,
            beforeExecute,
            afterPrepare,
            preparedFiles
        )
    }

    fun applyChanges(executionId: String, journal: MutationJournal): String =
        projectMirrors.apply(executionId, dev.wind.agent.project.ProjectManager(context).router, journal)

    fun previewApply(executionId: String): dev.wind.agent.shell.mirror.ApplyPlan =
        projectMirrors.previewApply(executionId, dev.wind.agent.project.ProjectManager(context).router)

    fun discardChanges(executionId: String, beforeSideEffect: (() -> Unit)? = null): String =
        projectMirrors.discard(executionId, dev.wind.agent.project.ProjectManager(context).router, beforeSideEffect)
}
