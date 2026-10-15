package dev.wind.agent.runtime.linux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Host regression tests for diagnostic retention and fail-closed readiness. No JNI/device launch. */
public final class ToolchainProbeHostTests {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static LinuxRuntimeLauncher.Result result(int exit, String out, String err, boolean timeout, boolean truncated) {
        return new LinuxRuntimeLauncher.Result(exit, out, err, timeout, truncated, 60200);
    }
    private static String failure(LinuxRuntimeLauncher.Result result) throws Exception {
        try { LinuxToolchainProbe.versions(result); }
        catch (IOException expected) { return expected.getMessage(); }
        throw new AssertionError("failure incorrectly marked ready");
    }
    public static void main(String[] args) throws Exception {
        String good = "git version 2.34.1\nPYTHON=3.8.10\nNODE=22.0.0\njavac 17.0.16\nVEGA_LINUX_TOOLS_READY\n";
        Map<String,String> versions = LinuxToolchainProbe.versions(result(0, good, "", false, false));
        check(versions.size() == 4 && "17.0.16".equals(versions.get("java")), "successful versions");
        String detail = failure(result(127, "git version 2.34.1\n", "VEGA_PROBE_STAGE=git\nVEGA_PROBE_STAGE=python\npython3: missing libpython\n", false, false));
        check(detail.contains("stage=python") && detail.contains("exit=127") && detail.contains("missing libpython") && detail.contains("git version"), "retain last stage and both streams");
        detail = failure(result(126, "", "VEGA_NATIVE_LAUNCH_FAILED stage=subreaper errno=1\n", false, false));
        check(detail.contains("stage=native-launch") && detail.contains("stage=subreaper errno=1"), "native failure before shell");
        check(failure(result(0, good, "VEGA_PROBE_STAGE=javac\n", true, false)).contains("timedOut=true"), "timeout cannot pass readiness");
        check(failure(result(0, good, "", false, true)).contains("outputTruncated=true"), "truncation cannot pass readiness");
        check(failure(result(0, good.replace("VEGA_LINUX_TOOLS_READY\n", ""), "", false, false)).contains("PROBE_FAILED"), "missing final sentinel");
        check(failure(result(0, good.replace("NODE=22.0.0\n", ""), "", false, false)).contains("VERSION_MISSING:node"), "missing version");
        check(failure(result(0, good.replace("javac 17.0.16", "javac 21.0.1"), "", false, false)).contains("JDK_VERSION_MISMATCH"), "wrong JDK rejected");
        String huge = new String(new char[65536]).replace('\0', 'x') + "FINAL_NATIVE_ERROR";
        detail = failure(result(1, huge, huge, false, false));
        check(detail.length() < 26000 && detail.contains("FINAL_NATIVE_ERROR") && detail.contains("earlier output omitted"), "bounded report retains tail");
        // Export the actual Java-built script for syntax validation, avoiding a duplicate fixture.
        if (args.length == 1) Files.write(Paths.get(args[0]), LinuxToolchainProbe.script().getBytes(StandardCharsets.UTF_8));
        System.out.println("ToolchainProbeHostTests PASS: " + checks + " checks");
    }
}
