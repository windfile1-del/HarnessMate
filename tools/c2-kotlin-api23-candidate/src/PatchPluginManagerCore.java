import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import org.jetbrains.org.objectweb.asm.*;
import org.jetbrains.org.objectweb.asm.tree.*;

public final class PatchPluginManagerCore {
  static final String TARGET = "org/jetbrains/kotlin/com/intellij/ide/plugins/PluginManagerCore";
  static final String FIELD = "HAS_LOADED_CLASS_METHOD_TYPE";
  static final String FIELD_DESC = "Ljava/lang/invoke/MethodType;";
  static String sha256(byte[] data) throws Exception {
    byte[] d=MessageDigest.getInstance("SHA-256").digest(data);
    StringBuilder b=new StringBuilder();
    for(byte x:d) b.append(String.format("%02x",x & 255));
    return b.toString();
  }
  static Map<LabelNode,Integer> labelIds(MethodNode m) {
    Map<LabelNode,Integer> ids=new IdentityHashMap<>();
    int n=0;
    for(AbstractInsnNode i=m.instructions.getFirst();i!=null;i=i.getNext())
      if(i instanceof LabelNode) ids.put((LabelNode)i,n++);
    return ids;
  }
  static int labelId(Map<LabelNode,Integer> ids, LabelNode l) {
    Integer n=ids.get(l);
    if(n==null) throw new IllegalStateException("unmapped label");
    return n;
  }
  static String render(AbstractInsnNode i, Map<LabelNode,Integer> labels) {
    if (i instanceof LabelNode) return "LABEL:"+labelId(labels,(LabelNode)i);
    if (i instanceof LineNumberNode) { LineNumberNode x=(LineNumberNode)i; return "LINE:"+x.line+":"+labelId(labels,x.start); }
    if (i instanceof FrameNode) { FrameNode x=(FrameNode)i; return "FRAME:"+x.type+":"+String.valueOf(x.local)+":"+String.valueOf(x.stack); }
    String base = i.getClass().getSimpleName() + ":" + i.getOpcode();
    if (i instanceof FieldInsnNode) { FieldInsnNode x=(FieldInsnNode)i; return base+":"+x.owner+"."+x.name+":"+x.desc; }
    if (i instanceof MethodInsnNode) { MethodInsnNode x=(MethodInsnNode)i; return base+":"+x.owner+"."+x.name+x.desc+":"+x.itf; }
    if (i instanceof LdcInsnNode) { Object x=((LdcInsnNode)i).cst; return base+":"+String.valueOf(x); }
    if (i instanceof TypeInsnNode) { TypeInsnNode x=(TypeInsnNode)i; return base+":"+x.desc; }
    if (i instanceof IntInsnNode) { IntInsnNode x=(IntInsnNode)i; return base+":"+x.operand; }
    if (i instanceof VarInsnNode) { VarInsnNode x=(VarInsnNode)i; return base+":"+x.var; }
    if (i instanceof IincInsnNode) { IincInsnNode x=(IincInsnNode)i; return base+":"+x.var+":"+x.incr; }
    if (i instanceof JumpInsnNode) { JumpInsnNode x=(JumpInsnNode)i; return base+":"+labelId(labels,x.label); }
    if (i instanceof InvokeDynamicInsnNode) { InvokeDynamicInsnNode x=(InvokeDynamicInsnNode)i; return base+":"+x.name+x.desc+":"+x.bsm+":"+Arrays.deepToString(x.bsmArgs); }
    if (i instanceof TableSwitchInsnNode) { TableSwitchInsnNode x=(TableSwitchInsnNode)i; StringBuilder b=new StringBuilder(base+":"+x.min+":"+x.max+":"+labelId(labels,x.dflt)); for(LabelNode l:x.labels)b.append(":").append(labelId(labels,l)); return b.toString(); }
    if (i instanceof LookupSwitchInsnNode) { LookupSwitchInsnNode x=(LookupSwitchInsnNode)i; StringBuilder b=new StringBuilder(base+":"+x.keys+":"+labelId(labels,x.dflt)); for(LabelNode l:x.labels)b.append(":").append(labelId(labels,l)); return b.toString(); }
    if (i instanceof MultiANewArrayInsnNode) { MultiANewArrayInsnNode x=(MultiANewArrayInsnNode)i; return base+":"+x.desc+":"+x.dims; }
    return base;
  }
  static String methodKey(MethodNode m) { return m.name+m.desc; }
  static String fingerprint(MethodNode m, Set<AbstractInsnNode> grouped, AbstractInsnNode inserted) {
    Map<LabelNode,Integer> labels=labelIds(m);
    StringBuilder b=new StringBuilder();
    boolean targetWritten=false;
    for(AbstractInsnNode i=m.instructions.getFirst();i!=null;i=i.getNext()) {
      if(grouped.contains(i)||i==inserted) {
        if(!targetWritten) { b.append("<TARGET-INIT>\n"); targetWritten=true; }
      } else b.append(render(i,labels)).append('\n');
    }
    b.append("MAX:").append(m.maxStack).append(':').append(m.maxLocals).append('\n');
    for(TryCatchBlockNode t:m.tryCatchBlocks) b.append("TC:").append(labelId(labels,t.start)).append(':').append(labelId(labels,t.end)).append(':').append(labelId(labels,t.handler)).append(':').append(t.type).append('\n');
    return b.toString();
  }
  static Map<String,String> classMethodFingerprints(ClassNode c, Set<AbstractInsnNode> targetNodes, AbstractInsnNode inserted, MethodNode targetMethod) {
    Map<String,String> out=new TreeMap<>();
    for(MethodNode m:c.methods) out.put(methodKey(m),fingerprint(m,m==targetMethod?targetNodes:Collections.emptySet(),m==targetMethod?inserted:null));
    return out;
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=2) throw new IllegalArgumentException("compiler jar path, overlay root");
    byte[] original;
    try(JarFile jar=new JarFile(args[0])) {
      JarEntry e=jar.getJarEntry(TARGET+".class");
      if(e==null) throw new IllegalStateException("target class not found in original jar");
      try(InputStream in=jar.getInputStream(e)) { original=in.readAllBytes(); }
    }
    String sourceHash=sha256(original);
    if(!sourceHash.equals("9057c880e414a03b97c1d87d147621e611f36886c9f319848150af30c1908d05")) throw new IllegalStateException("unexpected PluginManagerCore input SHA-256: "+sourceHash);
    ClassNode node=new ClassNode();
    new ClassReader(original).accept(node,0);
    if(!TARGET.equals(node.name)) throw new IllegalStateException("unexpected target owner "+node.name);
    MethodNode clinit=null;
    for(MethodNode m:node.methods) if(m.name.equals("<clinit>")&&m.desc.equals("()V")) clinit=m;
    if(clinit==null) throw new IllegalStateException("<clinit> missing");
    AbstractInsnNode a=null,b=null,c=null,d=null;
    int matches=0;
    for(AbstractInsnNode i=clinit.instructions.getFirst();i!=null;i=i.getNext()) {
      if(i instanceof FieldInsnNode&&i.getOpcode()==Opcodes.GETSTATIC) {
        FieldInsnNode f=(FieldInsnNode)i;
        if(f.owner.equals("java/lang/Boolean")&&f.name.equals("TYPE")&&f.desc.equals("Ljava/lang/Class;")) {
          AbstractInsnNode n1=i.getNext(), n2=n1==null?null:n1.getNext(), n3=n2==null?null:n2.getNext();
          boolean stringClass=n1 instanceof LdcInsnNode&&((LdcInsnNode)n1).cst instanceof Type&&((Type)((LdcInsnNode)n1).cst).getSort()==Type.OBJECT&&((Type)((LdcInsnNode)n1).cst).getInternalName().equals("java/lang/String");
          boolean methodType=n2 instanceof MethodInsnNode&&n2.getOpcode()==Opcodes.INVOKESTATIC&&((MethodInsnNode)n2).owner.equals("java/lang/invoke/MethodType")&&((MethodInsnNode)n2).name.equals("methodType")&&((MethodInsnNode)n2).desc.equals("(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;");
          boolean put=n3 instanceof FieldInsnNode&&n3.getOpcode()==Opcodes.PUTSTATIC&&((FieldInsnNode)n3).owner.equals(TARGET)&&((FieldInsnNode)n3).name.equals(FIELD)&&((FieldInsnNode)n3).desc.equals(FIELD_DESC);
          if(stringClass&&methodType&&put) { matches++;a=i;b=n1;c=n2;d=n3; }
        }
      }
    }
    if(matches!=1) throw new IllegalStateException("expected exactly one matching initializer sequence, found "+matches);
    Set<AbstractInsnNode> triplet=Collections.newSetFromMap(new IdentityHashMap<>());
    triplet.add(a);triplet.add(b);triplet.add(c);
    Map<String,String> before=classMethodFingerprints(node,triplet,null,clinit);
    InsnNode nullValue=new InsnNode(Opcodes.ACONST_NULL);
    InsnList replacement=new InsnList();replacement.add(nullValue);
    clinit.instructions.insertBefore(a,replacement);
    clinit.instructions.remove(a);clinit.instructions.remove(b);clinit.instructions.remove(c);
    ClassWriter writer=new ClassWriter(0);
    node.accept(writer);
    byte[] patched=writer.toByteArray();
    ClassNode verify=new ClassNode();
    new ClassReader(patched).accept(verify,0);
    MethodNode verifyClinit=null;
    int puts=0,nulls=0,methodTypeCalls=0;
    AbstractInsnNode verifyNull=null;
    for(MethodNode m:verify.methods) if(m.name.equals("<clinit>")&&m.desc.equals("()V")) verifyClinit=m;
    if(verifyClinit==null) throw new IllegalStateException("written <clinit> missing");
    for(AbstractInsnNode i=verifyClinit.instructions.getFirst();i!=null;i=i.getNext()) {
      if(i instanceof FieldInsnNode&&i.getOpcode()==Opcodes.PUTSTATIC) {
        FieldInsnNode f=(FieldInsnNode)i;
        if(f.owner.equals(TARGET)&&f.name.equals(FIELD)&&f.desc.equals(FIELD_DESC)) puts++;
      }
      if(i.getOpcode()==Opcodes.ACONST_NULL&&i.getNext() instanceof FieldInsnNode) {
        FieldInsnNode f=(FieldInsnNode)i.getNext();
        if(f.getOpcode()==Opcodes.PUTSTATIC&&f.owner.equals(TARGET)&&f.name.equals(FIELD)&&f.desc.equals(FIELD_DESC)) { nulls++;verifyNull=i; }
      }
      if(i instanceof MethodInsnNode&&((MethodInsnNode)i).owner.equals("java/lang/invoke/MethodType")&&((MethodInsnNode)i).name.equals("methodType")) methodTypeCalls++;
    }
    if(puts!=1||nulls!=1||methodTypeCalls!=0) throw new IllegalStateException("post-write initializer verification failed: put="+puts+" null+put="+nulls+" methodTypeCalls="+methodTypeCalls);
    Map<String,String> after=classMethodFingerprints(verify,Collections.emptySet(),verifyNull,verifyClinit);
    if(!before.equals(after)) {
      Set<String> keys=new TreeSet<>();keys.addAll(before.keySet());keys.addAll(after.keySet());
      for(String k:keys) if(!Objects.equals(before.get(k),after.get(k))) throw new IllegalStateException("non-target method structure differs after ASM write: "+k);
    }
    String outputHash=sha256(patched);
    if(!outputHash.equals("604dba89ce417fd705eebf93350c9bff50c15281d4f542665fe1c41059d439bf")) throw new IllegalStateException("unexpected PluginManagerCore overlay SHA-256: "+outputHash);
    Path out=Paths.get(args[1],"org","jetbrains","kotlin","com","intellij","ide","plugins","PluginManagerCore.class");
    Files.createDirectories(out.getParent());
    Files.write(out,patched);
    System.out.println("source SHA-256="+sha256(original));
    System.out.println("overlay SHA-256="+sha256(patched));
    System.out.println("matching initializers=1; mutation=GETSTATIC Boolean.TYPE + LDC String.class + INVOKESTATIC MethodType.methodType replaced by ACONST_NULL; existing PUTSTATIC retained");
    System.out.println("all method instruction/branch/frame/exception fingerprints match except the target initializer sequence="+before.equals(after));
    System.out.println("overlay="+out.toAbsolutePath());
  }
}
