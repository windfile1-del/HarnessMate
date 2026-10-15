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
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode;

/** Scratch-only experiment: preserve the NIO path's immutable result without its stream collector. */
public final class DesugarNioCollectorExperiment {
    private static final String INPUT_SHA =
        "25ddad0d96cf7ba284cfa7866f54aa3508468a1df80e79cb44733a66f755d330";
    private static final String ENTRY = "desugar/sun/nio/fs/DesugarUnixPath.class";
    private static final String CLASS_SHA =
        "da5baa690208b3a76180f4e422c95fcb0ff7fac6b6ff1cc14f68313c87ae6486";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input-jar fresh-output-jar");
        Path input = Paths.get(args[0]).toRealPath();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        if (!INPUT_SHA.equals(hex(Files.readAllBytes(input)))) throw new IOException("DESUGAR_INPUT_SHA_MISMATCH");
        if (Files.exists(output) || input.equals(output)) throw new IOException("DESUGAR_EXPERIMENT_OUTPUT_EXISTS");
        boolean patched = false;
        try (JarFile jar = new JarFile(input.toFile());
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(output))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] contents;
                try (InputStream source = jar.getInputStream(entry)) { contents = source.readAllBytes(); }
                if (ENTRY.equals(entry.getName())) {
                    if (!CLASS_SHA.equals(hex(contents))) throw new IOException("DESUGAR_CLASS_SHA_MISMATCH");
                    contents = patch(contents);
                    patched = true;
                    System.out.println("EXPERIMENT_CLASS_SHA=" + hex(contents));
                }
                JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(entry.getTime());
                target.putNextEntry(copy);
                target.write(contents);
                target.closeEntry();
            }
        }
        if (!patched) throw new IOException("DESUGAR_CLASS_NOT_FOUND");
        System.out.println("EXPERIMENT_JAR_SHA=" + hex(Files.readAllBytes(output)));
    }

    private static byte[] patch(byte[] input) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        if (!"desugar/sun/nio/fs/DesugarUnixPath".equals(node.name)) {
            throw new IOException("DESUGAR_CLASS_NAME_MISMATCH");
        }
        int changed = 0;
        for (MethodNode method : node.methods) {
            if (!"getFileNames".equals(method.name) ||
                !"(Ljava/lang/String;)Ljava/util/List;".equals(method.desc)) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (!"java/util/stream/Collectors".equals(call.owner) ||
                    !"toUnmodifiableList".equals(call.name) ||
                    !"()Ljava/util/stream/Collector;".equals(call.desc)) continue;
                AbstractInsnNode collect = call.getNext();
                AbstractInsnNode cast = collect == null ? null : collect.getNext();
                if (!(collect instanceof MethodInsnNode) || !(cast instanceof TypeInsnNode) ||
                    !"collect".equals(((MethodInsnNode) collect).name) ||
                    cast.getOpcode() != Opcodes.CHECKCAST ||
                    !"java/util/List".equals(((TypeInsnNode) cast).desc)) {
                    throw new IOException("DESUGAR_COLLECT_SEQUENCE_CHANGED");
                }
                call.name = "toList";
                method.instructions.insert(cast, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/util/Collections", "unmodifiableList",
                    "(Ljava/util/List;)Ljava/util/List;", false));
                changed++;
            }
        }
        if (changed != 1) throw new IOException("DESUGAR_COLLECT_PATCH_COUNT=" + changed);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static String hex(byte[] contents) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(contents);
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
