package dev.wind.phonebridge;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Android-free invariants shared by the shell display controller and host tests. */
public final class VirtualDisplayPolicy {
    public static final int WIDTH = 720, HEIGHT = 1280, DPI = 320;
    public static final int POLICY_VERSION = 3;
    private VirtualDisplayPolicy() {}

    public static final class ApiRequirement {
        public final String owner, member;
        public final Class<?>[] parameters; // null = integer flag field
        public final boolean required;
        ApiRequirement(String owner,String member,Class<?>[] parameters,boolean required) { this.owner=owner; this.member=member; this.parameters=parameters; this.required=required; }
        public String name() { return owner+"."+member; }
    }
    public interface ApiInspector { void inspect(ApiRequirement requirement) throws Exception; }
    public static final class ApiCheck {
        public final ApiRequirement requirement;
        public final boolean available;
        public final String error;
        ApiCheck(ApiRequirement requirement,boolean available,String error) { this.requirement=requirement; this.available=available; this.error=error; }
    }
    public static List<ApiCheck> checkApis(ApiInspector inspector) {
        List<ApiRequirement> requirements=new ArrayList<>();
        for(String flag:new String[]{"SUPPORTS_TOUCH","DESTROY_CONTENT_ON_REMOVAL"})
            requirements.add(new ApiRequirement("android.hardware.display.DisplayManager","VIRTUAL_DISPLAY_FLAG_"+flag,null,true));
        for(String flag:new String[]{"TRUSTED","OWN_FOCUS","STEAL_TOP_FOCUS_DISABLED"}) {
            requirements.add(new ApiRequirement("android.hardware.display.DisplayManager","VIRTUAL_DISPLAY_FLAG_"+flag,null,false));
            requirements.add(new ApiRequirement("android.view.Display","FLAG_"+flag,null,false));
        }
        requirements.add(new ApiRequirement("android.view.Display","getRemoveMode",new Class<?>[0],true));
        requirements.add(new ApiRequirement("android.view.Display","REMOVE_MODE_DESTROY_CONTENT",null,true));
        requirements.add(new ApiRequirement("android.view.IWindowManager","setDisplayImePolicy",new Class<?>[]{int.class,int.class},false));
        requirements.add(new ApiRequirement("android.view.IWindowManager","getDisplayImePolicy",new Class<?>[]{int.class},false));
        requirements.add(new ApiRequirement("android.view.IWindowManager","getTopFocusedDisplayId",new Class<?>[0],false));
        requirements.add(new ApiRequirement("android.view.InputEvent","setDisplayId",new Class<?>[]{int.class},true));
        requirements.add(new ApiRequirement("android.view.InputEvent","getDisplayId",new Class<?>[0],true));
        requirements.add(new ApiRequirement("android.app.UiAutomation","getWindowsOnAllDisplays",new Class<?>[0],true));
        requirements.add(new ApiRequirement("android.view.accessibility.AccessibilityWindowInfo","getDisplayId",new Class<?>[0],true));
        List<ApiCheck> result=new ArrayList<>();
        for(ApiRequirement r:requirements) {
            try { inspector.inspect(r); result.add(new ApiCheck(r,true,"")); }
            catch(Exception error) {
                String code=error instanceof NoSuchFieldException?"MISSING_FIELD":error instanceof NoSuchMethodException?"MISSING_METHOD":
                    error instanceof ClassNotFoundException?"MISSING_CLASS":error instanceof SecurityException?"ACCESS_DENIED":"API_INSPECTION_FAILED";
                result.add(new ApiCheck(r,false,code));
            }
        }
        return result;
    }

    public static void runtime(int removeMode) throws IOException {
        if(removeMode!=1) throw new IOException("VIRTUAL_DISPLAY_REMOVAL_ISOLATION_UNVERIFIED");
    }
    public static boolean usable(List<ApiCheck> checks) {
        for(ApiCheck check:checks) if(check.requirement.required && !check.available) return false;
        return !checks.isEmpty();
    }
    public static boolean focusApisPresent(List<ApiCheck> checks) {
        for(ApiCheck check:checks) if(!check.requirement.required && !check.requirement.member.contains("ImePolicy") && !check.available) return false;
        return !checks.isEmpty();
    }

    public static void session(String expected, String supplied, int displayId) throws IOException {
        if (expected == null || !expected.equals(supplied) || displayId <= 0)
            throw new IOException("VIRTUAL_DISPLAY_SESSION_CHANGED");
    }
    public static void action(String action, boolean hasNode) throws IOException {
        if (!Arrays.asList("tap", "long_press", "swipe", "click", "dismiss", "set_text", "type_text", "paste_text", "scroll", "back").contains(action))
            throw new IOException("VIRTUAL_DISPLAY_ACTION_UNSUPPORTED");
    }
    public static void geometry(int width, int height, int rotation) throws IOException {
        // v1 is a fixed portrait display. Reject OEM geometry changes rather than misroute coordinates.
        if (width != WIDTH || height != HEIGHT || rotation != 0)
            throw new IOException("VIRTUAL_DISPLAY_GEOMETRY_UNSUPPORTED");
    }
    public static void packageName(String pkg, boolean diagnostic) throws IOException {
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new IOException("PHONE_PACKAGE_INVALID");
        // Package identity is metadata. Android's actual permissions decide what can run.
    }
}
