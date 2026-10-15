package android.content;
import java.io.File;
public class ContextWrapper extends Context {
 private final Context base;
 public ContextWrapper(Context base){this.base=base;}
 public File getFilesDir(){return base.getFilesDir();}
 public File getCacheDir(){return base.getCacheDir();}
 public File getExternalFilesDir(String type){return base.getExternalFilesDir(type);}
 public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(name,mode);}
 public String getPackageName(){return base.getPackageName();}
 public Context getApplicationContext(){return base.getApplicationContext();}
}
