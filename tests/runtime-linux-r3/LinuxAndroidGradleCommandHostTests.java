package dev.wind.agent.runtime.linux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class LinuxAndroidGradleCommandHostTests {
    private static final AtomicInteger assertionCount = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        File scratchDir = new File(args.length > 0 ? args[0] : ".build-linux-r3/command-tests/scratch");
        deleteRecursively(scratchDir);
        scratchDir.mkdirs();

        testConstants();
        testR2PreservesVersion85(scratchDir);
        testR3SelectsAndroidProjectAndSubmodule(scratchDir);
        testSelectsOutsideMirrorAndMetadataInvalid(scratchDir);
        testWrapperVersionAndHashValidation(scratchDir);
        testMissingWrapperFailsForGradlew(scratchDir);
        testFullClasspathOfflineInitInprocessOptions(scratchDir);

        System.out.println("ALL_R3_GRADLE_HOST_TESTS_PASS assertions=" + assertionCount.get());
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static void testConstants() {
        require("8.9".equals(LinuxAndroidGradleCommand.VERSION), "VERSION must be 8.9");
        require("8.7.2".equals(LinuxAndroidGradleCommand.AGP_VERSION), "AGP_VERSION must be 8.7.2");
        require("35.0.0".equals(LinuxAndroidGradleCommand.BUILD_TOOLS), "BUILD_TOOLS must be 35.0.0");
        require("d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab".equals(LinuxAndroidGradleCommand.DISTRIBUTION_SHA256),
                "DISTRIBUTION_SHA256 mismatch");
    }

    private static void testR2PreservesVersion85(File base) throws Exception {
        File r2Root = new File(base, "r2-workspace");
        r2Root.mkdirs();
        File r2Props = new File(r2Root, "gradle/wrapper/gradle-wrapper.properties");
        r2Props.getParentFile().mkdirs();
        Files.write(r2Props.toPath(), ("distributionUrl=https://services.gradle.org/distributions/gradle-8.5-bin.zip\n"
                + "distributionSha256Sum=" + LinuxGradleCommand.DISTRIBUTION_SHA256).getBytes(StandardCharsets.UTF_8));

        List<String> r2Invocation = LinuxGradleCommand.prepare(Arrays.asList("./gradlew", "classes"), r2Root, r2Root);
        require(r2Invocation.contains("-classpath"), "R2 classpath missing");
        int cpIndex = r2Invocation.indexOf("-classpath");
        String r2Classpath = r2Invocation.get(cpIndex + 1);
        require(r2Classpath.contains("/opt/vega-jvm/gradle-8.5/lib/gradle-launcher-8.5.jar"),
                "R2 must retain 8.5 /opt/vega-jvm distribution: " + r2Classpath);
        require(r2Invocation.contains("-Xmx512m"), "R2 must retain 512m heap limit");
        require(r2Invocation.contains("/opt/vega-jvm/vega-offline.gradle"), "R2 must use /opt/vega-jvm init script");

        // R2 must NOT contain android.aapt2FromMavenOverride
        boolean hasAapt2Override = false;
        for (String arg : r2Invocation) {
            if (arg.contains("android.aapt2FromMavenOverride")) {
                hasAapt2Override = true;
                break;
            }
        }
        require(!hasAapt2Override, "R2 must NOT contain android.aapt2FromMavenOverride");
    }

    private static void testR3SelectsAndroidProjectAndSubmodule(File base) throws Exception {
        File r3Root = new File(base, "r3-selects-project");
        File subModule = new File(r3Root, "app");
        subModule.mkdirs();

        // Initially no android cues: Gradle 8.5 wrapper, standard java build
        File plainProps = new File(r3Root, "gradle/wrapper/gradle-wrapper.properties");
        plainProps.getParentFile().mkdirs();
        Files.write(plainProps.toPath(), "distributionUrl=https://services.gradle.org/distributions/gradle-8.5-bin.zip\n".getBytes(StandardCharsets.UTF_8));
        require(!LinuxAndroidGradleCommand.selects(subModule, r3Root), "Non-android project should not be selected by R3");

        // 1. Cue via gradle-8.9 in wrapper properties
        Files.write(plainProps.toPath(), "distributionUrl=https://services.gradle.org/distributions/gradle-8.9-bin.zip\n".getBytes(StandardCharsets.UTF_8));
        require(LinuxAndroidGradleCommand.selects(subModule, r3Root), "R3 must select project when wrapper contains gradle-8.9-");

        // Reset wrapper to 8.5
        Files.write(plainProps.toPath(), "distributionUrl=https://services.gradle.org/distributions/gradle-8.5-bin.zip\n".getBytes(StandardCharsets.UTF_8));
        require(!LinuxAndroidGradleCommand.selects(subModule, r3Root), "Should not select after wrapper reset");

        // 2. Cue via com.android.application in subModule build.gradle
        File appBuild = new File(subModule, "build.gradle");
        Files.write(appBuild.toPath(), "plugins { id 'com.android.application' }\n".getBytes(StandardCharsets.UTF_8));
        require(LinuxAndroidGradleCommand.selects(subModule, r3Root), "R3 must select when submodule contains com.android.application");

        // 3. Cue via com.android.library
        Files.write(appBuild.toPath(), "plugins { id 'com.android.library' }\n".getBytes(StandardCharsets.UTF_8));
        require(LinuxAndroidGradleCommand.selects(subModule, r3Root), "R3 must select when submodule contains com.android.library");

        // 4. Cue via com.android.dynamic-feature in settings.gradle
        appBuild.delete();
        File settingsFile = new File(r3Root, "settings.gradle");
        Files.write(settingsFile.toPath(), "// contains com.android.dynamic-feature\ninclude ':feature'\n".getBytes(StandardCharsets.UTF_8));
        require(LinuxAndroidGradleCommand.selects(subModule, r3Root), "R3 must select when root settings contains com.android.dynamic-feature");
    }

    private static void testSelectsOutsideMirrorAndMetadataInvalid(File base) throws Exception {
        File root = new File(base, "r3-escape-root");
        root.mkdirs();
        File outside = new File(base, "r3-escape-outside");
        outside.mkdirs();

        // 1. Outside root must throw R3_ANDROID_PROJECT_OUTSIDE_MIRROR
        try {
            LinuxAndroidGradleCommand.selects(outside, root);
            throw new AssertionError("Expected R3_ANDROID_PROJECT_OUTSIDE_MIRROR for outside cwd");
        } catch (IOException e) {
            require(e.getMessage().contains("R3_ANDROID_PROJECT_OUTSIDE_MIRROR"), "Unexpected error: " + e.getMessage());
        }

        // Relative path traversal pointing outside root must throw R3_ANDROID_PROJECT_OUTSIDE_MIRROR
        File relOutside = new File(root, "../r3-escape-outside");
        try {
            LinuxAndroidGradleCommand.selects(relOutside, root);
            throw new AssertionError("Expected R3_ANDROID_PROJECT_OUTSIDE_MIRROR for relative escape");
        } catch (IOException e) {
            require(e.getMessage().contains("R3_ANDROID_PROJECT_OUTSIDE_MIRROR"), "Unexpected error: " + e.getMessage());
        }

        // 2. Metadata file size exceeding 1MB must throw R3_ANDROID_PROJECT_METADATA_INVALID
        File oversizedBuild = new File(root, "build.gradle");
        byte[] bigContent = new byte[1024 * 1024 + 16];
        Files.write(oversizedBuild.toPath(), bigContent);
        try {
            LinuxAndroidGradleCommand.selects(root, root);
            throw new AssertionError("Expected R3_ANDROID_PROJECT_METADATA_INVALID for oversized metadata file");
        } catch (IOException e) {
            require(e.getMessage().contains("R3_ANDROID_PROJECT_METADATA_INVALID"),
                    "Expected R3_ANDROID_PROJECT_METADATA_INVALID, got: " + e.getMessage());
        } finally {
            Files.deleteIfExists(oversizedBuild.toPath());
        }

        // 3. Symlink / canonical path escaping root contract
        File outsideMetadata = new File(outside, "secret-build.gradle");
        Files.write(outsideMetadata.toPath(), "com.android.application".getBytes(StandardCharsets.UTF_8));
        File symlinkFile = new File(root, "build.gradle");
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), outsideMetadata.toPath());
            if (symlinkFile.getCanonicalPath().startsWith(outside.getCanonicalPath())) {
                try {
                    LinuxAndroidGradleCommand.selects(root, root);
                    throw new AssertionError("Expected R3_ANDROID_PROJECT_METADATA_INVALID for escaping symlink");
                } catch (IOException e) {
                    require(e.getMessage().contains("R3_ANDROID_PROJECT_METADATA_INVALID"),
                            "Expected R3_ANDROID_PROJECT_METADATA_INVALID: " + e.getMessage());
                }
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Host does not support unprivileged symlinks; validated via boundary checks
        } finally {
            Files.deleteIfExists(symlinkFile.toPath());
        }
    }

    private static void testWrapperVersionAndHashValidation(File base) throws Exception {
        File root = new File(base, "r3-wrapper-validation");
        File sub = new File(root, "app");
        sub.mkdirs();
        File props = new File(root, "gradle/wrapper/gradle-wrapper.properties");
        props.getParentFile().mkdirs();

        // Incorrect wrapper version
        Files.write(props.toPath(), ("distributionUrl=https://services.gradle.org/distributions/gradle-8.5-bin.zip\n"
                + "distributionSha256Sum=" + LinuxAndroidGradleCommand.DISTRIBUTION_SHA256).getBytes(StandardCharsets.UTF_8));
        try {
            LinuxAndroidGradleCommand.prepare(Arrays.asList("./gradlew", "assembleDebug"), sub, root);
            throw new AssertionError("Expected R2_GRADLE_VERSION_UNAVAILABLE");
        } catch (IOException e) {
            require(e.getMessage().contains("R2_GRADLE_VERSION_UNAVAILABLE"), "Expected R2_GRADLE_VERSION_UNAVAILABLE: " + e.getMessage());
        }

        // Incorrect wrapper sha256
        Files.write(props.toPath(), ("distributionUrl=https://services.gradle.org/distributions/gradle-8.9-bin.zip\n"
                + "distributionSha256Sum=badhashbadhashbadhashbadhashbadhashbadhashbadhashbadhashbadhashbadhash").getBytes(StandardCharsets.UTF_8));
        try {
            LinuxAndroidGradleCommand.prepare(Arrays.asList("./gradlew", "assembleDebug"), sub, root);
            throw new AssertionError("Expected R2_GRADLE_DISTRIBUTION_MISMATCH");
        } catch (IOException e) {
            require(e.getMessage().contains("R2_GRADLE_DISTRIBUTION_MISMATCH"), "Expected R2_GRADLE_DISTRIBUTION_MISMATCH: " + e.getMessage());
        }
    }

    private static void testMissingWrapperFailsForGradlew(File base) throws Exception {
        File root = new File(base, "r3-missing-wrapper");
        File sub = new File(root, "app");
        sub.mkdirs();
        // No gradle-wrapper.properties created
        try {
            LinuxAndroidGradleCommand.prepare(Arrays.asList("./gradlew", "assembleDebug"), sub, root);
            throw new AssertionError("Expected R2_GRADLE_WRAPPER_MISSING");
        } catch (IOException e) {
            require(e.getMessage().contains("R2_GRADLE_WRAPPER_MISSING"), "Expected R2_GRADLE_WRAPPER_MISSING: " + e.getMessage());
        }
    }

    private static void testFullClasspathOfflineInitInprocessOptions(File base) throws Exception {
        File root = new File(base, "r3-full-options");
        File sub = new File(root, "app");
        sub.mkdirs();
        File props = new File(root, "gradle/wrapper/gradle-wrapper.properties");
        props.getParentFile().mkdirs();
        Files.write(props.toPath(), ("distributionUrl=https://services.gradle.org/distributions/gradle-8.9-bin.zip\n"
                + "distributionSha256Sum=" + LinuxAndroidGradleCommand.DISTRIBUTION_SHA256).getBytes(StandardCharsets.UTF_8));

        List<String> command = Arrays.asList("./gradlew", "assembleDebug");
        List<String> prepared = LinuxAndroidGradleCommand.prepare(command, sub, root);

        require(prepared.get(0).equals("/opt/jdk/bin/java"), "Must invoke /opt/jdk/bin/java");
        require(prepared.contains("-Xmx768m"), "R3 heap memory must be 768m");
        require(prepared.contains("-Dorg.gradle.jvmargs=-Xmx768m"), "R3 gradle jvmargs must be -Xmx768m");

        int cpIdx = prepared.indexOf("-classpath");
        require(cpIdx >= 0 && cpIdx + 1 < prepared.size(), "Classpath flag missing");
        String cp = prepared.get(cpIdx + 1);
        require(cp.equals("/opt/vega-android/gradle-8.9/lib/gradle-launcher-8.9.jar"),
                "R3 classpath must point to /opt/vega-android launcher: " + cp);
        require(prepared.contains("org.gradle.launcher.GradleMain"), "GradleMain entry point missing");

        // Retain passed task arguments
        require(prepared.contains("assembleDebug"), "Task arg assembleDebug must be retained");

        // Mobile execution flags & offline options
        require(!prepared.contains("--offline"), "Launcher must preserve authorized online dependency resolution");
        require(prepared.contains("--no-daemon"), "--no-daemon flag required");
        require(prepared.contains("--no-watch-fs"), "--no-watch-fs flag required");
        require(prepared.contains("--max-workers=1"), "--max-workers=1 flag required");
        require(prepared.contains("--console=plain"), "--console=plain flag required");

        // In-process compiler and single-process flags
        require(prepared.contains("-Pkotlin.compiler.execution.strategy=in-process"),
                "Kotlin in-process compilation strategy required");
        require(prepared.contains("-Pkotlin.incremental=true"), "Kotlin incremental compilation required");

        // Toolchain and init script
        int initIdx = prepared.indexOf("--init-script");
        require(initIdx >= 0 && initIdx + 1 < prepared.size(), "--init-script flag required");
        require(prepared.get(initIdx + 1).equals("/opt/vega-android/vega-offline.gradle"),
                "R3 init script must be /opt/vega-android/vega-offline.gradle: " + prepared.get(initIdx + 1));

        // R3 FIXED AAPT2 OVERRIDE PROPERTY MUST BE PRESENT
        String expectedAaptOverride = "-Pandroid.aapt2FromMavenOverride=/opt/vega-android/sdk/build-tools/35.0.0/aapt2";
        require(prepared.contains(expectedAaptOverride),
                "R3 must include fixed CLI property: " + expectedAaptOverride);
    }

    private static void require(boolean condition, String message) {
        assertionCount.incrementAndGet();
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
