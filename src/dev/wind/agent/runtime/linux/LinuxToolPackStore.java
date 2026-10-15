package dev.wind.agent.runtime.linux;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

/** Catalog metadata stays in the APK; optional payloads are explicitly imported. */
public final class LinuxToolPackStore {
    private LinuxToolPackStore() {}
    private static byte[] catalog(Context context, String family) throws IOException {
        try (InputStream in = context.getAssets().open("embedded/" + family + "/manifest.json")) {
            return ToolPackArchive.readBounded(in, ToolPackArchive.MAX_METADATA_BYTES);
        }
    }
    private static File payload(Context context, String family) throws Exception {
        JSONObject metadata = new JSONObject(new String(catalog(context, family), "UTF-8"));
        return ToolPackArchive.payloadFile(new File(context.getFilesDir(), "tool-pack-imports"), family, metadata.getString("sha256"));
    }
    public static boolean hasPayload(Context context, String family) throws Exception {
        File file = payload(context, family);
        if (!file.isFile()) return false;
        try { verifyPublisher(context, family, file); return true; } catch (Exception invalid) { return false; }
    }
    public static InputStream openPayload(Context context, String family) throws Exception {
        File file = payload(context, family);
        if (!file.isFile()) throw new IOException("TOOL_PACK_NOT_IMPORTED: " + family + "; import the matching .hm-pack in Settings");
        verifyPublisher(context, family, file);
        return new FileInputStream(file);
    }
    private static void verifyPublisher(Context context, String family, File file) throws Exception {
        File receipt = new File(file.getParentFile(), file.getName() + ".publisher.json");
        if (!java.nio.file.Files.isRegularFile(receipt.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("PUBLISHER_RECEIPT_MISSING");
        byte[] envelope, roots;
        try (InputStream in = java.nio.file.Files.newInputStream(receipt.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { envelope = ToolPackArchive.readBounded(in, ToolPackArchive.MAX_METADATA_BYTES); }
        try (InputStream in = context.getAssets().open("publisher-trust.json")) { roots = ToolPackArchive.readBounded(in, ToolPackArchive.MAX_METADATA_BYTES); }
        PublisherTrust.verify(envelope, catalog(context, family), family, "tool-packs-2026.10.06", roots, System.currentTimeMillis());
    }
    /** Worker-thread action; pins, bounds and verifies before any installation. */
    public static String importPack(Context context, InputStream input) throws Exception {
        java.util.Map<String, byte[]> catalogs = new java.util.LinkedHashMap<>();
        for (String family : new String[]{"linux-jvm", "linux-android", "linux-lsp"}) catalogs.put(family, catalog(context, family));
        byte[] roots;
        try (InputStream trust = context.getAssets().open("publisher-trust.json")) { roots = ToolPackArchive.readBounded(trust, ToolPackArchive.MAX_METADATA_BYTES); }
        return ToolPackArchive.importPack(input, new File(context.getFilesDir(), "tool-pack-imports"), catalogs, roots, "tool-packs-2026.10.06");
    }
}
