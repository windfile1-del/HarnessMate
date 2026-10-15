package dev.wind.agent

import dev.wind.agent.build.Aapt2CompileRequest
import dev.wind.agent.build.Aapt2InvocationResult
import dev.wind.agent.build.Aapt2LinkRequest
import dev.wind.agent.build.Aapt2Provider
import dev.wind.agent.build.Aapt2ResourceCompiler
import dev.wind.agent.build.BuildOutcome
import dev.wind.agent.build.BuildTaskOutcome
import dev.wind.agent.build.NativeAapt2Provider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import kotlin.concurrent.thread
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Focused provider and real-native integration coverage for the P6-AR1 primitive. */
object Aapt2ResourceCompilerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        unavailableAndPreCancelledFailClosed()
        compileUsesStructuredArgumentsAndVerifiesOutput()
        compileFailureRemovesCurrentArchive()
        resourceLimitsAndUnsafeTreesDoNotStartProvider()
        linkValidatesInputsBeforeProvider()
        linkVerifiesArtifactsAndRemovesStaleOutputsOnFailure()
        nativeProviderValidatesExecutable()
        nativeProviderCancelsInFlightAndBoundsDiagnostics()
        realNativeCompileAndLink(args.singleOrNull()?.let(::File))
        println("Aapt2ResourceCompilerTests: OK")
    }

    private fun unavailableAndPreCancelledFailClosed() {
        val root = Files.createTempDirectory("vega aapt2 unavailable").toFile()
        try {
            val fixture = fixture(root)
            val provider = RecordingProvider(available = false) { _, _, _ ->
                error("unavailable provider must not execute")
            }
            val compiler = Aapt2ResourceCompiler(provider)
            val compile = compiler.compile(
                Aapt2CompileRequest(fixture.resources, File(root, "out/compiled/res.zip")),
                CancellationToken()
            )
            check(compile.outcome == BuildOutcome.UNSUPPORTED)
            check(compile.diagnostics.single().code == "AAPT2_UNAVAILABLE")
            check(compile.diagnostics.single().source.name == "AAPT2")
            check(compile.taskResults.single().outcome == BuildTaskOutcome.SKIPPED)
            check(provider.calls == 0)

            val cancelled = CancellationToken().also { it.cancel() }
            val cancelledResult = compiler.compile(
                Aapt2CompileRequest(fixture.resources, File(root, "out/cancelled.zip")),
                cancelled
            )
            check(cancelledResult.outcome == BuildOutcome.CANCELLED)
            check(cancelledResult.diagnostics.single().code == "BUILD_CANCELLED")
            check(provider.calls == 0)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compileUsesStructuredArgumentsAndVerifiesOutput() {
        val root = Files.createTempDirectory("vega aapt2 compile space & args").toFile()
        try {
            val fixture = fixture(root)
            val archive = File(root, "build output/compiled resources.zip")
            val provider = RecordingProvider { arguments, workingDirectory, _ ->
                check(arguments == listOf("compile", "--dir", fixture.resources.canonicalPath, "-o", archive.absolutePath)) {
                    "AAPT2 compile argv was not passed as separate exact arguments: $arguments"
                }
                check(workingDirectory.canonicalFile == archive.parentFile.canonicalFile)
                archive.parentFile.mkdirs()
                archive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
                Aapt2InvocationResult(0)
            }

            val result = Aapt2ResourceCompiler(provider).compile(
                Aapt2CompileRequest(fixture.resources, archive),
                CancellationToken()
            )

            check(result.outcome == BuildOutcome.SUCCESS) { result.diagnostics.toString() }
            check(result.taskResults.single().id == "compile-resources")
            check(result.taskResults.single().outcome == BuildTaskOutcome.EXECUTED)
            check(result.producedArtifacts.single().kind == "android-compiled-resources")
            check(result.producedArtifacts.single().file.canonicalFile == archive.canonicalFile)
            check(result.producedArtifacts.single().sha256 == sha256(archive))
            check(provider.calls == 1)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compileFailureRemovesCurrentArchive() {
        val root = Files.createTempDirectory("vega aapt2 compile cleanup").toFile()
        try {
            val fixture = fixture(root)
            val archive = File(root, "out/compiled/res.zip")
            val succeeding = RecordingProvider { _, _, _ ->
                archive.parentFile.mkdirs()
                archive.writeBytes(byteArrayOf(1, 2, 3))
                Aapt2InvocationResult(0)
            }
            val first = Aapt2ResourceCompiler(succeeding).compile(
                Aapt2CompileRequest(fixture.resources, archive), CancellationToken()
            )
            check(first.outcome == BuildOutcome.SUCCESS)
            check(archive.isFile)

            val failing = RecordingProvider { _, _, _ ->
                archive.writeBytes(byteArrayOf(9, 8, 7))
                Aapt2InvocationResult(2, stderr = "bad resource XML")
            }
            val second = Aapt2ResourceCompiler(failing).compile(
                Aapt2CompileRequest(fixture.resources, archive), CancellationToken()
            )
            check(second.outcome == BuildOutcome.FAILED)
            check(second.diagnostics.single().code == "AAPT2_COMPILE_FAILED")
            check(second.diagnostics.single().message.contains("bad resource XML"))
            check(second.producedArtifacts.isEmpty())
            check(!archive.exists()) { "Old/partial res.zip survived a failed compile" }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun resourceLimitsAndUnsafeTreesDoNotStartProvider() {
        val root = Files.createTempDirectory("vega aapt2 resource limits").toFile()
        try {
            val fixture = fixture(root)
            File(fixture.resources, "values/colors.xml").writeText("<resources />")
            val provider = RecordingProvider { _, _, _ -> error("limit/unsafe input must not start AAPT2") }
            val compiler = Aapt2ResourceCompiler(provider)
            val countLimited = compiler.compile(
                Aapt2CompileRequest(fixture.resources, File(root, "count.zip"), maxResourceFiles = 1),
                CancellationToken()
            )
            check(countLimited.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(countLimited.diagnostics.single().code == "AAPT2_RESOURCE_LIMIT")

            val byteLimited = compiler.compile(
                Aapt2CompileRequest(fixture.resources, File(root, "bytes.zip"), maxResourceBytes = 1L),
                CancellationToken()
            )
            check(byteLimited.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(byteLimited.diagnostics.single().code == "AAPT2_RESOURCE_LIMIT")
            check(provider.calls == 0)

            val outside = File(root, "outside.xml").apply { writeText("<resources />") }
            val link = File(fixture.resources, "values/linked.xml").toPath()
            val linked = runCatching { Files.createSymbolicLink(link, outside.toPath()) }.isSuccess
            if (linked) {
                val unsafe = compiler.compile(
                    Aapt2CompileRequest(fixture.resources, File(root, "symlink.zip")),
                    CancellationToken()
                )
                check(unsafe.outcome == BuildOutcome.FAILED)
                check(unsafe.diagnostics.single().code == "AAPT2_INPUT_INVALID")
                check(provider.calls == 0)
            } else {
                println("SKIP resource symlink fixture: symbolic links are unavailable on this host")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun linkValidatesInputsBeforeProvider() {
        val root = Files.createTempDirectory("vega aapt2 link validation").toFile()
        try {
            val fixture = fixture(root)
            val compiled = File(root, "compiled/res.zip").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val androidJar = File(root, "android.jar").apply { writeBytes(byteArrayOf(1)) }
            val provider = RecordingProvider { _, _, _ -> error("invalid input must not start AAPT2") }
            val compiler = Aapt2ResourceCompiler(provider)

            val missingManifest = compiler.link(
                linkRequest(fixture, compiled, File(root, "missing-manifest.xml"), androidJar),
                CancellationToken()
            )
            check(missingManifest.outcome == BuildOutcome.FAILED)
            check(missingManifest.diagnostics.single().code == "AAPT2_INPUT_INVALID")

            val missingJar = compiler.link(
                linkRequest(fixture, compiled, fixture.manifest, File(root, "missing-android.jar")),
                CancellationToken()
            )
            check(missingJar.outcome == BuildOutcome.FAILED)
            check(missingJar.diagnostics.single().code == "AAPT2_INPUT_INVALID")

            val invalidSdk = compiler.link(
                linkRequest(fixture, compiled, fixture.manifest, androidJar).copy(minSdk = 0),
                CancellationToken()
            )
            check(invalidSdk.outcome == BuildOutcome.FAILED)
            check(invalidSdk.diagnostics.single().code == "AAPT2_INPUT_INVALID")
            check(provider.calls == 0)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun linkVerifiesArtifactsAndRemovesStaleOutputsOnFailure() {
        val root = Files.createTempDirectory("vega aapt2 link outputs").toFile()
        try {
            val fixture = fixture(root)
            val compiled = File(root, "compiled/res.zip").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val androidJar = File(root, "android.jar").apply { writeBytes(byteArrayOf(1)) }
            val request = linkRequest(fixture, compiled, fixture.manifest, androidJar)
            val provider = RecordingProvider { arguments, _, _ ->
                check(arguments == listOf(
                    "link", "-o", request.outputPackage.absolutePath,
                    "-I", androidJar.canonicalPath,
                    "--manifest", fixture.manifest.canonicalPath,
                    "--java", request.generatedJavaDirectory.absolutePath,
                    "--output-text-symbols", request.symbolOutput.absolutePath,
                    "--min-sdk-version", "23",
                    "--target-sdk-version", "35",
                    "--version-code", "1",
                    "--version-name", "1.0",
                    compiled.canonicalPath
                )) { "AAPT2 link argv changed or used shell composition: $arguments" }
                writeLinkOutputs(request)
                Aapt2InvocationResult(0)
            }

            val success = Aapt2ResourceCompiler(provider).link(request, CancellationToken())
            check(success.outcome == BuildOutcome.SUCCESS) { success.diagnostics.toString() }
            check(success.taskResults.single().id == "link-resources")
            check(success.taskResults.single().outcome == BuildTaskOutcome.EXECUTED)
            check(success.producedArtifacts.map { it.kind } == listOf(
                "android-linked-resources", "android-r-java", "android-resource-symbols"
            ))
            success.producedArtifacts.forEach { artifact ->
                check(artifact.sha256 == sha256(artifact.file)) { "Artifact hash did not match ${artifact.file}" }
            }
            ZipFile(request.outputPackage).use { zip ->
                check(zip.getEntry("AndroidManifest.xml") != null)
                check(zip.getEntry("resources.arsc") != null)
            }
            check(request.generatedJavaDirectory.walkTopDown().any { it.isFile && it.name == "R.java" })
            check(request.symbolOutput.readText().contains("string app_name"))

            val failing = RecordingProvider { _, _, _ ->
                writeLinkOutputs(request)
                Aapt2InvocationResult(1, stderr = "link rejected")
            }
            val failure = Aapt2ResourceCompiler(failing).link(request, CancellationToken())
            check(failure.outcome == BuildOutcome.FAILED)
            check(failure.diagnostics.single().code == "AAPT2_LINK_FAILED")
            check(failure.diagnostics.single().message.contains("link rejected"))
            check(failure.producedArtifacts.isEmpty())
            check(!request.outputPackage.exists())
            check(!request.generatedJavaDirectory.exists())
            check(!request.symbolOutput.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun nativeProviderValidatesExecutable() {
        val root = Files.createTempDirectory("vega aapt2 executable check").toFile()
        try {
            check(!NativeAapt2Provider(root).available) { "A directory was accepted as an AAPT2 executable" }
            check(!NativeAapt2Provider(File(root, "missing-aapt2.exe")).available)
            val ordinaryFile = File(root, "not-executable").apply { writeText("not a binary") }
            check(NativeAapt2Provider(ordinaryFile).available) {
                "Provider availability must validate an ordinary explicitly supplied file, not run the tool"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun nativeProviderCancelsInFlightAndBoundsDiagnostics() {
        val root = Files.createTempDirectory("vega aapt2 native provider lifecycle").toFile()
        try {
            val java = currentJavaExecutable()
            check(java.isFile) { "Current JVM executable is unavailable" }
            val provider = NativeAapt2Provider(java)
            check(provider.available)
            val classpath = System.getProperty("java.class.path")
            val helperClass = "dev.wind.agent.Aapt2ProviderProcessHelper"

            val markerFile = File(root, "child-started.marker")
            val token = CancellationToken()
            val result = AtomicReference<Aapt2InvocationResult?>()
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "aapt2-provider-cancel-test", isDaemon = true) {
                try {
                    result.set(provider.execute(
                        listOf("-cp", classpath, helperClass, "sleep", markerFile.absolutePath),
                        root,
                        token
                    ))
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

            val startDeadline = System.nanoTime() + 5_000_000_000L
            while (!markerFile.isFile && worker.isAlive && System.nanoTime() < startDeadline) {
                Thread.sleep(20L)
            }
            check(markerFile.isFile) { "Native provider helper never started" }
            val cancelledAt = System.nanoTime()
            token.cancel()
            worker.join(5_000L)
            check(!worker.isAlive) { "In-flight native provider cancellation did not return within 5 seconds" }
            failure.get()?.let { throw AssertionError("Native cancellation raised unexpectedly", it) }
            check(result.get() != null) { "Native cancellation returned no invocation result" }
            check((System.nanoTime() - cancelledAt) / 1_000_000L < 5_000L)

            val spam = provider.execute(
                listOf("-cp", classpath, helperClass, "spam"),
                root,
                CancellationToken()
            )
            check(spam.exitCode == 0) { "Diagnostic helper failed" }
            check(spam.stdout.endsWith("[aapt2 diagnostics truncated]")) {
                "stdout was not bounded/truncated as promised"
            }
            check(spam.stderr.endsWith("[aapt2 diagnostics truncated]")) {
                "stderr was not bounded/truncated as promised"
            }
            check(spam.stdout.toByteArray(Charsets.UTF_8).size < 66 * 1024)
            check(spam.stderr.toByteArray(Charsets.UTF_8).size < 66 * 1024)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun currentJavaExecutable(): File {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        return File(System.getProperty("java.home"), "bin/java" + if (windows) ".exe" else "")
    }

    private fun realNativeCompileAndLink(androidJar: File?) {
        val executable = System.getenv("VEGA_AAPT2")?.takeIf { it.isNotBlank() }?.let(::File)
        if (executable == null) {
            println("SKIP real AAPT2 integration: VEGA_AAPT2 is not set")
            return
        }
        check(androidJar != null && androidJar.isFile && androidJar.length() > 0L) {
            "Pass the repository Android 35 android.jar as the integration-test argument"
        }
        val provider = NativeAapt2Provider(executable)
        check(provider.available) { "VEGA_AAPT2 is not an ordinary executable file: $executable" }

        val root = createNativeIntegrationDirectory()
        try {
            // This host's Build Tools AAPT2 cannot open absolute argv paths with
            // non-ASCII components. Keep the integration fixture and a byte-for-byte
            // copy of the repository platform jar under the ASCII system temp path.
            val nativeAndroidJar = File(root, "android-35.jar")
            androidJar.copyTo(nativeAndroidJar)
            check(sha256(nativeAndroidJar) == sha256(androidJar)) {
                "Temporary Android platform input differs from the repository asset"
            }
            val fixture = fixture(root)
            val outputs = File(root, "build outputs/resources")
            val compiled = File(outputs, "compiled/res.zip")
            val generated = File(outputs, "generated/r")
            val linked = File(outputs, "linked/resources.ap_")
            val symbols = File(outputs, "symbols/R.txt")
            val compiler = Aapt2ResourceCompiler(provider)

            val compile = compiler.compile(Aapt2CompileRequest(fixture.resources, compiled), CancellationToken())
            check(compile.outcome == BuildOutcome.SUCCESS) {
                "Real aapt2 compile failed: ${compile.diagnostics}"
            }
            check(compile.producedArtifacts.single().kind == "android-compiled-resources")
            check(compile.producedArtifacts.single().sha256 == sha256(compiled))

            val request = Aapt2LinkRequest(
                compiledResources = compiled,
                manifest = fixture.manifest,
                androidJar = nativeAndroidJar,
                outputPackage = linked,
                generatedJavaDirectory = generated,
                symbolOutput = symbols,
                minSdk = 23,
                targetSdk = 35
            )
            val link = compiler.link(request, CancellationToken())
            check(link.outcome == BuildOutcome.SUCCESS) { "Real aapt2 link failed: ${link.diagnostics}" }
            check(link.producedArtifacts.size == 3)
            check(link.producedArtifacts.all { it.sha256 == sha256(it.file) })
            ZipFile(linked).use { zip ->
                check(zip.getEntry("AndroidManifest.xml") != null)
                check(zip.getEntry("resources.arsc") != null)
            }
            val generatedR = generated.walkTopDown().filter { it.isFile && it.name == "R.java" }.toList()
            check(generatedR.isNotEmpty())
            check(generatedR.any { it.readText().contains("app_name") })
            check(symbols.isFile && symbols.length() > 0L && symbols.readText().contains("string app_name"))

            val staleFailureProvider = RecordingProvider { _, _, _ ->
                writeLinkOutputs(request)
                Aapt2InvocationResult(1, stderr = "intentional second-link failure")
            }
            val staleFailure = Aapt2ResourceCompiler(staleFailureProvider).link(request, CancellationToken())
            check(staleFailure.outcome == BuildOutcome.FAILED)
            check(staleFailure.diagnostics.single().code == "AAPT2_LINK_FAILED")
            check(!linked.exists() && !generated.exists() && !symbols.exists()) {
                "Stale or partial link outputs survived a failing second link"
            }

            File(fixture.resources, "values/strings.xml").writeText("<resources><string name=\"app_name\">broken")
            val broken = compiler.compile(Aapt2CompileRequest(fixture.resources, compiled), CancellationToken())
            check(broken.outcome == BuildOutcome.FAILED)
            check(broken.diagnostics.any { it.code == "AAPT2_COMPILE_FAILED" }) {
                "Malformed XML did not preserve the AAPT2 compile diagnostic: ${broken.diagnostics}"
            }
            check(!compiled.exists()) { "A stale res.zip survived the real malformed-XML compile" }

        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixture(root: File): Fixture {
        val manifest = File(root, "fixture/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.wind.fixture">
                    <application android:label="@string/app_name" />
                </manifest>
                """.trimIndent()
            )
        }
        val resources = File(root, "fixture/res").apply { mkdirs() }
        File(resources, "values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources><string name=\"app_name\">Vega Fixture</string></resources>")
        }
        return Fixture(resources, manifest)
    }

    private fun createNativeIntegrationDirectory(): File {
        val asciiTempParent = listOf("TEMP", "TMP", "TMPDIR")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank)?.let(::File) }
            .firstOrNull { candidate ->
                candidate.isDirectory && candidate.canWrite() && candidate.absolutePath.all { it.code < 128 }
            }
        val directory = if (asciiTempParent != null) {
            Files.createTempDirectory(asciiTempParent.toPath(), "vega aapt2 AR1 & fixture").toFile()
        } else {
            Files.createTempDirectory("vega aapt2 AR1 & fixture").toFile()
        }
        check(directory.absolutePath.all { it.code < 128 }) {
            "Real native AAPT2 test needs an ASCII-only temporary path on this Windows toolchain: ${directory.absolutePath}"
        }
        return directory
    }

    private fun linkRequest(fixture: Fixture, compiled: File, manifest: File, androidJar: File) =
        Aapt2LinkRequest(
            compiledResources = compiled,
            manifest = manifest,
            androidJar = androidJar,
            outputPackage = File(compiled.parentFile.parentFile, "resources/linked/resources.ap_"),
            generatedJavaDirectory = File(compiled.parentFile.parentFile, "resources/generated/r"),
            symbolOutput = File(compiled.parentFile.parentFile, "resources/symbols/R.txt"),
            minSdk = 23,
            targetSdk = 35
        )

    private fun writeLinkOutputs(request: Aapt2LinkRequest) {
        request.outputPackage.parentFile.mkdirs()
        ZipOutputStream(request.outputPackage.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("resources.arsc"))
            zip.write(byteArrayOf(2))
            zip.closeEntry()
        }
        val rJava = File(request.generatedJavaDirectory, "dev/wind/fixture/R.java").apply {
            parentFile.mkdirs()
            writeText("package dev.wind.fixture; public final class R { public static final class string { public static int app_name; } }")
        }
        check(rJava.isFile)
        request.symbolOutput.apply {
            parentFile.mkdirs()
            writeText("int string app_name 0x7f010000\n")
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Fixture(val resources: File, val manifest: File)

    private class RecordingProvider(
        override val available: Boolean = true,
        private val handler: (List<String>, File, CancellationToken) -> Aapt2InvocationResult
    ) : Aapt2Provider {
        var calls: Int = 0
            private set
        val invocations = mutableListOf<List<String>>()

        constructor(
            handler: (List<String>, File, CancellationToken) -> Aapt2InvocationResult
        ) : this(true, handler)

        override fun execute(
            arguments: List<String>,
            workingDirectory: File,
            token: CancellationToken
        ): Aapt2InvocationResult {
            calls++
            invocations += arguments.toList()
            return handler(arguments, workingDirectory, token)
        }
    }
}

object Aapt2ProviderProcessHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "sleep" -> {
                require(args.size == 2)
                File(args[1]).writeText("started")
                Thread.sleep(30_000L)
            }
            "spam" -> {
                val stdout = ByteArray(70 * 1024) { 'o'.code.toByte() }
                val stderr = ByteArray(70 * 1024) { 'e'.code.toByte() }
                System.out.write(stdout)
                System.out.flush()
                System.err.write(stderr)
                System.err.flush()
            }
            else -> error("unknown helper mode")
        }
    }
}
