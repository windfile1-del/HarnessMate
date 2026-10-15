package dev.wind.agent

import dev.wind.agent.CancellationToken
import dev.wind.agent.coding.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lifecycle sidecar tests:
 * 1. beforeTool edit no-op failure does not record mutation.
 * 2. beforeTool edit with partial change still records mutation on failure.
 * 3. Cancelled task prevents any mirrorDigest reads during verificationValid (even with valid stamp),
 *    requestFinish, finishRun, or beforeTool.
 * 4. Passive CodingSession on a cancelled runtime does not probe mirrorDigest.
 */
object CodingLifecycleSidecarTests {

    private class SidecarHost(var mirror: String? = "digest-0") : CodingRuntimeHost {
        val mirrorDigestCalls = AtomicInteger(0)
        @Volatile var prohibitMirrorDigest = false
        private var clock = 1000L

        override fun mirrorDigest(): String? {
            mirrorDigestCalls.incrementAndGet()
            if (prohibitMirrorDigest) {
                throw AssertionError("PROHIBITED_MIRROR_DIGEST_READ_AFTER_CANCEL")
            }
            return mirror
        }

        override fun now(): Long {
            clock += 10L
            return clock
        }

        override fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult {
            return ValidationRunResult(0, "OK", "", 1L)
        }
    }

    /**
     * Case 1: beforeTool establishes baseline; failed edit with unchanged digest must not record mutation.
     */
    fun testBeforeToolEditNoOpFailureDoesNotRecordMutation() {
        val host = SidecarHost("digest-baseline")
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-sidecar-noop", "proj", 1L, restore = false)
        eq(0, runtime.mutationEpoch)

        // 1. Tool starts: beforeTool captures initial baseline
        val reject = runtime.beforeTool("execution_edit_file")
        check(reject == null) { "beforeTool should not reject" }
        check(runtime.active) { "activating tool must set active = true" }

        // 2. Tool fails without altering mirror content
        runtime.afterTool("execution_edit_file", ok = false, declaredMutation = true)

        // 3. Verify no mutation was recorded
        check(!runtime.hasObservedMutation) { "failed edit with unchanged digest recorded a mutation" }
        eq(0, runtime.mutationEpoch)
        val decision = runtime.requestFinish()
        check(decision is FinishDecision.Allow) { "failed edit with no mutation was not allowed to finish: $decision" }
    }

    /**
     * Case 2: beforeTool establishes baseline; failed edit that partially wrote content still records mutation.
     */
    fun testBeforeToolEditPartialChangeStillRecordsMutation() {
        val host = SidecarHost("digest-baseline")
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-sidecar-partial", "proj", 1L, restore = false)

        // 1. Tool starts and captures baseline
        runtime.beforeTool("execution_edit_file")

        // 2. Partial write alters mirror before failure
        host.mirror = "digest-partially-written"
        runtime.afterTool("execution_edit_file", ok = false, declaredMutation = true)

        // 3. Verify mutation is observed and epoch advanced
        check(runtime.hasObservedMutation) { "partial write during failed edit was not observed" }
        check(runtime.mutationEpoch > 0) { "mutation epoch did not advance on partial write" }
        val report = runtime.finishRun()
        eq(TaskStatus.UNVERIFIED_NOT_APPLIED, report.status)
    }

    /**
     * Case 3: After cancellation, even if a verification stamp was issued, verificationValid, requestFinish,
     * finishRun, and beforeTool must NOT probe mirrorDigest or backend.
     */
    fun testCancellationPreventsMirrorDigestProbesOnFinish() {
        val host = SidecarHost("digest-active")
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-sidecar-cancel", "proj", 1L, restore = false)

        val profile = ProjectProfile(ProjectKind.GENERIC, "execution://main")
        val checkCmd = ValidationCommand("test-check", "Verify check", listOf("test"), 1000L)
        val plan = ValidationPlan(finalChecks = listOf(checkCmd), confidence = Confidence.HIGH, reason = "Lifecycle regression fixture")
        runtime.attachProfile(profile, plan)

        // Activate coding task
        runtime.beforeTool("execution_edit_file")
        host.mirror = "digest-modified"
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(runtime.active) { "runtime must be active" }
        check(runtime.hasObservedMutation) { "mutation observed" }

        // Issue a valid verification stamp
        val stamp = runtime.runFinalVerification(CancellationToken())
        check(stamp.passedChecks.contains("test-check")) { "stamp must record passed check" }
        check(runtime.verificationValid(plan.fingerprint())) { "stamp must be valid before cancel" }

        // Cancel task and strictly prohibit any further mirrorDigest calls
        runtime.cancel()
        host.prohibitMirrorDigest = true
        val callsBefore = host.mirrorDigestCalls.get()

        // verificationValid with valid stamp must return false without probing mirrorDigest
        check(!runtime.verificationValid(plan.fingerprint())) { "cancelled task verificationValid must return false" }

        // requestFinish must not call mirrorDigest when cancelled
        val decision = runtime.requestFinish()
        check(decision is FinishDecision.Allow) { "cancelled requestFinish must return Allow, was $decision" }

        // finishRun must not call mirrorDigest when cancelled
        val report = runtime.finishRun("cancelled by user")
        check(report.status == TaskStatus.CANCELLED) { "report status must be CANCELLED, was ${report.status}" }

        // beforeTool must reject without probing mirrorDigest
        val toolReject = runtime.beforeTool("shell_exec")
        check(toolReject != null && toolReject.contains("P4_TASK_CANCELLED")) {
            "tool call after cancel must be rejected, was $toolReject"
        }

        eq(callsBefore, host.mirrorDigestCalls.get())
    }

    /**
     * Case 4: Passive CodingSession on a cancelled runtime does not probe mirrorDigest.
     */
    fun testPassiveCodingSessionOnCancelledRuntimeDoesNotProbeMirror() {
        val host = SidecarHost("digest-clean")
        host.prohibitMirrorDigest = true // never allow probe
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-passive-cancel", "proj", 1L, restore = false)
        runtime.cancel()

        val session = CodingSession(null, runtime, "task-passive-cancel")
        check(session.finishDecision() == null) { "passive cancelled session finishDecision must be null" }
        check(session.finishRun("cancel") == null) { "passive cancelled session finishRun must be null" }
        eq(0, host.mirrorDigestCalls.get())
    }

    private fun eq(expected: Any?, actual: Any?) {
        if (expected != actual) {
            throw AssertionError("expected <$expected> but was <$actual>")
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running CodingLifecycleSidecarTests ===")
        testBeforeToolEditNoOpFailureDoesNotRecordMutation()
        println("PASS: testBeforeToolEditNoOpFailureDoesNotRecordMutation")
        testBeforeToolEditPartialChangeStillRecordsMutation()
        println("PASS: testBeforeToolEditPartialChangeStillRecordsMutation")
        testCancellationPreventsMirrorDigestProbesOnFinish()
        println("PASS: testCancellationPreventsMirrorDigestProbesOnFinish")
        testPassiveCodingSessionOnCancelledRuntimeDoesNotProbeMirror()
        println("PASS: testPassiveCodingSessionOnCancelledRuntimeDoesNotProbeMirror")
        println("ALL_CODING_LIFECYCLE_SIDECAR_TESTS_PASS (4 cases)")
    }
}
