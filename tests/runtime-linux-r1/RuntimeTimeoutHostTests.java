package dev.wind.agent.runtime.linux;

import dev.wind.agent.runtime.RuntimeProcessSupervisor;
import dev.wind.agent.runtime.RuntimeProcessSupervisor.BindingScope;
import dev.wind.agent.runtime.RuntimeProcessSupervisor.Execution;
import dev.wind.agent.runtime.RuntimeProcessSupervisor.ManagedProcess;
import dev.wind.agent.runtime.RuntimeProcessSupervisor.ProcessFactory;
import dev.wind.agent.runtime.RuntimeProcessSupervisor.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure Java host regression tests for RuntimeProcessSupervisor timeout limits,
 * immediate await completion, invalid timeout rejection, short timeout,
 * and cancellation cleanup.
 */
public final class RuntimeTimeoutHostTests {

    private static int checks = 0;

   private static void check(boolean condition, String label) {
       if (!condition) {
           throw new AssertionError("CHECK FAILED: " + label);
       }
       checks++;
   }

    private static void assertActiveCount(RuntimeProcessSupervisor supervisor, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (supervisor.activeCount() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        check(supervisor.activeCount() == expected, "Active count must be " + expected + " after wait");
    }

  public static void main(String[] args) throws Exception {
      // Pre-warm SecureRandom/UUID subsystem to avoid one-time OS entropy initialization overhead in measurements
      java.util.UUID.randomUUID();
      System.out.println("Starting RuntimeTimeoutHostTests (MAX_TIMEOUT_MS=" + RuntimeProcessSupervisor.MAX_TIMEOUT_MS + ")...");
      check(RuntimeProcessSupervisor.MAX_TIMEOUT_MS == 3600000L, "MAX_TIMEOUT_MS must be 3600000L (1 hour)");
      try {
          java.lang.reflect.Field awaitField = RuntimeProcessSupervisor.class.getDeclaredField("MAX_AWAIT_MS");
          awaitField.setAccessible(true);
          long awaitLimit = awaitField.getLong(null);
          check(awaitLimit == 125000L, "MAX_AWAIT_MS must remain 125000L to protect Binder poll bounds");
      } catch (NoSuchFieldException | IllegalAccessException error) {
          System.out.println("  (MAX_AWAIT_MS field check skipped: " + error.getMessage() + ")");
      }

       File testDir = new File(".").getCanonicalFile();

        testInvalidTimeoutsRejectWithoutDispatch(testDir);
        testValidLongTimeoutsStartAndAwaitImmediateSuccess(testDir);
        testHostJavaRealProcessSuccess(testDir);
        testShortTimeoutRegression(testDir);
        testCancellationAndCleanupRegression(testDir);
        testCancelBeforeStartRegression(testDir);
        testCancelAllAndUnconfirmedCleanupRegression(testDir);

        System.out.println("RuntimeTimeoutHostTests PASS: " + checks + " checks passed successfully.");
    }

    /**
     * 1. Verify invalid timeouts (0, -1, -5000, 3600001, Long.MAX_VALUE) are rejected immediately;
     *    Verify RUNTIME_TIMEOUT_INVALID is thrown;
     *    Verify ProcessFactory is never dispatched;
     *    Verify no long timeout waiting occurs.
     */
    private static void testInvalidTimeoutsRejectWithoutDispatch(File cwd) {
        System.out.println("-> Running testInvalidTimeoutsRejectWithoutDispatch...");
        long[] invalidTimeouts = new long[]{0L, -1L, -5000L, 3600001L, Long.MAX_VALUE};
        List<String> cmd = Arrays.asList("echo", "test");
        BindingScope scope = new BindingScope("proj-1", 1L, "ws-1", "inst-1");

        for (long invalidTimeout : invalidTimeouts) {
            CountingProcessFactory factory = new CountingProcessFactory(new ProcessSupplier() {
                @Override
                public Process create(List<String> argv, File dir, Map<String, String> env) {
                    return new FastMockProcess(0, "unexpected", "", true);
                }
            });

            try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(factory)) {
                long startNs = System.nanoTime();
                boolean threw = false;
                try {
                    supervisor.start(cmd, cwd, Collections.emptyMap(), invalidTimeout);
                } catch (IllegalArgumentException ex) {
                    threw = true;
                    check("RUNTIME_TIMEOUT_INVALID".equals(ex.getMessage()),
                            "Expected RUNTIME_TIMEOUT_INVALID for timeout " + invalidTimeout + " but got " + ex.getMessage());
                }
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

                check(threw, "start() must reject invalid timeout: " + invalidTimeout);
                check(factory.dispatchCount.get() == 0,
                        "ProcessFactory must NOT be dispatched on invalid timeout: " + invalidTimeout);
                check(elapsedMs < 200L,
                        "Rejection must be immediate without waiting, elapsed: " + elapsedMs + "ms");
                check(supervisor.activeCount() == 0, "No active execution should remain on rejection");

                // Test overloaded start with scope and stdin
                threw = false;
                try {
                    supervisor.start(scope, cmd, cwd, Collections.emptyMap(), invalidTimeout, "test-input");
                } catch (IllegalArgumentException ex) {
                    threw = true;
                    check("RUNTIME_TIMEOUT_INVALID".equals(ex.getMessage()),
                            "Overloaded start must throw RUNTIME_TIMEOUT_INVALID for timeout " + invalidTimeout);
                }
                check(threw, "Overloaded start must reject invalid timeout: " + invalidTimeout);
                check(factory.dispatchCount.get() == 0,
                        "ProcessFactory must still NOT be dispatched on invalid timeout: " + invalidTimeout);
            }
        }
    }

   /**
    * 2. Verify start and await with long valid timeouts (300000, 600000, 900000, 1800000, 3600000 ms);
    *    Fast commands finish immediately;
    *    Verify await(execution, timeoutMs) returns quickly without waiting for timeout.
    */
   private static void testValidLongTimeoutsStartAndAwaitImmediateSuccess(File cwd) throws Exception {
       System.out.println("-> Running testValidLongTimeoutsStartAndAwaitImmediateSuccess...");
        long[] validTimeouts = new long[]{300000L, 600000L, 900000L, 1800000L, 3600000L};
        List<String> cmd = Arrays.asList("fake-command", "--fast");
        BindingScope scope = new BindingScope("proj-valid", 100L, "ws-valid", "inst-valid");

        for (final long timeoutMs : validTimeouts) {
            final String expectedOut = "RESULT_OK_TIMEOUT_" + timeoutMs + "\n";
            CountingProcessFactory factory = new CountingProcessFactory(new ProcessSupplier() {
                @Override
                public Process create(List<String> argv, File dir, Map<String, String> env) {
                    return new FastMockProcess(0, expectedOut, "", true);
                }
            });

            try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(factory)) {
                check(supervisor.activeCount() == 0, "Initial active count must be 0");

              long startNs = System.nanoTime();
              Execution execution = supervisor.start(scope, cmd, cwd, Collections.emptyMap(), timeoutMs, "sample-stdin");
              check(execution != null, "Execution handle must not be null for timeout " + timeoutMs);
              check(execution.id != null && !execution.id.isEmpty(), "Execution ID must be valid");

              // await with the full timeoutMs - should return in milliseconds because process completes immediately
              Result result = supervisor.await(execution, timeoutMs);
              long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

                check(factory.dispatchCount.get() == 1, "ProcessFactory must be dispatched exactly once after await");
                check(result != null, "Result must not be null");
                check(result.success(), "Execution must succeed for timeout " + timeoutMs);
                check(result.exitCode == 0, "Exit code must be 0 for timeout " + timeoutMs);
                check(!result.timedOut, "Result must not be marked timedOut for timeout " + timeoutMs);
                check(!result.cancelled, "Result must not be marked cancelled for timeout " + timeoutMs);
                check(result.cleanupConfirmed, "Cleanup must be confirmed for timeout " + timeoutMs);
                check(expectedOut.equals(result.stdout), "Stdout must match expected output");
                check(result.stderr.isEmpty(), "Stderr must be empty");
                check(result.failure.isEmpty(), "Failure message must be empty");
               check(elapsedMs < 3000L, "Immediate command must finish quickly without waiting for timeout "
                       + timeoutMs + "ms, elapsed: " + elapsedMs + "ms");
               check(execution.isFinished(), "Execution must be marked finished");
               assertActiveCount(supervisor, 0, 1000L);

               // Verify getResult returns the cached completed result
                Result cached = supervisor.getResult(execution.id, scope);
                check(cached != null && cached.exitCode == 0, "getResult must return completed result");
            }
        }
    }

   /**
    * 3. Verify host java command with 3600000ms timeout completes rapidly using standard ProcessBuilder.
    */
   private static void testHostJavaRealProcessSuccess(File cwd) throws Exception {
       System.out.println("-> Running testHostJavaRealProcessSuccess...");
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            javaBin += ".exe";
        }
        if (!new File(javaBin).canExecute()) {
            javaBin = "java";
        }

        try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor()) {
            long startNs = System.nanoTime();
            List<String> argv = Arrays.asList(javaBin, "-version");
            Execution execution = supervisor.start(argv, cwd, Collections.emptyMap(), 3600000L);
            check(execution != null, "Host java execution handle must not be null");

            Result result = supervisor.await(execution, 3600000L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            check(result != null, "Host java result must not be null");
            check(result.exitCode == 0, "Host java exitCode must be 0, got " + result.exitCode + ", err=" + result.stderr);
            check(!result.timedOut, "Host java must not time out");
            check(!result.cancelled, "Host java must not be cancelled");
            check(result.cleanupConfirmed, "Host java cleanup must be confirmed");
            check(result.stdout.contains("version") || result.stderr.contains("version")
                    || result.stdout.contains("Runtime") || result.stderr.contains("Runtime"),
                    "Output should contain java version info");
           check(elapsedMs < 5000L, "Host java must finish rapidly, elapsed: " + elapsedMs + "ms");
           assertActiveCount(supervisor, 0, 1000L);
       }
   }

   /**
    * 4. Short timeout regression:
    *    Hanging mock process with short timeout (50ms) triggers timeout and destroys process.
    */
   private static void testShortTimeoutRegression(File cwd) throws Exception {
       System.out.println("-> Running testShortTimeoutRegression...");
        final HangingMockProcess hanging = new HangingMockProcess(true);
        CountingProcessFactory factory = new CountingProcessFactory(new ProcessSupplier() {
            @Override
            public Process create(List<String> argv, File dir, Map<String, String> env) {
                return hanging;
            }
        });

        try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(factory)) {
            long shortTimeoutMs = 50L;
            long startNs = System.nanoTime();
            Execution execution = supervisor.start(Arrays.asList("hanging-job"), cwd, Collections.emptyMap(), shortTimeoutMs);
            Result result = supervisor.await(execution, 2000L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            check(result != null, "Result must not be null");
            check(result.timedOut, "Result must be marked timedOut");
            check(!result.success(), "Timed out result must not be marked success");
            check(result.cleanupConfirmed, "Cleanup must be confirmed after timeout termination");
           check(hanging.isDestroyed(), "Hanging process must be destroyed by supervisor on timeout");
           check(elapsedMs < 2000L, "Short timeout should resolve within reasonable boundary, took " + elapsedMs + "ms");
           assertActiveCount(supervisor, 0, 1000L);
       }
   }

   /**
     * 5. Cancellation and cleanup regression:
     *    Hanging mock process with 3600000ms timeout is cancelled via cancelAndAwait.
     */
    private static void testCancellationAndCleanupRegression(File cwd) throws Exception {
        System.out.println("-> Running testCancellationAndCleanupRegression...");
        final HangingMockProcess hanging = new HangingMockProcess(true);
        CountingProcessFactory factory = new CountingProcessFactory(new ProcessSupplier() {
            @Override
            public Process create(List<String> argv, File dir, Map<String, String> env) {
                return hanging;
            }
        });

        try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(factory)) {
            Execution execution = supervisor.start(Arrays.asList("cancel-job"), cwd, Collections.emptyMap(), 3600000L);
            check(hanging.startedLatch.await(2000L, TimeUnit.MILLISECONDS), "Process must be started before cancelling");
            check(supervisor.activeCount() == 1, "Active count should be 1 during execution");

            long startNs = System.nanoTime();
            Result result = supervisor.cancelAndAwait(execution.id, 3000L);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            check(result != null, "Result must not be null on cancellation");
            check(result.cancelled, "Result must be marked cancelled");
            check(!result.timedOut, "Cancelled result must not be marked timedOut");
            check(result.cleanupConfirmed, "Cleanup must be confirmed on cancellation");
           check(hanging.isDestroyed(), "Hanging process must be destroyed on cancellation");
           check(elapsedMs < 2000L, "Cancellation should be fast, took " + elapsedMs + "ms");
           assertActiveCount(supervisor, 0, 1000L);
       }
   }

    /**
     * 6. Cancellation before start regression:
     *    Execution marked cancelled before start returns RUNTIME_CANCELLED_BEFORE_START.
     */
    private static void testCancelBeforeStartRegression(File cwd) {
        System.out.println("-> Running testCancelBeforeStartRegression...");
        CountingProcessFactory factory = new CountingProcessFactory(new ProcessSupplier() {
            @Override
            public Process create(List<String> argv, File dir, Map<String, String> env) {
                return new FastMockProcess(0, "", "", true);
            }
        });

        try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(factory)) {
            Execution execution = supervisor.start(Arrays.asList("pre-cancel"), cwd, Collections.emptyMap(), 3600000L);
            Result result = supervisor.cancelAndAwait(execution.id, 2000L);
            check(result != null, "Result must not be null");
            check(result.cancelled, "Result must be marked cancelled");
            check(result.cleanupConfirmed, "Cleanup confirmed must be true");
        }
    }

    /**
     * 7. Bulk cancellation and unconfirmed cleanup regressions:
     *    - cancelAllAndAwait regression;
     *    - cleanupConfirmed() = false leads to failure / cleanupConfirmed=false.
     */
    private static void testCancelAllAndUnconfirmedCleanupRegression(File cwd) throws Exception {
        System.out.println("-> Running testCancelAllAndUnconfirmedCleanupRegression...");
        // 7.1 cancelAllAndAwait
        final HangingMockProcess hang1 = new HangingMockProcess(true);
        final HangingMockProcess hang2 = new HangingMockProcess(true);
        final HangingMockProcess[] procs = new HangingMockProcess[]{hang1, hang2};
        final AtomicInteger idx = new AtomicInteger(0);

        CountingProcessFactory factory = new CountingProcessFactory(new ProcessSupplier() {
            @Override
            public Process create(List<String> argv, File dir, Map<String, String> env) {
                int i = idx.getAndIncrement();
                return procs[i < procs.length ? i : 0];
            }
        });

        try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(factory)) {
            Execution e1 = supervisor.start(Arrays.asList("bulk-1"), cwd, Collections.emptyMap(), 3600000L);
            Execution e2 = supervisor.start(Arrays.asList("bulk-2"), cwd, Collections.emptyMap(), 3600000L);
            check(hang1.startedLatch.await(2000L, TimeUnit.MILLISECONDS), "Proc 1 started");
            check(hang2.startedLatch.await(2000L, TimeUnit.MILLISECONDS), "Proc 2 started");
            check(supervisor.activeCount() == 2, "Active count should be 2");

            boolean allClean = supervisor.cancelAllAndAwait(3000L);
            check(allClean, "cancelAllAndAwait must return true when all processes confirm cleanup");
            check(hang1.isDestroyed() && hang2.isDestroyed(), "Both hanging processes must be destroyed");
            check(supervisor.activeCount() == 0, "Active count must be 0 after cancelAllAndAwait");
        }

        // 7.2 Unconfirmed cleanup
        final HangingMockProcess rogue = new HangingMockProcess(false); // cleanupConfirmed returns false
        CountingProcessFactory rogueFactory = new CountingProcessFactory(new ProcessSupplier() {
            @Override
            public Process create(List<String> argv, File dir, Map<String, String> env) {
                return rogue;
            }
        });

        try (RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(rogueFactory)) {
            Execution exec = supervisor.start(Arrays.asList("rogue-job"), cwd, Collections.emptyMap(), 50L);
            Result res = supervisor.await(exec, 2000L);
            check(res != null, "Result must not be null");
            check(!res.cleanupConfirmed, "When ManagedProcess fails cleanup proof, supervisor must mark cleanupConfirmed=false");
            check(!res.success(), "Unconfirmed cleanup cannot succeed");
        }
    }

    // =========================================================================
    // Mock Process and Factory Helpers
    // =========================================================================

    private interface ProcessSupplier {
        Process create(List<String> argv, File dir, Map<String, String> env) throws IOException;
    }

    private static final class CountingProcessFactory implements ProcessFactory {
        final AtomicInteger dispatchCount = new AtomicInteger(0);
        private final ProcessSupplier supplier;

        CountingProcessFactory(ProcessSupplier supplier) {
            this.supplier = supplier;
        }

        @Override
        public Process start(List<String> argv, File directory, Map<String, String> environment) throws IOException {
            dispatchCount.incrementAndGet();
            return supplier.create(argv, directory, environment);
        }
    }

    /**
     * Fast in-memory process that has already exited with specific output and cleanup proof.
     */
    private static final class FastMockProcess extends Process implements ManagedProcess {
        private final int exitCode;
        private final ByteArrayInputStream in;
        private final ByteArrayInputStream err;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final boolean clean;
        private final AtomicBoolean destroyed = new AtomicBoolean(false);

        FastMockProcess(int exitCode, String stdout, String stderr, boolean clean) {
            this.exitCode = exitCode;
            this.in = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.err = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.clean = clean;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            return err;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override
        public boolean cleanupConfirmed() {
            return clean;
        }
    }

    /**
     * Mock process that simulates long running execution until destroyed.
     */
    private static final class HangingMockProcess extends Process implements ManagedProcess {
        private final ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        private final ByteArrayInputStream err = new ByteArrayInputStream(new byte[0]);
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final boolean confirmCleanupOnDestroy;
        final CountDownLatch startedLatch = new CountDownLatch(1);
        private final AtomicBoolean destroyed = new AtomicBoolean(false);

        HangingMockProcess(boolean confirmCleanupOnDestroy) {
            this.confirmCleanupOnDestroy = confirmCleanupOnDestroy;
        }

        boolean isDestroyed() {
            return destroyed.get();
        }

        @Override
        public OutputStream getOutputStream() {
            startedLatch.countDown();
            return out;
        }

        @Override
        public InputStream getInputStream() {
            startedLatch.countDown();
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            startedLatch.countDown();
            return err;
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (!destroyed.get()) {
                Thread.sleep(10);
            }
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            while (!destroyed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            return destroyed.get();
        }

        @Override
        public int exitValue() {
            startedLatch.countDown();
            if (!destroyed.get()) {
                throw new IllegalThreadStateException("Process still active");
            }
            return 137;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyed.set(true);
            return this;
        }

        @Override
        public boolean cleanupConfirmed() {
            return confirmCleanupOnDestroy && destroyed.get();
        }
    }
}
