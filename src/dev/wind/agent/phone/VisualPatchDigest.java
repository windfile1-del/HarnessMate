package dev.wind.agent.phone;
import java.security.MessageDigest;
/** Identical app/Bridge pixel proof. No OCR, loose names or approximate matches grant authority. */
public final class VisualPatchDigest {
    public static String digest(int[] pixels,int width,int height,int[] rect) throws Exception {
        if(width<=0||height<=0||pixels.length!=width*height||rect.length!=4||
           rect[0]<0||rect[1]<0||rect[2]>1000||rect[3]>1000||rect[2]<=rect[0]||rect[3]<=rect[1])
            throw new IllegalArgumentException("CHAT_VISUAL_REGION_INVALID");
        int l=rect[0]*width/1000,t=rect[1]*height/1000,r=rect[2]*width/1000,b=rect[3]*height/1000;
        if(r<=l||b<=t) throw new IllegalArgumentException("CHAT_VISUAL_REGION_INVALID");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(int y=t;y<b;y++) for(int x=l;x<r;x++) {
            int pixel=pixels[y*width+x];
            hash.update((byte)(pixel>>16)); hash.update((byte)(pixel>>8)); hash.update((byte)pixel);
        }
        StringBuilder out=new StringBuilder();for(byte value:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return out.toString();
    }
}
