package dev.wind.phonebridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.app.UiAutomation;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

/** First-party shell-owned display. No projection token, default-display capture, or global IME/clipboard writes. */
final class VirtualScreen implements AutoCloseable {
    final String id = UUID.randomUUID().toString();
    private final Context context;
    private final Object frameLock = new Object();
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread frames;
    private Image latest;
    private long frameSequence;
    private volatile boolean closed;
    private volatile String closeReason="VIRTUAL_DISPLAY_LOST",closeStage="NONE";
    private volatile long closedAt;
    private volatile String launchedPackage="";
    private volatile JSONObject windowRouting=new JSONObject();
    private volatile int displayId = -1;
    private volatile boolean focusIsolated;
    private volatile int topFocusedDisplay=-1;
    private volatile int imePolicy=-1;
    private String imeConfiguration="NOT_ATTEMPTED";
    private String imeReadback="NOT_CHECKED";
    private boolean imeApisAvailable, internalWindowAuthorized;
    private int requestedFlags;
    volatile String eventPackage = "";
    volatile int eventWindow = -1;
    private long minimumFrameSequence = 1;
    private LaunchPlan launchPlan;
    JSONObject lastLaunchReceipt;
    private static final class BorrowedTask {
        final String pkg; final int user;
        BorrowedTask(String pkg,int user) { this.pkg=pkg; this.user=user; }
    }
    private final java.util.Map<Integer,BorrowedTask> borrowedMainRoots=new java.util.LinkedHashMap<>();
    private final JSONArray taskReturns=new JSONArray();
    static final class LaunchFailure extends IOException {
        final JSONObject diagnostic;
        LaunchFailure(String code,Throwable cause,JSONObject diagnostic) { super(code,cause); this.diagnostic=diagnostic; }
    }
    private static final class LaunchPlan {
        final String pkg,component;
        final int user;
        final Intent intent;
        final ActivityStartCall call;
        final String policy;
        final List<TaskInventory.Task> before;
        LaunchPlan(String pkg,String component,int user,Intent intent,ActivityStartCall call,String policy,List<TaskInventory.Task> before) {
            this.pkg=pkg; this.component=component; this.user=user; this.intent=intent; this.call=call;
            this.policy=policy; this.before=before;
        }
    }

    static class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public Context getApplicationContext() { return this; }
    }
    // Keep API-31 types out of the API-30 context class.
    static final class ShellContext31 extends ShellContext {
        ShellContext31(Context base) { super(base); }
        @Override public android.content.AttributionSource getAttributionSource() {
            return new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
        }
    }
    VirtualScreen(Context base) throws Exception {
        if (base == null || Build.VERSION.SDK_INT < 30) throw new IOException("VIRTUAL_DISPLAY_REQUIRES_ANDROID_11");
        context = Build.VERSION.SDK_INT >= 31 ? new ShellContext31(base) : new ShellContext(base);
    }
    static int constant(Class<?> clazz, String name) throws Exception {
        Field f = clazz.getDeclaredField(name); f.setAccessible(true); return f.getInt(null);
    }
    static Object service(String name, String type) throws Exception {
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IOException("VIRTUAL_DISPLAY_SYSTEM_SERVICE_UNAVAILABLE");
        return Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    static Object call(Object receiver,String name,Class<?>[] signature,Object... args) throws Exception {
        Method method=receiver.getClass().getMethod(name,signature);
        method.setAccessible(true); // Binder Stub.Proxy implementations are non-public classes.
        try { return method.invoke(receiver,args); }
        catch(java.lang.reflect.InvocationTargetException wrapped) {
            Throwable cause=wrapped.getCause();
            if(cause instanceof SecurityException) throw new IOException("VIRTUAL_DISPLAY_SHELL_PERMISSION_DENIED",cause);
            if(cause instanceof Exception) throw (Exception)cause;
            throw wrapped;
        }
    }
    /** WM rejects IME changes on untrusted displays even when shell has INTERNAL_SYSTEM_WINDOW. */
    static String configureIme(Object wm,int id,boolean trusted,boolean authorized,boolean apiAvailable) {
        if(!trusted) return "UNTRUSTED_DISPLAY";
        if(!authorized) return "INTERNAL_SYSTEM_WINDOW_DENIED";
        if(!apiAvailable) return "IME_API_UNAVAILABLE";
        try { call(wm,"setDisplayImePolicy",new Class<?>[]{int.class,int.class},id,2); return "CONFIGURED"; }
        catch(Exception unavailable) { return "VIRTUAL_DISPLAY_SHELL_PERMISSION_DENIED".equals(unavailable.getMessage()) ? "IME_PERMISSION_DENIED" : "IME_CONFIGURATION_UNAVAILABLE"; }
    }
    private static String permission(Context base,String name) {
        try { return base.checkPermission("android.permission."+name,android.os.Process.myPid(),android.os.Process.myUid())==android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED"; }
        catch(Exception unavailable) { return "CHECK_UNAVAILABLE"; }
    }
    static final class CreationFailure extends IOException {
        final JSONObject diagnostic;
        CreationFailure(String code,Throwable cause,JSONObject diagnostic) { super(code,cause); this.diagnostic=diagnostic; }
    }
    static String creationError(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof SecurityException) return "VIRTUAL_DISPLAY_SHELL_PERMISSION_DENIED";
        return Main.safeError(failure);
    }
    static JSONObject capabilities(Context base) {
        JSONObject out = new JSONObject();
        try {
            if (base == null || Build.VERSION.SDK_INT < 30) throw new IOException("VIRTUAL_DISPLAY_REQUIRES_ANDROID_11");
            JSONObject permissions=new JSONObject();
            for(String name:new String[]{"ADD_TRUSTED_DISPLAY","INTERNAL_SYSTEM_WINDOW","INJECT_EVENTS"}) permissions.put(name,permission(base,name));
            out.put("authority",new JSONObject().put("uid",android.os.Process.myUid()).put("package","com.android.shell")
                .put("permissions",permissions).put("app_runtime_grant_required",false));
            if(android.os.Process.myUid()!=2000) throw new IOException("SHELL_UID_REQUIRED");
            JSONArray checks=new JSONArray(),missing=new JSONArray(),missingFocus=new JSONArray(),missingOptional=new JSONArray();
            boolean imeApis=true;
            boolean trustedFlagPresent=true;
            List<VirtualDisplayPolicy.ApiCheck> apiChecks=VirtualDisplayPolicy.checkApis(r -> {
                Class<?> type=Class.forName(r.owner);
                if(r.parameters==null) constant(type,r.member); else type.getMethod(r.member,r.parameters);
            });
            for(VirtualDisplayPolicy.ApiCheck check:apiChecks) {
                checks.put(new JSONObject().put("api",check.requirement.name()).put("required",check.requirement.required).put("available",check.available).put("error",check.error));
                if(!check.available) {
                    (check.requirement.required?missing:missingOptional).put(check.requirement.name());
                    if(!check.requirement.required && !check.requirement.member.contains("ImePolicy")) missingFocus.put(check.requirement.name());
                }
                if(!check.available && check.requirement.member.contains("ImePolicy")) imeApis=false;
                if(!check.available && check.requirement.member.equals("VIRTUAL_DISPLAY_FLAG_TRUSTED")) trustedFlagPresent=false;
            }
            boolean trusted=trustedFlagPresent && "GRANTED".equals(permissions.optString("ADD_TRUSTED_DISPLAY"));
            out.put("checks",checks).put("missing_apis",missing).put("missing_focus_apis",missingFocus).put("missing_optional_apis",missingOptional)
                .put("ime_apis_available",imeApis).put("internal_system_window_authorized","GRANTED".equals(permissions.optString("INTERNAL_SYSTEM_WINDOW")))
                .put("ime_isolation_policy","BEST_EFFORT").put("ime_isolation_required",false)
                .put("trusted_display_authorized",trusted).put("focus_isolation_available",VirtualDisplayPolicy.focusApisPresent(apiChecks) && trusted)
                .put("focus_contention_possible",true).put("calibration_required",false);
            if(!VirtualDisplayPolicy.usable(apiChecks)) throw new IOException("VIRTUAL_DISPLAY_REQUIRED_APIS_UNAVAILABLE");
            out.put("supported", true).put("status", "API_READY");
        } catch (Exception failure) {
            try { out.put("supported", false).put("status", "NOT_SUPPORTED").put("error", failure instanceof IOException ? failure.getMessage() : "VIRTUAL_DISPLAY_FOCUS_OR_IME_API_UNAVAILABLE"); } catch (Exception ignored) {}
        }
        try { out.put("android_api", Build.VERSION.SDK_INT).put("aosp_focus_baseline_api",34).put("verification","API_SIGNATURES_ONLY")
            .put("policy_version", VirtualDisplayPolicy.POLICY_VERSION).put("width",720).put("height",1280).put("dpi",320); } catch (Exception ignored) {}
        return out;
    }
    JSONObject create() throws Exception {
        String stage="CAPABILITIES";
        JSONObject caps=capabilities(context);
        try {
            if(!caps.optBoolean("supported")) throw new IOException(caps.optString("error","VIRTUAL_DISPLAY_REQUIRED_APIS_UNAVAILABLE"));
            imeApisAvailable=caps.optBoolean("ime_apis_available"); internalWindowAuthorized=caps.optBoolean("internal_system_window_authorized");
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            for (String name : new String[]{"SUPPORTS_TOUCH", "DESTROY_CONTENT_ON_REMOVAL"})
                flags |= constant(DisplayManager.class, "VIRTUAL_DISPLAY_FLAG_" + name);
            if(caps.optBoolean("trusted_display_authorized")) flags |= constant(DisplayManager.class,"VIRTUAL_DISPLAY_FLAG_TRUSTED");
            if(caps.optBoolean("focus_isolation_available")) {
                flags |= constant(DisplayManager.class,"VIRTUAL_DISPLAY_FLAG_OWN_FOCUS") | constant(DisplayManager.class,"VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED");
            }
            try { flags |= constant(DisplayManager.class, "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED"); } catch (Exception optional) {}
            requestedFlags=flags; stage="ALLOCATE_SURFACE";
            frames = new HandlerThread("phone-virtual-frames"); frames.start();
            reader = ImageReader.newInstance(720,1280,PixelFormat.RGBA_8888,3);
            reader.setOnImageAvailableListener(r -> {
                synchronized(frameLock) {
                    if (closed) return;
                    try {
                        Image next = r.acquireLatestImage();
                        if (next != null) { if(latest != null) latest.close(); latest = next; frameSequence++; frameLock.notifyAll(); }
                    } catch (IllegalStateException stopped) { /* close can race with callback */ }
                }
            }, new Handler(frames.getLooper()));
            Constructor<DisplayManager> ctor = DisplayManager.class.getDeclaredConstructor(Context.class); ctor.setAccessible(true);
            stage="CREATE_DISPLAY";
            display = ctor.newInstance(context).createVirtualDisplay("HarnessVirtual-" + id,720,1280,320,reader.getSurface(),flags);
            if (display == null || display.getDisplay() == null || display.getDisplay().getDisplayId() <= 0) throw new IOException("VIRTUAL_DISPLAY_CREATE_FAILED");
            displayId = display.getDisplay().getDisplayId();
            // Optional enhancement only: older shell UIDs cannot create TRUSTED displays.
            if(caps.optBoolean("trusted_display_authorized") && internalWindowAuthorized && imeApisAvailable) {
                try { imeConfiguration=configureIme(service("window","android.view.IWindowManager"),displayId,true,true,true); }
                catch(Exception unavailable) { imeConfiguration="IME_SERVICE_UNAVAILABLE"; }
            } else imeConfiguration=configureIme(null,displayId,caps.optBoolean("trusted_display_authorized"),internalWindowAuthorized,imeApisAvailable);
            stage="VERIFY_DISPLAY";
            verifyIsolation();
            return status().put("status","CREATED");
        } catch(Exception failure) {
            JSONObject diagnostic=new JSONObject().put("failure_stage",stage).put("authority",caps.optJSONObject("authority"))
                .put("requested_flags",requestedFlags).put("display_id",displayId).put("ime_configuration",imeConfiguration);
            close(); throw new CreationFailure(creationError(failure),failure,diagnostic);
        }
    }
    int displayId() { return displayId; }
    boolean isClosed() { return closed; }
    String closeReason() { return closeReason; }
    JSONObject windowDiagnostic() throws Exception {
        JSONObject render;
        synchronized(frameLock) {
            render=new JSONObject().put("display_id",displayId).put("buffer_available",latest!=null).put("frame_sequence",frameSequence)
                .put("frame_timestamp_ns",latest==null?JSONObject.NULL:latest.getTimestamp())
                .put("verification","DISPLAY_BUFFER_ONLY_NOT_APP_CONTENT");
        }
        return new JSONObject().put("failure_stage","WINDOW_OBSERVATION").put("window_routing",windowRouting)
            .put("display",status()).put("render",render);
    }
    void require(String supplied) throws Exception { VirtualDisplayPolicy.session(closed ? null : id,supplied,displayId); verifyIsolation(); }
    private void verifyIsolation() throws Exception {
        if(closed) throw new IOException(closeReason);
        if(display == null || !display.getDisplay().isValid()) throw new IOException("VIRTUAL_DISPLAY_LOST");
        Display d = display.getDisplay(); int flags = d.getFlags();
        int removal=(Integer)call(d,"getRemoveMode",new Class<?>[0]);
        VirtualDisplayPolicy.runtime(removal);
        imePolicy=-1; imeReadback="NOT_AVAILABLE";
        if(imeApisAvailable && internalWindowAuthorized) try {
            imePolicy=(Integer)call(service("window","android.view.IWindowManager"),"getDisplayImePolicy",new Class<?>[]{int.class},displayId);
            imeReadback="READ";
        } catch(Exception unavailable) { imeReadback="IME_READBACK_UNAVAILABLE"; }
        focusIsolated=false; topFocusedDisplay=-1;
        try {
            topFocusedDisplay=(Integer)call(service("window","android.view.IWindowManager"),"getTopFocusedDisplayId",new Class<?>[0]);
            focusIsolated=(flags & constant(Display.class,"FLAG_TRUSTED"))!=0 && (flags & constant(Display.class,"FLAG_OWN_FOCUS"))!=0
                && (flags & constant(Display.class,"FLAG_STEAL_TOP_FOCUS_DISABLED"))!=0 && topFocusedDisplay==0;
        } catch(Exception unavailable) { /* shared-focus mode intentionally supports older focus APIs/permissions */ }
        DisplayMetrics dm=new DisplayMetrics(); d.getRealMetrics(dm); VirtualDisplayPolicy.geometry(dm.widthPixels,dm.heightPixels,d.getRotation());
    }
    void verifyLiveness() throws Exception { verifyIsolation(); }
    JSONObject status() throws Exception {
        return new JSONObject().put("display_session_id",id).put("display_id",displayId).put("display_epoch",id)
                .put("geometry_epoch",1).put("width",720).put("height",1280).put("rotation",0).put("dpi",320)
                .put("status",closed?"CLOSED":"READY").put("ime_policy",imePolicy==2?"HIDE":imePolicy==1?"FALLBACK_DISPLAY":imePolicy==0?"LOCAL":"UNKNOWN")
                .put("ime_configuration",imeConfiguration).put("ime_readback",imeReadback).put("ime_isolated",!closed && imePolicy==2)
                .put("ime_isolation_required",false).put("requested_flags",requestedFlags).put("focus_isolated",!closed && focusIsolated)
                .put("focus_mode",focusIsolated?"ISOLATION_FLAGS_ENABLED":"SHARED_FOCUS").put("focus_verification","RUNTIME_FLAGS_ONLY")
                .put("top_focused_display_id",topFocusedDisplay<0?JSONObject.NULL:topFocusedDisplay).put("focus_contention_possible",true).put("calibration_required",false)
                .put("host_interactive",((android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE)).isInteractive())
                .put("host_keyguard_locked",((android.app.KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked())
                .put("display_state",display==null?JSONObject.NULL:display.getDisplay().getState())
                .put("lock_policy","RETAIN_SESSION").put("locked_content_policy","SYSTEM_CONTROLLED_NO_UNLOCK")
                .put("borrowed_main_tasks",borrowedMainRoots.size()).put("task_returns",taskReturns)
                .put("launched_package",launchedPackage).put("close_reason",!closed?JSONObject.NULL:
                    new JSONObject().put("code",closeReason).put("stage",closeStage).put("at",closedAt).put("initiator","HARNESS"));
    }
    List<AccessibilityWindowInfo> windows(UiAutomation ui) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new IOException("VIRTUAL_DISPLAY_WINDOWS_UNSUPPORTED");
        // API 30/31 may retain the all-display snapshot taken before this display existed.
        // Refresh only this shell process's accessibility cache; never change system settings.
        refreshWindowCache();
        android.util.SparseArray<List<AccessibilityWindowInfo>> all=ui.getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> ws=all.get(displayId);
        for(int i=0;i<all.size();i++) if(all.keyAt(i)!=displayId) for(AccessibilityWindowInfo w:all.valueAt(i)) w.recycle();
        if(ws==null || ws.isEmpty()) {
            windowRouting=new JSONObject().put("display_id",displayId).put("checked_at",System.currentTimeMillis())
                .put("windows",new JSONArray()).put("selection","WINDOW_NOT_READY");
            throw new IOException("VIRTUAL_DISPLAY_WINDOW_NOT_READY");
        }
        return ws;
    }
    static void refreshWindowCache() {
        try {
            Class<?> client=Class.forName("android.view.accessibility.AccessibilityInteractionClient");
            Object instance=client.getMethod("getInstance").invoke(null);
            client.getMethod("clearCache").invoke(instance);
        } catch(Exception | LinkageError unavailable) { /* Use framework event invalidation when absent. */ }
    }
    int displayForWindow(UiAutomation ui,int window) throws Exception {
        if(closed || displayId<=0 || window<0) return -1;
        android.util.SparseArray<List<AccessibilityWindowInfo>> all=ui.getWindowsOnAllDisplays();
        int found=-1;
        try {
            // Read ids only. No main-screen root, text or active-window fallback.
            for(int i=0;i<all.size();i++) for(AccessibilityWindowInfo w:all.valueAt(i)) {
                if(w.getId()!=window) continue;
                int actual=w.getDisplayId();
                if(actual!=all.keyAt(i) || found>=0) throw new IOException("VIRTUAL_DISPLAY_WINDOW_UNVERIFIED");
                found=actual;
            }
            return found;
        } finally { for(int i=0;i<all.size();i++) for(AccessibilityWindowInfo w:all.valueAt(i)) w.recycle(); }
    }
    int windowId(UiAutomation ui) throws Exception {
        List<AccessibilityWindowInfo> ws=windows(ui);
        List<WindowRouting.Window> candidates=new java.util.ArrayList<>(); JSONArray catalog=new JSONArray();
        try {
            for(AccessibilityWindowInfo w:ws) {
                candidates.add(new WindowRouting.Window(w.getId(),w.getDisplayId(),w.getType(),w.getLayer(),w.isActive(),w.isFocused()));
                catalog.put(new JSONObject().put("id",w.getId()).put("display_id",w.getDisplayId()).put("type",w.getType())
                    .put("layer",w.getLayer()).put("active",w.isActive()).put("focused",w.isFocused()));
            }
            windowRouting=new JSONObject().put("display_id",displayId).put("checked_at",System.currentTimeMillis()).put("windows",catalog);
            JSONArray probes=new JSONArray(); windowRouting.put("root_probes",probes);
            WindowRouting.Window selected=WindowRouting.selectReadable(candidates,displayId,candidate -> {
                for(AccessibilityWindowInfo w:ws) if(w.getId()==candidate.id) {
                    AccessibilityNodeInfo node=w.getRoot();
                    JSONObject result=new JSONObject();
                    try {
                        // No focus change, main-screen root or title/text sampling.
                        if(node!=null && node.getWindowId()!=candidate.id) throw new IOException("PHONE_WINDOW_CHANGED");
                        result.put("window_id",candidate.id).put("readable",node!=null);
                        probes.put(result);
                        return node!=null;
                    } catch(org.json.JSONException invalid) { throw new IOException("WINDOW_DIAGNOSTIC_FAILED",invalid); }
                    finally { if(node!=null) node.recycle(); }
                }
                throw new IOException("PHONE_WINDOW_CHANGED");
            });
            windowRouting.put("selected_id",selected.id).put("selected_type",selected.type).put("selection",selected.selection());
            return selected.id;
        } finally { for(AccessibilityWindowInfo w:ws) w.recycle(); }
    }
    AccessibilityNodeInfo root(UiAutomation ui) throws Exception {
        return rootForWindow(ui,windowId(ui));
    }
    private AccessibilityNodeInfo rootForWindow(UiAutomation ui,int target) throws Exception {
        List<AccessibilityWindowInfo> ws=windows(ui);
        try { for(AccessibilityWindowInfo w:ws) if(w.getId()==target) {
            if(w.getDisplayId()!=displayId) throw new IOException("VIRTUAL_DISPLAY_WINDOW_UNVERIFIED");
            AccessibilityNodeInfo node=w.getRoot();
            if(node==null) throw new IOException("VIRTUAL_DISPLAY_ROOT_UNAVAILABLE");
            if(node.getWindowId()!=target) { node.recycle(); throw new IOException("PHONE_WINDOW_CHANGED"); }
            return node;
        } }
        finally { for(AccessibilityWindowInfo w:ws) w.recycle(); }
        throw new IOException("PHONE_WINDOW_CHANGED");
    }
    JSONObject metadata(UiAutomation ui, boolean diagnostic) throws Exception {
        verifyIsolation(); int window=windowId(ui);
        String pkg;
        // Event package may describe an IME or another overlay: read the selected root's identity.
        AccessibilityNodeInfo node=rootForWindow(ui,window);
        try { CharSequence name=node.getPackageName(); pkg=name==null?"":name.toString(); }
        finally { node.recycle(); }
        if(pkg.isEmpty()) throw new IOException("VIRTUAL_DISPLAY_PACKAGE_NOT_READY");
        VirtualDisplayPolicy.packageName(pkg,diagnostic);
        return status().put("package",pkg).put("window","uia:"+window).put("window_routing",windowRouting)
            .put("captured_at",System.currentTimeMillis()).put("status","COMPLETED");
    }
    void associateEvent(int window, String pkg) {
        if(window!=eventWindow || !pkg.equals(eventPackage)) barrier();
        eventWindow=window; eventPackage=pkg;
    }
    void barrier() { synchronized(frameLock) { minimumFrameSequence=frameSequence+1; } }
    final class FocusLease implements AutoCloseable {
        final Object tasks;
        final int originalTask;
        final boolean borrowed;
        boolean restored;
        FocusLease(Object tasks,int originalTask,boolean borrowed) { this.tasks=tasks; this.originalTask=originalTask; this.borrowed=borrowed; }
        @Override public void close() throws Exception {
            if(!borrowed) return;
            Object current=call(tasks,"getFocusedRootTaskInfo",new Class<?>[0]);
            // A user's newer focus wins; never restore an old main task over a different focus.
            if(current!=null && current.getClass().getField("displayId").getInt(current)==displayId) {
                call(tasks,"setFocusedTask",new Class<?>[]{int.class},originalTask);
                Object after=call(tasks,"getFocusedRootTaskInfo",new Class<?>[0]);
                restored=after!=null && after.getClass().getField("displayId").getInt(after)==0;
            }
        }
    }
    FocusLease rememberMainFocus() throws Exception {
        Object tasks=service("activity_task","android.app.IActivityTaskManager");
        Object focused=call(tasks,"getFocusedRootTaskInfo",new Class<?>[0]);
        if(focused!=null && focused.getClass().getField("displayId").getInt(focused)==0)
            return new FocusLease(tasks,focused.getClass().getField("taskId").getInt(focused),true);
        return new FocusLease(tasks,-1,false);
    }
    interface FocusActivator { void activate() throws Exception; }
    FocusLease borrowTextFocus(String pkg,FocusActivator activate) throws Exception {
        verifyIsolation();
        Object tasks=service("activity_task","android.app.IActivityTaskManager");
        Object focused=call(tasks,"getFocusedRootTaskInfo",new Class<?>[0]);
        if(focused==null) throw new IOException("PHONE_INPUT_FOCUS_UNAVAILABLE");
        int currentDisplay=focused.getClass().getField("displayId").getInt(focused);
        int currentTask=focused.getClass().getField("taskId").getInt(focused);
        if(currentDisplay==displayId) return new FocusLease(tasks,currentTask,false);
        if(currentDisplay!=0 || currentTask<0) throw new IOException("PHONE_INPUT_FOCUS_UNAVAILABLE");
        Object rows=call(tasks,"getAllRootTaskInfos",new Class<?>[0]);
        int targetTask=-1;
        if(rows instanceof java.util.List) for(Object row:(java.util.List<?>)rows) {
            int d=row.getClass().getField("displayId").getInt(row);
            Object component=row.getClass().getField("topActivity").get(row);
            if(d==displayId && component instanceof ComponentName && ((ComponentName)component).getPackageName().equals(pkg)) {
                if(targetTask>=0) throw new IOException("PHONE_INPUT_TASK_AMBIGUOUS");
                targetTask=row.getClass().getField("taskId").getInt(row);
            }
        }
        if(targetTask<0) throw new IOException("PHONE_INPUT_TASK_UNAVAILABLE");
        FocusLease lease=new FocusLease(tasks,currentTask,true);
        try {
            // AOSP12 does not raise untrusted displays through task focus alone.
            // A cancelled display-bound pointer sequence triggers its normal focus callback.
            activate.activate();
            call(tasks,"setFocusedTask",new Class<?>[]{int.class},targetTask);
            long until=SystemClock.elapsedRealtime()+1200;
            while(true) {
                Object current=call(tasks,"getFocusedRootTaskInfo",new Class<?>[0]);
                if(current!=null && current.getClass().getField("displayId").getInt(current)==displayId) return lease;
                if(SystemClock.elapsedRealtime()>=until) throw new IOException("PHONE_INPUT_FOCUS_UNAVAILABLE");
                SystemClock.sleep(20);
            }
        } catch(Exception failure) { try { lease.close(); } catch(Exception restore) { failure.addSuppressed(restore); } throw failure; }
    }
    void bindInput(InputEvent input) throws Exception {
        verifyIsolation(); InputEvent.class.getMethod("setDisplayId",int.class).invoke(input,displayId);
        if ((Integer)InputEvent.class.getMethod("getDisplayId").invoke(input)!=displayId) throw new IOException("VIRTUAL_DISPLAY_INPUT_TARGET_UNVERIFIED");
    }
    private java.util.List<TaskInventory.Task> taskSnapshot() throws Exception {
        Object am=service("activity_task","android.app.IActivityTaskManager"); Object rows;
        try { rows=call(am,"getAllRootTaskInfos",new Class<?>[0]); }
        catch(NoSuchMethodException api30) { rows=call(am,"getAllStackInfos",new Class<?>[0]); }
        return TaskInventory.decode(rows);
    }
    void checkPackageTasks(String pkg) throws Exception {
        try { TaskInventory.onDisplay(taskSnapshot(),pkg,displayId); }
        catch(Exception failure) { throw launchFailure("TASK_INVENTORY",failure,null); }
    }
    void checkLaunchedTasks() throws Exception {
        if(!launchedPackage.isEmpty()) checkPackageTasks(launchedPackage);
    }
    private LaunchFailure launchFailure(String stage,Throwable failure,Integer result) throws Exception {
        Throwable cause=failure;
        for(int i=0;i<4 && cause.getCause()!=null && cause.getCause()!=cause;i++) cause=cause.getCause();
        String code=creationError(failure);
        if(code.equals("DEVICE_PREFLIGHT_FAILED")) code="VIRTUAL_DISPLAY_"+stage+"_FAILED";
        JSONObject diagnostic=new JSONObject().put("failure_stage",stage).put("exception_class",cause.getClass().getName())
            .put("android_api",Build.VERSION.SDK_INT).put("display_id",displayId).put("submitted",stage.equals("START_ACTIVITY") || stage.equals("VERIFY_LAUNCH") || stage.equals("TASK_RELOCATION"))
            .put("authority",capabilities(context).optJSONObject("authority")).put("start_result",result==null?JSONObject.NULL:result);
        return new LaunchFailure(code,failure,diagnostic);
    }
    private JSONArray describeTasks(List<TaskInventory.Task> tasks,String pkg) throws Exception {
        JSONArray out=new JSONArray();
        for(TaskInventory.Task t:tasks) if(t.belongsTo(pkg) && out.length()<32) out.put(new JSONObject()
            .put("task_id",t.id).put("root_task_id",t.rootId).put("display_id",t.display).put("user_id",t.user).put("component",t.name));
        return out;
    }
    private boolean moveForLaunch(LaunchPlan plan,JSONArray moves) throws Exception {
        List<TaskInventory.Task> snapshot=taskSnapshot();
        TaskInventory.Root root=TaskInventory.movable(snapshot,plan.pkg,displayId,plan.user);
        if(root==null) return false;
        boolean borrowed=false;
        for(TaskInventory.Task t:plan.before) if(t.rootId==root.id && t.display==0 && t.user==plan.user) borrowed=true;
        if(borrowed) borrowedMainRoots.put(root.id,new BorrowedTask(plan.pkg,plan.user));
        JSONObject move=new JSONObject().put("root_task_id",root.id).put("from_display_id",root.display).put("to_display_id",displayId)
            .put("return_on_close",borrowed).put("verified",false);
        moves.put(move);
        call(service("activity_task","android.app.IActivityTaskManager"),"moveRootTaskToDisplay",new Class<?>[]{int.class,int.class},root.id,displayId);
        long deadline=SystemClock.elapsedRealtime()+1500;
        while(true) {
            List<TaskInventory.Task> after=taskSnapshot(); boolean seen=false;
            for(TaskInventory.Task t:after) if(t.rootId==root.id) {
                if(!t.belongsTo(plan.pkg) || t.user!=plan.user) throw new IOException("VIRTUAL_DISPLAY_TASK_IDENTITY_CHANGED");
                if(t.display==displayId) seen=true;
            }
            if(seen) { move.put("verified",true); return true; }
            if(SystemClock.elapsedRealtime()>=deadline) throw new IOException("VIRTUAL_DISPLAY_TASK_MOVE_NOT_VERIFIED");
            SystemClock.sleep(50);
        }
    }
    private boolean appWindowReady(UiAutomation ui,String pkg) throws Exception {
        if(ui==null) return false;
        List<AccessibilityWindowInfo> ws;
        try { ws=windows(ui); }
        catch(IOException pending) { if("VIRTUAL_DISPLAY_WINDOW_NOT_READY".equals(pending.getMessage())) return false; throw pending; }
        try { for(AccessibilityWindowInfo w:ws) {
            if(w.getDisplayId()!=displayId) throw new IOException("VIRTUAL_DISPLAY_WINDOW_UNVERIFIED");
            AccessibilityNodeInfo node=w.getRoot();
            try { if(node!=null && node.getWindowId()==w.getId() && pkg.contentEquals(node.getPackageName()==null?"":node.getPackageName())) return true; }
            finally { if(node!=null) node.recycle(); }
        } } finally { for(AccessibilityWindowInfo w:ws) w.recycle(); }
        return false;
    }
    JSONObject launch(String pkg, String component,UiAutomation ui) throws Exception {
        LaunchPlan plan=launchPlan; launchPlan=null;
        if(plan==null || !plan.pkg.equals(pkg) || !plan.component.equals(component)) throw new IOException("VIRTUAL_DISPLAY_LAUNCH_PLAN_REQUIRED");
        JSONArray moves=new JSONArray();
        try { if(plan.policy.equals("reuse_or_move")) moveForLaunch(plan,moves); }
        catch(Exception failure) { throw launchFailure("TASK_RELOCATION",failure,null); }
        android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic(); options.setLaunchDisplayId(displayId);
        // Explicit fullscreen target avoids ambient desktop-mode placement choosing another area.
        try { call(options,"setLaunchWindowingMode",new Class<?>[]{int.class},1); }
        catch(NoSuchMethodException unavailable) { /* launchDisplayId is always explicit */ }
        int result;
        try { barrier(); result=plan.call.start(plan.intent,options.toBundle(),plan.user); }
        catch(Exception failure) { throw launchFailure("START_ACTIVITY",failure,null); }
        lastLaunchReceipt=new JSONObject().put("strategy",plan.call.strategy).put("start_result",result).put("requested_display_id",displayId)
            .put("user_id",plan.user).put("task_policy",plan.policy).put("task_moves",moves)
            .put("tasks_before",describeTasks(plan.before,pkg)).put("task_display_verified",false)
            .put("placement_stable_ms",750).put("verification_timeout_ms",8000);
        if(!ActivityStartCall.accepted(result)) throw launchFailure("START_ACTIVITY",new IOException("VIRTUAL_DISPLAY_SYSTEM_START_REJECTED"),result);
        try {
            long deadline=SystemClock.elapsedRealtime()+8000;
            boolean redirectedMove=false;
            String stableTasks=""; long stableSince=0;
            boolean windowSeen=false;
            while(true) {
                List<TaskInventory.Task> now=taskSnapshot();
                lastLaunchReceipt.put("tasks_after",describeTasks(now,pkg));
                boolean placed=false;
                if(plan.policy.equals("new_only")) placed=TaskInventory.onDisplay(now,pkg,displayId);
                else {
                    TaskInventory.Root foreign=TaskInventory.movable(now,pkg,displayId,plan.user);
                    if(foreign!=null && !redirectedMove) { moveForLaunch(plan,moves); redirectedMove=true; stableTasks=""; continue; }
                    placed=foreign==null && TaskInventory.present(now,pkg,displayId,plan.user);
                }
                // An entry activity can be placed correctly and then redirect elsewhere.
                // Observe a bounded stable interval instead of accepting its first task row.
                if(placed) {
                    String signature=describeTasks(now,pkg).toString();
                    if(!signature.equals(stableTasks)) { stableTasks=signature; stableSince=SystemClock.elapsedRealtime(); }
                    if(!windowSeen && appWindowReady(ui,pkg)) { windowSeen=true; stableSince=SystemClock.elapsedRealtime(); }
                    if(windowSeen && SystemClock.elapsedRealtime()-stableSince>=750) break;
                } else stableTasks="";
                if(SystemClock.elapsedRealtime()>=deadline) {
                    // A visual-only/temporarily hidden app must not gain a new UI-tree gate.
                    // Its task placement can still be verified; report the window separately.
                    if(placed && SystemClock.elapsedRealtime()-stableSince>=750) break;
                    throw new IOException("VIRTUAL_DISPLAY_LAUNCH_NOT_VERIFIED");
                }
                SystemClock.sleep(50); verifyIsolation();
            }
            verifyIsolation(); lastLaunchReceipt.put("task_display_verified",true).put("app_window_seen",windowSeen); launchedPackage=pkg;
        } catch(Exception failure) { throw launchFailure("VERIFY_LAUNCH",failure,result); }
        return lastLaunchReceipt;
    }
    void validateLaunch(String pkg,String component,String policy) throws Exception {
        launchPlan=null; lastLaunchReceipt=null;
        String stage="DISPLAY_GUARD";
        try {
            verifyIsolation(); VirtualDisplayPolicy.packageName(pkg,false);
            if(!policy.equals("reuse_or_move") && !policy.equals("new_only")) throw new IOException("VIRTUAL_DISPLAY_TASK_POLICY_INVALID");
            stage="RESOLVE_ACTIVITY";
            ComponentName cn=ComponentName.unflattenFromString(component);
            if(cn==null || !cn.getPackageName().equals(pkg)) throw new IOException("PHONE_APP_COMPONENT_INVALID");
            android.content.pm.ActivityInfo info=context.getPackageManager().getActivityInfo(cn,0);
            if(!info.exported || !info.enabled || !info.applicationInfo.enabled) throw new IOException("VIRTUAL_DISPLAY_APP_ACTIVITY_INCOMPATIBLE");
            if(info.permission!=null && !info.permission.isEmpty() && context.checkPermission(info.permission,android.os.Process.myPid(),2000)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new IOException("VIRTUAL_DISPLAY_ACTIVITY_PERMISSION_DENIED");
            // launchMode/resizeMode are not authority checks. Let Android decide placement.
            Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | (policy.equals("new_only")?Intent.FLAG_ACTIVITY_MULTIPLE_TASK:0));
            stage="RESOLVE_USER";
            int user=(Integer)android.app.ActivityManager.class.getMethod("getCurrentUser").invoke(null);
            if(user<0) throw new IOException("VIRTUAL_DISPLAY_USER_UNVERIFIED");
            stage="TASK_INVENTORY";
            List<TaskInventory.Task> before=taskSnapshot();
            if(policy.equals("new_only")) TaskInventory.onDisplay(before,pkg,displayId);
            else if(TaskInventory.movable(before,pkg,displayId,user)!=null)
                service("activity_task","android.app.IActivityTaskManager").getClass().getMethod("moveRootTaskToDisplay",int.class,int.class);
            stage="SYSTEM_LAUNCH_POLICY";
            Object allowed=call(service("activity_task","android.app.IActivityTaskManager"),"isActivityStartAllowedOnDisplay",
                new Class<?>[]{int.class,Intent.class,String.class,int.class},displayId,intent,null,user);
            if(!Boolean.TRUE.equals(allowed)) throw new IOException("VIRTUAL_DISPLAY_SYSTEM_LAUNCH_DENIED");
            stage="RESOLVE_START_API";
            ActivityStartCall start=ActivityStartCall.resolve(service("activity","android.app.IActivityManager"),Class.forName("android.app.IApplicationThread"),
                Intent.class,IBinder.class,Class.forName("android.app.ProfilerInfo"),android.os.Bundle.class);
            launchPlan=new LaunchPlan(pkg,component,user,intent,start,policy,before);
        } catch(Exception failure) {
            LaunchFailure error=launchFailure(stage,failure,null);
            error.diagnostic.put("requested_package",pkg).put("requested_component",component).put("task_policy",policy);
            try { error.diagnostic.put("tasks",describeTasks(taskSnapshot(),pkg)); } catch(Exception unavailable) { }
            throw error;
        }
    }
    JSONObject capture(UiAutomation ui, ByteArrayOutputStream png, boolean diagnostic) throws Exception {
        JSONObject before=metadata(ui,diagnostic); Bitmap bitmap=null;
        long until=SystemClock.elapsedRealtime()+1500; long sequence=0,timestamp=0;
        try {
            synchronized(frameLock) {
                while((latest==null || frameSequence<minimumFrameSequence) && !closed && SystemClock.elapsedRealtime()<until) frameLock.wait(50);
                if(latest==null || frameSequence<minimumFrameSequence || closed) throw new IOException("VIRTUAL_DISPLAY_FRAME_NOT_READY");
                int width=latest.getWidth(),height=latest.getHeight(); VirtualDisplayPolicy.geometry(width,height,0);
                Image.Plane plane=latest.getPlanes()[0]; int pixel=plane.getPixelStride(),stride=plane.getRowStride();
                if(pixel!=4 || stride<width*4) throw new IOException("VIRTUAL_DISPLAY_FRAME_FORMAT_UNSUPPORTED");
                ByteBuffer data=plane.getBuffer().duplicate(); int[] pixels=new int[width*height];
                boolean nonBlack=false;
                for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                    int p=y*stride+x*pixel;
                    if(p+3>=data.limit()) throw new IOException("VIRTUAL_DISPLAY_FRAME_BOUNDS");
                    pixels[y*width+x]=((data.get(p+3)&255)<<24)|((data.get(p)&255)<<16)|((data.get(p+1)&255)<<8)|(data.get(p+2)&255);
                    if((pixels[y*width+x]&0x00ffffff)!=0) nonBlack=true;
                }
                if(!nonBlack) throw new IOException("VIRTUAL_DISPLAY_BLACK_OR_SECURE_FRAME");
                bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888); sequence=frameSequence; timestamp=latest.getTimestamp();
            }
            JSONObject after=metadata(ui,diagnostic);
            if(!before.getString("window").equals(after.getString("window")) || !before.getString("package").equals(after.getString("package"))) throw new IOException("PHONE_WINDOW_CHANGED");
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,png) || png.size()>Main.IMAGE_LIMIT) throw new IOException("IMAGE_LIMIT");
            return after.put("frame_sequence",sequence).put("frame_timestamp_ns",timestamp).put("bytes",png.size());
        } finally { if(bitmap!=null) bitmap.recycle(); }
    }
    @Override public void close() {
        close("DISPLAY_CLOSED","CLEANUP");
    }
    JSONArray taskReturns() { return taskReturns; }
    private void returnBorrowedTasks() {
        for(java.util.Map.Entry<Integer,BorrowedTask> entry:borrowedMainRoots.entrySet()) {
            JSONObject returned=new JSONObject();
            try {
                int root=entry.getKey(); BorrowedTask original=entry.getValue();
                returned.put("root_task_id",root).put("to_display_id",0);
                List<TaskInventory.Task> now=taskSnapshot(); boolean ours=false,other=false;
                for(TaskInventory.Task t:now) if(t.rootId==root) {
                    if(!t.belongsTo(original.pkg) || t.user!=original.user) throw new IOException("VIRTUAL_DISPLAY_TASK_IDENTITY_CHANGED");
                    if(t.display==displayId) ours=true; else other=true;
                }
                if(!ours || other) returned.put("status","ALREADY_MOVED_OR_GONE");
                else {
                    call(service("activity_task","android.app.IActivityTaskManager"),"moveRootTaskToDisplay",new Class<?>[]{int.class,int.class},root,0);
                    boolean verified=false;
                    for(TaskInventory.Task t:taskSnapshot()) if(t.rootId==root && t.display==0 && t.user==original.user && t.belongsTo(original.pkg)) verified=true;
                    returned.put("status",verified?"RETURNED":"RETURN_NOT_VERIFIED");
                }
            } catch(Exception failure) {
                try { returned.put("status","RETURN_FAILED").put("error",creationError(failure)); } catch(Exception ignored) { }
            }
            taskReturns.put(returned);
        }
        borrowedMainRoots.clear();
    }
    void close(String code,String stage) {
        synchronized(frameLock) {
            if(closed) return;
            closeReason=code; closeStage=stage; closedAt=System.currentTimeMillis(); closed=true;
            if(latest!=null) latest.close(); latest=null; frameLock.notifyAll();
        }
        // Existing same-app tasks were borrowed, not created by this session. Return those first.
        // Newly launched tasks still obey DESTROY_CONTENT_ON_REMOVAL. Process death cannot guarantee return.
        returnBorrowedTasks();
        try { if(display!=null) display.release(); } catch(RuntimeException lostBinder) { /* process death is a second release boundary */ }
        display=null;
        try { if(reader!=null) reader.close(); } catch(RuntimeException stopped) { }
        reader=null;
        try { if(frames!=null) frames.quitSafely(); } catch(RuntimeException stopped) { }
        frames=null;
    }
}
