package dev.wind.agent.runtime.linux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public final class GradleCommandHostTests {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0], "mirror");
        File module = new File(root, "module");
        module.mkdirs();
        File props = new File(root, "gradle/wrapper/gradle-wrapper.properties");
        props.getParentFile().mkdirs();
        Files.write(props.toPath(), ("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip\n"
                + "distributionSha256Sum=" + LinuxGradleCommand.DISTRIBUTION_SHA256).getBytes(StandardCharsets.UTF_8));
        List<String> invocation = LinuxGradleCommand.prepare(Arrays.asList("./gradlew", "classes"), module, root);
        require(invocation.get(0).equals("/opt/jdk/bin/java") && invocation.contains("org.gradle.launcher.GradleMain"), "Real JVM launcher missing");
        require(!invocation.contains("--offline") && invocation.contains("--no-daemon") && invocation.contains("--max-workers=1"), "Launcher must not override an authorized online command");
        require(LinuxGradleCommand.prepare(Arrays.asList("gradle", "classes", "--offline"), module, root).contains("--offline"), "Explicit offline lost");
        File helper = new File(module, ".harnessmate-package-cache/gradle/init/test.gradle");
        helper.getParentFile().mkdirs(); Files.writeString(helper.toPath(), "// helper");
        require(LinuxGradleCommand.prepare(Arrays.asList("gradle", "--init-script", ".harnessmate-package-cache/gradle/init/test.gradle",
                "--gradle-user-home", ".harnessmate-package-cache/gradle/user-home", "classes"), module, root).contains(".harnessmate-package-cache/gradle/init/test.gradle"), "App dependency helper rejected");
        expect("R2_GRADLE_SUPPORT_PATH_INVALID", Arrays.asList("gradle", "--init-script", "../outside.gradle"), module, root);
        for (String option : new String[]{"-pother", "--project-dir=other", "-Ievil", "-gother", "--daemon", "-Dorg.gradle.jvmargs=-Xmx9g"}) {
            expect("R2_GRADLE_RESERVED_OPTION", Arrays.asList("gradle", option), module, root);
        }
        Files.write(props.toPath(), "distributionUrl=https://services.gradle.org/distributions/gradle-9.0-bin.zip".getBytes(StandardCharsets.UTF_8));
        expect("R2_GRADLE_VERSION_UNAVAILABLE", Arrays.asList("gradlew", "build"), module, root);
        Files.write(props.toPath(), "distributionUrl=https://services.gradle.org/distributions/gradle-8.5-bin.zip\ndistributionSha256Sum=invalid".getBytes(StandardCharsets.UTF_8));
        expect("R2_GRADLE_DISTRIBUTION_MISMATCH", Arrays.asList("gradle", "build"), module, root);
        expect("R2_GRADLE_PROJECT_OUTSIDE_MIRROR", Arrays.asList("gradle", "build"), root.getParentFile(), root);
        require(!LinuxGradleCommand.handles(Arrays.asList("custom/gradlew")), "Unrelated executable intercepted");
        System.out.println("PASS Gradle wrapper identity, network intent, managed helper paths and mirror root");
    }
    private static void expect(String code, List<String> command, File cwd, File root) throws Exception {
        try { LinuxGradleCommand.prepare(command, cwd, root); throw new AssertionError("Expected " + code); }
        catch (java.io.IOException error) { require(error.getMessage().startsWith(code), error.toString()); }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
