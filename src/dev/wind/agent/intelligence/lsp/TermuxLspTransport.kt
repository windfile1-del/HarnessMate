package dev.wind.agent.intelligence.lsp

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.intelligence.CodeLanguage
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Production LSP transport.
 *
 * The daemon and the language server both run inside the Termux persistent
 * execution workspace, started through the trusted code-intel bridge — never by
 * the Android app process. The physical mirror path is used only to strip the
 * prefix from returned URIs; it is never handed to the model.
 */
class TermuxLspTransport(
    private val context: Context,
    private val workspaceIdProvider: () -> String?,
    private val sessionKey: TrustedLspSessionId,
    private val rootTaskId: String = "",
    private val projectId: String = "",
    private val rootGeneration: Long = 0L,
    private val clientFactory: (Context, String) -> CodeIntelBridgeClient = { ctx, id ->
        CodeIntelBridgeClient(ctx, id)
    }
) : LspTransport {
    private var sessionId: String? = null
    /** Reserved before dispatch so cancellation owns a concrete cleanup key. */
    private var attemptId: String? = null
    private var executionRootPath: String? = null
    /** Captured at start; requests and stop never follow a changed active project. */
    private var boundWorkspaceId: String? = null

    override fun executionRoot(): String? = executionRootPath

    override fun start(
        language: CodeLanguage,
        rootUri: String,
        token: CancellationToken,
        beforeDispatch: ExecutionBoundaryVerifier?
    ): LspOutcome<Unit> {
        val stopped = stop()
        if (stopped is LspOutcome.Failure) return stopped
        if (token.isCancelled) return LspOutcome.Failure(LspFailureCode.CANCELLED)
        val descriptor = LanguageServerRegistry.getDescriptor(language)
            ?: return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "language not registered")
        if (!descriptor.enabled) return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "language disabled")
        val workspaceId = workspaceIdProvider()
            ?: return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "workspace unavailable")
        val daemon = runCatching { context.assets.open("code_intel_lsp_daemon.py").use { it.readBytes() } }
            .getOrElse { return LspOutcome.Failure(LspFailureCode.START_FAILED, it.message ?: "daemon asset unavailable") }
        val id = sessionKey.value
        val attempt = UUID.randomUUID().toString().replace("-", "")
        // Reserve ownership before ACTION_RUN_COMMAND is dispatched.  If the
        // callback is cancelled or the bridge throws after dispatch, the
        // cleanup path still has the exact workspace/session/attempt identity.
        boundWorkspaceId = workspaceId
        sessionId = id
        attemptId = attempt
        return try {
            val result = clientFactory(context, workspaceId).call(
                "lsp_start",
                JSONObject()
                    .put("sessionKey", id)
                    .put("server", descriptor.id)
                    .put("language", language.identifier)
                    .put("rootUri", rootUri)
                    .put("rootTaskId", rootTaskId)
                    .put("projectId", projectId)
                    .put("rootGeneration", rootGeneration)
                    .put("attemptId", attempt),
                sources = listOf({ ByteArrayInputStream(daemon) }),
                token = token,
                timeoutMs = 45_000L,
                beforeDispatch = beforeDispatch,
                cleanupOwnership = cleanupOwnershipVerifier(id, workspaceId)
            )
            if (token.isCancelled) {
                val cleanup = abortCurrentSession()
                if (cleanup is LspOutcome.Failure) {
                    return LspOutcome.Failure(LspFailureCode.CANCELLED,
                        "cancelled; cleanup failed: ${cleanup.detail}")
                }
                return LspOutcome.Failure(LspFailureCode.CANCELLED)
            }
            val rootUriValue = result.optString("rootUri", "")
            executionRootPath = rootUriValue.removePrefix("file://").takeIf { it.isNotEmpty() }
                ?: run {
                    val cleanup = abortCurrentSession()
                    return if (cleanup is LspOutcome.Failure) {
                        LspOutcome.Failure(LspFailureCode.START_FAILED,
                            "missing execution root; cleanup failed: ${cleanup.detail}")
                    } else {
                        LspOutcome.Failure(LspFailureCode.START_FAILED, "missing execution root")
                    }
                }
            LspOutcome.Success(Unit)
        } catch (error: Exception) {
            val cleanup = abortCurrentSession()
            val failed = failure(error, token, LspFailureCode.START_FAILED)
            if (cleanup is LspOutcome.Failure) {
                LspOutcome.Failure(failed.code, "${failed.detail}; cleanup failed: ${cleanup.detail}")
            } else {
                failed
            }
        }
    }

    override fun request(
        method: String,
        params: JSONObject,
        timeoutMs: Long,
        token: CancellationToken,
        beforeDispatch: ExecutionBoundaryVerifier?
    ): LspOutcome<JSONObject> {
        val id = sessionId ?: return LspOutcome.Failure(LspFailureCode.UNAVAILABLE)
        if (token.isCancelled) return LspOutcome.Failure(LspFailureCode.CANCELLED)
        val workspaceId = boundWorkspaceId ?: return LspOutcome.Failure(LspFailureCode.UNAVAILABLE)
        return try {
            val result = clientFactory(context, workspaceId).call(
                "lsp_request",
                JSONObject()
                    .put("sessionKey", id)
                    .put("method", method)
                    .put("params", params)
                    .put("timeoutSec", timeoutMs / 1000.0)
                    .put("rootTaskId", rootTaskId)
                    .put("projectId", projectId)
                    .put("rootGeneration", rootGeneration)
                    .put("attemptId", attemptId ?: ""),
                token = token,
                timeoutMs = timeoutMs + 15_000L,
                beforeDispatch = beforeDispatch,
                cleanupOwnership = cleanupOwnershipVerifier(id, workspaceId)
            )
            val response = result.optJSONObject("response")
                ?: return LspOutcome.Failure(LspFailureCode.PROTOCOL_INVALID, "missing response")
            if (response.has("error")) {
                val errorValue = response.optString("error", "")
                return LspOutcome.Failure(mapError(errorValue), errorValue)
            }
            LspOutcome.Success(response)
        } catch (error: Exception) {
            failure(error, token, LspFailureCode.UNAVAILABLE)
        }
    }

    override fun notify(
        method: String,
        params: JSONObject,
        beforeDispatch: ExecutionBoundaryVerifier?
    ) {
        // Notifications use the daemon request channel, but they remain
        // productive bridge calls. Without the live lease there is no
        // authority to dispatch, so fail closed instead of substituting the
        // cleanup ownership verifier.
        if (beforeDispatch == null) return
        val id = sessionId ?: return
        val workspaceId = boundWorkspaceId ?: return
        val expectedAttempt = attemptId ?: return
        runCatching {
            clientFactory(context, workspaceId).call(
                "lsp_request",
                JSONObject()
                    .put("sessionKey", id)
                    .put("method", method)
                    .put("params", params)
                    .put("timeoutSec", 5.0)
                    .put("rootTaskId", rootTaskId)
                    .put("projectId", projectId)
                    .put("rootGeneration", rootGeneration)
                    .put("attemptId", attemptId ?: ""),
                timeoutMs = 20_000L,
                beforeDispatch = ExecutionBoundaryVerifier {
                    check(sessionId == id && boundWorkspaceId == workspaceId && attemptId == expectedAttempt) {
                        "LSP_NOTIFY_OWNERSHIP_MISMATCH"
                    }
                    beforeDispatch.verify()
                },
                cleanupOwnership = cleanupOwnershipVerifier(id, workspaceId)
            )
        }
    }

    override fun stop(): LspOutcome<Unit> {
        val id = sessionId
        if (id == null) return LspOutcome.Success(Unit)
        val workspaceId = boundWorkspaceId
        if (workspaceId == null) return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "workspace binding lost")
        return try {
            val result =
            clientFactory(context, workspaceId)
                .call(
                    "lsp_stop",
                    JSONObject().put("sessionKey", id)
                        .put("rootTaskId", rootTaskId)
                        .put("projectId", projectId)
                        .put("rootGeneration", rootGeneration)
                        .put("attemptId", attemptId ?: ""),
                    timeoutMs = 60_000L,
                    cleanupOwnership = cleanupOwnershipVerifier(id, workspaceId)
                )
            if (result.optBoolean("stopped", false)) {
                sessionId = null
                attemptId = null
                executionRootPath = null
                boundWorkspaceId = null
                LspOutcome.Success(Unit)
            }
            else LspOutcome.Failure(LspFailureCode.TIMEOUT, "daemon stop was not confirmed")
        } catch (error: Exception) {
            LspOutcome.Failure(LspFailureCode.UNAVAILABLE, error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Abort a start/request even when its parent cancellation is already set.
     * This is deliberately a separate bridge call with no cancellation token;
     * the helper writes a durable cancel marker and confirms the complete
     * daemon/language-server group is gone before this method releases the
     * reserved identity.
     */
    private fun abortCurrentSession(): LspOutcome<Unit> {
        val id = sessionId ?: return LspOutcome.Success(Unit)
        val workspaceId = boundWorkspaceId
            ?: return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "workspace binding lost")
        return try {
            val result = clientFactory(context, workspaceId).call(
                "lsp_abort",
                JSONObject().put("sessionKey", id)
                    .put("attemptId", attemptId ?: "")
                    .put("rootTaskId", rootTaskId)
                    .put("projectId", projectId)
                    .put("rootGeneration", rootGeneration),
                token = null,
                timeoutMs = 60_000L,
                cleanupOwnership = cleanupOwnershipVerifier(id, workspaceId)
            )
            if (result.optBoolean("stopped", false) || result.optBoolean("cancelRequested", false)) {
                sessionId = null
                attemptId = null
                executionRootPath = null
                boundWorkspaceId = null
                LspOutcome.Success(Unit)
            } else {
                LspOutcome.Failure(LspFailureCode.TIMEOUT, "daemon abort was not confirmed")
            }
        } catch (error: Exception) {
            LspOutcome.Failure(LspFailureCode.UNAVAILABLE, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun failure(error: Exception, token: CancellationToken, fallback: LspFailureCode): LspOutcome.Failure {
        if (token.isCancelled) return LspOutcome.Failure(LspFailureCode.CANCELLED)
        val message = error.message ?: error.javaClass.simpleName
        return LspOutcome.Failure(mapError(message, fallback), message)
    }

    /**
     * Cleanup has to remain possible after lease cancellation, but it still
     * needs a final immutable ownership check immediately before dispatch.
     * This prevents a late stop/abort callback from targeting a replacement
     * session while deliberately avoiding the live-lease check used by new
     * productive execution.
     */
    private fun cleanupOwnershipVerifier(
        expectedSessionId: String,
        expectedWorkspaceId: String
    ): ExecutionBoundaryVerifier = ExecutionBoundaryVerifier {
        check(sessionId == expectedSessionId && boundWorkspaceId == expectedWorkspaceId) {
            "LSP_CLEANUP_OWNERSHIP_MISMATCH"
        }
        check(!attemptId.isNullOrBlank()) { "LSP_CLEANUP_ATTEMPT_ID_MISSING" }
    }

    private fun mapError(message: String, fallback: LspFailureCode = LspFailureCode.UNAVAILABLE): LspFailureCode {
        val upper = message.uppercase()
        return when {
            "TIMEOUT" in upper -> LspFailureCode.TIMEOUT
            "CRASH" in upper -> LspFailureCode.CRASHED
            "PROTOCOL" in upper || "FRAME" in upper -> LspFailureCode.PROTOCOL_INVALID
            "INITIALIZE" in upper -> LspFailureCode.INITIALIZE_FAILED
            else -> fallback
        }
    }
}
