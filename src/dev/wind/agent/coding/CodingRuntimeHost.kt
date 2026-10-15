package dev.wind.agent.coding

import dev.wind.agent.CancellationToken

/** The raw outcome of one executed check, before it is interpreted. */
data class ValidationRunResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val elapsedMs: Long,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null
) {
    val failedToStart: Boolean get() = error != null && exitCode == null
}

/**
 * Everything the coding runtime needs from the platform.
 *
 * The runtime itself is pure logic over these calls, which is what lets the whole
 * loop — mutation tracking, validation, repair, completion gating — be tested on
 * a plain JVM with no device. The implementation that ships is a thin adapter
 * over ShellManager, so nothing here can reach a file the harness did not intend
 * to touch.
 */
interface CodingRuntimeHost {
    /** Current prepared mirror, not the canonical files awaiting apply. */
    fun detectProject(projectPath: String? = null): ProjectProfile? = null

    /** True only for a concrete, nonempty diff of plain documentation. */
    fun documentationOnly(): Boolean = false
    fun pendingExecutionId(): String? = null
    /** A prepare that preserved pending edits must not establish a clean baseline. */
    fun preparedBaselineRefreshSafe(): Boolean = true
    /** Toolchain and runtime identity used to invalidate verification after a bundle update. */
    fun validationEnvironmentIdentity(): String? = "host-test"
    /**
     * The digest of the execution mirror's current content, or null when no
     * mirror session exists. Content-based and ignore-aware on the P3 side, so a
     * rebuild that only rewrites build/ does not look like a source change.
     */
    fun mirrorDigest(): String?

    /** Runs one check in the execution mirror, through the P3 shell path. */
    fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult

    /**
     * Runs one check with an optional P2 capability re-check at the actual
     * command boundary. JVM test hosts can use the default implementation; the
     * shipping ShellCodingHost forwards it to ShellManager/ProjectMirrorManager.
     */
    fun runCommand(
        command: ValidationCommand,
        token: CancellationToken,
        beforeExecute: (() -> Unit)?
    ): ValidationRunResult = runCommand(command, token)

    /** Wall clock; injected so the state machine is testable. */
    fun now(): Long
}

/** Legacy result adapter. New runs always allow replies and report outcomes separately. */
sealed class FinishDecision {
    /** The task may end normally. */
    data object Allow : FinishDecision()

    /** The task may not end yet; [reason] is injected as an internal nudge. */
    data class Continue(val reason: String) : FinishDecision()

    /** The task cannot end without the user; [reason] is shown to them. */
    data class Blocked(val reason: String) : FinishDecision()
}
