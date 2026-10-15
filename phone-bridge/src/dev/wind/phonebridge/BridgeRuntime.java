package dev.wind.phonebridge;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Shell app_process needs an Android runtime environment, not only Looper.prepare(). */
final class BridgeRuntime {
    static Context initialize() throws Exception {
        Main.startupStage="MAIN_LOOPER";
        if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Main.startupStage="ACTIVITY_THREAD";
        Class<?> type=Class.forName("android.app.ActivityThread");
        Object at=type.getMethod("systemMain").invoke(null);
        if(Build.VERSION.SDK_INT>=31) {
            Main.startupStage="CONFIGURATION_CONTROLLER";
            Field field=type.getDeclaredField("mConfigurationController"); field.setAccessible(true);
            if(field.get(at)==null) {
                Constructor<?> ctor=Class.forName("android.app.ConfigurationController").getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
                ctor.setAccessible(true); field.set(at,ctor.newInstance(at));
            }
        }
        Main.startupStage="SYSTEM_CONTEXT";
        Context context=(Context)type.getMethod("getSystemContext").invoke(at);
        // Framework/OEM display code may read currentApplication and bound app identity.
        // These are shell-process local fields; no installed app or system setting is changed.
        Field initial=type.getDeclaredField("mInitialApplication"); initial.setAccessible(true);
        if(initial.get(at)==null) {
            Context shell=Build.VERSION.SDK_INT>=31?new VirtualScreen.ShellContext31(context):new VirtualScreen.ShellContext(context);
            Application app=Instrumentation.newApplication(Application.class,shell); initial.set(at,app);
        }
        Field bound=type.getDeclaredField("mBoundApplication"); bound.setAccessible(true);
        if(bound.get(at)==null && !Build.BRAND.equalsIgnoreCase("ONYX")) {
            Class<?> data=Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ctor=data.getDeclaredConstructor(); ctor.setAccessible(true); Object value=ctor.newInstance();
            Field info=data.getDeclaredField("appInfo"); info.setAccessible(true);
            ApplicationInfo application=new ApplicationInfo(); application.packageName="com.android.shell"; application.uid=2000;
            info.set(value,application); bound.set(at,value);
        }
        return context;
    }
}
