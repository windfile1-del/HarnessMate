package dev.wind.agent.runtime.linux;

import android.content.Context;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Shared verified archive store. Public R0 entry points remain fixture-only. */
public final class LinuxR0Installer {
    private static final long MAX_COMPRESSED_BYTES = 512L * 1024 * 1024;
    private LinuxR0Installer() {}
    /** Read-only lookup: presence alone never triggers extraction or download. */
    public static File findInstalled(Context context) throws Exception { return findInstalled(context, false); }
    static File findInstalled(Context context, boolean production) throws Exception {
        String abi = Build.SUPPORTED_ABIS[0];
        if (!abi.equals("arm64-v8a") && !abi.equals("x86_64")) return null;
        JSONObject manifest;
        try (InputStream in = context.getAssets().open((production ? "embedded/linux-runtime/" : "embedded/linux-r0/") + abi + "/manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 32768) throw new IOException("R0 manifest too large");
            }
            manifest = new JSONObject(bytes.toString("UTF-8"));
        }
        String sha = manifest.getString("sha256");
        if (!sha.matches("[0-9a-f]{64}") || !manifest.getString("abi").equals(abi)
                || manifest.getInt("schema") != 1 || manifest.getBoolean("fixtureOnly") == production) return null;
        if (production) validateProductionManifest(manifest);
        File managed = new File(context.getFilesDir(), production ? "linux-bundles" : "linux-r0");
        if (!isRealDirectory(managed)) return null;
        File active = transaction(context, production).find(manifest, sha, (directory, installedManifest) -> {
            if (production) validateProductionManifest(installedManifest);
            if (!installedManifest.getString("abi").equals(abi)) throw new IOException("RUNTIME_BUNDLE_ABI_MISMATCH");
            validate(directory, production);
        });
        if (active == null) return null;
        if (!new File(active, "usr/bin/env").isFile()) return null;
        File libs = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String name : new String[]{"libproot.so", "libprootloader.so", "libtalloc.so", "libandroidshmem.so", "libvegaspawn.so"})
            if (!new File(libs, name).isFile()) return null;
        LinuxNativeSpawn.load(libs.getCanonicalPath());
        if (LinuxNativeSpawn.apiVersion() != 3) return null;
        return active;
    }
    public static synchronized File install(Context context) throws Exception { return install(context, false); }
    static synchronized File install(Context context, boolean production) throws Exception {
        String abi = Build.SUPPORTED_ABIS[0];
        if (!abi.equals("arm64-v8a") && !abi.equals("x86_64")) throw new IOException("R0 ABI unavailable: " + abi);
        String base = (production ? "embedded/linux-runtime/" : "embedded/linux-r0/") + abi + "/";
        JSONObject manifest;
        try (InputStream in = context.getAssets().open(base + "manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 32768) throw new IOException("R0 manifest too large"); }
            manifest = new JSONObject(bytes.toString("UTF-8"));
        }
        String sha = manifest.getString("sha256");
        if (manifest.getInt("schema") != 1 || !manifest.getString("abi").equals(abi)
                || manifest.getInt("minSdk") != (production ? 29 : 23) || manifest.getInt("jdkMajor") != 17
                || manifest.getBoolean("fixtureOnly") == production || !manifest.getString("payload").equals("rootfs.tar.gz")
                || !sha.matches("[0-9a-f]{64}") || manifest.getLong("compressedBytes") < 1
                || manifest.getLong("compressedBytes") > MAX_COMPRESSED_BYTES)
            throw new IOException("Invalid Linux rootfs manifest");
        if (production) validateProductionManifest(manifest);
        return transaction(context, production).install(manifest, sha, (directory, installedManifest) -> {
            if (production) validateProductionManifest(installedManifest);
            if (!installedManifest.getString("abi").equals(abi)) throw new IOException("RUNTIME_BUNDLE_ABI_MISMATCH");
            validate(directory, production);
        }, stage -> {
            LinuxOfflineBundle.requireSpace(stage, manifest.getLong("expandedBytes") + 64L * 1024 * 1024);
            LinuxInstallControl.phase("VERIFYING");
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); long compressed = 0;
            try (InputStream in = LinuxInstallControl.cancellable(context.getAssets().open(base + "rootfs.tar.gz"))) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = in.read(buffer)) != -1) {
                    hash.update(buffer, 0, count); compressed += count;
                    if (compressed > MAX_COMPRESSED_BYTES || compressed > manifest.getLong("compressedBytes"))
                        throw new IOException("R0 payload length mismatch");
                }
            }
            if (compressed != manifest.getLong("compressedBytes") || !hex(hash.digest()).equals(sha))
                throw new IOException("R0 payload checksum mismatch");
            LinuxInstallControl.phase("EXTRACTING");
            try (InputStream in = LinuxInstallControl.cancellable(context.getAssets().open(base + "rootfs.tar.gz"))) {
                TrustedRootfsExtractor.extract(in, stage, manifest.getLong("expandedBytes"), manifest.getInt("entries"));
            }
            validate(stage, production);
            LinuxInstallControl.phase("PROBING");
            if (production) LinuxToolchainProbe.probe(context, stage, true);
        }, production ? legacy -> LinuxToolchainProbe.probe(context, legacy, true) : null);
    }
    private static LinuxBundleTransaction transaction(Context context, boolean production) throws IOException {
        return new LinuxBundleTransaction(new File(context.getFilesDir(), production ? "linux-bundles" : "linux-r0").getCanonicalFile(), "core", false);
    }
    static File rollback(Context context) throws Exception {
        return transaction(context, true).rollback((directory, installedManifest) -> {
            validateProductionManifest(installedManifest);
            if (!installedManifest.getString("abi").equals(Build.SUPPORTED_ABIS[0])) throw new IOException("RUNTIME_BUNDLE_ABI_MISMATCH");
            validate(directory, true);
            LinuxToolchainProbe.probe(context, directory, true);
        });
    }
    private static void validate(File root, boolean production) throws IOException {
        if (!isRealDirectory(root)) throw new IOException("R0 rootfs is not a real directory");
        String canonicalRoot = root.getCanonicalPath();
        for (String name : production ? new String[]{"bin/sh", "usr/bin/env", "usr/bin/git", "usr/bin/python3", "usr/bin/node", "opt/jdk/bin/java", "opt/jdk/bin/javac"} : new String[]{"bin/sh", "bin/uname", "opt/jdk/bin/java", "opt/jdk/bin/javac", "opt/vega-r0/probe.sh"}) {
            File file = new File(root, name);
            if (!file.isFile() || !file.canRead() || !file.getCanonicalPath().startsWith(canonicalRoot + File.separator))
                throw new IOException("R0 rootfs entry missing/escaping: " + name);
        }
    }
    private static void validateProductionManifest(JSONObject manifest) throws Exception {
        if (!manifest.getString("runtimeVersion").matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
                || manifest.getInt("nativeApi") != 3 || manifest.getInt("minSdk") != 29 || Build.VERSION.SDK_INT < 29
                || manifest.getInt("jdkMajor") != 17 || !manifest.getString("payload").equals("rootfs.tar.gz")
                || manifest.getLong("compressedBytes") < 1 || manifest.getLong("compressedBytes") > MAX_COMPRESSED_BYTES
                || manifest.getLong("expandedBytes") < 1 || manifest.getLong("expandedBytes") > 1024L * 1024 * 1024
                || manifest.getInt("entries") < 1 || manifest.getInt("entries") > 40000)
            throw new IOException("RUNTIME_BUNDLE_MANIFEST_INVALID");
    }
    private static boolean isRealDirectory(File file) throws IOException {
        try { return OsConstants.S_ISDIR(Os.lstat(file.getPath()).st_mode); }
        catch (ErrnoException e) { if (e.errno == OsConstants.ENOENT) return false; throw new IOException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString();
    }
}
