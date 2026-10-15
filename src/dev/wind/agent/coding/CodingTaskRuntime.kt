package dev.wind.agent.coding

import dev.wind.agent.CancellationToken

/**
 * P4: the layer that makes "the agent changed some files" into "the agent
 * finished a task".
 *
 * It owns four things the agent loop does not: what the project is, what has to
 * pass for a change to count as verified, whether the code has changed since the
 * verification being claimed, and the observed outcome when the model stops.
 *
 * It deliberately does NOT own a second model loop. The engine calls in at a few
 * points — before a tool, after a tool, when the model wants to finish — and
 * everything else here is bookkeeping. A second loop would mean a second set of
 * retry semantics, a second place to cancel, and two loops that can disagree
 * about what the task is doing.
 */
class CodingTaskRuntime(
    private val host: CodingRuntimeHost,
    private val store: CodingStateStore? = null,
    private val bus: CodingEventBus = CodingEventBus()
) {
    @Volatile
    private var state: CodingTaskState? = null

    @Volatile
    private var cancelledRun = false

    /** Diagnostics from the most recent validation, for the diagnostics tool. */
    @Volatile
    private var lastReport: DiagnosticReport? = null

    /** Errors from the previous validation, so fixed ones can be named. */
    @Volatile
    private var previousErrors: List<Diagnostic> = emptyList()

    private val retry = RetryController()
    private var plan: ValidationPlan? = null
    private var profile: ProjectProfile? = null
    private var persistenceWarning: String? = null

    /**
     * The mirror content fingerprint as the harness last observed it.
     *
     * Kept separately from the recorded state so that a change made by something
     * other than an execution tool — a shell command, a code generator inside a
     * build — is still detected as a change.
     */
    @Volatile
    private var lastKnownSource: String? = null

    val taskId: String? get() = state?.taskId
    val phase: CodingPhase get() = state?.phase ?: CodingPhase.CREATED
    val snapshot: CodingTaskState? get() = state
    val currentProfile: ProjectProfile? get() = profile
    val currentPlan: ValidationPlan? get() = plan

    /** The structured result of the most recent validation, or null. */
    fun currentReport(): DiagnosticReport? = lastReport

    /** True once any source mutation has been observed during this task. */
    val hasObservedMutation: Boolean get() = (state?.mutationEpoch ?: 0) > 0

    /** Current mutation epoch counter for task-level revision binding. */
    val mutationEpoch: Int get() = state?.mutationEpoch ?: 0

    fun subscribe(listener: (CodingEvent) -> Unit) = bus.subscribe(listener)

    /**
     * Starts (or restores) a task for one run.
     *
     * A restored task is never resumed mid-validation: an interrupted step has
     * already been turned into BLOCKED by [CodingTaskState.recoveredAfterInterruption],
     * which is what stops a build killed halfway through from reading back as the
     * build that passed.
     */
    fun start(taskId: String, projectId: String, generation: Long, restore: Boolean = true): CodingTaskState {
        cancelledRun = false
        persistenceWarning = null
        // NOT active yet. A run only becomes a coding task when a tool says so —
        // see [ACTIVATING_TOOLS] — because every ordinary conversation also has a
        // session, and gating those would take the file tools away from chats
        // that never touch a compiler.
        active = false
        val now = host.now()
        // B1: "there is no task" and "this task's state is unusable" are
        // different outcomes, and only the first may start fresh.
        //
        // store.load() throws for a corrupt file, an unsupported schema, or an
        // orphaned .new/.bak artifact. Catching that here would turn a
        // fail-closed store back into a fail-open one: an empty task would be
        // recreated, discarding whatever verification it had already earned, and
        // the run would continue as though nothing had been checked. The throw
        // propagates so the caller can stop the run instead.
        val existing = if (restore && store != null) {
            store.load(taskId, now)
        } else {
            null
        }
        val next = existing?.takeIf { it.projectId == projectId && it.projectGeneration == generation }
            ?: CodingTaskState.created(taskId, projectId, generation, now)
        state = next
        retry.reset()
        lastReport = null
        previousErrors = emptyList()
        lastValidationCancelled = false
        plan = next.validationPlan
        profile = next.profile
        active = next.mutationEpoch > 0 || next.applyReceipt != null || next.canonicalOperations.isNotEmpty()
        // Ordinary conversation startup must not bind/probe/scan the execution runtime.
        // Actual tool/verification boundaries observe the mirror when it is needed.
        lastKnownSource = null
        persist()
        bus.publish(CodingEvent.TaskStarted(now, taskId, projectId))
        return next
    }

    /** Records what the detector found, so the plan is durable. */
    fun attachProfile(detected: ProjectProfile, detectedPlan: ValidationPlan) {
        if (plan?.fingerprint() != detectedPlan.fingerprint()) {
            clearVerification("validation plan changed")
        }
        profile = detected
        plan = detectedPlan
        val current = state ?: return
        state = current.copy(
            profile = detected,
            validationPlan = detectedPlan,
            phase = if (current.phase == CodingPhase.CREATED) CodingPhase.INSPECTING else current.phase,
            updatedAt = host.now()
        )
        persist()
        bus.publish(CodingEvent.ProjectDetected(host.now(), detected))
    }

    fun detectProject(projectPath: String? = null): ProjectProfile? = host.detectProject(projectPath)
    fun documentationOnly(): Boolean = host.documentationOnly()
    fun currentSourceFingerprint(): String? = host.mirrorDigest()
    fun now(): Long = host.now()

    fun noteDiscarded() {
        val current = state ?: return
        lastKnownSource = host.mirrorDigest()
        state = current.copy(verification = null, finalDiffFingerprint = null,
            discardedEpoch = current.mutationEpoch, stopReason = null, phase = CodingPhase.WORKING, updatedAt = host.now())
        persist()
    }

    fun noteCanonicalOperation(tool: String) {
        val current = state ?: return
        active = true
        state = current.copy(canonicalOperations = current.canonicalOperations + tool,
            verification = null, runOutcome = null, updatedAt = host.now())
        persist()
    }
    fun pendingExecutionId(): String? = host.pendingExecutionId()

    fun cancel() {
        cancelledRun = true
        lastValidationCancelled = true
        val current = state
        if (current != null && !current.phase.terminal) {
            state = current.copy(phase = CodingPhase.CANCELLED, verification = null, updatedAt = host.now())
            persist()
            bus.publish(CodingEvent.TaskCancelled(host.now()))
        }
    }

    /** Observe task activity. Authorization remains the capability broker's job. */
    fun beforeTool(tool: String): String? {
        if (cancelledRun) {
            return "P4_TASK_CANCELLED: this coding task was stopped; start a new one."
        }
        if (state == null) {
            return null
        }
        if (tool in ACTIVATING_TOOLS) {
            active = true
        }
        if ((tool in ACTIVATING_TOOLS || tool in UNBOUNDED_MUTATION_TOOLS) && lastKnownSource == null) {
            lastKnownSource = host.mirrorDigest()
        }
        bus.publish(CodingEvent.ToolStarted(host.now(), tool))
        return null
    }

    /**
     * True once this run is a coding task, as opposed to a conversation that
     * happens to have a project mounted.
     */
    @Volatile
    var active: Boolean = false
        private set

    /**
     * A tool finished.
     *
     * [declaredMutation] is the caller's answer to "does this tool write source",
     * not the tool's own risk label. For the tools whose effect cannot be bounded
     * — a shell command can run a formatter, a code generator or a script that
     * rewrites files — the fingerprint is re-read instead of taken on trust,
     * because treating shell_exec as read-only is exactly how "tested before the
     * last edit" comes to be reported as "the fix is tested".
     */
    fun afterTool(tool: String, ok: Boolean, declaredMutation: Boolean) {
        bus.publish(CodingEvent.ToolCompleted(host.now(), tool, ok))
        if (state == null) {
            return
        }
        // Canonical application has its own transaction receipt. It does not
        // edit mirror content, even though its UI risk is WRITE.
        if (tool == "shell_apply_changes") return
        val unbounded = tool in UNBOUNDED_MUTATION_TOOLS
        // The prepare result is a new observation baseline, even when this tool
        // was not classified as a source writer by the caller.
        if (tool == "execution_prepare") {
            if (ok && host.preparedBaselineRefreshSafe()) {
                host.mirrorDigest()?.let { lastKnownSource = it }
            } else if (ok) {
                active = true
                val digest = host.mirrorDigest()
                if (digest != lastKnownSource || mutationEpoch == 0) noteMutation("pending mirror recovered")
                lastKnownSource = digest
                host.pendingExecutionId()?.let { noteExecutionId(it) }
            }
            return
        }
        // shell_exec is intentionally not an activation tool up front: a
        // read-only `git status` in an ordinary conversation must remain an
        // ordinary conversation. Observe actual content changes before the
        // inactive early-return so verification always describes current source.
        if (!active && !unbounded) {
            return
        }
        if (!declaredMutation && !unbounded) {
            return
        }
        val digest = host.mirrorDigest()
        // A refused/failed editor is not evidence of a mutation. Still observe
        // a changed digest if the tool wrote partially before failing.
        if (!ok && !unbounded && (digest == null || digest == lastKnownSource)) return
        if (digest != null && digest == lastKnownSource) return
        if (unbounded) {
            // The tool could have written anything, so believe the mirror rather
            // than the tool name: no change in content means no change to verify.
            val changed = digest != lastKnownSource
            lastKnownSource = digest
            if (!changed) {
                return
            }
            active = true
        } else {
            lastKnownSource = digest
        }
        noteMutation(tool)
    }

    /**
     * Captures the mirror baseline after P3 has prepared it but before an
     * unbounded shell command starts. The first shell call may create/sync the
     * persistent mirror, so comparing its post-command digest with the null
     * value observed at task start would otherwise misclassify a read-only
     * command as a source mutation. A real file change still moves the digest
     * after this baseline and is detected by [afterTool].
     */
    fun captureSourceBaseline() {
        if (state == null) {
            return
        }
        host.mirrorDigest()?.let { lastKnownSource = it }
    }

    /**
     * Re-reads the mirror and records a mutation if the content moved.
     *
     * Used before finishing and around validation, so a change made by something
     * the harness never saw as a tool call still invalidates a stale stamp.
     */
    fun refreshSourceFingerprint(): Boolean {
        if (state == null) {
            return false
        }
        val digest = host.mirrorDigest() ?: return false
        if (digest == lastKnownSource) {
            return false
        }
        // An out-of-band mirror change is itself evidence that this run has
        // crossed into coding work. Promote it before noteMutation() so the
        // durable task state is persisted and the completion/apply gates do not
        // treat the change as an ordinary-chat observation.
        active = true
        lastKnownSource = digest
        noteMutation("observed")
        return true
    }

    /**
     * P5/LSP may discover an integrity failure owned by another subsystem.
     * P4 does not rescan or repair the mirror here; it only invalidates the
     * verification stamp so an untrusted source can never be applied.
     */
    fun invalidateExternalSourceIntegrity(reason: String) {
        val current = state ?: return
        val now = host.now()
        if (current.verification == null && current.phase != CodingPhase.READY_TO_APPLY) return
        state = current.copy(
            verification = null,
            phase = CodingPhase.WORKING,
            runOutcome = null,
            updatedAt = now
        )
        persist()
        bus.publish(CodingEvent.VerificationInvalidated(now, reason))
    }

    private fun noteMutation(tool: String) {
        val current = state ?: return
        val epoch = current.mutationEpoch + 1
        val invalidated = current.verification != null
        val now = host.now()
        state = current.copy(
            mutationEpoch = epoch,
            verification = null,
            finalDiffFingerprint = null,
            phase = CodingPhase.WORKING,
            runOutcome = null,
            updatedAt = now
        )
        persist()
        bus.publish(CodingEvent.MutationObserved(now, tool, epoch))
        if (invalidated) {
            bus.publish(
                CodingEvent.VerificationInvalidated(now, "the project changed after it was verified")
            )
        }
    }

    /** One validation command, executed and recorded. */
    fun validate(
        command: ValidationCommand,
        token: CancellationToken,
        isFinal: Boolean,
        beforeExecute: (() -> Unit)? = null
    ): ValidationSummary {
        val current = state ?: throw IllegalStateException("CODING_TASK_NOT_STARTED")
        val at = host.now()
        state = current.copy(
            phase = if (isFinal) CodingPhase.VERIFYING else CodingPhase.VALIDATING,
            verification = null,
            finalDiffFingerprint = null,
            updatedAt = at
        )
        persist()
        bus.publish(CodingEvent.ValidationStarted(at, command.id))

        val result = host.runCommand(command, token, beforeExecute)
        host.pendingExecutionId()?.let { noteExecutionId(it) }
        lastValidationCancelled = result.cancelled
        val report = DiagnosticPipeline.parse(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            launchError = result.error,
            timedOut = result.timedOut
        )
        val resolved = DiagnosticPipeline.resolvedSince(previousErrors, report)
        val fresh = report.errors.filter { candidate ->
            previousErrors.none { it.key() == candidate.key() }
        }
        lastResolved = resolved
        lastFresh = fresh
        previousErrors = report.errors
        lastReport = report
        val blocked = DiagnosticPipeline.environmentBlocked(report)
        if (blocked) recordEnvironmentBlock(report.errors.firstOrNull()?.message ?: "validation environment unavailable")
        else {
            state = state?.copy(stopReason = null)
        }
        val ok = result.exitCode == 0 && !result.timedOut && !result.cancelled &&
            result.error == null && report.errors.isEmpty()
        val summary = ValidationSummary(
            check = command.id,
            ok = ok,
            exitCode = result.exitCode,
            errors = report.errors.size,
            warnings = report.warnings.size,
            blocked = blocked,
            sourceFingerprint = host.mirrorDigest() ?: NO_MIRROR,
            at = host.now()
        )

        val recorded = (state ?: current).copy(
            lastValidation = summary,
            validationHistory = (state ?: current).validationHistory + summary,
            validationPlan = plan,
            updatedAt = host.now()
        )
        state = if (result.cancelled) {
            recorded.copy(phase = CodingPhase.CANCELLED)
        } else {
            recorded.copy(phase = if (isFinal) CodingPhase.VERIFYING else CodingPhase.WORKING)
        }
        persist()
        bus.publish(CodingEvent.ValidationCompleted(host.now(), summary))
        bus.publish(CodingEvent.DiagnosticsUpdated(host.now(), report.errors.size, fresh.size))
        return summary
    }

    /** Errors that were present before the last run and are gone now. */
    @Volatile
    private var lastResolved: List<Diagnostic> = emptyList()

    /** Errors the last run produced that the previous run did not have. */
    @Volatile
    private var lastFresh: List<Diagnostic> = emptyList()

    /**
     * True when the most recent validation was stopped rather than finished.
     *
     * Carried separately from the report because a cancelled command usually
     * produces no output at all — which parses as "no errors", and "no errors"
     * must never be read as "the checks passed".
     */
    @Volatile
    private var lastValidationCancelled = false

    fun currentFreshErrors(): List<Diagnostic> = lastFresh

    fun currentResolvedErrors(): List<Diagnostic> = lastResolved

    /** The retry decision for the validation that was just run. */
    fun decideAfterFailure(): RetryDecision {
        if (cancelledRun || lastValidationCancelled) {
            return RetryDecision.Cancelled
        }
        val report = lastReport ?: return RetryDecision.VerificationReady
        val epoch = state?.mutationEpoch ?: 0
        val decision = retry.onValidation(report, epoch, cancelledRun)
        val current = state
        val stop = when (decision) {
            is RetryDecision.NoProgress -> "NO_PROGRESS: " + decision.reason
            is RetryDecision.BudgetExhausted -> "REPAIR_BUDGET_EXHAUSTED: " + decision.cycles
            is RetryDecision.EnvironmentBlocked -> decision.reason
            else -> null
        }
        if (stop != null) {
            state = state?.copy(stopReason = stop, updatedAt = host.now())
            persist()
        }
        if (current != null && decision is RetryDecision.ContinueRepair) {
            state = current.copy(
                repairCycle = decision.cycle,
                phase = CodingPhase.REPAIRING,
                updatedAt = host.now()
            )
            persist()
            bus.publish(CodingEvent.RepairCycleStarted(host.now(), decision.cycle, decision.remaining))
        }
        return decision
    }

    /**
     * Runs the plan's fast checks and returns the first failure, or null.
     *
     * Fast checks are the compile-shaped ones: cheap enough to run after an edit,
     * strict enough that a type error never survives to the final pass.
     */
    fun runFastChecks(
        token: CancellationToken,
        beforeExecute: (() -> Unit)? = null
    ): ValidationSummary? {
        val checks = plan?.fastChecks ?: return null
        for (command in checks) {
            val summary = validate(command, token, isFinal = false, beforeExecute = beforeExecute)
            if (!summary.ok) {
                return summary
            }
        }
        return null
    }

    /**
     * Runs every final check and, only if all of them pass against one unchanged
     * revision of the source, issues a verification stamp.
     *
     * The fingerprint is taken before the run and again after it; if the build
     * itself rewrote tracked source (formatters and code generators do), the
     * stamp would describe code that no longer exists, which is precisely the
     * claim this class exists to prevent.
     */
    fun runFinalVerification(
        token: CancellationToken,
        beforeExecute: (() -> Unit)? = null
    ): VerificationStamp {
        val currentPlan = plan ?: throw IllegalStateException("P4_PLAN_MISSING")
        if (currentPlan.finalChecks.isEmpty()) {
            recordEnvironmentBlock("P4_NO_FINAL_CHECKS: " + currentPlan.reason)
            throw IllegalStateException(
                "P4_NO_FINAL_CHECKS: the harness found no runnable final check for this project (" +
                    currentPlan.reason + ")"
            )
        }
        val before = host.mirrorDigest()
            ?: throw IllegalStateException("P4_MIRROR_DIGEST_UNAVAILABLE: cannot verify source without a mirror fingerprint")
        val environmentBefore = host.validationEnvironmentIdentity()
            ?: throw IllegalStateException("P4_VALIDATION_ENVIRONMENT_UNAVAILABLE")
        val epochBefore = state?.mutationEpoch ?: 0
        val at = host.now()
        bus.publish(CodingEvent.VerificationStarted(at, currentPlan.finalChecks.map { it.id }))

        val passed = mutableListOf<String>()
        for (command in currentPlan.finalChecks) {
            val summary = validate(command, token, isFinal = true, beforeExecute = beforeExecute)
            if (!summary.ok) {
                bus.publish(CodingEvent.VerificationFailed(host.now(), "check failed: " + command.id))
                throw VerificationFailedException(command.id, summary)
            }
            passed.add(command.id)
        }

        val epochAfter = state?.mutationEpoch ?: 0
        val after = host.mirrorDigest()
            ?: throw IllegalStateException("P4_MIRROR_DIGEST_UNAVAILABLE: mirror fingerprint disappeared during verification")
        if (host.validationEnvironmentIdentity() != environmentBefore) {
            throw IllegalStateException("P4_VALIDATION_ENVIRONMENT_CHANGED: run checks again with the current toolchain")
        }
        if (epochAfter != epochBefore || before != after) {
            throw IllegalStateException(
                "VALIDATION_MUTATED_SOURCE: the project changed while it was being validated; " +
                    "run the checks again"
            )
        }

        val stamp = VerificationStamp(
            sourceFingerprint = after,
            mutationEpoch = epochAfter,
            planFingerprint = currentPlan.fingerprint(),
            passedChecks = passed,
            verifiedAt = host.now(),
            environmentIdentity = environmentBefore
        )
        val current = state ?: throw IllegalStateException("CODING_TASK_NOT_STARTED")
        state = current.copy(
            phase = CodingPhase.READY_TO_APPLY,
            verification = stamp,
            repairCycle = 0,
            stopReason = null,
            updatedAt = host.now()
        )
        persist()
        bus.publish(CodingEvent.VerificationPassed(host.now(), stamp))
        return stamp
    }

    /**
     * True when the recorded verification still describes the code in the mirror.
     *
     * This is the question the commit gate turns on, so it is asked in exactly one
     * place. A stamp that matches its epoch but not its content fingerprint was
     * invalidated by an edit the tools did not report; a stamp from another plan
     * describes checks that were chosen for a different project shape.
     */
    fun verificationValid(expectedPlanFingerprint: String? = null): Boolean {
        if (cancelledRun) return false
        val current = state ?: return false
        val stamp = current.verification ?: return false
        if (stamp.environmentIdentity == null || stamp.environmentIdentity != host.validationEnvironmentIdentity()) return false
        if (stamp.mutationEpoch != current.mutationEpoch) {
            return false
        }
        if (expectedPlanFingerprint != null && stamp.planFingerprint != expectedPlanFingerprint) {
            return false
        }
        val live = host.mirrorDigest() ?: return false
        return stamp.sourceFingerprint == live
    }

    /** Completion reports recorded tool facts; it never starts mirror I/O. */
    private fun recordedVerificationValid(): Boolean {
        if (cancelledRun) return false
        val current = state ?: return false
        val stamp = current.verification ?: return false
        return stamp.environmentIdentity != null && stamp.mutationEpoch == current.mutationEpoch &&
            (plan == null || stamp.planFingerprint == plan?.fingerprint()) &&
            stamp.sourceFingerprint == lastKnownSource
    }

    fun clearVerification(reason: String) {
        val current = state ?: return
        if (current.verification == null) {
            return
        }
        state = current.copy(verification = null, updatedAt = host.now())
        persist()
        bus.publish(CodingEvent.VerificationInvalidated(host.now(), reason))
    }

    /** Stopping a reply is independent of validation and canonical application. */
    fun requestFinish(): FinishDecision {
        return FinishDecision.Allow
    }

    /** Finishing a run never requires passing a compiler. */
    fun finishRun(note: String = ""): FinalTaskReport {
        val current = state
        val applied = current?.applyReceipt?.let {
            it.mutationEpoch == current.mutationEpoch
        } == true
        val verified = recordedVerificationValid()
        val status = when {
            cancelledRun || current?.phase == CodingPhase.CANCELLED -> TaskStatus.CANCELLED
            applied && verified -> TaskStatus.SUCCESS
            applied -> TaskStatus.APPLIED_UNVERIFIED
            current?.stopReason != null -> TaskStatus.BLOCKED
            verified -> TaskStatus.VERIFIED_NOT_APPLIED
            hasObservedMutation && current?.discardedEpoch != current?.mutationEpoch -> TaskStatus.UNVERIFIED_NOT_APPLIED
            !current?.canonicalOperations.isNullOrEmpty() -> TaskStatus.APPLIED_UNVERIFIED
            else -> TaskStatus.NO_CHANGES
        }
        return finishWith(status, listOfNotNull(note.takeIf { it.isNotEmpty() }, current?.stopReason).joinToString("\n"))
    }

    fun configureValidation(projectPath: String?, checks: List<ValidationCommand>) {
        val current = state ?: return
        if (current.selectedProjectPath == projectPath && current.registeredChecks == checks) return
        clearVerification("explicit validation configuration changed")
        state = (state ?: current).copy(selectedProjectPath = projectPath, registeredChecks = checks, updatedAt = host.now())
        persist()
    }

    /** Records an unavailable validation entry point without inventing a passed check. */
    fun recordEnvironmentBlock(reason: String) {
        state = state?.copy(stopReason = reason.take(2000), updatedAt = host.now())
        persist()
    }

    /** Records the exact diff the apply step will present for approval. */
    fun noteDiffFingerprint(fingerprint: String, changedFiles: Int) {
        val current = state ?: return
        state = current.copy(finalDiffFingerprint = fingerprint, updatedAt = host.now())
        persist()
        bus.publish(CodingEvent.DiffReady(host.now(), fingerprint, changedFiles))
    }

    /** Clears the recorded diff fingerprint once it has been applied or dropped. */
    fun clearDiffFingerprint() {
        val current = state ?: return
        if (current.finalDiffFingerprint == null) {
            return
        }
        state = current.copy(finalDiffFingerprint = null, updatedAt = host.now())
        persist()
    }

    fun noteExecutionId(executionId: String) {
        val current = state ?: return
        state = current.copy(lastExecutionId = executionId, updatedAt = host.now())
        persist()
    }

    fun markApplying() {
        val current = state ?: return
        state = current.copy(phase = CodingPhase.APPLYING, updatedAt = host.now())
        persist()
        bus.publish(CodingEvent.ApplyStarted(host.now(), current.lastExecutionId ?: ""))
    }

    fun markApplied(ok: Boolean, note: String = "", receipt: CodingApplyReceipt? = null) {
        val current = state ?: return
        if (ok) requireNotNull(receipt) { "CODING_APPLY_RECEIPT_REQUIRED" }
        state = current.copy(phase = if (ok) CodingPhase.COMPLETED else CodingPhase.FAILED,
            applyReceipt = if (ok) receipt else current.applyReceipt, note = note, updatedAt = host.now())
        persist()
        bus.publish(CodingEvent.ApplyCompleted(host.now(), receipt?.executionId ?: current.lastExecutionId ?: "", ok))
    }

    fun finishWith(status: TaskStatus, note: String = ""): FinalTaskReport {
        val current = state
        val receipt = current?.applyReceipt
        val applied = receipt != null && receipt.mutationEpoch == current?.mutationEpoch
        val verified = recordedVerificationValid()
        val actualStatus = if (status == TaskStatus.SUCCESS || status == TaskStatus.VERIFIED_NOT_APPLIED || status == TaskStatus.APPLIED_UNVERIFIED) {
            when {
                applied && verified -> TaskStatus.SUCCESS
                applied -> TaskStatus.APPLIED_UNVERIFIED
                verified -> TaskStatus.VERIFIED_NOT_APPLIED
                hasObservedMutation && current?.discardedEpoch != current?.mutationEpoch -> TaskStatus.UNVERIFIED_NOT_APPLIED
                !current?.canonicalOperations.isNullOrEmpty() -> TaskStatus.APPLIED_UNVERIFIED
                else -> TaskStatus.NO_CHANGES
            }
        } else status
        val report = FinalTaskReport(
            status = actualStatus,
            changedFiles = if (applied) receipt!!.createdFiles + receipt.modifiedFiles + receipt.deletedFiles else 0,
            createdFiles = if (applied) receipt!!.createdFiles else 0,
            modifiedFiles = if (applied) receipt!!.modifiedFiles else 0,
            deletedFiles = if (applied) receipt!!.deletedFiles else 0,
            validations = current?.validationHistory?.takeIf { it.isNotEmpty() } ?: current?.lastValidation?.let { listOf(it) } ?: emptyList(),
            unresolvedDiagnostics = lastReport?.errors ?: emptyList(),
            diffFingerprint = if (applied) receipt!!.diffFingerprint else current?.finalDiffFingerprint,
            applied = applied || (!hasObservedMutation && !current?.canonicalOperations.isNullOrEmpty()),
            note = listOfNotNull(note.takeIf { it.isNotEmpty() }, persistenceWarning).joinToString("\n"), verified = verified,
            countsKnown = applied || ((!hasObservedMutation || current?.discardedEpoch == current?.mutationEpoch) && current?.canonicalOperations.isNullOrEmpty()),
            canonicalOperations = current?.canonicalOperations ?: emptyList())
        if (current != null) {
            state = current.copy(phase = when {
                applied -> CodingPhase.COMPLETED
                actualStatus == TaskStatus.CANCELLED -> CodingPhase.CANCELLED
                actualStatus == TaskStatus.BLOCKED -> CodingPhase.BLOCKED
                actualStatus == TaskStatus.FAILED -> CodingPhase.FAILED
                else -> CodingPhase.FINISHED
            }, runOutcome = actualStatus.name, note = note, updatedAt = host.now())
            persist()
        }
        val at = host.now()
        bus.publish(when (actualStatus) {
            TaskStatus.BLOCKED -> CodingEvent.TaskBlocked(at, note)
            TaskStatus.FAILED -> CodingEvent.TaskFailed(at, note)
            TaskStatus.CANCELLED -> CodingEvent.TaskCancelled(at)
            else -> CodingEvent.TaskCompleted(at, report)
        })
        return report
    }

    private fun persist() {
        if (!active) {
            // A conversation that never became a coding task writes nothing: the
            // store is for tasks, and a file per chat turn would be litter.
            return
        }
        val current = state ?: return
        try {
            store?.save(current)
            persistenceWarning = null
        } catch (failure: Exception) {
            persistenceWarning = "Task bookkeeping could not be persisted: " + failure.javaClass.simpleName
            // A control-state write failure must not abort the user's work; the
            // in-memory state is still authoritative for this run.
        }
    }

    companion object {
        /** Used when no execution mirror exists yet, so a stamp stays comparable. */
        const val NO_MIRROR = "no-mirror"

        /**
         * Tools whose effects the harness cannot bound.
         *
         * A shell command can run a formatter or a code generator, so after one
         * of these the source fingerprint is re-read rather than assumed.
         */
        val UNBOUNDED_MUTATION_TOOLS: Set<String> = setOf("shell_exec")

        /**
         * Tools whose use marks the run as a coding task.
         *
         * Preparing the mirror and running the build or tests are the three
         * unambiguous statements of intent: after any of them the user is
         * working on code in the mirror, and from that point the direct canonical
         * editors are the wrong tools rather than merely a stylistic choice.
         * Writing to the mirror counts for the same reason — the model has
         * already chosen the mirror, so an edit that bypasses it would be the one
         * that leaves the two out of step.
         */
        val ACTIVATING_TOOLS: Set<String> = setOf(
            "execution_prepare", "execution_recover", "build", "test",
            "execution_write_file", "execution_edit_file", "execution_delete_file",
            "execution_make_dir", "execution_import_archive", "shell_apply_changes"
        )

        /**
         * Execution-mirror writers, which the engine's own mutation bit does not
         * cover.
         *
         * That bit describes the CANONICAL side effect, and an execution write
         * deliberately has none: it changes the mirror only. For P4 that is
         * exactly the change that matters, because it is what the next build
         * compiles and what a verification stamp must be invalidated by.
         */
        val DECLARED_MIRROR_MUTATIONS: Set<String> = setOf(
            "execution_write_file", "execution_edit_file", "execution_delete_file",
            "execution_make_dir", "execution_import_archive", "shell_discard_changes"
        )
    }
}

/** Thrown when a final check fails; carries the summary for the caller. */
class VerificationFailedException(
    val check: String,
    val summary: ValidationSummary
) : IllegalStateException("P4_VERIFICATION_FAILED: " + check)

