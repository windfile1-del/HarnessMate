package dev.wind.agent.runtime.linux;

import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports only the exact tool payloads pinned by this application release. */
public final class ToolPackArchive {
    private ToolPackArchive() {}
    public static final int MAX_METADATA_BYTES = 32768;

    public static String importPack(InputStream source, File store, byte[] jvmCatalog, byte[] androidCatalog) throws Exception {
        java.util.Map<String, byte[]> catalogs = new java.util.LinkedHashMap<>();
        catalogs.put("linux-jvm", jvmCatalog); catalogs.put("linux-android", androidCatalog);
        return importPack(source, store, catalogs);
    }
    public static synchronized String importPack(InputStream source, File store, java.util.Map<String, byte[]> catalogs) throws Exception {
        return importPack(source, store, catalogs, null, null);
    }
    public static synchronized String importPack(InputStream source, File store, java.util.Map<String, byte[]> catalogs, byte[] publisherRoots, String release) throws Exception {
        LinuxInstallControl.phase("IMPORTING");
        if (!store.isDirectory() && !store.mkdirs()) throw new IOException("TOOL_PACK_STORE_UNAVAILABLE");
        if (Files.isSymbolicLink(store.toPath())) throw new IOException("TOOL_PACK_STORE_INVALID");
        File root = store.getCanonicalFile();
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(new File(root, ".import.lock").toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                java.nio.channels.FileLock ignored = channel.lock()) {
        File[] remnants = root.listFiles();
        if (remnants == null) throw new IOException("TOOL_PACK_STORE_UNREADABLE");
        for (File remnant : remnants) {
            if (remnant.getName().matches("\\.import-[0-9a-f-]{36}")) {
                if (!Files.isRegularFile(remnant.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("TOOL_PACK_STAGE_UNSAFE");
                Files.delete(remnant.toPath());
            }
        }
        File stage = null;
        try (ZipInputStream zip = new ZipInputStream(LinuxInstallControl.cancellable(source))) {
            ZipEntry metadata = zip.getNextEntry();
            requireEntry(metadata, "manifest.json");
            byte[] manifestBytes = readBounded(zip, MAX_METADATA_BYTES);
            String family = null;
            for (java.util.Map.Entry<String, byte[]> item : catalogs.entrySet())
                if (Arrays.equals(manifestBytes, item.getValue())) { family = item.getKey(); break; }
            if (family == null) throw new IOException("TOOL_PACK_RELEASE_MISMATCH: use a pack matching this app version");
            JSONObject manifest = new JSONObject(new String(manifestBytes, "UTF-8"));
            String expectedHash = manifest.getString("sha256");
            long expectedSize = manifest.getLong("compressedBytes");
            if (manifest.getInt("schema") != 1 || !manifest.getString("payload").equals("tools.tar.gz")
                    || !expectedHash.matches("[0-9a-f]{64}") || expectedSize < 1 || expectedSize > 1024L * 1024 * 1024)
                throw new IOException("TOOL_PACK_CATALOG_INVALID");
            ZipEntry payload = zip.getNextEntry();
            requireEntry(payload, "tools.tar.gz");
            if (payload.getSize() != expectedSize) throw new IOException("TOOL_PACK_SIZE_MISMATCH");
            if (root.getUsableSpace() < expectedSize + 16L * 1024 * 1024) throw new IOException("TOOL_INSTALL_NO_SPACE");
            stage = new File(root, ".import-" + UUID.randomUUID());
            if (!stage.createNewFile()) throw new IOException("TOOL_PACK_STAGE_UNAVAILABLE");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (FileOutputStream out = new FileOutputStream(stage)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = zip.read(buffer)) != -1) {
                    size += count;
                    if (size > expectedSize) throw new IOException("TOOL_PACK_SIZE_MISMATCH");
                    digest.update(buffer, 0, count); out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (size != expectedSize || !hex(digest.digest()).equals(expectedHash)) throw new IOException("TOOL_PACK_CHECKSUM_MISMATCH");
            ZipEntry signature = zip.getNextEntry();
            byte[] publisherEnvelope = null;
            if (publisherRoots != null) {
                requireEntry(signature, "publisher.json");
                publisherEnvelope = readBounded(zip, MAX_METADATA_BYTES);
                PublisherTrust.verify(publisherEnvelope, manifestBytes, family, release, publisherRoots, System.currentTimeMillis());
                if (zip.getNextEntry() != null) throw new IOException("TOOL_PACK_UNEXPECTED_ENTRY");
            } else if (signature != null) throw new IOException("TOOL_PACK_UNEXPECTED_ENTRY");
            File target = payloadFile(root, family, expectedHash);
            LinuxInstallControl.check();
            if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IOException("TOOL_PACK_PATH_INVALID");
            // The newly verified quarantine can repair a corrupt CAS blob.
            // Atomic replacement keeps already-open readers on their old inode.
            if (!target.exists() || target.length() != expectedSize || !hashFile(target).equals(expectedHash)) {
                Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (publisherEnvelope != null) {
                File receipt = new File(root, target.getName() + ".publisher.json");
                File receiptStage = new File(root, ".import-" + UUID.randomUUID());
                try {
                    if (!receiptStage.createNewFile()) throw new IOException("PUBLISHER_RECEIPT_FAILED");
                    try (FileOutputStream out = new FileOutputStream(receiptStage)) { out.write(publisherEnvelope); out.getFD().sync(); }
                    if (Files.isSymbolicLink(receipt.toPath())) throw new IOException("PUBLISHER_RECEIPT_UNSAFE");
                    Files.move(receiptStage.toPath(), receipt.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(receiptStage.toPath()); }
            }
            return family;
        } finally {
            if (stage != null && stage.exists() && !stage.delete()) stage.deleteOnExit();
        }
        }
    }

    public static File payloadFile(File store, String family, String digest) throws IOException {
        if ((!family.equals("linux-jvm") && !family.equals("linux-android") && !family.equals("linux-lsp")) || !digest.matches("[0-9a-f]{64}"))
            throw new IOException("TOOL_PACK_ID_INVALID");
        File root = store.getCanonicalFile();
        File file = new File(root, family + "-" + digest + ".tar.gz");
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalPath().startsWith(root.getPath() + File.separator))
            throw new IOException("TOOL_PACK_PATH_INVALID");
        return file;
    }
    private static void requireEntry(ZipEntry entry, String name) throws IOException {
        if (entry == null || entry.isDirectory() || !name.equals(entry.getName()) || entry.getMethod() != ZipEntry.STORED)
            throw new IOException("TOOL_PACK_FORMAT_INVALID: " + name);
    }
    public static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("TOOL_PACK_METADATA_TOO_LARGE");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private static String hashFile(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = LinuxInstallControl.cancellable(Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hex(hash.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
