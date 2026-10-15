package dev.wind.agent.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One staged P3 mirror synchronisation.
 *
 * The session exists so that "prepare the mirror" is a transaction rather than
 * a sequence of irreversible writes:
 *
 *   BEGIN   - re-validates the remote digest the host planned against and
 *             creates an empty staging tree. Not one byte of project/ is
 *             touched.
 *   PLAN    - bounded metadata pages are validated and recorded.
 *   FILE    - each payload is streamed into staging and checked against the
 *             size and SHA-256 the plan promised.
 *   COMMIT  - the remote digest is checked *again*, and only then is the
 *             staged tree installed into project/.
 *   ABORT   - staging is discarded; the project is exactly where it was.
 *
 * A remote that changed between planning and commit therefore fails closed,
 * which is the property the P3 protocol depends on and which an in-place sync
 * cannot provide.
 */
final class RuntimeMirrorSyncSession {
    enum Phase { OPEN, PLAN_READY, STAGING, COMMITTING, DONE, ABORTED }

    /**
     * P3's global plan budget (mirror_helper.py MAX_FILES).
     *
     * This is a parity constant, not a tuning knob: the helper and the host
     * scanner both allow 200_000 records, so a companion that stopped at
     * 100_000 would refuse a project the other provider syncs happily. The
     * bound applies to records, i.e. planned entries and deletions together,
     * which is what P3 counts.
     */
    static final int MAX_FILES = 200000;
    static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;

    /**
     * A staging root older than this is debris from a process that died.
     *
     * Sync state is in-memory by design (a sync never survives its service),
     * so after a crash the host cannot name the syncId it abandoned. Sweeping
     * only roots that are both unowned and older than this keeps the cleanup
     * safe for a sync that is merely slow.
     */
    static final long STALE_STAGING_MILLIS = 6L * 60L * 60L * 1000L;

    static final class Entry {
        final String path;
        final boolean directory;
        final long size;
        final String sha256;
        final String stagedName;

        Entry(String path, boolean directory, long size, String sha256, String stagedName) {
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.sha256 = sha256;
            this.stagedName = stagedName;
        }
    }

    private final String syncId;
    private final String expectedRemoteDigest;
    final RuntimeWorkspace.IgnorePolicy ignorePolicy;
    private final File stagingRoot;
    /**
     * The binding that opened this session.
     *
     * Every later call presents the same four values and must match: a sync is
     * a transaction on ONE project generation, so a syncId that could be
     * continued under a different - even perfectly legal - binding would let
     * one project's plan install into another. The service validates that the
     * caller's binding is live; only the session can validate that it is the
     * same one it was opened with.
     */
    private final String ownerWorkspaceId;
    private final String ownerProjectId;
    private final long ownerRootGeneration;
    private final String ownerInstanceId;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private final Set<String> deletes = new LinkedHashSet<String>();
    private final Set<String> staged = new LinkedHashSet<String>();
    private long totalPlannedRecords;
    private long totalBytes;
    private long stagedFiles;
    private long stagedBytes;
    private Phase phase = Phase.OPEN;

    RuntimeMirrorSyncSession(String syncId, String expectedRemoteDigest,
                             RuntimeWorkspace.IgnorePolicy ignorePolicy, File stagingRoot,
                             String ownerWorkspaceId, String ownerProjectId,
                             long ownerRootGeneration, String ownerInstanceId) {
        this.syncId = syncId;
        this.expectedRemoteDigest = expectedRemoteDigest;
        this.ignorePolicy = ignorePolicy;
        this.stagingRoot = stagingRoot;
        this.ownerWorkspaceId = ownerWorkspaceId;
        this.ownerProjectId = ownerProjectId;
        this.ownerRootGeneration = ownerRootGeneration;
        this.ownerInstanceId = ownerInstanceId;
    }

    /** Fail closed unless the caller is the binding that opened this session. */
    void assertOwner(String workspaceId, String projectId, long rootGeneration, String instanceId) {
        boolean same = ownerWorkspaceId.equals(workspaceId)
                && ownerProjectId.equals(projectId)
                && ownerRootGeneration == rootGeneration
                && ownerInstanceId.equals(instanceId);
        if (!same) {
            throw new IllegalStateException("RUNTIME_SYNC_BINDING_MISMATCH");
        }
    }

    String syncId() {
        return syncId;
    }

    String expectedRemoteDigest() {
        return expectedRemoteDigest;
    }

    Phase phase() {
        return phase;
    }

    int fileCount() {
        return entries.size();
    }

    long totalBytes() {
        return totalBytes;
    }

    int deleteCount() {
        return deletes.size();
    }

    long stagedCount() {
        return stagedFiles;
    }

    long stagedBytes() {
        return stagedBytes;
    }

    /**
     * Refuses to commit a session whose staged tree does not already match the
     * plan it recorded. Without this, a host that skipped a page or a file would
     * get a "successful" sync that silently dropped part of the mirror.
     */
    void requireComplete() {
        if (phase != Phase.PLAN_READY && phase != Phase.STAGING) {
            throw new IllegalStateException("RUNTIME_SYNC_PHASE_INVALID");
        }
        if (!entries.isEmpty()) markStaging();
        if (stagedFiles != plannedFileCount()) {
            throw new IllegalStateException("RUNTIME_SYNC_INCOMPLETE");
        }
        if (stagedBytes != plannedBytes()) {
            throw new IllegalStateException("RUNTIME_SYNC_INCOMPLETE");
        }
    }

    private long plannedFileCount() {
        long count = 0L;
        for (Entry entry : entries.values()) if (!entry.directory) count++;
        return count;
    }

    private long plannedBytes() {
        long bytes = 0L;
        for (Entry entry : entries.values()) if (!entry.directory) bytes += entry.size;
        return bytes;
    }

    File stagingRoot() {
        return stagingRoot;
    }

    File stagedFile(Entry entry) {
        return new File(stagingRoot, "files/" + entry.stagedName);
    }

    void markPlanReady() {
        if (phase == Phase.OPEN) phase = Phase.PLAN_READY;
    }

    void markStaging() {
        if (phase == Phase.PLAN_READY) phase = Phase.STAGING;
    }

    void markCommitting() {
        phase = Phase.COMMITTING;
    }

    void markDone() {
        phase = Phase.DONE;
    }

    void markAborted() {
        phase = Phase.ABORTED;
    }

    /** Deletions are ordered deepest-first so a directory never outlives its children. */
    List<String> deletesDeepestFirst() {
        List<String> ordered = new ArrayList<String>(deletes);
        Collections.sort(ordered, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int depthA = count(a, '/');
                int depthB = count(b, '/');
                if (depthA != depthB) return depthB - depthA;
                return b.length() - a.length();
            }
        });
        return ordered;
    }

    List<Entry> entriesInPlanOrder() {
        return new ArrayList<Entry>(entries.values());
    }

    private static int count(String value, char needle) {
        int found = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == needle) found++;
        return found;
    }

    /**
     * Records one plan page. Everything here is untrusted host input: paths are
     * re-validated even though the host is same-signed, because a path bug on
     * one side must not become a filesystem escape on the other.
     */
    void addPage(JSONArray deleted, JSONArray planned) throws JSONException {
        // Plan pages arrive while the session is OPEN (the first page) or
        // PLAN_READY (every page after it). Once a payload has been staged the
        // plan is frozen: accepting another page would let the plan grow after
        // the tree it was checked against stopped being the tree being written.
        if (phase != Phase.OPEN && phase != Phase.PLAN_READY) {
            throw new IllegalStateException("RUNTIME_SYNC_PHASE_INVALID");
        }
        // P3 counts *records* (len(values)), not unique set cardinality:
        // deletions and planned entries share one MAX_FILES budget. Checked
        // *before* mutating collections so a rejected page leaves zero pollution.
        int incomingRecords = (deleted != null ? deleted.length() : 0)
                + (planned != null ? planned.length() : 0);
        if (totalPlannedRecords + (long) incomingRecords > MAX_FILES) {
            throw new IllegalArgumentException("RUNTIME_SYNC_FILE_LIMIT");
        }
        totalPlannedRecords += incomingRecords;
        if (deleted != null) {
            for (int i = 0; i < deleted.length(); i++) {
                String path = requireRelativePath(deleted.getString(i));
                if (path.length() == 0) throw new IllegalArgumentException("RUNTIME_SYNC_PATH_INVALID");
                if (ignorePolicy.isIgnored(path)) {
                    throw new IllegalArgumentException("RUNTIME_SYNC_IGNORED_PATH");
                }
                deletes.add(path);
            }
        }
        if (planned != null) {
            for (int i = 0; i < planned.length(); i++) {
                JSONObject item = planned.getJSONObject(i);
                String path = requireRelativePath(item.getString("path"));
                if (path.length() == 0) throw new IllegalArgumentException("RUNTIME_SYNC_PATH_INVALID");
                if (ignorePolicy.isIgnored(path)) {
                    throw new IllegalArgumentException("RUNTIME_SYNC_IGNORED_PATH");
                }
                boolean directory = item.getBoolean("directory");
                long size = item.optLong("size", -1L);
                String sha = item.optString("sha256", "");
                if (directory) {
                    size = 0L;
                    sha = "";
                } else {
                    if (size < 0L) throw new IllegalArgumentException("RUNTIME_SYNC_SIZE_INVALID");
                    if (!sha.matches("[0-9a-f]{64}")) {
                        throw new IllegalArgumentException("RUNTIME_SYNC_HASH_INVALID");
                    }
                }
                if (entries.containsKey(path)) {
                    throw new IllegalArgumentException("RUNTIME_SYNC_DUPLICATE_PATH");
                }
                if (entries.size() >= MAX_FILES) {
                    throw new IllegalArgumentException("RUNTIME_SYNC_FILE_LIMIT");
                }
                Entry entry = new Entry(path, directory, size, sha,
                        directory ? null : String.format("%06d", entries.size()));
                if (!directory) {
                    totalBytes += size;
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw new IllegalArgumentException("RUNTIME_SYNC_SIZE_LIMIT");
                    }
                }
                entries.put(path, entry);
            }
        }
        markPlanReady();
    }

    Entry entry(String path) {
        Entry entry = entries.get(path);
        if (entry == null || entry.directory) {
            throw new IllegalArgumentException("RUNTIME_SYNC_FILE_UNPLANNED");
        }
        return entry;
    }

    private static final int MAX_PATH_LENGTH = 4096;

    static String requireRelativePath(String raw) {
        if (raw == null || raw.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PATH_INVALID");
        }
        String path = raw.replace('\\', '/').trim();
        if (path.startsWith("./")) path = path.substring(2);
        if (path.length() == 0 || path.startsWith("/") || path.startsWith("~")) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PATH_INVALID");
        }
        if (path.indexOf(':') >= 0 || path.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PATH_INVALID");
        }
        for (String segment : path.split("/")) {
            if (segment.length() == 0 || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("RUNTIME_SYNC_PATH_INVALID");
            }
        }
        return path;
    }

    /**
     * Streams one payload into staging, verifying size and digest as it goes.
     * Staged files live outside project/, so a rejected payload leaves the
     * mirror untouched. The temporary file is only renamed into place after
     * both checks pass, and the rename is the last thing that happens.
     */
    void stageFile(Entry entry, InputStream input) throws JSONException {
        if (phase != Phase.PLAN_READY && phase != Phase.STAGING) {
            throw new IllegalStateException("RUNTIME_SYNC_PHASE_INVALID");
        }
        if (staged.contains(entry.path)) {
            throw new IllegalArgumentException("RUNTIME_SYNC_DUPLICATE_FILE");
        }
        markStaging();
        File target = stagedFile(entry);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("RUNTIME_SYNC_STAGING_UNAVAILABLE");
        }
        File temp = new File(parent, entry.stagedName + ".part");
        MessageDigest digest = sha256();
        long count = 0L;
        byte[] buffer = new byte[64 * 1024];
        try {
            FileOutputStream output = new FileOutputStream(temp);
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    count += read;
                    if (count > entry.size) {
                        throw new IllegalArgumentException("RUNTIME_SYNC_SIZE_MISMATCH");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            } finally {
                output.close();
            }
            if (count != entry.size) {
                throw new IllegalArgumentException("RUNTIME_SYNC_SIZE_MISMATCH");
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(entry.sha256)) {
                throw new IllegalArgumentException("RUNTIME_SYNC_HASH_MISMATCH");
            }
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("RUNTIME_SYNC_STAGING_FAILED");
            }
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("RUNTIME_SYNC_STAGING_FAILED");
            }
            staged.add(entry.path);
            stagedFiles++;
            stagedBytes += count;
        } catch (IOException error) {
            throw new IllegalStateException("RUNTIME_SYNC_STAGING_FAILED", error);
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    /**
     * Installs the staged tree into project/. Mirrors P3's ordering: deletions
     * deepest-first, then directories shallow-first, then files.
     */
    void commit(File project) throws IOException {
        for (String path : deletesDeepestFirst()) {
            File target = resolve(project, path);
            if (target.isDirectory()) {
                File[] residual = target.listFiles();
                if (residual == null) throw new IOException("RUNTIME_SYNC_DELETE_LIST_FAILED: " + path);
                // The manifest deliberately omits caches. After all visible
                // child deletions, only ignored entries may remain. Delete
                // those inside this explicitly deleted private-mirror subtree;
                // never recursively erase an unexpected source file.
                for (File child : residual) {
                    String childPath = path + "/" + child.getName();
                    if (!ignorePolicy.isIgnored(childPath))
                        throw new IOException("RUNTIME_SYNC_DELETE_UNPLANNED_CHILD: " + childPath);
                    validateIgnoredTree(project, childPath);
                }
                for (File child : residual) removeIgnoredTree(project, path + "/" + child.getName());
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("RUNTIME_SYNC_DELETE_FAILED: " + path);
            }
        }
        List<Entry> ordered = entriesInPlanOrder();
        for (Entry entry : ordered) {
            if (!entry.directory) continue;
            File directory = resolve(project, entry.path);
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("RUNTIME_SYNC_MKDIR_FAILED");
            }
        }
        for (Entry entry : ordered) {
            if (entry.directory) continue;
            File target = resolve(project, entry.path);
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("RUNTIME_SYNC_MKDIR_FAILED");
            }
            File staged = stagedFile(entry);
            if (!staged.isFile()) throw new IOException("RUNTIME_SYNC_STAGED_FILE_MISSING");
            // Use unpredictable temporary name with .vega-transfer- prefix matching P3.
            // CREATE_NEW guarantees O_EXCL semantics: never truncates or overwrites
            // an existing legitimate project file.
            File installTemp = new File(parent, ".vega-transfer-"
                    + java.util.UUID.randomUUID().toString().replace("-", ""));
            try {
                copyExclusive(staged, installTemp);
                if (target.exists() && !target.delete()) {
                    throw new IOException("RUNTIME_SYNC_INSTALL_FAILED");
                }
                if (!installTemp.renameTo(target)) {
                    throw new IOException("RUNTIME_SYNC_INSTALL_FAILED");
                }
            } finally {
                if (installTemp.exists()) {
                    installTemp.delete();
                }
            }
        }
    }

    private static void validateIgnoredTree(File project, String path) throws IOException {
        File file = resolve(project, path); // No symlink traversal, including the leaf.
        java.nio.file.attribute.BasicFileAttributes attrs = java.nio.file.Files.readAttributes(
                file.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() && !attrs.isRegularFile())
            throw new IOException("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE: " + path);
        if (attrs.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("RUNTIME_SYNC_DELETE_LIST_FAILED: " + path);
            for (File child : children) validateIgnoredTree(project, path + "/" + child.getName());
        }
    }

    private static void removeIgnoredTree(File project, String path) throws IOException {
        File file = resolve(project, path);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("RUNTIME_SYNC_DELETE_LIST_FAILED: " + path);
            for (File child : children) removeIgnoredTree(project, path + "/" + child.getName());
        }
        if (!file.delete()) throw new IOException("RUNTIME_SYNC_DELETE_FAILED: " + path);
    }

    static File resolve(File project, String relative) throws IOException {
        // Delegated, not reimplemented: a commit must apply exactly the same
        // link rule as every other write. Canonicalising first would hide the
        // link it is supposed to refuse - project/alias -> victim canonicalises
        // to project/victim, which looks legal while the write lands on a file
        // the plan never named.
        return RuntimeWorkspace.resolveMirrorPath(project, relative);
    }

    private static void copyExclusive(File source, File target) throws IOException {
        if (!target.createNewFile()) {
            throw new IOException("TARGET_ALREADY_EXISTS: " + target);
        }
        InputStream input = new java.io.FileInputStream(source);
        try {
            FileOutputStream output = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            } finally {
                output.close();
            }
        } finally {
            input.close();
        }
    }

    /** Removes the staging tree; idempotent and safe to call on an aborted session. */
    void discard() {
        deleteTree(stagingRoot);
    }

    static void deleteTree(File root) {
        if (java.nio.file.Files.isSymbolicLink(root.toPath())) {
            root.delete();
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (root.exists()) root.delete();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
