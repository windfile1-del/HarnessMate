package dev.wind.agent.runtime.linux;

/** Batch JNI adapter migrated from Mobile Harness. Not a public shell API. */
final class LinuxNativeSpawn {
    private static boolean loaded;
    private LinuxNativeSpawn() {}
    static synchronized void load(String nativeDirectory) {
        if (!loaded) { System.load(nativeDirectory + "/libvegaspawn.so"); loaded = true; }
    }
    static native int[] spawn(String[] argv, String[] environment, String cwd);
    /** -2 means still running; other negative values are waitpid errors. */
    static native int poll(int pid);
    static native int killGroup(int pid);
    /** Observe without reaping: the child PID stays reserved until finish. */
    static native int peek(int pid);
    /** Kill/reap without blocking: 0 reaped, 1 pending, negative ownership/error.
     * Call under the Process monitor; pending retains ownership for later polling. */
    static native int finish(int pid);
    static native boolean groupGone(int pid);
    static native int apiVersion();
}
