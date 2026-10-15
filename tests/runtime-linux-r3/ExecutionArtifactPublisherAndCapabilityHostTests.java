package dev.wind.agent.runtime.linux;

import dev.wind.agent.artifact.ApkInstallPlan;
import dev.wind.agent.artifact.ExecutionArtifactPublisher;
import dev.wind.agent.artifact.VerifiedArtifact;
import dev.wind.agent.artifact.VerifiedArtifactStore;
import dev.wind.agent.capability.CapabilityMode;
import dev.wind.agent.capability.CapabilityRequirement;
import dev.wind.agent.capability.CapabilityResolver;
import dev.wind.agent.capability.TaskContext;
import dev.wind.agent.project.VegaProject;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutionArtifactPublisherAndCapabilityHostTests {
    private static final AtomicInteger assertionCount = new AtomicInteger(0);
    private static final long MAX_BYTES = 256L * 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        File scratchRoot = new File(args.length > 0 ? args[0] : ".build-linux-r3/publisher-tests-scratch").getCanonicalFile();
        deleteRecursively(scratchRoot);
        scratchRoot.mkdirs();

        testLargeBinaryStreamingOver16MiB(scratchRoot);
        testTruncatedStreamRejectionAndCleanup(scratchRoot);
        testOverlengthStreamRejectionAndCleanup(scratchRoot);
        testVerifyRevocationDuringStreaming(scratchRoot);
        testVerifyRevocationPostStreaming(scratchRoot);
        testRealCapabilityResolverForPrepareApkInstall();

        System.out.println("ALL_PUBLISHER_AND_REAL_RESOLVER_TESTS_PASS assertions=" + assertionCount.get());
    }

    private static void testLargeBinaryStreamingOver16MiB(File base) throws Exception {
        File testDir = new File(base, "large_binary_17mb");
        testDir.mkdirs();
        File scratch = new File(testDir, "scratch");
        scratch.mkdirs();
        File casRoot = new File(testDir, "cas");
        casRoot.mkdirs();

        VerifiedArtifactStore store = new VerifiedArtifactStore(casRoot, MAX_BYTES);
        ExecutionArtifactPublisher publisher = new ExecutionArtifactPublisher(scratch, store);

        long size17Mb = 17L * 1024L * 1024L; // 17 MiB (> 16 MiB)
        InputStream stream = new ChunkedPatternInputStream(size17Mb);
        String expectedSha = computeSha256(new ChunkedPatternInputStream(size17Mb), size17Mb);

        VerifiedArtifact artifact = publisher.publish(
                stream,
                size17Mb,
                "execution://main/app/build/outputs/apk/debug/app-debug.apk",
                new Function0<Unit>() {
                    @Override
                    public Unit invoke() {
                        return Unit.INSTANCE;
                    }
                }
        );

        require(artifact.getByteSize() == size17Mb, "Artifact size mismatch");
        require(artifact.getSha256().equals(expectedSha), "Artifact SHA256 mismatch");
        require(artifact.getUri().equals("artifact://sha256/" + expectedSha), "Artifact URI mismatch");
        String[] filesLeft = scratch.list();
        require(filesLeft != null && filesLeft.length == 0, "Scratch temp must be deleted after success");

        // Verify stored in CAS and openable
        InputStream casIn = store.openVerified(artifact.getUri());
        require(casIn != null, "CAS object must be openable");
        casIn.close();
    }

    private static void testTruncatedStreamRejectionAndCleanup(File base) {
        File testDir = new File(base, "truncated_stream");
        testDir.mkdirs();
        File scratch = new File(testDir, "scratch");
        scratch.mkdirs();
        File casRoot = new File(testDir, "cas");
        casRoot.mkdirs();

        VerifiedArtifactStore store = new VerifiedArtifactStore(casRoot, MAX_BYTES);
        ExecutionArtifactPublisher publisher = new ExecutionArtifactPublisher(scratch, store);

        long expectedSize = 1024L * 1024L;
        byte[] actualBytes = new byte[512 * 1024];
        for (int i = 0; i < actualBytes.length; i++) actualBytes[i] = (byte) (i & 0xFF);
        InputStream input = new ByteArrayInputStream(actualBytes);

        boolean failed = false;
        try {
            publisher.publish(input, expectedSize, "execution://test", new Function0<Unit>() {
                @Override
                public Unit invoke() { return Unit.INSTANCE; }
            });
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("EXECUTION_ARTIFACT_TRUNCATED")) {
                failed = true;
            }
        }
        require(failed, "Expected EXECUTION_ARTIFACT_TRUNCATED for incomplete stream");
        String[] filesLeft = scratch.list();
        require(filesLeft != null && filesLeft.length == 0, "Scratch temp must be cleaned up on truncated failure");
        String[] casFiles = casRoot.list();
        require(casFiles != null && casFiles.length == 0, "CAS must not contain failed artifact");
    }

    private static void testOverlengthStreamRejectionAndCleanup(File base) {
        File testDir = new File(base, "overlength_stream");
        testDir.mkdirs();
        File scratch = new File(testDir, "scratch");
        scratch.mkdirs();
        File casRoot = new File(testDir, "cas");
        casRoot.mkdirs();

        VerifiedArtifactStore store = new VerifiedArtifactStore(casRoot, MAX_BYTES);
        ExecutionArtifactPublisher publisher = new ExecutionArtifactPublisher(scratch, store);

        long expectedSize = 64L * 1024L;
        byte[] actualBytes = new byte[128 * 1024];
        for (int i = 0; i < actualBytes.length; i++) actualBytes[i] = (byte) (i & 0xFF);
        InputStream input = new ByteArrayInputStream(actualBytes);

        boolean failed = false;
        try {
            publisher.publish(input, expectedSize, "execution://test", new Function0<Unit>() {
                @Override
                public Unit invoke() { return Unit.INSTANCE; }
            });
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("EXECUTION_ARTIFACT_SIZE_CHANGED")) {
                failed = true;
            }
        }
        require(failed, "Expected EXECUTION_ARTIFACT_SIZE_CHANGED for overlength stream");
        String[] filesLeft = scratch.list();
        require(filesLeft != null && filesLeft.length == 0, "Scratch temp must be cleaned up on size change failure");
        String[] casFiles = casRoot.list();
        require(casFiles != null && casFiles.length == 0, "CAS must not contain corrupted overlength artifact");
    }

    private static void testVerifyRevocationDuringStreaming(File base) {
        File testDir = new File(base, "revocation_during");
        testDir.mkdirs();
        File scratch = new File(testDir, "scratch");
        scratch.mkdirs();
        File casRoot = new File(testDir, "cas");
        casRoot.mkdirs();

        VerifiedArtifactStore store = new VerifiedArtifactStore(casRoot, MAX_BYTES);
        ExecutionArtifactPublisher publisher = new ExecutionArtifactPublisher(scratch, store);

        long streamSize = 2L * 1024L * 1024L;
        InputStream input = new ChunkedPatternInputStream(streamSize);

        final int[] checks = {0};
        boolean cancelled = false;
        try {
            publisher.publish(input, streamSize, "execution://test", new Function0<Unit>() {
                @Override
                public Unit invoke() {
                    checks[0]++;
                    if (checks[0] > 3) {
                        throw new SecurityException("VERIFY_REVOKED_BY_USER");
                    }
                    return Unit.INSTANCE;
                }
            });
        } catch (SecurityException e) {
            if ("VERIFY_REVOKED_BY_USER".equals(e.getMessage())) {
                cancelled = true;
            }
        }
        require(cancelled, "Expected SecurityException during streaming verification revocation");
        String[] filesLeft = scratch.list();
        require(filesLeft != null && filesLeft.length == 0, "Scratch directory must be clean after mid-stream revocation");
        String[] casFiles = casRoot.list();
        require(casFiles != null && casFiles.length == 0, "CAS must remain empty on mid-stream revocation");
    }

    private static void testVerifyRevocationPostStreaming(File base) {
        File testDir = new File(base, "revocation_post");
        testDir.mkdirs();
        File scratch = new File(testDir, "scratch");
        scratch.mkdirs();
        File casRoot = new File(testDir, "cas");
        casRoot.mkdirs();

        VerifiedArtifactStore store = new VerifiedArtifactStore(casRoot, MAX_BYTES);
        ExecutionArtifactPublisher publisher = new ExecutionArtifactPublisher(scratch, store);

        final long streamSize = 128L * 1024L;
        final ChunkedPatternInputStream input = new ChunkedPatternInputStream(streamSize);

        final boolean[] readCompleted = {false};
        boolean cancelled = false;
        try {
            publisher.publish(input, streamSize, "execution://test", new Function0<Unit>() {
                @Override
                public Unit invoke() {
                    if (input.bytesRead >= streamSize) {
                        readCompleted[0] = true;
                        throw new SecurityException("PERMISSION_CANCELLED_BEFORE_IMPORT");
                    }
                    return Unit.INSTANCE;
                }
            });
        } catch (SecurityException e) {
            if ("PERMISSION_CANCELLED_BEFORE_IMPORT".equals(e.getMessage())) {
                cancelled = true;
            }
        }
        require(readCompleted[0], "Stream must have finished reading before post-stream revocation");
        require(cancelled, "Expected SecurityException for post-stream revocation");
        String[] filesLeft = scratch.list();
        require(filesLeft != null && filesLeft.length == 0, "Scratch temp must be deleted upon post-read cancellation");
        String[] casFiles = casRoot.list();
        require(casFiles != null && casFiles.length == 0, "CAS must not commit artifact upon post-read cancellation");
    }

    private static void testRealCapabilityResolverForPrepareApkInstall() throws Exception {
        final ApkInstallPlan mockPlan = new ApkInstallPlan(
                "task-1",
                "test-project-alpha",
                1L,
                "artifact://sha256/d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab",
                "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab",
                "com.example.mixed.app",
                1L,
                "1.0.0",
                "app-debug",
                1024L
        );

        // Instantiate the REAL production CapabilityResolver class with apkPlanLookup
        CapabilityResolver resolver = new CapabilityResolver(
                new Function1<String, VegaProject>() {
                    @Override
                    public VegaProject invoke(String projectId) {
                        return null;
                    }
                },
                null,
                null,
                new Function2<TaskContext, String, ApkInstallPlan>() {
                    @Override
                    public ApkInstallPlan invoke(TaskContext taskContext, String planId) {
                        if ("plan-42".equals(planId)) return mockPlan;
                        return null;
                    }
                }
        );

        String projectId = "test-project-alpha";
        TaskContext context = new TaskContext("task-1", projectId, 1L, CapabilityMode.ACCEPT, "origin-task");

        // Case 1: prepare_apk_install with execution_path -> must resolve to CapabilityRequirement.MirrorRead
        JSONObject argsWithExecutionPath = new JSONObject();
        argsWithExecutionPath.put("execution_path", "execution://main/app/build/outputs/apk/debug/app-debug.apk");
        CapabilityRequirement req1 = resolver.resolve(context, "prepare_apk_install", argsWithExecutionPath);
        require(req1 instanceof CapabilityRequirement.MirrorRead, "execution_path must resolve to CapabilityRequirement.MirrorRead");
        require(((CapabilityRequirement.MirrorRead) req1).getProjectId().equals(projectId), "MirrorRead projectId mismatch");

        // Case 2: prepare_apk_install with artifact_uri (no execution_path) -> must resolve to CapabilityRequirement.Internal
        JSONObject argsWithArtifactUri = new JSONObject();
        argsWithArtifactUri.put("artifact_uri", "artifact://sha256/d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab");
        CapabilityRequirement req2 = resolver.resolve(context, "prepare_apk_install", argsWithArtifactUri);
        require(req2 instanceof CapabilityRequirement.Internal, "artifact_uri must resolve to CapabilityRequirement.Internal");
        require(((CapabilityRequirement.Internal) req2).getProjectId().equals(projectId), "Internal projectId mismatch");

        // Case 3: prepare_apk_install with blank execution_path -> falls back to Internal
        JSONObject argsBlankExecutionPath = new JSONObject();
        argsBlankExecutionPath.put("execution_path", "   ");
        CapabilityRequirement req3 = resolver.resolve(context, "prepare_apk_install", argsBlankExecutionPath);
        require(req3 instanceof CapabilityRequirement.Internal, "Blank execution_path must fall back to CapabilityRequirement.Internal");

        // Case 4: verify install_apk with valid plan requires ArtifactInstall
        JSONObject installArgs = new JSONObject();
        installArgs.put("plan_id", "plan-42");
        CapabilityRequirement installReq = resolver.resolve(context, "install_apk", installArgs);
        require(installReq instanceof CapabilityRequirement.ArtifactInstall, "install_apk with plan must require ArtifactInstall");
        CapabilityRequirement.ArtifactInstall artInstall = (CapabilityRequirement.ArtifactInstall) installReq;
        require(artInstall.getTargetId().equals("com.example.mixed.app"), "Target package mismatch");
        require(artInstall.getArtifactSha256().equals("d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"), "Artifact SHA mismatch");

        // Case 5: verify install_apk without plan returns Denied(APK_INSTALL_PLAN_UNAVAILABLE)
        JSONObject invalidInstallArgs = new JSONObject();
        invalidInstallArgs.put("plan_id", "missing-plan");
        CapabilityRequirement deniedReq = resolver.resolve(context, "install_apk", invalidInstallArgs);
        require(deniedReq instanceof CapabilityRequirement.Denied, "install_apk without valid plan must be Denied");
    }

    private static void require(boolean condition, String message) {
        assertionCount.incrementAndGet();
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String computeSha256(InputStream input, long size) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[65536];
        long total = 0L;
        try (InputStream s = input) {
            while (true) {
                int r = s.read(buf);
                if (r < 0) break;
                digest.update(buf, 0, r)
;
                total += r;
            }
        }
        if (total != size) throw new IllegalStateException("Size mismatch");
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static class ChunkedPatternInputStream extends InputStream {
        private final long totalBytes;
        long bytesRead = 0L;

        ChunkedPatternInputStream(long totalBytes) {
            this.totalBytes = totalBytes;
        }

        @Override
        public int read() {
            if (bytesRead >= totalBytes) return -1;
            int b = (int) (bytesRead & 0xFF);
            bytesRead++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (bytesRead >= totalBytes) return -1;
            int toRead = (int) Math.min((long) len, totalBytes - bytesRead);
            for (int i = 0; i < toRead; i++) {
                b[off + i] = (byte) ((bytesRead + i) & 0xFF);
            }
            bytesRead += toRead;
            return toRead;
        }
    }
}
