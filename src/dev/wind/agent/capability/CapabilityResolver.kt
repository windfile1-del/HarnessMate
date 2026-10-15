package dev.wind.agent.capability

import android.content.Context
import dev.wind.agent.ToolRegistry
import dev.wind.agent.build.MavenDependencyInstallPlan
import dev.wind.agent.build.MavenDependencyPlanStore
import dev.wind.agent.project.ProjectPathParser
import dev.wind.agent.project.ProjectStore
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.MountMode
import dev.wind.agent.project.WorkspaceMount
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Maps a tool call to its real authority requirement. All path resolution is
 * done against the Project model, so aliases are converted to stable mountIds
 * before the request reaches the broker.
 */
class CapabilityResolver(
    private val projectLookup: (String) -> VegaProject?,
    private val dependencyPlanLookup: ((TaskContext, String) -> MavenDependencyInstallPlan?)?,
    private val downloadPlanLookup: ((TaskContext, String) -> dev.wind.agent.artifact.DownloadApplyPlan?)? = null,
    private val apkPlanLookup: ((TaskContext, String) -> dev.wind.agent.artifact.ApkInstallPlan?)? = null,
    private val searchDomains: () -> Set<String> = { setOf("lite.duckduckgo.com") },
    private val runtimeToolPlanLookup: ((TaskContext, String) -> CapabilityRequirement)? = null
) {

    constructor(projectLookup: (String) -> VegaProject?) : this(projectLookup, null, null, null)

    constructor(context: Context) : this(
        projectLookup = { projectId -> ProjectStore.get(context).getProject(projectId) },
        dependencyPlanLookup = { task, id ->
            try {
                MavenDependencyPlanStore(context.noBackupFilesDir.canonicalFile).load(task, id)
            } catch (_: Exception) {
                null
            }
        },
        downloadPlanLookup = { task, id ->
            try {
                dev.wind.agent.artifact.DownloadApplyPlanStore(context.noBackupFilesDir.canonicalFile).load(task, id)
            } catch (_: Exception) {
                null
            }
        },
        searchDomains = { dev.wind.agent.search.SearchProviderManager(dev.wind.agent.Prefs(context)).networkDomains() },
        runtimeToolPlanLookup = { task, id -> dev.wind.agent.runtime.linux.RuntimeToolInstallPlans(context).requirement(task, id) },
        apkPlanLookup = { task, id ->
            try {
                dev.wind.agent.artifact.ApkInstallPlanStore(context.noBackupFilesDir.canonicalFile).load(task, id)
            } catch (_: Exception) {
                null
            }
        }
    )

    fun resolve(context: TaskContext, toolName: String, args: JSONObject?): CapabilityRequirement {
        val spec = ToolRegistry.spec(toolName)
        if (spec == null || !spec.enabled || spec.risk.name == "DENY") {
            return CapabilityRequirement.Denied(context.projectId, "UNKNOWN_OR_DISABLED_TOOL")
        }
        val a = args ?: JSONObject()
        val project = context.projectId.takeIf { it.isNotBlank() }?.let(projectLookup)
        val projectId = context.projectId
        if(toolName=="mate_document")return if(context.mode==CapabilityMode.PLAN)CapabilityRequirement.Denied(projectId,"PLAN_MODE_DOCUMENT_WRITE_DENIED") else CapabilityRequirement.Internal(projectId)
        if(toolName=="mate_task") {
            val mode=a.optString("mode","status")
            if(mode !in setOf("status","list","note","wait","pause","finish"))return CapabilityRequirement.Denied(projectId,"MATE_TASK_MODE_INVALID")
            return if(context.mode==CapabilityMode.PLAN && mode !in setOf("status","list"))CapabilityRequirement.Denied(projectId,"PLAN_MODE_MATE_TASK_WRITE_DENIED") else CapabilityRequirement.Internal(projectId)
        }
        if (toolName in dev.wind.agent.browser.BrowserProtocol.tools) {
            return try {
                dev.wind.agent.browser.BrowserProtocol.validate(toolName,a)
                if(context.mode==CapabilityMode.PLAN && dev.wind.agent.browser.BrowserProtocol.isMutating(toolName,a))
                    CapabilityRequirement.Denied(projectId,"PLAN_MODE_BROWSER_MUTATION_DENIED")
                else {
                    val domains=dev.wind.agent.browser.BrowserProtocol.domains(a)
                    if(domains.isEmpty())CapabilityRequirement.Internal(projectId) else CapabilityRequirement.Network(projectId,domains)
                }
            }catch(e:Exception){CapabilityRequirement.Denied(projectId,"BROWSER_ARGUMENT_INVALID: ${e.message}")}
        }
        if(toolName=="phone_chat_guardian") {
            val mode=a.optString("mode","status")
            if(mode !in setOf("status","prepare","start","activate","pause","takeover","resume","stop","pending","approve","report","configure"))
                return CapabilityRequirement.Denied(projectId,"CHAT_GUARDIAN_MODE_INVALID")
            return if(context.mode==CapabilityMode.PLAN && mode !in setOf("status","pending","report"))
                CapabilityRequirement.Denied(projectId,"PLAN_MODE_PHONE_CONTROL_DENIED") else CapabilityRequirement.Internal(projectId)
        }
        if(toolName=="phone_display") {
            if(a.optString("action","status") !in setOf("status","create","close")) return CapabilityRequirement.Denied(projectId,"PHONE_DISPLAY_ACTION_INVALID")
            return if(context.mode==CapabilityMode.PLAN && a.optString("action","status")!="status")
                CapabilityRequirement.Denied(projectId,"PLAN_MODE_PHONE_CONTROL_DENIED") else CapabilityRequirement.Internal(projectId)
        }
        // ADB action mutability comes from validated arguments, never static risk labels.
        if (toolName.startsWith("adb_")) {
            val plan = try { dev.wind.agent.adb.AdbCommands.plan(toolName, a) }
                catch (error: Exception) { return CapabilityRequirement.Denied(projectId, "ADB_ARGUMENT_INVALID: ${error.message}") }
            return if (context.mode == CapabilityMode.PLAN && plan.mutating)
                CapabilityRequirement.Denied(projectId, "PLAN_MODE_ADB_MUTATION_DENIED")
            else CapabilityRequirement.Internal(projectId)
        }
        // Absolute user-storage file calls have their own canonical path gate and OS boundary.
        val fileTools = setOf("list_dir", "read_file", "file_info", "search_files", "glob", "write_file", "edit_file", "delete_path", "make_dir", "move_path")
        if (toolName in fileTools && (pathArg(a).startsWith("/") ||
                toolName == "move_path" && a.optString("source", a.optString("from")).startsWith("/"))) {
            return if (context.mode == CapabilityMode.PLAN && toolName !in setOf("list_dir", "read_file", "file_info", "search_files", "glob"))
                CapabilityRequirement.Denied(projectId, "PLAN_MODE_CAPABILITY_DENIED") else CapabilityRequirement.Internal(projectId)
        }

        // Phone authority is separate from project/file/network leases. The native PhoneTools
        // boundary enforces opt-in + service state + exact ONCE approval on every external effect.
        if (spec.effect == dev.wind.agent.ToolEffect.PHONE_CONTROL || spec.effect == dev.wind.agent.ToolEffect.PHONE_READ) {
            return if (context.mode == CapabilityMode.PLAN && spec.mutating)
                CapabilityRequirement.Denied(projectId, "PLAN_MODE_PHONE_CONTROL_DENIED")
            else CapabilityRequirement.Internal(projectId)
        }

        return when (toolName) {
            "list_dir", "read_file", "file_info", "search_files", "glob" ->
                CapabilityRequirement.ProjectRead(projectId, mountIds(project, pathArg(a)))

            // These calls expose project/task metadata, not file contents. They
            // are deliberately internal so a status probe cannot be turned into
            // a fake empty-mount read (which would otherwise fail with
            // MOUNT_ID_REQUIRED) and so diagnostics never prompt for authority.
            "workspace_mounts", "capability_status", "recall", "remember", "memory_search", "memory_read", "memory_propose", "memory_forget", "history_search", "history_read", "tool_search", "tool_describe", "shell_capabilities", "inspect_artifact", "list_skills", "read_skill", "list_runtime_tools", "mate_task" ->
                CapabilityRequirement.Internal(projectId)

            "list_secrets" -> CapabilityRequirement.Internal(projectId)
            "publish_execution_artifact" -> CapabilityRequirement.MirrorRead(projectId)
            "remote_git" -> try {
                val url=dev.wind.agent.secrets.SecretDestination.repository(a.getString("repository_url"))
                CapabilityRequirement.Network(projectId,setOf(URI(url).host))
            } catch (_:Exception) { CapabilityRequirement.Denied(projectId,"REMOTE_GIT_DESTINATION_INVALID") }
            "prepare_apk_install" -> if (a.optString("execution_path").isNotBlank())
                CapabilityRequirement.MirrorRead(projectId) else CapabilityRequirement.Internal(projectId)

            "list_downloads", "prepare_download_apply", "apk_install_status", "delete_download" ->
                CapabilityRequirement.Internal(projectId)

            "cleanup_downloads" -> {
                val scope = a.optString("scope", "task").lowercase()
                if (scope == "all" && !a.optBoolean("confirm_all", false)) {
                    CapabilityRequirement.Denied(projectId, "CLEANUP_CONFIRMATION_REQUIRED")
                } else {
                    CapabilityRequirement.Internal(projectId)
                }
            }

            "apply_download" -> {
                val plan = try { downloadPlanLookup?.invoke(context, a.optString("plan_id", "")) }
                    catch (_: Exception) { null }
                if (plan == null ||
                    (plan.projectId.isNotBlank() && projectId.isNotBlank() && plan.projectId != projectId) ||
                    (project != null && project.findMountById(plan.mountId) == null)
                ) {
                    CapabilityRequirement.Denied(projectId, "DOWNLOAD_APPLY_PLAN_UNAVAILABLE")
                } else {
                    CapabilityRequirement.CanonicalWrite(projectId, setOf(plan.mountId))
                }
            }

            "install_apk" -> {
                val plan = try { apkPlanLookup?.invoke(context, a.optString("plan_id", "")) }
                    catch (_: Exception) { null }
                if (plan == null) {
                    CapabilityRequirement.Denied(projectId, "APK_INSTALL_PLAN_UNAVAILABLE")
                } else {
                    CapabilityRequirement.ArtifactInstall(
                        projectId = projectId,
                        kind = CapabilityRequirement.ArtifactInstallKind.ANDROID_PACKAGE,
                        artifactSha256 = plan.artifactSha256,
                        targetId = plan.packageName
                    )
                }
            }

            "write_file", "edit_file", "make_dir" ->
                CapabilityRequirement.CanonicalWrite(projectId, mountIds(project, pathArg(a)))

            "move_path" -> CapabilityRequirement.CanonicalWrite(
                projectId,
                (mountIds(project, a.optString("source", a.optString("from", ""))) +
                    mountIds(project, a.optString("destination", a.optString("to", ""))))
            )

            "delete_path" -> CapabilityRequirement.CanonicalDelete(projectId, deleteMountIds(project, a))

            "execution_prepare", "execution_recover" -> CapabilityRequirement.Execution(projectId)

            "execution_list_dir", "execution_read_file", "execution_file_info",
            "execution_search_files", "execution_glob" -> CapabilityRequirement.MirrorRead(projectId)

            "execution_write_file", "execution_edit_file", "execution_delete_file",
            "execution_make_dir", "execution_import_archive", "shell_discard_changes" ->
                CapabilityRequirement.MirrorMutation(projectId)

            "project_packages" -> try {
                val plan = dev.wind.agent.packages.ProjectPackageCommands.plan(a)
                val alias = plan.projectPath.removePrefix("execution://").substringBefore('/')
                if (project?.findMount(alias) == null) CapabilityRequirement.Denied(projectId, "PACKAGE_MOUNT_NOT_IN_PROJECT")
                else {
                    val execution = CapabilityRequirement.Execution(projectId)
                    if (plan.networkDomains.isEmpty()) execution else CapabilityRequirement.Composite(projectId, listOf(
                        execution, CapabilityRequirement.Network(projectId, plan.networkDomains, execution = true)))
                }
            } catch (failure: Exception) {
                CapabilityRequirement.Denied(projectId, "PACKAGE_ARGUMENT_INVALID: ${failure.message}")
            }

            "shell_exec" -> {
                val execution = CapabilityRequirement.Execution(projectId)
                val network = if (a.optBoolean("network", false)) {
                    CapabilityRequirement.Network(projectId, networkDomains(a), execution = true)
                } else null
                if (network == null) execution else CapabilityRequirement.Composite(projectId, listOf(execution, network))
            }

            // P4 exposes build/test/diagnostics as first-class tools, but they
            // must not invent a new authority class: running the build is
            // execution, and reading the harness's own diagnostic list touches
            // no file at all.
            "build", "test" -> CapabilityRequirement.Execution(projectId)

            "diagnostics" -> CapabilityRequirement.Internal(projectId).let { internal ->
                val srcStr = a.optString("source", "validation").lowercase(); if (srcStr == "lsp" || srcStr == "all") {
                    CapabilityRequirement.Composite(projectId, listOf(CapabilityRequirement.MirrorRead(projectId), CapabilityRequirement.Execution(projectId)))
                } else internal
            }

            // P5 Code Intelligence
            "symbol_search", "semantic_search", "dependency_graph" ->
                CapabilityRequirement.MirrorRead(projectId)

            "find_definition", "find_references" -> {
                val backend = a.optString("backend", "index").lowercase()
                if (backend == "lsp") {
                    CapabilityRequirement.Composite(projectId, listOf(CapabilityRequirement.MirrorRead(projectId), CapabilityRequirement.Execution(projectId)))
                } else {
                    CapabilityRequirement.MirrorRead(projectId)
                }
            }

            "shell_apply_changes" -> if (a.optBoolean("no_changes", false)) {
                CapabilityRequirement.Internal(projectId)
            } else {
                CapabilityRequirement.ApplyChanges(
                    projectId = projectId,
                    executionId = a.optString("execution_id", a.optString("executionId", "")),
                    changedMountIds = jsonStringSet(a.optJSONArray("mount_ids")),
                    diffFingerprint = a.optString("diff_fingerprint", a.optString("diffFingerprint", "")),
                    hasDestructiveChanges = a.optBoolean("has_destructive_changes", false)
                )
            }

            "web_fetch", "fetch_url" -> host(a.optString("url", ""))?.let {
                CapabilityRequirement.Network(projectId, setOf(it))
            } ?: CapabilityRequirement.Denied(projectId, "NETWORK_URL_INVALID")
            "download_file" -> host(a.optString("url", ""))?.let {
                CapabilityRequirement.Network(projectId, setOf(it))
            } ?: CapabilityRequirement.Denied(projectId, "NETWORK_URL_INVALID")
            "ensure_runtime_tools" -> try {
                val list = a.getJSONArray("tools")
                dev.wind.agent.runtime.linux.RuntimeToolCatalog.families((0 until list.length()).map { list.getString(it) })
                CapabilityRequirement.Network(projectId, dev.wind.agent.runtime.linux.RuntimeToolCatalog.networkDomains)
            } catch (failure: Exception) { CapabilityRequirement.Denied(projectId, "RUNTIME_TOOL_ARGUMENT_INVALID") }
            "install_runtime_tools" -> try {
                runtimeToolPlanLookup?.invoke(context, a.getString("plan_id"))
                    ?: CapabilityRequirement.Denied(projectId, "RUNTIME_TOOL_PLAN_MISSING")
            } catch (failure: Exception) { CapabilityRequirement.Denied(projectId, "RUNTIME_TOOL_PLAN_INVALID: ${failure.message}") }
            "download_github_repository" -> {
                try {
                    dev.wind.agent.artifact.GithubRepositoryDownload.parse(
                        a.optString("repository_url", ""), a.optString("ref", "HEAD"))
                    CapabilityRequirement.Network(projectId, setOf("codeload.github.com"))
                } catch (_: Exception) {
                    CapabilityRequirement.Denied(projectId, "GITHUB_REPOSITORY_INPUT_INVALID")
                }
            }
            "resolve_maven_dependencies" -> {
                val domain = host(a.optString("repository_url", ""))
                val mounts = mountIds(project, a.optString("project_path", "workspace://"))
                if (domain == null || mounts.isEmpty()) CapabilityRequirement.Denied(projectId, "MAVEN_PLAN_INPUT_INVALID")
                else CapabilityRequirement.Composite(projectId, listOf(
                    CapabilityRequirement.ProjectRead(projectId, mounts),
                    CapabilityRequirement.Network(projectId, setOf(domain))))
            }
            "install_maven_dependencies" -> {
                val plan = try { dependencyPlanLookup?.invoke(context, a.optString("plan_id", "")) }
                    catch (_: Exception) { null }
                if (plan == null || plan.taskId != context.taskId || plan.projectId != projectId ||
                    plan.generation != context.projectGeneration || project?.findMountById(plan.mountId) == null) {
                    CapabilityRequirement.Denied(projectId, "MAVEN_INSTALL_PLAN_UNAVAILABLE")
                } else CapabilityRequirement.Composite(projectId, listOf(
                    CapabilityRequirement.ArtifactInstall(projectId,
                        CapabilityRequirement.ArtifactInstallKind.PROJECT_DEPENDENCY,
                        plan.id, plan.targetId, plan.mountId),
                    CapabilityRequirement.CanonicalWrite(projectId, setOf(plan.mountId))))
            }
            "web_search" -> {
                val domains = searchDomains()
                if (domains.isEmpty()) CapabilityRequirement.Denied(projectId, "SEARCH_PROVIDER_CONFIGURATION_INVALID")
                else CapabilityRequirement.Network(projectId, domains)
            }
            "request_workspace_access" -> {
                val alias = a.optString("alias", "").trim().lowercase()
                if (!alias.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) {
                    CapabilityRequirement.Denied(projectId, "MOUNT_ALIAS_INVALID")
                } else {
                    val mode = when (a.optString("mode", "read_write").trim().lowercase()) {
                        "read_only" -> MountMode.READ_ONLY
                        "read_write" -> MountMode.READ_WRITE
                        else -> return CapabilityRequirement.Denied(projectId, "MOUNT_MODE_INVALID")
                    }
                    val executionPath = a.optString(
                        "suggested_execution_path",
                        a.optString("suggestedExecutionPath", alias)
                    ).trim()
                    if (!WorkspaceMount.isValidExecutionPath(executionPath)) {
                        CapabilityRequirement.Denied(projectId, "EXECUTION_PATH_INVALID")
                    } else {
                        // Reauthorization of an existing alias must use the
                        // durable mount's effective mode/path. Otherwise a
                        // request can be approved for one topology while the
                        // picker later applies another.
                        val existing = project?.findMount(alias)
                        CapabilityRequirement.CapabilityExpansion(
                            projectId = projectId,
                            alias = alias,
                            requestedMode = existing?.mode ?: mode,
                            executionPath = existing?.executionPath ?: executionPath
                        )
                    }
                }
            }

            "task" -> CapabilityRequirement.Internal(projectId)
            else -> CapabilityRequirement.Denied(projectId, "TOOL_EFFECT_UNMAPPED")
        }
    }

    private fun pathArg(args: JSONObject): String {
        for (key in arrayOf("destination", "destination_path", "dest", "path", "file_path", "filepath", "file", "filename", "target")) {
            val value = args.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun deleteMountIds(project: VegaProject?, args: JSONObject): Set<String> {
        val array = args.optJSONArray("paths")
        if (array == null || array.length() == 0) return mountIds(project, pathArg(args))
        val out = linkedSetOf<String>()
        for (i in 0 until array.length()) out.addAll(mountIds(project, array.optString(i, "")))
        return out
    }

    private fun mountIds(project: VegaProject?, raw: String): Set<String> {
        if (project == null) return emptySet()
        val path = raw.trim()
        if (path.isEmpty()) return setOfNotNull(project.primaryMount()?.id)
        return try {
            setOfNotNull(project.findMount(ProjectPathParser.parse(path, project).mountAlias)?.id)
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun networkDomains(args: JSONObject): Set<String> =
        jsonStringSet(args.optJSONArray("network_domains")).mapNotNull { NetworkGrant.normalizeDomain(it) }.toSet()

    private fun jsonStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }

    private fun host(raw: String): String? = try {
        val value = URI(raw.trim()).host?.lowercase()?.trimEnd('.')
        NetworkGrant.normalizeDomain(value)
    } catch (_: Exception) {
        null
    }
}
