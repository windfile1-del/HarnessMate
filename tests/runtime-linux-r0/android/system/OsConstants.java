package android.system;

public final class OsConstants {
    public static final int ENOENT = 2;
    public static final int EPERM = 1, EIO = 5, EACCES = 13, EEXIST = 17;
    public static final int EXDEV = 18, ENOSPC = 28, ENOSYS = 38, EOPNOTSUPP = 95;
    public static final int S_IFMT = 0170000;
    public static final int S_IFREG = 0100000;
    public static final int S_IFDIR = 0040000;
    public static final int S_IFLNK = 0120000;
    private OsConstants() {}
    public static boolean S_ISREG(int mode) { return (mode & S_IFMT) == S_IFREG; }
    public static boolean S_ISDIR(int mode) { return (mode & S_IFMT) == S_IFDIR; }
    public static boolean S_ISLNK(int mode) { return (mode & S_IFMT) == S_IFLNK; }
}
