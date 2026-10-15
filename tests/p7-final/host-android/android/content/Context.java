package android.content;
import java.io.File;
import android.content.res.AssetManager;
/** Only filesystem/assets used by the publisher gate. No Android services, Keystore or SAF simulation. */
public abstract class Context {
 public abstract File getFilesDir();
 public AssetManager getAssets(){return new AssetManager();}
}
