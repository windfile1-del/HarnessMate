package dev.wind.agent.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android-independent process supervisor used by the companion and JVM tests.
 * The caller gets a result only after the process (and discoverable descendants)
 * have exited, or after the cleanup proof is explicitly marked false.
 */
public final class RuntimeProcessSupervisor implements AutoCloseable {
    /** Native runtimes supply a Process adapter without replacing binding/handle ownership. */
    public interface ProcessFactory {
        Process start(List<String> argv, File directory, Map<String, String> environment) throws IOException;
    }
    public interface ManagedProcess {
        boolean cleanupConfirmed();
    }
    private final ProcessFactory processFactory;
    private final File logDirectory;
    private boolean closed;
    private final java.util.ArrayDeque<String> retainedLogs = new java.util.ArrayDeque<>();
    public RuntimeProcessSupervisor() {
        this(new ProcessFactory() {
            public Process start(List<String> argv, File directory, Map<String, String> environment) throws IOException {
                ProcessBuilder builder = new ProcessBuilder(argv);
                builder.directory(directory);
                builder.environment().clear();
                builder.environment().putAll(environment);
                return builder.start();
            }
        });
    }
    public RuntimeProcessSupervisor(ProcessFactory factory) {
        this(factory, null);
    }
    public RuntimeProcessSupervisor(ProcessFactory factory, File logs) {
        if (factory == null) throw new IllegalArgumentException("RUNTIME_PROCESS_FACTORY_INVALID");
        processFactory = factory;
        logDirectory = logs;
        if (logs != null) {
            try {
                if (!logs.getCanonicalFile().equals(logs.getAbsoluteFile()))
                    throw new IOException("RUNTIME_LOG_DIRECTORY_INVALID");
                if (!logs.isDirectory() && !logs.mkdirs()) throw new IOException("RUNTIME_LOG_UNAVAILABLE");
                File[] stale = logs.listFiles();
                if (stale == null) throw new IOException("RUNTIME_LOG_UNAVAILABLE");
                // Handles are process-local. After restart old log files no
                // longer have a binding owner and must not be exposed again.
                for (File file : stale) if (file.getName().matches("[0-9a-f-]{36}\\.(stdout|stderr)")
                        && !file.isDirectory() && !file.delete()) throw new IOException("RUNTIME_LOG_RECOVERY_FAILED");
            } catch (IOException failure) { throw new IllegalStateException(failure.getMessage(), failure); }
        }
    }
    // Match ValidationCommand's longest supported check (one hour). This is
    // the requested execution deadline, not a Binder poll/wait duration.
    public static final long MAX_TIMEOUT_MS = 3600000L;
    private static final long MAX_AWAIT_MS = 125000L;
    public static final int MAX_ARGUMENTS = 128;
    public static final int MAX_ARGUMENT_BYTES = 8192;
    public static final int MAX_OUTPUT_BYTES = 64 * 1024;
    public static final int MAX_LOG_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ACTIVE_EXECUTIONS = 4;
    public static final int MAX_RETAINED_LOG_EXECUTIONS = 4;
    // At most four active and four retained pairs of bounded 16 MiB files.
    public static final long MAX_LOG_CACHE_BYTES = 256L * 1024 * 1024;

    public static final class BindingScope {
        public final String projectId;
        public final long rootGeneration;
        public final String workspaceId;
        public final String runtimeInstanceId;

        public BindingScope(String projectId, long rootGeneration,
                            String workspaceId, String runtimeInstanceId) {
            this.projectId = projectId;
            this.rootGeneration = rootGeneration;
            this.workspaceId = workspaceId;
            this.runtimeInstanceId = runtimeInstanceId;
        }

        public boolean matches(String projectId, long rootGeneration,
                               String workspaceId, String runtimeInstanceId) {
            return this.projectId != null && this.projectId.equals(projectId)
                    && this.rootGeneration == rootGeneration
                    && this.workspaceId != null && this.workspaceId.equals(workspaceId)
                    && this.runtimeInstanceId != null && this.runtimeInstanceId.equals(runtimeInstanceId);
        }
    }

    public static final class Result {
        public final String executionId;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long durationMs;
        public final boolean timedOut;
        public final boolean cancelled;
        public final boolean cleanupConfirmed;
        public final String failure;
        public final boolean outputTruncated;

        Result(String id, int code, String out, String err, long duration,
               boolean timeout, boolean cancel, boolean clean, String problem) {
            this(id, code, out, err, duration, timeout, cancel, clean, problem, false);
        }
        Result(String id, int code, String out, String err, long duration,
               boolean timeout, boolean cancel, boolean clean, String problem, boolean truncated) {
            executionId = id;
            exitCode = code;
            stdout = out;
            stderr = err;
            durationMs = duration;
            timedOut = timeout;
            cancelled = cancel;
            cleanupConfirmed = clean;
            failure = problem;
            outputTruncated = truncated;
        }

        public boolean success() {
            return exitCode == 0 && !timedOut && !cancelled
                    && cleanupConfirmed && failure.length() == 0;
        }
    }

    public static final class Execution {
        public final String id;
        public final BindingScope scope;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch finished = new CountDownLatch(1);
        final java.util.Set<Object> trackedDescendants =
                java.util.Collections.synchronizedSet(new java.util.HashSet<Object>());
        private volatile Process process;
        private volatile Result result;
        private final java.util.List<Runnable> completionListeners = new java.util.ArrayList<>();

        Execution(String value, BindingScope scope) {
            id = value;
            this.scope = scope;
        }

        void markCancelled() {
            cancelled.set(true);
            Process current = process;
            if (current != null) terminateProcessTree(current, trackedDescendants);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        public boolean isFinished() {
            return finished.getCount() == 0;
        }

        public Result getResult() {
            return result;
        }

        public void onFinished(Runnable action) {
            synchronized (completionListeners) {
                if (!isFinished()) { completionListeners.add(action); return; }
            }
            try { action.run(); } catch (Throwable ignored) {}
        }

        void finish(Result value) {
            java.util.List<Runnable> actions;
            synchronized (completionListeners) {
                result = value;
                finished.countDown();
                actions = new java.util.ArrayList<>(completionListeners);
                completionListeners.clear();
            }
            for (Runnable action : actions) { try { action.run(); } catch (Throwable ignored) {} }
        }
    }

    private final ConcurrentHashMap<String, Execution> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Result> recentCompleted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Execution> recentExecutions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BindingScope> executionScopes = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "vega-runtime-process");
            thread.setDaemon(true);
            return thread;
        }
    });

    public Execution start(List<String> argv, File workingDirectory,
                           Map<String, String> environment, long timeoutMs) {
        return start(null, argv, workingDirectory, environment, timeoutMs);
    }

    public Execution start(BindingScope scope, List<String> argv, File workingDirectory,
                           Map<String, String> environment, long timeoutMs) {
        return start(scope, argv, workingDirectory, environment, timeoutMs, null);
    }

    public synchronized Execution start(BindingScope scope, List<String> argv, File workingDirectory,
                           Map<String, String> environment, long timeoutMs, String stdin) {
        if (closed) throw new IllegalStateException("RUNTIME_SUPERVISOR_CLOSED");
        if (active.size() >= MAX_ACTIVE_EXECUTIONS) throw new IllegalStateException("RUNTIME_EXECUTION_LIMIT");
        if (logDirectory != null) {
            long reserved = (active.size() + 1L) * 2 * MAX_LOG_BYTES;
            File[] files = logDirectory.listFiles();
            if (files == null) throw new IllegalStateException("RUNTIME_LOG_UNAVAILABLE");
            for (File file : files) {
                String name = file.getName();
                if (name.matches("[0-9a-f-]{36}\\.(stdout|stderr)") && !active.containsKey(name.substring(0, 36)))
                    reserved += file.length();
            }
            if (reserved > MAX_LOG_CACHE_BYTES) throw new IllegalStateException("RUNTIME_LOG_CACHE_LIMIT");
        }
        validate(argv, workingDirectory, environment, timeoutMs);
        final byte[] input = stdin == null ? new byte[0] : stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (input.length > MAX_OUTPUT_BYTES) throw new IllegalArgumentException("RUNTIME_STDIN_LIMIT");
        final List<String> command = Collections.unmodifiableList(new ArrayList<>(argv));
        final File directory;
        try {
            directory = workingDirectory.getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalArgumentException("RUNTIME_WORKSPACE_INVALID", error);
        }
        final Map<String, String> env = Collections.unmodifiableMap(
                new HashMap<>(environment == null
                        ? Collections.<String, String>emptyMap() : environment));
        final Execution execution = new Execution(UUID.randomUUID().toString(), scope);
        if (scope != null) executionScopes.put(execution.id, scope);
        if (active.putIfAbsent(execution.id, execution) != null) {
            if (scope != null) executionScopes.remove(execution.id);
            throw new IllegalStateException("RUNTIME_EXECUTION_ID_COLLISION");
        }
        workers.execute(new Runnable() {
            @Override public void run() {
                runProcess(execution, command, directory, env, timeoutMs, input);
            }
        });
        return execution;
    }

    public Result await(Execution execution, long waitMs) {
        if (execution == null) throw new IllegalArgumentException("RUNTIME_EXECUTION_UNKNOWN");
        long bounded = Math.max(1L, Math.min(waitMs, MAX_AWAIT_MS));
        try {
            if (!execution.finished.await(bounded, TimeUnit.MILLISECONDS)) {
                execution.markCancelled();
                execution.finished.await(2500L, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            execution.markCancelled();
            try {
                execution.finished.await(2500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Result result = execution.result;
        if (result != null) return result;
        return new Result(execution.id, -1, "", "", bounded, false,
                execution.isCancelled(), false, "RUNTIME_CLEANUP_UNCONFIRMED");
    }

    public Execution getExecution(String executionId, BindingScope expectedScope) {
        BindingScope scope = executionScopes.get(executionId);
        if (scope != null) {
            if (expectedScope == null || !expectedScope.matches(scope.projectId, scope.rootGeneration,
                    scope.workspaceId, scope.runtimeInstanceId)) {
                throw new IllegalArgumentException("RUNTIME_EXECUTION_BINDING_MISMATCH");
            }
        }
        return active.get(executionId);
    }

    public Result getResult(String executionId, BindingScope expectedScope) {
        BindingScope scope = executionScopes.get(executionId);
        if (scope != null) {
            if (expectedScope == null || !expectedScope.matches(scope.projectId, scope.rootGeneration,
                    scope.workspaceId, scope.runtimeInstanceId)) {
                throw new IllegalArgumentException("RUNTIME_EXECUTION_BINDING_MISMATCH");
            }
        }
        Execution exec = active.get(executionId);
        if (exec != null && exec.isFinished()) return exec.getResult();
        return recentCompleted.get(executionId);
    }

    public Result cancelAndAwait(String executionId, long waitMs) {
        return cancelAndAwait(executionId, null, waitMs);
    }

    public Result cancelAndAwait(String executionId, BindingScope expectedScope, long waitMs) {
        BindingScope scope = executionScopes.get(executionId);
        if (scope != null) {
            if (expectedScope == null || !expectedScope.matches(scope.projectId, scope.rootGeneration,
                    scope.workspaceId, scope.runtimeInstanceId)) {
                throw new IllegalArgumentException("RUNTIME_EXECUTION_BINDING_MISMATCH");
            }
        }
        Execution execution = active.get(executionId);
        if (execution != null) {
            execution.markCancelled();
            return await(execution, Math.max(waitMs, 4000L));
        }
        Execution retained = recentExecutions.get(executionId);
        if (retained != null) {
            retained.markCancelled();
            Process current = retained.process;
            terminateProcessTree(current, retained.trackedDescendants);
            boolean clean = verifyCleanup(current, retained.trackedDescendants, true);
            Result old = recentCompleted.get(executionId);
            if (old != null) {
                Result updated = new Result(old.executionId, old.exitCode, old.stdout, old.stderr,
                        old.durationMs, old.timedOut, true, clean, old.failure, old.outputTruncated);
                recentCompleted.put(executionId, updated);
                return updated;
            }
        }
        return recentCompleted.get(executionId);
    }

    public int activeCount() {
        return active.size();
    }

    public static boolean supportsProcessGroupCancellation() {
        try {
            Class.forName("java.lang.ProcessHandle");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public File executionLog(String id, BindingScope scope, String stream) {
        return executionLog(id, scope, stream, false);
    }
    public File executionLog(String id, BindingScope scope, String stream, boolean follow) {
        if (logDirectory == null || !("stdout".equals(stream) || "stderr".equals(stream)))
            throw new IllegalArgumentException("RUNTIME_LOG_UNAVAILABLE");
        BindingScope owned = executionScopes.get(id);
        if (owned == null || scope == null || !scope.matches(owned.projectId, owned.rootGeneration,
                owned.workspaceId, owned.runtimeInstanceId))
            throw new IllegalArgumentException("RUNTIME_EXECUTION_BINDING_MISMATCH");
        if (!follow && getResult(id, scope) == null) throw new IllegalArgumentException("RUNTIME_LOG_NOT_TERMINAL");
        File file = new File(logDirectory, id + "." + stream);
        if (!file.isFile()) throw new IllegalArgumentException("RUNTIME_LOG_UNAVAILABLE");
        return file;
    }
    private File logFile(String id, String stream) throws IOException {
        if (logDirectory == null) return null;
        if (!logDirectory.isDirectory() && !logDirectory.mkdirs()) throw new IOException("RUNTIME_LOG_UNAVAILABLE");
        File file = new File(logDirectory, id + "." + stream);
        if (!file.createNewFile()) throw new IOException("RUNTIME_LOG_COLLISION");
        return file;
    }
    public boolean cancelAllAndAwait(long waitMs) {
        for (Execution execution : active.values()) execution.markCancelled();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, waitMs));
        boolean clean = true;
        for (Execution execution : active.values()) {
            long remaining = Math.max(1L,
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            clean &= await(execution, remaining).cleanupConfirmed;
        }
        return clean && active.isEmpty();
    }

    private void runProcess(Execution execution, List<String> argv, File directory,
                            Map<String, String> environment, long timeoutMs, byte[] input) {
        long started = System.nanoTime();
        Process process = null;
        StreamCollector out = null;
        StreamCollector err = null;
        Thread feeder = null;
        int exitCode = -1;
        boolean timedOut = false;
        boolean cancelled = execution.isCancelled();
        boolean cleanupConfirmed = false;
        boolean requireDescendantProof = false;
        String failure = "";
        try {
            if (cancelled) {
                failure = "RUNTIME_CANCELLED_BEFORE_START";
                cleanupConfirmed = true;
            } else {
                process = processFactory.start(argv, directory, environment);
                execution.process = process;
                out = new StreamCollector(process.getInputStream(), logFile(execution.id, "stdout"));
                err = new StreamCollector(process.getErrorStream(), logFile(execution.id, "stderr"));
                out.start();
                err.start();
                final OutputStream stdinPipe = process.getOutputStream();
                feeder = new Thread(new Runnable() {
                    public void run() {
                        try (OutputStream pipe = stdinPipe) { pipe.write(input); }
                        catch (IOException ignored) { /* Child may deliberately close stdin. */ }
                    }
                }, "vega-runtime-stdin");
                feeder.setDaemon(true);
                feeder.start();
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                boolean exited = false;
                while (!exited) {
                    collectDescendants(process, execution.trackedDescendants);
                    try {
                        exitCode = process.exitValue();
                        exited = true;
                        break;
                    } catch (IllegalThreadStateException stillRunning) {
                        // Polling is non-blocking so cancellation and the one
                        // deadline remain authoritative.
                    }
                    collectDescendants(process, execution.trackedDescendants);
                    if (execution.isCancelled()) {
                        cancelled = true;
                        requireDescendantProof = true;
                        terminateProcessTree(process, execution.trackedDescendants);
                        break;
                    }
                    if (System.nanoTime() >= deadline) {
                        timedOut = true;
                        requireDescendantProof = true;
                        terminateProcessTree(process, execution.trackedDescendants);
                        break;
                    }
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                        requireDescendantProof = true;
                        terminateProcessTree(process, execution.trackedDescendants);
                        break;
                    }
                }
                collectDescendants(process, execution.trackedDescendants);
                if (!exited) exitCode = waitForExit(process, 2500L)
                        ? safeExitValue(process) : -1;
                cleanupConfirmed = verifyCleanup(process, execution.trackedDescendants, requireDescendantProof);
            }
        } catch (Throwable error) {
            failure = error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage());
            requireDescendantProof = true;
            if (process != null) {
                terminateProcessTree(process, execution.trackedDescendants);
                cleanupConfirmed = verifyCleanup(process, execution.trackedDescendants, true);
            }
        } finally {
            if (process != null) {
                if (!hasExited(process)) {
                    requireDescendantProof = true;
                    terminateProcessTree(process, execution.trackedDescendants);
                }
                cleanupConfirmed = verifyCleanup(process, execution.trackedDescendants, requireDescendantProof);
            }
            if (out != null) out.joinQuietly();
            if (err != null) err.joinQuietly();
            if (process != null) {
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
            }
            if (feeder != null) {
                try { feeder.join(1000L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (feeder.isAlive()) cleanupConfirmed = false;
            }
            if (out != null && !out.finished()) cleanupConfirmed = false;
            if (err != null && !err.finished()) cleanupConfirmed = false;
            if ((out != null && out.failed) || (err != null && err.failed)) failure = "RUNTIME_OUTPUT_READ_FAILED";
            if (execution.isCancelled()) cancelled = true;
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Result finalResult = new Result(execution.id, exitCode,
                    out == null ? "" : out.text(),
                    err == null ? "" : err.text(), duration, timedOut,
                    cancelled, cleanupConfirmed, failure,
                    (out != null && out.truncated) || (err != null && err.truncated));
            execution.finish(finalResult);
            recentCompleted.put(execution.id, finalResult);
            recentExecutions.put(execution.id, execution);
            synchronized (RuntimeProcessSupervisor.this) {
                if (logDirectory != null) {
                    retainedLogs.addLast(execution.id);
                    while (retainedLogs.size() > MAX_RETAINED_LOG_EXECUTIONS) {
                        String old = retainedLogs.removeFirst();
                        new File(logDirectory, old + ".stdout").delete();
                        new File(logDirectory, old + ".stderr").delete();
                    }
                }
                active.remove(execution.id, execution);
            }
            if (recentCompleted.size() > 256) {
                Iterator<String> it = recentCompleted.keySet().iterator();
                int removeCount = recentCompleted.size() - 256;
                while (it.hasNext() && removeCount-- > 0) {
                    String key = it.next();
                    it.remove();
                    recentExecutions.remove(key);
                    executionScopes.remove(key);
                    if (logDirectory != null) {
                        new File(logDirectory, key + ".stdout").delete();
                        new File(logDirectory, key + ".stderr").delete();
                    }
                }
            }
        }
    }

    private static void validate(List<String> argv, File directory,
                                 Map<String, String> environment, long timeoutMs) {
        if (argv == null || argv.isEmpty() || argv.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("RUNTIME_ARGUMENT_LIMIT");
        }
        for (String argument : argv) {
            if (argument == null || argument.indexOf('\u0000') >= 0
                    || argument.length() > MAX_ARGUMENT_BYTES) {
                throw new IllegalArgumentException("RUNTIME_ARGUMENT_INVALID");
            }
        }
        if (directory == null || !directory.isDirectory()) {
            throw new IllegalArgumentException("RUNTIME_WORKSPACE_INVALID");
        }
        if (timeoutMs < 1L || timeoutMs > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("RUNTIME_TIMEOUT_INVALID");
        }
        if (environment != null) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.indexOf('\u0000') >= 0
                        || key.length() > MAX_ARGUMENT_BYTES
                        || key.startsWith("VEGA_RUNTIME_")
                        || key.startsWith("VEGA_INTERNAL_")) {
                    throw new IllegalArgumentException("RUNTIME_ENV_RESERVED");
                }
                if (value == null || value.indexOf('\u0000') >= 0
                        || value.length() > MAX_ARGUMENT_BYTES) {
                    throw new IllegalArgumentException("RUNTIME_ENV_INVALID");
                }
            }
        }
    }

    private static int safeExitValue(Process process) {
        try { return process.exitValue(); }
        catch (IllegalThreadStateException notExited) { return -1; }
    }

    private static boolean hasExited(Process process) {
        try { process.exitValue(); return true; }
        catch (IllegalThreadStateException stillRunning) { return false; }
    }

    private static boolean waitForExit(Process process, long timeoutMs) {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        while (!hasExited(process) && System.nanoTime() < deadline) {
            try { Thread.sleep(20L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return hasExited(process);
            }
        }
        return hasExited(process);
    }

    /**
     * Reflection keeps the source/API floor compatible with Android 23 while
     * using ProcessHandle descendants on runtimes that expose it. The direct
     * destroy/destroyForcibly fallback is followed by an exit proof.
     */
    private static void collectDescendants(Process process, java.util.Set<Object> tracked) {
        if (process == null || tracked == null) return;
        try {
            Object handle = processHandle(process);
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object stream = handleType.getMethod("descendants").invoke(handle);
            Iterator<?> iterator = (Iterator<?>) Class.forName("java.util.stream.Stream")
                    .getMethod("iterator").invoke(stream);
            synchronized (tracked) {
                while (iterator.hasNext()) tracked.add(iterator.next());
            }
        } catch (Throwable ignored) {}
    }

    private static void terminateProcessTree(Process process, java.util.Set<Object> tracked) {
        if (process == null) return;
        if (process instanceof ManagedProcess) { process.destroy(); return; }
        collectDescendants(process, tracked);
        if (tracked != null) {
            try {
                Class<?> handleType = Class.forName("java.lang.ProcessHandle");
                Method destroyForcibly = handleType.getMethod("destroyForcibly");
                Object[] copy;
                synchronized (tracked) { copy = tracked.toArray(); }
                for (Object child : copy) {
                    try { destroyForcibly.invoke(child); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        try {
            Object handle = processHandle(process);
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            handleType.getMethod("destroyForcibly").invoke(handle);
        } catch (Throwable ignored) {
            // Android 23 has no ProcessHandle; direct destruction below is used.
        }
        try { process.destroy(); } catch (Throwable ignored) {}
        if (!waitForExit(process, 250L)) {
            try { process.destroyForcibly(); } catch (Throwable ignored) {}
            waitForExit(process, 1000L);
        }
    }

    private static boolean verifyCleanup(Process process, java.util.Set<Object> tracked, boolean requireDescendantProof) {
        if (process == null) return false;
        if (process instanceof ManagedProcess) return ((ManagedProcess) process).cleanupConfirmed();
        if (!hasExited(process)) return false;
        collectDescendants(process, tracked);
        if (tracked != null && !tracked.isEmpty()) {
            try {
                Class<?> handleType = Class.forName("java.lang.ProcessHandle");
                Method isAlive = handleType.getMethod("isAlive");
                Object[] copy;
                synchronized (tracked) { copy = tracked.toArray(); }
                for (Object child : copy) {
                    if (Boolean.TRUE.equals(isAlive.invoke(child))) return false;
                }
            } catch (Throwable ignored) {
                return !requireDescendantProof;
            }
        }
        try {
            Object handle = processHandle(process);
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object stream = handleType.getMethod("descendants").invoke(handle);
            Iterator<?> iterator = (Iterator<?>) Class.forName("java.util.stream.Stream")
                    .getMethod("iterator").invoke(stream);
            Method isAlive = handleType.getMethod("isAlive");
            while (iterator.hasNext()) {
                Object child = iterator.next();
                if (Boolean.TRUE.equals(isAlive.invoke(child))) return false;
            }
        } catch (Throwable ignored) {
            // On Android 23 the descendant API is unavailable.  A normal
            // short-lived command may rely on direct exit, but cleanup after
            // cancellation/timeout/error must remain fail-closed.
            return !requireDescendantProof;
        }
        return true;
    }

    private static Object processHandle(Process process) throws Exception {
        // Resolve the method from public Process rather than the non-public
        // platform ProcessImpl class; the latter rejects reflective invocation
        // on Java 9+ even though ProcessHandle itself is available.
        return Process.class.getMethod("toHandle").invoke(process);
    }

    private static final class StreamCollector {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;
        volatile boolean truncated;
        volatile boolean failed;

        StreamCollector(InputStream source, final File logFile) {
            input = source;
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    byte[] buffer = new byte[4096];
                    int remaining = MAX_OUTPUT_BYTES;
                    int logged = 0;
                    try (OutputStream log = logFile == null ? new OutputStream() {
                        public void write(int value) {}
                    } : new FileOutputStream(logFile)) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            int logKeep = Math.min(count, MAX_LOG_BYTES - logged);
                            if (logKeep > 0) { log.write(buffer, 0, logKeep); logged += logKeep; }
                            if (remaining > 0) {
                                int keep = Math.min(count, remaining);
                                bytes.write(buffer, 0, keep);
                                remaining -= keep;
                                if (keep < count) truncated = true;
                            } else truncated = true;
                        }
                    } catch (IOException unavailable) { failed = true; }
                }
            }, "vega-runtime-output");
            thread.setDaemon(true);
        }

        void start() { thread.start(); }

        void joinQuietly() {
            try { thread.join(1000L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            return new String(bytes.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        boolean finished() { return !thread.isAlive(); }
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        cancelAllAndAwait(2500L);
        workers.shutdownNow();
    }
}
