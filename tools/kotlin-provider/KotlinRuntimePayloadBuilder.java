import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds the Android DEX and isolated resource payload from the pinned SC1B1 inputs. */
public final class KotlinRuntimePayloadBuilder {
    private static final String VERSION = "1.9.20-android3";
    private static final String COMPILER_VERSION = "1.9.20";
    private static final int MIN_API = 23;
    private static final String R8_VERSION = "8.2.33";
    private static final int ANDROID_API = 35;
    private static final String ANDROID_JAR_SHA256 =
        "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b";
    private static final long ZIP_EPOCH_MILLIS = 315532800000L;
    private static final String UPSTREAM_COMPILER =
        "third_party/embedded/kotlin/upstream/kotlin-compiler-embeddable-1.9.20.jar";
    private static final String TROVE_INPUT =
        "third_party/embedded/kotlin/upstream/trove4j-1.0.20200330.jar";
    private static final String ANDROID_JAR = "assets/embedded/android-35/android.jar";
    private static final String R8_JAR = "third_party/embedded/r8/r8-8.2.33.jar";
    private static final String DESUGAR_RUNTIME =
        "third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1-android3.jar";
    private static final String NIO_EXPERIMENT_SHA256 =
        "0fd0f9ce4487082e35638e747af4d1602cb6e20c1066d0c2c9f750c3a642a11f";
    private static final String COROUTINES_INPUT =
        "third_party/embedded/kotlin/upstream/kotlinx-coroutines-core-jvm-1.5.0.jar";
    private static final String COROUTINES_SHA256 =
        "78d6cc7135f84d692ff3752fcfd1fa1bbe0940d7df70652e4f1eaeec0c78afbb";
    private static final long COROUTINES_SIZE = 1482528L;
    private static final String COMPILER_INPUT =
        "third_party/embedded/kotlin/kotlin-compiler-embeddable-1.9.20-android3.jar";
    private static final String COMPILER_EXPERIMENT_SHA256 =
        "499fb869c135da5e46e57674eb065dce74ce9ab82c166930a3b1b73989174a4e";
    private static final long COMPILER_EXPERIMENT_SIZE = 60542541L;
    private static final String DESUGAR_CONFIGURATION =
        "assets/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar";
    private static final String CONFIGURATION_ENTRY = "META-INF/desugar/d8/desugar.json";
    private static final String CONFIGURATION_ID =
        "com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1";

    private static final List<String> CHILD_FIRST_PREFIXES;
    private static final List<String> CHILD_FIRST_CLASSES;
    private static final List<InputArtifact> PROGRAM_INPUTS;
    private static final List<ResourceArtifact> RESOURCE_INPUTS;

    static {
        List<String> prefixes = new ArrayList<String>();
        prefixes.add("kotlin.");
        prefixes.add("org.jetbrains.kotlin.");
        prefixes.add("org.jetbrains.org.");
        prefixes.add("gnu.trove.");
        prefixes.add("org.jetbrains.annotations.");
        prefixes.add("org.intellij.lang.annotations.");
        // These additional packages occur in the fixed derived compiler input.
        prefixes.add("javaslang.");
        prefixes.add("org.jetbrains.concurrency.");
        prefixes.add("org.jetbrains.jps.");
        // L8's provider-local core-library runtime must not resolve from Vega's loader.
        prefixes.add("j$.");
        prefixes.add("kotlinx.coroutines.");
        CHILD_FIRST_PREFIXES = Collections.unmodifiableList(prefixes);

        List<String> classes = new ArrayList<String>();
        classes.add("FirNativeForwardDeclarationGetClassCallChecker");
        CHILD_FIRST_CLASSES = Collections.unmodifiableList(classes);

        List<InputArtifact> inputs = new ArrayList<InputArtifact>();
        inputs.add(new InputArtifact(COMPILER_INPUT,
            COMPILER_EXPERIMENT_SHA256, COMPILER_EXPERIMENT_SIZE));
        inputs.add(new InputArtifact("third_party/embedded/kotlin/upstream/kotlin-stdlib-1.9.20.jar",
            "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c", 1718949L));
        inputs.add(new InputArtifact("third_party/embedded/kotlin/upstream/kotlin-reflect-1.6.10.jar",
            "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203", 3038560L));
        inputs.add(new InputArtifact("third_party/embedded/kotlin/upstream/kotlin-script-runtime-1.9.20.jar",
            "a26a6256a76f766ab8bacdb409b3f8c940d999712a8e88864252b678d66bab9e", 43282L));
        inputs.add(new InputArtifact("third_party/embedded/kotlin/upstream/kotlin-daemon-embeddable-1.9.20.jar",
            "a939cb5d6ee2a758c9285bd9f3286824beabe12d9a4b5f49f784d0bca329dea5", 398747L));
        inputs.add(new InputArtifact("third_party/embedded/kotlin/upstream/trove4j-1.0.20200330.jar",
            "c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d", 572985L));
        inputs.add(new InputArtifact("third_party/embedded/kotlin/upstream/annotations-13.0.jar",
            "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478", 17536L));
        inputs.add(new InputArtifact(COROUTINES_INPUT, COROUTINES_SHA256, COROUTINES_SIZE));
        PROGRAM_INPUTS = Collections.unmodifiableList(inputs);

        List<ResourceArtifact> resources = new ArrayList<ResourceArtifact>();
        resources.add(new ResourceArtifact("compiler", inputs.get(0)));
        resources.add(new ResourceArtifact("stdlib", inputs.get(1)));
        resources.add(new ResourceArtifact("reflect", inputs.get(2)));
        resources.add(new ResourceArtifact("script-runtime", inputs.get(3)));
        resources.add(new ResourceArtifact("daemon", inputs.get(4)));
        resources.add(new ResourceArtifact("trove", inputs.get(5)));
        resources.add(new ResourceArtifact("annotations", inputs.get(6)));
        resources.add(new ResourceArtifact("coroutines", inputs.get(7)));
        RESOURCE_INPUTS = Collections.unmodifiableList(resources);
    }

    private KotlinRuntimePayloadBuilder() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: KotlinRuntimePayloadBuilder <repository-root> <fresh-stage-root> [--trove-jar <jar>] [--output-root <embedded-kotlin-root>]");
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        Path stageRoot = Paths.get(args[1]).toAbsolutePath().normalize();
        Path troveOverride = null;
        Path outputRoot = null;
        Path pinsSourceOutput = null;
        Path nioExperiment = null;
        Path coroutinesExperiment = null;
        Path compilerExperiment = null;
        for (int index = 2; index < args.length; index += 2) {
            if (index + 1 >= args.length) throw new IllegalArgumentException("KOTLIN_RUNTIME_OPTION_VALUE_MISSING");
            String option = args[index];
            Path value = Paths.get(args[index + 1]).toAbsolutePath().normalize();
            if ("--trove-jar".equals(option) && troveOverride == null) troveOverride = value;
            else if ("--output-root".equals(option) && outputRoot == null) outputRoot = value;
            else if ("--pins-source-output".equals(option) && pinsSourceOutput == null) pinsSourceOutput = value;
            else if ("--nio-experiment".equals(option) && nioExperiment == null) nioExperiment = value;
            else if ("--coroutines-experiment".equals(option) && coroutinesExperiment == null)
                coroutinesExperiment = value;
            else if ("--compiler-experiment".equals(option) && compilerExperiment == null)
                compilerExperiment = value;
            else throw new IllegalArgumentException("KOTLIN_RUNTIME_OPTION_INVALID: " + option);
        }
        requireDirectory(root, "KOTLIN_RUNTIME_ROOT_INVALID");
        requireDirectory(stageRoot, "KOTLIN_RUNTIME_STAGE_INVALID");
        if (Files.isSymbolicLink(stageRoot) || !stageRoot.toRealPath().equals(stageRoot)) {
            throw new IOException("KOTLIN_RUNTIME_STAGE_LINK");
        }
        try (DirectoryStream<Path> contents = Files.newDirectoryStream(stageRoot)) {
            if (contents.iterator().hasNext()) throw new IOException("KOTLIN_RUNTIME_STAGE_NOT_EMPTY");
        }
        if (troveOverride != null && (!Files.isRegularFile(troveOverride, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(troveOverride) || !troveOverride.toRealPath().equals(troveOverride))) {
            throw new IOException("KOTLIN_RUNTIME_TROVE_OVERRIDE_INVALID");
        }
        if (outputRoot == null) outputRoot = root.resolve("assets/embedded/kotlin");
        if (nioExperiment != null) {
            verifyArtifact(nioExperiment, new InputArtifact("scratch-only patched NIO JAR",
                NIO_EXPERIMENT_SHA256, 0L), "KOTLIN_RUNTIME_NIO_EXPERIMENT_INVALID");
            if (outputRoot.equals(root.resolve("assets/embedded/kotlin")) || pinsSourceOutput == null ||
                pinsSourceOutput.equals(root.resolve("src/dev/wind/agent/build/PackagedKotlinRuntimePins.kt"))) {
                throw new IOException("KOTLIN_RUNTIME_NIO_EXPERIMENT_REQUIRES_SEPARATE_OUTPUTS");
            }
        }
        if (coroutinesExperiment != null) {
            if (!coroutinesExperiment.equals(root.resolve(COROUTINES_INPUT))) {
                throw new IOException("KOTLIN_RUNTIME_COROUTINES_PATH_MISMATCH");
            }
            verifyArtifact(coroutinesExperiment, new InputArtifact(COROUTINES_INPUT,
                COROUTINES_SHA256, COROUTINES_SIZE), "KOTLIN_RUNTIME_COROUTINES_INTEGRITY");
            if (outputRoot.equals(root.resolve("assets/embedded/kotlin")) || pinsSourceOutput == null ||
                pinsSourceOutput.equals(root.resolve("src/dev/wind/agent/build/PackagedKotlinRuntimePins.kt"))) {
                throw new IOException("KOTLIN_RUNTIME_COROUTINES_EXPERIMENT_REQUIRES_SEPARATE_OUTPUTS");
            }
        }
        if (compilerExperiment != null) {
            verifyArtifact(compilerExperiment, new InputArtifact("scratch-only ZipFile adapter",
                COMPILER_EXPERIMENT_SHA256, COMPILER_EXPERIMENT_SIZE),
                "KOTLIN_RUNTIME_COMPILER_EXPERIMENT_INTEGRITY");
            if (outputRoot.equals(root.resolve("assets/embedded/kotlin")) || pinsSourceOutput == null ||
                pinsSourceOutput.equals(root.resolve("src/dev/wind/agent/build/PackagedKotlinRuntimePins.kt"))) {
                throw new IOException("KOTLIN_RUNTIME_COMPILER_EXPERIMENT_REQUIRES_SEPARATE_OUTPUTS");
            }
        }
        if (Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(outputRoot))) {
            throw new IOException("KOTLIN_RUNTIME_OUTPUT_ROOT_INVALID");
        }

        Path work = Files.createDirectory(stageRoot.resolve("work"));
        Path stagedAssets = Files.createDirectory(stageRoot.resolve("payload"));
        List<InputArtifact> programInputs = runtimeProgramInputs(root, troveOverride,
            true, compilerExperiment);
        List<ResourceArtifact> resourceInputs = runtimeResourceInputs(programInputs);
        List<String> childFirstPrefixes = new ArrayList<String>(CHILD_FIRST_PREFIXES);
        verifyInputs(root, programInputs, troveOverride, compilerExperiment);
        auditProviderNamespaces(root, programInputs, troveOverride, compilerExperiment, childFirstPrefixes);
        verifyR8Version(root, work);
        Path config = extractAndVerifyConfiguration(root, work);
        Path d8Output = Files.createDirectory(work.resolve("d8"));
        Path keepRules = work.resolve("l8.pg");
        runD8(root, d8Output, keepRules, config, programInputs, troveOverride, compilerExperiment);
        if (!Files.isRegularFile(keepRules, LinkOption.NOFOLLOW_LINKS) || Files.size(keepRules) == 0L) {
            throw new IOException("KOTLIN_RUNTIME_D8_KEEP_RULES_MISSING");
        }
        Path l8Zip = work.resolve("l8.zip");
        runL8(root, keepRules, l8Zip, config,
            nioExperiment == null ? root.resolve(DESUGAR_RUNTIME) : nioExperiment);
        Path l8Dex = extractL8Dex(l8Zip, work.resolve("l8-dex"));

        Path runtimeDir = Files.createDirectories(stagedAssets.resolve("runtime"));
        Path resourcesDir = Files.createDirectories(stagedAssets.resolve("resources"));
        Path classpathDir = Files.createDirectories(stagedAssets.resolve("classpath"));
        Path dexContainer = runtimeDir.resolve("kotlin-provider-runtime-" + VERSION + ".jar");
        List<DexFileInfo> dexFiles = combineDex(d8Output, l8Dex, dexContainer,
            programInputs, childFirstPrefixes);
        List<ArtifactInfo> outputs = new ArrayList<ArtifactInfo>();
        outputs.add(artifactInfo("runtime/kotlin-provider-runtime-" + VERSION + ".jar", dexContainer));

        List<ArtifactInfo> resourceOutputs = new ArrayList<ArtifactInfo>();
        for (ResourceArtifact resource : resourceInputs) {
            Path target = resourcesDir.resolve(resource.name + "-resources.jar");
            Path resourceInput = resolveInput(root, resource.input, troveOverride, compilerExperiment);
            buildResourceJar(resourceInput, target);
            verifyResourceJar(resourceInput, target);
            ArtifactInfo info = artifactInfo("resources/" + target.getFileName().toString(), target);
            outputs.add(info);
            resourceOutputs.add(info);
        }

        Path stdlib = resolveInput(root, programInputs.get(1), troveOverride, compilerExperiment);
        Path stdlibCopy = classpathDir.resolve("kotlin-stdlib-1.9.20.jar");
        Files.copy(stdlib, stdlibCopy);
        verifyArtifact(stdlibCopy, programInputs.get(1), "KOTLIN_RUNTIME_STDLIB_COPY_INVALID");
        ArtifactInfo stdlibInfo = artifactInfo("classpath/kotlin-stdlib-1.9.20.jar", stdlibCopy);
        outputs.add(stdlibInfo);

        String manifest = buildOuterManifest(outputs, resourceOutputs, stdlibInfo, dexFiles,
            sha256(keepRules), root, programInputs,
            nioExperiment == null ? root.resolve(DESUGAR_RUNTIME) : nioExperiment,
            childFirstPrefixes);
        Path manifestPath = stagedAssets.resolve("manifest.json");
        Files.write(manifestPath, manifest.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        ArtifactInfo manifestInfo = artifactInfo("manifest.json", manifestPath);
        outputs.add(manifestInfo);
        verifyPinnedOutputs(outputs, troveOverride != null || nioExperiment != null || coroutinesExperiment != null ||
            compilerExperiment != null, true);
        Path generatedPins = writeRuntimePinsSource(stageRoot, outputs, programInputs, dexFiles.size(),
            sha256(nioExperiment == null ? root.resolve(DESUGAR_RUNTIME) : nioExperiment), childFirstPrefixes);
        if (troveOverride == null && nioExperiment == null && coroutinesExperiment == null && compilerExperiment == null)
            verifyDefaultRuntimePins(root, generatedPins);
        if (pinsSourceOutput != null) generatedPins = publishRuntimePinsSource(generatedPins, pinsSourceOutput);
        publishAssets(stagedAssets, outputRoot, outputs);

        System.out.println("KOTLIN_RUNTIME_PROVIDER=" + VERSION);
        System.out.println("KOTLIN_RUNTIME_TROVE_SHA256=" + programInputs.get(5).sha256);
        System.out.println("KOTLIN_RUNTIME_PINS_SOURCE=" + generatedPins);
        System.out.println("KOTLIN_RUNTIME_D8_DEX_COUNT=" + countDex(d8Output));
        System.out.println("KOTLIN_RUNTIME_L8_DEX_COUNT=" + (dexFiles.size() - countDex(d8Output)));
        for (ArtifactInfo output : outputs) {
            System.out.println("KOTLIN_RUNTIME_OUTPUT=" + output.relativePath + " " +
                output.sizeBytes + " " + output.sha256);
        }
        System.out.println("KOTLIN_RUNTIME_CHILD_FIRST_PREFIXES=" + String.join(",", childFirstPrefixes));
        System.out.println("KOTLIN_RUNTIME_CHILD_FIRST_CLASSES=" + String.join(",", CHILD_FIRST_CLASSES));
    }

    private static void verifyInputs(Path root, List<InputArtifact> programInputs,
                                     Path troveOverride, Path compilerExperiment) throws Exception {
        for (InputArtifact input : programInputs) verifyArtifact(resolveInput(root, input,
            troveOverride, compilerExperiment), input,
            "KOTLIN_RUNTIME_INPUT_INTEGRITY");
        verifyArtifact(root.resolve(ANDROID_JAR), new InputArtifact(ANDROID_JAR,
            "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b", 27092450L),
            "KOTLIN_RUNTIME_ANDROID_JAR_INTEGRITY");
        verifyArtifact(root.resolve(R8_JAR), new InputArtifact(R8_JAR,
            "80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3", 0L),
            "KOTLIN_RUNTIME_R8_INTEGRITY");
        verifyArtifact(root.resolve(DESUGAR_RUNTIME), new InputArtifact(DESUGAR_RUNTIME,
            "0fd0f9ce4487082e35638e747af4d1602cb6e20c1066d0c2c9f750c3a642a11f", 2965852L),
            "KOTLIN_RUNTIME_DESUGAR_INTEGRITY");
        verifyArtifact(root.resolve(DESUGAR_CONFIGURATION), new InputArtifact(DESUGAR_CONFIGURATION,
            "1dd26f9e2440904eab5014a244f500d8b453f3629bfd5478a3f9895838e5c35a", 1097805L),
            "KOTLIN_RUNTIME_CONFIGURATION_INTEGRITY");
        Path forbidden = root.resolve(UPSTREAM_COMPILER).toRealPath();
        for (InputArtifact input : programInputs) {
            if (resolveInput(root, input, troveOverride, compilerExperiment).toRealPath().equals(forbidden)) {
                throw new IOException("KOTLIN_RUNTIME_UPSTREAM_COMPILER_FORBIDDEN");
            }
        }
    }

    private static void auditProviderNamespaces(Path root, List<InputArtifact> programInputs,
                                                 Path troveOverride, Path compilerExperiment,
                                                 List<String> prefixes) throws Exception {
        for (InputArtifact input : programInputs) {
            try (ZipFile archive = new ZipFile(resolveInput(root, input,
                troveOverride, compilerExperiment).toFile())) {
                Enumeration<? extends ZipEntry> entries = archive.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")) continue;
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    if (className.equals("module-info")) continue;
                    if (className.indexOf('.') < 0) {
                        if (!CHILD_FIRST_CLASSES.contains(className)) {
                            throw new IOException("KOTLIN_RUNTIME_UNOWNED_DEFAULT_PACKAGE_CLASS: " + className);
                        }
                        continue;
                    }
                    boolean owned = false;
                    for (String prefix : prefixes) owned |= className.startsWith(prefix);
                    if (!owned) throw new IOException("KOTLIN_RUNTIME_UNOWNED_NAMESPACE: " + className);
                }
            }
        }
    }

    private static Path extractAndVerifyConfiguration(Path root, Path work) throws Exception {
        Path archivePath = root.resolve(DESUGAR_CONFIGURATION);
        Path output = work.resolve("desugar.json");
        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            ZipEntry entry = archive.getEntry(CONFIGURATION_ENTRY);
            if (entry == null || entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > 2L * 1024L * 1024L) {
                throw new IOException("KOTLIN_RUNTIME_CONFIGURATION_JSON_INVALID");
            }
            try (InputStream input = archive.getInputStream(entry);
                 OutputStream target = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                copy(input, target);
            }
        }
        byte[] bytes = Files.readAllBytes(output);
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (!json.contains("\"identifier\":\"" + CONFIGURATION_ID + "\"") ||
            !json.contains("\"configuration_format_version\":200") ||
            !json.contains("\"synthesized_library_classes_package_prefix\":\"j$/\"")) {
            throw new IOException("KOTLIN_RUNTIME_CONFIGURATION_CONTRACT_INVALID");
        }
        return output;
    }

    private static void runD8(Path root, Path output, Path keepRules, Path config,
                              List<InputArtifact> programInputs, Path troveOverride,
                              Path compilerExperiment) throws Exception {
        List<String> command = javaCommand(root.resolve(R8_JAR), "com.android.tools.r8.D8");
        Collections.addAll(command, "--release", "--min-api", String.valueOf(MIN_API),
            "--lib", root.resolve(ANDROID_JAR).toString(), "--desugared-lib", config.toString(),
            "--desugared-lib-pg-conf-output", keepRules.toString(), "--output", output.toString());
        for (InputArtifact input : programInputs) command.add(resolveInput(root, input,
            troveOverride, compilerExperiment).toString());
        run(command, "KOTLIN_RUNTIME_D8_FAILED", output.getParent().resolve("d8.log"));
        List<Path> dex = collectDexFiles(output);
        if (dex.isEmpty()) throw new IOException("KOTLIN_RUNTIME_D8_NO_DEX");
        for (Path path : dex) verifyDex(path);
    }

    private static void runL8(Path root, Path keepRules, Path output, Path config,
                              Path desugarRuntime) throws Exception {
        List<String> command = javaCommand(root.resolve(R8_JAR), "com.android.tools.r8.L8");
        Collections.addAll(command, "--release", "--min-api", String.valueOf(MIN_API),
            "--lib", root.resolve(ANDROID_JAR).toString(), "--desugared-lib", config.toString(),
            "--pg-conf", keepRules.toString(), "--output", output.toString(),
            desugarRuntime.toString(), root.resolve(DESUGAR_CONFIGURATION).toString());
        run(command, "KOTLIN_RUNTIME_L8_FAILED", output.resolveSibling("l8.log"));
    }

    private static List<String> javaCommand(Path r8Jar, String entryPoint) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path java = Paths.get(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        List<String> command = new ArrayList<String>();
        command.add(java.toString());
        command.add("-Xmx4g");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(r8Jar.toString());
        command.add(entryPoint);
        return command;
    }

    private static void verifyR8Version(Path root, Path work) throws Exception {
        List<String> command = javaCommand(root.resolve(R8_JAR), "com.android.tools.r8.D8");
        command.add("--version");
        Path output = work.resolve("r8-version.txt");
        int exit = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start().waitFor();
        String version = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        if (exit != 0 || !version.matches("(?s).*\\b" + R8_VERSION.replace(".", "\\.") + "\\b.*")) {
            throw new IOException("KOTLIN_RUNTIME_R8_VERSION_INVALID: " + version.trim());
        }
        System.out.println("KOTLIN_RUNTIME_R8_VERSION=" + version.trim());
    }

    private static void run(List<String> command, String errorCode, Path log) throws Exception {
        int exit = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start().waitFor();
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        long warnings = lines.stream().filter(line -> line.startsWith("Warning ")).count();
        System.out.println(errorCode.replace("_FAILED", "") + "_WARNING_COUNT=" + warnings);
        if (warnings > 0) {
            lines.stream().filter(line -> line.startsWith("Warning ")).limit(5)
                .forEach(line -> System.out.println("KOTLIN_RUNTIME_D8_WARNING=" + line));
        }
        if (exit != 0) {
            int start = Math.max(0, lines.size() - 40);
            for (int i = start; i < lines.size(); i++) System.err.println(lines.get(i));
            throw new IOException(errorCode + ": exit=" + exit + ", log=" + log);
        }
    }

    private static Path extractL8Dex(Path l8Zip, Path outputDirectory) throws Exception {
        if (!Files.isRegularFile(l8Zip, LinkOption.NOFOLLOW_LINKS)) throw new IOException("KOTLIN_RUNTIME_L8_OUTPUT_MISSING");
        Files.createDirectory(outputDirectory);
        List<ZipEntry> entries = new ArrayList<ZipEntry>();
        try (ZipFile archive = new ZipFile(l8Zip.toFile())) {
            Enumeration<? extends ZipEntry> all = archive.entries();
            Set<String> seen = new HashSet<String>();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (!seen.add(entry.getName())) throw new IOException("KOTLIN_RUNTIME_L8_DUPLICATE_ENTRY");
                if (entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) entries.add(entry);
            }
            entries.sort(Comparator.comparingInt(e -> dexIndex(e.getName())));
            if (entries.isEmpty() || dexIndex(entries.get(0).getName()) != 1) {
                throw new IOException("KOTLIN_RUNTIME_L8_DEX_MISSING");
            }
            for (int i = 0; i < entries.size(); i++) {
                ZipEntry entry = entries.get(i);
                if (dexIndex(entry.getName()) != i + 1) throw new IOException("KOTLIN_RUNTIME_L8_DEX_GAP");
                Path target = outputDirectory.resolve(entry.getName());
                try (InputStream input = archive.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    copy(input, output);
                }
                verifyDex(target);
            }
        }
        return outputDirectory;
    }

    private static List<DexFileInfo> combineDex(Path d8Directory, Path l8Directory, Path output,
                                                List<InputArtifact> programInputs,
                                                List<String> prefixes) throws Exception {
        List<Path> d8Files = collectDexFiles(d8Directory);
        List<Path> l8Files = collectDexFiles(l8Directory);
        if (d8Files.isEmpty() || l8Files.isEmpty()) throw new IOException("KOTLIN_RUNTIME_DEX_INPUT_MISSING");
        List<DexFileInfo> infos = new ArrayList<DexFileInfo>();
        try (ZipOutputStream archive = new ZipOutputStream(new BufferedOutputStream(
            Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)))) {
            archive.setLevel(9);
            int index = 1;
            for (Path dex : d8Files) infos.add(addDex(archive, dex, index++));
            for (Path dex : l8Files) infos.add(addDex(archive, dex, index++));
            byte[] metadata = buildInternalManifest(infos, programInputs, prefixes).getBytes(StandardCharsets.UTF_8);
            ZipEntry meta = new ZipEntry("META-INF/vega-kotlin-provider.json");
            meta.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
            archive.putNextEntry(meta);
            archive.write(metadata);
            archive.closeEntry();
        }
        verifyDexContainer(output, infos);
        return infos;
    }

    private static void verifyDexContainer(Path container, List<DexFileInfo> expected) throws Exception {
        try (ZipFile archive = new ZipFile(container.toFile())) {
            Set<String> names = new HashSet<String>();
            Enumeration<? extends ZipEntry> entries = archive.entries();
            int dexCount = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name) || name.endsWith(".class")) {
                    throw new IOException("KOTLIN_RUNTIME_CONTAINER_ENTRY_INVALID: " + name);
                }
                if (name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) dexCount++;
                else if (!"META-INF/vega-kotlin-provider.json".equals(name)) {
                    throw new IOException("KOTLIN_RUNTIME_CONTAINER_UNEXPECTED_ENTRY: " + name);
                }
            }
            if (dexCount != expected.size() || names.size() != expected.size() + 1) {
                throw new IOException("KOTLIN_RUNTIME_CONTAINER_DEX_COUNT_INVALID");
            }
            for (DexFileInfo dex : expected) {
                ZipEntry entry = archive.getEntry(dex.name);
                if (entry == null || entry.getSize() != dex.sizeBytes ||
                    !sha256(archive.getInputStream(entry)).equals(dex.sha256)) {
                    throw new IOException("KOTLIN_RUNTIME_CONTAINER_DEX_INVALID: " + dex.name);
                }
            }
        }
    }

    private static DexFileInfo addDex(ZipOutputStream archive, Path source, int index) throws Exception {
        verifyDex(source);
        String name = index == 1 ? "classes.dex" : "classes" + index + ".dex";
        long size = Files.size(source);
        CRC32 crc = new CRC32();
        try (InputStream input = Files.newInputStream(source)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) crc.update(buffer, 0, count);
        }
        String sha = sha256(source);
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        archive.putNextEntry(entry);
        try (InputStream input = Files.newInputStream(source)) { copy(input, archive); }
        archive.closeEntry();
        return new DexFileInfo(name, size, sha);
    }

    private static String buildInternalManifest(List<DexFileInfo> dexFiles,
                                                List<InputArtifact> programInputs,
                                                List<String> prefixes) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n  \"schemaVersion\":1,\n  \"providerVersion\":").append(quote(VERSION));
        json.append(",\n  \"compilerVersion\":").append(quote(COMPILER_VERSION));
        json.append(",\n  \"r8Version\":").append(quote(R8_VERSION));
        json.append(",\n  \"builderJdkMajor\":17");
        json.append(",\n  \"androidApi\":").append(ANDROID_API);
        json.append(",\n  \"minApi\":").append(MIN_API).append(",\n  \"runtimeDexCount\":").append(dexFiles.size());
        json.append(",\n  \"childFirstPrefixes\":").append(stringArray(prefixes));
        json.append(",\n  \"childFirstClasses\":").append(stringArray(CHILD_FIRST_CLASSES));
        json.append(",\n  \"dexEntries\":[\n");
        for (int i = 0; i < dexFiles.size(); i++) {
            DexFileInfo dex = dexFiles.get(i);
            json.append("    {\"name\":").append(quote(dex.name)).append(",\"sizeBytes\":")
                .append(dex.sizeBytes).append(",\"sha256\":").append(quote(dex.sha256)).append("}");
            json.append(i + 1 == dexFiles.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"programInputs\":[\n");
        for (int i = 0; i < programInputs.size(); i++) {
            InputArtifact input = programInputs.get(i);
            json.append("    {\"path\":").append(quote(input.path)).append(",\"sha256\":")
                .append(quote(input.sha256)).append("}");
            json.append(i + 1 == programInputs.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static String buildOuterManifest(List<ArtifactInfo> outputs, List<ArtifactInfo> resources,
                                             ArtifactInfo stdlib, List<DexFileInfo> dexFiles,
                                             String keepRulesSha, Path root,
                                             List<InputArtifact> programInputs,
                                             Path desugarRuntimePath,
                                             List<String> prefixes) throws Exception {
        ArtifactInfo runtime = outputs.get(0);
        ArtifactInfo desugarRuntime = artifactInfo(DESUGAR_RUNTIME, desugarRuntimePath);
        ArtifactInfo desugarConfig = artifactInfo(DESUGAR_CONFIGURATION, root.resolve(DESUGAR_CONFIGURATION));
        StringBuilder json = new StringBuilder(8192);
        json.append("{\n  \"schemaVersion\":1,\n  \"providerVersion\":").append(quote(VERSION));
        json.append(",\n  \"compilerVersion\":").append(quote(COMPILER_VERSION));
        json.append(",\n  \"r8Version\":").append(quote(R8_VERSION));
        json.append(",\n  \"builderJdkMajor\":17");
        json.append(",\n  \"patchBaseCompilerSha256\":")
            .append(quote("a25024fe5da8440de01af045c4fcb954a22f078738ec02616085f0cfc57b2702"));
        json.append(",\n  \"androidPlatform\":{\"api\":").append(ANDROID_API)
            .append(",\"androidJarSha256\":").append(quote(ANDROID_JAR_SHA256)).append("}");
        json.append(",\n  \"minApi\":").append(MIN_API).append(",\n  \"runtimeDexCount\":").append(dexFiles.size());
        json.append(",\n  \"runtimeDexContainer\":").append(artifactJson(runtime));
        json.append(",\n  \"dexEntries\":[\n");
        for (int i = 0; i < dexFiles.size(); i++) {
            DexFileInfo dex = dexFiles.get(i);
            json.append("    {\"name\":").append(quote(dex.name)).append(",\"sizeBytes\":")
                .append(dex.sizeBytes).append(",\"sha256\":").append(quote(dex.sha256)).append("}");
            json.append(i + 1 == dexFiles.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"resourceJars\":[\n");
        for (int i = 0; i < resources.size(); i++) {
            json.append("    ").append(artifactJson(resources.get(i)));
            json.append(i + 1 == resources.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"stdlibClasspath\":").append(artifactJson(stdlib));
        json.append(",\n  \"desugaring\":{\"runtimeSha256\":").append(quote(desugarRuntime.sha256));
        json.append(",\"configurationSha256\":").append(quote(desugarConfig.sha256));
        json.append(",\"d8KeepRulesSha256\":").append(quote(keepRulesSha)).append("}");
        json.append(",\n  \"programInputs\":[\n");
        for (int i = 0; i < programInputs.size(); i++) {
            InputArtifact input = programInputs.get(i);
            json.append("    {\"path\":").append(quote(input.path)).append(",\"sha256\":")
                .append(quote(input.sha256)).append("}");
            json.append(i + 1 == programInputs.size() ? "\n" : ",\n");
        }
        json.append("  ],\n  \"childFirstPrefixes\":").append(stringArray(prefixes));
        json.append(",\n  \"childFirstClasses\":").append(stringArray(CHILD_FIRST_CLASSES)).append("\n}\n");
        return json.toString();
    }

    private static String artifactJson(ArtifactInfo info) {
        return "{\"asset\":" + quote("embedded/kotlin/" + info.relativePath) +
            ",\"sizeBytes\":" + info.sizeBytes + ",\"sha256\":" + quote(info.sha256) + "}";
    }

    private static void buildResourceJar(Path source, Path output) throws Exception {
        TreeMap<String, ZipEntry> entries = new TreeMap<String, ZipEntry>();
        try (ZipFile archive = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> all = archive.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                String name = entry.getName();
                validateEntryName(name);
                if (name.endsWith(".class") || isSignatureFile(name)) continue;
                if (entries.put(name, entry) != null) throw new IOException("KOTLIN_RESOURCE_DUPLICATE_ENTRY: " + name);
            }
            try (ZipOutputStream target = new ZipOutputStream(new BufferedOutputStream(
                Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)))) {
                target.setLevel(9);
                for (Map.Entry<String, ZipEntry> resource : entries.entrySet()) {
                    ZipEntry sourceEntry = resource.getValue();
                    ZipEntry entry = new ZipEntry(resource.getKey());
                    entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0));
                    target.putNextEntry(entry);
                    if (!sourceEntry.isDirectory()) {
                        try (InputStream input = archive.getInputStream(sourceEntry)) { copy(input, target); }
                    }
                    target.closeEntry();
                }
            }
        }
    }

    private static void verifyResourceJar(Path source, Path output) throws Exception {
        try (ZipFile original = new ZipFile(source.toFile()); ZipFile filtered = new ZipFile(output.toFile())) {
            Map<String, ZipEntry> expected = new TreeMap<String, ZipEntry>();
            Enumeration<? extends ZipEntry> sourceEntries = original.entries();
            while (sourceEntries.hasMoreElements()) {
                ZipEntry entry = sourceEntries.nextElement();
                if (entry.getName().endsWith(".class") || isSignatureFile(entry.getName())) continue;
                expected.put(entry.getName(), entry);
            }
            Map<String, ZipEntry> actual = new TreeMap<String, ZipEntry>();
            Enumeration<? extends ZipEntry> outputEntries = filtered.entries();
            while (outputEntries.hasMoreElements()) {
                ZipEntry entry = outputEntries.nextElement();
                if (entry.getName().endsWith(".class") || isSignatureFile(entry.getName()) ||
                    actual.put(entry.getName(), entry) != null) {
                    throw new IOException("KOTLIN_RESOURCE_OUTPUT_ENTRY_INVALID: " + entry.getName());
                }
            }
            if (!expected.keySet().equals(actual.keySet())) throw new IOException("KOTLIN_RESOURCE_OUTPUT_NAMES_CHANGED");
            for (String name : expected.keySet()) {
                ZipEntry sourceEntry = expected.get(name);
                ZipEntry outputEntry = actual.get(name);
                if (sourceEntry.isDirectory() != outputEntry.isDirectory() ||
                    !sha256(original.getInputStream(sourceEntry)).equals(sha256(filtered.getInputStream(outputEntry)))) {
                    throw new IOException("KOTLIN_RESOURCE_OUTPUT_PAYLOAD_CHANGED: " + name);
                }
            }
        }
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String tail = upper.substring("META-INF/".length());
        if (tail.indexOf('/') >= 0) return false;
        return tail.endsWith(".SF") || tail.endsWith(".RSA") || tail.endsWith(".DSA") ||
            tail.endsWith(".EC") || tail.endsWith(".SIG");
    }

    private static void validateEntryName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") ||
            name.matches("^[A-Za-z]:.*") || name.indexOf('\\') >= 0) {
            throw new IOException("KOTLIN_RESOURCE_ENTRY_UNSAFE: " + name);
        }
        for (String segment : name.split("/")) if (segment.equals("..")) {
            throw new IOException("KOTLIN_RESOURCE_ENTRY_UNSAFE: " + name);
        }
    }

    private static List<Path> collectDexFiles(Path directory) throws Exception {
        List<Path> result = new ArrayList<Path>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) ||
                    !entry.getFileName().toString().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) {
                    throw new IOException("KOTLIN_RUNTIME_UNEXPECTED_DEX_OUTPUT: " + entry.getFileName());
                }
                result.add(entry);
            }
        }
        result.sort(Comparator.comparingInt(path -> dexIndex(path.getFileName().toString())));
        for (int i = 0; i < result.size(); i++) {
            if (dexIndex(result.get(i).getFileName().toString()) != i + 1) {
                throw new IOException("KOTLIN_RUNTIME_DEX_INDEX_GAP");
            }
        }
        return result;
    }

    private static int countDex(Path directory) throws Exception { return collectDexFiles(directory).size(); }

    private static int dexIndex(String name) {
        if ("classes.dex".equals(name)) return 1;
        if (!name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) return -1;
        return Integer.parseInt(name.substring("classes".length(), name.length() - ".dex".length()));
    }

    private static void verifyDex(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) < 112L) {
            throw new IOException("KOTLIN_RUNTIME_DEX_INVALID: " + path.getFileName());
        }
        byte[] header = new byte[8];
        try (InputStream input = Files.newInputStream(path)) {
            if (input.read(header) != header.length || header[0] != 'd' || header[1] != 'e' ||
                header[2] != 'x' || header[3] != '\n' || header[7] != 0) {
                throw new IOException("KOTLIN_RUNTIME_DEX_MAGIC_INVALID: " + path.getFileName());
            }
        }
    }

    private static ArtifactInfo artifactInfo(String relativePath, Path path) throws Exception {
        return new ArtifactInfo(relativePath, Files.size(path), sha256(path));
    }

    private static void verifyPinnedOutputs(List<ArtifactInfo> outputs, boolean experimentalRuntime,
                                            boolean coroutinesExperiment) throws Exception {
        Map<String, ArtifactPin> expected = new LinkedHashMap<String, ArtifactPin>();
        expected.put("runtime/kotlin-provider-runtime-1.9.20-android3.jar",
            new ArtifactPin(52022626L, "47ff6b4532d3c11d29180dcc97e47a0ff9cb0ef4c444f8c241a057534926900a"));
        expected.put("resources/compiler-resources.jar",
            new ArtifactPin(930944L, "875f0fc72a93d0a5b447ffae5493398032495cf11872b78780be68438055f669"));
        expected.put("resources/stdlib-resources.jar",
            new ArtifactPin(22883L, "85055839229e0682ae4031b131b0ff1131c249c8d48affd12b9a88ef26d357f5"));
        expected.put("resources/reflect-resources.jar",
            new ArtifactPin(26666L, "7703b40a9d3ee256516abadbf5d6c4dde061ff491a7276bcfa34d9d7610feb16"));
        expected.put("resources/script-runtime-resources.jar",
            new ArtifactPin(2235L, "9a8a58469cc6e5c371ea35e3d5bb5fb5b2c661447a836712b1b40e72fcca907d"));
        expected.put("resources/daemon-resources.jar",
            new ArtifactPin(2050L, "92af105e650b00ca5d93d307ec070ab1df5b3e9f79ac5d25ba6301c7c34dfc18"));
        expected.put("resources/trove-resources.jar",
            new ArtifactPin(733L, "4531cfa003aa0090bf7574e682930bb39ed9c2138aecff2d21facc7a158c7573"));
        expected.put("resources/annotations-resources.jar",
            new ArtifactPin(3471L, "bba68f4bf563ca3293086f637e2281a25d2db48c152500ddaa5d713baa15dd25"));
        expected.put("classpath/kotlin-stdlib-1.9.20.jar",
            new ArtifactPin(1718949L, "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c"));
        expected.put("manifest.json",
            new ArtifactPin(4867L, "3d20ba3e83f04c0c81d38bc33124f81083278535d91266b89fa9b675954d5222"));
        expected.put("resources/coroutines-resources.jar",
            new ArtifactPin(5412L, "3a9bc24d70e28c76b0759f1e59f91efb6595fe71d1c316d6b46e4d42bc9a050f"));
        if (outputs.size() != expected.size()) throw new IOException("KOTLIN_RUNTIME_OUTPUT_SET_INVALID");
        for (ArtifactInfo output : outputs) {
            ArtifactPin pin = expected.get(output.relativePath);
            boolean runtimeDependent = "runtime/kotlin-provider-runtime-1.9.20-android3.jar".equals(output.relativePath) ||
                "resources/trove-resources.jar".equals(output.relativePath) || "manifest.json".equals(output.relativePath) ||
                coroutinesExperiment && "resources/coroutines-resources.jar".equals(output.relativePath);
            if (pin == null || (!experimentalRuntime || !runtimeDependent) &&
                (pin.sizeBytes != output.sizeBytes || !pin.sha256.equals(output.sha256))) {
                throw new IOException("KOTLIN_RUNTIME_OUTPUT_PIN_MISMATCH: " + output.relativePath +
                    " size=" + output.sizeBytes + " sha256=" + output.sha256);
            }
        }
    }

    private static List<InputArtifact> runtimeProgramInputs(Path root, Path troveOverride,
                                                             boolean coroutinesExperiment,
                                                             Path compilerExperiment) throws Exception {
        List<InputArtifact> inputs = new ArrayList<InputArtifact>(PROGRAM_INPUTS);
        if (compilerExperiment != null) inputs.set(0, new InputArtifact(COMPILER_INPUT,
            COMPILER_EXPERIMENT_SHA256, COMPILER_EXPERIMENT_SIZE));
        if (troveOverride != null) {
            int troveIndex = -1;
            for (int index = 0; index < inputs.size(); index++) {
                if (TROVE_INPUT.equals(inputs.get(index).path)) troveIndex = index;
            }
            if (troveIndex < 0) throw new IOException("KOTLIN_RUNTIME_TROVE_INPUT_NOT_PINNED");
            inputs.set(troveIndex, new InputArtifact(TROVE_INPUT, sha256(troveOverride), Files.size(troveOverride)));
        }
        return Collections.unmodifiableList(inputs);
    }

    private static List<ResourceArtifact> runtimeResourceInputs(List<InputArtifact> programInputs) {
        List<ResourceArtifact> resources = new ArrayList<ResourceArtifact>();
        resources.add(new ResourceArtifact("compiler", programInputs.get(0)));
        resources.add(new ResourceArtifact("stdlib", programInputs.get(1)));
        resources.add(new ResourceArtifact("reflect", programInputs.get(2)));
        resources.add(new ResourceArtifact("script-runtime", programInputs.get(3)));
        resources.add(new ResourceArtifact("daemon", programInputs.get(4)));
        resources.add(new ResourceArtifact("trove", programInputs.get(5)));
        resources.add(new ResourceArtifact("annotations", programInputs.get(6)));
        if (programInputs.size() == 8) resources.add(new ResourceArtifact("coroutines", programInputs.get(7)));
        return Collections.unmodifiableList(resources);
    }

    private static Path resolveInput(Path root, InputArtifact input, Path troveOverride,
                                     Path compilerExperiment) {
        if (troveOverride != null && TROVE_INPUT.equals(input.path)) return troveOverride;
        if (compilerExperiment != null && COMPILER_INPUT.equals(input.path)) return compilerExperiment;
        return root.resolve(input.path);
    }

    private static Path writeRuntimePinsSource(Path stageRoot, List<ArtifactInfo> outputs,
                                               List<InputArtifact> programInputs,
                                               int runtimeDexCount, String desugarRuntimeSha256,
                                               List<String> prefixes) throws Exception {
        ArtifactInfo runtime = findOutput(outputs, "runtime/kotlin-provider-runtime-1.9.20-android3.jar");
        ArtifactInfo manifest = findOutput(outputs, "manifest.json");
        ArtifactInfo stdlib = findOutput(outputs, "classpath/kotlin-stdlib-1.9.20.jar");
        List<ArtifactInfo> resources = new ArrayList<ArtifactInfo>();
        for (ArtifactInfo output : outputs) if (output.relativePath.startsWith("resources/")) resources.add(output);

        StringBuilder source = new StringBuilder(8192);
        source.append("package dev.wind.agent.build\n\n");
        source.append("/** Generated together with the Kotlin runtime payload; do not edit by hand. */\n");
        source.append("internal data class KotlinRuntimeAssetPin(\n");
        source.append("    val assetPath: String,\n    val directory: String,\n    val fileName: String,\n");
        source.append("    val sizeBytes: Long,\n    val sha256: String\n)\n\n");
        source.append("internal object PackagedKotlinRuntimePins {\n");
        source.append("    const val PROVIDER_VERSION = \"").append(VERSION).append("\"\n");
        source.append("    const val MANIFEST_SIZE_BYTES = ").append(manifest.sizeBytes).append("\n");
        source.append("    const val MANIFEST_SHA256 = \"").append(manifest.sha256).append("\"\n");
        source.append("    const val DESUGAR_RUNTIME_SHA256 = \"")
            .append(desugarRuntimeSha256).append("\"\n");
        source.append("    const val RUNTIME_DEX_COUNT = ").append(runtimeDexCount).append("\n\n");
        source.append("    val RUNTIME_ARTIFACT = ");
        appendPin(source, runtime);
        source.append("\n    val RESOURCE_ARTIFACTS = listOf(\n");
        for (int index = 0; index < resources.size(); index++) {
            source.append("        ");
            appendPin(source, resources.get(index));
            source.append(index + 1 == resources.size() ? "\n" : ",\n");
        }
        source.append("    )\n    val STDLIB_ARTIFACT = ");
        appendPin(source, stdlib);
        source.append("\n\n    val RUNTIME_PROGRAM_INPUTS = listOf(\n");
        for (int index = 0; index < programInputs.size(); index++) {
            InputArtifact input = programInputs.get(index);
            String prefix = "third_party/embedded/kotlin/";
            if (!input.path.startsWith(prefix)) throw new IOException("KOTLIN_RUNTIME_PROGRAM_INPUT_PATH_INVALID");
            String relativePath = input.path.substring(prefix.length());
            int slash = relativePath.lastIndexOf('/');
            String fileName = slash < 0 ? relativePath : relativePath.substring(slash + 1);
            source.append("        KotlinProviderArtifact(\"").append(fileName).append("\", \"")
                .append(relativePath).append("\", \"").append(input.sha256).append("\", ")
                .append(input.sizeBytes).append("L)");
            source.append(index + 1 == programInputs.size() ? "\n" : ",\n");
        }
        source.append("    )\n}\n");

        Path output = stageRoot.resolve("generated-src/dev/wind/agent/build/PackagedKotlinRuntimePins.kt");
        Files.createDirectories(output.getParent());
        Files.write(output, source.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        return output;
    }

    private static ArtifactInfo findOutput(List<ArtifactInfo> outputs, String relativePath) throws IOException {
        for (ArtifactInfo output : outputs) if (relativePath.equals(output.relativePath)) return output;
        throw new IOException("KOTLIN_RUNTIME_GENERATED_PIN_MISSING: " + relativePath);
    }

    private static void appendPin(StringBuilder source, ArtifactInfo artifact) {
        int slash = artifact.relativePath.lastIndexOf('/');
        String directory = slash < 0 ? "" : artifact.relativePath.substring(0, slash);
        String fileName = slash < 0 ? artifact.relativePath : artifact.relativePath.substring(slash + 1);
        source.append("KotlinRuntimeAssetPin(\"embedded/kotlin/").append(artifact.relativePath)
            .append("\", \"").append(directory.substring(directory.lastIndexOf('/') + 1))
            .append("\", \"").append(fileName).append("\", ")
            .append(artifact.sizeBytes).append("L, \"").append(artifact.sha256).append("\")");
    }

    private static void verifyDefaultRuntimePins(Path root, Path generatedPins) throws Exception {
        Path checkedIn = root.resolve("src/dev/wind/agent/build/PackagedKotlinRuntimePins.kt");
        if (!Files.isRegularFile(checkedIn, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(checkedIn) ||
            !MessageDigest.isEqual(Files.readAllBytes(checkedIn), Files.readAllBytes(generatedPins))) {
            throw new IOException("KOTLIN_RUNTIME_PIN_SOURCE_MISMATCH");
        }
    }

    private static Path publishRuntimePinsSource(Path generatedPins, Path destination) throws Exception {
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(parent) || !parent.toRealPath().equals(parent.toAbsolutePath().normalize()) ||
            Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("KOTLIN_RUNTIME_PINS_OUTPUT_INVALID");
        }
        Files.copy(generatedPins, destination);
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination) ||
            !MessageDigest.isEqual(Files.readAllBytes(generatedPins), Files.readAllBytes(destination))) {
            throw new IOException("KOTLIN_RUNTIME_PINS_OUTPUT_VERIFY_FAILED");
        }
        return destination;
    }

    private static void publishAssets(Path stagedAssets, Path outputRoot, List<ArtifactInfo> outputs) throws Exception {
        if (Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(outputRoot))) {
            throw new IOException("KOTLIN_RUNTIME_ASSET_ROOT_INVALID");
        }
        Files.createDirectories(outputRoot);
        for (ArtifactInfo output : outputs) {
            Path source = stagedAssets.resolve(output.relativePath);
            Path target = outputRoot.resolve(output.relativePath);
            if (!target.normalize().startsWith(outputRoot.normalize())) throw new IOException("KOTLIN_RUNTIME_ASSET_PATH_INVALID");
            if (Files.isSymbolicLink(target)) throw new IOException("KOTLIN_RUNTIME_ASSET_TARGET_LINK");
            Path parent = target.getParent();
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent) || !parent.toRealPath().equals(parent.toAbsolutePath().normalize())) {
                throw new IOException("KOTLIN_RUNTIME_ASSET_PARENT_LINK");
            }
            Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".part");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                if (Files.size(temporary) != output.sizeBytes || !sha256(temporary).equals(output.sha256)) {
                    throw new IOException("KOTLIN_RUNTIME_PUBLISH_STAGE_INVALID: " + output.relativePath);
                }
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        for (ArtifactInfo output : outputs) {
            Path target = outputRoot.resolve(output.relativePath);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target) ||
                Files.size(target) != output.sizeBytes || !sha256(target).equals(output.sha256)) {
                throw new IOException("KOTLIN_RUNTIME_PUBLISHED_ASSET_INVALID: " + output.relativePath);
            }
        }
    }

    private static void verifyArtifact(Path path, InputArtifact expected, String error) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            (expected.sizeBytes != 0L && Files.size(path) != expected.sizeBytes) ||
            !sha256(path).equals(expected.sha256)) {
            throw new IOException(error + ": " + expected.path);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static String sha256(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = stream) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
    }

    private static void requireDirectory(Path path, String code) throws Exception {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(code + ": " + path);
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String stringArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static final class InputArtifact {
        final String path;
        final String sha256;
        final long sizeBytes;
        InputArtifact(String path, String sha256, long sizeBytes) {
            this.path = path; this.sha256 = sha256; this.sizeBytes = sizeBytes;
        }
    }

    private static final class ResourceArtifact {
        final String name;
        final InputArtifact input;
        ResourceArtifact(String name, InputArtifact input) { this.name = name; this.input = input; }
    }

    private static final class ArtifactInfo {
        final String relativePath;
        final long sizeBytes;
        final String sha256;
        ArtifactInfo(String relativePath, long sizeBytes, String sha256) {
            this.relativePath = relativePath; this.sizeBytes = sizeBytes; this.sha256 = sha256;
        }
    }

    private static final class DexFileInfo {
        final String name;
        final long sizeBytes;
        final String sha256;
        DexFileInfo(String name, long sizeBytes, String sha256) {
            this.name = name; this.sizeBytes = sizeBytes; this.sha256 = sha256;
        }
    }

    private static final class ArtifactPin {
        final long sizeBytes;
        final String sha256;
        ArtifactPin(long sizeBytes, String sha256) { this.sizeBytes = sizeBytes; this.sha256 = sha256; }
    }
}
