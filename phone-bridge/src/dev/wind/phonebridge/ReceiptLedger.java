package dev.wind.phonebridge;

import java.io.*;
import java.util.*;

/** Metadata-only write-ahead tombstones outlive the bounded detailed-receipt cache. */
public final class ReceiptLedger {
    public interface Journal { void append(String id, String state) throws IOException; }
    private final Journal journal;
    private final int limit;
    private final Map<String,String> states=new LinkedHashMap<>();
    public ReceiptLedger(Journal journal,int limit) { if(limit<1 || limit>2048) throw new IllegalArgumentException("JOURNAL_LIMIT"); this.journal=journal; this.limit=limit; }
    public static ReceiptLedger open(File file) throws IOException {
        ReceiptLedger ledger=new ReceiptLedger((id,state) -> {
            if(file.length()>131072) throw new IOException("JOURNAL_LIMIT_RESTART_SESSION");
            try(FileOutputStream out=new FileOutputStream(file,true)) { out.write((id+" "+state+"\n").getBytes("UTF-8")); out.getFD().sync(); }
        },1024);
        if(file.exists()) {
            if(file.length()>131072) throw new IOException("JOURNAL_LIMIT_RESTART_SESSION");
            try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"))) {
                String line; while((line=in.readLine())!=null) {
                    String[] parts=line.split(" ");
                    if(parts.length!=2 || !validId(parts[0]) || !(parts[1].equals("UNKNOWN") || parts[1].equals("DISPATCHED"))) throw new IOException("JOURNAL_CORRUPT_NO_REPLAY");
                    ledger.states.put(parts[0],parts[1]);
                    if(ledger.states.size()>ledger.limit) throw new IOException("JOURNAL_LIMIT_RESTART_SESSION");
                }
            }
        }
        return ledger;
    }
    static boolean validId(String id) { return id!=null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    public synchronized String state(String id) { return states.get(id); }
    public synchronized boolean begin(String id) throws IOException {
        if(!validId(id)) throw new IOException("REQUEST_ID_INVALID");
        if(states.containsKey(id)) return false;
        if(states.size()>=limit) throw new IOException("JOURNAL_LIMIT_RESTART_SESSION");
        // A failed fsync prevents effects and retains an in-process tombstone as a conservative stop.
        states.put(id,"UNKNOWN"); journal.append(id,"UNKNOWN"); return true;
    }
    public synchronized void finish(String id) throws IOException {
        if(!states.containsKey(id)) throw new IOException("JOURNAL_PREPARE_REQUIRED");
        journal.append(id,"DISPATCHED"); states.put(id,"DISPATCHED");
    }
}
