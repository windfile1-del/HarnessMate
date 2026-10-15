package android.system;

public final class ErrnoException extends Exception {
    public final int errno;
    public ErrnoException(String functionName, int errno) { super(functionName); this.errno = errno; }
}
