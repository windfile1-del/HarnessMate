package dev.wind.phonebridge;

/** Coalesced, bounded metadata handoff. Chat text and pixels never enter this mailbox. */
public final class ChatEventMailbox {
    public static final class Event {
        public final long epoch; public final int display, window, type; public final String pkg;
        Event(long e,int d,int w,int t,String p) { epoch=e; display=d; window=w; type=t; pkg=p; }
    }
    private Event latest=new Event(0,-1,-1,0,"");
    private int waiters;
    public synchronized void publish(long epoch,int display,int window,int type,String pkg) {
        if(epoch<=latest.epoch) return;
        latest=new Event(epoch,display,window,type,pkg==null?"":pkg); notifyAll();
    }
    public synchronized Event await(long after,long timeoutMs) throws InterruptedException {
        if(after < -1 || timeoutMs<0 || timeoutMs>10000 || waiters>=2) throw new IllegalArgumentException("CHAT_EVENT_WAIT_INVALID");
        long end=System.nanoTime()+timeoutMs*1000000L; waiters++;
        try {
            while(latest.epoch<=after) {
                long remaining=(end-System.nanoTime())/1000000L;
                if(remaining<=0) break;
                wait(Math.max(1,remaining));
            }
            return latest;
        } finally { waiters--; }
    }
}
