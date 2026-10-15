package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File
import java.io.PrintWriter
import java.io.Writer

/** Keep compiler diagnostics bounded even when a provider emits pathological output. */
internal class CompilerDiagnosticWriter(private val limit: Int = 64 * 1024) : Writer() {
    private val text = StringBuilder()
    private var truncated = false
    override fun write(chars: CharArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= chars.size - length)
        val count = minOf(length, limit - text.length)
        if (count > 0) text.append(chars, offset, count)
        if (count < length) truncated = true
    }
    override fun flush() = Unit
    override fun close() = Unit
    override fun toString(): String = text.toString() +
        if (truncated) "\n[compiler diagnostics truncated]" else ""
}

data class EcjCompileRequest(
    val sources: List<File>,
    val outputDirectory: File,
    val classpath: List<File> = emptyList(),
    val sourceLevel: String = "17",
    val targetLevel: String = "17",
    val maxSources: Int = 512,
    val maxSourceBytes: Long = 8L * 1024L * 1024L,
    val bootClasspath: List<File> = emptyList()
) {
    init {
        require(sources.isNotEmpty()) { "ECJ_SOURCES_EMPTY" }
        require(sources.size <= maxSources) { "ECJ_SOURCE_COUNT_LIMIT" }
        require(maxSources > 0) { "ECJ_SOURCE_COUNT_LIMIT_INVALID" }
        require(maxSourceBytes > 0L) { "ECJ_SOURCE_BYTES_LIMIT_INVALID" }
        require(sources.all { it.isFile }) { "ECJ_SOURCE_MISSING" }
        require(outputDirectory.path.isNotBlank()) { "ECJ_OUTPUT_DIRECTORY_MISSING" }
        require(sourceLevel in SUPPORTED_LEVELS && targetLevel in SUPPORTED_LEVELS) {
            "ECJ_LANGUAGE_LEVEL_UNSUPPORTED"
        }
    }

    companion object {
        private val SUPPORTED_LEVELS = (8..17).map { it.toString() }.toSet() + "1.8"
    }
}

data class EcjInvocation(
    val commandLine: String,
    val outputDirectory: File,
    val sources: List<File>,
    val bootClasspath: List<File> = emptyList()
)

data class EcjInvocationResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = ""
)

/**
 * Optional provider boundary. The default implementation discovers ECJ only
 * when its actual batch compiler classes are packaged in the runtime.
 */
interface EcjCompilerProvider {
    val available: Boolean

    fun compile(invocation: EcjInvocation, token: CancellationToken): EcjInvocationResult
}

/**
 * Reflection keeps an absent ECJ artifact out of the Android link. Once an
 * ECJ jar is packaged, this invokes BatchCompiler in-process, never a shell.
 */
class ReflectionEcjCompilerProvider(
    private val loader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: ReflectionEcjCompilerProvider::class.java.classLoader
) : EcjCompilerProvider {
    private val compilerClass = runCatching {
        Class.forName(BATCH_COMPILER_CLASS, false, loader)
    }.getOrNull()
    private val progressClass = runCatching {
        Class.forName(COMPILATION_PROGRESS_CLASS, false, loader)
    }.getOrNull()
    private val compileMethod = runCatching {
        val compiler = compilerClass ?: return@runCatching null
        val progress = progressClass ?: return@runCatching null
        compiler.getMethod(
            "compile",
            String::class.java,
            PrintWriter::class.java,
            PrintWriter::class.java,
            progress
        )
    }.getOrNull()

    override val available: Boolean
        get() = compileMethod != null

    override fun compile(
        invocation: EcjInvocation,
        token: CancellationToken
    ): EcjInvocationResult {
        if (!available) {
            return EcjInvocationResult(false, stderr = "ECJ provider classes are unavailable")
        }
        if (token.isCancelled) {
            return EcjInvocationResult(false, stderr = "cancelled")
        }
        val stdout = CompilerDiagnosticWriter()
        val stderr = CompilerDiagnosticWriter()
        return runCatching {
            val ok = if (invocation.bootClasspath.isNotEmpty()) {
                AndroidEcjMain(PrintWriter(stdout, true), PrintWriter(stderr, true),
                    invocation.bootClasspath, token).compileCommand(invocation.commandLine)
            } else compileMethod!!.invoke(
                null,
                invocation.commandLine,
                PrintWriter(stdout, true),
                PrintWriter(stderr, true),
                null
            ) as? Boolean ?: false
            EcjInvocationResult(ok, stdout.toString(), stderr.toString())
        }.getOrElse { error ->
            EcjInvocationResult(
                success = false,
                stdout = stdout.toString(),
                stderr = stderr.toString().ifBlank {
                    error.javaClass.simpleName + ": " + (error.message ?: "ECJ invocation failed")
                }
            )
        }
    }

    companion object {
        const val BATCH_COMPILER_CLASS = "org.eclipse.jdt.core.compiler.batch.BatchCompiler"
        const val COMPILATION_PROGRESS_CLASS = "org.eclipse.jdt.core.compiler.CompilationProgress"
    }
}

/**
 * Production-facing Java compiler adapter. It only succeeds after a real ECJ
 * provider returns success and leaves class output; no JDK javac fallback is
 * hidden behind this API.
 */
class EcjJavaCompiler(
    private val provider: EcjCompilerProvider = ReflectionEcjCompilerProvider()
) {
    fun compile(request: EcjCompileRequest, token: CancellationToken): BuildResult {
        val started = System.nanoTime()
        fun result(
            outcome: BuildOutcome,
            diagnostics: List<BuildDiagnostic> = emptyList(),
            artifacts: List<BuildArtifact> = emptyList()
        ): BuildResult {
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            val taskOutcome = when (outcome) {
                BuildOutcome.SUCCESS -> BuildTaskOutcome.EXECUTED
                BuildOutcome.CANCELLED -> BuildTaskOutcome.CANCELLED
                BuildOutcome.RESOURCE_LIMIT -> BuildTaskOutcome.SKIPPED
                BuildOutcome.UNSUPPORTED -> BuildTaskOutcome.SKIPPED
                BuildOutcome.FAILED -> BuildTaskOutcome.FAILED
            }
            return BuildResult(
                outcome = outcome,
                diagnostics = diagnostics,
                producedArtifacts = artifacts,
                taskResults = listOf(
                    BuildTaskResult(
                        id = "compile-java",
                        outcome = taskOutcome,
                        durationMs = elapsed,
                        diagnostics = diagnostics
                    )
                ),
                durationMs = elapsed
            )
        }

        if (token.isCancelled) {
            return result(
                BuildOutcome.CANCELLED,
                listOf(javaDiagnostic("BUILD_CANCELLED", "ECJ compilation was cancelled"))
            )
        }
        val sourceBytes = request.sources.sumOf { it.length() }
        if (request.sources.size > request.maxSources ||
            sourceBytes > request.maxSourceBytes
        ) {
            return result(
                BuildOutcome.RESOURCE_LIMIT,
                listOf(javaDiagnostic(
                    "ECJ_RESOURCE_LIMIT",
                    "Java source input exceeds the configured embedded compiler budget"
                ))
            )
        }
        if (!provider.available) {
            return result(
                BuildOutcome.UNSUPPORTED,
                listOf(BuildDiagnostic(
                    BuildDiagnosticSeverity.ERROR,
                    "ECJ_UNAVAILABLE",
                    "Eclipse ECJ BatchCompiler is not packaged in this runtime",
                    BuildDiagnosticSource.TOOLCHAIN
                ))
            )
        }

        if (!request.outputDirectory.exists() && !request.outputDirectory.mkdirs()) {
            return result(
                BuildOutcome.FAILED,
                listOf(javaDiagnostic(
                    "ECJ_OUTPUT_DIRECTORY_UNAVAILABLE",
                    "Could not create Java compiler output directory"
                ))
            )
        }
        if (!request.outputDirectory.isDirectory) {
            return result(
                BuildOutcome.FAILED,
                listOf(javaDiagnostic(
                    "ECJ_OUTPUT_DIRECTORY_UNAVAILABLE",
                    "Java compiler output path is not a directory"
                ))
            )
        }

        val invocation = EcjInvocation(
            commandLine = commandLine(request),
            outputDirectory = request.outputDirectory,
            sources = request.sources,
            bootClasspath = request.bootClasspath
        )
        val invocationResult = runCatching {
            provider.compile(invocation, token)
        }.getOrElse { error ->
            return result(
                BuildOutcome.FAILED,
                listOf(javaDiagnostic(
                    "ECJ_PROVIDER_FAILURE",
                    error.javaClass.simpleName + ": " + (error.message ?: "provider invocation failed")
                ))
            )
        }
        if (token.isCancelled) {
            return result(
                BuildOutcome.CANCELLED,
                listOf(javaDiagnostic("BUILD_CANCELLED", "ECJ compilation was cancelled"))
            )
        }
        if (!invocationResult.success) {
            val message = invocationResult.stderr.ifBlank {
                invocationResult.stdout.ifBlank { "ECJ compilation failed" }
            }
            return result(
                BuildOutcome.FAILED,
                listOf(javaDiagnostic("ECJ_COMPILE_FAILED", message))
            )
        }
        val classFiles = request.outputDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("class", ignoreCase = true) }
            .toList()
        if (classFiles.isEmpty()) {
            return result(
                BuildOutcome.FAILED,
                listOf(javaDiagnostic(
                    "ECJ_NO_CLASS_OUTPUT",
                    "ECJ reported success but produced no class files"
                ))
            )
        }
        return result(
            BuildOutcome.SUCCESS,
            artifacts = listOf(BuildArtifact("JAVA_CLASS_OUTPUT", request.outputDirectory))
        )
    }

    private fun commandLine(request: EcjCompileRequest): String {
        val args = mutableListOf(
            "-encoding", "UTF-8",
            // Annotation processors are executable dependency code and need a
            // separate authorized generated-source stage. Never discover them
            // implicitly from the ordinary project classpath on Android.
            "-proc:none",
            "-source", request.sourceLevel,
            "-target", request.targetLevel,
            "-d", quote(request.outputDirectory.absolutePath)
        )
        if (request.classpath.isNotEmpty()) {
            args += listOf(
                "-classpath",
                quote(request.classpath.joinToString(File.pathSeparator) { it.absolutePath })
            )
        }
        args += request.sources.map { quote(it.absolutePath) }
        return args.joinToString(" ")
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\"", "\\\"") + "\""

    private fun javaDiagnostic(code: String, message: String) = BuildDiagnostic(
        BuildDiagnosticSeverity.ERROR,
        code,
        message,
        BuildDiagnosticSource.JAVA
    )
}
