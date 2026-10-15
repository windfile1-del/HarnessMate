package dev.wind.agent.runtime.linux

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.artifact.SecureArtifactDownloader
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.capability.CapabilityBroker
import dev.wind.agent.capability.CapabilityLease
import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.TaskContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Quarantine preparation and exact-digest activation are separate broker-visible steps. */
class RuntimeToolInstallPlans(private val context: Context) {
    private val privateRoot = context.noBackupFilesDir.canonicalFile
    private val plans = File(privateRoot, "p7-runtime-tool-plans")
    private val store = VerifiedArtifactStore(File(privateRoot, "p7-artifacts"))
    private val access = TaskArtifactAccess(File(privateRoot, "p7-artifact-access"))
    companion object { private val LOCK = java.util.concurrent.locks.ReentrantLock() }
    private fun <T> withInstallLock(block: () -> T): T {
        LOCK.lockInterruptibly()
        return try { block() } finally { LOCK.unlock() }
    }

    fun prepare(task: TaskContext, tools: List<String>, token: CancellationToken,
                authorize: (String) -> Boolean, progress: (String) -> Unit): JSONObject = withInstallLock {
        require(task.taskId.isNotBlank()) { "RUNTIME_TOOL_TASK_REQUIRED" }
        val needed = RuntimeToolCatalog.families(tools).filter { !RuntimeToolCatalog.isInstalled(context, it) }
        if (needed.isEmpty()) return@withInstallLock JSONObject().put("state", "READY").put("tools", RuntimeToolCatalog.json(context))
        val items = JSONArray()
        for (family in needed) {
            token.throwIfCancelled()
            val metadata = RuntimeToolCatalog.manifest(context, family)
            val item = JSONObject().put("family", family).put("payload_sha256", metadata.getString("sha256"))
            if (family == "core") {
                item.put("artifact_sha256", metadata.getString("sha256"))
            } else {
                val downloader = SecureArtifactDownloader.runtimeToolPacks(store, File(privateRoot, "p7-quarantine"),
                    RuntimeToolCatalog.networkDomains) { authorize(it.toString()) }
                val download = RuntimeToolCatalog.downloadManifest(context, family)
                val artifact = downloader.download(RuntimeToolCatalog.url(family), "Install approved Harness Mate $family extension",
                    download.getString("sha256"), download.getLong("bytes"), token) { count, total ->
                    progress("$family: $count" + (total?.let { " / $it bytes" } ?: " bytes"))
                }
                access.record(task.taskId, task.projectId, task.projectGeneration, artifact)
                // Independent trust root is the APK's fixed manifest, not the download's self-reported digest.
                token.throwIfCancelled()
                val imported = store.openVerified(artifact.uri).use { LinuxToolPackStore.importPack(context, it) }
                check(imported == family) { "RUNTIME_TOOL_PACK_FAMILY_MISMATCH" }
                item.put("artifact_uri", artifact.uri).put("artifact_sha256", artifact.sha256)
            }
            items.put(item)
        }
        token.throwIfCancelled()
        check(plans.isDirectory || plans.mkdirs()) { "RUNTIME_TOOL_PLAN_STORAGE_FAILED" }
        val id = UUID.randomUUID().toString()
        val plan = JSONObject().put("schema", 1).put("plan_id", id).put("task_id", task.taskId)
            .put("project_id", task.projectId).put("generation", task.projectGeneration).put("items", items)
        val temp = File(plans, "$id.new")
        try {
            FileOutputStream(temp).use { output -> output.write(plan.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
            check(temp.renameTo(File(plans, "$id.json"))) { "RUNTIME_TOOL_PLAN_COMMIT_FAILED" }
        } finally { temp.delete() }
        JSONObject().put("state", "READY_TO_INSTALL").put("plan_id", id).put("items", items)
            .put("next_tool", "install_runtime_tools").put("trust", "APK_PINNED_PUBLISHER_SIGNATURE_AND_PAYLOAD")
    }

    private fun load(task: TaskContext, id: String): JSONObject {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "RUNTIME_TOOL_PLAN_ID_INVALID" }
        val file = File(plans, "$id.json")
        require(file.isFile && file.length() in 1..65536) { "RUNTIME_TOOL_PLAN_MISSING" }
        val plan = JSONObject(file.readText(Charsets.UTF_8))
        require(plan.getInt("schema") == 1 && plan.getString("plan_id") == id) { "RUNTIME_TOOL_PLAN_INVALID" }
        require(plan.getString("task_id") == task.taskId && plan.getString("project_id") == task.projectId &&
            plan.getLong("generation") == task.projectGeneration) { "RUNTIME_TOOL_PLAN_SCOPE_MISMATCH" }
        return plan
    }
    fun requirement(task: TaskContext, id: String): CapabilityRequirement {
        val items = load(task, id).getJSONArray("items")
        require(items.length() in 1..4) { "RUNTIME_TOOL_PLAN_INVALID" }
        val ordered = (0 until items.length()).map { items.getJSONObject(it).getString("family") }
        require(ordered.distinct() == ordered && RuntimeToolCatalog.families(ordered).filter { it in ordered } == ordered) { "RUNTIME_TOOL_PLAN_INVALID" }
        return CapabilityRequirement.Composite(task.projectId, (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            val family = item.getString("family")
            require(family in RuntimeToolCatalog.families(listOf(family)))
            require(item.getString("payload_sha256") == RuntimeToolCatalog.manifest(context, family).getString("sha256")) { "RUNTIME_TOOL_PLAN_CATALOG_CHANGED" }
            if (family == "core") require(item.getString("artifact_sha256") == item.getString("payload_sha256")) { "RUNTIME_TOOL_PLAN_DIGEST_MISMATCH" }
            else require(item.getString("artifact_uri") == "artifact://sha256/" + item.getString("artifact_sha256")) { "RUNTIME_TOOL_PLAN_DIGEST_MISMATCH" }
            CapabilityRequirement.ArtifactInstall(task.projectId, CapabilityRequirement.ArtifactInstallKind.RUNTIME_TOOL,
                item.getString("artifact_sha256"), "harness-tool:$family")
        })
    }
    fun install(task: TaskContext, id: String, token: CancellationToken, broker: CapabilityBroker,
                lease: CapabilityLease, progress: (String) -> Unit): JSONObject = withInstallLock {
        val requirement = requirement(task, id)
        val items = load(task, id).getJSONArray("items")
        broker.verify(lease, requirement)
        val installed = JSONArray()
        val worker = Thread.currentThread()
        val cancellationLock = Any()
        var active = true
        val cancellation = token.onCancel {
            synchronized(cancellationLock) { if (active) worker.interrupt() }
        }
        LinuxInstallControl.setListener { phase ->
            token.throwIfCancelled()
            broker.verify(lease, requirement)
            progress(phase)
        }
        try {
        for (i in 0 until items.length()) {
            token.throwIfCancelled()
            val item = items.getJSONObject(i)
            val family = item.getString("family")
            progress("Installing $family")
            broker.verify(lease, requirement)
            if (family == "core") LinuxRuntimeInstaller.installBundled(context) else {
                val uri = item.getString("artifact_uri")
                access.requireAccess(task.taskId, task.projectId, task.projectGeneration, uri)
                check(store.openVerified(uri).use { LinuxToolPackStore.importPack(context, it) } == family) { "RUNTIME_TOOL_PACK_FAMILY_MISMATCH" }
                broker.verify(lease, requirement)
                LinuxRuntimeInstaller.installExtension(context, family)
            }
            check(RuntimeToolCatalog.isInstalled(context, family)) { "RUNTIME_TOOL_PROBE_FAILED: $family" }
            installed.put(family)
        }
        JSONObject().put("state", "INSTALLED_VERIFIED").put("installed", installed)
            .put("tools", RuntimeToolCatalog.json(context)).put("next_step", "Re-run shell_capabilities and continue the original task.")
        } finally {
            synchronized(cancellationLock) { active = false }
            cancellation.close()
            LinuxInstallControl.clearListener()
        }
    }
}
