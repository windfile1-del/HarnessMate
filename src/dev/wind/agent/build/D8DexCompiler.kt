package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.zip.ZipFile

private const val MAX_KEEP_RULES_CHARS = 8 * 1024 * 1024

data class D8CoreLibraryDesugaringRequest(
    val configurationJson: String,
    val keepRulesOutput: File,
    val maxConfigurationBytes: Long = 2L * 1024L * 1024L,
    val maxKeepRulesBytes: Long = 8L * 1024L * 1024L
)

data class D8CompileRequest(
    val classDirectories: List<File>,
    val libraryFiles: List<File>,
    val outputDirectory: File,
    val minApi: Int,
    val release: Boolean = true,
    val maxClassFiles: Int = 20_000,
    val maxClassBytes: Long = 256L * 1024L * 1024L,
    val programArchives: List<File> = emptyList(),
    val maxProgramArchiveBytes: Long = 256L * 1024L * 1024L,
    val coreLibraryDesugaring: D8CoreLibraryDesugaringRequest? = null
)

data class D8Invocation(
    val programFiles: List<File>,
    val libraryFiles: List<File>,
    val outputDirectory: File,
    val minApi: Int,
    val release: Boolean,
    val coreLibraryDesugaringConfigurationJson: String? = null,
    val maxKeepRulesChars: Int = MAX_KEEP_RULES_CHARS
)

data class D8InvocationResult(
    val success: Boolean,
    val diagnostics: String = "",
    val desugaredLibraryKeepRules: String? = null,
    val keepRulesLimitExceeded: Boolean = false
)

interface D8Provider {
    val available: Boolean

    fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult
}

/** Typed, in-process D8 invocation. It deliberately does not use D8.main or a shell. */
class ReflectionD8Provider(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: ReflectionD8Provider::class.java.classLoader
) : D8Provider {
    private val api: Api? = discoverApi(classLoader)

    /** Null means this runtime does not expose R8's optional version API. */
    val version: String? = runCatching {
        val versionClass = Class.forName(VERSION_CLASS, false, classLoader)
        versionClass.getMethod("getVersionString").invoke(null) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override val available: Boolean
        get() = api != null

    override fun compile(
        invocation: D8Invocation,
        token: CancellationToken
    ): D8InvocationResult {
        if (token.isCancelled) return D8InvocationResult(false, "cancelled")
        val methods = api ?: return D8InvocationResult(false, "D8 API is unavailable")
        return try {
            val builder = methods.builder.invoke(null)
            methods.addProgramFiles.invoke(builder, invocation.programFiles.map { it.toPath() })
            methods.addLibraryFiles.invoke(builder, invocation.libraryFiles.map { it.toPath() })
            methods.setOutput.invoke(builder, invocation.outputDirectory.toPath(), methods.dexIndexed)
            methods.setMinApi.invoke(builder, invocation.minApi)
            methods.setMode.invoke(builder, if (invocation.release) methods.releaseMode else methods.debugMode)
            val desugaringConfiguration = invocation.coreLibraryDesugaringConfigurationJson
            if (desugaringConfiguration != null) {
                val addDesugaredLibraryConfiguration = methods.addDesugaredLibraryConfiguration
                    ?: return D8InvocationResult(false, "D8 core-library desugaring API is unavailable")
                val setKeepRuleConsumer = methods.setDesugaredLibraryKeepRuleConsumer
                    ?: return D8InvocationResult(false, "D8 keep-rule consumer API is unavailable")
                val stringConsumerClass = methods.stringConsumerClass
                    ?: return D8InvocationResult(false, "D8 StringConsumer API is unavailable")
                addDesugaredLibraryConfiguration.invoke(builder, desugaringConfiguration)
                val keepRules = StringBuilder()
                var keepRulesLimitExceeded = false
                val consumer = Proxy.newProxyInstance(classLoader, arrayOf(stringConsumerClass)) { proxy, method, args ->
                    when (method.name) {
                        "accept" -> {
                            val chunk = args?.firstOrNull() as? String ?: ""
                            if (!keepRulesLimitExceeded) {
                                if (chunk.length > invocation.maxKeepRulesChars - keepRules.length) {
                                    keepRulesLimitExceeded = true
                                } else {
                                    keepRules.append(chunk)
                                }
                            }
                            null
                        }
                        "finished" -> null
                        "toString" -> "D8DesugaredLibraryKeepRuleConsumer"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> null
                    }
                }
                setKeepRuleConsumer.invoke(builder, consumer)
                val command = methods.build.invoke(builder)
                methods.run.invoke(null, command)
                return D8InvocationResult(
                    success = true,
                    desugaredLibraryKeepRules = keepRules.toString(),
                    keepRulesLimitExceeded = keepRulesLimitExceeded
                )
            }
            val command = methods.build.invoke(builder)
            methods.run.invoke(null, command)
            D8InvocationResult(true)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            D8InvocationResult(false, formatFailure(cause))
        } catch (error: ReflectiveOperationException) {
            D8InvocationResult(false, formatFailure(error))
        } catch (error: RuntimeException) {
            D8InvocationResult(false, formatFailure(error))
        } catch (error: LinkageError) {
            D8InvocationResult(false, formatFailure(error))
        }
    }

    private data class Api(
        val builder: Method,
        val addProgramFiles: Method,
        val addLibraryFiles: Method,
        val setOutput: Method,
        val setMinApi: Method,
        val setMode: Method,
        val addDesugaredLibraryConfiguration: Method?,
        val setDesugaredLibraryKeepRuleConsumer: Method?,
        val stringConsumerClass: Class<*>?,
        val build: Method,
        val run: Method,
        val dexIndexed: Any,
        val releaseMode: Any,
        val debugMode: Any
    )

    private fun discoverApi(loader: ClassLoader): Api? = runCatching {
        val commandClass = Class.forName(D8_COMMAND_CLASS, false, loader)
        val builderClass = Class.forName(D8_BUILDER_CLASS, false, loader)
        val outputModeClass = Class.forName(OUTPUT_MODE_CLASS, false, loader)
        val compilationModeClass = Class.forName(COMPILATION_MODE_CLASS, false, loader)
        val d8Class = Class.forName(D8_CLASS, false, loader)
        val desugaredLibraryConfigurationMethod = runCatching {
            builderClass.getMethod("addDesugaredLibraryConfiguration", String::class.java)
        }.getOrNull()
        val stringConsumerClass = runCatching {
            Class.forName(STRING_CONSUMER_CLASS, false, loader)
        }.getOrNull()
        val keepRuleConsumerMethod = stringConsumerClass?.let { consumerClass ->
            runCatching { builderClass.getMethod("setDesugaredLibraryKeepRuleConsumer", consumerClass) }.getOrNull()
        }
        val outputMode = outputModeClass.enumConstants.orEmpty().associateBy { (it as Enum<*>).name }
        val compilationModes = compilationModeClass.enumConstants.orEmpty()
            .associateBy { (it as Enum<*>).name }
        Api(
            builder = commandClass.getMethod("builder"),
            addProgramFiles = builderClass.getMethod("addProgramFiles", java.util.Collection::class.java),
            addLibraryFiles = builderClass.getMethod("addLibraryFiles", java.util.Collection::class.java),
            setOutput = builderClass.getMethod("setOutput", Path::class.java, outputModeClass),
            setMinApi = builderClass.getMethod("setMinApiLevel", Int::class.javaPrimitiveType),
            setMode = builderClass.getMethod("setMode", compilationModeClass),
            addDesugaredLibraryConfiguration = desugaredLibraryConfigurationMethod,
            setDesugaredLibraryKeepRuleConsumer = keepRuleConsumerMethod,
            stringConsumerClass = stringConsumerClass,
            build = builderClass.getMethod("build"),
            run = d8Class.getMethod("run", commandClass),
            dexIndexed = outputMode["DexIndexed"] ?: error("DexIndexed output mode missing"),
            releaseMode = compilationModes["RELEASE"] ?: error("RELEASE mode missing"),
            debugMode = compilationModes["DEBUG"] ?: error("DEBUG mode missing")
        )
    }.getOrNull()

    private fun formatFailure(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: "D8 invocation failed"}"

    companion object {
        private const val D8_CLASS = "com.android.tools.r8.D8"
        private const val D8_COMMAND_CLASS = "com.android.tools.r8.D8Command"
        private const val D8_BUILDER_CLASS = "com.android.tools.r8.D8Command\$Builder"
        private const val OUTPUT_MODE_CLASS = "com.android.tools.r8.OutputMode"
        private const val COMPILATION_MODE_CLASS = "com.android.tools.r8.CompilationMode"
        private const val VERSION_CLASS = "com.android.tools.r8.Version"
        private const val STRING_CONSUMER_CLASS = "com.android.tools.r8.StringConsumer"
    }
}

class D8CompileResult(
    val outcome: BuildOutcome,
    diagnostics: List<BuildDiagnostic> = emptyList(),
    artifacts: List<BuildArtifact> = emptyList(),
    val durationMs: Long = 0L
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val artifacts: List<BuildArtifact> = Collections.unmodifiableList(artifacts.toList())
    val success: Boolean get() = outcome == BuildOutcome.SUCCESS

    init {
        require(durationMs >= 0L) { "D8_DURATION_INVALID" }
        require(outcome == BuildOutcome.SUCCESS || this.artifacts.isEmpty()) {
            "D8_FAILED_RESULT_HAS_ARTIFACTS"
        }
    }
}

/**
 * Standalone class-to-dex primitive. It never packages an APK or invokes L8,
 * and only clears the exact caller-supplied dex directory and optional
 * keep-rules file.
 */
class D8DexCompiler(
    private val provider: D8Provider = ReflectionD8Provider()
) {
    fun compile(request: D8CompileRequest, token: CancellationToken): D8CompileResult {
        val started = System.nanoTime()
        fun result(
            outcome: BuildOutcome,
            code: String,
            message: String,
            path: String? = null,
            artifacts: List<BuildArtifact> = emptyList()
        ): D8CompileResult {
            val diagnostic = BuildDiagnostic(
                severity = BuildDiagnosticSeverity.ERROR,
                code = code,
                message = message,
                source = BuildDiagnosticSource.DEX,
                path = path
            )
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            return D8CompileResult(outcome, listOf(diagnostic), artifacts, elapsed)
        }
        fun cleanupFailure(message: String, output: Path, keepRulesOutput: Path?): D8CompileResult = result(
            BuildOutcome.FAILED,
            if (keepRulesOutput == null) "D8_OUTPUT_INVALID" else "D8_DESUGAR_OUTPUT_INVALID",
            message,
            keepRulesOutput?.toString() ?: output.toString()
        )

        if (token.isCancelled) {
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "D8 compilation was cancelled")
        }
        request.coreLibraryDesugaring?.let { desugaring ->
            try {
                validateDesugaringRequest(desugaring)
            } catch (failure: D8Failure) {
                return result(failure.outcome, failure.code, failure.message, failure.path)
            } catch (_: Exception) {
                return result(BuildOutcome.FAILED, "D8_DESUGAR_CONFIG_INVALID", "D8 desugaring configuration is invalid")
            }
        }
        val isAvailable = try {
            provider.available
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!isAvailable) {
            return result(
                BuildOutcome.UNSUPPORTED,
                "D8_UNAVAILABLE",
                "D8 provider API is unavailable in this runtime"
            )
        }

        val prepared = try {
            prepareInputs(request, token)
        } catch (failure: D8Failure) {
            return result(failure.outcome, failure.code, failure.message, failure.path)
        } catch (_: Exception) {
            return result(BuildOutcome.FAILED, "D8_INPUT_INVALID", "D8 inputs could not be validated")
        }
        if (prepared.cancelled || token.isCancelled) {
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "D8 compilation was cancelled")
        }

        val keepRulesPath = request.coreLibraryDesugaring?.let { desugaring ->
            try {
                validateKeepRulesOutputPath(
                    desugaring.keepRulesOutput,
                    request.outputDirectory,
                    prepared.classRoots + prepared.programArchives,
                    prepared.libraryFiles
                )
            } catch (failure: D8Failure) {
                return result(failure.outcome, failure.code, failure.message, failure.path)
            } catch (_: Exception) {
                return result(
                    BuildOutcome.FAILED,
                    "D8_DESUGAR_OUTPUT_INVALID",
                    "D8 keep-rules output path is unsafe",
                    desugaring.keepRulesOutput.path
                )
            }
        }

        val output = try {
            prepareOutput(request.outputDirectory, prepared.classRoots + prepared.programArchives, prepared.libraryFiles)
                .also(::clearDirectoryContents)
        } catch (_: Exception) {
            return result(
                BuildOutcome.FAILED,
                "D8_OUTPUT_INVALID",
                "D8 output directory is unsafe or could not be prepared",
                request.outputDirectory.path
            )
        }
        if (keepRulesPath != null) {
            try {
                prepareKeepRulesOutput(keepRulesPath)
            } catch (_: Exception) {
                cleanupOutput(output)
                return result(
                    BuildOutcome.FAILED,
                    "D8_DESUGAR_OUTPUT_INVALID",
                    "D8 keep-rules output could not be prepared safely",
                    keepRulesPath.toString()
                )
            }
        }
        if (token.isCancelled) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Cancelled D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "D8 compilation was cancelled")
        }

        val invocation = D8Invocation(
            programFiles = prepared.programFiles.map(Path::toFile),
            libraryFiles = prepared.libraryFiles.map(Path::toFile),
            outputDirectory = output.toFile(),
            minApi = request.minApi,
            release = request.release,
            coreLibraryDesugaringConfigurationJson = request.coreLibraryDesugaring?.configurationJson,
            maxKeepRulesChars = request.coreLibraryDesugaring?.maxKeepRulesBytes
                ?.coerceAtMost(MAX_KEEP_RULES_CHARS.toLong())?.toInt()
                ?: MAX_KEEP_RULES_CHARS
        )
        val invocationResult = try {
            provider.compile(invocation, token)
        } catch (error: Exception) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Failed D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(
                BuildOutcome.FAILED,
                "D8_COMPILE_FAILED",
                boundedMessage(error.javaClass.simpleName + ": " + (error.message ?: "provider failed"))
            )
        } catch (error: LinkageError) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Failed D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(
                BuildOutcome.FAILED,
                "D8_COMPILE_FAILED",
                boundedMessage(error.javaClass.simpleName + ": " + (error.message ?: "provider linkage failed"))
            )
        }
        if (token.isCancelled) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Cancelled D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "D8 compilation was cancelled")
        }
        if (invocationResult.keepRulesLimitExceeded) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Over-limit D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(
                BuildOutcome.RESOURCE_LIMIT,
                "D8_DESUGAR_KEEP_RULES_LIMIT",
                "D8 desugared-library keep rules exceed the configured character limit",
                keepRulesPath?.toString()
            )
        }
        if (!invocationResult.success) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Failed D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(
                BuildOutcome.FAILED,
                "D8_COMPILE_FAILED",
                boundedMessage(invocationResult.diagnostics.ifBlank { "D8 invocation failed" })
            )
        }

        val artifacts = try {
            validateDexOutputs(output)
        } catch (failure: D8Failure) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Invalid D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(failure.outcome, failure.code, failure.message, failure.path)
        } catch (_: Exception) {
            if (!cleanupOutputs(output, keepRulesPath)) {
                return cleanupFailure("Invalid D8 outputs could not be cleaned", output, keepRulesPath)
            }
            return result(
                BuildOutcome.FAILED,
                "D8_OUTPUT_INVALID",
                "D8 produced invalid or unsafe output",
                output.toString()
            )
        }
        val finalArtifacts = artifacts.toMutableList()
        val desugaring = request.coreLibraryDesugaring
        val keepRules = invocationResult.desugaredLibraryKeepRules.orEmpty()
        if (desugaring != null && keepRules.isNotEmpty()) {
            if (keepRules.length > MAX_KEEP_RULES_CHARS) {
                if (!cleanupOutputs(output, keepRulesPath)) {
                    return cleanupFailure("Over-limit D8 outputs could not be cleaned", output, keepRulesPath)
                }
                return result(
                    BuildOutcome.RESOURCE_LIMIT,
                    "D8_DESUGAR_KEEP_RULES_LIMIT",
                    "D8 desugared-library keep rules exceed the configured character limit",
                    keepRulesPath?.toString()
                )
            }
            val keepRulesBytes = utf8ByteCountUpTo(keepRules, desugaring.maxKeepRulesBytes)
            if (keepRulesBytes > desugaring.maxKeepRulesBytes) {
                if (!cleanupOutputs(output, keepRulesPath)) {
                    return cleanupFailure("Over-limit D8 outputs could not be cleaned", output, keepRulesPath)
                }
                return result(
                    BuildOutcome.RESOURCE_LIMIT,
                    "D8_DESUGAR_KEEP_RULES_LIMIT",
                    "D8 desugared-library keep rules exceed the configured byte limit",
                    keepRulesPath?.toString()
                )
            }
            try {
                val target = keepRulesPath ?: error("Validated keep-rules output path is missing")
                writeKeepRulesAtomically(target, keepRules.toByteArray(StandardCharsets.UTF_8))
                finalArtifacts.add(BuildArtifact("android-desugar-keep-rules", target.toFile(), sha256(target)))
            } catch (_: Exception) {
                if (!cleanupOutputs(output, keepRulesPath)) {
                    return cleanupFailure("Unwritable D8 outputs could not be cleaned", output, keepRulesPath)
                }
                return result(
                    BuildOutcome.FAILED,
                    "D8_DESUGAR_KEEP_RULES_WRITE_FAILED",
                    "D8 desugared-library keep rules could not be written atomically",
                    keepRulesPath?.toString()
                )
            }
        }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        return D8CompileResult(BuildOutcome.SUCCESS, artifacts = finalArtifacts, durationMs = elapsed)
    }

    private fun validateDesugaringRequest(request: D8CoreLibraryDesugaringRequest) {
        if (request.configurationJson.isBlank() || request.maxConfigurationBytes <= 0L || request.maxKeepRulesBytes <= 0L) {
            fail("D8_DESUGAR_CONFIG_INVALID", "D8 desugaring configuration and limits must be valid")
        }
        val byteCount = utf8ByteCountUpTo(request.configurationJson, request.maxConfigurationBytes)
        if (byteCount <= 0L) fail("D8_DESUGAR_CONFIG_INVALID", "D8 desugaring configuration is empty")
        if (byteCount > request.maxConfigurationBytes) {
            fail(
                "D8_DESUGAR_CONFIG_LIMIT",
                "D8 desugaring configuration exceeds the configured byte limit",
                outcome = BuildOutcome.RESOURCE_LIMIT
            )
        }
    }

    private fun validateKeepRulesOutputPath(
        keepRulesFile: File,
        dexOutputFile: File,
        roots: List<Path>,
        libraries: List<Path>
    ): Path {
        if (keepRulesFile.path.isBlank()) {
            fail("D8_DESUGAR_OUTPUT_INVALID", "D8 keep-rules output path is missing")
        }
        val path = keepRulesFile.toPath().toAbsolutePath().normalize()
        if (path.root == path) fail("D8_DESUGAR_OUTPUT_INVALID", "Filesystem root cannot be a keep-rules output", path.toString())
        rejectSymlinkComponents(path, "D8_DESUGAR_OUTPUT_INVALID")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules target must be an ordinary file", path.toString())
        }
        val existingAncestor = generateSequence(path.parent) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules output has no existing parent", path.toString())
        if (Files.isSymbolicLink(existingAncestor) || !Files.isDirectory(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules output parent is not an ordinary directory", existingAncestor.toString())
        }
        val canonicalPath = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            path.toRealPath()
        } else {
            existingAncestor.toRealPath().resolve(existingAncestor.relativize(path)).normalize()
        }
        val dexOutput = dexOutputFile.toPath().toAbsolutePath().normalize()
        rejectSymlinkComponents(dexOutput, "D8_DESUGAR_OUTPUT_INVALID")
        val canonicalDexOutput = if (Files.exists(dexOutput, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(dexOutput, LinkOption.NOFOLLOW_LINKS)) {
                fail("D8_DESUGAR_OUTPUT_INVALID", "App dex output path is not an ordinary directory", dexOutput.toString())
            }
            dexOutput.toRealPath()
        } else {
            val dexAncestor = generateSequence(dexOutput) { it.parent }
                .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                ?: fail("D8_DESUGAR_OUTPUT_INVALID", "App dex output has no existing parent", dexOutput.toString())
            dexAncestor.toRealPath().resolve(dexAncestor.relativize(dexOutput)).normalize()
        }
        if (pathsOverlap(canonicalPath, canonicalDexOutput) ||
            (roots + libraries).any { pathsOverlap(canonicalPath, it) }
        ) {
            fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules output overlaps D8 inputs or app dex output", path.toString())
        }
        return path
    }

    private fun prepareKeepRulesOutput(path: Path) {
        rejectSymlinkComponents(path, "D8_DESUGAR_OUTPUT_INVALID")
        val parent = path.parent ?: fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules output has no parent", path.toString())
        Files.createDirectories(parent)
        rejectSymlinkComponents(path, "D8_DESUGAR_OUTPUT_INVALID")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules output parent is unsafe", parent.toString())
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            fail("D8_DESUGAR_OUTPUT_INVALID", "Keep-rules target must be an ordinary file", path.toString())
        }
        Files.deleteIfExists(path)
    }

    private fun writeKeepRulesAtomically(path: Path, bytes: ByteArray) {
        val parent = path.parent ?: throw IOException("Keep-rules output has no parent")
        rejectSymlinkComponents(parent, "D8_DESUGAR_OUTPUT_INVALID")
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw IOException("Keep-rules output parent is unsafe")
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw IOException("Keep-rules output target became a symbolic link")
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Keep-rules output target is not an ordinary file")
        }
        val temporary = Files.createTempFile(parent, ".d8-keep-rules-", ".tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Keep-rules output target became unsafe after the atomic write")
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun cleanupOutputs(output: Path, keepRulesOutput: Path?): Boolean {
        val dexClean = cleanupOutput(output)
        val keepRulesClean = keepRulesOutput?.let { runCatching { Files.deleteIfExists(it) }.isSuccess } ?: true
        return dexClean && keepRulesClean
    }

    private fun utf8ByteCountUpTo(value: String, limit: Long): Long {
        var count = 0L
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val added = when {
                current.code <= 0x7f -> 1L
                current.code <= 0x7ff -> 2L
                Character.isHighSurrogate(current) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4L
                }
                Character.isSurrogate(current) -> 1L
                else -> 3L
            }
            count += added
            if (count > limit) return count
            index++
        }
        return count
    }

    private fun prepareInputs(request: D8CompileRequest, token: CancellationToken): PreparedInputs {
        if (request.minApi <= 0) fail("D8_INPUT_INVALID", "minApi must be greater than zero")
        if (request.maxClassFiles <= 0 || request.maxClassBytes <= 0L || request.maxProgramArchiveBytes <= 0L) {
            fail("D8_INPUT_LIMIT", "D8 program input limits must be positive", outcome = BuildOutcome.RESOURCE_LIMIT)
        }
        if (request.classDirectories.isEmpty()) fail("D8_PROGRAM_INPUT_EMPTY", "No class directories were supplied")
        if (request.outputDirectory.path.isBlank()) fail("D8_INPUT_INVALID", "D8 output directory is missing")

        val roots = request.classDirectories.map { directory ->
            val path = safeExistingPath(directory, expectedDirectory = true, "D8_INPUT_INVALID")
            path
        }.distinct().sortedBy(Path::toString)
        val classes = LinkedHashMap<String, Path>()
        roots.forEach { root ->
            if (token.isCancelled) return PreparedInputs(roots, emptyList(), emptyList(), emptyList(), cancelled = true)
            try {
                Files.walkFileTree(
                    root,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (attrs.isSymbolicLink || Files.isSymbolicLink(dir) || !attrs.isDirectory) {
                                fail("D8_INPUT_INVALID", "Class directory tree contains an unsafe directory", dir.toString())
                            }
                            ensureContained(root, dir)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            if (attrs.isSymbolicLink || Files.isSymbolicLink(file) || !attrs.isRegularFile) {
                                fail("D8_INPUT_INVALID", "Class directory tree contains an unsafe file", file.toString())
                            }
                            ensureContained(root, file)
                            if (file.fileName.toString().endsWith(".class")) {
                                val canonical = file.toRealPath()
                                val key = canonical.toString()
                                if (!classes.containsKey(key)) classes[key] = canonical
                                if (classes.size > request.maxClassFiles) {
                                    fail("D8_INPUT_LIMIT", "D8 class file count exceeds the configured limit", file.toString(), BuildOutcome.RESOURCE_LIMIT)
                                }
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                            fail("D8_INPUT_INVALID", "Could not safely inspect class directory", file.toString())
                        }
                    }
                )
            } catch (failure: D8Failure) {
                throw failure
            } catch (_: Exception) {
                fail("D8_INPUT_INVALID", "Could not safely inspect class directory", root.toString())
            }
        }

        if (classes.isEmpty()) fail("D8_PROGRAM_INPUT_EMPTY", "No .class files were found")
        val orderedClasses = classes.values.sortedBy(Path::toString)
        var totalBytes = 0L
        orderedClasses.forEach { classFile ->
            if (token.isCancelled) return PreparedInputs(roots, emptyList(), emptyList(), emptyList(), cancelled = true)
            val size = Files.size(classFile)
            if (size <= CLASS_HEADER_SIZE) {
                fail("D8_INPUT_CLASS_INVALID", "Class file is too small to contain a class header", classFile.toString())
            }
            if (size > request.maxClassBytes - totalBytes) {
                fail("D8_INPUT_LIMIT", "D8 class bytes exceed the configured limit", classFile.toString(), BuildOutcome.RESOURCE_LIMIT)
            }
            totalBytes += size
            val header = ByteArray(CLASS_HEADER_SIZE.toInt())
            Files.newInputStream(classFile, LinkOption.NOFOLLOW_LINKS).use { input ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read != header.size || header[0] != 0xCA.toByte() || header[1] != 0xFE.toByte() ||
                    header[2] != 0xBA.toByte() || header[3] != 0xBE.toByte()
                ) {
                    fail("D8_INPUT_CLASS_INVALID", "Class file has an invalid CAFEBABE header", classFile.toString())
                }
            }
        }

        val classNames = mutableSetOf<String>()
        roots.forEach { root ->
            orderedClasses.filter { it.startsWith(root) }.forEach { classFile ->
                val name = root.relativize(classFile).toString().replace(File.separatorChar, '/')
                if (!classNames.add(name)) {
                    fail("D8_PROGRAM_CLASS_DUPLICATE", "Program class occurs in multiple class roots", name)
                }
            }
        }
        val archives = request.programArchives.map { file ->
            safeExistingPath(file, expectedDirectory = false, "D8_PROGRAM_ARCHIVE_INVALID")
        }.distinct().sortedBy(Path::toString)
        var archiveBytes = 0L
        archives.forEach { archive ->
            if (token.isCancelled) return PreparedInputs(roots, emptyList(), emptyList(), emptyList(), cancelled = true)
            val size = Files.size(archive)
            if (size <= 0L || size > request.maxProgramArchiveBytes - archiveBytes) {
                fail("D8_PROGRAM_ARCHIVE_LIMIT", "Program archive bytes exceed the configured limit",
                    archive.toString(), BuildOutcome.RESOURCE_LIMIT)
            }
            archiveBytes += size
            try {
                ZipFile(archive.toFile()).use { zip ->
                    val entryNames = mutableSetOf<String>()
                    val entries = zip.entries()
                    var entryCount = 0
                    while (entries.hasMoreElements()) {
                        if (token.isCancelled) return PreparedInputs(roots, emptyList(), emptyList(), emptyList(), cancelled = true)
                        val entry = entries.nextElement()
                        entryCount++
                        if (entryCount > request.maxClassFiles * 2L) {
                            fail("D8_PROGRAM_ARCHIVE_LIMIT", "Program archive entry count exceeds the configured limit",
                                archive.toString(), BuildOutcome.RESOURCE_LIMIT)
                        }
                        val name = entry.name
                        val segments = if (entry.isDirectory && name.endsWith('/'))
                            name.dropLast(1).split('/') else name.split('/')
                        if (name.isBlank() || name.startsWith('/') || name.contains('\\') ||
                            segments.any { it.isEmpty() || it == "." || it == ".." } ||
                            !entryNames.add(name)
                        ) {
                            fail("D8_PROGRAM_ARCHIVE_INVALID", "Program archive contains an unsafe or duplicate entry",
                                archive.toString())
                        }
                        if (!entry.isDirectory && name.endsWith(".class")) {
                            if (!classNames.add(name)) {
                                fail("D8_PROGRAM_CLASS_DUPLICATE", "Program class occurs more than once", name)
                            }
                            if (classNames.size > request.maxClassFiles || entry.size <= CLASS_HEADER_SIZE ||
                                entry.size > request.maxClassBytes - totalBytes) {
                                fail("D8_INPUT_LIMIT", "Program class count or bytes exceed the configured limit",
                                    archive.toString(), BuildOutcome.RESOURCE_LIMIT)
                            }
                            totalBytes += entry.size
                            zip.getInputStream(entry).use { input ->
                                val header = ByteArray(CLASS_HEADER_SIZE.toInt())
                                var read = 0
                                while (read < header.size) {
                                    val count = input.read(header, read, header.size - read)
                                    if (count < 0) break
                                    read += count
                                }
                                if (read != header.size || header[0] != 0xCA.toByte() ||
                                    header[1] != 0xFE.toByte() || header[2] != 0xBA.toByte() ||
                                    header[3] != 0xBE.toByte()) {
                                    fail("D8_PROGRAM_ARCHIVE_INVALID", "Program archive contains an invalid class",
                                        archive.toString())
                                }
                            }
                        }
                    }
                }
            } catch (failure: D8Failure) {
                throw failure
            } catch (_: Exception) {
                fail("D8_PROGRAM_ARCHIVE_INVALID", "Program archive could not be inspected safely", archive.toString())
            }
        }
        val libraries = request.libraryFiles.map { file ->
            val path = safeExistingPath(file, expectedDirectory = false, "D8_LIBRARY_INPUT_INVALID")
            if (Files.size(path) <= 0L) fail("D8_LIBRARY_INPUT_INVALID", "D8 library file is empty", path.toString())
            path
        }.distinct().sortedBy(Path::toString)
        if (archives.any { it in libraries || roots.any { root -> pathsOverlap(root, it) } }) {
            fail("D8_PROGRAM_ARCHIVE_INVALID", "Program archive overlaps a class root or library input")
        }
        return PreparedInputs(roots, orderedClasses + archives, archives, libraries)
    }

    private fun prepareOutput(outputFile: File, roots: List<Path>, libraries: List<Path>): Path {
        val output = outputFile.toPath().toAbsolutePath().normalize()
        if (output.root == output) fail("D8_OUTPUT_INVALID", "Filesystem root cannot be a D8 output directory", output.toString())
        rejectSymlinkComponents(output, "D8_OUTPUT_INVALID")
        val existingAncestor = generateSequence(output) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: fail("D8_OUTPUT_INVALID", "D8 output has no existing filesystem ancestor", output.toString())
        if (Files.isSymbolicLink(existingAncestor) || !Files.isDirectory(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            fail("D8_OUTPUT_INVALID", "D8 output path is not an ordinary directory", existingAncestor.toString())
        }
        val initialOutputPath = if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) output.toRealPath() else output
        val protected = roots + libraries
        if (protected.any { pathsOverlap(initialOutputPath, it) }) {
            fail("D8_OUTPUT_INVALID", "D8 output directory overlaps program or library inputs", output.toString())
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))
        ) {
            fail("D8_OUTPUT_INVALID", "D8 output path is not an ordinary directory", output.toString())
        }
        Files.createDirectories(output)
        rejectSymlinkComponents(output, "D8_OUTPUT_INVALID")
        val canonicalOutput = output.toRealPath()
        if (protected.any { pathsOverlap(canonicalOutput, it) }) {
            fail("D8_OUTPUT_INVALID", "D8 output directory overlaps program or library inputs", output.toString())
        }
        return canonicalOutput
    }

    private fun validateDexOutputs(output: Path): List<BuildArtifact> {
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
            fail("D8_OUTPUT_INVALID", "D8 output directory is missing or unsafe", output.toString())
        }
        val files = mutableListOf<Path>()
        Files.newDirectoryStream(output).use { children ->
            for (child in children) {
                if (Files.isSymbolicLink(child) || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    fail("D8_OUTPUT_INVALID", "D8 produced a non-regular output", child.toString())
                }
                files.add(child)
            }
        }
        if (files.isEmpty()) fail("D8_OUTPUT_MISSING", "D8 reported success but produced no dex files", output.toString())
        val indexed = files.map { file ->
            val name = file.fileName.toString()
            val index = when {
                name == "classes.dex" -> BigInteger.ONE
                name.matches(Regex("classes([2-9]|[1-9][0-9]+)\\.dex")) -> {
                    BigInteger(name.removePrefix("classes").removeSuffix(".dex"))
                }
                else -> fail("D8_OUTPUT_INVALID", "D8 output filename is not a valid indexed dex name", file.toString())
            }
            file to index
        }.sortedBy { it.second }
        if (indexed.first().second != BigInteger.ONE || indexed.map { it.second }.distinct().size != indexed.size ||
            indexed.withIndex().any { (position, entry) -> entry.second != BigInteger.valueOf(position.toLong() + 1L) }
        ) {
            fail("D8_OUTPUT_INVALID", "D8 dex output indexes are duplicated or not contiguous", output.toString())
        }
        return indexed.map { (file, _) ->
            val size = Files.size(file)
            if (size <= DEX_HEADER_SIZE) fail("D8_OUTPUT_INVALID", "D8 output is too small to contain a dex header", file.toString())
            val header = ByteArray(DEX_HEADER_SIZE.toInt())
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
                if (read != header.size || header[0] != 'd'.code.toByte() || header[1] != 'e'.code.toByte() ||
                    header[2] != 'x'.code.toByte() || header[3] != '\n'.code.toByte() ||
                    !header.sliceArray(4..6).all { it in '0'.code.toByte()..'9'.code.toByte() } || header[7] != 0.toByte()
                ) {
                    fail("D8_OUTPUT_INVALID", "D8 output has an invalid dex magic header", file.toString())
                }
            }
            BuildArtifact("android-dex", file.toFile(), sha256(file))
        }
    }

    private fun safeExistingPath(file: File, expectedDirectory: Boolean, code: String): Path {
        if (file.path.isBlank()) fail(code, "D8 input path is missing")
        val path = file.toPath().toAbsolutePath().normalize()
        rejectSymlinkComponents(path, code)
        if (expectedDirectory) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                fail(code, "D8 class directory is missing or unsafe", path.toString())
            }
        } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            fail(code, "D8 library must be an ordinary file", path.toString())
        }
        return try {
            path.toRealPath()
        } catch (_: IOException) {
            fail(code, "D8 input path could not be resolved safely", path.toString())
        }
    }

    private fun rejectSymlinkComponents(path: Path, code: String) {
        val root = path.root ?: fail(code, "D8 path has no filesystem root", path.toString())
        var current = root
        for (component in path) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                fail(code, "D8 path contains a symbolic link", current.toString())
            }
        }
    }

    private fun ensureContained(root: Path, child: Path) {
        val canonicalChild = child.toRealPath()
        if (!canonicalChild.startsWith(root)) {
            fail("D8_INPUT_INVALID", "Class file escaped its canonical input directory", child.toString())
        }
    }

    private fun clearDirectoryContents(directory: Path) {
        Files.newDirectoryStream(directory).use { children ->
            for (child in children) deleteTreeWithoutFollowingLinks(child)
        }
    }

    private fun cleanupOutput(directory: Path): Boolean =
        runCatching { clearDirectoryContents(directory) }.isSuccess

    private fun deleteTreeWithoutFollowingLinks(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun pathsOverlap(left: Path, right: Path): Boolean = left.startsWith(right) || right.startsWith(left)

    private fun boundedMessage(message: String): String =
        message.take(MAX_DIAGNOSTIC_CHARS).ifBlank { "D8 invocation failed" }

    private data class PreparedInputs(
        val classRoots: List<Path>,
        val programFiles: List<Path>,
        val programArchives: List<Path>,
        val libraryFiles: List<Path>,
        val cancelled: Boolean = false
    )

    private class D8Failure(
        val code: String,
        override val message: String,
        val path: String? = null,
        val outcome: BuildOutcome = BuildOutcome.FAILED
    ) : RuntimeException(message)

    private fun fail(
        code: String,
        message: String,
        path: String? = null,
        outcome: BuildOutcome = BuildOutcome.FAILED
    ): Nothing = throw D8Failure(code, message, path, outcome)

    private companion object {
        const val CLASS_HEADER_SIZE = 8L
        const val DEX_HEADER_SIZE = 8L
        const val MAX_DIAGNOSTIC_CHARS = 64 * 1024
    }
}
