package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

data class Aapt2CompileRequest(
    val resourceDirectory: File,
    val outputArchive: File,
    val maxResourceFiles: Int = 4096,
    val maxResourceBytes: Long = 64L * 1024L * 1024L
)

data class Aapt2LinkRequest(
    val compiledResources: File,
    val manifest: File,
    val androidJar: File,
    val outputPackage: File,
    val generatedJavaDirectory: File,
    val symbolOutput: File,
    val minSdk: Int,
    val targetSdk: Int,
    val versionCode: Int = 1,
    val versionName: String = "1.0"
)

interface Aapt2Provider {
    val available: Boolean

    fun execute(
        arguments: List<String>,
        workingDirectory: File,
        token: CancellationToken
    ): Aapt2InvocationResult
}

data class Aapt2InvocationResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = ""
) {
    val success: Boolean get() = exitCode == 0
}

/** Executes an explicitly supplied native AAPT2 binary without a command shell. */
class NativeAapt2Provider(
    private val executable: File
) : Aapt2Provider {
    override val available: Boolean
        get() = try {
            resolveExecutable() != null
        } catch (_: Exception) {
            false
        }

    override fun execute(
        arguments: List<String>,
        workingDirectory: File,
        token: CancellationToken
    ): Aapt2InvocationResult {
        if (token.isCancelled) return Aapt2InvocationResult(-1, stderr = "cancelled")
        require(arguments.isNotEmpty()) { "AAPT2_ARGUMENTS_EMPTY" }
        require(arguments.none { '\u0000' in it }) { "AAPT2_ARGUMENT_INVALID" }

        val executablePath = resolveExecutable()
            ?: throw IOException("AAPT2 executable is missing, unsafe, or not an ordinary file")
        val workingPath = prepareExistingDirectory(workingDirectory.toPath())
        val logDirectory = ensureDirectory(workingPath.resolve("logs"))
        val operation = arguments.firstOrNull()?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) }
            ?: "process"
        val suffix = UUID.randomUUID().toString()
        val stdoutLog = logDirectory.resolve("aapt2-$operation-$suffix.stdout.log")
        val stderrLog = logDirectory.resolve("aapt2-$operation-$suffix.stderr.log")
        val command = ArrayList<String>(arguments.size + 1).apply {
            add(executablePath.toString())
            addAll(arguments)
        }

        var process: Process? = null
        var registration: CancellationToken.Registration? = null
        try {
            process = ProcessBuilder(command)
                .directory(workingPath.toFile())
                .redirectOutput(stdoutLog.toFile())
                .redirectError(stderrLog.toFile())
                .start()
            val activeProcess = process
            registration = token.onCancel(Runnable {
                runCatching { activeProcess.destroy() }
            })

            var exitCode: Int
            while (true) {
                if (token.isCancelled) {
                    terminate(activeProcess)
                    exitCode = exitValueOrNull(activeProcess) ?: -1
                    break
                }
                val completed = exitValueOrNull(activeProcess)
                if (completed != null) {
                    exitCode = completed
                    break
                }
                try {
                    Thread.sleep(PROCESS_POLL_MILLIS)
                } catch (interrupted: InterruptedException) {
                    if (!token.isCancelled) Thread.currentThread().interrupt()
                    terminate(activeProcess)
                    if (!token.isCancelled) throw IOException("Interrupted while waiting for AAPT2", interrupted)
                    exitCode = exitValueOrNull(activeProcess) ?: -1
                    break
                }
            }
            return Aapt2InvocationResult(
                exitCode = exitCode,
                stdout = readBoundedLog(stdoutLog),
                stderr = readBoundedLog(stderrLog)
            )
        } catch (error: Exception) {
            process?.let { active ->
                if (exitValueOrNull(active) == null) runCatching { terminate(active) }
            }
            throw error
        } finally {
            registration?.close()
        }
    }

    private fun resolveExecutable(): Path? {
        val raw = executable.absoluteFile.toPath().normalize()
        rejectSymlinkComponents(raw)
        if (!Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)) return null
        return raw.toRealPath()
    }

    private fun prepareExistingDirectory(directory: Path): Path {
        val raw = directory.toAbsolutePath().normalize()
        rejectSymlinkComponents(raw)
        if (!Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("AAPT2 working directory is not an ordinary directory: $raw")
        }
        return raw.toRealPath()
    }

    private fun ensureDirectory(directory: Path): Path {
        val raw = directory.toAbsolutePath().normalize()
        val root = raw.root ?: throw IOException("AAPT2 log path has no filesystem root")
        var current = root
        for (component in raw) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("AAPT2 log path is not an ordinary directory: $current")
                }
            } else {
                Files.createDirectory(current)
            }
        }
        return raw.toRealPath()
    }

    private fun rejectSymlinkComponents(path: Path) {
        val root = path.root ?: throw IOException("Path has no filesystem root: $path")
        var current = root
        for (component in path) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw IOException("Symbolic link is not allowed in AAPT2 provider path: $current")
            }
        }
    }

    private fun readBoundedLog(path: Path): String {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return ""
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return "[AAPT2 diagnostic log is not an ordinary file]"
        }
        val bytes = ByteArrayOutputStream()
        var remaining = MAX_DIAGNOSTIC_BYTES + 1
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(4096)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                if (count > 0) {
                    bytes.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        val content = bytes.toByteArray()
        val truncated = content.size > MAX_DIAGNOSTIC_BYTES
        val visible = String(content, 0, minOf(content.size, MAX_DIAGNOSTIC_BYTES), Charsets.UTF_8)
        return visible + if (truncated) "\n[aapt2 diagnostics truncated]" else ""
    }

    private fun terminate(process: Process) {
        if (exitValueOrNull(process) != null) return
        runCatching { process.destroy() }
        if (waitForExit(process, GRACEFUL_TERMINATION_MILLIS)) return
        runCatching { process.destroyForcibly() }
        if (!waitForExit(process, FORCEFUL_TERMINATION_MILLIS)) {
            throw IOException("AAPT2 process did not terminate after destroyForcibly()")
        }
    }

    private fun waitForExit(process: Process, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (exitValueOrNull(process) == null) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(TERMINATION_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return exitValueOrNull(process) != null
            }
        }
        return true
    }

    private fun exitValueOrNull(process: Process): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    private companion object {
        const val MAX_DIAGNOSTIC_BYTES = 64 * 1024
        const val PROCESS_POLL_MILLIS = 50L
        const val TERMINATION_POLL_MILLIS = 25L
        const val GRACEFUL_TERMINATION_MILLIS = 250L
        const val FORCEFUL_TERMINATION_MILLIS = 2_000L
    }
}

/** Real AAPT2 compile/link primitive; Android build DAG integration is intentionally separate. */
class Aapt2ResourceCompiler(
    private val provider: Aapt2Provider
) {
    fun compile(request: Aapt2CompileRequest, token: CancellationToken): BuildResult {
        val started = System.nanoTime()
        var output: Path? = null
        var outputPrepared = false
        try {
            if (token.isCancelled) return result("compile-resources", BuildOutcome.CANCELLED, started,
                listOf(diagnostic("BUILD_CANCELLED", "AAPT2 compile was cancelled before execution")))
            if (!providerAvailable()) return result("compile-resources", BuildOutcome.UNSUPPORTED, started,
                listOf(diagnostic("AAPT2_UNAVAILABLE", "AAPT2 provider is unavailable")))

            if (request.maxResourceFiles <= 0 || request.maxResourceBytes <= 0L) {
                throw Aapt2Problem("AAPT2_INPUT_INVALID", "Resource input limits must be positive")
            }
            val resourceRoot = existingDirectory(request.resourceDirectory, "resource directory")
            val outputPath = outputFilePath(request.outputArchive)
            if (outputPath.startsWith(resourceRoot)) {
                throw Aapt2Problem("AAPT2_INPUT_INVALID", "Compiled resource archive must not be inside the input res directory")
            }
            scanResources(resourceRoot, request.maxResourceFiles, request.maxResourceBytes)

            prepareDirectory(outputPath.parent)
            validateReplaceableFile(outputPath, "compiled resource archive")
            Files.deleteIfExists(outputPath)
            output = outputPath
            outputPrepared = true
            if (token.isCancelled) return cancelledAfterCompileCleanup(outputPath, started)

            val invocation = invoke(
                arguments = listOf("compile", "--dir", resourceRoot.toString(), "-o", outputPath.toString()),
                workingDirectory = outputPath.parent.toFile(),
                token = token
            )
            if (token.isCancelled) return cancelledAfterCompileCleanup(outputPath, started)
            if (!invocation.success) {
                throw Aapt2Problem(
                    "AAPT2_COMPILE_FAILED",
                    diagnosticText(invocation, "AAPT2 compile failed with exit code ${invocation.exitCode}")
                )
            }
            val artifactPath = verifyNonEmptyOutput(outputPath, "AAPT2_COMPILE_OUTPUT_MISSING")
            val artifact = BuildArtifact(
                kind = "android-compiled-resources",
                file = artifactPath.toFile(),
                sha256 = sha256(artifactPath)
            )
            if (token.isCancelled) return cancelledAfterCompileCleanup(outputPath, started)
            return result("compile-resources", BuildOutcome.SUCCESS, started, artifacts = listOf(artifact))
        } catch (problem: Aapt2Problem) {
            val cleanupError = if (outputPrepared) cleanupFile(output) else null
            val errors = mutableListOf(diagnostic(problem.code, problem.message ?: problem.code))
            if (cleanupError != null) errors += diagnostic(
                "AAPT2_PROCESS_FAILED",
                "Could not clear this compile output after failure: $cleanupError"
            )
            return result("compile-resources", problem.outcome, started, errors)
        } catch (error: Exception) {
            val cleanupError = if (outputPrepared) cleanupFile(output) else null
            val message = error.message ?: error.javaClass.simpleName
            val errors = mutableListOf(diagnostic(
                "AAPT2_PROCESS_FAILED",
                "AAPT2 compile could not complete: $message"
            ))
            if (cleanupError != null) errors += diagnostic(
                "AAPT2_PROCESS_FAILED",
                "Could not clear this compile output after failure: $cleanupError"
            )
            val outcome = if (token.isCancelled) BuildOutcome.CANCELLED else BuildOutcome.FAILED
            if (outcome == BuildOutcome.CANCELLED) errors[0] = diagnostic("BUILD_CANCELLED", message)
            return result("compile-resources", outcome, started, errors)
        }
    }

    fun link(request: Aapt2LinkRequest, token: CancellationToken): BuildResult {
        val started = System.nanoTime()
        var outputs: LinkOutputs? = null
        var outputsPrepared = false
        try {
            if (token.isCancelled) return result("link-resources", BuildOutcome.CANCELLED, started,
                listOf(diagnostic("BUILD_CANCELLED", "AAPT2 link was cancelled before execution")))
            if (!providerAvailable()) return result("link-resources", BuildOutcome.UNSUPPORTED, started,
                listOf(diagnostic("AAPT2_UNAVAILABLE", "AAPT2 provider is unavailable")))

            val compiled = existingFile(request.compiledResources, "compiled resources", requireNonEmpty = true)
            val manifest = existingFile(request.manifest, "AndroidManifest.xml", requireNonEmpty = false)
            val androidJar = existingFile(request.androidJar, "android.jar", requireNonEmpty = true)
            if (request.minSdk <= 0 || request.targetSdk < request.minSdk || request.versionCode <= 0 ||
                request.versionName.isBlank() || '\u0000' in request.versionName
            ) {
                throw Aapt2Problem("AAPT2_INPUT_INVALID", "AAPT2 link SDK/version arguments are invalid")
            }

            val packagePath = outputFilePath(request.outputPackage)
            val generatedPath = outputDirectoryPath(request.generatedJavaDirectory)
            val symbolPath = outputFilePath(request.symbolOutput)
            validateDistinctLinkPaths(packagePath, generatedPath, symbolPath, listOf(compiled, manifest, androidJar))

            prepareDirectory(packagePath.parent)
            prepareDirectory(generatedPath.parent)
            prepareDirectory(symbolPath.parent)
            validateReplaceableFile(packagePath, "linked resource package")
            validateReplaceableFile(symbolPath, "resource symbol output")
            validateReplaceableDirectory(generatedPath, "generated Java output")

            outputs = LinkOutputs(packagePath, generatedPath, symbolPath)
            outputsPrepared = true
            Files.deleteIfExists(packagePath)
            Files.deleteIfExists(symbolPath)
            deleteTreeNoFollow(generatedPath)
            prepareDirectory(generatedPath)
            if (token.isCancelled) return cancelledAfterLinkCleanup(outputs, started)

            val invocation = invoke(
                arguments = listOf(
                    "link",
                    "-o", packagePath.toString(),
                    "-I", androidJar.toString(),
                    "--manifest", manifest.toString(),
                    "--java", generatedPath.toString(),
                    "--output-text-symbols", symbolPath.toString(),
                    "--min-sdk-version", request.minSdk.toString(),
                    "--target-sdk-version", request.targetSdk.toString(),
                    "--version-code", request.versionCode.toString(),
                    "--version-name", request.versionName,
                    compiled.toString()
                ),
                workingDirectory = packagePath.parent.toFile(),
                token = token
            )
            if (token.isCancelled) return cancelledAfterLinkCleanup(outputs, started)
            if (!invocation.success) {
                throw Aapt2Problem(
                    "AAPT2_LINK_FAILED",
                    diagnosticText(invocation, "AAPT2 link failed with exit code ${invocation.exitCode}")
                )
            }

            val artifacts = verifyLinkOutputs(outputs)
            if (token.isCancelled) return cancelledAfterLinkCleanup(outputs, started)
            return result("link-resources", BuildOutcome.SUCCESS, started, artifacts = artifacts)
        } catch (problem: Aapt2Problem) {
            val cleanupError = if (outputsPrepared) cleanupLinkOutputs(outputs) else null
            val errors = mutableListOf(diagnostic(problem.code, problem.message ?: problem.code))
            if (cleanupError != null) errors += diagnostic(
                "AAPT2_PROCESS_FAILED",
                "Could not clear AAPT2 link outputs after failure: $cleanupError"
            )
            return result("link-resources", problem.outcome, started, errors)
        } catch (error: Exception) {
            val cleanupError = if (outputsPrepared) cleanupLinkOutputs(outputs) else null
            val message = error.message ?: error.javaClass.simpleName
            val errors = mutableListOf(diagnostic(
                "AAPT2_PROCESS_FAILED",
                "AAPT2 link could not complete: $message"
            ))
            if (cleanupError != null) errors += diagnostic(
                "AAPT2_PROCESS_FAILED",
                "Could not clear AAPT2 link outputs after failure: $cleanupError"
            )
            val outcome = if (token.isCancelled) BuildOutcome.CANCELLED else BuildOutcome.FAILED
            if (outcome == BuildOutcome.CANCELLED) errors[0] = diagnostic("BUILD_CANCELLED", message)
            return result("link-resources", outcome, started, errors)
        }
    }

    private fun verifyLinkOutputs(outputs: LinkOutputs): List<BuildArtifact> {
        val packagePath = verifyNonEmptyOutput(outputs.packageFile, "AAPT2_LINK_OUTPUT_MISSING")
        try {
            ZipFile(packagePath.toFile()).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null || zip.getEntry("resources.arsc") == null) {
                    throw Aapt2Problem(
                        "AAPT2_LINK_OUTPUT_MISSING",
                        "AAPT2 resource package must contain AndroidManifest.xml and resources.arsc"
                    )
                }
            }
        } catch (problem: Aapt2Problem) {
            throw problem
        } catch (error: Exception) {
            throw Aapt2Problem(
                "AAPT2_LINK_OUTPUT_MISSING",
                "AAPT2 resource package is not a readable ZIP: ${error.message ?: error.javaClass.simpleName}"
            )
        }

        val generated = collectRJava(outputs.generatedJavaDirectory)
        if (generated.isEmpty()) throw Aapt2Problem(
            "AAPT2_GENERATED_R_MISSING",
            "AAPT2 completed without producing an ordinary R.java file"
        )
        val symbolPath = try {
            verifyNonEmptyOutput(outputs.symbolFile, "AAPT2_SYMBOL_OUTPUT_MISSING")
        } catch (problem: Aapt2Problem) {
            if (problem.code == "AAPT2_LINK_OUTPUT_MISSING") {
                throw Aapt2Problem("AAPT2_SYMBOL_OUTPUT_MISSING", problem.message ?: "R.txt is missing")
            }
            throw problem
        }

        val artifacts = mutableListOf(
            BuildArtifact("android-linked-resources", packagePath.toFile(), sha256(packagePath))
        )
        generated.forEach { path ->
            artifacts += BuildArtifact("android-r-java", path.toFile(), sha256(path))
        }
        artifacts += BuildArtifact("android-resource-symbols", symbolPath.toFile(), sha256(symbolPath))
        return artifacts
    }

    private fun collectRJava(directory: Path): List<Path> {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Generated Java output is not an ordinary directory")
        }
        val root = try {
            directory.toRealPath()
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Could not resolve generated Java output: ${error.message}")
        }
        val results = mutableListOf<Path>()
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isDirectory || !dir.toRealPath().startsWith(root)) {
                        throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Generated Java output contains an unsafe directory: $dir")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink) {
                        throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Generated Java output contains a symbolic link: $file")
                    }
                    if (file.fileName.toString() == "R.java") {
                        if (!attrs.isRegularFile || !file.toRealPath().startsWith(root) || Files.size(file) <= 0L) {
                            throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Generated R.java is not an ordinary non-empty file: $file")
                        }
                        results.add(file.toRealPath())
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Could not inspect generated Java output $file: ${error.message}")
                }
            })
        } catch (problem: Aapt2Problem) {
            throw problem
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_GENERATED_R_MISSING", "Could not scan generated Java output: ${error.message}")
        }
        return results.distinct().sortedBy { root.relativize(it).toString().replace(File.separatorChar, '/') }
    }

    private fun scanResources(root: Path, maxFiles: Int, maxBytes: Long) {
        var fileCount = 0
        var byteCount = 0L
        try {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isDirectory || !dir.toRealPath().startsWith(root)) {
                        throw Aapt2Problem("AAPT2_INPUT_INVALID", "Resource tree contains an unsafe directory: $dir")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                        throw Aapt2Problem("AAPT2_INPUT_INVALID", "Resource tree contains a non-ordinary file: $file")
                    }
                    if (!file.toRealPath().startsWith(root)) {
                        throw Aapt2Problem("AAPT2_INPUT_INVALID", "Resource file escapes the res directory: $file")
                    }
                    if (fileCount >= maxFiles) throw Aapt2Problem(
                        "AAPT2_RESOURCE_LIMIT",
                        "Resource file count exceeds the configured limit of $maxFiles",
                        BuildOutcome.RESOURCE_LIMIT
                    )
                    fileCount++
                    val size = attrs.size()
                    if (size < 0L || size > maxBytes - byteCount) throw Aapt2Problem(
                        "AAPT2_RESOURCE_LIMIT",
                        "Resource byte count exceeds the configured limit of $maxBytes",
                        BuildOutcome.RESOURCE_LIMIT
                    )
                    byteCount += size
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                    throw Aapt2Problem("AAPT2_INPUT_INVALID", "Could not inspect resource input $file: ${error.message}")
                }
            })
        } catch (problem: Aapt2Problem) {
            throw problem
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "Could not scan resource directory: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun existingDirectory(file: File, label: String): Path {
        val raw = file.absoluteFile.toPath().normalize()
        requireNoSymlinkComponents(raw, label)
        if (!Files.isDirectory(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "$label is missing or is not an ordinary directory: $file")
        }
        return try {
            raw.toRealPath()
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "Could not resolve $label: ${error.message}")
        }
    }

    private fun existingFile(file: File, label: String, requireNonEmpty: Boolean): Path {
        val raw = file.absoluteFile.toPath().normalize()
        requireNoSymlinkComponents(raw, label)
        if (!Files.isRegularFile(raw, LinkOption.NOFOLLOW_LINKS)) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "$label is missing or is not an ordinary file: $file")
        }
        if (requireNonEmpty && Files.size(raw) <= 0L) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "$label must not be empty: $file")
        }
        return try {
            raw.toRealPath()
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "Could not resolve $label: ${error.message}")
        }
    }

    private fun requireNoSymlinkComponents(path: Path, label: String) {
        try {
            rejectSymlinkComponents(path)
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "$label contains an unsafe path component: ${error.message}")
        }
    }

    private fun outputFilePath(file: File): Path {
        val raw = file.absoluteFile.toPath().normalize()
        if (raw.fileName == null || raw.parent == null) throw Aapt2Problem("AAPT2_INPUT_INVALID", "Output file path is invalid: $file")
        requireNoSymlinkComponents(raw, "output file")
        return raw
    }

    private fun outputDirectoryPath(file: File): Path {
        val raw = file.absoluteFile.toPath().normalize()
        if (raw.fileName == null || raw.parent == null) throw Aapt2Problem("AAPT2_INPUT_INVALID", "Output directory path is invalid: $file")
        requireNoSymlinkComponents(raw, "output directory")
        return raw
    }

    private fun validateDistinctLinkPaths(
        packagePath: Path,
        generatedPath: Path,
        symbolPath: Path,
        inputs: List<Path>
    ) {
        val outputPaths = listOf(packagePath, generatedPath, symbolPath)
        for (left in outputPaths.indices) {
            for (right in left + 1 until outputPaths.size) {
                if (overlaps(outputPaths[left], outputPaths[right])) {
                    throw Aapt2Problem("AAPT2_INPUT_INVALID", "AAPT2 link output paths overlap")
                }
            }
        }
        for (input in inputs) {
            if (outputPaths.any { output -> input == output || input.startsWith(output) }) {
                throw Aapt2Problem("AAPT2_INPUT_INVALID", "AAPT2 link output would overwrite or contain an input: $input")
            }
        }
    }

    private fun overlaps(first: Path, second: Path): Boolean = first.startsWith(second) || second.startsWith(first)

    private fun prepareDirectory(directory: Path) {
        val absolute = directory.toAbsolutePath().normalize()
        val root = absolute.root ?: throw Aapt2Problem("AAPT2_INPUT_INVALID", "Output directory has no filesystem root")
        var current = root
        try {
            for (component in absolute) {
                current = current.resolve(component)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw Aapt2Problem("AAPT2_INPUT_INVALID", "Output parent is not an ordinary directory: $current")
                    }
                } else {
                    Files.createDirectory(current)
                }
            }
        } catch (problem: Aapt2Problem) {
            throw problem
        } catch (error: Exception) {
            throw Aapt2Problem("AAPT2_INPUT_INVALID", "Could not prepare output directory: ${error.message}")
        }
    }

    private fun validateReplaceableFile(path: Path, label: String) {
        requireNoSymlinkComponents(path, label)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        ) throw Aapt2Problem("AAPT2_INPUT_INVALID", "$label output is not an ordinary file: $path")
    }

    private fun validateReplaceableDirectory(path: Path, label: String) {
        requireNoSymlinkComponents(path, label)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        ) throw Aapt2Problem("AAPT2_INPUT_INVALID", "$label is not an ordinary directory: $path")
    }

    private fun verifyNonEmptyOutput(path: Path, code: String): Path {
        try {
            requireNoSymlinkComponents(path, "AAPT2 output")
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) <= 0L) {
                throw Aapt2Problem(code, "AAPT2 did not produce a non-empty ordinary output file: $path")
            }
            val canonical = path.toRealPath()
            if (canonical != path.toAbsolutePath().normalize()) {
                throw Aapt2Problem(code, "AAPT2 output resolved outside its requested path: $path")
            }
            return canonical
        } catch (problem: Aapt2Problem) {
            throw problem
        } catch (error: Exception) {
            throw Aapt2Problem(code, "Could not verify AAPT2 output $path: ${error.message}")
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun invoke(arguments: List<String>, workingDirectory: File, token: CancellationToken): Aapt2InvocationResult =
        try {
            provider.execute(arguments.toList(), workingDirectory, token)
        } catch (error: Exception) {
            if (token.isCancelled) throw CancellationException(error.message ?: "AAPT2 invocation was cancelled")
            throw Aapt2Problem(
                "AAPT2_PROCESS_FAILED",
                "Could not start or wait for AAPT2: ${error.message ?: error.javaClass.simpleName}"
            )
        }

    private fun providerAvailable(): Boolean = try {
        provider.available
    } catch (_: Exception) {
        false
    }

    private fun diagnosticText(invocation: Aapt2InvocationResult, fallback: String): String =
        listOf(invocation.stderr, invocation.stdout).filter { it.isNotBlank() }
            .joinToString("\n").ifBlank { fallback }

    private fun cancelledAfterCompileCleanup(path: Path, started: Long): BuildResult {
        val cleanup = cleanupFile(path)
        val diagnostics = mutableListOf(diagnostic("BUILD_CANCELLED", "AAPT2 compile was cancelled"))
        if (cleanup != null) diagnostics += diagnostic("AAPT2_PROCESS_FAILED", "Could not clear cancelled compile output: $cleanup")
        return result("compile-resources", BuildOutcome.CANCELLED, started, diagnostics)
    }

    private fun cancelledAfterLinkCleanup(outputs: LinkOutputs, started: Long): BuildResult {
        val cleanup = cleanupLinkOutputs(outputs)
        val diagnostics = mutableListOf(diagnostic("BUILD_CANCELLED", "AAPT2 link was cancelled"))
        if (cleanup != null) diagnostics += diagnostic("AAPT2_PROCESS_FAILED", "Could not clear cancelled link outputs: $cleanup")
        return result("link-resources", BuildOutcome.CANCELLED, started, diagnostics)
    }

    private fun cleanupFile(path: Path?): String? = try {
        if (path != null) deleteExactFileNoFollow(path)
        null
    } catch (error: Exception) {
        error.message ?: error.javaClass.simpleName
    }

    private fun cleanupLinkOutputs(outputs: LinkOutputs?): String? {
        if (outputs == null) return null
        val errors = mutableListOf<String>()
        listOf(outputs.packageFile, outputs.symbolFile).forEach { path ->
            runCatching { deleteExactFileNoFollow(path) }.exceptionOrNull()?.let {
                errors += it.message ?: it.javaClass.simpleName
            }
        }
        runCatching { deleteTreeNoFollow(outputs.generatedJavaDirectory) }.exceptionOrNull()?.let {
            errors += it.message ?: it.javaClass.simpleName
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun deleteExactFileNoFollow(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            throw IOException("Refusing to recursively delete file output path: $path")
        }
        Files.delete(path)
    }

    private fun deleteTreeNoFollow(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun result(
        taskId: String,
        outcome: BuildOutcome,
        started: Long,
        diagnostics: List<BuildDiagnostic> = emptyList(),
        artifacts: List<BuildArtifact> = emptyList()
    ) = BuildResult(
        outcome = outcome,
        diagnostics = diagnostics,
        producedArtifacts = if (outcome == BuildOutcome.SUCCESS) artifacts else emptyList(),
        taskResults = listOf(BuildTaskResult(taskId, taskOutcome(outcome), diagnostics = diagnostics)),
        durationMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
    )

    private fun taskOutcome(outcome: BuildOutcome): BuildTaskOutcome = when (outcome) {
        BuildOutcome.SUCCESS -> BuildTaskOutcome.EXECUTED
        BuildOutcome.CANCELLED -> BuildTaskOutcome.CANCELLED
        BuildOutcome.FAILED -> BuildTaskOutcome.FAILED
        BuildOutcome.UNSUPPORTED, BuildOutcome.RESOURCE_LIMIT -> BuildTaskOutcome.SKIPPED
    }

    private fun diagnostic(code: String, message: String) = BuildDiagnostic(
        severity = BuildDiagnosticSeverity.ERROR,
        code = code,
        message = message.ifBlank { code },
        source = BuildDiagnosticSource.AAPT2
    )

    private data class LinkOutputs(val packageFile: Path, val generatedJavaDirectory: Path, val symbolFile: Path)

    private class Aapt2Problem(
        val code: String,
        override val message: String,
        val outcome: BuildOutcome = BuildOutcome.FAILED
    ) : RuntimeException(message)

    private companion object {
        const val HASH_BUFFER_BYTES = 16 * 1024
    }
}

private fun rejectSymlinkComponents(path: Path) {
    val root = path.root ?: throw IOException("Path has no filesystem root: $path")
    var current = root
    for (component in path) {
        current = current.resolve(component)
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            throw IOException("Symbolic link is not allowed: $current")
        }
    }
}
