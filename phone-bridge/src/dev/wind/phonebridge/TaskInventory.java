package dev.wind.phonebridge;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** RootTaskInfo (API31+) and StackInfo (API30) are different wire schemas. */
public final class TaskInventory {
    public static final class Task {
        public final int display;
        public final int id,rootId,user;
        public final String name;
        Task(int display,String name) { this(display,name,-1,-1,-1); }
        Task(int display,String name,int id,int rootId,int user) { this.display=display; this.name=name; this.id=id; this.rootId=rootId; this.user=user; }
        boolean belongsTo(String pkg) { return name.equals(pkg) || name.startsWith(pkg+"/"); }
    }
    public static final class Root {
        public final int id,display,user;
        Root(int id,int display,int user) { this.id=id; this.display=display; this.user=user; }
    }
    private static Object field(Object row,String name) throws Exception {
        Field f=row.getClass().getField(name); f.setAccessible(true); return f.get(row);
    }
    private static int optionalInt(Object row,String name) throws Exception {
        try { Object v=field(row,name); if(!(v instanceof Integer)) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED"); return (Integer)v; }
        catch(NoSuchFieldException unavailable) { return -1; }
    }
    public static List<Task> decode(Object rows) throws Exception {
        if(!(rows instanceof List) || ((List<?>)rows).size()>256) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
        List<Task> tasks=new ArrayList<>();
        for(Object row:(List<?>)rows) {
            if(row==null) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
            Object display=field(row,"displayId"), names,ids;
            if(!(display instanceof Integer) || (Integer)display<0) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
            try { names=field(row,"childTaskNames"); ids=field(row,"childTaskIds"); }
            catch(NoSuchFieldException old) { names=field(row,"taskNames"); ids=field(row,"taskIds"); }
            if(!(ids instanceof int[]) || ((int[])ids).length>2048) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
            int count=((int[])ids).length;
            if(count==0 && names==null) continue;
            if(!(names instanceof String[]) || ((String[])names).length!=count) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
            int root=optionalInt(row,"taskId"),user=optionalInt(row,"userId");
            int index=0;
            for(String name:(String[])names) {
                if(name==null || name.isEmpty() || name.length()>1024) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
                tasks.add(new Task((Integer)display,name,((int[])ids)[index++],root,user));
                if(tasks.size()>8192) throw new IOException("VIRTUAL_DISPLAY_TASK_SCHEMA_UNSUPPORTED");
            }
        }
        return tasks;
    }
    public static boolean onDisplay(List<Task> tasks,String pkg,int display) throws IOException {
        if(display<=0) throw new IOException("VIRTUAL_DISPLAY_TARGET_INVALID");
        boolean found=false;
        for(Task task:tasks) if(task.belongsTo(pkg)) {
            if(task.display!=display) throw new IOException("PACKAGE_PRESENT_ON_OTHER_DISPLAY");
            found=true;
        }
        return found;
    }
    public static boolean present(List<Task> tasks,String pkg,int display,int user) {
        for(Task t:tasks) if(t.belongsTo(pkg) && t.display==display && (t.user==user || t.user<0)) return true;
        return false;
    }
    /** Move only one complete, same-user, same-package main-screen root; never a mixed stack. */
    public static Root movable(List<Task> tasks,String pkg,int display,int user) throws IOException {
        Root result=null;
        for(Task t:tasks) if(t.belongsTo(pkg) && t.display!=display && (t.user==user || t.user<0)) {
            if(t.display!=0) throw new IOException("VIRTUAL_DISPLAY_TASK_ON_OTHER_SECONDARY_DISPLAY");
            if(t.rootId<0 || t.id<0 || t.user!=user) throw new IOException("VIRTUAL_DISPLAY_TASK_IDENTITY_UNAVAILABLE");
            if(result!=null && result.id!=t.rootId) throw new IOException("VIRTUAL_DISPLAY_TASK_AMBIGUOUS");
            result=new Root(t.rootId,t.display,t.user);
        }
        if(result!=null) for(Task t:tasks) if(t.rootId==result.id &&
            (!t.belongsTo(pkg) || t.user!=user || t.display!=result.display)) throw new IOException("VIRTUAL_DISPLAY_TASK_MIXED_ROOT");
        return result;
    }
}
