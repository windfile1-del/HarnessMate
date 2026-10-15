package dev.wind.agent

import android.content.ContextWrapper
import dev.wind.agent.intelligence.CodeLanguage
import dev.wind.agent.intelligence.lsp.*
import dev.wind.agent.runtime.*
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Real production transport/HTTP readiness/TCP framing with an injected runtime control boundary. */
object RuntimeLspTransportTests {
    private var checks = 0
    private fun check(value: Boolean, message: String) { if (!value) error(message); checks++ }
    private val binding = RuntimeBinding(RuntimeProviderId.VEGA_LINUX, "0123456789abcdef01234567", "project", 3, "instance")
    private val key = LspSessionKey("task", "project", 3, CodeLanguage.PYTHON)
    private val context = object : ContextWrapper(null) {}

    private class Control : LspRuntimeControl {
        var starts = 0
        var cancelled = 0
        var valid = true
        var clean = true
        var root = "file:///workspace/main"
        var expectedRoot = root
        var invalidReply = false
        var silent = false
        var secret = ""
        val seen = mutableListOf<RuntimeBinding>()
        private var listener: ServerSocket? = null
        private val stopped = AtomicBoolean(false)
        override fun start(binding: RuntimeBinding, argv: List<String>, timeoutMs: Long, stdin: String): String {
            starts++
            seen.add(binding)
            check(timeoutMs == 3_600_000L && argv.first() == "python3", "supervised persistent command")
            check(argv[argv.indexOf("--root-uri") + 1] == expectedRoot, "project mount root")
            check(argv[argv.indexOf("--source-root") + 1] == "/workspace", "bounded mirror container")
            secret = argv[argv.indexOf("--secret") + 1]
            val url = argv[argv.indexOf("--control-url") + 1]
            val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            listener = server
            Thread {
                while (!stopped.get()) {
                    try {
                        server.accept().use { socket ->
                            val request = JSONObject(socket.getInputStream().bufferedReader().readLine())
                            check(request.getString("secret") == secret, "private authenticated channel")
                            if (silent) Thread.sleep(400)
                            else socket.getOutputStream().write((if (invalidReply) "{invalid}\n" else
                                JSONObject().put("result", JSONObject().put("observed", request.getString("method"))).toString() + "\n").toByteArray())
                        }
                    } catch (_: Exception) { if (!stopped.get()) throw IllegalStateException("test channel failed") }
                }
            }.apply { isDaemon = true; start() }
            val connection = URL(url + "/result").openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            val data = JSONObject().put("ok", true).put("port", server.localPort).put("rootUri", root).toString().toByteArray()
            connection.setFixedLengthStreamingMode(data.size)
            connection.outputStream.use { it.write(data) }
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            return "owned-handle"
        }
        override fun poll(binding: RuntimeBinding, handle: String): JSONObject { seen.add(binding); return JSONObject().put("state", "RUNNING") }
        override fun reconcile(binding: RuntimeBinding): Boolean { seen.add(binding); return valid }
        override fun cancel(binding: RuntimeBinding, handle: String) {
            seen.add(binding); check(handle == "owned-handle", "cleanup owns immutable handle")
            cancelled++; stopped.set(true); listener?.close()
        }
        override fun await(binding: RuntimeBinding, handle: String): RuntimeCommandResult {
            seen.add(binding); return RuntimeCommandResult(0, "", "", 1, cleanupConfirmed = clean)
        }
    }
    private fun transport(control: Control, bound: RuntimeBinding = binding, sessionKey: LspSessionKey = key) = RuntimeLspTransport(context, bound, sessionKey,
        control, { "# trusted daemon fixture".toByteArray() })
    private val lease = ExecutionBoundaryVerifier {}

    @JvmStatic fun main(args: Array<String>) {
        val control = Control()
        val transport = transport(control)
        check(transport.start(CodeLanguage.PYTHON, "workspace://main", CancellationToken(), lease) is LspOutcome.Success, "starts without Termux")
        check(transport.executionRoot() == "/workspace", "real execution root for path mapper")
        val result = transport.request("textDocument/definition", JSONObject(), 1000, CancellationToken(), lease)
        check(result is LspOutcome.Success && result.value.getJSONObject("result").getString("observed") == "textDocument/definition", "roundtrip framed JSON")
        control.valid = false
        check(transport.request("textDocument/references", JSONObject(), 1000, CancellationToken(), lease) is LspOutcome.Failure, "revoked runtime rejects productive call")
        check(transport.stop() is LspOutcome.Success && control.cancelled == 1, "cleanup survives expired authority")
        check(control.seen.all { it == binding }, "all operations retain original binding")
        check(transport.executionRoot() == null, "stopped root unavailable")

        val missing = Control(); val absent = transport(missing)
        check(absent.start(CodeLanguage.PYTHON, "", CancellationToken(), null) is LspOutcome.Failure && missing.starts == 0, "missing lease never dispatches")
        val wrong = Control(); val wrongBinding = transport(wrong, binding.copy(rootGeneration = 9))
        check(wrongBinding.start(CodeLanguage.PYTHON, "", CancellationToken(), lease) is LspOutcome.Failure && wrong.starts == 0, "generation mismatch never dispatches")
        val other = Control(); val termux = transport(other, binding.copy(providerId = RuntimeProviderId.TERMUX))
        check(termux.start(CodeLanguage.PYTHON, "", CancellationToken(), lease) is LspOutcome.Failure && other.starts == 0, "provider mismatch has no fallback")
        val cancelled = Control(); val cancelToken = CancellationToken().apply { cancel() }
        check(transport(cancelled).start(CodeLanguage.PYTHON, "", cancelToken, lease) is LspOutcome.Failure && cancelled.starts == 0, "pre-cancel never starts")

        val badRoot = Control().apply { root = "file:///other" }
        check(transport(badRoot).start(CodeLanguage.PYTHON, "", CancellationToken(), lease) is LspOutcome.Failure && badRoot.cancelled == 1, "bad ready root cleans owned process")
        val dirty = Control(); val dirtyTransport = transport(dirty)
        check(dirtyTransport.start(CodeLanguage.PYTHON, "", CancellationToken(), lease) is LspOutcome.Success, "cleanup fixture starts")
        dirty.clean = false
        check(dirtyTransport.stop() is LspOutcome.Failure, "uncertain cleanup is never success")
        dirty.clean = true
        check(dirtyTransport.stop() is LspOutcome.Success && dirty.cancelled == 2, "retained ownership supports cleanup retry")
        val malformed = Control(); val malformedTransport = transport(malformed)
        malformedTransport.start(CodeLanguage.PYTHON, "", CancellationToken(), lease)
        malformed.invalidReply = true
        val malformedResult = malformedTransport.request("textDocument/definition", JSONObject(), 1000, CancellationToken(), lease)
        check(malformedResult is LspOutcome.Failure && malformedResult.code == LspFailureCode.PROTOCOL_INVALID, "invalid protocol cannot pass")
        malformedTransport.stop()
        val nestedKey = key.copy(executionPath = "shared/中文 项目")
        val nested = Control().apply {
            root = java.net.URI("file", "", "/workspace/" + nestedKey.executionPath, null).toString()
            expectedRoot = root
        }
        val nestedTransport = transport(nested, sessionKey = nestedKey)
        check(nestedTransport.start(CodeLanguage.PYTHON, "", CancellationToken(), lease) is LspOutcome.Success,
            "nested Unicode mount uses exact encoded project URI")
        nestedTransport.stop()
        val escaped = Control()
        check(transport(escaped, sessionKey = key.copy(executionPath = "../other")).start(CodeLanguage.PYTHON,
            "", CancellationToken(), lease) is LspOutcome.Failure && escaped.starts == 0, "invalid project root cannot dispatch")
        val startupCleanup = Control().apply { root = "file:///wrong"; clean = false }
        val owningManager = LanguageServerManager { transport(startupCleanup) }
        check(owningManager.getSession(key, "digest", beforeSideEffect = {}) is LspOutcome.Failure, "failed startup reports error")
        val attempts = startupCleanup.starts
        check(owningManager.getSession(key, "digest", beforeSideEffect = {}) is LspOutcome.Failure && startupCleanup.starts == attempts,
            "unconfirmed startup cleanup cannot dispatch a replacement")
        val cancellations = startupCleanup.cancelled
        startupCleanup.clean = true
        owningManager.stopAll()
        check(startupCleanup.cancelled > cancellations, "manager retains failed startup ownership for cleanup retry")
        val pending = Control().apply { silent = true }
        val pendingSession = LspSession(key, "digest", transport(pending))
        check(pendingSession.start(CancellationToken(), lease) is LspOutcome.Success, "cancel fixture starts")
        val pendingToken = CancellationToken()
        Thread { Thread.sleep(50); pendingToken.cancel() }.start()
        val cancelledRequest = pendingSession.request("textDocument/definition", JSONObject(), 1000L, pendingToken, lease)
        check(cancelledRequest is LspOutcome.Failure && cancelledRequest.code == LspFailureCode.CANCELLED
            && pending.cancelled == 1, "in-flight cancellation cleans the owned runtime process")
        println("PASS RuntimeLspTransportTests: $checks checks")
    }
}
