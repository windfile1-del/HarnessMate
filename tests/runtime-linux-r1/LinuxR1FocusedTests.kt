package dev.wind.agent.runtime

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Host-only R1 seams. This does not load JNI or claim Android/device acceptance. */
object LinuxR1FocusedTests {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "expected test scratch directory" }
        val scratch = File(args[0]).canonicalFile.apply { mkdirs() }
        supervisorStdinOutputCancelAndScope(scratch)
        terminalLogRetentionAndReadAuthority(scratch)
        linuxWorkspaceDoesNotRewriteLegacyBinding(scratch)
        wrongProviderProbeIsRejected()
        pollAuthorityLossIsNotReportedAsClean(scratch)
        println("LinuxR1FocusedTests: OK")
    }

    private fun terminalLogRetentionAndReadAuthority(scratch: File) {
        val logs = File(scratch, "execution-logs-${UUID.randomUUID()}").apply { mkdirs() }
        val supervisor = RuntimeProcessSupervisor(
            { argv, cwd, env ->
                ProcessBuilder(argv).directory(cwd).apply {
                    environment().clear()
                    environment().putAll(env)
                }.start()
            }, logs
        )
        try {
            val scope = RuntimeProcessSupervisor.BindingScope(
                "log-project", 1L, "aaaaaaaaaaaaaaaaaaaaaaaa", "log-instance")
            val wrongScope = RuntimeProcessSupervisor.BindingScope(
                "other-project", 1L, "bbbbbbbbbbbbbbbbbbbbbbbb", "log-instance")
            val execution = supervisor.start(
                scope, childCommand("large"), scratch, emptyMap(), 10_000L)

            var activeReadRejected = false
            try {
                supervisor.executionLog(execution.id, scope, "stdout")
            } catch (error: IllegalArgumentException) {
                activeReadRejected = error.message == "RUNTIME_LOG_NOT_TERMINAL"
            }
            check(activeReadRejected) { "active execution log was readable" }

            val result = supervisor.await(execution, 12_000L)
            check(result.success()) { "large-output command failed: $result" }
            check(result.stdout.length == RuntimeProcessSupervisor.MAX_OUTPUT_BYTES) {
                "summary retained ${result.stdout.length} bytes"
            }
            check(result.outputTruncated) { "large output was not marked truncated" }

            var wrongBindingRejected = false
            try {
                supervisor.executionLog(execution.id, wrongScope, "stdout")
            } catch (error: IllegalArgumentException) {
                wrongBindingRejected = error.message == "RUNTIME_EXECUTION_BINDING_MISMATCH"
            }
            check(wrongBindingRejected) { "wrong binding read a terminal log" }

            val log = supervisor.executionLog(execution.id, scope, "stdout")
            val bytes = Files.readAllBytes(log.toPath())
            check(bytes.size == 100 * 1024) { "disk log retained ${bytes.size} bytes" }
            check(bytes.all { it == 'A'.code.toByte() }) { "disk log payload changed" }
        } finally {
            supervisor.close()
        }
    }

    private fun supervisorStdinOutputCancelAndScope(scratch: File) {
        val factoryCalls = AtomicInteger()
        val supervisor = RuntimeProcessSupervisor { argv, cwd, env ->
            factoryCalls.incrementAndGet()
            ProcessBuilder(argv).directory(cwd).apply {
                environment().clear()
                environment().putAll(env)
            }.start()
        }
        try {
            val scope = RuntimeProcessSupervisor.BindingScope(
                "project-a", 3L, "0123456789abcdef01234567", "linux-instance")
            val wrongScope = RuntimeProcessSupervisor.BindingScope(
                "project-b", 3L, "fedcba9876543210fedcba98", "linux-instance")
            val input = supervisor.start(
                scope, childCommand("stdin"), scratch, emptyMap(), 8_000L, "bounded-input")
            val result = supervisor.await(input, 10_000L)
            check(result.success()) { "stdin result failed: $result" }
            check(result.stdout == "OUT:bounded-input") { "stdout mismatch: ${result.stdout}" }
            check(result.stderr == "ERR:complete") { "stderr mismatch: ${result.stderr}" }

            val waiting = supervisor.start(scope, childCommand("wait"), scratch, emptyMap(), 30_000L)
            Thread.sleep(250L)
            var rejected = false
            try {
                supervisor.getExecution(waiting.id, wrongScope)
            } catch (error: IllegalArgumentException) {
                rejected = error.message == "RUNTIME_EXECUTION_BINDING_MISMATCH"
            }
            check(rejected) { "wrong scope accessed execution handle" }
            val cancelled = supervisor.cancelAndAwait(waiting.id, scope, 5_000L)
            check(cancelled != null && cancelled.cancelled && cancelled.cleanupConfirmed) {
                "scoped cancellation was not confirmed: $cancelled"
            }
            check(factoryCalls.get() == 2) { "injected ProcessFactory calls=${factoryCalls.get()}" }
        } finally {
            supervisor.close()
        }
    }

    private fun linuxWorkspaceDoesNotRewriteLegacyBinding(scratch: File) {
        val filesDir = File(scratch, "workspace-${UUID.randomUUID()}").apply { mkdirs() }
        val workspaceId = "0123456789abcdef01234567"
        val legacy = RuntimeWorkspace(filesDir)
        val legacyBefore = legacy.open("project-shared", 9L, workspaceId)
        val legacyJsonBefore = legacy.readBinding("project-shared")!!.toJson().toString()

        val linux = RuntimeWorkspace(filesDir, "VEGA_LINUX", "linux-runtime-root")
        val linuxBinding = linux.open("project-shared", 9L, workspaceId)
        val legacyAfter = legacy.readBinding("project-shared")!!
        check(legacyBefore.providerId == "VEGA_COMPANION")
        check(linuxBinding.providerId == "VEGA_LINUX")
        check(legacyAfter.toJson().toString() == legacyJsonBefore) {
            "Linux provider changed the legacy binding record"
        }
        check(legacy.workspaceDirectory(workspaceId).canonicalPath !=
            linux.workspaceDirectory(workspaceId).canonicalPath) {
            "Linux and legacy providers share a workspace path"
        }
    }

    private fun wrongProviderProbeIsRejected() {
        val endpoint = object : RuntimeEndpoint {
            override val endpointId = "wrong-provider-probe"
            override fun transact(code: Int, payload: JSONObject): JSONObject =
                JSONObject().put("ok", true).put("providerId", RuntimeProviderId.VEGA_COMPANION.name)
                    .put("runtimeInstanceId", "companion-instance")
                    .put("capabilities", JSONObject())

            override fun openFileStream(
                binding: RuntimeBinding,
                path: String,
                operationId: String,
                expectedSize: Long,
                maxBytes: Long,
                expectedSha256: String?
            ): InputStream = error("unexpected stream request")
        }
        val client = CompanionRuntimeClient(
            endpointOverride = endpoint,
            expectedProviderId = RuntimeProviderId.VEGA_LINUX
        )
        val result = client.probe()
        check(!result.ready) { "probe accepted VEGA_COMPANION for a Linux client" }
        check(result.diagnostic.contains("RUNTIME_PROVIDER_MISMATCH")) {
            "wrong provider did not fail closed with a provider mismatch: $result"
        }
    }

    private fun pollAuthorityLossIsNotReportedAsClean(scratch: File) {
        val endpoint = object : RuntimeEndpoint {
            override val endpointId = "runtime-dies-during-poll"
            override fun transact(code: Int, payload: JSONObject): JSONObject = when (code) {
                CompanionRuntimeClient.TRANSACTION_START_COMMAND ->
                    JSONObject().put("ok", true).put("executionId", "handle-r1")
                CompanionRuntimeClient.TRANSACTION_POLL_COMMAND ->
                    throw IllegalStateException("simulated service death")
                else -> error("unexpected transaction $code")
            }

            override fun openFileStream(
                binding: RuntimeBinding,
                path: String,
                operationId: String,
                expectedSize: Long,
                maxBytes: Long,
                expectedSha256: String?
            ): InputStream = error("unexpected stream request")
        }
        val client = CompanionRuntimeClient(
            endpointOverride = endpoint,
            expectedProviderId = RuntimeProviderId.VEGA_LINUX
        )
        val binding = RuntimeBinding(
            RuntimeProviderId.VEGA_LINUX, "0123456789abcdef01234567", "project-r1", 1L, "linux-instance")
        val result = CompanionCommandExecutor(binding, client).execute(
            binding, RuntimeCommandSpec("true", timeoutMs = 5_000L), null)
        check(result.error == "RUNTIME_EXECUTION_AUTHORITY_LOST") { "authority loss hidden: $result" }
        check(!result.cleanupConfirmed) { "authority loss falsely confirmed cleanup: $result" }
        check(result.executionHandle == "handle-r1") { "execution handle lost: $result" }
    }

    private fun childCommand(mode: String): List<String> = listOf(
        File(System.getProperty("java.home"), "bin/java.exe").absolutePath,
        "-cp", System.getProperty("java.class.path"),
        LinuxR1Child::class.java.name, mode
    )
}

object LinuxR1Child {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.single()) {
            "stdin" -> {
                val bytes = System.`in`.readAllBytes()
                print("OUT:" + String(bytes, Charsets.UTF_8))
                System.err.print("ERR:complete")
            }
            "wait" -> {
                println("WAITING")
                System.out.flush()
                Thread.sleep(30_000L)
            }
            "large" -> {
                System.out.write(ByteArray(100 * 1024) { 'A'.code.toByte() })
                System.out.flush()
            }
            else -> error("unknown child mode")
        }
    }
}
