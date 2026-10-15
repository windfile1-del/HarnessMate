package dev.wind.agent

import dev.wind.agent.CancellationToken
import dev.wind.agent.coding.*
import dev.wind.agent.runtime.*
import dev.wind.agent.shell.mirror.MirrorLockRegistry
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Host regression tests for lifecycle and cancellation freeze repairs:
 * 1. CodingTaskRuntime.start does not trigger host.mirrorDigest (read only on actual coding activation),
 *    ordinary final allows reply, and passive CodingSession (null Context, throwing mirror) does not probe on construct/finishDecision/finishRun.
 * 2. CancellationToken.watchThread interrupts blocking latch.await on cancel,
 *    deregistration via close() avoids interruption,
 *    prior cancellation still interrupts, and cancel() is non-blocking.
 * 3. AgentBus per-chat concurrency: two chats beginStarting/markRunning concurrently,
 *    explicit selectChat allows independent stop, bindWorker preserves isolation until unbindWorker,
 *    token(runId) is explicitly retrievable, and finish on an old run preserves new run approvals.
 * 4. KeyedWorkQueue: independent keys do not block each other, same-key jobs coalesce when coalesce=true,
 *    delete barrier (coalesce=false) is not overwritten and executes in serial order,
 *    await timeout retains job, flush false/true.
 * 5. MirrorLockRegistry: holding project A does not block independent project B,
 *    and waiter on project A exits cleanly on watchThread cancellation.
 * 6. CompanionCommandExecutor: active RPC cancellation sends cancel transaction independently
 *    to unblock a stuck poll, without waiting for worker thread polling loop.
 */
object LifecycleRepairRegressionTests {

    /**
     * Scripted/fake host for CodingTaskRuntime testing.
     */
    private class FakeHost(
        private val throwOnMirrorDigest: Boolean = false,
        private val returnDigest: String? = "baseline-mirror-digest"
    ) : CodingRuntimeHost {
        val mirrorDigestCalls = AtomicInteger(0)
        private var clock = 1000L

        override fun mirrorDigest(): String? {
            mirrorDigestCalls.incrementAndGet()
            if (throwOnMirrorDigest) {
                throw IllegalStateException("HOST_MIRROR_DIGEST_TRIGGERED: mirrorDigest must not be invoked during passive operation!")
            }
            return returnDigest
        }

        override fun now(): Long {
            clock += 10L
            return clock
        }

        override fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult {
            return ValidationRunResult(0, "OK", "", 1L)
        }
    }

    private fun resetAgentBus() {
        for (id in AgentBus.runningIds()) {
            AgentBus.finish(id)
        }
        AgentBus.selectChat(null)
        AgentBus.unbindWorker()
        AgentBus.activeTaskId = null
    }

    // -------------------------------------------------------------------------
    // 1. CodingTaskRuntime & CodingSession start / finish tests
    // -------------------------------------------------------------------------

    /**
     * 1.1: Ordinary start with a host that throws on mirrorDigest.
     * Verifies CodingTaskRuntime.start does NOT invoke mirrorDigest and initializes cleanly.
     */
    fun testOrdinaryStartWithThrowingHost() {
        val host = FakeHost(throwOnMirrorDigest = true)
        val runtime = CodingTaskRuntime(host)

        val state = runtime.start("task-throwing-host", "test-project", 1L, restore = false)
        check(state != null) { "runtime.start must return initial state" }
        check(state.phase == CodingPhase.CREATED) { "initial phase must be CREATED, was ${state.phase}" }
        check(!runtime.active) { "runtime must not be active for ordinary non-coding task" }
        check(host.mirrorDigestCalls.get() == 0) {
            "mirrorDigest was called during start: call count ${host.mirrorDigestCalls.get()}"
        }
    }

    /**
     * 1.2: Ordinary start with counting host: mirrorDigest invocation count remains 0.
     */
    fun testOrdinaryStartCountingHost() {
        val host = FakeHost(throwOnMirrorDigest = false, returnDigest = "baseline-digest")
        val runtime = CodingTaskRuntime(host)

        val state = runtime.start("task-counting-host", "test-project", 1L, restore = false)
        check(state != null) { "runtime.start must return state" }
        check(host.mirrorDigestCalls.get() == 0) {
            "CodingTaskRuntime.start must not trigger host.mirrorDigest, but count was ${host.mirrorDigestCalls.get()}"
        }
    }

    /**
     * 1.3: Ordinary final allows reply without probing mirror when inactive.
     * requestFinish() returns FinishDecision.Allow and finishRun() produces a clean report (NO_CHANGES).
     */
    fun testOrdinaryFinalAllowsReply() {
        val host = FakeHost(throwOnMirrorDigest = true)
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-final-allow", "test-project", 1L, restore = false)

        val decision = runtime.requestFinish()
        check(decision is FinishDecision.Allow) {
            "ordinary final requestFinish must return FinishDecision.Allow, was $decision"
        }

        val report = runtime.finishRun("normal conversation complete")
        check(report.status == TaskStatus.NO_CHANGES) {
            "ordinary conversation run must report NO_CHANGES status, was ${report.status}"
        }
        check(host.mirrorDigestCalls.get() == 0) {
            "ordinary final without mutations must not call mirrorDigest"
        }
    }

    /**
     * 1.4: Passive CodingSession construction, finishDecision and finishRun do not probe runtime.
     * Constructed with null Context and throwing FakeHost; neither probe nor scan occurs.
     */
    fun testPassiveCodingSessionDoesNotProbeRuntime() {
        val host = FakeHost(throwOnMirrorDigest = true)
        val runtime = CodingTaskRuntime(host)
        runtime.start("task-passive-session", "test-project", 1L, restore = false)

        // Passive construction with null context must not read mirror or throw
        val session = CodingSession(null, runtime, "task-passive-session")

        // finishDecision on inactive session must return null and never probe mirror
        val decision = session.finishDecision()
        check(decision == null) { "passive session finishDecision must return null, was $decision" }

        // finishRun on inactive session must return null and never probe mirror
        val report = session.finishRun("passive completion")
        check(report == null) { "passive session finishRun must return null, was $report" }

        check(host.mirrorDigestCalls.get() == 0) {
            "host.mirrorDigest must not be invoked during passive session lifecycle"
        }
    }

    // -------------------------------------------------------------------------
    // 2. CancellationToken.watchThread tests
    // -------------------------------------------------------------------------

    /**
     * 2.1: token.watchThread cancel can interrupt blocking latch.await.
     */
    fun testWatchThreadCancelInterruptsBlockingAwait() {
        val token = CancellationToken()
        val readyLatch = CountDownLatch(1)
        val blockLatch = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)

        val worker = Thread({
            val reg = token.watchThread(Thread.currentThread())
            readyLatch.countDown()
            try {
                blockLatch.await()
            } catch (e: InterruptedException) {
                interrupted.set(true)
            }
        }, "vepro-test-watch-interrupt")

        worker.start()
        check(readyLatch.await(5, TimeUnit.SECONDS)) { "worker failed to register watchThread within timeout" }
        Thread.sleep(50)

        val cancelled = token.cancel()
        check(cancelled) { "token.cancel() should return true" }

        worker.join(5000)
        check(!worker.isAlive) { "worker thread should have terminated after interrupt" }
        check(interrupted.get()) { "blocking latch.await must be interrupted by token.cancel()" }
    }

    /**
     * 2.2: token.watchThread after registration close() no longer interrupts.
     */
    fun testWatchThreadClosePreventsInterrupt() {
        val token = CancellationToken()
        val readyLatch = CountDownLatch(1)
        val wasInterrupted = AtomicBoolean(false)
        val completed = AtomicBoolean(false)

        val worker = Thread({
            val reg = token.watchThread(Thread.currentThread())
            reg.close()
            readyLatch.countDown()
            try {
                Thread.sleep(150)
                completed.set(true)
            } catch (e: InterruptedException) {
                wasInterrupted.set(true)
            }
        }, "vepro-test-watch-close")

        worker.start()
        check(readyLatch.await(5, TimeUnit.SECONDS)) { "worker failed to ready within timeout" }

        token.cancel()
        worker.join(5000)

        check(!worker.isAlive) { "worker thread should have finished" }
        check(!wasInterrupted.get()) { "worker thread must NOT be interrupted after registration close()" }
        check(completed.get()) { "worker thread should complete normally after registration close()" }
    }

    /**
     * 2.3: Cancellation occurring before watchThread still interrupts the thread.
     */
    fun testWatchThreadCancelPriorInterruptsImmediately() {
        val token = CancellationToken()
        val cancelOk = token.cancel()
        check(cancelOk) { "token.cancel() should return true" }
        check(token.isCancelled) { "token must be in cancelled state" }

        val readyLatch = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)

        val worker = Thread({
            readyLatch.countDown()
            val reg = token.watchThread(Thread.currentThread())
            val blockLatch = CountDownLatch(1)
            try {
                blockLatch.await(1000, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                interrupted.set(true)
            }
        }, "vepro-test-watch-prior-cancel")

        worker.start()
        check(readyLatch.await(5, TimeUnit.SECONDS)) { "worker failed to start within timeout" }
        worker.join(5000)

        check(!worker.isAlive) { "worker thread should terminate" }
        check(interrupted.get() || worker.isInterrupted) {
            "registration on an already-cancelled token must immediately interrupt the target thread"
        }
    }

    /**
     * 2.4: cancel() itself is non-blocking.
     */
    fun testWatchThreadCancelIsNonBlocking() {
        val token = CancellationToken()
        val readyLatch = CountDownLatch(1)
        val neverLatch = CountDownLatch(1)

        val worker = Thread({
            val reg = token.watchThread(Thread.currentThread())
            readyLatch.countDown()
            try {
                neverLatch.await()
            } catch (_: InterruptedException) {
            }
        }, "vepro-test-watch-non-blocking")

        worker.start()
        check(readyLatch.await(5, TimeUnit.SECONDS)) { "worker failed to start within timeout" }
        Thread.sleep(50)

        val startNanos = System.nanoTime()
        val cancelled = token.cancel()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        check(cancelled) { "token.cancel() should return true" }
        check(elapsedMs < 100) { "token.cancel() must be non-blocking; took ${elapsedMs}ms" }

        worker.join(2000)
    }

    // -------------------------------------------------------------------------
    // 3. AgentBus per-chat concurrency tests
    // -------------------------------------------------------------------------

    /**
     * 3.1: Two distinct chats can concurrently beginStarting and markRunning without stealing active selection.
     */
    fun testAgentBusConcurrentChatsBeginAndMarkRunning() {
        resetAgentBus()

        // Explicitly select chat A first
        AgentBus.selectChat("chat-concurrent-A")

        val runA = AgentBus.beginStarting("chat-concurrent-A", null)
        val runB = AgentBus.beginStarting("chat-concurrent-B", null)

        check(runA > 0L) { "runA must be positive runId, was $runA" }
        check(runB > 0L) { "runB must be positive runId, was $runB" }
        check(runA != runB) { "runA and runB must have distinct runIds" }

        // beginStarting must NOT steal already-selected chat A!
        check(AgentBus.activeChatId == "chat-concurrent-A") {
            "beginStarting must not overwrite previously selected chat, was ${AgentBus.activeChatId}"
        }

        val markA = AgentBus.markRunning(runA)
        val markB = AgentBus.markRunning(runB)

        check(markA) { "markRunning(runA) must succeed" }
        check(markB) { "markRunning(runB) must succeed" }

        check(AgentBus.state(runA) == AgentBus.RunState.RUNNING) { "chat A must be RUNNING" }
        check(AgentBus.state(runB) == AgentBus.RunState.RUNNING) { "chat B must be RUNNING" }
        check(AgentBus.isRunningFor("chat-concurrent-A")) { "isRunningFor(A) must be true" }
        check(AgentBus.isRunningFor("chat-concurrent-B")) { "isRunningFor(B) must be true" }

        // Explicitly verify token(runId) is retrievable per-run
        val tokenA = AgentBus.token(runA)
        val tokenB = AgentBus.token(runB)
        check(tokenA != null && tokenB != null && tokenA !== tokenB) {
            "token(runId) must return distinct tokens for runA and runB"
        }

        AgentBus.finish(runA)
        AgentBus.finish(runB)
        resetAgentBus()
    }

    /**
     * 3.2: Explicit selectChat allows independent stop without affecting the other chat.
     */
    fun testAgentBusSelectChatIndependentStop() {
        resetAgentBus()

        AgentBus.selectChat("chat-stop-A")
        val runA = AgentBus.beginStarting("chat-stop-A", null)
        val runB = AgentBus.beginStarting("chat-stop-B", null)
        AgentBus.markRunning(runA)
        AgentBus.markRunning(runB)

        // Cancel selected chat A
        val cancelA = AgentBus.requestCancel(byUser = true)
        check(cancelA) { "requestCancel for selected chat A must succeed" }

        check(AgentBus.state(runA) == AgentBus.RunState.STOPPING) { "chat A must be in STOPPING state" }
        check(AgentBus.token(runA)?.isCancelled == true) { "chat A token must be cancelled" }

        // Chat B must remain unaffected
        check(AgentBus.state(runB) == AgentBus.RunState.RUNNING) { "chat B must remain RUNNING" }
        check(AgentBus.token(runB)?.isCancelled == false) { "chat B token must NOT be cancelled" }

        // Explicitly select chat B and verify its state and token remain active
        AgentBus.selectChat("chat-stop-B")
        check(AgentBus.state() == AgentBus.RunState.RUNNING) { "selected chat B must report RUNNING" }
        check(!AgentBus.token().isCancelled) { "selected chat B token must NOT be cancelled" }

        // Stop chat B
        val cancelB = AgentBus.requestCancel(byUser = true)
        check(cancelB) { "requestCancel for selected chat B must succeed" }
        check(AgentBus.state(runB) == AgentBus.RunState.STOPPING) { "chat B must now be STOPPING" }

        AgentBus.finish(runA)
        AgentBus.finish(runB)
        resetAgentBus()
    }

    /**
     * 3.3: bindWorker keeps token and taskId isolated in worker thread until unbindWorker.
     */
    fun testAgentBusBindWorkerMaintainsIsolation() {
        resetAgentBus()

        AgentBus.selectChat("chat-worker-A")
        val runA = AgentBus.beginStarting("chat-worker-A", null)
        val runB = AgentBus.beginStarting("chat-worker-B", null)
        AgentBus.markRunning(runA)
        AgentBus.markRunning(runB)

        val workerTokenMatches = AtomicBoolean(false)
        val workerTaskIdMatches = AtomicBoolean(false)
        val workerChatIdMatches = AtomicBoolean(false)
        val unbindClearedIsolation = AtomicBoolean(false)
        val workerReady = CountDownLatch(1)
        val mainSwitched = CountDownLatch(1)
        val workerDone = CountDownLatch(1)

        val workerThread = Thread({
            try {
                AgentBus.bindWorker(runA)
                AgentBus.activeTaskId = "task-worker-A"
                workerReady.countDown()
                mainSwitched.await()

                val workerToken = AgentBus.token()
                val expectedToken = AgentBus.token(runA)
                workerTokenMatches.set(workerToken === expectedToken)

                val taskId = AgentBus.activeTaskId
                workerTaskIdMatches.set(taskId == "task-worker-A")

                val chatId = AgentBus.activeChatId
                workerChatIdMatches.set(chatId == "chat-worker-A")
            } finally {
                AgentBus.unbindWorker()
                // After unbindWorker, the thread now observes UI selectedChat (B)
                unbindClearedIsolation.set(AgentBus.activeChatId == "chat-worker-B")
                workerDone.countDown()
            }
        }, "vepro-test-worker-bind")

        workerThread.start()
        check(workerReady.await(5, TimeUnit.SECONDS)) { "worker failed to ready" }

        // Main thread explicitly switches UI selection to chat B
        AgentBus.selectChat("chat-worker-B")
        AgentBus.activeTaskId = "task-worker-B"

        mainSwitched.countDown()
        check(workerDone.await(5, TimeUnit.SECONDS)) { "worker failed to finish" }
        workerThread.join(2000)

        check(workerTokenMatches.get()) { "worker thread must access run A token, not run B" }
        check(workerTaskIdMatches.get()) { "worker thread must access run A taskId, not run B" }
        check(workerChatIdMatches.get()) { "worker thread must access run A chatId, not run B" }
        check(unbindClearedIsolation.get()) { "unbindWorker on thread exit must restore UI-selected chat context" }

        AgentBus.finish(runA)
        AgentBus.finish(runB)
        resetAgentBus()
    }

    /**
     * 3.4: Finishing an old run does NOT clear a new run's pending approvals.
     */
    fun testAgentBusFinishOldRunPreservesNewRunApprovals() {
        resetAgentBus()

        AgentBus.selectChat("chat-appr-A")
        val runA = AgentBus.beginStarting("chat-appr-A", null)
        val runB = AgentBus.beginStarting("chat-appr-B", null)
        AgentBus.markRunning(runA)
        AgentBus.markRunning(runB)

        val tokenA = AgentBus.token(runA)!!
        val tokenB = AgentBus.token(runB)!!

        val aApprovalReceived = CountDownLatch(1)
        val bApprovalReceived = CountDownLatch(1)
        val aDecisionHolder = AtomicReference<AgentBus.Decision?>(null)

        val threadA = Thread({
            aApprovalReceived.countDown()
            val dec = AgentBus.awaitApprovalDetailed("tool-A", null, tokenA)
            aDecisionHolder.set(dec)
        }, "appr-thread-A")

        val threadB = Thread({
            bApprovalReceived.countDown()
            AgentBus.awaitApprovalDetailed("tool-B", null, tokenB)
        }, "appr-thread-B")

        threadA.start()
        threadB.start()

        check(aApprovalReceived.await(5, TimeUnit.SECONDS))
        check(bApprovalReceived.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)

        // Select chat A and verify outstanding approvals is 1
        AgentBus.selectChat("chat-appr-A")
        check(AgentBus.outstandingApprovals() == 1) {
            "chat A must have 1 outstanding approval, was ${AgentBus.outstandingApprovals()}"
        }

        // Select chat B and verify outstanding approvals is 1
        AgentBus.selectChat("chat-appr-B")
        check(AgentBus.outstandingApprovals() == 1) {
            "chat B must have 1 outstanding approval, was ${AgentBus.outstandingApprovals()}"
        }

        // Finish old run A
        AgentBus.finish(runA)
        threadA.join(3000)

        // Run A's approval should be resolved with REASON_RUN_ENDED
        val decA = aDecisionHolder.get()
        check(decA != null && !decA.approved && decA.reason == AgentBus.REASON_RUN_ENDED) {
            "Run A approval must be rejected with REASON_RUN_ENDED on finish, was $decA"
        }

        // Run B's approval must still be active and preserved
        AgentBus.selectChat("chat-appr-B")
        check(AgentBus.outstandingApprovals() == 1) {
            "chat B outstanding approval must be preserved after finishing run A"
        }

        AgentBus.finish(runB)
        threadB.join(3000)
        resetAgentBus()
    }

    // -------------------------------------------------------------------------
    // 4. KeyedWorkQueue tests
    // -------------------------------------------------------------------------

    /**
     * 4.1: A job blocking on key A does not prevent key B from completing.
     */
    fun testKeyedWorkQueueIndependentKeysDoNotBlock() {
        val queue = KeyedWorkQueue()
        val holdALatch = CountDownLatch(1)
        val aStarted = CountDownLatch(1)
        val aDone = AtomicBoolean(false)
        val bDone = AtomicBoolean(false)

        val ticketA = queue.submit("key-A") {
            aStarted.countDown()
            holdALatch.await()
            aDone.set(true)
        }

        check(aStarted.await(5, TimeUnit.SECONDS)) { "A job failed to start" }

        val ticketB = queue.submit("key-B") {
            bDone.set(true)
        }

        ticketB.await(3000)
        check(bDone.get()) { "B job must complete while A is blocked" }
        check(!aDone.get()) { "A job should still be blocked" }

        holdALatch.countDown()
        ticketA.await(3000)
        check(aDone.get()) { "A job must complete after unblock" }
    }

    /**
     * 4.2: Same key jobs coalesce pending work to the latest action when coalesce=true.
     */
    fun testKeyedWorkQueueCoalescesPendingAndExecutesLatest() {
        val queue = KeyedWorkQueue()
        val holdFirstLatch = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val actionsRun = CopyOnWriteArrayList<String>()

        val ticket1 = queue.submit("coalesce-key", coalesce = true) {
            firstStarted.countDown()
            holdFirstLatch.await()
            actionsRun.add("action-1")
        }

        check(firstStarted.await(5, TimeUnit.SECONDS)) { "first job failed to start" }

        val ticket2 = queue.submit("coalesce-key", coalesce = true) {
            actionsRun.add("action-2")
        }
        val ticket3 = queue.submit("coalesce-key", coalesce = true) {
            actionsRun.add("action-3")
        }

        holdFirstLatch.countDown()
        ticket1.await(3000)
        ticket2.await(3000)
        ticket3.await(3000)

        check(actionsRun.contains("action-1")) { "action-1 must run" }
        check(actionsRun.contains("action-3")) { "latest action-3 must run" }
        check(!actionsRun.contains("action-2")) { "intermediate action-2 should be superseded by action-3" }
    }

    /**
     * 4.3: Non-coalescing barrier (coalesce = false) delete followed by write is NOT overwritten
     * and executes in serial order.
     */
    fun testKeyedWorkQueueBarrierDeleteFollowedByWritePreservesOrder() {
        val queue = KeyedWorkQueue()
        val holdActiveLatch = CountDownLatch(1)
        val activeStarted = CountDownLatch(1)
        val executionLog = CopyOnWriteArrayList<String>()

        // 1. Initial running job holds the lane
        val ticket0 = queue.submit("barrier-key", coalesce = true) {
            activeStarted.countDown()
            holdActiveLatch.await()
            executionLog.add("initial-run")
        }

        check(activeStarted.await(5, TimeUnit.SECONDS)) { "initial job failed to start" }

        // 2. Delete barrier submitted with coalesce = false
        val ticketDeleteBarrier = queue.submit("barrier-key", coalesce = false) {
            executionLog.add("delete-barrier")
        }

        // 3. Write job submitted afterwards with coalesce = true
        val ticketPostWrite = queue.submit("barrier-key", coalesce = true) {
            executionLog.add("post-barrier-write")
        }

        // Release the initial job and wait for all tickets
        holdActiveLatch.countDown()
        ticket0.await(3000)
        ticketDeleteBarrier.await(3000)
        ticketPostWrite.await(3000)

        // Both the barrier delete and subsequent write MUST execute in exact sequence without being coalesced away
        val expectedSequence = listOf("initial-run", "delete-barrier", "post-barrier-write")
        check(executionLog == expectedSequence) {
            "Barrier delete followed by write must execute serially without coalescing. Actual: $executionLog, Expected: $expectedSequence"
        }
    }

    /**
     * 4.4: ticket.await timeout retains the job in the queue without discarding it.
     */
    fun testKeyedWorkQueueAwaitTimeoutRetainsJob() {
        val queue = KeyedWorkQueue()
        val slowFinished = AtomicBoolean(false)

        val ticket = queue.submit("slow-key") {
            Thread.sleep(150)
            slowFinished.set(true)
        }

        var timedOut = false
        try {
            ticket.await(20)
        } catch (e: IllegalStateException) {
            if (e.message == "CHAT_SAVE_PENDING") {
                timedOut = true
            }
        }
        check(timedOut) { "ticket.await with short timeout must throw CHAT_SAVE_PENDING" }

        ticket.await(2000)
        check(slowFinished.get()) { "job must still complete and not be discarded after timeout" }
    }

    /**
     * 4.5: flush returns false while pending jobs are active and true when drained.
     */
    fun testKeyedWorkQueueFlush() {
        val queue = KeyedWorkQueue()
        val holdLatch = CountDownLatch(1)
        val started = CountDownLatch(1)

        queue.submit("flush-key") {
            started.countDown()
            holdLatch.await()
        }

        check(started.await(5, TimeUnit.SECONDS)) { "flush job failed to start" }

        val flushFalse = queue.flush(30)
        check(!flushFalse) { "flush must return false while lanes are active" }

        holdLatch.countDown()
        val flushTrue = queue.flush(3000)
        check(flushTrue) { "flush must return true after lanes are drained" }
    }

    // -------------------------------------------------------------------------
    // 5. MirrorLockRegistry concurrency & cancellation tests
    // -------------------------------------------------------------------------

    /**
     * 5.1: Holding project A does not block independent project B, and waiter on project A
     * exits cleanly on watchThread cancellation.
     */
    fun testMirrorLockRegistryWatchThreadCancelAndIndependentProject() {
        MirrorLockRegistry.clearForTests()

        val holdLockALatch = CountDownLatch(1)
        val lockAAcquired = CountDownLatch(1)

        val thread1 = Thread({
            MirrorLockRegistry.withProject("project-A") {
                lockAAcquired.countDown()
                holdLockALatch.await()
            }
        }, "mirror-test-holder-A")
        thread1.start()
        check(lockAAcquired.await(5, TimeUnit.SECONDS)) { "Thread 1 failed to acquire lock A" }

        val projectBFinished = AtomicBoolean(false)
        val thread3 = Thread({
            MirrorLockRegistry.withProject("project-B") {
                projectBFinished.set(true)
            }
        }, "mirror-test-independent-B")
        thread3.start()
        thread3.join(5000)
        check(projectBFinished.get()) { "Independent project B must complete while project A is held" }

        val token2 = CancellationToken()
        val waiterA2Interrupted = AtomicBoolean(false)
        val waiterA2Started = CountDownLatch(1)

        val thread2 = Thread({
            val reg = token2.watchThread(Thread.currentThread())
            waiterA2Started.countDown()
            try {
                MirrorLockRegistry.withProject("project-A") {
                }
            } catch (e: InterruptedException) {
                waiterA2Interrupted.set(true)
            }
        }, "mirror-test-waiter-A")
        thread2.start()
        check(waiterA2Started.await(5, TimeUnit.SECONDS)) { "Thread 2 failed to start" }
        Thread.sleep(50)

        token2.cancel()
        thread2.join(5000)
        check(!thread2.isAlive) { "Thread 2 must terminate" }
        check(waiterA2Interrupted.get()) { "Waiting thread on project-A must exit on watchThread cancellation" }

        holdLockALatch.countDown()
        thread1.join(5000)
        MirrorLockRegistry.clearForTests()
    }

    // -------------------------------------------------------------------------
    // 6. CompanionCommandExecutor active RPC cancellation test
    // -------------------------------------------------------------------------

    /**
     * 6.1: Active RPC cancellation sends TRANSACTION_CANCEL to unblock a stuck poll,
     * demonstrating cancellation does not depend on worker thread polling loop.
     */
    fun testCompanionCommandExecutorCancellation() {
        val binding = RuntimeBinding(
            RuntimeProviderId.VEGA_COMPANION,
            "0123456789abcdef01234567",
            "test-project",
            1L,
            "test-instance",
            protocolVersion = 2,
            layoutVersion = 1
        )
        val pollEnteredLatch = CountDownLatch(1)
        val cancelReceivedLatch = CountDownLatch(1)
        val cancelTransactionReceived = AtomicBoolean(false)

        val dispatcher = { code: Int, payload: String ->
            val req = JSONObject(payload)
            when (code) {
                CompanionRuntimeClient.TRANSACTION_PROBE -> JSONObject()
                    .put("ok", true)
                    .put("providerId", RuntimeProviderId.VEGA_COMPANION.name)
                    .put("runtimeInstanceId", "test-instance")
                    .put("capabilities", JSONObject().put("commandExecution", true))
                    .toString()
                CompanionRuntimeClient.TRANSACTION_START_COMMAND -> JSONObject()
                    .put("ok", true)
                    .put("executionId", "exec-cancel-test")
                    .toString()
                CompanionRuntimeClient.TRANSACTION_POLL_COMMAND -> {
                    pollEnteredLatch.countDown()
                    // Block in poll until cancellation transaction arrives
                    cancelReceivedLatch.await(5, TimeUnit.SECONDS)
                    JSONObject().put("ok", true).put("state", "RUNNING").toString()
                }
                CompanionRuntimeClient.TRANSACTION_CANCEL -> {
                    cancelTransactionReceived.set(true)
                    cancelReceivedLatch.countDown() // release the blocked poll
                    JSONObject().put("ok", true).put("cancelled", true).put("cleanupConfirmed", true).toString()
                }
                CompanionRuntimeClient.TRANSACTION_AWAIT_COMMAND -> JSONObject()
                    .put("ok", true)
                    .put("state", "TERMINATED")
                    .put("cancelled", true)
                    .put("cleanupConfirmed", true)
                    .toString()
                else -> JSONObject().put("ok", true).toString()
            }
        }

        val endpoint = ExternalCompanionEndpoint(
            inProcessDispatcher = dispatcher,
            inProcessStreamOpener = { _, _, _, _ -> ByteArrayInputStream(ByteArray(0)) }
        )
        val client = CompanionRuntimeClient(endpointOverride = endpoint)
        val executor = CompanionCommandExecutor(binding, client)
        val token = CancellationToken()

        val resultHolder = AtomicReference<RuntimeCommandResult?>(null)
        val worker = Thread({
            val spec = RuntimeCommandSpec("sleep", listOf("100"), timeoutMs = 10000)
            val res = executor.execute(binding, spec, token)
            resultHolder.set(res)
        }, "companion-cancel-worker")

        worker.start()
        check(pollEnteredLatch.await(5, TimeUnit.SECONDS)) { "Worker failed to enter poll" }
        check(!cancelTransactionReceived.get()) { "Cancel must not have happened yet" }

        // Actively cancel the token. onCancel triggers client.cancel asynchronously,
        // which sends TRANSACTION_CANCEL to the endpoint and unblocks poll
        token.cancel()

        worker.join(5000)
        check(!worker.isAlive) { "Worker thread must finish" }
        check(cancelTransactionReceived.get()) {
            "TRANSACTION_CANCEL must reach the endpoint independently of the blocked poll"
        }
        val res = resultHolder.get()
        check(res != null && res.cancelled) {
            "Command result must be marked cancelled, was $res"
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Running LifecycleRepairRegressionTests ===")

        testOrdinaryStartWithThrowingHost()
        println("PASS: testOrdinaryStartWithThrowingHost")

        testOrdinaryStartCountingHost()
        println("PASS: testOrdinaryStartCountingHost")

        testOrdinaryFinalAllowsReply()
        println("PASS: testOrdinaryFinalAllowsReply")

        testPassiveCodingSessionDoesNotProbeRuntime()
        println("PASS: testPassiveCodingSessionDoesNotProbeRuntime")

        testWatchThreadCancelInterruptsBlockingAwait()
        println("PASS: testWatchThreadCancelInterruptsBlockingAwait")

        testWatchThreadClosePreventsInterrupt()
        println("PASS: testWatchThreadClosePreventsInterrupt")

        testWatchThreadCancelPriorInterruptsImmediately()
        println("PASS: testWatchThreadCancelPriorInterruptsImmediately")

        testWatchThreadCancelIsNonBlocking()
        println("PASS: testWatchThreadCancelIsNonBlocking")

        testAgentBusConcurrentChatsBeginAndMarkRunning()
        println("PASS: testAgentBusConcurrentChatsBeginAndMarkRunning")

        testAgentBusSelectChatIndependentStop()
        println("PASS: testAgentBusSelectChatIndependentStop")

        testAgentBusBindWorkerMaintainsIsolation()
        println("PASS: testAgentBusBindWorkerMaintainsIsolation")

        testAgentBusFinishOldRunPreservesNewRunApprovals()
        println("PASS: testAgentBusFinishOldRunPreservesNewRunApprovals")

        testKeyedWorkQueueIndependentKeysDoNotBlock()
        println("PASS: testKeyedWorkQueueIndependentKeysDoNotBlock")

        testKeyedWorkQueueCoalescesPendingAndExecutesLatest()
        println("PASS: testKeyedWorkQueueCoalescesPendingAndExecutesLatest")

        testKeyedWorkQueueBarrierDeleteFollowedByWritePreservesOrder()
        println("PASS: testKeyedWorkQueueBarrierDeleteFollowedByWritePreservesOrder")

        testKeyedWorkQueueAwaitTimeoutRetainsJob()
        println("PASS: testKeyedWorkQueueAwaitTimeoutRetainsJob")

        testKeyedWorkQueueFlush()
        println("PASS: testKeyedWorkQueueFlush")

        testMirrorLockRegistryWatchThreadCancelAndIndependentProject()
        println("PASS: testMirrorLockRegistryWatchThreadCancelAndIndependentProject")

        testCompanionCommandExecutorCancellation()
        println("PASS: testCompanionCommandExecutorCancellation")

        println("ALL_LIFECYCLE_REPAIR_HOST_TESTS_PASS (19 checks)")
    }
}
