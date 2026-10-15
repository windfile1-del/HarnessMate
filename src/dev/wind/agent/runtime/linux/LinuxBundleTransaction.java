package dev.wind.agent.runtime.linux;

import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/**
 * P7 version-pointer publication adapted to trusted Linux TAR bundles. Input
 * trust stays with the app catalog; this class does not download or authorize.
 * All version directories are immutable publication identities. Interrupted
 * staging is disposable; a failed update cannot replace the current pointer.
 */
public final class LinuxBundleTransaction {
    public interface Validator { void validate(File directory, JSONObject manifest) throws Exception; }
    public interface Workload { void prepare(File staging) throws Exception; }
    private static final String MANIFEST = ".r5-manifest.json", RECEIPT = ".r5-receipt.json";
    private static final int MAX_POINTER = 4096;
    private final File root;
    private final String binding;
    private final boolean treeIntegrity;
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object mutationLock;

    public LinuxBundleTransaction(File root, String binding, boolean treeIntegrity) throws IOException {
        if (!binding.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,191}")) throw new IOException("TOOL_INSTALL_BINDING_INVALID");
        if (Files.isSymbolicLink(root.toPath()) || !root.getAbsoluteFile().equals(root.getCanonicalFile()))
            throw new IOException("TOOL_INSTALL_ROOT_UNSAFE");
        this.root = root; this.binding = binding; this.treeIntegrity = treeIntegrity;
        mutationLock = LOCKS.computeIfAbsent(root.getCanonicalPath(), ignored -> new Object());
    }

    /** Read-only resolution, including a compatible last known good version. */
    public File find(JSONObject catalog, String legacyName, Validator validator) throws Exception {
        {
            if (!realDirectory(root)) return null;
            JSONObject pointer = readPointer();
            if (pointer != null) {
                File current = resolve(pointer.optJSONObject("current"), validator, false);
                if (current != null) return current;
                return resolve(pointer.optJSONObject("previous"), validator, false);
            }
            // Existing R1-R4 installations are observable without writes. The
            // next explicit install upgrades them by running the workload.
            if (Files.exists(pointerFile().toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
            File legacy = child(legacyName);
            if (!realDirectory(legacy)) return null;
            try { validator.validate(legacy, catalog); return legacy; }
            catch (Exception failure) { return null; }
        }
    }

    public File install(JSONObject catalog, String legacyName, Validator validator, Workload workload) throws Exception {
        return install(catalog, legacyName, validator, workload, null);
    }
    /** Core migration keeps its original identity, so existing extension bindings survive. */
    File install(JSONObject catalog, String legacyName, Validator validator, Workload workload, Workload legacyProbe) throws Exception {
        synchronized (mutationLock) {
            LinuxInstallControl.check();
            ensureRoot();
            File lock = child(".install.lock");
            try (FileChannel channel = FileChannel.open(lock.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
                recoverStages();
                JSONObject old = readPointer();
                if (old == null && Files.exists(pointerFile().toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    // Keep malformed pointer evidence; do not follow it.
                    Files.move(pointerFile().toPath(), child(".invalid-pointer-" + UUID.randomUUID()).toPath(), StandardCopyOption.ATOMIC_MOVE);
                }
                JSONObject prior = goodRecord(old, validator);
                File existing = resolve(prior, validator, true);
                if (existing != null && prior.getString("digest").equals(catalog.getString("sha256")) && legacyProbe != null) {
                    // Core has writable package directories: explicitly check
                    // its workload without treating user packages as immutable.
                    try {
                        LinuxInstallControl.phase("PROBING");
                        legacyProbe.prepare(existing);
                    } catch (InterruptedException | InterruptedIOException cancelled) { throw cancelled; }
                    catch (Exception unhealthy) { existing = null; prior = null; }
                }
                if (existing != null && prior.getString("digest").equals(catalog.getString("sha256"))) {
                    if (old != null && (old.optJSONObject("current") == null ||
                            !prior.getString("directory").equals(old.optJSONObject("current").optString("directory"))))
                        publishPointer(prior, null);
                    try { state("READY", existing.getName()); } catch (IOException ignoredState) { }
                    return existing;
                }
                File legacy = child(legacyName);
                if (old == null && legacyProbe != null && realDirectory(legacy)) {
                    boolean valid = false;
                    try { validator.validate(legacy, catalog); valid = true; } catch (Exception invalid) { }
                    if (valid) {
                        LinuxInstallControl.phase("PROBING");
                        try { legacyProbe.prepare(legacy); }
                        catch (InterruptedException | InterruptedIOException cancelled) { throw cancelled; }
                        catch (Exception unhealthy) { valid = false; }
                    }
                    if (valid) {
                        validator.validate(legacy, catalog);
                        LinuxInstallControl.check();
                        File metadata = new File(legacy, MANIFEST);
                        File receiptFile = new File(legacy, RECEIPT);
                        atomicWrite(metadata, catalog.toString().getBytes("UTF-8"));
                        String manifestHash = sha(metadata);
                        atomicWrite(receiptFile, new JSONObject().put("schema", 1).put("binding", binding)
                                .put("digest", catalog.getString("sha256")).put("manifest", manifestHash)
                                .put("tree", treeIntegrity ? treeSha(legacy) : "").toString().getBytes("UTF-8"));
                        LinuxInstallControl.check();
                        publishPointer(new JSONObject().put("directory", legacy.getName())
                                .put("digest", catalog.getString("sha256")).put("manifest", manifestHash), null);
                        try { state("READY", legacy.getName()); } catch (IOException ignoredState) { }
                        return legacy;
                    }
                }
                File stage = child(".stage-" + UUID.randomUUID());
                if (!stage.mkdir()) throw new IOException("TOOL_INSTALL_STAGE_UNAVAILABLE");
                boolean published = false;
                Throwable failure = null;
                try {
                    state("STAGING", stage.getName());
                    LinuxInstallControl.phase("VERIFYING");
                    workload.prepare(stage);
                    LinuxInstallControl.check();
                    validator.validate(stage, catalog);
                    LinuxInstallControl.phase("INTEGRITY");
                    String tree = treeIntegrity ? treeSha(stage) : "";
                    writeNew(new File(stage, MANIFEST), catalog.toString().getBytes("UTF-8"));
                    JSONObject receipt = new JSONObject().put("schema", 1).put("binding", binding)
                            .put("digest", catalog.getString("sha256")).put("tree", tree)
                            .put("manifest", sha(new File(stage, MANIFEST)));
                    writeNew(new File(stage, RECEIPT), receipt.toString().getBytes("UTF-8"));
                    LinuxInstallControl.check();
                    // Unique directory prevents repair from modifying tools still
                    // owned by a running command. The pointer is the only switch.
                    File version = child(catalog.getString("sha256") + "-v" + UUID.randomUUID());
                    Files.move(stage.toPath(), version.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    JSONObject next = new JSONObject().put("directory", version.getName())
                            .put("digest", catalog.getString("sha256")).put("manifest", receipt.getString("manifest"));
                    LinuxInstallControl.phase("ACTIVATING");
                    // Once publication starts, complete it even if cancellation
                    // arrives; the commit cannot report CANCELLED after success.
                    publishPointer(next, prior);
                    published = true;
                    // Diagnostic writes are not part of the committed result.
                    try { state("READY", version.getName()); } catch (IOException ignoredState) { }
                    return version;
                } catch (Exception | Error error) {
                    failure = error;
                    try { state(Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED", error.toString()); }
                    catch (IOException recordError) { error.addSuppressed(recordError); }
                    throw error;
                } finally {
                    if (!published && Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        try { deleteTree(stage); }
                        catch (IOException cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; }
                    }
                }
            }
        }
    }

    /** Explicit repair/rollback action; read-only provider lookup never rewrites pointers. */
    public File rollback(Validator validator) throws Exception {
        synchronized (mutationLock) {
            ensureRoot();
            try (FileChannel channel = FileChannel.open(child(".install.lock").toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
                recoverStages();
                JSONObject pointer = readPointer();
                JSONObject previous = pointer == null ? null : pointer.optJSONObject("previous");
                File version = resolve(previous, validator, true);
                if (version == null) throw new IOException("TOOL_INSTALL_ROLLBACK_UNAVAILABLE");
                LinuxInstallControl.check();
                JSONObject current = pointer.optJSONObject("current");
                publishPointer(previous, resolve(current, validator, true) == null ? null : current);
                try { state("ROLLED_BACK", version.getName()); } catch (IOException ignoredState) { }
                return version;
            }
        }
    }

    public String status() throws Exception {
        {
            if (!realDirectory(root)) return "NOT_INSTALLED";
            File file = child(".state-" + binding + ".json");
            return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ? small(file, MAX_POINTER) : "LEGACY";
        }
    }
    public boolean integrity(Validator validator) throws Exception {
        if (!realDirectory(root)) return false;
        JSONObject pointer = readPointer();
        if (pointer == null) return false;
        return resolve(pointer.optJSONObject("current"), validator, true) != null;
    }

    private JSONObject goodRecord(JSONObject pointer, Validator validator) throws Exception {
        if (pointer == null) return null;
        JSONObject current = pointer.optJSONObject("current");
        if (resolve(current, validator, true) != null) return current;
        JSONObject previous = pointer.optJSONObject("previous");
        return resolve(previous, validator, true) == null ? null : previous;
    }
    private File resolve(JSONObject record, Validator validator, boolean fullIntegrity) throws Exception {
        if (record == null) return null;
        try {
            File version = child(record.getString("directory"));
            if (!realDirectory(version)) return null;
            JSONObject receipt = new JSONObject(small(new File(version, RECEIPT), MAX_POINTER));
            File metadata = new File(version, MANIFEST);
            if (!Files.isRegularFile(metadata.toPath(), LinkOption.NOFOLLOW_LINKS) || metadata.length() > ToolPackArchive.MAX_METADATA_BYTES)
                return null;
            if (receipt.getInt("schema") != 1 || !receipt.getString("binding").equals(binding)
                    || !record.getString("digest").equals(receipt.getString("digest"))
                    || !record.getString("manifest").equals(receipt.getString("manifest"))
                    || !sha(metadata).equals(record.getString("manifest"))) return null;
            JSONObject catalog = new JSONObject(small(metadata, ToolPackArchive.MAX_METADATA_BYTES));
            if (!catalog.getString("sha256").equals(record.getString("digest"))) return null;
            validator.validate(version, catalog);
            if (fullIntegrity && treeIntegrity && !treeSha(version).equals(receipt.getString("tree"))) return null;
            return version;
        } catch (java.io.InterruptedIOException | InterruptedException failure) { throw failure; }
        catch (Exception failure) { return null; }
    }
    private File pointerFile() throws IOException { return child(".active-" + binding + ".json"); }
    private JSONObject readPointer() throws Exception {
        File file = pointerFile();
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            JSONObject value = new JSONObject(small(file, MAX_POINTER));
            return value.getInt("schema") == 1 && value.getString("binding").equals(binding) ? value : null;
        } catch (Exception failure) { return null; }
    }
    private void publishPointer(JSONObject current, JSONObject previous) throws Exception {
        JSONObject value = new JSONObject().put("schema", 1).put("binding", binding).put("current", current);
        if (previous != null) value.put("previous", previous);
        atomicWrite(pointerFile(), value.toString().getBytes("UTF-8"));
    }
    private void state(String phase, String detail) throws IOException {
        try {
            atomicWrite(child(".state-" + binding + ".json"), new JSONObject().put("phase", phase)
                    .put("detail", detail.substring(0, Math.min(detail.length(), 1024))).toString().getBytes("UTF-8"));
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("TOOL_INSTALL_STATE_FAILED", failure); }
    }
    private void recoverStages() throws IOException {
        LinuxInstallControl.phase("RECOVERING");
        File[] files = root.listFiles();
        if (files == null) throw new IOException("TOOL_INSTALL_STORE_UNREADABLE");
        for (File file : files) {
            if (file.getName().matches("\\.stage-[0-9a-f-]{36}|\\.pointer-[0-9a-f-]{36}")) deleteTree(file);
        }
    }
    private void ensureRoot() throws IOException {
        if (!root.exists() && !root.mkdirs()) throw new IOException("TOOL_INSTALL_STORE_UNAVAILABLE");
        if (!realDirectory(root)) throw new IOException("TOOL_INSTALL_ROOT_UNSAFE");
    }
    private File child(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9._-]{1,240}") || name.equals(".") || name.equals(".."))
            throw new IOException("TOOL_INSTALL_PATH_INVALID");
        File file = new File(root, name);
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
            throw new IOException("TOOL_INSTALL_PATH_UNSAFE");
        return file;
    }
    private void atomicWrite(File target, byte[] bytes) throws IOException {
        if (bytes.length > ToolPackArchive.MAX_METADATA_BYTES) throw new IOException("TOOL_INSTALL_METADATA_TOO_LARGE");
        File stage = child(".pointer-" + UUID.randomUUID());
        try {
            writeNew(stage, bytes);
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(stage.toPath()); }
    }
    private static void writeNew(File file, byte[] bytes) throws IOException {
        if (!file.createNewFile()) throw new IOException("TOOL_INSTALL_METADATA_EXISTS");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    private static String small(File file, int budget) throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() > budget)
            throw new IOException("TOOL_INSTALL_METADATA_INVALID");
        try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return new String(ToolPackArchive.readBounded(input, budget), "UTF-8");
        }
    }
    private static boolean realDirectory(File file) {
        return Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
    static void deleteTree(File directory) throws IOException {
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }
    private static String treeSha(File directory) throws Exception {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                LinuxInstallControl.check(); paths.add(dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                LinuxInstallControl.check(); paths.add(file); return FileVisitResult.CONTINUE;
            }
        });
        if (paths.size() > 40000) throw new IOException("TOOL_INSTALL_TREE_LIMIT");
        paths.sort(Comparator.comparing(p -> directory.toPath().relativize(p).toString().replace('\\', '/')));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long bytes = 0;
        for (Path path : paths) {
            String relative = directory.toPath().relativize(path).toString().replace('\\', '/');
            if (relative.isEmpty() || relative.equals(MANIFEST) || relative.equals(RECEIPT)) continue;
            LinuxInstallControl.check();
            String type = Files.isSymbolicLink(path) ? "L" : Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "D" : "F";
            digest.update((type + relative + "\0").getBytes("UTF-8"));
            try {
                digest.update(java.nio.file.attribute.PosixFilePermissions.toString(
                        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)).getBytes("UTF-8"));
            } catch (UnsupportedOperationException unsupportedHost) {
                // Windows host verification has no POSIX permission view.
                digest.update("NO_POSIX_VIEW".getBytes("UTF-8"));
            }
            digest.update((byte)0);
            if (type.equals("L")) {
                if (!path.toFile().getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator))
                    throw new IOException("TOOL_INSTALL_TREE_LINK_UNSAFE");
                digest.update(Files.readSymbolicLink(path).toString().getBytes("UTF-8"));
            } else if (type.equals("F")) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("TOOL_INSTALL_TREE_SPECIAL_FILE");
                bytes += Files.size(path);
                if (bytes > 1024L * 1024 * 1024) throw new IOException("TOOL_INSTALL_TREE_SIZE_LIMIT");
                digest.update(sha(path.toFile()).getBytes("UTF-8"));
            }
            digest.update((byte)0);
        }
        return hex(digest.digest());
    }
    static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = LinuxInstallControl.cancellable(Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
