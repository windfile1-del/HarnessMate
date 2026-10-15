package dev.wind.agent.runtime.linux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Full AGP CLI; the Android overlay never replaces R2's JVM distribution. */
public final class LinuxAndroidGradleCommand {
    public static final String VERSION = "8.9";
    public static final String AGP_VERSION = "8.7.2";
    public static final String BUILD_TOOLS = "35.0.0";
    public static final String DISTRIBUTION_SHA256 = "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab";
    private LinuxAndroidGradleCommand() {}

    public static boolean selects(File cwd, File workspaceRoot) throws IOException {
        File root = workspaceRoot.getCanonicalFile();
        File current = cwd.getCanonicalFile();
        if (!current.equals(root) && !current.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("R3_ANDROID_PROJECT_OUTSIDE_MIRROR");
        for (; current != null; current = current.getParentFile()) {
            for (String name : new String[]{"gradle/wrapper/gradle-wrapper.properties", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}) {
                File file = new File(current, name);
                if (!file.isFile()) continue;
                if (file.length() > 1024 * 1024 || !file.getCanonicalPath().startsWith(root.getPath() + File.separator))
                    throw new IOException("R3_ANDROID_PROJECT_METADATA_INVALID");
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (text.contains("gradle-8.9-") || text.contains("com.android.application")
                        || text.contains("com.android.library") || text.contains("com.android.dynamic-feature")) return true;
            }
            if (current.equals(root)) break;
        }
        return false;
    }

    public static List<String> prepare(List<String> command, File cwd, File workspaceRoot) throws IOException {
        return LinuxGradleCommand.prepare(command, cwd, workspaceRoot, VERSION,
                DISTRIBUTION_SHA256, "/opt/vega-android", "768m");
    }
}
