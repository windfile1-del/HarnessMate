package android.content;
import java.io.File;
/** Host filesystem fixture only, not an implementation of Android services or SAF. */
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public abstract File getFilesDir();
 public abstract File getCacheDir();
 public abstract File getExternalFilesDir(String type);
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
 public abstract String getPackageName();
 public Context getApplicationContext(){return this;}
}
