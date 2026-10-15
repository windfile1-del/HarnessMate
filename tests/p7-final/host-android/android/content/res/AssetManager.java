package android.content.res;
import java.io.*;
public class AssetManager {
 public InputStream open(String name) throws IOException {
  File root=new File(System.getProperty("harnessmate.fixture.assets")).getCanonicalFile();
  File file=new File(root,name).getCanonicalFile();
  if(!file.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Fixture asset scope");
  return new FileInputStream(file);
 }
}
