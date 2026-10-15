package dev.wind.agent.runtime.linux;

import android.content.Context;
import dev.wind.agent.runtime.RuntimeProcessSupervisor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses the R0 DSHA/Mobile Harness PRoot recipe with the authoritative P3 workspace. */
public final class LinuxProcessFactory implements RuntimeProcessSupervisor.ProcessFactory {
    private final Context context;
    private final File rootfs;
    public LinuxProcessFactory(Context context, File rootfs) { this.context = context; this.rootfs = rootfs; }
    public Process start(List<String> command, File directory, Map<String, String> supplied) throws IOException {
        if (command.isEmpty() || !command.get(0).matches("/?[A-Za-z0-9_./+-]+"))
            throw new IOException("RUNTIME_EXECUTABLE_INVALID");
        File runtime = new File(context.getFilesDir(), "linux-runtime-root").getCanonicalFile();
        File cwd = directory.getCanonicalFile();
        if (!cwd.getPath().startsWith(runtime.getPath() + File.separator + "workspaces" + File.separator))
            throw new IOException("RUNTIME_WORKSPACE_INVALID");
        File project = cwd;
        while (project != null && !new File(project.getParentFile(), ".vega/runtime_binding.json").isFile()) project = project.getParentFile();
        if (project == null || !project.getName().equals("project")) throw new IOException("RUNTIME_WORKSPACE_INVALID");
        File workspace = project.getParentFile();
        File lsp = null;
        try { lsp = LinuxLspInstaller.findInstalled(context); }
        catch (Exception failure) { /* Optional intelligence never disables core commands. */ }
        File jvm;
        try { jvm = LinuxJvmInstaller.findInstalled(context); }
        catch (Exception failure) {
            if (LinuxGradleCommand.handles(command)) throw new IOException("R2_JVM_UNAVAILABLE: " + failure, failure);
            jvm = null;
        }
        boolean androidProject = LinuxGradleCommand.handles(command) && LinuxAndroidGradleCommand.selects(cwd, project);
        File android = null;
        try { android = LinuxAndroidInstaller.findInstalled(context); }
        catch (Exception failure) {
            if (androidProject) throw new IOException("R3_ANDROID_UNAVAILABLE: " + failure, failure);
        }
        if (LinuxGradleCommand.handles(command)) {
            if (jvm == null) throw new IOException("R2_JVM_NOT_INSTALLED: import and install the JVM build extension in Settings");
            if (androidProject && android == null) throw new IOException("R3_ANDROID_NOT_INSTALLED: import and install the Android build extension in Settings");
            command = androidProject ? LinuxAndroidGradleCommand.prepare(command, cwd, project)
                    : LinuxGradleCommand.prepare(command, cwd, project);
        }
        File libs = new File(context.getApplicationInfo().nativeLibraryDir).getCanonicalFile();
        for (String name : new String[]{"libproot.so", "libprootloader.so", "libtalloc.so", "libandroidshmem.so", "libvegaspawn.so"})
            if (!new File(libs, name).isFile()) throw new IOException("RUNTIME_NATIVE_UNAVAILABLE");
        LinuxNativeSpawn.load(libs.getPath());
        if (LinuxNativeSpawn.apiVersion() != 3) throw new IOException("RUNTIME_NATIVE_API_MISMATCH");
        File temp = new File(context.getCacheDir(), "linux-r1-tmp");
        if (!temp.isDirectory() && !temp.mkdirs()) throw new IOException("RUNTIME_TMP_UNAVAILABLE");
        String relative = project.toPath().relativize(cwd.toPath()).toString().replace(File.separatorChar, '/');
        List<String> argv = new ArrayList<>(Arrays.asList(new File(libs, "libproot.so").getPath(),
                "-L", "--kill-on-exit", "-0", "--rootfs=" + rootfs.getCanonicalPath(), "--cwd=/workspace" + (relative.isEmpty() ? "" : "/" + relative)));
        for (String path : new String[]{"/dev", "/proc", "/sys", "/system", "/apex", "/vendor", "/product", "/linkerconfig"})
            if (new File(path).exists()) argv.add("--bind=" + path);
        argv.add("--bind=" + project + ":/workspace");
        argv.add("--bind=" + new File(workspace, "home") + ":/root");
        argv.add("--bind=" + new File(workspace, "tmp") + ":/tmp");
        if (jvm != null) argv.add("--bind=" + jvm.getCanonicalPath() + ":/opt/vega-jvm");
        if (lsp != null) argv.add("--bind=" + lsp.getCanonicalPath() + ":/opt/vega-lsp");
        if (android != null) {
            argv.add("--bind=" + android.getCanonicalPath() + ":/opt/vega-android");
            argv.add("--bind=" + libs + ":/opt/vega-native");
            LinuxAndroidNative.addBinds(argv, libs);
        }
        argv.add("/usr/bin/env");
        argv.add("-i");
        argv.add("--");
        argv.addAll(Arrays.asList("HOME=/root", "TMPDIR=/tmp", "JAVA_HOME=/opt/jdk",
                "PATH=" + (android == null ? "" : "/opt/vega-android/bin:/opt/vega-android/sdk/build-tools/35.0.0:")
                        + (lsp == null ? "" : "/opt/vega-lsp/bin:")
                        + "/opt/jdk/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "GLIBC_TUNABLES=glibc.pthread.rseq=0"));
        if (android != null) argv.addAll(Arrays.asList("ANDROID_HOME=/opt/vega-android/sdk", "ANDROID_SDK_ROOT=/opt/vega-android/sdk"));
        if (jvm != null) argv.add("GRADLE_USER_HOME=/root/.gradle/" + (androidProject ? "vega-r3/" + android.getName() : "vega-r2/" + jvm.getName()));
        for (Map.Entry<String, String> entry : supplied.entrySet()) {
            String key = entry.getKey();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || key.startsWith("LD_") || key.startsWith("PROOT_")
                    || key.equals("GLIBC_TUNABLES") || key.equals("HOME") || key.equals("TMPDIR") || key.equals("PATH") || key.equals("JAVA_HOME")
                    || key.equals("ANDROID_HOME") || key.equals("ANDROID_SDK_ROOT")
                    || key.equals("GRADLE_USER_HOME") || key.equals("JAVA_OPTS") || key.equals("JAVA_TOOL_OPTIONS")
                    || key.equals("JDK_JAVA_OPTIONS") || key.equals("_JAVA_OPTIONS") || key.equals("GRADLE_OPTS"))
                throw new IOException("RUNTIME_ENV_RESERVED");
            argv.add(key + "=" + entry.getValue());
        }
        argv.addAll(command);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LD_LIBRARY_PATH", libs.getPath()); env.put("PROOT_LOADER", new File(libs, "libprootloader.so").getPath());
        env.put("PROOT_TMP_DIR", temp.getPath()); env.put("PROOT_NO_SECCOMP", "1");
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0"); env.put("HOME", "/root"); env.put("TMPDIR", "/tmp");
        env.put("JAVA_HOME", "/opt/jdk"); env.put("PATH", "/opt/jdk/bin:/usr/local/bin:/usr/bin:/bin"); env.put("LANG", "C.UTF-8");
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) values.add(entry.getKey() + "=" + entry.getValue());
        int[] child = LinuxNativeSpawn.spawn(argv.toArray(new String[0]), values.toArray(new String[0]), cwd.getPath());
        if (child == null || child.length != 5) throw new IOException("RUNTIME_NATIVE_SPAWN_FAILED");
        return new LinuxManagedProcess(child);
    }
}
