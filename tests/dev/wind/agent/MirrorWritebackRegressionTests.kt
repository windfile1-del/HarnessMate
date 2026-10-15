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
import java.nio.file.Files

/** Real mirror/WAL/broker boundaries; SAF is represented by a host filesystem fixture. */
object MirrorWritebackRegressionTests {
    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("mirror-writeback-").toFile()
        var assertions = 0
        fun verify(value: Boolean, message: String) { assertions++; check(value) { message } }
        try {
            val contextClass = Class.forName("dev.wind.agent.CompanionMirrorIntegrationTests\$TestContext")
            val contextConstructor = contextClass.getDeclaredConstructor(File::class.java).apply { isAccessible = true }
            val context = contextConstructor.newInstance(File(root, "host").apply { mkdirs() }) as Context
            val fixture = CompanionMirrorIntegrationTests.javaClass.getDeclaredMethod(
                "createInProcessClient", File::class.java, RuntimeProviderId::class.java).apply { isAccessible = true }
            val client = fixture.invoke(CompanionMirrorIntegrationTests, File(root, "provider"), RuntimeProviderId.VEGA_COMPANION) as CompanionRuntimeClient
            val storage = FakeWorkspaceFs("canonical")
            var failNextWrite = false
            val fs = object : WorkspaceFs by storage {
                override fun list(path: String): List<WorkspaceEntry> = storage.list(path).map {
                    it.copy(size = if (it.isDirectory) 4096L else storage.read(it.path).size.toLong())
                }
                override fun stat(path: String): WorkspaceEntry? = storage.stat(path)?.let {
                    it.copy(size = if (it.isDirectory) 4096L else storage.read(path).size.toLong())
                }
                override fun writeText(path: String, text: String, charset: String) {
                    if (failNextWrite) { failNextWrite = false; throw IllegalStateException("FIXTURE_WRITE_FAILED") }
                    storage.writeText(path, text, charset)
                }
                override fun openOutputStream(path: String): java.io.OutputStream {
                    if (failNextWrite) {
                        failNextWrite = false
                        return object : java.io.OutputStream() {
                            override fun write(b: Int) { throw IllegalStateException("FIXTURE_WRITE_FAILED") }
                            override fun write(b: ByteArray, off: Int, len: Int) { throw IllegalStateException("FIXTURE_WRITE_FAILED") }
                        }
                    }
                    return object : java.io.ByteArrayOutputStream() {
                        override fun close() {
                            super.close()
                            storage.write(path, toByteArray())
                        }
                    }
                }
            }
            storage.mkdir("docs")
            storage.writeText("docs/report.txt", "original long report to be replaced")
            val mount = WorkspaceMount("writeback-mount", "main", "content://fixture/main", "Main", MountMode.READ_WRITE, "main", true)
            val project = VegaProject("writeback-project", "Writeback", mount.id, listOf(mount), 1L)
            val store = ProjectStore.get(context)
            store.saveProject(project); store.setActiveProject(project.id)
            val router = object : WorkspaceRouter(context, store) {
                override fun checkMountState(mount: WorkspaceMount) = MountState.AVAILABLE
                override fun getFsForMount(mount: WorkspaceMount): WorkspaceFs = fs
            }
            val provider = CompanionExecutionRuntime(client)
            val registry = RuntimeRegistry(RuntimePreflight(mapOf(RuntimeProviderId.VEGA_COMPANION to provider))) { RuntimePreference.AUTO }
            val manager = ProjectMirrorManager(context, store, registry, mirrorClientFactory = { _, binding -> CompanionMirrorClient(binding, client) })
            val broker = CapabilityBrokerImpl(CapabilityStore.inMemory(), { store.getProject(it) }, { MountState.AVAILABLE })
            val brokersField = CapabilityBrokerRegistry.javaClass.getDeclaredField("brokers").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val brokers = brokersField.get(CapabilityBrokerRegistry) as MutableMap<String, CapabilityBrokerImpl>
            brokers[context.filesDir.absolutePath] = broker
            val journalConstructor = MutationJournal::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            val journal = journalConstructor.newInstance(context)
            val taskId = "writeback-task"
            val token = CancellationToken()
            fun stage(path: String, content: String): String = manager.tool("execution_write_file",
                JSONObject().put("path", "execution://main/$path").put("content", content).put("overwrite", true), router, taskId, token)
                .substringAfter("Execution ID: ").substringBefore('\n')
            fun authorize(id: String) {
                val plan = manager.previewApply(id, router)
                val requirement = CapabilityRequirement.ApplyChanges(project.id, id, plan.changedMountIds, plan.diffFingerprint, plan.hasDestructiveChanges)
                val check = broker.check(TaskContext(taskId, project.id, 1L, CapabilityMode.ACCEPT, taskId), requirement)
                if (check is CapabilityCheck.NeedsApproval) broker.grant(check.request.requestId, CapabilityDecision.Allow(GrantScope.TASK))
            }
            verify(manager.tool("execution_prepare", JSONObject(), router, taskId, token).startsWith("OK:"), "prepare with SAF directory size must succeed")
            val first = stage("docs/report.txt", "短文")
            authorize(first)
            verify(manager.apply(first, router, journal).startsWith("OK:"), "short Unicode replacement must apply through WAL")
            verify(storage.readText("docs/report.txt") == "短文", "canonical content must exactly equal mirror")
            verify(manager.lifecycleStatus().contains("CLEAN"), "successful apply clears pending state")
            val second = stage("created.txt", "new document")
            authorize(second)
            verify(manager.apply(second, router, journal).startsWith("OK:"), "new file must reach canonical storage")
            verify(storage.readText("created.txt") == "new document", "new file bytes must be present")
            val third = stage("docs/report.txt", "retry content")
            authorize(third)
            failNextWrite = true
            val failure = runCatching { manager.apply(third, router, journal) }.exceptionOrNull()
            verify(failure?.message == "FIXTURE_WRITE_FAILED", "failed provider write must remain a failure")
            verify(storage.readText("docs/report.txt") == "短文", "failed edit must restore original canonical bytes")
            verify(manager.lifecycleStatus().contains("Mirror lifecycle: PENDING"), "proven rollback must preserve retryable pending state")
            verify(manager.apply(third, router, journal).startsWith("OK:"), "same pending diff must be retryable")
            verify(storage.readText("docs/report.txt") == "retry content", "retry must write actual canonical bytes")
            verify(manager.pendingExecutionId() == null, "retry success clears execution ID")
            val permissions = dev.wind.agent.permissions.AgentPermissions.get(context)
            val scope = dev.wind.agent.history.MutationScope(project.id, mount.id, mount.treeUri)
            storage.writeText("strategy.txt", "original")
            journal.transactionalEdit(scope, fs, "strategy-transaction", "strategy.txt", "staged") { fs.writeText("strategy.txt", "staged") }
            val transaction = journal.getTaskRecords("strategy-transaction").last()
            verify(transaction.snapshotId != null, "transaction strategy preserves pre-image")
            verify(journal.undoRecord(transaction, fs) && storage.readText("strategy.txt") == "original", "transaction strategy can undo")
            val injectedFailure = runCatching { journal.transactionalEdit(scope, fs, "strategy-failure", "strategy.txt", "bad") {
                fs.writeText("strategy.txt", "partial"); error("INJECTED_FAILURE")
            } }.exceptionOrNull()
            verify(injectedFailure?.message == "INJECTED_FAILURE" && storage.readText("strategy.txt") == "original", "failed transactional edit restores bytes")
            permissions.configure(strategy = dev.wind.agent.permissions.WriteStrategy.DIRECT)
            journal.transactionalEdit(scope, fs, "strategy-direct", "strategy.txt", "direct") { fs.writeText("strategy.txt", "direct") }
            val directRecord = journal.getTaskRecords("strategy-direct").last()
            verify(directRecord.snapshotId == null && directRecord.originAgent == "direct", "direct strategy audits without snapshot")
            verify(!journal.undoRecord(directRecord, fs) && storage.readText("strategy.txt") == "direct", "direct strategy does not promise undo")
            permissions.configure(strategy = dev.wind.agent.permissions.WriteStrategy.TRANSACTIONAL)
            storage.mkdir("empty")
            verify(journal.transactionalRmdir(scope, fs, "strategy-rmdir", "empty") { fs.delete("empty") }, "transaction removes empty directory")
            val rmdir = journal.getTaskRecords("strategy-rmdir").last()
            verify(journal.undoRecord(rmdir, fs) && storage.stat("empty")?.isDirectory == true, "removed directory can be restored")
            println("PASS MirrorWritebackRegressionTests: $assertions assertions")
        } finally { root.deleteRecursively(); CapabilityBrokerRegistry.clearForTests() }
    }
}
