package dev.wind.agent.runtime.linux;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android Bionic tools are executable only from PackageManager's native directory. */
final class LinuxAndroidNative {
    static void addBinds(java.util.List<String> argv, File libraries) {
        for (String tool : new String[]{"aapt2", "aidl", "zipalign", "aapt", "dexdump", "split-select"})
            argv.add("--bind=" + new File(libraries, "libvega" + tool + ".so")
                    + ":/opt/vega-android/sdk/build-tools/35.0.0/" + tool);
    }
    static void validate(Context context) throws Exception {
        JSONObject manifest;
        try (InputStream in = context.getAssets().open("embedded/linux-android/native-manifest.json")) {
            byte[] bytes = new byte[32769];
            int size = 0, count;
            while ((count = in.read(bytes, size, bytes.length - size)) > 0) size += count;
            if (size == bytes.length) throw new IOException("R3_ANDROID_NATIVE_MANIFEST_TOO_LARGE");
            manifest = new JSONObject(new String(bytes, 0, size, "UTF-8"));
        }
        if (manifest.getInt("schema") != 1 || !manifest.getString("abi").equals("arm64-v8a")
                || manifest.getInt("minSdk") != 29) throw new IOException("R3_ANDROID_NATIVE_MANIFEST_INVALID");
        File directory = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        JSONArray files = manifest.getJSONArray("binaries");
        java.util.Set<String> required = new java.util.HashSet<>(java.util.Arrays.asList(
                "libvegaaapt2.so", "libvegaaidl.so", "libvegazipalign.so", "libvegaaapt.so", "libvegadexdump.so", "libvegasplit-select.so"));
        if (files.length() != required.size()) throw new IOException("R3_ANDROID_NATIVE_SET_INVALID");
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.getJSONObject(i);
            String name = entry.getString("path");
            if (!required.remove(name)) throw new IOException("R3_ANDROID_NATIVE_SET_INVALID");
            File file = new File(directory, name);
            if (!file.isFile() || !file.canExecute() || file.length() != entry.getLong("bytes")
                    || !file.getCanonicalPath().startsWith(directory.getPath() + File.separator))
                throw new IOException("R3_ANDROID_NATIVE_UNAVAILABLE: " + name);
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            if (!hex.toString().equals(entry.getString("sha256")))
                throw new IOException("R3_ANDROID_NATIVE_CHECKSUM_MISMATCH: " + name);
        }
    }
}
