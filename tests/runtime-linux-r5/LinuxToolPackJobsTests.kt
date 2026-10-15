package dev.wind.agent.runtime.linux

import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deterministic test suite for LinuxToolPackJobs and LinuxInstallControl.
 *
 * Verifies:
 * 1. Single-worker concurrency rejection: a second start() returns false while first operation runs.
 * 2. observe and removeObserver mechanics: new observer receives current state immediately; removed observer receives no further updates.
 * 3. Observer replacement (simulating Activity recreation): changing observer does NOT cancel or disrupt the ongoing background operation.
 * 4. Cancellation throwing InterruptedIOException: resets busy state and transitions state to CANCELLED.
 * 5. Recovery after general failure: a failed operation resets busy state and allows starting a subsequent operation.
 * 6. Phase progress reporting: LinuxInstallControl.phase publishes status updates through LinuxInstallControl.Listener to active observer.
 * 7. Late cancellation immunity: a successful operation commit is NOT overwritten or marked CANCELLED by a delayed cancel() call.
 */
object LinuxToolPackJobsTests {
    private var checks = 0

    private fun check(condition: Boolean, message: String) {
        checks++
        if (!condition) {
            throw AssertionError("FAILED: " + message)
        }
        println("  [PASS] " + message)
    }

    private fun awaitLatch(latch: CountDownLatch, desc: String, timeoutSec: Long = 10) {
        val ok = latch.await(timeoutSec, TimeUnit.SECONDS)
        if (!ok) {
            throw AssertionError("Timeout waiting for: " + desc + " (" + timeoutSec + "s)")
        }
    }

    fun testSingleWorkerConcurrencyRejection() {
        println("=== Test 1: Single-worker concurrency rejection ===")
        val startedLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        val firstAccepted = LinuxToolPackJobs.start {
            startedLatch.countDown()
            awaitLatch(releaseLatch, "op 1 release")
            "op1-complete"
        }
        check(firstAccepted, "First start() accepted")

        awaitLatch(startedLatch, "first op started")
        check(LinuxToolPackJobs.isBusy(), "isBusy() is true while operation 1 is executing")

        val secondAccepted = LinuxToolPackJobs.start {
            "op2-should-not-run"
        }
        check(!secondAccepted, "Second start() rejected while first is busy")

        releaseLatch.countDown()

        // Wait for first op completion
        val finishLatch = CountDownLatch(1)
        val obs: (LinuxToolPackJobs.Status) -> Unit = { status ->
            if (!status.busy && status.phase == "READY" && status.message == "op1-complete") {
                finishLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obs)
        awaitLatch(finishLatch, "first op finished")
        LinuxToolPackJobs.removeObserver(obs)

        check(!LinuxToolPackJobs.isBusy(), "isBusy() is false after completion")
    }

    fun testObserveAndRemoveObserver() {
        println("=== Test 2: observe and removeObserver mechanics ===")
        val eventsA = CopyOnWriteArrayList<LinuxToolPackJobs.Status>()
        val obsA: (LinuxToolPackJobs.Status) -> Unit = { eventsA.add(it) }

        LinuxToolPackJobs.observe(obsA)
        check(eventsA.isNotEmpty(), "Observer immediately receives current state upon observe()")
        val initialSize = eventsA.size

        LinuxToolPackJobs.removeObserver(obsA)

        // Trigger an operation; obsA should NOT receive any events
        val opLatch = CountDownLatch(1)
        LinuxToolPackJobs.start {
            opLatch.countDown()
            "quick-op"
        }
        awaitLatch(opLatch, "quick op started")

        // Wait until idle
        val waitIdle = CountDownLatch(1)
        val tempObs: (LinuxToolPackJobs.Status) -> Unit = {
            if (!it.busy && it.phase == "READY" && it.message == "quick-op") waitIdle.countDown()
        }
        LinuxToolPackJobs.observe(tempObs)
        awaitLatch(waitIdle, "quick op complete")
        LinuxToolPackJobs.removeObserver(tempObs)

        check(eventsA.size == initialSize, "Removed observer received no subsequent updates")
    }

    fun testObserverReplacementActivityRecreation() {
        println("=== Test 3: Observer replacement (Activity recreation) ===")
        val opStartedLatch = CountDownLatch(1)
        val opReleaseLatch = CountDownLatch(1)
        val phaseReportedLatch = CountDownLatch(1)
        val opCompletedLatch = CountDownLatch(1)

        val events1 = CopyOnWriteArrayList<LinuxToolPackJobs.Status>()
        val obs1: (LinuxToolPackJobs.Status) -> Unit = { status ->
            events1.add(status)
            if (status.phase == "CUSTOM_PHASE_1") {
                phaseReportedLatch.countDown()
            }
        }

        LinuxToolPackJobs.observe(obs1)

        val accepted = LinuxToolPackJobs.start {
            opStartedLatch.countDown()
            LinuxInstallControl.phase("CUSTOM_PHASE_1")
            awaitLatch(opReleaseLatch, "op release latch")
            "activity-recreate-complete"
        }
        check(accepted, "Operation started successfully")

        awaitLatch(phaseReportedLatch, "phase reported to first observer")

        // Simulate Activity onDestroy(): remove old observer
        LinuxToolPackJobs.removeObserver(obs1)
        val events1CountAtDetach = events1.size

        // Simulate new Activity onCreate(): attach new observer
        val events2 = CopyOnWriteArrayList<LinuxToolPackJobs.Status>()
        val obs2: (LinuxToolPackJobs.Status) -> Unit = { status ->
            events2.add(status)
            if (!status.busy && status.phase == "READY" && status.message == "activity-recreate-complete") {
                opCompletedLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obs2)

        check(events2.isNotEmpty(), "New observer receives ongoing state immediately")
        check(events2.first().busy, "New observer sees busy == true")
        check(events2.first().phase == "CUSTOM_PHASE_1", "New observer sees current phase CUSTOM_PHASE_1")

        // Release operation
        opReleaseLatch.countDown()

        awaitLatch(opCompletedLatch, "new observer receives final completion")
        check(events1.size == events1CountAtDetach, "Detached old observer received no events after detachment")
        check(!LinuxToolPackJobs.isBusy(), "isBusy() is false after completion")

        LinuxToolPackJobs.removeObserver(obs2)
    }

    fun testCancelWithInterruptedIOException() {
        println("=== Test 4: Cancel throwing InterruptedIOException & busy reset ===")
        val opRunningLatch = CountDownLatch(1)
        val cancelObsLatch = CountDownLatch(1)
        val completedCancelledLatch = CountDownLatch(1)

        val obs: (LinuxToolPackJobs.Status) -> Unit = { status ->
            if (status.phase == "CANCELLING") {
                cancelObsLatch.countDown()
            }
            if (!status.busy && status.phase == "CANCELLED") {
                completedCancelledLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obs)

        val accepted = LinuxToolPackJobs.start {
            opRunningLatch.countDown()
            try {
                val sleepLatch = CountDownLatch(1)
                sleepLatch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            LinuxInstallControl.check() // throws InterruptedIOException
            "should-not-reach"
        }
        check(accepted, "Operation started")

        awaitLatch(opRunningLatch, "operation is running")
        LinuxToolPackJobs.cancel()

        awaitLatch(cancelObsLatch, "CANCELLING phase observed")
        awaitLatch(completedCancelledLatch, "CANCELLED phase observed")

        check(!LinuxToolPackJobs.isBusy(), "isBusy() reset to false after cancel")
        LinuxToolPackJobs.removeObserver(obs)
    }

    fun testRecoveryAfterGeneralFailure() {
        println("=== Test 5: Recovery after general failure ===")
        val failedLatch = CountDownLatch(1)
        val obsFail: (LinuxToolPackJobs.Status) -> Unit = { status ->
            if (!status.busy && status.phase == "FAILED" && status.message == "SIMULATED_FAILURE") {
                failedLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obsFail)

        val acceptedFail = LinuxToolPackJobs.start {
            throw IllegalStateException("SIMULATED_FAILURE")
        }
        check(acceptedFail, "Failed operation was started")

        awaitLatch(failedLatch, "failure status reported to observer")
        check(!LinuxToolPackJobs.isBusy(), "isBusy() reset to false after failure")
        LinuxToolPackJobs.removeObserver(obsFail)

        // Verify that a subsequent operation can start and complete cleanly
        val recoveredLatch = CountDownLatch(1)
        val obsRecover: (LinuxToolPackJobs.Status) -> Unit = { status ->
            if (!status.busy && status.phase == "READY" && status.message == "RECOVERED") {
                recoveredLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obsRecover)

        val acceptedNext = LinuxToolPackJobs.start {
            "RECOVERED"
        }
        check(acceptedNext, "Next operation accepted after failure")
        awaitLatch(recoveredLatch, "subsequent operation completed successfully")
        check(!LinuxToolPackJobs.isBusy(), "isBusy() is false after subsequent operation")
        LinuxToolPackJobs.removeObserver(obsRecover)
    }

    fun testPhaseProgressPublishing() {
        println("=== Test 6: Phase progress publishing via LinuxInstallControl ===")
        val phasesObserved = CopyOnWriteArrayList<String>()
        val doneLatch = CountDownLatch(1)

        val obs: (LinuxToolPackJobs.Status) -> Unit = { status ->
            phasesObserved.add(status.phase)
            println("  [Test 6 obs callback] phase=" + status.phase + " busy=" + status.busy + " message=" + status.message)
            if (!status.busy && status.phase == "READY" && status.message == "PHASES_COMPLETE") {
                doneLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obs)

        val accepted = LinuxToolPackJobs.start {
            println("  [Test 6 worker] calling IMPORTING")
            LinuxInstallControl.phase("IMPORTING")
            println("  [Test 6 worker] calling VERIFYING")
            LinuxInstallControl.phase("VERIFYING")
            println("  [Test 6 worker] calling EXTRACTING")
            LinuxInstallControl.phase("EXTRACTING")
            println("  [Test 6 worker] calling PROBING")
            LinuxInstallControl.phase("PROBING")
            "PHASES_COMPLETE"
        }
        check(accepted, "Phase test operation started")

        awaitLatch(doneLatch, "phase test completed")
        LinuxToolPackJobs.removeObserver(obs)
        println("  [Test 6 final list] " + phasesObserved)

        check(phasesObserved.contains("IMPORTING"), "Phase IMPORTING was published")
        check(phasesObserved.contains("VERIFYING"), "Phase VERIFYING was published")
        check(phasesObserved.contains("EXTRACTING"), "Phase EXTRACTING was published")
        check(phasesObserved.contains("PROBING"), "Phase PROBING was published")
        check(phasesObserved.contains("READY"), "Phase READY was published")
    }
    fun testLateCancelDoesNotOverWriteCommittedSuccess() {
        println("=== Test 7: Late cancel does not overwrite committed success ===")
        val opFinishedLatch = CountDownLatch(1)
        val finalObsLatch = CountDownLatch(1)
        val observedStatuses = CopyOnWriteArrayList<LinuxToolPackJobs.Status>()

        val obs: (LinuxToolPackJobs.Status) -> Unit = { status ->
            observedStatuses.add(status)
            if (!status.busy && status.phase == "READY" && status.message == "COMMITTED_SUCCESS") {
                finalObsLatch.countDown()
            }
        }
        LinuxToolPackJobs.observe(obs)

        val accepted = LinuxToolPackJobs.start {
            val res = "COMMITTED_SUCCESS"
            opFinishedLatch.countDown()
            res
        }
        check(accepted, "Operation started")

        awaitLatch(opFinishedLatch, "operation body completed")
        // Now invoke cancel() right after body return / commit
        LinuxToolPackJobs.cancel()

        awaitLatch(finalObsLatch, "final state reached")
        LinuxToolPackJobs.removeObserver(obs)

        val lastStatus = observedStatuses.last()
        check(lastStatus.phase == "READY", "Committed success remains READY, got: " + lastStatus.phase)
        check(lastStatus.message == "COMMITTED_SUCCESS", "Message remains COMMITTED_SUCCESS, got: " + lastStatus.message)
        check(!lastStatus.busy, "busy is false")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting LinuxToolPackJobsTests...")
        testSingleWorkerConcurrencyRejection()
        testObserveAndRemoveObserver()
        testObserverReplacementActivityRecreation()
        testCancelWithInterruptedIOException()
        testRecoveryAfterGeneralFailure()
        testPhaseProgressPublishing()
        testLateCancelDoesNotOverWriteCommittedSuccess()
        println("ALL " + checks + " CHECKS PASSED SUCCESSFULLY.")
    }
}
