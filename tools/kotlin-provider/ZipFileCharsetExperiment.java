import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.InsnNode;
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode;

/** Scratch only: avoid the ZipFile(File, Charset) overload absent on API 23. */
public final class ZipFileCharsetExperiment {
    private static final String INPUT_SHA =
        "7df9610f68d745d1ef513141a0ee1e2ab6052cbcb87341cba9bea02be67cd7eb";
    private static final String ENTRY =
        "org/jetbrains/kotlin/com/intellij/ide/plugins/PluginDescriptorLoader.class";
    private static final String CLASS_SHA =
        "e1ac991010a05607888dc0d60161501f4ece01a87456377cf28bd421e35b8d2a";
    private static final String BUS_ENTRY =
        "org/jetbrains/kotlin/com/intellij/util/messages/impl/MessageBusImpl$RootBus.class";
    private static final String BUS_SHA =
        "2017fcbb8af516ca9612d0d942f608030fc2df24e579eb42da66d728359b9f46";
    private static final String TRACE_ENTRY = "org/jetbrains/kotlin/container/CacheKt.class";
    private static final String TRACE_SHA =
        "3c08742ab122df736d79a3aa9696e7367ccb4de94c52ddc0fa9e7425d61d0b4a";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input-jar fresh-output-jar");
        Path input = Paths.get(args[0]).toRealPath();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        if (!INPUT_SHA.equals(hex(Files.readAllBytes(input)))) throw new IOException("COMPILER_INPUT_SHA_MISMATCH");
        if (Files.exists(output) || input.equals(output)) throw new IOException("COMPILER_EXPERIMENT_OUTPUT_EXISTS");
        boolean patched = false;
        boolean busPatched = false;
        boolean tracePatched = false;
        try (JarFile jar = new JarFile(input.toFile());
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(output))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] contents;
                try (InputStream source = jar.getInputStream(entry)) { contents = source.readAllBytes(); }
                if (ENTRY.equals(entry.getName())) {
                    if (!CLASS_SHA.equals(hex(contents))) throw new IOException("COMPILER_CLASS_SHA_MISMATCH");
                    contents = patch(contents);
                    patched = true;
                    System.out.println("EXPERIMENT_CLASS_SHA=" + hex(contents));
                }
                if (BUS_ENTRY.equals(entry.getName())) {
                    if (!BUS_SHA.equals(hex(contents))) throw new IOException("BUS_CLASS_SHA_MISMATCH");
                    contents = patchBus(contents);
                    busPatched = true;
                    System.out.println("EXPERIMENT_BUS_SHA=" + hex(contents));
                }
                if (TRACE_ENTRY.equals(entry.getName())) {
                    if (!TRACE_SHA.equals(hex(contents))) throw new IOException("TRACE_CLASS_SHA_MISMATCH");
                    contents = skipUnsupportedPlatformBridgeReflection(contents);
                    tracePatched = true;
                    System.out.println("EXPERIMENT_TRACE_SHA=" + hex(contents));
                }
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(entry.getTime());
                target.putNextEntry(copy);
                target.write(contents);
                target.closeEntry();
            }
        }
        if (!patched) throw new IOException("COMPILER_CLASS_NOT_FOUND");
        if (!busPatched) throw new IOException("BUS_CLASS_NOT_FOUND");
        if (!tracePatched) throw new IOException("TRACE_CLASS_NOT_FOUND");
        System.out.println("EXPERIMENT_JAR_SHA=" + hex(Files.readAllBytes(output)));
    }

    /** The pinned TargetPlatform has no setters; avoid ART 23 resolving D8's invalid Stream bridge. */
    private static byte[] skipUnsupportedPlatformBridgeReflection(byte[] input) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!TRACE_ENTRY.substring(0, TRACE_ENTRY.length() - 6).equals(node.name)) {
            throw new IOException("TRACE_CLASS_NAME_MISMATCH");
        }
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!"getSetterInfos".equals(method.name) ||
                !"(Ljava/lang/Class;)Ljava/util/List;".equals(method.desc)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() != Opcodes.INVOKEVIRTUAL ||
                    !"java/lang/Class".equals(call.owner) || !"getMethods".equals(call.name) ||
                    !"()[Ljava/lang/reflect/Method;".equals(call.desc)) continue;
                org.jetbrains.org.objectweb.asm.tree.LabelNode normal =
                    new org.jetbrains.org.objectweb.asm.tree.LabelNode();
                org.jetbrains.org.objectweb.asm.tree.InsnList guard =
                    new org.jetbrains.org.objectweb.asm.tree.InsnList();
                guard.add(new InsnNode(Opcodes.DUP));
                guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class", "getName", "()Ljava/lang/String;", false));
                guard.add(new org.jetbrains.org.objectweb.asm.tree.LdcInsnNode(
                    "org.jetbrains.kotlin.platform.TargetPlatform"));
                guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                guard.add(new org.jetbrains.org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, normal));
                guard.add(new InsnNode(Opcodes.POP));
                guard.add(new org.jetbrains.org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new InsnNode(Opcodes.ARETURN));
                guard.add(normal);
                method.instructions.insertBefore(call, guard);
                count++;
            }
        }
        if (count != 1) throw new IOException("TRACE_PATCH_COUNT=" + count);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Scratch-only: use the API 1 Future/ExecutorService contract in IntelliJ's housekeeping. */
    private static byte[] patchBus(byte[] input) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!BUS_ENTRY.substring(0, BUS_ENTRY.length() - 6).equals(node.name)) {
            throw new IOException("BUS_CLASS_NAME_MISMATCH");
        }
        int castCount = 0;
        int cancelCount = 0;
        int submitCount = 0;
        for (MethodNode method : node.methods) {
            if (!"scheduleEmptyConnectionRemoving".equals(method.name) &&
                !"dispose".equals(method.name)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof TypeInsnNode) {
                    TypeInsnNode cast = (TypeInsnNode) instruction;
                    if (cast.getOpcode() == Opcodes.CHECKCAST &&
                        "java/util/concurrent/CompletableFuture".equals(cast.desc)) {
                        cast.desc = "java/util/concurrent/Future";
                        castCount++;
                    }
                }
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL &&
                    "java/util/concurrent/CompletableFuture".equals(call.owner) &&
                    "cancel".equals(call.name) && "(Z)Z".equals(call.desc)) {
                    MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                        "java/util/concurrent/Future", "cancel", "(Z)Z", true);
                    method.instructions.set(call, replacement);
                    instruction = replacement;
                    cancelCount++;
                } else if (call.getOpcode() == Opcodes.INVOKESTATIC &&
                    "java/util/concurrent/CompletableFuture".equals(call.owner) &&
                    "runAsync".equals(call.name) &&
                    "(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;".equals(call.desc)) {
                    method.instructions.insertBefore(call, new InsnNode(Opcodes.SWAP));
                    MethodInsnNode replacement = new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                        "java/util/concurrent/ExecutorService", "submit",
                        "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", true);
                    method.instructions.set(call, replacement);
                    instruction = replacement;
                    submitCount++;
                }
            }
        }
        if (castCount != 2 || cancelCount != 2 || submitCount != 1) {
            throw new IOException("BUS_PATCH_COUNTS=" + castCount + "/" + cancelCount + "/" + submitCount);
        }
        node.fields.stream().filter(field -> "compactionFutureRef".equals(field.name))
            .forEach(field -> field.signature = null);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] patch(byte[] input) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!ENTRY.substring(0, ENTRY.length() - 6).equals(node.name)) {
            throw new IOException("COMPILER_CLASS_NAME_MISMATCH");
        }
        int count = 0;
        for (MethodNode method : node.methods) {
            if (!"loadDescriptorFromJar".equals(method.name)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.getOpcode() != Opcodes.INVOKESPECIAL ||
                    !"java/util/zip/ZipFile".equals(call.owner) || !"<init>".equals(call.name) ||
                    !"(Ljava/io/File;Ljava/nio/charset/Charset;)V".equals(call.desc)) continue;
                AbstractInsnNode previous = call.getPrevious();
                if (!(previous instanceof FieldInsnNode)) throw new IOException("ZIP_CHARSET_SEQUENCE_CHANGED");
                FieldInsnNode charset = (FieldInsnNode) previous;
                if (charset.getOpcode() != Opcodes.GETSTATIC ||
                    !"java/nio/charset/StandardCharsets".equals(charset.owner) ||
                    !"UTF_8".equals(charset.name) ||
                    !"Ljava/nio/charset/Charset;".equals(charset.desc)) {
                    throw new IOException("ZIP_CHARSET_SEQUENCE_CHANGED");
                }
                method.instructions.remove(charset);
                call.desc = "(Ljava/io/File;)V";
                count++;
            }
        }
        if (count != 1) throw new IOException("ZIP_CHARSET_PATCH_COUNT=" + count);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static String hex(byte[] contents) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(contents);
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
