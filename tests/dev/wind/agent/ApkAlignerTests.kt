package dev.wind.agent

import dev.wind.agent.build.ApkAligner
import dev.wind.agent.build.ApkAlignmentInvocation
import dev.wind.agent.build.ApkAlignmentInvocationResult
import dev.wind.agent.build.ApkAlignmentProvider
import dev.wind.agent.build.ApkAlignmentRequest
import dev.wind.agent.build.ApkAlignmentVerifier
import dev.wind.agent.build.ApkAssembler
import dev.wind.agent.build.ApkAssemblyRequest
import dev.wind.agent.build.BuildDiagnosticSource
import dev.wind.agent.build.BuildOutcome
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Contract and independent-verifier tests for P6-AP2A. */
object ApkAlignerTests {
    private const val FIXED_ZIP_TIME = 315_532_800_000L // 1980-01-01 00:00:00 UTC

    @JvmStatic
    fun main(args: Array<String>) {
        fakeProviderContractsAndIndependentRejection()
        rejectsV1V2SignaturesAndNativeLibraries()
        verifierChecksAlignmentCorruptionAndUnsupportedZipSubset()
        outputSafetyStaleCleanupAndCancellation()
        payloadFingerprintsAndDeterminism()
        hostZipalignIntegrationIfExplicitlyConfigured()
        println("ApkAlignerTests: OK")
    }

    private fun fakeProviderContractsAndIndependentRejection() = withFixture { f ->
        createApk(f.input)
        checkUnaligned(f.input)
        var calls = 0

        val unavailableOutput = File(f.root, "unavailable/aligned.apk")
        unavailableOutput.parentFile.mkdirs()
        unavailableOutput.writeText("stale")
        val unavailable = ApkAligner(FakeProvider(available = false) { _, _ ->
            calls++
            error("unavailable alignment provider must not be invoked")
        }).align(f.request(unavailableOutput), CancellationToken())
        assertOutcome(unavailable, BuildOutcome.UNSUPPORTED, "APK_ALIGNER_UNAVAILABLE")
        check(!unavailableOutput.exists()) { "Unavailable provider left stale output" }
        check(calls == 0)

        val failedOutput = File(f.root, "provider-failed/aligned.apk")
        failedOutput.parentFile.mkdirs()
        failedOutput.writeText("stale")
        val failed = ApkAligner(FakeProvider { _, _ ->
            calls++
            ApkAlignmentInvocationResult(success = false, diagnostics = "synthetic zipalign failure")
        }).align(f.request(failedOutput), CancellationToken())
        assertOutcome(failed, BuildOutcome.FAILED, "APK_ALIGNMENT_FAILED")
        check(!failedOutput.exists())
        check(failedOutput.parentFile.listFiles().orEmpty().isEmpty()) { "Provider failure left a temporary APK" }

        val missingOutput = File(f.root, "provider-missing/aligned.apk")
        val missing = ApkAligner(FakeProvider { _, _ ->
            calls++
            ApkAlignmentInvocationResult(success = true)
        }).align(f.request(missingOutput), CancellationToken())
        assertOutcome(missing, BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_MISSING")
        check(!missingOutput.exists())

        val copyOutput = File(f.root, "provider-copy/aligned.apk")
        val copy = ApkAligner(FakeProvider { invocation, _ ->
            calls++
            Files.copy(invocation.inputApk.toPath(), invocation.outputApk.toPath())
            ApkAlignmentInvocationResult(success = true)
        }).align(f.request(copyOutput), CancellationToken())
        assertOutcome(copy, BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID")
        check(!copyOutput.exists()) { "Provider output that merely copied an unaligned APK was published" }
        check(copyOutput.parentFile.listFiles().orEmpty().isEmpty())
        check(calls == 3)
    }

    private fun rejectsV1V2SignaturesAndNativeLibraries() = withFixture { f ->
        val provider = FakeProvider { _, _ -> error("Rejected APK must not reach provider") }

        createApk(f.input, extra = listOf(ZipPayload("META-INF/CERT.SF", bytes("signature"))))
        assertOutcome(
            ApkAligner(provider).align(f.request(File(f.root, "v1/out.apk")), CancellationToken()),
            BuildOutcome.FAILED,
            "APK_ALIGNMENT_SIGNED_INPUT"
        )

        createApk(f.input)
        addApkSigningBlock(f.input)
        val block = ApkAlignmentVerifier().verify(f.input)
        check(block.outcome == BuildOutcome.SUCCESS && block.apkSigningBlockPresent) {
            "Verifier did not detect synthetic APK Signing Block: ${block.diagnostics}"
        }
        assertOutcome(
            ApkAligner(provider).align(f.request(File(f.root, "v2/out.apk")), CancellationToken()),
            BuildOutcome.FAILED,
            "APK_ALIGNMENT_SIGNED_INPUT"
        )

        createApk(f.input, extra = listOf(
            ZipPayload("lib/arm64-v8a/libfoo.so", bytes("native-library"), ZipEntry.STORED)
        ))
        val native = ApkAligner(provider).align(f.request(File(f.root, "native/out.apk")), CancellationToken())
        assertOutcome(native, BuildOutcome.UNSUPPORTED, "APK_ALIGNMENT_NATIVE_UNSUPPORTED")
        check(native.artifacts.isEmpty())
    }

    private fun verifierChecksAlignmentCorruptionAndUnsupportedZipSubset() = withFixture { f ->
        createApk(f.input)
        val unaligned = ApkAlignmentVerifier().verify(f.input)
        check(unaligned.outcome == BuildOutcome.SUCCESS) { "Valid unaligned ZIP rejected: ${unaligned.diagnostics}" }
        check(!unaligned.aligned) { "Synthetic input unexpectedly satisfies 4-byte stored payload alignment" }

        val alignedFile = File(f.root, "aligned-fixture.apk")
        writeAlignedCopy(f.input, alignedFile)
        val aligned = ApkAlignmentVerifier().verify(alignedFile)
        check(aligned.outcome == BuildOutcome.SUCCESS && aligned.aligned) {
            "Independent verifier rejected aligned fixture: ${aligned.diagnostics}"
        }
        check(aligned.entries.map { Triple(it.name, it.method, it.uncompressedSize) } ==
            unaligned.entries.map { Triple(it.name, it.method, it.uncompressedSize) })

        val corrupt = File(f.root, "corrupt-local-name.apk")
        Files.copy(alignedFile.toPath(), corrupt.toPath())
        replaceFirst(corrupt, bytes("AndroidManifest.xml"), bytes("AndroidManifest.xlm"))
        val corrupted = ApkAlignmentVerifier().verify(corrupt)
        assertOutcome(corrupted, BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID")

        val zip64 = File(f.root, "zip64-sentinel.apk")
        Files.copy(alignedFile.toPath(), zip64.toPath())
        patchZip64Eocd(zip64)
        assertOutcome(
            ApkAlignmentVerifier().verify(zip64),
            BuildOutcome.UNSUPPORTED,
            "APK_ALIGNMENT_ZIP_UNSUPPORTED"
        )

        val multiDisk = File(f.root, "multidisk.apk")
        Files.copy(alignedFile.toPath(), multiDisk.toPath())
        patchEocdU16(multiDisk, eocdFieldOffset = 4, value = 1)
        assertOutcome(
            ApkAlignmentVerifier().verify(multiDisk),
            BuildOutcome.UNSUPPORTED,
            "APK_ALIGNMENT_ZIP_UNSUPPORTED"
        )

        val encrypted = File(f.root, "encrypted.apk")
        Files.copy(alignedFile.toPath(), encrypted.toPath())
        setFirstEntryEncrypted(encrypted)
        assertOutcome(
            ApkAlignmentVerifier().verify(encrypted),
            BuildOutcome.UNSUPPORTED,
            "APK_ALIGNMENT_ZIP_UNSUPPORTED"
        )
    }

    private fun outputSafetyStaleCleanupAndCancellation() = withFixture { f ->
        createApk(f.input)
        val provider = FakeProvider { invocation, _ ->
            writeAlignedCopy(invocation.inputApk, invocation.outputApk)
            ApkAlignmentInvocationResult(success = true)
        }

        val samePath = f.input.readBytes()
        val overlap = ApkAligner(provider).align(f.request(f.input), CancellationToken())
        assertOutcome(overlap, BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID")
        check(f.input.readBytes().contentEquals(samePath)) { "Output/input overlap modified the source APK" }

        val inputLink = File(f.root, "input-link.apk")
        try {
            Files.createSymbolicLink(inputLink.toPath(), f.input.toPath())
            val linkedInput = ApkAligner(provider).align(
                ApkAlignmentRequest(inputApk = inputLink, outputApk = File(f.root, "input-link-output.apk")),
                CancellationToken()
            )
            assertOutcome(linkedInput, BuildOutcome.FAILED, "APK_ALIGNMENT_INPUT_INVALID")
            check(f.input.readBytes().contentEquals(samePath))
        } catch (error: UnsupportedOperationException) {
            println("ApkAlignerTests: input symlink case skipped (${error.javaClass.simpleName})")
        } catch (error: SecurityException) {
            println("ApkAlignerTests: input symlink case skipped (${error.javaClass.simpleName})")
        } catch (error: java.io.IOException) {
            println("ApkAlignerTests: input symlink case skipped (${error.javaClass.simpleName})")
        }

        val staleOutput = File(f.root, "stale/aligned.apk").apply { parentFile.mkdirs(); writeText("old artifact") }
        val failProvider = FakeProvider { _, _ -> ApkAlignmentInvocationResult(false, "expected failure") }
        assertOutcome(
            ApkAligner(failProvider).align(f.request(staleOutput), CancellationToken()),
            BuildOutcome.FAILED,
            "APK_ALIGNMENT_FAILED"
        )
        check(!staleOutput.exists())
        check(staleOutput.parentFile.listFiles().orEmpty().isEmpty())

        val cancelOutput = File(f.root, "cancelled/aligned.apk")
        val token = CancellationToken()
        val cancellingProvider = FakeProvider { invocation, receivedToken ->
            invocation.outputApk.writeBytes(byteArrayOf(1, 2, 3, 4))
            receivedToken.cancel()
            ApkAlignmentInvocationResult(success = true)
        }
        val cancelled = ApkAligner(cancellingProvider).align(f.request(cancelOutput), token)
        assertOutcome(cancelled, BuildOutcome.CANCELLED, "BUILD_CANCELLED")
        check(!cancelOutput.exists())
        check(cancelOutput.parentFile.listFiles().orEmpty().isEmpty()) { "Cancellation left provider temp output" }

        val outputLinkParent = File(f.root, "output-link-parent")
        val outside = File(f.root, "outside").apply { mkdirs() }
        val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
        try {
            Files.createSymbolicLink(outputLinkParent.toPath(), outside.toPath())
            val symlinkResult = ApkAligner(provider).align(
                f.request(File(outputLinkParent, "aligned.apk")), CancellationToken()
            )
            assertOutcome(symlinkResult, BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID")
            check(sentinel.readText() == "keep")
            check(!File(outside, "aligned.apk").exists())
        } catch (error: UnsupportedOperationException) {
            println("ApkAlignerTests: ancestor symlink case skipped (${error.javaClass.simpleName})")
        } catch (error: SecurityException) {
            println("ApkAlignerTests: ancestor symlink case skipped (${error.javaClass.simpleName})")
        } catch (error: java.io.IOException) {
            println("ApkAlignerTests: ancestor symlink case skipped (${error.javaClass.simpleName})")
        }

        val outputTarget = File(f.root, "output-target.txt").apply { writeText("target-preserved") }
        val outputLink = File(f.root, "output-link.apk")
        try {
            Files.createSymbolicLink(outputLink.toPath(), outputTarget.toPath())
            val linked = ApkAligner(provider).align(f.request(outputLink), CancellationToken())
            assertOutcome(linked, BuildOutcome.FAILED, "APK_ALIGNMENT_OUTPUT_INVALID")
            check(Files.isSymbolicLink(outputLink.toPath()))
            check(outputTarget.readText() == "target-preserved")
        } catch (error: UnsupportedOperationException) {
            println("ApkAlignerTests: output symlink case skipped (${error.javaClass.simpleName})")
        } catch (error: SecurityException) {
            println("ApkAlignerTests: output symlink case skipped (${error.javaClass.simpleName})")
        } catch (error: java.io.IOException) {
            println("ApkAlignerTests: output symlink case skipped (${error.javaClass.simpleName})")
        }
    }

    private fun payloadFingerprintsAndDeterminism() = withFixture { f ->
        createApk(f.input, extra = listOf(
            ZipPayload("assets/stored.bin", ByteArray(31) { (it * 3).toByte() }, ZipEntry.STORED),
            ZipPayload("assets/deflated.txt", bytes("repeated payload ".repeat(20)), ZipEntry.DEFLATED)
        ))
        val before = ApkAlignmentVerifier().verify(f.input)
        check(before.outcome == BuildOutcome.SUCCESS && !before.aligned)

        val provider = FakeProvider { invocation, _ ->
            writeAlignedCopy(invocation.inputApk, invocation.outputApk)
            ApkAlignmentInvocationResult(success = true)
        }
        val firstOutput = File(f.root, "first/aligned.apk")
        val secondOutput = File(f.root, "second/aligned.apk")
        val aligner = ApkAligner(provider)
        val first = aligner.align(f.request(firstOutput), CancellationToken())
        check(first.outcome == BuildOutcome.SUCCESS) { "Alignment failed: ${first.diagnostics}" }
        check(first.artifacts.single().kind == "android-aligned-unsigned-apk")
        check(first.artifacts.single().sha256 == sha256(firstOutput))
        val after = ApkAlignmentVerifier().verify(firstOutput)
        check(after.outcome == BuildOutcome.SUCCESS && after.aligned)
        check(before.entries.map { listOf(it.name, it.method, it.uncompressedSize, it.sha256) } ==
            after.entries.map { listOf(it.name, it.method, it.uncompressedSize, it.sha256) }) {
            "Alignment changed entry name, method, size, or uncompressed payload fingerprint"
        }

        val second = aligner.align(f.request(secondOutput), CancellationToken())
        check(second.outcome == BuildOutcome.SUCCESS) { "Deterministic rerun failed: ${second.diagnostics}" }
        check(first.artifacts.single().sha256 == second.artifacts.single().sha256)
        check(firstOutput.readBytes().contentEquals(secondOutput.readBytes()))
    }

    private fun hostZipalignIntegrationIfExplicitlyConfigured() {
        val configured = System.getenv("VEGA_ZIPALIGN")?.takeIf { it.isNotBlank() }
        if (configured == null) {
            println("ApkAlignerTests: host zipalign integration skipped (VEGA_ZIPALIGN is unset)")
            return
        }
        val executable = File(configured)
        check(executable.isFile) { "VEGA_ZIPALIGN does not name an ordinary file: $executable" }
        val root = Files.createTempDirectory("vega-ap2a-host").toFile()
        try {
            val base = File(root, "resources.ap_")
            writeZip(base, listOf(
                ZipPayload("AndroidManifest.xml", bytes("synthetic-manifest")),
                ZipPayload("resources.arsc", bytes("synthetic-resource-table")),
                ZipPayload("res/raw/host-proof.bin", bytes("host payload"), ZipEntry.STORED)
            ), alignStoredEntries = false)
            val appDex = File(root, "app-dex").apply { mkdirs() }
            File(appDex, "classes.dex").writeBytes(validDexBytes())
            val unaligned = File(root, "unsigned-unaligned.apk")
            val assembly = ApkAssembler().assemble(
                ApkAssemblyRequest(baseResourcePackage = base, appDexDirectory = appDex, outputApk = unaligned),
                CancellationToken()
            )
            check(assembly.outcome == BuildOutcome.SUCCESS) { "AP1 fixture assembly failed: ${assembly.diagnostics}" }
            val inputVerification = ApkAlignmentVerifier().verify(unaligned)
            check(inputVerification.outcome == BuildOutcome.SUCCESS && !inputVerification.aligned) {
                "AP1 did not produce a known-unaligned APK: ${inputVerification.diagnostics}"
            }

            val aligner = ApkAligner(dev.wind.agent.build.HostZipalignProvider(executable))
            val firstOutput = File(root, "unsigned-aligned-1.apk")
            val secondOutput = File(root, "unsigned-aligned-2.apk")
            val first = aligner.align(ApkAlignmentRequest(unaligned, firstOutput), CancellationToken())
            check(first.outcome == BuildOutcome.SUCCESS) { "Host zipalign failed: ${first.diagnostics}" }
            val verified = ApkAlignmentVerifier().verify(firstOutput)
            check(verified.outcome == BuildOutcome.SUCCESS && verified.aligned) {
                "Independent verifier rejected host zipalign result: ${verified.diagnostics}"
            }
            val second = aligner.align(ApkAlignmentRequest(unaligned, secondOutput), CancellationToken())
            check(second.outcome == BuildOutcome.SUCCESS) { "Host zipalign deterministic rerun failed: ${second.diagnostics}" }
            check(first.artifacts.single().sha256 == second.artifacts.single().sha256) {
                "Host zipalign output is not deterministic for identical input"
            }
            check(firstOutput.readBytes().contentEquals(secondOutput.readBytes()))

            val externalCheck = ProcessBuilder(
                executable.absolutePath, "-c", "-v", "4", firstOutput.absolutePath
            ).redirectErrorStream(true).start()
            val output = externalCheck.inputStream.use { readUtf8Bounded(it, 64 * 1024) }
            val exitCode = externalCheck.waitFor()
            // Some Build Tools 34 zipalign builds print "succesful" with one s;
            // the process exit code is the stable verification contract.
            check(exitCode == 0) {
                "zipalign -c -v 4 failed (exit=$exitCode): $output"
            }
            check(first.artifacts.single().kind == "android-aligned-unsigned-apk")
            val fingerprints = verified.entries.joinToString(",") {
                "${it.name}:${it.method}:${it.uncompressedSize}:${it.sha256}"
            }
            println("ApkAlignerTests: host zipalign integration OK (${executable.name})")
            println("ApkAlignerTests: inputSha256=${sha256(unaligned)} outputSha256=${first.artifacts.single().sha256}")
            println("ApkAlignerTests: entryFingerprints=$fingerprints")
            println("ApkAlignerTests: zipalignCheckExit=$exitCode output=${output.replace('\n', ' ').trim()}")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertOutcome(result: Any, expected: BuildOutcome, code: String) {
        val outcome: BuildOutcome
        val diagnostics: List<dev.wind.agent.build.BuildDiagnostic>
        val artifacts: List<dev.wind.agent.build.BuildArtifact>
        when (result) {
            is dev.wind.agent.build.ApkAlignmentResult -> {
                outcome = result.outcome
                diagnostics = result.diagnostics
                artifacts = result.artifacts
            }
            is dev.wind.agent.build.ApkAlignmentVerificationResult -> {
                outcome = result.outcome
                diagnostics = result.diagnostics
                artifacts = emptyList()
            }
            else -> error("Unexpected result type ${result.javaClass.name}")
        }
        check(outcome == expected) { "Expected $expected/$code, got $outcome: $diagnostics" }
        check(diagnostics.any { it.code == code }) { "Expected $code, got $diagnostics" }
        check(diagnostics.all { it.source == BuildDiagnosticSource.PACKAGING }) {
            "Alignment diagnostics must use PACKAGING source: $diagnostics"
        }
        check(artifacts.isEmpty()) { "Failure result unexpectedly contains artifacts" }
    }

    private fun checkUnaligned(file: File) {
        val verification = ApkAlignmentVerifier().verify(file)
        check(verification.outcome == BuildOutcome.SUCCESS && !verification.aligned) {
            "Fixture must be valid and unaligned: ${verification.diagnostics}"
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("vega-ap2a-test").toFile()
        try {
            block(Fixture(root, File(root, "unsigned-unaligned.apk")))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(val root: File, val input: File) {
        fun request(output: File) = ApkAlignmentRequest(inputApk = input, outputApk = output)
    }

    private data class ZipPayload(val name: String, val content: ByteArray, val method: Int = ZipEntry.STORED)

    private class FakeProvider(
        override val available: Boolean = true,
        private val operation: (ApkAlignmentInvocation, CancellationToken) -> ApkAlignmentInvocationResult
    ) : ApkAlignmentProvider {
        override fun align(
            invocation: ApkAlignmentInvocation,
            token: CancellationToken
        ): ApkAlignmentInvocationResult = operation(invocation, token)
    }

    private fun createApk(file: File, extra: List<ZipPayload> = emptyList()) {
        val payloads = listOf(
            ZipPayload("AndroidManifest.xml", bytes("synthetic-manifest")),
            ZipPayload("resources.arsc", bytes("synthetic-resource-table")),
            ZipPayload("classes.dex", validDexBytes())
        ) + extra
        writeZip(file, payloads, alignStoredEntries = false)
    }

    private fun writeAlignedCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        val payloads = ZipFile(source).use { zip ->
            zip.entries().toList().map { entry ->
                ZipPayload(entry.name, zip.getInputStream(entry).use { it.readBytes() }, entry.method)
            }
        }
        writeZip(target, payloads, alignStoredEntries = true)
    }

    private fun writeZip(file: File, payloads: List<ZipPayload>, alignStoredEntries: Boolean) {
        file.parentFile?.mkdirs()
        CountingOutputStream(file.outputStream().buffered()).use { counted ->
            ZipOutputStream(counted).use { zip ->
                for (payload in payloads) {
                    val entry = ZipEntry(payload.name).apply {
                        time = FIXED_ZIP_TIME
                        method = payload.method
                        if (payload.method == ZipEntry.STORED) {
                            size = payload.content.size.toLong()
                            compressedSize = payload.content.size.toLong()
                            crc = CRC32().apply { update(payload.content) }.value
                        }
                        if (alignStoredEntries && payload.method == ZipEntry.STORED) {
                            extra = alignmentExtra(counted.count, payload.name)
                        }
                    }
                    zip.putNextEntry(entry)
                    zip.write(payload.content)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun alignmentExtra(localHeaderOffset: Long, name: String): ByteArray {
        val dataOffset = localHeaderOffset + 30L + name.toByteArray(StandardCharsets.UTF_8).size
        val requiredPadding = ((4L - dataOffset % 4L) % 4L).toInt()
        if (requiredPadding == 0) return ByteArray(0)
        val totalLength = requiredPadding + 4
        val extra = ByteArray(totalLength)
        extra[0] = 0xFE.toByte()
        extra[1] = 0xCA.toByte()
        putU16(extra, 2, totalLength - 4)
        return extra
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            out.write(bytes, offset, length)
            count += length.toLong()
        }
    }

    private fun addApkSigningBlock(file: File) {
        val bytes = file.readBytes()
        val eocd = findEocd(bytes)
        val centralOffset = u32(bytes, eocd + 16).toInt()
        val block = ByteArray(32)
        putU64(block, 0, 24L)
        putU64(block, 8, 24L)
        "APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII).copyInto(block, 16)
        val output = ByteArray(bytes.size + block.size)
        bytes.copyInto(output, 0, 0, centralOffset)
        block.copyInto(output, centralOffset)
        bytes.copyInto(output, centralOffset + block.size, centralOffset, bytes.size)
        val shiftedEocd = eocd + block.size
        putU32(output, shiftedEocd + 16, centralOffset.toLong() + block.size)
        file.writeBytes(output)
    }

    private fun patchZip64Eocd(file: File) {
        val bytes = file.readBytes()
        val eocd = findEocd(bytes)
        putU16(bytes, eocd + 8, 0xFFFF)
        putU16(bytes, eocd + 10, 0xFFFF)
        putU32(bytes, eocd + 12, 0xFFFF_FFFFL)
        putU32(bytes, eocd + 16, 0xFFFF_FFFFL)
        file.writeBytes(bytes)
    }

    private fun patchEocdU16(file: File, eocdFieldOffset: Int, value: Int) {
        val bytes = file.readBytes()
        putU16(bytes, findEocd(bytes) + eocdFieldOffset, value)
        file.writeBytes(bytes)
    }

    private fun setFirstEntryEncrypted(file: File) {
        val bytes = file.readBytes()
        val local = findSignature(bytes, 0x04034b50L)
        val central = findSignature(bytes, 0x02014b50L)
        val localFlags = u16(bytes, local + 6)
        val centralFlags = u16(bytes, central + 8)
        putU16(bytes, local + 6, localFlags or 1)
        putU16(bytes, central + 8, centralFlags or 1)
        file.writeBytes(bytes)
    }

    private fun replaceFirst(file: File, old: ByteArray, replacement: ByteArray) {
        require(old.size == replacement.size)
        val bytes = file.readBytes()
        val index = indexOf(bytes, old)
        check(index >= 0)
        replacement.copyInto(bytes, index)
        file.writeBytes(bytes)
    }

    private fun findEocd(bytes: ByteArray): Int {
        for (index in bytes.size - 22 downTo maxOf(0, bytes.size - 65_557)) {
            if (u32(bytes, index) == 0x06054b50L) return index
        }
        error("ZIP EOCD not found")
    }

    private fun findSignature(bytes: ByteArray, signature: Long): Int {
        for (index in 0..bytes.size - 4) if (u32(bytes, index) == signature) return index
        error("ZIP signature 0x${signature.toString(16)} not found")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) {
                matches = false
                break
            }
            if (matches) return start
        }
        return -1
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun putU64(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun validDexBytes(): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0,
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x12
    )

    private fun bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    private fun readUtf8Bounded(input: java.io.InputStream, maxBytes: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var remaining = maxBytes
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
