import dev.wind.agent.runtime.linux.ToolPackArchive;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;

/** Host checks for the exact stream imported by the Android settings action. */
public final class ToolPackArchiveTests {
    private static int assertions;
    private static void verify(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format("%02x", b & 255));
        return value.toString();
    }
    private static byte[] metadata(byte[] payload) throws Exception {
        return new JSONObject().put("schema", 1).put("payload", "tools.tar.gz")
                .put("compressedBytes", payload.length).put("sha256", hash(payload)).toString().getBytes("UTF-8");
    }
    private static byte[] zip(String[] names, byte[][] data, boolean stored) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (int i=0; i<names.length; i++) {
                ZipEntry entry = new ZipEntry(names[i]);
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(data[i]);
                    entry.setMethod(ZipEntry.STORED); entry.setSize(data[i].length); entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry); zip.write(data[i]); zip.closeEntry();
            }
        }
        return raw.toByteArray();
    }
    private static void refused(byte[] archive, File root, byte[] metadata, String code) throws Exception {
        Exception error = null;
        try { ToolPackArchive.importPack(new ByteArrayInputStream(archive), root, metadata, new byte[]{1}); }
        catch (Exception failure) { error = failure; }
        verify(error != null && error.getMessage().contains(code), "expected " + code + ", got " + error);
        verify(Arrays.stream(Objects.requireNonNull(root.listFiles())).noneMatch(f -> f.getName().startsWith(".import-")), "failed import leaves no staging payload");
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("tool-pack-test-");
        File root = temp.resolve("imports").toFile();
        try {
            byte[] payload = "complete extension contents".getBytes("UTF-8");
            byte[] catalog = metadata(payload);
            byte[] good = zip(new String[]{"manifest.json","tools.tar.gz"}, new byte[][]{catalog,payload}, true);
            verify("linux-jvm".equals(ToolPackArchive.importPack(new ByteArrayInputStream(good), root, catalog, new byte[]{1})), "correct family imported");
            File target = ToolPackArchive.payloadFile(root, "linux-jvm", hash(payload));
            verify(Arrays.equals(payload, Files.readAllBytes(target.toPath())), "payload bytes unchanged");
            verify("linux-jvm".equals(ToolPackArchive.importPack(new ByteArrayInputStream(good), root, catalog, new byte[]{1})), "reimport is idempotent");
            verify("linux-android".equals(ToolPackArchive.importPack(new ByteArrayInputStream(good), root, new byte[]{1}, catalog)), "Android family recognized");

            // linux-lsp via new Map catalog overload
            byte[] lspPayload = "complete lsp extension contents".getBytes("UTF-8");
            byte[] lspCatalog = metadata(lspPayload);
            byte[] goodLsp = zip(new String[]{"manifest.json","tools.tar.gz"}, new byte[][]{lspCatalog,lspPayload}, true);
            Map<String, byte[]> catalogs = new LinkedHashMap<>();
            catalogs.put("linux-jvm", catalog);
            catalogs.put("linux-android", new byte[]{1});
            catalogs.put("linux-lsp", lspCatalog);

            verify("linux-lsp".equals(ToolPackArchive.importPack(new ByteArrayInputStream(goodLsp), root, catalogs)), "LSP family recognized via Map catalogs");
            File lspTarget = ToolPackArchive.payloadFile(root, "linux-lsp", hash(lspPayload));
            verify(Arrays.equals(lspPayload, Files.readAllBytes(lspTarget.toPath())), "lsp payload bytes unchanged");
            verify("linux-lsp".equals(ToolPackArchive.importPack(new ByteArrayInputStream(goodLsp), root, catalogs)), "lsp reimport is idempotent");

            byte[] lspWrong = lspPayload.clone(); lspWrong[0] ^= 1;
            Files.write(lspTarget.toPath(), lspWrong);
            verify("linux-lsp".equals(ToolPackArchive.importPack(new ByteArrayInputStream(goodLsp), root, catalogs)), "lsp good reimport repairs corrupt cache");
            verify(Arrays.equals(lspPayload, Files.readAllBytes(lspTarget.toPath())), "lsp corrupt cache restored to good payload");

            // Rejection of unknown families
            Exception badFamilyError = null;
            try { ToolPackArchive.payloadFile(root, "unknown-family", hash(payload)); }
            catch (Exception failure) { badFamilyError = failure; }
            verify(badFamilyError != null && badFamilyError.getMessage().contains("TOOL_PACK_ID_INVALID"), "unknown family rejected by payloadFile");

            Map<String, byte[]> badCatalogs = new LinkedHashMap<>();
            badCatalogs.put("unknown-family", lspCatalog);
            Exception badMapError = null;
            try { ToolPackArchive.importPack(new ByteArrayInputStream(goodLsp), root, badCatalogs); }
            catch (Exception failure) { badMapError = failure; }
            verify(badMapError != null && badMapError.getMessage().contains("TOOL_PACK_ID_INVALID"), "unknown family in catalog map rejected");

            refused(zip(new String[]{"manifest.json","../tools.tar.gz"},new byte[][]{catalog,payload},true),root,catalog,"TOOL_PACK_FORMAT_INVALID");
            refused(zip(new String[]{"tools.tar.gz","manifest.json"},new byte[][]{payload,catalog},true),root,catalog,"TOOL_PACK_FORMAT_INVALID");
            refused(zip(new String[]{"manifest.json","tools.tar.gz"},new byte[][]{catalog,payload},false),root,catalog,"TOOL_PACK_FORMAT_INVALID");
            refused(zip(new String[]{"manifest.json","tools.tar.gz","extra"},new byte[][]{catalog,payload,new byte[]{1}},true),root,catalog,"TOOL_PACK_UNEXPECTED_ENTRY");
            byte[] wrong = payload.clone(); wrong[0] ^= 1;
            refused(zip(new String[]{"manifest.json","tools.tar.gz"},new byte[][]{catalog,wrong},true),root,catalog,"TOOL_PACK_CHECKSUM_MISMATCH");
            refused(zip(new String[]{"manifest.json","tools.tar.gz"},new byte[][]{catalog,new byte[]{1}},true),root,catalog,"TOOL_PACK_SIZE_MISMATCH");
            refused(zip(new String[]{"manifest.json","tools.tar.gz"},new byte[][]{metadata(wrong),wrong},true),root,catalog,"TOOL_PACK_RELEASE_MISMATCH");
            verify(Arrays.equals(payload, Files.readAllBytes(target.toPath())), "failed imports preserve prior installed archive");
            Files.write(target.toPath(), wrong);
            verify("linux-jvm".equals(ToolPackArchive.importPack(new ByteArrayInputStream(good), root, catalog, new byte[]{1})), "good reimport repairs corrupt jvm cache");
            verify(Arrays.equals(payload, Files.readAllBytes(target.toPath())), "jvm corrupt cache restored to good payload");

            // Stale .import-UUID remnant cleanup test
            File staleImport = new File(root, ".import-" + UUID.randomUUID());
            Files.write(staleImport.toPath(), "stale staging bytes".getBytes("UTF-8"));
            verify(staleImport.exists(), "stale staging file created");
            verify("linux-jvm".equals(ToolPackArchive.importPack(new ByteArrayInputStream(good), root, catalog, new byte[]{1})), "import cleans stale .import-UUID files");
            verify(!staleImport.exists(), "stale staging file cleaned up");

            // Bad input preserves prior good blob
            byte[] goodBeforeBad = Files.readAllBytes(target.toPath());
            refused(zip(new String[]{"manifest.json","tools.tar.gz"},new byte[][]{catalog,wrong},true),root,catalog,"TOOL_PACK_CHECKSUM_MISMATCH");
            verify(Arrays.equals(goodBeforeBad, Files.readAllBytes(target.toPath())), "corrupt input payload preserves good existing blob");

            // Cancellation test via LinuxInstallControl
            AtomicReference<Throwable> cancelFailure = new AtomicReference<>();
            Thread cancelThread = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    ToolPackArchive.importPack(new ByteArrayInputStream(good), root, catalog, new byte[]{1});
                    cancelFailure.set(new AssertionError("cancellation should have thrown InterruptedIOException"));
                } catch (InterruptedIOException expected) {
                    // expected cancellation exception
                } catch (Throwable unexpected) {
                    cancelFailure.set(unexpected);
                }
            });
            cancelThread.start();
            cancelThread.join();
            verify(cancelFailure.get() == null, "cancelled import had no unexpected failures: " + cancelFailure.get());
            verify(Arrays.stream(Objects.requireNonNull(root.listFiles())).noneMatch(f -> f.getName().startsWith(".import-")), "cancelled import leaves no staging payload");
            System.out.println("PASS ToolPackArchiveTests: " + assertions + " checks");
        } finally {
            try (java.util.stream.Stream<Path> walk = Files.walk(temp)) {
                for (Path path : (Iterable<Path>)walk.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
