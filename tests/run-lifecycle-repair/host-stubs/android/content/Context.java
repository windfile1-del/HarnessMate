package android.content;

import java.io.File;

public abstract class Context {
    public abstract File getFilesDir();
    public File getCacheDir() { File cache = new File(getFilesDir(), ".host-cache"); cache.mkdirs(); return cache; }
    public Context getApplicationContext() { return this; }
}
