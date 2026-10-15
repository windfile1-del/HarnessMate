package dev.wind.agent.runtime.linux;

import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure Java host regression tests for LinuxBundleTransaction and LinuxInstallControl.
 */
public final class LinuxBundleTransactionTests {
    private static int assertions = 0;
    private static int skips = 0;

    private static void skip(String reason) {
        skips++;
        System.out.println("SKIP: " + reason);
    }

    private static void verify(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b & 255));
        return sb.toString();
    }

    private static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static JSONObject makeCatalog(String id, byte[] payload) throws Exception {
        return new JSONObject()
                .put("schema", 1)
                .put("id", id)
                .put("payload", "tools.tar.gz")
                .put("compressedBytes", payload.length)
                .put("sha256", sha256(payload));
    }

    private static void deleteDirectory(File dir) throws IOException {
        if (!dir.exists()) return;
        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // 1. Success install, status READY, pointer format and receipt checks
    private static void testSuccessfulInstallAndStatus(File root) throws Exception {
        System.out.println("Running testSuccessfulInstallAndStatus...");
        File uncreatedRoot = new File(root, "uncreated-sub");
        LinuxBundleTransaction txUncreated = new LinuxBundleTransaction(uncreatedRoot, "jvm-test", true);
        verify("NOT_INSTALLED".equals(txUncreated.status()), "initial status NOT_INSTALLED when dir does not exist");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        verify("LEGACY".equals(tx.status()), "status reports LEGACY when root exists but no state file");

        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);

        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            File tool = new File(dir, "bin/java");
            if (!tool.isFile()) throw new IllegalStateException("bin/java missing");
        };

        LinuxBundleTransaction.Workload work1 = stage -> {
            File bin = new File(stage, "bin");
            bin.mkdirs();
            Files.write(new File(bin, "java").toPath(), payload1);
        };

        File v1Dir = tx.install(cat1, "legacy-jvm", val, work1);
        verify(v1Dir != null && v1Dir.isDirectory(), "v1 installed to real directory");
        verify(v1Dir.getName().startsWith(cat1.getString("sha256") + "-v"), "v1 directory follows digest prefix");
        verify(new File(v1Dir, ".r5-manifest.json").isFile(), "manifest file exists");
        verify(new File(v1Dir, ".r5-receipt.json").isFile(), "receipt file exists");

        String status = tx.status();
        JSONObject statusObj = new JSONObject(status);
        verify("READY".equals(statusObj.getString("phase")), "status reports READY");
        verify(statusObj.getString("detail").contains(v1Dir.getName()), "status detail mentions version dir");

        File found = tx.find(cat1, "legacy-jvm", val);
        verify(v1Dir.equals(found), "find returns active v1 directory");
    }

    // 2. Re-install idempotent: returns existing directory without executing workload again
    private static void testReinstallIdempotent(File root) throws Exception {
        System.out.println("Running testReinstallIdempotent...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);

        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };

        AtomicInteger workloadRuns = new AtomicInteger(0);
        LinuxBundleTransaction.Workload workFailing = stage -> {
            workloadRuns.incrementAndGet();
            throw new RuntimeException("workload should not run on idempotent install");
        };

        File v1Reinstall = tx.install(cat1, "legacy-jvm", val, workFailing);
        verify(v1Reinstall != null && v1Reinstall.isDirectory(), "reinstall returns active directory");
        verify(workloadRuns.get() == 0, "workload was not executed during idempotent reinstall");
        verify(tx.find(cat1, "legacy-jvm", val).equals(v1Reinstall), "find still resolves to same dir");
    }

    // 3. v2 update success: pointer current updates, previous preserves v1
    private static void testV2UpdateSuccess(File root) throws Exception {
        System.out.println("Running testV2UpdateSuccess...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);

        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };

        File v1Dir = tx.find(cat1, "legacy-jvm", val);
        verify(v1Dir != null, "prior v1 exists before update");

        // Test probe failure on v2 attempt retains v1
        byte[] payloadProbeFail = "v2-probe-fail".getBytes("UTF-8");
        JSONObject catProbeFail = makeCatalog("jvm-probe-fail", payloadProbeFail);
        boolean caughtProbeFail = false;
        try {
            tx.install(catProbeFail, "legacy-jvm", (dir, cat) -> {
                throw new IllegalStateException("probe failed on candidate v2");
            }, stage -> {
                File bin = new File(stage, "bin");
                bin.mkdirs();
                Files.write(new File(bin, "java").toPath(), payloadProbeFail);
            });
        } catch (IllegalStateException e) {
            caughtProbeFail = e.getMessage().contains("probe failed on candidate v2");
        }
        verify(caughtProbeFail, "probe failure on candidate v2 caught");
        verify(v1Dir.equals(tx.find(cat1, "legacy-jvm", val)), "candidate v2 probe failure retains v1");

        // Test cancellation on v2 attempt retains v1
        AtomicReference<Throwable> cancelV2Failure = new AtomicReference<>();
        Thread cancelV2Thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                tx.install(catProbeFail, "legacy-jvm", val, stage -> {
                    LinuxInstallControl.check();
                });
                cancelV2Failure.set(new AssertionError("cancellation should throw"));
            } catch (InterruptedIOException expected) {
            } catch (Throwable unexpected) {
                cancelV2Failure.set(unexpected);
            }
        });
        cancelV2Thread.start();
        cancelV2Thread.join();
        verify(cancelV2Failure.get() == null, "candidate v2 cancellation had no unexpected errors: " + cancelV2Failure.get());
        verify(v1Dir.equals(tx.find(cat1, "legacy-jvm", val)), "candidate v2 cancellation retains v1");

        byte[] payload2 = "v2-content-world-updated".getBytes("UTF-8");
        JSONObject cat2 = makeCatalog("jvm-2.0", payload2);

        File v2Dir = tx.install(cat2, "legacy-jvm", val, stage -> {
            File bin = new File(stage, "bin");
            bin.mkdirs();
            Files.write(new File(bin, "java").toPath(), payload2);
        });

        verify(v2Dir != null && !v2Dir.equals(v1Dir), "v2 directory is distinct from v1");
        File found = tx.find(cat2, "legacy-jvm", val);
        verify(v2Dir.equals(found), "find resolves to new v2 directory");

        File activeFile = new File(root, ".active-jvm-test.json");
        JSONObject activeObj = new JSONObject(new String(Files.readAllBytes(activeFile.toPath()), "UTF-8"));
        verify(activeObj.getJSONObject("current").getString("directory").equals(v2Dir.getName()), "current points to v2");
        verify(activeObj.getJSONObject("previous").getString("directory").equals(v1Dir.getName()), "previous points to v1");
    }

    // 4. Probe / validation failure and cancellation during v3 install preserves v2
    private static void testProbeFailureAndCancellationPreservesCurrent(File root) throws Exception {
        System.out.println("Running testProbeFailureAndCancellationPreservesCurrent...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        byte[] payload2 = "v2-content-world-updated".getBytes("UTF-8");
        JSONObject cat2 = makeCatalog("jvm-2.0", payload2);

        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };
        File v2Dir = tx.find(cat2, "legacy-jvm", val);
        verify(v2Dir != null, "v2 confirmed active");

        // 4a. Workload throws exception
        byte[] payloadBad = "v3-broken".getBytes("UTF-8");
        JSONObject catBad = makeCatalog("jvm-3.0-bad", payloadBad);
        boolean caughtWorkloadErr = false;
        try {
            tx.install(catBad, "legacy-jvm", val, stage -> {
                throw new IllegalStateException("simulated workload explosion");
            });
        } catch (IllegalStateException e) {
            caughtWorkloadErr = e.getMessage().contains("simulated workload explosion");
        }
        verify(caughtWorkloadErr, "workload failure caught");
        verify(v2Dir.equals(tx.find(cat2, "legacy-jvm", val)), "active still points to v2 after workload error");

        // 4b. Validator rejects stage
        boolean caughtValErr = false;
        try {
            tx.install(catBad, "legacy-jvm", (dir, cat) -> {
                throw new IllegalStateException("validator rejected files in stage");
            }, stage -> {
                // prepare something without valid files
                Files.write(new File(stage, "garbage.txt").toPath(), new byte[]{1,2,3});
            });
        } catch (IllegalStateException e) {
            caughtValErr = e.getMessage().contains("validator rejected files in stage");
        }
        verify(caughtValErr, "validator rejection caught");
        verify(v2Dir.equals(tx.find(cat2, "legacy-jvm", val)), "active still points to v2 after validator error");

        // 4c. Thread cancellation / interrupt during prepare
        AtomicReference<Throwable> cancelV3Failure = new AtomicReference<>();
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                tx.install(catBad, "legacy-jvm", val, stage -> {
                    // check or write
                    LinuxInstallControl.check();
                });
                cancelV3Failure.set(new AssertionError("should have failed on cancellation"));
            } catch (InterruptedIOException expected) {
                // good
            } catch (Throwable unexpected) {
                cancelV3Failure.set(unexpected);
            }
        });
        t.start();
        t.join();
        verify(cancelV3Failure.get() == null, "cancel v3 had no unexpected errors: " + cancelV3Failure.get());
        verify(v2Dir.equals(tx.find(cat2, "legacy-jvm", val)), "active still points to v2 after thread cancellation");

        // 4d. Explicit test: v3 install failure retains v2
        byte[] payload3 = "v3-candidate".getBytes("UTF-8");
        JSONObject cat3 = makeCatalog("jvm-3.0", payload3);
        boolean caughtV3Probe = false;
        try {
            tx.install(cat3, "legacy-jvm", (dir, cat) -> {
                throw new IllegalStateException("probe failed on v3");
            }, stage -> {
                File bin = new File(stage, "bin");
                bin.mkdirs();
                Files.write(new File(bin, "java").toPath(), payload3);
            });
        } catch (IllegalStateException e) {
            caughtV3Probe = e.getMessage().contains("probe failed on v3");
        }
        verify(caughtV3Probe, "v3 probe failure caught");
        verify(v2Dir.equals(tx.find(cat2, "legacy-jvm", val)), "v3 failure retains v2");
    }

    // 5. Rollback returns v1, active pointer switches, rollback full tree integrity
    private static void testRollbackToV1(File root) throws Exception {
        System.out.println("Running testRollbackToV1...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };

        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);

        File rolledBack = tx.rollback(val);
        verify(rolledBack != null && rolledBack.isDirectory(), "rollback succeeds and returns directory");
        verify(rolledBack.getName().startsWith(cat1.getString("sha256") + "-v"), "rolled back directory is v1");

        File currentFound = tx.find(cat1, "legacy-jvm", val);
        verify(rolledBack.equals(currentFound), "find resolves to v1 after rollback");

        JSONObject statusObj = new JSONObject(tx.status());
        verify("ROLLED_BACK".equals(statusObj.getString("phase")), "status reports ROLLED_BACK");
    }

    // 6. Rollback when previous is corrupt/damaged is refused
    private static void testRollbackRefusedWhenPreviousDamaged(File root) throws Exception {
        System.out.println("Running testRollbackRefusedWhenPreviousDamaged...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };

        File activeFile = new File(root, ".active-jvm-test.json");
        JSONObject activeObj = new JSONObject(new String(Files.readAllBytes(activeFile.toPath()), "UTF-8"));
        JSONObject prev = activeObj.optJSONObject("previous");
        verify(prev != null, "previous exists in pointer");

        File prevDir = new File(root, prev.getString("directory"));
        File prevTool = new File(prevDir, "bin/java");
        byte[] origBytes = Files.readAllBytes(prevTool.toPath());
        try {
            // Corrupt file in previous to fail tree/validator integrity
            Files.write(prevTool.toPath(), "tampered-content".getBytes("UTF-8"));

            boolean caughtRollbackErr = false;
            try {
                tx.rollback(val);
            } catch (IOException e) {
                caughtRollbackErr = e.getMessage().contains("TOOL_INSTALL_ROLLBACK_UNAVAILABLE");
            }
            verify(caughtRollbackErr, "rollback refused with TOOL_INSTALL_ROLLBACK_UNAVAILABLE when previous is corrupt");
        } finally {
            Files.write(prevTool.toPath(), origBytes);
        }
    }

    // 7. Read-only find never creates or modifies active pointer or state files
    private static void testFindReadOnlyDoesNotWrite(File root) throws Exception {
        System.out.println("Running testFindReadOnlyDoesNotWrite...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);
        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };
        File nonExistentRoot = new File(root, "non-existent-sub");
        LinuxBundleTransaction txNonExistent = new LinuxBundleTransaction(nonExistentRoot, "sub-test", true);
        File result = txNonExistent.find(new JSONObject().put("sha256", "abc"), "legacy", (d, c) -> {});
        verify(result == null, "find on non-existent root returns null");
        verify(!nonExistentRoot.exists(), "find did not create non-existent root directory");

        // Existing root with active pointer: find verifies pointer exists, reads pointer, but never modifies pointer file
        File activeFile = new File(root, ".active-jvm-test.json");
        verify(activeFile.isFile(), "active pointer file exists before find");
        long lastModBefore = activeFile.lastModified();
        byte[] bytesBefore = Files.readAllBytes(activeFile.toPath());
        File foundActive = tx.find(cat1, "legacy-jvm", val);
        verify(foundActive != null, "find resolved active");
        byte[] bytesAfter = Files.readAllBytes(activeFile.toPath());
        verify(Arrays.equals(bytesBefore, bytesAfter), "find did not alter active pointer bytes");
        verify(activeFile.lastModified() == lastModBefore, "find did not touch active pointer timestamp");

        // Existing root with legacy directory but no active pointer
        File legacyRoot = new File(root, "legacy-only-store");
        legacyRoot.mkdirs();
        File legacyDir = new File(legacyRoot, "legacy-name");
        legacyDir.mkdirs();
        Files.write(new File(legacyDir, "tool.txt").toPath(), "tool-bytes".getBytes("UTF-8"));

        LinuxBundleTransaction txLegacy = new LinuxBundleTransaction(legacyRoot, "legacy-binding", true);
        File foundLegacy = txLegacy.find(new JSONObject(), "legacy-name", (d, c) -> {
            if (!new File(d, "tool.txt").isFile()) throw new RuntimeException("missing tool.txt");
        });
        verify(legacyDir.equals(foundLegacy), "legacy directory found when valid");
        verify(!new File(legacyRoot, ".active-legacy-binding.json").exists(), "legacy find did not create active pointer");
        verify(!new File(legacyRoot, ".state-legacy-binding.json").exists(), "legacy find did not create state file");
    }

    // 8. Crash remnants (.stage-UUID and .pointer-UUID) recovered during next install
    private static void testStageAndPointerRecovery(File root) throws Exception {
        System.out.println("Running testStageAndPointerRecovery...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-test", true);
        File fakeStage = new File(root, ".stage-" + UUID.randomUUID());
        fakeStage.mkdirs();
        Files.write(new File(fakeStage, "leftover.tmp").toPath(), "leftover".getBytes("UTF-8"));

        File fakePointerStage = new File(root, ".pointer-" + UUID.randomUUID());
        Files.write(fakePointerStage.toPath(), "partial-pointer".getBytes("UTF-8"));

        verify(fakeStage.exists(), "fake stage dir created");
        verify(fakePointerStage.exists(), "fake pointer stage file created");

        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);
        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };

        // Running install should recover/delete stages
        tx.install(cat1, "legacy-jvm", val, stage -> {});

        verify(!fakeStage.exists(), "leftover .stage-UUID cleaned up by install recovery");
        verify(!fakePointerStage.exists(), "leftover .pointer-UUID cleaned up by install recovery");
    }

    // 9. Corrupt active pointer repairs via re-install: detects corrupt active, reinstalls fresh version
    private static void testCorruptActiveReinstallRepair(File root) throws Exception {
        System.out.println("Running testCorruptActiveReinstallRepair...");
        File cleanRoot = new File(root, "repair-store");
        cleanRoot.mkdirs();
        LinuxBundleTransaction tx = new LinuxBundleTransaction(cleanRoot, "jvm-repair", true);
        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "bin/java").isFile()) throw new IllegalStateException("bin/java missing");
        };

        byte[] payload1 = "v1-content-hello".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("jvm-1.0", payload1);

        File v1Dir = tx.install(cat1, "legacy-jvm", val, stage -> {
            File bin = new File(stage, "bin");
            bin.mkdirs();
            Files.write(new File(bin, "java").toPath(), payload1);
        });
        verify(v1Dir != null, "v1 exists before corruption");

        // Corrupt active directory content (tamper file in active version directory)
        File toolInV1 = new File(v1Dir, "bin/java");
        byte[] originalToolBytes = Files.readAllBytes(toolInV1.toPath());
        // Removing the tool binary causes validator to fail during resolve
        Files.delete(toolInV1.toPath());

        // Without a valid previous, find returns null
        verify(tx.find(cat1, "legacy-jvm", val) == null, "find returns null when current is corrupt and no previous");

        // Install of same catalog detects corrupt active and repairs by fresh reinstall
        File repairedV1 = tx.install(cat1, "legacy-jvm", val, stage -> {
            File bin = new File(stage, "bin");
            bin.mkdirs();
            Files.write(new File(bin, "java").toPath(), payload1);
        });
        verify(repairedV1 != null && repairedV1.isDirectory(), "reinstall repaired corrupted active version");
        verify(repairedV1.equals(tx.find(cat1, "legacy-jvm", val)), "find resolves to repaired active version");

        // Tamper active pointer content with invalid JSON
        File activeFile = new File(cleanRoot, ".active-jvm-repair.json");
        Files.write(activeFile.toPath(), "{broken-json".getBytes("UTF-8"));

        // find returns null on malformed pointer
        File foundWhileBroken = tx.find(cat1, "legacy-jvm", val);
        verify(foundWhileBroken == null, "find returns null when pointer is malformed JSON");

        // Install should move invalid pointer to .invalid-pointer-UUID and install fresh version
        File freshV1 = tx.install(cat1, "legacy-jvm", val, stage -> {
            File bin = new File(stage, "bin");
            bin.mkdirs();
            Files.write(new File(bin, "java").toPath(), payload1);
        });
        verify(freshV1 != null && freshV1.isDirectory(), "reinstall succeeds after corrupt pointer");
        verify(tx.find(cat1, "legacy-jvm", val) != null, "find succeeds after repair");

        // Verify invalid pointer was archived
        File[] invalidPointers = cleanRoot.listFiles((d, name) -> name.startsWith(".invalid-pointer-"));
        verify(invalidPointers != null && invalidPointers.length > 0, "invalid pointer archived as evidence");
    }

    // 10. Binding isolation: operations on binding A do not collide with binding B
    private static void testBindingIsolation(File root) throws Exception {
        System.out.println("Running testBindingIsolation...");
        LinuxBundleTransaction txA = new LinuxBundleTransaction(root, "tool-alpha", true);
        LinuxBundleTransaction txB = new LinuxBundleTransaction(root, "tool-beta", true);

        byte[] payloadA = "alpha-payload".getBytes("UTF-8");
        JSONObject catA = makeCatalog("alpha-1", payloadA);
        byte[] payloadA2 = "alpha-payload-v2".getBytes("UTF-8");
        JSONObject catA2 = makeCatalog("alpha-2", payloadA2);
        LinuxBundleTransaction.Validator valA = (d, c) -> {
            if (!new File(d, "alpha.exe").isFile()) throw new IllegalStateException("alpha.exe missing");
        };

        byte[] payloadB = "beta-payload".getBytes("UTF-8");
        JSONObject catB = makeCatalog("beta-1", payloadB);
        LinuxBundleTransaction.Validator valB = (d, c) -> {
            if (!new File(d, "beta.exe").isFile()) throw new IllegalStateException("beta.exe missing");
        };

        File dirA = txA.install(catA, "legacy-alpha", valA, stage -> {
            Files.write(new File(stage, "alpha.exe").toPath(), payloadA);
        });
        File dirA2 = txA.install(catA2, "legacy-alpha", valA, stage -> {
            Files.write(new File(stage, "alpha.exe").toPath(), payloadA2);
        });

        File dirB = txB.install(catB, "legacy-beta", valB, stage -> {
            Files.write(new File(stage, "beta.exe").toPath(), payloadB);
        });

        verify(dirA2.isDirectory() && dirB.isDirectory(), "both tools installed");
        verify(!dirA2.equals(dirB), "tools are in distinct directories");

        verify(dirA2.equals(txA.find(catA2, "legacy-alpha", valA)), "txA resolves to dirA2");
        verify(dirB.equals(txB.find(catB, "legacy-beta", valB)), "txB resolves to dirB");

        verify(txA.find(catB, "legacy-beta", valB) == null, "txA cannot resolve catB because pointer is bound to tool-alpha");
        verify(txB.find(catA, "legacy-alpha", valA) == null, "txB cannot resolve catA because pointer is bound to tool-beta");

        // Rollback on A does not affect B
        File rolledBackA = txA.rollback(valA);
        verify(dirA.equals(rolledBackA), "rollback A succeeds and returns dirA");
        verify(dirB.equals(txB.find(catB, "legacy-beta", valB)), "B remains unaffected after A rollback");
    }

    // 11. Symlink and path boundary rejection
    private static void testSymlinkAndPathRejections(File root) throws Exception {
        System.out.println("Running testSymlinkAndPathRejections...");
        // Invalid binding name rejection
        boolean caughtBadBinding = false;
        try {
            new LinuxBundleTransaction(root, "../escaping-binding", true);
        } catch (IOException e) {
            caughtBadBinding = e.getMessage().contains("TOOL_INSTALL_BINDING_INVALID");
        }
        verify(caughtBadBinding, "invalid binding name with dotdot rejected");

        boolean caughtEmptyBinding = false;
        try {
            new LinuxBundleTransaction(root, "", true);
        } catch (IOException e) {
            caughtEmptyBinding = e.getMessage().contains("TOOL_INSTALL_BINDING_INVALID");
        }
        verify(caughtEmptyBinding, "empty binding name rejected");

        // Invalid characters / traversal in child path / legacyName
        byte[] p = "valid".getBytes("UTF-8");
        JSONObject cat = makeCatalog("valid-id", p);
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "valid-binding", true);
        boolean caughtTraversalChild = false;
        try {
            tx.install(cat, "../escape-dir", (d, c) -> {}, stage -> {});
        } catch (IOException e) {
            caughtTraversalChild = e.getMessage().contains("TOOL_INSTALL_PATH_INVALID");
        }
        verify(caughtTraversalChild, "traversal legacyName rejected with TOOL_INSTALL_PATH_INVALID");

        // Symlink root rejection (test if symlinks can be created)
        Path probeTarget = root.toPath().resolve("sym-probe-target");
        Path probeLink = root.toPath().resolve("sym-probe-link");
        probeTarget.toFile().mkdirs();
        boolean symlinkSupported = false;
        try {
            Files.createSymbolicLink(probeLink, probeTarget);
            symlinkSupported = true;
        } catch (Exception e) {
            skip("symlink creation not supported/permitted on this platform: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(probeLink); } catch (Exception ignored) {}
            try { Files.deleteIfExists(probeTarget); } catch (Exception ignored) {}
        }

        if (symlinkSupported) {
            Path realDir = root.toPath().resolve("real-dir-target");
            realDir.toFile().mkdirs();
            Path symRoot = root.toPath().resolve("sym-root");
            Files.createSymbolicLink(symRoot, realDir);
            boolean caughtSymRoot = false;
            try {
                new LinuxBundleTransaction(symRoot.toFile(), "test-bind", true);
            } catch (IOException e) {
                caughtSymRoot = e.getMessage().contains("TOOL_INSTALL_ROOT_UNSAFE");
            }
            verify(caughtSymRoot, "symlinked root directory rejected with TOOL_INSTALL_ROOT_UNSAFE");
            Files.deleteIfExists(symRoot);
            deleteDirectory(realDir.toFile());
        }
    }

    // 12. Concurrent install: serialized safely by lock and monitor
    private static void testConcurrentInstall(File root) throws Exception {
        System.out.println("Running testConcurrentInstall...");
        LinuxBundleTransaction tx = new LinuxBundleTransaction(root, "jvm-concurrent", true);
        byte[] payload = "concurrent-payload".getBytes("UTF-8");
        JSONObject cat = makeCatalog("jvm-concurrent-1", payload);

        LinuxBundleTransaction.Validator val = (dir, c) -> {
            if (!new File(dir, "exec.bin").isFile()) throw new IllegalStateException("exec.bin missing");
        };

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<File> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    File res = tx.install(cat, "legacy-conc", val, stage -> {
                        // Small sleep to ensure threads overlap
                        Thread.sleep(30);
                        Files.write(new File(stage, "exec.bin").toPath(), payload);
                    });
                    results.add(res);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        verify(doneLatch.await(30, TimeUnit.SECONDS), "all concurrent installs completed within 30s");
        executor.shutdown();

        verify(errors.isEmpty(), "no errors during concurrent installs: " + errors);
        verify(results.size() == threadCount, "all threads returned results");

        File first = results.get(0);
        for (File f : results) {
            verify(first.equals(f), "all concurrent threads converged to the same installed directory");
        }
    }

    // 13. Non-blocking find/status while install holds mutation lock: returns old valid within 1 second
    private static void testNonBlockingFindAndStatusDuringInstall(File root) throws Exception {
        System.out.println("Running testNonBlockingFindAndStatusDuringInstall...");
        File store = new File(root, "nonblocking-store");
        store.mkdirs();
        LinuxBundleTransaction tx = new LinuxBundleTransaction(store, "nb-test", true);

        byte[] payload1 = "v1-bytes".getBytes("UTF-8");
        JSONObject cat1 = makeCatalog("nb-1.0", payload1);
        LinuxBundleTransaction.Validator val = (dir, cat) -> {
            if (!new File(dir, "app.bin").isFile()) throw new IllegalStateException("app.bin missing");
        };

        File v1Dir = tx.install(cat1, "legacy-nb", val, stage -> {
            Files.write(new File(stage, "app.bin").toPath(), payload1);
        });
        verify(v1Dir != null && v1Dir.isDirectory(), "initial v1 installed");

        byte[] payload2 = "v2-bytes".getBytes("UTF-8");
        JSONObject cat2 = makeCatalog("nb-2.0", payload2);

        CountDownLatch workloadEnteredLatch = new CountDownLatch(1);
        CountDownLatch releaseWorkloadLatch = new CountDownLatch(1);
        CountDownLatch installFinishedLatch = new CountDownLatch(1);
        AtomicReference<Throwable> installThreadError = new AtomicReference<>();

        Thread installThread = new Thread(() -> {
            try {
                tx.install(cat2, "legacy-nb", val, stage -> {
                    Files.write(new File(stage, "app.bin").toPath(), payload2);
                    workloadEnteredLatch.countDown();
                    // Block inside workload holding the lock
                    releaseWorkloadLatch.await(10, TimeUnit.SECONDS);
                });
            } catch (Throwable e) {
                installThreadError.set(e);
            } finally {
                installFinishedLatch.countDown();
            }
        });

        installThread.start();
        verify(workloadEnteredLatch.await(5, TimeUnit.SECONDS), "workload entered and holding mutation lock");

        // Other thread queries find and status while lock is held
        long startFind = System.currentTimeMillis();
        File foundDir = tx.find(cat1, "legacy-nb", val);
        long elapsedFind = System.currentTimeMillis() - startFind;
        verify(elapsedFind < 1000, "find returned within 1 second while install lock is held (elapsed: " + elapsedFind + "ms)");
        verify(v1Dir.equals(foundDir), "find returns prior valid v1 version without waiting for v2 install to complete");

        long startStatus = System.currentTimeMillis();
        String currentStatus = tx.status();
        long elapsedStatus = System.currentTimeMillis() - startStatus;
        verify(elapsedStatus < 1000, "status returned within 1 second while install lock is held (elapsed: " + elapsedStatus + "ms)");
        JSONObject statusObjDuringInstall = new JSONObject(currentStatus);
        verify("STAGING".equals(statusObjDuringInstall.getString("phase")), "status non-blocking read returns active stage status without waiting");

        // Release workload and allow install to finish
        releaseWorkloadLatch.countDown();
        verify(installFinishedLatch.await(5, TimeUnit.SECONDS), "install finished cleanly after releasing workload");
        verify(installThreadError.get() == null, "install thread had no error: " + installThreadError.get());

        // After release, find resolves to v2
        File v2Found = tx.find(cat2, "legacy-nb", val);
        verify(v2Found != null && !v2Found.equals(v1Dir), "find now resolves to newly activated v2");
    }

    // 14. Core migration with legacyProbe: success keeps original legacy dir, exception triggers re-unpack, cancellation keeps legacy readable
    private static void testLegacyCoreMigration(File root) throws Exception {
        System.out.println("Running testLegacyCoreMigration...");
        File coreRoot = new File(root, "core-migration-store");
        coreRoot.mkdirs();

        // 14a. Success path: legacyProbe succeeds -> retains legacyName unchanged, writes pointer and receipt, treeIntegrity=false
        File legacyDir = new File(coreRoot, "legacy-core-dir");
        legacyDir.mkdirs();
        byte[] corePayload = "core-v1-bytes".getBytes("UTF-8");
        JSONObject coreCat = makeCatalog("core-1.0", corePayload);
        Files.write(new File(legacyDir, "core.bin").toPath(), corePayload);

        LinuxBundleTransaction coreTx = new LinuxBundleTransaction(coreRoot, "core-binding", false);
        LinuxBundleTransaction.Validator coreVal = (dir, cat) -> {
            if (!new File(dir, "core.bin").isFile()) throw new IllegalStateException("core.bin missing");
        };

        AtomicInteger legacyProbeRuns = new AtomicInteger(0);
        LinuxBundleTransaction.Workload probeSuccess = dir -> {
            legacyProbeRuns.incrementAndGet();
            if (!new File(dir, "core.bin").isFile()) throw new IllegalStateException("probe: core.bin missing");
        };

        AtomicInteger unpackWorkloadRuns = new AtomicInteger(0);
        LinuxBundleTransaction.Workload freshUnpack = stage -> {
            unpackWorkloadRuns.incrementAndGet();
            Files.write(new File(stage, "core.bin").toPath(), corePayload);
        };

        File migrated = coreTx.install(coreCat, "legacy-core-dir", coreVal, freshUnpack, probeSuccess);
        verify(legacyDir.equals(migrated), "successful legacy probe retains legacy directory unchanged");
        verify(legacyProbeRuns.get() == 1, "legacy probe was executed");
        verify(unpackWorkloadRuns.get() == 0, "fresh unpack workload was bypassed on successful probe");
        verify(new File(legacyDir, ".r5-manifest.json").isFile(), "legacy directory received manifest");
        verify(new File(legacyDir, ".r5-receipt.json").isFile(), "legacy directory received receipt");
        verify(legacyDir.equals(coreTx.find(coreCat, "legacy-core-dir", coreVal)), "find resolves to migrated legacy dir");

        // 14b. Failure path: probe throws exception -> triggers fresh unpack (workload), does not retain corrupted legacy dir
        File badCoreRoot = new File(root, "bad-core-migration-store");
        badCoreRoot.mkdirs();
        File badLegacyDir = new File(badCoreRoot, "legacy-bad-core");
        badLegacyDir.mkdirs();
        Files.write(new File(badLegacyDir, "core.bin").toPath(), "old-corrupt-core".getBytes("UTF-8"));

        LinuxBundleTransaction badCoreTx = new LinuxBundleTransaction(badCoreRoot, "core-binding-bad", false);
        AtomicInteger badProbeRuns = new AtomicInteger(0);
        LinuxBundleTransaction.Workload probeThrowing = dir -> {
            badProbeRuns.incrementAndGet();
            throw new IllegalStateException("probe detected corrupted legacy core runtime");
        };

        AtomicInteger fallbackUnpackRuns = new AtomicInteger(0);
        LinuxBundleTransaction.Workload unpackFallback = stage -> {
            fallbackUnpackRuns.incrementAndGet();
            Files.write(new File(stage, "core.bin").toPath(), corePayload);
        };

        File freshlyUnpacked = badCoreTx.install(coreCat, "legacy-bad-core", coreVal, unpackFallback, probeThrowing);
        verify(freshlyUnpacked != null && !freshlyUnpacked.equals(badLegacyDir), "corrupt legacy probe triggers fresh unpack to new version dir");
        verify(badProbeRuns.get() == 1, "bad probe was attempted");
        verify(fallbackUnpackRuns.get() == 1, "fallback fresh unpack was executed");
        verify(freshlyUnpacked.equals(badCoreTx.find(coreCat, "legacy-bad-core", coreVal)), "find resolves to fresh unpacked version");

        // 14c. Cancellation during legacyProbe -> retains legacy read-only visible without writing active pointer
        File cancelCoreRoot = new File(root, "cancel-core-migration-store");
        cancelCoreRoot.mkdirs();
        File cancelLegacyDir = new File(cancelCoreRoot, "legacy-cancel-core");
        cancelLegacyDir.mkdirs();
        Files.write(new File(cancelLegacyDir, "core.bin").toPath(), corePayload);

        LinuxBundleTransaction cancelCoreTx = new LinuxBundleTransaction(cancelCoreRoot, "core-binding-cancel", false);
        AtomicReference<Throwable> cancelCoreFailure = new AtomicReference<>();
        Thread cancelThread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                cancelCoreTx.install(coreCat, "legacy-cancel-core", coreVal, freshUnpack, dir -> {
                    LinuxInstallControl.check();
                });
                cancelCoreFailure.set(new AssertionError("cancellation should have interrupted"));
            } catch (InterruptedIOException expected) {
                // expected
            } catch (Throwable unexpected) {
                cancelCoreFailure.set(unexpected);
            }
        });
        cancelThread.start();
        cancelThread.join();
        verify(cancelCoreFailure.get() == null, "cancel core had no unexpected errors: " + cancelCoreFailure.get());

        verify(!new File(cancelCoreRoot, ".active-core-binding-cancel.json").exists(), "cancellation prevented writing active pointer");
        File legacyStillReadable = cancelCoreTx.find(coreCat, "legacy-cancel-core", coreVal);
        verify(cancelLegacyDir.equals(legacyStillReadable), "legacy directory remains read-only findable after cancellation");
    }

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("linux-bundle-tx-test-");
        File root = tempDir.resolve("tools-root").toFile();
        root.mkdirs();
        try {
            testSuccessfulInstallAndStatus(root);
            testReinstallIdempotent(root);
            testV2UpdateSuccess(root);
            testProbeFailureAndCancellationPreservesCurrent(root);
            testRollbackToV1(root);
            testRollbackRefusedWhenPreviousDamaged(root);
            testFindReadOnlyDoesNotWrite(root);
            testStageAndPointerRecovery(root);
            testCorruptActiveReinstallRepair(root);
            testBindingIsolation(root);
            testSymlinkAndPathRejections(root);
            testConcurrentInstall(root);
            testNonBlockingFindAndStatusDuringInstall(root);
            testLegacyCoreMigration(root);

            System.out.println("PASS LinuxBundleTransactionTests: " + assertions + " checks, " + skips + " skips");
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }
}
