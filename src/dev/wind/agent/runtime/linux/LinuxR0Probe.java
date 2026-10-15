package dev.wind.agent.runtime.linux;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;

/** Explicit test entry point. Production capability selection stays unchanged. */
public final class LinuxR0Probe {
    private LinuxR0Probe() {}
    public static Bundle run(Context context) {
        Bundle report = new Bundle();
        long started = SystemClock.elapsedRealtime();
        report.putString("status", "FAIL");
        report.putInt("sdk", Build.VERSION.SDK_INT);
        report.putString("abi", Build.SUPPORTED_ABIS[0]);
        report.putInt("uid", Process.myUid());
        report.putInt("targetSdk", context.getApplicationInfo().targetSdkVersion);
        report.putString("package", context.getPackageName());
        report.putString("nativeLibraryDir", context.getApplicationInfo().nativeLibraryDir);
        report.putInt("exitCode", -1);
        try {
            if (!context.getPackageName().equals("dev.wind.agent") || Process.myUid() < 10000
                    || context.getApplicationInfo().targetSdkVersion != 35)
                throw new IllegalStateException("R0 requires the Vega package, application UID and targetSdk 35");
            report.putString("stage", "install");
            File rootfs = LinuxR0Installer.install(context);
            LinuxRuntimeLauncher launcher = new LinuxRuntimeLauncher(context, rootfs);
            report.putString("stage", "compile-and-child");
            LinuxRuntimeLauncher.Result result = launcher.run(180000, "/bin/sh", "/opt/vega-r0/probe.sh");
            report.putInt("exitCode", result.exitCode);
            report.putString("stdout", result.stdout);
            report.putString("stderr", result.stderr);
            report.putBoolean("timedOut", result.timedOut);
            report.putBoolean("outputTruncated", result.outputTruncated);
            if (result.exitCode != 0 || result.timedOut || result.outputTruncated
                    || !result.stdout.contains("R0_SHELL_OK") || !result.stdout.contains("R0_JAVA_REFERENCE_OK")
                    || !result.stdout.contains("R0_KOTLIN_EXECUTED_OK") || !result.stdout.contains("R0_CHILD_STDOUT")
                    || !result.stdout.contains("R0_COMPILE_AND_CHILD_OK") || !result.stderr.contains("R0_CHILD_STDERR"))
                throw new IllegalStateException("R0 compile/child acceptance markers missing");
            report.putString("stage", "timeout");
            LinuxRuntimeLauncher.Result timeout = launcher.run(750, "/bin/sh", "-c", "sleep 30 & wait");
            report.putBoolean("timeoutProbePassed", timeout.timedOut && timeout.exitCode == 137);
            if (!timeout.timedOut || timeout.exitCode != 137 || timeout.elapsedMillis > 12000)
                throw new IllegalStateException("R0 bounded timeout/reap probe failed");
            report.putString("stage", "complete");
            report.putString("status", "PASS");
        } catch (Exception failure) {
            report.putString("error", failure.getClass().getName() + ": " + failure.getMessage());
        }
        report.putLong("elapsedMillis", SystemClock.elapsedRealtime() - started);
        return report;
    }
}
