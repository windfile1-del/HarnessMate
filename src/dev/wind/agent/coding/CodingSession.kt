package dev.wind.agent.coding

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.project.ProjectIdentity
import dev.wind.agent.project.ProjectManager
import dev.wind.agent.trimJavaOrEmpty
import org.json.JSONArray
import org.json.JSONObject

/**
 * One coding task, as the tools and the engine see it.
 *
 * This is the adapter layer: it owns the runtime, turns the three P4 tools into
 * calls on it, and answers the engine's "may this run finish" question. It is
 * deliberately thin — every decision of substance lives in [CodingTaskRuntime],
 * so the decisions are testable without a Context and there is exactly one place
 * where "is this verified" is answered.
 */
class CodingSession(
    private val context: Context?,
    val runtime: CodingTaskRuntime,
    private val taskId: String,
    /**
     * Whether this session owns the root run's factual closing report.
     *
     * True for the run that owns the task. False for a sub-agent: a delegated
     * phase ends when its brief is done, and forcing every child to run the full
     * final verification would multiply the build cost by the number of agents
     * for no additional guarantee — the root owns canonical application and its evidence, and a child's mutations invalidate the root's stamp while
     * it is at it.
     */
    private val gateFinish: Boolean = true,
    /** Only the root session may commit a canonical apply. */
    private val rootOwner: Boolean = true,
    /**
     * Receives each coding event's phase line, so the existing trail shows what
     * the task is doing without P4 owning a progress UI of its own.
     */
    private val onPhase: ((String) -> Unit)? = null
) {
    @Volatile
    private var profile: ProjectProfile? = runtime.currentProfile
    private var detectionFailure: String? = null
    private var projectPath: String? = runtime.snapshot?.selectedProjectPath
    private var customChecks: List<ValidationCommand> = runtime.snapshot?.registeredChecks ?: emptyList()
    // A passive session must not consult a blocked runtime just to send a message.
    private var plannedSource: String? = null

    init {
        val sink = onPhase
        if (sink != null) {
            runtime.subscribe { event ->
                dev.wind.agent.coding.CodingTrailAdapter.phaseFor(event)?.let { phase -> sink(phase) }
            }
        }
    }

    val phase: CodingPhase get() = runtime.phase

    fun onToolStart(tool: String): String? = runtime.beforeTool(tool)

    fun onToolEnd(tool: String, ok: Boolean, declaredMutation: Boolean) =
        runtime.afterTool(tool, ok, declaredMutation)

    /** Risk labels describe UI/authority, not source-content changes. */
    fun onToolEnd(tool: String, ok: Boolean, effect: dev.wind.agent.ToolEffect) {
        if (tool == "shell_apply_changes") return
        if (tool == "shell_discard_changes" && ok) {
            runtime.noteDiscarded()
            return
        }
        if (effect == dev.wind.agent.ToolEffect.CANONICAL_WRITE || effect == dev.wind.agent.ToolEffect.CANONICAL_DELETE) {
            if (ok) runtime.noteCanonicalOperation(tool)
            return
        }
        runtime.afterTool(tool, ok, effect == dev.wind.agent.ToolEffect.MIRROR_MUTATE)
    }

    /** Called by the P3 shell boundary after it has prepared the mirror. */
    fun captureSourceBaseline() = runtime.captureSourceBaseline()

    /**
     * Implements the three P4 tools.
     *
     * Returns null when [name] is not one of them, so the caller can fall through
     * to the ordinary tool path without this layer needing a tool registry.
     */
    fun run(
        name: String,
        args: JSONObject,
        token: CancellationToken,
        beforeExecute: (() -> Unit)? = null
    ): String? = when (name) {
        "build" -> build(args, token, beforeExecute)
        "test" -> test(args, token, beforeExecute)
        "diagnostics" -> diagnostics()
        else -> null
    }

    private fun build(
        args: JSONObject,
        token: CancellationToken,
        beforeExecute: (() -> Unit)?
    ): String {
        val mode = args.optString("mode", "fast").trimJavaOrEmpty().lowercase()
        if (mode != "fast" && mode != "final") {
            return "ERROR: mode must be 'fast' or 'final'."
        }
        if (args.has("project_path")) projectPath = args.getString("project_path")
        args.optJSONArray("checks")?.let { raw ->
            require(raw.length() in 1..8) { "P4_CUSTOM_CHECK_COUNT_INVALID" }
            customChecks = (0 until raw.length()).map { index ->
                val argv = raw.getJSONArray(index)
                require(argv.length() in 1..64) { "P4_CUSTOM_ARGV_INVALID" }
                val values = (0 until argv.length()).map { argv.getString(it).also { arg ->
                    require(arg.length <= 4096 && '\u0000' !in arg) { "P4_CUSTOM_ARGUMENT_INVALID" }
                } }
                require(values.first() != "harness-documentation-diff") { "P4_INTERNAL_CHECK_RESERVED" }
                dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest(values.first(), values.drop(1)))
                ValidationCommand("custom-${index + 1}", "Registered check ${index + 1}", values)
            }
        }
        runtime.configureValidation(projectPath, customChecks)
        val currentPlan = ensurePlan(args.has("project_path") || args.has("checks") || args.optBoolean("refresh_plan", false)) ?: return missingPlan()
        return if (mode == "fast") {
            val checks = currentPlan.fastChecks
            if (checks.isEmpty()) {
                if (currentPlan.finalChecks.isEmpty()) return noChecks("P4_NO_FAST_CHECKS", currentPlan)
                return "ERROR: P4_NO_FAST_CHECKS: the harness has no compile-shaped check for this project. " +
                    "Run test with scope=all to use the final plan."
            }
            val failure = runtime.runFastChecks(token, beforeExecute)
            if (failure == null) {
                "Build (fast) passed: " + checks.joinToString(", ") { it.id }
            } else {
                describeFailure(failure)
            }
        } else {
            // mode=final is the verification path: it runs the whole plan and
            // issues the stamp, so the model cannot ask for "final" and then
            // claim success on a run the harness never stamped.
            verifyOrDescribe(token, beforeExecute)
        }
    }

    private fun test(
        args: JSONObject,
        token: CancellationToken,
        beforeExecute: (() -> Unit)?
    ): String {
        val scope = args.optString("scope", "affected").trimJavaOrEmpty().lowercase()
        if (scope != "affected" && scope != "all") {
            return "ERROR: scope must be 'affected' or 'all'."
        }
        val currentPlan = ensurePlan() ?: return missingPlan()
        if (scope == "all" && currentPlan.finalChecks.isNotEmpty()) return verifyOrDescribe(token, beforeExecute)
        val checks = if (scope == "all") {
            currentPlan.finalChecks.filter { it.id != "build" }
        } else {
            currentPlan.finalChecks.filter { it.id == "test" || it.id == "check" }
        }
        if (checks.isEmpty()) {
            if (currentPlan.finalChecks.isEmpty()) return noChecks("P4_NO_TEST_CHECKS", currentPlan)
            return "ERROR: P4_NO_TEST_CHECKS: no test runner was found for this project. " +
                "(" + currentPlan.reason + ")"
        }
        for (command in checks) {
            val summary = runtime.validate(command, token, isFinal = true, beforeExecute = beforeExecute)
            if (!summary.ok) {
                return describeFailure(summary)
            }
        }
        return "Tests passed: " + checks.joinToString(", ") { it.id }
    }

    private fun diagnostics(): String {
        val report = runtime.currentReport()
            ?: return "No build or test has been run for this task yet."
        val sb = StringBuilder()
        sb.append("Errors: ").append(report.errors.size)
        sb.append("  Warnings: ").append(report.warnings.size).append('\n')
        val fresh = runtime.currentFreshErrors()
        val resolved = runtime.currentResolvedErrors()
        if (fresh.isNotEmpty() && fresh.size != report.errors.size) {
            sb.append("New in the last run: ").append(fresh.size).append('\n')
        }
        if (resolved.isNotEmpty()) {
            sb.append("Resolved since the previous run: ").append(resolved.size).append('\n')
            for (item in resolved.take(10)) {
                sb.append("  - ").append(item.oneLine()).append('\n')
            }
        }
        for (item in report.errors.take(20)) {
            sb.append("- ").append(item.oneLine()).append('\n')
        }
        for (item in report.warnings.take(5)) {
            sb.append("~ ").append(item.oneLine()).append('\n')
        }
        val phase = runtime.snapshot?.phase
        val stamp = runtime.snapshot?.verification
        sb.append("Phase: ").append(phase ?: CodingPhase.CREATED).append('\n')
        sb.append("Verified: ").append(if (stamp != null && runtime.verificationValid()) "yes" else "no")
        if (stamp != null) {
            sb.append(" (").append(stamp.passedChecks.joinToString(", ")).append(')')
        }
        return sb.toString()
    }

    private fun verifyOrDescribe(token: CancellationToken, beforeExecute: (() -> Unit)?): String = try {
        val stamp = runtime.runFinalVerification(token, beforeExecute)
        "Verification passed: " + stamp.passedChecks.joinToString(", ") +
            "\nSource fingerprint: " + stamp.sourceFingerprint.take(16) +
            (runtime.pendingExecutionId()?.let { "\nExecution ID: $it\nUse this latest ID for shell_apply_changes." } ?: "")
    } catch (failed: VerificationFailedException) {
        describeFailure(failed.summary)
    } catch (e: Exception) {
        val detail = e.message ?: e.javaClass.simpleName
        if (detail.startsWith("P4_NO_") || detail.startsWith("P4_PLAN_") || detail.startsWith("P4_MIRROR_")) {
            runtime.recordEnvironmentBlock(detail)
            "BLOCKED: $detail"
        } else {
            runtime.recordEnvironmentBlock(detail)
            "ERROR: $detail"
        }
    }

    private fun describeFailure(summary: ValidationSummary): String {
        val report = runtime.currentReport()
        val sb = StringBuilder()
        sb.append(if (summary.blocked) "BLOCKED: " else "FAILED: ")
        sb.append(summary.check).append('\n')
        if (report != null) {
            sb.append(
                DiagnosticPipeline.summary(
                    report,
                    runtime.currentFreshErrors(),
                    runtime.currentResolvedErrors()
                )
            )
            sb.append('\n')
        }
        val decision = runtime.decideAfterFailure()
        when (decision) {
            is RetryDecision.ContinueRepair -> {
                sb.append("Repair attempt ").append(decision.cycle).append(" of ")
                sb.append(decision.cycle + decision.remaining)
                if (!decision.improving) {
                    sb.append(" (the error count did not drop last time)")
                }
                sb.append(". Fix the errors above, then run build with mode=fast again.")
            }
            is RetryDecision.EnvironmentBlocked ->
                sb.append(
                    "This is an ENVIRONMENT failure, not a code failure: "
                ).append(decision.reason).append(
                    ". Do not edit source to work around it — report what is missing instead."
                )
            is RetryDecision.NoProgress ->
                sb.append("NO_PROGRESS: ").append(decision.reason).append(
                    ". Stop repeating the same change: reconsider the diagnosis, or report the task as blocked."
                )
            is RetryDecision.BudgetExhausted ->
                sb.append("The repair budget (").append(decision.cycles).append(
                    " cycles) is exhausted. Report honestly what still fails."
                )
            is RetryDecision.Cancelled -> sb.append("The run was cancelled.")
            RetryDecision.VerificationReady -> Unit
        }
        return sb.toString()
    }

    /** The plan, detected on first use if the engine has not supplied one yet. */
    private fun ensurePlan(refresh: Boolean = false): ValidationPlan? {
        val source = runtime.currentSourceFingerprint()
        if (!refresh && (context == null || source == plannedSource)) runtime.currentPlan?.let { return it }
        val documents = try { runtime.documentationOnly() } catch (failure: Exception) {
            detectionFailure = failure.message ?: failure.javaClass.simpleName
            return null
        }
        val detected = if (documents) ProjectProfile(ProjectKind.GENERIC, "execution://", confidence = Confidence.HIGH)
            else detect() ?: return null
        var plan = ValidationPlanner.plan(detected)
        if (documents) {
            plan = ValidationPlan(finalChecks = listOf(ValidationCommand("documentation-diff", "Review documentation diff",
                listOf("harness-documentation-diff"))), confidence = Confidence.HIGH,
                reason = "Only documentation changed; exact diff integrity is checked, no compilation or tests claimed.")
        } else if (customChecks.isNotEmpty()) {
            plan = plan.copy(finalChecks = plan.finalChecks + customChecks.map { it.copy(cwd = detected.root) })
        }
        // Platform-free hosts may supply their own plan; production always
        // redetects the prepared mirror rather than caching canonical markers.
        if (context == null && runtime.currentProfile == detected && customChecks.isEmpty() && !documents) {
            runtime.currentPlan?.let { return it }
        }
        runtime.attachProfile(detected, plan)
        plannedSource = source
        return runtime.currentPlan
    }

    private fun noChecks(code: String, plan: ValidationPlan): String {
        val detail = "$code: ${plan.reason}. Register checks with build mode=final checks=[[executable,arg,...]], or specify project_path."
        runtime.recordEnvironmentBlock(detail)
        return "BLOCKED: $detail"
    }

    private fun missingPlan(): String {
        val detail = detectionFailure ?: "P4_PLAN_MISSING: no validation plan could be detected for this project"
        runtime.recordEnvironmentBlock(detail)
        return "BLOCKED: " + detail + ". No check was run; do not repeat the same build without correcting the environment."
    }

    /**
     * Reads the prepared mirror on every validation; no canonical marker cache.
     *
     * Returns null without a Context, which is the case in the JVM suites: they
     * drive the runtime directly and never need detection.
     */
    fun detect(): ProjectProfile? {
        return try {
            val mirrored = runtime.detectProject(projectPath)
            val detected = mirrored ?: if (context == null) runtime.currentProfile else ProjectDetector.detect(ProjectManager(context).router)
            if (context != null) configurePathMapper(ProjectManager(context).router)
            detectionFailure = null
            profile = detected
            detected
        } catch (failure: Exception) {
            detectionFailure = failure.message ?: failure.javaClass.simpleName
            null
        }
    }

    /**
     * Points the diagnostic path mapper at this project's mounts, so no build
     * output can leak a Termux path to the model.
     */
    private fun configurePathMapper(router: dev.wind.agent.project.WorkspaceRouter) {
        val project = try {
            router.getActiveProject()
        } catch (_: Exception) {
            return
        }
        val mounts = project.mounts.map { mount ->
            mount.executionPath to if (mount.isPrimary) "workspace://" else "project://" + mount.alias + "/"
        }
        val remoteRoot = ProjectIdentity.remoteRoot(project.id) + "/project"
        // PRoot exposes the mirror container as /workspace. Keep that root,
        // so main/src/A.kt still maps through the primary mount to src/A.kt.
        val remoteRoots = listOf(remoteRoot, "/workspace")
        DiagnosticPathMapper.configure(mounts, remoteRoots)
    }

    /** Called when the run ends, so the recorded task reflects how it ended. */
    fun finish(status: TaskStatus, note: String = ""): FinalTaskReport = runtime.finishWith(status, note)

    fun finishRun(note: String = ""): FinalTaskReport? {
        if (!gateFinish) return null
        return if (runtime.active) runtime.finishRun(note) else null
    }

    /** Root ownership; quality evidence does not replace P2 authorization or P3 integrity. */
    fun allowApply(diffFingerprint: String, beforeReview: (() -> Unit)? = null): String? {
        if (!rootOwner) return "P4_SUBAGENT_APPLY_DENIED: only the root session owns canonical apply."
        if (runtime.snapshot == null) return "P4_CODING_TASK_REQUIRED: no root task state."
        // Permission, exact diff, content integrity and WAL are enforced by P2/P3.
        // Verification describes quality; it does not authorize or prohibit saving.
        beforeReview?.invoke()
        runtime.beforeTool("shell_apply_changes")?.let { return it }
        runtime.refreshSourceFingerprint()
        runtime.noteDiffFingerprint(diffFingerprint, 0)
        return null
    }

    /**
     * Records the result of the real apply, so the task's durable state agrees
     * with what happened to the user's project.
     *
     * Called from the tool layer rather than from the engine, because only the
     * tool knows whether the P3 commit actually went through.
     */
    fun prepareApplyReceipt(plan: dev.wind.agent.shell.mirror.ApplyPlan): CodingApplyReceipt {
        if (!rootOwner) throw IllegalStateException("P4_SUBAGENT_APPLY_DENIED")
        return CodingApplyReceipt(plan.executionId, plan.diffFingerprint,
            runtime.currentSourceFingerprint() ?: CodingTaskRuntime.NO_MIRROR, runtime.mutationEpoch,
            plan.createdFiles, plan.modifiedFiles, plan.deletedFiles, runtime.now(),
            runtime.verificationValid(runtime.currentPlan?.fingerprint()))
    }

    fun onApplied(ok: Boolean, detail: String = "", plan: dev.wind.agent.shell.mirror.ApplyPlan? = null,
        preparedReceipt: CodingApplyReceipt? = null) {
        if (!rootOwner) throw IllegalStateException("P4_SUBAGENT_APPLY_DENIED")
        val receipt = if (ok) preparedReceipt ?: prepareApplyReceipt(requireNotNull(plan) { "CODING_APPLY_PLAN_REQUIRED" }) else null
        runtime.markApplied(ok, detail, receipt)
    }

    /** The gate the engine should consult, or null when this run may just end. */
    fun finishDecision(): dev.wind.agent.coding.FinishDecision? {
        if (!gateFinish) {
            return null
        }
        // Ordinary replies do not depend on another task's mirror or runtime.
        if (!runtime.active) return null
        return runtime.requestFinish()
    }

}
