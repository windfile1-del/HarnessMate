package dev.wind.agent

import dev.wind.agent.build.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkDependencyResourceTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "apk-dependency-resources-").toFile().canonicalFile
        try {
            preservationServicesDedupAndAlignment(root)
            conflictsAndReservedEntries(root)
            limitsCancellationAndMalformedServices(root)
            overlappingOutputPreservesInput(root)
            println("ApkDependencyResourceTests: OK")
        } finally {
            Files.walk(root.toPath()).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private class Fixture(root: File, name: String, baseExtra: List<Entry> = emptyList()) {
        val directory = File(root, name).apply { mkdir() }
        val base = File(directory, "base.ap_")
        val dex = File(directory, "dex").apply { mkdir() }
        val output = File(directory, "unsigned.apk")
        init {
            zip(base, listOf(Entry("AndroidManifest.xml", byteArrayOf(1, 2)),
                Entry("resources.arsc", byteArrayOf(3, 4), ZipEntry.STORED)) + baseExtra)
            File(dex, "classes.dex").writeBytes(byteArrayOf(100, 101, 120, 10, 48, 51, 53, 0, 1, 2, 3, 4))
        }
        fun jar(name: String, entries: List<Entry>) = File(directory, "$name.jar").also { zip(it, entries) }
        fun request(vararg jars: File) = ApkAssemblyRequest(base, dex, outputApk = output, dependencyResourceArchives = jars.toList())
        fun assemble(request: ApkAssemblyRequest, token: CancellationToken = CancellationToken()) = ApkAssembler().assemble(request, token)
    }
    private data class Entry(val name: String, val data: ByteArray, val method: Int = ZipEntry.DEFLATED) {
        constructor(name: String, text: String, method: Int = ZipEntry.DEFLATED) : this(name, text.toByteArray(), method)
    }

    private fun preservationServicesDedupAndAlignment(root: File) {
        val f = Fixture(root, "preserve", listOf(Entry("shared/config.txt", "same"),
            Entry("META-INF/services/example.Service", "example.Base\n")))
        val a = f.jar("a", listOf(Entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n"),
            Entry("META-INF/OLD.RSA", "old signature"), Entry("META-INF/SIG-OLD", "old signature marker"),
            Entry("meta-inf/sig-old", "lowercase signature marker"),
            Entry("example/A.class", byteArrayOf(1, 2, 3)), Entry("shared/config.txt", "same"),
            Entry("data/a.bin", byteArrayOf(9, 8, 7), ZipEntry.STORED),
            Entry("META-INF/services/example.Service", "example.A # comment\r\nexample.Base\nexample.A\n")))
        val b = f.jar("b", listOf(Entry("data/b.txt", "second"), Entry("data/a.bin", byteArrayOf(9, 8, 7)),
            Entry("META-INF/services/example.Service", "example.B\nexample.A\n")))
        val request = f.request(a, b)
        val result = f.assemble(request)
        check(result.outcome == BuildOutcome.SUCCESS) { result.diagnostics.toString() }
        val first = f.output.readBytes()
        ZipFile(f.output).use { zip ->
            check(zip.entries().toList().map { it.name }.toSet() == setOf("AndroidManifest.xml", "resources.arsc", "classes.dex",
                "shared/config.txt", "data/a.bin", "data/b.txt", "META-INF/services/example.Service"))
            check(read(zip, "data/a.bin").contentEquals(byteArrayOf(9, 8, 7)))
            check(zip.getEntry("data/a.bin").method == ZipEntry.STORED)
            check(String(read(zip, "META-INF/services/example.Service")) == "example.Base\nexample.A\nexample.B\n")
        }
        check(f.assemble(request).outcome == BuildOutcome.SUCCESS && f.output.readBytes().contentEquals(first))
        val aligned = File(f.directory, "aligned.apk")
        val alignment = ApkAligner(ReflectionZipflingerAlignmentProvider()).align(ApkAlignmentRequest(f.output, aligned), CancellationToken())
        check(alignment.outcome == BuildOutcome.SUCCESS) { alignment.diagnostics.toString() }
        val before = ApkAlignmentVerifier().verify(f.output, token = CancellationToken())
        val after = ApkAlignmentVerifier().verify(aligned, token = CancellationToken())
        check(before.outcome == BuildOutcome.SUCCESS && after.outcome == BuildOutcome.SUCCESS && after.aligned)
        check(before.entries == after.entries)
        val sourceRoot = File(f.directory, "service-sources").apply { mkdir() }
        val sources = listOf("Service", "Base", "A", "B").map { name -> File(sourceRoot, "$name.java").apply {
            writeText(if (name == "Service") "package example; public interface Service { String value(); }"
                else "package example; public class $name implements Service { public String value() { return \"$name\"; } }")
        } }
        val classRoot = File(f.directory, "service-classes")
        val compilation = EcjJavaCompiler().compile(EcjCompileRequest(sources, classRoot), CancellationToken())
        check(compilation.success) { compilation.diagnostics.toString() }
        // Classes live in a host-only fixture directory; the descriptor comes
        // exclusively from the merged/aligned APK, not from the input JARs.
        java.net.URLClassLoader(arrayOf(aligned.toURI().toURL(), classRoot.toURI().toURL()), null).use { loader ->
            @Suppress("UNCHECKED_CAST")
            val serviceClass = loader.loadClass("example.Service") as Class<Any>
            val loaded = java.util.ServiceLoader.load(serviceClass, loader).map { provider ->
                provider.javaClass.getMethod("value").invoke(provider)
            }
            check(loaded == listOf("Base", "A", "B"))
        }
    }

    private fun conflictsAndReservedEntries(root: File) {
        val f = Fixture(root, "conflicts", listOf(Entry("settings.txt", "base")))
        val a = f.jar("a", listOf(Entry("common.txt", "first")))
        val b = f.jar("b", listOf(Entry("common.txt", "different")))
        failure(f, f.request(a, b), "APK_DEPENDENCY_RESOURCE_CONFLICT")
        failure(f, f.request(f.jar("base-conflict", listOf(Entry("settings.txt", "different")))), "APK_DEPENDENCY_RESOURCE_CONFLICT")
        failure(f, f.request(f.jar("prefix", listOf(Entry("data", "file"), Entry("data/child", "child")))), "APK_DEPENDENCY_RESOURCE_CONFLICT")
        val directory = Fixture(root, "directory", listOf(Entry("data/", byteArrayOf())))
        failure(directory, directory.request(directory.jar("dir-conflict", listOf(Entry("data", "file")))), "APK_DEPENDENCY_RESOURCE_CONFLICT")
        for ((index, name) in listOf("AndroidManifest.xml", "resources.arsc", "classes2.dex", "res/raw/a", "lib/x86/a.so", "../escape", "bad:entry").withIndex()) {
            failure(f, f.request(f.jar("reserved-$index", listOf(Entry(name, "invalid")))),
                if (name == "../escape" || name == "bad:entry") "APK_DEPENDENCY_RESOURCE_INVALID" else "APK_DEPENDENCY_RESERVED_ENTRY")
        }
        val duplicate = f.jar("duplicate", listOf(Entry("data/one", "x"), Entry("data/two", "y")))
        replaceName(duplicate, "data/two", "data/one")
        failure(f, f.request(duplicate), "APK_DEPENDENCY_RESOURCE_INVALID")
    }

    private fun limitsCancellationAndMalformedServices(root: File) {
        val f = Fixture(root, "limits")
        val a = f.jar("a", listOf(Entry("data/a", "some data"), Entry("data/b", "more")))
        failure(f, f.request(a).copy(maxDependencyEntries = 1), "APK_DEPENDENCY_RESOURCE_LIMIT", BuildOutcome.RESOURCE_LIMIT)
        failure(f, f.request(a).copy(maxDependencyArchiveBytes = 1), "APK_DEPENDENCY_RESOURCE_LIMIT", BuildOutcome.RESOURCE_LIMIT)
        failure(f, f.request(a).copy(maxDependencyUncompressedBytes = 1), "APK_DEPENDENCY_RESOURCE_LIMIT", BuildOutcome.RESOURCE_LIMIT)
        for ((index, bytes) in listOf("invalid class name".toByteArray(), byteArrayOf(0xC3.toByte(), 0x28)).withIndex()) {
            failure(f, f.request(f.jar("invalid-$index", listOf(Entry("META-INF/services/example.Service", bytes)))), "APK_DEPENDENCY_RESOURCE_INVALID")
        }
        val service = f.jar("service", listOf(Entry("META-INF/services/example.Service", "example.A\nexample.B\n")))
        failure(f, f.request(service).copy(maxServiceDescriptorBytes = 5), "APK_DEPENDENCY_RESOURCE_LIMIT", BuildOutcome.RESOURCE_LIMIT)
        failure(f, f.request(service).copy(maxMergedServiceBytes = 5), "APK_DEPENDENCY_RESOURCE_LIMIT", BuildOutcome.RESOURCE_LIMIT)
        failure(f, f.request(a), "BUILD_CANCELLED", BuildOutcome.CANCELLED, CancellationToken().also { it.cancel() })
        val corrupt = f.jar("crc", listOf(Entry("data/stored", "UNIQUE_PAYLOAD", ZipEntry.STORED)))
        replaceName(corrupt, "UNIQUE_PAYLOAD", "BROKEN_PAYLOAD")
        failure(f, f.request(corrupt), "APK_DEPENDENCY_RESOURCE_INVALID")
    }

    private fun overlappingOutputPreservesInput(root: File) {
        val f = Fixture(root, "overlap")
        val jar = f.jar("input", listOf(Entry("data/a", "preserve")))
        val bytes = jar.readBytes()
        val result = f.assemble(f.request(jar).copy(outputApk = jar))
        check(result.outcome == BuildOutcome.FAILED && jar.readBytes().contentEquals(bytes))
    }
    private fun failure(f: Fixture, request: ApkAssemblyRequest, code: String, outcome: BuildOutcome = BuildOutcome.FAILED,
        token: CancellationToken = CancellationToken()) {
        f.output.writeText("stale")
        val result = f.assemble(request, token)
        check(result.outcome == outcome && result.diagnostics.any { it.code == code }) { "Expected $code/$outcome: ${result.outcome} ${result.diagnostics}" }
        check(result.artifacts.isEmpty() && !f.output.exists())
    }
    private fun zip(file: File, entries: List<Entry>) {
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { e ->
            zip.putNextEntry(ZipEntry(e.name).apply {
                method = e.method
                if (method == ZipEntry.STORED) { size = e.data.size.toLong(); compressedSize = size; crc = CRC32().also { it.update(e.data) }.value }
            })
            zip.write(e.data); zip.closeEntry()
        } }
    }
    private fun read(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    private fun replaceName(file: File, from: String, to: String) {
        check(from.length == to.length)
        val bytes = file.readBytes()
        val source = from.toByteArray(); val replacement = to.toByteArray()
        var count = 0
        for (i in 0..bytes.size - source.size) {
            if (source.indices.all { bytes[i + it] == source[it] }) { replacement.copyInto(bytes, i); count++ }
        }
        check(count > 0)
        file.writeBytes(bytes)
    }
}
