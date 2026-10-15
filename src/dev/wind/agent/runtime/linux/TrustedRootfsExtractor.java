package dev.wind.agent.runtime.linux;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Trusted APK rootfs TAR reader, derived from DSHA TarGzipExtractor (MIT).
 * Copyright (c) 2026 qiannianhuanxiang. See third_party/runtime/licenses/DSHA-MIT.txt.
 * R0 adaptation: strict checksum/EOF/PAX, quotas, deferred links, no ignored
 * link/chmod failures. This is separate from P7's ordinary ZIP extraction;
 * it must never accept arbitrary network archives or an existing destination.
 */
public final class TrustedRootfsExtractor {
    private static final int BLOCK = 512, MAX_META = 262144;
    private static final long MAX_META_TOTAL = 8L * 1024 * 1024;
    private static final int MAX_META_RECORDS = 20000;
    private static final long MAX_TAR_OVERHEAD = 64L * 1024 * 1024;
    private static final long MAX_TRAILING_ZEROES = 1024L * 1024;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024 * 1024;
    private static final long MAX_HARDLINK_COPY_BYTES = 64L * 1024 * 1024;
    private TrustedRootfsExtractor() {}
    public static void extract(InputStream compressed, File destination, long expectedBytes, int expectedEntries) throws IOException {
        if (!isRealDirectory(destination) || destination.list() == null || destination.list().length != 0)
            throw new IOException("Rootfs extraction requires a fresh empty directory");
        if (expectedBytes < 1 || expectedBytes > MAX_EXPANDED_BYTES || expectedEntries < 1 || expectedEntries > 40000)
            throw new IOException("Rootfs quota exceeds the supported runtime budget");
        File root = destination.getCanonicalFile();
        List<Link> links = new ArrayList<>();
        Map<String, Boolean> paths = new HashMap<>();
        Map<String, String> pending = new HashMap<>();
        long total = 0;
        long metaTotal = 0;
        int entries = 0, metaRecords = 0;
        long streamLimit = expectedBytes + MAX_TAR_OVERHEAD;
        try (InputStream in = new BufferedInputStream(new LimitedInputStream(
                new GZIPInputStream(compressed, 65536), streamLimit), 65536)) {
            byte[] header = new byte[BLOCK];
            for (;;) {
                LinuxInstallControl.check();
                full(in, header, BLOCK);
                if (zero(header)) {
                    full(in, header, BLOCK);
                    if (!zero(header) || !pending.isEmpty()) throw new IOException("Invalid TAR terminator");
                    long trailing = 0;
                    int value;
                    while ((value = in.read()) != -1) {
                        if (value != 0) throw new IOException("Trailing TAR data");
                        if (++trailing > MAX_TRAILING_ZEROES) throw new IOException("Excessive trailing TAR padding");
                    }
                    break;
                }
                long stored = octal(header, 148, 8), sum = 0;
                for (int i = 0; i < BLOCK; i++) sum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                if (stored != sum) throw new IOException("TAR header checksum mismatch");
                long size = octal(header, 124, 12);
                int mode = (int)octal(header, 100, 8) & 0777;
                int type = header[156] & 255;
                String name = string(header, 0, 100), target = string(header, 157, 100);
                String prefix = string(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                if (type == 'x' || type == 'L' || type == 'K') {
                    if (size < 1 || size > MAX_META || ++metaRecords > MAX_META_RECORDS
                            || metaTotal > MAX_META_TOTAL - size)
                        throw new IOException("TAR metadata quota exceeded");
                    metaTotal += size;
                    byte[] data = new byte[(int)size]; full(in, data, data.length); padding(in, size);
                    if (type == 'x') pending.putAll(pax(data));
                    else pending.put(type == 'L' ? "path" : "linkpath", gnuString(data));
                    continue;
                }
                if (pending.containsKey("path")) name = pending.get("path");
                if (pending.containsKey("linkpath")) target = pending.get("linkpath");
                if (pending.containsKey("size")) {
                    size = paxSize(pending.get("size"));
                }
                pending.clear();
                name = normalized(name, false);
                if (name.isEmpty()) throw new IOException("Empty rootfs TAR path");
                if (++entries > expectedEntries || size < 0 || size > 512L * 1024 * 1024 || total > expectedBytes - size)
                    throw new IOException("Rootfs size/entry quota exceeded");
                total += size;
                File out = inside(root, name);
                boolean directory = type == '5';
                Boolean priorDirectory = paths.get(name);
                paths.put(name, directory);
                if (priorDirectory != null && !(priorDirectory && directory))
                    throw new IOException("Duplicate rootfs path: " + name);
                switch (type) {
                    case 0: case '0': case '7':
                        mkdir(out.getParentFile());
                        if (out.exists()) throw new IOException("Rootfs path already exists: " + name);
                        try (FileOutputStream stream = new FileOutputStream(out)) { copy(in, stream, size); }
                        chmod(out, mode); break;
                    case '5':
                        if (size != 0) throw new IOException("Directory has TAR data");
                        mkdir(out); chmod(out, mode | 0700); break;
                    case '1': case '2':
                        if (size != 0) throw new IOException("Link has TAR data");
                        links.add(new Link(name, target, type == '1')); break;
                    default: throw new IOException("Unsupported trusted rootfs TAR type: " + type);
                }
                padding(in, size);
            }
        }
        if (entries != expectedEntries || total != expectedBytes)
            throw new IOException("Rootfs manifest size/entry mismatch");
        // Files and directories exist before links; resolve hardlink chains
        // independent of archive order and create symlinks only after them.
        List<Link> hardlinks = new ArrayList<>();
        long copiedLinks = 0;
        long copyBudget = Math.min(MAX_HARDLINK_COPY_BYTES, MAX_EXPANDED_BYTES - expectedBytes);
        for (Link link : links) {
            LinuxInstallControl.check();
            if (link.hard) hardlinks.add(link);
        }
        while (!hardlinks.isEmpty()) {
            LinuxInstallControl.check();
            boolean progressed = false;
            for (int i = 0; i < hardlinks.size();) {
                Link link = hardlinks.get(i);
                ensureNoSymlinkParents(root, link.name);
                File out = inside(root, link.name);
                mkdir(out.getParentFile());
                ensureNoSymlinkParents(root, link.name);
                if (out.exists() || isLink(out)) throw new IOException("Link destination already exists");
                String targetName = normalized(link.target, false);
                ensureNoSymlinkParents(root, targetName);
                File target = inside(root, targetName);
                if (!exists(target)) { i++; continue; }
                if (!isRegularFile(target)) throw new IOException("Invalid hardlink target");
                try { copiedLinks += createHardlink(target, out, copyBudget - copiedLinks); }
                catch (IOException failure) { throw new IOException("Rootfs hardlink failed: " + link.name + ": " + failure.getMessage(), failure); }
                hardlinks.remove(i);
                progressed = true;
            }
            if (!progressed) throw new IOException("Unresolved or cyclic rootfs hardlink");
        }
        for (Link link : links) {
            LinuxInstallControl.check();
            if (link.hard) continue;
            ensureNoSymlinkParents(root, link.name);
            File out = inside(root, link.name);
            mkdir(out.getParentFile());
            ensureNoSymlinkParents(root, link.name);
            if (out.exists() || isLink(out)) throw new IOException("Link destination already exists");
            try {
                String virtual = link.target.startsWith("/") ? link.target.substring(1)
                        : parent(link.name) + link.target;
                String target = normalized(virtual, true);
                File mapped = inside(root, target);
                // Convert guest absolute links into host-relative links.
                String relative = relative(parent(link.name), target);
                if (!mapped.getPath().startsWith(root.getPath() + File.separator) && !mapped.equals(root))
                    throw new IOException("Escaping rootfs link");
                Os.symlink(relative, out.getPath());
            } catch (ErrnoException e) { throw new IOException("Rootfs link failed: " + link.name, e); }
        }
    }
    /** Fresh private staging only. Copies preserve content and executable mode
     * when Android policy/filesystem disallows links, without disabling quotas.
     * Package visibility permits a small host regression to exercise the budget. */
    static long createHardlink(File target, File out, long copyRemaining) throws IOException {
        ErrnoException denied;
        try { Os.link(target.getPath(), out.getPath()); return 0; }
        catch (ErrnoException failure) {
            if (failure.errno != OsConstants.EPERM && failure.errno != OsConstants.EACCES
                    && failure.errno != OsConstants.EOPNOTSUPP && failure.errno != OsConstants.ENOSYS
                    && failure.errno != OsConstants.EXDEV)
                throw new IOException("link errno=" + failure.errno, failure);
            denied = failure;
        }
        long size = target.length();
        if (size > copyRemaining) throw new IOException("Rootfs hardlink copy quota exceeded", denied);
        int mode;
        try {
            int targetMode = Os.lstat(target.getPath()).st_mode;
            if (!OsConstants.S_ISREG(targetMode)) throw new IOException("Invalid hardlink copy target");
            mode = targetMode & 0777;
        } catch (ErrnoException failure) { throw new IOException("Cannot stat hardlink copy target", failure); }
        if (!out.createNewFile()) throw new IOException("Hardlink copy destination already exists", denied);
        try {
            try (InputStream input = new FileInputStream(target); FileOutputStream output = new FileOutputStream(out)) {
                copy(input, output, size);
                if (input.read() != -1) throw new IOException("Hardlink copy target changed size");
            }
            chmod(out, mode);
            return size;
        } catch (IOException failure) {
            failure.addSuppressed(denied);
            if (!out.delete()) failure.addSuppressed(new IOException("Cannot remove failed hardlink copy"));
            throw failure;
        }
    }
    private static File inside(File root, String name) throws IOException {
        File out = new File(root, name);
        File canonical = out.getCanonicalFile();
        if (!canonical.equals(root) && !canonical.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("Escaping rootfs path");
        return out;
    }
    private static String parent(String name) { int at = name.lastIndexOf('/'); return at < 0 ? "" : name.substring(0, at + 1); }
    private static String normalized(String value, boolean allowParent) throws IOException {
        if (value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0)
            throw new IOException("Invalid rootfs path");
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!allowParent || parts.isEmpty()) throw new IOException("Escaping rootfs path");
                parts.remove(parts.size() - 1);
            } else parts.add(part);
        }
        StringBuilder result = new StringBuilder();
        for (String part : parts) { if (result.length() > 0) result.append('/'); result.append(part); }
        return result.toString();
    }
    private static String relative(String from, String to) {
        String[] left = from.isEmpty() ? new String[0] : from.split("/");
        String[] right = to.isEmpty() ? new String[0] : to.split("/");
        int same = 0;
        while (same < left.length && same < right.length && left[same].equals(right[same])) same++;
        StringBuilder result = new StringBuilder();
        for (int i = same; i < left.length; i++) result.append("../");
        for (int i = same; i < right.length; i++) { if (i > same) result.append('/'); result.append(right[i]); }
        return result.length() == 0 ? "." : result.toString();
    }
    private static boolean isLink(File file) throws IOException {
        try { return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode); }
        catch (ErrnoException e) { if (e.errno == OsConstants.ENOENT) return false; throw new IOException(e); }
    }
    private static boolean isRealDirectory(File file) throws IOException {
        try { return OsConstants.S_ISDIR(Os.lstat(file.getPath()).st_mode); }
        catch (ErrnoException e) { if (e.errno == OsConstants.ENOENT) return false; throw new IOException(e); }
    }
    private static boolean exists(File file) throws IOException {
        try { Os.lstat(file.getPath()); return true; }
        catch (ErrnoException e) { if (e.errno == OsConstants.ENOENT) return false; throw new IOException(e); }
    }
    private static boolean isRegularFile(File file) throws IOException {
        try { return OsConstants.S_ISREG(Os.lstat(file.getPath()).st_mode); }
        catch (ErrnoException e) { if (e.errno == OsConstants.ENOENT) return false; throw new IOException(e); }
    }
    private static void ensureNoSymlinkParents(File root, String name) throws IOException {
        String[] parts = name.split("/");
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (path.length() > 0) path.append('/');
            path.append(parts[i]);
            File parent = new File(root, path.toString());
            try {
                int mode = Os.lstat(parent.getPath()).st_mode;
                if (OsConstants.S_ISLNK(mode) || !OsConstants.S_ISDIR(mode))
                    throw new IOException("Rootfs link parent is not a real directory: " + path);
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT) return;
                throw new IOException(e);
            }
        }
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create rootfs directory");
        if (isLink(file)) throw new IOException("Rootfs parent is a symlink");
    }
    private static void chmod(File file, int mode) throws IOException {
        try { Os.chmod(file.getPath(), mode); } catch (ErrnoException e) { throw new IOException(e); }
    }
    private static void full(InputStream in, byte[] data, int length) throws IOException {
        int at = 0;
        while (at < length) { int count = in.read(data, at, length - at); if (count < 0) throw new IOException("Truncated rootfs TAR"); at += count; }
    }
    private static void copy(InputStream in, FileOutputStream out, long count) throws IOException {
        byte[] data = new byte[65536];
        while (count > 0) { int n = in.read(data, 0, (int)Math.min(data.length, count)); if (n < 0) throw new IOException("Truncated TAR file"); out.write(data, 0, n); count -= n; }
    }
    private static void padding(InputStream in, long size) throws IOException {
        int count = (int)((BLOCK - size % BLOCK) % BLOCK);
        full(in, new byte[count], count);
    }
    private static boolean zero(byte[] data) { for (byte item : data) if (item != 0) return false; return true; }
    private static String string(byte[] data, int start, int count) {
        int end = start; while (end < start + count && data[end] != 0) end++;
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }
    private static String gnuString(byte[] data) {
        String value = string(data, 0, data.length);
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        return value;
    }
    private static long paxSize(String value) throws IOException {
        if (value.isEmpty()) throw new IOException("Invalid PAX size");
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9' || result > (Long.MAX_VALUE - (c - '0')) / 10)
                throw new IOException("Invalid PAX size");
            result = result * 10 + c - '0';
        }
        return result;
    }
    private static long octal(byte[] data, int start, int count) throws IOException {
        long result = 0; boolean seenDigit = false, ended = false;
        for (int i = start; i < start + count; i++) {
            int c = data[i] & 255;
            if (c == 0 || c == ' ') { if (seenDigit) ended = true; continue; }
            if (ended || c < '0' || c > '7') throw new IOException("Unsupported TAR numeric field");
            if (result > (Long.MAX_VALUE - (c - '0')) / 8) throw new IOException("TAR numeric field overflow");
            result = result * 8 + c - '0'; seenDigit = true;
        }
        return result;
    }
    private static Map<String, String> pax(byte[] data) throws IOException {
        Map<String, String> result = new HashMap<>(); int at = 0;
        while (at < data.length) {
            int space = at; long recordSize = 0;
            while (space < data.length && data[space] != ' ') {
                int digit = data[space] - '0';
                if (digit < 0 || digit > 9 || recordSize > (Integer.MAX_VALUE - digit) / 10L)
                    throw new IOException("Invalid PAX record length");
                recordSize = recordSize * 10 + digit;
                space++;
            }
            int size = (int)recordSize;
            if (size < 3 || size > data.length - at || space >= at + size - 1 || data[at + size - 1] != '\n')
                throw new IOException("Truncated PAX record");
            String value = new String(data, space + 1, at + size - space - 2, StandardCharsets.UTF_8);
            int equals = value.indexOf('='); if (equals < 1) throw new IOException("Invalid PAX key");
            String key = value.substring(0, equals);
            if (!key.equals("path") && !key.equals("linkpath") && !key.equals("size"))
                throw new IOException("Unsupported PAX attribute: " + key);
            result.put(key, value.substring(equals + 1)); at += size;
        }
        return result;
    }
    private static final class Link {
        final String name, target; final boolean hard;
        Link(String name, String target, boolean hard) { this.name = name; this.target = target; this.hard = hard; }
    }
    private static final class LimitedInputStream extends InputStream {
        private final InputStream input;
        private long remaining;
        LimitedInputStream(InputStream input, long limit) { this.input = input; this.remaining = limit; }
        @Override public int read() throws IOException {
            if (remaining == 0) {
                if (input.read() == -1) return -1;
                throw new IOException("Expanded TAR quota exceeded");
            }
            int value = input.read();
            if (value != -1) remaining--;
            return value;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (remaining == 0) {
                if (input.read() == -1) return -1;
                throw new IOException("Expanded TAR quota exceeded");
            }
            int count = input.read(data, offset, (int)Math.min((long)length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
        @Override public void close() throws IOException { input.close(); }
    }
}
