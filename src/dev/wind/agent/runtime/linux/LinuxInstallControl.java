package dev.wind.agent.runtime.linux;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

/** Installation cancellation belongs to its worker, never to a workspace task. */
public final class LinuxInstallControl {
    public interface Listener { void progress(String phase); }
    private static final ThreadLocal<Listener> LISTENER = new ThreadLocal<>();
    private LinuxInstallControl() {}
    public static void setListener(Listener listener) { LISTENER.set(listener); }
    public static void clearListener() { LISTENER.remove(); }
    public static void check() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("TOOL_INSTALL_CANCELLED");
    }
    public static void phase(String phase) throws IOException {
        check();
        Listener listener = LISTENER.get();
        if (listener != null) listener.progress(phase);
    }
    static InputStream cancellable(InputStream input) {
        return new FilterInputStream(input) {
            @Override public int read() throws IOException { check(); return in.read(); }
            @Override public int read(byte[] buffer, int start, int size) throws IOException {
                check(); return in.read(buffer, start, size);
            }
            @Override public long skip(long bytes) throws IOException { check(); return in.skip(bytes); }
        };
    }
}
