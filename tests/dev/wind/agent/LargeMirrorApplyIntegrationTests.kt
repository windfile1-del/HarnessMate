package dev.wind.agent

import android.content.Context
import dev.wind.agent.capability.*
import dev.wind.agent.history.MutationJournal
import dev.wind.agent.project.*
import dev.wind.agent.runtime.*
import dev.wind.agent.shell.mirror.*
import dev.wind.agent.workspace.*
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

/** Exercises the real Linux mirror/export/apply/WAL path with disk files and a small JVM heap. */
object LargeMirrorApplyIntegrationTests {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("large-mirror-apply-").toFile()
        var assertions = 0
        fun verify(ok: Boolean, message: String) { assertions++; check(ok) { message } }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun payload(file: File, size: Long, seed: Int) {
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024) { ((it + seed) % 256).toByte() }
                var remaining = size
                while (remaining > 0) { val n = minOf(remaining, buffer.size.toLong()).toInt(); output.write(buffer, 0, n); remaining -= n }
            }
        }
        try {
            val contextType = Class.forName("dev.wind.agent.CompanionMirrorIntegrationTests\$TestContext")
            val context = contextType.getDeclaredConstructor(File::class.java).apply { isAccessible = true }
                .newInstance(File(root, "host").apply { mkdirs() }) as Context
            val fixture = CompanionMirrorIntegrationTests.javaClass.getDeclaredMethod("createInProcessClient",
                File::class.java, RuntimeProviderId::class.java).apply { isAccessible = true }
            val providerRoot = File(root, "provider")
            val client = fixture.invoke(CompanionMirrorIntegrationTests, providerRoot, RuntimeProviderId.VEGA_LINUX) as CompanionRuntimeClient
            val canonical = File(root, "canonical").apply { mkdirs() }
            val disk = DirectWorkspaceFs(null, canonical)
            var failNextOutput = false
            var outputOpens = 0
            val fs = object : WorkspaceFs by disk {
                override fun read(path: String): ByteArray = error("BUFFERED_READ_FORBIDDEN")
                override fun readText(path: String, charset: String): String = error("BUFFERED_READ_FORBIDDEN")
                override fun write(path: String, data: ByteArray) { error("BUFFERED_WRITE_FORBIDDEN") }
                override fun writeText(path: String, text: String, charset: String) { error("BUFFERED_WRITE_FORBIDDEN") }
                override fun openOutputStream(path: String): OutputStream {
                    outputOpens++
                    val output = disk.openOutputStream(path) // Truncates immediately, like SAF's wt mode.
                    if (!failNextOutput) return output
                    failNextOutput = false
                    return object : OutputStream() {
                        override fun write(b: Int) { output.write(b); error("INJECTED_PARTIAL_WRITE") }
                        override fun write(b: ByteArray, off: Int, len: Int) { output.write(b, off, minOf(len, 1024)); error("INJECTED_PARTIAL_WRITE") }
                        override fun close() = output.close()
                    }
                }
            }
            val mount = WorkspaceMount("large-mount", "main", "content://fixture/large", "Large", MountMode.READ_WRITE, "main", true)
            val project = VegaProject("large-project", "Large", mount.id, listOf(mount), 1L)
            val store = ProjectStore.get(context)
            store.saveProject(project); store.setActiveProject(project.id)
            val router = object : WorkspaceRouter(context, store) {
                override fun checkMountState(mount: WorkspaceMount) = MountState.AVAILABLE
                override fun getFsForMount(mount: WorkspaceMount): WorkspaceFs = fs
            }
            val registry = RuntimeRegistry(RuntimePreflight(mapOf(RuntimeProviderId.VEGA_LINUX to CompanionExecutionRuntime(client)))) { RuntimePreference.AUTO }
            val manager = ProjectMirrorManager(context, store, registry, mirrorClientFactory = { _, binding -> CompanionMirrorClient(binding, client) })
            val broker = CapabilityBrokerImpl(CapabilityStore.inMemory(), { store.getProject(it) }, { MountState.AVAILABLE })
            val brokersField = CapabilityBrokerRegistry.javaClass.getDeclaredField("brokers").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val brokers = brokersField.get(CapabilityBrokerRegistry) as MutableMap<String, CapabilityBrokerImpl>
            brokers[context.filesDir.absolutePath] = broker
            val journal = MutationJournal::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
            val taskId = "large-apply-task"
            val token = CancellationToken()
            fun stage(marker: String): String = manager.tool("execution_write_file", JSONObject()
                .put("path", "execution://main/marker.txt").put("content", marker).put("overwrite", true), router, taskId, token)
                .substringAfter("Execution ID: ").substringBefore('\n')
            fun authorize(id: String) {
                val plan = manager.previewApply(id, router)
                val requirement = CapabilityRequirement.ApplyChanges(project.id, id, plan.changedMountIds, plan.diffFingerprint, plan.hasDestructiveChanges)
                val check = broker.check(TaskContext(taskId, project.id, 1L, CapabilityMode.ACCEPT, taskId), requirement)
                if (check is CapabilityCheck.NeedsApproval) broker.grant(check.request.requestId, CapabilityDecision.Allow(GrantScope.TASK))
            }
            verify(manager.tool("execution_prepare", JSONObject(), router, taskId, token).startsWith("OK:"), "prepare succeeds")
            stage("first")
            val remoteDirectory = providerRoot.walkTopDown().single { it.isFile && it.name == "marker.txt" }.parentFile!!
            val remote = File(remoteDirectory, "a-large.bin")
            val local = File(canonical, remote.name)
            val size = 34L * 1024 * 1024 + 137
            payload(remote, size, 0)
            val originalHash = hash(remote)
            val first = stage("first")
            authorize(first)
            verify(manager.apply(first, router, journal).startsWith("OK:"), "34 MiB binary create applies")
            verify(local.length() == size && hash(local) == originalHash, "exact binary bytes including NUL and non-UTF8 preserved")
            verify(manager.pendingExecutionId() == null, "create clears pending")
            stage("second")
            payload(remote, size + 19, 7)
            val editedHash = hash(remote)
            val second = stage("second")
            authorize(second)
            failNextOutput = true
            val failure = runCatching { manager.apply(second, router, journal) }.exceptionOrNull()
            verify(failure?.message == "INJECTED_PARTIAL_WRITE", "partial write remains a failure")
            verify(local.length() == size && hash(local) == originalHash, "large preimage restored after truncated partial write")
            verify(manager.lifecycleStatus().contains("PENDING"), "proven rollback remains retryable")
            verify(manager.apply(second, router, journal).startsWith("OK:"), "same diff retries successfully")
            verify(local.length() == size + 19 && hash(local) == editedHash, "edited large bytes match mirror")
            val edit = journal.getTaskRecords(taskId).last { it.path == remote.name && it.state == dev.wind.agent.history.TransactionState.COMMITTED }
            verify(journal.undoRecord(edit, fs), "large edit supports streamed undo")
            verify(local.length() == size && hash(local) == originalHash, "undo restores original binary")
            verify(context.filesDir.listFiles()!!.none { it.name.startsWith("mirror-writeback-") }, "staging cleaned on success and failure")
            verify(outputOpens >= 5, "disk outputs actually opened for create/edit/rollback/undo")
            println("PASS LargeMirrorApplyIntegrationTests: $assertions assertions; payload=$size; maxHeap=${Runtime.getRuntime().maxMemory()}")
        } finally {
            root.deleteRecursively()
            CapabilityBrokerRegistry.clearForTests()
        }
    }
}
