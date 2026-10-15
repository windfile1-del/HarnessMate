package dev.wind.agent

import dev.wind.agent.build.*
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Focused host tests for the explicitly opted-in Android Kotlin-only backend slice. */
object EmbeddedKotlinBackendTests {
    @JvmStatic
    fun main(args: Array<String>) {
        kotlinOnlyExecutesThenDexFailsClosedByDefault()
        unpinnedTargetStdlibsAreRejectedBeforeD8()
        pinnedTargetStdlibReachesD8AsProgramArchive()
        preCancelledBuildDoesNotStartProviders()
        mixedCompilationFailsClosed()
        missingCompilerAndStdlibFailClosed()
        invalidCompilerOutputIsRejectedAndCleaned()
        cancellationCleansPartialKotlinOutput()
        symbolicLinkInputsAndOutputsFailClosed()
        println("EmbeddedKotlinBackendTests: Android Kotlin-only execution and fail-closed boundaries passed")
    }

    private fun kotlinOnlyExecutesThenDexFailsClosedByDefault() {
        val fixture = Fixture.create()
        try {
            val stale = File(fixture.classOutput, "stale/Old.class").apply {
                parentFile.mkdirs()
                writeBytes(minimalClassFile())
            }
            var captured: KotlinCompileRequest? = null
            val compiler = KotlinCompilationProvider { request, _ ->
                captured = request
                check(!stale.exists()) { "Stale Kotlin class survived output preparation" }
                File(request.outputDirectory, "dev/wind/fixture/KotlinOnly.class").apply {
                    parentFile.mkdirs()
                    writeBytes(minimalClassFile())
                }
                BuildResult(BuildOutcome.SUCCESS)
            }
            val d8 = RecordingD8Provider()
            val aapt = FakeAndroidAapt2Provider()
            val backend = fixture.backend(
                compiler,
                fixture.stdlibJar,
                d8,
                aapt,
                packageStdlibInTarget = false
            )
            val result = execute(fixture, backend)

            val suffix = moduleSuffix(":")
            check(result.outcome == BuildOutcome.UNSUPPORTED) {
                "Kotlin-only host compilation must stop at the unimplemented Kotlin DEX/runtime boundary: " +
                    "${result.outcome} ${result.diagnostics}"
            }
            check(result.diagnostics.any {
                it.code == "EMBEDDED_KOTLIN_RUNTIME_PACKAGING_UNSUPPORTED" &&
                    it.source == BuildDiagnosticSource.DEX
            }) { "Kotlin DEX did not fail closed: ${result.diagnostics}" }
            check(result.taskResults.single { it.id == "compile-kotlin:$suffix" }.outcome ==
                BuildTaskOutcome.EXECUTED) { "Kotlin-only compile node did not execute: ${result.taskResults}" }
            check(result.taskResults.single { it.id == "dex:$suffix" }.outcome ==
                BuildTaskOutcome.FAILED) { "DEX guard task did not report failure: ${result.taskResults}" }
            check(result.taskResults.single { it.id == "package-apk:$suffix" }.outcome ==
                BuildTaskOutcome.SKIPPED)
            check(d8.calls == 0) { "D8 was invoked before Kotlin runtime packaging was supported" }
            check(aapt.calls == 2) { "Expected one fake AAPT2 compile and link, got ${aapt.calls}" }
            check(result.producedArtifacts.isEmpty()) {
                "An unsuccessful fail-closed build must not publish build artifacts"
            }

            val request = checkNotNull(captured) { "Kotlin compiler provider was not invoked" }
            check(request.sources == listOf(fixture.kotlinSource.canonicalFile)) {
                "Kotlin provider received unexpected source paths: ${request.sources}"
            }
            check(request.outputDirectory.canonicalFile == fixture.classOutput.canonicalFile) {
                "Kotlin provider received the wrong output path: ${request.outputDirectory}"
            }
            check(request.bootClasspath == listOf(fixture.androidJar.canonicalFile)) {
                "Kotlin provider did not receive the Android boot classpath: ${request.bootClasspath}"
            }
            check(request.classpath == listOf(fixture.stdlibJar.canonicalFile)) {
                "Kotlin provider did not receive the explicit stdlib classpath: ${request.classpath}"
            }
            val generatedR = File(
                fixture.outputDirectory,
                "modules/$suffix/resources/generated/r/dev/wind/fixture/R.java"
            ).canonicalFile
            check(request.javaSources == listOf(generatedR) && generatedR.isFile) {
                "Kotlin provider did not receive the generated Android R.java: ${request.javaSources}"
            }
            check(generatedR.readText().contains("package dev.wind.fixture;") &&
                generatedR.readText().contains("class R")) {
                "AAPT2 fake did not produce the expected R.java input"
            }

            val compiledClass = File(fixture.classOutput, "dev/wind/fixture/KotlinOnly.class")
            check(compiledClass.isFile && compiledClass.length() > 8L) {
                "Verified Kotlin .class output is missing or empty: $compiledClass"
            }
            check(compiledClass.readBytes().take(4) == listOf(
                0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()
            )) { "Kotlin class output did not pass class-file magic validation" }
            check(!stale.exists()) { "Stale Kotlin output reappeared after compilation" }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun unpinnedTargetStdlibsAreRejectedBeforeD8() {
        val packageStdlib = packagedStdlibFile()
        val fixtureWrongLength = Fixture.create()
        try {
            val d8 = RecordingD8Provider()
            val result = execute(
                fixtureWrongLength,
                fixtureWrongLength.backend(
                    successfulKotlinCompiler(),
                    fixtureWrongLength.stdlibJar,
                    d8Provider = d8,
                    packageStdlibInTarget = true,
                    coreLibraryDesugaring = fakeCoreLibraryDesugaringSupport()
                )
            )
            assertInvalidTargetStdlib(result, d8, "wrong-length ordinary JAR")
        } finally {
            fixtureWrongLength.root.deleteRecursively()
        }

        val fixtureModified = Fixture.create()
        try {
            val alteredStdlib = File(fixtureModified.root, "libs/altered-stdlib.jar").apply {
                parentFile.mkdirs()
                val bytes = packageStdlib.readBytes()
                bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
                writeBytes(bytes)
            }
            check(alteredStdlib.length() == packageStdlib.length()) {
                "The SHA mismatch fixture must preserve the pinned artifact length"
            }
            val d8 = RecordingD8Provider()
            val result = execute(
                fixtureModified,
                fixtureModified.backend(
                    successfulKotlinCompiler(),
                    alteredStdlib,
                    d8Provider = d8,
                    packageStdlibInTarget = true,
                    coreLibraryDesugaring = fakeCoreLibraryDesugaringSupport()
                )
            )
            assertInvalidTargetStdlib(result, d8, "same-length hash-tampered pinned JAR")
        } finally {
            fixtureModified.root.deleteRecursively()
        }
    }

    private fun assertInvalidTargetStdlib(result: BuildResult, d8: RecordingD8Provider, label: String) {
        check(result.outcome == BuildOutcome.UNSUPPORTED) {
            "$label must be rejected as unsupported: ${result.outcome} ${result.diagnostics}"
        }
        check(result.diagnostics.any {
            it.code == "EMBEDDED_KOTLIN_STDLIB_INVALID" && it.source == BuildDiagnosticSource.DEX
        }) { "$label did not fail with the DEX stdlib-pin diagnostic: ${result.diagnostics}" }
        check(d8.calls == 0) { "D8 ran with $label" }
        check(result.taskResults.single { it.id.startsWith("compile-kotlin:") }.outcome ==
            BuildTaskOutcome.EXECUTED) {
            "$label was rejected before Kotlin-only compilation reached its DEX boundary: ${result.taskResults}"
        }
        check(result.taskResults.single { it.id.startsWith("dex:") }.outcome == BuildTaskOutcome.FAILED) {
            "$label did not stop at the DEX task: ${result.taskResults}"
        }
    }

    private fun pinnedTargetStdlibReachesD8AsProgramArchive() {
        val fixture = Fixture.create()
        try {
            val pinnedStdlib = packagedStdlibFile()
            var captured: D8Invocation? = null
            val d8 = RecordingD8Provider { invocation ->
                captured = invocation
                File(invocation.outputDirectory, "classes.dex").apply {
                    parentFile.mkdirs()
                    writeBytes(validDexBytes())
                }
            }
            val result = execute(
                fixture,
                fixture.backend(
                    successfulKotlinCompiler(),
                    pinnedStdlib,
                    d8Provider = d8,
                    packageStdlibInTarget = true,
                    coreLibraryDesugaring = fakeCoreLibraryDesugaringSupport()
                )
            )

            val dexTask = result.taskResults.single { it.id.startsWith("dex:") }
            check(dexTask.outcome == BuildTaskOutcome.EXECUTED) {
                "Pinned stdlib did not reach an executed DEX task: ${result.outcome} ${result.diagnostics}"
            }
            check(d8.calls == 1) { "Expected one D8 invocation for the pinned stdlib, got ${d8.calls}" }
            val invocation = checkNotNull(captured) { "D8 invocation was not captured" }
            check(invocation.programFiles.any { it.canonicalFile == pinnedStdlib.canonicalFile }) {
                "Pinned stdlib was not included among D8 program files: ${invocation.programFiles}"
            }
            check(invocation.libraryFiles == listOf(fixture.androidJar.canonicalFile)) {
                "android.jar must remain a D8 library input: ${invocation.libraryFiles}"
            }
            check(File(invocation.outputDirectory, "classes.dex").isFile) {
                "Fake D8 did not leave the verified DEX output"
            }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun preCancelledBuildDoesNotStartProviders() {
        val fixture = Fixture.create()
        try {
            var kotlinCalls = 0
            val aapt = FakeAndroidAapt2Provider()
            val compiler = KotlinCompilationProvider { _, _ ->
                kotlinCalls++
                BuildResult(BuildOutcome.SUCCESS)
            }
            val backend = fixture.backend(compiler, fixture.stdlibJar, aapt2Provider = aapt)
            val token = CancellationToken().also { it.cancel() }

            val result = execute(fixture, backend, token)

            check(result.outcome == BuildOutcome.CANCELLED)
            check(result.diagnostics.any { it.code == "BUILD_CANCELLED" })
            check(kotlinCalls == 0 && aapt.calls == 0) {
                "Providers ran after pre-cancellation: kotlin=$kotlinCalls, aapt=${aapt.calls}"
            }
            check(!fixture.outputDirectory.exists()) { "Pre-cancelled build created output" }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun mixedCompilationFailsClosed() {
        val fixture = Fixture.create(withJavaSource = true)
        try {
            var kotlinCalls = 0
            val aapt = FakeAndroidAapt2Provider()
            val compiler = KotlinCompilationProvider { _, _ ->
                kotlinCalls++
                BuildResult(BuildOutcome.SUCCESS)
            }
            val backend = fixture.backend(compiler, fixture.stdlibJar, aapt2Provider = aapt)

            val result = execute(fixture, backend)

            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(result.diagnostics.any {
                it.code == "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED" &&
                    it.source == BuildDiagnosticSource.KOTLIN
            }) { "Mixed-language project did not fail closed: ${result.diagnostics}" }
            check(backend.plan(BuildContext(EmbeddedProjectModelParser().parse(fixture.root), fixture.outputDirectory))
                .tasks.any { it.id.startsWith("compile-mixed:") }) {
                "Mixed-language source set did not produce its explicit compile-mixed node"
            }
            check(kotlinCalls == 0 && aapt.calls == 0) {
                "Providers ran before mixed compilation was rejected: kotlin=$kotlinCalls, aapt=${aapt.calls}"
            }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun missingCompilerAndStdlibFailClosed() {
        val withoutCompiler = Fixture.create()
        try {
            val aapt = FakeAndroidAapt2Provider()
            val backend = EmbeddedBuildBackend(
                bootClasspath = { listOf(withoutCompiler.androidJar) },
                aapt2Compiler = Aapt2ResourceCompiler(aapt),
                d8Compiler = D8DexCompiler(RecordingD8Provider())
            )

            val result = execute(withoutCompiler, backend)

            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(result.diagnostics.any { it.code == "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED" }) {
                "Missing opt-in Kotlin provider was not reported: ${result.diagnostics}"
            }
            check(aapt.calls == 0) { "AAPT2 ran before missing Kotlin support was rejected" }
        } finally {
            withoutCompiler.root.deleteRecursively()
        }

        val withoutStdlib = Fixture.create()
        try {
            var kotlinCalls = 0
            val compiler = KotlinCompilationProvider { _, _ ->
                kotlinCalls++
                BuildResult(BuildOutcome.SUCCESS)
            }
            val missing = File(withoutStdlib.root, "libs/missing-stdlib.jar")
            val result = execute(
                withoutStdlib,
                withoutStdlib.backend(compiler, missing)
            )

            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(result.diagnostics.any { it.code == "EMBEDDED_KOTLIN_STDLIB_UNAVAILABLE" }) {
                "Missing stdlib was not rejected: ${result.diagnostics}"
            }
            check(kotlinCalls == 0 && !withoutStdlib.classOutput.exists()) {
                "Compiler/output was touched without an ordinary stdlib"
            }
        } finally {
            withoutStdlib.root.deleteRecursively()
        }
    }

    private fun invalidCompilerOutputIsRejectedAndCleaned() {
        val fixture = Fixture.create()
        try {
            val compiler = KotlinCompilationProvider { request, _ ->
                File(request.outputDirectory, "dev/wind/fixture/Invalid.class").apply {
                    parentFile.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
                }
                BuildResult(BuildOutcome.SUCCESS)
            }

            val result = execute(fixture, fixture.backend(compiler, fixture.stdlibJar))

            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.any { it.code == "EMBEDDED_KOTLIN_ARTIFACT_INVALID" }) {
                "Invalid class-file output was not rejected: ${result.diagnostics}"
            }
            check(!fixture.classOutput.exists()) { "Invalid Kotlin output survived cleanup" }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun cancellationCleansPartialKotlinOutput() {
        val fixture = Fixture.create()
        try {
            val compiler = KotlinCompilationProvider { request, token ->
                File(request.outputDirectory, "partial/Partial.class").apply {
                    parentFile.mkdirs()
                    writeBytes(minimalClassFile())
                }
                token.cancel()
                BuildResult(BuildOutcome.SUCCESS)
            }
            val token = CancellationToken()

            val result = execute(fixture, fixture.backend(compiler, fixture.stdlibJar), token)

            check(result.outcome == BuildOutcome.CANCELLED)
            check(result.taskResults.single { it.id.startsWith("compile-kotlin:") }.outcome ==
                BuildTaskOutcome.CANCELLED)
            check(!fixture.classOutput.exists()) { "Partial Kotlin output survived cancellation cleanup" }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun symbolicLinkInputsAndOutputsFailClosed() {
        val stdlibFixture = Fixture.create()
        val stdlibLink = File(stdlibFixture.root, "libs/stdlib-link.jar")
        try {
            var compilerCalls = 0
            val compiler = KotlinCompilationProvider { _, _ ->
                compilerCalls++
                BuildResult(BuildOutcome.SUCCESS)
            }
            if (createSymbolicLinkOrSkip(stdlibLink, stdlibFixture.stdlibJar, "stdlib input")) {
                val result = execute(
                    stdlibFixture,
                    stdlibFixture.backend(compiler, stdlibLink)
                )
                check(result.outcome == BuildOutcome.UNSUPPORTED)
                check(result.diagnostics.any { it.code == "EMBEDDED_KOTLIN_STDLIB_UNAVAILABLE" }) {
                    "Symbolic-link stdlib was not rejected: ${result.diagnostics}"
                }
                check(compilerCalls == 0)
            }
        } finally {
            if (Files.isSymbolicLink(stdlibLink.toPath())) Files.deleteIfExists(stdlibLink.toPath())
            stdlibFixture.root.deleteRecursively()
        }

        val outputFixture = Fixture.create()
        val outside = Files.createTempDirectory("vega-kotlin-backend-outside-").toFile()
        val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
        try {
            var compilerCalls = 0
            val compiler = KotlinCompilationProvider { _, _ ->
                compilerCalls++
                BuildResult(BuildOutcome.SUCCESS)
            }
            outputFixture.classOutput.parentFile.mkdirs()
            if (createSymbolicLinkOrSkip(outputFixture.classOutput, outside, "class output")) {
                val result = execute(
                    outputFixture,
                    outputFixture.backend(compiler, outputFixture.stdlibJar)
                )
                check(result.outcome == BuildOutcome.FAILED)
                check(result.diagnostics.any { it.code == "EMBEDDED_OUTPUT_DIRECTORY_UNSAFE" }) {
                    "Symbolic-link output was not rejected: ${result.diagnostics}"
                }
                check(compilerCalls == 0)
                check(sentinel.readText() == "keep") {
                    "Output symlink handling changed data outside the build output"
                }
            }
        } finally {
            if (Files.isSymbolicLink(outputFixture.classOutput.toPath())) {
                Files.deleteIfExists(outputFixture.classOutput.toPath())
            }
            outputFixture.root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun execute(
        fixture: Fixture,
        backend: EmbeddedBuildBackend,
        token: CancellationToken = CancellationToken()
    ): BuildResult {
        val model = EmbeddedProjectModelParser().parse(fixture.root)
        check(model.supported) { "Android fixture was rejected: ${model.unsupportedReasons}" }
        val context = BuildContext(model, fixture.outputDirectory)
        return backend.execute(context, backend.plan(context), token)
    }

    private fun successfulKotlinCompiler() = KotlinCompilationProvider { request, _ ->
        File(request.outputDirectory, "dev/wind/fixture/KotlinOnly.class").apply {
            parentFile.mkdirs()
            writeBytes(minimalClassFile())
        }
        BuildResult(BuildOutcome.SUCCESS)
    }

    private fun fakeCoreLibraryDesugaringSupport() = EmbeddedCoreLibraryDesugaringSupport(
        configurationJson = "{}",
        programFiles = emptyList(),
        l8Compiler = L8RuntimeDexCompiler(FailIfInvokedL8Provider())
    )

    private fun packagedStdlibFile(): File {
        val relativePath = "assets/embedded/kotlin/classpath/kotlin-stdlib-1.9.20.jar"
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile.canonicalFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.canonicalFile
            directory = directory.parentFile
        }
        error("Pinned Kotlin stdlib is unavailable at $relativePath")
    }

    private fun validDexBytes(): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '9'.code.toByte(), 0, 0x70, 0x00
    )

    private fun createSymbolicLinkOrSkip(link: File, target: File, label: String): Boolean =
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()); true }
            .getOrElse {
                println("SKIP $label symlink fixture: symbolic links unavailable (${it.javaClass.simpleName})")
                false
            }

    private data class Fixture(
        val root: File,
        val androidJar: File,
        val stdlibJar: File,
        val kotlinSource: File,
        val outputDirectory: File
    ) {
        val classOutput: File
            get() = File(outputDirectory, "modules/${moduleSuffix(":")}/classes/java/main")

        fun backend(
            compiler: KotlinCompilationProvider,
            stdlibJar: File,
            d8Provider: D8Provider = RecordingD8Provider(),
            aapt2Provider: FakeAndroidAapt2Provider = FakeAndroidAapt2Provider(),
            packageStdlibInTarget: Boolean = false,
            coreLibraryDesugaring: EmbeddedCoreLibraryDesugaringSupport? = null
        ): EmbeddedBuildBackend {
            val kotlinSupport = EmbeddedKotlinCompilationSupport(
                compiler = compiler,
                stdlibJar = stdlibJar,
                packageStdlibInTarget = packageStdlibInTarget
            )
            val bootClasspath: () -> List<File> = { listOf(androidJar) }
            val aaptCompiler = Aapt2ResourceCompiler(aapt2Provider)
            val dexCompiler = D8DexCompiler(d8Provider)
            return if (coreLibraryDesugaring == null) {
                EmbeddedBuildBackend(
                    bootClasspath = bootClasspath,
                    aapt2Compiler = aaptCompiler,
                    d8Compiler = dexCompiler,
                    kotlinCompilation = kotlinSupport
                )
            } else {
                EmbeddedBuildBackend(
                    bootClasspath = bootClasspath,
                    aapt2Compiler = aaptCompiler,
                    d8Compiler = dexCompiler,
                    coreLibraryDesugaring = coreLibraryDesugaring,
                    apkPackaging = EmbeddedApkPackagingSupport(UnusedApkAlignmentProvider()),
                    kotlinCompilation = kotlinSupport
                )
            }
        }

        companion object {
            fun create(withJavaSource: Boolean = false): Fixture {
                val root = Files.createTempDirectory("vega-kotlin-backend-focused-").toFile()
                File(root, "build.gradle.kts").writeText(
                    "plugins { id(\"com.android.application\") }\n" +
                        "android { namespace = \"dev.wind.fixture\"; compileSdk = 35; " +
                        "defaultConfig { minSdk = 23; targetSdk = 35 } }"
                )
                File(root, "src/main/AndroidManifest.xml").apply {
                    parentFile.mkdirs()
                    writeText(
                        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                            "package=\"dev.wind.fixture\"><application android:label=\"@string/app_name\" /></manifest>"
                    )
                }
                File(root, "src/main/res/values/strings.xml").apply {
                    parentFile.mkdirs()
                    writeText("<resources><string name=\"app_name\">Focused</string></resources>")
                }
                val kotlinSource = File(root, "src/main/kotlin/dev/wind/fixture/KotlinOnly.kt").apply {
                    parentFile.mkdirs()
                    writeText("package dev.wind.fixture\nclass KotlinOnly { fun value(): Int = 7 }\n")
                }
                if (withJavaSource) {
                    File(root, "src/main/java/dev/wind/fixture/JavaPeer.java").apply {
                        parentFile.mkdirs()
                        writeText("package dev.wind.fixture; public final class JavaPeer {}\n")
                    }
                }
                val androidJar = File(root, "sdk/android.jar").apply {
                    parentFile.mkdirs()
                    writeEmptyJar(this)
                }
                val stdlibJar = File(root, "libs/kotlin-stdlib.jar").apply {
                    parentFile.mkdirs()
                    writeEmptyJar(this)
                }
                return Fixture(root, androidJar, stdlibJar, kotlinSource, File(root, "build-out"))
            }
        }
    }

    private class RecordingD8Provider(
        private val onCompile: ((D8Invocation) -> Unit)? = null
    ) : D8Provider {
        override val available: Boolean = true
        var calls: Int = 0

        override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
            calls++
            onCompile?.invoke(invocation)
            return D8InvocationResult(success = true)
        }
    }

    private class FailIfInvokedL8Provider : L8Provider {
        override val available: Boolean = true

        override fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult =
            error("Fake D8 emitted no keep-rules; L8 must not run in the focused DEX test")
    }

    private class UnusedApkAlignmentProvider : ApkAlignmentProvider {
        override val available: Boolean = false

        override fun align(
            invocation: ApkAlignmentInvocation,
            token: CancellationToken
        ): ApkAlignmentInvocationResult = error("APK alignment is outside this DEX-focused test")
    }

    private class FakeAndroidAapt2Provider : Aapt2Provider {
        override val available: Boolean = true
        var calls: Int = 0

        override fun execute(
            arguments: List<String>,
            workingDirectory: File,
            token: CancellationToken
        ): Aapt2InvocationResult {
            calls++
            if (token.isCancelled) return Aapt2InvocationResult(-1, stderr = "cancelled")
            return when (arguments.firstOrNull()) {
                "compile" -> {
                    writeZip(optionValue(arguments, "-o"), mapOf("compiled/values_strings.arsc" to "resources"))
                    Aapt2InvocationResult(0)
                }
                "link" -> {
                    writeZip(optionValue(arguments, "-o"), mapOf(
                        "AndroidManifest.xml" to "<manifest package=dev.wind.fixture />",
                        "resources.arsc" to "table"
                    ))
                    val generatedR = File(optionValue(arguments, "--java"), "dev/wind/fixture/R.java")
                    generatedR.parentFile.mkdirs()
                    generatedR.writeText(
                        "package dev.wind.fixture; public final class R { " +
                            "public static final class string { public static final int app_name = 0x7f010001; } }"
                    )
                    val symbols = optionValue(arguments, "--output-text-symbols")
                    symbols.parentFile.mkdirs()
                    symbols.writeText("int string app_name 0x7f010001\n")
                    Aapt2InvocationResult(0)
                }
                else -> Aapt2InvocationResult(2, stderr = "unexpected fake AAPT2 operation")
            }
        }

        private fun optionValue(arguments: List<String>, option: String): File {
            val index = arguments.indexOf(option)
            check(index >= 0 && index + 1 < arguments.size) { "Missing AAPT2 option $option: $arguments" }
            return File(arguments[index + 1])
        }

        private fun writeZip(output: File, entries: Map<String, String>) {
            output.parentFile.mkdirs()
            ZipOutputStream(output.outputStream()).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun writeEmptyJar(file: File) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { }
    }

    private fun minimalClassFile(): ByteArray = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0, 0, 0, 61, 0, 5,
        1, 0, 10, 0x4B, 0x6F, 0x74, 0x6C, 0x69, 0x6E, 0x4F, 0x6E, 0x6C, 0x79,
        7, 0, 2,
        1, 0, 16, 0x6A, 0x61, 0x76, 0x61, 0x2F, 0x6C, 0x61, 0x6E, 0x67, 0x2F,
        0x4F, 0x62, 0x6A, 0x65, 0x63, 0x74,
        7, 0, 4,
        0, 0x21, 0, 2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0
    )

    private fun moduleSuffix(name: String): String = name.toByteArray(Charsets.UTF_8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
