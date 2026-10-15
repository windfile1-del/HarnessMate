package dev.wind.agent

import dev.wind.agent.build.BuildOutcome
import dev.wind.agent.build.D8CompileRequest
import dev.wind.agent.build.D8CoreLibraryDesugaringRequest
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.D8Provider
import dev.wind.agent.build.L8CompileRequest
import dev.wind.agent.build.L8Invocation
import dev.wind.agent.build.L8InvocationResult
import dev.wind.agent.build.L8Provider
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.ReflectionD8Provider
import dev.wind.agent.build.ReflectionL8Provider
import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjJavaCompiler
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

/** Fake-provider and opt-in real R8/L8 coverage for P6-DX2B. */
object L8RuntimeDexCompilerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        unavailableAndPreCancelledFailClosed()
        emptyAndOversizedConfigurationFailBeforeInvocation()
        keepRulesAreRequiredAndBounded()
        invalidProgramAndLibraryInputsFailBeforeInvocation()
        inputLimitsAndCrossRoleAliasesFailClosed()
        invocationCapturesCanonicalInputsAndOptions()
        unsafeOutputOverlapPreservesInputs()
        successWithoutDexAndInvalidDexFailClosed()
        multidexIsValidatedAndNumericallyOrdered()
        failedRerunAndCancellationCleanStaleOutput()
        realL8Integration(args.singleOrNull()?.let(::File))
        println("L8RuntimeDexCompilerTests: OK")
    }

    private fun unavailableAndPreCancelledFailClosed() {
        withFixture { fixture ->
            var calls = 0
            val unavailable = L8RuntimeDexCompiler(FakeProvider(available = false) { _, _ ->
                calls++
                error("unavailable L8 must not execute")
            }).compile(fixture.request(), CancellationToken())
            check(unavailable.outcome == BuildOutcome.UNSUPPORTED)
            check(unavailable.diagnostics.single().code == "L8_UNAVAILABLE")
            check(!fixture.output.exists())

            val token = CancellationToken().also { it.cancel() }
            val cancelled = L8RuntimeDexCompiler(FakeProvider { _, _ ->
                calls++
                error("pre-cancelled L8 must not execute")
            }).compile(fixture.request(File(fixture.root, "out/cancelled")), token)
            check(cancelled.outcome == BuildOutcome.CANCELLED)
            check(cancelled.diagnostics.single().code == "BUILD_CANCELLED")
            check(calls == 0)
        }
    }

    private fun emptyAndOversizedConfigurationFailBeforeInvocation() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("invalid configuration must not invoke L8")
            }
            val compiler = L8RuntimeDexCompiler(provider)
            val empty = compiler.compile(fixture.request().copy(desugaredLibraryConfigurationJson = " \t"), CancellationToken())
            check(empty.outcome == BuildOutcome.FAILED)
            check(empty.diagnostics.single().code == "L8_CONFIG_INVALID")

            val oversized = compiler.compile(
                fixture.request().copy(
                    desugaredLibraryConfigurationJson = "💚",
                    maxConfigurationBytes = 3L
                ),
                CancellationToken()
            )
            check(oversized.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(oversized.diagnostics.single().code == "L8_CONFIG_LIMIT")

            val invalidLimits = compiler.compile(
                fixture.request().copy(maxProgramFiles = 0),
                CancellationToken()
            )
            check(invalidLimits.outcome == BuildOutcome.FAILED)
            check(invalidLimits.diagnostics.single().code == "L8_INPUT_INVALID")
            check(calls == 0)
        }
    }

    private fun keepRulesAreRequiredAndBounded() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("invalid keep rules must not invoke L8")
            }
            val missing = File(fixture.root, "missing.pg")
            val missingResult = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(keepRulesFile = missing), CancellationToken()
            )
            check(missingResult.diagnostics.single().code == "L8_KEEP_RULES_INVALID")

            val emptyRules = File(fixture.root, "empty.pg").apply { createNewFile() }
            val emptyResult = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(keepRulesFile = emptyRules), CancellationToken()
            )
            check(emptyResult.diagnostics.single().code == "L8_KEEP_RULES_INVALID")

            val tooLarge = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(maxKeepRulesBytes = 1L), CancellationToken()
            )
            check(tooLarge.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(tooLarge.diagnostics.single().code == "L8_KEEP_RULES_LIMIT")
            check(calls == 0)
        }
    }

    private fun invalidProgramAndLibraryInputsFailBeforeInvocation() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("invalid inputs must not invoke L8")
            }
            val missingProgram = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(programFiles = listOf(File(fixture.root, "missing.jar"))),
                CancellationToken()
            )
            check(missingProgram.diagnostics.single().code == "L8_PROGRAM_INPUT_INVALID")

            val emptyProgram = File(fixture.root, "empty.jar").apply { createNewFile() }
            val emptyProgramResult = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(programFiles = listOf(emptyProgram)), CancellationToken()
            )
            check(emptyProgramResult.diagnostics.single().code == "L8_PROGRAM_INPUT_INVALID")

            val missingLibrary = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(libraryFiles = listOf(File(fixture.root, "missing-android.jar"))),
                CancellationToken()
            )
            check(missingLibrary.diagnostics.single().code == "L8_LIBRARY_INPUT_INVALID")
            check(calls == 0)
        }
    }

    private fun inputLimitsAndCrossRoleAliasesFailClosed() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("limited or ambiguous inputs must not invoke L8")
            }
            val tooMany = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(programFiles = listOf(fixture.program, fixture.program), maxProgramFiles = 1),
                CancellationToken()
            )
            check(tooMany.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(tooMany.diagnostics.single().code == "L8_PROGRAM_INPUT_LIMIT")

            val tooManyBytes = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(maxProgramBytes = 1L), CancellationToken()
            )
            check(tooManyBytes.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(tooManyBytes.diagnostics.single().code == "L8_PROGRAM_INPUT_LIMIT")

            val roleAlias = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(libraryFiles = listOf(fixture.program)), CancellationToken()
            )
            check(roleAlias.diagnostics.single().code == "L8_INPUT_INVALID")

            val rulesAlias = L8RuntimeDexCompiler(provider).compile(
                fixture.request().copy(keepRulesFile = fixture.program), CancellationToken()
            )
            check(rulesAlias.diagnostics.single().code == "L8_INPUT_INVALID")
            check(calls == 0)
        }
    }

    private fun invocationCapturesCanonicalInputsAndOptions() {
        withFixture { fixture ->
            var captured: L8Invocation? = null
            val output = File(fixture.root, "out/runtime")
            val result = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                captured = invocation
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                L8InvocationResult(success = true)
            }).compile(
                fixture.request(output).copy(
                    programFiles = listOf(fixture.program, fixture.program),
                    libraryFiles = listOf(fixture.library, fixture.library),
                    desugaredLibraryConfigurationJson = fixture.configuration,
                    minApi = 23,
                    release = true
                ),
                CancellationToken()
            )

            check(result.success) { result.diagnostics.toString() }
            val invocation = checkNotNull(captured)
            check(invocation.programFiles == listOf(fixture.program.canonicalFile))
            check(invocation.libraryFiles == listOf(fixture.library.canonicalFile))
            check(invocation.desugaredLibraryConfigurationJson == fixture.configuration)
            check(invocation.keepRulesFile == fixture.keepRules.canonicalFile)
            check(invocation.outputDirectory == output.canonicalFile)
            check(invocation.minApi == 23 && invocation.release)
            check(result.artifacts.single().kind == "android-desugar-runtime-dex")
            check(result.artifacts.single().sha256 == sha256(result.artifacts.single().file))
        }
    }

    private fun unsafeOutputOverlapPreservesInputs() {
        withFixture { fixture ->
            var calls = 0
            val programParent = fixture.program.parentFile
            val sentinel = File(programParent, "preserve.txt").apply { writeText("do not clear input directory") }
            val result = L8RuntimeDexCompiler(FakeProvider { _, _ ->
                calls++
                error("overlapping output must not invoke L8")
            }).compile(fixture.request(programParent), CancellationToken())

            check(result.diagnostics.single().code == "L8_OUTPUT_INVALID") { result.diagnostics.toString() }
            check(sentinel.readText() == "do not clear input directory")
            check(fixture.program.isFile && fixture.keepRules.isFile)

            val rulesParent = fixture.keepRules.parentFile
            val rulesSentinel = File(rulesParent, "preserve.txt").apply { writeText("do not clear rules input") }
            val rulesOverlap = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                calls++
                error(
                    "output overlapping keep rules must not invoke L8: " +
                        "expected=$rulesParent actual=${invocation.outputDirectory} keep=${invocation.keepRulesFile}"
                )
            }).compile(fixture.request(rulesParent), CancellationToken())
            check(rulesOverlap.diagnostics.single().code == "L8_OUTPUT_INVALID") { rulesOverlap.diagnostics.toString() }
            check(rulesSentinel.readText() == "do not clear rules input")
            check(calls == 0)
        }
    }

    private fun successWithoutDexAndInvalidDexFailClosed() {
        withFixture { fixture ->
            val missing = L8RuntimeDexCompiler(FakeProvider { _, _ -> L8InvocationResult(success = true) })
                .compile(fixture.request(), CancellationToken())
            check(missing.diagnostics.single().code == "L8_OUTPUT_MISSING")

            val badName = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "runtime.dex").writeBytes(validDexBytes())
                L8InvocationResult(success = true)
            }).compile(fixture.request(File(fixture.root, "out/bad-name")), CancellationToken())
            check(badName.diagnostics.single().code == "L8_OUTPUT_INVALID")

            val badMagic = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(ByteArray(16) { 1 })
                L8InvocationResult(success = true)
            }).compile(fixture.request(File(fixture.root, "out/bad-magic")), CancellationToken())
            check(badMagic.diagnostics.single().code == "L8_OUTPUT_INVALID")
        }
    }

    private fun multidexIsValidatedAndNumericallyOrdered() {
        withFixture { fixture ->
            val provider = FakeProvider { invocation, _ ->
                listOf(10, 2, 1, 7, 3, 9, 4, 8, 5, 6).forEach { index ->
                    val name = if (index == 1) "classes.dex" else "classes$index.dex"
                    File(invocation.outputDirectory, name).writeBytes(validDexBytes())
                }
                L8InvocationResult(success = true)
            }
            val result = L8RuntimeDexCompiler(provider).compile(fixture.request(), CancellationToken())
            check(result.success) { result.diagnostics.toString() }
            check(result.artifacts.map { it.file.name } ==
                (listOf("classes.dex") + (2..10).map { "classes$it.dex" }))
            check(result.artifacts.all { it.kind == "android-desugar-runtime-dex" && it.sha256 != null })

            val gap = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                File(invocation.outputDirectory, "classes3.dex").writeBytes(validDexBytes())
                L8InvocationResult(success = true)
            }).compile(fixture.request(File(fixture.root, "out/gap")), CancellationToken())
            check(gap.diagnostics.single().code == "L8_OUTPUT_INVALID")
        }
    }

    private fun failedRerunAndCancellationCleanStaleOutput() {
        withFixture { fixture ->
            val output = fixture.output
            val first = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                L8InvocationResult(success = true)
            }).compile(fixture.request(), CancellationToken())
            check(first.success)
            check(File(output, "classes.dex").isFile)

            val failed = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                File(invocation.outputDirectory, "classes2.dex").writeBytes(validDexBytes())
                L8InvocationResult(success = false, diagnostics = "synthetic L8 failure")
            }).compile(fixture.request(), CancellationToken())
            check(failed.diagnostics.single().code == "L8_COMPILE_FAILED")
            check(output.listFiles().orEmpty().isEmpty())

            val linkageFailure = L8RuntimeDexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                throw NoClassDefFoundError("synthetic L8 linkage failure")
            }).compile(fixture.request(), CancellationToken())
            check(linkageFailure.diagnostics.single().code == "L8_COMPILE_FAILED")
            check(output.listFiles().orEmpty().isEmpty())

            val token = CancellationToken()
            val cancelled = L8RuntimeDexCompiler(FakeProvider { invocation, receivedToken ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                receivedToken.cancel()
                L8InvocationResult(success = true)
            }).compile(fixture.request(), token)
            check(cancelled.outcome == BuildOutcome.CANCELLED)
            check(cancelled.diagnostics.single().code == "BUILD_CANCELLED")
            check(output.listFiles().orEmpty().isEmpty())
        }
    }

    private fun realL8Integration(androidJar: File?) {
        val rawR8Jar = System.getenv("VEGA_R8_JAR")?.takeIf { it.isNotBlank() }
        if (rawR8Jar == null) {
            println("L8RuntimeDexCompilerTests: real L8 integration skipped (VEGA_R8_JAR is unset)")
            return
        }
        val r8Jar = File(rawR8Jar)
        check(r8Jar.isFile) { "VEGA_R8_JAR does not name an ordinary file: $r8Jar" }
        val platformJar = androidJar?.takeIf { it.isFile }
            ?: error("Offline test invocation must supply the Android 35 android.jar")

        val runtimeJar = File("third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar").absoluteFile
        val configurationJar = File(
            "third_party/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
        ).absoluteFile
        check(runtimeJar.isFile && configurationJar.isFile) { "Pinned desugar input JARs are missing" }
        check(sha256(runtimeJar) == EXPECTED_RUNTIME_JAR_SHA256) { "NIO runtime JAR digest does not match the pinned asset" }
        check(sha256(configurationJar) == EXPECTED_CONFIGURATION_JAR_SHA256) {
            "NIO configuration JAR digest does not match the pinned asset"
        }
        check(sha256(platformJar) == EXPECTED_ANDROID_JAR_SHA256) { "Android 35 jar digest does not match the pinned asset" }

        val configurationJson = JarFile(configurationJar).use { jar ->
            val entry = jar.getJarEntry("META-INF/desugar/d8/desugar.json")
                ?: error("Configuration JAR lacks META-INF/desugar/d8/desugar.json")
            jar.getInputStream(entry).use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        }
        check(configurationJson.contains("com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1"))
        check(configurationJson.contains("\"configuration_format_version\":200"))
        check(configurationJson.contains("j$/"))

        val root = Files.createTempDirectory("vega-dx2b-real").toFile()
        try {
            val source = File(root, "src/example/NioReference.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example;
                    public final class NioReference {
                        public static boolean exists(String path) {
                            return java.nio.file.Files.exists(java.nio.file.Paths.get(path));
                        }
                    }
                    """.trimIndent()
                )
            }
            val classes = File(root, "classes")
            val ecj = EcjJavaCompiler().compile(
                EcjCompileRequest(listOf(source), classes, bootClasspath = listOf(platformJar)),
                CancellationToken()
            )
            check(ecj.success) { "ECJ NIO fixture compilation failed: ${ecj.diagnostics}" }

            URLClassLoader(arrayOf(r8Jar.toURI().toURL()), ReflectionD8Provider::class.java.classLoader).use { loader ->
                val d8Provider = ReflectionD8Provider(loader)
                check(d8Provider.available) { "Explicit VEGA_R8_JAR does not expose D8" }
                check(d8Provider.version?.contains("8.2.33") == true) { "DX2B integration requires R8 8.2.33" }
                val d8Output = File(root, "app-dex")
                val keepRules = File(root, "app-desugar/l8.pg")
                val d8Result = D8DexCompiler(d8Provider).compile(
                    D8CompileRequest(
                        classDirectories = listOf(classes),
                        libraryFiles = listOf(platformJar),
                        outputDirectory = d8Output,
                        minApi = 23,
                        coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(configurationJson, keepRules)
                    ),
                    CancellationToken()
                )
                check(d8Result.success) { "DX2A prerequisite failed: ${d8Result.diagnostics}" }
                check(keepRules.isFile && keepRules.length() > 0L) { "DX2A did not produce non-empty l8.pg" }
                check(String(d8Result.artifacts.single { it.kind == "android-dex" }.file.readBytes(), StandardCharsets.ISO_8859_1)
                    .contains("j$/nio/file")) { "DX2A app dex lacks j$/nio/file reference evidence" }

                val inputsBefore = listOf(runtimeJar, configurationJar, platformJar, keepRules).associateWith(::sha256)
                val l8Provider = ReflectionL8Provider(loader)
                check(l8Provider.available) { "Explicit VEGA_R8_JAR does not expose required L8 API" }
                check(l8Provider.version?.contains("8.2.33") == true) { "L8 version API did not report R8 8.2.33" }
                val compiler = L8RuntimeDexCompiler(l8Provider)
                fun request(output: File) = L8CompileRequest(
                    desugaredLibraryConfigurationJson = configurationJson,
                    keepRulesFile = keepRules,
                    programFiles = listOf(runtimeJar, configurationJar),
                    libraryFiles = listOf(platformJar),
                    outputDirectory = output,
                    minApi = 23,
                    release = true
                )

                val first = compiler.compile(request(File(root, "runtime-dex-first")), CancellationToken())
                check(first.success) { "Real L8 compilation failed: ${first.diagnostics}" }
                check(first.artifacts.isNotEmpty())
                check(first.artifacts.all { it.kind == "android-desugar-runtime-dex" })
                check(first.artifacts.any { String(it.file.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file") }) {
                    "L8 runtime dex lacks j$/nio/file implementation evidence"
                }

                val second = compiler.compile(request(File(root, "runtime-dex-second")), CancellationToken())
                check(second.success) { "Repeated real L8 compilation failed: ${second.diagnostics}" }
                val firstEvidence = first.artifacts.map { it.file.name to it.sha256 }
                val secondEvidence = second.artifacts.map { it.file.name to it.sha256 }
                check(secondEvidence == firstEvidence) { "L8 runtime DEX digests changed across identical runs" }
                check(listOf(runtimeJar, configurationJar, platformJar, keepRules).associateWith(::sha256) == inputsBefore) {
                    "L8 modified a program, library, or keep-rules input"
                }
                println(
                    "L8RuntimeDexCompilerTests: real DX2B integration OK " +
                        "(version=${l8Provider.version}, l8pgSha256=${sha256(keepRules)}, runtimeDex=$firstEvidence)"
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validDexBytes(): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '9'.code.toByte(), 0, 0x70, 0x00
    )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("vega-dx2b-test").toFile()
        try {
            block(Fixture.create(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val root: File,
        val program: File,
        val library: File,
        val keepRules: File,
        val output: File,
        val configuration: String
    ) {
        fun request(output: File = this.output) = L8CompileRequest(
            desugaredLibraryConfigurationJson = configuration,
            keepRulesFile = keepRules,
            programFiles = listOf(program),
            libraryFiles = listOf(library),
            outputDirectory = output,
            minApi = 23
        )

        companion object {
            fun create(root: File): Fixture {
                val program = File(root, "program/runtime.jar").apply {
                    parentFile.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3, 4))
                }
                val library = File(root, "android.jar").apply { writeBytes(byteArrayOf(5, 6, 7)) }
                val keepRules = File(root, "rules/l8.pg").apply {
                    parentFile.mkdirs()
                    writeText("-keep class j$.** { *; }\n")
                }
                return Fixture(root, program, library, keepRules, File(root, "out/runtime"), "{\"configuration\":\"fixture\"}")
            }
        }
    }

    private class FakeProvider(
        override val available: Boolean = true,
        private val action: (L8Invocation, CancellationToken) -> L8InvocationResult
    ) : L8Provider {
        override fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult =
            action(invocation, token)
    }

    private const val EXPECTED_RUNTIME_JAR_SHA256 = "25ddad0d96cf7ba284cfa7866f54aa3508468a1df80e79cb44733a66f755d330"
    private const val EXPECTED_CONFIGURATION_JAR_SHA256 = "1dd26f9e2440904eab5014a244f500d8b453f3629bfd5478a3f9895838e5c35a"
    private const val EXPECTED_ANDROID_JAR_SHA256 = "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b"
}
