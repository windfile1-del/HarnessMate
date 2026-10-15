package dev.wind.agent.runtime.linux;

import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

public final class ExtractorHostTests {
    private static int passed;
    private ExtractorHostTests() {}

    public static void main(String[] args) throws Exception {
        run("PAX path and size plus GNU long path/link", ExtractorHostTests::paxAndGnu);
        run("forward hardlink chain", ExtractorHostTests::forwardHardlinks);
        run("bzcat denied hardlink copies content and executable mode", ExtractorHostTests::deniedHardlinks);
        run("denied forward hardlink chain", ExtractorHostTests::deniedForwardHardlinks);
        run("hardlink copy budget and non-compatible errors", ExtractorHostTests::hardlinkFailures);
        run("duplicate directories and duplicate files", ExtractorHostTests::duplicates);
        run("absolute guest links and parent-link rejection", ExtractorHostTests::symlinks);
        run("checksum, metadata quota, and trailing padding quota", ExtractorHostTests::rejectMalformedAndOversized);
        System.out.println("R0 extractor host tests passed: " + passed);
    }

    private static void deniedHardlinks() throws Exception {
        for (int errno : new int[]{OsConstants.EPERM, OsConstants.EACCES, OsConstants.EOPNOTSUPP,
                OsConstants.ENOSYS, OsConstants.EXDEV}) {
            Os.clearRecords(); Os.failHardlinks(errno);
            List<Member> members = new ArrayList<>();
            members.add(new Member("usr/bin/bzcat", '1', "usr/bin/bunzip2", new byte[0], 0, 0755));
            members.add(new Member("usr/bin/bunzip2", '0', "", bytes("bzip-payload"), 12, 0755));
            Path root = extract(archive(members, 0), 2, 12);
            try {
                Path out = root.resolve("usr/bin/bzcat");
                check(java.util.Arrays.equals(Files.readAllBytes(out), bytes("bzip-payload")), "fallback content " + errno);
                check((Os.lstat(out.toString()).st_mode & 0777) == 0755, "fallback executable mode " + errno);
                check(Os.hardlinkTarget(out.toString()) == null, "fallback must not depend on link " + errno);
            } finally { delete(root); }
        }
    }

    private static void deniedForwardHardlinks() throws Exception {
        Os.failHardlinks(OsConstants.EPERM);
        List<Member> members = new ArrayList<>();
        members.add(new Member("alias-a", '1', "alias-b", new byte[0], 0, 0644));
        members.add(new Member("alias-b", '1', "payload", new byte[0], 0, 0644));
        members.add(new Member("payload", '0', "", bytes("linked"), 6, 0755));
        Path root = extract(archive(members, 0), 3, 6);
        try {
            check(java.util.Arrays.equals(Files.readAllBytes(root.resolve("alias-a")), bytes("linked")), "fallback chain content");
            check((Os.lstat(root.resolve("alias-a").toString()).st_mode & 0777) == 0755, "fallback chain mode");
        } finally { delete(root); }
    }

    private static void hardlinkFailures() throws Exception {
        Path root = Files.createTempDirectory("r1-link-fail-");
        try {
            Path target = root.resolve("payload"); Files.write(target, bytes("data"));
            for (int errno : new int[]{OsConstants.EIO, OsConstants.ENOSPC, OsConstants.EEXIST, OsConstants.ENOENT}) {
                Os.failHardlinks(errno);
                Path out = root.resolve("error-" + errno);
                try {
                    TrustedRootfsExtractor.createHardlink(target.toFile(), out.toFile(), 100);
                    throw new AssertionError("masked non-compatible errno " + errno);
                } catch (IOException expected) {
                    check(expected.getMessage().contains("errno=" + errno), "errno diagnostic");
                    check(!Files.exists(out), "fatal error must not copy");
                }
            }
            Os.failHardlinks(OsConstants.EPERM);
            Path out = root.resolve("over-budget");
            try {
                TrustedRootfsExtractor.createHardlink(target.toFile(), out.toFile(), 3);
                throw new AssertionError("accepted excess copy bytes");
            } catch (IOException expected) {
                check(expected.getMessage().contains("quota"), "copy quota error");
                check(!Files.exists(out), "quota rejection must not create destination");
            }
        } finally { delete(root); }
    }

    private static void paxAndGnu() throws Exception {
        String paxPath = "usr/share/" + repeat('p', 112);
        String gnuPath = "opt/share/" + repeat('g', 112);
        byte[] pax = (paxRecord("path", paxPath) + paxRecord("size", "4")).getBytes(StandardCharsets.UTF_8);
        List<Member> members = new ArrayList<>();
        members.add(new Member("PaxHeader", 'x', "", pax, pax.length, 0644));
        members.add(new Member("fallback", '0', "", bytes("PAX!"), 1, 0644));
        members.add(new Member("LongLink", 'L', "", bytes(gnuPath + "\n"), gnuPath.length() + 1, 0644));
        members.add(new Member("fallback2", '0', "", bytes("G"), 1, 0644));
        Path root = extract(archive(members, 0), 2, 5);
        check(new String(Files.readAllBytes(root.resolve(paxPath)), StandardCharsets.UTF_8).equals("PAX!"), "PAX size/path");
        check(new String(Files.readAllBytes(root.resolve(gnuPath)), StandardCharsets.UTF_8).equals("G"), "GNU long path");
        delete(root);

        String target = "/opt/jdk/bin/java";
        members = new ArrayList<>();
        members.add(new Member("LongLink", 'K', "", bytes(target + "\n"), target.length() + 1, 0644));
        members.add(new Member("bin/java", '2', "short", new byte[0], 0, 0777));
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        Path linkRoot = extract(archive(members, 0), 2, 1);
        check("../opt/jdk/bin/java".equals(Os.readlink(linkRoot.resolve("bin/java").toString())), "GNU absolute link mapping");
        delete(linkRoot);
    }

    private static void forwardHardlinks() throws Exception {
        List<Member> members = new ArrayList<>();
        members.add(new Member("alias-a", '1', "alias-b", new byte[0], 0, 0644));
        members.add(new Member("alias-b", '1', "payload", new byte[0], 0, 0644));
        members.add(new Member("payload", '0', "", bytes("linked"), 6, 0644));
        Path root = extract(archive(members, 0), 3, 6);
        check(new String(Files.readAllBytes(root.resolve("alias-a")), StandardCharsets.UTF_8).equals("linked"), "forward hardlink content");
        check(Os.hardlinkTarget(root.resolve("alias-a").toString()).equals(root.resolve("alias-b").toAbsolutePath().normalize()),
                "hardlink chain resolution order");
        delete(root);

        members = new ArrayList<>();
        members.add(new Member("cycle-a", '1', "cycle-b", new byte[0], 0, 0644));
        members.add(new Member("cycle-b", '1', "cycle-a", new byte[0], 0, 0644));
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        rejects(archive(members, 0), 3, 1, "cyclic hardlink");
    }

    private static void duplicates() throws Exception {
        List<Member> members = new ArrayList<>();
        members.add(new Member("root", '5', "", new byte[0], 0, 0755));
        members.add(new Member("tmp", '5', "", new byte[0], 0, 0755));
        members.add(new Member("tmp", '5', "", new byte[0], 0, 0700));
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        Path root = extract(archive(members, 0), 4, 1);
        check(Files.isDirectory(root.resolve("tmp")), "repeated directory entry");
        delete(root);

        members = new ArrayList<>();
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        members.add(new Member("payload", '0', "", bytes("y"), 1, 0644));
        rejects(archive(members, 0), 2, 2, "duplicate file path");
    }

    private static void symlinks() throws Exception {
        List<Member> members = new ArrayList<>();
        members.add(new Member("bin/sh", '0', "", bytes("sh"), 2, 0755));
        members.add(new Member("etc/alternatives/sh", '2', "/bin/sh", new byte[0], 0, 0777));
        Path root = extract(archive(members, 0), 2, 2);
        check("../../bin/sh".equals(Os.readlink(root.resolve("etc/alternatives/sh").toString())), "absolute guest path mapping");
        delete(root);

        members = new ArrayList<>();
        members.add(new Member("dir", '2', "real", new byte[0], 0, 0777));
        members.add(new Member("dir/child", '2', "payload", new byte[0], 0, 0777));
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        rejects(archive(members, 0), 3, 1, "symlink parent destination");

        members = new ArrayList<>();
        members.add(new Member("first", '2', "second", new byte[0], 0, 0777));
        members.add(new Member("second", '2', "../../outside", new byte[0], 0, 0777));
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        rejects(archive(members, 0), 3, 1, "symlink chain parent escape");
    }

    private static void rejectMalformedAndOversized() throws Exception {
        List<Member> members = new ArrayList<>();
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        byte[] bad = archive(members, 0);
        bad[10] ^= 1;
        rejects(bad, 1, 1, "checksum mismatch");

        members = new ArrayList<>();
        byte[] meta = new byte[256 * 1024]; meta[0] = 'n'; meta[1] = 0;
        for (int i = 0; i < 33; i++) members.add(new Member("LongLink", 'L', "", meta, meta.length, 0644));
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        rejects(archive(members, 0), 1, 1, "metadata quota");

        members = new ArrayList<>();
        members.add(new Member("payload", '0', "", bytes("x"), 1, 0644));
        rejects(archive(members, 1024 * 1024 + 1), 1, 1, "trailing TAR padding");
    }

    private static void run(String name, Test test) throws Exception {
        Os.clearRecords(); test.run(); passed++; System.out.println("PASS " + name);
    }
    private static Path extract(byte[] archive, int entries, long bytes) throws Exception {
        Path root = Files.createTempDirectory("r0-extract-");
        TrustedRootfsExtractor.extract(new ByteArrayInputStream(archive), root.toFile(), bytes, entries);
        return root;
    }
    private static void rejects(byte[] archive, int entries, long bytes, String label) throws Exception {
        Path root = Files.createTempDirectory("r0-reject-");
        try {
            TrustedRootfsExtractor.extract(new ByteArrayInputStream(archive), root.toFile(), bytes, entries);
            throw new AssertionError("accepted " + label);
        } catch (IOException expected) {
            if (expected.getMessage() == null) throw new AssertionError("missing failure reason: " + label);
        } finally { delete(root); }
    }
    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] sorted = paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toArray(Path[]::new);
            for (Path path : sorted) Files.deleteIfExists(path);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private interface Test { void run() throws Exception; }

    private static final class Member {
        final String name; final char type; final String link; final byte[] data; final int headerSize, mode;
        Member(String name, char type, String link, byte[] data, int headerSize, int mode) {
            this.name = name; this.type = type; this.link = link; this.data = data; this.headerSize = headerSize; this.mode = mode;
        }
    }
    private static byte[] archive(List<Member> members, int trailingZeros) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (Member member : members) {
            byte[] header = new byte[512]; putString(header, 0, 100, member.name); putOctal(header, 100, 8, member.mode);
            putOctal(header, 108, 8, 0); putOctal(header, 116, 8, 0); putOctal(header, 124, 12, member.headerSize);
            putOctal(header, 136, 12, 0); for (int i = 148; i < 156; i++) header[i] = ' ';
            header[156] = (byte)member.type; putString(header, 157, 100, member.link);
            putString(header, 257, 6, "ustar"); putString(header, 263, 2, "00");
            long checksum = 0; for (byte item : header) checksum += item & 255;
            String sum = String.format(Locale.ROOT, "%06o", checksum); putString(header, 148, 6, sum); header[154] = 0; header[155] = ' ';
            raw.write(header); raw.write(member.data); int pad = (512 - member.data.length % 512) % 512; raw.write(new byte[pad]);
        }
        raw.write(new byte[1024 + trailingZeros]);
        ByteArrayOutputStream gzip = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gzip)) { raw.writeTo(out); }
        return gzip.toByteArray();
    }
    private static void putString(byte[] out, int at, int length, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8); System.arraycopy(data, 0, out, at, Math.min(length, data.length));
    }
    private static void putOctal(byte[] out, int at, int length, long value) {
        String text = String.format(Locale.ROOT, "%0" + (length - 1) + "o", value);
        putString(out, at, length - 1, text); out[at + length - 1] = 0;
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String repeat(char value, int count) { StringBuilder out = new StringBuilder(); while (count-- > 0) out.append(value); return out.toString(); }
    private static String paxRecord(String key, String value) {
        String body = key + "=" + value + "\n"; int size = body.length() + 2;
        for (;;) { int updated = body.length() + Integer.toString(size).length() + 1; if (updated == size) break; size = updated; }
        return size + " " + body;
    }
}
