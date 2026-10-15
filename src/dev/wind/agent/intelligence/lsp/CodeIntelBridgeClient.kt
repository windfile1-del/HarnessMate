package dev.wind.agent.intelligence.lsp

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.mirror.MirrorSyncBridgeServer
import dev.wind.agent.shell.mirror.WorkspaceIdentity
import dev.wind.agent.shell.termux.TermuxRunCommandClient
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trusted execution bridge for Code Intelligence.
 *
 * This is the P5 twin of TermuxMirrorClient: the APK asset is streamed over an
 * internally generated loopback URL and piped into python3 *inside Termux*,
 * exactly like the P3 mirror helper. The model never supplies a script, an
 * executable, an argv or a path here — only a language id and JSON-RPC payloads.
 *
 * The Android app sandbox is therefore NOT an execution substrate: the language
 * server daemon runs in the same persistent execution workspace P3 owns.
 */
class CodeIntelBridgeClient(
    private val context: Context,
    private val workspaceId: String
) {
    fun call(
        operation: String,
        args: JSONObject = JSONObject(),
        sources: List<() -> InputStream> = emptyList(),
        token: CancellationToken? = null,
        timeoutMs: Long = 120_000L,
        beforeDispatch: ExecutionBoundaryVerifier? = null,
        cleanupOwnership: ExecutionBoundaryVerifier? = null
    ): JSONObject {
        WorkspaceIdentity.remoteRoot(workspaceId)
        val control = JSONObject()
            .put("protocolVersion", 2)
            .put("operation", operation)
            .put("workspaceId", workspaceId)
            .put("args", args)
        val helper = context.assets.open("code_intel_helper.py").use { it.readBytes() }
        MirrorSyncBridgeServer(
            helper,
            control.toString().toByteArray(Charsets.UTF_8),
            { index -> sources.getOrNull(index)?.invoke() ?: error("CODE_INTEL_SOURCE_UNKNOWN") },
            { token?.isCancelled == true },
            emptyList()
        ).use { server ->
            // ACTION_RUN_COMMAND cannot be withdrawn after dispatch.  For the
            // two operations that own a daemon, retain the immutable session
            // identity and arrange an out-of-band, non-cancelled abort when
            // the original callback is abandoned.  The helper validates the
            // identity and makes the operation idempotent, so a late callback
            // cannot resurrect a detached language-server process group.
            val cleanupArgs = if (operation == "lsp_start" || operation == "lsp_request") {
                JSONObject(args.toString())
            } else {
                null
            }
            val cleanupInvoked = AtomicBoolean(false)
            val cleanup: (() -> Unit)? = cleanupArgs?.let { abortArgs ->
                {
                    if (cleanupInvoked.compareAndSet(false, true)) {
                        runCatching {
                            CodeIntelBridgeClient(context, workspaceId).call(
                                "lsp_abort",
                                JSONObject(abortArgs.toString()),
                                token = null,
                                timeoutMs = 60_000L,
                                cleanupOwnership = cleanupOwnershipVerifier(abortArgs)
                            )
                        }
                    }
                }
            }
            // The bootstrap contains only an internally generated URL.
            val script = "export PATH=/data/data/com.termux/files/usr/bin:/system/bin\n" +
                "command -v python3 >/dev/null || { echo CODE_INTEL_REQUIRES_PYTHON >&2; exit 69; }\n" +
                "set -o pipefail\n" +
                "curl --noproxy '*' -fsS '${server.url}/helper' | python3 - '${server.url}'\n"
            // The verifier is deliberately the final operation before the
            // Termux dispatch. Rechecking earlier in the call would leave a
            // lease-expiry race between validation and ACTION_RUN_COMMAND.
            // Productive calls always use the live lease verifier. Cleanup
            // calls use a separate immutable ownership verifier: cancellation
            // must not be blocked by an expired lease, but it must still prove
            // that the reserved session/attempt belongs to this invocation.
            val dispatchVerifier = when {
                operation == "lsp_start" || operation == "lsp_request" ->
                    beforeDispatch ?: error("LSP_LIVE_LEASE_REQUIRED")
                operation == "lsp_stop" || operation == "lsp_abort" ->
                    cleanupOwnership ?: beforeDispatch ?: error("LSP_CLEANUP_AUTHORITY_REQUIRED")
                else -> if (beforeDispatch != null) beforeDispatch else cleanupOwnership
            }
            dispatchVerifier?.verify()
            val outcome = TermuxRunCommandClient(context).run(
                TermuxRunCommandClient.Request(
                    commandPath = "/data/data/com.termux/files/usr/bin/bash",
                    args = listOf("-s"),
                    stdin = script,
                    timeoutMs = timeoutMs + 30_000L,
                    cancellation = token,
                    onAbandon = cleanup
                )
            )
            val raw = outcome.result
            if (outcome.cancelled || token?.isCancelled == true) {
                cleanup?.invoke()
                error("LSP_CANCELLED")
            }
            if (outcome.timedOut) {
                cleanup?.invoke()
                error("LSP_TIMEOUT")
            }
            check(outcome.callbackReceived && raw != null && !raw.hasInternalError && raw.exitCode == 0) {
                cleanup?.invoke()
                "CODE_INTEL_RPC_FAILED: " +
                    (outcome.dispatchError ?: raw?.stderr ?: "callback unavailable; code intelligence state uncertain")
            }
            server.failure?.let {
                cleanup?.invoke()
                throw it
            }
            val bytes = server.result ?: run {
                cleanup?.invoke()
                error("CODE_INTEL_RPC_MISSING_RESULT")
            }
            val result = JSONObject(String(bytes, Charsets.UTF_8))
            if (!result.optBoolean("ok", false)) {
                cleanup?.invoke()
                error(result.optString("error", "CODE_INTEL_FAILED"))
            }
            return result
        }
    }

    private fun cleanupOwnershipVerifier(args: JSONObject): ExecutionBoundaryVerifier =
        ExecutionBoundaryVerifier {
            // This is intentionally not a live capability/lease check.  The
            // parent request may already be cancelled, and rejecting cleanup
            // on that basis would strand the process group.  It is instead an
            // immutable identity check performed immediately before the
            // cleanup ACTION_RUN_COMMAND dispatch.
            check(args.optString("sessionKey").isNotBlank()) {
                "LSP_CLEANUP_SESSION_ID_MISSING"
            }
            check(args.optString("attemptId").isNotBlank()) {
                "LSP_CLEANUP_ATTEMPT_ID_MISSING"
            }
            check(args.has("rootTaskId") && args.has("projectId") && args.has("rootGeneration")) {
                "LSP_CLEANUP_IDENTITY_MISSING"
            }
        }
}
