package dev.wind.agent.runtime.linux;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Private, bounded installation diagnostics, retained when failed staging is removed. */
public final class LinuxRuntimeDiagnostics {
    private static final int LIMIT = 65536;
    private LinuxRuntimeDiagnostics() {}

    public static void record(Context context, String detail) {
        File pending = null;
        try {
            File directory = new File(context.getFilesDir(), "linux-runtime-diagnostics");
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            pending = new File(directory, UUID.randomUUID() + ".tmp");
            if (detail.length() > 15000) detail = detail.substring(0, 3000)
                    + "\n[intermediate output omitted]\n" + detail.substring(detail.length() - 12000);
            String report = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
                    + ")\nABI: " + java.util.Arrays.toString(Build.SUPPORTED_ABIS)
                    + "\nTimestamp: " + System.currentTimeMillis() + "\n" + detail;
            byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(bytes, 0, Math.min(bytes.length, LIMIT));
                output.getFD().sync();
            }
            // Atomic replacement: readers in the host/service see a complete report.
            pending.renameTo(new File(directory, "last-failure.txt"));
        } catch (Exception ignored) {
            // Diagnostics must never replace the original installation failure.
        } finally { if (pending != null) pending.delete(); }
    }

    public static String last(Context context) {
        File file = new File(context.getFilesDir(), "linux-runtime-diagnostics/last-failure.txt");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[LIMIT];
            int size = 0, count;
            while (size < bytes.length && (count = input.read(bytes, size, bytes.length - size)) > 0) size += count;
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }
}
