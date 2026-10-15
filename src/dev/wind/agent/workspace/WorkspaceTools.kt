package dev.wind.agent.workspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import dev.wind.agent.Fa
import dev.wind.agent.Prefs
import dev.wind.agent.Tools.Observer
import dev.wind.agent.Util
import dev.wind.agent.isBlankJava
import dev.wind.agent.trimJava
import dev.wind.agent.project.*
import dev.wind.agent.history.MutationScope
import dev.wind.agent.capability.CapabilityBrokerRegistry
import dev.wind.agent.capability.CapabilityApprovalKey
import dev.wind.agent.capability.CapabilityCheck
import dev.wind.agent.capability.CapabilityLease
import dev.wind.agent.capability.CapabilityMode
import dev.wind.agent.capability.CapabilityResolver
import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.NetworkGrant
import dev.wind.agent.capability.TaskContext
import dev.wind.agent.build.MavenCoordinate
import dev.wind.agent.build.MavenDependencyLock
import dev.wind.agent.build.MavenDependencyInstallPlan
import dev.wind.agent.build.MavenDependencyPlanStore
import dev.wind.agent.build.MavenDependencyWorkspaceInstaller
import dev.wind.agent.artifact.MavenRepository
import dev.wind.agent.artifact.MavenRepositoryAcquirer
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.artifact.DownloadApplyPlan
import dev.wind.agent.artifact.DownloadApplyPlanStore
import dev.wind.agent.artifact.DownloadWorkspaceApplier
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.ZipDirectoryInspector
import java.io.File

class WorkspaceTools(
    private val ctx: Context,
    private val prefs: Prefs
) {
    val projectManager = ProjectManager(ctx)
    val router: WorkspaceRouter get() = projectManager.router
    val journal by lazy { dev.wind.agent.history.MutationJournal.get(ctx) }
    var currentTaskId: String = "default-task"
    private val capabilityResolver = CapabilityResolver(ctx)
    private val capabilityBroker = CapabilityBrokerRegistry.get(ctx)
    private val permissionChecks = java.util.concurrent.ConcurrentHashMap<String, Pair<String, JSONObject>>()

    data class ReadKey(val projectId: String, val mountId: String, val path: String)
    private val readHistory = mutableSetOf<ReadKey>()

    fun noteRead(resolved: ResolvedProjectPath) {
        readHistory.add(ReadKey(resolved.projectId, resolved.mount.id, resolved.relativePath))
    }

    fun hasBeenRead(resolved: ResolvedProjectPath): Boolean =
        readHistory.contains(ReadKey(resolved.projectId, resolved.mount.id, resolved.relativePath))

    private fun pathArg(args: JSONObject): String {
        for (key in arrayOf("destination", "destination_path", "dest", "path", "file_path", "filepath", "file", "filename", "target")) {
            val value = args.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun formatVirtual(mount: WorkspaceMount, relPath: String): String {
        if (mount.treeUri.startsWith("file://")) return mount.treeUri.removePrefix("file://").trimEnd('/') + "/" + relPath
        return if (mount.isPrimary) {
            if (relPath.isEmpty()) "workspace://" else "workspace://$relPath"
        } else {
            if (relPath.isEmpty()) "project://${mount.alias}/" else "project://${mount.alias}/$relPath"
        }
    }

    private data class CapabilityGate(val lease: CapabilityLease?, val error: String?)

    /**
     * Defense-in-depth: tools cannot mutate just because they bypassed
     * AgentEngine. When AgentEngine already authorized a call, the exact lease
     * is carried into this layer instead of synthesizing a second proof.
     */
    private fun capabilityGate(
        tool: String,
        args: JSONObject,
        providedLease: CapabilityLease? = null
    ): CapabilityGate {
        dev.wind.agent.permissions.AgentPermissionGate(ctx).error(tool, args, currentTaskId)?.let { return CapabilityGate(null, it) }
        val project = projectManager.getActiveProject()
        val taskContext = TaskContext(
            currentTaskId,
            project?.id ?: "",
            project?.generation ?: 0L,
            CapabilityMode.ACCEPT,
            currentTaskId
        )
        val requirement = capabilityResolver.resolve(taskContext, tool, args)
        if (providedLease != null) {
            return try {
                permissionChecks[providedLease.grantId] = tool to JSONObject(args.toString())
                CapabilityGate(capabilityBroker.verify(providedLease, requirement), null)
            } catch (e: Exception) {
                CapabilityGate(null, "CAPABILITY_REVOKED: ${e.message}")
            }
        }
        return when (val checked = capabilityBroker.check(taskContext, requirement)) {
            is CapabilityCheck.Allowed -> try {
                permissionChecks[checked.lease.grantId] = tool to JSONObject(args.toString())
                CapabilityGate(capabilityBroker.verify(checked.lease), null)
            } catch (e: Exception) {
                CapabilityGate(null, "CAPABILITY_REVOKED: ${e.message}")
            }
            is CapabilityCheck.NeedsApproval -> CapabilityGate(
                null,
                "CAPABILITY_APPROVAL_REQUIRED: ${checked.request.summary}"
            )
            is CapabilityCheck.Denied -> CapabilityGate(null, "CAPABILITY_DENIED: ${checked.reason}")
        }
    }

    private fun capabilityError(tool: String, args: JSONObject): String? =
        capabilityGate(tool, args).error

    private fun verifyAtBoundary(lease: CapabilityLease) {
        permissionChecks[lease.grantId]?.let { (name, args) ->
            check(dev.wind.agent.permissions.AgentPermissionGate(ctx).error(name, args, currentTaskId) == null) { "AGENT_PERMISSION_REVOKED" }
        }
        // Keep this call directly adjacent to the irreversible operation. A
        // revoke, expiry, generation change, or SAF loss after the initial
        // check must result in zero writes.
        capabilityBroker.verify(lease)
    }

    private fun commitText(fs: WorkspaceFs, path: String, text: String, lease: CapabilityLease) {
        if (dev.wind.agent.permissions.AgentPermissions.get(ctx).strategy == dev.wind.agent.permissions.WriteStrategy.DIRECT) {
            verifyAtBoundary(lease); fs.writeText(path, text); return
        }
        val staged = File.createTempFile("agent-change-", ".tmp", ctx.noBackupFilesDir)
        try {
            java.io.FileOutputStream(staged).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            verifyAtBoundary(lease)
            fs.write(path, staged.readBytes())
        } finally { staged.delete() }
    }

    fun workspaceMounts(): String {
        capabilityError("workspace_mounts", JSONObject())?.let { return "ERROR: $it" }
        val proj = projectManager.getActiveProject() ?: return "ERROR: No active project configured"
        val sb = StringBuilder("Project: ").append(proj.name).append(" (id: ").append(proj.id).append(")\n\n")
        for (m in proj.mounts) {
            val prefix = if (m.isPrimary) "workspace://" else "project://${m.alias}/"
            sb.append("- ").append(m.alias)
            if (m.isPrimary) sb.append(" [PRIMARY]")
            sb.append("\n  URI: ").append(prefix)
            sb.append("\n  Mode: ").append(m.mode.name.lowercase())
            sb.append("\n  Status: ").append(router.checkMountState(m).name.lowercase())
            sb.append("\n  Execution Path: /workspace/").append(m.executionPath)
            sb.append("\n  Display: ").append(m.displayName).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun mavenTaskContext(): TaskContext {
        val project = projectManager.getActiveProject() ?: error("MAVEN_INSTALL_PROJECT_REQUIRED")
        return TaskContext(currentTaskId, project.id, project.generation, CapabilityMode.ACCEPT, currentTaskId)
    }

    /** UI metadata is derived from the issued plan, never from model-supplied digests/targets. */
    fun enrichMavenInstallArgs(args: JSONObject): String? = try {
        val context = mavenTaskContext()
        val plan = MavenDependencyPlanStore(ctx.noBackupFilesDir.canonicalFile)
            .load(context, args.optString("plan_id", ""))
        val mount = projectManager.getActiveProject()?.findMountById(plan.mountId)
            ?: error("MAVEN_INSTALL_MOUNT_UNAVAILABLE")
        val target = formatVirtual(mount, plan.relativeRoot)
        args.put("plan_sha256", plan.id).put("lock_sha256", plan.lock.fingerprint)
            .put("target_project_path", target).put("target_mount_id", plan.mountId)
            .put("module_count", plan.lock.modules.size).put("publisher_signature_verified", false)
            .put("coordinates", JSONArray().also { array -> plan.lock.roots.forEach { array.put(it.notation) } })
            .put("installation_summary", "Maven dependency lock at $target; " +
                "${plan.lock.modules.size} modules; ${plan.lock.roots.take(8).joinToString { it.notation }}; " +
                "repository checksums, NOT independently verified publisher signatures")
        null
    } catch (error: Exception) { error.message ?: error.javaClass.simpleName }

    fun resolveMavenDependencies(args: JSONObject, token: dev.wind.agent.CancellationToken,
        observer: Observer?, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("resolve_maven_dependencies", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val context = mavenTaskContext()
        val target = router.resolve(args.optString("project_path", "workspace://"), write = true)
        if (target.relativePath.isNotEmpty() && target.fs.stat(target.relativePath)?.isDirectory != true) {
            return "ERROR: MAVEN_INSTALL_TARGET_NOT_DIRECTORY"
        }
        val coordinates = args.optJSONArray("coordinates") ?: return "ERROR: MAVEN_COORDINATES_REQUIRED"
        require(coordinates.length() in 1..128) { "MAVEN_ROOT_COUNT_INVALID" }
        val roots = (0 until coordinates.length()).map { MavenCoordinate.parse(coordinates.getString(it)) }
        require(roots.distinct().size == roots.size) { "MAVEN_ROOTS_DUPLICATE" }
        val maxBytes = args.optLong("max_jar_bytes", 256L * 1024L * 1024L)
        require(maxBytes > 0L) { "MAVEN_JAR_SIZE_LIMIT_INVALID" }
        val root = ctx.noBackupFilesDir.canonicalFile
        val repositoryUrl = args.getString("repository_url")
        val repository = MavenRepository("repo-" + MavenDependencyInstallPlan.digest(
            repositoryUrl.toByteArray(Charsets.UTF_8)).take(16), repositoryUrl)
        val lockPath = if (target.relativePath.isEmpty()) ".vega/maven-dependencies.lock"
            else "${target.relativePath}/.vega/maven-dependencies.lock"
        val before = MavenDependencyInstallPlan.previousDigest(target.fs, lockPath)
        fun verify() {
            token.throwIfCancelled()
            verifyAtBoundary(lease)
            check(mavenTaskContext().let { it.taskId == context.taskId && it.projectId == context.projectId &&
                it.projectGeneration == context.projectGeneration }) { "MAVEN_INSTALL_CONTEXT_CHANGED" }
        }
        verify()
        observer?.onProgress("Resolving exact Maven dependencies")
        val result = MavenRepositoryAcquirer(repository, VerifiedArtifactStore(File(root, "p7-artifacts")),
            File(root, "p7-quarantine")) { _ ->
            // The acquirer additionally enforces this exact repository's origin/path.
            // Reverify the original ONCE/TASK lease instead of trying to mint a new hop grant.
            verify()
            true
        }.acquireGraph(roots, token, maxJarBytes = maxBytes)
        val lock = MavenDependencyLock.fromGraph(roots, result.graph, result.modules, result.supportingPoms)
        verify()
        val plan = MavenDependencyInstallPlan(context.taskId, context.projectId, context.projectGeneration,
            target.mount.id, target.relativePath, before, lock)
        val id = MavenDependencyPlanStore(root).issue(plan)
        return JSONObject().put("status", "PLAN_READY").put("plan_id", id)
            .put("lock_sha256", lock.fingerprint).put("module_count", lock.modules.size)
            .put("supporting_pom_count", lock.modelPoms.size).put("workspace_changed", false)
            .put("publisher_signature_verified", false).put("installation_authorized", false).toString()
    }

    fun installMavenDependencies(args: JSONObject, token: dev.wind.agent.CancellationToken,
        observer: Observer?, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("install_maven_dependencies", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val context = mavenTaskContext()
        val root = ctx.noBackupFilesDir.canonicalFile
        val plan = MavenDependencyPlanStore(root).load(context, args.optString("plan_id", ""))
        val project = projectManager.getActiveProject() ?: error("MAVEN_INSTALL_PROJECT_REQUIRED")
        val mount = project.findMountById(plan.mountId) ?: error("MAVEN_INSTALL_MOUNT_UNAVAILABLE")
        val target = router.resolve(formatVirtual(mount, plan.relativeRoot), write = true)
        val scope = MutationScope(project.id, mount.id, mount.treeUri)
        fun verify() {
            token.throwIfCancelled()
            verifyAtBoundary(lease)
            check(mavenTaskContext().let { it.taskId == context.taskId && it.projectId == context.projectId &&
                it.projectGeneration == context.projectGeneration }) { "MAVEN_INSTALL_CONTEXT_CHANGED" }
            projectMirrors.assertCanonicalDependencyInstallSafe()
        }
        verify()
        observer?.onProgress("Verifying Maven plan and private dependency cache")
        MavenDependencyWorkspaceInstaller(root, VerifiedArtifactStore(File(root, "p7-artifacts")))
            .install(plan, target.fs, token, ::verify) { issued, bytes ->
                synchronized(dev.wind.agent.history.MutationJournal.MUTATION_LOCK) {
                    verify()
                    if (MavenDependencyInstallPlan.previousDigest(target.fs, issued.lockPath) != issued.previousLockSha256) {
                        throw dev.wind.agent.history.MutationPreconditionConflict("MAVEN_INSTALL_LOCK_CONFLICT")
                    }
                    if (!target.fs.exists(issued.metadataPath)) {
                        journal.transactionalMkdir(scope, target.fs, context.taskId, issued.metadataPath) {
                            verify()
                            target.fs.mkdir(issued.metadataPath)
                        }
                    }
                    require(target.fs.stat(issued.metadataPath)?.isDirectory == true) { "MAVEN_INSTALL_METADATA_UNSAFE" }
                    journal.transactionalWrite(scope, target.fs, context.taskId, issued.lockPath,
                        String(bytes, Charsets.UTF_8), overwrite = issued.previousLockSha256 != null) {
                        verify()
                        if (MavenDependencyInstallPlan.previousDigest(target.fs, issued.lockPath) != issued.previousLockSha256) {
                            throw dev.wind.agent.history.MutationPreconditionConflict("MAVEN_INSTALL_LOCK_CONFLICT")
                        }
                        target.fs.write(issued.lockPath, bytes)
                    }
                }
            }
        observer?.onFileChange(formatVirtual(mount, plan.lockPath), "", String(plan.lock.encode(), Charsets.UTF_8))
        return JSONObject().put("status", "PROJECT_LOCK_INSTALLED").put("plan_id", plan.id)
            .put("lock_sha256", plan.lock.fingerprint).put("module_count", plan.lock.modules.size)
            .put("jars_in_workspace", false).put("publisher_signature_verified", false)
            .put("build_pipeline_integrated", false).toString()
    }

    fun prepareDownloadApply(args: JSONObject): String {
        val artifactUri = args.optString("artifact_uri", "")
        if (artifactUri.isBlankJava()) return "ERROR: artifact_uri required"
        val destination = args.optString("destination", "").trimJava().ifEmpty { pathArg(args) }
        if (destination.isBlankJava()) return "ERROR: destination path required"

        val privateRoot = ctx.noBackupFilesDir.canonicalFile
        val access = TaskArtifactAccess(File(privateRoot, "p7-artifact-access"))
        val store = VerifiedArtifactStore(File(privateRoot, "p7-artifacts"))
        if (!access.hasAccess(currentTaskId, artifactUri)) {
            return "ERROR: ARTIFACT_ACCESS_DENIED: artifact not found or not accessible to this task"
        }
        val artifact = store.getArtifact(artifactUri)
            ?: return "ERROR: ARTIFACT_NOT_FOUND"

        val resolved = try { router.resolve(destination, write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        if (resolved.mount.mode != MountMode.READ_WRITE) return "ERROR: Destination mount is read-only"

        val mode = args.optString("mode", "file").lowercase()
        val overwrite = args.optBoolean("overwrite", false)
        val stripSingleRoot = args.optBoolean("strip_single_root", true)

        val project = projectManager.getActiveProject()
            ?: return "ERROR: PROJECT_NOT_CONFIGURED: a project must be open to apply downloads into workspace"
        access.record(currentTaskId, project.id, project.generation, artifact)

        val (entryCount, totalBytes) = if (mode == "file") {
            if (resolved.fs.exists(resolved.relativePath) && !overwrite) {
                return "ERROR: DESTINATION_EXISTS: ${formatVirtual(resolved.mount, resolved.relativePath)} already exists. Use overwrite=true to replace."
            }
            1 to artifact.byteSize
        } else if (mode == "zip") {
            val zipPlan = try {
                store.withVerifiedChannel(artifactUri) { channel, _ ->
                    ZipDirectoryInspector().plan(channel)
                }
            } catch (e: Exception) {
                return "ERROR: INVALID_ZIP_ARCHIVE: ${e.message}"
            }
            for (entry in zipPlan.entries) {
                if (entry.name.startsWith("/") || entry.name.contains("../") || entry.name.contains("/..") || entry.name == "..") {
                    return "ERROR: ZIP_ENTRY_PATH_UNSAFE: ${entry.name}"
                }
            }
            zipPlan.entries.size to zipPlan.summary.declaredUncompressedBytes
        } else {
            return "ERROR: mode must be 'file' or 'zip'"
        }

        val plan = DownloadApplyPlan(
            taskId = currentTaskId,
            projectId = project.id,
            generation = project.generation,
            mountId = resolved.mount.id,
            relativePath = resolved.relativePath,
            artifactUri = artifactUri,
            artifactSha256 = artifact.sha256,
            mode = mode,
            overwrite = overwrite,
            stripSingleRoot = stripSingleRoot,
            entryCount = entryCount,
            totalBytes = totalBytes
        )

        val planId = DownloadApplyPlanStore(privateRoot).issue(plan)
        return JSONObject().put("status", "PLAN_READY")
            .put("plan_id", planId)
            .put("mode", mode)
            .put("destination", formatVirtual(resolved.mount, resolved.relativePath))
            .put("artifact_uri", artifactUri)
            .put("sha256", artifact.sha256)
            .put("entry_count", entryCount)
            .put("total_bytes", totalBytes)
            .put("overwrite", overwrite)
            .toString()
    }

    fun applyDownload(
        args: JSONObject,
        token: dev.wind.agent.CancellationToken,
        observer: Observer?,
        providedLease: CapabilityLease? = null
    ): String {
        val gate = capabilityGate("apply_download", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"

        val planId = args.optString("plan_id", "")
        if (planId.isBlank()) return "ERROR: plan_id required"

        val privateRoot = ctx.noBackupFilesDir.canonicalFile
        val project = projectManager.getActiveProject()
            ?: return "ERROR: PROJECT_NOT_CONFIGURED"
        val context = TaskContext(currentTaskId, project.id, project.generation, CapabilityMode.ACCEPT, currentTaskId)

        val plan = try {
            DownloadApplyPlanStore(privateRoot).load(context, planId)
        } catch (e: Exception) {
            return "ERROR: DOWNLOAD_APPLY_PLAN_UNAVAILABLE: ${e.message}"
        }

        val mount = project.findMountById(plan.mountId)
            ?: return "ERROR: MOUNT_NOT_FOUND"
        val target = router.resolve(formatVirtual(mount, plan.relativePath), write = true)
        val scope = MutationScope(project.id, mount.id, mount.treeUri)

        fun verify() {
            token.throwIfCancelled()
            verifyAtBoundary(lease)
            projectMirrors.assertCanonicalDependencyInstallSafe()
        }
        verify()

        observer?.onProgress("Applying ${plan.mode} to ${formatVirtual(mount, plan.relativePath)}")
        val store = VerifiedArtifactStore(File(privateRoot, "p7-artifacts"))
        val applier = DownloadWorkspaceApplier(store, journal)
        return try {
            val res = applier.apply(plan, target.fs, scope, token, ::verify)
            observer?.onFileChange(formatVirtual(mount, plan.relativePath), "", "Applied download")
            res.toString()
        } catch (e: Exception) {
            "ERROR: DOWNLOAD_APPLY_FAILED: ${e.message}"
        }
    }

    fun capabilityStatus(): String {
        val project = projectManager.getActiveProject()
        val mounts = project?.mounts.orEmpty().map { m -> JSONObject().put("id", m.id).put("path", m.treeUri)
            .put("grant_key", dev.wind.agent.permissions.AgentPermissions.mountKey(requireNotNull(project), m)).put("virtual_path", "project://${m.alias}/")
            .put("android_status", router.checkMountState(m).name) }
        val permissions = dev.wind.agent.permissions.AgentPermissions.get(ctx).status(currentTaskId)
        val adb = dev.wind.agent.adb.AdbManager.get(ctx).status(currentTaskId)
        val effective = permissions.getJSONArray("effective")
        for (index in 0 until effective.length()) {
            val capability = effective.getJSONObject(index)
            if (capability.optString("capability") in setOf("LOCAL_ADB", "RAW_ADB_SHELL")) {
                val available = adb.optBoolean("available")
                capability.put("available", available).put("status", when {
                    !adb.optBoolean("wireless_supported") && !adb.optBoolean("tcp_supported") -> "ADB_WIRELESS_REQUIRES_ANDROID_11"
                    !adb.optBoolean("native_available") -> "ADB_NATIVE_COMPONENT_UNAVAILABLE"
                    !available -> "ADB_CONNECTION_REQUIRED"
                    !capability.optBoolean("configured") -> "AGENT_PERMISSION_REQUIRED"
                    else -> "READY"
                })
            }
        }
        return permissions.put("mounts", JSONArray(mounts))
            .put("accessibility_connected", dev.wind.agent.phone.PhoneAutomation.service != null)
            .put("screen_sharing", dev.wind.agent.screen.ScreenShareSession.isSharing)
            .put("adb", adb)
            .put("legacy_capabilities", legacyCapabilityStatus()).toString(2)
    }

    private fun legacyCapabilityStatus(): String {
        val project = projectManager.getActiveProject() ?: return "ERROR: No active project configured"
        val context = TaskContext(
            currentTaskId,
            project.id,
            project.generation,
            CapabilityMode.ACCEPT,
            currentTaskId
        )
        val envelope = capabilityBroker.getEnvelope(context)
        val sb = StringBuilder("Current reusable task/session capabilities (not execution history)\n")
            .append("Task: ").append(currentTaskId).append('\n')
            .append("Project: ").append(project.name).append(" (generation ").append(project.generation).append(")\n")
            .append("Execution: ").append(envelope.executionAccess.name).append('\n')
            .append("Mirror: ").append(envelope.mirrorAccess.name).append('\n')
            .append("Network: ").append(envelope.networkGrant.mode.name)
        if (envelope.networkGrant.domains.isNotEmpty()) sb.append(" [").append(envelope.networkGrant.domains.sorted().joinToString(", ")).append(']')
        sb.append('\n').append("Mount grants:\n")
        for (mount in project.mounts.sortedBy { it.id }) {
            val grant = envelope.mountGrants[mount.id]
            sb.append("- ").append(mount.alias).append(" (").append(mount.id).append("): ")
                .append(if (grant?.read == true) "READ" else "NONE")
            if (grant?.canonicalWrite == true) sb.append(", CANONICAL_WRITE")
            if (grant?.canonicalDelete == true) sb.append(", CANONICAL_DELETE")
            sb.append('\n')
        }
        sb.append("Expires: ").append(if (envelope.expiresAt == Long.MAX_VALUE) "task end / process end" else envelope.expiresAt)
        sb.append("\nConsumed ONCE approvals are not reusable grants. Earlier successful calls do not grant later execution, mirror writes or network access.")
        sb.append("\nProject IDs identify projects; mount IDs identify separate authorization targets.")
        sb.append("\nApply authorization is an exact diff approval, independent of generic CANONICAL_WRITE grants.")
        sb.append('\n').append(projectMirrors.lifecycleStatus())
        sb.append("\nA persisted mirror is project data; task permissions govern access to it, not its lifecycle.")
        return sb.toString()
    }

    fun requestWorkspaceAccess(args: JSONObject, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("request_workspace_access", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val alias = args.optString("alias", "").trim().lowercase()
        val reason = args.optString("reason", "").trim()
        val modeStr = args.optString("mode", "read_write").trim().lowercase()
        val requestedExecutionPath = args.optString(
            "suggested_execution_path",
            args.optString("suggestedExecutionPath", alias)
        ).trim()

        if (!alias.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) return "ERROR: Invalid or missing alias: $alias"
        val requestedMode = when (modeStr) {
            "read_only" -> MountMode.READ_ONLY
            "read_write" -> MountMode.READ_WRITE
            else -> return "ERROR: mode must be read_only or read_write"
        }
        if (!WorkspaceMount.isValidExecutionPath(requestedExecutionPath)) {
            return "ERROR: Invalid suggested_execution_path: $requestedExecutionPath"
        }

        val proj = projectManager.getActiveProject() ?: return "ERROR: No active project configured"
        val existing = proj.findMount(alias)
        if (existing != null && router.checkMountState(existing) == MountState.AVAILABLE) {
            return "ALREADY_GRANTED: Mount alias '$alias' is already authorized in this project."
        }

        val mode = existing?.mode ?: requestedMode
        val executionPath = existing?.executionPath ?: requestedExecutionPath
        if (existing == null) {
            try {
                VegaProject(
                    id = proj.id,
                    name = proj.name,
                    primaryMountId = proj.primaryMountId,
                    mounts = proj.mounts + WorkspaceMount(
                        id = "request-preview-$alias",
                        alias = alias,
                        treeUri = "content://pending/$alias",
                        displayName = alias,
                        mode = mode,
                        executionPath = executionPath,
                        isPrimary = false
                    ),
                    generation = proj.generation,
                    createdAt = proj.createdAt
                )
            } catch (e: Exception) {
                return "ERROR: ${e.message}"
            }
        }

        val expansionRequirement = CapabilityRequirement.CapabilityExpansion(
            proj.id,
            alias,
            mode,
            executionPath
        )
        val capabilityKey = CapabilityApprovalKey.expansion(expansionRequirement)
        val reqId = try {
            WorkspaceAccessRequestStore.createRequest(
                projectId = proj.id,
                alias = alias,
                mode = mode,
                executionPath = executionPath,
                reason = reason,
                rootTaskId = currentTaskId,
                projectGeneration = proj.generation,
                capabilityKey = capabilityKey,
                capabilityLease = lease
            )
        } catch (e: Exception) {
            return "ERROR: failed to create workspace access request: ${e.message}"
        }
        return "REQUEST_PENDING: Access requested for mount '$alias'. Request ID: $reqId.\n" +
            "Reason: $reason\nThe Android folder picker has been requested. Continue after the user grants or declines access."
    }

    fun listDir(args: JSONObject): String {
        capabilityError("list_dir", args)?.let { return "ERROR: $it" }
        val resolved = try { router.resolve(pathArg(args), write = false) } catch (e: Exception) { return "ERROR: ${e.message}" }
        val entries = try { resolved.fs.list(resolved.relativePath) } catch (e: Exception) {
            return "ERROR: cannot read directory: ${formatVirtual(resolved.mount, resolved.relativePath)} (${e.message})"
        }
        val sb = StringBuilder().append("Directory: ").append(formatVirtual(resolved.mount, resolved.relativePath))
            .append("  (").append(entries.size).append(" entries)\n")
        for (entry in entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
            if (entry.isDirectory) sb.append("  ► ").append(entry.name).append("/\n")
            else sb.append("  · ").append(entry.name).append("  (").append(Util.humanSize(entry.size)).append(")\n")
        }
        return sb.toString()
    }

    fun fileInfo(args: JSONObject): String {
        capabilityError("file_info", args)?.let { return "ERROR: $it" }
        val resolved = try { router.resolve(pathArg(args), write = false) } catch (e: Exception) { return "ERROR: ${e.message}" }
        val stat = resolved.fs.stat(resolved.relativePath) ?: return "ERROR: not found: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        return buildString {
            append("Path: ").append(formatVirtual(resolved.mount, resolved.relativePath)).append('\n')
            append("Mount: ").append(resolved.mount.alias).append(" (").append(resolved.mount.mode.name).append(")\n")
            append("Type: ").append(if (stat.isDirectory) "directory" else "file").append('\n')
            append("Size: ").append(Util.humanSize(stat.size)).append('\n')
            append("Readable: true, Writable: ").append(resolved.mount.mode == MountMode.READ_WRITE).append('\n')
            append("MIME: ").append(stat.mimeType ?: Util.mimeOf(stat.name))
        }
    }

    fun readFile(args: JSONObject): String {
        capabilityError("read_file", args)?.let { return "ERROR: $it" }
        val resolved = try { router.resolve(pathArg(args), write = false) } catch (e: Exception) { return "ERROR: ${e.message}" }
        if (!resolved.fs.exists(resolved.relativePath)) return "ERROR: file not found: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        val stat = resolved.fs.stat(resolved.relativePath)
        if (stat?.isDirectory == true) return "ERROR: is a directory: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        val maxBytes = Math.min(65536, Math.max(1024, args.optInt("max_bytes", 65536)))
        if (stat != null && stat.size > maxBytes && !args.has("start_line")) {
            return "ERROR: file is ${Util.humanSize(stat.size)} (exceeds ${Util.humanSize(maxBytes.toLong())} limit). Use start_line and end_line."
        }
        val text = try { resolved.fs.readText(resolved.relativePath) } catch (e: Exception) { return "ERROR: failed to read file: ${e.message}" }
        noteRead(resolved)
        val lines = text.split("\n")
        val startLine = Math.max(1, args.optInt("start_line", 1))
        val maxLines = Math.min(400, Math.max(1, args.optInt("max_lines", 400)))
        val endLine = if (args.has("end_line")) Math.min(lines.size, Math.max(startLine, args.optInt("end_line", lines.size)))
        else Math.min(lines.size, startLine + maxLines - 1)
        val sb = StringBuilder()
        for (i in startLine..endLine) if (i - 1 < lines.size) sb.append(String.format("%4d | %s\n", i, lines[i - 1]))
        return sb.toString()
    }

    fun writeFile(
        args: JSONObject,
        observer: Observer? = null,
        providedLease: CapabilityLease? = null
    ): String {
        val gate = capabilityGate("write_file", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val resolved = try { router.resolve(pathArg(args), write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        val content = args.optString("content", "")
        val overwrite = args.optBoolean("overwrite", false)
        if (resolved.fs.exists(resolved.relativePath) && !overwrite) {
            return "ERROR: file already exists: ${formatVirtual(resolved.mount, resolved.relativePath)}. Use overwrite=true or edit_file."
        }
        val scope = MutationScope(resolved.projectId, resolved.mount.id, resolved.mount.treeUri)
        val fs = resolved.fs
        val cleanPath = resolved.relativePath
        val raw = content
        observer?.onFileChange(formatVirtual(resolved.mount, resolved.relativePath), if (fs.exists(cleanPath)) fs.readText(cleanPath) else "", content)
        try {
            journal.transactionalWrite(scope, fs, currentTaskId, cleanPath, content, overwrite) {
                commitText(fs, cleanPath, raw, lease)
            }
        } catch (e: Exception) { return "ERROR: failed to write file: ${e.message}" }
        noteRead(resolved)
        return "OK: wrote ${content.length} chars to ${formatVirtual(resolved.mount, resolved.relativePath)}"
    }

    fun editFile(
        args: JSONObject,
        observer: Observer? = null,
        providedLease: CapabilityLease? = null
    ): String {
        val gate = capabilityGate("edit_file", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val resolved = try { router.resolve(pathArg(args), write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        if (!resolved.fs.exists(resolved.relativePath)) return "ERROR: file not found: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        if (!hasBeenRead(resolved)) return "ERROR: read_file comes first — this file has not been read in this session.\nPath: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        val stat = resolved.fs.stat(resolved.relativePath)
        if (stat != null && stat.size > 16L * 1024 * 1024) return "ERROR: file size (${stat.size} bytes) exceeds 16MB editable limit"
        val original = try { resolved.fs.readText(resolved.relativePath) } catch (_: Exception) { return "ERROR: cannot read file: ${formatVirtual(resolved.mount, resolved.relativePath)}" }
        val text = try { dev.wind.agent.shell.mirror.TextEditEngine.apply(original, args) } catch (e: Exception) { return "ERROR: ${e.message}" }
        observer?.onProgress(formatVirtual(resolved.mount, resolved.relativePath) + "  " + Fa.TRAIL_PROG_WRITING)
        observer?.onFileChange(formatVirtual(resolved.mount, resolved.relativePath), original, text)
        val scope = MutationScope(resolved.projectId, resolved.mount.id, resolved.mount.treeUri)
        val fs = resolved.fs
        val cleanPath = resolved.relativePath
        try {
            journal.transactionalEdit(scope, fs, currentTaskId, cleanPath, text) {
                commitText(fs, cleanPath, text, lease)
            }
        } catch (e: Exception) { return "ERROR: failed to write edits: ${e.message}" }
        return "OK: edited ${formatVirtual(resolved.mount, resolved.relativePath)}"
    }

    fun deletePath(args: JSONObject, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("delete_path", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val resolved = try { router.resolve(pathArg(args), write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        if (!resolved.fs.exists(resolved.relativePath)) return "ERROR: not found: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        if (resolved.fs.stat(resolved.relativePath)?.isDirectory == true) {
            return deleteDirectory(resolved, args, lease)
        }
        val scope = MutationScope(resolved.projectId, resolved.mount.id, resolved.mount.treeUri)
        val fs = resolved.fs
        val cleanPath = resolved.relativePath
        return try {
            if (journal.transactionalDelete(scope, fs, currentTaskId, cleanPath) {
                    verifyAtBoundary(lease)
                    fs.delete(cleanPath)
                })
                "OK: deleted ${formatVirtual(resolved.mount, resolved.relativePath)}"
            else "ERROR: failed to delete ${formatVirtual(resolved.mount, resolved.relativePath)}"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun deleteDirectory(resolved: ResolvedProjectPath, args: JSONObject, lease: CapabilityLease): String {
        require(resolved.relativePath.isNotBlank()) { "ROOT_DELETE_DENIED" }
        val fs = resolved.fs; val scope = MutationScope(resolved.projectId, resolved.mount.id, resolved.mount.treeUri)
        val files = mutableListOf<String>(); val directories = mutableListOf<String>(); val visited = mutableSetOf<String>()
        var bytes = 0L
        fun inspect(path: String) {
            check(visited.add(path) && visited.size + files.size <= 2000) { "DIRECTORY_DELETE_LIMIT" }
            val entries = fs.list(path)
            require(entries.isEmpty() || args.optBoolean("recursive", false)) { "RECURSIVE_DELETE_REQUIRED" }
            for (entry in entries) {
                require(!entry.name.contains('/') && entry.name !in setOf(".", "..")) { "INVALID_CHILD_PATH" }
                val child = path.trimEnd('/') + "/" + entry.name
                if (entry.isDirectory) inspect(child) else {
                    bytes += entry.size
                    require(entry.size <= SafWorkspaceFs.MAX_FILE_SIZE_BYTES && bytes <= 64L * 1024 * 1024) { "DIRECTORY_SNAPSHOT_LIMIT" }; files.add(child)
                }
            }
            directories.add(path)
        }
        inspect(resolved.relativePath)
        val batch = if (dev.wind.agent.permissions.AgentPermissions.get(ctx).strategy == dev.wind.agent.permissions.WriteStrategy.TRANSACTIONAL)
            journal.createBatch(java.util.UUID.randomUUID().toString(), currentTaskId) else null
        return try {
            for (file in files) check(journal.transactionalDelete(scope, fs, currentTaskId, file, batchId = batch?.batchId) { verifyAtBoundary(lease); fs.delete(file) })
            for (dir in directories) check(journal.transactionalRmdir(scope, fs, currentTaskId, dir, batchId = batch?.batchId) { verifyAtBoundary(lease); fs.delete(dir) })
            batch?.let { journal.commitBatch(it) }
            "OK: deleted directory (${files.size} files, ${directories.size} directories)"
        } catch (error: Exception) {
            batch?.let { runCatching { journal.rollbackBatch(it) } }
            "ERROR: ${error.message}; " + if (batch == null) "direct changes may be partial; inspect mutation history" else "transaction recovery recorded in mutation history"
        }
    }

    fun makeDir(args: JSONObject, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("make_dir", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val resolved = try { router.resolve(pathArg(args), write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        val scope = MutationScope(resolved.projectId, resolved.mount.id, resolved.mount.treeUri)
        val fs = resolved.fs
        val cleanPath = resolved.relativePath
        return try {
            if (journal.transactionalMkdir(scope, fs, currentTaskId, cleanPath) {
                    verifyAtBoundary(lease)
                    fs.mkdir(cleanPath)
                })
                "OK: created directory ${formatVirtual(resolved.mount, resolved.relativePath)}"
            else "ERROR: failed to create directory: ${formatVirtual(resolved.mount, resolved.relativePath)}"
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    fun movePath(args: JSONObject, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("move_path", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val source = args.optString("source", args.optString("from", "")).trim()
        val destination = args.optString("destination", args.optString("to", "")).trim()
        if (source.isEmpty() || destination.isEmpty()) return "ERROR: both source and destination are required"
        val src = try { router.resolve(source, write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        val dst = try { router.resolve(destination, write = true) } catch (e: Exception) { return "ERROR: ${e.message}" }
        if (src.mount.id != dst.mount.id) return moveAcrossMounts(src, dst, lease)
        if (!src.fs.exists(src.relativePath)) return "ERROR: source not found: ${formatVirtual(src.mount, src.relativePath)}"
        val scope = MutationScope(src.projectId, src.mount.id, src.mount.treeUri)
        val fs = src.fs
        val cleanSrc = src.relativePath
        val cleanDst = dst.relativePath
        return try {
            if (journal.transactionalMove(scope, fs, currentTaskId, cleanSrc, cleanDst) {
                    verifyAtBoundary(lease)
                    fs.move(cleanSrc, cleanDst)
                })
                "OK: moved ${formatVirtual(src.mount, src.relativePath)} to ${formatVirtual(dst.mount, dst.relativePath)}"
            else "ERROR: failed to move"
        } catch (e: Exception) { "ERROR: failed to move: ${e.message}" }
    }

    private fun moveAcrossMounts(src: ResolvedProjectPath, dst: ResolvedProjectPath, lease: CapabilityLease): String {
        require(src.fs.stat(src.relativePath)?.isDirectory == false) { "CROSS_MOUNT_FILE_REQUIRED" }
        require(!dst.fs.exists(dst.relativePath)) { "MOVE_DESTINATION_EXISTS" }
        val bytes = src.fs.read(src.relativePath)
        val srcScope = MutationScope(src.projectId, src.mount.id, src.mount.treeUri)
        val dstScope = MutationScope(dst.projectId, dst.mount.id, dst.mount.treeUri)
        val batch = if (dev.wind.agent.permissions.AgentPermissions.get(ctx).strategy == dev.wind.agent.permissions.WriteStrategy.TRANSACTIONAL)
            journal.createBatch(java.util.UUID.randomUUID().toString(), currentTaskId) else null
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            journal.transactionalBinaryStream(dstScope, dst.fs, currentTaskId, dst.relativePath, digest, bytes.size.toLong(), false, batchId = batch?.batchId) { output ->
                verifyAtBoundary(lease); output.write(bytes)
            }
            check(journal.transactionalDelete(srcScope, src.fs, currentTaskId, src.relativePath, batchId = batch?.batchId) { verifyAtBoundary(lease); src.fs.delete(src.relativePath) })
            batch?.let { journal.commitBatch(it) }
            "OK: moved file across mounts"
        } catch (error: Exception) { batch?.let { runCatching { journal.rollbackBatch(it) } }; "ERROR: ${error.message}; inspect mutation history" }
    }

    fun searchFiles(args: JSONObject, observer: Observer? = null): String {
        capabilityError("search_files", args)?.let { return "ERROR: $it" }
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return "ERROR: query is required"
        val maxResults = Math.max(1, Math.min(500, args.optInt("max_results", 50)))
        val rawScope = pathArg(args)
        val sb = StringBuilder("Search results for \"").append(query).append("\":\n")
        var count = 0
        if (rawScope.isNotEmpty()) {
            val resolved = try { router.resolve(rawScope, write = false) } catch (e: Exception) { return "ERROR: ${e.message}" }
            for (r in resolved.fs.search(query, maxResults, resolved.relativePath)) {
                sb.append(if (r.isDirectory) "  ► " else "  · ").append(formatVirtual(resolved.mount, r.path)).append('\n')
                count++
            }
        } else {
            for ((mount, fs) in router.readableMounts()) {
                if (count >= maxResults) break
                for (r in fs.search(query, maxResults - count, "")) {
                    sb.append(if (r.isDirectory) "  ► " else "  · ").append(formatVirtual(mount, r.path)).append('\n')
                    count++
                    if (count >= maxResults) break
                }
            }
        }
        return if (count == 0) "No files matching \"$query\" found." else sb.toString().trim()
    }

    fun glob(args: JSONObject, observer: Observer? = null): String {
        capabilityError("glob", args)?.let { return "ERROR: $it" }
        val pattern = args.optString("pattern", "*").trim()
        val maxResults = Math.max(1, Math.min(500, args.optInt("max_results", 50)))
        val rawScope = pathArg(args)
        val sb = StringBuilder("Glob matches for \"").append(pattern).append("\":\n")
        var count = 0
        if (rawScope.isNotEmpty()) {
            val resolved = try { router.resolve(rawScope, write = false) } catch (e: Exception) { return "ERROR: ${e.message}" }
            for (r in resolved.fs.glob(pattern, maxResults, resolved.relativePath)) {
                sb.append(if (r.isDirectory) "  ► " else "  · ").append(formatVirtual(resolved.mount, r.path)).append('\n')
                count++
            }
        } else {
            for ((mount, fs) in router.readableMounts()) {
                if (count >= maxResults) break
                for (r in fs.glob(pattern, maxResults - count, "")) {
                    sb.append(if (r.isDirectory) "  ► " else "  · ").append(formatVirtual(mount, r.path)).append('\n')
                    count++
                    if (count >= maxResults) break
                }
            }
        }
        return if (count == 0) "No files matching glob pattern \"$pattern\" found." else sb.toString().trim()
    }

    val runtimeRegistry = dev.wind.agent.runtime.RuntimeRegistry(ctx)
    val projectMirrors = dev.wind.agent.shell.mirror.ProjectMirrorManager(ctx, runtimeRegistry = runtimeRegistry)
    val shellManager = dev.wind.agent.shell.ShellManager(ctx, projectMirrors)
    val codeIntelligence = dev.wind.agent.intelligence.CodeIntelligenceManager(ctx, projectMirrors)

    /**
     * The P4 coding session for the current run, when one is active.
     *
     * Set by AgentEngine before the tool runs. Nullable rather than required
     * because the same Tools instance serves ordinary chat turns, where there is
     * no coding task and build/test/diagnostics are simply unavailable.
     */
    @Volatile
    var codingSession: dev.wind.agent.coding.CodingSession? = null

    /** Runs one of the P4 tools, or null when [name] is not one of them. */
    fun codingTool(
        name: String,
        args: JSONObject,
        token: dev.wind.agent.CancellationToken,
        providedLease: CapabilityLease? = null
    ): String? {
        val session = codingSession ?: return null
        return when (name) {
            dev.wind.agent.Tools.ToolNames.BUILD,
            dev.wind.agent.Tools.ToolNames.TEST,
            dev.wind.agent.Tools.ToolNames.DIAGNOSTICS -> {
                // build/test cross the execution boundary and therefore need the
                // same authority as shell_exec; diagnostics resolves to an
                // internal requirement, which the gate also evaluates so there is
                // exactly one authorization path for all three.
                val gate = capabilityGate(name, args, providedLease)
                gate.error?.let { return "ERROR: $it" }
                val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
                session.run(name, args, token) { verifyAtBoundary(lease) }
            }
            else -> null
        }
    }

    fun intelligenceTool(
        name: String,
        args: JSONObject,
        token: dev.wind.agent.CancellationToken,
        providedLease: CapabilityLease? = null
    ): String? {
        val gate = capabilityGate(name, args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val runtime = codingSession?.runtime
        return when (name) {
            "find_definition" -> codeIntelligence.findDefinition(args, router, runtime, token) { verifyAtBoundary(lease) }.toString()
            "find_references" -> codeIntelligence.findReferences(args, router, runtime, token) { verifyAtBoundary(lease) }.toString()
            "symbol_search" -> codeIntelligence.symbolSearch(args, router, runtime, token) { verifyAtBoundary(lease) }.toString()
            "semantic_search" -> codeIntelligence.semanticSearch(args, router, runtime, token) { verifyAtBoundary(lease) }.toString()
            "dependency_graph" -> codeIntelligence.dependencyGraph(args, router, runtime, token) { verifyAtBoundary(lease) }.toString()
            "diagnostics" -> codeIntelligence.diagnostics(args, router, runtime, token) { verifyAtBoundary(lease) }.toString()
            else -> null
        }
    }

    fun executionTool(
        name: String,
        args: JSONObject,
        token: dev.wind.agent.CancellationToken,
        providedLease: CapabilityLease? = null
    ): String {
        val gate = capabilityGate(name, args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val readOnly = name in dev.wind.agent.shell.mirror.ProjectMirrorManager.READ_ONLY_EXECUTION_TOOLS
        val result = projectMirrors.tool(
            name,
            args,
            router,
            currentTaskId,
            token,
            // A MirrorRead call has no side-effect callback to accidentally
            // authorize a prepare/sync. ProjectMirrorManager also enforces the
            // read-only observation boundary by tool name.
            beforeSideEffect = if (readOnly) null else ({ verifyAtBoundary(lease) })
        )
        // Establish the P4 baseline only after the authoritative P3
        // execution_prepare call has completed successfully.  Recovery is a
        // revalidation path and must not silently manufacture a new baseline.
        if (name == "execution_prepare" && result.startsWith("OK:") &&
            !result.contains("CHANGES_PENDING")) {
            codingSession?.captureSourceBaseline()
        }
        return result
    }

    fun shellCapabilities(): String = shellManager.detectCapabilities()

    fun publishExecutionArtifact(args: JSONObject, token: dev.wind.agent.CancellationToken,
        providedLease: CapabilityLease?, toolName: String = "prepare_apk_install"
    ): dev.wind.agent.artifact.VerifiedArtifact {
        val gate = capabilityGate(toolName, args, providedLease)
        check(gate.error == null) { gate.error ?: "CAPABILITY_DENIED" }
        val lease = requireNotNull(gate.lease) { "CAPABILITY_LEASE_MISSING" }
        val store = VerifiedArtifactStore(File(ctx.noBackupFilesDir.canonicalFile, "p7-artifacts"))
        return projectMirrors.publishExecutionArtifact(args.getString("execution_path"), router, token, store) {
            verifyAtBoundary(lease)
        }
    }

    fun shellExec(
        args: JSONObject,
        token: dev.wind.agent.CancellationToken,
        observer: Observer? = null,
        providedLease: CapabilityLease? = null
    ): String {
        val gate = capabilityGate("shell_exec", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val exe = args.optString("executable", "").trim()
        if (exe.isEmpty()) return "ERROR: executable is required"
        val argsList = mutableListOf<String>()
        val jsonArgs = args.optJSONArray("args")
        if (jsonArgs != null) for (i in 0 until jsonArgs.length()) argsList.add(jsonArgs.optString(i, ""))
        val timeoutSec = Math.max(5, Math.min(600, args.optInt("timeout_seconds", 60)))
        val networkDomains = linkedSetOf<String>()
        args.optJSONArray("network_domains")?.let { domains ->
            for (i in 0 until domains.length()) {
                NetworkGrant.normalizeDomain(domains.optString(i, ""))?.let { networkDomains.add(it) }
            }
        }
        val request = dev.wind.agent.shell.ShellRequest(
            executable = exe,
            args = argsList,
            cwd = "workspace://",
            timeoutMs = timeoutSec * 1000L,
            network = args.optBoolean("network", false),
            networkDomains = networkDomains,
            networkIsolation = "POLICY_ONLY"
        )
        observer?.onProgress("Executing: $exe ${argsList.joinToString(" ")}")
        val res = shellManager.executeCommand(
            currentTaskId,
            request,
            token,
            beforeExecute = {
                verifyAtBoundary(lease)
            },
            afterPrepare = {
                // The pre-execute lease callback also runs before a cold mirror
                // sync. Capture only after prepareSessionLocked has completed,
                // otherwise the sync itself looks like a source mutation.
                verifyAtBoundary(lease)
                codingSession?.captureSourceBaseline()
            }
        )
        val sb = StringBuilder()
        when {
            res.exitCode != null -> sb.append("Exit code: ").append(res.exitCode).append('\n')
            res.timedOut -> sb.append("TIMEOUT: command exceeded ").append(timeoutSec).append(" seconds\n")
            res.cancelled -> sb.append("CANCELLED: command was stopped\n")
            res.error != null -> sb.append("ERROR: ").append(res.error).append('\n')
        }
        if (res.stdout.isNotEmpty()) sb.append("\n=== STDOUT ===\n").append(res.stdout).append('\n')
        if (res.stderr.isNotEmpty()) sb.append("\n=== STDERR ===\n").append(res.stderr).append('\n')
        if (res.diffSummary.isNotEmpty()) sb.append('\n').append(res.diffSummary).append('\n')
        return sb.toString().trim()
    }

    fun projectPackages(args: JSONObject, token: dev.wind.agent.CancellationToken,
        observer: Observer? = null, providedLease: CapabilityLease? = null): String {
        val plan = dev.wind.agent.packages.ProjectPackageCommands.plan(args)
        val project = projectManager.getActiveProject() ?: return "ERROR: PROJECT_NOT_CONFIGURED"
        val alias = plan.projectPath.removePrefix("execution://").substringBefore('/')
        if (project.findMount(alias) == null) return "ERROR: PACKAGE_MOUNT_NOT_IN_PROJECT"
        val gate = capabilityGate("project_packages", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        return dev.wind.agent.packages.ProjectPackageRunner.run(plan, token) { request ->
            observer?.onProgress("${plan.manager}/${plan.action}: ${request.executable}")
            shellManager.executeCommand(currentTaskId, request, token,
                beforeExecute = { verifyAtBoundary(lease) },
                afterPrepare = {
                    verifyAtBoundary(lease)
                    codingSession?.captureSourceBaseline()
                }, preparedFiles = plan.preparedFiles)
        }.toString()
    }

    fun shellApplyChanges(args: JSONObject, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("shell_apply_changes", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val execId = args.optString("execution_id", "").trim()
        if (execId.isEmpty()) return "ERROR: execution_id is required"
        if (args.optBoolean("no_changes", false)) {
            return "OK: execution mirror has no pending canonical changes."
        }
        // Root ownership and bookkeeping are independent of quality checks.
        // P2 and P3 still bind approval, detect conflicts and commit via WAL.
        val session = codingSession
            ?: return "BLOCKED: P4_CODING_TASK_REQUIRED: canonical changes require a root coding session."
        val codingRefusal = session.allowApply(args.optString("diff_fingerprint", "")) { verifyAtBoundary(lease) }
        if (codingRefusal != null) {
            return "BLOCKED: $codingRefusal"
        }
        // ProjectMirrorManager performs a second exact ApplyChanges check at
        // its own mutation lock. Verify the carried proof immediately before
        // entering that boundary as well.
        verifyAtBoundary(lease)
        val applyPlan = shellManager.previewApply(execId)
        val receipt = session.prepareApplyReceipt(applyPlan)
        val applied = try {
            shellManager.applyChanges(execId, journal)
        } catch (failure: Exception) {
            session.onApplied(false, failure.message ?: failure.javaClass.simpleName, applyPlan)
            throw failure
        }
        val failed = applied.startsWith("ERROR") || applied.startsWith("BLOCKED")
        session.onApplied(!failed, applied, applyPlan, receipt)
        return applied
    }

    /** Enrich the mutable tool args with the exact plan before P2 authorization. */
    fun enrichApplyArgs(args: JSONObject): String? {
        val execId = args.optString("execution_id", args.optString("executionId", "")).trim()
        if (execId.isEmpty()) return "execution_id is required"
        val plan = try { shellManager.previewApply(execId) } catch (e: Exception) {
            return e.message ?: e.javaClass.simpleName
        }
        val mounts = JSONArray()
        plan.changedMountIds.sorted().forEach { mounts.put(it) }
        args.put("execution_id", plan.executionId)
            .put("mount_ids", mounts)
            .put("diff_fingerprint", plan.diffFingerprint)
            .put("has_destructive_changes", plan.hasDestructiveChanges)
            .put("no_changes", plan.changedMountIds.isEmpty())
        return null
    }

    fun shellDiscardChanges(args: JSONObject, providedLease: CapabilityLease? = null): String {
        val gate = capabilityGate("shell_discard_changes", args, providedLease)
        gate.error?.let { return "ERROR: $it" }
        val lease = gate.lease ?: return "ERROR: CAPABILITY_LEASE_MISSING"
        val execId = args.optString("execution_id", "").trim()
        if (execId.isEmpty()) return "ERROR: execution_id is required"
        return shellManager.discardChanges(execId, beforeSideEffect = { verifyAtBoundary(lease) })
    }
}


