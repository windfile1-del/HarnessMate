package dev.wind.agent

import dev.wind.agent.build.BuildOutcome
import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjCompilerProvider
import dev.wind.agent.build.EcjInvocation
import dev.wind.agent.build.EcjInvocationResult
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.ReflectionEcjCompilerProvider
import dev.wind.agent.build.CompilerDiagnosticWriter
import java.io.File
import java.nio.file.Files

/** B3 ECJ adapter contract; no external ECJ jar is assumed by this test. */
object EcjJavaCompilerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        absentProviderFailsClosed()
        injectedProviderProducesTruthfulOutput()
        providerFailureIsStructured()
        successWithoutClassesFailsClosed()
        resourceLimitAndCancellationAreExplicit()
        compilerDiagnosticsAreBounded()
        languageLevelCannotInjectCompilerOptions()
        realProviderIsAvailable()
        realProviderCompilesValidJava17()
        realProviderFailsInvalidJava()
        dependencyAnnotationProcessorIsNotExecuted()
        println("EcjJavaCompilerTests: OK")
    }

    private fun absentProviderFailsClosed() {
        val fixture = Fixture.create()
        try {
            val provider = ReflectionEcjCompilerProvider(object : ClassLoader(null) {})
            val result = EcjJavaCompiler(provider).compile(fixture.request(), CancellationToken())
            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(result.producedArtifacts.isEmpty())
            check(result.diagnostics.any { it.code == "ECJ_UNAVAILABLE" })
        } finally {
            fixture.close()
        }
    }

    private fun injectedProviderProducesTruthfulOutput() {
        val fixture = Fixture.create()
        try {
            var invocation: EcjInvocation? = null
            val provider = object : EcjCompilerProvider {
                override val available = true

                override fun compile(
                    request: EcjInvocation,
                    token: CancellationToken
                ): EcjInvocationResult {
                    invocation = request
                    check(!token.isCancelled)
                    File(request.outputDirectory, "Example.class").writeText("class")
                    return EcjInvocationResult(success = true)
                }
            }
            val result = EcjJavaCompiler(provider).compile(
                fixture.request(classpath = listOf(fixture.classpath)),
                CancellationToken()
            )
            check(result.outcome == BuildOutcome.SUCCESS)
            check(result.success)
            check(result.producedArtifacts.single().kind == "JAVA_CLASS_OUTPUT")
            check(invocation?.commandLine?.contains("-source 17") == true)
            check(invocation?.commandLine?.contains("-classpath") == true)
            check(invocation?.commandLine?.contains("-proc:none") == true)
        } finally {
            fixture.close()
        }
    }

    private fun providerFailureIsStructured() {
        val fixture = Fixture.create()
        try {
            val provider = object : EcjCompilerProvider {
                override val available = true

                override fun compile(
                    request: EcjInvocation,
                    token: CancellationToken
                ): EcjInvocationResult =
                    EcjInvocationResult(success = false, stderr = "Example.java:1: error")
            }
            val result = EcjJavaCompiler(provider).compile(fixture.request(), CancellationToken())
            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.single().code == "ECJ_COMPILE_FAILED")
            check(result.diagnostics.single().message.contains("Example.java"))
        } finally {
            fixture.close()
        }
    }

    private fun successWithoutClassesFailsClosed() {
        val fixture = Fixture.create()
        try {
            val provider = object : EcjCompilerProvider {
                override val available = true

                override fun compile(
                    request: EcjInvocation,
                    token: CancellationToken
                ): EcjInvocationResult = EcjInvocationResult(success = true)
            }
            val result = EcjJavaCompiler(provider).compile(fixture.request(), CancellationToken())
            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.single().code == "ECJ_NO_CLASS_OUTPUT")
        } finally {
            fixture.close()
        }
    }

    private fun resourceLimitAndCancellationAreExplicit() {
        val fixture = Fixture.create()
        try {
            val provider = object : EcjCompilerProvider {
                override val available = true
                override fun compile(
                    request: EcjInvocation,
                    token: CancellationToken
                ): EcjInvocationResult = EcjInvocationResult(success = true)
            }
            val limited = EcjJavaCompiler(provider).compile(
                fixture.request(maxSourceBytes = 1L),
                CancellationToken()
            )
            check(limited.outcome == BuildOutcome.RESOURCE_LIMIT)
            check(limited.diagnostics.single().code == "ECJ_RESOURCE_LIMIT")

            val cancelled = CancellationToken().also { it.cancel() }
            val result = EcjJavaCompiler(provider).compile(fixture.request(), cancelled)
            check(result.outcome == BuildOutcome.CANCELLED)
            check(result.diagnostics.single().code == "BUILD_CANCELLED")
        } finally {
            fixture.close()
        }
    }

    private fun realProviderIsAvailable() {
        val provider = ReflectionEcjCompilerProvider()
        check(provider.available) {
            "Expected ReflectionEcjCompilerProvider().available to be true under canonical offline classpath"
        }
    }

    private fun dependencyAnnotationProcessorIsNotExecuted() {
        val root = Files.createTempDirectory("vega-ecj-processor").toFile()
        try {
            val processor = File(root, "UnexpectedProcessor.java").apply {
                writeText("""
                    import javax.annotation.processing.*;
                    import javax.lang.model.SourceVersion;
                    import javax.lang.model.element.TypeElement;
                    import java.util.Set;
                    @SupportedAnnotationTypes("*")
                    @SupportedSourceVersion(SourceVersion.RELEASE_17)
                    public class UnexpectedProcessor extends AbstractProcessor {
                        public boolean process(Set<? extends TypeElement> types, RoundEnvironment round) {
                            throw new AssertionError("DEPENDENCY_PROCESSOR_EXECUTED");
                        }
                    }
                """.trimIndent())
            }
            val processorClasses = File(root, "processor-classes")
            val compiler = EcjJavaCompiler()
            check(compiler.compile(EcjCompileRequest(listOf(processor), processorClasses),
                CancellationToken()).success)
            File(processorClasses, "META-INF/services/javax.annotation.processing.Processor").apply {
                parentFile.mkdirs()
                writeText("UnexpectedProcessor\n")
            }
            val source = File(root, "Clean.java").apply { writeText("public class Clean {}") }
            val output = File(root, "classes")
            val result = compiler.compile(EcjCompileRequest(listOf(source), output,
                classpath = listOf(processorClasses)), CancellationToken())
            check(result.success) { "Dependency annotation processor was executed: ${result.diagnostics}" }
            check(File(output, "Clean.class").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compilerDiagnosticsAreBounded() {
        val writer = CompilerDiagnosticWriter()
        repeat(1000) { writer.write("x".repeat(4096)) }
        check(writer.toString() == "x".repeat(64 * 1024) + "\n[compiler diagnostics truncated]")
        writer.write("later output")
        check(writer.toString().length < 66 * 1024)
    }

    private fun languageLevelCannotInjectCompilerOptions() {
        val fixture = Fixture.create()
        try {
            for (level in listOf("17 -processor Evil", "17 -d /outside", "", "99")) {
                check(runCatching { fixture.request().copy(sourceLevel = level) }.isFailure)
                check(runCatching { fixture.request().copy(targetLevel = level) }.isFailure)
            }
        } finally {
            fixture.close()
        }
    }

    private fun realProviderCompilesValidJava17() {
        val root = Files.createTempDirectory("vega-ecj-real-valid").toFile()
        try {
            val srcDir = File(root, "src/c1b2").apply { mkdirs() }
            val source = File(srcDir, "RealExample.java").apply {
                writeText(
                    """
                    package c1b2;

                    public final class RealExample {
                        public static int deterministicValue() {
                            return 42;
                        }
                    }
                    """.trimIndent()
                )
            }
            val output = File(root, "classes")
            val provider = ReflectionEcjCompilerProvider()
            val compiler = EcjJavaCompiler(provider)
            val request = EcjCompileRequest(
                sources = listOf(source),
                outputDirectory = output,
                sourceLevel = "17",
                targetLevel = "17"
            )
            val result = compiler.compile(request, CancellationToken())
            check(result.outcome == BuildOutcome.SUCCESS) {
                "Expected SUCCESS but got ${result.outcome}"
            }
            check(result.success) { "Expected result.success to be true" }
            check(result.producedArtifacts.size == 1) { "Expected exactly 1 produced artifact" }
            val artifact = result.producedArtifacts.single()
            check(artifact.kind == "JAVA_CLASS_OUTPUT") {
                "Expected JAVA_CLASS_OUTPUT but got ${artifact.kind}"
            }
            check(artifact.file == output) {
                "Expected artifact file $output but got ${artifact.file}"
            }

            val classFile = File(output, "c1b2/RealExample.class")
            check(classFile.exists()) {
                "Expected class file at ${classFile.absolutePath} to exist"
            }
            check(classFile.isFile) { "Expected ${classFile.absolutePath} to be a file" }
            check(classFile.length() > 4L) {
                "Expected class file size > 4 bytes, was ${classFile.length()}"
            }

            val bytes = classFile.readBytes()
            check(bytes.size >= 4) { "Class file too small" }
            val expectedMagic = byteArrayOf(
                0xCA.toByte(),
                0xFE.toByte(),
                0xBA.toByte(),
                0xBE.toByte()
            )
            check(
                bytes[0] == expectedMagic[0] &&
                bytes[1] == expectedMagic[1] &&
                bytes[2] == expectedMagic[2] &&
                bytes[3] == expectedMagic[3]
            ) {
                "First four bytes must be CA FE BA BE"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun realProviderFailsInvalidJava() {
        val root = Files.createTempDirectory("vega-ecj-real-invalid").toFile()
        try {
            val srcDir = File(root, "src/c1b2").apply { mkdirs() }
            val source = File(srcDir, "BrokenExample.java").apply {
                writeText(
                    """
                    package c1b2;

                    public final class BrokenExample {
                        broken syntax invalid code !@#$
                    }
                    """.trimIndent()
                )
            }
            val output = File(root, "classes")
            val provider = ReflectionEcjCompilerProvider()
            val compiler = EcjJavaCompiler(provider)
            val request = EcjCompileRequest(
                sources = listOf(source),
                outputDirectory = output,
                sourceLevel = "17",
                targetLevel = "17"
            )
            val result = compiler.compile(request, CancellationToken())
            check(result.outcome == BuildOutcome.FAILED) {
                "Expected FAILED but got ${result.outcome}"
            }
            check(!result.success) { "Expected result.success to be false" }
            check(result.producedArtifacts.isEmpty()) { "Expected producedArtifacts to be empty" }
            check(result.diagnostics.isNotEmpty()) { "Expected diagnostics" }
            val diagnostic = result.diagnostics.single()
            check(diagnostic.code == "ECJ_COMPILE_FAILED") {
                "Expected diagnostic code ECJ_COMPILE_FAILED but got ${diagnostic.code}"
            }
            check(diagnostic.message.isNotBlank()) { "Expected nonblank diagnostic message" }

            val classFiles = if (output.exists()) {
                output.walkTopDown().filter { it.isFile && it.extension.equals("class", ignoreCase = true) }.toList()
            } else {
                emptyList()
            }
            check(classFiles.isEmpty()) {
                "Expected no .class output for invalid source, but found: $classFiles"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture private constructor(
        val root: File,
        val source: File,
        val output: File,
        val classpath: File
    ) {
        fun request(
            classpath: List<File> = emptyList(),
            maxSourceBytes: Long = 1024L
        ) = EcjCompileRequest(
            sources = listOf(source),
            outputDirectory = output,
            classpath = classpath,
            maxSourceBytes = maxSourceBytes
        )

        fun close() {
            root.deleteRecursively()
        }

        companion object {
            fun create(): Fixture {
                val root = Files.createTempDirectory("vega-ecj").toFile()
                val source = File(root, "Example.java").apply {
                    writeText("public class Example {}")
                }
                val output = File(root, "classes")
                val classpath = File(root, "dependency.jar").apply { writeText("jar") }
                return Fixture(root, source, output, classpath)
            }
        }
    }
}
