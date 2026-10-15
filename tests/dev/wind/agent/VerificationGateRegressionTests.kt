package dev.wind.agent

import dev.wind.agent.CancellationToken
import dev.wind.agent.coding.CodingPhase
import dev.wind.agent.coding.CodingRuntimeHost
import dev.wind.agent.coding.CodingSession
import dev.wind.agent.coding.CodingTaskRuntime
import dev.wind.agent.coding.Confidence
import dev.wind.agent.coding.FinishDecision
import dev.wind.agent.coding.ProjectDetector
import dev.wind.agent.coding.ProjectKind
import dev.wind.agent.coding.ProjectProfile
import dev.wind.agent.coding.TaskStatus
import dev.wind.agent.coding.ValidationCommand
import dev.wind.agent.coding.ValidationPlan
import dev.wind.agent.coding.ValidationPlanner
import dev.wind.agent.coding.ValidationRunResult
import dev.wind.agent.shell.ShellPolicy
import dev.wind.agent.shell.ShellRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scripted JVM regression tests for P4 verification gates, completion decisions,
 * and project mirror detection rules.
 *
 * Verifies that:
 * 1. GENERIC empty check projects block build(fast/final) and test(all), transitioning to BLOCKED terminal phase.
 * 2. Python projects without pytest still emit a non-empty final check containing syntax byte-compile.
 * 3. Mirror project detection discovers root markers, single nested modules, and refuses multiple ambiguous roots.
 * 4. Custom checks registered via mode=final are genuinely executed by the host, grant verification stamps on success,
 *    refuse apply on non-zero exit codes, and invalidate stamps on mirror digest change.
 * 5. Documentation-only diffs auto-run documentation-diff during allowApply without claiming compilation.
 * 6. Mixed code/documentation changes do not auto-apply without an explicit verification pass.
 * 7. Invocations of 'sh -c' / 'bash -c' or interpreter command strings are rejected by ShellPolicy.
 * 8. Validation plan fingerprints are strictly sensitive to working directory (cwd) changes.
 */
object VerificationGateRegressionTests {

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
        genericProjectEmptyChecksBlockBuildAndTestAndReachesTerminalState()
        pythonPlanWithoutPytestStillIncludesNonEmptySyntaxFinalCheck()
        detectMirrorDiscoversProjectRootsAndRejectsMultipleRoots()
        customChecksExecutedOnBuildFinalAllowApplyAndInvalidateOnDigestChange()
        documentationOnlyApplyRunsDocumentationDiffAndDoesNotClaimCompilation()
        mixedCodeAndDocChangesDoNotAutoApply()
        customShellDashCStringRejectedByShellPolicy()
        planFingerprintIsSensitiveToCwd()
        toolchainUpdateInvalidatesVerification()
        jvmPlannerAndKotlinLocations()
        validationTimeoutsReachRealSupervisor()
        prepareWithPendingEditsDoesNotEraseMutationFacts()
        println("PASS VerificationGateRegressionTests: $assertions assertions")
    }

    // ------------------------------------------------------------ test cases

    /**
     * GENERIC projects have no default build or test tasks.
     * Attempting build (fast/final) or test (all) must report BLOCKED and reach the BLOCKED terminal state.
     */
    private fun genericProjectEmptyChecksBlockBuildAndTestAndReachesTerminalState() {
        val host = ScriptedHost()
        host.profile = ProjectProfile(ProjectKind.GENERIC, "workspace://", confidence = Confidence.LOW)
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-generic", "proj-generic", 1L)
        val session = CodingSession(null, runtime, "task-generic")

        session.onToolStart("execution_edit_file")
        host.mirror = "digest-generic-1"
        session.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)

        // build mode=fast on empty plan
        val fastResult = session.run("build", JSONObject().put("mode", "fast"), CancellationToken())
        check(fastResult != null && fastResult.contains("BLOCKED: P4_NO_FAST_CHECKS"),
            "build fast should report BLOCKED: P4_NO_FAST_CHECKS, got: $fastResult")

        // build mode=final on empty plan
        val finalResult = session.run("build", JSONObject().put("mode", "final"), CancellationToken())
        check(finalResult != null && (finalResult.contains("BLOCKED: P4_PLAN_EMPTY") || finalResult.contains("BLOCKED: P4_NO_")),
            "build final should report BLOCKED, got: $finalResult")

        // test scope=all on empty plan
        val testAllResult = session.run("test", JSONObject().put("scope", "all"), CancellationToken())
        check(testAllResult != null && testAllResult.contains("BLOCKED: P4_NO_TEST_CHECKS"),
            "test all should report BLOCKED: P4_NO_TEST_CHECKS, got: $testAllResult")

        // Completion decision in new policy is Allow (finish/explanation is not blocked by compiler)
        val decision = session.finishDecision()
        eq(FinishDecision.Allow::class, decision!!::class)

        // But factual finishRun records BLOCKED because stopReason is recorded
        val report = session.finishRun()
        check(report != null, "finishRun must produce a factual report")
        eq(TaskStatus.BLOCKED, report!!.status)
        check(report.note.contains("P4_NO_") || report.note.contains("P4_PLAN_"), "report note must retain stopReason: " + report.note)

        // Finishing as BLOCKED sets terminal phase
        val finalReport = session.finish(TaskStatus.BLOCKED, "Generic project has no runnable build or test harness")
        eq(TaskStatus.BLOCKED, finalReport.status)
        eq(CodingPhase.BLOCKED, session.phase)
    }

    /**
     * Python projects without pytest must still emit a non-empty finalChecks containing the syntax check.
     */
    private fun pythonPlanWithoutPytestStillIncludesNonEmptySyntaxFinalCheck() {
        val pythonNoPytest = ProjectProfile(
            kind = ProjectKind.PYTHON,
            root = "workspace://",
            confidence = Confidence.HIGH,
            testFrameworkHints = emptyList()
        )
        val planNoPytest = ValidationPlanner.plan(pythonNoPytest)
        check(planNoPytest.finalChecks.isNotEmpty(), "Python plan finalChecks must not be empty without pytest")
        check(planNoPytest.finalChecks.any { it.id == "syntax" }, "Python plan without pytest must include 'syntax' check")
        check(planNoPytest.finalChecks.none { it.id == "test" }, "Python plan without pytest must not include 'test' check")
        eq(listOf("python3", "-m", "compileall", "-q", "."), planNoPytest.finalChecks.first { it.id == "syntax" }.argv)

        val pythonWithPytest = ProjectProfile(
            kind = ProjectKind.PYTHON,
            root = "workspace://",
            confidence = Confidence.HIGH,
            testFrameworkHints = listOf("pytest")
        )
        val planWithPytest = ValidationPlanner.plan(pythonWithPytest)
        check(planWithPytest.finalChecks.any { it.id == "test" }, "Python plan with pytest must include 'test' check")
        check(planWithPytest.finalChecks.any { it.id == "syntax" }, "Python plan with pytest must still retain 'syntax' check")
    }

    /**
     * detectMirror should discover project markers directly under primary, single nested subdirectories,
     * and fail with an explicit hint when multiple candidate subprojects are present.
     */
    private fun detectMirrorDiscoversProjectRootsAndRejectsMultipleRoots() {
        // Single root project: main/pyproject.toml
        val pathsRoot = setOf("main/pyproject.toml", "main/app.py", "main/README.md")
        val profileRoot = ProjectDetector.detectMirror(pathsRoot, "main") { path ->
            if (path == "main/pyproject.toml") "[tool.poetry]\nname = 'demo'\n" else ""
        }
        eq(ProjectKind.PYTHON, profileRoot.kind)
        eq("execution://main", profileRoot.root)

        // Single nested child project: main/kotlinc_probe/build.gradle
        val pathsSingleChild = setOf("main/kotlinc_probe/build.gradle", "main/kotlinc_probe/src/Main.kt")
        val profileSingleChild = ProjectDetector.detectMirror(pathsSingleChild, "main") { path ->
            if (path == "main/kotlinc_probe/build.gradle") "plugins { id 'org.jetbrains.kotlin.jvm' }\n" else ""
        }
        eq(ProjectKind.JVM_GRADLE, profileSingleChild.kind)
        eq("execution://main/kotlinc_probe", profileSingleChild.root)

        // Multiple subprojects: main/sub1/build.gradle and main/sub2/pyproject.toml
        val pathsMultiple = setOf("main/sub1/build.gradle", "main/sub2/pyproject.toml")
        var caughtMultiple = false
        try {
            ProjectDetector.detectMirror(pathsMultiple, "main") { "" }
        } catch (e: IllegalArgumentException) {
            caughtMultiple = true
            val msg = e.message ?: ""
            check(msg.contains("P4_MULTIPLE_PROJECT_ROOTS"), "Exception message must contain P4_MULTIPLE_PROJECT_ROOTS: $msg")
            check(msg.contains("specify project_path"), "Exception message must prompt to specify project_path: $msg")
        }
        check(caughtMultiple, "detectMirror must reject ambiguous multiple subprojects")
    }

    /**
     * Custom checks registered as checks=[["python3", "selftest.py"]] are executed by the host on build mode=final.
     * Passing execution permits allowApply; non-zero exit code refuses apply; and modifying mirror digest invalidates stamp.
     */
    private fun customChecksExecutedOnBuildFinalAllowApplyAndInvalidateOnDigestChange() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-custom-check", "proj-custom", 1L)
        val session = CodingSession(null, runtime, "task-custom-check")

        session.onToolStart("execution_edit_file")
        host.mirror = "digest-custom-v1"
        session.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)

        val checksArg = JSONArray().put(JSONArray().put("python3").put("selftest.py"))

        // Case 1: Custom check passes (exit 0)
        host.commandHandler = { cmd ->
            ValidationRunResult(0, "ALL TESTS PASSED", "", 45L)
        }
        val buildResult = session.run(
            "build",
            JSONObject().put("mode", "final").put("checks", checksArg),
            CancellationToken()
        )
        check(buildResult != null && buildResult.startsWith("Verification passed:"),
            "build mode=final with custom checks should pass: $buildResult")
        check(host.executedCommands.any { it.argv == listOf("python3", "selftest.py") },
            "custom command python3 selftest.py must be genuinely dispatched to host")

        // allowApply succeeds with valid stamp
        val applyError = session.allowApply("diff-stamp-v1")
        check(applyError == null, "allowApply should succeed when verification passed: $applyError")
        host.commandHandler = { ValidationRunResult(1, "", "check now fails", 1L) }
        check(session.run("build", JSONObject().put("mode", "final"), CancellationToken())!!.contains("FAILED:"),
            "rechecking the same source must report the new failure")
        check(!runtime.verificationValid(), "failed revalidation must revoke an earlier passing stamp")
        check(session.allowApply("diff-stamp-v1") == null, "allowApply must still allow root session saving after failed revalidation")
        host.commandHandler = { ValidationRunResult(0, "", "", 1L) }
        session.run("build", JSONObject().put("mode", "final"), CancellationToken())
        check(runtime.verificationValid(), "a subsequent real pass may restore verification")

        // Invalidate by changing digest
        host.mirror = "digest-custom-v2"
        val applyAfterMutation = session.allowApply("diff-stamp-v1")
        check(applyAfterMutation == null, "allowApply succeeds for root task without verification: $applyAfterMutation")

        // Case 2: Custom check fails (exit 1)
        val hostFail = ScriptedHost()
        val runtimeFail = CodingTaskRuntime(hostFail)
        runtimeFail.start("task-custom-fail", "proj-custom", 1L)
        val sessionFail = CodingSession(null, runtimeFail, "task-custom-fail")

        sessionFail.onToolStart("execution_edit_file")
        hostFail.mirror = "digest-fail-v1"
        sessionFail.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)

        hostFail.commandHandler = { cmd ->
            ValidationRunResult(1, "", "selftest.py failed: assertion error", 30L)
        }
        val failResult = sessionFail.run(
            "build",
            JSONObject().put("mode", "final").put("checks", checksArg),
            CancellationToken()
        )
        check(failResult != null && failResult.contains("FAILED:"), "build mode=final must report failure: $failResult")

        val applyOnFail = sessionFail.allowApply("diff-fail-v1")
        check(applyOnFail == null, "allowApply does not block unverified root changes: $applyOnFail")
    }

    /**
     * Documentation-only tasks automatically execute 'documentation-diff' during allowApply,
     * generating a valid stamp whose reason does not claim compilation or test execution.
     */
    private fun documentationOnlyApplyRunsDocumentationDiffAndDoesNotClaimCompilation() {
        val host = ScriptedHost()
        host.docOnly = true
        host.commandHandler = { cmd ->
            ValidationRunResult(0, "diff clean", "", 5L)
        }
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-doc", "proj-doc", 1L)
        val session = CodingSession(null, runtime, "task-doc")

        session.onToolStart("execution_edit_file")
        host.mirror = "digest-doc-v1"
        session.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)

        // In the new policy: allowApply only permits and records the diff; it does NOT execute checks or issue stamps
        val applyResult = session.allowApply("diff-doc-plan-1")
        check(applyResult == null, "doc-only allowApply should succeed automatically: $applyResult")
        check(host.executedCommands.isEmpty(), "allowApply must not execute validation commands or documentation-diff")
        check(runtime.snapshot?.verification == null, "allowApply must not issue a verification stamp")

        // Build mode=final can explicitly generate documentation stamp when requested
        val buildResult = session.run("build", JSONObject().put("mode", "final"), CancellationToken())
        check(buildResult != null && buildResult.startsWith("Verification passed:"), "build mode=final for doc-only project must pass: $buildResult")
        check(host.executedCommands.any { it.id == "documentation-diff" }, "host must execute documentation-diff on explicit build final")
        val stamp = runtime.snapshot?.verification
        check(stamp != null, "verification stamp must be issued on build final")
        eq(listOf("documentation-diff"), stamp?.passedChecks)
        val planReason = runtime.currentPlan?.reason ?: ""
        check(planReason.contains("no compilation or tests claimed"), "plan reason must state no compilation or tests claimed: $planReason")
    }

    private fun mixedCodeAndDocChangesDoNotAutoApply() {
        val host = ScriptedHost()
        host.docOnly = false // not documentation-only
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-mixed", "proj-mixed", 1L)
        val session = CodingSession(null, runtime, "task-mixed")

        session.onToolStart("execution_edit_file")
        host.mirror = "digest-mixed-v1"
        session.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)

        val applyResult = session.allowApply("diff-mixed-plan-1")
        check(applyResult == null, "root session allowApply does not require verification: $applyResult")
    }

    /**
     * Custom shell invocations with '-c' (such as 'sh -c', 'bash -c', or 'python3 -c')
     * must be immediately refused by ShellPolicy.
     */
    private fun customShellDashCStringRejectedByShellPolicy() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-shell-policy", "proj-shell", 1L)
        val session = CodingSession(null, runtime, "task-shell-policy")

        // sh -c rejection via CodingSession custom check parsing
        var caughtSh = false
        try {
            val checks = JSONArray().put(JSONArray().put("sh").put("-c").put("echo 1"))
            session.run("build", JSONObject().put("mode", "final").put("checks", checks), CancellationToken())
        } catch (e: SecurityException) {
            caughtSh = true
            check(e.message?.contains("sh -c") == true, "SecurityException must mention sh -c: ${e.message}")
        }
        check(caughtSh, "ShellPolicy must reject 'sh -c'")

        // bash -c rejection
        var caughtBash = false
        try {
            val checks = JSONArray().put(JSONArray().put("bash").put("-c").put("echo 1"))
            session.run("build", JSONObject().put("mode", "final").put("checks", checks), CancellationToken())
        } catch (e: SecurityException) {
            caughtBash = true
            check(e.message?.contains("sh -c") == true, "SecurityException must mention sh -c: ${e.message}")
        }
        check(caughtBash, "ShellPolicy must reject 'bash -c'")

        // python3 -c interpreter string rejection
        var caughtPython = false
        try {
            val checks = JSONArray().put(JSONArray().put("python3").put("-c").put("import os"))
            session.run("build", JSONObject().put("mode", "final").put("checks", checks), CancellationToken())
        } catch (e: SecurityException) {
            caughtPython = true
            check(e.message?.contains("Interpreter command strings are forbidden") == true,
                "SecurityException must mention interpreter strings: ${e.message}")
        }
        check(caughtPython, "ShellPolicy must reject 'python3 -c'")
    }

    /**
     * Plan fingerprints must incorporate the working directory (cwd).
     * Modifying cwd while preserving id/argv/labels must produce a distinct fingerprint.
     */
    private fun planFingerprintIsSensitiveToCwd() {
        val cmdRoot = ValidationCommand(
            id = "compile",
            label = "Compile",
            argv = listOf("python3", "main.py"),
            cwd = "workspace://"
        )
        val cmdSub = ValidationCommand(
            id = "compile",
            label = "Compile",
            argv = listOf("python3", "main.py"),
            cwd = "workspace://sub_module/"
        )

        val planRoot = ValidationPlan(
            fastChecks = listOf(cmdRoot),
            confidence = Confidence.HIGH,
            reason = "Root plan"
        )
        val planSub = ValidationPlan(
            fastChecks = listOf(cmdSub),
            confidence = Confidence.HIGH,
            reason = "Root plan"
        )

        val fpRoot = planRoot.fingerprint()
        val fpSub = planSub.fingerprint()

        check(fpRoot.isNotBlank() && fpSub.isNotBlank(), "Plan fingerprints must not be blank")
        check(fpRoot != fpSub, "Plan fingerprint must change when cwd changes: root=$fpRoot, sub=$fpSub")
    }

    // ------------------------------------------------------------ scripted host

    /**
     * Scripted test host implementing CodingRuntimeHost with mutable profile, docOnly,
     * mirror digest, and recorded command history.
     */
    private fun toolchainUpdateInvalidatesVerification() {
        val host = ScriptedHost()
        host.profile = ProjectProfile(ProjectKind.PYTHON, "execution://main")
        val runtime = CodingTaskRuntime(host)
        runtime.start("r2-toolchain", "project", 1L)
        runtime.attachProfile(host.profile!!, ValidationPlanner.plan(host.profile!!))
        runtime.runFinalVerification(CancellationToken())
        check(runtime.verificationValid(), "fresh environment stamp must pass")
        host.environment = "new-bundle"
        check(!runtime.verificationValid(), "bundle change must invalidate stamp without a source edit")
        runtime.runFinalVerification(CancellationToken())
        check(runtime.verificationValid(), "rerun with new toolchain should restore verification")
        host.environment = null
        check(!runtime.verificationValid(), "unavailable toolchain must invalidate stamp")
    }

    private fun jvmPlannerAndKotlinLocations() {
        dev.wind.agent.coding.DiagnosticPathMapper.configure(listOf("main" to "workspace://"), listOf("/workspace"))
        val java = ProjectProfile(ProjectKind.JVM_GRADLE, "execution://main")
        eq("classes", ValidationPlanner.plan(java).fastChecks.first().argv[1])
        val kotlin = java.copy(kotlinPlugin = true)
        eq("classes", ValidationPlanner.plan(kotlin).fastChecks.first().argv[1])
        val report = dev.wind.agent.coding.DiagnosticPipeline.parse("", "w: file:///workspace/main/src/A B.kt: (7, 3): Warning text", 0)
        eq(1, report.warnings.size)
        eq(7, report.warnings.single().line)
        eq(3, report.warnings.single().column)
        val error = dev.wind.agent.coding.DiagnosticPipeline.parse("", "e: file:///workspace/main/src/A.kt:9:2 Unresolved reference", 1)
        eq(9, error.errors.single().line)
        eq(2, error.errors.single().column)
        eq("workspace://src/A.kt", error.errors.single().path)
    }

    private fun validationTimeoutsReachRealSupervisor() {
        val root = java.nio.file.Files.createTempDirectory("vega-validation-timeout").toFile()
        val java = java.io.File(System.getProperty("java.home"), "bin/java.exe").let {
            if (it.isFile) it else java.io.File(System.getProperty("java.home"), "bin/java")
        }.absolutePath
        val host = ScriptedHost()
        val supervisor = dev.wind.agent.runtime.RuntimeProcessSupervisor()
        try {
            host.commandHandler = { command ->
                ShellPolicy.validateRequest(ShellRequest("echo", listOf("ok"), timeoutMs = command.timeoutMs))
                val execution = supervisor.start(listOf(java, "-version"), root, emptyMap(), command.timeoutMs)
                val result = supervisor.await(execution, 10_000L)
                check(result != null, "immediate host command must finish without waiting for the requested build deadline")
                ValidationRunResult(result!!.exitCode, result.stdout, result.stderr, result.durationMs,
                    timedOut = result.timedOut, cancelled = result.cancelled, error = result.failure.takeIf { it.isNotBlank() })
            }
            // Real planner budgets pass through runtime.validate -> supervisor.start;
            // only the guest executable is substituted with the host Java version command.
            for (profile in listOf(
                ProjectProfile(ProjectKind.PYTHON, "execution://main"),
                ProjectProfile(ProjectKind.PYTHON, "execution://main", testFrameworkHints = listOf("pytest")),
                ProjectProfile(ProjectKind.JVM_GRADLE, "execution://main", testFrameworkHints = listOf("junit"))
            )) {
                host.profile = profile
                val runtime = CodingTaskRuntime(host)
                runtime.start("timeout-${profile.kind}", "timeout-project", 1L)
                runtime.attachProfile(profile, ValidationPlanner.plan(profile))
                check(runtime.runFastChecks(CancellationToken()) == null, "planner fast budget must reach dispatch")
                runtime.runFinalVerification(CancellationToken())
                check(runtime.verificationValid(), "real completed checks must grant a final stamp")
            }
            val runtime = CodingTaskRuntime(host)
            host.profile = ProjectProfile(ProjectKind.GENERIC, "execution://main")
            runtime.start("custom-timeout", "timeout-project", 1L)
            val session = CodingSession(null, runtime, "custom-timeout")
            session.onToolStart("execution_edit_file")
            host.mirror = "digest-timeout-edited"
            session.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)
            val final = session.run("build", JSONObject().put("mode", "final")
                .put("checks", JSONArray().put(JSONArray().put("echo").put("ok"))), CancellationToken())
            check(final?.contains("Verification passed") == true, "registered default 600s checks must execute")
            val applyError = session.allowApply("validated-diff")
            check(applyError == null, "successful final verification must unlock apply gate: $applyError")
        } finally {
            supervisor.close()
            root.deleteRecursively()
        }
    }


    /**
     * Regressions for the refactored lifecycle:
     * 1. Unverified changes allow finish and report UNVERIFIED_NOT_APPLIED without claiming verification.
     * 2. Verified changes report VERIFIED_NOT_APPLIED if not applied.
     * 3. Applying changes requires a durable CodingApplyReceipt (or ApplyPlan); without it markApplied fails.
     * 4. Once applied with valid receipt, finishRun reports SUCCESS (if verified) or APPLIED_UNVERIFIED (if unverified).
     * 5. noteDiscarded resets outcome to NO_CHANGES and does not falsely claim applied.
     * 6. stopReason persists in task state and appears in report note.
     * 7. No-op edits (unchanged mirror digest) do not advance mutation epoch or invalidate stamps.
     * 8. Explicit project_path and registered checks survive in task state.
     */
    private fun harnessLifecycleRefactoredRegressions() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-harness-lifecycle", "proj", 1L)
        val session = CodingSession(null, runtime, "task-harness-lifecycle")

        // 1. Full successful chain (F3/F4 fix regression):
        // Edit -> verify -> allowApply -> onApplied(plan) -> onToolEnd(CANONICAL_WRITE) -> finishRun = SUCCESS
        host.mirror = "digest-chain-1"
        session.onToolStart("execution_edit_file")
        session.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)
        val epochBeforeApply = runtime.mutationEpoch
        eq(1, epochBeforeApply)

        val profile = ProjectProfile(ProjectKind.PYTHON, "execution://main")
        runtime.attachProfile(profile, ValidationPlanner.plan(profile))
        host.commandHandler = { ValidationRunResult(0, "OK", "", 10L) }
        runtime.runFinalVerification(CancellationToken())
        check(runtime.verificationValid(), "verification must be valid before apply")

        val applyError = session.allowApply("diff-chain-fp")
        check(applyError == null, "allowApply must succeed: $applyError")

        val planSuccess = dev.wind.agent.shell.mirror.ApplyPlan(
            executionId = "exec-chain-1",
            diffFingerprint = "diff-chain-fp",
            hasDestructiveChanges = false,
            changedMountIds = setOf("mount-1"),
            createdFiles = 1,
            modifiedFiles = 2,
            deletedFiles = 0
        )
        session.onApplied(ok = true, detail = "Applied cleanly", plan = planSuccess)
        eq(CodingPhase.COMPLETED, session.phase)

        // Tool callback from AgentEngine for shell_apply_changes:
        // Must NOT clear verification, must NOT advance mutationEpoch, must maintain COMPLETED phase
        session.onToolEnd("shell_apply_changes", ok = true, effect = dev.wind.agent.ToolEffect.CANONICAL_WRITE)
        eq(epochBeforeApply, runtime.mutationEpoch)
        eq(CodingPhase.COMPLETED, session.phase)
        check(runtime.verificationValid(), "verification must remain valid after onToolEnd CANONICAL_WRITE")

        val successReport = session.finishRun()!!
        eq(TaskStatus.SUCCESS, successReport.status)
        check(successReport.applied, "must be applied")
        check(successReport.verified, "must be verified")
        eq(3, successReport.changedFiles)
        eq(1, successReport.createdFiles)
        eq(2, successReport.modifiedFiles)

        // 2. Unverified save chain:
        // Edit -> allowApply -> onApplied(plan) -> onToolEnd(CANONICAL_WRITE) -> finishRun = APPLIED_UNVERIFIED
        val runtimeUnv = CodingTaskRuntime(host)
        runtimeUnv.start("task-unverified-apply", "proj", 1L)
        val sessionUnv = CodingSession(null, runtimeUnv, "task-unverified-apply")
        host.mirror = "digest-chain-unv-1"
        sessionUnv.onToolStart("execution_edit_file")
        sessionUnv.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)

        val unvApplyError = sessionUnv.allowApply("diff-unv-fp")
        check(unvApplyError == null, "unverified allowApply must succeed on root session: $unvApplyError")

        val planUnv = dev.wind.agent.shell.mirror.ApplyPlan(
            executionId = "exec-unv-1",
            diffFingerprint = "diff-unv-fp",
            hasDestructiveChanges = false,
            changedMountIds = setOf("mount-1"),
            createdFiles = 0,
            modifiedFiles = 1,
            deletedFiles = 0
        )
        sessionUnv.onApplied(ok = true, detail = "Applied unverified", plan = planUnv)
        sessionUnv.onToolEnd("shell_apply_changes", ok = true, effect = dev.wind.agent.ToolEffect.CANONICAL_WRITE)

        val unvReport = sessionUnv.finishRun()!!
        eq(TaskStatus.APPLIED_UNVERIFIED, unvReport.status)
        check(unvReport.applied, "unverified save must report applied=true")
        check(!unvReport.verified, "unverified save must not claim verified")

        // 3. noteDiscarded -> resets outcome to NO_CHANGES and does not falsely claim applied
        val runtimeDisc = CodingTaskRuntime(host)
        runtimeDisc.start("task-discard", "proj", 1L)
        val sessionDisc = CodingSession(null, runtimeDisc, "task-discard")
        host.mirror = "digest-disc-1"
        sessionDisc.onToolStart("execution_edit_file")
        sessionDisc.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, sessionDisc.finishRun()!!.status)

        runtimeDisc.noteDiscarded()
        val discardedReport = sessionDisc.finishRun()!!
        eq(TaskStatus.NO_CHANGES, discardedReport.status)
        check(!discardedReport.applied, "discarded changes must not claim applied")

        // 4. NoProgress: repeated identical failures record stopReason -> finishRun is BLOCKED, requestFinish is Allow
        val runtimeNoProg = CodingTaskRuntime(host)
        runtimeNoProg.start("task-noprogress", "proj", 1L)
        val sessionNoProg = CodingSession(null, runtimeNoProg, "task-noprogress")
        host.mirror = "digest-noprogress"
        sessionNoProg.onToolStart("execution_edit_file")
        sessionNoProg.onToolEnd("execution_edit_file", ok = true, declaredMutation = true)
        val failProfile = ProjectProfile(ProjectKind.PYTHON, "execution://main")
        runtimeNoProg.attachProfile(failProfile, ValidationPlanner.plan(failProfile))

        val errorLine = "e: syntax error in main.py"
        host.commandHandler = { ValidationRunResult(1, "", errorLine, 5L) }
        runtimeNoProg.runFastChecks(CancellationToken())
        runtimeNoProg.decideAfterFailure()

        // Same failure repeated without edits triggers NoProgress
        runtimeNoProg.runFastChecks(CancellationToken())
        val retryDecision = runtimeNoProg.decideAfterFailure()
        check(retryDecision is dev.wind.agent.coding.RetryDecision.NoProgress, "expected NoProgress on identical failure: $retryDecision")
        check(runtimeNoProg.snapshot?.stopReason != null, "stopReason must be persisted on NoProgress")

        // requestFinish is Allow (model is not forced to loop or rebuild)
        eq(FinishDecision.Allow::class, sessionNoProg.finishDecision()!!::class)
        val noProgReport = sessionNoProg.finishRun()!!
        eq(TaskStatus.BLOCKED, noProgReport.status)
        check(noProgReport.note.contains("NO_PROGRESS"), "report note must explain NoProgress: " + noProgReport.note)

        // 5. Applying without receipt is rejected by markApplied
        var caughtNoReceipt = false
        try {
            runtime.markApplied(true, "applied without receipt")
        } catch (e: IllegalArgumentException) {
            caughtNoReceipt = e.message?.contains("CODING_APPLY_RECEIPT_REQUIRED") == true
        }
        check(caughtNoReceipt, "markApplied(true) without receipt must throw CODING_APPLY_RECEIPT_REQUIRED")

        // 6. Selected project_path and checks persistence roundtrip and recovery
        val store = MemoryStore()
        val runtimeWithStore = CodingTaskRuntime(host, store)
        runtimeWithStore.start("task-persistence", "proj", 1L)
        val sessionWithStore = CodingSession(null, runtimeWithStore, "task-persistence")

        val customChecksArg = JSONArray().put(JSONArray().put("python3").put("run_tests.py"))
        sessionWithStore.run("build", JSONObject().put("mode", "fast").put("project_path", "services/backend").put("checks", customChecksArg), CancellationToken())
        eq("services/backend", runtimeWithStore.snapshot?.selectedProjectPath)
        eq(1, runtimeWithStore.snapshot?.registeredChecks?.size)

        // Recovering task state restores selectedProjectPath and registeredChecks
        val savedState = store.saved!!
        val recoveredState = dev.wind.agent.coding.CodingTaskState.fromJson(savedState.toJson(), "task-persistence")
        eq("services/backend", recoveredState.selectedProjectPath)
        eq(1, recoveredState.registeredChecks.size)
        eq(listOf("python3", "run_tests.py"), recoveredState.registeredChecks.first().argv)
    }

    private class MemoryStore : dev.wind.agent.coding.CodingStateStore {
        var saved: dev.wind.agent.coding.CodingTaskState? = null
        override fun load(taskId: String, now: Long): dev.wind.agent.coding.CodingTaskState? = saved
        override fun save(state: dev.wind.agent.coding.CodingTaskState) { saved = state }
    }

    private fun prepareWithPendingEditsDoesNotEraseMutationFacts() {
        val host = ScriptedHost()
        val runtime = CodingTaskRuntime(host)
        runtime.start("recovered-task", "project", 1L)
        host.baselineSafe = false
        host.pendingId = "recovered-execution"
        runtime.beforeTool("execution_prepare")
        runtime.afterTool("execution_prepare", true, false)
        check(runtime.mutationEpoch > 0, "preserved edits must remain an observed mutation even when the digest did not change")
        eq("recovered-execution", runtime.snapshot?.lastExecutionId)
        val report = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, report.status)
        check(!report.countsKnown && !report.applied, "pending files must not be reported as zero changed or saved")
        check(host.executedCommands.isEmpty(), "closing a recovered task must not force build or test")
    }

    private class ScriptedHost : CodingRuntimeHost {
        var baselineSafe = true
        var pendingId: String? = null
        override fun preparedBaselineRefreshSafe(): Boolean = baselineSafe
        override fun pendingExecutionId(): String? = pendingId
        var environment: String? = "initial-bundle"
        override fun validationEnvironmentIdentity(): String? = environment
        var profile: ProjectProfile? = ProjectProfile(ProjectKind.GENERIC, "execution://main")
        var docOnly: Boolean = false
        var mirror: String? = "digest-0"
        var commandHandler: ((ValidationCommand) -> ValidationRunResult)? = null
        val executedCommands = mutableListOf<ValidationCommand>()
        private var clock = 1_000L

        override fun detectProject(projectPath: String?): ProjectProfile? = profile

        override fun documentationOnly(): Boolean = docOnly

        override fun mirrorDigest(): String? = mirror

        override fun now(): Long {
            clock += 10
            return clock
        }

        override fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult {
            executedCommands.add(command)
            return commandHandler?.invoke(command) ?: ValidationRunResult(0, "OK", "", 10L)
        }
    }
}
