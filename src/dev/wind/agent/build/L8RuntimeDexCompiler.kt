package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections

data class L8CompileRequest(
    val desugaredLibraryConfigurationJson: String,
    val keepRulesFile: File,
    val programFiles: List<File>,
    val libraryFiles: List<File>,
    val outputDirectory: File,
    val minApi: Int,
    val release: Boolean = true,
    val maxConfigurationBytes: Long = 2L * 1024L * 1024L,
    val maxKeepRulesBytes: Long = 8L * 1024L * 1024L,
    val maxProgramFiles: Int = 16,
    val maxProgramBytes: Long = 128L * 1024L * 1024L
)

data class L8Invocation(
    val programFiles: List<File>,
    val libraryFiles: List<File>,
    val desugaredLibraryConfigurationJson: String,
    val keepRulesFile: File,
    val outputDirectory: File,
    val minApi: Int,
    val release: Boolean
)

data class L8InvocationResult(
    val success: Boolean,
    val diagnostics: String = ""
)

interface L8Provider {
    val available: Boolean

    fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult
}

/** In-process typed reflection over R8's L8Command API; never invokes L8.main or a process. */
class ReflectionL8Provider(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: ReflectionL8Provider::class.java.classLoader
) : L8Provider {
    private val api: Api? = discoverApi(classLoader)

    /** Optional metadata only; its absence does not make the L8 command API unavailable. */
    val version: String? = runCatching {
        val versionClass = Class.forName(VERSION_CLASS, false, classLoader)
        versionClass.getMethod("getVersionString").invoke(null) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override val available: Boolean
        get() = api != null

    override fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult {
        if (token.isCancelled) return L8InvocationResult(false, "cancelled")
        val methods = api ?: return L8InvocationResult(false, "L8 API is unavailable")
        return try {
            val builder = methods.builder.invoke(null)
            methods.addProgramFiles.invoke(builder, invocation.programFiles.map { it.toPath() })
            methods.addLibraryFiles.invoke(builder, invocation.libraryFiles.map { it.toPath() })
            methods.addDesugaredLibraryConfiguration.invoke(builder, invocation.desugaredLibraryConfigurationJson)
            methods.addProguardConfigurationFiles.invoke(builder, listOf(invocation.keepRulesFile.toPath()))
            methods.setOutput.invoke(builder, invocation.outputDirectory.toPath(), methods.dexIndexed)
            methods.setMinApi.invoke(builder, invocation.minApi)
            methods.setMode.invoke(builder, if (invocation.release) methods.releaseMode else methods.debugMode)
            val command = methods.build.invoke(builder)
            if (token.isCancelled) return L8InvocationResult(false, "cancelled")
            methods.run.invoke(null, command)
            L8InvocationResult(true)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            L8InvocationResult(false, formatFailure(cause))
        } catch (error: ReflectiveOperationException) {
            L8InvocationResult(false, formatFailure(error))
        } catch (error: RuntimeException) {
            L8InvocationResult(false, formatFailure(error))
        } catch (error: LinkageError) {
            L8InvocationResult(false, formatFailure(error))
        }
    }

    private data class Api(
        val builder: Method,
        val addProgramFiles: Method,
        val addLibraryFiles: Method,
        val addDesugaredLibraryConfiguration: Method,
        val addProguardConfigurationFiles: Method,
        val setOutput: Method,
        val setMinApi: Method,
        val setMode: Method,
        val build: Method,
        val run: Method,
        val dexIndexed: Any,
        val releaseMode: Any,
        val debugMode: Any
    )

    private fun discoverApi(loader: ClassLoader): Api? = runCatching {
        val commandClass = Class.forName(L8_COMMAND_CLASS, false, loader)
        val builderClass = Class.forName(L8_BUILDER_CLASS, false, loader)
        val outputModeClass = Class.forName(OUTPUT_MODE_CLASS, false, loader)
        val compilationModeClass = Class.forName(COMPILATION_MODE_CLASS, false, loader)
        val l8Class = Class.forName(L8_CLASS, false, loader)
        val outputModes = outputModeClass.enumConstants.orEmpty().associateBy { (it as Enum<*>).name }
        val compilationModes = compilationModeClass.enumConstants.orEmpty()
            .associateBy { (it as Enum<*>).name }
        Api(
            builder = commandClass.getMethod("builder"),
            addProgramFiles = builderClass.getMethod("addProgramFiles", java.util.Collection::class.java),
            addLibraryFiles = builderClass.getMethod("addLibraryFiles", java.util.Collection::class.java),
            addDesugaredLibraryConfiguration = builderClass.getMethod(
                "addDesugaredLibraryConfiguration",
                String::class.java
            ),
            addProguardConfigurationFiles = builderClass.getMethod(
                "addProguardConfigurationFiles",
                java.util.List::class.java
            ),
            setOutput = builderClass.getMethod("setOutput", Path::class.java, outputModeClass),
            setMinApi = builderClass.getMethod("setMinApiLevel", Int::class.javaPrimitiveType),
            setMode = builderClass.getMethod("setMode", compilationModeClass),
            build = builderClass.getMethod("build"),
            run = l8Class.getMethod("run", commandClass),
            dexIndexed = outputModes["DexIndexed"] ?: error("DexIndexed output mode missing"),
            releaseMode = compilationModes["RELEASE"] ?: error("RELEASE mode missing"),
            debugMode = compilationModes["DEBUG"] ?: error("DEBUG mode missing")
        )
    }.getOrNull()

    private fun formatFailure(error: Throwable): String =
        "${error.javaClass.simpleName}: ${error.message ?: "L8 invocation failed"}"

    companion object {
        private const val L8_CLASS = "com.android.tools.r8.L8"
        private const val L8_COMMAND_CLASS = "com.android.tools.r8.L8Command"
        private const val L8_BUILDER_CLASS = "com.android.tools.r8.L8Command\$Builder"
        private const val OUTPUT_MODE_CLASS = "com.android.tools.r8.OutputMode"
        private const val COMPILATION_MODE_CLASS = "com.android.tools.r8.CompilationMode"
        private const val VERSION_CLASS = "com.android.tools.r8.Version"
    }
}

class L8CompileResult(
    val outcome: BuildOutcome,
    diagnostics: List<BuildDiagnostic> = emptyList(),
    artifacts: List<BuildArtifact> = emptyList(),
    val durationMs: Long = 0L
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val artifacts: List<BuildArtifact> = Collections.unmodifiableList(artifacts.toList())
    val success: Boolean get() = outcome == BuildOutcome.SUCCESS

    init {
        require(durationMs >= 0L) { "L8_DURATION_INVALID" }
        require(outcome == BuildOutcome.SUCCESS || this.artifacts.isEmpty()) {
            "L8_FAILED_RESULT_HAS_ARTIFACTS"
        }
    }
}

/** Standalone L8 runtime-dex primitive. It does not package or renumber APK dex files. */
class L8RuntimeDexCompiler(
    private val provider: L8Provider = ReflectionL8Provider()
) {
    fun compile(request: L8CompileRequest, token: CancellationToken): L8CompileResult {
        val started = System.nanoTime()
        fun result(
            outcome: BuildOutcome,
            code: String,
            message: String,
            path: String? = null,
            artifacts: List<BuildArtifact> = emptyList()
        ): L8CompileResult {
            val diagnostic = BuildDiagnostic(
                severity = BuildDiagnosticSeverity.ERROR,
                code = code,
                message = message,
                source = BuildDiagnosticSource.DEX,
                path = path
            )
            val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            return L8CompileResult(outcome, listOf(diagnostic), artifacts, elapsed)
        }

        if (token.isCancelled) {
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "L8 compilation was cancelled")
        }
        try {
            validateRequest(request)
        } catch (failure: L8Failure) {
            return result(failure.outcome, failure.code, failure.message, failure.path)
        } catch (_: Exception) {
            return result(BuildOutcome.FAILED, "L8_INPUT_INVALID", "L8 request could not be validated")
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
                "L8_UNAVAILABLE",
                "L8 provider API is unavailable in this runtime"
            )
        }

        val prepared = try {
            prepareInputs(request, token)
        } catch (failure: L8Failure) {
            return result(failure.outcome, failure.code, failure.message, failure.path)
        } catch (_: Exception) {
            return result(BuildOutcome.FAILED, "L8_INPUT_INVALID", "L8 inputs could not be validated")
        }
        if (prepared.cancelled || token.isCancelled) {
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "L8 compilation was cancelled")
        }

        val output = try {
            validateOutputPath(request.outputDirectory, prepared)
            prepareOutputDirectory(request.outputDirectory, prepared).also(::clearDirectoryContents)
        } catch (_: Exception) {
            return result(
                BuildOutcome.FAILED,
                "L8_OUTPUT_INVALID",
                "L8 output directory is unsafe or could not be prepared",
                request.outputDirectory.path
            )
        }
        if (token.isCancelled) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Cancelled L8 output could not be cleaned", output.toString())
            }
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "L8 compilation was cancelled")
        }

        val invocation = L8Invocation(
            programFiles = prepared.programFiles.map(Path::toFile),
            libraryFiles = prepared.libraryFiles.map(Path::toFile),
            desugaredLibraryConfigurationJson = request.desugaredLibraryConfigurationJson,
            keepRulesFile = checkNotNull(prepared.keepRulesFile).toFile(),
            outputDirectory = output.toFile(),
            minApi = request.minApi,
            release = request.release
        )
        val invocationResult = try {
            provider.compile(invocation, token)
        } catch (error: Exception) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Failed L8 output could not be cleaned", output.toString())
            }
            return result(
                BuildOutcome.FAILED,
                "L8_COMPILE_FAILED",
                boundedMessage(error.javaClass.simpleName + ": " + (error.message ?: "provider failed"))
            )
        } catch (error: LinkageError) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Failed L8 output could not be cleaned", output.toString())
            }
            return result(
                BuildOutcome.FAILED,
                "L8_COMPILE_FAILED",
                boundedMessage(error.javaClass.simpleName + ": " + (error.message ?: "provider linkage failed"))
            )
        }
        if (token.isCancelled) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Cancelled L8 output could not be cleaned", output.toString())
            }
            return result(BuildOutcome.CANCELLED, "BUILD_CANCELLED", "L8 compilation was cancelled")
        }
        if (!invocationResult.success) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Failed L8 output could not be cleaned", output.toString())
            }
            return result(
                BuildOutcome.FAILED,
                "L8_COMPILE_FAILED",
                boundedMessage(invocationResult.diagnostics.ifBlank { "L8 invocation failed" })
            )
        }

        val artifacts = try {
            validateDexOutputs(output)
        } catch (failure: L8Failure) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Invalid L8 output could not be cleaned", output.toString())
            }
            return result(failure.outcome, failure.code, failure.message, failure.path)
        } catch (_: Exception) {
            if (!cleanupOutput(output)) {
                return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "Invalid L8 output could not be cleaned", output.toString())
            }
            return result(BuildOutcome.FAILED, "L8_OUTPUT_INVALID", "L8 produced invalid or unsafe output", output.toString())
        }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        return L8CompileResult(BuildOutcome.SUCCESS, artifacts = artifacts, durationMs = elapsed)
    }

    private fun validateRequest(request: L8CompileRequest) {
        if (request.minApi <= 0 || request.outputDirectory.path.isBlank()) {
            fail("L8_INPUT_INVALID", "minApi and output directory must be valid")
        }
        if (request.desugaredLibraryConfigurationJson.isBlank()) {
            fail("L8_CONFIG_INVALID", "Desugared-library configuration JSON is empty")
        }
        if (request.maxConfigurationBytes <= 0L || request.maxKeepRulesBytes <= 0L ||
            request.maxProgramFiles <= 0 || request.maxProgramBytes <= 0L
        ) {
            fail("L8_INPUT_INVALID", "L8 input limits must be positive")
        }
        if (utf8ByteCountUpTo(request.desugaredLibraryConfigurationJson, request.maxConfigurationBytes) >
            request.maxConfigurationBytes
        ) {
            fail(
                "L8_CONFIG_LIMIT",
                "Desugared-library configuration exceeds the configured byte limit",
                outcome = BuildOutcome.RESOURCE_LIMIT
            )
        }
        if (request.programFiles.size > request.maxProgramFiles) {
            fail(
                "L8_PROGRAM_INPUT_LIMIT",
                "L8 program file count exceeds the configured limit",
                outcome = BuildOutcome.RESOURCE_LIMIT
            )
        }
        if (request.programFiles.isEmpty()) {
            fail("L8_PROGRAM_INPUT_INVALID", "No L8 program files were supplied")
        }
    }

    private fun prepareInputs(request: L8CompileRequest, token: CancellationToken): PreparedInputs {
        val programFiles = request.programFiles.map { file ->
            if (token.isCancelled) return PreparedInputs(emptyList(), emptyList(), null, cancelled = true)
            safeExistingFile(file, "L8_PROGRAM_INPUT_INVALID")
        }.distinct()
        var totalProgramBytes = 0L
        programFiles.forEach { path ->
            if (token.isCancelled) return PreparedInputs(emptyList(), emptyList(), null, cancelled = true)
            val size = Files.size(path)
            if (size > request.maxProgramBytes - totalProgramBytes) {
                fail(
                    "L8_PROGRAM_INPUT_LIMIT",
                    "L8 program input bytes exceed the configured limit",
                    path.toString(),
                    BuildOutcome.RESOURCE_LIMIT
                )
            }
            totalProgramBytes += size
        }

        val libraryFiles = request.libraryFiles.map { file ->
            if (token.isCancelled) return PreparedInputs(emptyList(), emptyList(), null, cancelled = true)
            safeExistingFile(file, "L8_LIBRARY_INPUT_INVALID")
        }.distinct()
        if (programFiles.toSet().intersect(libraryFiles.toSet()).isNotEmpty()) {
            fail("L8_INPUT_INVALID", "A canonical file cannot be both an L8 program and library input")
        }

        val keepRulesFile = safeExistingFile(request.keepRulesFile, "L8_KEEP_RULES_INVALID")
        val keepRulesSize = Files.size(keepRulesFile)
        if (keepRulesSize <= 0L) {
            fail("L8_KEEP_RULES_INVALID", "L8 keep-rules file is empty", keepRulesFile.toString())
        }
        if (keepRulesSize > request.maxKeepRulesBytes) {
            fail(
                "L8_KEEP_RULES_LIMIT",
                "L8 keep-rules file exceeds the configured byte limit",
                keepRulesFile.toString(),
                BuildOutcome.RESOURCE_LIMIT
            )
        }
        if (keepRulesFile in programFiles || keepRulesFile in libraryFiles) {
            fail("L8_INPUT_INVALID", "L8 keep-rules file overlaps a program or library input", keepRulesFile.toString())
        }
        return PreparedInputs(programFiles, libraryFiles, keepRulesFile)
    }

    private fun validateOutputPath(outputFile: File, inputs: PreparedInputs) {
        val output = outputFile.toPath().toAbsolutePath().normalize()
        if (output.root == output) fail("L8_OUTPUT_INVALID", "Filesystem root cannot be an L8 output", output.toString())
        rejectSymlinkComponents(output, "L8_OUTPUT_INVALID")
        val existingAncestor = generateSequence(output) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: fail("L8_OUTPUT_INVALID", "L8 output has no existing filesystem ancestor", output.toString())
        if (Files.isSymbolicLink(existingAncestor) || !Files.isDirectory(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            fail("L8_OUTPUT_INVALID", "L8 output path has an unsafe ancestor", existingAncestor.toString())
        }
        val canonicalOutput = prospectiveCanonicalPath(output)
        val keepRulesFile = inputs.keepRulesFile ?: fail("L8_INPUT_INVALID", "L8 keep-rules path is unavailable")
        val protectedInputs = inputs.programFiles + inputs.libraryFiles + listOf(keepRulesFile)
        if (protectedInputs.any { pathsOverlap(canonicalOutput, it) }) {
            fail("L8_OUTPUT_INVALID", "L8 output directory overlaps an input", output.toString())
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS))
        ) {
            fail("L8_OUTPUT_INVALID", "L8 output path is not an ordinary directory", output.toString())
        }
    }

    private fun prepareOutputDirectory(outputFile: File, inputs: PreparedInputs): Path {
        val output = outputFile.toPath().toAbsolutePath().normalize()
        val keepRulesFile = inputs.keepRulesFile ?: fail("L8_INPUT_INVALID", "L8 keep-rules path is unavailable")
        val protectedInputs = inputs.programFiles + inputs.libraryFiles + listOf(keepRulesFile)
        Files.createDirectories(output)
        rejectSymlinkComponents(output, "L8_OUTPUT_INVALID")
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
            fail("L8_OUTPUT_INVALID", "L8 output path is not an ordinary directory", output.toString())
        }
        val canonicalOutput = output.toRealPath()
        if (protectedInputs.any { pathsOverlap(canonicalOutput, it) }) {
            fail("L8_OUTPUT_INVALID", "L8 output directory overlaps an input", output.toString())
        }
        return canonicalOutput
    }

    private fun validateDexOutputs(output: Path): List<BuildArtifact> {
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
            fail("L8_OUTPUT_INVALID", "L8 output directory is missing or unsafe", output.toString())
        }
        val files = mutableListOf<Path>()
        Files.newDirectoryStream(output).use { children ->
            for (child in children) {
                if (Files.isSymbolicLink(child) || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    fail("L8_OUTPUT_INVALID", "L8 produced a non-regular output", child.toString())
                }
                files.add(child)
            }
        }
        if (files.isEmpty()) fail("L8_OUTPUT_MISSING", "L8 reported success but produced no dex files", output.toString())
        val indexed = files.map { file ->
            val name = file.fileName.toString()
            val index = when {
                name == "classes.dex" -> BigInteger.ONE
                name.matches(Regex("classes([2-9]|[1-9][0-9]+)\\.dex")) -> {
                    BigInteger(name.removePrefix("classes").removeSuffix(".dex"))
                }
                else -> fail("L8_OUTPUT_INVALID", "L8 output filename is not an indexed dex name", file.toString())
            }
            file to index
        }.sortedBy { it.second }
        if (indexed.first().second != BigInteger.ONE || indexed.map { it.second }.distinct().size != indexed.size ||
            indexed.withIndex().any { (position, entry) -> entry.second != BigInteger.valueOf(position.toLong() + 1L) }
        ) {
            fail("L8_OUTPUT_INVALID", "L8 dex output indexes are duplicated or not contiguous", output.toString())
        }
        return indexed.map { (file, _) ->
            val size = Files.size(file)
            if (size <= DEX_HEADER_SIZE) fail("L8_OUTPUT_INVALID", "L8 dex output is too small", file.toString())
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
                    fail("L8_OUTPUT_INVALID", "L8 dex output has an invalid magic header", file.toString())
                }
            }
            BuildArtifact("android-desugar-runtime-dex", file.toFile(), sha256(file))
        }
    }

    private fun safeExistingFile(file: File, code: String): Path {
        if (file.path.isBlank()) fail(code, "L8 input path is missing")
        val path = file.toPath().toAbsolutePath().normalize()
        rejectSymlinkComponents(path, code)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            fail(code, "L8 input must be an ordinary file", path.toString())
        }
        if (Files.size(path) <= 0L) fail(code, "L8 input file is empty", path.toString())
        return try {
            path.toRealPath()
        } catch (_: IOException) {
            fail(code, "L8 input path could not be resolved safely", path.toString())
        }
    }

    private fun prospectiveCanonicalPath(path: Path): Path {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return path.toRealPath()
        val ancestor = generateSequence(path) { it.parent }
            .firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?: fail("L8_OUTPUT_INVALID", "Output path has no existing ancestor", path.toString())
        return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize()
    }

    private fun rejectSymlinkComponents(path: Path, code: String) {
        val root = path.root ?: fail(code, "L8 path has no filesystem root", path.toString())
        var current = root
        for (component in path) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                fail(code, "L8 path contains a symbolic link", current.toString())
            }
        }
    }

    private fun clearDirectoryContents(directory: Path) {
        Files.newDirectoryStream(directory).use { children ->
            for (child in children) deleteTreeWithoutFollowingLinks(child)
        }
    }

    private fun cleanupOutput(directory: Path): Boolean = runCatching { clearDirectoryContents(directory) }.isSuccess

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

    private fun pathsOverlap(left: Path, right: Path): Boolean = left.startsWith(right) || right.startsWith(left)

    private fun boundedMessage(message: String): String = message.take(MAX_DIAGNOSTIC_CHARS).ifBlank { "L8 invocation failed" }

    private data class PreparedInputs(
        val programFiles: List<Path>,
        val libraryFiles: List<Path>,
        val keepRulesFile: Path?,
        val cancelled: Boolean = false
    )

    private class L8Failure(
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
    ): Nothing = throw L8Failure(code, message, path, outcome)

    private companion object {
        const val DEX_HEADER_SIZE = 8L
        const val MAX_DIAGNOSTIC_CHARS = 64 * 1024
    }
}
