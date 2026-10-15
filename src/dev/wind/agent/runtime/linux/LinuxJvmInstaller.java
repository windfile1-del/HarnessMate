package dev.wind.agent.runtime.linux;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Separate JVM overlay: preserves the R1 rootfs budget and existing user HOME. */
public final class LinuxJvmInstaller {
    private LinuxJvmInstaller() {}
    private static JSONObject manifest(Context context) throws Exception {
        JSONObject value;
        try (InputStream in = context.getAssets().open("embedded/linux-jvm/manifest.json")) {
            value = new JSONObject(read(in, 32768));
        }
        if (value.getInt("schema") != 1 || !value.getString("payload").equals("tools.tar.gz")
                || !value.getString("sha256").matches("[0-9a-f]{64}")
                || !value.getString("lockSha256").matches("[0-9a-f]{64}")
                || !value.getString("gradleVersion").equals(LinuxGradleCommand.VERSION)
                || !value.getString("kotlinVersion").equals(LinuxGradleCommand.KOTLIN_VERSION)
                || value.getInt("jdkMajor") != 17 || value.getLong("compressedBytes") < 1
                || value.getLong("compressedBytes") > 512L * 1024 * 1024
                || value.getLong("expandedBytes") < 1 || value.getLong("expandedBytes") > 512L * 1024 * 1024
                || value.getInt("entries") < 1 || value.getInt("entries") > 10000)
            throw new IOException("R2_JVM_MANIFEST_INVALID");
        return value;
    }

    /** Does not install or download. The verified deployment receipt is required; compilation is explicit. */
    public static File findInstalled(Context context) throws Exception {
        JSONObject value = manifest(context);
        File rootfs = LinuxR0Installer.findInstalled(context, true);
        if (rootfs == null) return null;
        return LinuxOfflineBundle.transaction(context, "linux-jvm", rootfs).find(value,
                LinuxOfflineBundle.legacyName(value, rootfs), LinuxJvmInstaller::validateInstalled);
    }
    private static void validateInstalled(File root, JSONObject value) throws Exception {
        validate(root, value);
        LinuxOfflineBundle.requireReceipt(root, ".verified-jvm", value);
    }
    public static synchronized File install(Context context, File rootfs) throws Exception {
        JSONObject value = manifest(context);
        return LinuxOfflineBundle.transaction(context, "linux-jvm", rootfs).install(value,
                LinuxOfflineBundle.legacyName(value, rootfs), LinuxJvmInstaller::validateInstalled, stage -> {
            LinuxOfflineBundle.extract(context, "linux-jvm", value, stage);
            validate(stage, value);
            // Deploy verified files now; compilation belongs to explicit project checks.
            LinuxOfflineBundle.receipt(stage, ".verified-jvm", value);
        });
    }
    public static boolean integrity(Context context, File rootfs) throws Exception {
        return LinuxOfflineBundle.transaction(context, "linux-jvm", rootfs).integrity(LinuxJvmInstaller::validateInstalled);
    }
    public static File rollback(Context context, File rootfs) throws Exception {
        manifest(context);
        return LinuxOfflineBundle.transaction(context, "linux-jvm", rootfs).rollback(LinuxJvmInstaller::validateInstalled);
    }

    private static void validate(File root, JSONObject value) throws Exception {
        String prefix = root.getCanonicalPath() + File.separator;
        for (String entry : new String[]{"gradle-8.5/lib/gradle-launcher-8.5.jar", "vega-offline.gradle", "jvm-tools-lock.json", "probe.sh",
                "maven/org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.21/kotlin-gradle-plugin-2.0.21-gradle85.jar",
                "maven/junit/junit/4.13.2/junit-4.13.2.jar"}) {
            File file = new File(root, entry);
            if (!file.isFile() || file.length() == 0 || !file.getCanonicalPath().startsWith(prefix))
                throw new IOException("R2_JVM_ENTRY_MISSING: " + entry);
        }
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(new File(root, "jvm-tools-lock.json"))) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        if (!hex(hash.digest()).equals(value.getString("lockSha256"))) throw new IOException("R2_JVM_LOCK_MISMATCH");
    }

    private static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("R2_JVM_METADATA_TOO_LARGE");
            out.write(buffer, 0, count);
        }
        return out.toString("UTF-8");
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
