import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.Deflater;

import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode;
import org.jetbrains.org.objectweb.asm.tree.FrameNode;
import org.jetbrains.org.objectweb.asm.tree.IincInsnNode;
import org.jetbrains.org.objectweb.asm.tree.IntInsnNode;
import org.jetbrains.org.objectweb.asm.tree.InsnList;
import org.jetbrains.org.objectweb.asm.tree.InsnNode;
import org.jetbrains.org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.jetbrains.org.objectweb.asm.tree.JumpInsnNode;
import org.jetbrains.org.objectweb.asm.tree.LabelNode;
import org.jetbrains.org.objectweb.asm.tree.LdcInsnNode;
import org.jetbrains.org.objectweb.asm.tree.LineNumberNode;
import org.jetbrains.org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;
import org.jetbrains.org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.jetbrains.org.objectweb.asm.tree.TableSwitchInsnNode;
import org.jetbrains.org.objectweb.asm.tree.TryCatchBlockNode;
import org.jetbrains.org.objectweb.asm.tree.TypeInsnNode;
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode;

/** Builds the pinned Kotlin 1.9.20 Android overlay compiler JAR. */
public final class KotlinCompilerAndroidPatcher {
    private static final String EXPECTED_COMPILER_SHA256 =
            "a25024fe5da8440de01af045c4fcb954a22f078738ec02616085f0cfc57b2702";
    private static final String EXPECTED_PLUGIN_INPUT_SHA256 =
            "9057c880e414a03b97c1d87d147621e611f36886c9f319848150af30c1908d05";
    private static final String EXPECTED_PLUGIN_OVERLAY_SHA256 =
            "604dba89ce417fd705eebf93350c9bff50c15281d4f542665fe1c41059d439bf";
    private static final String EXPECTED_PROVIDER_JAR_SHA256 =
            "7df9610f68d745d1ef513141a0ee1e2ab6052cbcb87341cba9bea02be67cd7eb";
    private static final String PLUGIN_MANAGER =
            "org/jetbrains/kotlin/com/intellij/ide/plugins/PluginManagerCore";
    private static final String GROUPING_COLLECTOR =
            "org/jetbrains/kotlin/cli/common/messages/GroupingMessageCollector";
    private static final String EXPECTED_GROUPING_INPUT_SHA256 =
            "e0f12fb24581ae8c6e9e359172347757407a7d256026c95eb3ec81362319ef36";
    private static final String EXPECTED_GROUPING_OVERLAY_SHA256 =
            "2ad4b5f936bc367e36e33b9cb86e34bf8a7e05c37b9c178bccb84b40f9b1404d";
    private static final String PICO_CONSTRUCTOR_ADAPTER =
            "org/jetbrains/kotlin/com/intellij/util/pico/CachingConstructorInjectionComponentAdapter";
    private static final String EXPECTED_PICO_INPUT_SHA256 =
            "c9e65ae238f1c5e2469d0550ee195aacfc41696f1b7b87d094e9f8d6d325ca13";
    private static final String EXPECTED_PICO_OVERLAY_SHA256 =
            "1098a4281367870f5e8bf626b0761c3b974b0be1e38770208acb6d726befd0af";
    private static final String MAP_PACKAGE =
            "org/jetbrains/kotlin/com/intellij/util/containers/";
    private static final String INT_MAP = MAP_PACKAGE + "ConcurrentIntObjectHashMap";
    private static final String LONG_MAP = MAP_PACKAGE + "ConcurrentLongObjectHashMap";
    private static final String METHOD_TYPE_FIELD = "HAS_LOADED_CLASS_METHOD_TYPE";
    private static final String METHOD_TYPE_FIELD_DESC = "Ljava/lang/invoke/MethodType;";
    private static final LocalDateTime FIXED_ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

    private KotlinCompilerAndroidPatcher() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println(
                    "usage: KotlinCompilerAndroidPatcher <upstream-compiler.jar> <overlay-class-root> <output.jar>");
            System.exit(2);
        }
        try {
            build(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
        } catch (Exception error) {
            System.err.println("KotlinCompilerAndroidPatcher: " + error.getMessage());
            System.exit(1);
        }
    }

    private static void build(Path compilerJar, Path overlayRoot, Path outputJar) throws Exception {
        Path input = compilerJar.toAbsolutePath().normalize();
        Path overlays = overlayRoot.toAbsolutePath().normalize();
        Path output = outputJar.toAbsolutePath().normalize();
        requireRegularFile(input, "upstream Kotlin compiler JAR");
        if (!Files.isDirectory(overlays, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(overlays)) {
            throw new IOException("overlay class root is not a regular directory: " + overlays);
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(input, output)) {
            throw new IOException("refusing to rewrite the upstream compiler JAR");
        }

        String compilerHash = sha256(input);
        if (!EXPECTED_COMPILER_SHA256.equals(compilerHash)) {
            throw new IOException("KOTLIN_PROVIDER_UPSTREAM_MISMATCH expected="
                    + EXPECTED_COMPILER_SHA256 + " actual=" + compilerHash);
        }

        TreeMap<String, byte[]> originalEntries = readJar(input);
        byte[] originalPluginManager = requireEntry(originalEntries, PLUGIN_MANAGER + ".class");
        byte[] patchedPluginManager = patchPluginManagerCore(originalPluginManager);
        byte[] patchedGroupingCollector = patchGroupingCollector(
                requireEntry(originalEntries, GROUPING_COLLECTOR + ".class"));
        byte[] patchedPicoAdapter = patchPicoAdapter(
                requireEntry(originalEntries, PICO_CONSTRUCTOR_ADAPTER + ".class"));
        TreeMap<String, byte[]> outputEntries = new TreeMap<>();

        for (Map.Entry<String, byte[]> entry : originalEntries.entrySet()) {
            String name = entry.getKey();
            if (isSignatureOrManifest(name) || isMapImplementationClass(name)) {
                continue;
            }
            outputEntries.put(name, name.equals(PLUGIN_MANAGER + ".class")
                    ? patchedPluginManager : name.equals(GROUPING_COLLECTOR + ".class")
                    ? patchedGroupingCollector : name.equals(PICO_CONSTRUCTOR_ADAPTER + ".class")
                    ? patchedPicoAdapter : entry.getValue());
        }

        TreeMap<String, byte[]> overlayEntries = readOverlayClasses(overlays);
        for (Map.Entry<String, byte[]> entry : overlayEntries.entrySet()) {
            if (outputEntries.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IOException("duplicate transformed JAR entry: " + entry.getKey());
            }
        }
        requireEntry(outputEntries, INT_MAP + ".class");
        requireEntry(outputEntries, LONG_MAP + ".class");
        requireEntry(outputEntries, PLUGIN_MANAGER + ".class");

        Path parent = output.getParent();
        if (parent == null) {
            throw new IOException("output JAR must have a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, output.getFileName().toString() + ".", ".tmp");
        boolean published = false;
        try {
            writeJar(temporary, outputEntries);
            verifyWrittenJar(temporary, outputEntries, originalEntries,
                    patchedPluginManager, patchedGroupingCollector, patchedPicoAdapter);
            String generatedHash = sha256(temporary);
            if (!EXPECTED_PROVIDER_JAR_SHA256.equals(generatedHash)) {
                throw new IOException("KOTLIN_PROVIDER_OUTPUT_MISMATCH expected="
                        + EXPECTED_PROVIDER_JAR_SHA256 + " actual=" + generatedHash);
            }
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }

        System.out.println("compiler input SHA-256=" + compilerHash);
        System.out.println("PluginManagerCore input SHA-256=" + EXPECTED_PLUGIN_INPUT_SHA256);
        System.out.println("PluginManagerCore overlay SHA-256=" + EXPECTED_PLUGIN_OVERLAY_SHA256);
        System.out.println("retained non-signature entries=" + outputEntries.size());
        System.out.println("android2 JAR=" + output);
        System.out.println("android2 JAR SHA-256=" + EXPECTED_PROVIDER_JAR_SHA256);
    }

    private static TreeMap<String, byte[]> readJar(Path jarPath) throws IOException {
        TreeMap<String, byte[]> entries = new TreeMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> items = jar.entries();
            while (items.hasMoreElements()) {
                JarEntry entry = items.nextElement();
                String name = entry.getName();
                byte[] bytes;
                if (entry.isDirectory()) {
                    bytes = new byte[0];
                } else {
                    try (InputStream stream = jar.getInputStream(entry)) {
                        bytes = stream.readAllBytes();
                    }
                }
                if (entries.putIfAbsent(name, bytes) != null) {
                    throw new IOException("duplicate entry in pinned upstream JAR: " + name);
                }
            }
        }
        return entries;
    }

    private static TreeMap<String, byte[]> readOverlayClasses(Path root) throws IOException {
        TreeMap<String, byte[]> classes = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            List<Path> files = new ArrayList<>();
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(files::add);
            files.sort(Comparator.comparing(path -> root.relativize(path).toString()));
            for (Path file : files) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("refusing symbolic-link overlay class: " + file);
                }
                String name = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (!name.endsWith(".class") || !isMapImplementationClass(name)) {
                    throw new IOException("unexpected overlay output; only the two map class families are allowed: "
                            + name);
                }
                byte[] bytes = Files.readAllBytes(file);
                ClassReader reader = new ClassReader(bytes);
                String expectedInternalName = name.substring(0, name.length() - ".class".length());
                if (!expectedInternalName.equals(reader.getClassName())) {
                    throw new IOException("overlay path/class identity mismatch: " + name);
                }
                int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
                if (major != 52) {
                    throw new IOException("overlay class must target Java 8 bytecode (major 52): " + name
                            + " major=" + major);
                }
                classes.put(name, bytes);
            }
        }
        requireEntry(classes, INT_MAP + ".class");
        requireEntry(classes, LONG_MAP + ".class");
        return classes;
    }

    /** Preserve the two upstream predicates but replace the Android 23 j$ stream traversal. */
    private static byte[] patchGroupingCollector(byte[] original) throws IOException {
        if (!EXPECTED_GROUPING_INPUT_SHA256.equals(sha256(original))) {
            throw new IOException("KOTLIN_GROUPING_UPSTREAM_MISMATCH");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        if (!GROUPING_COLLECTOR.equals(node.name)) throw new IOException("KOTLIN_GROUPING_CLASS_MISMATCH");
        for (String[] target : new String[][] {
                {"hasExplicitErrors", "lambda$hasExplicitErrors$0"},
                {"hasWarnings", "lambda$hasWarnings$1"}
        }) {
            MethodNode method = null;
            int matches = 0;
            for (MethodNode candidate : node.methods) {
                if (candidate.name.equals(target[0]) && candidate.desc.equals("()Z")) {
                    method = candidate;
                    matches++;
                }
            }
            if (matches != 1 || method == null || !usesStreamAnyMatch(method)) {
                throw new IOException("KOTLIN_GROUPING_METHOD_MISMATCH: " + target[0]);
            }
            boolean predicatePresent = false;
            for (MethodNode candidate : node.methods) {
                if (candidate.name.equals(target[1]) &&
                        candidate.desc.equals("(Ljava/util/Map$Entry;)Z")) predicatePresent = true;
            }
            if (!predicatePresent) throw new IOException("KOTLIN_GROUPING_PREDICATE_MISSING: " + target[1]);
            replaceAnyMatchWithIterator(method, target[1]);
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] patched = writer.toByteArray();
        ClassNode verified = new ClassNode();
        new ClassReader(patched).accept(verified, 0);
        for (MethodNode method : verified.methods) {
            if ((method.name.equals("hasExplicitErrors") || method.name.equals("hasWarnings")) &&
                    usesStreamAnyMatch(method)) throw new IOException("KOTLIN_GROUPING_STREAM_STILL_PRESENT");
        }
        String digest = sha256(patched);
        if (!EXPECTED_GROUPING_OVERLAY_SHA256.equals(digest)) {
            throw new IOException("KOTLIN_GROUPING_OVERLAY_MISMATCH expected=" +
                    EXPECTED_GROUPING_OVERLAY_SHA256 + " actual=" + digest);
        }
        return patched;
    }

    private static boolean usesStreamAnyMatch(MethodNode method) {
        int stream = 0;
        int anyMatch = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals("java/util/Collection") && call.name.equals("stream")) stream++;
            if (call.owner.equals("java/util/stream/Stream") && call.name.equals("anyMatch")) anyMatch++;
        }
        return stream == 1 && anyMatch == 1;
    }

    /** Android 23 Constructor lacks getParameterCount(); array length is equivalent. */
    private static byte[] patchPicoAdapter(byte[] original) throws IOException {
        if (!EXPECTED_PICO_INPUT_SHA256.equals(sha256(original))) {
            throw new IOException("KOTLIN_PICO_UPSTREAM_MISMATCH");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        if (!PICO_CONSTRUCTOR_ADAPTER.equals(node.name)) throw new IOException("KOTLIN_PICO_CLASS_MISMATCH");
        int replaced = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (call.getOpcode() == Opcodes.INVOKEVIRTUAL &&
                            call.owner.equals("java/lang/reflect/Constructor") &&
                            call.name.equals("getParameterCount") && call.desc.equals("()I")) {
                        call.name = "getParameterTypes";
                        call.desc = "()[Ljava/lang/Class;";
                        method.instructions.insert(call, new InsnNode(Opcodes.ARRAYLENGTH));
                        replaced++;
                    }
                }
                instruction = next;
            }
        }
        if (replaced != 3) throw new IOException("KOTLIN_PICO_CALLSITE_COUNT: " + replaced);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] patched = writer.toByteArray();
        String digest = sha256(patched);
        if (!EXPECTED_PICO_OVERLAY_SHA256.equals(digest)) {
            throw new IOException("KOTLIN_PICO_OVERLAY_MISMATCH expected=" +
                    EXPECTED_PICO_OVERLAY_SHA256 + " actual=" + digest);
        }
        return patched;
    }

    private static void replaceAnyMatchWithIterator(MethodNode method, String predicate) {
        InsnList body = new InsnList();
        LabelNode check = new LabelNode();
        LabelNode yes = new LabelNode();
        LabelNode no = new LabelNode();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new FieldInsnNode(Opcodes.GETFIELD, GROUPING_COLLECTOR, "groupedMessages",
                "Lorg/jetbrains/kotlin/com/google/common/collect/Multimap;"));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "org/jetbrains/kotlin/com/google/common/collect/Multimap", "entries",
                "()Ljava/util/Collection;", true));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;", true));
        body.add(new VarInsnNode(Opcodes.ASTORE, 1));
        body.add(check);
        body.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {"java/util/Iterator"}, 0, null));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        body.add(new JumpInsnNode(Opcodes.IFEQ, no));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        body.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map$Entry"));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GROUPING_COLLECTOR, predicate,
                "(Ljava/util/Map$Entry;)Z", false));
        body.add(new JumpInsnNode(Opcodes.IFNE, yes));
        body.add(new JumpInsnNode(Opcodes.GOTO, check));
        body.add(yes);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        body.add(new InsnNode(Opcodes.ICONST_1));
        body.add(new InsnNode(Opcodes.IRETURN));
        body.add(no);
        body.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.clear();
        method.instructions.add(body);
        method.maxStack = 1;
        method.maxLocals = 2;
    }

    private static byte[] patchPluginManagerCore(byte[] original) throws IOException {
        if (!EXPECTED_PLUGIN_INPUT_SHA256.equals(sha256(original))) {
            throw new IOException("KOTLIN_PROVIDER_UPSTREAM_MISMATCH PluginManagerCore class digest changed");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        if (!PLUGIN_MANAGER.equals(node.name)) {
            throw new IOException("unexpected PluginManagerCore internal name: " + node.name);
        }

        MethodNode clinit = null;
        int clinitCount = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                clinit = method;
                clinitCount++;
            }
        }
        if (clinitCount != 1 || clinit == null) {
            throw new IOException("expected one PluginManagerCore.<clinit>, found " + clinitCount);
        }

        AbstractInsnNode getBooleanType = null;
        AbstractInsnNode stringClass = null;
        AbstractInsnNode methodTypeCall = null;
        int matches = 0;
        for (AbstractInsnNode instruction = clinit.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.GETSTATIC) {
                continue;
            }
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (!field.owner.equals("java/lang/Boolean") || !field.name.equals("TYPE")
                    || !field.desc.equals("Ljava/lang/Class;")) {
                continue;
            }
            AbstractInsnNode next = instruction.getNext();
            AbstractInsnNode following = next == null ? null : next.getNext();
            AbstractInsnNode put = following == null ? null : following.getNext();
            boolean isStringClass = next instanceof LdcInsnNode
                    && ((LdcInsnNode) next).cst instanceof Type
                    && ((Type) ((LdcInsnNode) next).cst).getSort() == Type.OBJECT
                    && ((Type) ((LdcInsnNode) next).cst).getInternalName().equals("java/lang/String");
            boolean isMethodType = following instanceof MethodInsnNode
                    && following.getOpcode() == Opcodes.INVOKESTATIC
                    && ((MethodInsnNode) following).owner.equals("java/lang/invoke/MethodType")
                    && ((MethodInsnNode) following).name.equals("methodType")
                    && ((MethodInsnNode) following).desc.equals(
                            "(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;");
            boolean isTargetPut = put instanceof FieldInsnNode
                    && put.getOpcode() == Opcodes.PUTSTATIC
                    && ((FieldInsnNode) put).owner.equals(PLUGIN_MANAGER)
                    && ((FieldInsnNode) put).name.equals(METHOD_TYPE_FIELD)
                    && ((FieldInsnNode) put).desc.equals(METHOD_TYPE_FIELD_DESC);
            if (isStringClass && isMethodType && isTargetPut) {
                matches++;
                getBooleanType = instruction;
                stringClass = next;
                methodTypeCall = following;
            }
        }
        if (matches != 1) {
            throw new IOException("expected exactly one matching PluginManagerCore initializer sequence, found "
                    + matches);
        }

        Set<AbstractInsnNode> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        replaced.add(getBooleanType);
        replaced.add(stringClass);
        replaced.add(methodTypeCall);
        Map<String, String> before = methodFingerprints(node, replaced, null, clinit);
        InsnNode nullValue = new InsnNode(Opcodes.ACONST_NULL);
        InsnList replacement = new InsnList();
        replacement.add(nullValue);
        clinit.instructions.insertBefore(getBooleanType, replacement);
        clinit.instructions.remove(getBooleanType);
        clinit.instructions.remove(stringClass);
        clinit.instructions.remove(methodTypeCall);

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] patched = writer.toByteArray();
        verifyPluginManagerPatch(patched, before);
        String patchedHash = sha256(patched);
        if (!EXPECTED_PLUGIN_OVERLAY_SHA256.equals(patchedHash)) {
            throw new IOException("PluginManagerCore overlay digest mismatch expected="
                    + EXPECTED_PLUGIN_OVERLAY_SHA256 + " actual=" + patchedHash);
        }
        return patched;
    }

    private static void verifyPluginManagerPatch(byte[] patched, Map<String, String> before) throws IOException {
        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        MethodNode clinit = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>") && method.desc.equals("()V")) {
                clinit = method;
                break;
            }
        }
        if (clinit == null) {
            throw new IOException("patched PluginManagerCore.<clinit> missing");
        }

        int targetPuts = 0;
        int nullThenPut = 0;
        int methodTypeCalls = 0;
        AbstractInsnNode insertedNull = null;
        for (AbstractInsnNode instruction = clinit.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode && instruction.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.owner.equals(PLUGIN_MANAGER) && field.name.equals(METHOD_TYPE_FIELD)
                        && field.desc.equals(METHOD_TYPE_FIELD_DESC)) {
                    targetPuts++;
                }
            }
            if (instruction.getOpcode() == Opcodes.ACONST_NULL
                    && instruction.getNext() instanceof FieldInsnNode) {
                FieldInsnNode next = (FieldInsnNode) instruction.getNext();
                if (next.getOpcode() == Opcodes.PUTSTATIC && next.owner.equals(PLUGIN_MANAGER)
                        && next.name.equals(METHOD_TYPE_FIELD) && next.desc.equals(METHOD_TYPE_FIELD_DESC)) {
                    nullThenPut++;
                    insertedNull = instruction;
                }
            }
            if (instruction instanceof MethodInsnNode
                    && ((MethodInsnNode) instruction).owner.equals("java/lang/invoke/MethodType")
                    && ((MethodInsnNode) instruction).name.equals("methodType")) {
                methodTypeCalls++;
            }
        }
        if (targetPuts != 1 || nullThenPut != 1 || methodTypeCalls != 0) {
            throw new IOException("PluginManagerCore patch verification failed: fieldPuts=" + targetPuts
                    + " nullThenPut=" + nullThenPut + " methodTypeCalls=" + methodTypeCalls);
        }
        Map<String, String> after = methodFingerprints(node, Collections.emptySet(), insertedNull, clinit);
        if (!before.equals(after)) {
            Set<String> methods = new TreeSet<>();
            methods.addAll(before.keySet());
            methods.addAll(after.keySet());
            for (String method : methods) {
                if (!Objects.equals(before.get(method), after.get(method))) {
                    throw new IOException("unexpected PluginManagerCore method change: " + method);
                }
            }
        }
    }

    private static Map<String, String> methodFingerprints(
            ClassNode node,
            Set<AbstractInsnNode> replaced,
            AbstractInsnNode inserted,
            MethodNode targetMethod) {
        TreeMap<String, String> fingerprints = new TreeMap<>();
        for (MethodNode method : node.methods) {
            Set<AbstractInsnNode> replacedHere = method == targetMethod ? replaced : Collections.emptySet();
            AbstractInsnNode insertedHere = method == targetMethod ? inserted : null;
            fingerprints.put(method.name + method.desc, methodFingerprint(method, replacedHere, insertedHere));
        }
        return fingerprints;
    }

    private static String methodFingerprint(
            MethodNode method,
            Set<AbstractInsnNode> replaced,
            AbstractInsnNode inserted) {
        Map<LabelNode, Integer> labels = new IdentityHashMap<>();
        int labelId = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (instruction instanceof LabelNode) {
                labels.put((LabelNode) instruction, labelId++);
            }
        }

        StringBuilder result = new StringBuilder();
        boolean targetWritten = false;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
             instruction = instruction.getNext()) {
            if (replaced.contains(instruction) || instruction == inserted) {
                if (!targetWritten) {
                    result.append("<TARGET-INIT>\n");
                    targetWritten = true;
                }
            } else {
                result.append(renderInstruction(instruction, labels)).append('\n');
            }
        }
        result.append("MAX:").append(method.maxStack).append(':').append(method.maxLocals).append('\n');
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            result.append("TC:").append(labelId(labels, block.start)).append(':')
                    .append(labelId(labels, block.end)).append(':')
                    .append(labelId(labels, block.handler)).append(':')
                    .append(block.type).append('\n');
        }
        return result.toString();
    }

    private static String renderInstruction(AbstractInsnNode instruction, Map<LabelNode, Integer> labels) {
        if (instruction instanceof LabelNode) {
            return "LABEL:" + labelId(labels, (LabelNode) instruction);
        }
        if (instruction instanceof LineNumberNode) {
            LineNumberNode line = (LineNumberNode) instruction;
            return "LINE:" + line.line + ':' + labelId(labels, line.start);
        }
        if (instruction instanceof FrameNode) {
            FrameNode frame = (FrameNode) instruction;
            return "FRAME:" + frame.type + ':' + String.valueOf(frame.local) + ':' + String.valueOf(frame.stack);
        }
        String base = instruction.getClass().getSimpleName() + ':' + instruction.getOpcode();
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) instruction;
            return base + ':' + field.owner + '.' + field.name + ':' + field.desc;
        }
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode method = (MethodInsnNode) instruction;
            return base + ':' + method.owner + '.' + method.name + method.desc + ':' + method.itf;
        }
        if (instruction instanceof LdcInsnNode) {
            return base + ':' + String.valueOf(((LdcInsnNode) instruction).cst);
        }
        if (instruction instanceof TypeInsnNode) {
            return base + ':' + ((TypeInsnNode) instruction).desc;
        }
        if (instruction instanceof VarInsnNode) {
            return base + ':' + ((VarInsnNode) instruction).var;
        }
        if (instruction instanceof IntInsnNode) {
            return base + ':' + ((IntInsnNode) instruction).operand;
        }
        if (instruction instanceof IincInsnNode) {
            IincInsnNode increment = (IincInsnNode) instruction;
            return base + ':' + increment.var + ':' + increment.incr;
        }
        if (instruction instanceof JumpInsnNode) {
            return base + ':' + labelId(labels, ((JumpInsnNode) instruction).label);
        }
        if (instruction instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
            return base + ':' + dynamic.name + dynamic.desc + ':' + dynamic.bsm + ':'
                    + Arrays.deepToString(dynamic.bsmArgs);
        }
        if (instruction instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode table = (TableSwitchInsnNode) instruction;
            StringBuilder rendered = new StringBuilder(base).append(':').append(table.min).append(':')
                    .append(table.max).append(':').append(labelId(labels, table.dflt));
            for (LabelNode label : table.labels) {
                rendered.append(':').append(labelId(labels, label));
            }
            return rendered.toString();
        }
        if (instruction instanceof LookupSwitchInsnNode) {
            LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) instruction;
            StringBuilder rendered = new StringBuilder(base).append(':').append(lookup.keys).append(':')
                    .append(labelId(labels, lookup.dflt));
            for (LabelNode label : lookup.labels) {
                rendered.append(':').append(labelId(labels, label));
            }
            return rendered.toString();
        }
        if (instruction instanceof MultiANewArrayInsnNode) {
            MultiANewArrayInsnNode array = (MultiANewArrayInsnNode) instruction;
            return base + ':' + array.desc + ':' + array.dims;
        }
        return base;
    }

    private static int labelId(Map<LabelNode, Integer> labels, LabelNode label) {
        Integer value = labels.get(label);
        if (value == null) {
            throw new IllegalStateException("unmapped ASM label");
        }
        return value;
    }

    private static void writeJar(Path output, TreeMap<String, byte[]> entries) throws IOException {
        try (OutputStream file = Files.newOutputStream(output);
             JarOutputStream jar = new JarOutputStream(file)) {
            jar.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                JarEntry entry = new JarEntry(item.getKey());
                entry.setTimeLocal(FIXED_ENTRY_TIME);
                jar.putNextEntry(entry);
                if (!item.getKey().endsWith("/")) {
                    jar.write(item.getValue());
                }
                jar.closeEntry();
            }
        }
    }

    private static void verifyWrittenJar(
            Path written,
            TreeMap<String, byte[]> expected,
            TreeMap<String, byte[]> original,
            byte[] patchedPluginManager,
            byte[] patchedGroupingCollector,
            byte[] patchedPicoAdapter) throws IOException {
        try (JarFile jar = new JarFile(written.toFile())) {
            int count = 0;
            Enumeration<JarEntry> items = jar.entries();
            while (items.hasMoreElements()) {
                JarEntry entry = items.nextElement();
                count++;
                byte[] actual;
                try (InputStream stream = jar.getInputStream(entry)) {
                    actual = stream.readAllBytes();
                }
                byte[] wanted = expected.get(entry.getName());
                if (wanted == null || !Arrays.equals(wanted, actual)) {
                    throw new IOException("written provider JAR entry mismatch: " + entry.getName());
                }
            }
            if (count != expected.size()) {
                throw new IOException("written provider JAR entry count mismatch expected=" + expected.size()
                        + " actual=" + count);
            }
        }

        for (Map.Entry<String, byte[]> source : original.entrySet()) {
            String name = source.getKey();
            if (isSignatureOrManifest(name) || isMapImplementationClass(name)) {
                continue;
            }
            byte[] wanted = name.equals(PLUGIN_MANAGER + ".class") ? patchedPluginManager :
                    name.equals(GROUPING_COLLECTOR + ".class") ? patchedGroupingCollector :
                    name.equals(PICO_CONSTRUCTOR_ADAPTER + ".class") ? patchedPicoAdapter : source.getValue();
            byte[] actual = expected.get(name);
            if (actual == null || !Arrays.equals(wanted, actual)) {
                throw new IOException("source entry was not preserved as specified: " + name);
            }
        }
    }

    private static boolean isMapImplementationClass(String name) {
        return isClassInFamily(name, INT_MAP) || isClassInFamily(name, LONG_MAP);
    }

    private static boolean isClassInFamily(String name, String family) {
        return name.equals(family + ".class")
                || (name.startsWith(family + "$") && name.endsWith(".class"));
    }

    private static boolean isSignatureOrManifest(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF")) {
            return true;
        }
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String leaf = upper.substring(upper.lastIndexOf('/') + 1);
        return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC") || leaf.endsWith(".SIG");
    }

    private static byte[] requireEntry(TreeMap<String, byte[]> entries, String name) throws IOException {
        byte[] bytes = entries.get(name);
        if (bytes == null) {
            throw new IOException("required provider JAR entry missing: " + name);
        }
        return bytes;
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(label + " is not a regular non-symlink file: " + path);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
