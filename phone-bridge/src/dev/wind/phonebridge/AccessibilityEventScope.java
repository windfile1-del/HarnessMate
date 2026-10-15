package dev.wind.phonebridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional API-33 event display id; API-30/31/32 use verified window metadata. */
public final class AccessibilityEventScope {
    public interface WindowDisplayLookup { int displayForWindow(int window) throws Exception; }
    private volatile Method displayMethod;
    public AccessibilityEventScope(Class<?> eventClass) {
        try { displayMethod=eventClass.getMethod("getDisplayId"); }
        catch(NoSuchMethodException | SecurityException | LinkageError unavailable) { displayMethod=null; }
    }
    public boolean hasDisplayMethod() { return displayMethod!=null; }
    public boolean belongsTo(Object event,int window,int target,WindowDisplayLookup windows) throws Exception {
        if(target<0) return false;
        Method method=displayMethod;
        if(method!=null) {
            try {
                Object value=method.invoke(event);
                if(value instanceof Integer && (Integer)value>=0) return (Integer)value==target;
            } catch(InvocationTargetException failed) {
                Throwable cause=failed.getCause();
                if(cause instanceof LinkageError || cause instanceof SecurityException) displayMethod=null;
                else if(cause instanceof Error) throw (Error)cause;
                else if(cause instanceof Exception) throw (Exception)cause;
                else throw failed;
            } catch(IllegalAccessException | SecurityException | LinkageError unavailable) { displayMethod=null; }
        }
        return window>=0 && windows.displayForWindow(window)==target;
    }
}
