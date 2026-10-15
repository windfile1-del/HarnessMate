package dev.wind.agent.runtime.linux;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Real Gradle CLI, independent of the embedded restricted project grammar. */
public final class LinuxGradleCommand {
    public static final String VERSION = "8.5";
    public static final String KOTLIN_VERSION = "2.0.21";
    public static final String DISTRIBUTION_SHA256 = "9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026";
    private LinuxGradleCommand() {}

    public static boolean handles(List<String> command) {
        if (command.isEmpty()) return false;
        String exe = command.get(0);
        return exe.equals("gradle") || exe.equals("gradlew") || exe.equals("./gradlew") || exe.equals("gradlew.bat");
    }

    public static List<String> prepare(List<String> command, File cwd, File workspaceRoot) throws IOException {
        return prepare(command, cwd, workspaceRoot, VERSION, DISTRIBUTION_SHA256, "/opt/vega-jvm", "512m");
    }

    static List<String> prepare(List<String> command, File cwd, File workspaceRoot,
            String version, String distributionSha, String tools, String memory) throws IOException {
        if (!handles(command)) return command;
        File root = workspaceRoot.getCanonicalFile();
        File directory = cwd.getCanonicalFile();
        if (!directory.equals(root) && !directory.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("R2_GRADLE_PROJECT_OUTSIDE_MIRROR");
        // A module may inherit the enclosing project's wrapper, but never another mount's.
        boolean wrapperFound = false;
        for (File current = directory; current != null; current = current.getParentFile()) {
            File properties = new File(current, "gradle/wrapper/gradle-wrapper.properties");
            if (properties.exists()) {
                wrapperFound = true;
                if (!properties.isFile() || properties.length() > 32768
                        || !properties.getCanonicalPath().startsWith(root.getPath() + File.separator))
                    throw new IOException("R2_GRADLE_WRAPPER_INVALID");
                Properties values = new Properties();
                try (FileInputStream in = new FileInputStream(properties)) { values.load(in); }
                String url = values.getProperty("distributionUrl", "");
                if (!url.matches("https://(?:services|downloads)\\.gradle\\.org/distributions/gradle-" + version.replace(".", "\\.") + "-(?:bin|all)\\.zip"))
                    throw new IOException("R2_GRADLE_VERSION_UNAVAILABLE: bundled=" + version + " requested=" + url);
                String checksum = values.getProperty("distributionSha256Sum");
                // The all distribution has a different identity and is not shipped in this slice.
                if (url.endsWith("-all.zip") || (checksum != null && !distributionSha.equals(checksum)))
                    throw new IOException("R2_GRADLE_DISTRIBUTION_MISMATCH");
                break;
            }
            if (current.equals(root)) break;
        }
        if (!command.get(0).equals("gradle") && !wrapperFound)
            throw new IOException("R2_GRADLE_WRAPPER_MISSING: use gradle or add pinned wrapper properties");
        List<String> args = command.subList(1, command.size());
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if (arg.equals("--init-script") || arg.equals("--gradle-user-home")) {
                if (++index >= args.size()) throw new IOException("R2_GRADLE_PATH_MISSING");
                String path = args.get(index);
                File local = new File(directory, path).getCanonicalFile();
                if (!path.startsWith(".harnessmate-package-cache/gradle/") ||
                        !local.getPath().startsWith(root.getPath() + File.separator) ||
                        arg.equals("--init-script") && !local.isFile())
                    throw new IOException("R2_GRADLE_SUPPORT_PATH_INVALID");
                continue;
            }
            // These options change the checked project or replace the fixed toolchain.
            if (arg.startsWith("-p") || arg.startsWith("--project-dir")
                    || arg.startsWith("-g") || arg.startsWith("--gradle-user-home")
                    || arg.startsWith("-I") || arg.startsWith("--init-script") || arg.equals("--")
                    || arg.startsWith("-Dorg.gradle.java.home") || arg.equals("--daemon")
                    || arg.startsWith("-Dorg.gradle.jvmargs") || arg.startsWith("-Dorg.gradle.native")
                    || arg.startsWith("-Pkotlin.compiler.execution.strategy"))
                throw new IOException("R2_GRADLE_RESERVED_OPTION: " + arg);
        }
        List<String> result = new ArrayList<>(Arrays.asList("/opt/jdk/bin/java", "-Xmx" + memory,
                "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", "--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED",
                "--add-opens=java.base/java.nio.charset=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED", "-Dfile.encoding=UTF-8",
                "-Dorg.gradle.native=false", "-Dorg.gradle.vfs.watch=false", "-Dorg.gradle.jvmargs=-Xmx" + memory,
                "-Dorg.gradle.internal.instrumentation.agent=false",
                "-classpath", tools + "/gradle-" + version + "/lib/gradle-launcher-" + version + ".jar", "org.gradle.launcher.GradleMain"));
        result.addAll(args);
        result.addAll(Arrays.asList("--no-daemon", "--no-watch-fs", "--max-workers=1", "--console=plain",
                "-Pkotlin.compiler.execution.strategy=in-process", "-Pkotlin.incremental=true",
                "-Porg.gradle.java.installations.auto-detect=false", "-Porg.gradle.java.installations.auto-download=false",
                "-Porg.gradle.java.installations.paths=/opt/jdk",
                "--init-script", tools + "/vega-offline.gradle"));
        if (tools.equals("/opt/vega-android"))
            result.add("-Pandroid.aapt2FromMavenOverride=/opt/vega-android/sdk/build-tools/35.0.0/aapt2");
        return result;
    }
}
