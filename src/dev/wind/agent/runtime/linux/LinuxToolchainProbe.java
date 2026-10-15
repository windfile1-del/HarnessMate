package dev.wind.agent.runtime.linux;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual offline workloads, not manifest-claimed versions or file-presence readiness. */
final class LinuxToolchainProbe {
    private LinuxToolchainProbe() {}
    static Map<String,String> probe(Context context, File rootfs) throws Exception {
        return probe(context, rootfs, false);
    }
    static Map<String,String> probe(Context context, File rootfs, boolean installation) throws Exception {
        LinuxNativeSpawn.load(new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalPath());
        if (LinuxNativeSpawn.apiVersion() != 3) throw new IOException("RUNTIME_NATIVE_API_MISMATCH");
        try (LinuxRuntimeLauncher launcher = installation ? LinuxRuntimeLauncher.installationProbe(context, rootfs, null, null, null)
                : new LinuxRuntimeLauncher(context, rootfs)) {
        LinuxRuntimeLauncher.Result result = launcher.run(60000,
                "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp", "JAVA_HOME=/opt/jdk",
                "PATH=/opt/jdk/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "GLIBC_TUNABLES=glibc.pthread.rseq=0",
                "/bin/sh", "-c", script());
        return versions(result);
        }
    }

    static String script() {
        return "set -eu; "
                + "printf 'VEGA_PROBE_STAGE=workspace\\n' >&2; "
                + "trial=$(mktemp -d /workspace/.vega-tool-probe.XXXXXX); trap 'rm -rf \"$trial\"' EXIT; "
                + "printf 'VEGA_PROBE_STAGE=git\\n' >&2; "
                + "git -C \"$trial\" init --quiet; test \"$(git -C \"$trial\" rev-parse --is-inside-work-tree)\" = true; "
                + "git --version; "
                + "printf 'VEGA_PROBE_STAGE=python\\n' >&2; "
                + "python3 -c 'import json,sys; print(\"PYTHON=\"+sys.version.split()[0]); assert json.loads(\"{\\\"ok\\\":true}\")[\"ok\"]'; "
                + "printf 'VEGA_PROBE_STAGE=node\\n' >&2; "
                + "node -e 'if(JSON.parse(\"{\\\"ok\\\":true}\").ok!==true)process.exit(1); console.log(\"NODE=\"+process.versions.node)'; "
                + "printf 'VEGA_PROBE_STAGE=java\\n' >&2; "
                + "/opt/jdk/bin/java -version 2>&1; printf 'VEGA_PROBE_STAGE=javac\\n' >&2; "
                + "/opt/jdk/bin/javac -version; printf '\nVEGA_LINUX_TOOLS_READY\n'";
    }

    static String failureReport(LinuxRuntimeLauncher.Result result) {
        String stage = "native-launch";
        for (String line : result.stderr.split("\n")) {
            if (line.startsWith("VEGA_PROBE_STAGE=")) stage = line.substring(17).trim();
        }
        return "RUNTIME_TOOLCHAIN_PROBE_FAILED stage=" + stage
                + " exit=" + result.exitCode + " elapsedMs=" + result.elapsedMillis
                + " timedOut=" + result.timedOut + " outputTruncated=" + result.outputTruncated
                + "\n--- stdout (tail) ---\n" + tail(result.stdout, 8192)
                + "\n--- stderr (tail) ---\n" + tail(result.stderr, 16384);
    }

    private static String tail(String text, int limit) {
        return text.length() <= limit ? text : "[earlier output omitted]\n" + text.substring(text.length() - limit);
    }

    static Map<String,String> versions(LinuxRuntimeLauncher.Result result) throws IOException {
        if (result.exitCode != 0 || result.timedOut || result.outputTruncated
                || !result.stdout.endsWith("VEGA_LINUX_TOOLS_READY\n"))
            throw new IOException(failureReport(result));
        Map<String,String> versions = new LinkedHashMap<>();
        for (String line : result.stdout.split("\n")) {
            if (line.startsWith("git version ")) versions.put("git", line.substring(12).trim());
            if (line.startsWith("PYTHON=")) versions.put("python", line.substring(7).trim());
            if (line.startsWith("NODE=")) versions.put("node", line.substring(5).trim());
            if (line.startsWith("javac ")) versions.put("java", line.substring(6).trim());
        }
        for (String key : new String[]{"git", "python", "node", "java"}) {
            String value = versions.get(key);
            if (value == null || value.isEmpty() || value.length() > 128)
                throw new IOException("RUNTIME_TOOLCHAIN_VERSION_MISSING:" + key + "\n" + failureReport(result));
        }
        if (!versions.get("java").matches("17(?:[.+-].*)?"))
            throw new IOException("RUNTIME_JDK_VERSION_MISMATCH\n" + failureReport(result));
        return Collections.unmodifiableMap(versions);
    }
}
