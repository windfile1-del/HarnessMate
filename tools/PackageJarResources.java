import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Adds non-class resources from provider jars to an unsigned APK.
 *
 * <p>The APK is rewritten through a same-directory temporary file.  All input
 * ZIPs are read and validated before the target is replaced, so rejected
 * resources cannot leave a partially written APK behind.</p>
 */
public final class PackageJarResources {
    private static final long MAX_RESOURCE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_APK_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long FIXED_TIME_MILLIS = 0L;
    private static final byte[] APK_SIGNING_BLOCK_MAGIC =
            "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

    private PackageJarResources() {
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                throw new IOException("usage: java PackageJarResources unsigned.apk jar1 [jar2 ...]");
            }
            Path apk = Paths.get(args[0]).toAbsolutePath().normalize();
            List<Path> jars = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                jars.add(Paths.get(args[i]).toAbsolutePath().normalize());
            }
            Result result = packageResources(apk, jars);
            System.out.println("Packaged " + result.resourceEntries + " resource entries ("
                    + result.resourceBytes + " bytes) into " + apk);
        } catch (Exception error) {
            System.err.println("PackageJarResources: ERROR: " + error.getMessage());
            System.exit(1);
        }
    }

    static Result packageResources(Path apk, List<Path> inputJars) throws IOException {
        Objects.requireNonNull(apk, "apk");
        Objects.requireNonNull(inputJars, "inputJars");
        if (inputJars.isEmpty()) {
            throw new IOException("at least one input jar is required");
        }
        if (!Files.isRegularFile(apk)) {
            throw new IOException("APK is not a regular file: " + apk);
        }
        if (Files.isSymbolicLink(apk)) {
            throw new IOException("refusing symbolic-link APK: " + apk);
        }
        if (hasApkSigningBlock(apk)) {
            throw new IOException("target APK must be unsigned; APK signing block present");
        }

        List<Path> jars = new ArrayList<>();
        for (Path jar : inputJars) {
            if (!Files.isRegularFile(jar)) {
                throw new IOException("input jar is not a regular file: " + jar);
            }
            jars.add(jar);
        }
        jars.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Path::toString));

        Map<String, OutputEntry> existing = readApk(apk);
        Map<String, List<ResourceCandidate>> resources = new TreeMap<>();
        long inputResourceBytes = 0L;
        for (int index = 0; index < jars.size(); index++) {
            Path jar = jars.get(index);
            String source = sourceId(index, jar);
            try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
                Set<String> seenNames = new HashSet<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    validateZipPath(name, "jar " + jar);
                    if (!seenNames.add(name)) {
                        // Duplicate entries are valid ZIP syntax.  They are
                        // resolved by the same content/conflict rules as
                        // duplicate resources from separate jars.
                    }
                    if (entry.isDirectory() || isFilteredJarEntry(name)) {
                        continue;
                    }
                    long remaining = MAX_RESOURCE_BYTES - inputResourceBytes;
                    if (remaining < 0L) {
                        throw new IOException("provider resource size limit exceeded");
                    }
                    byte[] data;
                    try (InputStream input = zip.getInputStream(entry)) {
                        data = readBounded(input, remaining, "resource " + name + " from " + jar);
                    }
                    inputResourceBytes += data.length;
                    if (inputResourceBytes > MAX_RESOURCE_BYTES) {
                        throw new IOException("provider resource size limit exceeded: "
                                + MAX_RESOURCE_BYTES + " bytes");
                    }
                    resources.computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(new ResourceCandidate(source, data, true));
                }
            }
        }

        TreeMap<String, OutputEntry> output = new TreeMap<>(existing);
        long outputResourceBytes = 0L;
        int outputResourceEntries = 0;
        for (Map.Entry<String, List<ResourceCandidate>> group : resources.entrySet()) {
            String name = group.getKey();
            List<ResourceCandidate> candidates = new ArrayList<>(group.getValue());
            OutputEntry existingEntry = existing.get(name);
            if (existingEntry != null) {
                candidates.add(0, new ResourceCandidate("apk-existing", existingEntry.data, false));
            }
            Resolution resolution = resolve(name, candidates);
            for (Map.Entry<String, ResolvedEntry> resolved : resolution.entries.entrySet()) {
                String outputName = resolved.getKey();
                ResolvedEntry resolvedEntry = resolved.getValue();
                OutputEntry old = output.get(outputName);
                if (old != null && !Arrays.equals(old.data, resolvedEntry.data)) {
                    throw new IOException("resource path conflict after source merge: " + outputName);
                }
                if (old == null || resolvedEntry.replaceExisting) {
                    output.put(outputName, new OutputEntry(resolvedEntry.data, resolvedEntry.method,
                            resolvedEntry.fromResource));
                }
                if (resolvedEntry.fromResource && (old == null || resolvedEntry.replaceExisting)) {
                    outputResourceEntries++;
                    outputResourceBytes += resolvedEntry.data.length;
                    if (outputResourceBytes > MAX_RESOURCE_BYTES) {
                        throw new IOException("packaged resource size limit exceeded: "
                                + MAX_RESOURCE_BYTES + " bytes");
                    }
                }
            }
        }

        Path parent = apk.getParent();
        if (parent == null) {
            parent = Paths.get(".").toAbsolutePath().normalize();
        }
        if (!Files.isDirectory(parent)) {
            throw new IOException("APK parent is not a directory: " + parent);
        }
        Path temporary = Files.createTempFile(parent, apk.getFileName().toString() + ".resources-", ".tmp");
        boolean replaced = false;
        try {
            writeApk(temporary, output);
            moveIntoPlace(temporary, apk);
            replaced = true;
        } finally {
            if (!replaced) {
                Files.deleteIfExists(temporary);
            }
        }
        return new Result(outputResourceEntries, outputResourceBytes);
    }

    private static Map<String, OutputEntry> readApk(Path apk) throws IOException {
        TreeMap<String, OutputEntry> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(apk.toFile(), StandardCharsets.UTF_8)) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = entry.getName();
                // Windows aapt2 may already have emitted legacy backslashes
                // in existing asset names.  They are preserved as-is; newly
                // imported jar resources still use the strict validator below.
                validateExistingApkPath(name, "APK");
                if (entry.isDirectory()) {
                    continue;
                }
                if (isSignatureOrManifest(name)) {
                    throw new IOException("target APK must be unsigned; signature entry present: " + name);
                }
                if (entries.containsKey(name)) {
                    throw new IOException("duplicate entry in target APK: " + name);
                }
                int method = entry.getMethod();
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    throw new IOException("unsupported APK compression method for " + name + ": " + method);
                }
                byte[] data;
                try (InputStream input = zip.getInputStream(entry)) {
                    data = readBounded(input, MAX_APK_ENTRY_BYTES, "APK entry " + name);
                }
                entries.put(name, new OutputEntry(data, method, false));
            }
        }
        return entries;
    }

    private static boolean hasApkSigningBlock(Path apk) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(apk.toFile(), "r")) {
            long fileLength = file.length();
            int tailLength = (int) Math.min(fileLength, 22L + 0xffffL);
            if (tailLength < 22) {
                throw new IOException("invalid APK ZIP: missing end-of-central-directory record");
            }
            long tailOffset = fileLength - tailLength;
            byte[] tail = new byte[tailLength];
            file.seek(tailOffset);
            file.readFully(tail);

            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (readUnsignedInt(tail, i) == 0x06054b50L
                        && i + 22 + readUnsignedShort(tail, i + 20) == tail.length) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException("invalid APK ZIP: missing end-of-central-directory record");
            }

            long eocdOffset = tailOffset + eocd;
            long centralDirectoryOffset = readUnsignedInt(tail, eocd + 16);
            if (centralDirectoryOffset == 0xffffffffL) {
                if (eocdOffset < 20) {
                    throw new IOException("invalid APK ZIP64: missing locator");
                }
                byte[] locator = new byte[20];
                file.seek(eocdOffset - locator.length);
                file.readFully(locator);
                if (readUnsignedInt(locator, 0) != 0x07064b50L) {
                    throw new IOException("invalid APK ZIP64: missing locator");
                }
                long zip64EocdOffset = readUnsignedLong(locator, 8);
                if (zip64EocdOffset > fileLength - 56) {
                    throw new IOException("invalid APK ZIP64: end record is out of range");
                }
                byte[] zip64Eocd = new byte[56];
                file.seek(zip64EocdOffset);
                file.readFully(zip64Eocd);
                if (readUnsignedInt(zip64Eocd, 0) != 0x06064b50L
                        || readUnsignedLong(zip64Eocd, 4) < 44) {
                    throw new IOException("invalid APK ZIP64: malformed end record");
                }
                centralDirectoryOffset = readUnsignedLong(zip64Eocd, 48);
            }

            if (centralDirectoryOffset < 24 || centralDirectoryOffset > fileLength) {
                return false;
            }
            byte[] magic = new byte[APK_SIGNING_BLOCK_MAGIC.length];
            file.seek(centralDirectoryOffset - magic.length);
            file.readFully(magic);
            if (!Arrays.equals(magic, APK_SIGNING_BLOCK_MAGIC)) {
                return false;
            }

            long blockSizeOffset = centralDirectoryOffset - magic.length - 8;
            long blockSize = readFileUnsignedLong(file, blockSizeOffset);
            if (blockSize < 24 || blockSize > centralDirectoryOffset - 8) {
                throw new IOException("malformed APK signing block");
            }
            long blockStart = centralDirectoryOffset - blockSize - 8;
            if (readFileUnsignedLong(file, blockStart) != blockSize) {
                throw new IOException("malformed APK signing block");
            }
            return true;
        }
    }

    private static long readUnsignedInt(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24);
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readUnsignedLong(byte[] data, int offset) throws IOException {
        long value = 0L;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        if (value < 0L) {
            throw new IOException("ZIP offset exceeds supported range");
        }
        return value;
    }

    private static long readFileUnsignedLong(RandomAccessFile file, long offset) throws IOException {
        file.seek(offset);
        byte[] encoded = new byte[8];
        file.readFully(encoded);
        return readUnsignedLong(encoded, 0);
    }

    private static Resolution resolve(String originalName, List<ResourceCandidate> candidates) throws IOException {
        boolean allEqual = true;
        byte[] first = candidates.get(0).data;
        for (int i = 1; i < candidates.size(); i++) {
            if (!Arrays.equals(first, candidates.get(i).data)) {
                allEqual = false;
                break;
            }
        }
        TreeMap<String, ResolvedEntry> resolved = new TreeMap<>();
        if (allEqual) {
            ResourceCandidate winner = candidates.get(0);
            int method = winner.fromResource ? ZipEntry.DEFLATED : ZipEntry.STORED;
            resolved.put(originalName, new ResolvedEntry(first, method, winner.fromResource, winner.fromResource));
            return new Resolution(resolved);
        }

        if (isServicePath(originalName)) {
            byte[] merged = mergeServices(candidates, originalName);
            resolved.put(originalName, new ResolvedEntry(merged, ZipEntry.DEFLATED, true, true));
            return new Resolution(resolved);
        }

        if (isRelocatableMetadata(originalName)) {
            Set<ByteArrayKey> unique = new LinkedHashSet<>();
            for (ResourceCandidate candidate : candidates) {
                if (unique.add(new ByteArrayKey(candidate.data))) {
                    String mapped = "META-INF/vega-resources/" + candidate.source + "/" + originalName;
                    resolved.put(mapped, new ResolvedEntry(candidate.data, ZipEntry.DEFLATED,
                            candidate.fromResource, false));
                }
            }
            return new Resolution(resolved);
        }

        throw new IOException("conflicting resource bytes for " + originalName);
    }

    private static byte[] mergeServices(List<ResourceCandidate> candidates, String name) throws IOException {
        TreeSet<String> providers = new TreeSet<>();
        for (ResourceCandidate candidate : candidates) {
            String text = new String(candidate.data, StandardCharsets.UTF_8);
            String[] lines = text.split("\\R", -1);
            for (String raw : lines) {
                String line = raw;
                int comment = line.indexOf('#');
                if (comment >= 0) {
                    line = line.substring(0, comment);
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (!line.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
                    throw new IOException("invalid provider name in " + name + ": " + line);
                }
                providers.add(line);
            }
        }
        StringBuilder merged = new StringBuilder();
        for (String provider : providers) {
            merged.append(provider).append('\n');
        }
        return merged.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeApk(Path outputPath, Map<String, OutputEntry> entries) throws IOException {
        try (OutputStream output = Files.newOutputStream(outputPath);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            for (Map.Entry<String, OutputEntry> item : entries.entrySet()) {
                String name = item.getKey();
                OutputEntry payload = item.getValue();
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(FIXED_TIME_MILLIS);
                if (payload.method == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(payload.data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(payload.data.length);
                    entry.setCompressedSize(payload.data.length);
                    entry.setCrc(crc.getValue());
                } else {
                    entry.setMethod(ZipEntry.DEFLATED);
                }
                zip.putNextEntry(entry);
                zip.write(payload.data);
                zip.closeEntry();
            }
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readBounded(InputStream input, long limit, String label) throws IOException {
        if (limit < 0L) {
            throw new IOException("size limit exceeded while reading " + label);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 64 * 1024L));
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IOException("size limit exceeded while reading " + label);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateZipPath(String name, String context) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 1024) {
            throw new IOException("invalid ZIP path in " + context + ": " + name);
        }
        if (name.indexOf('\\') >= 0 || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("unsafe ZIP path in " + context + ": " + name);
        }
        if (name.indexOf("//") >= 0) {
            throw new IOException("empty ZIP path segment in " + context + ": " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == 0 || c < 0x20 || c == 0x7f) {
                throw new IOException("control character in ZIP path in " + context + ": " + name);
            }
        }
        String[] segments = name.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("dot path segment in " + context + ": " + name);
            }
            if (segment.isEmpty() && i != segments.length - 1) {
                throw new IOException("empty ZIP path segment in " + context + ": " + name);
            }
        }
    }

    private static void validateExistingApkPath(String name, String context) throws IOException {
        // Validate the logical path while tolerating the pre-existing
        // backslashes produced by the Windows aapt2 input walk.  Absolute and
        // dot-segment paths remain rejected after normalization.
        validateZipPath(name.replace('\\', '/'), context);
    }

    private static boolean isFilteredJarEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.endsWith(".CLASS")) {
            return true;
        }
        if (upper.equals("META-INF/MANIFEST.MF") || upper.equals("META-INF/INDEX.LIST")) {
            return true;
        }
        if (upper.startsWith("META-INF/")) {
            String leaf = upper.substring(upper.lastIndexOf('/') + 1);
            return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                    || leaf.endsWith(".EC") || leaf.endsWith(".SIG");
        }
        return false;
    }

    private static boolean isSignatureOrManifest(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF") || upper.equals("META-INF/INDEX.LIST")) {
            return true;
        }
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String leaf = upper.substring(upper.lastIndexOf('/') + 1);
        return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC") || leaf.endsWith(".SIG");
    }

    private static boolean isServicePath(String name) {
        return name.regionMatches(true, 0, "META-INF/services/", 0, "META-INF/services/".length())
                && name.length() > "META-INF/services/".length();
    }

    private static boolean isRelocatableMetadata(String name) {
        if (name.equalsIgnoreCase("about.html")) {
            return true;
        }
        String leaf = name.substring(name.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
        return leaf.startsWith("LICENSE") || leaf.startsWith("NOTICE");
    }

    private static String sourceId(int index, Path jar) {
        String file = jar.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return String.format(Locale.ROOT, "jar%03d-%s", index, file);
    }

    static final class Result {
        final int resourceEntries;
        final long resourceBytes;

        Result(int resourceEntries, long resourceBytes) {
            this.resourceEntries = resourceEntries;
            this.resourceBytes = resourceBytes;
        }
    }

    private static final class ResourceCandidate {
        final String source;
        final byte[] data;
        final boolean fromResource;

        ResourceCandidate(String source, byte[] data, boolean fromResource) {
            this.source = source;
            this.data = data;
            this.fromResource = fromResource;
        }
    }

    private static final class OutputEntry {
        final byte[] data;
        final int method;
        final boolean fromResource;

        OutputEntry(byte[] data, int method, boolean fromResource) {
            this.data = data;
            this.method = method;
            this.fromResource = fromResource;
        }
    }

    private static final class ResolvedEntry {
        final byte[] data;
        final int method;
        final boolean fromResource;
        final boolean replaceExisting;

        ResolvedEntry(byte[] data, int method, boolean fromResource, boolean replaceExisting) {
            this.data = data;
            this.method = method;
            this.fromResource = fromResource;
            this.replaceExisting = replaceExisting;
        }
    }

    private static final class Resolution {
        final Map<String, ResolvedEntry> entries;

        Resolution(Map<String, ResolvedEntry> entries) {
            this.entries = entries;
        }
    }

    private static final class ByteArrayKey {
        final byte[] data;
        final int hash;

        ByteArrayKey(byte[] data) {
            this.data = data;
            this.hash = Arrays.hashCode(data);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteArrayKey && Arrays.equals(data, ((ByteArrayKey) other).data);
        }
    }
}
