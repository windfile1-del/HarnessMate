package android.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-only stand-in for the Android Os calls used by the R0 TAR reader. */
public final class Os {
    private static final Map<Path, String> SYMLINKS = new LinkedHashMap<>();
    private static final Map<Path, Path> HARDLINKS = new LinkedHashMap<>();
    private static final Map<Path, Integer> MODES = new LinkedHashMap<>();
    private static int linkErrno;
    private Os() {}

    public static StructStat lstat(String value) throws ErrnoException {
        Path path = key(value);
        if (SYMLINKS.containsKey(path)) return new StructStat(OsConstants.S_IFLNK);
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            int mode = attrs.isDirectory() ? OsConstants.S_IFDIR : OsConstants.S_IFREG;
            return new StructStat(mode | MODES.getOrDefault(path, 0644));
        } catch (IOException e) { throw new ErrnoException("lstat", OsConstants.ENOENT); }
    }
    public static void chmod(String value, int mode) throws ErrnoException { MODES.put(key(value), mode & 0777); }
    public static void link(String existing, String added) throws ErrnoException {
        if (linkErrno != 0) throw new ErrnoException("link", linkErrno);
        Path from = key(existing), to = key(added);
        try {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            HARDLINKS.put(to, from);
            MODES.put(to, MODES.getOrDefault(from, 0644));
        } catch (IOException e) { throw new ErrnoException("link", 1); }
    }
    public static void symlink(String target, String added) throws ErrnoException {
        Path path = key(added);
        try { Files.createFile(path); SYMLINKS.put(path, target); }
        catch (IOException e) { throw new ErrnoException("symlink", 1); }
    }
    public static String readlink(String value) { return SYMLINKS.get(key(value)); }
    public static Path hardlinkTarget(String value) { return HARDLINKS.get(key(value)); }
    public static void clearRecords() { SYMLINKS.clear(); HARDLINKS.clear(); MODES.clear(); linkErrno = 0; }
    public static void failHardlinks(int errno) { linkErrno = errno; }
    private static Path key(String value) { return Paths.get(value).toAbsolutePath().normalize(); }
}
