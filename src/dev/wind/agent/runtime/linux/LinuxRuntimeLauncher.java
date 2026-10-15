package dev.wind.agent.runtime.linux;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * R0 application-UID Linux launcher. PRoot invocation follows DSHA/Mobile
 * Harness; the executable and loader must be PackageManager-extracted APK
 * native libraries. No external Termux and no executable filesDir shortcut.
 * This does not register a runtime provider or promise hard confinement.
 */
public final class LinuxRuntimeLauncher implements AutoCloseable {
    private final File rootfs, workspace, temp, libraries, jvm, android, lsp;
    private final File isolatedProbe;
    public LinuxRuntimeLauncher(Context context, File rootfs) throws IOException {
        this(context, rootfs, null);
    }
    LinuxRuntimeLauncher(Context context, File rootfs, File jvm) throws IOException {
        this(context, rootfs, jvm, null);
    }
    LinuxRuntimeLauncher(Context context, File rootfs, File jvm, File android) throws IOException {
        this(context, rootfs, jvm, android, null);
    }
    LinuxRuntimeLauncher(Context context, File rootfs, File jvm, File android, File lsp) throws IOException {
        this(context, rootfs, jvm, android, lsp, false);
    }
    static LinuxRuntimeLauncher installationProbe(Context context, File rootfs, File jvm, File android, File lsp) throws IOException {
        return new LinuxRuntimeLauncher(context, rootfs, jvm, android, lsp, true);
    }
    private LinuxRuntimeLauncher(Context context, File rootfs, File jvm, File android, File lsp, boolean isolated) throws IOException {
        this.jvm = jvm;
        this.android = android;
        this.lsp = lsp;
        this.rootfs = rootfs.getCanonicalFile();
        File managed = new File(context.getFilesDir(), "linux-r0").getCanonicalFile();
        File production = new File(context.getFilesDir(), "linux-bundles").getCanonicalFile();
        if (!this.rootfs.getPath().startsWith(managed.getPath() + File.separator)
                && !this.rootfs.getPath().startsWith(production.getPath() + File.separator))
            throw new IOException("Linux rootfs must belong to a managed private directory");
        libraries = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        for (String name : new String[]{"libproot.so", "libprootloader.so", "libtalloc.so", "libandroidshmem.so", "libvegaspawn.so"}) {
            File file = new File(libraries, name);
            if (!file.isFile() || !file.canRead()) throw new IOException("APK native runtime unavailable: " + name);
        }
        boolean runtimeBundle = this.rootfs.getPath().startsWith(production.getPath() + File.separator);
        LinuxNativeSpawn.load(libraries.getPath());
        File probeOwner = lsp != null ? lsp : android != null ? android : jvm != null ? jvm : this.rootfs;
        if (isolated) {
            // This directory belongs to the transaction holding the bundle lock.
            // New installs recover it with their stage, legacy core migration
            // removes its own remnants without scanning other active probes.
            File[] children = probeOwner.listFiles();
            if (children == null) throw new IOException("TOOL_INSTALL_PROBE_STORE_UNAVAILABLE");
            for (File child : children) {
                if (child.getName().matches("\\.r5-probe-[0-9a-f-]{36}")) LinuxBundleTransaction.deleteTree(child);
            }
        }
        isolatedProbe = isolated ? new File(probeOwner, ".r5-probe-" + java.util.UUID.randomUUID()) : null;
        workspace = isolated ? new File(isolatedProbe, "workspace")
                : new File(context.getFilesDir(), runtimeBundle ? "linux-probe-workspace" : "linux-r0-workspace");
        temp = isolated ? new File(isolatedProbe, "tmp")
                : new File(context.getCacheDir(), runtimeBundle ? "linux-probe-tmp" : "linux-r0-tmp");
        try {
            mkdir(workspace); mkdir(temp);
            if (isolated) mkdir(new File(isolatedProbe, "home"));
        } catch (IOException failure) {
            if (isolatedProbe != null && isolatedProbe.exists()) {
                try { LinuxBundleTransaction.deleteTree(isolatedProbe); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create " + file);
    }
    public Result run(long timeoutMillis, String... guestCommand) throws IOException, InterruptedException {
        LinuxInstallControl.check();
        if (timeoutMillis < 1 || timeoutMillis > 900000 || guestCommand.length == 0 || !guestCommand[0].startsWith("/"))
            throw new IllegalArgumentException("R0 requires an absolute guest command and a bounded timeout");
        List<String> argv = new ArrayList<>(Arrays.asList(new File(libraries, "libproot.so").getPath(),
                "-L", "--kill-on-exit", "-0", "--rootfs=" + rootfs, "--cwd=/workspace"));
        for (String path : new String[]{"/dev", "/proc", "/sys", "/system", "/apex", "/vendor", "/product", "/linkerconfig"}) {
            if (new File(path).exists()) { argv.add("--bind=" + path); }
        }
        argv.add("--bind=" + workspace + ":/workspace");
        argv.add("--bind=" + temp + ":/tmp");
        if (isolatedProbe != null) argv.add("--bind=" + new File(isolatedProbe, "home") + ":/root");
        if (jvm != null) argv.add("--bind=" + jvm.getCanonicalPath() + ":/opt/vega-jvm");
        if (lsp != null) argv.add("--bind=" + lsp.getCanonicalPath() + ":/opt/vega-lsp");
        if (android != null) {
            argv.add("--bind=" + android.getCanonicalPath() + ":/opt/vega-android");
            argv.add("--bind=" + libraries + ":/opt/vega-native");
            LinuxAndroidNative.addBinds(argv, libraries);
        }
        argv.addAll(Arrays.asList(guestCommand));
        // Fixed environment: a caller cannot replace loader, tmp or library paths.
        String[] environment = {"LD_LIBRARY_PATH=" + libraries,
                "PROOT_LOADER=" + new File(libraries, "libprootloader.so"),
                "PROOT_TMP_DIR=" + temp, "PROOT_NO_SECCOMP=1",
                "GLIBC_TUNABLES=glibc.pthread.rseq=0", "HOME=/root", "TMPDIR=/tmp",
                "JAVA_HOME=/opt/jdk", "PATH=" + (lsp == null ? "" : "/opt/vega-lsp/bin:") + "/opt/jdk/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8"};
        long started = SystemClock.elapsedRealtime();
        int[] child = LinuxNativeSpawn.spawn(argv.toArray(new String[0]), environment, workspace.getPath());
        if (child == null || child.length != 5) throw new IOException("R0 native spawn failed");
        ParcelFileDescriptor owner = ParcelFileDescriptor.adoptFd(child[4]);
        ParcelFileDescriptor stdin = ParcelFileDescriptor.adoptFd(child[1]);
        Collector stdout = new Collector(ParcelFileDescriptor.adoptFd(child[2]));
        Collector stderr = new Collector(ParcelFileDescriptor.adoptFd(child[3]));
        int exit = -2;
        boolean timedOut = false;
        try {
            stdin.close();
            stdout.start(); stderr.start();
            while ((exit = LinuxNativeSpawn.poll(child[0])) == -2) {
                if (SystemClock.elapsedRealtime() - started >= timeoutMillis) { timedOut = true; break; }
                Thread.sleep(20);
            }
        } finally {
            owner.close();
            boolean interrupted = Thread.interrupted();
            if (exit == -2) {
                LinuxNativeSpawn.killGroup(child[0]);
                long deadline = SystemClock.elapsedRealtime() + 5000;
                while ((exit = LinuxNativeSpawn.poll(child[0])) == -2 && SystemClock.elapsedRealtime() < deadline) {
                    try { Thread.sleep(20); } catch (InterruptedException e) { interrupted = true; }
                }
            }
            try {
                stdout.join(2000); stderr.join(2000);
            } catch (InterruptedException e) { interrupted = true; }
            try { stdout.close(); } finally {
                try { stderr.close(); } finally {
                    try { stdin.close(); } finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
            }
        }
        if (exit < 0) throw new IOException("R0 waitpid/reap failed: " + exit);
        if (stdout.isAlive() || stderr.isAlive()) throw new IOException("R0 output pipes did not reach EOF");
        if (stdout.error != null) throw stdout.error;
        if (stderr.error != null) throw stderr.error;
        return new Result(exit, stdout.text(), stderr.text(), timedOut, stdout.truncated || stderr.truncated,
                SystemClock.elapsedRealtime() - started);
    }
    @Override public void close() throws IOException {
        if (isolatedProbe != null && isolatedProbe.exists()) LinuxBundleTransaction.deleteTree(isolatedProbe);
    }
    public static final class Result {
        public final int exitCode;
        public final String stdout, stderr;
        public final boolean timedOut, outputTruncated;
        public final long elapsedMillis;
        Result(int exit, String out, String err, boolean timeout, boolean truncated, long elapsed) {
            exitCode = exit; stdout = out; stderr = err; timedOut = timeout; outputTruncated = truncated; elapsedMillis = elapsed;
        }
    }
    private static final class Collector extends Thread {
        private final InputStream input;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        volatile IOException error;
        volatile boolean truncated;
        Collector(ParcelFileDescriptor fd) {
            super("vega-r0-stream"); setDaemon(true);
            input = new ParcelFileDescriptor.AutoCloseInputStream(fd);
        }
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    int keep = Math.min(count, 65536 - bytes.size());
                    if (keep > 0) bytes.write(buffer, 0, keep);
                    if (keep != count) truncated = true;
                }
            } catch (IOException e) { error = e; }
        }
        void close() throws IOException { input.close(); }
        String text() throws IOException { return bytes.toString("UTF-8"); }
    }
}
