import com.android.repository.Revision;
import com.android.sdklib.BuildToolInfo;
import com.android.utils.StdLogger;
import java.nio.file.*;

/** Uses the unmodified sdklib shipped alongside AGP 8.7.2, not our own whitelist. */
public final class BuildToolsLayoutTests {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var info = BuildToolInfo.fromStandardDirectoryLayout(new Revision(35, 0, 0), root);
        var log = new StdLogger(StdLogger.Level.ERROR);
        if (!info.isValid(log)) throw new AssertionError("Actual packaged SDK rejected by sdklib");
        int checks = 1;
        for (var id : BuildToolInfo.PathId.values()) {
            if (!id.isPresentIn(new Revision(35, 0, 0))) continue;
            Path path = Path.of(info.getPath(id));
            System.out.println("SDK35_REQUIRED " + id + " " + path.getFileName());
        }
        Path split = root.resolve("split-select");
        Path backup = root.resolve("split-select.test-backup");
        Files.move(split, backup);
        try {
            if (info.isValid(log)) throw new AssertionError("Missing split-select incorrectly accepted");
            checks++;
        } finally { Files.move(backup, split); }
        if (!info.isValid(log)) throw new AssertionError("Restored split-select rejected");
        System.out.println("BUILD_TOOLS_LAYOUT_PASS checks=" + (checks + 1));
    }
}
