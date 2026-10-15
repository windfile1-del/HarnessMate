package dev.wind.agent.runtime.linux;

import android.os.ParcelFileDescriptor;
import dev.wind.agent.runtime.RuntimeProcessSupervisor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Pipe process owned by one monitor. No signals are sent after the leader is reaped. */
final class LinuxManagedProcess extends Process implements RuntimeProcessSupervisor.ManagedProcess {
    private final int pid;
    private final InputStream stdout, stderr;
    private final OutputStream stdin;
    private final ParcelFileDescriptor owner;
    private Integer exit;
    private boolean clean;
    LinuxManagedProcess(int[] child) throws IOException {
        pid = child[0];
        owner = ParcelFileDescriptor.adoptFd(child[4]);
        stdin = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(child[1]));
        stdout = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[2]));
        stderr = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[3]));
    }
    public InputStream getInputStream() { return stdout; }
    public InputStream getErrorStream() { return stderr; }
    public OutputStream getOutputStream() { return stdin; }
    public synchronized int exitValue() {
        if (exit != null) return exit;
        int value = LinuxNativeSpawn.peek(pid);
        if (value == -2) throw new IllegalThreadStateException();
        if (value < 0) { exit = -1; clean = false; closeOwner(); return exit; }
        int finished = LinuxNativeSpawn.finish(pid);
        if (finished == 1) throw new IllegalThreadStateException();
        clean = finished == 0 && LinuxNativeSpawn.groupGone(pid);
        exit = value;
        closeOwner();
        return value;
    }
    public int waitFor() throws InterruptedException {
        for (;;) { try { return exitValue(); } catch (IllegalThreadStateException running) { Thread.sleep(20); } }
    }
    public synchronized void destroy() {
        if (exit != null) return;
        closeOwner();
        int value = LinuxNativeSpawn.peek(pid);
        if (value < 0 && value != -2) { exit = -1; clean = false; return; }
        int finished = LinuxNativeSpawn.finish(pid);
        // The native call is non-blocking. An unreaped child remains owned;
        // exitValue() keeps observing it without declaring premature completion.
        if (finished == 1) return;
        clean = finished == 0 && LinuxNativeSpawn.groupGone(pid);
        exit = value == -2 ? 137 : value;
    }
    public synchronized boolean cleanupConfirmed() { return exit != null && clean; }
    private void closeOwner() { try { owner.close(); } catch (IOException ignored) {} }
}
