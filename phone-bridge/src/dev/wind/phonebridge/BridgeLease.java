package dev.wind.phonebridge;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Independent authenticated heartbeat may renew while the owner dispatch monitor is occupied. */
public final class BridgeLease {
    public interface Clock { long now(); }
    private final Clock clock;
    private final AtomicLong deadline;
    private final AtomicBoolean owner=new AtomicBoolean();
    private final AtomicBoolean closed=new AtomicBoolean();
    public BridgeLease(Clock clock) { this.clock=clock; deadline=new AtomicLong(clock.now()+15000); }
    public boolean acquireOwner() { return !closed.get() && owner.compareAndSet(false,true); }
    public boolean ownerConnected() { return owner.get() && !closed.get(); }
    public boolean expired() { return closed.get() || clock.now()>deadline.get(); }
    public synchronized void renew(boolean heartbeat,String method) throws IOException {
        if(heartbeat && !"ping".equals(method)) throw new IOException("HEARTBEAT_METHOD_INVALID");
        if(heartbeat && !ownerConnected()) throw new IOException("CONTROL_OWNER_REQUIRED");
        if(expired()) throw new IOException("LEASE_EXPIRED");
        deadline.set(clock.now()+12000);
    }
    public void closeOwner() { closed.set(true); owner.set(false); }
}
