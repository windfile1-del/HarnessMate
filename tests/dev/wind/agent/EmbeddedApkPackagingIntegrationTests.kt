package dev.wind.agent

import dev.wind.agent.build.Aapt2InvocationResult
import dev.wind.agent.build.Aapt2Provider
import dev.wind.agent.build.Aapt2ResourceCompiler
import dev.wind.agent.build.ApkAlignmentInvocation
import dev.wind.agent.build.ApkAlignmentInvocationResult
import dev.wind.agent.build.ApkAlignmentProvider
import dev.wind.agent.build.ApkAlignmentVerifier
import dev.wind.agent.build.BuildContext
import dev.wind.agent.build.BuildDiagnosticSeverity
import dev.wind.agent.build.BuildOutcome
import dev.wind.agent.build.BuildTaskOutcome
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.D8Invocation
import dev.wind.agent.build.D8InvocationResult
import dev.wind.agent.build.D8Provider
import dev.wind.agent.build.EmbeddedApkPackagingSupport
import dev.wind.agent.build.EmbeddedBuildBackend
import dev.wind.agent.build.EmbeddedCoreLibraryDesugaringSupport
import dev.wind.agent.build.EmbeddedProjectModelParser
import dev.wind.agent.build.L8Invocation
import dev.wind.agent.build.L8InvocationResult
import dev.wind.agent.build.L8Provider
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.ReflectionZipflingerAlignmentProvider
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Focused P6-BE5-A transaction tests; intentionally not part of BuildCoreTests.main(). */
object EmbeddedApkPackagingIntegrationTests {
    @JvmStatic
    fun main(args: Array<String>) {
        appOnlyPackageIsAlignedAndStopsAtSigning()
        currentRunL8RuntimeDexIsPackaged()
        ap1FailureRemovesBothStalePackageOutputsAndSkipsAp2()
        ap2FailureUnavailableAndCancellationRollback()
        rollbackFailureUpgradesCancellationToFailure()
        packageOwnedOutputPathSafetyFailsClosed()
        legacyConstructorRemainsPackagingUnsupported()
        println("EmbeddedApkPackagingIntegrationTests: OK")
    }

    private fun appOnlyPackageIsAlignedAndStopsAtSigning() = withFixture { fixture ->
        val suffix = moduleSuffix(":")
        val output = File(fixture.root, "out")
        File(output, "modules/$suffix/dex/desugar-runtime/classes.dex").apply {
            parentFile.mkdirs()
            writeBytes(validDexFixture(71))
        }
        val oldApks = seedOwnedApks(output, suffix)
        val backend = backend(fixture, ReflectionZipflingerAlignmentProvider())
        val result = fixture.execute(backend, output)

        check(result.outcome == BuildOutcome.UNSUPPORTED) { "Expected signing-only stop, got ${result.outcome}: ${result.diagnostics}" }
        check(taskOutcome(result, "dex:") == BuildTaskOutcome.EXECUTED)
        check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.EXECUTED)
        check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.FAILED)
        check(result.diagnostics.filter { it.severity == BuildDiagnosticSeverity.ERROR }
            .single().code == "EMBEDDED_APK_SIGNING_UNSUPPORTED") { "Signing must be the only error: ${result.diagnostics}" }
        check(result.diagnostics.any { it.code == "APK_UNSIGNED_UNALIGNED" })
        check(result.diagnostics.any { it.code == "APK_ALIGNMENT_VERIFIED" })
        check(result.producedArtifacts.isEmpty())
        check(oldApks.all { file ->
            file.isFile && runCatching {
                ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
            }.getOrDefault(false)
        }) { "Package task did not replace the pre-existing APK bytes with valid current-run outputs" }

        val unaligned = packageApk(output, suffix, "unsigned-unaligned.apk")
        val aligned = packageApk(output, suffix, "unsigned-aligned.apk")
        check(unaligned.isFile && aligned.isFile)
        val verification = ApkAlignmentVerifier().verify(aligned)
        check(verification.outcome == BuildOutcome.SUCCESS && verification.aligned)
        ZipFile(aligned).use { apk ->
            check(apk.getEntry("classes.dex") != null)
            check(apk.getEntry("classes2.dex") == null) {
                "Stale runtime DEX was included without a current-run L8 artifact"
            }
        }
        val inputFingerprints = ApkAlignmentVerifier().verify(unaligned).entries
        check(verification.entries == inputFingerprints) { "Alignment changed payload fingerprints" }
    }

    private fun currentRunL8RuntimeDexIsPackaged() = withFixture { fixture ->
        val suffix = moduleSuffix(":")
        val output = File(fixture.root, "out")
        val runtimeDexBytes = validDexFixture(99)
        val d8 = FakeD8Provider(keepRules = "-keep class j$.nio.** { *; }\n")
        val l8 = FakeL8Provider(runtimeDexBytes)
        val backend = backend(
            fixture,
            ReflectionZipflingerAlignmentProvider(),
            d8 = d8,
            l8 = l8
        )
        val result = fixture.execute(backend, output)

        check(taskOutcome(result, "dex:") == BuildTaskOutcome.EXECUTED)
        check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.EXECUTED)
        check(l8.invocations == 1)
        val aligned = packageApk(output, suffix, "unsigned-aligned.apk")
        ZipFile(aligned).use { apk ->
            check(apk.getEntry("classes.dex") != null)
            val runtime = apk.getInputStream(apk.getEntry("classes2.dex")).use { it.readBytes() }
            check(runtime.contentEquals(runtimeDexBytes)) { "Current-run L8 DEX payload was not preserved" }
        }
    }

    private fun ap1FailureRemovesBothStalePackageOutputsAndSkipsAp2() = withFixture { fixture ->
        val suffix = moduleSuffix(":")
        val output = File(fixture.root, "out")
        val owned = seedOwnedApks(output, suffix)
        val alignmentProvider = RecordingAlignmentProvider(available = true) { _, _ ->
            error("AP2 must not run after AP1 failure")
        }
        val d8 = FakeD8Provider(afterCompile = { invocation ->
            val moduleRoot = invocation.outputDirectory.parentFile.parentFile
            File(moduleRoot, "resources/linked/resources.ap_").writeText("not-a-zip")
        })
        val backend = backend(
            fixture,
            alignmentProvider,
            d8 = d8
        )
        val result = fixture.execute(backend, output)

        check(result.outcome == BuildOutcome.FAILED) { "Expected AP1 failure: ${result.diagnostics}" }
        check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.FAILED)
        check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
        check(result.diagnostics.any { it.code == "APK_BASE_PACKAGE_INVALID" })
        check(alignmentProvider.calls == 0) { "AP2 was called after AP1 failed" }
        check(owned.none { it.exists() }) { "AP1 failure left a stale or partial package-owned APK" }
    }

    private fun ap2FailureUnavailableAndCancellationRollback() {
        withFixture { fixture ->
            val suffix = moduleSuffix(":")
            val output = File(fixture.root, "out")
            val owned = seedOwnedApks(output, suffix)
            val provider = RecordingAlignmentProvider(available = true) { invocation, _ ->
                invocation.outputApk.writeBytes(byteArrayOf(1, 2, 3))
                ApkAlignmentInvocationResult(success = false, diagnostics = "synthetic AP2 failure")
            }
            val result = fixture.execute(backend(fixture, provider), output)
            check(result.outcome == BuildOutcome.FAILED)
            check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.FAILED)
            check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
            check(result.diagnostics.any { it.code == "APK_ALIGNMENT_FAILED" })
            check(provider.calls == 1)
            check(owned.none { it.exists() }) { "AP2 failure did not roll back both package outputs" }
        }

        withFixture { fixture ->
            val suffix = moduleSuffix(":")
            val output = File(fixture.root, "out")
            val owned = seedOwnedApks(output, suffix)
            val unavailable = RecordingAlignmentProvider(available = false) { _, _ ->
                error("Unavailable provider must not be invoked")
            }
            val result = fixture.execute(backend(fixture, unavailable), output)
            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.SKIPPED)
            check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
            check(result.diagnostics.any { it.code == "APK_ALIGNER_UNAVAILABLE" })
            check(unavailable.calls == 0) { "Unavailable AP2 provider was invoked" }
            check(owned.none { it.exists() }) { "Unavailable AP2 provider left the AP1 output behind" }
        }

        withFixture { fixture ->
            val suffix = moduleSuffix(":")
            val output = File(fixture.root, "out")
            val owned = seedOwnedApks(output, suffix)
            val token = CancellationToken()
            val provider = RecordingAlignmentProvider(available = true) { invocation, cancellation ->
                invocation.outputApk.writeBytes(byteArrayOf(1, 2, 3))
                cancellation.cancel()
                ApkAlignmentInvocationResult(success = true)
            }
            val result = fixture.execute(backend(fixture, provider), output, token)
            check(result.outcome == BuildOutcome.CANCELLED)
            check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.CANCELLED)
            check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
            check(result.diagnostics.any { it.code == "BUILD_CANCELLED" })
            check(owned.none { it.exists() }) { "Cancelled AP2 did not roll back both package outputs" }
        }
    }

    private fun rollbackFailureUpgradesCancellationToFailure() = withFixture { fixture ->
        val suffix = moduleSuffix(":")
        val output = File(fixture.root, "out")
        seedOwnedApks(output, suffix)
        val token = CancellationToken()
        val provider = RecordingAlignmentProvider(available = true) { invocation, cancellation ->
            check(invocation.inputApk.delete()) { "Could not replace AP1 output for rollback-failure fixture" }
            check(invocation.inputApk.mkdir()) { "Could not create unsafe package-owned target fixture" }
            File(invocation.inputApk, "preserve-marker.txt").writeText("do not recursively delete")
            cancellation.cancel()
            ApkAlignmentInvocationResult(success = true)
        }
        val result = fixture.execute(backend(fixture, provider), output, token)
        val unaligned = packageApk(output, suffix, "unsigned-unaligned.apk")

        check(result.outcome == BuildOutcome.FAILED) { "Rollback failure must dominate cancellation: ${result.diagnostics}" }
        check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.FAILED)
        check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
        check(result.diagnostics.any { it.code == "BUILD_CANCELLED" }) { "Original AP2 cancellation diagnostic was lost" }
        check(result.diagnostics.any { it.code == "EMBEDDED_APK_OUTPUT_CLEANUP_FAILED" })
        check(unaligned.isDirectory)
        check(File(unaligned, "preserve-marker.txt").readText() == "do not recursively delete") {
            "Rollback traversed a non-ordinary package-owned target"
        }
        check(!packageApk(output, suffix, "unsigned-aligned.apk").exists())
        check(provider.calls == 1)
    }

    private fun packageOwnedOutputPathSafetyFailsClosed() = withFixture { fixture ->
        val suffix = moduleSuffix(":")
        val output = File(fixture.root, "out")
        val apkDirectory = File(output, "modules/$suffix/apk")
        val unsafeAlignedTarget = File(apkDirectory, "unsigned-aligned.apk").apply {
            mkdirs()
            File(this, "marker.txt").writeText("preserve")
        }
        val unrelated = File(apkDirectory, "unowned-note.txt").apply { writeText("keep") }
        val provider = RecordingAlignmentProvider(available = true) { _, _ ->
            error("AP2 must not run when a package-owned output path is unsafe")
        }
        val result = fixture.execute(backend(fixture, provider), output)

        check(result.outcome == BuildOutcome.FAILED)
        check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.FAILED)
        check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
        check(result.diagnostics.single().code == "EMBEDDED_APK_OUTPUT_UNSAFE")
        check(provider.calls == 0) { "AP2 ran after package-owned path validation failed" }
        check(File(output, "modules/$suffix/dex/app/classes.dex").isFile)
        check(File(output, "modules/$suffix/resources/linked/resources.ap_").isFile)
        check(!packageApk(output, suffix, "unsigned-unaligned.apk").exists()) {
            "AP1 ran despite package-owned output path validation failure"
        }
        check(File(unsafeAlignedTarget, "marker.txt").readText() == "preserve")
        check(unrelated.readText() == "keep") { "Path preflight changed an unowned sibling" }
    }

    private fun legacyConstructorRemainsPackagingUnsupported() = withFixture { fixture ->
        val output = File(fixture.root, "out")
        val legacy = EmbeddedBuildBackend(
            bootClasspath = { listOf(androidJar()) },
            aapt2Compiler = Aapt2ResourceCompiler(FakeAapt2Provider()),
            d8Compiler = D8DexCompiler(FakeD8Provider())
        )
        val result = fixture.execute(legacy, output)
        check(result.outcome == BuildOutcome.UNSUPPORTED)
        check(taskOutcome(result, "dex:") == BuildTaskOutcome.EXECUTED)
        check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.FAILED)
        check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
        check(result.diagnostics.single().code == "EMBEDDED_APK_PACKAGING_UNSUPPORTED")
    }

    private fun backend(
        fixture: Fixture,
        alignmentProvider: ApkAlignmentProvider,
        aapt2: Aapt2ResourceCompiler = Aapt2ResourceCompiler(FakeAapt2Provider()),
        d8: FakeD8Provider = FakeD8Provider(),
        l8: FakeL8Provider? = null
    ): EmbeddedBuildBackend {
        val d8Compiler = D8DexCompiler(d8)
        val packaging = EmbeddedApkPackagingSupport(alignmentProvider)
        return if (l8 == null) {
            EmbeddedBuildBackend(
                bootClasspath = { listOf(androidJar()) },
                aapt2Compiler = aapt2,
                d8Compiler = d8Compiler,
                apkPackaging = packaging
            )
        } else {
            val runtimeJar = File(fixture.root, "desugar-runtime.jar").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            EmbeddedBuildBackend(
                bootClasspath = { listOf(androidJar()) },
                aapt2Compiler = aapt2,
                d8Compiler = d8Compiler,
                coreLibraryDesugaring = EmbeddedCoreLibraryDesugaringSupport(
                    configurationJson = "{\"configuration_format_version\":200,\"identifier\":\"fixture\"}",
                    programFiles = listOf(runtimeJar),
                    l8Compiler = L8RuntimeDexCompiler(l8)
                ),
                apkPackaging = packaging
            )
        }
    }

    private fun androidJar(): File = File("assets/embedded/android-35/android.jar").canonicalFile

    private fun taskOutcome(result: dev.wind.agent.build.BuildResult, prefix: String): BuildTaskOutcome =
        result.taskResults.single { it.id.startsWith(prefix) }.outcome

    private fun packageApk(output: File, suffix: String, name: String) =
        File(output, "modules/$suffix/apk/$name")

    private fun seedOwnedApks(output: File, suffix: String): List<File> = listOf(
        packageApk(output, suffix, "unsigned-unaligned.apk"),
        packageApk(output, suffix, "unsigned-aligned.apk")
    ).onEachIndexed { index, file ->
        file.parentFile.mkdirs()
        file.writeText("stale-$index")
    }

    private fun validDexFixture(marker: Int): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '9'.code.toByte(), 0, marker.toByte(), 0
    )

    private fun moduleSuffix(name: String): String = name.toByteArray(Charsets.UTF_8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private inline fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("vega-be5a-package").toFile()
        try {
            block(Fixture(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(val root: File) {
        init {
            File(root, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\n" +
                    "android { namespace = \"dev.wind.fixture\"; compileSdk = 35; " +
                    "defaultConfig { minSdk = 23; targetSdk = 35 } }"
            )
            File(root, "src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                    "package=\"dev.wind.fixture\"><application android:label=\"@string/app_name\" /></manifest>")
            }
            File(root, "src/main/res/values/strings.xml").apply {
                parentFile.mkdirs()
                writeText("<resources><string name=\"app_name\">BE5</string></resources>")
            }
            File(root, "src/main/java/dev/wind/fixture/ResourceUser.java").apply {
                parentFile.mkdirs()
                writeText("package dev.wind.fixture; public final class ResourceUser { " +
                    "public static int label() { return R.string.app_name; } }")
            }
        }

        fun execute(
            backend: EmbeddedBuildBackend,
            output: File,
            token: CancellationToken = CancellationToken()
        ): dev.wind.agent.build.BuildResult {
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { "Android fixture was rejected: ${model.unsupportedReasons}" }
            val context = BuildContext(model, output)
            return backend.execute(context, backend.plan(context), token)
        }
    }

    private class FakeAapt2Provider(
        private val includeResourceTable: Boolean = true
    ) : Aapt2Provider {
        override val available = true

        override fun execute(
            arguments: List<String>,
            workingDirectory: File,
            token: CancellationToken
        ): Aapt2InvocationResult {
            if (token.isCancelled) return Aapt2InvocationResult(-1, stderr = "cancelled")
            return when (arguments.firstOrNull()) {
                "compile" -> {
                    writeZip(option(arguments, "-o"), listOf("compiled/resources.bin" to bytes("compiled")))
                    Aapt2InvocationResult(0)
                }
                "link" -> {
                    val entries = mutableListOf("AndroidManifest.xml" to bytes("manifest"))
                    if (includeResourceTable) entries += "resources.arsc" to bytes("resource-table")
                    writeZip(option(arguments, "-o"), entries)
                    File(option(arguments, "--java"), "dev/wind/fixture/R.java").apply {
                        parentFile.mkdirs()
                        writeText("package dev.wind.fixture; public final class R { " +
                            "public static final class string { public static final int app_name = 0x7f010001; } }")
                    }
                    option(arguments, "--output-text-symbols").apply {
                        parentFile.mkdirs()
                        writeText("int string app_name 0x7f010001\n")
                    }
                    Aapt2InvocationResult(0)
                }
                else -> Aapt2InvocationResult(2, stderr = "unexpected fake AAPT2 operation")
            }
        }

        private fun option(arguments: List<String>, key: String): File {
            val index = arguments.indexOf(key)
            check(index >= 0 && index + 1 < arguments.size) { "Missing fake AAPT2 option $key: $arguments" }
            return File(arguments[index + 1])
        }
    }

    private class FakeD8Provider(
        private val keepRules: String? = null,
        private val afterCompile: (D8Invocation) -> Unit = {}
    ) : D8Provider {
        override val available = true

        override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
            if (token.isCancelled) return D8InvocationResult(false, "cancelled")
            File(invocation.outputDirectory, "classes.dex").writeBytes(validDexFixture(31))
            afterCompile(invocation)
            return D8InvocationResult(success = true, desugaredLibraryKeepRules = keepRules)
        }
    }

    private class FakeL8Provider(private val runtimeDex: ByteArray) : L8Provider {
        override val available = true
        var invocations: Int = 0
            private set

        override fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult {
            invocations++
            if (token.isCancelled) return L8InvocationResult(false, "cancelled")
            File(invocation.outputDirectory, "classes.dex").writeBytes(runtimeDex)
            return L8InvocationResult(success = true)
        }
    }

    private class RecordingAlignmentProvider(
        override val available: Boolean,
        private val operation: (ApkAlignmentInvocation, CancellationToken) -> ApkAlignmentInvocationResult
    ) : ApkAlignmentProvider {
        var calls: Int = 0
            private set

        override fun align(
            invocation: ApkAlignmentInvocation,
            token: CancellationToken
        ): ApkAlignmentInvocationResult {
            calls++
            return operation(invocation, token)
        }
    }

    private fun writeZip(output: File, entries: List<Pair<String, ByteArray>>) {
        output.parentFile.mkdirs()
        ZipOutputStream(output.outputStream()).use { zip ->
            entries.forEach { (name, payload) ->
                val entry = ZipEntry(name)
                if (name == "resources.arsc") {
                    entry.method = ZipEntry.STORED
                    entry.size = payload.size.toLong()
                    entry.compressedSize = payload.size.toLong()
                    entry.crc = CRC32().apply { update(payload) }.value
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
        }
    }

    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
}
