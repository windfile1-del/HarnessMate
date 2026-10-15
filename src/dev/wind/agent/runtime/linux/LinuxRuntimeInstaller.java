package dev.wind.agent.runtime.linux;

import android.content.Context;
import java.io.File;

/** Explicit APK-asset installation; shares the verified R0 extractor, not fixture activation. */
public final class LinuxRuntimeInstaller {
    private LinuxRuntimeInstaller() {}
    /** Run off the UI thread. No network and no caller-supplied payload or digest. */
    public static File installBundled(Context context) throws Exception {
        try {
            File rootfs = LinuxR0Installer.install(context, true);
            return rootfs;
        }
        catch (Exception | LinkageError failure) {
            LinuxRuntimeDiagnostics.record(context, failure.toString());
            throw failure;
        }
    }
    /** Explicit extension installation; base tools do not depend on extensions. */
    public static File installExtension(Context context, String family) throws Exception {
        LinuxInstallControl.check();
        try {
            File rootfs = findInstalled(context);
            if (rootfs == null) throw new java.io.IOException("TOOL_PACK_REQUIRES_BASE_RUNTIME");
            File installed;
            if (family.equals("linux-jvm")) installed = LinuxJvmInstaller.install(context, rootfs);
            else if (family.equals("linux-android")) installed = LinuxAndroidInstaller.install(context, rootfs);
            else if (family.equals("linux-lsp")) installed = LinuxLspInstaller.install(context, rootfs);
            else throw new java.io.IOException("TOOL_PACK_ID_INVALID");
            File selected = findInstalled(context);
            if (selected == null || !LinuxOfflineBundle.baseIdentity(selected).equals(LinuxOfflineBundle.baseIdentity(rootfs)))
                throw new java.io.IOException("TOOL_PACK_BASE_CHANGED: retry with the current base tools");
            return installed;
        } catch (Exception | LinkageError failure) {
            LinuxRuntimeDiagnostics.record(context, failure.toString());
            throw failure;
        }
    }
    public static File rollback(Context context, String family) throws Exception {
        try {
            if (family.equals("core")) return LinuxR0Installer.rollback(context);
            File rootfs = findInstalled(context);
            if (rootfs == null) throw new java.io.IOException("TOOL_PACK_REQUIRES_BASE_RUNTIME");
            if (family.equals("linux-jvm")) return LinuxJvmInstaller.rollback(context, rootfs);
            if (family.equals("linux-android")) return LinuxAndroidInstaller.rollback(context, rootfs);
            if (family.equals("linux-lsp")) return LinuxLspInstaller.rollback(context, rootfs);
            throw new java.io.IOException("TOOL_PACK_ID_INVALID");
        } catch (Exception | LinkageError failure) {
            LinuxRuntimeDiagnostics.record(context, failure.toString()); throw failure;
        }
    }
    /** Stored transaction detail plus actual read-only resolution; neither implies a new probe. */
    public static String installationStatus(Context context) throws Exception {
        File rootfs = findInstalled(context);
        StringBuilder result = new StringBuilder("core: ").append(rootfs == null ? "NOT_READY" : rootfs.getName());
        for (String family : new String[]{"linux-jvm", "linux-android", "linux-lsp"}) {
            if (rootfs == null) { result.append("\n").append(family).append(": BASE_REQUIRED"); continue; }
            try {
            File installed = family.equals("linux-jvm") ? LinuxJvmInstaller.findInstalled(context)
                    : family.equals("linux-android") ? LinuxAndroidInstaller.findInstalled(context) : LinuxLspInstaller.findInstalled(context);
            result.append("\n").append(family).append(": ").append(installed == null ? "NOT_READY" : installed.getName());
            boolean intact = family.equals("linux-jvm") ? LinuxJvmInstaller.integrity(context, rootfs)
                    : family.equals("linux-android") ? LinuxAndroidInstaller.integrity(context, rootfs) : LinuxLspInstaller.integrity(context, rootfs);
            result.append("\nIntegrity: ").append(intact ? "VERIFIED" : "NOT_VERIFIED (legacy, missing or damaged; reimport to repair)");
            result.append("\n").append(LinuxOfflineBundle.transaction(context, family, rootfs).status());
            } catch (Exception failure) { result.append("\n").append(family).append(": ").append(failure.toString()); }
        }
        return result.toString();
    }
    /** Read-only version lookup; never installs as a side effect of provider selection. */
    public static File findInstalled(Context context) throws Exception {
        return LinuxR0Installer.findInstalled(context, true);
    }
}
