package dev.wind.agent.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The companion's only workspace authority. Every path is derived below the
 * private filesDir/runtime-root tree; no SAF URI or external storage path enters
 * this class.
 */
public final class RuntimeWorkspace {
    public static final int PROTOCOL_VERSION = 2;
    /**
     * P6.3 Closure-2 mirror data-plane revision. Additive: a binding stays
     * protocol 2, but a host that does not see this capability must refuse to
     * start a P3 execution rather than discover the gap mid-sync.
     */
    public static final int MIRROR_STREAM_PROTOCOL_VERSION = 1;
    /** Serialized plan page budget, matching mirror_helper.py. */
    public static final int MAX_PLAN_PAGE_BYTES = 1024 * 1024;
    public static final int MAX_PLAN_PAGE_RECORDS = 2048;
    public static final int LAYOUT_VERSION = 1;
    /**
     * Bounded manifest paging: entry count *and* serialized bytes, matching the
     * P3 page budget (mirror_helper.py MANIFEST_PAGE_LIMIT / MANIFEST_PAGE_BYTES).
     */
    static final int MANIFEST_PAGE_ENTRIES = 2048;
    static final int MANIFEST_PAGE_BYTES = 1024 * 1024;
    private static final Pattern WORKSPACE_ID = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    /**
     * Matches physical paths (Windows, POSIX, file:// URIs, Android /data or /system)
     * so model-visible output never leaks host substrate paths.
     */
    private static final Pattern PHYSICAL_PATH = Pattern.compile(
            "(?:file://(?:/[A-Za-z]:|/[^\\s\\\"'<>\\]\\[(){};,]*)|[A-Za-z]:[\\\\/][^\\s\\\"'<>\\]\\[(){};,]*|/(?:data|system|apex|storage|sdcard|root|bin|sbin|usr|etc|tmp|var|proc|sys|dev|mnt|opt|run|home)(?:/[^\\s\\\"'<>\\]\\[(){};,]*)*)");

    private final File runtimeRoot;
    private final String providerId;
    private final File bindings;
    private final String runtimeInstanceId;
    /**
     * Live P3 mirror synchronisations, keyed by syncId.
     *
     * A sync is a transaction, not a sequence of writes: until COMMIT, the only
     * thing a sync may create or delete lives under its own staging root, so a
     * rejected or crashed sync leaves project/ byte-for-byte where it was.
     */
    private final Map<String, RuntimeMirrorSyncSession> syncSessions =
            new java.util.concurrent.ConcurrentHashMap<String, RuntimeMirrorSyncSession>();

    public static final class Binding {
        public final String providerId;
        public final String workspaceId;
        public final String projectId;
        public final long rootGeneration;
        public final String runtimeInstanceId;
        public final int protocolVersion;
        public final int layoutVersion;

        Binding(String provider, String workspace, String project, long generation,
                String instance, int protocol, int layout) {
            providerId = provider;
            workspaceId = workspace;
            projectId = project;
            rootGeneration = generation;
            runtimeInstanceId = instance;
            protocolVersion = protocol;
            layoutVersion = layout;
        }

        public boolean sameIdentity(Binding other) {
            return other != null && providerId.equals(other.providerId)
                    && workspaceId.equals(other.workspaceId)
                    && projectId.equals(other.projectId)
                    && rootGeneration == other.rootGeneration
                    && runtimeInstanceId.equals(other.runtimeInstanceId)
                    && protocolVersion == other.protocolVersion
                    && layoutVersion == other.layoutVersion;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("providerId", providerId)
                    .put("workspaceId", workspaceId)
                    .put("projectId", projectId)
                    .put("rootGeneration", rootGeneration)
                    .put("runtimeInstanceId", runtimeInstanceId)
                    .put("protocolVersion", protocolVersion)
                    .put("layoutVersion", layoutVersion);
        }

        static Binding fromJson(JSONObject json) throws JSONException {
            return new Binding(json.getString("providerId"),
                    json.getString("workspaceId"), json.getString("projectId"),
                    json.getLong("rootGeneration"),
                    json.getString("runtimeInstanceId"),
                    json.getInt("protocolVersion"), json.getInt("layoutVersion"));
        }
    }

    public RuntimeWorkspace(File filesDirectory) {
        this(filesDirectory, "VEGA_COMPANION", "runtime-root");
    }

    public RuntimeWorkspace(File filesDirectory, String provider, String rootName) {
        if (!("VEGA_COMPANION".equals(provider) && "runtime-root".equals(rootName))
                && !("VEGA_LINUX".equals(provider) && "linux-runtime-root".equals(rootName)))
            throw new IllegalArgumentException("RUNTIME_PROVIDER_MISMATCH");
        providerId = provider;
        if (filesDirectory == null) throw new IllegalArgumentException("RUNTIME_FILES_DIR_INVALID");
        try {
            File base = filesDirectory.getCanonicalFile();
            File requestedRoot = new File(base, rootName);
            runtimeRoot = requestedRoot.getCanonicalFile();
            requireBelow(runtimeRoot, base);
            bindings = new File(runtimeRoot, "bindings").getCanonicalFile();
            requireBelow(bindings, runtimeRoot);
            requireDirectoryBelow(runtimeRoot, base);
            requireDirectoryBelow(bindings, runtimeRoot);
            runtimeInstanceId = loadOrCreateInstanceId(runtimeRoot);
        } catch (IOException error) {
            throw new IllegalStateException("RUNTIME_ROOT_UNAVAILABLE", error);
        }
    }

    public String runtimeInstanceId() {
        return runtimeInstanceId;
    }

    public File workspaceDirectory(String workspaceId) {
        requireWorkspaceId(workspaceId);
        try {
            File rawWorkspace = new File(new File(runtimeRoot, "workspaces"), workspaceId);
            if (java.nio.file.Files.isSymbolicLink(rawWorkspace.toPath())) {
                throw new IllegalStateException("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY");
            }
            File candidate = rawWorkspace.getCanonicalFile();
            requireBelow(candidate, runtimeRoot);
            Boolean directory = classify(candidate);
            if (directory == null && candidate.exists()) {
                throw new IllegalStateException("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY");
            }
            return candidate;
        } catch (IOException error) {
            throw new IllegalArgumentException("RUNTIME_WORKSPACE_INVALID", error);
        }
    }

    public synchronized Binding open(String projectId, long rootGeneration,
                                     String workspaceId) {
        requireProjectId(projectId);
        requireGeneration(rootGeneration);
        requireWorkspaceId(workspaceId);
        Binding wanted = new Binding(providerId, workspaceId, projectId,
                rootGeneration, runtimeInstanceId, PROTOCOL_VERSION, LAYOUT_VERSION);
        File bindingFile = new File(bindings, projectId + ".json");
        Binding existing = read(bindingFile);
        if (existing != null && !existing.sameIdentity(wanted)) {
            throw new IllegalStateException("RUNTIME_BINDING_GENERATION_CONFLICT");
        }

        File workspace = workspaceDirectory(workspaceId);
        File marker = new File(new File(workspace, ".vega"), "runtime_binding.json");
        Binding marked = read(marker);
        if (marked != null && !marked.sameIdentity(wanted)) {
            throw new IllegalStateException("RUNTIME_WORKSPACE_CONFLICT");
        }

        requireDirectoryBelow(workspace, runtimeRoot);
        requireDirectoryBelow(new File(workspace, "project"), workspace);
        requireDirectoryBelow(new File(workspace, "home"), workspace);
        requireDirectoryBelow(new File(workspace, "tmp"), workspace);
        requireDirectoryBelow(new File(workspace, "cache"), workspace);
        requireDirectoryBelow(new File(workspace, "imports"), workspace);
        requireDirectoryBelow(new File(workspace, ".vega"), workspace);
        if (existing == null) persistBinding(bindingFile, wanted);
        if (marked == null) persistBinding(marker, wanted);
        return wanted;
    }

    public synchronized Binding requireBound(String projectId, long rootGeneration,
                                              String workspaceId, String instanceId) {
        requireProjectId(projectId);
        requireGeneration(rootGeneration);
        requireWorkspaceId(workspaceId);
        if (!runtimeInstanceId.equals(instanceId)) {
            throw new IllegalStateException("RUNTIME_INSTANCE_MISMATCH");
        }
        Binding binding = read(new File(bindings, projectId + ".json"));
        Binding wanted = new Binding(providerId, workspaceId, projectId,
                rootGeneration, runtimeInstanceId, PROTOCOL_VERSION, LAYOUT_VERSION);
        if (binding == null || !binding.sameIdentity(wanted)) {
            throw new IllegalStateException("RUNTIME_BINDING_NOT_OPEN");
        }
        return binding;
    }

    public synchronized Binding readBinding(String projectId) {
        requireProjectId(projectId);
        return read(new File(bindings, projectId + ".json"));
    }

    public File projectDirectory(String workspaceId) {
        File workspace = workspaceDirectory(workspaceId);
        try {
            File rawProject = new File(workspace, "project");
            if (java.nio.file.Files.isSymbolicLink(rawProject.toPath())) {
                throw new IllegalStateException("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY");
            }
            File project = rawProject.getCanonicalFile();
            requireBelow(project, workspace);
            Boolean directory = classify(project);
            if (directory == null || !directory.booleanValue() || !project.isDirectory()) {
                throw new IllegalStateException("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY");
            }
            return project;
        } catch (IOException error) {
            throw new IllegalStateException("RUNTIME_PROJECT_DIRECTORY_INVALID", error);
        }
    }

    public java.io.InputStream openReadStream(String workspaceId, String path) throws IOException {
        File project = projectDirectory(workspaceId);
        File file = new File(project, path).getCanonicalFile();
        requireBelow(file, project);
        if (!file.exists() || file.isDirectory()) throw new IOException("FILE_NOT_FOUND");
        return new FileInputStream(file);
    }

    /**
     * Resolves and verifies an execution working directory relative to project/.
     *
     * An empty or blank [relative] path resolves directly to project/. Non-empty
     * paths are resolved strictly component-by-component without traversing any
     * symlinks, guaranteeing that the child process cannot execute outside or
     * in an aliased structural root.
     */
    public File resolveWorkingDirectory(String workspaceId, String relative) {
        File project = projectDirectory(workspaceId);
        if (relative == null || relative.trim().isEmpty()) {
            return project;
        }
        File target;
        try {
            target = resolveMirrorPath(project, relative);
        } catch (IOException error) {
            throw new IllegalArgumentException("DIRECTORY_INVALID: " + relative, error);
        }
        Boolean directory = classify(target);
        if (directory == null || !directory.booleanValue() || !target.isDirectory()) {
            throw new IllegalArgumentException("DIRECTORY_INVALID: " + relative);
        }
        return target;
    }

    /**
     * The mirror operations that observe directory content and therefore need
     * the host's ignore policy. Metadata-only operations (read/write/edit/delete
     * of one explicit path) do not enumerate and do not take a policy.
     */
    private static boolean requiresIgnorePolicy(String operation) {
        switch (operation) {
            case "manifest":
            case "manifest_page":
            case "execution_list_dir":
            case "execution_glob":
            case "execution_search_files":
                return true;
            default:
                return false;
        }
    }

    /**
     * Dispatches one mirror RPC.
     *
     * [ignorePolicyJson] is the host's authority for what the mirror must never
     * enumerate. Byte-payload operations (file upload/download) are deliberately
     * absent: they travel over the sync-session transactions and a
     * ParcelFileDescriptor, never through this JSON envelope.
     */
    public JSONObject handleMirrorCall(String workspaceId, String operation,
                                       JSONObject args, JSONObject ignorePolicyJson)
            throws JSONException {
        File project = projectDirectory(workspaceId);
        IgnorePolicy policy = requiresIgnorePolicy(operation)
                ? IgnorePolicy.fromJson(ignorePolicyJson)
                : null;
        switch (operation) {
            case "manifest_page": return mirrorManifestPage(project, args, policy);
            case "manifest": return mirrorManifest(project, policy);
            case "fetch_text": return mirrorFetchText(project, args);
            case "write_file": return mirrorWriteFile(project, args);
            case "edit_file": return mirrorEditFile(project, args);
            case "delete_file": return mirrorDeleteFile(project, args);
            case "execution_read_file": return mirrorFetchText(project, args);
            case "execution_write_file": return mirrorWriteFile(project, args);
            case "execution_edit_file": return mirrorEditFile(project, args);
            case "execution_delete_file": return mirrorDeleteFile(project, args);
            case "execution_make_dir": return mirrorMakeDir(project, args);
            case "execution_file_info": return mirrorFileInfo(project, args);
            case "execution_list_dir": return mirrorListDir(project, args, policy);
            case "execution_glob": return mirrorGlob(project, args, policy);
            case "execution_search_files": return mirrorSearchFiles(project, args, policy);
            default: throw new IllegalArgumentException("RUNTIME_MIRROR_OPERATION_UNKNOWN");
        }
    }

    /**
     * The content digest of the mirror as it stands right now, computed with the
     * host's ignore policy. Used by the sync session's begin/commit CAS checks.
     */
    public String mirrorDigest(String workspaceId, JSONObject ignorePolicyJson)
            throws JSONException {
        return mirrorManifest(projectDirectory(workspaceId),
                IgnorePolicy.fromJson(ignorePolicyJson)).getString("manifestDigest");
    }

    /**
     * Opens a P3 sync transaction and performs the FIRST compare-and-set.
     *
     * The host planned against [expectedRemoteDigest]; if the mirror has moved
     * since, the plan describes a tree that no longer exists and not one byte of
     * it may be applied. Nothing below project/ is touched before this passes,
     * and nothing is touched after it either - only the staging root is created.
     */
    public JSONObject syncBegin(String workspaceId, JSONObject args) throws JSONException {
        File workspace = workspaceDirectory(workspaceId);
        File project = projectDirectory(workspaceId);
        String expected = args.getString("expectedRemoteDigest");
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("MIRROR_REMOTE_DIGEST_INVALID");
        }
        IgnorePolicy policy = IgnorePolicy.fromJson(args.optJSONObject("ignorePolicy"));
        String syncId = args.getString("syncId");
        if (!syncId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("RUNTIME_SYNC_ID_INVALID");
        }
        JSONObject capacity = args.optJSONObject("plan");
        long plannedFiles = capacity == null ? -1L : capacity.optLong("files", -1L);
        long plannedBytes = capacity == null ? -1L : capacity.optLong("bytes", -1L);
        if ((plannedFiles >= 0L && plannedFiles > RuntimeMirrorSyncSession.MAX_FILES)
                || (plannedBytes >= 0L && plannedBytes > RuntimeMirrorSyncSession.MAX_TOTAL_BYTES)) {
            throw new IllegalArgumentException("RUNTIME_SYNC_SIZE_LIMIT");
        }

        String actual = mirrorManifest(project, policy).getString("manifestDigest");
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException("STALE_REMOTE_BEFORE_SYNC");
        }

        File stagingRoot;
        try {
            stagingRoot = new File(new File(workspace, ".vega"), "sync/" + syncId).getCanonicalFile();
            requireBelow(stagingRoot, workspace);
        } catch (IOException error) {
            throw new IllegalStateException("RUNTIME_SYNC_STAGING_UNAVAILABLE", error);
        }
        RuntimeMirrorSyncSession session = new RuntimeMirrorSyncSession(
                syncId, expected, policy, stagingRoot,
                workspaceId, args.getString("projectId"),
                args.getLong("rootGeneration"), args.getString("runtimeInstanceId"));
        // Claim the id atomically. A live syncId belongs to the transaction that
        // opened it: two concurrent BEGINs must not both believe they own it,
        // and a silent re-BEGIN would turn a host bug into a partially applied
        // mirror instead of a refusal.
        if (syncSessions.putIfAbsent(syncId, session) != null) {
            throw new IllegalStateException("RUNTIME_SYNC_ALREADY_OPEN");
        }
        sweepAbandonedStaging(workspace);
        // Only now that the id is claimed can the tree be swept; a crash may
        // have left the same id behind, and it is not a live session.
        RuntimeMirrorSyncSession.deleteTree(stagingRoot);
        return new JSONObject()
                .put("ok", true)
                .put("syncId", syncId)
                .put("remoteDigest", actual);
    }

    /**
     * Removes staging roots that no live session owns and that are old enough to
     * be debris.
     *
     * Sync state is deliberately in-memory: a sync never survives its service,
     * so after a process death nobody can name the syncId that was abandoned and
     * nothing would ever collect it. Sweeping on the next BEGIN bounds that leak
     * without touching a session that is merely slow - the age threshold is what
     * separates the two, and a live session is skipped outright.
     */
    private void sweepAbandonedStaging(File workspace) {
        File syncRoot = new File(new File(workspace, ".vega"), "sync");
        File[] children = syncRoot.listFiles();
        if (children == null) return;
        long cutoff = System.currentTimeMillis() - RuntimeMirrorSyncSession.STALE_STAGING_MILLIS;
        for (File child : children) {
            if (syncSessions.containsKey(child.getName())) continue;
            if (child.lastModified() > cutoff) continue;
            RuntimeMirrorSyncSession.deleteTree(child);
        }
    }

    /**
     * Records one bounded plan page. The payload is metadata (bounded by
     * MAX_PLAN_PAGE_BYTES), never file content, and every field is re-validated
     * here even though the host wrote it.
     */
    public JSONObject syncPlanPage(String workspaceId, JSONObject args, byte[] payload)
            throws JSONException {
        projectDirectory(workspaceId);
        RuntimeMirrorSyncSession session = requireSyncSession(workspaceId, args);
        if (payload == null) throw new IllegalArgumentException("RUNTIME_SYNC_PLAN_PAGE_INVALID");
        if (payload.length == 0 || payload.length > MAX_PLAN_PAGE_BYTES) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PLAN_PAGE_LIMIT");
        }
        JSONObject page;
        try {
            page = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PLAN_PAGE_INVALID");
        }
        JSONArray deleted = page.optJSONArray("deleted");
        JSONArray entries = page.optJSONArray("entries");
        if ((deleted == null) == (entries == null)) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PLAN_PAGE_KIND");
        }
        JSONArray values = deleted != null ? deleted : entries;
        if (values.length() == 0 || values.length() > MAX_PLAN_PAGE_RECORDS) {
            throw new IllegalArgumentException("RUNTIME_SYNC_PLAN_PAGE_COUNT");
        }
        session.addPage(deleted, entries);
        return new JSONObject()
                .put("ok", true)
                .put("plannedFiles", session.fileCount())
                .put("plannedBytes", session.totalBytes());
    }

    /**
     * Streams one planned file into staging.
     *
     * The companion re-reads size and SHA-256 from the bytes themselves: the
     * plan's promises are what the host intended to send, and only the received
     * stream can show whether that is what arrived.
     */
    public JSONObject syncFile(String workspaceId, JSONObject args, java.io.InputStream payload)
            throws JSONException {
        projectDirectory(workspaceId);
        RuntimeMirrorSyncSession session = requireSyncSession(workspaceId, args);
        String path = args.getString("path");
        session.stageFile(session.entry(path), payload);
        return new JSONObject()
                .put("ok", true)
                .put("stagedFiles", session.stagedCount())
                .put("stagedBytes", session.stagedBytes());
    }

    /**
     * Installs the staged tree - after the SECOND compare-and-set.
     *
     * The mirror is re-read here rather than trusted from BEGIN because staging
     * a large payload takes time, and a project that changed during that window
     * would otherwise be overwritten with a tree planned against its old self.
     */
    public JSONObject syncCommit(String workspaceId, JSONObject args) throws JSONException {
        File project = projectDirectory(workspaceId);
        RuntimeMirrorSyncSession session = requireSyncSession(workspaceId, args);
        session.requireComplete();
        String actual = mirrorManifest(project, session.ignorePolicy).getString("manifestDigest");
        if (!actual.equalsIgnoreCase(session.expectedRemoteDigest())) {
            session.markAborted();
            session.discard();
            syncSessions.remove(session.syncId());
            throw new IllegalStateException("STALE_REMOTE_BEFORE_COMMIT");
        }
        session.markCommitting();
        try {
            session.commit(project);
        } catch (IOException error) {
            session.markAborted();
            throw new IllegalStateException("RUNTIME_SYNC_COMMIT_FAILED: " + scrub(error.getMessage()), error);
        } finally {
            session.discard();
            syncSessions.remove(session.syncId());
        }
        session.markDone();
        return new JSONObject()
                .put("ok", true)
                .put("remoteDigest",
                        mirrorManifest(project, session.ignorePolicy).getString("manifestDigest"))
                .put("transferredFiles", session.fileCount())
                .put("transferredBytes", session.totalBytes())
                .put("deletedPaths", session.deleteCount());
    }

    /**
     * Ends a sync. Idempotent by design: the host calls it from a failure path
     * where the first thing to fail may well have been the abort itself.
     */
    public JSONObject syncAbort(String workspaceId, JSONObject args) throws JSONException {
        projectDirectory(workspaceId);
        String syncId = args.getString("syncId");
        RuntimeMirrorSyncSession session = syncSessions.get(syncId);
        if (session != null) {
            // Aborting someone else's sync would be a way to cancel a live
            // transaction across projects; the owner check applies here too.
            session.assertOwner(workspaceId, args.getString("projectId"),
                    args.getLong("rootGeneration"), args.getString("runtimeInstanceId"));
            syncSessions.remove(syncId);
        }
        if (session != null) {
            session.markAborted();
            session.discard();
        } else {
            File stagingRoot = new File(new File(workspaceDirectory(workspaceId), ".vega"),
                    "sync/" + syncId);
            try {
                requireBelow(stagingRoot.getCanonicalFile(), workspaceDirectory(workspaceId));
            } catch (IOException error) {
                throw new IllegalArgumentException("RUNTIME_SYNC_ID_INVALID", error);
            }
            RuntimeMirrorSyncSession.deleteTree(stagingRoot);
        }
        return new JSONObject().put("ok", true).put("syncId", syncId);
    }

    private RuntimeMirrorSyncSession requireSyncSession(String workspaceId, JSONObject args)
            throws JSONException {
        RuntimeMirrorSyncSession session = syncSessions.get(args.getString("syncId"));
        if (session == null) throw new IllegalStateException("RUNTIME_SYNC_UNKNOWN");
        session.assertOwner(workspaceId, args.getString("projectId"),
                args.getLong("rootGeneration"), args.getString("runtimeInstanceId"));
        return session;
    }

    /** Test seam: how many sync transactions are still open. */
    int openSyncSessions() {
        return syncSessions.size();
    }

    private static final class MirrorFileEntry {
        final String path;
        final boolean isDir;
        final long size;
        final long modified;
        final String sha256;
        MirrorFileEntry(String path, boolean isDir, long size, long modified, String sha256) {
            this.path = path;
            this.isDir = isDir;
            this.size = size;
            this.modified = modified;
            this.sha256 = sha256;
        }
    }

    JSONObject mirrorManifest(File project, IgnorePolicy policy) throws JSONException {
        return mirrorManifestPage(project,
                new JSONObject().put("offset", 0).put("limit", MANIFEST_PAGE_ENTRIES), policy);
    }

    private JSONObject mirrorManifestPage(File project, JSONObject args, IgnorePolicy policy)
            throws JSONException {
        List<MirrorFileEntry> entries = new ArrayList<>();
        scanProjectFiles(project, "", entries, policy);
        Collections.sort(entries, new Comparator<MirrorFileEntry>() {
            @Override public int compare(MirrorFileEntry a, MirrorFileEntry b) {
                return a.path.compareTo(b.path);
            }
        });

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        for (MirrorFileEntry e : entries) {
            appendDigestString(md, e.path);
            appendDigestString(md, e.isDir ? "D" : "F");
            appendDigestString(md, String.valueOf(e.size));
            appendDigestString(md, e.sha256 != null ? e.sha256 : "-");
        }

        String digest = bytesToHex(md.digest());
        String expectedDigest = args.optString("expectedDigest", "");
        if (!expectedDigest.isEmpty() && !expectedDigest.equalsIgnoreCase(digest)) {
            throw new IllegalStateException("MIRROR_MANIFEST_DIGEST_MISMATCH");
        }

        int offset = Math.max(0, args.optInt("offset", 0));
        int limit = Math.max(1, Math.min(args.optInt("limit", MANIFEST_PAGE_ENTRIES), MANIFEST_PAGE_ENTRIES));
        JSONArray array = new JSONArray();
        // The budget is on the serialized page, not on the sum of its items.
        // P3 measures len(json.dumps(page)), which includes the brackets and the
        // separator commas; counting item bytes alone would let a page of 2048
        // entries exceed the budget by the framing it forgot.
        int itemBytesTotal = 0;
        int cursor = Math.min(offset, entries.size());
        while (cursor < entries.size() && array.length() < limit) {
            MirrorFileEntry e = entries.get(cursor);
            JSONObject item = new JSONObject()
                    .put("path", e.path)
                    .put("directory", e.isDir)
                    .put("size", e.size)
                    .put("modified", e.modified);
            if (e.sha256 != null) item.put("sha256", e.sha256);
            // The count limit alone does not bound the reply: a page of long
            // paths would still exceed what a Binder reply must carry. Stop
            // before the serialized page passes the byte budget, and refuse the
            // single entry that cannot fit on its own.
            int itemBytes = item.toString().getBytes(StandardCharsets.UTF_8).length;
            int pageBytesIfAdded = itemBytesTotal + itemBytes + framingBytes(array.length() + 1);
            if (pageBytesIfAdded > MANIFEST_PAGE_BYTES && array.length() == 0) {
                throw new IllegalStateException("MANIFEST_ENTRY_TOO_LARGE");
            }
            if (array.length() > 0 && pageBytesIfAdded > MANIFEST_PAGE_BYTES) {
                break;
            }
            itemBytesTotal += itemBytes;
            array.put(item);
            cursor++;
        }
        int serializedBytes = itemBytesTotal + framingBytes(array.length());
        int nextOffset = cursor < entries.size() ? cursor : -1;
        return new JSONObject()
                .put("ok", true)
                .put("entries", array)
                .put("offset", offset)
                .put("digest", digest)
                .put("totalEntries", entries.size())
                .put("nextOffset", nextOffset)
                .put("pageBytes", serializedBytes)
                .put("manifestDigest", digest);
    }

    /**
     * The bytes a JSON array spends on punctuation for [count] items:
     * "[" and "]" plus one comma between each pair.
     */
    private static int framingBytes(int count) {
        if (count <= 0) return 2;
        return 2 + (count - 1);
    }

    private JSONObject mirrorFetchText(File project, JSONObject args) throws JSONException {
        String path = args.getString("path");
        File file = new File(project, path);
        try {
            File canonical = requireRegularFileBelow(file, project);
            return new JSONObject().put("ok", true).put("text", readText(canonical));
        } catch (IOException e) {
            throw new IllegalArgumentException("FILE_NOT_FOUND", e);
        }
    }

    private JSONObject mirrorWriteFile(File project, JSONObject args) throws JSONException {
        String path = args.getString("path");
        String content = args.getString("content");
        File file = new File(project, path);
        try {
            File canonical = requireNoLinkTraversal(file, project);
            requireDirectoryBelowNoSymlink(canonical.getParentFile(), project);
            writeAtomic(file, content);
            return new JSONObject().put("ok", true);
        } catch (IOException e) {
            throw new IllegalStateException("RUNTIME_MIRROR_WRITE_FAILED", e);
        }
    }

    private JSONObject mirrorEditFile(File project, JSONObject args) throws JSONException {
        String path = args.getString("path");
        String oldText = args.getString("old_text");
        String newText = args.getString("new_text");
        File file = new File(project, path);
        try {
            File canonical = requireRegularFileBelow(file, project);
            String current = readText(canonical);
            if (!current.contains(oldText)) throw new IllegalStateException("EDIT_TARGET_MISMATCH");
            String updated = current.replace(oldText, newText);
            writeAtomic(file, updated);
            return new JSONObject().put("ok", true);
        } catch (IOException e) {
            throw new IllegalStateException("RUNTIME_MIRROR_EDIT_FAILED", e);
        }
    }

    private JSONObject mirrorDeleteFile(File project, JSONObject args) throws JSONException {
        String path = args.getString("path");
        if (path == null || path.trim().isEmpty()) {
            // Cleaning the root would delete the execution root itself.
            throw new IllegalArgumentException("ROOT_MUTATION_FORBIDDEN");
        }
        File file = new File(project, path);
        try {
            File canonical = requireNoLinkTraversal(file, project);
            Boolean directory = classify(canonical);
            if (directory == null) {
                // A special file must not be silently ignored or unlinked by
                // name alone: the caller asked about a regular file or a
                // directory and this is neither.
                if (!canonical.exists()) return new JSONObject().put("ok", true);
                throw new IllegalArgumentException("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE");
            }
            if (canonical.exists() && !canonical.delete()) {
                throw new IOException("MIRROR_DELETE_FAILED");
            }
            return new JSONObject().put("ok", true);
        } catch (IOException e) {
            throw new IllegalStateException("RUNTIME_MIRROR_DELETE_FAILED", e);
        }
    }

    /**
     * Resolves one mirror file for a byte-capable read (the download direction).
     *
     * The reply is metadata only; the bytes leave through a
     * ParcelFileDescriptor, so a payload never becomes a Base64 Parcel string
     * and never exists as one giant in-memory document on either side.
     *
     * [expectedSize] and [maxBytes] are the host's promise about the file it
     * planned to read. They are enforced here as well as by the reader, because
     * a mirror file that changed between the plan and the read is exactly the
     * case the digest agreement cannot catch on its own.
     */
    public JSONObject openReadFile(String workspaceId, String path, long expectedSize, long maxBytes)
            throws JSONException {
        File project = projectDirectory(workspaceId);
        return openReadFile(project, path, expectedSize, maxBytes);
    }

    private JSONObject openReadFile(File project, String path, long expectedSize, long maxBytes)
            throws JSONException {
        File file = new File(project, path);
        try {
            File canonical = requireRegularFileBelow(file, project);
            long size = canonical.length();
            if (maxBytes >= 0L && size > maxBytes) {
                throw new IllegalStateException("MIRROR_FILE_SIZE_EXCEEDS_MAX_BYTES");
            }
            if (expectedSize >= 0L && size != expectedSize) {
                throw new IllegalStateException("MIRROR_FILE_SIZE_MISMATCH");
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("path", canonical.getPath())
                    .put("size", size);
        } catch (IOException e) {
            throw new IllegalArgumentException("FILE_NOT_FOUND", e);
        }
    }

    private JSONObject mirrorMakeDir(File project, JSONObject args) throws JSONException {
        String path = args.getString("path");
        File file = new File(project, path);
        try {
            requireDirectoryBelowNoSymlink(file, project);
            return new JSONObject().put("ok", true).put("path", path);
        } catch (IOException e) {
            throw new IllegalStateException("RUNTIME_MIRROR_MAKEDIR_FAILED", e);
        }
    }

    private JSONObject mirrorFileInfo(File project, JSONObject args) throws JSONException {
        String path = args.getString("path");
        File file = new File(project, path);
        try {
            File canonical = requireNoLinkTraversal(file, project);
            Boolean directory = classify(canonical);
            if (directory == null && canonical.exists()) {
                throw new IllegalArgumentException("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE");
            }
            return new JSONObject()
                    .put("ok", true)
                    .put("exists", canonical.exists())
                    .put("size", directory != null && !directory.booleanValue() ? canonical.length() : 0L)
                    .put("directory", directory != null && directory.booleanValue())
                    .put("modified", canonical.lastModified());
        } catch (IOException e) {
            throw new IllegalArgumentException("FILE_NOT_FOUND", e);
        }
    }

    private JSONObject mirrorListDir(File project, JSONObject args, IgnorePolicy policy)
            throws JSONException {
        String path = args.optString("path", "");
        try {
            File targetDir = path.isEmpty() ? project : resolveMirrorPath(project, path);
            Boolean dirClass = classify(targetDir);
            if (dirClass == null || !dirClass.booleanValue() || !targetDir.isDirectory()) {
                throw new IllegalArgumentException("DIRECTORY_INVALID");
            }
            File[] list = targetDir.listFiles();
            JSONArray arr = new JSONArray();
            if (list != null) {
                for (File f : list) {
                    String name = f.getName();
                    if (policy.isIgnored(name)) continue;
                    // P3's listing() stats each child with follow_symlinks=False
                    // and refuses anything that is neither a regular file nor a
                    // directory, so a listing that quietly dropped such a child
                    // would describe a different tree than the manifest does.
                    Boolean directory = classify(f);
                    if (directory == null) {
                        throw new IllegalArgumentException(
                                "MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE: " + name);
                    }
                    arr.put(new JSONObject()
                            .put("name", name)
                            .put("directory", directory.booleanValue())
                            .put("size", directory.booleanValue() ? 0L : f.length())
                            .put("modified", f.lastModified()));
                }
            }
            return new JSONObject().put("ok", true).put("files", arr);
        } catch (IOException e) {
            throw new IllegalArgumentException("DIRECTORY_INVALID", e);
        }
    }

    private JSONObject mirrorGlob(File project, JSONObject args, IgnorePolicy policy)
            throws JSONException {
        List<MirrorFileEntry> entries = new ArrayList<>();
        scanProjectFiles(project, "", entries, policy);
        String pattern = args.getString("pattern").replace('\\', '/').trim();
        JSONArray arr = new JSONArray();
        for (MirrorFileEntry e : entries) {
            if (!e.isDir && globMatches(pattern, e.path)) {
                arr.put(e.path);
            }
        }
        return new JSONObject().put("ok", true).put("matches", arr);
    }

    private JSONObject mirrorSearchFiles(File project, JSONObject args, IgnorePolicy policy)
            throws JSONException {
        String query = args.getString("query");
        List<MirrorFileEntry> entries = new ArrayList<>();
        scanProjectFiles(project, "", entries, policy);
        JSONArray arr = new JSONArray();
        for (MirrorFileEntry e : entries) {
            if (!e.isDir && e.size < 1024 * 1024) {
                try {
                    String txt = readText(requireRegularFileBelow(new File(project, e.path), project));
                    if (txt.contains(query)) arr.put(e.path);
                } catch (IOException ignored) {}
            }
        }
        return new JSONObject().put("ok", true).put("matches", arr);
    }

    private static boolean globMatches(String pattern, String path) {
        String regex = pattern.replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*")
                .replace("?", ".");
        return path.matches(regex);
    }

    /**
     * The workspace ignore policy, supplied by the host exactly once per sync
     * session.
     *
     * There is deliberately no built-in list: the Host's
     * WorkspaceIgnorePolicy.kt is the ONLY authority, and the companion applies
     * what it is given. A second private list here is how the two digests
     * drifted apart, and with them every "is the mirror in sync" answer.
     */
    public static final class IgnorePolicy {
        private final String[] segments;
        private final String[] suffixes;

        IgnorePolicy(String[] segments, String[] suffixes) {
            this.segments = segments == null ? new String[0] : segments.clone();
            this.suffixes = suffixes == null ? new String[0] : suffixes.clone();
        }

        public static IgnorePolicy fromJson(JSONObject policy) throws JSONException {
            if (policy == null) {
                throw new IllegalArgumentException("MIRROR_IGNORE_POLICY_MISSING");
            }
            JSONArray segmentArray = policy.optJSONArray("ignoredSegments");
            JSONArray suffixArray = policy.optJSONArray("ignoredSuffixes");
            if (segmentArray == null || suffixArray == null) {
                throw new IllegalArgumentException("MIRROR_IGNORE_POLICY_INVALID");
            }
            String[] segments = new String[segmentArray.length()];
            for (int i = 0; i < segmentArray.length(); i++) {
                String value = segmentArray.getString(i);
                if (value == null || value.length() == 0 || value.indexOf('/') >= 0) {
                    throw new IllegalArgumentException("MIRROR_IGNORE_POLICY_INVALID");
                }
                segments[i] = value;
            }
            String[] suffixes = new String[suffixArray.length()];
            for (int i = 0; i < suffixArray.length(); i++) {
                String value = suffixArray.getString(i);
                if (value == null || value.length() == 0 || value.indexOf('/') >= 0) {
                    throw new IllegalArgumentException("MIRROR_IGNORE_POLICY_INVALID");
                }
                suffixes[i] = value;
            }
            return new IgnorePolicy(segments, suffixes);
        }

        public boolean isIgnored(String path) {
            String clean = path.replace('\\', '/').trim();
            while (clean.startsWith("/")) clean = clean.substring(1);
            while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            String[] parts = clean.split("/");
            for (String segment : parts) {
                for (String ignored : segments) {
                    if (segment.equals(ignored)) return true;
                }
                for (String suffix : suffixes) {
                    if (segment.endsWith(suffix)) return true;
                }
            }
            return false;
        }

        public JSONArray toSegments() {
            JSONArray array = new JSONArray();
            for (String value : segments) array.put(value);
            return array;
        }

        public JSONArray toSuffixes() {
            JSONArray array = new JSONArray();
            for (String value : suffixes) array.put(value);
            return array;
        }
    }

    /** A private mirror contains only the paths the mirror itself created. */
    private static void scanProjectFiles(File dir, String prefix, List<MirrorFileEntry> out,
                                         IgnorePolicy policy) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            String rel = prefix.isEmpty() ? name : prefix + "/" + name;
            if (policy.isIgnored(rel)) continue;
            Boolean directory = classify(f);
            if (directory == null) {
                // P3 stats every entry with follow_symlinks=False and raises
                // MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE for anything that is
                // neither a regular file nor a directory. Skipping instead
                // would make the two providers disagree about the same tree -
                // and, worse, a link inside project/ would be invisible to the
                // digest while still being followable by a later write.
                throw new IllegalStateException("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE: " + rel);
            }
            if (directory.booleanValue()) {
                out.add(new MirrorFileEntry(rel, true, 0L, f.lastModified(), null));
                scanProjectFiles(f, rel, out, policy);
            } else {
                try {
                    out.add(scanRegularFile(f, rel));
                } catch (IOException error) {
                    throw new IllegalStateException("MIRROR_SCAN_FAILED: " + rel, error);
                }
            }
        }
    }

    /**
     * True for a directory, false for a regular file, null for anything else.
     *
     * [File.isDirectory]/[File.isFile] follow symlinks, so they answer "what is
     * at the end of this link" rather than "what is here". The P3 helper uses
     * lstat/O_NOFOLLOW for exactly this reason: a symlinked directory must not
     * become a subtree of the mirror.
     */
    private static Boolean classify(File file) {
        try {
            java.nio.file.Path path = file.toPath();
            if (java.nio.file.Files.isSymbolicLink(path)) return null;
            java.nio.file.attribute.BasicFileAttributes attributes =
                    java.nio.file.Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory()) return Boolean.TRUE;
            if (attributes.isRegularFile()) return Boolean.FALSE;
            return null;
        } catch (IOException error) {
            return null;
        }
    }

    /**
     * Canonicalizes [candidate] below [root] without following a link that is
     * itself part of the path. Every mirror operation that names a path goes
     * through here, so "project/link/secret" is a path error rather than a read
     * from wherever the link points.
     */
    private static File requireNoLinkTraversal(File candidate, File root) throws IOException {
        java.nio.file.Path canonicalRoot = root.getCanonicalFile().toPath();
        java.nio.file.Path raw = candidate.toPath().toAbsolutePath().normalize();
        if (raw.equals(canonicalRoot)) {
            // The root itself is the one path with no component to check.
            return canonicalRoot.toFile();
        }
        if (!raw.startsWith(canonicalRoot)) {
            throw new IllegalArgumentException("RUNTIME_PATH_ESCAPE");
        }
        String relative = canonicalRoot.relativize(raw).toString().replace('\\', '/');
        return resolveMirrorPath(root, relative);
    }

    /** [requireNoLinkTraversal] plus "and it is a regular file". */
    private static File requireRegularFileBelow(File candidate, File root) throws IOException {
        File canonical = requireNoLinkTraversal(candidate, root);
        Boolean directory = classify(canonical);
        if (directory == null) {
            if (!canonical.exists()) throw new IllegalArgumentException("FILE_NOT_FOUND");
            throw new IllegalArgumentException("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE");
        }
        if (directory.booleanValue()) throw new IllegalArgumentException("FILE_NOT_FOUND");
        return canonical;
    }

    /**
     * Resolves a mirror-relative path component by component, refusing any
     * component that is a link.
     *
     * This is the equivalent of the P3 helper's dir_fd + O_NOFOLLOW walk, and
     * the ORDER matters: canonicalising first would resolve the very link this
     * is meant to reject, so "project/alias -> victim" would quietly become
     * "project/victim" and a write planned for alias would land on victim.
     * Each name is therefore tested against the untouched path, from the root
     * downwards, before anything is opened or written.
     */
    static File resolveMirrorPath(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty()) {
            throw new IllegalArgumentException("RUNTIME_PATH_ESCAPE");
        }
        java.nio.file.Path canonicalRoot = root.getCanonicalFile().toPath();
        java.nio.file.Path current = canonicalRoot;
        for (String segment : relative.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("RUNTIME_PATH_ESCAPE");
            }
            if (segment.indexOf(':') >= 0 || segment.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("RUNTIME_PATH_ESCAPE");
            }
            current = current.resolve(segment);
            if (java.nio.file.Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("MIRROR_UNSUPPORTED_LINK_OR_SPECIAL_FILE");
            }
        }
        File resolved = current.toFile();
        requireBelow(resolved, canonicalRoot.toFile());
        return resolved;
    }

    /**
     * Creates or validates a directory below [root] without following links,
     * mirroring [requireDirectoryBelow] for the operations that accept a
     * model-supplied path.
     */
    private static void requireDirectoryBelowNoSymlink(File directory, File root) throws IOException {
        File canonical = requireNoLinkTraversal(directory, root);
        if (canonical.isDirectory()) return;
        if (canonical.exists() || !canonical.mkdirs()) {
            throw new IllegalStateException("RUNTIME_DIRECTORY_UNAVAILABLE");
        }
        requireNoLinkTraversal(canonical, root);
    }

    /**
     * Hashes the WHOLE file as a stream and proves the file did not move under
     * the scan.
     *
     * The previous implementation read at most 16 MiB in a single
     * InputStream.read() and recorded the full length as the size, so a file
     * larger than the window produced a manifest entry whose size and hash
     * disagreed with each other, and a single read() is not even guaranteed to
     * fill its buffer. Both defects are silent: the mirror identity simply
     * stopped matching. Reading in bounded chunks and re-checking size and
     * mtime afterwards makes the entry either true or an explicit failure.
     */
    private static MirrorFileEntry scanRegularFile(File file, String rel) throws IOException {
        long sizeBefore = file.length();
        long modifiedBefore = file.lastModified();
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        FileInputStream input = new FileInputStream(file);
        long count = 0L;
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                count += read;
            }
        } finally {
            input.close();
        }
        long sizeAfter = file.length();
        long modifiedAfter = file.lastModified();
        if (sizeBefore != sizeAfter || modifiedBefore != modifiedAfter || count != sizeBefore) {
            throw new IllegalStateException("MIRROR_CHANGED_DURING_SCAN: " + rel);
        }
        return new MirrorFileEntry(rel, false, count, modifiedAfter, bytesToHex(digest.digest()));
    }

    private static void appendDigestString(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int len = bytes.length;
        md.update((byte) (len >>> 24));
        md.update((byte) (len >>> 16));
        md.update((byte) (len >>> 8));
        md.update((byte) len);
        md.update(bytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public String scrub(String text) {
        if (text == null || text.length() == 0) return "";
        try {
            String root = runtimeRoot.getCanonicalPath();
            String slash = root.replace(File.separatorChar, '/');
            String sanitized = text.replace(root, "workspace://runtime-root")
                    .replace(slash, "workspace://runtime-root")
                    .replace(root.replace('/', '\\'), "workspace://runtime-root");
            Matcher matcher = PHYSICAL_PATH.matcher(sanitized);
            return matcher.replaceAll("workspace://path");
        } catch (IOException error) {
            return "workspace://path";
        }
    }

    private static void requireWorkspaceId(String id) {
        if (id == null || !WORKSPACE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("RUNTIME_WORKSPACE_ID_INVALID");
        }
    }

    private static void requireProjectId(String id) {
        if (id == null || !PROJECT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("RUNTIME_PROJECT_ID_INVALID");
        }
    }

    private static void requireGeneration(long generation) {
        if (generation < 0L) throw new IllegalArgumentException("RUNTIME_ROOT_GENERATION_INVALID");
    }

    private static void requireBelow(File candidate, File root) throws IOException {
        String child = candidate.getCanonicalPath();
        String rootPath = root.getCanonicalPath();
        String parent = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
        if (!child.equals(rootPath) && !child.startsWith(parent)) {
            throw new IllegalArgumentException("RUNTIME_PATH_ESCAPE");
        }
    }

    private static void requireDirectoryBelow(File directory, File root) {
        try {
            if (java.nio.file.Files.isSymbolicLink(directory.toPath())) {
                throw new IllegalStateException("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY");
            }
            File canonical = directory.getCanonicalFile();
            requireBelow(canonical, root);
            Boolean dirClass = classify(canonical);
            if (dirClass == null && canonical.exists()) {
                throw new IllegalStateException("MIRROR_ROOT_SYMLINK_OR_NON_DIRECTORY");
            }
            if (canonical.isDirectory()) return;
            if (canonical.exists() || !canonical.mkdirs()) {
                throw new IllegalStateException("RUNTIME_DIRECTORY_UNAVAILABLE");
            }
            requireBelow(canonical, root);
        } catch (IOException error) {
            throw new IllegalStateException("RUNTIME_DIRECTORY_UNAVAILABLE", error);
        }
    }

    private static void requireDirectory(File directory) {
        if (directory.isDirectory()) return;
        if (directory.exists() || !directory.mkdirs()) {
            throw new IllegalStateException("RUNTIME_DIRECTORY_UNAVAILABLE");
        }
    }

    private static Binding read(File file) {
        recoverAtomic(file);
        if (!file.isFile()) return null;
        try {
            return Binding.fromJson(new JSONObject(readText(file)));
        } catch (Exception error) {
            throw new IllegalStateException("RUNTIME_BINDING_CORRUPT", error);
        }
    }

    private static void persistBinding(File file, Binding binding) {
        try {
            writeAtomic(file, binding.toJson().toString());
        } catch (JSONException | IOException error) {
            throw new IllegalStateException("RUNTIME_BINDING_WRITE_FAILED", error);
        }
    }

    private static String loadOrCreateInstanceId(File root) throws IOException {
        File file = new File(root, "runtime_instance_id");
        recoverAtomic(file);
        if (file.isFile()) {
            String value = readText(file).trim();
            if (INSTANCE_ID.matcher(value).matches()) return value;
            throw new IllegalStateException("RUNTIME_INSTANCE_ID_CORRUPT");
        }
        String value = "companion-" + UUID.randomUUID().toString();
        writeAtomic(file, value + "\n");
        return value;
    }

    private static String readText(File file) throws IOException {
        java.io.FileInputStream input = new java.io.FileInputStream(file);
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1024 * 1024)];
            int count = input.read(bytes);
            return new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    /** Recover an old value if a process died after the backup rename. */
    private static void recoverAtomic(File file) {
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        if (!file.isFile() && backup.isFile()) {
            backup.renameTo(file);
        } else if (file.isFile() && backup.isFile()) {
            // The new value was installed; the backup is now stale.
            backup.delete();
        }
    }

    private static void writeAtomic(File file, String text) throws IOException {
        File parent = file.getParentFile();
        requireDirectory(parent);
        File temp = new File(parent, file.getName() + ".new");
        File backup = new File(parent, file.getName() + ".bak");
        FileOutputStream output = new FileOutputStream(temp);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (backup.exists() && !backup.delete()) {
            temp.delete();
            throw new IOException("RUNTIME_BINDING_REPLACE_FAILED");
        }
        boolean movedOld = false;
        if (file.exists()) {
            if (!file.renameTo(backup)) {
                temp.delete();
                throw new IOException("RUNTIME_BINDING_REPLACE_FAILED");
            }
            movedOld = true;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            if (movedOld) backup.renameTo(file);
            throw new IOException("RUNTIME_BINDING_INSTALL_FAILED");
        }
        if (movedOld) backup.delete();
    }

    /** Recursively removes [root] without following symlinks. */
    public static void deleteTree(File root) {
        RuntimeMirrorSyncSession.deleteTree(root);
    }
}
