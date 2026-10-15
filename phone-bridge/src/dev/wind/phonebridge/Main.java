package dev.wind.phonebridge;

import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import dev.wind.agent.phone.VisualPatchDigest;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.content.ClipData;
import android.content.Context;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** First party shell-uid process. No HTTP, downloaded dependencies or arbitrary shell RPC. */
public final class Main {
    static final int VERSION = 2, JSON_LIMIT = 1048576, IMAGE_LIMIT = 16777216;
    static volatile String startupStage="CONFIG";
    final String session, secret;
    final boolean allowUi, allowScreen;
    final boolean virtualOnly;
    final boolean stdioControl;
    final Set<String> allowedPackages = new HashSet<>();
    volatile VirtualScreen virtual;
    final LocalServerSocket control, images, heartbeats;
    final BridgeLease lease = new BridgeLease(SystemClock::elapsedRealtime);
    final BridgeExitJournal exitJournal;
    volatile String lastMethod="START";
    final DisplayEventClock virtualClock=new DisplayEventClock(), mainClock=new DisplayEventClock();
    final ChatEventMailbox chatEvents=new ChatEventMailbox();
    final ThreadLocal<Boolean> mainRequest=new ThreadLocal<>();
    boolean virtualTarget() { return virtualOnly && !Boolean.TRUE.equals(mainRequest.get()); }
    DisplayEventClock clock() { return virtualTarget()?virtualClock:mainClock; }
    AtomicLong events() { return clock().events; }
    AtomicLong structural() { return clock().structural; }
    void selectTarget(JSONObject a) throws IOException {
        String screen=a==null?"":a.optString("screen");
        if(!screen.isEmpty() && !screen.equals("main") && !screen.equals("virtual")) throw new IOException("PHONE_SCREEN_INVALID");
        if(screen.equals("main") && a.has("display_session_id")) throw new IOException("PHONE_SCREEN_SCOPE_CONFLICT");
        mainRequest.set(screen.equals("main"));
    }
    final LinkedHashMap<String, JSONObject> receipts = new LinkedHashMap<>();
    final LinkedHashMap<String, JSONObject> prepared = new LinkedHashMap<>();
    final Map<String, JSONObject> observations = new LinkedHashMap<>();
    final ReceiptLedger ledger;
    java.nio.channels.FileLock displayLock;
    RandomAccessFile displayLockFile;
    UiAutomation ui;
    String uiError="";
    volatile String uiEventFailure="";
    final AccessibilityEventScope eventScope=new AccessibilityEventScope(AccessibilityEvent.class);
    volatile String eventPackage = "";
    volatile int eventWindow = -1;
    HandlerThread thread;
    Context systemContext;
    String cachedPackage = "";
    int cachedWindow = -1;
    JSONObject lastFullObservation;
    long lastFullRead;
    int nodeBudget=512, depthBudget=40;
    boolean contentTruncated;
    boolean budgetExhausted;
    int encodedBytes;
    Main(JSONObject config,Context runtimeContext) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("SHELL_UID_REQUIRED");
        session = config.getString("session"); secret = config.getString("secret");
        allowUi=config.optBoolean("allow_ui",false); allowScreen=config.optBoolean("allow_screen",false);
        virtualOnly=config.optBoolean("virtual_only",false);
        stdioControl=config.optBoolean("stdio_control",false);
        JSONArray packages=config.optJSONArray("allowed_packages");
        if(packages!=null) for(int i=0;i<packages.length();i++) allowedPackages.add(packages.getString(i));
        if (!session.matches("[a-f0-9]{32}") || !secret.matches("[a-f0-9]{64}")) throw new SecurityException("CONFIG_INVALID");
        startupStage="JOURNAL";
        exitJournal=new BridgeExitJournal(new File("/data/local/tmp/vega-phone"),session,android.os.Process.myPid()); exitJournal.started();
        ledger=ReceiptLedger.open(new File("/data/local/tmp/vega-phone/receipt-"+session));
        if(virtualOnly) {
            startupStage="DEVICE_LOCK";
            File lockPath=new File("/data/local/tmp/vega-phone/virtual-display.lock");
            lockPath.createNewFile();
            android.system.StructStat stat=android.system.Os.lstat(lockPath.getAbsolutePath());
            if(stat.st_uid!=2000 || !android.system.OsConstants.S_ISREG(stat.st_mode)) throw new IOException("VIRTUAL_DISPLAY_LOCK_INVALID");
            displayLockFile=new RandomAccessFile(lockPath,"rw"); displayLock=displayLockFile.getChannel().tryLock();
            if(displayLock==null) throw new IOException("VIRTUAL_DISPLAY_DEVICE_BUSY");
        }
        startupStage="LOCAL_SOCKETS";
        control = stdioControl?null:new LocalServerSocket("vega_phone_" + session);
        images = new LocalServerSocket("vega_phone_" + session + "_image");
        heartbeats = new LocalServerSocket("vega_phone_" + session + "_lease");
        systemContext=runtimeContext;
        // API 23 cannot promise coexistence: compatibility shell backend remains available.
        if (Build.VERSION.SDK_INT < 24) { uiError="UI_AUTOMATION_COEXISTENCE_REQUIRES_API_24"; return; }
        try {
            startupStage="UI_AUTOMATION";
            thread = new HandlerThread("vega-phone-ui"); thread.start();
            Class<?> connection = Class.forName("android.app.UiAutomationConnection");
            Class<?> contract = Class.forName("android.app.IUiAutomationConnection");
            Constructor<?> ctor = UiAutomation.class.getDeclaredConstructor(android.os.Looper.class, contract);
            ctor.setAccessible(true);
            ui = (UiAutomation) ctor.newInstance(thread.getLooper(), connection.getDeclaredConstructor().newInstance());
            UiAutomation.class.getMethod("connect", int.class).invoke(ui, 1); // DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            ui.setServiceInfo(info);
            ui.setOnAccessibilityEventListener(e -> {
                if(DisplayEventClock.focusOnly(e.getEventType(),e.getWindowChanges())) return;
                if(virtualOnly) {
                    VirtualScreen target=virtual;
                    if(target==null || target.isClosed()) return;
                    try {
                        if(eventScope.belongsTo(e,e.getWindowId(),target.displayId(),window -> target.displayForWindow(ui,window))) {
                            virtualClock.record(e.getEventType(),e.getWindowChanges(),SystemClock.elapsedRealtime());
                            chatEvents.publish(virtualClock.events.get(),target.displayId(),e.getWindowId(),e.getEventType(),e.getPackageName()==null?"":e.getPackageName().toString());
                            if(e.getPackageName()!=null && e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                                target.associateEvent(e.getWindowId(),e.getPackageName().toString());
                        } else if(eventScope.belongsTo(e,e.getWindowId(),0,window -> target.displayForWindow(ui,window))) {
                            mainClock.record(e.getEventType(),e.getWindowChanges(),SystemClock.elapsedRealtime());
                        }
                    } catch(Exception | LinkageError failure) {
                        uiEventFailure=failure.getClass().getName();
                        // Invalidate outstanding visual/recipient proofs conservatively when routing is uncertain.
                        virtualClock.record(AccessibilityEvent.TYPE_WINDOWS_CHANGED,0,SystemClock.elapsedRealtime());
                    }
                    return;
                }
                if(e.getEventType()==AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                    e.getPackageName()!=null && e.getPackageName().toString().equals("dev.wind.agent")) return;
                mainClock.record(e.getEventType(),e.getWindowChanges(),SystemClock.elapsedRealtime());
                if(e.getPackageName()!=null && e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    eventPackage=e.getPackageName().toString(); eventWindow=e.getWindowId();
                }
            });
        } catch (Throwable unsupported) {
            Throwable cause=unsupported.getCause()==null?unsupported:unsupported.getCause();
            uiError="UI_AUTOMATION_CONNECT_FAILED:"+cause.getClass().getSimpleName();
            ui = null; if (thread != null) thread.quitSafely();
        }
    }
    public static void main(String[] ignored) throws Exception {
        if(android.os.Process.myUid()!=2000) throw new SecurityException("SHELL_UID_REQUIRED");
        // Secret arrives via private shell stdin, never command line or output.
        byte[] bytes = new byte[16384]; int size = 0, b;
        while ((b = System.in.read()) != -1 && b != '\n') { if (size == bytes.length) throw new IOException("CONFIG_LIMIT"); bytes[size++] = (byte)b; }
        JSONObject config=new JSONObject(new String(bytes, 0, size, "UTF-8"));
        Arrays.fill(bytes, (byte)0);
        Main server;
        try { server=new Main(config,BridgeRuntime.initialize()); }
        catch(Throwable startup) {
            if(android.os.Process.myUid()==2000) try {
                new BridgeExitJournal(new File("/data/local/tmp/vega-phone"),config.optString("session"),android.os.Process.myPid())
                    .crashed("STARTUP_BRIDGE_FAILURE","START",Thread.currentThread(),startup);
            } catch(Exception unavailable) { }
            if(config.optBoolean("stdio_control")) {
                Throwable cause=startup;
                for(int i=0;i<4 && cause.getCause()!=null && cause.getCause()!=cause;i++) cause=cause.getCause();
                write(new DataOutputStream(System.out),new JSONObject().put("status","START_FAILED").put("version",VERSION)
                    .put("session",config.optString("session")).put("error","PHONE_BRIDGE_STARTUP_FAILED")
                    .put("diagnostic",new JSONObject().put("failure_stage",startupStage).put("exception_class",cause.getClass().getName())
                        .put("pid",android.os.Process.myPid()).put("android_api",Build.VERSION.SDK_INT)).toString().getBytes("UTF-8"));
            }
            if(startup instanceof Exception) throw (Exception)startup;
            throw new RuntimeException("PHONE_BRIDGE_START_FAILED",startup);
        }
        Thread.setDefaultUncaughtExceptionHandler((thread,failure) -> {
            try { server.exitJournal.crashed("UNCAUGHT_BRIDGE_FAILURE",server.lastMethod,thread,failure); }
            finally { System.exit(1); }
        });
        new Thread(() -> server.accept(server.images, true, false), "vega-phone-images").start();
        new Thread(() -> server.accept(server.heartbeats, false, true), "vega-phone-heartbeats").start();
        new Thread(() -> {
            for (;;) {
                SystemClock.sleep(250);
                if (server.lease.expired()) {
                    server.exitJournal.stopped("LEASE_EXPIRED",server.lastMethod);
                    // A stuck UI/Binder call must not hold the lease-expiry thread behind dispatch's monitor.
                    Thread cleanup=new Thread(() -> server.closeVirtual("LEASE_EXPIRED","LEASE"),"vega-phone-expired-cleanup"); cleanup.setDaemon(true); cleanup.start();
                    SystemClock.sleep(250);
                    System.exit(0);
                }
            }
        }, "vega-phone-lease").start();
        Thread owner=new Thread(() -> {
            if(server.stdioControl) server.serve(null,System.in,System.out,false,false);
            else server.accept(server.control,false,false);
        },"vega-phone-owner"); owner.start();
        android.os.Looper.loop();
    }
    void accept(LocalServerSocket server, boolean image, boolean heartbeat) {
        try {
            for (;;) {
                LocalSocket socket = server.accept();
                // ADB's local socket peer must be adbd/root or shell, never an arbitrary app.
                int uid = socket.getPeerCredentials().getUid();
                if (uid != 0 && uid != 2000) { socket.close(); continue; }
                new Thread(() -> serve(socket, image, heartbeat), "vega-phone-client").start();
            }
        } catch (IOException closed) { }
    }
    static byte[] read(DataInputStream in, int limit) throws Exception {
        int n = in.readInt(); if (n <= 0 || n > limit) throw new IOException("FRAME_LIMIT");
        byte[] bytes = new byte[n]; in.readFully(bytes); return bytes;
    }
    static void write(DataOutputStream out, byte[] bytes) throws Exception { out.writeInt(bytes.length); out.write(bytes); out.flush(); }
    String hmac(String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        return hex(mac.doFinal(message.getBytes("UTF-8")));
    }
    static String hex(byte[] bytes) { StringBuilder b = new StringBuilder(); for (byte v: bytes) b.append(String.format(Locale.ROOT, "%02x", v & 255)); return b.toString(); }
    void serve(LocalSocket socket, boolean image, boolean heartbeat) {
        try { serve(socket,socket.getInputStream(),socket.getOutputStream(),image,heartbeat); }
        catch(IOException unavailable) { try { socket.close(); } catch(IOException ignored) { } }
    }
    void serve(LocalSocket socket,InputStream source,OutputStream sink,boolean image,boolean heartbeat) {
        boolean authenticated = false;
        String exitCode="CONTROL_CHANNEL_CLOSED";
        try {
            if(socket!=null) socket.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(source);
            DataOutputStream out = new DataOutputStream(sink);
            String challenge = UUID.randomUUID().toString();
            write(out, new JSONObject().put("version", VERSION).put("session", session).put("challenge", challenge).toString().getBytes("UTF-8"));
            JSONObject auth = new JSONObject(new String(read(in, JSON_LIMIT), "UTF-8"));
            String proof = hmac(session + ":" + challenge + ":" + (image ? "image" : heartbeat ? "lease" : "control"));
            if (!MessageDigest.isEqual(proof.getBytes("UTF-8"), auth.getString("proof").getBytes("UTF-8"))) throw new SecurityException("AUTH_FAILED");
            if(!image && !heartbeat) {
                if(!lease.acquireOwner()) throw new IOException("CONTROL_OWNER_ALREADY_CONNECTED");
                authenticated=true; // A failed authentication reply still releases this owner.
            }
            write(out, new JSONObject().put("status", "AUTHENTICATED").put("version", VERSION).toString().getBytes("UTF-8"));
            authenticated = true;
            if(!image && !heartbeat && socket!=null) socket.setSoTimeout(0); // Independent lease watchdog protects the idle owner.
            for (;;) {
                JSONObject request = new JSONObject(new String(read(in, JSON_LIMIT), "UTF-8"));
                if (request.getInt("version") != VERSION || !session.equals(request.getString("session"))) throw new SecurityException("SESSION_CHANGED");
                String method=request.getString("method");
                if(heartbeat && "events_wait".equals(method)) {
                    if(!virtualOnly || !lease.ownerConnected() || lease.expired()) throw new IOException("CHAT_EVENT_SCOPE_REQUIRED");
                    VirtualScreen target=virtual;
                    if(target==null || target.isClosed()) throw new IOException("VIRTUAL_DISPLAY_SESSION_LOST");
                    long after=request.getJSONObject("args").getLong("after_epoch");
                    ChatEventMailbox.Event event=chatEvents.await(after,5000);
                    if(!lease.ownerConnected() || lease.expired() || target!=virtual || target.isClosed()) throw new IOException("CHAT_EVENT_SCOPE_LOST");
                    write(out,new JSONObject().put("status","COMPLETED").put("version",VERSION).put("request_id",request.getString("request_id"))
                        .put("display_session_id",target.status().getString("display_session_id")).put("display_id",target.displayId())
                        .put("event_epoch",event.epoch).put("window_id",event.window).put("event_type",event.type).put("package",event.pkg).toString().getBytes("UTF-8"));
                    continue; // Waiting for events must never renew the control owner's lease.
                }
                lease.renew(heartbeat,method);
                if(heartbeat) {
                    // No dispatch monitor, window tree, frame capture, or effects on this channel.
                    JSONObject reply=new JSONObject().put("status","COMPLETED");
                    if(virtualOnly) try {
                        if(virtual!=null && virtual.isClosed()) throw new IOException(virtual.closeReason());
                        if(virtual!=null) virtual.verifyLiveness();
                        if(virtual!=null) reply.put("display",virtual.status());
                    } catch(Exception invalid) {
                        reply.put("status","NOT_SUBMITTED").put("error",safeError(invalid));
                        if(fatalVirtual(invalid)) closeVirtual(safeError(invalid),"HEARTBEAT");
                    }
                    write(out,reply.put("request_id",request.getString("request_id"))
                        .put("version",VERSION).put("pid",android.os.Process.myPid()).put("heartbeat","INDEPENDENT")
                        .put("event_epoch",events().get()).put("input_verification","NOT_PROBED")
                        .toString().getBytes("UTF-8"));
                    continue;
                }
                if(!image) lastMethod=method;
                if (image) {
                    if(!allowScreen) throw new SecurityException("SCREEN_SCOPE_REQUIRED");
                    if (!request.getString("method").equals("capture")) throw new SecurityException("IMAGE_METHOD_INVALID");
                    JSONObject result;
                    byte[] png;
                    synchronized (this) {
                        try {
                            selectTarget(request.optJSONObject("args"));
                            guard();
                            if(virtualTarget()) {
                                requireVirtual(request.optJSONObject("args"));
                                long before=structural().get();
                                ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                                result=virtual.capture(ui,encoded,false); png=encoded.toByteArray();
                                if(before!=structural().get()) throw new IOException("PHONE_WINDOW_CHANGED");
                                result.put("structural_epoch",before).put("event_epoch",events().get());
                            } else { png=capture(); result=metadata().put("status","COMPLETED").put("bytes",png.length); }
                        } catch(Exception failure) {
                            result=new JSONObject().put("status","NOT_SUBMITTED").put("error",safeError(failure)); png=null;
                            if(virtualTarget() && virtual!=null) result.put("diagnostic",virtual.windowDiagnostic());
                            if(virtualTarget() && fatalVirtual(failure)) closeVirtual(safeError(failure),"CAPTURE");
                        }
                        mainRequest.remove();
                        result.put("request_id",request.getString("request_id")).put("version",VERSION);
                    }
                    if(png==null) { write(out,result.toString().getBytes("UTF-8")); continue; }
                    write(out, result.toString().getBytes("UTF-8")); write(out, png);
                } else {
                    JSONObject result;
                    synchronized (this) { result = dispatch(request); }
                    byte[] encoded = result.toString().getBytes("UTF-8");
                    if (encoded.length > JSON_LIMIT) throw new IOException("RESPONSE_LIMIT");
                    write(out, encoded);
                }
            }
        } catch (Throwable failure) {
            exitCode=failure instanceof java.net.SocketTimeoutException ? "CONTROL_READ_TIMEOUT" :
                failure instanceof EOFException ? "CONTROL_EOF" : safeError(failure);
            if(exitCode.equals("DEVICE_PREFLIGHT_FAILED")) exitCode="CONTROL_CHANNEL_FAILURE";
        }
        finally {
            try { if(socket!=null) socket.close(); } catch (IOException ignored) {}
            if (!image && !heartbeat && authenticated) {
                exitJournal.stopped(exitCode,lastMethod); lease.closeOwner();
                closeVirtual(exitCode,"CONTROL");
                try { if (ui != null) UiAutomation.class.getMethod("disconnect").invoke(ui); if(control!=null) control.close(); images.close(); heartbeats.close(); } catch (Exception ignored) {}
                System.exit(0);
            }
        }
    }
    JSONObject dispatch(JSONObject r) throws Exception {
        try { return dispatchTarget(r); } finally { mainRequest.remove(); }
    }
    JSONObject dispatchTarget(JSONObject r) throws Exception {
        long started=SystemClock.elapsedRealtime();
        String method = r.getString("method"), id = r.getString("request_id");
        JSONObject a = r.optJSONObject("args"); if (a == null) a = new JSONObject();
        JSONObject result;
        selectTarget(a);
        try {
            if(virtualTarget() && virtual!=null && (method.equals("ping") || method.equals("status")) && !virtual.status().getString("status").equals("CLOSED")) {
                guard(); virtual.require(virtual.id);
            }
            if (method.equals("status") || method.equals("ping")) result = new JSONObject().put("status", "COMPLETED").put("ui", ui != null)
                .put("input", ui != null).put("screen", ui != null).put("clipboard", clipboardAvailable()).put("coexistence", ui != null && Build.VERSION.SDK_INT >= 24).put("ui_error",uiError)
                .put("input_verification","NOT_PROBED").put("screen_verification","ON_DEMAND").put("clipboard_verification","SIGNATURE_ONLY").put("event_epoch",events().get())
                .put("pid",android.os.Process.myPid()).put("heartbeat_channel","INDEPENDENT").put("chat_event_stream",true).put("chat_event_wait_ms",5000)
                .put("control_transport",stdioControl?"MANAGED_SHELL_V2":"LOCAL_ABSTRACT")
                .put("event_display_routing",eventScope.hasDisplayMethod()?"OPTIONAL_EVENT_DISPLAY_ID":"WINDOW_ID_LOOKUP")
                .put("ui_event_failure",uiEventFailure)
                .put("virtual_display",VirtualScreen.capabilities(systemContext)).put("display",virtual==null?JSONObject.NULL:virtual.status());
            else if(method.equals("display_create")) {
                if(!virtualTarget() || !allowUi || !allowScreen) throw new IOException("VIRTUAL_DISPLAY_SCOPE_REQUIRED");
                JSONObject caps=VirtualScreen.capabilities(systemContext);
                if(!caps.optBoolean("supported")) throw new IOException(caps.optString("error","VIRTUAL_DISPLAY_NOT_SUPPORTED"));
                if(virtual!=null) throw new IOException("VIRTUAL_DISPLAY_ALREADY_CREATED");
                // Publish only a fully initialized resource to the independent lease
                // and event threads. A heartbeat must not release a half-created display.
                guard(); VirtualScreen pending=new VirtualScreen(systemContext); result=pending.create(); virtual=pending;
           } else if(method.equals("display_close")) { requireVirtual(a); closeVirtual("DISPLAY_CLOSED","display_close"); result=new JSONObject().put("status","CLOSED").put("task_returns",virtual.taskReturns()); }
           else if (method.equals("observe")) result = observe(a);
            else if (method.equals("prepare")) {
                if(!allowUi) throw new SecurityException("INPUT_SCOPE_REQUIRED");
                if(ledger.state(id)!=null) return new JSONObject().put("status",ledger.state(id)).put("submitted",true).put("request_id",id).put("version",VERSION);
                if (prepared.size() >= 64) throw new IOException("PREPARE_LIMIT");
                validateAction(a); if(!a.optString("action").equals("launch")) checkObservation(a);
                prepared.put(id, new JSONObject(a.toString()));
                result = new JSONObject().put("status", "PREPARED");
            } else if (method.equals("receipt")) result = receipts.containsKey(id) ? receipts.get(id) : new JSONObject().put("status",ledger.state(id)==null?"NOT_FOUND":ledger.state(id)).put("submitted",ledger.state(id)!=null);
            else if (method.equals("commit")) {
                if(!allowUi) throw new SecurityException("INPUT_SCOPE_REQUIRED");
                if (receipts.containsKey(id)) return receipts.get(id);
                if(ledger.state(id)!=null) return new JSONObject().put("status",ledger.state(id)).put("submitted",true).put("request_id",id).put("version",VERSION);
                JSONObject action = prepared.remove(id);
                if (action == null) throw new IOException("PREPARE_REQUIRED");
                if(action!=null && !action.optString("screen").equals(a.optString("screen"))) throw new IOException("PHONE_SCREEN_SCOPE_CHANGED");
                guard(); validateAction(action); if(!action.optString("action").equals("launch")) checkObservation(action);
                // Durable metadata only, written BEFORE effects. Unknown receipt must never be replayed.
                result = new JSONObject().put("status", "UNKNOWN").put("submitted", true).put("request_id", id);
                if(!ledger.begin(id)) throw new IOException("REQUEST_ALREADY_ATTEMPTED"); receipts.put(id, result);
                try { if(virtualTarget()) virtual.barrier(); action(action); result = new JSONObject().put("status", "DISPATCHED").put("submitted", true);
                    if(action.has("input_receipt")) result.put("input",action.getJSONObject("input_receipt")); }
                catch (Throwable effectError) {
                    result.put("error", "DEVICE_ACTION_COMPLETION_UNKNOWN").put("effect_error",safeError(effectError));
                    if(effectError instanceof VirtualScreen.LaunchFailure) result.put("error",safeError(effectError)).put("diagnostic",((VirtualScreen.LaunchFailure)effectError).diagnostic);
                    boolean knownLaunchFailure=effectError instanceof VirtualScreen.LaunchFailure && virtual.lastLaunchReceipt!=null
                        && ((VirtualScreen.LaunchFailure)effectError).diagnostic.optString("failure_stage").equals("VERIFY_LAUNCH");
                    if(knownLaunchFailure) result.put("status","LAUNCH_NOT_VERIFIED").put("retry_safe",false);
                    else if(virtualTarget()) closeVirtual(safeError(effectError).equals("DEVICE_PREFLIGHT_FAILED")?"DEVICE_ACTION_COMPLETION_UNKNOWN":safeError(effectError),"commit");
                }
                if(virtualTarget() && action.optString("action").equals("launch") && virtual.lastLaunchReceipt!=null) result.put("launch",virtual.lastLaunchReceipt);
                if(result.getString("status").equals("DISPATCHED")) {
                    try { ledger.finish(id); }
                    catch(IOException lostReceipt) { result.put("status","UNKNOWN").put("error","RECEIPT_PERSISTENCE_FAILED_NO_REPLAY"); }
                }
                result.put("request_id", id); receipts.put(id, result);
                while (receipts.size() > 100) receipts.remove(receipts.keySet().iterator().next());
            } else throw new IOException("METHOD_INVALID");
        } catch (Exception failure) {
            result = new JSONObject().put("status", "NOT_SUBMITTED").put("submitted", false).put("error",safeError(failure));
            if(failure instanceof VirtualScreen.CreationFailure) result.put("diagnostic",((VirtualScreen.CreationFailure)failure).diagnostic);
            if(failure instanceof VirtualScreen.LaunchFailure) result.put("diagnostic",((VirtualScreen.LaunchFailure)failure).diagnostic);
            if(virtualTarget() && virtual!=null && method.equals("observe")) result.put("diagnostic",virtual.windowDiagnostic());
            if(virtualTarget() && fatalVirtual(failure)) closeVirtual(safeError(failure),method);
        }
        mainRequest.remove();
        return result.put("request_id", id).put("version", VERSION).put("device_dispatch_ms",SystemClock.elapsedRealtime()-started);
    }
    static String safeError(Throwable e) { return e.getMessage()!=null && e.getMessage().matches("[A-Z][A-Z0-9_]{2,100}") ? e.getMessage() : "DEVICE_PREFLIGHT_FAILED"; }
    static boolean fatalVirtual(Throwable e) {
        String error=safeError(e);
        return WindowRouting.requiresRelease(error);
    }
    void closeVirtual(String code,String method) {
        VirtualScreen target=virtual;
        if(target!=null && !target.isClosed()) {
            exitJournal.displayClosed(code,method); target.close(code,method);
        }
    }
    void requireVirtual(JSONObject a) throws Exception {
        if(!virtualOnly || virtual==null || a==null) throw new IOException("VIRTUAL_DISPLAY_SESSION_REQUIRED");
        virtual.require(a.optString("display_session_id",""));
    }
    void requireAllowed(String pkg) throws Exception {
        if(!allowedPackages.contains(pkg)) throw new IOException("VIRTUAL_DISPLAY_PACKAGE_NOT_ALLOWED");
    }
    AccessibilityNodeInfo root() throws Exception { return virtualTarget() ? virtual.root(ui) : (virtualOnly?mainRoot():ui.getRootInActiveWindow()); }
    AccessibilityNodeInfo mainRoot() throws Exception {
        VirtualScreen.refreshWindowCache();
        android.util.SparseArray<List<AccessibilityWindowInfo>> all=ui.getWindowsOnAllDisplays();
        AccessibilityWindowInfo selected=null;
        try {
            List<AccessibilityWindowInfo> ws=all.get(0);
            if(ws!=null) for(AccessibilityWindowInfo w:ws) {
                if(w.getDisplayId()!=0) throw new IOException("PHONE_MAIN_DISPLAY_UNVERIFIED");
                if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if(selected==null || w.getLayer()>selected.getLayer()) selected=w;
            }
            if(selected==null) throw new IOException("PHONE_UI_UNAVAILABLE");
            AccessibilityNodeInfo n=selected.getRoot();
            if(n==null) throw new IOException("PHONE_UI_UNAVAILABLE");
            if(n.getWindowId()!=selected.getId()) { n.recycle(); throw new IOException("PHONE_WINDOW_CHANGED"); }
            return n;
        } finally { for(int i=0;i<all.size();i++) for(AccessibilityWindowInfo w:all.valueAt(i)) w.recycle(); }
    }
    void guard() throws Exception {
        if (ui == null) throw new IOException("UI_AUTOMATION_UNAVAILABLE");
        if (virtualOnly && !uiEventFailure.isEmpty()) {
            if(!virtualTarget() || virtual==null) throw new IOException("VIRTUAL_DISPLAY_EVENT_ROUTING_FAILED");
            // API31 window transitions can break one event lookup; a fresh scoped window/root identity is authoritative.
            try { virtual.metadata(ui,false); uiEventFailure=""; }
            catch(Exception notReady) { throw new IOException("VIRTUAL_DISPLAY_WINDOW_NOT_READY",notReady); }
        }
        // Host power/keyguard state is not the lifetime of a shell-owned virtual display.
        // Display-zero operations below still obey the ordinary lock-screen guard.
        if(virtualTarget()) return;
        if (systemContext != null) {
            if (!((PowerManager)systemContext.getSystemService(Context.POWER_SERVICE)).isInteractive()) throw new IOException("PHONE_SCREEN_NOT_INTERACTIVE");
            if (((KeyguardManager)systemContext.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked()) throw new IOException("PHONE_LOCKED");
            return;
        }
        String power = fixedOutput("dumpsys", "power");
        if (!(power.contains("mWakefulness=Awake") || power.contains("mInteractive=true"))) throw new IOException("PHONE_SCREEN_NOT_INTERACTIVE");
        String policy = fixedOutput("dumpsys", "window", "policy");
        if (policy.contains("mShowingLockscreen=true") || policy.contains("isStatusBarKeyguard=true") || policy.contains("showing=true")) throw new IOException("PHONE_LOCKED");
    }
    static String fixedOutput(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        try { while ((n = p.getInputStream().read(b)) != -1) { if (out.size() + n > JSON_LIMIT) throw new IOException("METADATA_LIMIT"); out.write(b, 0, n); } }
        finally { p.destroy(); }
        return out.toString("UTF-8");
    }
    JSONObject metadata() throws Exception {
        if(virtualTarget()) {
            if(virtual==null) throw new IOException("VIRTUAL_DISPLAY_SESSION_REQUIRED");
            JSONObject meta=virtual.metadata(ui,false);
            virtual.checkLaunchedTasks();
            return meta.put("structural_epoch",structural().get()).put("event_epoch",events().get()).put("quiet_ms",SystemClock.elapsedRealtime()-clock().lastEvent);
        }
        if(virtualOnly) {
            AccessibilityNodeInfo n=mainRoot();
            try {
                String pkg=str(n.getPackageName());
                if(pkg.isEmpty()) throw new IOException("PHONE_FOREGROUND_UNVERIFIED");
                android.view.Display display=((android.hardware.display.DisplayManager)systemContext.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(0);
                DisplayMetrics dm=new DisplayMetrics(); display.getRealMetrics(dm);
                return new JSONObject().put("package",pkg).put("window","uia:"+n.getWindowId()).put("display_id",0)
                    .put("width",dm.widthPixels).put("height",dm.heightPixels).put("rotation",display.getRotation())
                    .put("structural_epoch",structural().get()).put("event_epoch",events().get())
                    .put("quiet_ms",SystemClock.elapsedRealtime()-clock().lastEvent).put("captured_at",System.currentTimeMillis());
            } finally { n.recycle(); }
        }
        // Window metadata is allowed in pure visual mode; no roots or tree nodes are read.
        if (systemContext != null && ui != null) {
            int active = -1;
            List<AccessibilityWindowInfo> windows = ui.getWindows();
            for (AccessibilityWindowInfo win: windows) {
                try { if (win.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && win.isActive()) active=win.getId(); }
                finally { win.recycle(); }
            }
            String pkg = eventWindow == active && !eventPackage.isEmpty() ? eventPackage : active == cachedWindow ? cachedPackage : "";
            if (active >= 0 && !pkg.isEmpty()) {
                if (pkg.equals("dev.wind.agent") || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")) throw new IOException("PHONE_PROTECTED_WINDOW");
                android.view.Display display = ((WindowManager)systemContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
                DisplayMetrics dm = new DisplayMetrics(); display.getRealMetrics(dm);
                if (dm.widthPixels <= 0 || dm.heightPixels <= 0 || (long)dm.widthPixels*dm.heightPixels>33554432) throw new IOException("DISPLAY_INVALID");
                return new JSONObject().put("package",pkg).put("window","uia:"+active).put("width",dm.widthPixels).put("height",dm.heightPixels).put("rotation",display.getRotation())
                    .put("display_id",display.getDisplayId()).put("structural_epoch",structural().get()).put("event_epoch",events().get()).put("quiet_ms",SystemClock.elapsedRealtime()-clock().lastEvent).put("captured_at",System.currentTimeMillis());
            }
            // First observation seeds package identity using a bounded fixed diagnostic only.
            if (active >= 0) cachedWindow = active;
            else { cachedWindow=-1; cachedPackage=""; }
        }
        String window = fixedOutput("dumpsys", "window", "windows");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("mCurrentFocus=Window\\{([^}]+)\\}").matcher(window);
        if (!m.find()) throw new IOException("PHONE_FOREGROUND_UNVERIFIED");
        String focus = m.group(1);
        java.util.regex.Matcher component = java.util.regex.Pattern.compile("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/([^ ]+)").matcher(focus);
        if (!component.find()) throw new IOException("PHONE_FOREGROUND_UNVERIFIED");
        String pkg = component.group(1);
        cachedPackage = pkg;
        if (pkg.equals("dev.wind.agent") || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")) throw new IOException("PHONE_PROTECTED_WINDOW");
        String size = fixedOutput("wm", "size");
        java.util.regex.Matcher s = java.util.regex.Pattern.compile("(?:Physical|Override) size: (\\d+)x(\\d+)").matcher(size);
        int width = 0, height = 0; while (s.find()) { width = Integer.parseInt(s.group(1)); height = Integer.parseInt(s.group(2)); }
        String display = fixedOutput("dumpsys", "display");
        java.util.regex.Matcher rot = java.util.regex.Pattern.compile("(?:rotation|mRotation)[ =]+(?:ROTATION_)?([0-3])").matcher(display);
        if(!rot.find()) throw new IOException("DISPLAY_ROTATION_UNVERIFIED");
        int rotation = Integer.parseInt(rot.group(1));
        if (rotation % 2 != 0) { int t = width; width = height; height = t; }
        if (width <= 0 || height <= 0 || (long)width * height > 33554432) throw new IOException("DISPLAY_INVALID");
        return new JSONObject().put("package", pkg).put("window", cachedWindow >= 0 ? "uia:"+cachedWindow : focus).put("width", width).put("height", height).put("rotation", rotation).put("display_id", 0)
            .put("structural_epoch",structural().get()).put("event_epoch", events().get()).put("quiet_ms", SystemClock.elapsedRealtime() - clock().lastEvent).put("captured_at", System.currentTimeMillis());
    }
    JSONObject observe(String mode) throws Exception {
        JSONObject options=new JSONObject().put("mode",mode).put("fresh",true);
        if(virtualTarget() && virtual!=null) options.put("display_session_id",virtual.id);
        return observe(options);
    }
    JSONObject observe(JSONObject options) throws Exception {
        if(virtualTarget()) requireVirtual(options);
        String mode=options.optString("mode","ui");
        if(!mode.equals("visual") && !allowUi) throw new SecurityException("UI_SCOPE_REQUIRED");
        if(mode.equals("visual") && !allowScreen && !allowUi) throw new SecurityException("OBSERVATION_SCOPE_REQUIRED");
        if (!mode.equals("ui") && !mode.equals("visual") && !mode.equals("hybrid")) throw new IOException("MODE_INVALID");
        nodeBudget=options.optInt("max_nodes",512); depthBudget=options.optInt("max_depth",40);
        contentTruncated=false;
        budgetExhausted=false; encodedBytes=0;
        if(nodeBudget<1 || nodeBudget>512 || depthBudget<1 || depthBudget>40) throw new IOException("OBSERVE_BUDGET_INVALID");
        guard(); JSONObject meta = metadata(); JSONArray nodes = new JSONArray();
        if(!mode.equals("visual") && !options.has("scope_node_id") && !options.optBoolean("fresh",false) && lastFullObservation!=null &&
            nodeBudget==512 && depthBudget==40 && SystemClock.elapsedRealtime()-lastFullRead<=150 &&
            lastFullObservation.getLong("event_epoch")==events().get() && lastFullObservation.getString("window").equals(meta.getString("window")) &&
            lastFullObservation.getInt("rotation")==meta.getInt("rotation")) {
            JSONObject cached = new JSONObject(lastFullObservation.toString());
            cached.put("cached",true).put("cache_age_ms",SystemClock.elapsedRealtime()-lastFullRead).put("observation_id",UUID.randomUUID().toString());
            observations.put(cached.getString("observation_id"),new JSONObject(cached.toString()));
            while(observations.size()>24) observations.remove(observations.keySet().iterator().next());
            return cached;
        }
        if (!mode.equals("visual")) {
            String scope=options.optString("scope_node_id","0");
            if(options.has("scope_node_id")) {
                JSONObject base=observations.get(options.getString("base_observation_id"));
                if(base==null || !base.getString("window").equals(meta.getString("window"))) throw new IOException("SCOPE_BASE_STALE");
                JSONObject old=find(base.getJSONArray("nodes"),scope);
                AccessibilityNodeInfo current=node(scope);
                try {
                    Rect bounds=new Rect(); current.getBoundsInScreen(bounds);
                    if(!old.optString("class").equals(str(current.getClassName())) || !old.optString("resource_id").equals(current.getViewIdResourceName()==null?"":current.getViewIdResourceName()) ||
                        !old.getJSONArray("bounds").toString().equals(new JSONArray(new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom}).toString())) throw new IOException("SCOPE_TARGET_CHANGED");
                } finally { current.recycle(); }
            }
            AccessibilityNodeInfo root = scope.equals("0") ? root() : node(scope);
            if (root != null) {
                try {
                    if(!root.isVisibleToUser() && root.getChildCount()==0) root.refresh();
                    if (!meta.getString("package").equals(str(root.getPackageName()))) throw new IOException("PHONE_WINDOW_CHANGED");
                    walk(root, scope, nodes, 0);
                }
                finally { root.recycle(); }
            }
        }
        JSONObject after = metadata();
        if (!meta.getString("window").equals(after.getString("window")) || meta.getInt("rotation") != after.getInt("rotation") || meta.getLong("structural_epoch")!=after.getLong("structural_epoch")) throw new IOException("PHONE_WINDOW_CHANGED");
        String id = UUID.randomUUID().toString();
        JSONObject result = meta.put("status", "COMPLETED").put("observation_id", id).put("mode", mode).put("nodes", nodes)
            .put("truncated", contentTruncated || nodes.length() >= nodeBudget || depthBudget<40).put("ime_visible", imeVisible());
        boolean useful=false;
        for(int i=0;i<nodes.length();i++) { JSONObject n=nodes.getJSONObject(i); JSONArray b=n.getJSONArray("bounds");
            if(n.optBoolean("visible") && b.getInt(2)>b.getInt(0) && b.getInt(3)>b.getInt(1) &&
                (n.optBoolean("editable") || !n.optString("text").isEmpty() || !n.optString("description").isEmpty())) useful=true;
        }
        result.put("ui_content_available",useful).put("ui_content_status",mode.equals("visual")?"NOT_REQUESTED":useful?"AVAILABLE":"EMPTY_OR_INACCESSIBLE");
        result.put("scope_node_id",options.optString("scope_node_id","0"));
        if(!mode.equals("visual") && !options.has("scope_node_id") && nodeBudget==512 && depthBudget==40) { lastFullObservation=new JSONObject(result.toString()); lastFullRead=SystemClock.elapsedRealtime(); }
        JSONObject base=observations.get(options.optString("base_observation_id",""));
        if(base!=null && base.getString("window").equals(meta.getString("window"))) {
            Map<String,String> old=new LinkedHashMap<>(); JSONArray prior=base.getJSONArray("nodes");
            for(int i=0;i<prior.length();i++) { JSONObject n=prior.getJSONObject(i); old.put(n.getString("node_id"),n.toString()); }
            JSONArray changed=new JSONArray();
            for(int i=0;i<nodes.length();i++) { JSONObject n=nodes.getJSONObject(i); String previous=old.remove(n.getString("node_id")); if(!n.toString().equals(previous)) changed.put(n); }
            result.put("delta",new JSONObject().put("base_observation_id",options.getString("base_observation_id")).put("changed",changed).put("removed",new JSONArray(old.keySet())));
        }
        observations.put(id, new JSONObject(result.toString())); while (observations.size() > 24) observations.remove(observations.keySet().iterator().next());
        return result;
    }
    static String str(CharSequence s) { return s == null ? "" : s.toString(); }
    boolean imeVisible() {
        if(virtualTarget()) return false;
        boolean visible=false;
        for(AccessibilityWindowInfo window:ui.getWindows()) { try { if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD) visible=true; } finally { window.recycle(); } }
        return visible;
    }
    String bounded(CharSequence value, boolean editor) { String text=str(value); int limit=editor?16000:1024; if(text.length()>limit) { contentTruncated=true; return text.substring(0,limit); } return text; }
    void walk(AccessibilityNodeInfo n, String path, JSONArray nodes, int depth) throws Exception {
        if (nodes.length() >= nodeBudget || depth > depthBudget || budgetExhausted) return;
        Rect b = new Rect(); n.getBoundsInScreen(b);
        JSONObject entry=new JSONObject().put("node_id", path).put("path", path).put("class", str(n.getClassName())).put("resource_id", n.getViewIdResourceName()==null?"":n.getViewIdResourceName())
            .put("text", n.isPassword() ? "" : bounded(n.getText(),n.isEditable())).put("description", n.isPassword() ? "" : bounded(n.getContentDescription(),false))
            .put("bounds", new JSONArray(new int[]{b.left,b.top,b.right,b.bottom})).put("clickable", n.isClickable()).put("editable", n.isEditable())
            .put("scrollable", n.isScrollable()).put("enabled", n.isEnabled()).put("password", n.isPassword()).put("visible", n.isVisibleToUser());
        int size=entry.toString().getBytes("UTF-8").length;
        if(encodedBytes+size>400000) { contentTruncated=true; budgetExhausted=true; return; }
        encodedBytes+=size; nodes.put(entry);
        for (int i = 0; i < n.getChildCount() && nodes.length() < nodeBudget && !budgetExhausted; i++) {
            AccessibilityNodeInfo c = n.getChild(i); if (c != null) try { walk(c, path + "." + i, nodes, depth + 1); } finally { c.recycle(); }
        }
    }
    void checkObservation(JSONObject a) throws Exception {
        if(virtualTarget()) requireVirtual(a);
        JSONObject old = observations.get(a.getString("observation_id"));
        if (old == null || System.currentTimeMillis() - old.getLong("captured_at") > 15000) throw new IOException("PHONE_OBSERVATION_EXPIRED");
        JSONObject now = metadata();
        for (String key: new String[]{"window", "package", "width", "height", "rotation", "display_id", "structural_epoch"}) if (!old.get(key).toString().equals(now.get(key).toString())) throw new IOException("PHONE_WINDOW_CHANGED");
        if(virtualTarget()) for(String key:new String[]{"display_session_id","display_epoch","geometry_epoch"})
            if(!old.get(key).toString().equals(now.get(key).toString())) throw new IOException("SESSION_CHANGED");
        JSONObject pixelProof=a.optJSONObject("visual_guard");
        if(pixelProof!=null) {
            if(!virtualTarget() || !allowScreen) throw new IOException("CHAT_VISUAL_SCOPE_REQUIRED");
            ByteArrayOutputStream image=new ByteArrayOutputStream();
            virtual.capture(ui,image,false);
            byte[] bytes=image.toByteArray(); Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            if(bitmap==null) throw new IOException("CHAT_VISUAL_FRAME_UNAVAILABLE");
            try {
                int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()]; bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());
                for(String part:pixelProof.has("target")?new String[]{"header","composer","conversation","target"}:new String[]{"header","composer","conversation"}) {
                    JSONArray region=pixelProof.getJSONArray(part); if(region.length()!=4) throw new IOException("CHAT_VISUAL_REGION_INVALID");
                    int[] box={region.getInt(0),region.getInt(1),region.getInt(2),region.getInt(3)};
                    if(!VisualPatchDigest.digest(pixels,bitmap.getWidth(),bitmap.getHeight(),box).equals(pixelProof.getString(part+"_hash")))
                        throw new IOException("CHAT_VISUAL_CONTENT_CHANGED");
                }
            } finally { bitmap.recycle(); }
        }
        JSONObject peer=a.optJSONObject("peer_guard");
        if(peer!=null) {
            if(peer.optBoolean("require_event_epoch") && peer.optLong("event_epoch",-1)!=events().get()) throw new IOException("CHAT_MESSAGE_CHANGED_BEFORE_COMMIT");
            if(!peer.getString("package").equals(now.getString("package"))) throw new IOException("CHAT_TARGET_CHANGED");
            if(peer.getString("source").equals("ocr")) {
                if(peer.optLong("event_epoch",-1)<0 || peer.getLong("event_epoch")!=events().get()) throw new IOException("CHAT_PAGE_CHANGED_AFTER_OCR");
            } else {
                JSONObject fresh=observe("ui"); JSONArray nodes=fresh.getJSONArray("nodes"); int titles=0;
                if(fresh.optBoolean("truncated")) throw new IOException("CHAT_TREE_TRUNCATED");
                String title=peer.getString("title").replaceAll("\\s+","");
                for(int i=0;i<nodes.length();i++) {
                    JSONObject node=nodes.getJSONObject(i); JSONArray bounds=node.getJSONArray("bounds"); int top=bounds.getInt(1)*1000/fresh.getInt("height");
                    String label=node.optString("text").isEmpty()?node.optString("description"):node.optString("text");
                    if(top<20 || top>225 || !node.optBoolean("visible") || node.optBoolean("editable") || !label.replaceAll("\\s+","").equals(title)) continue;
                    boolean duplicate=false; String path=node.getString("node_id")+".";
                    for(int j=0;j<nodes.length();j++) { JSONObject child=nodes.getJSONObject(j); String childLabel=child.optString("text").isEmpty()?child.optString("description"):child.optString("text");
                        if(child.getString("node_id").startsWith(path) && childLabel.replaceAll("\\s+","").equals(title)) duplicate=true;
                    }
                    if(!duplicate) titles++;
                }
                if(titles!=1) throw new IOException("CHAT_CONVERSATION_NOT_VERIFIED");
            }
        }
        if (a.has("node_id")) {
            if (old.getString("mode").equals("visual")) throw new IOException("VISUAL_NODE_FORBIDDEN");
            JSONObject before = find(old.getJSONArray("nodes"), a.getString("node_id"));
            JSONObject after = singleNode(a.getString("node_id"),now.getString("package"));
            for (String key: new String[]{"resource_id", "class", "bounds", "text", "editable", "password", "enabled", "visible"})
                if (!String.valueOf(before.opt(key)).equals(String.valueOf(after.opt(key)))) throw new IOException("TARGET_CHANGED");
            if (after.optBoolean("password") || !after.optBoolean("visible") || !after.optBoolean("enabled")) throw new IOException("TARGET_PROTECTED");
        } else if (!old.getString("mode").equals("visual") && a.has("x") && a.has("y")) {
            JSONObject before = point(old,a), fresh=observe("ui"), after=point(fresh,a);
            if (after != null && (after.optBoolean("password") || !after.optBoolean("enabled"))) throw new IOException("TARGET_PROTECTED");
            if(before==null && after!=null && (after.optBoolean("clickable") || after.optBoolean("editable") || after.optBoolean("scrollable"))) throw new IOException("TARGET_APPEARED_AFTER_OBSERVATION");
            if (before != null) {
                if (after == null) throw new IOException("TARGET_CHANGED");
                for (String key:new String[]{"resource_id","class","bounds","text","clickable","editable","enabled"})
                    if (!String.valueOf(before.opt(key)).equals(String.valueOf(after.opt(key)))) throw new IOException("TARGET_CHANGED");
            }
        }
    }
    JSONObject singleNode(String path,String pkg) throws Exception {
        AccessibilityNodeInfo current=node(path);
        int priorNodes=nodeBudget, priorDepth=depthBudget, priorBytes=encodedBytes;
        boolean priorTruncated=contentTruncated, priorExhausted=budgetExhausted;
        try {
            if(!current.refresh() || !pkg.equals(str(current.getPackageName()))) throw new IOException("TARGET_CHANGED");
            nodeBudget=1; depthBudget=0; encodedBytes=0; contentTruncated=false; budgetExhausted=false;
            JSONArray one=new JSONArray(); walk(current,path,one,0);
            return find(one,path);
        } finally {
            current.recycle(); nodeBudget=priorNodes; depthBudget=priorDepth; encodedBytes=priorBytes;
            contentTruncated=priorTruncated; budgetExhausted=priorExhausted;
        }
    }
    JSONObject point(JSONObject o, JSONObject a) throws Exception {
        int x=(int)(a.getDouble("x")*(o.getInt("width")-1)/1000), y=(int)(a.getDouble("y")*(o.getInt("height")-1)/1000);
        JSONObject found=null; long area=Long.MAX_VALUE; JSONArray nodes=o.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++) { JSONObject n=nodes.getJSONObject(i); JSONArray b=n.getJSONArray("bounds");
            long next=(long)(b.getInt(2)-b.getInt(0))*(b.getInt(3)-b.getInt(1));
            if(n.optBoolean("visible") && x>=b.getInt(0) && x<b.getInt(2) && y>=b.getInt(1) && y<b.getInt(3) && next>=0 && next<area) { found=n; area=next; }
        }
        return found;
    }
    static JSONObject find(JSONArray nodes, String id) throws Exception { for (int i=0; i<nodes.length(); i++) if (nodes.getJSONObject(i).getString("node_id").equals(id)) return nodes.getJSONObject(i); throw new IOException("NODE_NOT_FOUND"); }
    void validateAction(JSONObject a) throws Exception {
        String action = a.getString("action");
        if(virtualTarget()) {
            requireVirtual(a);
            if(action.equals("launch")) {
                VirtualDisplayPolicy.packageName(a.getString("package"),false); requireAllowed(a.getString("package"));
                virtual.validateLaunch(a.getString("package"),a.getString("component"),a.optString("task_policy","reuse_or_move")); return;
            }
            VirtualDisplayPolicy.action(action,a.has("node_id"));
        }
        if (!Arrays.asList("tap", "long_press", "swipe", "click", "dismiss", "set_text", "paste_text", "type_text", "scroll", "back", "home", "recents", "notifications", "quick_settings", "ime_action").contains(action)) throw new IOException("ACTION_UNSUPPORTED");
        if (action.equals("ime_action")) throw new IOException("IME_ACTION_REQUIRES_EXPLICIT_SEND_BUTTON");
        if (Arrays.asList("click","dismiss","scroll").contains(action) && !a.has("node_id")) throw new IOException("NODE_REQUIRED");
        if(virtualTarget() && !a.has("node_id") && Arrays.asList("set_text","type_text","paste_text").contains(action)) {
            String text=a.getString("text");
            KeyEvent[] keys=android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
            if((action.equals("paste_text") || keys==null) && !clipboardAvailable()) throw new IOException("PHONE_CLIPBOARD_UNAVAILABLE");
        }
        if (a.has("text") && (a.getString("text").length() > 16000 || a.getString("text").indexOf(0) >= 0)) throw new IOException("TEXT_LIMIT");
        for (String key: new String[]{"x","y","x2","y2"}) if (a.has(key) && !(a.getDouble(key) >= 0 && a.getDouble(key) <= 1000)) throw new IOException("COORDINATE_INVALID");
        if (a.optInt("duration_ms",300) < 1 || a.optInt("duration_ms",300) > 1500) throw new IOException("DURATION_INVALID");
    }
    AccessibilityNodeInfo node(String path) throws Exception {
        if (!path.matches("0(?:\\.[0-9]{1,3}){0,40}")) throw new IOException("NODE_PATH_INVALID");
        AccessibilityNodeInfo n = root();
        for (String part: path.split("\\.")) { if (part.equals("0") && path.indexOf('.') < 0) break; }
        String[] parts = path.split("\\.");
        for (int i=1; i<parts.length && n!=null; i++) { AccessibilityNodeInfo next = n.getChild(Integer.parseInt(parts[i])); n.recycle(); n = next; }
        if (n == null || n.isPassword() || !n.isEnabled() || !n.isVisibleToUser()) { if (n != null) n.recycle(); throw new IOException("NODE_PROTECTED"); }
        return n;
    }
    void action(JSONObject a) throws Exception {
        String action = a.getString("action");
        if(virtualTarget() && action.equals("launch")) { virtual.launch(a.getString("package"),a.getString("component"),ui); return; }
        if(virtualTarget() && !a.has("node_id") && Arrays.asList("set_text","type_text","paste_text").contains(action)) {
            focusedText(a); return;
        }
        if (Arrays.asList("click", "dismiss", "set_text", "paste_text", "type_text", "scroll").contains(action)) {
            AccessibilityNodeInfo n = node(a.getString("node_id"));
            try {
                Bundle b = new Bundle(); int op;
                if (action.equals("click") || action.equals("dismiss")) op = AccessibilityNodeInfo.ACTION_CLICK;
                else if (action.equals("scroll")) {
                    String direction=a.optString("direction","down");
                    if(direction.equals("left")) op=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                    else if(direction.equals("right")) op=AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
                    else op=direction.equals("up") || direction.equals("backward") ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                }
                else {
                    if (!n.isEditable()) throw new IOException("EDITOR_REQUIRED");
                    if (action.equals("paste_text") && clipboardAvailable()) {
                        setClipboard(a.getString("text"));
                        if (a.optBoolean("replace",false)) {
                            Bundle selection = new Bundle(); selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, str(n.getText()).length());
                            if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)) throw new IOException("EDITOR_SELECTION_REFUSED");
                        }
                        op = AccessibilityNodeInfo.ACTION_PASTE;
                    } else {
                        if (action.equals("paste_text") && !a.optBoolean("replace",false)) throw new IOException("CLIPBOARD_UNAVAILABLE_USE_SET_TEXT");
                        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.equals("type_text") ? str(n.getText())+a.getString("text") : a.getString("text")); op = AccessibilityNodeInfo.ACTION_SET_TEXT;
                    }
                }
                if (!n.performAction(op, b)) throw new IOException("ACTION_REFUSED");
            } finally { n.recycle(); }
            return;
        }
        if (action.equals("tap") || action.equals("long_press") || action.equals("swipe")) {
            JSONObject meta = metadata(); int w = meta.getInt("width"), h = meta.getInt("height");
            float x = (float)(a.getDouble("x") * (w-1) / 1000), y = (float)(a.getDouble("y") * (h-1) / 1000);
            int duration = action.equals("long_press") ? 650 : action.equals("tap") ? 40 : a.optInt("duration_ms",300);
            float x2 = action.equals("swipe") ? (float)(a.getDouble("x2") * (w-1) / 1000) : x, y2 = action.equals("swipe") ? (float)(a.getDouble("y2") * (h-1) / 1000) : y;
            VirtualScreen.FocusLease focus=virtualTarget()?virtual.rememberMainFocus():null;
            try {
            long start = SystemClock.uptimeMillis(); motion(start, start, MotionEvent.ACTION_DOWN, x, y);
            boolean released=false;
            try {
                for (int elapsed=16; elapsed<duration; elapsed+=16) { SystemClock.sleep(16); float ratio = elapsed/(float)duration; motion(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x+(x2-x)*ratio, y+(y2-y)*ratio); }
                motion(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2); released=true;
            } finally { if(!released) try { motion(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,x2,y2); } catch(Exception ignored) {} }
            } finally { if(focus!=null) focus.close(); }
            if(focus!=null) a.put("input_receipt",new JSONObject().put("strategy","DISPLAY_BOUND_TOUCH").put("display_id",virtual.displayId())
                .put("focus_restored",focus.restored).put("main_focus_preserved_best_effort",true));
            return;
        }
        if (action.equals("notifications") || action.equals("quick_settings")) { fixedOutput("cmd", "statusbar", action.equals("notifications") ? "expand-notifications" : "expand-settings"); return; }
        int code = action.equals("back") ? KeyEvent.KEYCODE_BACK : action.equals("home") ? KeyEvent.KEYCODE_HOME : action.equals("recents") ? KeyEvent.KEYCODE_APP_SWITCH : KeyEvent.KEYCODE_ENTER;
        if (action.equals("ime_action")) throw new IOException("IME_ACTION_REQUIRES_EXPLICIT_SEND_BUTTON");
        if(virtualTarget()) {
            VirtualScreen.FocusLease focus=virtual.borrowTextFocus(metadata().getString("package"),this::activateVirtualFocus);
            try { pressKey(code,0); } finally { focus.close(); }
            a.put("input_receipt",new JSONObject().put("strategy","DISPLAY_BOUND_KEY").put("display_id",virtual.displayId())
                .put("focus_borrowed",focus.borrowed).put("focus_restored",focus.restored));
        } else pressKey(code,0);
    }
    void activateVirtualFocus() throws Exception {
        long t=SystemClock.uptimeMillis();
        motion(t,t,MotionEvent.ACTION_DOWN,1,1);
        try { SystemClock.sleep(30); }
        finally { motion(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,1,1); }
    }
    void pressKey(int code,int meta) throws Exception {
        long t=SystemClock.uptimeMillis();
        KeyEvent down=new KeyEvent(t,t,KeyEvent.ACTION_DOWN,code,0,meta,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
        KeyEvent up=new KeyEvent(t,t,KeyEvent.ACTION_UP,code,0,meta,android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
        bindInput(down); bindInput(up);
        if(!ui.injectInputEvent(down,true) || !ui.injectInputEvent(up,true)) throw new IOException("INPUT_REFUSED");
    }
    void focusedText(JSONObject a) throws Exception {
        String text=a.getString("text");
        KeyEvent[] keys=android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        boolean paste=a.getString("action").equals("paste_text") || keys==null;
        // Plan all characters before effects; do not emit a partial ASCII prefix then replay using paste.
        String pkg=metadata().getString("package");
        VirtualScreen.FocusLease focus=virtual.borrowTextFocus(pkg,this::activateVirtualFocus);
        long began=SystemClock.elapsedRealtime();
        try {
            if(paste) setClipboard(text);
            if(a.getString("action").equals("set_text") || a.optBoolean("replace")) pressKey(KeyEvent.KEYCODE_A,KeyEvent.META_CTRL_ON);
            if(paste) pressKey(KeyEvent.KEYCODE_PASTE,0);
            else for(KeyEvent e:keys) { bindInput(e); if(!ui.injectInputEvent(e,true)) throw new IOException("INPUT_REFUSED"); }
            // WebView handles paste asynchronously after InputDispatcher acknowledges the key.
            // Keep its clipboard-reading focus until that queued work has had time to run.
            // This is a bounded dispatch grace period, not verification or an effect retry.
            if(paste) SystemClock.sleep(600);
        } finally { focus.close(); }
        a.put("input_receipt",new JSONObject().put("strategy",paste?"DISPLAY_BOUND_CLIPBOARD_PASTE":"DISPLAY_BOUND_KEY_EVENTS")
            .put("display_id",virtual.displayId()).put("clipboard_changed",paste).put("ime_switched",false)
            .put("focus_borrowed",focus.borrowed).put("focus_restored",focus.restored).put("focus_lease_ms",SystemClock.elapsedRealtime()-began)
            .put("text_verified",false).put("verification","DISPATCH_ONLY_REOBSERVE_VISUALLY"));
    }
    void bindInput(android.view.InputEvent e) throws Exception {
        if(virtualTarget()) virtual.bindInput(e);
        else if(Build.VERSION.SDK_INT>=29) {
            android.view.InputEvent.class.getMethod("setDisplayId",int.class).invoke(e,0);
            if((Integer)android.view.InputEvent.class.getMethod("getDisplayId").invoke(e)!=0) throw new IOException("PHONE_MAIN_DISPLAY_UNVERIFIED");
        }
    }
    void motion(long start, long now, int action, float x, float y) throws Exception {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
        coordinate.x = x; coordinate.y = y; coordinate.pressure = 1f; coordinate.size = 1f;
        MotionEvent e = MotionEvent.obtain(start, now, action, 1,
            new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinate},
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { bindInput(e); if (!ui.injectInputEvent(e,true)) throw new IOException("INPUT_REFUSED"); } finally { e.recycle(); }
    }
    Object clipboard() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "clipboard");
        return Class.forName("android.content.IClipboard$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null,binder);
    }
    boolean clipboardAvailable() { try { Object c = clipboard(); for (Method m:c.getClass().getMethods()) if (m.getName().equals("setPrimaryClip")) return true; } catch (Exception ignored) {} return false; }
    void setClipboard(String text) throws Exception {
        Object c = clipboard();
        for (Method m:c.getClass().getMethods()) if (m.getName().equals("setPrimaryClip")) {
            Class<?>[] types=m.getParameterTypes(); Object[] values=new Object[types.length]; int strings=0, integers=0;
            for (int i=0;i<types.length;i++) {
                if (types[i] == ClipData.class) values[i] = ClipData.newPlainText("Vega",text);
                else if (types[i] == String.class) values[i] = strings++ == 0 ? "com.android.shell" : null;
                else if (types[i] == int.class) values[i] = integers++ == 0 ? 0 : 0;
                else throw new IOException("CLIPBOARD_SIGNATURE_UNSUPPORTED");
            }
            m.setAccessible(true); m.invoke(c,values); return;
        }
        throw new IOException("CLIPBOARD_UNAVAILABLE");
    }
    byte[] capture() throws Exception {
        Bitmap bitmap = ui.takeScreenshot(); if (bitmap == null) throw new IOException("SCREENSHOT_UNAVAILABLE");
        try {
            if ((long)bitmap.getWidth()*bitmap.getHeight() > 33554432) throw new IOException("PIXEL_BUDGET");
            int edge = Math.max(bitmap.getWidth(),bitmap.getHeight());
            if (edge > 2048) { Bitmap resized = Bitmap.createScaledBitmap(bitmap,bitmap.getWidth()*2048/edge,bitmap.getHeight()*2048/edge,true); bitmap.recycle(); bitmap = resized; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);
            if (bytes.size() > IMAGE_LIMIT) throw new IOException("IMAGE_LIMIT"); return bytes.toByteArray();
        } finally { bitmap.recycle(); }
    }
}
