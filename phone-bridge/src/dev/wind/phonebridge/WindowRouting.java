package dev.wind.phonebridge;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

/** Select the interacting window on one display; window type is not a permission check. */
public final class WindowRouting {
    public static final class Window {
        public final int id,display,type,layer;
        public final boolean active,focused;
        public Window(int id,int display,int type,int layer,boolean active,boolean focused) {
            this.id=id; this.display=display; this.type=type; this.layer=layer; this.active=active; this.focused=focused;
        }
        public boolean unknownType() { return type<1 || type>6; }
        public String selection() { return focused?"FOCUSED":active?"ACTIVE":unknownType()?"UNKNOWN_TYPE_ROOT_PROBE":"TOP_APPLICATION"; }
        private int priority() {
            // IME/divider/magnifier are auxiliary windows, never the app's node-tree target.
            if(type==2 || type==5 || type==6) return -1;
            return focused?3:active?2:type==1?1:unknownType()?0:-1;
        }
    }
    public static Window select(List<Window> windows,int display) throws IOException {
        List<Window> candidates=ranked(windows,display);
        if(candidates.isEmpty()) throw new IOException("VIRTUAL_DISPLAY_WINDOW_NOT_READY");
        return candidates.get(0);
    }
    @FunctionalInterface public interface RootProbe { boolean readable(Window window) throws IOException; }
    /** Unknown type is not a drawing/focus state. Test its own root before rejecting it. */
    public static Window selectReadable(List<Window> windows,int display,RootProbe probe) throws IOException {
        List<Window> candidates=ranked(windows,display);
        if(candidates.isEmpty()) throw new IOException("VIRTUAL_DISPLAY_WINDOW_NOT_READY");
        Window first=candidates.get(0);
        if(!first.unknownType()) return first;
        for(Window candidate:candidates) {
            if(!candidate.unknownType()) break;
            if(probe.readable(candidate)) return candidate;
            // Do not act underneath an unreadable focused/active modal window.
            if(candidate.focused || candidate.active) break;
        }
        throw new IOException("VIRTUAL_DISPLAY_ROOT_UNAVAILABLE");
    }
    private static List<Window> ranked(List<Window> windows,int display) throws IOException {
        if(display<=0) throw new IOException("VIRTUAL_DISPLAY_TARGET_INVALID");
        if(windows.size()>128) throw new IOException("VIRTUAL_DISPLAY_WINDOW_LIMIT");
        Set<Integer> ids=new HashSet<>(); List<Window> candidates=new ArrayList<>();
        for(Window window:windows) {
            if(window.display!=display || window.id<0 || !ids.add(window.id)) throw new IOException("VIRTUAL_DISPLAY_WINDOW_UNVERIFIED");
            if(window.priority()<0) continue;
            candidates.add(window);
        }
        candidates.sort((a,b) -> {
            int rank=Integer.compare(b.priority(),a.priority());
            if(rank==0) rank=Integer.compare(b.layer,a.layer);
            return rank==0?Integer.compare(a.id,b.id):rank;
        });
        return candidates;
    }
    /** Temporary observation/preflight errors never revoke the display resource. */
    public static boolean requiresRelease(String error) {
        return error.equals("SESSION_CHANGED") ||
            error.equals("VIRTUAL_DISPLAY_SESSION_CHANGED") || error.equals("VIRTUAL_DISPLAY_LOST") ||
            error.equals("VIRTUAL_DISPLAY_EVENT_ROUTING_FAILED") || error.contains("ISOLATION") || error.contains("GEOMETRY");
    }
}
