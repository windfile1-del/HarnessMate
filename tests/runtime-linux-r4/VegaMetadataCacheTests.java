import org.eclipse.jdt.ls.core.internal.filesystem.VegaMetadataCache;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Pure JDK regression tests for VegaMetadataCache.
 *
 * Verifies:
 * 1. Only .project, .classpath, .factorypath, .settings are seeded into cache.
 * 2. Source bytes remain unchanged.
 * 3. Modifying files in cache does not write back to source.
 * 4. Repeated seed does not overwrite existing cache entries.
 * 5. Cache directory cannot be located inside source directory.
 * 6. Symlinked source metadata or symlinked cache is rejected (skipped if symlink creation not permitted).
 */
public final class VegaMetadataCacheTests {
    private static int assertions = 0;

    private static void verify(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Path tempBase = Files.createTempDirectory("vega-metadata-cache-test-");
        try {
            testMetadataOnlyAndSourcePreserved(tempBase);
            testCacheModificationDoesNotWriteBack(tempBase);
            testRepeatedSeedDoesNotOverwriteCache(tempBase);
            testCacheCannotBeLocatedInsideSource(tempBase);
            testSymlinkSourceAndCacheRejected(tempBase);

            System.out.println("PASS VegaMetadataCacheTests: " + assertions + " checks");
        } finally {
            deleteRecursively(tempBase);
        }
    }

    private static void testMetadataOnlyAndSourcePreserved(Path tempBase) throws Exception {
        Path project = tempBase.resolve("project-1");
        Path cache = tempBase.resolve("cache-1");
        Files.createDirectories(project);

        // Metadata files and folders
        byte[] projectBytes = "test-project-config".getBytes("UTF-8");
        byte[] classpathBytes = "test-classpath-config".getBytes("UTF-8");
        byte[] factorypathBytes = "test-factorypath-config".getBytes("UTF-8");
        byte[] prefsBytes = "org.eclipse.jdt.core.compiler.compliance=17".getBytes("UTF-8");
        byte[] nestedPrefsBytes = "nested.setting=true".getBytes("UTF-8");

        Files.write(project.resolve(".project"), projectBytes);
        Files.write(project.resolve(".classpath"), classpathBytes);
        Files.write(project.resolve(".factorypath"), factorypathBytes);

        Path settingsDir = project.resolve(".settings");
        Path nestedDir = settingsDir.resolve("sub");
        Files.createDirectories(nestedDir);
        Files.write(settingsDir.resolve("org.eclipse.jdt.core.prefs"), prefsBytes);
        Files.write(nestedDir.resolve("nested.prefs"), nestedPrefsBytes);

        // Non-metadata files that must NOT be copied
        byte[] srcBytes = "public class Main {}".getBytes("UTF-8");
        byte[] buildGradleBytes = "plugins { id 'java' }".getBytes("UTF-8");
        byte[] readmeBytes = "Project Documentation".getBytes("UTF-8");

        Path srcDir = project.resolve("src");
        Files.createDirectories(srcDir);
        Files.write(srcDir.resolve("Main.java"), srcBytes);
        Files.write(project.resolve("build.gradle"), buildGradleBytes);
        Files.write(project.resolve("README.md"), readmeBytes);

        // Execute seed
        VegaMetadataCache.seed(project.toFile(), cache.toFile());

        // Verify metadata files exist in cache with identical bytes
        verify(Files.isRegularFile(cache.resolve(".project")), "cache .project exists");
        verify(Arrays.equals(Files.readAllBytes(cache.resolve(".project")), projectBytes), "cache .project bytes match");

        verify(Files.isRegularFile(cache.resolve(".classpath")), "cache .classpath exists");
        verify(Arrays.equals(Files.readAllBytes(cache.resolve(".classpath")), classpathBytes), "cache .classpath bytes match");

        verify(Files.isRegularFile(cache.resolve(".factorypath")), "cache .factorypath exists");
        verify(Arrays.equals(Files.readAllBytes(cache.resolve(".factorypath")), factorypathBytes), "cache .factorypath bytes match");

        verify(Files.isRegularFile(cache.resolve(".settings/org.eclipse.jdt.core.prefs")), "cache prefs exists");
        verify(Arrays.equals(Files.readAllBytes(cache.resolve(".settings/org.eclipse.jdt.core.prefs")), prefsBytes), "cache prefs bytes match");

        verify(Files.isRegularFile(cache.resolve(".settings/sub/nested.prefs")), "cache nested prefs exists");
        verify(Arrays.equals(Files.readAllBytes(cache.resolve(".settings/sub/nested.prefs")), nestedPrefsBytes), "cache nested prefs bytes match");

        verify(Files.isRegularFile(cache.resolve(".vega-metadata-seeded")), "cache receipt marker created");

        // Verify non-metadata files were NOT copied
        verify(!Files.exists(cache.resolve("src")), "src must not be copied to cache");
        verify(!Files.exists(cache.resolve("build.gradle")), "build.gradle must not be copied to cache");
        verify(!Files.exists(cache.resolve("README.md")), "README.md must not be copied to cache");

        // Verify source files remain completely untouched
        verify(Arrays.equals(Files.readAllBytes(project.resolve(".project")), projectBytes), "source .project unchanged");
        verify(Arrays.equals(Files.readAllBytes(project.resolve(".classpath")), classpathBytes), "source .classpath unchanged");
        verify(Arrays.equals(Files.readAllBytes(project.resolve(".factorypath")), factorypathBytes), "source .factorypath unchanged");
        verify(Arrays.equals(Files.readAllBytes(settingsDir.resolve("org.eclipse.jdt.core.prefs")), prefsBytes), "source prefs unchanged");
        verify(Arrays.equals(Files.readAllBytes(nestedDir.resolve("nested.prefs")), nestedPrefsBytes), "source nested prefs unchanged");
        verify(Arrays.equals(Files.readAllBytes(srcDir.resolve("Main.java")), srcBytes), "source Main.java unchanged");
        verify(Arrays.equals(Files.readAllBytes(project.resolve("build.gradle")), buildGradleBytes), "source build.gradle unchanged");
        verify(Arrays.equals(Files.readAllBytes(project.resolve("README.md")), readmeBytes), "source README.md unchanged");
    }

    private static void testCacheModificationDoesNotWriteBack(Path tempBase) throws Exception {
        Path project = tempBase.resolve("project-2");
        Path cache = tempBase.resolve("cache-2");
        Files.createDirectories(project);

        byte[] origProject = "original-project".getBytes("UTF-8");
        byte[] origClasspath = "original-classpath".getBytes("UTF-8");
        Files.write(project.resolve(".project"), origProject);
        Files.write(project.resolve(".classpath"), origClasspath);

        VegaMetadataCache.seed(project.toFile(), cache.toFile());

        // Modify files inside cache
        byte[] modifiedProject = "modified-in-cache".getBytes("UTF-8");
        Files.write(cache.resolve(".project"), modifiedProject);
        Files.delete(cache.resolve(".classpath"));
        Files.write(cache.resolve("extra-cache-file.txt"), "cache-only".getBytes("UTF-8"));

        // Verify source files are NOT affected
        verify(Arrays.equals(Files.readAllBytes(project.resolve(".project")), origProject), "source .project not overwritten by cache edits");
        verify(Files.exists(project.resolve(".classpath")), "source .classpath still exists after cache deletion");
        verify(Arrays.equals(Files.readAllBytes(project.resolve(".classpath")), origClasspath), "source .classpath unchanged");
        verify(!Files.exists(project.resolve("extra-cache-file.txt")), "extra cache file does not leak to source");
    }

    private static void testRepeatedSeedDoesNotOverwriteCache(Path tempBase) throws Exception {
        Path project = tempBase.resolve("project-3");
        Path cache = tempBase.resolve("cache-3");
        Files.createDirectories(project);

        byte[] origProject = "project-v1".getBytes("UTF-8");
        Files.write(project.resolve(".project"), origProject);

        VegaMetadataCache.seed(project.toFile(), cache.toFile());

        // Modify cache entry
        byte[] jdtEdits = "jdt-modified-project-v2".getBytes("UTF-8");
        Files.write(cache.resolve(".project"), jdtEdits);

        // Update project source to a new version
        byte[] updatedProject = "project-v3".getBytes("UTF-8");
        Files.write(project.resolve(".project"), updatedProject);

        // Repeated seed must be a no-op due to receipt marker and not overwrite cache
        VegaMetadataCache.seed(project.toFile(), cache.toFile());

        verify(Arrays.equals(Files.readAllBytes(cache.resolve(".project")), jdtEdits), "repeated seed preserves JDT cache modifications");
        verify(Arrays.equals(Files.readAllBytes(project.resolve(".project")), updatedProject), "source remains v3");
    }

    private static void testCacheCannotBeLocatedInsideSource(Path tempBase) throws Exception {
        Path project = tempBase.resolve("project-4");
        Files.createDirectories(project);
        Files.write(project.resolve(".project"), "test".getBytes("UTF-8"));

        // Direct child cache
        Path insideCache1 = project.resolve(".jdt-cache");
        boolean caught1 = false;
        try {
            VegaMetadataCache.seed(project.toFile(), insideCache1.toFile());
        } catch (IOException e) {
            caught1 = e.getMessage() != null && e.getMessage().contains("R4_JDT_CACHE_INSIDE_SOURCE");
        }
        verify(caught1, "cache inside source rejected (direct child)");

        // Deeply nested child cache
        Path insideCache2 = project.resolve("deep/nested/jdt-cache");
        boolean caught2 = false;
        try {
            VegaMetadataCache.seed(project.toFile(), insideCache2.toFile());
        } catch (IOException e) {
            caught2 = e.getMessage() != null && e.getMessage().contains("R4_JDT_CACHE_INSIDE_SOURCE");
        }
        verify(caught2, "cache inside source rejected (deeply nested)");
    }

    private static void testSymlinkSourceAndCacheRejected(Path tempBase) throws Exception {
        // Probe if symlinks can be created
        Path probeTarget = tempBase.resolve("probe-target.txt");
        Path probeLink = tempBase.resolve("probe-link.txt");
        Files.write(probeTarget, "probe".getBytes("UTF-8"));
        try {
            Files.createSymbolicLink(probeLink, probeTarget);
        } catch (Exception e) {
            System.out.println("SKIP: symlink creation not supported/permitted on this platform: " + e.getMessage());
            return;
        } finally {
            try { Files.deleteIfExists(probeLink); } catch (Exception ignored) {}
            try { Files.deleteIfExists(probeTarget); } catch (Exception ignored) {}
        }

        // 1. Symlinked metadata file inside source project
        Path projectSymSource = tempBase.resolve("project-sym-source");
        Path cacheForSymSource = tempBase.resolve("cache-for-sym-source");
        Files.createDirectories(projectSymSource);
        Path externalTarget = tempBase.resolve("external-project-file.txt");
        Files.write(externalTarget, "external".getBytes("UTF-8"));

        Path symProjectFile = projectSymSource.resolve(".project");
        Files.createSymbolicLink(symProjectFile, externalTarget);

        boolean caughtSymSource = false;
        try {
            VegaMetadataCache.seed(projectSymSource.toFile(), cacheForSymSource.toFile());
        } catch (IOException e) {
            caughtSymSource = e.getMessage() != null && (
                e.getMessage().contains("R4_JDT_METADATA_SYMLINK") || e.getMessage().contains("R4_JDT_CACHE_SYMLINK")
            );
        }
        verify(caughtSymSource, "symlinked metadata in source rejected");

        // 2. Symlinked cache directory
        Path normalProject = tempBase.resolve("project-for-sym-cache");
        Files.createDirectories(normalProject);
        Files.write(normalProject.resolve(".project"), "normal".getBytes("UTF-8"));

        Path realCacheTarget = tempBase.resolve("real-cache-target");
        Files.createDirectories(realCacheTarget);
        Path symCacheDir = tempBase.resolve("sym-cache-dir");
        Files.createSymbolicLink(symCacheDir, realCacheTarget);

        boolean caughtSymCache = false;
        try {
            VegaMetadataCache.seed(normalProject.toFile(), symCacheDir.toFile());
        } catch (IOException e) {
            caughtSymCache = e.getMessage() != null && e.getMessage().contains("R4_JDT_CACHE_SYMLINK");
        }
        verify(caughtSymCache, "symlinked cache directory rejected");
    }
}
