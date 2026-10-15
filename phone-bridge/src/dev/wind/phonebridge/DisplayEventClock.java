package dev.wind.phonebridge;

import java.util.concurrent.atomic.AtomicLong;

/** Page changes are scoped by display; a shared focus transfer is not a page mutation. */
public final class DisplayEventClock {
    public final AtomicLong events=new AtomicLong(),structural=new AtomicLong();
    public volatile long lastEvent;
    public static boolean focusOnly(int type,int changes) {
        return type==4194304 && changes!=0 && (changes & ~224)==0;
    }
    public void record(int type,int changes,long now) {
        if(focusOnly(type,changes)) return;
        events.incrementAndGet(); lastEvent=now;
        if(type==32 || type==4194304) structural.incrementAndGet();
    }
}
