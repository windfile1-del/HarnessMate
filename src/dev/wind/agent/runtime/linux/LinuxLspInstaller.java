package dev.wind.agent.runtime.linux;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;

/** Offline upstream servers activated only after the actual five-language workload. */
public final class LinuxLspInstaller {
    private LinuxLspInstaller() {}
    private static JSONObject manifest(Context context) throws Exception {
        try (InputStream in = context.getAssets().open("embedded/linux-lsp/manifest.json")) {
            JSONObject value = new JSONObject(new String(ToolPackArchive.readBounded(in, 32768), "UTF-8"));
            if (value.getInt("schema") != 1 || !value.getString("payload").equals("tools.tar.gz")
                    || value.getInt("jdkMajor") != 17 || !value.getString("sha256").matches("[0-9a-f]{64}")
                    || value.getLong("compressedBytes") < 1 || value.getLong("compressedBytes") > 512L * 1024 * 1024
                    || value.getLong("expandedBytes") < 1 || value.getLong("expandedBytes") > 1024L * 1024 * 1024
                    || value.getInt("entries") < 1 || value.getInt("entries") > 20000
                    || !value.getString("jdtAdapterSha256").matches("[0-9a-f]{64}"))
                throw new IOException("R4_LSP_MANIFEST_INVALID");
            return value;
        }
    }
    public static File findInstalled(Context context) throws Exception {
        JSONObject value = manifest(context);
        File rootfs = LinuxR0Installer.findInstalled(context, true);
        if (rootfs == null) return null;
        return LinuxOfflineBundle.transaction(context, "linux-lsp", rootfs).find(value,
                LinuxOfflineBundle.legacyName(value, rootfs), LinuxLspInstaller::validateInstalled);
    }
    private static void validateInstalled(File root, JSONObject value) throws Exception {
        validate(root, value);
        LinuxOfflineBundle.requireReceipt(root, ".verified-lsp", value);
    }
    public static synchronized File install(Context context, File rootfs) throws Exception {
        JSONObject value = manifest(context);
        return LinuxOfflineBundle.transaction(context, "linux-lsp", rootfs).install(value,
                LinuxOfflineBundle.legacyName(value, rootfs), LinuxLspInstaller::validateInstalled, stage -> {
            LinuxOfflineBundle.extract(context, "linux-lsp", value, stage);
            validate(stage, value);
            LinuxInstallControl.phase("PROBING");
            try (LinuxRuntimeLauncher launcher = LinuxRuntimeLauncher.installationProbe(context, rootfs, null, null, stage)) {
                LinuxRuntimeLauncher.Result result = launcher.run(900000, "/usr/bin/python3", "-u", "/opt/vega-lsp/probe.py");
                if (result.exitCode != 0 || result.timedOut || result.outputTruncated || !result.stdout.endsWith("VEGA_LSP_READY\n"))
                    throw new IOException("R4_LSP_PROBE_FAILED exit=" + result.exitCode + " timedOut=" + result.timedOut
                            + "\nstdout:\n" + result.stdout + "\nstderr:\n" + result.stderr);
            }
            LinuxOfflineBundle.receipt(stage, ".verified-lsp", value);
        });
    }
    public static boolean integrity(Context context, File rootfs) throws Exception {
        return LinuxOfflineBundle.transaction(context, "linux-lsp", rootfs).integrity(LinuxLspInstaller::validateInstalled);
    }
    public static File rollback(Context context, File rootfs) throws Exception {
        manifest(context);
        return LinuxOfflineBundle.transaction(context, "linux-lsp", rootfs).rollback(LinuxLspInstaller::validateInstalled);
    }

    private static void validate(File root, JSONObject value) throws Exception {
        String prefix = root.getCanonicalPath() + File.separator;
        for (String entry : new String[]{"bin/jdtls", "bin/kotlin-language-server", "bin/pyright-langserver", "bin/typescript-language-server",
                "node_modules/pyright/langserver.index.js", "node_modules/typescript/lib/typescript.js", "node_modules/typescript-language-server/lib/cli.mjs"}) {
            File file = new File(root, entry);
            if (!file.isFile() || file.length() == 0 || !file.getCanonicalPath().startsWith(prefix)) throw new IOException("R4_LSP_ENTRY_MISSING: " + entry);
        }
        for (String[] pair : new String[][]{{"lsp-tools-lock.json", "lockSha256"}, {"lsp_daemon.py", "daemonSha256"}, {"probe.py", "probeSha256"},
                {"java/plugins/org.eclipse.jdt.ls.filesystem_1.28.0.202309281329.jar", "jdtAdapterSha256"}}) {
            File file = new File(root, pair[0]);
            if (!file.isFile() || !file.getCanonicalPath().startsWith(prefix) || !sha(file).equals(value.getString(pair[1])))
                throw new IOException("R4_LSP_METADATA_MISMATCH: " + pair[0]);
        }
    }
    private static String sha(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hex(hash.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
