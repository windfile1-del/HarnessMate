package dev.wind.agent.intelligence.lsp

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.intelligence.CodeIntelLimits
import dev.wind.agent.intelligence.CodeLanguage
import dev.wind.agent.runtime.LinuxRuntimeClient
import dev.wind.agent.runtime.RuntimeBinding
import dev.wind.agent.runtime.RuntimeProviderId
import dev.wind.agent.runtime.RuntimeCommandResult
import dev.wind.agent.project.WorkspaceMount
import dev.wind.agent.shell.mirror.MirrorSyncBridgeServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.UUID

/** Runtime control retains the binding for every start, query and cleanup operation. */
interface LspRuntimeControl {
    fun start(binding: RuntimeBinding, argv: List<String>, timeoutMs: Long, stdin: String): String
    fun poll(binding: RuntimeBinding, handle: String): JSONObject?
    fun reconcile(binding: RuntimeBinding): Boolean
    fun cancel(binding: RuntimeBinding, handle: String)
    fun await(binding: RuntimeBinding, handle: String): RuntimeCommandResult
}

private class LinuxLspRuntimeControl(context: Context) : LspRuntimeControl {
    private val client = LinuxRuntimeClient(context.applicationContext)
    override fun start(binding: RuntimeBinding, argv: List<String>, timeoutMs: Long, stdin: String) =
        client.startCommand(binding, argv, timeoutMs = timeoutMs, stdin = stdin)
    override fun poll(binding: RuntimeBinding, handle: String) = client.pollCommand(binding, handle)
    override fun reconcile(binding: RuntimeBinding) = client.reconcile(binding)
    override fun cancel(binding: RuntimeBinding, handle: String) { client.cancel(binding, handle, 10_000L) }
    override fun await(binding: RuntimeBinding, handle: String) = client.awaitCommand(binding, handle, 2000L)
}

/** LSP stays in the authoritative execution mirror and owns a supervised runtime handle. */
class RuntimeLspTransport(
    private val context: Context,
    private val binding: RuntimeBinding,
    private val key: LspSessionKey,
    private val client: LspRuntimeControl = LinuxLspRuntimeControl(context),
    private val daemonSource: () -> ByteArray = { context.assets.open("code_intel_lsp_daemon.py").use { it.readBytes() } }
) : LspTransport {
    private var handle: String? = null
    private var port = 0
    private var secret = ""

    override fun executionRoot(): String? = if (port > 0) "/workspace" else null

    override fun start(language: CodeLanguage, rootUri: String, token: CancellationToken,
                       beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<Unit> {
        val stopped = stop()
        if (stopped is LspOutcome.Failure) return stopped
        return try {
            check(binding.providerId == RuntimeProviderId.VEGA_LINUX && binding.projectId == key.projectId &&
                binding.rootGeneration == key.rootGeneration && language == key.language) { "LSP_RUNTIME_IDENTITY_MISMATCH" }
            check(WorkspaceMount.isValidExecutionPath(key.executionPath)) {
                "LSP_PROJECT_ROOT_INVALID"
            }
            val projectUri = URI("file", "", "/workspace/" + key.executionPath, null).toString()
            token.throwIfCancelled()
            val descriptor = LanguageServerRegistry.getDescriptor(language) ?: error("LSP_LANGUAGE_UNAVAILABLE")
            val daemon = daemonSource()
            check(daemon.size <= 64 * 1024) { "LSP_BOOTSTRAP_LIMIT" }
            secret = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
            MirrorSyncBridgeServer(ByteArray(0), "{}".toByteArray(), { error("LSP_SOURCE_UNUSED") },
                { token.isCancelled }, emptyList()).use { bridge ->
                requireLive(beforeDispatch)
                handle = client.start(binding, listOf("python3", "-u", "-", "--root-uri", projectUri, "--source-root", "/workspace",
                    "--server", descriptor.id, "--session", TrustedLspSessionId.forKey(key).value,
                    "--secret", secret, "--control-url", bridge.url), timeoutMs = 3_600_000L,
                    stdin = String(daemon, Charsets.UTF_8))
                val deadline = System.nanoTime() + (descriptor.startTimeoutMs + 5000L) * 1_000_000L
                while (bridge.result == null) {
                    token.throwIfCancelled()
                    bridge.failure?.let { throw it }
                    if (System.nanoTime() >= deadline) error("LSP_TIMEOUT")
                    val status = client.poll(binding, handle!!)
                    if (status?.optString("state") == "TERMINATED")
                        error("LSP_START_FAILED: " + status.optString("stderr").takeLast(2000))
                    Thread.sleep(50)
                }
                val result = JSONObject(String(bridge.result!!, Charsets.UTF_8))
                check(result.optBoolean("ok") && result.optString("rootUri") == projectUri) {
                    "LSP_EXECUTION_ROOT_INVALID"
                }
                port = result.getInt("port").also { check(it in 1..65535) { "LSP_PORT_INVALID" } }
                token.throwIfCancelled()
            }
            LspOutcome.Success(Unit)
        } catch (error: Exception) {
            val cleanup = stop()
            val detail = (error.message ?: error.javaClass.simpleName) +
                if (cleanup is LspOutcome.Failure) "; cleanup: ${cleanup.detail}" else ""
            LspOutcome.Failure(if (token.isCancelled) LspFailureCode.CANCELLED else LspFailureCode.START_FAILED, detail)
        }
    }

    private fun requireLive(verifier: ExecutionBoundaryVerifier?) {
        check(handle == null || client.poll(binding, handle!!)?.optString("state") != "TERMINATED") {
            "LSP_CRASHED"
        }
        check(client.reconcile(binding)) { "LSP_RUNTIME_AUTHORITY_LOST" }
        (verifier ?: error("LSP_LIVE_LEASE_REQUIRED")).verify()
    }

    override fun request(method: String, params: JSONObject, timeoutMs: Long, token: CancellationToken,
                         beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<JSONObject> {
        return try {
            token.throwIfCancelled()
            requireLive(beforeDispatch)
            check(port > 0 && handle != null) { "LSP_UNAVAILABLE" }
            val request = JSONObject().put("action", "request").put("secret", secret)
                .put("method", method).put("params", params).put("timeoutSec", timeoutMs / 1000.0)
            val result = exchange(request, timeoutMs + 1000L, token)
            if (result.has("error")) {
                val detail = result.opt("error").toString()
                val code = when {
                    detail.contains("CANCELLED") -> LspFailureCode.CANCELLED
                    detail.contains("TIMEOUT") -> LspFailureCode.TIMEOUT
                    detail.contains("CRASHED") -> LspFailureCode.CRASHED
                    detail.contains("PATH_REJECTED") -> LspFailureCode.PATH_REJECTED
                    detail.contains("FRAME_LIMIT") -> LspFailureCode.PROTOCOL_LIMIT
                    detail.contains("PROTOCOL") -> LspFailureCode.PROTOCOL_INVALID
                    else -> LspFailureCode.UNAVAILABLE
                }
                LspOutcome.Failure(code, detail)
            } else LspOutcome.Success(result)
        } catch (error: Exception) {
            LspOutcome.Failure(when {
                token.isCancelled -> LspFailureCode.CANCELLED
                error is SocketTimeoutException -> LspFailureCode.TIMEOUT
                error is org.json.JSONException -> LspFailureCode.PROTOCOL_INVALID
                error.message?.contains("LSP_FRAME_LIMIT") == true -> LspFailureCode.PROTOCOL_LIMIT
                error.message?.contains("LSP_CRASHED") == true -> LspFailureCode.CRASHED
                else -> LspFailureCode.UNAVAILABLE
            },
                error.message ?: error.javaClass.simpleName)
        }
    }

    private fun exchange(request: JSONObject, timeoutMs: Long, token: CancellationToken): JSONObject {
        val bytes = (request.toString() + "\n").toByteArray(Charsets.UTF_8)
        check(bytes.size <= CodeIntelLimits.MAX_LSP_FRAME_BYTES) { "LSP_FRAME_LIMIT" }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), minOf(timeoutMs, 1000L).toInt())
            socket.soTimeout = 100
            socket.getOutputStream().write(bytes)
            val result = ByteArrayOutputStream()
            val input = socket.getInputStream().buffered()
            while (true) {
                token.throwIfCancelled()
                if (System.nanoTime() >= deadline) throw SocketTimeoutException("LSP_TIMEOUT")
                val byte = try { input.read() } catch (_: SocketTimeoutException) { continue }
                check(byte >= 0) { "LSP_CHANNEL_CLOSED" }
                if (byte == 10) return JSONObject(result.toString("UTF-8"))
                check(result.size() < CodeIntelLimits.MAX_LSP_FRAME_BYTES) { "LSP_FRAME_LIMIT" }
                result.write(byte)
            }
        }
    }

    override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {
        requireLive(beforeDispatch)
        check(port > 0 && handle != null) { "LSP_UNAVAILABLE" }
        val response = exchange(JSONObject().put("action", "notify").put("secret", secret)
            .put("method", method).put("params", params), 5000L, CancellationToken())
        check(!response.has("error")) { response.opt("error")?.toString().orEmpty() }
    }

    override fun stop(): LspOutcome<Unit> {
        val owned = handle ?: return LspOutcome.Success(Unit)
        return try {
            // Cancellation uses the immutable runtime handle, independent of an expired live lease.
            client.cancel(binding, owned)
            val result = client.await(binding, owned)
            check(result.cleanupConfirmed) { "LSP_STOP_UNCONFIRMED" }
            handle = null
            port = 0
            secret = ""
            LspOutcome.Success(Unit)
        } catch (error: Exception) {
            LspOutcome.Failure(LspFailureCode.UNAVAILABLE, error.message ?: "LSP_STOP_UNCONFIRMED")
        }
    }
}
