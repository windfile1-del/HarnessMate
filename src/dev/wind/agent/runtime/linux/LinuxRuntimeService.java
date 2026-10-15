package dev.wind.agent.runtime.linux;

import android.os.Build;
import dev.wind.agent.runtime.RuntimeWorkspace;
import dev.wind.agent.runtime.RuntimeProcessSupervisor;
import dev.wind.agent.runtime.VegaRuntimeService;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Separate binding authority, same proven mirror/Binder protocol; never installs in probe. */
public final class LinuxRuntimeService extends VegaRuntimeService {
    private volatile File rootfs;
    private volatile boolean functional;
    private Map<String,String> toolchains = Collections.emptyMap();
    private long nextProbeNanos;
    private String unavailable = "RUNTIME_LINUX_NOT_INSTALLED";
    protected RuntimeWorkspace createWorkspace() {
        return new RuntimeWorkspace(getFilesDir(), "VEGA_LINUX", "linux-runtime-root");
    }
    protected RuntimeProcessSupervisor createSupervisor() {
        try { rootfs = LinuxRuntimeInstaller.findInstalled(this); }
        catch (Exception | LinkageError failure) { rootfs = null; }
        return new RuntimeProcessSupervisor(new RuntimeProcessSupervisor.ProcessFactory() {
            public Process start(List<String> argv, File cwd, Map<String, String> env) throws java.io.IOException {
                File selected;
                synchronized (LinuxRuntimeService.this) {
                    if (!runtimeReady()) throw new java.io.IOException(unavailable);
                    selected = rootfs;
                }
                return new LinuxProcessFactory(LinuxRuntimeService.this, selected).start(argv, cwd, env);
            }
        }, logDirectory());
    }
    private File logDirectory() {
        try { return new File(getCacheDir().getCanonicalFile(), "linux-r1-logs"); }
        catch (java.io.IOException failure) { throw new IllegalStateException("RUNTIME_LOG_UNAVAILABLE", failure); }
    }
    protected String providerId() { return "VEGA_LINUX"; }
    protected synchronized String runtimeDiagnostic() { return functional ? "" : unavailable; }
    protected synchronized boolean runtimeReady() {
        File selected;
        try { selected = LinuxRuntimeInstaller.findInstalled(this); }
        catch (Exception | LinkageError failure) {
            rootfs = null;
            functional = false;
            toolchains = Collections.emptyMap();
            unavailable = failure.toString();
            LinuxRuntimeDiagnostics.record(this, unavailable);
            return false;
        }
        if (selected == null || !selected.equals(rootfs)) {
            rootfs = selected;
            functional = false;
            nextProbeNanos = 0;
            toolchains = Collections.emptyMap();
        }
        if (rootfs != null && !functional && System.nanoTime() >= nextProbeNanos) {
            try {
                toolchains = LinuxToolchainProbe.probe(this, rootfs);
                functional = true;
            } catch (Exception | LinkageError failure) {
                functional = false;
                toolchains = Collections.emptyMap();
                unavailable = failure.toString();
                LinuxRuntimeDiagnostics.record(this, unavailable);
                nextProbeNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            }
        }
        if (rootfs == null) unavailable = "RUNTIME_LINUX_NOT_INSTALLED";
        return rootfs != null && functional;
    }
    protected synchronized JSONObject runtimeCapabilities() throws JSONException {
        boolean ready = runtimeReady();
        JSONObject reportedTools = new JSONObject();
        for (Map.Entry<String,String> tool : toolchains.entrySet()) reportedTools.put(tool.getKey(), tool.getValue());
        String jvmIdentity = "unavailable";
        try {
            File jvm = LinuxJvmInstaller.findInstalled(this);
            if (jvm != null && ready) {
                reportedTools.put("gradle", LinuxGradleCommand.VERSION);
                reportedTools.put("kotlin", LinuxGradleCommand.KOTLIN_VERSION);
                jvmIdentity = jvm.getName();
            }
        } catch (Exception failure) { /* R1 tools remain usable; Gradle gives a precise setup error. */ }
        String androidIdentity = "unavailable";
        JSONArray languageServers = new JSONArray();
        String lspIdentity = "unavailable";
        try {
            File lsp = LinuxLspInstaller.findInstalled(this);
            if (lsp != null && ready) {
                for (String language : new String[]{"java", "kotlin", "python", "typescript", "javascript"}) languageServers.put(language);
                lspIdentity = lsp.getName();
            }
        } catch (Exception failure) { /* An optional language pack does not disable core execution. */ }
        try {
            File android = LinuxAndroidInstaller.findInstalled(this);
            if (android != null && ready) {
                reportedTools.put("androidGradle", LinuxAndroidGradleCommand.VERSION);
                reportedTools.put("agp", LinuxAndroidGradleCommand.AGP_VERSION);
                reportedTools.put("androidSdk", "35");
                reportedTools.put("androidBuildTools", LinuxAndroidGradleCommand.BUILD_TOOLS);
                reportedTools.put("aapt2", LinuxAndroidGradleCommand.BUILD_TOOLS);
                reportedTools.put("apksigner", LinuxAndroidGradleCommand.BUILD_TOOLS);
                androidIdentity = android.getName();
            }
        } catch (Exception failure) { /* The Android overlay does not disable installed R1/R2 tools. */ }
        return new JSONObject()
                .put("commandExecution", ready).put("persistentWorkspace", true)
                .put("processGroupCancellation", ready)
                .put("commandOutputStreamVersion", 1).put("serviceDeathCleanup", "NATIVE_SUBREAPER")
                .put("maxLogCacheBytes", RuntimeProcessSupervisor.MAX_LOG_CACHE_BYTES)
                .put("mirrorProtocolVersion", 2).put("mirrorStreamProtocolVersion", 1)
                .put("helperPythonVersion", "none").put("toolchains", reportedTools)
                .put("languageServers", languageServers).put("networkIsolation", "POLICY_ONLY")
                .put("supportedAbis", new JSONArray().put(Build.SUPPORTED_ABIS[0]))
                .put("runtimeVersion", rootfs == null ? "r1-unavailable" : "r5-" + rootfs.getName() + ":" + jvmIdentity + ":" + androidIdentity + ":" + lspIdentity);
    }
}
