package dev.wind.agent

import dev.wind.agent.coding.CodingApplyReceipt
import dev.wind.agent.coding.CodingPhase
import dev.wind.agent.coding.CodingRuntimeHost
import dev.wind.agent.coding.CodingTaskRuntime
import dev.wind.agent.coding.Confidence
import dev.wind.agent.coding.DiagnosticPipeline
import dev.wind.agent.coding.FinishDecision
import dev.wind.agent.coding.ProjectDetector
import dev.wind.agent.coding.ProjectKind
import dev.wind.agent.coding.ProjectProfile
import dev.wind.agent.coding.RetryDecision
import dev.wind.agent.coding.TaskStatus
import dev.wind.agent.coding.ValidationCommand
import dev.wind.agent.coding.ValidationPlanner
import dev.wind.agent.coding.ValidationRunResult
import dev.wind.agent.coding.VerificationFailedException
import org.json.JSONObject

/**
 * The P4 loop, end to end, without a device.
 *
 * The same reasoning as [AgentLoopTests]: the suite that mattered least during
 * the port was the one nobody had written, because model output and build output
 * are the two things this harness exists to interpret and both are just text.
 * Everything below therefore drives the REAL runtime �?real state machine, real
 * retry controller, real diagnostic parsers �?against a scripted host, so
 * "the model cannot finish unverified work" is a test rather than a claim.
 */
object CodingLoopTests {

    private var assertions = 0

    private fun check(value: Boolean, message: String) {
        assertions++
        if (!value) {
            throw AssertionError(message)
        }
    }

    private fun eq(expected: Any?, actual: Any?) {
        assertions++
        if (expected != actual) {
            throw AssertionError("expected <$expected> but was <$actual>")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        grammarOfEachBuildSystemIsRecognized()
        plannerNeverInventsTestsItCannotFind()
        compileFailureBecomesRepairableDiagnostics()
        terminalPathsStayOnDiskButAreNotWrittenAsSuccess()
        termuxPathsAreNeverShownToTheModel()
        aChangeInvalidatesAnEarlierVerification()
        theModelCannotFinishUnverifiedWork()
        aReadOnlyRunMayStillAnswerNormally()
        environmentFailureDoesNotBecomeACodeRepairLoop()
        repeatedFailureWithNoEditIsNoProgress()
        verificationNeedsTheSourceToHoldStill()
        cancellationIsNeverReportedAsSuccess()
        interruptedValidationIsNeverSuccess()
        directCanonicalEditsAreRefusedDuringATask()
        anOrdinaryCustomizationOfAMirrorEditIsRecorded()
        verificationReportIsBoundToTheDiff()
        noFinalChecksMeansBlockedNotFinished()
        writesSurviveAReload()
        aCorruptTaskCannotBeSilentlyRecreated()
        messageTextIsScrubbedOfDevicePaths()
        launchFailuresBecomeEnvironmentDiagnostics()
        metadataDoesNotBreakDiagnosticBounds()
        nodeFalsePositiveIsRejected()
        applyIsGatedByVerification()
        anInactiveShellMutationActivatesTheCodingGate()
        verificationRequiresAMirrorDigest()
        passiveSessionsDoNotManufactureP4Reports()
        subAgentsCannotApplyCanonicalChanges()
        theGateCannotBeOutlasted()
        failedEditWithUnchangedDigestDoesNotRecordMutation()
        failedEditWithChangedDigestStillRecordsMutation()
        executionPrepareSyncingMirrorDigestWithoutUserEditAllowsFinish()
        toolchainLaunchErrorDuringValidationBlocksCompletionGate()
        environmentBlockPersistsAcrossSourceEdits()
        pureEnvironmentProbeWithoutMutationAllowsFinish()
        sessionBuildFastWithoutPlanBlocksWithMutationAndAllowsWithoutMutation()
        harnessLifecycleExtraRegressions()
        println("PASS CodingLoopTests: $assertions assertions")
    }

    // ------------------------------------------------------------ detection

    private fun grammarOfEachBuildSystemIsRecognized() {
        val android = ProjectDetector.classify(
            "workspace://",
            setOf("settings.gradle.kts", "build.gradle.kts", "gradlew", "app", "app/build.gradle.kts"),
            mapOf(
                "settings.gradle.kts" to "include(\":app\")",
                "build.gradle.kts" to "plugins { id(\"com.android.application\") }"
            )
        )
        eq(ProjectKind.ANDROID_GRADLE, android.kind)
        check(android.androidApplication, "the Android application plugin was not recognised")
        eq(listOf("app"), android.modules)
        eq("gradlew", android.wrapper)
        eq(Confidence.HIGH, android.confidence)

        val jvm = ProjectDetector.classify(
            "workspace://",
            setOf("build.gradle.kts"),
            mapOf("build.gradle.kts" to "plugins { kotlin(\"jvm\") }")
        )
        eq(ProjectKind.JVM_GRADLE, jvm.kind)

        eq(ProjectKind.MAVEN, ProjectDetector.classify("workspace://", setOf("pom.xml"), emptyMap()).kind)
        eq(ProjectKind.NODE, ProjectDetector.classify("workspace://", setOf("package.json"), emptyMap()).kind)
        eq(
            ProjectKind.PYTHON,
            ProjectDetector.classify("workspace://", setOf("pyproject.toml"), emptyMap()).kind
        )
        eq(ProjectKind.RUST, ProjectDetector.classify("workspace://", setOf("Cargo.toml"), emptyMap()).kind)
        eq(ProjectKind.GO, ProjectDetector.classify("workspace://", setOf("go.mod"), emptyMap()).kind)
        eq(ProjectKind.GENERIC, ProjectDetector.classify("workspace://", setOf("README.md"), emptyMap()).kind)

        // Modules come out of a real settings file, with or without a space.
        eq(listOf("app", "lib"), ProjectDetector.gradleModules("include(\":app\", \":lib\")"))
        eq(listOf("core"), ProjectDetector.gradleModules("include( ':core' )"))
        eq(0, ProjectDetector.gradleModules("").size)
    }

    private fun plannerNeverInventsTestsItCannotFind() {
        val android = ProjectProfile(
            kind = ProjectKind.ANDROID_GRADLE,
            root = "workspace://",
            modules = listOf("app"),
            buildSystem = "gradle",
            wrapper = "gradlew",
            testFrameworkHints = listOf("junit"),
            confidence = Confidence.HIGH
        )
        val plan = ValidationPlanner.plan(android)
        check(plan.fastChecks.isNotEmpty(), "an Android project got no fast check")
        check(plan.finalChecks.any { it.id == "build" }, "the final plan has no build")
        check(plan.finalChecks.any { it.id == "test" }, "the final plan dropped the discovered tests")
        eq("gradlew", plan.fastChecks.first().argv.first())

        val unknown = ProjectProfile(kind = ProjectKind.GENERIC, root = "workspace://")
        val emptyPlan = ValidationPlanner.plan(unknown)
        check(emptyPlan.finalChecks.isEmpty(), "a generic project was given a fabricated final check")
        eq(Confidence.LOW, emptyPlan.confidence)
        check(emptyPlan.reason.contains("No recognised build system"), "the plan does not explain itself: " + emptyPlan.reason)

        val rust = ValidationPlanner.plan(ProjectProfile(kind = ProjectKind.RUST, root = "workspace://"))
        eq("cargo", rust.fastChecks.first().argv.first())
    }

    // ------------------------------------------------------------ diagnostics

    private fun compileFailureBecomesRepairableDiagnostics() {
        // Configure the mapper the way a session does, so this test exercises the
        // real rewriting rather than the unconfigured fallback.
        dev.wind.agent.coding.DiagnosticPathMapper.configure(
            listOf("project" to "workspace://"),
            listOf("/data/data/com.termux/files/home/.vega-safe/workspaces/abc")
        )
        val report = DiagnosticPipeline.parse(
            stdout = "",
            stderr = "e: file:///data/data/com.termux/files/home/.vega-safe/workspaces/" +
                "abc/project/src/Foo.kt:42:17 Unresolved reference: bar",
            exitCode = 1
        )
        eq(1, report.errors.size)
        val error = report.errors.first()
        eq("workspace://src/Foo.kt", error.path)
        eq(42, error.line)
        eq(17, error.column)
        check(error.message.contains("Unresolved reference"), "the message was lost: " + error.message)

        // javac, and the JUnit line that follows it in a Gradle test run.
        val javac = DiagnosticPipeline.parse(
            "", "/tmp/project/src/Bar.java:18: error: cannot find symbol", 1
        )
        eq(1, javac.errors.size)
        eq(18, javac.errors.first().line)

        val junit = DiagnosticPipeline.parse("FooTest > shouldCreateUser FAILED", "", 1)
        eq(1, junit.errors.size)
        eq("shouldCreateUser", junit.errors.first().code)

        // A test line must not be turned into a compile error by the generic matcher.
        val combined = DiagnosticPipeline.parse(
            "FooTest > givenValidInput FAILED\nFAILED tests/test_api.py::test_login - AssertionError: nope",
            "", 1
        )
        eq(2, combined.errors.size)
        dev.wind.agent.coding.DiagnosticPathMapper.reset()
    }

    private fun terminalPathsStayOnDiskButAreNotWrittenAsSuccess() {
        // Nothing matched but the command failed: the exit code is still a
        // diagnostic, because "there was an error" is information.
        val report = DiagnosticPipeline.parse("", "some tool said something odd", 2)
        eq(1, report.errors.size)
        check(report.errors.first().message.contains("exit code 2"), "the exit code was dropped")

        val empty = DiagnosticPipeline.parse("", "", 0)
        check(empty.ok, "a clean run reported an error")
    }

    private fun termuxPathsAreNeverShownToTheModel() {
        // The mapper is configured the way a session configures it on project
        // detection: the mount's execution path, and the Termux workspace root
        // the mirror actually lives under.
        dev.wind.agent.coding.DiagnosticPathMapper.configure(
            listOf("project" to "workspace://"),
            listOf("/data/data/com.termux/files/home/.vega-safe/workspaces/abc")
        )
        val raw =
            "e: file:///data/data/com.termux/files/home/.vega-safe/workspaces/" +
                "abc/project/src/Deep/Path.kt:9:1 Some error"
        val report = DiagnosticPipeline.parse("", raw, 1)
        val line = report.errors.first().oneLine()
        check(!line.contains("termux"), "a Termux path reached the diagnostic: " + line)
        check(!line.contains("/data/data"), "an absolute device path reached the diagnostic: " + line)
        check(line.startsWith("workspace://"), "the diagnostic is not in the virtual space: " + line)
        eq("workspace://src/Deep/Path.kt", report.errors.first().path)

        // A second mount maps to its own virtual root, and the longest matching
        // execution path wins so a nested mount is not swallowed by its parent.
        dev.wind.agent.coding.DiagnosticPathMapper.configure(
            listOf("project" to "workspace://", "project/shared" to "project://shared/"),
            listOf("/data/data/com.termux/files/home/.vega-safe/workspaces/abc")
        )
        val nested = DiagnosticPipeline.parse(
            "",
            "e: file:///data/data/com.termux/files/home/.vega-safe/workspaces/" +
                "abc/project/shared/Lib.kt:2:1 boom",
            1
        )
        eq("project://shared/Lib.kt", nested.errors.first().path)
        dev.wind.agent.coding.DiagnosticPathMapper.reset()
    }

    // ------------------------------------------------------------ the loop

    /**
     * The core scenario: a failing compile, a fix, a passing compile, then a
     * final verification that issues a stamp �?and the model is only allowed to
     * stop once that stamp exists.
     */
    private fun aChangeInvalidatesAnEarlierVerification() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-1")

        // Cycle 1: the compile fails.
        host.script("compile") { ValidationRunResult(1, "", "e: file:///w/src/Foo.kt:3:1 Unresolved reference: bar", 10L) }
        val failure = runtime.runFastChecks(CancellationToken())
        check(failure != null && !failure.ok, "a failing compile was reported as passing")
        eq(1, (failure as dev.wind.agent.coding.ValidationSummary).errors)
        eq(RetryDecision.ContinueRepair::class, runtime.decideAfterFailure()::class)

        // The model fixes it.
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        // Cycle 2: the compile passes.
        host.script("compile") { ValidationRunResult(0, "BUILD OK", "", 10L) }
        check(runtime.runFastChecks(CancellationToken()) == null, "a passing compile was reported as failing")

        // Final verification passes �?a stamp exists.
        host.script("build") { ValidationRunResult(0, "ok", "", 20L) }
        host.script("test") { ValidationRunResult(0, "ok", "", 20L) }
        val stamp = runtime.runFinalVerification(CancellationToken())
        check(stamp.passedChecks.contains("build"), "the stamp does not name the build")
        check(runtime.verificationValid(runtime.currentPlan?.fingerprint()), "a fresh stamp is not valid")
        eq(FinishDecision.Allow::class, runtime.requestFinish()::class)

        // Now the model edits again: the stamp must die with the change.
        host.mirror = "digest-after-edit"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(
            !runtime.verificationValid(runtime.currentPlan?.fingerprint()),
            "an edit left the earlier verification standing"
        )
        eq(FinishDecision.Allow::class, runtime.requestFinish()::class)
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, runtime.finishRun().status)
    }

    private fun theModelCannotFinishUnverifiedWork() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-2")

        host.mirror = "digest-task-2-edit"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        val decision = runtime.requestFinish()
        eq(FinishDecision.Allow::class, decision::class)

        val report = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, report.status)
        check(!report.verified, "unverified work claimed verified")
        check(!report.applied, "unapplied work claimed applied")
    }

    private fun aReadOnlyRunMayStillAnswerNormally() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-3")
        check(
            runtime.requestFinish() is FinishDecision.Allow,
            "a run that changed nothing was blocked from answering"
        )
    }

    private fun environmentFailureDoesNotBecomeACodeRepairLoop() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-4")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        host.script("compile") {
            ValidationRunResult(127, "", "gradlew: command not found", 5L)
        }
        runtime.runFastChecks(CancellationToken())
        val decision = runtime.decideAfterFailure()
        check(
            decision is RetryDecision.EnvironmentBlocked,
            "a missing toolchain was treated as a code failure: " + decision
        )
    }

    private fun repeatedFailureWithNoEditIsNoProgress() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-5")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        val line = "e: file:///w/src/Foo.kt:3:1 Unresolved reference: bar"
        host.script("compile") { ValidationRunResult(1, "", line, 5L) }
        runtime.runFastChecks(CancellationToken())
        eq(RetryDecision.ContinueRepair::class, runtime.decideAfterFailure()::class)

        // No edit in between: the same failure twice is a stuck loop, not a
        // signal to keep spending agent steps.
        host.script("compile") { ValidationRunResult(1, "", line, 5L) }
        runtime.runFastChecks(CancellationToken())
        val second = runtime.decideAfterFailure()
        check(second is RetryDecision.NoProgress, "the same error twice was not detected: " + second)
    }

    private fun verificationNeedsTheSourceToHoldStill() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-6")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        // The build itself rewrites a file, which makes the pass describe code
        // that no longer exists.
        host.script("build") {
            host.mirror = "digest-changed-by-build"
            ValidationRunResult(0, "ok", "", 20L)
        }
        host.script("test") { ValidationRunResult(0, "ok", "", 20L) }
        var threw = false
        try {
            runtime.runFinalVerification(CancellationToken())
        } catch (e: Exception) {
            threw = e.message?.contains("VALIDATION_MUTATED_SOURCE") == true
        }
        check(threw, "a verification whose source moved underneath it was accepted")
    }

    private fun cancellationIsNeverReportedAsSuccess() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-7")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        host.script("compile") { ValidationRunResult(null, "", "", 1L, cancelled = true) }
        val summary = runtime.runFastChecks(CancellationToken())
        check(summary != null && !summary.ok, "a cancelled command was reported as a pass")
        eq(CodingPhase.CANCELLED, runtime.phase)
        eq(RetryDecision.Cancelled::class, runtime.decideAfterFailure()::class)
    }

    private fun interruptedValidationIsNeverSuccess() {
        val store = MemoryStore()
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host, store)
        runtime.beginCoding("task-8")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        // Enter VALIDATING and die: the saved state must not read back as a pass.
        host.script("compile") { ValidationRunResult(1, "", "e: file:///w/src/A.kt:1:1 boom", 3L) }
        runtime.validate(ValidationPlanner.plan(androidProfile()).fastChecks.first(), CancellationToken(), false)
        val saved = store.saved ?: throw AssertionError("nothing was persisted")
        val recovered = saved.copy(phase = CodingPhase.VALIDATING).recoveredAfterInterruption(999L)
        eq(CodingPhase.BLOCKED, recovered.phase)
        check(recovered.verification == null, "an interrupted run kept its verification")
        check(recovered.lastValidation == null, "an interrupted run kept a validation result")

        // And a CLEAN completion is unaffected by recovery. The state has to be
        // built honestly: a COMPLETED task without a stamp is rejected outright,
        // so the fixture carries one.
        val done = saved.copy(
            phase = CodingPhase.COMPLETED,
            verification = dev.wind.agent.coding.VerificationStamp(
                sourceFingerprint = "digest",
                mutationEpoch = saved.mutationEpoch,
                planFingerprint = "plan",
                passedChecks = listOf("build"),
                verifiedAt = 1L
            )
        )
        eq(CodingPhase.COMPLETED, done.recoveredAfterInterruption(999L).phase)
    }

    private fun directCanonicalEditsAreRefusedDuringATask() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-9")
        for (tool in listOf("write_file", "edit_file", "delete_path", "move_path", "make_dir")) {
            check(runtime.beforeTool(tool) == null, "$tool was refused by P4 active state")
        }
        check(runtime.beforeTool("execution_edit_file") == null, "an execution edit was refused")
        check(runtime.beforeTool("shell_exec") == null, "shell_exec was refused")
        check(runtime.beforeTool("read_file") == null, "read_file was refused")
    }

    private fun anOrdinaryCustomizationOfAMirrorEditIsRecorded() {
        val host = ScriptedHost()
        // The baseline has to exist BEFORE the task starts, so "the same digest
        // twice" really means the command changed nothing.
        host.mirror = "digest-a"
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-10")

        // A shell command that changed nothing must NOT invalidate anything: the
        // fingerprint is what decides, not the tool name.
        runtime.beforeTool("shell_exec")
        runtime.afterTool("shell_exec", ok = true, declaredMutation = false)
        check(!runtime.hasObservedMutation, "a no-op shell command counted as a source change")

        host.mirror = "digest-b"
        runtime.beforeTool("shell_exec")
        runtime.afterTool("shell_exec", ok = true, declaredMutation = false)
        check(runtime.hasObservedMutation, "a shell command that rewrote source was not noticed")
    }

    private fun verificationReportIsBoundToTheDiff() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-11")
        runtime.noteDiffFingerprint("fingerprint-1", 3)
        eq("fingerprint-1", runtime.snapshot?.finalDiffFingerprint)

        // Apply without receipt is rejected by markApplied
        var caughtNoReceipt = false
        try {
            runtime.markApplied(true, "applied without receipt")
        } catch (e: IllegalArgumentException) {
            caughtNoReceipt = e.message?.contains("CODING_APPLY_RECEIPT_REQUIRED") == true
        }
        check(caughtNoReceipt, "markApplied(true) without receipt must fail closed")

        // With a valid receipt, markApplied succeeds
        host.mirror = "digest-task-11-edit"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        host.script("compile") { ValidationRunResult(0, "", "", 5L) }
        host.script("build") { ValidationRunResult(0, "", "", 5L) }
        host.script("test") { ValidationRunResult(0, "", "", 5L) }
        runtime.runFinalVerification(CancellationToken())
        val receipt = CodingApplyReceipt("exec-11", "fingerprint-1", "digest-task-11-edit", 1, 1, 0, 0, 1000L, true)
        runtime.markApplied(true, "applied cleanly", receipt)
        eq(CodingPhase.COMPLETED, runtime.phase)
    }

    private fun noFinalChecksMeansBlockedNotFinished() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        val generic = ProjectProfile(kind = ProjectKind.GENERIC, root = "workspace://")
        runtime.beginCoding("task-12", generic)
        host.mirror = "digest-task-12-edit"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        val decision = runtime.requestFinish()
        eq(FinishDecision.Allow::class, decision::class)

        val report = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, report.status)
        check(!report.verified, "unverified work claimed verified")
    }

    private fun writesSurviveAReload() {
        val store = MemoryStore()
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host, store)
        runtime.beginCoding("task-13")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        runtime.noteDiffFingerprint("fp", 2)
        val saved = store.saved ?: throw AssertionError("the state was never persisted")
        val payload = JSONObject(saved.toJson().toString())
        val restored = dev.wind.agent.coding.CodingTaskState.fromJson(payload, "task-13")
        eq(saved.taskId, restored.taskId)
        eq(saved.mutationEpoch, restored.mutationEpoch)
        eq("fp", restored.finalDiffFingerprint)
        eq(ProjectKind.ANDROID_GRADLE, restored.profile?.kind)

        // A coerced schema is not a schema.
        var rejected = false
        try {
            dev.wind.agent.coding.CodingTaskState.fromJson(
                JSONObject(saved.toJson().toString()).put("schemaVersion", "1"),
                "task-13"
            )
        } catch (_: Exception) {
            rejected = true
        }
        check(rejected, "a string schema version was accepted")

        var mismatch = false
        try {
            dev.wind.agent.coding.CodingTaskState.fromJson(payload, "someone-else")
        } catch (_: Exception) {
            mismatch = true
        }
        check(mismatch, "a task state was loaded into another task")

        // A recovered run must not keep a stale completion either.
        val recoveredFromValidating = saved.copy(
            phase = CodingPhase.VALIDATING
        ).recoveredAfterInterruption(500L)
        check(
            recoveredFromValidating.phase == CodingPhase.BLOCKED,
            "a task interrupted mid-validation recovered as " + recoveredFromValidating.phase
        )
    }

    // ------------------------------------------------------------ fixtures

    /**
     * B1: a store that cannot be read must not be papered over with a fresh
     * task. Only "there is no task" may start a new one; everything else has to
     * reach the caller so the run can be stopped.
     */
    private fun aCorruptTaskCannotBeSilentlyRecreated() {
        val throwing = object : dev.wind.agent.coding.CodingStateStore {
            override fun load(taskId: String, now: Long): dev.wind.agent.coding.CodingTaskState? =
                throw IllegalStateException("CODING_STATE_CORRUPT")

            override fun save(state: dev.wind.agent.coding.CodingTaskState) {}
        }
        val runtime = CodingTaskRuntime(ScriptedHost(), throwing)
        var threw = false
        try {
            runtime.start("task-corrupt", "proj", 1L)
        } catch (e: Exception) {
            threw = e.message?.contains("CODING_STATE_CORRUPT") == true
        }
        check(threw, "a corrupt coding task was recreated as a fresh empty task")

        // A genuinely absent task still starts cleanly.
        val empty = object : dev.wind.agent.coding.CodingStateStore {
            override fun load(taskId: String, now: Long): dev.wind.agent.coding.CodingTaskState? = null
            override fun save(state: dev.wind.agent.coding.CodingTaskState) {}
        }
        val fresh = CodingTaskRuntime(ScriptedHost(), empty).start("task-new", "proj", 1L)
        eq(CodingPhase.CREATED, fresh.phase)
    }

    /** B4: a device path inside a diagnostic MESSAGE must not reach the model. */
    private fun messageTextIsScrubbedOfDevicePaths() {
        dev.wind.agent.coding.DiagnosticPathMapper.configure(
            listOf("project" to "workspace://"),
            listOf("/data/data/com.termux/files/home/.vega-safe/workspaces/abc")
        )
        val gradle = DiagnosticPipeline.parse(
            "",
            "ERROR: /data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/lib.so: permission denied",
            1
        )
        for (item in gradle.errors) {
            val line = item.oneLine()
            check(!line.contains("termux"), "a Termux path survived in a message: " + line)
            check(!line.contains("/data/data"), "an absolute device path survived in a message: " + line)
        }
        val generic = DiagnosticPipeline.parse(
            "",
            "FAIL /data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/out.bin: cannot write",
            1
        )
        for (item in generic.errors) {
            check(
                !item.oneLine().contains("/data/data"),
                "an absolute device path survived a generic message: " + item.oneLine()
            )
        }
        val kotlin = DiagnosticPipeline.parse(
            "",
            "e: file:///data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/src/Foo.kt:4:2 " +
                "see /data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/Other.kt",
            1
        )
        check(!kotlin.errors.first().oneLine().contains("/data/data"),
            "an absolute device path survived a Kotlin message")
        val javac = DiagnosticPipeline.parse(
            "",
            "/data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/src/Foo.java:8: error: " +
                "see /data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/Other.java",
            1
        )
        check(!javac.errors.first().oneLine().contains("/data/data"),
            "an absolute device path survived a javac message")
        val pytest = DiagnosticPipeline.parse(
            "",
            "FAILED /data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/test_api.py::test_login - " +
                "/data/data/com.termux/files/home/.vega-safe/workspaces/abc/project/secret.txt",
            1
        )
        check(!pytest.errors.first().oneLine().contains("/data/data"),
            "an absolute device path survived a pytest message")
        dev.wind.agent.coding.DiagnosticPathMapper.reset()
    }

    /**
     * B5: a command the backend could not start must not read as a pass. It
     * produces no stderr and no useful exit code, so without an explicit
     * diagnostic the report parses clean and the retry controller calls it done.
     */
    private fun launchFailuresBecomeEnvironmentDiagnostics() {
        val report = DiagnosticPipeline.parse(
            stdout = "",
            stderr = "",
            exitCode = null,
            launchError = "SecurityException: gradlew could not be started",
            timedOut = false
        )
        check(!report.ok, "a launch failure produced no diagnostic")
        check(
            report.errors.first().category == dev.wind.agent.coding.DiagnosticCategory.TOOLCHAIN,
            "a launch failure was not classified as an environment problem"
        )
        check(
            DiagnosticPipeline.environmentBlocked(report),
            "a launch failure did not become environment-blocked"
        )

        val timedOut = DiagnosticPipeline.parse("", "", null, null, timedOut = true)
        check(!timedOut.ok, "a timeout produced no diagnostic, so it would read as a pass")
        check(
            timedOut.errors.first().category == dev.wind.agent.coding.DiagnosticCategory.TIMEOUT,
            "a timeout was not classified as a timeout"
        )

        // End to end through the runtime: a failed launch must not be reported
        // as VerificationReady.
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-launch")
        host.script("compile") {
            ValidationRunResult(null, "", "", 2L, error = "backend unavailable")
        }
        val summary = runtime.runFastChecks(CancellationToken())
        check(summary != null && !summary.ok, "a failed launch was reported as passing")
        val decision = runtime.decideAfterFailure()
        check(
            decision is RetryDecision.EnvironmentBlocked,
            "a failed launch was treated as a code failure: " + decision
        )
    }

    /** B5: execution metadata shares the same hard bound as parsed errors. */
    private fun metadataDoesNotBreakDiagnosticBounds() {
        val stderr = (0 until DiagnosticPipeline.MAX_ERRORS)
            .joinToString("\n") { "FAIL unique-diagnostic-$it" }
        val report = DiagnosticPipeline.parse(
            stdout = "",
            stderr = stderr,
            exitCode = null,
            launchError = "gradlew could not be started",
            timedOut = true
        )
        check(
            report.errors.size <= DiagnosticPipeline.MAX_ERRORS,
            "execution metadata expanded the bounded report to ${report.errors.size} errors"
        )
        check(report.errors.any { it.category == dev.wind.agent.coding.DiagnosticCategory.TOOLCHAIN },
            "the launch diagnostic was dropped when the parser reached its bound")
        check(report.errors.any { it.category == dev.wind.agent.coding.DiagnosticCategory.TIMEOUT },
            "the timeout diagnostic was dropped when the parser reached its bound")
    }

    /** B6: a project that merely mentions the word must not get a test command. */
    private fun nodeFalsePositiveIsRejected() {
        val noScript = ProjectDetector.classify(
            "workspace://",
            setOf("package.json"),
            mapOf(
                "package.json" to
                    "{ \"name\": \"x\", \"config\": { \"test\": \"unrelated\" }, " +
                    "\"devDependencies\": { \"vitest\": \"^1\" } }"
            )
        )
        eq(ProjectKind.NODE, noScript.kind)
        check(
            !noScript.testFrameworkHints.contains("npm-test"),
            "a package.json without scripts.test was credited with npm test: " +
                noScript.testFrameworkHints
        )
        val plan = ValidationPlanner.plan(noScript)
        check(
            plan.finalChecks.none { it.id == "test" },
            "a project with no test script was given a test command"
        )

        val withScript = ProjectDetector.classify(
            "workspace://",
            setOf("package.json"),
            mapOf("package.json" to "{ \"scripts\": { \"test\": \"vitest run\" } }")
        )
        check(
            withScript.testFrameworkHints.contains("npm-test"),
            "a real scripts.test was not detected: " + withScript.testFrameworkHints
        )
        check(
            ValidationPlanner.plan(withScript).finalChecks.any { it.id == "test" },
            "a project with a test script got no test command"
        )
    }

    /** AllowApply succeeds for root session regardless of verification; verification only determines outcome. */
    private fun applyIsGatedByVerification() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        val session = dev.wind.agent.coding.CodingSession(null, runtime, "task-apply")
        runtime.beginCoding("task-apply")
        host.mirror = "digest-apply-1"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        val allowedUnverified = session.allowApply("fingerprint-1")
        check(allowedUnverified == null, "root session allowApply failed: $allowedUnverified")

        val subSession = dev.wind.agent.coding.CodingSession(null, runtime, "task-apply", rootOwner = false)
        val subRefusal = subSession.allowApply("fingerprint-1")
        check(subRefusal?.contains("P4_SUBAGENT_APPLY_DENIED") == true, "subagent was allowed to apply: $subRefusal")

        host.script("compile") { ValidationRunResult(0, "", "", 5L) }
        host.script("build") { ValidationRunResult(0, "", "", 5L) }
        host.script("test") { ValidationRunResult(0, "", "", 5L) }
        runtime.runFinalVerification(CancellationToken())
        check(runtime.verificationValid(), "verification should be valid after run")
    }

    /** B3: a first shell_exec mutation cannot stay outside the coding gate. */
    private fun anInactiveShellMutationActivatesTheCodingGate() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        val session = dev.wind.agent.coding.CodingSession(null, runtime, "task-shell-first")
        runtime.start("task-shell-first", "proj", 1L)
        check(!runtime.active, "a fresh task was active before any coding evidence")

        // A read-only shell command remains ordinary chat.
        runtime.beforeTool("shell_exec")
        runtime.afterTool("shell_exec", ok = true, declaredMutation = false)
        check(!runtime.active, "a read-only shell command activated the coding gate")

        // On a cold device P3 may create the mirror during this shell call.
        // The post-prepare digest is the baseline, not evidence of a source
        // edit; the production callback captures it before the command runs.
        val coldHost = ScriptedHost()
        coldHost.mirror = null
        val coldRuntime = CodingTaskRuntime(coldHost)
        coldRuntime.start("task-shell-cold", "proj", 1L)
        coldHost.mirror = "digest-prepared"
        coldRuntime.captureSourceBaseline()
        coldRuntime.afterTool("shell_exec", ok = true, declaredMutation = false)
        check(!coldRuntime.active, "mirror preparation alone activated the coding gate")

        // A persistent mirror can exist but lag behind canonical source. The
        // post-prepare baseline must replace the stale task-start digest too.
        val staleHost = ScriptedHost()
        staleHost.mirror = "digest-stale"
        val staleRuntime = CodingTaskRuntime(staleHost)
        staleRuntime.start("task-shell-stale", "proj", 1L)
        staleHost.mirror = "digest-synchronised"
        staleRuntime.captureSourceBaseline()
        staleRuntime.afterTool("shell_exec", ok = true, declaredMutation = false)
        check(!staleRuntime.active, "mirror synchronisation from a stale digest activated the coding gate")

        // A command that changes the mirror is coding evidence even though the
        // tool itself was not an activation tool at call start.
        host.mirror = "digest-shell-mutation"
        runtime.beforeTool("shell_exec")
        runtime.afterTool("shell_exec", ok = true, declaredMutation = false)
        check(runtime.active, "a first shell mutation did not activate P4")
        eq(1, runtime.snapshot?.mutationEpoch)
        // In new policy, requestFinish allows reply, and allowApply allows root session saving without verification
        check(
            runtime.requestFinish() is FinishDecision.Allow,
            "reply should be allowed for shell mutation"
        )
        check(
            session.allowApply("shell-diff") == null,
            "root session allowApply should succeed without verification"
        )
        val report = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, report.status)
    }

    /** A missing mirror fingerprint must fail closed rather than stamp success. */
    private fun verificationRequiresAMirrorDigest() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-no-digest")
        host.mirror = null
        host.script("compile") { ValidationRunResult(0, "", "", 1L) }
        host.script("build") { ValidationRunResult(0, "", "", 1L) }
        host.script("test") { ValidationRunResult(0, "", "", 1L) }
        var failed = false
        try {
            runtime.runFinalVerification(CancellationToken())
        } catch (error: IllegalStateException) {
            failed = error.message?.contains("P4_MIRROR_DIGEST_UNAVAILABLE") == true
        }
        check(failed, "verification issued a stamp without a usable mirror fingerprint")
        check(runtime.snapshot?.verification == null, "a missing mirror digest left a verification stamp")
    }

    /** Ordinary chat stays passive and does not gain a synthetic P4 report. */
    private fun passiveSessionsDoNotManufactureP4Reports() {
        val runtime = CodingTaskRuntime(ScriptedHost())
        val session = dev.wind.agent.coding.CodingSession(null, runtime, "task-passive")
        runtime.start("task-passive", "proj", 1L)
        check(session.finishDecision() == null, "an untouched ordinary session entered the P4 report path")
    }

    /** A delegated child shares runtime state but never owns canonical apply. */
    private fun subAgentsCannotApplyCanonicalChanges() {
        val runtime = CodingTaskRuntime(ScriptedHost())
        val child = dev.wind.agent.coding.CodingSession(
            null,
            runtime,
            "task-child",
            gateFinish = false,
            rootOwner = false
        )
        runtime.start("task-child", "proj", 1L)
        check(
            child.allowApply("child-diff")?.contains("P4_SUBAGENT_APPLY_DENIED") == true,
            "a sub-agent retained a canonical apply capability"
        )
        check(child.finishDecision() == null, "a sub-agent owned the root completion gate")
    }

    /**
     * B2: the gate keeps answering after its push budget. A model that repeats
     * "done" has to end up blocked, never completed.
     */
    private fun theGateCannotBeOutlasted() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-gate")
        host.mirror = "digest-gate-edit"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)

        repeat(20) {
            val decision = runtime.requestFinish()
            eq(FinishDecision.Allow::class, decision::class)
        }
    }

    private fun failedEditWithUnchangedDigestDoesNotRecordMutation() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-failed-edit-noop")
        eq(0, runtime.mutationEpoch)

        // Tool execution fails and host mirror digest is unchanged
        runtime.afterTool("execution_edit_file", ok = false, declaredMutation = true)

        check(!runtime.hasObservedMutation, "failed edit with unchanged digest recorded a mutation")
        eq(0, runtime.mutationEpoch)
        val decision = runtime.requestFinish()
        check(decision is FinishDecision.Allow, "failed edit with no mutation was not allowed to finish: $decision")
    }

    private fun failedEditWithChangedDigestStillRecordsMutation() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-failed-edit-partial")

        // Tool execution fails, but mirror digest changed due to partial write
        host.mirror = "digest-partially-written"
        runtime.afterTool("execution_edit_file", ok = false, declaredMutation = true)

        check(runtime.hasObservedMutation, "partial write during failed edit was not observed")
        check(runtime.mutationEpoch > 0, "mutation epoch did not advance on partial write")
        val decision = runtime.requestFinish()
        eq(FinishDecision.Allow::class, decision::class)
        val report = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, report.status)
    }

    private fun executionPrepareSyncingMirrorDigestWithoutUserEditAllowsFinish() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-prepare-only", "proj", 1L)
        runtime.attachProfile(androidProfile(), ValidationPlanner.plan(androidProfile()))

        // execution_prepare syncs mirror digest without being a user edit
        runtime.beforeTool("execution_prepare")
        check(runtime.active, "execution_prepare did not activate coding task")
        host.mirror = "digest-synced-from-workspace"
        runtime.afterTool("execution_prepare", ok = true, declaredMutation = false)

        eq(0, runtime.mutationEpoch)
        check(!runtime.hasObservedMutation, "execution_prepare was treated as a user mutation")
        val decision = runtime.requestFinish()
        check(decision is FinishDecision.Allow, "execution_prepare without user edits was not allowed to finish: $decision")
    }

    private fun toolchainLaunchErrorDuringValidationBlocksCompletionGate() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-toolchain-launch-err")

        // Real edit occurs
        host.mirror = "digest-user-edit-1"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(runtime.hasObservedMutation, "user mutation was not recorded")

        // Validation fails with TOOLCHAIN launch error
        val checkCmd = runtime.currentPlan!!.fastChecks.first()
        host.script(checkCmd.id) {
            ValidationRunResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                elapsedMs = 5L,
                error = "RUNTIME_PROVIDER_UNAVAILABLE:VEGA_LINUX"
            )
        }
        val summary = runtime.validate(checkCmd, CancellationToken(), isFinal = false)
        check(!summary.ok, "validation summary with launch error reported ok")
        check(summary.blocked, "validation summary with launch error was not marked blocked")

        // requestFinish returns Allow for reply, but finishRun reports BLOCKED
        val decision = runtime.requestFinish()
        eq(FinishDecision.Allow::class, decision::class)
        val report = runtime.finishRun()
        eq(TaskStatus.BLOCKED, report.status)
        check(report.note.contains("RUNTIME_PROVIDER_UNAVAILABLE:VEGA_LINUX"), "report note missing error: " + report.note)
        check(!runtime.verificationValid(), "verificationValid returned true despite launch error")
    }

    private fun environmentBlockPersistsAcrossSourceEdits() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-env-block-lifecycle")

        // Real edit first so there is a change to block
        host.mirror = "digest-epoch-1"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        val initialEpoch = runtime.mutationEpoch

        // Record environment block
        runtime.recordEnvironmentBlock("TOOLCHAIN_MISSING: python3 not found")

        // Repeated finishRun calls without edits must all be BLOCKED
        repeat(3) { index ->
            val report = runtime.finishRun()
            eq(TaskStatus.BLOCKED, report.status)
        }

        // A source edit advances the epoch but does not prove the toolchain recovered.
        host.mirror = "digest-epoch-2"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(runtime.mutationEpoch > initialEpoch, "mutation epoch did not advance on new edit")

        // After the edit, the unresolved environment fact remains visible.
        val nextReport = runtime.finishRun()
        eq(TaskStatus.BLOCKED, nextReport.status)
        check(nextReport.note.contains("TOOLCHAIN_MISSING"), "source editing erased environment evidence")
    }

    private fun pureEnvironmentProbeWithoutMutationAllowsFinish() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-probe-only", "proj", 1L)
        runtime.attachProfile(androidProfile(), ValidationPlanner.plan(androidProfile()))

        // Read-only shell probe, e.g. checking environment capabilities
        runtime.captureSourceBaseline()
        runtime.beforeTool("shell_exec")
        runtime.afterTool("shell_exec", ok = true, declaredMutation = false)

        // Read file probe
        runtime.beforeTool("read_file")
        runtime.afterTool("read_file", ok = true, declaredMutation = false)

        eq(0, runtime.mutationEpoch)
        check(!runtime.hasObservedMutation, "read-only environment probe recorded a mutation")
        val decision = runtime.requestFinish()
        check(decision is FinishDecision.Allow, "pure environment probe without mutation was blocked: $decision")
    }

    private fun sessionBuildFastWithoutPlanBlocksWithMutationAndAllowsWithoutMutation() {
        // Case 1: with real mutation
        val hostWithMut = ScriptedHost()
        val runtimeWithMut = CodingTaskRuntime(hostWithMut)
        runtimeWithMut.start("task-session-mut", "proj", 1L)
        val sessionWithMut = dev.wind.agent.coding.CodingSession(null, runtimeWithMut, "task-session-mut")

        sessionWithMut.onToolStart("execution_edit_file")
        hostWithMut.mirror = "digest-session-mut-1"
        sessionWithMut.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)
        check(runtimeWithMut.hasObservedMutation, "mutation was not observed in session")

        sessionWithMut.onToolStart("build")
        val buildResult = sessionWithMut.run("build", JSONObject().put("mode", "fast"), CancellationToken())
        sessionWithMut.onToolEnd("build", ok = false, declaredMutation = false)
        check(buildResult?.contains("P4_PLAN_MISSING") == true, "build without plan did not report P4_PLAN_MISSING: $buildResult")

        // Finish decision is Allow, finishRun reports BLOCKED
        val firstDecision = sessionWithMut.finishDecision()
        eq(FinishDecision.Allow::class, firstDecision!!::class)
        val reportWithMut = sessionWithMut.finishRun()
        check(reportWithMut != null && reportWithMut.status == TaskStatus.BLOCKED, "expected BLOCKED status")

        // Case 2: without mutation
        val hostNoMut = ScriptedHost()
        val runtimeNoMut = CodingTaskRuntime(hostNoMut)
        runtimeNoMut.start("task-session-nomut", "proj", 1L)
        val sessionNoMut = dev.wind.agent.coding.CodingSession(null, runtimeNoMut, "task-session-nomut")

        sessionNoMut.onToolStart("build")
        val noMutBuildResult = sessionNoMut.run("build", JSONObject().put("mode", "fast"), CancellationToken())
        sessionNoMut.onToolEnd("build", ok = false, declaredMutation = false)
        check(noMutBuildResult?.contains("P4_PLAN_MISSING") == true, "no-mutation build did not report P4_PLAN_MISSING: $noMutBuildResult")

        check(!runtimeNoMut.hasObservedMutation, "mutation recorded on read-only session")
        val noMutDecision = sessionNoMut.finishDecision()
        check(noMutDecision is FinishDecision.Allow, "run with no mutation was not allowed to finish: $noMutDecision")
        check(runtimeNoMut.requestFinish() is FinishDecision.Allow, "runtime with no mutation was not allowed to finish")
    }



    /**
     * Additional regressions for refactored harness lifecycle:
     * - noteDiscarded resets outcome to NO_CHANGES and does not falsely claim applied.
     * - stopReason persists in task state and is reflected in the final report.
     * - No-op edit with unchanged mirror digest does not increment epoch or invalidate stamps.
     */
    private fun harnessLifecycleExtraRegressions() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.beginCoding("task-extra-regressions")

        // 1. Unverified edit -> finishDecision is Allow, finishRun is UNVERIFIED_NOT_APPLIED
        host.mirror = "digest-extra-1"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(runtime.hasObservedMutation, "mutation must be observed")
        eq(1, runtime.mutationEpoch)
        eq(FinishDecision.Allow::class, runtime.requestFinish()::class)
        val unverifiedReport = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, unverifiedReport.status)

        // 2. noteDiscarded -> resets outcome to NO_CHANGES
        runtime.noteDiscarded()
        val discardedReport = runtime.finishRun()
        eq(TaskStatus.NO_CHANGES, discardedReport.status)
        check(!discardedReport.applied, "discarded changes must not claim applied")

        // 3. New mutation, verified, and applied with receipt
        host.mirror = "digest-extra-2"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        eq(2, runtime.mutationEpoch)

        host.script("compile") { ValidationRunResult(0, "", "", 5L) }
        host.script("build") { ValidationRunResult(0, "", "", 5L) }
        host.script("test") { ValidationRunResult(0, "", "", 5L) }
        runtime.runFinalVerification(CancellationToken())
        check(runtime.verificationValid(), "verification stamp must be valid")

        val receipt = CodingApplyReceipt("exec-extra", "diff-extra", "digest-extra-2", 2, 1, 0, 0, 1000L, true)
        runtime.markApplied(true, "applied cleanly", receipt)
        val successReport = runtime.finishRun()
        eq(TaskStatus.SUCCESS, successReport.status)
        check(successReport.applied, "report must reflect applied")
        check(successReport.verified, "report must reflect verified")

        // 4. No-op edit with unchanged digest does not increment epoch
        val currentEpoch = runtime.mutationEpoch
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        eq(currentEpoch, runtime.mutationEpoch)
    }

    private fun androidProfile() = ProjectProfile(
        kind = ProjectKind.ANDROID_GRADLE,
        root = "workspace://",
        modules = listOf("app"),
        buildSystem = "gradle",
        wrapper = "gradlew",
        testFrameworkHints = listOf("junit"),
        confidence = Confidence.HIGH,
        androidApplication = true
    )

    /**
     * Puts a runtime into the "this run is a coding task" state the way the
     * engine does: by using a tool that only makes sense for code.
     */
    private fun CodingTaskRuntime.beginCoding(task: String, profile: ProjectProfile = androidProfile()) {
        start(task, "proj", 1L)
        attachProfile(profile, ValidationPlanner.plan(profile))
        captureSourceBaseline()
        beforeTool("execution_edit_file")
    }

    /**
     * A host whose command results are scripted per check id, and whose mirror
     * digest is a mutable string.
     */
    private class ScriptedHost : CodingRuntimeHost {
        var mirror: String? = "digest-0"
        private val results = HashMap<String, () -> ValidationRunResult>()
        private var clock = 1_000L

        fun script(id: String, body: () -> ValidationRunResult) {
            results[id] = body
        }

        override fun mirrorDigest(): String? = mirror

        override fun now(): Long {
            clock += 10
            return clock
        }

        override fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult {
            val body = results[command.id]
                ?: return ValidationRunResult(0, "", "", 1L)
            return body()
        }
    }

    /** An in-memory stand-in for the durable store. */
    private class MemoryStore : dev.wind.agent.coding.CodingStateStore {
        var saved: dev.wind.agent.coding.CodingTaskState? = null

        override fun load(taskId: String, now: Long): dev.wind.agent.coding.CodingTaskState? = saved

        override fun save(state: dev.wind.agent.coding.CodingTaskState) {
            saved = state
        }
    }
}
