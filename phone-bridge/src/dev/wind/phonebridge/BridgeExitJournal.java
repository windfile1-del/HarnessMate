package dev.wind.phonebridge;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded private diagnostic, containing no exception text, request arguments, images or secrets. */
public final class BridgeExitJournal {
    private final File file;
    private final int pid;
    private final AtomicBoolean stopped=new AtomicBoolean();
    public BridgeExitJournal(File directory,String session,int pid) throws IOException {
        if(!session.matches("[a-f0-9]{32}") || pid<=0) throw new IOException("DIAGNOSTIC_ID_INVALID");
        file=new File(directory,"diagnostic-"+session);
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("DIAGNOSTIC_PATH_INVALID");
        this.pid=pid;
    }
    public void started() { write("RUNNING","STARTED","START",""); }
    public void stopped(String code,String method) { if(stopped.compareAndSet(false,true)) write("EXIT",code,method,""); }
    public void displayClosed(String code,String method) { if(stopped.compareAndSet(false,true)) write("DISPLAY_CLOSED",code,method,""); }
    public void crashed(String code,String method,Thread thread,Throwable failure) {
        if(!stopped.compareAndSet(false,true)) return;
        StringBuilder causes=new StringBuilder(),stack=new StringBuilder();
        Throwable cause=failure.getCause();
        for(int i=0;cause!=null && cause!=failure && i<3;i++,cause=cause.getCause()) {
            if(i>0) causes.append(';'); causes.append(identifier(cause.getClass().getName(),160));
        }
        StackTraceElement[] frames=failure.getStackTrace();
        for(int i=0;i<frames.length && i<8;i++) {
            if(i>0) stack.append(';');
            stack.append(identifier(frames[i].getClassName(),160)).append('#')
                .append(identifier(frames[i].getMethodName(),100)).append(':').append(Math.max(-2,frames[i].getLineNumber()));
        }
        String name=thread.getName();
        if(!name.matches("main|vega-phone-(ui|images|heartbeats|lease|client|expired-cleanup)|phone-virtual-frames")) name="OTHER";
        write("EXIT",code,method,"diagnostic_version=1\nthread="+name+"\nexception_class="+identifier(failure.getClass().getName(),160)
            +"\ncause_classes="+causes+"\nstack_frames="+stack+"\n");
    }
    private static String identifier(String value,int limit) {
        return value.length()<=limit && value.matches("[A-Za-z_$][A-Za-z0-9_.$<>]*")?value:"UNKNOWN";
    }
    private synchronized void write(String state,String code,String method,String detail) {
        if(!code.matches("[A-Z][A-Z0-9_]{2,100}") || !method.matches("[A-Za-z_]{1,40}")) return;
        if(state.equals("RUNNING") && stopped.get()) return;
        // Diagnostic I/O must never turn a successful operation into an action replay.
        try {
            if(!file.getCanonicalFile().equals(file.getAbsoluteFile())) return;
            try(FileOutputStream out=new FileOutputStream(file,false)) {
                out.write(("state="+state+"\npid="+pid+"\ncode="+code+"\nmethod="+method+"\nat="+System.currentTimeMillis()+"\n"+detail).getBytes("UTF-8"));
            }
        } catch(IOException unavailable) { }
    }
}
