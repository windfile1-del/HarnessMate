package android.content;

import java.io.File;

public class ContextWrapper extends Context {
    private final Context base;
    public ContextWrapper(Context base) { this.base = base; }
    @Override public File getFilesDir() { return base.getFilesDir(); }
    @Override public Context getApplicationContext() { return base.getApplicationContext(); }
}
