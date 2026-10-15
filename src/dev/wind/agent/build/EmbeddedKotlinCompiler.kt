package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.DataInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class KotlinCompileRequest(
    val sources: List<File>,
    val outputDirectory: File,
    val bootClasspath: List<File>,
    val classpath: List<File>,
    val javaSources: List<File> = emptyList(),
    val target: String = "17",
    val maxSources: Int = 512,
    val maxSourceBytes: Long = 8L * 1024 * 1024,
    val maxOutputBytes: Long = 64L * 1024 * 1024,
    val timeoutMs: Long = 60_000
) {
    init {
        require(target in (8..17).map(Int::toString)) { "KOTLIN_TARGET_UNSUPPORTED" }
        require(maxSources > 0 && maxSourceBytes > 0 && maxOutputBytes > 0 && timeoutMs > 0)
    }
}

/** In-process only. Absence of the compiler never falls back to a host executable. */
fun interface KotlinCompilationProvider {
    fun compile(request: KotlinCompileRequest, token: CancellationToken): BuildResult
}

class EmbeddedKotlinCompiler(
    private val loader: ClassLoader = EmbeddedKotlinCompiler::class.java.classLoader,
    private val javaCompiler: EcjJavaCompiler = EcjJavaCompiler(ReflectionEcjCompilerProvider(loader))
) : KotlinCompilationProvider {
    override fun compile(request: KotlinCompileRequest, token: CancellationToken): BuildResult {
        val start = System.nanoTime()
        val diagnostics = ArrayList<BuildDiagnostic>()
        var diagnosticChars = 0
        var hadErrors = false
        var diagnosticsTruncated = false
        var stage: File? = null
        val output = request.outputDirectory.absoluteFile
        fun elapsed() = (System.nanoTime() - start) / 1_000_000
        fun expired() = elapsed() >= request.timeoutMs
        fun diagnostic(code: String, message: String) {
            diagnostics.add(BuildDiagnostic(BuildDiagnosticSeverity.ERROR, code,
                message.take(4096).ifBlank { code }, BuildDiagnosticSource.KOTLIN))
        }
        fun finish(outcome: BuildOutcome): BuildResult {
            stage?.let {
                if (it.deleteRecursively()) stage = null else diagnostics.add(BuildDiagnostic(
                    BuildDiagnosticSeverity.WARNING, "KOTLIN_STAGE_CLEANUP_FAILED",
                    "Private compiler staging output could not be fully removed", BuildDiagnosticSource.KOTLIN))
            }
            val reported = diagnostics.toList() + if (diagnosticsTruncated) listOf(BuildDiagnostic(
                BuildDiagnosticSeverity.WARNING, "KOTLIN_DIAGNOSTICS_TRUNCATED",
                "Additional compiler diagnostics exceeded the reporting budget", BuildDiagnosticSource.KOTLIN)) else emptyList()
            return BuildResult(
            outcome, reported,
            if (outcome == BuildOutcome.SUCCESS)
                listOf(BuildArtifact("JVM_CLASS_OUTPUT", output)) else emptyList(),
            listOf(BuildTaskResult("compile-kotlin-java", when (outcome) {
                BuildOutcome.SUCCESS -> BuildTaskOutcome.EXECUTED
                BuildOutcome.CANCELLED -> BuildTaskOutcome.CANCELLED
                BuildOutcome.UNSUPPORTED, BuildOutcome.RESOURCE_LIMIT -> BuildTaskOutcome.SKIPPED
                BuildOutcome.FAILED -> BuildTaskOutcome.FAILED
            }, elapsed(), reported)), elapsed())
        }
        if (token.isCancelled) return finish(BuildOutcome.CANCELLED)
        val sources = request.sources + request.javaSources
        if (request.sources.isEmpty() || request.sources.any { !it.isFile || it.extension != "kt" } ||
            request.javaSources.any { !it.isFile || it.extension != "java" } ||
            request.bootClasspath.isEmpty() || (request.bootClasspath + request.classpath).any { !it.exists() }) {
            diagnostic("KOTLIN_INPUT_INVALID", "Explicit Kotlin sources, platform and classpath are required")
            return finish(BuildOutcome.FAILED)
        }
        var inputBytes = 0L
        for (source in sources) {
            if (source.length() > request.maxSourceBytes - inputBytes) {
                diagnostic("KOTLIN_SOURCE_LIMIT", "Source input exceeds the byte budget")
                return finish(BuildOutcome.RESOURCE_LIMIT)
            }
            inputBytes += source.length()
        }
        if (sources.size > request.maxSources) {
            diagnostic("KOTLIN_SOURCE_LIMIT", "Source input exceeds the file budget")
            return finish(BuildOutcome.RESOURCE_LIMIT)
        }
        val compilerType = try { Class.forName(COMPILER, false, loader) } catch (_: ClassNotFoundException) {
            diagnostic("KOTLIN_UNAVAILABLE", "Embedded Kotlin compiler is not packaged")
            return finish(BuildOutcome.UNSUPPORTED)
        } catch (_: LinkageError) {
            diagnostic("KOTLIN_UNAVAILABLE", "Embedded Kotlin compiler cannot be linked")
            return finish(BuildOutcome.UNSUPPORTED)
        }
        if (output.exists() || Files.isSymbolicLink(output.toPath()) || output.canonicalFile != output) {
            diagnostic("KOTLIN_OUTPUT_NOT_FRESH", "Output must be a fresh canonical path")
            return finish(BuildOutcome.FAILED)
        }
        val parent = output.parentFile
        if (!parent.isDirectory) {
            diagnostic("KOTLIN_OUTPUT_PARENT_MISSING", "Output parent must already exist")
            return finish(BuildOutcome.FAILED)
        }
        try {
            val privateOutput = Files.createTempDirectory(parent.toPath(), ".kotlin-stage-").toFile()
            stage = privateOutput
            while (!compilerLock.tryLock(25, TimeUnit.MILLISECONDS)) {
                if (token.isCancelled || Thread.currentThread().isInterrupted) return finish(BuildOutcome.CANCELLED)
                if (expired()) {
                    diagnostic("KOTLIN_TIMEOUT", "Compilation deadline expired while waiting for the compiler")
                    return finish(BuildOutcome.RESOURCE_LIMIT)
                }
            }
            try {
                if (token.isCancelled) return finish(BuildOutcome.CANCELLED)
                if (expired()) {
                    diagnostic("KOTLIN_TIMEOUT", "Compilation deadline expired before compiler invocation")
                    return finish(BuildOutcome.RESOURCE_LIMIT)
                }
                val oldLoader = Thread.currentThread().contextClassLoader
                try {
                    Thread.currentThread().contextClassLoader = loader
                    val collectorType = loader.loadClass("org.jetbrains.kotlin.cli.common.messages.MessageCollector")
                    val collector = Proxy.newProxyInstance(loader, arrayOf(collectorType)) { proxy, method, args ->
                        when (method.name) {
                            "clear" -> { diagnostics.clear(); diagnosticChars = 0; hadErrors = false; diagnosticsTruncated = false; null }
                            "hasErrors" -> hadErrors
                            "report" -> {
                                val level = args!![0].toString()
                                val severity = when (level) {
                                    "ERROR", "EXCEPTION" -> BuildDiagnosticSeverity.ERROR
                                    "WARNING", "STRONG_WARNING" -> BuildDiagnosticSeverity.WARNING
                                    else -> BuildDiagnosticSeverity.INFO
                                }
                                if (severity == BuildDiagnosticSeverity.ERROR) hadErrors = true
                                if (diagnostics.size < 256 && diagnosticChars < 65536 && level != "LOGGING") {
                                    val message = args[1].toString().take(minOf(4096, 65536 - diagnosticChars))
                                    diagnosticChars += message.length
                                    val location = args.getOrNull(2)
                                    fun locationValue(name: String) = try { location?.let {
                                        loader.loadClass("org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation")
                                            .getMethod(name).invoke(it)
                                    } } catch (_: ReflectiveOperationException) { null }
                                    diagnostics.add(BuildDiagnostic(severity, "KOTLIN_$level", message.ifBlank { level },
                                        BuildDiagnosticSource.KOTLIN, locationValue("getPath") as? String,
                                        (locationValue("getLine") as? Int)?.takeIf { it > 0 },
                                        (locationValue("getColumn") as? Int)?.takeIf { it > 0 }))
                                } else if (level != "LOGGING") diagnosticsTruncated = true
                                null
                            }
                            "toString" -> "VegaKotlinMessageCollector"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                            else -> null
                        }
                    }
                    val cancelType = loader.loadClass("org.jetbrains.kotlin.progress.CompilationCanceledStatus")
                    val cancel = Proxy.newProxyInstance(loader, arrayOf(cancelType)) { proxy, method, args ->
                        when (method.name) {
                            "checkCanceled" -> {
                                if (token.isCancelled || expired() || Thread.currentThread().isInterrupted)
                                    throw loader.loadClass("org.jetbrains.kotlin.progress.CompilationCanceledException")
                                        .getDeclaredConstructor().newInstance() as Throwable
                                null
                            }
                            "toString" -> "VegaKotlinCancellation"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                            else -> null
                        }
                    }
                    val builderType = loader.loadClass("org.jetbrains.kotlin.config.Services\$Builder")
                    val builder = builderType.getDeclaredConstructor().newInstance()
                    builderType.getMethod("register", Class::class.java, Any::class.java).invoke(builder, cancelType, cancel)
                    val services = builderType.getMethod("build").invoke(builder)
                    val compiler = compilerType.getDeclaredConstructor().newInstance()
                    compilerType.getMethod("setReadingSettingsFromEnvironmentAllowed", Boolean::class.javaPrimitiveType)
                        .invoke(compiler, false)
                    val arguments = compilerType.getMethod("createArguments").invoke(compiler)
                    // Kotlin 1.9.20 otherwise tries to locate PathUtil.class as a JAR
                    // resource; Android's DEX class loader has no .class resource.
                    // Its IntelliJ extension registry has a separate fallback
                    // to KotlinCoreEnvironment$Companion.class; point the public
                    // CLI option at the already-verified resource-only JAR.
                    val extensionResource = loader.getResource("META-INF/extensions/compiler.xml")
                        ?: error("KOTLIN_EXTENSION_CONFIG_MISSING")
                    val extensionArchive = (extensionResource.openConnection() as? JarURLConnection)
                        ?.jarFileURL?.toURI()?.let(::File)
                        ?: error("KOTLIN_EXTENSION_ARCHIVE_INVALID")
                    check(extensionArchive.isFile && extensionArchive.canonicalFile == extensionArchive.absoluteFile) {
                        "KOTLIN_EXTENSION_ARCHIVE_INVALID"
                    }
                    val args = listOf("-no-jdk", "-no-stdlib", "-no-reflect",
                        "-kotlin-home", privateOutput.absolutePath,
                        "-Xintellij-plugin-root=${extensionArchive.absolutePath}", "-jvm-target",
                        if (request.target == "8") "1.8" else request.target,
                        "-module-name", "vega_workspace", "-d", privateOutput.absolutePath,
                        "-classpath", (request.bootClasspath + request.classpath).joinToString(File.pathSeparator) { it.absolutePath }) +
                        sources.map { it.absolutePath }
                    val commonArgs = loader.loadClass("org.jetbrains.kotlin.cli.common.arguments.CommonToolArguments")
                    compilerType.getMethod("parseArguments", Array<String>::class.java, commonArgs)
                        .invoke(compiler, args.toTypedArray(), arguments)
                    val exit = compilerType.getMethod("exec", collectorType,
                        loader.loadClass("org.jetbrains.kotlin.config.Services"), commonArgs)
                        .invoke(compiler, collector, services, arguments)
                    if (token.isCancelled || Thread.currentThread().isInterrupted) return finish(BuildOutcome.CANCELLED)
                    if (expired()) {
                        diagnostic("KOTLIN_TIMEOUT", "Compilation exceeded the time budget")
                        return finish(BuildOutcome.RESOURCE_LIMIT)
                    }
                    if (exit.toString() != "OK" || hadErrors) {
                        if (!hadErrors) diagnostic("KOTLIN_COMPILE_FAILED", "Compiler returned $exit")
                        return finish(BuildOutcome.FAILED)
                    }
                } finally { Thread.currentThread().contextClassLoader = oldLoader }
            } finally { compilerLock.unlock() }
            if (token.isCancelled || Thread.currentThread().isInterrupted) return finish(BuildOutcome.CANCELLED)
            if (expired()) {
                diagnostic("KOTLIN_TIMEOUT", "Compilation exceeded the time budget")
                return finish(BuildOutcome.RESOURCE_LIMIT)
            }
            // Kotlin resolves Java source symbols first; ECJ then resolves the emitted Kotlin classes.
            if (request.javaSources.isNotEmpty()) {
                val javaResult = javaCompiler.compile(EcjCompileRequest(request.javaSources, privateOutput,
                    request.classpath + privateOutput, request.target, request.target,
                    request.maxSources, request.maxSourceBytes, request.bootClasspath), token)
                diagnostics.addAll(javaResult.diagnostics)
                if (!javaResult.success) return finish(javaResult.outcome)
            }
            if (token.isCancelled) return finish(BuildOutcome.CANCELLED)
            if (expired()) {
                diagnostic("KOTLIN_TIMEOUT", "Compilation exceeded the time budget")
                return finish(BuildOutcome.RESOURCE_LIMIT)
            }
            var bytes = 0L
            var classes = 0
            for (file in privateOutput.walkTopDown()) {
                if (token.isCancelled || Thread.currentThread().isInterrupted) return finish(BuildOutcome.CANCELLED)
                if (expired()) {
                    diagnostic("KOTLIN_TIMEOUT", "Compilation deadline expired during output validation")
                    return finish(BuildOutcome.RESOURCE_LIMIT)
                }
                check(!Files.isSymbolicLink(file.toPath())) { "KOTLIN_OUTPUT_LINK" }
                if (!file.isFile) continue
                if (file.length() > request.maxOutputBytes - bytes) {
                    diagnostic("KOTLIN_OUTPUT_LIMIT", "Compiler output exceeds the byte budget")
                    return finish(BuildOutcome.RESOURCE_LIMIT)
                }
                bytes += file.length()
                if (file.extension == "class") {
                    DataInputStream(file.inputStream()).use {
                        check(it.readInt() == 0xCAFEBABE.toInt()) { "KOTLIN_CLASS_INVALID" }
                        it.readUnsignedShort()
                        check(it.readUnsignedShort() == request.target.toInt() + 44) { "KOTLIN_CLASS_TARGET_INVALID" }
                    }
                    classes++
                }
            }
            check(classes > 0) { "KOTLIN_NO_CLASS_OUTPUT" }
            if (token.isCancelled || Thread.currentThread().isInterrupted) return finish(BuildOutcome.CANCELLED)
            if (expired()) {
                diagnostic("KOTLIN_TIMEOUT", "Compilation deadline expired before output publication")
                return finish(BuildOutcome.RESOURCE_LIMIT)
            }
            // This final check is the commit point: cancellation after it applies to the next task.
            Files.move(privateOutput.toPath(), output.toPath())
            stage = null
            return finish(BuildOutcome.SUCCESS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            return finish(BuildOutcome.CANCELLED)
        } catch (error: Exception) {
            if (token.isCancelled || Thread.currentThread().isInterrupted) return finish(BuildOutcome.CANCELLED)
            if (expired()) {
                diagnostic("KOTLIN_TIMEOUT", "Compilation exceeded the time budget")
                return finish(BuildOutcome.RESOURCE_LIMIT)
            }
            val cause = if (error is InvocationTargetException) error.targetException else error
            diagnostic("KOTLIN_PROVIDER_FAILURE", failureTrace(cause))
            return finish(BuildOutcome.FAILED)
        } catch (error: LinkageError) {
            diagnostic("KOTLIN_PROVIDER_LINKAGE", failureTrace(error))
            return finish(BuildOutcome.UNSUPPORTED)
        } finally {
            stage?.deleteRecursively()
        }
    }

    companion object {
        private const val COMPILER = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        private val compilerLock = ReentrantLock()

        // Include the actual ART call site in bounded probe diagnostics. A short
        // exception message alone cannot identify failures inside Kotlin/IntelliJ.
        private fun failureTrace(failure: Throwable): String = buildString {
            generateSequence(failure as Throwable?) { it.cause }.take(3).forEach { cause ->
                if (isNotEmpty()) append(" <- ")
                append(cause.javaClass.name).append(": ").append(cause.message.orEmpty())
                cause.stackTrace.take(36).forEach { frame -> append(" @ ").append(frame) }
            }
        }.take(3800)
    }
}
