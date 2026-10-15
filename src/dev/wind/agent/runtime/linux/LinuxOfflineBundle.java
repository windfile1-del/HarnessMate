package dev.wind.agent.runtime.linux;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;

/** Common offline catalog verification/extraction; guest links stay in the trusted TAR reader. */
final class LinuxOfflineBundle {
    private LinuxOfflineBundle() {}
    static String baseIdentity(File rootfs) throws IOException {
        String name = rootfs.getName();
        if (!name.matches("[0-9a-f]{64}(?:-v[0-9a-f-]{36})?")) throw new IOException("TOOL_PACK_BASE_IDENTITY_INVALID");
        // Same-content base repair must not invalidate healthy extensions.
        return name.substring(0, 64);
    }
    static String legacyName(JSONObject manifest, File rootfs) throws Exception {
        return manifest.getString("sha256") + "-" + baseIdentity(rootfs);
    }
    static LinuxBundleTransaction transaction(Context context, String family, File rootfs) throws IOException {
        return new LinuxBundleTransaction(new File(context.getFilesDir(), family + "-bundles").getCanonicalFile(), baseIdentity(rootfs), true);
    }
    static void extract(Context context, String family, JSONObject manifest, File stage) throws Exception {
        requireSpace(stage, manifest.getLong("expandedBytes") + 256L * 1024 * 1024);
        LinuxInstallControl.phase("VERIFYING");
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); long bytes = 0;
        try (InputStream in = LinuxInstallControl.cancellable(LinuxToolPackStore.openPayload(context, family))) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) {
                bytes += count;
                if (bytes > manifest.getLong("compressedBytes")) throw new IOException("TOOL_PACK_SIZE_MISMATCH");
                hash.update(buffer, 0, count);
            }
        }
        if (bytes != manifest.getLong("compressedBytes") || !LinuxBundleTransaction.hex(hash.digest()).equals(manifest.getString("sha256")))
            throw new IOException("TOOL_PACK_CHECKSUM_MISMATCH");
        LinuxInstallControl.phase("EXTRACTING");
        try (InputStream in = LinuxInstallControl.cancellable(LinuxToolPackStore.openPayload(context, family))) {
            TrustedRootfsExtractor.extract(in, stage, manifest.getLong("expandedBytes"), manifest.getInt("entries"));
        }
    }
    static void requireSpace(File directory, long bytes) throws IOException {
        long available = directory.getUsableSpace();
        if (available < bytes) throw new IOException("TOOL_INSTALL_NO_SPACE required=" + bytes + " available=" + available);
    }
    static void receipt(File directory, String name, JSONObject manifest) throws Exception {
        File file = new File(directory, name);
        if (!file.createNewFile()) throw new IOException("TOOL_INSTALL_RECEIPT_EXISTS");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(manifest.getString("sha256").getBytes("UTF-8")); out.getFD().sync();
        }
    }
    static void requireReceipt(File directory, String name, JSONObject manifest) throws Exception {
        File file = new File(directory, name);
        if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || file.length() > 128)
            throw new IOException("TOOL_INSTALL_RECEIPT_INVALID");
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!new String(ToolPackArchive.readBounded(in, 128), "UTF-8").equals(manifest.getString("sha256")))
                throw new IOException("TOOL_INSTALL_RECEIPT_INVALID");
        }
    }
}
