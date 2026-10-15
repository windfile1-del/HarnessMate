# Run Lifecycle & Cancellation Repair Host Test Suite

This directory contains the host-side regression test suites and test runners for the Android automation runtime lifecycle, cancellation interruption, per-chat concurrency, and persistence queue repairs.

> **Verification Boundary Notice**:
> All tests in this directory are executed on the host JVM using compiled production classes and lightweight host-side fixtures (`host-stubs`). These fixtures validate threading, locking, lifecycle state machines, and filesystem write behavior only. They do not constitute Android physical device, OEM power management, VPN, or SAF runtime acceptance.

---

## Test Suites Summary

### 1. `LifecycleRepairRegressionTests.kt` (19 Core Checks)
- **Final execution**: PASS, EXIT 0, 19 checks. Main-agent run: `.build-run-lifecycle/lifecycle-suite-final.log`; summary: `.build-run-lifecycle-repair/summary.json`.
- **Source File**: `tests/run-lifecycle-repair/LifecycleRepairRegressionTests.kt`
- **Runner**: `tests/run-lifecycle-repair/run-lifecycle-tests.cjs`
- **Checks Covered**:
  1. `testOrdinaryStartWithThrowingHost`: Ordinary `CodingTaskRuntime.start` does not probe `host.mirrorDigest` and remains inactive.
  2. `testOrdinaryStartCountingHost`: Start invocation leaves mirror digest call count strictly at 0.
  3. `testOrdinaryFinalAllowsReply`: Inactive ordinary task allows reply (`FinishDecision.Allow`) and finishes with `TaskStatus.NO_CHANGES` without reading mirror.
  4. `testPassiveCodingSessionDoesNotProbeRuntime`: Passive `CodingSession` (null Context, throwing mirror) does not probe runtime on construct, `finishDecision`, or `finishRun`.
  5. `testWatchThreadCancelInterruptsBlockingAwait`: `CancellationToken.watchThread` actively interrupts threads blocked in `CountDownLatch.await` upon `token.cancel()`.
  6. `testWatchThreadClosePreventsInterrupt`: Calling `Registration.close()` removes listener so subsequent `token.cancel()` will not interrupt the thread.
  7. `testWatchThreadCancelPriorInterruptsImmediately`: Calling `watchThread` on an already-cancelled token immediately interrupts the target thread.
  8. `testWatchThreadCancelIsNonBlocking`: `token.cancel()` returns immediately (<100ms) regardless of target thread state.
  9. `testAgentBusConcurrentChatsBeginAndMarkRunning`: Two distinct conversations can concurrently claim slots via `beginStarting` and advance to `RUNNING`.
  10. `testAgentBusSelectChatIndependentStop`: Cancelling the UI-selected conversation does not interrupt or alter another running conversation.
  11. `testAgentBusBindWorkerMaintainsIsolation`: Background worker thread bound via `bindWorker(runId)` retains independent `token` and `taskId` even when UI switches `selectChat`.
  12. `testAgentBusFinishOldRunPreservesNewRunApprovals`: Finishing an old conversation run drains only its own approvals and preserves pending approvals of new runs.
  13. `testKeyedWorkQueueIndependentKeysDoNotBlock`: A blocked job on key A does not delay execution or completion of jobs on key B.
  14. `testKeyedWorkQueueCoalescesPendingAndExecutesLatest`: Same-key jobs with `coalesce = true` collapse intermediate jobs and execute the latest action.
  15. `testKeyedWorkQueueBarrierDeleteFollowedByWritePreservesOrder`: Non-coalescing barrier (`coalesce = false`) delete followed by write preserves strict serial execution without being overwritten.
  16. `testKeyedWorkQueueAwaitTimeoutRetainsJob`: `ticket.await` timeout throws `CHAT_SAVE_PENDING` while keeping the job alive to complete on disk.
  17. `testKeyedWorkQueueFlush`: `flush` returns `false` while lanes have active work and `true` once all lanes drain.
  18. `testMirrorLockRegistryWatchThreadCancelAndIndependentProject`: Holding project A lock does not block independent project B; waiter on project A unblocks cleanly on `watchThread` cancellation.
  19. `testCompanionCommandExecutorCancellation`: Active RPC cancellation sends `TRANSACTION_CANCEL` to unblock a stuck poll, independent of worker polling loops.

### 2. `ChatStoreIntegrationTests.kt` (1 Suite / 6 Checks)
- **Source File**: `tests/run-lifecycle-repair/ChatStoreIntegrationTests.kt`
- **Runner**: `tests/run-lifecycle-repair/run-chatstore-integration.cjs`
- **Execution Log**: `.build-run-lifecycle-repair/run-chatstore-integration.log`
- **Summary Artifact**: `.build-run-lifecycle-repair/chatstore-summary.json` (`status: HOST_TESTS_PASS`, `exitCode: 0`)
- **Checks Covered**:
  1. Reflective access to `ChatStore.writes` (`KeyedWorkQueue`).
  2. Injecting a blocking latch into conversation A's lane simulates disk saturation.
  3. Main thread (`Looper.setAsMainThread()`) `store.saveNow(A)` returns in <500ms without blocking, and `store.load(A)` immediately returns the in-memory live pending object.
  4. Worker thread `store.saveNow(B)` independently completes and writes real JSON to disk while A remains blocked.
  5. Releasing conversation A's latch and invoking `ChatStore.flushPendingWrites` commits latest messages to real JSON on disk.
  6. Non-coalescing `store.delete(A)` removes the file from disk, and subsequent `load(A)` returns null without resurrection.

### 3. `CodingLoopTests.kt` (198 Assertions)
- **Source File**: `tests/dev/wind/agent/CodingLoopTests.kt`
- **Compile Log**: `.build-run-lifecycle-repair/codingloop-compile.log` (`STATUS: 0`)
- **Execution Log**: `.build-run-lifecycle-repair/codingloop-run.log` (`STATUS: 0`)
- **Summary**: `PASS CodingLoopTests: 198 assertions`
- **Key Lifecycle Refinements**:
  - `anOrdinaryCustomizationOfAMirrorEditIsRecorded` includes `beforeTool("shell_exec")` to establish the baseline before `afterTool`.
  - Test helper `beginCoding` explicitly invokes `captureSourceBaseline()` to bootstrap the baseline for coding test fixtures.
  - All 198 assertions for no-op detection, mutation tracking, verification gating, and cancellation pass under the clean classpath.

### 4. `CodingLifecycleSidecarTests.kt` (4 Core Cases)
- **Source File**: `tests/run-lifecycle-repair/CodingLifecycleSidecarTests.kt`
- **Runner**: `tests/run-lifecycle-repair/run-sidecar.cjs`
- **Execution Log**: `.build-run-lifecycle-repair/sidecar-run.log` (`EXIT: 0`)
- **Summary Artifact**: `.build-run-lifecycle-repair/sidecar-summary.json` (`status: HOST_TESTS_PASS`, `casesPassed: 4`)
- **Cases Covered**:
  1. `testBeforeToolEditNoOpFailureDoesNotRecordMutation`: `beforeTool("execution_edit_file")` captures baseline; failed edit without mirror change records 0 mutations.
  2. `testBeforeToolEditPartialChangeStillRecordsMutation`: `beforeTool("execution_edit_file")` captures baseline; failed edit with partial mirror change records mutation and advances epoch.
  3. `testCancellationPreventsMirrorDigestProbesOnFinish`: Task cancelled with an active `VerificationStamp` strictly bypasses `host.mirrorDigest` in `verificationValid`, `requestFinish`, `finishRun`, and `beforeTool` (0 mirror probes after cancellation).
  4. `testPassiveCodingSessionOnCancelledRuntimeDoesNotProbeMirror`: Passive `CodingSession` on cancelled runtime returns null on finish without probing mirror.

---

## Host Stubs Architecture

Located under `tests/run-lifecycle-repair/host-stubs/`:
- `android/os/Looper.java`: Thread-local simulated `Looper` supporting `setAsMainThread()` / `clearMainThread()` for main-thread vs worker-thread execution branching.
- `android/util/Log.java`: Non-throwing static logger for host execution.
- `android/content/Context.java` & `ContextWrapper.java`: Minimal filesystem Context supporting `getFilesDir()` and `getApplicationContext()` for file store operations.

## Classpath Precedence Rule

Runners enforce the clean classpath ordering:
```
[test-classes, json, host-android, final-classes (if present), production-check/java, production-check/classes, android.jar, libraries]
```
Historical incremental delta folders (`.build-run-lifecycle/delta`, `ui-delta`, `cancellation-delta`) are excluded to prevent cross-module symbol and smartcast conflicts.
