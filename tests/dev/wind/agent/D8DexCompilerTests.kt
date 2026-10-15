package dev.wind.agent

import dev.wind.agent.build.BuildOutcome
import dev.wind.agent.build.D8CompileRequest
import dev.wind.agent.build.D8CoreLibraryDesugaringRequest
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.D8Invocation
import dev.wind.agent.build.D8InvocationResult
import dev.wind.agent.build.D8Provider
import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.L8CompileRequest
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.ReflectionD8Provider
import dev.wind.agent.build.ReflectionL8Provider
import java.io.File
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/** Focused fake-provider and optional real-R8 coverage for the P6-DX1/DX2A primitive. */
object D8DexCompilerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--real-kotlin-stdlib-dx2b") {
            check(args.size == 2) { "Usage: D8DexCompilerTests --real-kotlin-stdlib-dx2b <android-35-android.jar>" }
            realKotlinStdlibNioIntegration(File(args[1]))
            return
        }
        unavailableAndPreCancelledFailClosed()
        malformedAndEmptyProgramInputsFailBeforeInvocation()
        validProgramArchiveIsPassedToProvider()
        malformedAndSymlinkProgramArchivesFailBeforeInvocation()
        programArchiveClassCannotDuplicateClassDirectoryEntry()
        outputMayNotOverlapProgramArchive()
        missingLibraryFailsBeforeInvocation()
        successWithoutDexFailsClosed()
        invalidDexOutputFailsClosed()
        multidexOutputIsValidatedAndNumericallySorted()
        failedRerunRemovesStaleDexOutputs()
        coreLibraryDesugaringNullPreservesDx1Behavior()
        coreLibraryDesugaringConfigPassesThroughAndWritesRules()
        coreLibraryDesugaringRequestLimitsAreValidated()
        coreLibraryDesugaringFailuresCleanBothOutputs()
        coreLibraryDesugaringOutputMustBeIndependent()
        realD8Integration(args.singleOrNull()?.let(::File))
        println("D8DexCompilerTests: OK")
    }

    private fun unavailableAndPreCancelledFailClosed() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider(available = false) { _, _ ->
                calls++
                error("unavailable D8 must not execute")
            }
            val output = File(fixture.root, "out/unavailable")
            val unavailable = D8DexCompiler(provider).compile(fixture.request(output), CancellationToken())
            check(unavailable.outcome == BuildOutcome.UNSUPPORTED)
            check(unavailable.diagnostics.single().code == "D8_UNAVAILABLE")
            check(unavailable.diagnostics.single().source.name == "DEX")
            check(!output.exists())
            check(calls == 0)

            val cancelled = CancellationToken().also { it.cancel() }
            val result = D8DexCompiler(FakeProvider { _, _ -> error("cancelled D8 must not execute") })
                .compile(fixture.request(File(fixture.root, "out/cancelled")), cancelled)
            check(result.outcome == BuildOutcome.CANCELLED)
            check(result.diagnostics.single().code == "BUILD_CANCELLED")
        }
    }

    private fun malformedAndEmptyProgramInputsFailBeforeInvocation() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("invalid program inputs must not invoke D8")
            }
            val invalidClass = File(fixture.classes, "broken/Bad.class").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9))
            }
            val malformed = D8DexCompiler(provider).compile(fixture.request(), CancellationToken())
            check(malformed.outcome == BuildOutcome.FAILED)
            check(malformed.diagnostics.single().code == "D8_INPUT_CLASS_INVALID")
            check(malformed.diagnostics.single().path == invalidClass.canonicalPath)
            check(calls == 0)

            invalidClass.delete()
            val emptyDirectory = File(fixture.root, "empty-classes").apply { mkdirs() }
            val empty = D8DexCompiler(provider).compile(
                fixture.request().copy(classDirectories = listOf(emptyDirectory)), CancellationToken()
            )
            check(empty.outcome == BuildOutcome.FAILED)
            check(empty.diagnostics.single().code == "D8_PROGRAM_INPUT_EMPTY")
            check(calls == 0)
        }
    }

    private fun missingLibraryFailsBeforeInvocation() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("missing library must not invoke D8")
            }
            val result = D8DexCompiler(provider).compile(
                fixture.request().copy(libraryFiles = listOf(File(fixture.root, "missing.jar"))),
                CancellationToken()
            )
            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.single().code == "D8_LIBRARY_INPUT_INVALID")
            check(calls == 0)
        }
    }

    private fun validProgramArchiveIsPassedToProvider() {
        withFixture { fixture ->
            val archive = File(fixture.root, "libs/program.jar")
            writeJar(archive, listOf(
                "archive/" to byteArrayOf(),
                "archive/Archived.class" to File(fixture.classes, "example/Example.class").readBytes()
            ))
            var captured: D8Invocation? = null
            val output = File(fixture.root, "out/archive-input")
            val result = D8DexCompiler(FakeProvider { invocation, _ ->
                captured = invocation
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true)
            }).compile(fixture.request(output).copy(programArchives = listOf(archive)), CancellationToken())

            check(result.outcome == BuildOutcome.SUCCESS) { result.diagnostics.toString() }
            val invocation = checkNotNull(captured) { "D8 provider was not invoked" }
            val expectedClass = File(fixture.classes, "example/Example.class").canonicalFile
            check(invocation.programFiles == listOf(expectedClass, archive.canonicalFile)) {
                "D8 did not receive class directory output followed by the ordinary program JAR: " +
                    invocation.programFiles
            }
            check(JarFile(archive).use { it.getEntry("archive/Archived.class") != null })
            check(invocation.programFiles.last().canonicalFile == archive.canonicalFile)
        }
    }

    private fun malformedAndSymlinkProgramArchivesFailBeforeInvocation() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("invalid program archive must not invoke D8")
            }
            val malformed = File(fixture.root, "malformed.jar").apply {
                writeBytes("not a ZIP archive".toByteArray(StandardCharsets.UTF_8))
            }
            val malformedResult = D8DexCompiler(provider).compile(
                fixture.request().copy(programArchives = listOf(malformed)), CancellationToken()
            )
            check(malformedResult.outcome == BuildOutcome.FAILED)
            check(malformedResult.diagnostics.single().code == "D8_PROGRAM_ARCHIVE_INVALID") {
                "Corrupt JAR diagnostic changed: ${malformedResult.diagnostics}"
            }
            check(calls == 0)

            val target = File(fixture.root, "target.jar")
            writeJar(target, listOf("archive/Archived.class" to File(fixture.classes, "example/Example.class").readBytes()))
            val link = File(fixture.root, "program-link.jar")
            val linked = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
            if (!linked) {
                println("D8DexCompilerTests: archive symlink fixture skipped (symbolic links unavailable)")
            } else {
                val symlinkResult = D8DexCompiler(provider).compile(
                    fixture.request().copy(programArchives = listOf(link)), CancellationToken()
                )
                check(symlinkResult.outcome == BuildOutcome.FAILED)
                check(symlinkResult.diagnostics.single().code == "D8_PROGRAM_ARCHIVE_INVALID") {
                    "Symbolic-link JAR diagnostic changed: ${symlinkResult.diagnostics}"
                }
                check(calls == 0)
            }
        }
    }

    private fun programArchiveClassCannotDuplicateClassDirectoryEntry() {
        withFixture { fixture ->
            val archive = File(fixture.root, "duplicate.jar")
            writeJar(archive, listOf(
                "example/Example.class" to File(fixture.classes, "example/Example.class").readBytes()
            ))
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("duplicate program classes must not invoke D8")
            }

            val result = D8DexCompiler(provider).compile(
                fixture.request().copy(programArchives = listOf(archive)), CancellationToken()
            )

            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.single().code == "D8_PROGRAM_CLASS_DUPLICATE") {
                "Duplicate archive/class-directory class was not diagnosed: ${result.diagnostics}"
            }
            check(calls == 0)
        }
    }

    private fun outputMayNotOverlapProgramArchive() {
        withFixture { fixture ->
            val output = File(fixture.root, "out/with-input")
            val archive = File(output, "inputs/program.jar")
            writeJar(archive, listOf(
                "archive/Archived.class" to File(fixture.classes, "example/Example.class").readBytes()
            ))
            val digestBefore = sha256(archive)
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("D8 output overlapping its program archive must not invoke provider")
            }

            val result = D8DexCompiler(provider).compile(
                fixture.request(output).copy(programArchives = listOf(archive)), CancellationToken()
            )

            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.single().code == "D8_OUTPUT_INVALID") {
                "D8 output/archive overlap was not rejected: ${result.diagnostics}"
            }
            check(calls == 0)
            check(sha256(archive) == digestBefore) { "Overlap rejection modified the program archive" }
        }
    }

    private fun successWithoutDexFailsClosed() {
        withFixture { fixture ->
            val provider = FakeProvider { invocation, _ ->
                check(invocation.minApi == 23)
                check(invocation.release)
                D8InvocationResult(success = true)
            }
            val output = File(fixture.root, "out/no-dex")
            val result = D8DexCompiler(provider).compile(fixture.request(output), CancellationToken())
            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.single().code == "D8_OUTPUT_MISSING")
            check(result.artifacts.isEmpty())
        }
    }

    private fun invalidDexOutputFailsClosed() {
        withFixture { fixture ->
            val invalidName = FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes01.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true)
            }
            val badName = D8DexCompiler(invalidName).compile(fixture.request(), CancellationToken())
            check(badName.outcome == BuildOutcome.FAILED)
            check(badName.diagnostics.single().code == "D8_OUTPUT_INVALID")
            check(badName.artifacts.isEmpty())

            val invalidMagic = FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(ByteArray(12) { 1 })
                D8InvocationResult(success = true)
            }
            val badMagic = D8DexCompiler(invalidMagic).compile(fixture.request(), CancellationToken())
            check(badMagic.outcome == BuildOutcome.FAILED)
            check(badMagic.diagnostics.single().code == "D8_OUTPUT_INVALID")
        }
    }

    private fun multidexOutputIsValidatedAndNumericallySorted() {
        withFixture { fixture ->
            val provider = FakeProvider { invocation, _ ->
                // Deliberately create these in a non-numeric order.
                val creationOrder = listOf(10, 2, 1, 7, 3, 9, 4, 8, 5, 6)
                creationOrder.map { index ->
                    if (index == 1) "classes.dex" else "classes$index.dex"
                }.forEach { name ->
                    File(invocation.outputDirectory, name).writeBytes(validDexBytes())
                }
                D8InvocationResult(success = true)
            }
            val result = D8DexCompiler(provider).compile(fixture.request(), CancellationToken())
            check(result.outcome == BuildOutcome.SUCCESS) { result.diagnostics.toString() }
            check(result.artifacts.map { it.file.name } ==
                (listOf("classes.dex") + (2..10).map { "classes$it.dex" }))
            check(result.artifacts.all { it.kind == "android-dex" && it.sha256?.matches(Regex("[0-9a-f]{64}")) == true })
        }
    }

    private fun failedRerunRemovesStaleDexOutputs() {
        withFixture { fixture ->
            val output = File(fixture.root, "out/stale-cleanup")
            val succeeding = FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true)
            }
            val first = D8DexCompiler(succeeding).compile(fixture.request(output), CancellationToken())
            check(first.outcome == BuildOutcome.SUCCESS)
            check(File(output, "classes.dex").isFile)

            val failing = FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                File(invocation.outputDirectory, "classes2.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = false, diagnostics = "synthetic D8 failure")
            }
            val second = D8DexCompiler(failing).compile(fixture.request(output), CancellationToken())
            check(second.outcome == BuildOutcome.FAILED)
            check(second.diagnostics.single().code == "D8_COMPILE_FAILED")
            check(second.artifacts.isEmpty())
            check(output.listFiles().orEmpty().isEmpty()) { "Stale or partial dex output survived a failed D8 run" }
        }
    }

    private fun coreLibraryDesugaringNullPreservesDx1Behavior() {
        withFixture { fixture ->
            var captured: D8Invocation? = null
            val output = File(fixture.root, "out/dex")
            val result = D8DexCompiler(FakeProvider { invocation, _ ->
                captured = invocation
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true, desugaredLibraryKeepRules = "ignored by null configuration")
            }).compile(fixture.request(output), CancellationToken())

            check(result.success) { result.diagnostics.toString() }
            check(captured?.coreLibraryDesugaringConfigurationJson == null)
            check(result.artifacts.size == 1 && result.artifacts.single().kind == "android-dex")
            check(!File(fixture.root, "out/desugar/l8.pg").exists())
        }
    }

    private fun coreLibraryDesugaringConfigPassesThroughAndWritesRules() {
        withFixture { fixture ->
            val json = "{\"configuration_format_version\":200,\"identifier\":\"fixture\"}"
            val rules = "-keep class j$.nio.file.** { *; }\n"
            val output = File(fixture.root, "out/app")
            val keepRules = File(fixture.root, "out/desugar/l8.pg")
            var captured: D8Invocation? = null
            val result = D8DexCompiler(FakeProvider { invocation, _ ->
                captured = invocation
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true, desugaredLibraryKeepRules = rules)
            }).compile(
                fixture.request(output).copy(
                    coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(json, keepRules)
                ),
                CancellationToken()
            )

            check(result.success) { result.diagnostics.toString() }
            check(captured?.coreLibraryDesugaringConfigurationJson == json)
            check(captured?.maxKeepRulesChars == 8 * 1024 * 1024)
            check(keepRules.readText() == rules)
            check(result.artifacts.map { it.kind } == listOf("android-dex", "android-desugar-keep-rules"))
            val keepArtifact = result.artifacts.single { it.kind == "android-desugar-keep-rules" }
            check(keepArtifact.file.canonicalFile == keepRules.canonicalFile)
            check(keepArtifact.sha256 == sha256(keepRules))
        }
    }

    private fun coreLibraryDesugaringRequestLimitsAreValidated() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("invalid desugaring request must not invoke D8")
            }
            val base = fixture.request()
            val blank = D8DexCompiler(provider).compile(
                base.copy(coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(" \t", File(fixture.root, "out/l8.pg"))),
                CancellationToken()
            )
            check(blank.outcome == BuildOutcome.FAILED)
            check(blank.diagnostics.single().code == "D8_DESUGAR_CONFIG_INVALID")

            val oversized = D8DexCompiler(provider).compile(
                base.copy(
                    coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(
                        "💚",
                        File(fixture.root, "out/l8.pg"),
                        maxConfigurationBytes = 3
                    )
                ),
                CancellationToken()
            )
            check(oversized.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(oversized.diagnostics.single().code == "D8_DESUGAR_CONFIG_LIMIT")

            val invalidLimit = D8DexCompiler(provider).compile(
                base.copy(
                    coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(
                        "{}",
                        File(fixture.root, "out/l8.pg"),
                        maxKeepRulesBytes = 0
                    )
                ),
                CancellationToken()
            )
            check(invalidLimit.outcome == BuildOutcome.FAILED)
            check(invalidLimit.diagnostics.single().code == "D8_DESUGAR_CONFIG_INVALID")
            check(calls == 0)
        }
    }

    private fun coreLibraryDesugaringFailuresCleanBothOutputs() {
        withFixture { fixture ->
            val output = File(fixture.root, "out/app")
            val keepRules = File(fixture.root, "out/desugar/l8.pg")
            val desugaring = D8CoreLibraryDesugaringRequest("{}", keepRules)
            val seed = D8DexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true, desugaredLibraryKeepRules = "stale rules")
            }).compile(fixture.request(output).copy(coreLibraryDesugaring = desugaring), CancellationToken())
            check(seed.success && keepRules.isFile)

            val overLimit = D8DexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true, keepRulesLimitExceeded = true)
            }).compile(fixture.request(output).copy(coreLibraryDesugaring = desugaring), CancellationToken())
            check(overLimit.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(overLimit.diagnostics.single().code == "D8_DESUGAR_KEEP_RULES_LIMIT")
            check(output.listFiles().orEmpty().isEmpty())
            check(!keepRules.exists())

            val byteLimit = D8DexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true, desugaredLibraryKeepRules = "rules")
            }).compile(
                fixture.request(output).copy(
                    coreLibraryDesugaring = desugaring.copy(maxKeepRulesBytes = 2)
                ),
                CancellationToken()
            )
            check(byteLimit.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(byteLimit.diagnostics.single().code == "D8_DESUGAR_KEEP_RULES_LIMIT")
            check(output.listFiles().orEmpty().isEmpty())
            check(!keepRules.exists())

            val providerFailure = D8DexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = false, diagnostics = "synthetic desugaring failure")
            }).compile(fixture.request(output).copy(coreLibraryDesugaring = desugaring), CancellationToken())
            check(providerFailure.outcome == BuildOutcome.FAILED)
            check(providerFailure.diagnostics.single().code == "D8_COMPILE_FAILED")
            check(output.listFiles().orEmpty().isEmpty())
            check(!keepRules.exists())

            val token = CancellationToken()
            val cancelled = D8DexCompiler(FakeProvider { invocation, receivedToken ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                receivedToken.cancel()
                D8InvocationResult(success = true, desugaredLibraryKeepRules = "not persisted")
            }).compile(fixture.request(output).copy(coreLibraryDesugaring = desugaring), token)
            check(cancelled.outcome == BuildOutcome.CANCELLED)
            check(cancelled.diagnostics.single().code == "BUILD_CANCELLED")
            check(output.listFiles().orEmpty().isEmpty())
            check(!keepRules.exists())

            val emptyRules = D8DexCompiler(FakeProvider { invocation, _ ->
                File(invocation.outputDirectory, "classes.dex").writeBytes(validDexBytes())
                D8InvocationResult(success = true, desugaredLibraryKeepRules = "")
            }).compile(fixture.request(output).copy(coreLibraryDesugaring = desugaring), CancellationToken())
            check(emptyRules.success) { emptyRules.diagnostics.toString() }
            check(emptyRules.artifacts.map { it.kind } == listOf("android-dex"))
            check(!keepRules.exists())
        }
    }

    private fun coreLibraryDesugaringOutputMustBeIndependent() {
        withFixture { fixture ->
            var calls = 0
            val provider = FakeProvider { _, _ ->
                calls++
                error("unsafe keep-rules output must not invoke D8")
            }
            val appOutput = File(fixture.root, "out/app")
            val insideAppDex = D8DexCompiler(provider).compile(
                fixture.request(appOutput).copy(
                    coreLibraryDesugaring = D8CoreLibraryDesugaringRequest("{}", File(appOutput, "l8.pg"))
                ),
                CancellationToken()
            )
            check(insideAppDex.diagnostics.single().code == "D8_DESUGAR_OUTPUT_INVALID")

            val overlapsProgramInput = D8DexCompiler(provider).compile(
                fixture.request(appOutput).copy(
                    coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(
                        "{}",
                        File(fixture.classes, "l8.pg")
                    )
                ),
                CancellationToken()
            )
            check(overlapsProgramInput.diagnostics.single().code == "D8_DESUGAR_OUTPUT_INVALID")
            check(calls == 0)
        }
    }

    private fun realKotlinStdlibNioIntegration(androidJar: File) {
        val rawR8Jar = System.getenv("VEGA_R8_JAR")?.takeIf { it.isNotBlank() }
            ?: error("Set VEGA_R8_JAR to the repository-pinned R8 8.2.33 JAR")
        val r8Jar = File(rawR8Jar).canonicalFile
        check(r8Jar.isFile) { "VEGA_R8_JAR does not name an ordinary file: $r8Jar" }
        check(androidJar.isFile) { "Android 35 android.jar is missing: $androidJar" }

        val stdlibJar = File("assets/embedded/kotlin/classpath/kotlin-stdlib-1.9.20.jar").canonicalFile
        val runtimeJar = File("third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar").canonicalFile
        val configurationJar = File(
            "third_party/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
        ).canonicalFile
        check(stdlibJar.isFile && stdlibJar.length() == 1_718_949L &&
            sha256(stdlibJar) == "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c") {
            "Pinned Kotlin 1.9.20 stdlib length/SHA-256 mismatch"
        }
        check(runtimeJar.isFile &&
            sha256(runtimeJar) == "25ddad0d96cf7ba284cfa7866f54aa3508468a1df80e79cb44733a66f755d330") {
            "Pinned NIO desugar runtime JAR SHA-256 mismatch"
        }
        check(configurationJar.isFile &&
            sha256(configurationJar) == "1dd26f9e2440904eab5014a244f500d8b453f3629bfd5478a3f9895838e5c35a") {
            "Pinned NIO desugar configuration JAR SHA-256 mismatch"
        }
        check(sha256(androidJar) == "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b") {
            "Android platform input is not the pinned Android 35 android.jar"
        }
        val configurationJson = JarFile(configurationJar).use { jar ->
            val entry = jar.getJarEntry("META-INF/desugar/d8/desugar.json")
                ?: error("Pinned NIO configuration JAR lacks META-INF/desugar/d8/desugar.json")
            jar.getInputStream(entry).use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        }
        check(configurationJson.contains("com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1"))
        check(configurationJson.contains("\"configuration_format_version\":200"))
        check(configurationJson.contains("j$/"))

        val root = Files.createTempDirectory("vega-kotlin-stdlib-dx2b-real-").toFile()
        try {
            val source = File(root, "src/example/KotlinStdlibNioReference.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example;
                    public final class KotlinStdlibNioReference {
                        public static kotlin.Unit stdlibMarker() { return kotlin.Unit.INSTANCE; }
                        public static boolean exists(String path) {
                            return java.nio.file.Files.exists(java.nio.file.Paths.get(path));
                        }
                    }
                    """.trimIndent()
                )
            }
            val classes = File(root, "classes")
            val ecjResult = EcjJavaCompiler().compile(
                EcjCompileRequest(
                    sources = listOf(source),
                    outputDirectory = classes,
                    classpath = listOf(stdlibJar),
                    bootClasspath = listOf(androidJar)
                ),
                CancellationToken()
            )
            check(ecjResult.success) { "ECJ Kotlin-stdlib/NIO fixture compilation failed: ${ecjResult.diagnostics}" }
            val classFile = File(classes, "example/KotlinStdlibNioReference.class")
            check(classFile.isFile && classFile.length() > 8L) { "ECJ did not emit the app class directory input" }

            URLClassLoader(arrayOf(r8Jar.toURI().toURL()), ReflectionD8Provider::class.java.classLoader).use { loader ->
                val realD8 = ReflectionD8Provider(loader)
                check(realD8.available) { "Pinned R8 JAR does not expose the D8 API" }
                val d8Version = realD8.version ?: error("D8 Version API is unavailable")
                check(d8Version.contains("8.2.33")) { "Expected R8/D8 8.2.33, got $d8Version" }
                var capturedInvocation: D8Invocation? = null
                val observingD8 = object : D8Provider {
                    override val available: Boolean get() = realD8.available

                    override fun compile(
                        invocation: D8Invocation,
                        token: CancellationToken
                    ): D8InvocationResult {
                        capturedInvocation = invocation
                        return realD8.compile(invocation, token)
                    }
                }
                val d8Output = File(root, "app-dex")
                val keepRules = File(root, "app-desugar/l8.pg")
                val d8Result = D8DexCompiler(observingD8).compile(
                    D8CompileRequest(
                        classDirectories = listOf(classes),
                        libraryFiles = listOf(androidJar),
                        outputDirectory = d8Output,
                        minApi = 23,
                        programArchives = listOf(stdlibJar),
                        coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(configurationJson, keepRules)
                    ),
                    CancellationToken()
                )
                check(d8Result.success) { "Real D8 Kotlin-stdlib compilation failed: ${d8Result.diagnostics}" }
                val invocation = checkNotNull(capturedInvocation) { "Real D8 invocation was not observed" }
                check(invocation.programFiles.any { it.canonicalFile == stdlibJar }) {
                    "Pinned Kotlin stdlib was not passed as a D8 program input: ${invocation.programFiles}"
                }
                check(invocation.libraryFiles == listOf(androidJar)) {
                    "Android 35 android.jar was not kept in D8 library inputs: ${invocation.libraryFiles}"
                }
                val d8Dex = d8Result.artifacts.filter { it.kind == "android-dex" }
                check(d8Dex.isNotEmpty()) { "Real D8 produced no application DEX artifacts" }
                val d8DexText = d8Dex.joinToString("\n") {
                    String(it.file.readBytes(), StandardCharsets.ISO_8859_1)
                }
                check(d8DexText.contains("kotlin/Unit")) {
                    "D8 output lacks the Kotlin stdlib marker type evidence"
                }
                check(d8DexText.contains("j$/nio/file")) {
                    "D8 output lacks the NIO desugaring reference evidence"
                }
                val keepRuleArtifacts = d8Result.artifacts.filter { it.kind == "android-desugar-keep-rules" }
                check(keepRuleArtifacts.size == 1 && keepRules.isFile && keepRules.length() > 0L) {
                    "Real D8 did not produce exactly one non-empty L8 keep-rules artifact: ${d8Result.artifacts}"
                }

                val runtimeInputDigests = listOf(stdlibJar, runtimeJar, configurationJar, androidJar, keepRules)
                    .associateWith(::sha256)
                val realL8 = ReflectionL8Provider(loader)
                check(realL8.available) { "Pinned R8 JAR does not expose the L8 API" }
                val l8Version = realL8.version ?: error("L8 Version API is unavailable")
                check(l8Version.contains("8.2.33")) { "Expected R8/L8 8.2.33, got $l8Version" }
                val l8Output = File(root, "desugar-runtime-dex")
                val l8Result = L8RuntimeDexCompiler(realL8).compile(
                    L8CompileRequest(
                        desugaredLibraryConfigurationJson = configurationJson,
                        keepRulesFile = keepRules,
                        programFiles = listOf(runtimeJar, configurationJar),
                        libraryFiles = listOf(androidJar),
                        outputDirectory = l8Output,
                        minApi = 23,
                        release = true
                    ),
                    CancellationToken()
                )
                check(l8Result.success) { "Real L8 NIO runtime compilation failed: ${l8Result.diagnostics}" }
                check(l8Result.artifacts.isNotEmpty() &&
                    l8Result.artifacts.all { it.kind == "android-desugar-runtime-dex" }) {
                    "L8 did not return verified runtime DEX artifacts: ${l8Result.artifacts}"
                }
                val l8DexText = l8Result.artifacts.joinToString("\n") {
                    String(it.file.readBytes(), StandardCharsets.ISO_8859_1)
                }
                check(l8DexText.contains("j$/nio/file")) {
                    "L8 output lacks j$/nio/file runtime implementation evidence"
                }
                check(listOf(stdlibJar, runtimeJar, configurationJar, androidJar, keepRules)
                    .associateWith(::sha256) == runtimeInputDigests) {
                    "D8/L8 modified the stdlib, desugar, Android, or keep-rules inputs"
                }
                println(
                    "D8DexCompilerTests: real Kotlin-stdlib DX2A/DX2B PASS " +
                        "(D8=$d8Version, L8=$l8Version, " +
                        "programArchives=${invocation.programFiles.filter { it.canonicalFile == stdlibJar }}, " +
                        "libraryFiles=${invocation.libraryFiles}, " +
                        "appDex=${d8Dex.map { it.file.name to it.sha256 }}, " +
                        "l8pgSha256=${sha256(keepRules)}, " +
                        "runtimeDex=${l8Result.artifacts.map { it.file.name to it.sha256 }})"
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun realD8Integration(androidJar: File?) {
        val rawJar = System.getenv("VEGA_R8_JAR")?.takeIf { it.isNotBlank() }
        if (rawJar == null) {
            println("D8DexCompilerTests: real D8 integration skipped (VEGA_R8_JAR is unset)")
            return
        }
        val r8Jar = File(rawJar)
        check(r8Jar.isFile) { "VEGA_R8_JAR does not name an ordinary file: $r8Jar" }
        check(androidJar?.isFile == true) { "Offline test invocation must supply the Android 35 android.jar" }

        val configurationJar = File(
            "third_party/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
        ).absoluteFile
        check(configurationJar.isFile) { "Pinned desugaring configuration JAR is missing: $configurationJar" }
        val configurationJson = JarFile(configurationJar).use { jar ->
            val entry = jar.getJarEntry("META-INF/desugar/d8/desugar.json")
                ?: error("Pinned configuration JAR lacks META-INF/desugar/d8/desugar.json")
            jar.getInputStream(entry).use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        }
        check(configurationJson.contains("com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1"))
        check(configurationJson.contains("\"configuration_format_version\":200"))
        check(configurationJson.contains("j$/"))

        val root = Files.createTempDirectory("vega-dx2a-real").toFile()
        try {
            val sourceDirectory = File(root, "src/example").apply { mkdirs() }
            val plain = File(sourceDirectory, "Plain.java").apply {
                writeText(
                    """
                    package example;
                    public final class Plain {
                        public static int value() { return 42; }
                    }
                    """.trimIndent()
                )
            }
            val androidReference = File(sourceDirectory, "AndroidReference.java").apply {
                writeText(
                    """
                    package example;
                    public final class AndroidReference {
                        public static int sdk() { return android.os.Build.VERSION.SDK_INT; }
                    }
                    """.trimIndent()
                )
            }
            val nioReference = File(sourceDirectory, "NioReference.java").apply {
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
                EcjCompileRequest(
                    sources = listOf(plain, androidReference, nioReference),
                    outputDirectory = classes,
                    bootClasspath = listOf(androidJar!!)
                ),
                CancellationToken()
            )
            check(ecj.success) { "ECJ fixture compilation failed: ${ecj.diagnostics}" }

            URLClassLoader(arrayOf(r8Jar.toURI().toURL()), ReflectionD8Provider::class.java.classLoader).use { loader ->
                val provider = ReflectionD8Provider(loader)
                check(provider.available) { "Explicit VEGA_R8_JAR does not expose the required D8 API" }
                val version = provider.version ?: error("D8 Version.getVersionString() did not return a version")
                check(version.contains("8.2.33")) { "DX2A fixture requires R8/D8 8.2.33; got $version" }
                val compiler = D8DexCompiler(provider)
                val firstOutput = File(root, "dex-first")
                val firstKeepRules = File(root, "desugar-first/l8.pg")
                val first = compiler.compile(
                    D8CompileRequest(
                        listOf(classes),
                        listOf(androidJar),
                        firstOutput,
                        minApi = 23,
                        coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(configurationJson, firstKeepRules)
                    ),
                    CancellationToken()
                )
                check(first.success) { "Real D8 compilation failed: ${first.diagnostics}" }
                check(first.artifacts.isNotEmpty())
                check(first.artifacts.map { it.kind }.toSet() == setOf("android-dex", "android-desugar-keep-rules"))
                check(firstKeepRules.isFile && firstKeepRules.length() > 0L)
                val firstDex = first.artifacts.single { it.kind == "android-dex" }
                check(String(firstDex.file.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file")) {
                    "Real D8 output does not contain the expected j$/nio/file reference evidence"
                }
                val firstRules = first.artifacts.single { it.kind == "android-desugar-keep-rules" }

                val secondOutput = File(root, "dex-second")
                val secondKeepRules = File(root, "desugar-second/l8.pg")
                val second = compiler.compile(
                    D8CompileRequest(
                        listOf(classes),
                        listOf(androidJar),
                        secondOutput,
                        minApi = 23,
                        coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(configurationJson, secondKeepRules)
                    ),
                    CancellationToken()
                )
                check(second.success) { "Deterministic rerun failed: ${second.diagnostics}" }
                val secondDex = second.artifacts.single { it.kind == "android-dex" }
                val secondRules = second.artifacts.single { it.kind == "android-desugar-keep-rules" }
                check(secondDex.sha256 == firstDex.sha256) { "DEX output digest changed across identical inputs" }
                check(secondRules.sha256 == firstRules.sha256) { "D8 keep-rule digest changed across identical inputs" }
                check(String(secondDex.file.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file")) {
                    "Repeated D8 output does not contain the expected j$/nio/file reference evidence"
                }
                println(
                    "D8DexCompilerTests: real DX2A integration OK " +
                        "(version=$version, dexSha256=${firstDex.sha256}, keepRulesSha256=${firstRules.sha256})"
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

    private fun writeJar(output: File, entries: List<Pair<String, ByteArray>>) {
        output.parentFile?.mkdirs()
        JarOutputStream(output.outputStream()).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("vega-dx1-test").toFile()
        try {
            block(Fixture.create(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(val root: File, val classes: File, val library: File) {
        fun request(output: File = File(root, "out/dex")) = D8CompileRequest(
            classDirectories = listOf(classes),
            libraryFiles = listOf(library),
            outputDirectory = output,
            minApi = 23
        )

        companion object {
            fun create(root: File): Fixture {
                val classes = File(root, "classes/example").apply { mkdirs() }
                File(classes, "Example.class").writeBytes(
                    byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 1)
                )
                val library = File(root, "android.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                return Fixture(root, classes.parentFile, library)
            }
        }
    }

    private class FakeProvider(
        override val available: Boolean = true,
        private val action: (D8Invocation, CancellationToken) -> D8InvocationResult
    ) : D8Provider {
        override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult =
            action(invocation, token)
    }
}
