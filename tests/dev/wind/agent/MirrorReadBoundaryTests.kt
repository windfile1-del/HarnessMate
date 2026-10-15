package dev.wind.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.wind.agent.project.MountState
import dev.wind.agent.project.ProjectStore
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.WorkspaceMount
import dev.wind.agent.project.WorkspaceRouter
import dev.wind.agent.project.MountMode
import dev.wind.agent.shell.mirror.ManifestDigest
import dev.wind.agent.shell.mirror.ManifestEntry
import dev.wind.agent.shell.mirror.MirrorSessionMount
import dev.wind.agent.shell.mirror.MirrorState
import dev.wind.agent.shell.mirror.MirrorStateStore
import dev.wind.agent.shell.mirror.MirrorPhase
import dev.wind.agent.shell.mirror.PendingExecution
import dev.wind.agent.shell.mirror.ProjectMirrorManager
import dev.wind.agent.shell.mirror.TermuxMirrorClient
import dev.wind.agent.shell.mirror.MirrorSink
import dev.wind.agent.workspace.WorkspaceFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Production-boundary regression for the MirrorRead authority.  The fake
 * client records real ProjectMirrorManager.tool calls, so restoring the old
 * prepareSessionLocked path would make the sync assertion fail.
 */
object MirrorReadBoundaryTests {
    private var assertions = 0

    private fun check(value: Boolean, message: String) {
        assertions++
        if (!value) throw AssertionError(message)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = Fixture()
        preparedReadNeverSyncs(fixture)
        coldReadFailsClosed(fixture)
        canonicalDriftFailsClosedWithoutSync(fixture)
        remoteDriftFailsClosedWithoutSync(fixture)
        pendingCanonicalDriftFailsClosedWithoutSync(fixture)
        pendingRemoteDriftFailsClosedWithoutSync(fixture)
        println("MirrorReadBoundaryTests: $assertions production-boundary assertions passed")
    }

    private fun preparedReadNeverSyncs(fixture: Fixture) {
        val fs = FakeWorkspaceFs("prepared")
        fs.writeText("src/Main.kt", "canonical")
        val project = fixture.project("prepared")
        val router = FixedRouter(fixture.context, fixture.store, fs)
        val baseline = canonicalDigest(project, fs)
        val remoteDigest = ManifestDigest.of(emptyMap<String, ManifestEntry>())
        saveCleanState(fixture, project, baseline, remoteDigest)
        val client = RecordingClient(fixture.context, remoteDigest)
        val manager = manager(fixture, client)

        val result = manager.tool(
            "execution_read_file",
            JSONObject().put("path", "execution://src/Main.kt"),
            router,
            "task-prepared",
            CancellationToken()
        )
        check(result.contains("mirror-read"), "a prepared execution read did not reach the production client")
        check("sync" !in client.operations, "MirrorRead execution read triggered mirror sync")
        check(client.operations == listOf("manifest_page", "execution_read_file"),
            "prepared read did not use observation then the requested read: ${client.operations}")
    }

    private fun coldReadFailsClosed(fixture: Fixture) {
        val fs = FakeWorkspaceFs("cold")
        fs.writeText("src/Main.kt", "canonical")
        val project = fixture.project("cold")
        val router = FixedRouter(fixture.context, fixture.store, fs)
        val client = RecordingClient(fixture.context, ManifestDigest.of(emptyMap<String, ManifestEntry>()))
        val manager = manager(fixture, client)
        val error = runCatching {
            manager.tool("execution_list_dir", JSONObject(), router, "task-cold", CancellationToken())
        }.exceptionOrNull()
        check(error?.message?.contains("MIRROR_NOT_PREPARED") == true,
            "a cold MirrorRead did not fail closed: ${error?.message}")
        check("sync" !in client.operations && client.operations.isEmpty(),
            "a cold MirrorRead touched the mirror client: ${client.operations}")
    }

    private fun canonicalDriftFailsClosedWithoutSync(fixture: Fixture) {
        val fs = FakeWorkspaceFs("canonical-drift")
        fs.writeText("src/Main.kt", "canonical")
        val project = fixture.project("canonical-drift")
        val router = FixedRouter(fixture.context, fixture.store, fs)
        val remoteDigest = ManifestDigest.of(emptyMap<String, ManifestEntry>())
        saveCleanState(fixture, project, "b".repeat(64), remoteDigest)
        val client = RecordingClient(fixture.context, remoteDigest)
        val manager = manager(fixture, client)
        val error = runCatching {
            manager.tool("execution_search_files", JSONObject().put("query", "x"), router, "task-canonical", CancellationToken())
        }.exceptionOrNull()
        check(error?.message?.contains("MIRROR_NOT_PREPARED") == true,
            "canonical drift did not fail closed: ${error?.message}")
        check("sync" !in client.operations, "canonical drift caused a MirrorRead sync")
    }

    private fun remoteDriftFailsClosedWithoutSync(fixture: Fixture) {
        val fs = FakeWorkspaceFs("remote-drift")
        fs.writeText("src/Main.kt", "canonical")
        val project = fixture.project("remote-drift")
        val router = FixedRouter(fixture.context, fixture.store, fs)
        val baseline = canonicalDigest(project, fs)
        val liveRemote = ManifestDigest.of(emptyMap<String, ManifestEntry>())
        saveCleanState(fixture, project, baseline, "a".repeat(64))
        val client = RecordingClient(fixture.context, liveRemote)
        val manager = manager(fixture, client)
        val error = runCatching {
            manager.tool("execution_file_info", JSONObject().put("path", "execution://src/Main.kt"), router, "task-remote", CancellationToken())
        }.exceptionOrNull()
        check(error?.message?.contains("MIRROR_INTEGRITY_LOST") == true,
            "remote drift did not fail closed: ${error?.message}")
        check(client.operations == listOf("manifest_page") && "sync" !in client.operations,
            "remote drift took an unexpected mirror path: ${client.operations}")
    }

    private fun pendingCanonicalDriftFailsClosedWithoutSync(fixture: Fixture) {
        val fs = FakeWorkspaceFs("pending-canonical-drift")
        fs.writeText("src/Main.kt", "canonical")
        val project = fixture.project("pending-canonical-drift")
        val router = FixedRouter(fixture.context, fixture.store, fs)
        val remoteDigest = ManifestDigest.of(emptyMap<String, ManifestEntry>())
        savePendingState(fixture, project, "b".repeat(64), remoteDigest, "pending-canonical")
        val client = RecordingClient(fixture.context, remoteDigest)
        val manager = manager(fixture, client)
        val error = runCatching {
            manager.tool("execution_read_file", JSONObject().put("path", "execution://src/Main.kt"),
                router, "task-pending-canonical", CancellationToken())
        }.exceptionOrNull()
        check(error?.message?.contains("MIRROR_NOT_PREPARED") == true,
            "pending canonical drift did not fail closed: ${error?.message}")
        check(client.operations.isEmpty() && "sync" !in client.operations,
            "pending canonical drift touched the mirror client: ${client.operations}")
    }

    private fun pendingRemoteDriftFailsClosedWithoutSync(fixture: Fixture) {
        val fs = FakeWorkspaceFs("pending-remote-drift")
        fs.writeText("src/Main.kt", "canonical")
        val project = fixture.project("pending-remote-drift")
        val router = FixedRouter(fixture.context, fixture.store, fs)
        val baseline = canonicalDigest(project, fs)
        val liveRemote = ManifestDigest.of(emptyMap<String, ManifestEntry>())
        savePendingState(fixture, project, baseline, "a".repeat(64), "pending-remote")
        val client = RecordingClient(fixture.context, liveRemote)
        val manager = manager(fixture, client)
        val error = runCatching {
            manager.tool("execution_file_info", JSONObject().put("path", "execution://src/Main.kt"),
                router, "task-pending-remote", CancellationToken())
        }.exceptionOrNull()
        check(error?.message?.contains("MIRROR_INTEGRITY_LOST") == true,
            "pending remote drift did not fail closed: ${error?.message}")
        check(client.operations == listOf("manifest_page") && "sync" !in client.operations,
            "pending remote drift took an unexpected mirror path: ${client.operations}")
    }

    private fun manager(fixture: Fixture, client: RecordingClient): ProjectMirrorManager =
        ProjectMirrorManager(fixture.context, fixture.store) { _: Context, _: String -> client }

    private fun saveCleanState(
        fixture: Fixture,
        project: VegaProject,
        canonicalDigest: String,
        remoteDigest: String
    ) {
        val topology = topologyHash(project)
        MirrorStateStore(fixture.context, project.id).save(
            MirrorState.clean(project.id, project.generation, topology).copy(
                canonicalBaselineDigest = canonicalDigest,
                remoteDigest = remoteDigest,
                sessionMounts = project.mounts.map {
                    MirrorSessionMount(it.id, it.treeUri, it.mode.name, it.executionPath)
                }
            )
        )
    }

    private fun savePendingState(
        fixture: Fixture,
        project: VegaProject,
        canonicalDigest: String,
        remoteDigest: String,
        executionId: String
    ) {
        val topology = topologyHash(project)
        val pending = PendingExecution(
            executionId = executionId,
            taskId = "task-$executionId",
            rootTaskId = "task-$executionId",
            remoteDigest = remoteDigest,
            eligible = true,
            createdAt = System.currentTimeMillis()
        )
        MirrorStateStore(fixture.context, project.id).save(
            MirrorState.clean(project.id, project.generation, topology).copy(
                phase = MirrorPhase.PENDING,
                canonicalBaselineDigest = canonicalDigest,
                remoteDigest = remoteDigest,
                sessionMounts = project.mounts.map {
                    MirrorSessionMount(it.id, it.treeUri, it.mode.name, it.executionPath)
                },
                pending = pending
            )
        )
    }

    private fun canonicalDigest(project: VegaProject, fs: WorkspaceFs): String {
        val manifest = dev.wind.agent.shell.mirror.WorkspaceManifestScanner.scan(
            fs, project.primaryMount()!!.id, project.generation
        )
        val primary = project.primaryMount()!!.executionPath.trim('/')
        val flat = manifest.entries.mapKeys { (path, _) ->
            if (path.isEmpty()) primary else "$primary/$path"
        }.mapValues { (path, entry) -> entry.copy(path = path) }
        return ManifestDigest.of(flat)
    }

    private fun topologyHash(project: VegaProject): String {
        val raw = buildString {
            append("generation=").append(project.generation).append(';')
            project.mounts.sortedBy { it.id }.forEach {
                append(it.id).append(':').append(it.alias).append(':')
                    .append(it.executionPath).append(':').append(it.mode).append(':')
                    .append(it.treeUri).append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class Fixture {
        val context = TestContext()
        val store = ProjectStore.get(context)

        fun project(label: String): VegaProject {
            val mount = WorkspaceMount(
                id = dev.wind.agent.shell.mirror.WorkspaceIdentity.fromUri(
                    "content://mirror-read/$label"
                ),
                alias = "main",
                treeUri = "content://mirror-read/$label",
                displayName = label,
                mode = MountMode.READ_WRITE,
                executionPath = "main",
                isPrimary = true
            )
            val saved = store.saveProject(VegaProject(
                id = "mirror-read-$label",
                name = label,
                primaryMountId = mount.id,
                mounts = listOf(mount)
            ))
            store.setActiveProject(saved.id)
            return saved
        }
    }

    private class FixedRouter(
        context: Context,
        store: ProjectStore,
        private val fs: WorkspaceFs
    ) : WorkspaceRouter(context, store) {
        override fun checkMountState(mount: WorkspaceMount): MountState = MountState.AVAILABLE
        override fun getFsForMount(mount: WorkspaceMount): WorkspaceFs = fs
    }

    private class RecordingClient(context: Context, private val manifestDigest: String) :
        TermuxMirrorClient(context, "test-workspace") {
        val operations = mutableListOf<String>()

        override fun call(
            operation: String,
            args: JSONObject,
            sources: List<() -> InputStream>,
            token: CancellationToken?,
            timeoutMs: Long,
            sinks: List<MirrorSink>
        ): JSONObject {
            operations += operation
            return when (operation) {
                "manifest_page" -> JSONObject()
                    .put("entries", JSONArray())
                    .put("digest", manifestDigest)
                    .put("totalEntries", 0)
                    .put("nextOffset", -1)
                "execution_read_file", "execution_file_info", "execution_list_dir",
                "execution_search_files", "execution_glob" ->
                    JSONObject().put("ok", true).put("text", "mirror-read")
                else -> error("unexpected mirror operation: $operation")
            }
        }
    }

    private class TestContext : ContextWrapper(null) {
        private val dir = File(
            System.getProperty("java.io.tmpdir"), "vega-mirror-read-" + System.nanoTime()
        ).also { it.mkdirs() }
        private val preferences = TestPreferences()

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
        override fun getCacheDir(): File = dir
        override fun getExternalFilesDir(type: String?): File = dir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        override fun getPackageName(): String = "dev.wind.agent"
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
}


