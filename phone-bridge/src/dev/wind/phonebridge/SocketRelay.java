package dev.wind.phonebridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

/** Fixed shell-UID pipe for image/lease sockets when adbd localabstract forwarding is unavailable. */
public final class SocketRelay {
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=2000 || args.length!=2 || !args[0].matches("[a-f0-9]{32}") || !args[1].matches("image|lease"))
            throw new SecurityException("RELAY_SCOPE_INVALID");
        LocalSocket socket=new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress("vega_phone_"+args[0]+"_"+args[1],LocalSocketAddress.Namespace.ABSTRACT));
        } catch(Exception failure) {
            Main.write(new java.io.DataOutputStream(System.out),new JSONObject().put("status","START_FAILED").put("version",Main.VERSION)
                .put("session",args[0]).put("error","PHONE_BRIDGE_RELAY_CONNECT_FAILED")
                .put("diagnostic",new JSONObject().put("failure_stage","RELAY_CONNECT").put("exception_class",failure.getClass().getName())).toString().getBytes("UTF-8"));
            socket.close(); return;
        }
        Thread input=new Thread(() -> {
            try { copy(System.in,socket.getOutputStream()); }
            catch(Exception stopped) { }
            finally { try { socket.close(); } catch(Exception ignored) { } }
        },"vega-phone-relay-input"); input.setDaemon(true); input.start();
        try { copy(socket.getInputStream(),System.out); }
        finally { socket.close(); }
    }
    private static void copy(InputStream in,OutputStream out) throws Exception {
        byte[] buffer=new byte[65536]; int size;
        while((size=in.read(buffer))!=-1) { out.write(buffer,0,size); out.flush(); }
    }
}
