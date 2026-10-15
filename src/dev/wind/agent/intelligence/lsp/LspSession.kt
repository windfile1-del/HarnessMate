package dev.wind.agent.intelligence.lsp

import dev.wind.agent.CancellationToken
import dev.wind.agent.intelligence.CodeLanguage
import org.json.JSONObject

enum class LspState {
    STOPPED,
    STARTING,
    READY,
    STALE,
    BACKOFF,
    DEGRADED
}

data class LspSessionKey(
    val rootTaskId: String,
    val projectId: String,
    val rootGeneration: Long,
    val language: CodeLanguage,
    val executionPath: String = "main"
)

interface LspTransport {
    fun start(
        language: CodeLanguage,
        rootUri: String,
        token: CancellationToken,
        beforeDispatch: ExecutionBoundaryVerifier? = null
    ): LspOutcome<Unit>
    fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long,
        token: CancellationToken,
        beforeDispatch: ExecutionBoundaryVerifier? = null
    ): LspOutcome<JSONObject>
    /**
     * Send a notification only with the same live lease used by productive
     * requests. The default null keeps disabled/test transports source
     * compatible, but production transports must fail closed when it is
     * absent rather than substituting cleanup ownership.
     */
    fun notify(
        method: String,
        params: JSONObject,
        beforeDispatch: ExecutionBoundaryVerifier? = null
    )
    /**
     * Physical execution-mirror root the language server reports URIs against.
     * Used only to strip the prefix before a path becomes model-visible; it is
     * never emitted in a tool result. Null means "unknown", which the sanitizer
     * treats as fail-closed.
     */
    fun executionRoot(): String?
    /** A successful return is the barrier proving the process group is gone. */
    fun stop(): LspOutcome<Unit>
}

class LspSession(
    val key: LspSessionKey,
    var sourceDigest: String,
    private val transport: LspTransport
) {
    var state: LspState = LspState.STOPPED
        private set
    var lastUsedAt: Long = System.currentTimeMillis()
        private set
    fun start(
        token: CancellationToken = CancellationToken(),
        beforeDispatch: ExecutionBoundaryVerifier? = null
    ): LspOutcome<Unit> {
        if (state == LspState.READY) return LspOutcome.Success(Unit)
        if (state == LspState.DEGRADED) return LspOutcome.Failure(LspFailureCode.RESTART_BUDGET_EXHAUSTED)
        state = LspState.STARTING
        val outcome = transport.start(key.language, "project://", token, beforeDispatch)
        if (outcome is LspOutcome.Success) {
            state = LspState.READY
            lastUsedAt = System.currentTimeMillis()
            return outcome
        } else {
            val failure = outcome as LspOutcome.Failure
            val stopped = transport.stop()
            state = if (failure.code == LspFailureCode.RESTART_BUDGET_EXHAUSTED || stopped is LspOutcome.Failure)
                LspState.DEGRADED else LspState.BACKOFF
            return outcome
        }
    }

    fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long,
        token: CancellationToken,
        beforeDispatch: ExecutionBoundaryVerifier? = null
    ): LspOutcome<JSONObject> {
        if (state != LspState.READY) return LspOutcome.Failure(LspFailureCode.UNAVAILABLE)
        if (token.isCancelled) return LspOutcome.Failure(LspFailureCode.CANCELLED)
        lastUsedAt = System.currentTimeMillis()
        val outcome = transport.request(method, params, timeoutMs, token, beforeDispatch)
        if (outcome is LspOutcome.Failure &&
            (outcome.code == LspFailureCode.CRASHED || outcome.code == LspFailureCode.UNAVAILABLE ||
                outcome.code == LspFailureCode.TIMEOUT || outcome.code == LspFailureCode.CANCELLED ||
                outcome.code == LspFailureCode.PROTOCOL_INVALID || outcome.code == LspFailureCode.PROTOCOL_LIMIT)) {
            // Timeout/cancel/crash are terminal for this process group.  Tear
            // it down before exposing the failure so a later restart cannot
            // inherit a live child from the previous session.
            val stopped = transport.stop()
            state = if (stopped is LspOutcome.Success) LspState.BACKOFF else LspState.DEGRADED
        }
        return outcome
    }

    fun touch() {
        lastUsedAt = System.currentTimeMillis()
    }

    fun markStale(): LspOutcome<Unit> {
        state = LspState.STALE
        val stopped = transport.stop()
        state = if (stopped is LspOutcome.Success) LspState.STOPPED else LspState.DEGRADED
        return stopped
    }

    /** Execution-mirror root for the active session, or null when unknown. */
    fun executionRoot(): String? = transport.executionRoot()

    fun stop(): LspOutcome<Unit> {
        val stopped = transport.stop()
        state = if (stopped is LspOutcome.Success) LspState.STOPPED else LspState.DEGRADED
        return stopped
    }
}

