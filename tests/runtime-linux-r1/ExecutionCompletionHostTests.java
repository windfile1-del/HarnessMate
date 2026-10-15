package dev.wind.agent.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Death-watch cleanup registration must not leak or miss completion races. */
public final class ExecutionCompletionHostTests {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 100; i++) {
            RuntimeProcessSupervisor.Execution execution = new RuntimeProcessSupervisor.Execution("test", null);
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            Thread register = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                execution.onFinished(calls::incrementAndGet);
            });
            register.start(); go.countDown();
            execution.finish(new RuntimeProcessSupervisor.Result("test", 0, "", "", 1, false, false, true, ""));
            register.join();
            if (calls.get() != 1) throw new AssertionError("completion registration race: " + calls);
            execution.onFinished(calls::incrementAndGet);
            if (calls.get() != 2) throw new AssertionError("late completion registration lost");
            execution.onFinished(() -> { throw new IllegalStateException("cleanup callback failed"); });
        }
        System.out.println("EXECUTION_COMPLETION_PASS (100 races and late registrations)");
    }
}
