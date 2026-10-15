package dev.wind.agent.shell.mirror

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.capability.CapabilityBrokerRegistry
import dev.wind.agent.capability.CapabilityCheck
import dev.wind.agent.capability.CapabilityMode
import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.TaskContext
import dev.wind.agent.history.MutationJournal
import dev.wind.agent.history.MutationPreconditionConflict
import dev.wind.agent.history.MutationScope
import dev.wind.agent.project.*
import dev.wind.agent.runtime.*
import dev.wind.agent.shell.*
import dev.wind.agent.shell.backends.TermuxBackend
import dev.wind.agent.workspace.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class PreparedMirrorRevision(
    val sourceDigest: String,
    val phase: MirrorPhase,
    val baselineRefreshSafe: Boolean
)

/**
 * A read-only observation of the already prepared P3 mirror.  This type is
 * intentionally separate from a prepare/sync result: code intelligence owns
 * only MirrorRead and must never be able to turn that lease into a mutation.
 */
data class ObservedMirrorRevision(
    val projectId: String,
    val rootGeneration: Long,
    val phase: MirrorPhase,
    val expectedRemoteDigest: String,
    val liveRemoteDigest: String
)

data class MirrorFileEntry(
    val path: String,
    val size: Long,
    val sha256: String?,
    val isDirectory: Boolean
)

data class MirrorManifestSummary(
    val digest: String,
    val files: Map<String, MirrorFileEntry>
)

/**
 * Read/observation surface consumed by Code Intelligence.  Keeping this
 * narrow interface separate from the mutating mirror manager makes it
 * possible to prove that a static query only observes an already prepared
 * P3 root; it has no sync/prepare entry point at this boundary.
 */
interface CodeIntelligenceMirror {
    fun observeForCodeIntelligence(
        router: WorkspaceRouter,
        token: CancellationToken = CancellationToken()
    ): ObservedMirrorRevision

    fun markUnexpectedMirrorMutation(reason: String)

    fun currentMirrorDigest(token: CancellationToken? = null): String

    fun getMirrorManifest(token: CancellationToken? = null): MirrorManifestSummary

    fun readMirrorText(executionRelativePath: String, token: CancellationToken? = null): String?

    /**
     * The durable provider authority for one project generation.
     *
     * Code Intelligence needs it to decide whether a language server may be
     * started at all, and the answer must come from the P3 owner of the mirror
     * state rather than from P5 re-reading the state file. Anything the state
     * machine rejects - a missing state, an unstable phase, an absent or
     * mismatched binding - throws, and the caller must fail closed rather than
     * assume a default provider.
     */
    fun requireRuntimeBindingForCodeIntelligence(
        projectId: String,
        rootGeneration: Long
    ): RuntimeBinding
}

/**
 * Multi-root persistent execution mirror.
 *
 * The durable runtime binding selects the execution substrate. Vega persists a
 * schema-versioned digest/state record; manifests and file payloads are
 * always recomputed or streamed at the point they are needed.
 */
class ProjectMirrorManager(
    private val context: Context,
    private val projectStore: ProjectStore = ProjectStore.get(context),
    private val runtimeRegistry: RuntimeRegistry = RuntimeRegistry(context),
    private val mirrorClientFactory: (Context, RuntimeBinding) -> MirrorClient =
        { owner, binding ->
            when (binding.providerId) {
                RuntimeProviderId.VEGA_LINUX -> CompanionMirrorClient(binding, dev.wind.agent.runtime.LinuxRuntimeClient(owner))
                RuntimeProviderId.VEGA_COMPANION -> CompanionMirrorClient(binding, CompanionRuntimeClient(owner))
                RuntimeProviderId.TERMUX -> TermuxMirrorClientAdapter(TermuxMirrorClient(owner, binding.workspaceId))
            }
        },
    private val isLegacyFactory: Boolean = false
) : CodeIntelligenceMirror {
    constructor(
        context: Context,
        projectStore: ProjectStore,
        legacyMirrorClientFactory: (Context, String) -> TermuxMirrorClient
    ) : this(
        context = context,
        projectStore = projectStore,
        runtimeRegistry = RuntimeRegistry(context),
        mirrorClientFactory = { owner, binding ->
            TermuxMirrorClientAdapter(legacyMirrorClientFactory(owner, binding.workspaceId))
        },
        isLegacyFactory = true
    )

    companion object {
        private const val SYNC_PLAN_PAGE_LIMIT = 2048
        private const val SYNC_PLAN_PAGE_BYTES = 1024 * 1024
        /** Execution reads may only observe a prepared mirror; they never sync it. */
        internal val READ_ONLY_EXECUTION_TOOLS = setOf(
            "execution_list_dir",
            "execution_read_file",
            "execution_file_info",
            "execution_search_files",
            "execution_glob"
        )
    }

    private data class CanonicalSnapshot(
        val mounts: Map<String, Pair<WorkspaceMount, WorkspaceManifest>>,
        val flat: Map<String, ManifestEntry>,
        val sources: Map<String, () -> InputStream>
    ) {
        val digest: String get() = ManifestDigest.of(flat)
    }

    private data class RemoteSnapshot(
        val entries: Map<String, ManifestEntry>,
        val digest: String
    )

    private data class ApplyChange(
        val mount: WorkspaceMount,
        val relativePath: String,
        val canonical: ManifestEntry?,
        val remote: ManifestEntry?
    )

    private val project: VegaProject
        get() = projectStore.getActiveProject() ?: throw IllegalStateException("No active project configured")

    private fun <T> withActiveProject(block: (VegaProject) -> T): T {
        val id = project.id
        return MirrorLockRegistry.withProject(id) {
            val current = projectStore.getActiveProject()
                ?: throw IllegalStateException("No active project configured")
            check(current.id == id) { "PROJECT_CHANGED_DURING_MIRROR_OPERATION" }
            block(current)
        }
    }

    private fun remoteId(proj: VegaProject): String = ProjectIdentity.remoteId(proj.id)

    private fun resolveRuntimeBindingLocked(proj: VegaProject): BoundProjectRuntime {
        val store = stateStore(proj)
        val topology = computeTopologyHash(proj)
        val state = store.load(proj.generation.toLong(), topology)
        if (state?.runtimeBinding != null) {
            return runtimeRegistry.requireBinding(
                state.runtimeBinding, proj.id, proj.generation.toLong(), remoteId(proj)
            )
        }
        val bound = runtimeRegistry.resolveBoundRuntime(
            proj.id, proj.generation.toLong(), remoteId(proj), state
        )
        val baseState = state ?: MirrorState.clean(proj.id, proj.generation.toLong(), topology)
        store.save(baseState.copy(runtimeBinding = bound.binding))
        return bound
    }

    private fun client(proj: VegaProject): MirrorClient {
        if (isLegacyFactory) {
            val fakeBinding = RuntimeBinding(
                RuntimeProviderId.TERMUX, remoteId(proj), proj.id, proj.generation.toLong(), "legacy-test"
            )
            return mirrorClientFactory(context, fakeBinding)
        }
        val bound = resolveRuntimeBindingLocked(proj)
        return mirrorClientFactory(context, bound.binding)
    }
    private fun stateStore(proj: VegaProject): MirrorStateStore = MirrorStateStore(context, proj.id)

    private fun computeTopologyHash(proj: VegaProject): String {
        val raw = buildString {
            append("generation=").append(proj.generation).append(';')
            proj.mounts.sortedBy { it.id }.forEach {
                append(it.id).append(':')
                    .append(it.alias).append(':')
                    .append(it.executionPath).append(':')
                    .append(it.mode).append(':')
                    .append(it.treeUri).append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun loadState(proj: VegaProject): MirrorState? =
        stateStore(proj).load(proj.generation, computeTopologyHash(proj))

    private fun saveState(proj: VegaProject, state: MirrorState) = stateStore(proj).save(state)

    private fun operation(
        id: String,
        kind: String,
        result: String,
        startedAt: Long,
        scanned: Int = 0,
        transferred: Int = 0,
        bytes: Long = 0L,
        created: Int = 0,
        modified: Int = 0,
        deleted: Int = 0
    ) = MirrorOperationStats(
        id, kind, scanned, transferred, bytes, created, modified, deleted,
        (System.currentTimeMillis() - startedAt).coerceAtLeast(0L), result
    )

    private fun broken(proj: VegaProject, state: MirrorState?, reason: String): MirrorState {
        val base = state ?: MirrorState.clean(proj.id, proj.generation, computeTopologyHash(proj))
        val next = base.copy(
            phase = MirrorPhase.BROKEN,
            lastOperation = operation("broken", "recovery", reason, System.currentTimeMillis())
        )
        runCatching { saveState(proj, next) }
        return next
    }

    private fun validateSessionIdentity(state: MirrorState, proj: VegaProject) {
        check(state.projectGeneration == proj.generation && state.topologyHash == computeTopologyHash(proj)) {
            "PENDING_TOPOLOGY_CHANGE: project topology changed while execution state is pending. Apply/discard before changing capabilities."
        }
    }

    /**
     * Pending sessions may observe only mounts whose exact capability snapshot
     * still exists. A revoked SAF grant therefore fails closed before any
     * mirror read or canonical write.
     */
    private fun validateSessionCapabilities(state: MirrorState, proj: VegaProject, router: WorkspaceRouter) {
        validateSessionIdentity(state, proj)
        for (saved in state.sessionMounts) {
            val current = proj.findMountById(saved.id)
                ?: throw IllegalStateException("CAPABILITY_CHANGED: A mount used by this execution session was removed. Discard or recover before continuing.")
            if (current.treeUri != saved.treeUri || current.mode.name != saved.mode ||
                current.executionPath != saved.executionPath) {
                throw IllegalStateException("CAPABILITY_CHANGED: Mount '${current.alias}' changed after this execution session began. Discard or recover before continuing.")
            }
            if (router.checkMountState(current) != MountState.AVAILABLE) {
                throw IllegalStateException("CAPABILITY_CHANGED: Mount '${current.alias}' is no longer authorized. Old mirror data is inaccessible until the session is discarded or access is reauthorized.")
            }
        }
    }

    private fun scanCanonicalLocked(proj: VegaProject, router: WorkspaceRouter): CanonicalSnapshot {
        val mounts = linkedMapOf<String, Pair<WorkspaceMount, WorkspaceManifest>>()
        val flat = linkedMapOf<String, ManifestEntry>()
        val sources = linkedMapOf<String, () -> InputStream>()
        for (mount in proj.mounts) {
            when (router.checkMountState(mount)) {
                MountState.AVAILABLE -> {
                    val fs = router.getFsForMount(mount)
                    val manifest = WorkspaceManifestScanner.scan(fs, WorkspaceIdentity.fromUri(mount.id), proj.generation)
                    mounts[mount.id] = mount to manifest
                    val prefix = mount.executionPath.trim('/')
                    // Providers enumerate mount containers too. Include them in
                    // the target tree so sync verifies the same manifest and an
                    // empty mount still has a usable working directory.
                    if (prefix.isNotEmpty()) {
                        var container = ""
                        for (segment in prefix.split('/')) {
                            container = if (container.isEmpty()) segment else "$container/$segment"
                            val previous = flat.putIfAbsent(container, ManifestEntry(container, true, 0L, 0L, null))
                            require(previous == null || previous.directory) { "DUPLICATE_EXECUTION_PATH" }
                        }
                    }
                    for (entry in manifest.entries.values) {
                        val mapped = if (entry.path.isEmpty()) prefix else "$prefix/${entry.path}"
                        val mappedEntry = entry.copy(path = mapped)
                        require(flat.put(mapped, mappedEntry) == null) { "DUPLICATE_EXECUTION_PATH" }
                        if (!entry.directory) sources[mapped] = { fs.openInputStream(entry.path) }
                    }
                }
                else -> if (mount.isPrimary) {
                    throw IllegalStateException("PRIMARY_MOUNT_PERMISSION_REVOKED: Primary mount '${mount.alias}' is unavailable. Reauthorize it before executing.")
                }
            }
        }
        return CanonicalSnapshot(mounts, flat, sources)
    }

    fun scanCanonicalProject(router: WorkspaceRouter): Map<String, Pair<WorkspaceMount, WorkspaceManifest>> =
        withActiveProject { scanCanonicalLocked(it, router).mounts }

    /** P4 observes the same prepared PENDING/CLEAN mirror that will be applied. */
    fun detectValidationProject(router: WorkspaceRouter, projectPath: String? = null): dev.wind.agent.coding.ProjectProfile =
        withActiveProject { proj ->
            observeExecutionReadLocked(proj, router, null)
            val primary = proj.primaryMount() ?: error("PROJECT_WITHOUT_PRIMARY_MOUNT")
            val paths = remoteManifestLocked(proj).entries.keys
            val selected = projectPath?.let { resolveExecutionVirtualPath(it) }
            dev.wind.agent.coding.ProjectDetector.detectMirror(paths, primary.executionPath, selected) { path ->
                client(proj).call("fetch_text", JSONObject().put("path", path)).getString("text")
            }
        }

    fun hasOnlyDocumentationChanges(router: WorkspaceRouter): Boolean = withActiveProject { proj ->
        observeExecutionReadLocked(proj, router, null)
        val changes = collectChanges(proj, scanCanonicalLocked(proj, router), remoteManifestLocked(proj))
        val files = changes.filter { it.canonical?.directory != true && it.remote?.directory != true }
        files.isNotEmpty() && files.all {
            dev.wind.agent.coding.DocumentationPolicy.isDocumentation(it.relativePath)
        } && changes.none { it.relativePath.isEmpty() || it.canonical?.directory == true && it.remote == null }
    }

    /** Observes the authoritative provider without binding, preparing or migrating a project. */
    fun runtimeStatus(): RuntimeProbeResult = withActiveProject { proj ->
        runtimeRegistry.probeForProject(proj.id, proj.generation, remoteId(proj), loadState(proj))
    }

    fun pendingExecutionId(): String? = withActiveProject { proj -> loadState(proj)?.pending?.executionId }

    /** Local lifecycle observation, separate from task-scoped P2 authority. */
    fun lifecycleStatus(): String = withActiveProject { proj ->
        val state = loadState(proj)
        buildString {
            append("Mirror lifecycle: ").append(state?.phase?.name ?: "NOT_PREPARED")
            state?.pending?.let {
                append("\nPending execution: ").append(it.executionId)
                append("\nPending owner: ").append(it.rootTaskId)
                append("\nApply eligible: ").append(it.eligible)
            }
            state?.lastOperation?.let { append("\nLast mirror operation: ").append(it.result) }
        }
    }

    /**
     * Reconcile durable interruption metadata with both actual trees. Never
     * sync here: starting another task must not discard a previous task's work.
     * Recovery can adopt edits, but does not turn a failed command into a
     * successful command or manufacture verification evidence.
     */
    private fun reconcileInterruptedLocked(
        proj: VegaProject, state: MirrorState, router: WorkspaceRouter,
        token: CancellationToken?, taskId: String?, beforeSideEffect: (() -> Unit)?
    ): MirrorState {
        check(state.phase != MirrorPhase.REBINDING && state.runtimeMigration == null) {
            "RUNTIME_MIGRATION_PENDING"
        }
        validateSessionCapabilities(state, proj, router)
        val canonical = scanCanonicalLocked(proj, router)
        val remote = remoteManifestLocked(proj, token)
        val same = canonical.digest == remote.digest
        check(same || state.canonicalBaselineDigest == canonical.digest) {
            "RECOVERY_CONFLICT: Canonical files changed; pending mirror edits were preserved."
        }
        val old = state.pending
        check(same || taskId != null || old != null) {
            "MIRROR_RECOVERY_REQUIRED: edits preserved; use execution_recover to adopt the interrupted session."
        }
        val executionId = if (taskId != null || old == null)
            "proj-recovered-" + UUID.randomUUID().toString().replace("-", "").take(16)
        else old.executionId
        val pending = if (same) null else PendingExecution(
            executionId, taskId ?: old!!.taskId, taskId ?: old!!.rootTaskId,
            remote.digest, old?.eligible == true && old.remoteDigest == remote.digest,
            System.currentTimeMillis()
        )
        val next = state.copy(
            phase = if (same) MirrorPhase.CLEAN else MirrorPhase.PENDING,
            canonicalBaselineDigest = canonical.digest,
            remoteDigest = remote.digest,
            sessionMounts = canonical.mounts.values.map { (mount, _) ->
                MirrorSessionMount(mount.id, mount.treeUri, mount.mode.name, mount.executionPath)
            },
            pending = pending,
            lastOperation = operation(executionId, "recovery",
                if (same) "RECONCILED_CLEAN" else "RECONCILED_PENDING", System.currentTimeMillis())
        )
        beforeSideEffect?.invoke()
        saveState(proj, next)
        return next
    }

    fun publishExecutionArtifact(rawPath: String, router: WorkspaceRouter, token: CancellationToken,
        store: dev.wind.agent.artifact.VerifiedArtifactStore, verify: () -> Unit
    ): dev.wind.agent.artifact.VerifiedArtifact = withActiveProject { proj ->
        observeExecutionReadLocked(proj, router, token)
        val path = resolvePathInProject(proj, rawPath)
        val remote = client(proj)
        val info = remote.call("execution_file_info", JSONObject().put("path", path), token = token)
        check(info.optBoolean("exists") && !info.optBoolean("directory")) { "EXECUTION_ARTIFACT_NOT_A_FILE" }
        val size = info.getLong("size")
        check(size > 0) { "EXECUTION_ARTIFACT_EMPTY" }
        val origin = java.net.URI("execution", proj.id, "/${proj.generation}/$path", null).toASCIIString()
        remote.openArtifactStream(path, size, token).use { input ->
            dev.wind.agent.artifact.ExecutionArtifactPublisher(context.cacheDir.canonicalFile, store)
                .publish(input, size, origin) { token.throwIfCancelled(); verify() }
        }
    }

    /** Fetches bounded manifest pages and reconstructs only an in-memory index. */
    private fun remoteManifestLocked(proj: VegaProject, token: CancellationToken? = null): RemoteSnapshot {
        val map = linkedMapOf<String, ManifestEntry>()
        var offset = 0
        var advertisedDigest = ""
        var total = -1
        while (true) {
            val page = client(proj).call(
                "manifest_page",
                JSONObject().put("offset", offset).put("limit", 2048)
                    .put("expectedDigest", advertisedDigest),
                token = token
            )
            val entries = page.getJSONArray("entries")
            advertisedDigest = page.optString("digest", advertisedDigest)
            total = page.optInt("totalEntries", total)
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val path = item.getString("path")
                val entry = ManifestEntry(
                    path,
                    item.getBoolean("directory"),
                    item.getLong("size"),
                    item.optLong("modified", 0L),
                    item.optString("sha256", "").takeIf { ManifestDigest.isDigest(it) }
                )
                require(map.put(path, entry) == null) { "MIRROR_DUPLICATE_MANIFEST_PATH" }
                require(map.size <= WorkspaceManifestScanner.MAX_FILES) { "WORKSPACE_FILE_LIMIT" }
            }
            val next = page.optInt("nextOffset", -1)
            if (next < 0) break
            require(next > offset && entries.length() > 0) { "MIRROR_MANIFEST_PAGING_STALLED" }
            offset = next
        }
        require(total < 0 || total == map.size) { "MIRROR_MANIFEST_COUNT_MISMATCH" }
        val digest = ManifestDigest.of(map)
        require(advertisedDigest.isBlank() || advertisedDigest == digest) { "MIRROR_MANIFEST_DIGEST_MISMATCH" }
        return RemoteSnapshot(map, digest)
    }

    private fun mappedPath(mount: WorkspaceMount, relative: String): String {
        val prefix = mount.executionPath.trim('/')
        return if (relative.isEmpty()) prefix else "$prefix/$relative"
    }

    private fun sameContent(a: ManifestEntry?, b: ManifestEntry?): Boolean {
        if (a == null || b == null) return a == b
        if (a.directory != b.directory) return false
        return a.directory || (a.size == b.size && a.sha256 != null && a.sha256 == b.sha256)
    }

    private fun syncArguments(
        canonical: CanonicalSnapshot,
        remote: RemoteSnapshot,
        sources: MutableList<() -> InputStream>
    ): JSONObject {
        val targetPaths = canonical.flat.keys
        val pathsToDelete = remote.entries.keys.filter { path ->
            path !in targetPaths ||
                (canonical.flat[path]?.directory != remote.entries[path]?.directory)
        }.toSet()
            .sortedWith(compareByDescending<String> { it.count { c -> c == '/' } }.thenByDescending { it.length })

        val transferItems = mutableListOf<JSONObject>()
        var transferFiles = 0
        var transferBytes = 0L
        canonical.flat.entries.sortedWith(
            compareBy<Map.Entry<String, ManifestEntry>> { if (it.value.directory) 0 else 1 }
                .thenBy { it.key.count { c -> c == '/' } }.thenBy { it.key }
        ).forEach { (path, entry) ->
            if (sameContent(remote.entries[path], entry)) return@forEach
            val item = JSONObject().put("path", path).put("directory", entry.directory)
                .put("size", entry.size).put("sha256", entry.sha256 ?: "")
            if (!entry.directory) {
                item.put("source", sources.size)
                sources.add(canonical.sources[path] ?: error("MIRROR_SOURCE_MISSING"))
                transferFiles++
                transferBytes += entry.size
            }
            transferItems += item
        }

        // Plan metadata travels as bounded source pages instead of one giant
        // control JSON document. All pages are consumed by one helper process
        // under one flock, so paging cannot introduce a remote race between
        // delete/write phases.
        val planSources = JSONArray()
        fun addPages(key: String, values: List<Any>) {
            var page = JSONArray()
            fun flush() {
                if (page.length() == 0) return
                val payload = JSONObject().put(key, page).toString().toByteArray(Charsets.UTF_8)
                require(payload.size <= SYNC_PLAN_PAGE_BYTES) { "MIRROR_SYNC_PLAN_ENTRY_TOO_LARGE" }
                val index = sources.size
                sources.add { ByteArrayInputStream(payload) }
                planSources.put(index)
                page = JSONArray()
            }
            values.forEach { value ->
                val candidate = JSONArray().apply {
                    for (i in 0 until page.length()) put(page.get(i))
                    put(value)
                }
                val candidateBytes = JSONObject().put(key, candidate).toString()
                    .toByteArray(Charsets.UTF_8)
                if (page.length() >= SYNC_PLAN_PAGE_LIMIT ||
                    candidateBytes.size > SYNC_PLAN_PAGE_BYTES) {
                    require(page.length() > 0) { "MIRROR_SYNC_PLAN_ENTRY_TOO_LARGE" }
                    flush()
                    page.put(value)
                } else {
                    page = candidate
                }
            }
            flush()
        }
        addPages("deleted", pathsToDelete)
        addPages("entries", transferItems)
        return JSONObject()
            .put("planSources", planSources)
            .put("planPageCount", planSources.length())
            .put("plannedFiles", transferFiles)
            .put("plannedBytes", transferBytes)
            .put("plannedDeleted", pathsToDelete.size)
            .put("expectedRemoteDigest", remote.digest)
    }

    private fun prepareSessionLocked(
        proj: VegaProject,
        router: WorkspaceRouter,
        token: CancellationToken?,
        forceSync: Boolean,
        beforeSideEffect: (() -> Unit)?
    ): MirrorState {
        val store = stateStore(proj)
        val topology = computeTopologyHash(proj)
        val bound = if (!isLegacyFactory) resolveRuntimeBindingLocked(proj) else null
        var state = store.load(proj.generation, topology)
            ?: MirrorState.clean(proj.id, proj.generation, topology).copy(runtimeBinding = bound?.binding)
        if (state.runtimeBinding == null && bound != null) {
            state = state.copy(runtimeBinding = bound.binding)
            store.save(state)
        }

        if (!forceSync && state.phase in setOf(MirrorPhase.BROKEN, MirrorPhase.SYNCING,
                MirrorPhase.MUTATING, MirrorPhase.APPLYING, MirrorPhase.DISCARDING)) {
            state = reconcileInterruptedLocked(proj, state, router, token, null, beforeSideEffect)
        }

        if (!forceSync) {
            when (state.phase) {
                MirrorPhase.PENDING -> {
                    if (state.projectGeneration != proj.generation || state.topologyHash != topology) {
                        throw IllegalStateException("PENDING_TOPOLOGY_CHANGE: project topology changed while execution edits are pending. Apply/discard before changing capabilities.")
                    }
                    validateSessionCapabilities(state, proj, router)
                    return state
                }
                MirrorPhase.BROKEN, MirrorPhase.SYNCING, MirrorPhase.MUTATING,
                MirrorPhase.APPLYING, MirrorPhase.DISCARDING ->
                    throw IllegalStateException("EXECUTION_RECOVERY_REQUIRED: execution state is ${state.phase}; use execution_recover or discard before continuing.")
                MirrorPhase.REBINDING ->
                    throw IllegalStateException("RUNTIME_REBIND_IN_PROGRESS: runtime migration must finish or recover before execution.")
                MirrorPhase.CLEAN -> Unit
            }
        }

        val started = System.currentTimeMillis()
        val canonical = scanCanonicalLocked(proj, router)
        val remoteBefore = remoteManifestLocked(proj, token)
        val sessionMounts = canonical.mounts.values.map { (mount, _) ->
            MirrorSessionMount(mount.id, mount.treeUri, mount.mode.name, mount.executionPath)
        }
        val discardState = if (forceSync && state.pending != null) {
            state.copy(phase = MirrorPhase.DISCARDING, sessionMounts = sessionMounts)
        } else state
        state = discardState.copy(
            phase = MirrorPhase.SYNCING,
            projectGeneration = proj.generation,
            topologyHash = topology,
            canonicalBaselineDigest = canonical.digest,
            remoteDigest = remoteBefore.digest,
            sessionMounts = sessionMounts,
            lastOperation = operation("sync", if (forceSync) "discard" else "prepare", "STARTED", started)
        )
        store.save(state)

        val sources = mutableListOf<() -> InputStream>()
        try {
            val args = syncArguments(canonical, remoteBefore, sources)
            val planPageCount = args.optInt("planPageCount", 0)
            var syncResult = JSONObject()
            if (planPageCount > 0) {
                // A provider that cannot carry the bounded streaming plane is
                // rejected before the mirror is touched: discovering the gap at
                // the first transaction would leave half a sync behind.
                val streamVersion = client(proj).mirrorStreamProtocolVersion
                require(streamVersion >= RuntimeRegistry.MIN_MIRROR_STREAM_PROTOCOL_VERSION) {
                    "RUNTIME_MIRROR_DATA_PLANE_UNAVAILABLE"
                }
                beforeSideEffect?.invoke()
                syncResult = client(proj).call("sync", args, sources, token)
            }
            token?.throwIfCancelled()
            val remoteAfter = remoteManifestLocked(proj, token)
            require(remoteAfter.digest == ManifestDigest.of(canonical.flat)) { "MIRROR_SYNC_REMOTE_VERIFY_FAILED" }
            val canonicalAfter = scanCanonicalLocked(proj, router)
            require(canonicalAfter.digest == canonical.digest) { "CANONICAL_CHANGED_DURING_SYNC" }
            val finalState = state.copy(
                phase = MirrorPhase.CLEAN,
                canonicalBaselineDigest = canonicalAfter.digest,
                remoteDigest = remoteAfter.digest,
                sessionMounts = canonicalAfter.mounts.values.map { (mount, _) ->
                    MirrorSessionMount(mount.id, mount.treeUri, mount.mode.name, mount.executionPath)
                },
                pending = null,
                lastOperation = operation(
                    "sync", if (forceSync) "discard" else "prepare", "OK", started,
                    scanned = canonicalAfter.flat.size,
                    transferred = syncResult.optInt("transferredFiles", args.optInt("plannedFiles", 0)),
                    bytes = syncResult.optLong("transferredBytes", args.optLong("plannedBytes", 0L)),
                    deleted = syncResult.optInt("deletedPaths", args.optInt("plannedDeleted", 0))
                )
            )
            store.save(finalState)
            return finalState
        } catch (e: Exception) {
            broken(proj, state, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /**
     * Observe an execution mirror for a read operation without granting that
     * read any synchronization authority.  The ordinary prepare path is
     * intentionally not reused here: it is allowed to call the irreversible
     * remote `sync` operation when a CLEAN mirror differs from canonical SAF.
     * A MirrorRead lease must instead fail closed until an execution_prepare (or
     * an explicit recovery path) has established a stable session.
     */
    private fun observeExecutionReadLocked(
        proj: VegaProject,
        router: WorkspaceRouter,
        token: CancellationToken?
    ): MirrorState {
        val state = loadState(proj) ?: throw IllegalStateException("MIRROR_NOT_PREPARED")
        when (state.phase) {
            MirrorPhase.PENDING, MirrorPhase.CLEAN -> Unit
            MirrorPhase.BROKEN -> throw IllegalStateException(
                "MIRROR_BROKEN: " + (state.lastOperation?.result ?: "reason unknown")
            )
            else -> throw IllegalStateException("MIRROR_NOT_CLEAN: ${state.phase}")
        }

        validateSessionCapabilities(state, proj, router)
        val baseline = state.canonicalBaselineDigest
            ?: throw IllegalStateException("MIRROR_NOT_PREPARED")
        val canonical = scanCanonicalLocked(proj, router)
        if (canonical.digest != baseline) {
            throw IllegalStateException(
                "MIRROR_NOT_PREPARED: canonical roots changed; run execution_prepare before reading the execution mirror"
            )
        }

        val expectedRemote = state.remoteDigest
            ?: throw IllegalStateException("MIRROR_NOT_PREPARED")
        val liveRemote = remoteManifestLocked(proj, token)
        if (liveRemote.digest != expectedRemote) {
            // Recording loss of trust is local bookkeeping, not a mirror sync;
            // the read remains fail-closed and no remote mutation is attempted.
            broken(proj, state, "MIRROR_INTEGRITY_LOST")
            throw IllegalStateException("MIRROR_INTEGRITY_LOST")
        }
        return state
    }

    fun prepareSession(
        router: WorkspaceRouter,
        token: CancellationToken? = null,
        forceSync: Boolean = false,
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject = withActiveProject { proj ->
        prepareSessionLocked(proj, router, token, forceSync, beforeSideEffect).toJson()
    }

    fun resolveExecutionVirtualPath(rawPath: String): String = withActiveProject { proj ->
        val raw = rawPath.removePrefix("execution://").replace('\\', '/').trim('/')
        val slash = raw.indexOf('/')
        val candidateAlias = if (slash < 0) raw else raw.substring(0, slash)
        val matchingMount = proj.findMount(candidateAlias)
        if (matchingMount != null) {
            val relRaw = if (slash < 0) "" else raw.substring(slash + 1)
            val rel = WorkspacePath.sanitize(relRaw)
            return@withActiveProject if (rel.isEmpty()) matchingMount.executionPath else "${matchingMount.executionPath}/$rel"
        }
        val clean = WorkspacePath.sanitize(raw)
        val primary = proj.primaryMount() ?: throw IllegalStateException("No primary mount")
        if (clean.isEmpty()) "" else "${primary.executionPath}/$clean"
    }

    private fun beginMutation(proj: VegaProject, state: MirrorState, taskId: String, executionId: String): MirrorState {
        val remoteDigest = state.remoteDigest ?: error("MIRROR_REMOTE_DIGEST_MISSING")
        require(state.canonicalBaselineDigest != null) { "MIRROR_BASELINE_DIGEST_MISSING" }
        val pending = PendingExecution(executionId, taskId, taskId, remoteDigest, false, System.currentTimeMillis())
        val next = state.copy(
            phase = MirrorPhase.MUTATING,
            pending = pending,
            lastOperation = operation(executionId, "mutation", "MUTATING", System.currentTimeMillis())
        )
        saveState(proj, next)
        return next
    }

    private fun completeMutation(proj: VegaProject, state: MirrorState, remote: RemoteSnapshot, eligible: Boolean): MirrorState {
        val pending = state.pending ?: throw IllegalStateException("MIRROR_PENDING_RECORD_MISSING")
        val next = state.copy(
            phase = MirrorPhase.PENDING,
            remoteDigest = remote.digest,
            pending = pending.copy(remoteDigest = remote.digest, eligible = eligible),
            lastOperation = operation(pending.executionId, "mutation", if (eligible) "CHANGES_PENDING" else "FAILED", System.currentTimeMillis())
        )
        saveState(proj, next)
        return next
    }

    /** A canonical dependency publication may not invalidate a pending mirror transaction. */
    fun assertCanonicalDependencyInstallSafe(): Unit = withActiveProject { proj ->
        val state = loadState(proj)
        require(state == null || state.phase == MirrorPhase.CLEAN) { "MAVEN_INSTALL_MIRROR_PENDING" }
    }

    fun tool(
        name: String,
        args: JSONObject,
        router: WorkspaceRouter,
        taskId: String,
        token: CancellationToken,
        beforeSideEffect: (() -> Unit)? = null
    ): String = withActiveProject { proj ->
        var state = loadState(proj) ?: MirrorState.clean(proj.id, proj.generation, computeTopologyHash(proj))
        if (name == "execution_prepare") {
            if (state.phase in setOf(MirrorPhase.PENDING, MirrorPhase.BROKEN, MirrorPhase.SYNCING,
                    MirrorPhase.MUTATING, MirrorPhase.APPLYING, MirrorPhase.DISCARDING)) {
                state = reconcileInterruptedLocked(proj, state, router, token, taskId, beforeSideEffect)
                if (state.phase == MirrorPhase.PENDING) {
                    return@withActiveProject "OK: Mirror reconciled; existing edits preserved. Status: CHANGES_PENDING. Execution ID: ${state.pending!!.executionId}"
                }
            }
            if (state.phase != MirrorPhase.CLEAN) {
                return@withActiveProject "EXECUTION_NOT_CLEAN: State is ${state.phase}. Apply/discard pending edits or use execution_recover first."
            }
            prepareSessionLocked(proj, router, token, true, beforeSideEffect)
            return@withActiveProject "OK: Multi-Root project mirror prepared and synchronized with canonical roots."
        }
        if (name == "execution_recover") {
            if (state.phase == MirrorPhase.CLEAN) return@withActiveProject "ERROR: No interrupted execution requires recovery."
            state = reconcileInterruptedLocked(proj, state, router, token, taskId, beforeSideEffect)
            return@withActiveProject if (state.phase == MirrorPhase.CLEAN)
                "OK: Mirror reconciled with canonical roots. Status: CLEAN."
            else state.pending!!.let {
                "OK: Mirror session recovered; edits preserved. Status: CHANGES_PENDING. Apply eligible: ${it.eligible}. Execution ID: ${it.executionId}"
            }
        }

        state = if (name in READ_ONLY_EXECUTION_TOOLS) {
            observeExecutionReadLocked(proj, router, token)
        } else {
            prepareSessionLocked(proj, router, token, false, beforeSideEffect)
        }
        val remoteArgs = JSONObject(args.toString())
        val rawPath = args.optString("path", "")
        if (rawPath.isNotEmpty()) remoteArgs.put("path", resolvePathInProject(proj, rawPath))
        val mutating = name in setOf(
            "execution_write_file", "execution_edit_file", "execution_delete_file",
            "execution_make_dir", "execution_import_archive"
        )
        var operationName = name
        val sources = mutableListOf<() -> InputStream>()
        if (name == "execution_import_archive") {
            val resolved = router.resolve(args.getString("workspace_path"), write = false)
            val stat = resolved.fs.stat(resolved.relativePath) ?: error("Archive not found")
            require(!stat.isDirectory && stat.size >= 0L)
            val format = when {
                resolved.relativePath.endsWith(".zip", true) -> "zip"
                resolved.relativePath.endsWith(".tar", true) || resolved.relativePath.endsWith(".tar.gz", true) -> "tar"
                else -> error("ARCHIVE_FORMAT_UNSUPPORTED: zip, tar, tar.gz only")
            }
            remoteArgs.put("size", stat.size).put("format", format)
                .put("destination", resolvePathInProject(proj, args.optString("destination", "")))
            sources.add { resolved.fs.openInputStream(resolved.relativePath) }
        } else if (name == "execution_edit_file") {
            val oldText = client(proj).call("fetch_text", remoteArgs, token = token).getString("text")
            remoteArgs.put("content", TextEditEngine.apply(oldText, args)).put("overwrite", true)
            operationName = "execution_write_file"
        }

        if (!mutating) {
            return@withActiveProject client(proj).call(operationName, remoteArgs, sources, token = token).toString()
        }

        val execId = "proj-tool-" + UUID.randomUUID().toString().replace("-", "")
        state = beginMutation(proj, state, taskId, execId)
        try {
            beforeSideEffect?.invoke()
            val result = client(proj).call(operationName, remoteArgs, sources, token = token)
            val remoteAfter = remoteManifestLocked(proj, token)
            completeMutation(proj, state, remoteAfter, true)
            return@withActiveProject result.toString() + "\nExecution ID: $execId\n[CHANGES PENDING: Review execution diff and use shell_apply_changes]"
        } catch (e: Exception) {
            broken(proj, state, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun resolvePathInProject(proj: VegaProject, rawPath: String): String {
        val raw = rawPath.removePrefix("execution://").replace('\\', '/').trim('/')
        val slash = raw.indexOf('/')
        val alias = if (slash < 0) raw else raw.substring(0, slash)
        val mount = proj.findMount(alias)
        if (mount != null) {
            val relative = WorkspacePath.sanitize(if (slash < 0) "" else raw.substring(slash + 1))
            return mappedPath(mount, relative)
        }
        val clean = WorkspacePath.sanitize(raw)
        val primary = proj.primaryMount() ?: error("No primary mount")
        return if (clean.isEmpty()) "" else mappedPath(primary, clean.removePrefix(primary.executionPath + "/"))
    }

    fun execute(
        taskId: String,
        request: ShellRequest,
        router: WorkspaceRouter,
        token: CancellationToken,
        beforeExecute: (() -> Unit)? = null,
        afterPrepare: (() -> Unit)? = null,
        preparedFiles: Map<String, String> = emptyMap()
    ): ShellResult = withActiveProject { proj ->
        ShellPolicy.validateRequest(request)
        require(preparedFiles.size <= 8 && preparedFiles.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 131072L) {
            "EXECUTION_SUPPORT_FILES_INVALID"
        }
        preparedFiles.keys.forEach { path ->
            require(path.startsWith(".harnessmate-package-cache/") &&
                WorkspacePath.sanitize(path) == path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "EXECUTION_SUPPORT_PATH_INVALID"
            }
        }
        // PENDING means an earlier command already left a real mirror diff.
        // Its post-command digest must remain visible to P4; refreshing the
        // coding baseline in that state would hide an unaccounted build/codegen
        // mutation. CLEAN (or no state) is the prepare/synchronise path where
        // the baseline may safely be established after sync.
        val refreshBaselineAfterPrepare = loadState(proj)?.phase?.let { it == MirrorPhase.CLEAN } ?: true
        var state = prepareSessionLocked(proj, router, token, false, beforeExecute)
        // A cold mirror may have been created/synchronised by prepareSessionLocked.
        // Give callers a post-sync hook so a source baseline is never captured
        // from the pre-sync manifest while retaining the pre-side-effect lease
        // check above.
        if (refreshBaselineAfterPrepare) {
            afterPrepare?.invoke()
        }
        val priorEligible = state.pending?.eligible == true
        val priorDigest = state.remoteDigest
        val execId = "proj-exec-" + UUID.randomUUID().toString().replace("-", "")
        val primary = proj.primaryMount() ?: throw IllegalStateException("No primary mount")
        val cwd = when {
            request.cwd == "workspace://" -> primary.executionPath
            request.cwd.startsWith("execution://") -> resolveExecutionVirtualPath(request.cwd)
            else -> throw IllegalArgumentException("EXECUTION_CWD_INVALID")
        }
        val target = ExecutionTarget(remoteId(proj), ProjectIdentity.remoteRoot(proj.id) + "/project", cwd)
        state = beginMutation(proj, state, taskId, execId)
        try {
            beforeExecute?.invoke()
            // Stage app-authored Gradle helpers after sync and under this exact execution/WAL.
            // No separate mirror mutation, source-baseline refresh or apply-eligibility reset.
            for ((path, content) in preparedFiles) {
                token.throwIfCancelled()
                beforeExecute?.invoke()
                client(proj).call("execution_write_file", JSONObject()
                    .put("path", if (cwd.isEmpty()) path else cwd.trimEnd('/') + "/" + path)
                    .put("content", content), token = token)
            }
            if (preparedFiles.isNotEmpty()) { token.throwIfCancelled(); beforeExecute?.invoke() }
            val bound = if (!isLegacyFactory) resolveRuntimeBindingLocked(proj) else null
            val result = if (bound != null && bound.binding.providerId in setOf(RuntimeProviderId.VEGA_COMPANION, RuntimeProviderId.VEGA_LINUX)) {
                val spec = RuntimeCommandSpec(
                    executable = request.executable,
                    args = request.args,
                    workDir = target.cwdRelative,
                    env = request.env,
                    timeoutMs = request.timeoutMs,
                    network = request.network,
                    networkDomains = request.networkDomains
                )
                val cmdResult = bound.runtime.commandExecutor(bound.binding).execute(bound.binding, spec, token)
                ShellResult(
                    exitCode = cmdResult.exitCode ?: -1,
                    stdout = cmdResult.stdout,
                    stderr = cmdResult.stderr,
                    elapsedMs = cmdResult.elapsedMs,
                    timedOut = cmdResult.timedOut,
                    cancelled = cmdResult.cancelled,
                    error = cmdResult.error
                )
            } else if (bound != null && bound.binding.providerId == RuntimeProviderId.TERMUX) {
                val spec = RuntimeCommandSpec(
                    executable = request.executable,
                    args = request.args,
                    workDir = target.cwdRelative,
                    env = request.env,
                    timeoutMs = request.timeoutMs,
                    network = request.network,
                    networkDomains = request.networkDomains
                )
                val cmdResult = bound.runtime.commandExecutor(bound.binding).execute(bound.binding, spec, token)
                ShellResult(
                    exitCode = cmdResult.exitCode ?: -1,
                    stdout = cmdResult.stdout,
                    stderr = cmdResult.stderr,
                    elapsedMs = cmdResult.elapsedMs,
                    timedOut = cmdResult.timedOut,
                    cancelled = cmdResult.cancelled,
                    error = cmdResult.error
                )
            } else {
                TermuxBackend(context).execute(request, target, token)
            }
            // A failed assertion does not revoke an earlier safe staging result
            // when the harness check left the content unchanged. Failed shell
            // edits, timeouts, cancellations and source-mutating checks remain
            // ineligible for automatic application.
            token.throwIfCancelled()
            if (result.cancelled) throw CancellationToken.CancelledException()
            val remoteAfter = remoteManifestLocked(proj, token)
            val eligible = !result.timedOut && !result.cancelled && result.error == null &&
                (result.exitCode == 0 || request.validationCheck && priorEligible && remoteAfter.digest == priorDigest)
            completeMutation(proj, state, remoteAfter, eligible)
            result.copy(diffSummary = "Execution ID: $execId\nMulti-Root project execution completed.\nUse shell_apply_changes to review and commit.")
        } catch (e: Exception) {
            broken(proj, state, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun entryFingerprint(entry: ManifestEntry?): String = if (entry == null) "-" else
        listOf(entry.directory, entry.size, entry.sha256 ?: "").joinToString(":")

    private fun collectChanges(
        proj: VegaProject,
        canonical: CanonicalSnapshot,
        remote: RemoteSnapshot
    ): List<ApplyChange> {
        val changes = mutableListOf<ApplyChange>()
        for (mount in proj.mounts) {
            val pair = canonical.mounts[mount.id] ?: continue
            val entries = pair.second.entries
            val prefix = mount.executionPath.trim('/')
            val paths = linkedSetOf<String>()
            paths.addAll(entries.keys)
            remote.entries.keys.filter { it == prefix || it.startsWith("$prefix/") }
                .forEach { paths.add(if (it == prefix) "" else it.substring(prefix.length + 1)) }
            for (relative in paths.sorted()) {
                val mapped = mappedPath(mount, relative)
                val canonicalEntry = if (relative.isEmpty()) canonical.flat[mapped] else entries[relative]
                val remoteEntry = remote.entries[mapped]
                if (sameContent(canonicalEntry, remoteEntry)) continue
                if (mount.mode == MountMode.READ_ONLY) {
                    throw IllegalStateException("READ_ONLY_MOUNT_MUTATION: Change detected in read-only mount '${mount.alias}' ($mapped). Apply rejected with 0 writes.")
                }
                if (canonicalEntry?.directory == true && remoteEntry == null) {
                    throw IllegalStateException("DIRECTORY_DELETE_PROHIBITED: Canonical directory deletion is disabled in this release ($mapped).")
                }
                if (canonicalEntry != null && remoteEntry != null && canonicalEntry.directory != remoteEntry.directory) {
                    throw IllegalStateException("TYPE_CHANGE_UNSUPPORTED: File/directory type replacement is not atomic ($mapped).")
                }
                changes.add(ApplyChange(mount, relative, canonicalEntry, remoteEntry))
            }
        }
        return changes
    }

    private fun planFrom(
        executionId: String,
        changes: List<ApplyChange>
    ): ApplyPlan {
        val mounts = changes.map { it.mount.id }.toSet()
        var created = 0
        var modified = 0
        var deleted = 0
        val input = StringBuilder()
        for (change in changes.sortedWith(compareBy<ApplyChange> { it.mount.id }.thenBy { it.relativePath })) {
            val c = change.canonical
            val r = change.remote
            if (c == null && r != null && !r.directory) created++
            else if (c != null && r == null && !c.directory) deleted++
            else if (c != null && r != null && !r.directory) modified++
            input.append(change.mount.id).append('|').append(change.relativePath).append('|')
                .append(entryFingerprint(c)).append('|').append(entryFingerprint(r)).append(';')
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return ApplyPlan(executionId, mounts, created, modified, deleted, deleted > 0, digest)
    }

    private fun previewApplyLocked(executionId: String, proj: VegaProject, router: WorkspaceRouter): ApplyPlan {
        val state = loadState(proj) ?: error("No pending execution state found")
        validateSessionCapabilities(state, proj, router)
        check(state.phase == MirrorPhase.PENDING && state.pending != null) { "EXECUTION_NOT_PENDING" }
        check(state.pending.executionId == executionId) { "STALE_EXECUTION_ID" }
        check(state.pending.eligible) { "APPLY_NOT_ALLOWED: Command failed/cancelled or recovered session not revalidated" }
        val remote = remoteManifestLocked(proj)
        check(remote.digest == state.pending.remoteDigest) {
            "STALE_MIRROR_DIFF: Remote mirror changed after execution. Review and re-execute."
        }
        val canonical = scanCanonicalLocked(proj, router)
        val baselineDigest = requireNotNull(state.canonicalBaselineDigest) { "MIRROR_BASELINE_DIGEST_MISSING" }
        check(canonical.digest == baselineDigest) {
            "CONFLICT: Canonical project changed after execution began. Apply rejected with 0 writes."
        }
        return planFrom(executionId, collectChanges(proj, canonical, remote))
    }

    fun previewApply(executionId: String, router: WorkspaceRouter): ApplyPlan =
        withActiveProject { previewApplyLocked(executionId, it, router) }

    /**
     * The content digest of the execution mirror as it stands right now.
     *
     * Read-only: it asks the mirror for its manifest and digests it, and changes
     * no state, so it is safe to call between edits. Primary use is P4's
     * verification stamp — "has the source moved since this was verified" has to
     * be answerable without preparing a session or making a change, and it has to
     * be answerable the same way the commit gate will answer it later.
     *
     * Throws when no session exists or the mirror is unreachable; callers that
     * only need a best-effort answer catch that.
     */
    fun currentMirrorDigest(router: WorkspaceRouter): String? = currentMirrorDigest(router, null)

    fun currentMirrorDigest(router: WorkspaceRouter, token: CancellationToken?): String? {
        token?.throwIfCancelled()
        return withActiveProject { proj ->
            token?.throwIfCancelled()
            remoteManifestLocked(proj, token).digest
        }
    }

    /** Re-read one canonical path immediately before its WAL transaction. */
    private fun canonicalEntryAt(fs: WorkspaceFs, path: String): ManifestEntry? {
        try {
            val before = fs.stat(path) ?: return null
            val digest = if (before.isDirectory) null else {
                val sha = MessageDigest.getInstance("SHA-256")
                fs.openInputStream(path).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count > 0) { "CANONICAL_READ_STALLED" }
                        sha.update(buffer, 0, count)
                    }
                }
                sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            val after = fs.stat(path)
            if (after == null || after.isDirectory != before.isDirectory ||
                after.size != before.size || after.lastModified != before.lastModified) {
                throw MutationPreconditionConflict("CANONICAL_CHANGED_DURING_APPLY: $path")
            }
            return ManifestEntry(path, before.isDirectory, if (before.isDirectory) 0L else before.size,
                before.lastModified.coerceAtLeast(0L), digest)
        } catch (e: MutationPreconditionConflict) {
            throw e
        } catch (e: Exception) {
            throw MutationPreconditionConflict("CANONICAL_PRECONDITION_UNAVAILABLE: $path")
        }
    }

    private fun assertCanonicalBefore(fs: WorkspaceFs, change: ApplyChange) {
        val live = canonicalEntryAt(fs, change.relativePath)
        if (!sameContent(change.canonical, live)) {
            throw MutationPreconditionConflict("CANONICAL_CHANGED_DURING_APPLY: ${change.relativePath}")
        }
    }

    fun apply(executionId: String, router: WorkspaceRouter, journal: MutationJournal): String =
        withActiveProject { proj ->
            val plan = previewApplyLocked(executionId, proj, router)
            val store = stateStore(proj)
            var state = loadState(proj) ?: error("No pending execution state found")
            val pending = state.pending ?: error("MIRROR_PENDING_RECORD_MISSING")
            val capability = CapabilityBrokerRegistry.get(context)
            val capabilityContext = TaskContext(
                pending.taskId, proj.id, proj.generation, CapabilityMode.ACCEPT, pending.rootTaskId
            )
            val requirement = CapabilityRequirement.ApplyChanges(
                proj.id, executionId, plan.changedMountIds, plan.diffFingerprint, plan.hasDestructiveChanges
            )
            val applyLease = when (val capabilityCheck = capability.check(capabilityContext, requirement)) {
                is CapabilityCheck.Allowed -> {
                    capability.verify(capabilityCheck.lease)
                    capabilityCheck.lease
                }
                is CapabilityCheck.NeedsApproval -> throw IllegalStateException("CAPABILITY_APPROVAL_REQUIRED: ${capabilityCheck.request.summary}")
                is CapabilityCheck.Denied -> throw IllegalStateException("CAPABILITY_DENIED: ${capabilityCheck.reason}")
            }

            // Re-read both sides after obtaining approval, then make APPLYING
            // durable before the first canonical mutation.
            val remote = remoteManifestLocked(proj)
            check(remote.digest == pending.remoteDigest) { "STALE_MIRROR_DIFF: Remote mirror changed after execution. Review and re-execute." }
            val canonical = scanCanonicalLocked(proj, router)
            val baselineDigest = requireNotNull(state.canonicalBaselineDigest) { "MIRROR_BASELINE_DIGEST_MISSING" }
            check(canonical.digest == baselineDigest) {
                "CONFLICT: Canonical project changed after execution began. Apply rejected with 0 writes."
            }
            val changes = collectChanges(proj, canonical, remote)
            check(planFrom(executionId, changes).diffFingerprint == plan.diffFingerprint) { "STALE_MIRROR_DIFF" }
            state = state.copy(
                phase = MirrorPhase.APPLYING,
                lastOperation = operation(executionId, "apply", "APPLYING", System.currentTimeMillis())
            )
            store.save(state)

            val changesByMount = changes.groupBy { it.mount }.mapValues { (_, value) -> value.toMutableList() }
            var appliedRemoteDigest = remote.digest
            synchronized(MutationJournal.MUTATION_LOCK) {
                capability.verify(applyLease)
                val batchId = "batch-" + UUID.randomUUID().toString()
                val batch = journal.createBatch(batchId, pending.taskId)
                try {
                    for ((mount, mountChanges) in changesByMount) {
                        val fs = router.getFsForMount(mount)
                        val scope = MutationScope(proj.id, mount.id, mount.treeUri)
                        val ordered = mountChanges.sortedWith(
                            compareBy<ApplyChange> {
                                when {
                                    it.remote == null -> 2
                                    it.remote.directory -> 0
                                    else -> 1
                                }
                            }.thenBy { it.relativePath.count { c -> c == '/' } }
                                .thenByDescending { if (it.remote == null) it.relativePath.count { c -> c == '/' } else 0 }
                        )
                        for (change in ordered) {
                            val remoteEntry = change.remote
                            when {
                                remoteEntry == null -> check(
                                    journal.transactionalDelete(scope, fs, pending.taskId, change.relativePath, batchId = batchId) {
                                        assertCanonicalBefore(fs, change)
                                        capability.verify(applyLease)
                                    fs.delete(change.relativePath)
                                    }
                                )
                                remoteEntry.directory -> check(
                                    journal.transactionalMkdir(scope, fs, pending.taskId, change.relativePath, batchId = batchId) {
                                        assertCanonicalBefore(fs, change)
                                        capability.verify(applyLease)
                                        fs.mkdir(change.relativePath)
                                    }
                                )
                                else -> {
                                    val remotePath = mappedPath(mount, change.relativePath)
                                    val hash = remoteEntry.sha256 ?: error("MIRROR_REMOTE_HASH_MISSING")
                                    MirrorFileTransfer.withStagedFile(context.filesDir, client(proj), remotePath,
                                        remoteEntry.size, hash) { staged ->
                                        check(journal.transactionalBinaryStream(scope, fs, pending.taskId,
                                            change.relativePath, hash, remoteEntry.size,
                                            overwrite = change.canonical != null, batchId = batchId,
                                            beforeWrite = {
                                                assertCanonicalBefore(fs, change)
                                                capability.verify(applyLease)
                                            }) { output ->
                                            staged.inputStream().use { it.copyTo(output, 64 * 1024) }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    val remoteAtCommit = remoteManifestLocked(proj)
                    check(remoteAtCommit.digest == remote.digest) {
                        "MIRROR_CHANGED_DURING_APPLY: Remote mirror changed before commit."
                    }
                    appliedRemoteDigest = remoteAtCommit.digest
                    val after = scanCanonicalLocked(proj, router)
                    check(after.digest == remoteAtCommit.digest) {
                        val mismatches = (after.flat.keys + remoteAtCommit.entries.keys).sorted()
                            .filter { !sameContent(after.flat[it], remoteAtCommit.entries[it]) }
                        "CANONICAL_APPLY_VERIFY_FAILED: ${mismatches.size} mismatched paths; " +
                            mismatches.take(8).joinToString(", ") { path ->
                                "$path [canonical=${entryFingerprint(after.flat[path])}, mirror=${entryFingerprint(remoteAtCommit.entries[path])}]"
                            }
                    }
                    journal.commitBatch(batch)
                } catch (e: Exception) {
                    val rollback = runCatching { journal.rollbackBatch(batch) }
                    // A fully restored baseline is a retryable pending diff,
                    // not a damaged mirror. Prove both sides before rearming.
                    val restored = rollback.isSuccess &&
                        batch.state == dev.wind.agent.history.BatchState.ROLLED_BACK &&
                        runCatching {
                            scanCanonicalLocked(proj, router).digest == baselineDigest &&
                                remoteManifestLocked(proj).digest == remote.digest
                        }.getOrDefault(false)
                    state = if (restored) state.copy(
                        phase = MirrorPhase.PENDING,
                        lastOperation = operation(executionId, "apply",
                            "APPLY_ROLLED_BACK: " + (e.message ?: e.javaClass.simpleName), System.currentTimeMillis())
                    ).also { saveState(proj, it) }
                    else broken(proj, state, e.message ?: e.javaClass.simpleName)
                    throw e
                }
            }

            val final = state.copy(
                phase = MirrorPhase.CLEAN,
                canonicalBaselineDigest = appliedRemoteDigest,
                remoteDigest = appliedRemoteDigest,
                pending = null,
                lastOperation = operation(executionId, "apply", "OK", System.currentTimeMillis())
            )
            store.save(final)
            "OK: Successfully applied Multi-Root changes across project mounts."
        }

    fun discard(
        executionId: String,
        router: WorkspaceRouter,
        beforeSideEffect: (() -> Unit)? = null
    ): String = withActiveProject { proj ->
        val state = loadState(proj) ?: error("No pending execution state found")
        check(state.pending?.executionId == executionId) { "STALE_EXECUTION_ID" }
        validateSessionIdentity(state, proj)
        prepareSessionLocked(proj, router, null, true, beforeSideEffect)
        "OK: Pending execution $executionId physically discarded and mirror restored from canonical roots."
    }

    fun prepareForCodeIntelligence(
        router: WorkspaceRouter,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): PreparedMirrorRevision = withActiveProject { proj ->
        // A missing state is reported as MIRROR_NOT_PREPARED by the observation
        // method; this compatibility wrapper never performs a sync.
        val observed = observeForCodeIntelligence(router, token)
        PreparedMirrorRevision(
            sourceDigest = observed.liveRemoteDigest,
            phase = observed.phase,
            baselineRefreshSafe = false
        )
    }

    /**
     * Pure P3 observation for static code intelligence.  It reads the durable
     * mirror state and a live manifest, but never scans SAF, writes state as a
     * sync side effect, or calls the mirror `sync` operation.  A missing,
     * unstable, broken, or digest-mismatched mirror fails closed. Authorized
     * staged edits remain queryable in PENDING without syncing them from SAF.
     */
    override fun observeForCodeIntelligence(
        router: WorkspaceRouter,
        token: CancellationToken
    ): ObservedMirrorRevision = withActiveProject { proj ->
        val state = loadState(proj) ?: throw IllegalStateException("MIRROR_NOT_PREPARED")
        check(state.phase == MirrorPhase.CLEAN || state.phase == MirrorPhase.PENDING) {
            if (state.phase == MirrorPhase.BROKEN) {
                "MIRROR_BROKEN: " + (state.lastOperation?.result ?: "reason unknown")
            } else {
                "MIRROR_NOT_STABLE: ${state.phase}"
            }
        }
        validateSessionCapabilities(state, proj, router)
        val expected = state.remoteDigest ?: throw IllegalStateException("MIRROR_REMOTE_DIGEST_MISSING")
        val live = remoteManifestLocked(proj, token).digest
        if (live != expected) {
            // This is not a synchronisation: it records that the sole
            // execution substrate is no longer trustworthy.  Callers then
            // invalidate P4 and mark the index dirty before returning an error.
            broken(proj, state, "CODE_INTEL_MIRROR_INTEGRITY_LOST")
            throw IllegalStateException("CODE_INTEL_MIRROR_INTEGRITY_LOST")
        }
        ObservedMirrorRevision(proj.id, proj.generation, state.phase, expected, live)
    }

    override fun markUnexpectedMirrorMutation(reason: String): Unit = withActiveProject { proj ->
        val current = loadState(proj) ?: MirrorState.clean(proj.id, proj.generation, computeTopologyHash(proj))
        broken(proj, current, reason)
    }

    /**
     * The provider authority as Code Intelligence must see it.
     *
     * The state machine owns the answer: an unknown project, a different
     * generation, a mirror that is not stable CLEAN/PENDING, or a generation whose binding was
     * never made durable all throw here. Returning a default provider instead
     * would let a code-intelligence call start a language server in a substrate
     * the project is not bound to, which is the one thing the single-substrate
     * invariant forbids.
     */
    override fun requireRuntimeBindingForCodeIntelligence(
        projectId: String,
        rootGeneration: Long
    ): RuntimeBinding = withActiveProject { proj ->
        check(proj.id == projectId) { "CODE_INTEL_PROJECT_MISMATCH" }
        check(proj.generation == rootGeneration) { "CODE_INTEL_GENERATION_MISMATCH" }
        val state = loadState(proj) ?: throw IllegalStateException("MIRROR_NOT_PREPARED")
        check(state.phase == MirrorPhase.CLEAN || state.phase == MirrorPhase.PENDING) {
            "MIRROR_NOT_STABLE: " + state.phase
        }
        validateSessionIdentity(state, proj)
        state.runtimeBinding ?: throw IllegalStateException("RUNTIME_BINDING_MISSING")
    }

    override fun currentMirrorDigest(token: CancellationToken?): String = withActiveProject { proj ->
        remoteManifestLocked(proj, token).digest
    }

    override fun getMirrorManifest(token: CancellationToken?): MirrorManifestSummary = withActiveProject { proj ->
        val remote = remoteManifestLocked(proj, token)
        MirrorManifestSummary(
            digest = remote.digest,
            files = remote.entries.mapValues { (k, v) ->
                MirrorFileEntry(k, v.size, v.sha256, v.directory)
            }
        )
    }

    override fun readMirrorText(executionRelativePath: String, token: CancellationToken?): String? = withActiveProject { proj ->
        runCatching {
            client(proj).call("fetch_text", JSONObject().put("path", executionRelativePath), token = token).getString("text")
        }.getOrNull()
    }
}
