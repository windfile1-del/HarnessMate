package dev.wind.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.wind.agent.project.*
import dev.wind.agent.runtime.*
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_AWAIT_COMMAND
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_CANCEL
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_MIRROR_CALL
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_OPEN_WORKSPACE
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_POLL_COMMAND
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_PROBE
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_RECONCILE
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_START_COMMAND
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_SYNC_ABORT
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_SYNC_BEGIN
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_SYNC_COMMIT
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_SYNC_FILE
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.TRANSACTION_SYNC_PLAN_PAGE
import dev.wind.agent.runtime.CompanionRuntimeClient.Companion.MIRROR_STREAM_PROTOCOL_VERSION
import dev.wind.agent.shell.ExecutionTarget
import dev.wind.agent.shell.ShellRequest
import dev.wind.agent.shell.mirror.*
import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Production-boundary integration tests for P6.3:
 * Host + Provider-Neutral Mirror Integration using VegaRuntimeCompanion.
 */
object CompanionMirrorIntegrationTests {
    private var assertions = 0

    private fun check(value: Boolean, message: String) {
        assertions++
        if (!value) throw AssertionError(message)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val tempRoot = Files.createTempDirectory("vega-p63-test").toFile()
        try {
            if (args.firstOrNull() == "--discard-cache-only") {
                discardDirectoryContainingIgnoredCaches(File(tempRoot, "discard").apply { mkdirs() })
                println("PASS discard cache regression: $assertions assertions")
                return
            }
            if (args.firstOrNull() == "--code-intel-pending-only") {
                linuxUuidProjectExecutionAndCapabilities(File(tempRoot, "t8_linux_uuid").apply { mkdirs() })
                println("PASS code intel pending regression: $assertions assertions")
                return
            }
            companionClientProtocolAndMirror(File(tempRoot, "t1").apply { mkdirs() })
            syncSessionBindingIsolation(File(tempRoot, "t1_iso").apply { mkdirs() })
            companionCommandExecution(File(tempRoot, "t2").apply { mkdirs() })
            longCommandDoesNotCancelEarly(File(tempRoot, "t2_long").apply { mkdirs() })
            companionMirrorP3SyncAndDiff(File(tempRoot, "t3").apply { mkdirs() })
            discardDirectoryContainingIgnoredCaches(File(tempRoot, "t3_discard").apply { mkdirs() })
            manifestPagingAndIgnorePolicy(File(tempRoot, "t3_manifest").apply { mkdirs() })
            executionFileOperations(File(tempRoot, "t3_ops").apply { mkdirs() })
            manifestPageHonorsByteBudget(File(tempRoot, "t3_budget").apply { mkdirs() })
            largeFileManifestHashesWholePayload(File(tempRoot, "t3_hash").apply { mkdirs() })
            ignorePolicyHasNoHiddenCompanionRules(File(tempRoot, "t3_policy").apply { mkdirs() })
            symlinkNeverEntersManifest(File(tempRoot, "t3_link").apply { mkdirs() })
            staleRemoteBeforeSyncHasZeroMutation(File(tempRoot, "t3_stale_begin").apply { mkdirs() })
            staleRemoteDuringStagingHasZeroSyncMutation(File(tempRoot, "t3_stale_commit").apply { mkdirs() })
            streamRejectsWrongSizeShaAndEarlyEof(File(tempRoot, "t3_stream").apply { mkdirs() })
            multiProviderZeroCrossRouting(File(tempRoot, "t4").apply { mkdirs() })
            companionLspReturnsUnavailableWithZeroTermuxCalls(File(tempRoot, "t4_lsp").apply { mkdirs() })
            authorityFailureNeverConstructsTermux(File(tempRoot, "t4_auth").apply { mkdirs() })
            pathEscapeFailsClosed(File(tempRoot, "t5").apply { mkdirs() })
            largeBinaryStreamIntegrityInProcess(File(tempRoot, "t6_stream").apply { mkdirs() })
            syncAbortIsIdempotent(File(tempRoot, "t6_abort").apply { mkdirs() })
            productionProjectMirrorManagerE2EAndFailClosed(File(tempRoot, "t7_e2e").apply { mkdirs() })
            linuxUuidProjectExecutionAndCapabilities(File(tempRoot, "t8_linux_uuid").apply { mkdirs() })
            println("CompanionMirrorIntegrationTests: $assertions assertions passed")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Runs one complete sync for a set of text files, in the shape the host
     * produces: a page of entries carrying source indices, then one lambda per
     * payload.
     *
     * Every sync in this file goes through here, so the plan format is written
     * once. A test that hand-rolled its own page could pass while the host
     * produced something else entirely.
     */
    private fun syncFiles(
        mirrorClient: CompanionMirrorClient,
        expectedRemoteDigest: String,
        vararg files: Pair<String, String>
    ): JSONObject {
        val entries = JSONArray()
        val payloads = mutableListOf<() -> java.io.InputStream>()
        for ((index, file) in files.withIndex()) {
            val (path, content) = file
            val bytes = content.toByteArray()
            entries.put(JSONObject()
                .put("path", path)
                .put("directory", false)
                .put("size", bytes.size.toLong())
                .put("sha256", sha256Of(content))
                .put("source", index + 1))
            payloads.add { java.io.ByteArrayInputStream(bytes) }
        }
        val page = JSONObject().put("entries", entries).toString().toByteArray()
        val sources = listOf<() -> java.io.InputStream>({ java.io.ByteArrayInputStream(page) }) + payloads
        return mirrorClient.call("sync", JSONObject()
            .put("planSources", JSONArray().put(0))
            .put("planPageCount", 1)
            .put("expectedRemoteDigest", expectedRemoteDigest)
            .put("plannedFiles", files.size)
            .put("plannedBytes", files.sumOf { it.second.toByteArray().size.toLong() }),
            sources)
    }

    private fun sha256Of(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun createInProcessClient(root: File, providerId: RuntimeProviderId = RuntimeProviderId.VEGA_COMPANION): CompanionRuntimeClient {
        val ws = if (providerId == RuntimeProviderId.VEGA_LINUX) RuntimeWorkspace(root, providerId.name, "linux-runtime-root")
            else RuntimeWorkspace(root)
        val supervisor = RuntimeProcessSupervisor()
        return object : CompanionRuntimeClient(
            expectedProviderId = providerId,
            inProcessDispatcher = { code, payload ->
            val request = JSONObject(payload)
            when (code) {
                TRANSACTION_PROBE -> JSONObject()
                    .put("ok", true)
                    .put("providerId", providerId.name)
                    .put("protocolVersion", 2)
                    .put("layoutVersion", 1)
                    .put("runtimeInstanceId", ws.runtimeInstanceId())
                    .put("capabilities", JSONObject()
                        .put("commandExecution", true)
                        .put("persistentWorkspace", true)
                        .put("processGroupCancellation", RuntimeProcessSupervisor.supportsProcessGroupCancellation())
                        .put("mirrorProtocolVersion", 2)
                        .put("mirrorStreamProtocolVersion", MIRROR_STREAM_PROTOCOL_VERSION)
                        .put("networkIsolation", "POLICY_ONLY")
                        .put("runtimeVersion", "p6.3-test"))
                    .toString()

                TRANSACTION_OPEN_WORKSPACE -> {
                    val b = ws.open(request.getString("projectId"), request.getLong("rootGeneration"), request.getString("workspaceId"))
                    JSONObject().put("ok", true).put("binding", b.toJson()).toString()
                }

                TRANSACTION_START_COMMAND -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    val argv = mutableListOf<String>()
                    val arr = request.getJSONArray("argv")
                    for (i in 0 until arr.length()) argv.add(arr.getString(i))
                    val scope = RuntimeProcessSupervisor.BindingScope(proj, gen, wid, inst)
                    val workDirRelative = request.optString("workDir", "")
                    val workingDir = ws.resolveWorkingDirectory(wid, workDirRelative)
                    val ex = supervisor.start(scope, argv, workingDir, emptyMap(), request.optLong("timeoutMs", 30000L))
                    JSONObject().put("ok", true).put("executionId", ex.id).put("state", "RUNNING").toString()
                }

                TRANSACTION_AWAIT_COMMAND -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    val scope = RuntimeProcessSupervisor.BindingScope(proj, gen, wid, inst)
                    val id = request.getString("executionId")
                    val ex = supervisor.getExecution(id, scope)
                    val res = if (ex != null) supervisor.await(ex, request.optLong("waitMs", 5000L)) else supervisor.getResult(id, scope)
                    if (res == null) {
                        JSONObject().put("ok", false).put("errorCode", "RUNTIME_EXECUTION_UNKNOWN").toString()
                    } else {
                        JSONObject().put("ok", res.success()).put("executionId", res.executionId)
                            .put("exitCode", res.exitCode).put("stdout", ws.scrub(res.stdout))
                            .put("stderr", ws.scrub(res.stderr)).put("durationMs", res.durationMs)
                            .put("timedOut", res.timedOut).put("cancelled", res.cancelled)
                            .put("cleanupConfirmed", res.cleanupConfirmed).toString()
                    }
                }

                TRANSACTION_CANCEL -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    val scope = RuntimeProcessSupervisor.BindingScope(proj, gen, wid, inst)
                    val res = supervisor.cancelAndAwait(request.getString("executionId"), scope, request.optLong("waitMs", 2500L))
                    JSONObject().put("ok", res != null && res.cancelled && res.cleanupConfirmed).toString()
                }

                TRANSACTION_POLL_COMMAND -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    val scope = RuntimeProcessSupervisor.BindingScope(proj, gen, wid, inst)
                    val id = request.getString("executionId")
                    val res = supervisor.getResult(id, scope)
                    if (res != null) {
                        JSONObject().put("ok", res.success()).put("executionId", res.executionId)
                            .put("state", "TERMINATED").put("exitCode", res.exitCode)
                            .put("stdout", ws.scrub(res.stdout)).put("stderr", ws.scrub(res.stderr))
                            .put("durationMs", res.durationMs).put("timedOut", res.timedOut)
                            .put("cancelled", res.cancelled).put("cleanupConfirmed", res.cleanupConfirmed).toString()
                    } else {
                        val ex = supervisor.getExecution(id, scope)
                        if (ex == null) {
                            JSONObject().put("ok", false).put("errorCode", "RUNTIME_EXECUTION_UNKNOWN").toString()
                        } else {
                            JSONObject().put("ok", true).put("executionId", id).put("state", "RUNNING").toString()
                        }
                    }
                }

                TRANSACTION_RECONCILE -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    val b = ws.requireBound(proj, gen, wid, inst)
                    val ok = ws.projectDirectory(b.workspaceId).isDirectory
                    JSONObject().put("ok", ok).put("runtimeInstanceId", ws.runtimeInstanceId()).toString()
                }

                TRANSACTION_MIRROR_CALL -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    val op = request.getString("operation")
                    val args = request.optJSONObject("args") ?: JSONObject()
                    ws.handleMirrorCall(wid, op, args, request.optJSONObject("ignorePolicy")).toString()
                }

                TRANSACTION_SYNC_BEGIN -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    runCatching { ws.syncBegin(wid, request) }
                        .getOrElse { JSONObject().put("ok", false).put("errorCode", it.message ?: "RUNTIME_SYNC_FAILED") }
                        .toString()
                }

                TRANSACTION_SYNC_COMMIT -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    runCatching { ws.syncCommit(wid, request) }
                        .getOrElse { JSONObject().put("ok", false).put("errorCode", it.message ?: "RUNTIME_SYNC_FAILED") }
                        .toString()
                }

                TRANSACTION_SYNC_ABORT -> {
                    val proj = request.getString("projectId")
                    val gen = request.getLong("rootGeneration")
                    val wid = request.getString("workspaceId")
                    val inst = request.getString("runtimeInstanceId")
                    ws.requireBound(proj, gen, wid, inst)
                    runCatching { ws.syncAbort(wid, request) }
                        .getOrElse { JSONObject().put("ok", false).put("errorCode", it.message ?: "RUNTIME_SYNC_FAILED") }
                        .toString()
                }

                else -> JSONObject().put("ok", false).put("errorCode", "UNKNOWN_TRANSACTION").toString()
            }
        },
            inProcessStreamOpener = { binding, path, expectedSize, _ ->
                ws.requireBound(binding.projectId, binding.rootGeneration, binding.workspaceId, binding.runtimeInstanceId)
                // Match descriptor exports: use the manifest's exact size, not the old text buffer cap.
                val metadata = ws.openReadFile(binding.workspaceId, path, expectedSize,
                    if (expectedSize >= 0) expectedSize else 16L * 1024 * 1024)
                ws.openReadStream(binding.workspaceId, metadata.getString("path")
                    .substring(ws.projectDirectory(binding.workspaceId).path.length + 1))
            },
            /**
             * The descriptor-carrying transactions, without a descriptor.
             *
             * Only the transport differs: the source stream is handed straight
             * to the same production entry points the service calls, so a test
             * failure here is a failure of the session logic, not of the seam.
             */
            inProcessPipeline = { code, payload, source, _ ->
                val request = JSONObject(payload)
                val proj = request.getString("projectId")
                val gen = request.getLong("rootGeneration")
                val wid = request.getString("workspaceId")
                val inst = request.getString("runtimeInstanceId")
                ws.requireBound(proj, gen, wid, inst)
                runCatching {
                    when (code) {
                        TRANSACTION_SYNC_PLAN_PAGE -> {
                            val page = source().use { it.readBytes() }
                            ws.syncPlanPage(wid, request, page)
                        }
                        TRANSACTION_SYNC_FILE -> ws.syncFile(wid, request, source())
                        else -> throw IllegalStateException("UNKNOWN_TRANSACTION")
                    }
                }.getOrElse {
                    JSONObject().put("ok", false)
                        .put("errorCode", it.message ?: "RUNTIME_SYNC_FAILED")
                }.toString()
            }
        ) {}
    }

    private fun companionClientProtocolAndMirror(root: File) {
        val client = createInProcessClient(root)
        val probe = client.probe()
        check(probe.ready, "probe must be ready")
        check(probe.providerId == RuntimeProviderId.VEGA_COMPANION, "provider must be companion")

        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-test", 1L, workspaceId)
        check(binding.matches("proj-test", 1L, workspaceId), "binding identity mismatch")
        check(binding.protocolVersion == 2, "protocol version must be 2")

        val mirrorClient = CompanionMirrorClient(binding, client)
        val emptyManifest = mirrorClient.call("manifest")
        check(emptyManifest.optBoolean("ok"), "manifest call failed")
        check(emptyManifest.getString("manifestDigest").matches(Regex("[0-9a-f]{64}")), "digest invalid")

        // Sync a file
        val syncResult = syncFiles(
            mirrorClient, emptyManifest.getString("manifestDigest"), "src/Hello.kt" to "fun main() {}")
        check(syncResult.optBoolean("ok"), "sync call failed")
        val newDigest = syncResult.getString("remoteDigest")
        check(newDigest != emptyManifest.getString("manifestDigest"), "digest unchanged after sync")

        // Fetch text
        val fetched = mirrorClient.call("fetch_text", JSONObject().put("path", "src/Hello.kt"))
        check(fetched.getString("text") == "fun main() {}", "fetched text mismatch")

        // Download file
        val bytes = mirrorClient.downloadFile("src/Hello.kt", -1L, "")
        check(String(bytes, Charsets.UTF_8) == "fun main() {}", "downloaded bytes mismatch")
    }

    private fun companionCommandExecution(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-cmd", 1L, workspaceId)
        val runtime = CompanionExecutionRuntime(client)
        val executor = runtime.commandExecutor(binding)

        val windows = System.getProperty("os.name").lowercase().contains("win")
        val spec = if (windows) {
            RuntimeCommandSpec("cmd.exe", listOf("/c", "echo companion-ok"))
        } else {
            RuntimeCommandSpec("/bin/sh", listOf("-c", "printf 'companion-ok'"))
        }
        val result = executor.execute(binding, spec)
        check(result.exitCode == 0, "exit code must be 0")
        check(result.stdout.trim() == "companion-ok", "stdout mismatch: " + result.stdout)
        check(result.cleanupConfirmed, "cleanup confirmed must be true")

        // Test command execution respects workDir (Blocker G-01)
        val mirrorClient = CompanionMirrorClient(binding, client)
        mirrorClient.call("execution_make_dir", JSONObject().put("path", "sub/app"))
        mirrorClient.call("execution_write_file", JSONObject().put("path", "sub/app/marker.txt").put("content", "in-app"))
        val workDirCmd = if (windows) {
            RuntimeCommandSpec("cmd.exe", listOf("/c", "type marker.txt"), workDir = "sub/app")
        } else {
            RuntimeCommandSpec("cat", listOf("marker.txt"), workDir = "sub/app")
        }
        val workDirResult = executor.execute(binding, workDirCmd)
        check(workDirResult.exitCode == 0, "command with workDir must succeed")
        check(workDirResult.stdout.contains("in-app"), "command must execute in designated workDir (got: " + workDirResult.stdout + ")")
    }

    private fun longCommandDoesNotCancelEarly(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-long", 1L, workspaceId)
        val runtime = CompanionExecutionRuntime(client)
        val executor = runtime.commandExecutor(binding)

        val windows = System.getProperty("os.name").lowercase().contains("win")
        val spec = if (windows) {
            RuntimeCommandSpec("powershell.exe", listOf("-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 3; echo long-done"), timeoutMs = 15000L)
        } else {
            RuntimeCommandSpec("/bin/sh", listOf("-c", "sleep 3 && printf 'long-done'"), timeoutMs = 15000L)
        }
        val result = executor.execute(binding, spec)
        check(result.exitCode == 0, "long command must exit 0")
        check(result.stdout.contains("long-done"), "stdout must contain long-done")
        check(!result.cancelled, "command was cancelled early by poll")
        check(!result.timedOut, "command timed out prematurely")
        check(result.cleanupConfirmed, "cleanup confirmed must be true")
    }

    private fun companionMirrorP3SyncAndDiff(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-p3", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)

        // Initial sync of 2 files
        val emptyDigest = mirrorClient.call("manifest").getString("manifestDigest")
        val sync1 = syncFiles(mirrorClient, emptyDigest,
            "README.md" to "# Title\n", "src/App.kt" to "class App\n")
        val digest1 = sync1.getString("remoteDigest")

        // Mutation via edit_file
        val editResult = mirrorClient.call("edit_file", JSONObject()
            .put("path", "README.md")
            .put("old_text", "# Title\n")
            .put("new_text", "# New Title\n")
        )
        check(editResult.optBoolean("ok"), "edit failed")

        // Manifest after mutation
        val manifest2 = mirrorClient.call("manifest")
        val digest2 = manifest2.getString("manifestDigest")
        check(digest1 != digest2, "digest must change after edit")

        val updatedText = mirrorClient.call("fetch_text", JSONObject().put("path", "README.md")).getString("text")
        check(updatedText == "# New Title\n", "text not updated")
    }

    private fun discardDirectoryContainingIgnoredCaches(root: File) {
        val client = createInProcessClient(root)
        val binding = client.openWorkspace("proj-discard-cache", 1L, "0123456789abcdef01234567")
        val mirror = CompanionMirrorClient(binding, client)
        mirror.call("execution_write_file", JSONObject().put("path", "probe/main.py").put("content", "print('ok')"))
        mirror.call("execution_write_file", JSONObject().put("path", "probe/__pycache__/main.pyc").put("content", "cache"))
        mirror.call("execution_write_file", JSONObject().put("path", "probe/.gradle/cache.bin").put("content", "cache"))
        mirror.call("execution_write_file", JSONObject().put("path", "probe/build/classes/Main.class").put("content", "cache"))
        mirror.call("execution_write_file", JSONObject().put("path", "retained/.gradle/cache.bin").put("content", "keep"))
        mirror.call("execution_write_file", JSONObject().put("path", "retained/source.py").put("content", "keep"))
        val digest = mirror.call("manifest").getString("manifestDigest")
        val page = JSONObject().put("deleted", JSONArray().put("probe/main.py").put("probe"))
        val result = mirror.call("sync", JSONObject().put("expectedRemoteDigest", digest)
            .put("planSources", JSONArray().put(0)).put("planPageCount", 1),
            listOf({ java.io.ByteArrayInputStream(page.toString().toByteArray()) }))
        check(result.optBoolean("ok"), "discard must delete a source directory even with ignored caches")
        check(!mirror.call("execution_file_info", JSONObject().put("path", "probe")).getBoolean("exists"))
        check(mirror.call("execution_read_file", JSONObject().put("path", "retained/.gradle/cache.bin")).getString("text") == "keep",
            "caches outside the deleted subtree must survive discard")
        // Unplanned visible children cannot be removed by a directory deletion.
        val unsafePage = JSONObject().put("deleted", JSONArray().put("retained"))
        val failure = runCatching {
            mirror.call("sync", JSONObject().put("expectedRemoteDigest", mirror.call("manifest").getString("manifestDigest"))
                .put("planSources", JSONArray().put(0)).put("planPageCount", 1),
                listOf({ java.io.ByteArrayInputStream(unsafePage.toString().toByteArray()) }))
        }.exceptionOrNull()
        check(failure?.message?.contains("RUNTIME_SYNC_DELETE_UNPLANNED_CHILD") == true,
            "reject unplanned source deletion and expose the underlying commit cause")
        check(mirror.call("execution_read_file", JSONObject().put("path", "retained/source.py")).getString("text") == "keep")
        check(mirror.call("execution_read_file", JSONObject().put("path", "retained/.gradle/cache.bin")).getString("text") == "keep")
    }

    private fun manifestPagingAndIgnorePolicy(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-manifest", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)

        // Write source files and ignored files (build/, .class, .apk)
        mirrorClient.call("execution_write_file", JSONObject().put("path", "src/Main.kt").put("content", "fun main() {}"))
        mirrorClient.call("execution_write_file", JSONObject().put("path", "build/classes/Main.class").put("content", "ignored bytecode"))
        mirrorClient.call("execution_write_file", JSONObject().put("path", "app.apk").put("content", "ignored apk"))

        // Call manifest_page with paging
        val page1 = mirrorClient.call("manifest_page", JSONObject().put("offset", 0).put("limit", 1))
        check(page1.optBoolean("ok"), "manifest_page must succeed")
        check(page1.getInt("totalEntries") == 2, "only src and src/Main.kt must be in manifest; build/ and apk must be ignored")
        val entries = page1.getJSONArray("entries")
        check(entries.length() == 1, "page size limit respected")
        val page2 = mirrorClient.call("manifest_page", JSONObject().put("offset", 1).put("limit", 10))
        check(page2.getJSONArray("entries").getJSONObject(0).getString("path") == "src/Main.kt", "unignored file present")
    }

    private fun executionFileOperations(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-ops", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)

        mirrorClient.call("execution_write_file", JSONObject().put("path", "dir/test.txt").put("content", "hello world"))
        val info = mirrorClient.call("execution_file_info", JSONObject().put("path", "dir/test.txt"))
        check(info.getBoolean("exists") && !info.getBoolean("directory") && info.getLong("size") == 11L)

        val read = mirrorClient.call("execution_read_file", JSONObject().put("path", "dir/test.txt"))
        check(read.getString("text") == "hello world")

        val list = mirrorClient.call("execution_list_dir", JSONObject().put("path", "dir"))
        check(list.getJSONArray("files").length() == 1)

        val search = mirrorClient.call("execution_search_files", JSONObject().put("query", "world"))
        check(search.getJSONArray("matches").length() == 1)

        val glob = mirrorClient.call("execution_glob", JSONObject().put("pattern", "**/*.txt"))
        check(glob.getJSONArray("matches").length() == 1)

        mirrorClient.call("execution_delete_file", JSONObject().put("path", "dir/test.txt"))
        val infoAfter = mirrorClient.call("execution_file_info", JSONObject().put("path", "dir/test.txt"))
        check(!infoAfter.getBoolean("exists"))
    }

    private fun companionLspReturnsUnavailableWithZeroTermuxCalls(root: File) {
        var termuxLspCalled = 0
        val companionClient = createInProcessClient(root)
        val compWorkspaceId = "333333333333333333333333"
        val compBinding = companionClient.openWorkspace("proj-lsp-comp", 1L, compWorkspaceId)

        // Save state to store with runtimeBinding = VEGA_COMPANION
        val context = TestContext(File(root, "host_files").apply { mkdirs() })
        val store = MirrorStateStore(context, "proj-lsp-comp")
        store.save(MirrorState.clean("proj-lsp-comp", 1L, "0".repeat(64)).copy(runtimeBinding = compBinding))

        val codeIntelManager = dev.wind.agent.intelligence.CodeIntelligenceManager(
            context = context,
            mirrorManager = dev.wind.agent.shell.mirror.ProjectMirrorManager(context),
            termuxLspFactory = {
                termuxLspCalled++
                error("Termux factory must not be called for Companion LSP")
            }
        )
        val sessionKey = dev.wind.agent.intelligence.lsp.LspSessionKey("root", "proj-lsp-comp", 1L, dev.wind.agent.intelligence.CodeLanguage.KOTLIN)
        val session = codeIntelManager.lspManager.getSession(sessionKey, "0".repeat(64))
        check(session is dev.wind.agent.intelligence.lsp.LspOutcome.Failure, "Companion LSP must return unavailable before P6.4")
        check(termuxLspCalled == 0, "Termux must not be called for Companion LSP")
    }

    private class TestContext(private val files: File) : ContextWrapper(null) {
        private val preferences = TestPreferences()
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = files
        override fun getExternalFilesDir(type: String?): File = files
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        override fun getPackageName(): String = "dev.wind.agent"
        override fun getApplicationContext(): Context = this
    }

    private class TestPreferences : SharedPreferences {
        private val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = Editor(values)

        private class Editor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor { values[key] = value; return this }
            override fun putStringSet(key: String, value: MutableSet<String>?): SharedPreferences.Editor { values[key] = value; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { values[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { values[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { values[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { values[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { values.remove(key); return this }
            override fun clear(): SharedPreferences.Editor { values.clear(); return this }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }
    private fun multiProviderZeroCrossRouting(root: File) {
        var termuxCalls = 0
        var companionCalls = 0

        val companionClient = createInProcessClient(root)
        val compWorkspaceId = "111111111111111111111111"
        val compBinding = companionClient.openWorkspace("proj-comp", 1L, compWorkspaceId)

        val companionRuntime = object : ExecutionRuntime by CompanionExecutionRuntime(companionClient) {
            override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
                override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?): RuntimeCommandResult {
                    companionCalls++
                    return RuntimeCommandResult(0, "comp", "", 10L, cleanupConfirmed = true)
                }
                override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
                override fun verifyBinding(binding: RuntimeBinding) = true
            }
        }

        val termuxRuntime = object : ExecutionRuntime {
            override val providerId = RuntimeProviderId.TERMUX
            override fun probe() = RuntimeProbeResult(RuntimeProviderId.TERMUX, RuntimeProbeState.READY, RuntimeCapabilities(true, true, true, 2, mirrorStreamProtocolVersion = 1))
            override fun bind(projectId: String, rootGeneration: Long, workspaceId: String) =
                RuntimeBinding(RuntimeProviderId.TERMUX, workspaceId, projectId, rootGeneration, "termux-inst")
            override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
                override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?): RuntimeCommandResult {
                    termuxCalls++
                    return RuntimeCommandResult(0, "termux", "", 10L, cleanupConfirmed = true)
                }
                override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
                override fun verifyBinding(binding: RuntimeBinding) = true
            }
            override fun capabilities(binding: RuntimeBinding) = RuntimeCapabilities(true, true, true, 2, mirrorStreamProtocolVersion = 1)
            override fun verifyBinding(binding: RuntimeBinding) = true
            override fun destroyCandidate(binding: RuntimeBinding) = true
        }

        val preflight = RuntimePreflight(linkedMapOf(
            RuntimeProviderId.TERMUX to termuxRuntime,
            RuntimeProviderId.VEGA_COMPANION to companionRuntime
        ))
        val registry = RuntimeRegistry(preflight) { RuntimePreference.AUTO }

        // Resolve Companion-bound project
        val boundComp = registry.resolveBoundRuntime("proj-comp", 1L, compWorkspaceId,
            MirrorState.clean("proj-comp", 1L, "0".repeat(64)).copy(runtimeBinding = compBinding))
        check(boundComp.binding.providerId == RuntimeProviderId.VEGA_COMPANION)
        boundComp.runtime.commandExecutor(boundComp.binding).execute(boundComp.binding, RuntimeCommandSpec("ls"))
        check(companionCalls == 1, "companionCalls must be 1")
        check(termuxCalls == 0, "Termux was invoked for Companion-bound project!")

        // Resolve Termux-bound project
        val termuxWorkspaceId = "222222222222222222222222"
        val termuxBinding = RuntimeBinding(RuntimeProviderId.TERMUX, termuxWorkspaceId, "proj-term", 1L, "termux-inst")
        val boundTerm = registry.resolveBoundRuntime("proj-term", 1L, termuxWorkspaceId,
            MirrorState.clean("proj-term", 1L, "0".repeat(64)).copy(runtimeBinding = termuxBinding))
        check(boundTerm.binding.providerId == RuntimeProviderId.TERMUX)
        boundTerm.runtime.commandExecutor(boundTerm.binding).execute(boundTerm.binding, RuntimeCommandSpec("ls"))
        check(termuxCalls == 1, "termuxCalls must be 1")
        check(companionCalls == 1, "Companion calls increased during Termux execution!")
    }

    /**
     * The page budget is a byte budget as well as a count budget.
     *
     * A page of 2048 entries is not automatically small: long paths make the
     * serialized reply exceed what a Binder reply can carry, and the failure
     * mode of that is a truncated or dropped reply rather than a clear error.
     *
     * The count limit and the byte limit are checked on every platform. Making
     * the byte limit actually bind needs paths of several hundred bytes, which
     * the Windows File API cannot create (MAX_PATH, plus a 255-character
     * component cap), so that fixture is built only where the filesystem allows
     * it; the count-limit half is asserted everywhere, and the fixtures are the
     * same shape either way.
     */
    private fun manifestPageHonorsByteBudget(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-budget", 1L, workspaceId)
        val projectDir = File(File(File(root, "runtime-root"), "workspaces"), "$workspaceId/project")
        val pathBytesBounded = File.separatorChar == '/'
        // Construct multi-segment paths in a single leaf directory so the average entry
        // length stays ~550 bytes without being diluted by thousands of short directory entries.
        // At ~1920 entries, the 1 MiB byte budget triggers well before the 2048 entry limit.
        val seg = "p".repeat(if (pathBytesBounded) 75 else 20)
        val count = if (pathBytesBounded) 2200 else 240
        var created = 0
        val leafDir = if (pathBytesBounded) {
            File(projectDir, "$seg/$seg/$seg/$seg/$seg")
        } else {
            File(projectDir, "leaf")
        }
        leafDir.mkdirs()
        for (i in 0 until count) {
            val file = File(leafDir, "f" + i.toString().padStart(6, '0') + ".txt")
            try {
                file.writeText("payload-$i")
                created++
            } catch (_: java.io.IOException) {
                // The platform refused the path, so the fixture cannot reach the
                // byte budget; the assertions below still hold for what was made.
                break
            }
        }
        val mirrorClient = CompanionMirrorClient(binding, client)
        var offset = 0
        var total = -1
        var pages = 0
        val seen = mutableSetOf<String>()
        while (true) {
            val page = mirrorClient.call("manifest_page", JSONObject().put("offset", offset).put("limit", 2048))
            val entries = page.getJSONArray("entries")
            check(entries.length() <= 2048, "page must not exceed the entry limit")
            val pageBytes = page.optInt("pageBytes", -1)
            check(pageBytes in 0..1024 * 1024, "page bytes must stay within the 1 MiB budget (got $pageBytes)")
            check(entries.length() > 0, "a non-empty mirror must produce a non-empty page")
            total = if (total < 0) page.getInt("totalEntries") else total
            check(page.getInt("totalEntries") == total, "totalEntries must be stable across pages")
            for (i in 0 until entries.length()) {
                check(seen.add(entries.getJSONObject(i).getString("path")), "paging must not repeat a path")
            }
            pages++
            val next = page.optInt("nextOffset", -1)
            if (next < 0) break
            check(next > offset) { "paging must make progress" }
            offset = next
            check(pages < 200) { "paging did not terminate" }
        }
        check(seen.size == total, "every entry must appear exactly once across the pages")
        check(total >= created, "the manifest must describe every file the fixture created")
        if (pathBytesBounded && created >= 1800) {
            check(pages > 1, "long paths must split a page before the entry limit is reached")
            val firstPage = mirrorClient.call("manifest_page", JSONObject().put("offset", 0).put("limit", 2048))
            check(firstPage.getJSONArray("entries").length() < 2048, "first page must be bounded by bytes, not entry count")
            check(firstPage.optInt("pageBytes", -1) in 1..1024 * 1024, "page bytes must stay within 1 MiB budget")
        }
    }

    /**
     * A manifest hash must cover the whole file.
     *
     * The previous implementation hashed the first 16 MiB and recorded the full
     * length, so two files sharing a 16 MiB prefix were indistinguishable - and
     * the mirror "agreed" with itself while the content differed.
     */
    private fun largeFileManifestHashesWholePayload(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-hash", 1L, workspaceId)
        val projectDir = File(File(File(root, "runtime-root"), "workspaces"), "$workspaceId/project")
        projectDir.mkdirs()
        val prefix = ByteArray(16 * 1024 * 1024) { (it and 0x7F).toByte() }
        val first = prefix + "AAA".toByteArray()
        val second = prefix + "BBB".toByteArray()
        File(projectDir, "first.bin").writeBytes(first)
        File(projectDir, "second.bin").writeBytes(second)

        val mirrorClient = CompanionMirrorClient(binding, client)
        val page = mirrorClient.call("manifest_page", JSONObject().put("offset", 0).put("limit", 2048))
        val entries = page.getJSONArray("entries")
        var firstHash: String? = null
        var secondHash: String? = null
        for (i in 0 until entries.length()) {
            val item = entries.getJSONObject(i)
            when (item.getString("path")) {
                "first.bin" -> {
                    check(item.getLong("size") == first.size.toLong()) { "size must be the real size" }
                    firstHash = item.getString("sha256")
                }
                "second.bin" -> secondHash = item.getString("sha256")
            }
        }
        val a = firstHash ?: error("first.bin must be in the manifest")
        val b = secondHash ?: error("second.bin must be in the manifest")
        check(a != b, "files past the first 16 MiB must not hash identically")
        val expected = MessageDigest.getInstance("SHA-256").digest(first).joinToString("") { "%02x".format(it) }
        check(a == expected) { "the manifest hash must cover the entire payload" }
    }

    /**
     * The companion has no ignore policy of its own.
     *
     * Every name below is ignored by nothing in particular: if a private list
     * survives anywhere on the companion side, these files vanish from its
     * manifest while remaining in the host's, and the two digests that are
     * supposed to describe one project stop agreeing.
     */
    private fun ignorePolicyHasNoHiddenCompanionRules(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-policy", 1L, workspaceId)
        val projectDir = File(File(File(root, "runtime-root"), "workspaces"), "$workspaceId/project")
        projectDir.mkdirs()
        val hidden = listOf("foo.dex", "important.bak", "snapshot.new", "notes.idsig", "entry.vega")
        for (name in hidden) File(projectDir, name).writeText("content of $name")
        val mirrorClient = CompanionMirrorClient(binding, client)
        val page = mirrorClient.call("manifest_page", JSONObject().put("offset", 0).put("limit", 2048))
        val entries = page.getJSONArray("entries")
        val paths = (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }.toSet()
        for (name in hidden) {
            check(name in paths) { "the host policy does not ignore $name, so the companion must not either" }
        }
    }

    /**
     * A symlink is not part of the mirror.
     *
     * The P3 helper opens every path with O_NOFOLLOW, so a link is neither
     * listed nor followed. A scanner that answers "what is at the end of this
     * link" would enumerate a subtree the mirror does not own.
     */
    private fun symlinkNeverEntersManifest(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-link", 1L, workspaceId)
        val projectDir = File(File(File(root, "runtime-root"), "workspaces"), "$workspaceId/project")
        val outside = File(root, "outside")
        outside.mkdirs()
        File(outside, "secret.txt").writeText("must not be enumerated")
        File(projectDir, "real.txt").writeText("visible")
        val linkCreated = try {
            java.nio.file.Files.createSymbolicLink(File(projectDir, "link").toPath(), outside.toPath())
            true
        } catch (_: Exception) {
            false
        }
        val dirLinkCreated = try {
            java.nio.file.Files.createSymbolicLink(
                File(projectDir, "dir-link").toPath(), File(outside, "secret.txt").toPath())
            true
        } catch (_: Exception) {
            false
        }
        val mirrorClient = CompanionMirrorClient(binding, client)
        if (linkCreated || dirLinkCreated) {
            var manifestRejected = false
            try {
                mirrorClient.call("manifest_page", JSONObject().put("offset", 0).put("limit", 2048))
            } catch (e: Exception) {
                manifestRejected = e.message?.contains("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE") == true
            }
            check(manifestRejected, "manifest scan must fail closed on encountering a symlink")
        } else {
            val page = mirrorClient.call("manifest_page", JSONObject().put("offset", 0).put("limit", 2048))
            val entries = page.getJSONArray("entries")
            val paths = (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }.toSet()
            check("real.txt" in paths, "an ordinary file must still be enumerated")
        }

        var linkReadRejected = false
        try {
            mirrorClient.call("fetch_text", JSONObject().put("path", "link/secret.txt"))
        } catch (e: Exception) {
            linkReadRejected = true
        }
        check(linkReadRejected, "reading through a symlink must fail closed")

        if (linkCreated) {
            var listDirRejected = false
            try {
                mirrorClient.call("execution_list_dir", JSONObject().put("path", "link"))
            } catch (e: Exception) {
                listDirRejected = e.message?.contains("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE") == true
            }
            check(listDirRejected, "listing a symlinked directory must fail closed")
        }

        // Blocker F-02: internal symlinks must never be traversed to overwrite existing files via sync commit
        val victim = File(projectDir, "victim.txt").apply { writeText("original-victim") }
        val alias = File(projectDir, "alias.txt")
        val internalLinkCreated = try {
            java.nio.file.Files.createSymbolicLink(alias.toPath(), victim.toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (internalLinkCreated) {
            var syncCommitRejected = false
            try {
                syncFiles(mirrorClient, mirrorClient.call("manifest").getString("manifestDigest"), "alias.txt" to "intruder-payload")
            } catch (e: Exception) {
                syncCommitRejected = true
            }
            check(syncCommitRejected, "sync committing through an internal symlink must be rejected")
            check(victim.readText() == "original-victim", "victim file must never be overwritten via internal symlink")
        }

        // Blocker G-02: workspace/project itself being a symlink must fail closed
        val wsDir = File(File(root, "runtime-root"), "workspaces/$workspaceId")
        val testWsId = "777777777777777777777777"
        val testRootWs = File(File(root, "runtime-root"), "workspaces/$testWsId").apply { mkdirs() }
        File(testRootWs, ".vega").mkdirs()
        val symlinkProjectTarget = File(testRootWs, "project")
        val rootSymlinkCreated = try {
            java.nio.file.Files.createSymbolicLink(symlinkProjectTarget.toPath(), File(testRootWs, ".vega").toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (rootSymlinkCreated) {
            var rootSymlinkRejected = false
            try {
                client.openWorkspace("proj-root-symlink", 1L, testWsId)
            } catch (e: Exception) {
                rootSymlinkRejected = e.message?.contains("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY") == true
            }
            check(rootSymlinkRejected, "a symlinked project root must fail closed with MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY")
        }

        // Blocker H-01: deleteTree must not follow symlinks
        val stagingDir = File(wsDir, "staging-symlink-test").apply { mkdirs() }
        val secretDir = File(root, "precious-dir").apply { mkdirs() }
        File(secretDir, "keep-me.txt").writeText("important")
        val stagingLinkCreated = try {
            java.nio.file.Files.createSymbolicLink(File(stagingDir, "link-to-precious").toPath(), secretDir.toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (stagingLinkCreated) {
            dev.wind.agent.runtime.RuntimeWorkspace.deleteTree(stagingDir)
            check(File(secretDir, "keep-me.txt").exists(), "deleteTree must not follow symlinks into target directory")
            check(File(secretDir, "keep-me.txt").readText() == "important", "precious file must remain intact")
        }
    }

    /**
     * Blocker F-01: A sync session must be strictly bound to the project/workspace
     * that opened it, and cannot be reused, hijacked across bindings, or re-opened.
     */
    private fun syncSessionBindingIsolation(root: File) {
        val client = createInProcessClient(root)
        val bindingA = client.openWorkspace("proj-iso-a", 1L, "111111111111111111111111")
        val bindingB = client.openWorkspace("proj-iso-b", 1L, "222222222222222222222222")

        val clientA = CompanionMirrorClient(bindingA, client)
        val clientB = CompanionMirrorClient(bindingB, client)

        val digestA = clientA.call("manifest").getString("manifestDigest")
        val digestB = clientB.call("manifest").getString("manifestDigest")

        val syncId = "sync-iso-test-1"
        val policy = JSONObject()
            .put("ignoredSegments", WorkspaceIgnorePolicy.sortedSegmentsJson())
            .put("ignoredSuffixes", WorkspaceIgnorePolicy.sortedSuffixesJson())

        // 1. Begin on A
        client.syncBegin(bindingA, syncId, digestA, policy, 1, 4)

        // 2. Duplicate BEGIN with same syncId must fail closed (atomic claim)
        var dupRejected = false
        try {
            client.syncBegin(bindingA, syncId, digestA, policy, 1, 4)
        } catch (e: Exception) {
            dupRejected = e.message?.contains("RUNTIME_SYNC_ALREADY_OPEN") == true
        }
        check(dupRejected, "duplicate BEGIN with existing syncId must fail closed")

        // 3. Staging/committing with mismatched binding must fail closed
        var crossStagingRejected = false
        try {
            client.syncFile(bindingB, syncId, "foo.txt", { java.io.ByteArrayInputStream("test".toByteArray()) }, null)
        } catch (e: Exception) {
            crossStagingRejected = e.message?.contains("RUNTIME_SYNC_BINDING_MISMATCH") == true
        }
        check(crossStagingRejected, "staging a file with mismatched binding must fail closed")

        var crossCommitRejected = false
        try {
            client.syncCommit(bindingB, syncId)
        } catch (e: Exception) {
            crossCommitRejected = e.message?.contains("RUNTIME_SYNC_BINDING_MISMATCH") == true
        }
        check(crossCommitRejected, "committing with mismatched binding must fail closed")

        var crossAbortRejected = false
        try {
            client.syncAbort(bindingB, syncId)
        } catch (e: Exception) {
            crossAbortRejected = e.message?.contains("RUNTIME_SYNC_BINDING_MISMATCH") == true
        }
        check(crossAbortRejected, "aborting with mismatched binding must fail closed")

        // 4. Abort cleanly with legitimate owner
        client.syncAbort(bindingA, syncId)

        // 5. Blocker F-03: duplicate records in plan must be bounded by aggregate count, not set cardinality
        val syncIdBudget = "sync-iso-budget"
        client.syncBegin(bindingA, syncIdBudget, digestA, policy, 1, 4)
        val dupDeletes = JSONArray()
        for (i in 0 until 2000) dupDeletes.put("dup_path.txt")
        val deletePageBytes = JSONObject().put("deleted", dupDeletes).toString().toByteArray(Charsets.UTF_8)
        var budgetExceeded = false
        try {
            // 101 pages of 2000 duplicate deletes = 202,000 records > 200,000 MAX_FILES
            for (p in 0 until 101) {
                client.syncPlanPage(bindingA, syncIdBudget, deletePageBytes, null)
            }
        } catch (e: Exception) {
            budgetExceeded = e.message?.contains("RUNTIME_SYNC_FILE_LIMIT") == true
        }
        check(budgetExceeded, "aggregate plan records exceeding MAX_FILES must be rejected")
        client.syncAbort(bindingA, syncIdBudget)
    }

    /**
     * The first compare-and-set: a remote that moved since the plan was built.
     *
     * The sync must be refused *before* anything is written, so the mirror the
     * plan no longer describes is left exactly as it is.
     */
    private fun staleRemoteBeforeSyncHasZeroMutation(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-stale-begin", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)
        mirrorClient.call("execution_write_file", JSONObject().put("path", "base.txt").put("content", "original"))

        val staleDigest = mirrorClient.call("manifest").getString("manifestDigest")
        // Somebody else changes the mirror after the host planned against it.
        mirrorClient.call("execution_write_file", JSONObject().put("path", "intruder.txt").put("content", "external"))
        val beforeDigest = mirrorClient.call("manifest").getString("manifestDigest")
        check(beforeDigest != staleDigest, "the fixture must actually move the mirror")

        val planPage = JSONObject().put("entries", org.json.JSONArray().put(
            JSONObject().put("path", "new.txt").put("directory", false)
                .put("size", 3L).put("sha256", sha256Of("new")).put("source", 1)))
        var rejected = false
        try {
            mirrorClient.call("sync", JSONObject()
                .put("planSources", org.json.JSONArray().put(0))
                .put("planPageCount", 1)
                .put("expectedRemoteDigest", staleDigest)
                .put("plannedFiles", 1)
                .put("plannedBytes", 3L),
                listOf(
                    { java.io.ByteArrayInputStream(planPage.toString().toByteArray()) },
                    { java.io.ByteArrayInputStream("new".toByteArray()) }
                ))
        } catch (e: Exception) {
            rejected = true
        }
        check(rejected, "a stale remote digest must be rejected at begin")
        val afterDigest = mirrorClient.call("manifest").getString("manifestDigest")
        check(afterDigest == beforeDigest, "a rejected sync must not change the mirror at all")
        val info = mirrorClient.call("execution_file_info", JSONObject().put("path", "new.txt"))
        check(!info.getBoolean("exists"), "no planned file may be installed")
    }

    /**
     * The second compare-and-set: the remote moves during staging.
     *
     * This is the case an in-place sync cannot survive. The plan is valid, the
     * payload arrives correctly, and only the commit-time re-read shows that the
     * tree it was planned against no longer exists.
     */
    private fun staleRemoteDuringStagingHasZeroSyncMutation(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-stale-commit", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)
        mirrorClient.call("execution_write_file", JSONObject().put("path", "base.txt").put("content", "original"))

        val plannedDigest = mirrorClient.call("manifest").getString("manifestDigest")
        val payload = ByteArray(8 * 1024 * 1024) { (it and 0xFF).toByte() }
        val payloadHash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val planPage = JSONObject().put("entries", org.json.JSONArray().put(
            JSONObject().put("path", "big.bin").put("directory", false)
                .put("size", payload.size.toLong()).put("sha256", payloadHash).put("source", 1)))
        val before = mirrorClient.call("manifest").getString("manifestDigest")

        var rejected = false
        var rejectionMessage = ""
        try {
            mirrorClient.call("sync", JSONObject()
                .put("planSources", org.json.JSONArray().put(0))
                .put("planPageCount", 1)
                .put("expectedRemoteDigest", plannedDigest)
                .put("plannedFiles", 1)
                .put("plannedBytes", payload.size.toLong()),
                listOf(
                    { java.io.ByteArrayInputStream(planPage.toString().toByteArray()) },
                    {
                        // The mirror changes while the payload is in flight.
                        mirrorClient.call("execution_write_file",
                            JSONObject().put("path", "intruder.txt").put("content", "external"))
                        java.io.ByteArrayInputStream(payload)
                    }
                ))
        } catch (e: Exception) {
            rejected = true
            rejectionMessage = e.message ?: ""
        }
        check(rejected, "a remote that moved during staging must be rejected at commit")
        check(rejectionMessage.contains("STALE_REMOTE_BEFORE_COMMIT")) {
            "the rejection must name the commit-time check, got: $rejectionMessage"
        }
        val info = mirrorClient.call("execution_file_info", JSONObject().put("path", "big.bin"))
        check(!info.getBoolean("exists"), "the staged payload must never be installed")
        // The fixture's own concurrent write is the only difference the mirror
        // is allowed to show. Removing it must restore the exact original tree,
        // which is what "the rejected sync changed nothing" means when a second
        // writer is in the picture.
        mirrorClient.call("execution_delete_file", JSONObject().put("path", "intruder.txt"))
        val restored = mirrorClient.call("manifest").getString("manifestDigest")
        check(restored == before) { "a rejected sync must leave no trace of its own in the mirror" }
        check(mirrorClient.call("execution_read_file", JSONObject().put("path", "base.txt"))
            .getString("text") == "original") { "the rejection disturbed an existing file" }
    }

    /**
     * Every payload is checked against the plan's own promises.
     *
     * The companion recomputes size and SHA-256 from the bytes it actually
     * received, so a short file, a corrupted payload, and an early end-of-stream
     * are all refusals rather than partially applied mirrors.
     */
    private fun streamRejectsWrongSizeShaAndEarlyEof(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-stream", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)
        mirrorClient.call("execution_write_file", JSONObject().put("path", "base.txt").put("content", "original"))
        val baseDigest = mirrorClient.call("manifest").getString("manifestDigest")

        fun attempt(name: String, claimedSize: Long, claimedSha: String, payload: ByteArray): String {
            val page = JSONObject().put("entries", org.json.JSONArray().put(
                JSONObject().put("path", name).put("directory", false)
                    .put("size", claimedSize).put("sha256", claimedSha).put("source", 1)))
            return try {
                mirrorClient.call("sync", JSONObject()
                    .put("planSources", org.json.JSONArray().put(0))
                    .put("planPageCount", 1)
                    .put("expectedRemoteDigest", baseDigest)
                    .put("plannedFiles", 1)
                    .put("plannedBytes", claimedSize),
                    listOf(
                        { java.io.ByteArrayInputStream(page.toString().toByteArray()) },
                        { java.io.ByteArrayInputStream(payload) }
                    ))
                "ACCEPTED"
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }

        val body = "the quick brown fox".toByteArray()
        val goodHash = sha256Of(String(body))
        check(attempt("wrong-size.bin", body.size + 5L, goodHash, body) != "ACCEPTED") {
            "a payload shorter than the plan promised must be rejected"
        }
        check(attempt("wrong-hash.bin", body.size.toLong(), "0".repeat(64), body) != "ACCEPTED") {
            "a payload whose hash differs from the plan must be rejected"
        }
        var earlyEof = false
        try {
            mirrorClient.call("sync", JSONObject()
                .put("planSources", org.json.JSONArray().put(0))
                .put("planPageCount", 1)
                .put("expectedRemoteDigest", baseDigest)
                .put("plannedFiles", 1)
                .put("plannedBytes", body.size.toLong()),
                listOf(
                    { java.io.ByteArrayInputStream(JSONObject().put("entries", org.json.JSONArray().put(
                        JSONObject().put("path", "early.bin").put("directory", false)
                            .put("size", body.size.toLong()).put("sha256", goodHash).put("source", 1)))
                        .toString().toByteArray()) },
                    { java.io.ByteArrayInputStream(ByteArray(0)) }
                ))
        } catch (e: Exception) {
            earlyEof = true
        }
        check(earlyEof, "a source that ends early must be rejected")

        check(mirrorClient.call("manifest").getString("manifestDigest") == baseDigest) {
            "no rejected payload may leave a trace in the mirror"
        }
        for (name in listOf("wrong-size.bin", "wrong-hash.bin", "early.bin")) {
            check(!mirrorClient.call("execution_file_info", JSONObject().put("path", name)).getBoolean("exists")) {
                "$name must not exist after its payload was rejected"
            }
        }
    }

    /**
     * Cancellation mid-stream must never commit, and the abort path must be
     * safe to call when there is nothing left to abort.
     */
    private fun syncAbortIsIdempotent(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-abort", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)
        mirrorClient.call("execution_write_file", JSONObject().put("path", "base.txt").put("content", "original"))
        val baseDigest = mirrorClient.call("manifest").getString("manifestDigest")

        val cancelled = CancellationToken().apply { cancel() }
        var cancelledRejected = false
        try {
            mirrorClient.call("sync", JSONObject()
                .put("planSources", org.json.JSONArray().put(0))
                .put("planPageCount", 1)
                .put("expectedRemoteDigest", baseDigest)
                .put("plannedFiles", 1)
                .put("plannedBytes", 3L),
                listOf(
                    { java.io.ByteArrayInputStream(JSONObject().put("entries", org.json.JSONArray().put(
                        JSONObject().put("path", "cancelled.txt").put("directory", false)
                            .put("size", 3L).put("sha256", sha256Of("abc")).put("source", 1)))
                        .toString().toByteArray()) },
                    { java.io.ByteArrayInputStream("abc".toByteArray()) }
                ),
                cancelled)
        } catch (e: Exception) {
            cancelledRejected = true
        }
        check(cancelledRejected, "a cancelled sync must not report success")
        check(!mirrorClient.call("execution_file_info", JSONObject().put("path", "cancelled.txt")).getBoolean("exists"),
            "a cancelled sync must not install its payload")
        check(mirrorClient.call("manifest").getString("manifestDigest") == baseDigest,
            "a cancelled sync must not change the mirror")

        // Aborting a sync that does not exist is a success, not an error: the
        // host calls it from failure paths where the previous failure may well
        // have been the abort itself.
        val unknown = client.syncAbort(binding, "sync-does-not-exist")
        check(unknown.optBoolean("ok"), "aborting an unknown sync must succeed")
        val workspace = File(File(File(root, "runtime-root"), "workspaces"), workspaceId)
        val staging = File(File(workspace, ".vega"), "sync/sync-does-not-exist")
        check(!staging.exists(), "abort must not leave a staging directory behind")
    }

    /**
     * Authority resolution must never reach Termux.
     *
     * The Termux factory is injected so that reaching it is not "an unexpected
     * branch" but a test failure. A project whose state is missing, corrupt, or
     * bound to a provider the caller cannot establish must answer UNAVAILABLE and
     * stop there.
     */
    private fun authorityFailureNeverConstructsTermux(root: File) {
        var termuxFactoryCalls = 0
        val context = TestContext(File(root, "host").apply { mkdirs() })
        val store = ProjectStore.get(context)
        val project = VegaProject(
            id = "proj-auth",
            name = "Authority",
            primaryMountId = WorkspaceIdentity.fromUri("content://test/auth"),
            mounts = listOf(WorkspaceMount(
                id = WorkspaceIdentity.fromUri("content://test/auth"),
                alias = "src",
                treeUri = "content://test/auth",
                displayName = "src",
                mode = MountMode.READ_WRITE,
                executionPath = "src",
                isPrimary = true
            )),
            generation = 3L
        )
        store.saveProject(project)
        store.setActiveProject(project.id)

        val mirror = object : dev.wind.agent.shell.mirror.CodeIntelligenceMirror {
            override fun observeForCodeIntelligence(router: WorkspaceRouter, token: CancellationToken) =
                error("not used")
            override fun markUnexpectedMirrorMutation(reason: String) {}
            override fun currentMirrorDigest(token: CancellationToken?) = "0".repeat(64)
            override fun getMirrorManifest(token: CancellationToken?) =
                dev.wind.agent.shell.mirror.MirrorManifestSummary("0".repeat(64), emptyMap())
            override fun readMirrorText(executionRelativePath: String, token: CancellationToken?): String? = null
            override fun requireRuntimeBindingForCodeIntelligence(projectId: String, rootGeneration: Long):
                RuntimeBinding = throw IllegalStateException("MIRROR_NOT_PREPARED")
        }
        val manager = dev.wind.agent.intelligence.CodeIntelligenceManager(
            context = context,
            mirrorManager = mirror,
            termuxLspFactory = {
                termuxFactoryCalls++
                error("TERMUX_FACTORY_MUST_NOT_BE_CALLED")
            }
        )
        val sessionKey = dev.wind.agent.intelligence.lsp.LspSessionKey(
            "root", project.id, project.generation, dev.wind.agent.intelligence.CodeLanguage.KOTLIN)
        val session = manager.lspManager.getSession(sessionKey, "0".repeat(64))
        check(session is dev.wind.agent.intelligence.lsp.LspOutcome.Failure,
            "an unresolvable authority must report unavailable")
        check((session as dev.wind.agent.intelligence.lsp.LspOutcome.Failure).code ==
            dev.wind.agent.intelligence.lsp.LspFailureCode.UNAVAILABLE,
            "the failure must be UNAVAILABLE, not a Termux attempt")
        check(termuxFactoryCalls == 0, "Termux must not be constructed without authority")
    }

    private fun pathEscapeFailsClosed(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-escape", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)

        var escapeRejected = false
        try {
            mirrorClient.call("fetch_text", JSONObject().put("path", "../../escape.txt"))
        } catch (e: Exception) {
            escapeRejected = e.message?.contains("RUNTIME_PATH_ESCAPE") == true ||
                e.message?.contains("FILE_NOT_FOUND") == true
        }
        check(escapeRejected, "path traversal was not rejected")
    }

    /**
     * The bounded-streaming read path, exercised without a Binder.
     *
     * The name says in-process on purpose: this is the production client and the
     * production companion session, but the descriptor is replaced by a stream,
     * so it proves the size/hash/cancellation rules rather than the kernel's
     * descriptor semantics. The cross-APK descriptor path is device acceptance.
     */
    private fun largeBinaryStreamIntegrityInProcess(root: File) {
        val client = createInProcessClient(root)
        val workspaceId = "0123456789abcdef01234567"
        val binding = client.openWorkspace("proj-bin", 1L, workspaceId)
        val mirrorClient = CompanionMirrorClient(binding, client)

        // Generate 256 KiB binary data
        val randomBytes = ByteArray(256 * 1024)
        for (i in randomBytes.indices) {
            randomBytes[i] = (i and 0xFF).toByte()
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(randomBytes).joinToString("") { "%02x".format(it) }

        // Write binary file into workspace
        val projectDir = File(File(File(root, "runtime-root"), "workspaces"), "$workspaceId/project")
        val binFile = File(projectDir, "binary_data.bin")
        binFile.parentFile.mkdirs()
        binFile.writeBytes(randomBytes)

        // 1. Download valid binary file with expected size and hash
        val downloaded = mirrorClient.downloadFile("binary_data.bin", randomBytes.size.toLong(), sha)
        check(downloaded.contentEquals(randomBytes), "downloaded binary bytes must match original")

        // 2. Hash mismatch must fail closed
        var hashFailed = false
        try {
            mirrorClient.downloadFile("binary_data.bin", randomBytes.size.toLong(), "0".repeat(64))
        } catch (e: Exception) {
            hashFailed = e.message?.contains("MIRROR_FILE_HASH_MISMATCH") == true ||
                e.message?.contains("HASH") == true
        }
        check(hashFailed, "hash mismatch must fail-closed")

        // 3. Truncation must fail closed
        var truncatedFailed = false
        try {
            mirrorClient.downloadFile("binary_data.bin", (randomBytes.size - 100).toLong(), sha)
        } catch (e: Exception) {
            truncatedFailed = true
        }
        check(truncatedFailed, "truncated file size must fail-closed")

        // 4. Cancellation mid-stream must fail
        val cancelledToken = CancellationToken().apply { cancel() }
        var cancelFailed = false
        try {
            mirrorClient.downloadFile("binary_data.bin", randomBytes.size.toLong(), sha, cancelledToken)
        } catch (e: Exception) {
            cancelFailed = e is CancellationToken.CancelledException || e.message?.contains("cancel", ignoreCase = true) == true
        }
        check(cancelFailed, "cancelled download must fail")
    }

    private fun productionProjectMirrorManagerE2EAndFailClosed(root: File) {
        var termuxCalls = 0
        var companionCalls = 0

        val companionClient = createInProcessClient(File(root, "companion"))

        val companionRuntime = object : ExecutionRuntime by CompanionExecutionRuntime(companionClient) {
            override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
                override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?): RuntimeCommandResult {
                    companionCalls++
                    return RuntimeCommandResult(0, "e2e-ok", "", 10L, cleanupConfirmed = true)
                }
                override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
                override fun verifyBinding(binding: RuntimeBinding) = true
            }
        }

        val termuxRuntime = object : ExecutionRuntime {
            override val providerId = RuntimeProviderId.TERMUX
            override fun probe() = RuntimeProbeResult(RuntimeProviderId.TERMUX, RuntimeProbeState.READY, RuntimeCapabilities(true, true, true, 2, mirrorStreamProtocolVersion = 1))
            override fun bind(projectId: String, rootGeneration: Long, workspaceId: String) =
                RuntimeBinding(RuntimeProviderId.TERMUX, workspaceId, projectId, rootGeneration, "termux-inst")
            override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
                override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?): RuntimeCommandResult {
                    termuxCalls++
                    return RuntimeCommandResult(0, "termux", "", 10L, cleanupConfirmed = true)
                }
                override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
                override fun verifyBinding(binding: RuntimeBinding) = true
            }
            override fun capabilities(binding: RuntimeBinding) = RuntimeCapabilities(true, true, true, 2, mirrorStreamProtocolVersion = 1)
            override fun verifyBinding(binding: RuntimeBinding) = true
            override fun destroyCandidate(binding: RuntimeBinding) = true
        }

        val preflight = RuntimePreflight(linkedMapOf(
            RuntimeProviderId.TERMUX to termuxRuntime,
            RuntimeProviderId.VEGA_COMPANION to companionRuntime
        ))
        val context = TestContext(File(root, "host").apply { mkdirs() })
        // Legacy Companion integration explicitly uses AUTO. SELF_CONTAINED
        // now requires Linux and must not silently fall back to this fixture.
        val registry = RuntimeRegistry(preflight) { RuntimePreference.AUTO }

        val fakeFs = FakeWorkspaceFs("canonical")
        fakeFs.writeText("src/Main.kt", "fun main() {}")
        // Production ProjectStore generates UUIDs, not the hashed IDs older fixtures used.
        val mountId = "76957930-ebe4-4789-b665-3672d2a72238"
        val mount = WorkspaceMount(
            id = mountId,
            alias = "src",
            treeUri = "content://test/canonical",
            displayName = "src",
            mode = MountMode.READ_WRITE,
            executionPath = "src",
            isPrimary = true
        )
        val proj = VegaProject(
            id = "proj-e2e",
            name = "Test E2E",
            primaryMountId = mountId,
            mounts = listOf(mount),
            generation = 1L
        )
        val projectStore = ProjectStore.get(context)
        projectStore.saveProject(proj)
        projectStore.setActiveProject("proj-e2e")
        val router = FixedRouter(context, projectStore, fakeFs)
        val token = CancellationToken()

        val manager = ProjectMirrorManager(
            context = context,
            projectStore = projectStore,
            runtimeRegistry = registry,
            mirrorClientFactory = { _, binding ->
                if (binding.providerId == RuntimeProviderId.VEGA_COMPANION) {
                    CompanionMirrorClient(binding, companionClient)
                } else {
                    termuxCalls++
                    throw IllegalStateException("Termux factory should not be called!")
                }
            }
        )

        // 1. First execution on CLEAN/unbound project: must select Companion and persist binding
        val execResult = manager.execute("task-1", ShellRequest("echo", listOf("hi")), router, token)
        check(companionCalls == 1, "companionCalls must be 1")
        check(termuxCalls == 0, "Termux must not be called")
        val store = MirrorStateStore(context, "proj-e2e")
        val persisted = store.loadBinding()
        check(persisted != null && persisted.providerId == RuntimeProviderId.VEGA_COMPANION,
            "runtimeBinding must be durably persisted to store on first call")

        // 2. Change preference to AUTO / TERMUX: must STAY on Companion binding for same generation
        val regTermux = RuntimeRegistry(preflight) { RuntimePreference.TERMUX }
        val manager2 = ProjectMirrorManager(context, projectStore, regTermux, mirrorClientFactory = { _, b ->
            if (b.providerId == RuntimeProviderId.VEGA_COMPANION) CompanionMirrorClient(b, companionClient) else { termuxCalls++; error("Wrong") }
        })
        manager2.execute("task-2", ShellRequest("echo", listOf("stay")), router, token)
        check(companionCalls == 2, "companionCalls must be 2")
        check(termuxCalls == 0, "Termux must still not be called after preference change")

        // 3. Companion unavailable (mismatched instance) -> fail closed, 0 Termux calls
        val brokenCompanionRuntime = object : ExecutionRuntime by companionRuntime {
            override fun verifyBinding(binding: RuntimeBinding) = false
        }
        val brokenPreflight = RuntimePreflight(linkedMapOf(
            RuntimeProviderId.TERMUX to termuxRuntime,
            RuntimeProviderId.VEGA_COMPANION to brokenCompanionRuntime
        ))
        val brokenRegistry = RuntimeRegistry(brokenPreflight) { RuntimePreference.SELF_CONTAINED }
        val manager3 = ProjectMirrorManager(context, projectStore, brokenRegistry, mirrorClientFactory = { _, b -> CompanionMirrorClient(b, companionClient) })
        var failClosed = false
        try {
            manager3.execute("task-3", ShellRequest("echo", listOf("fail")), router, token)
        } catch (e: Exception) {
            failClosed = true
        }
        check(failClosed, "must fail closed when Companion verification fails")
        check(termuxCalls == 0, "Termux must NOT be called on Companion failure (no fallback)")
    }

    /** Real production UUID shape and Linux protocol; guest command executor is scripted, not a device test. */
    private fun linuxUuidProjectExecutionAndCapabilities(root: File) {
        val projectId = "f0bf18f8-50b3-4365-b999-8178fe954eca"
        val mountId = "76957930-ebe4-4789-b665-3672d2a72238"
        val context = TestContext(File(root, "host").apply { mkdirs() })
        val store = ProjectStore.get(context)
        val mount = WorkspaceMount(mountId, "main", "content://test/linux", "Main", MountMode.READ_WRITE, "main", true)
        val project = VegaProject(projectId, "UUID Linux", mountId, listOf(mount), 1L)
        store.saveProject(project)
        store.setActiveProject(projectId)
        val fs = FakeWorkspaceFs("uuid-canonical").apply {
            writeText("requirements.txt", "")
            writeText("selftest.py", "print('ok')")
        }
        val router = FixedRouter(context, store, fs)
        val client = createInProcessClient(File(root, "provider"), RuntimeProviderId.VEGA_LINUX)
        var linuxCalls = 0
        var commandExit = 0
        var commandTimedOut = false
        var termuxCalls = 0
        var preference = RuntimePreference.SELF_CONTAINED
        val linuxRuntime = object : ExecutionRuntime by CompanionExecutionRuntime(client) {
            override fun commandExecutor(binding: RuntimeBinding) = object : RuntimeCommandExecutor {
                override fun execute(binding: RuntimeBinding, command: RuntimeCommandSpec, token: CancellationToken?): RuntimeCommandResult {
                    check(binding.providerId == RuntimeProviderId.VEGA_LINUX)
                    check(command.workDir == "main", "primary mount must be the provider-relative working directory")
                    linuxCalls++
                    return RuntimeCommandResult(commandExit, "uuid-linux-ok", "", 1L, timedOut = commandTimedOut, cleanupConfirmed = true)
                }
                override fun cancel(binding: RuntimeBinding, executionHandle: String) = true
                override fun verifyBinding(binding: RuntimeBinding) = true
            }
        }
        val termux = object : ExecutionRuntime by linuxRuntime {
            override val providerId = RuntimeProviderId.TERMUX
            override fun probe(): RuntimeProbeResult { termuxCalls++; error("TERMUX_MUST_NOT_BE_PROBED") }
        }
        val registry = RuntimeRegistry(RuntimePreflight(mapOf(RuntimeProviderId.VEGA_LINUX to linuxRuntime, RuntimeProviderId.TERMUX to termux))) { preference }
        val manager = ProjectMirrorManager(context, store, registry, mirrorClientFactory = { _, binding ->
            check(binding.providerId == RuntimeProviderId.VEGA_LINUX)
            CompanionMirrorClient(binding, client)
        })
        val shell = dev.wind.agent.shell.ShellManager(context, manager)
        val stateStore = MirrorStateStore(context, projectId)
        val status = shell.detectCapabilities()
        check(status.contains("backend: VEGA_LINUX") && status.contains("preflight: READY"), "capabilities must reflect selected Linux provider")
        check(!status.contains("permission missing") && !status.contains("Mirror RPC requires python3"))
        check(stateStore.loadBinding() == null, "capability observation must not bind or prepare a workspace")
        preference = RuntimePreference.AUTO
        check(shell.detectCapabilities().contains("backend: VEGA_LINUX"))
        check(termuxCalls == 0, "AUTO status should stop probing once Linux is ready")
        preference = RuntimePreference.SELF_CONTAINED
        val scan = manager.scanCanonicalProject(router)
        check(scan.keys == setOf(mountId), "canonical mount map must retain its UUID key")
        check(scan[mountId]!!.second.workspaceId.matches(Regex("[0-9a-f]{24}")))
        check(dev.wind.agent.coding.ProjectDetector.detect(router).kind == dev.wind.agent.coding.ProjectKind.PYTHON)

        // Exercise the formerly crashing diagnostic mapper with a project UUID.
        val runtime = dev.wind.agent.coding.CodingTaskRuntime(object : dev.wind.agent.coding.CodingRuntimeHost {
            override fun mirrorDigest(): String? = null
            override fun now() = 1L
            override fun runCommand(command: dev.wind.agent.coding.ValidationCommand, token: CancellationToken) = error("not called")
        })
        val session = dev.wind.agent.coding.CodingSession(null, runtime, "uuid-task")
        val mapper = session.javaClass.getDeclaredMethod("configurePathMapper", WorkspaceRouter::class.java)
        mapper.isAccessible = true
        mapper.invoke(session, router)
        check(dev.wind.agent.coding.DiagnosticPathMapper.configured)
        dev.wind.agent.coding.DiagnosticPathMapper.reset()

        check(manager.tool("execution_prepare", JSONObject(), router, "uuid-task", CancellationToken()).startsWith("OK:"))
        val beforeInvalid = manager.currentMirrorDigest(router)
        for (badTimeout in listOf(0L, -1L, 3_600_001L, Long.MAX_VALUE)) {
            val failure = runCatching {
                manager.execute("uuid-task", ShellRequest("echo", listOf("ok"), timeoutMs = badTimeout), router, CancellationToken())
            }.exceptionOrNull()
            check(failure?.message?.contains("RUNTIME_TIMEOUT_INVALID") == true)
            check(manager.currentMirrorDigest(router) == beforeInvalid, "pre-dispatch timeout rejection must not break the mirror")
            check(linuxCalls == 0, "invalid timeout must not dispatch")
        }
        val binding = stateStore.loadBinding() ?: error("binding not persisted")
        check(binding.workspaceId == ProjectIdentity.remoteId(projectId) && binding.projectId == projectId)
        check(manager.detectValidationProject(router).kind == dev.wind.agent.coding.ProjectKind.PYTHON)
        val cleanObserved = manager.observeForCodeIntelligence(router)
        check(cleanObserved.phase == MirrorPhase.CLEAN, "clean mirror must observe as CLEAN")
        val cleanBinding = manager.requireRuntimeBindingForCodeIntelligence(projectId, binding.rootGeneration)
        check(cleanBinding == binding, "code intelligence must use the durable Linux binding")
        check(cleanBinding.providerId == RuntimeProviderId.VEGA_LINUX, "requireRuntimeBindingForCodeIntelligence must return VEGA_LINUX binding")
        check(cleanBinding.projectId == projectId && cleanBinding.rootGeneration == router.getActiveProject().generation)
        manager.tool("execution_write_file", JSONObject().put("path", "execution://report.txt").put("content", "handset diagnosis"), router, "uuid-task", CancellationToken())
        val pendingObserved = manager.observeForCodeIntelligence(router)
        check(pendingObserved.phase == MirrorPhase.PENDING, "pending mirror must observe as PENDING")
        check(pendingObserved.liveRemoteDigest != cleanObserved.liveRemoteDigest,
            "pending observation must see the edited source revision, not the old SAF baseline")
        val pendingBinding = manager.requireRuntimeBindingForCodeIntelligence(projectId, binding.rootGeneration)
        check(pendingBinding == cleanBinding, "requireRuntimeBindingForCodeIntelligence must match clean binding in PENDING")
        val reportText = manager.readMirrorText("main/report.txt")
        check(reportText == "handset diagnosis", "readMirrorText must read newly written content in PENDING")
        check(manager.hasOnlyDocumentationChanges(router), "plain report diff must be reviewable without a compiler")
        val reviewHost = object : dev.wind.agent.coding.CodingRuntimeHost {
            override fun mirrorDigest() = manager.currentMirrorDigest(router)
            override fun now() = 10L
            override fun detectProject(projectPath: String?) = manager.detectValidationProject(router, projectPath)
            override fun documentationOnly() = manager.hasOnlyDocumentationChanges(router)
            override fun runCommand(command: dev.wind.agent.coding.ValidationCommand, token: CancellationToken): dev.wind.agent.coding.ValidationRunResult {
                check(command.id == "documentation-diff", "document review must not launch a compiler")
                return dev.wind.agent.coding.ValidationRunResult(if (documentationOnly()) 0 else 1, "exact diff reviewed", "", 0L)
            }
        }
        val reviewRuntime = dev.wind.agent.coding.CodingTaskRuntime(reviewHost)
        reviewRuntime.start("review-report", projectId, 1L)
        reviewRuntime.beforeTool("execution_write_file")
        reviewRuntime.afterTool("execution_write_file", true, true)
        val reviewSession = dev.wind.agent.coding.CodingSession(null, reviewRuntime, "review-report")
        check(reviewSession.allowApply("report-diff") == null, "authorized document saving must not need a compiler")
        check(!reviewRuntime.verificationValid(), "allowApply must not invent validation or run a documentation check")
        check(fs.stat("report.txt") == null, "review must not itself write canonical content")
        manager.tool("execution_delete_file", JSONObject().put("path", "execution://report.txt"), router, "uuid-task", CancellationToken())
        check(!manager.hasOnlyDocumentationChanges(router), "empty diff must not qualify for a document exemption")
        manager.tool("execution_write_file", JSONObject().put("path", "execution://pyproject.toml").put("content", "[tool.pytest.ini_options]\n"), router, "uuid-task", CancellationToken())
        check(manager.detectValidationProject(router).testFrameworkHints.contains("pytest"), "detection must read newly staged marker contents")
        check(fs.stat("pyproject.toml") == null, "marker detection must not read only canonical SAF")
        check(!manager.hasOnlyDocumentationChanges(router), "build configuration is not a document-only change")
        check(reviewSession.allowApply("mixed-diff") == null, "authorized saving must not depend on configuration verification")
        check(!reviewRuntime.verificationValid(), "saving a mixed diff must not invent verification")
        manager.tool("execution_delete_file", JSONObject().put("path", "execution://pyproject.toml"), router, "uuid-task", CancellationToken())
        preference = RuntimePreference.TERMUX
        check(shell.detectCapabilities().contains("backend: VEGA_LINUX"), "durable Linux authority must override changed preference")
        val version = manager.execute("uuid-task", ShellRequest("git", listOf("--version")), router, CancellationToken())
        check(version.exitCode == 0 && version.stdout == "uuid-linux-ok")
        val unchanged = manager.previewApply(version.diffSummary.orEmpty().substringAfter("Execution ID: ").substringBefore('\n'), router)
        check(unchanged.changedFiles == 0 && unchanged.diffFingerprint == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "mount container must not appear as a canonical apply change")
        check(manager.tool("execution_make_dir", JSONObject().put("path", "execution://demo_repo"), router, "uuid-task", CancellationToken()).contains("CHANGES PENDING"))
        check(manager.tool("execution_write_file", JSONObject().put("path", "execution://demo_repo/main.py").put("content", "print('script-ok')"), router, "uuid-task", CancellationToken()).contains("CHANGES PENDING"))
        val read = manager.tool("execution_read_file", JSONObject().put("path", "execution://demo_repo/main.py"), router, "uuid-task", CancellationToken())
        check(read.contains("script-ok"), "written script must be readable through mirror protocol")
        val script = manager.execute("uuid-task", ShellRequest("python3", listOf("demo_repo/main.py")), router, CancellationToken())
        check(script.exitCode == 0 && linuxCalls == 2)
        check(!manager.hasOnlyDocumentationChanges(router), "Python source requires actual checks")
        check(manager.previewApply(script.diffSummary.orEmpty().substringAfter("Execution ID: ").substringBefore('\n'), router).createdFiles == 1,
            "file count should include the new script and exclude directories")
        check(fs.stat("demo_repo/main.py") == null, "mirror writes must not bypass canonical apply")
        check(termuxCalls == 0, "Linux capability/execution path must not probe Termux")
        // Quality-check failures are separate from safe staging eligibility.
        commandExit = 1
        val failedCheck = manager.execute("uuid-task", ShellRequest("echo", listOf("check"), validationCheck = true), router, CancellationToken())
        val failedCheckId = failedCheck.diffSummary.substringAfter("Execution ID: ").substringBefore('\n')
        check(failedCheck.exitCode == 1, "a failed check must still be reported as failed")
        check(manager.previewApply(failedCheckId, router).createdFiles == 1,
            "unchanged failed harness check must not revoke an earlier safe staging result")
        val failedShell = manager.execute("uuid-task", ShellRequest("echo", listOf("failure")), router, CancellationToken())
        val failedShellId = failedShell.diffSummary.substringAfter("Execution ID: ").substringBefore('\n')
        var failedShellDenied = false
        try { manager.previewApply(failedShellId, router) }
        catch (error: IllegalStateException) { failedShellDenied = error.message?.contains("APPLY_NOT_ALLOWED") == true }
        check(failedShellDenied, "failed ordinary shell execution must remain ineligible")
        val failedAfterUnsafe = manager.execute("uuid-task", ShellRequest("echo", listOf("check"), validationCheck = true), router, CancellationToken())
        var priorUnsafeDenied = false
        try { manager.previewApply(failedAfterUnsafe.diffSummary.substringAfter("Execution ID: ").substringBefore('\n'), router) }
        catch (error: IllegalStateException) { priorUnsafeDenied = error.message?.contains("APPLY_NOT_ALLOWED") == true }
        check(priorUnsafeDenied, "failed check cannot manufacture eligibility from an unsafe prior execution")
        commandExit = 0
        manager.execute("uuid-task", ShellRequest("echo", listOf("safe")), router, CancellationToken())
        commandTimedOut = true
        val timedCheck = manager.execute("uuid-task", ShellRequest("echo", listOf("timed"), validationCheck = true), router, CancellationToken())
        var timedCheckDenied = false
        try { manager.previewApply(timedCheck.diffSummary.substringAfter("Execution ID: ").substringBefore('\n'), router) }
        catch (error: IllegalStateException) { timedCheckDenied = error.message?.contains("APPLY_NOT_ALLOWED") == true }
        check(timedCheckDenied, "timed-out validation cannot inherit staging eligibility")
        val wrong = binding.copy(rootGeneration = 2L)
        var mismatch = false
        try { registry.probeForProject(projectId, 1L, binding.workspaceId, MirrorState.clean(projectId, 2L, "0".repeat(64)).copy(runtimeBinding = wrong)) }
        catch (error: IllegalArgumentException) { mismatch = error.message == "RUNTIME_BINDING_IDENTITY_MISMATCH" }
        check(mismatch, "read-only status must reject an identity mismatch")

        // Directory sizes are metadata, not portable content identity.
        commandTimedOut = false
        val directoryA = ManifestEntry("folder", true, 4096L, 1000L, null)
        val directoryB = directoryA.copy(size = 0L, modified = 2000L)
        check(ManifestDigest.of(mapOf("folder" to directoryA)) == ManifestDigest.of(mapOf("folder" to directoryB)))
        fun loadedState(): MirrorState = MirrorState.fromJson(JSONObject(
            File(context.filesDir, "projects/$projectId/mirror_state.json").readText()), projectId)
        manager.tool("execution_write_file", JSONObject().put("path", "execution://recovery.txt")
            .put("content", "retained recovery data"), router, "uuid-task", CancellationToken())
        val previousId = loadedState().pending!!.executionId
        stateStore.save(loadedState().copy(phase = MirrorPhase.BROKEN))
        val prep = manager.tool("execution_prepare", JSONObject(), router, "new-task", CancellationToken())
        check(prep.contains("CHANGES_PENDING"), "prepare must preserve interrupted edits")
        check(loadedState().phase == MirrorPhase.PENDING)
        check(loadedState().pending!!.executionId != previousId, "adopting task must receive a fresh execution ID")
        check(loadedState().pending!!.rootTaskId == "new-task")
        check(loadedState().pending!!.eligible, "safe unchanged staging retains eligibility")
        check(manager.previewApply(loadedState().pending!!.executionId, router).createdFiles == 2)
        val recover = manager.tool("execution_recover", JSONObject(), router, "recover-task", CancellationToken())
        check(recover.contains("CHANGES_PENDING"))
        check(loadedState().pending!!.rootTaskId == "recover-task")
        commandExit = 1
        manager.execute("recover-task", ShellRequest("echo", listOf("unsafe")), router, CancellationToken())
        commandExit = 0
        stateStore.save(loadedState().copy(phase = MirrorPhase.BROKEN))
        manager.tool("execution_recover", JSONObject(), router, "unsafe-task", CancellationToken())
        check(!loadedState().pending!!.eligible, "recovery cannot manufacture failed-command eligibility")
        fs.writeText("requirements.txt", "concurrent canonical edit")
        val conflict = runCatching { manager.tool("execution_recover", JSONObject(), router, "conflict-task", CancellationToken()) }.exceptionOrNull()
        check(conflict?.message?.contains("RECOVERY_CONFLICT") == true)
        check(loadedState().pending != null, "conflict preserves pending metadata")
        fs.writeText("requirements.txt", "")
        manager.discard(loadedState().pending!!.executionId, router)
        check(loadedState().phase == MirrorPhase.CLEAN)
        stateStore.save(loadedState().copy(phase = MirrorPhase.BROKEN))
        val clean = manager.tool("execution_recover", JSONObject(), router, "clean-task", CancellationToken())
        check(clean.contains("Status: CLEAN"))
        check(loadedState().phase == MirrorPhase.CLEAN && loadedState().pending == null)
        check(fs.stat("selftest.py") != null)

        codeIntelligenceRegressionChecks(projectId, binding, manager, router, stateStore, client, ::loadedState)
    }

    private fun codeIntelligenceRegressionChecks(
        projectId: String,
        binding: RuntimeBinding,
        manager: ProjectMirrorManager,
        router: WorkspaceRouter,
        stateStore: MirrorStateStore,
        client: CompanionRuntimeClient,
        loadedState: () -> MirrorState
    ) {
        var projectMismatchRejected = false
        try {
            manager.requireRuntimeBindingForCodeIntelligence("wrong-project-id", binding.rootGeneration)
        } catch (e: IllegalStateException) {
            projectMismatchRejected = e.message == "CODE_INTEL_PROJECT_MISMATCH"
        }
        check(projectMismatchRejected, "requireRuntimeBindingForCodeIntelligence must reject mismatched projectId")

        var generationMismatchRejected = false
        try {
            manager.requireRuntimeBindingForCodeIntelligence(projectId, binding.rootGeneration + 1)
        } catch (e: IllegalStateException) {
            generationMismatchRejected = e.message == "CODE_INTEL_GENERATION_MISMATCH"
        }
        check(generationMismatchRejected, "requireRuntimeBindingForCodeIntelligence must reject mismatched generation")

        manager.tool("execution_write_file", JSONObject().put("path", "execution://code-intel-state.txt")
            .put("content", "valid pending fixture"), router, "code-intel-state", CancellationToken())
        val savedBaseline = loadedState()
        for (unstablePhase in listOf(MirrorPhase.MUTATING, MirrorPhase.APPLYING, MirrorPhase.BROKEN)) {
            stateStore.save(savedBaseline.copy(phase = unstablePhase))
            var observeRejected = false
            try {
                manager.observeForCodeIntelligence(router)
            } catch (e: IllegalStateException) {
                observeRejected = if (unstablePhase == MirrorPhase.BROKEN) {
                    e.message?.startsWith("MIRROR_BROKEN") == true
                } else {
                    e.message?.startsWith("MIRROR_NOT_STABLE") == true
                }
            }
            check(observeRejected, "observeForCodeIntelligence must reject phase $unstablePhase")

            var bindingRejected = false
            try {
                manager.requireRuntimeBindingForCodeIntelligence(projectId, binding.rootGeneration)
            } catch (e: IllegalStateException) {
                bindingRejected = e.message?.startsWith("MIRROR_NOT_STABLE") == true
            }
            check(bindingRejected, "requireRuntimeBindingForCodeIntelligence must reject phase $unstablePhase")
        }
        stateStore.save(savedBaseline)
        manager.discard(savedBaseline.pending!!.executionId, router)
        check(loadedState().phase == MirrorPhase.CLEAN, "phase checks preserve canonical source and allow discard")

        val providerMirror = CompanionMirrorClient(binding, client)
        providerMirror.call("execution_write_file", JSONObject().put("path", "main/unauthorized.txt").put("content", "external-mutation"))
        var unauthorizedRejected = false
        try {
            manager.observeForCodeIntelligence(router)
        } catch (e: IllegalStateException) {
            unauthorizedRejected = e.message?.contains("CODE_INTEL_MIRROR_INTEGRITY_LOST") == true
        }
        check(unauthorizedRejected, "observeForCodeIntelligence must reject when remote manifest changed externally")
        check(loadedState().phase == MirrorPhase.BROKEN, "external mutation must transition mirror to BROKEN")

        var brokenBindingRejected = false
        try {
            manager.requireRuntimeBindingForCodeIntelligence(projectId, binding.rootGeneration)
        } catch (e: IllegalStateException) {
            brokenBindingRejected = e.message?.startsWith("MIRROR_NOT_STABLE") == true
        }
        check(brokenBindingRejected, "requireRuntimeBindingForCodeIntelligence must reject BROKEN state")

        providerMirror.call("execution_delete_file", JSONObject().put("path", "main/unauthorized.txt"))
        manager.tool("execution_recover", JSONObject(), router, "recover-clean-after-tamper", CancellationToken())
        check(loadedState().phase == MirrorPhase.CLEAN, "mirror recovered to CLEAN after external mutation check")
    }

    private class FixedRouter(
        context: Context,
        store: ProjectStore,
        private val fs: WorkspaceFs
    ) : WorkspaceRouter(context, store) {
        override fun checkMountState(mount: WorkspaceMount): MountState = MountState.AVAILABLE
        override fun getFsForMount(mount: WorkspaceMount): WorkspaceFs = fs
    }
}
