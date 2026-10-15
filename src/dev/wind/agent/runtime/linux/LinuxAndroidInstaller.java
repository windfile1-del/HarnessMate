package dev.wind.agent.runtime.linux;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Offline Android SDK/AGP overlay; deployment does not require a sample-project build. */
public final class LinuxAndroidInstaller {
    private LinuxAndroidInstaller() {}
    private static JSONObject manifest(Context context) throws Exception {
        JSONObject value;
        try (InputStream in = context.getAssets().open("embedded/linux-android/manifest.json")) {
            value = new JSONObject(read(in, 32768));
        }
        if (value.getInt("schema") != 1 || !value.getString("payload").equals("tools.tar.gz")
                || !value.getString("sha256").matches("[0-9a-f]{64}")
                || !value.getString("lockSha256").matches("[0-9a-f]{64}")
                || !value.getString("gradleVersion").equals(LinuxAndroidGradleCommand.VERSION)
                || !value.getString("kotlinVersion").equals(LinuxGradleCommand.KOTLIN_VERSION)
                || !value.getString("agpVersion").equals(LinuxAndroidGradleCommand.AGP_VERSION)
                || !value.getString("abi").equals("arm64-v8a") || value.getInt("minSdk") != 29
                || value.getInt("compileSdk") != 35 || !value.getString("buildToolsVersion").equals("35.0.0")
                || value.getInt("jdkMajor") != 17 || value.getLong("compressedBytes") < 1
                || value.getLong("compressedBytes") > 1024L * 1024 * 1024
                || value.getLong("expandedBytes") < 1 || value.getLong("expandedBytes") > 1024L * 1024 * 1024
                || value.getInt("entries") < 1 || value.getInt("entries") > 20000)
            throw new IOException("R3_ANDROID_MANIFEST_INVALID");
        return value;
    }

    /** Does not install or download. The verified deployment receipt is required; compilation is explicit. */
    public static File findInstalled(Context context) throws Exception {
        JSONObject value = manifest(context);
        if (!java.util.Arrays.asList(android.os.Build.SUPPORTED_ABIS).contains("arm64-v8a")) return null;
        LinuxAndroidNative.validate(context);
        if (LinuxJvmInstaller.findInstalled(context) == null) return null;
        File rootfs = LinuxR0Installer.findInstalled(context, true);
        if (rootfs == null) return null;
        return LinuxOfflineBundle.transaction(context, "linux-android", rootfs).find(value,
                LinuxOfflineBundle.legacyName(value, rootfs), LinuxAndroidInstaller::validateInstalled);
    }
    private static void validateInstalled(File root, JSONObject value) throws Exception {
        validate(root, value);
        LinuxOfflineBundle.requireReceipt(root, ".verified-android", value);
    }
    public static synchronized File install(Context context, File rootfs) throws Exception {
        LinuxAndroidNative.validate(context);
        if (LinuxJvmInstaller.findInstalled(context) == null) throw new IOException("R3_ANDROID_REQUIRES_JVM_BUNDLE");
        JSONObject value = manifest(context);
        return LinuxOfflineBundle.transaction(context, "linux-android", rootfs).install(value,
                LinuxOfflineBundle.legacyName(value, rootfs), LinuxAndroidInstaller::validateInstalled, stage -> {
            LinuxOfflineBundle.extract(context, "linux-android", value, stage);
            validate(stage, value);
            // Deploy verified files now; compilation belongs to explicit project checks.
            LinuxOfflineBundle.receipt(stage, ".verified-android", value);
        });
    }
    public static boolean integrity(Context context, File rootfs) throws Exception {
        return LinuxOfflineBundle.transaction(context, "linux-android", rootfs).integrity(LinuxAndroidInstaller::validateInstalled);
    }
    public static File rollback(Context context, File rootfs) throws Exception {
        LinuxAndroidNative.validate(context);
        if (LinuxJvmInstaller.findInstalled(context) == null) throw new IOException("R3_ANDROID_REQUIRES_JVM_BUNDLE");
        manifest(context);
        return LinuxOfflineBundle.transaction(context, "linux-android", rootfs).rollback(LinuxAndroidInstaller::validateInstalled);
    }

    private static void validate(File root, JSONObject value) throws Exception {
        String prefix = root.getCanonicalPath() + File.separator;
        for (String entry : new String[]{"gradle-8.9/lib/gradle-launcher-8.9.jar", "vega-offline.gradle", "android-tools-lock.json", "probe.sh",
                "maven/com/android/tools/build/gradle/8.7.2/gradle-8.7.2.jar",
                "sdk/platforms/android-35/android.jar", "sdk/build-tools/35.0.0/lib/apksigner.jar",
                "sdk/build-tools/35.0.0/aapt2", "sdk/build-tools/35.0.0/aidl", "sdk/build-tools/35.0.0/split-select", "bin/gradle",
                "sdk/build-tools/35.0.0/aapt", "sdk/build-tools/35.0.0/dexdump", "sdk/build-tools/35.0.0/zipalign",
                "sdk/build-tools/35.0.0/d8", "sdk/build-tools/35.0.0/lib/d8.jar",
                "sdk/build-tools/35.0.0/apksigner", "sdk/build-tools/35.0.0/core-lambda-stubs.jar",
                "maven/junit/junit/4.13.2/junit-4.13.2.jar"}) {
            File file = new File(root, entry);
            if (!file.isFile() || file.length() == 0 || !file.getCanonicalPath().startsWith(prefix))
                throw new IOException("R3_ANDROID_ENTRY_MISSING: " + entry);
        }
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(new File(root, "android-tools-lock.json"))) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        if (!hex(hash.digest()).equals(value.getString("lockSha256"))) throw new IOException("R3_ANDROID_LOCK_MISMATCH");
    }

    private static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("R3_ANDROID_METADATA_TOO_LARGE");
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
