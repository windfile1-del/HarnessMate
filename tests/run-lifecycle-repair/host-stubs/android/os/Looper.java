package android.os;

public class Looper {
    private static final Looper MAIN_LOOPER = new Looper();
    private static final ThreadLocal<Looper> CURRENT = new ThreadLocal<Looper>();

    public static Looper getMainLooper() {
        return MAIN_LOOPER;
    }

    public static Looper myLooper() {
        return CURRENT.get();
    }

    public static void setAsMainThread() {
        CURRENT.set(MAIN_LOOPER);
    }

    public static void clearMainThread() {
        CURRENT.remove();
    }
}
