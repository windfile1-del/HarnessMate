package dev.wind.phonebridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Resolve a typed Binder call before recording or attempting an effect; never retry it. */
public final class ActivityStartCall {
    private final Object manager;
    private final Method method;
    public final String strategy;
    private ActivityStartCall(Object manager,Method method,String strategy) { this.manager=manager; this.method=method; this.strategy=strategy; }
    public static ActivityStartCall resolve(Object manager,Class<?> caller,Class<?> intent,Class<?> binder,Class<?> profiler,Class<?> bundle) throws Exception {
        Method method; String strategy;
        try {
            method=manager.getClass().getMethod("startActivityAsUser",caller,String.class,intent,String.class,binder,String.class,int.class,int.class,profiler,bundle,int.class);
            strategy="START_ACTIVITY_AS_USER";
        } catch(NoSuchMethodException oldUnavailable) {
            method=manager.getClass().getMethod("startActivityAsUserWithFeature",caller,String.class,String.class,intent,String.class,binder,String.class,int.class,int.class,profiler,bundle,int.class);
            strategy="START_ACTIVITY_AS_USER_WITH_FEATURE";
        }
        method.setAccessible(true); return new ActivityStartCall(manager,method,strategy);
    }
    public int start(Object intent,Object options,int user) throws Exception {
        Object[] args=strategy.equals("START_ACTIVITY_AS_USER") ?
            new Object[]{null,"com.android.shell",intent,null,null,null,0,0,null,options,user} :
            new Object[]{null,"com.android.shell",null,intent,null,null,null,0,0,null,options,user};
        try {
            Object result=method.invoke(manager,args);
            if(!(result instanceof Integer)) throw new java.io.IOException("VIRTUAL_DISPLAY_START_RESULT_INVALID");
            return (Integer)result;
        } catch(InvocationTargetException wrapped) {
            Throwable cause=wrapped.getCause();
            if(cause instanceof Exception) throw (Exception)cause;
            if(cause instanceof Error) throw (Error)cause;
            throw wrapped;
        }
    }
    public static boolean accepted(int result) { return result==0 || result==2 || result==3; }
}
