package dev.wind.agent

import dev.wind.agent.build.ApkAssembler
import dev.wind.agent.build.ApkAssemblyRequest
import dev.wind.agent.build.ApkAssemblyResult
import dev.wind.agent.build.BuildDiagnosticSource
import dev.wind.agent.build.BuildOutcome
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Synthetic ZIP/Dex contract tests for P6-AP1. */
object ApkAssemblerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        appOnlyPreservesBaseEntriesAndCompression()
        multidexNamespacesAreMappedNumerically()
        rejectsMalformedBasePackagesAndConflicts()
        rejectsMalformedOrGappedDexInputs()
        enforcesBaseAndDexLimits()
        removesStaleOutputOnFailureAndCancellation()
        rejectsOutputSymlinkWhenSupported()
        rejectsOutputAncestorSymlinkWhenSupported()
        deterministicRerunHasIdenticalBytes()
        println("ApkAssemblerTests: OK")
    }

    private fun appOnlyPreservesBaseEntriesAndCompression() = withFixture { f ->
        val stored = "stored-resource-payload".toByteArray(StandardCharsets.UTF_8)
        val deflated = "a compressible resource payload ".repeat(12).toByteArray(StandardCharsets.UTF_8)
        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1, 2, 3)),
            Entry("resources.arsc", deflated, ZipEntry.DEFLATED),
            Entry("res/raw/keep.bin", stored, ZipEntry.STORED)
        ))
        writeDex(File(f.app, "classes.dex"), 1)

        val result = ApkAssembler().assemble(f.request(), CancellationToken())
        assertSuccess(result, f.output)
        ZipFile(f.output).use { apk ->
            val entries = apk.entries().toList()
            check(entries.map { it.name }.toSet() == setOf(
                "AndroidManifest.xml", "resources.arsc", "res/raw/keep.bin", "classes.dex"
            ))
            check(entries.map { it.name }.distinct().size == entries.size)
            check(read(apk, "AndroidManifest.xml").contentEquals(byteArrayOf(1, 2, 3)))
            check(read(apk, "resources.arsc").contentEquals(deflated))
            check(read(apk, "res/raw/keep.bin").contentEquals(stored))
            check(read(apk, "classes.dex").contentEquals(dexBytes(1)))
            check(apk.getEntry("resources.arsc").method == ZipEntry.DEFLATED)
            check(apk.getEntry("res/raw/keep.bin").method == ZipEntry.STORED)
        }
    }

    private fun multidexNamespacesAreMappedNumerically() = withFixture { f ->
        createBase(f.base)
        for (index in 1..10) writeDex(File(f.app, dexName(index)), index)
        Files.createDirectories(f.runtime.toPath())
        writeDex(File(f.runtime, "classes.dex"), 41)
        writeDex(File(f.runtime, "classes2.dex"), 42)

        val result = ApkAssembler().assemble(f.request(runtime = f.runtime), CancellationToken())
        assertSuccess(result, f.output)
        ZipFile(f.output).use { apk ->
            val expectedNames = (1..12).map(::dexName)
            val actualNames = apk.entries().toList().map { it.name }
                .filter { it == "classes.dex" || it.matches(Regex("classes[0-9]+\\.dex")) }
            check(actualNames == expectedNames) { "DEX entry ordering mismatch: $actualNames" }
            for (index in 1..10) check(read(apk, dexName(index)).contentEquals(dexBytes(index)))
            check(read(apk, "classes11.dex").contentEquals(dexBytes(41)))
            check(read(apk, "classes12.dex").contentEquals(dexBytes(42)))
        }
    }

    private fun rejectsMalformedBasePackagesAndConflicts() = withFixture { f ->
        val ordinaryDex = listOf(Entry("classes.dex", dexBytes(70)))
        createBase(f.base, listOf(Entry("AndroidManifest.xml", byteArrayOf(1))))
        writeDex(File(f.app, "classes.dex"), 1)
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_BASE_PACKAGE_INVALID")

        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1)), Entry("resources.arsc", byteArrayOf(2)),
            Entry("META-INF/CERT.SF", byteArrayOf(3))
        ))
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_BASE_PACKAGE_INVALID")

        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1)), Entry("resources.arsc", byteArrayOf(2)),
            Entry("META-INF/MANIFEST.MF", byteArrayOf(3))
        ))
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_BASE_PACKAGE_INVALID")

        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1)), Entry("resources.arsc", byteArrayOf(2)),
            Entry("../escape", byteArrayOf(3))
        ))
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_BASE_PACKAGE_INVALID")

        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1)), Entry("resources.arsc", byteArrayOf(2)),
            Entry("dir\\bad", byteArrayOf(3))
        ))
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_BASE_PACKAGE_INVALID")

        createBase(f.base, ordinaryDex)
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_DEX_NAMESPACE_CONFLICT")

        // ZipOutputStream disallows duplicate names; rewrite an equal-length name in both ZIP headers.
        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1)), Entry("resources.arsc", byteArrayOf(2)),
            Entry("x/a", byteArrayOf(3)), Entry("x/b", byteArrayOf(4))
        ))
        replaceAscii(f.base, "x/b", "x/a")
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_BASE_PACKAGE_INVALID")
    }

    private fun rejectsMalformedOrGappedDexInputs() = withFixture { f ->
        createBase(f.base)

        writeDex(File(f.app, "classes.dex"), 1)
        writeDex(File(f.app, "classes3.dex"), 3)
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_DEX_INPUT_INVALID")
        File(f.app, "classes3.dex").delete()

        File(f.app, "classes.dex").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        assertFailure(ApkAssembler().assemble(f.request(), CancellationToken()), "APK_DEX_INPUT_INVALID")

        writeDex(File(f.app, "classes.dex"), 1)
        Files.createDirectories(f.runtime.toPath())
        writeDex(File(f.runtime, "classes2.dex"), 2)
        assertFailure(
            ApkAssembler().assemble(f.request(runtime = f.runtime), CancellationToken()),
            "APK_DEX_INPUT_INVALID"
        )
        File(f.runtime, "classes2.dex").delete()

        val emptyRuntime = File(f.root, "empty-runtime").apply { mkdirs() }
        assertFailure(
            ApkAssembler().assemble(f.request(runtime = emptyRuntime), CancellationToken()),
            "APK_DEX_INPUT_INVALID"
        )

        val badBase = File(f.root, "not-a-zip.ap_").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        assertFailure(
            ApkAssembler().assemble(f.request(base = badBase), CancellationToken()),
            "APK_BASE_PACKAGE_INVALID"
        )
    }

    private fun enforcesBaseAndDexLimits() = withFixture { f ->
        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1)), Entry("resources.arsc", byteArrayOf(2)),
            Entry("assets/data.bin", byteArrayOf(3, 4))
        ))
        writeDex(File(f.app, "classes.dex"), 1)

        val entryLimit = ApkAssembler().assemble(
            f.request().copy(maxBaseEntries = 2), CancellationToken()
        )
        assertResourceLimit(entryLimit, "APK_BASE_PACKAGE_LIMIT")

        val baseBytesLimit = ApkAssembler().assemble(
            f.request().copy(maxBaseUncompressedBytes = 3), CancellationToken()
        )
        assertResourceLimit(baseBytesLimit, "APK_BASE_PACKAGE_LIMIT")

        writeDex(File(f.app, "classes2.dex"), 2)
        val dexCountLimit = ApkAssembler().assemble(
            f.request().copy(maxDexFiles = 1), CancellationToken()
        )
        assertResourceLimit(dexCountLimit, "APK_DEX_INPUT_LIMIT")

        val dexBytesLimit = ApkAssembler().assemble(
            f.request().copy(maxDexBytes = 16), CancellationToken()
        )
        assertResourceLimit(dexBytesLimit, "APK_DEX_INPUT_LIMIT")

        val invalidLimit = ApkAssembler().assemble(
            f.request().copy(maxDexFiles = 0), CancellationToken()
        )
        assertFailure(invalidLimit, "APK_ASSEMBLY_INPUT_INVALID")
    }

    private fun removesStaleOutputOnFailureAndCancellation() = withFixture { f ->
        createBase(f.base)
        writeDex(File(f.app, "classes.dex"), 1)
        f.output.parentFile.mkdirs()
        f.output.writeText("stale apk")
        val token = CancellationToken().also { it.cancel() }
        val cancelled = ApkAssembler().assemble(f.request(), token)
        check(cancelled.outcome == BuildOutcome.CANCELLED)
        check(cancelled.diagnostics.any { it.code == "BUILD_CANCELLED" })
        check(!f.output.exists())
        check(f.output.parentFile.listFiles().orEmpty().isEmpty()) { "Cancellation left a temporary APK behind" }

        writeDex(File(f.app, "classes.dex"), 1)
        f.output.writeText("stale apk")
        val missingInput = ApkAssembler().assemble(
            f.request().copy(appDexDirectory = File(f.root, "missing-app-dex")),
            CancellationToken()
        )
        assertFailure(missingInput, "APK_ASSEMBLY_INPUT_INVALID")
        check(!f.output.exists()) { "Invalid input left a stale APK behind" }
        check(f.output.parentFile.listFiles().orEmpty().isEmpty()) { "Invalid input left a temporary APK behind" }

        f.output.writeText("stale apk")
        File(f.app, "classes.dex").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
        val failed = ApkAssembler().assemble(f.request(), CancellationToken())
        assertFailure(failed, "APK_DEX_INPUT_INVALID")
        check(!f.output.exists())
        check(f.output.parentFile.listFiles().orEmpty().isEmpty()) { "Failure left a temporary APK behind" }
    }

    private fun rejectsOutputSymlinkWhenSupported() = withFixture { f ->
        createBase(f.base)
        writeDex(File(f.app, "classes.dex"), 1)
        f.output.parentFile.mkdirs()
        val sentinel = File(f.root, "sentinel.txt").apply { writeText("must remain unchanged") }
        try {
            Files.createSymbolicLink(f.output.toPath(), sentinel.toPath())
        } catch (e: Exception) {
            println("ApkAssemblerTests: symlink case skipped (${e.javaClass.simpleName})")
            return@withFixture
        }
        check(Files.isSymbolicLink(f.output.toPath()))
        val result = ApkAssembler().assemble(f.request(), CancellationToken())
        assertFailure(result, "APK_ASSEMBLY_OUTPUT_INVALID")
        check(Files.isSymbolicLink(f.output.toPath()))
        check(sentinel.readText() == "must remain unchanged")
    }

    private fun rejectsOutputAncestorSymlinkWhenSupported() = withFixture { f ->
        createBase(f.base)
        writeDex(File(f.app, "classes.dex"), 1)

        val outside = File(f.root, "outside-apk-parent").apply { mkdirs() }
        val sentinel = File(outside, "sentinel.txt").apply { writeText("preserve") }
        val link = File(f.root, "apk-link")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            println("ApkAssemblerTests: ancestor symlink case skipped (${e.javaClass.simpleName})")
            return@withFixture
        }

        val redirectedOutput = File(link, "unsigned-unaligned.apk")
        val result = ApkAssembler().assemble(
            f.request().copy(outputApk = redirectedOutput),
            CancellationToken()
        )
        assertFailure(result, "APK_ASSEMBLY_OUTPUT_INVALID")
        check(sentinel.readText() == "preserve")
        check(!File(outside, "unsigned-unaligned.apk").exists()) {
            "Assembler followed a symlinked output ancestor"
        }
    }

    private fun deterministicRerunHasIdenticalBytes() = withFixture { f ->
        createBase(f.base, listOf(
            Entry("AndroidManifest.xml", byteArrayOf(1, 3, 5)),
            Entry("resources.arsc", byteArrayOf(2, 4, 6), ZipEntry.STORED),
            Entry("res/z.bin", byteArrayOf(9, 8, 7)), Entry("res/a.bin", byteArrayOf(6, 5, 4))
        ))
        writeDex(File(f.app, "classes.dex"), 1)
        writeDex(File(f.app, "classes2.dex"), 2)
        Files.createDirectories(f.runtime.toPath())
        writeDex(File(f.runtime, "classes.dex"), 3)
        val request = f.request(runtime = f.runtime)

        val first = ApkAssembler().assemble(request, CancellationToken())
        assertSuccess(first, f.output)
        val firstBytes = f.output.readBytes()
        val firstSha = sha256(firstBytes)
        val second = ApkAssembler().assemble(request, CancellationToken())
        assertSuccess(second, f.output)
        check(sha256(f.output.readBytes()) == firstSha)
        check(first.artifacts.single().sha256 == second.artifacts.single().sha256)
        check(firstBytes.contentEquals(f.output.readBytes()))
    }

    private fun assertSuccess(result: ApkAssemblyResult, output: File) {
        check(result.outcome == BuildOutcome.SUCCESS) { "Expected success, got ${result.outcome}: ${result.diagnostics}" }
        check(result.diagnostics.none { it.severity.name == "ERROR" }) { "Unexpected error diagnostics: ${result.diagnostics}" }
        check(output.isFile && output.length() > 0L)
        val artifact = result.artifacts.single()
        check(artifact.kind == "android-unsigned-apk")
        check(artifact.file.canonicalFile == output.canonicalFile)
        check(artifact.sha256 == sha256(output.readBytes()))
    }

    private fun assertFailure(result: ApkAssemblyResult, code: String) {
        check(result.outcome == BuildOutcome.FAILED) { "Expected failure $code, got ${result.outcome}: ${result.diagnostics}" }
        check(result.diagnostics.any { it.code == code }) { "Expected $code, got ${result.diagnostics}" }
        check(result.diagnostics.all { it.source == BuildDiagnosticSource.PACKAGING })
        check(result.artifacts.isEmpty())
    }

    private fun assertResourceLimit(result: ApkAssemblyResult, code: String) {
        assertFailureOutcome(result, BuildOutcome.RESOURCE_LIMIT, code)
    }

    private fun assertFailureOutcome(result: ApkAssemblyResult, outcome: BuildOutcome, code: String) {
        check(result.outcome == outcome) { "Expected $outcome/$code, got ${result.outcome}: ${result.diagnostics}" }
        check(result.diagnostics.any { it.code == code }) { "Expected $code, got ${result.diagnostics}" }
        check(result.diagnostics.all { it.source == BuildDiagnosticSource.PACKAGING })
        check(result.artifacts.isEmpty())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("vega-ap1-test").toFile()
        try {
            val fixture = Fixture(
                root = root,
                base = File(root, "resources.ap_"),
                app = File(root, "app-dex").apply { mkdirs() },
                runtime = File(root, "runtime-dex"),
                output = File(root, "apk/unsigned-unaligned.apk")
            )
            block(fixture)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val root: File,
        val base: File,
        val app: File,
        val runtime: File,
        val output: File
    ) {
        fun request(
            base: File = this.base,
            runtime: File? = null
        ) = ApkAssemblyRequest(
            baseResourcePackage = base,
            appDexDirectory = app,
            runtimeDexDirectory = runtime,
            outputApk = output
        )
    }

    private data class Entry(
        val name: String,
        val bytes: ByteArray,
        val method: Int = ZipEntry.DEFLATED
    )

    private fun createBase(file: File, entries: List<Entry> = listOf(
        Entry("AndroidManifest.xml", byteArrayOf(1, 2, 3)),
        Entry("resources.arsc", byteArrayOf(4, 5, 6), ZipEntry.STORED)
    )) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for (entry in entries) {
                val zipEntry = ZipEntry(entry.name).apply {
                    method = entry.method
                    if (method == ZipEntry.STORED) {
                        size = entry.bytes.size.toLong()
                        compressedSize = entry.bytes.size.toLong()
                        crc = CRC32().apply { update(entry.bytes) }.value
                    }
                }
                zip.putNextEntry(zipEntry)
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun writeDex(file: File, marker: Int) {
        file.parentFile.mkdirs()
        file.writeBytes(dexBytes(marker))
    }

    private fun dexBytes(marker: Int): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0,
        marker.toByte(), 0x23, 0x45, 0x67, 0x11, 0x22, 0x33, 0x44
    )

    private fun dexName(index: Int): String = if (index == 1) "classes.dex" else "classes${index}.dex"

    private fun read(zip: ZipFile, name: String): ByteArray =
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }

    private fun replaceAscii(file: File, from: String, to: String) {
        require(from.toByteArray(StandardCharsets.US_ASCII).size == to.toByteArray(StandardCharsets.US_ASCII).size)
        val bytes = file.readBytes()
        val source = from.toByteArray(StandardCharsets.US_ASCII)
        val target = to.toByteArray(StandardCharsets.US_ASCII)
        var replacements = 0
        var index = 0
        while (index <= bytes.size - source.size) {
            var matches = true
            for (offset in source.indices) if (bytes[index + offset] != source[offset]) {
                matches = false
                break
            }
            if (matches) {
                target.copyInto(bytes, index)
                replacements++
                index += source.size
            } else {
                index++
            }
        }
        check(replacements == 2) { "Expected local and central ZIP name, replaced $replacements occurrences" }
        file.writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
