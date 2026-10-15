package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.TreeMap

/** Provider identity is separate from the result semantics exposed to P4. */
enum class BuildBackendId {
    EMBEDDED,
    SHELL,
    UNAVAILABLE
}

enum class BuildOutcome {
    SUCCESS,
    FAILED,
    UNSUPPORTED,
    CANCELLED,
    RESOURCE_LIMIT
}

enum class BuildDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

enum class BuildDiagnosticSource {
    PROJECT,
    DEPENDENCY,
    JAVA,
    KOTLIN,
    AAPT2,
    DEX,
    PACKAGING,
    SIGNING,
    TEST,
    TOOLCHAIN
}

data class BuildDiagnostic(
    val severity: BuildDiagnosticSeverity,
    val code: String,
    val message: String,
    val source: BuildDiagnosticSource,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null
) {
    init {
        require(code.isNotBlank()) { "BUILD_DIAGNOSTIC_CODE_MISSING" }
        require(message.isNotBlank()) { "BUILD_DIAGNOSTIC_MESSAGE_MISSING" }
    }
}

data class BuildArtifact(
    val kind: String,
    val file: File,
    val sha256: String? = null
) {
    init {
        require(kind.isNotBlank()) { "BUILD_ARTIFACT_KIND_MISSING" }
        require(file.path.isNotBlank()) { "BUILD_ARTIFACT_PATH_MISSING" }
    }
}

enum class BuildTaskOutcome {
    PENDING,
    UP_TO_DATE,
    EXECUTED,
    FAILED,
    CANCELLED,
    SKIPPED
}

class BuildTaskResult(
    val id: String,
    val outcome: BuildTaskOutcome,
    val durationMs: Long = 0L,
    diagnostics: List<BuildDiagnostic> = emptyList()
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())

    init {
        require(id.isNotBlank()) { "BUILD_TASK_ID_MISSING" }
        require(durationMs >= 0L) { "BUILD_TASK_DURATION_INVALID" }
    }

    override fun equals(other: Any?): Boolean = other is BuildTaskResult &&
        id == other.id && outcome == other.outcome && durationMs == other.durationMs &&
        diagnostics == other.diagnostics

    override fun hashCode(): Int = listOf(id, outcome, durationMs, diagnostics).hashCode()
}

/** Structured output shared by Embedded and compatibility backends. */
class BuildResult(
    val outcome: BuildOutcome,
    diagnostics: List<BuildDiagnostic> = emptyList(),
    producedArtifacts: List<BuildArtifact> = emptyList(),
    taskResults: List<BuildTaskResult> = emptyList(),
    val durationMs: Long = 0L
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val producedArtifacts: List<BuildArtifact> = Collections.unmodifiableList(producedArtifacts.toList())
    val taskResults: List<BuildTaskResult> = Collections.unmodifiableList(taskResults.toList())

    init {
        require(durationMs >= 0L) { "BUILD_DURATION_INVALID" }
        if (outcome == BuildOutcome.SUCCESS) {
            require(diagnostics.none { it.severity == BuildDiagnosticSeverity.ERROR }) {
                "BUILD_SUCCESS_WITH_ERROR_DIAGNOSTIC"
            }
            require(taskResults.none { result ->
                result.diagnostics.any { it.severity == BuildDiagnosticSeverity.ERROR }
            }) { "BUILD_SUCCESS_WITH_ERROR_DIAGNOSTIC" }
            require(taskResults.none {
                it.outcome == BuildTaskOutcome.FAILED ||
                    it.outcome == BuildTaskOutcome.CANCELLED ||
                    it.outcome == BuildTaskOutcome.PENDING
            }) { "BUILD_SUCCESS_WITH_INCOMPLETE_TASK" }
        }
    }

    val success: Boolean get() = outcome == BuildOutcome.SUCCESS

    override fun equals(other: Any?): Boolean = other is BuildResult &&
        outcome == other.outcome && diagnostics == other.diagnostics &&
        producedArtifacts == other.producedArtifacts && taskResults == other.taskResults &&
        durationMs == other.durationMs

    override fun hashCode(): Int = listOf(
        outcome, diagnostics, producedArtifacts, taskResults, durationMs
    ).hashCode()
}

/** A plan is data; B1 can therefore be tested without running any compiler. */
class BuildTaskPlan(
    val id: String,
    val description: String,
    dependsOn: List<String> = emptyList(),
    val optional: Boolean = false
) {
    val dependsOn: List<String> = Collections.unmodifiableList(dependsOn.toList())

    init {
        require(id.isNotBlank()) { "BUILD_TASK_ID_MISSING" }
        require(description.isNotBlank()) { "BUILD_TASK_DESCRIPTION_MISSING" }
        require(id !in dependsOn) { "BUILD_TASK_SELF_DEPENDENCY" }
    }

    override fun equals(other: Any?): Boolean = other is BuildTaskPlan &&
        id == other.id && description == other.description &&
        dependsOn == other.dependsOn && optional == other.optional

    override fun hashCode(): Int = listOf(id, description, dependsOn, optional).hashCode()
}

class BuildExecutionInputs(
    val projectRoot: String,
    val outputDirectory: String,
    requestedTasks: List<String>,
    options: Map<String, String>,
    val projectFingerprint: String
) {
    /** Defensive snapshots: a caller cannot mutate a plan after it is created. */
    val requestedTasks: List<String> = Collections.unmodifiableList(requestedTasks.toList().sorted())
    val options: Map<String, String> = Collections.unmodifiableMap(TreeMap(options))

    init {
        require(projectRoot.isNotBlank()) { "BUILD_PROJECT_ROOT_MISSING" }
        require(outputDirectory.isNotBlank()) { "BUILD_OUTPUT_DIRECTORY_MISSING" }
        require(projectFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "BUILD_PROJECT_FINGERPRINT_INVALID"
        }
    }

    override fun equals(other: Any?): Boolean = other is BuildExecutionInputs &&
        projectRoot == other.projectRoot && outputDirectory == other.outputDirectory &&
        requestedTasks == other.requestedTasks && options == other.options &&
        projectFingerprint == other.projectFingerprint

    override fun hashCode(): Int = listOf(
        projectRoot, outputDirectory, requestedTasks, options, projectFingerprint
    ).hashCode()

    companion object {
        fun from(context: BuildContext): BuildExecutionInputs = BuildExecutionInputs(
            projectRoot = context.project.root.canonicalFile.path,
            outputDirectory = context.outputDirectory.canonicalFile.path,
            requestedTasks = context.requestedTasks.toList().sorted(),
            options = context.options.toSortedMap(),
            projectFingerprint = BuildFingerprints.project(context.project)
        )
    }
}

class BuildPlan(
    val backend: BuildBackendId,
    tasks: List<BuildTaskPlan>,
    val execution: BuildExecutionInputs,
    diagnostics: List<BuildDiagnostic> = emptyList()
) {
    val tasks: List<BuildTaskPlan> = Collections.unmodifiableList(tasks.toList())
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())

    /** Computed, canonical identity; callers cannot inject a fake digest. */
    val fingerprint: String
        get() = BuildFingerprints.plan(backend, tasks, diagnostics, execution)

    init {
        val ids = tasks.map { it.id }
        require(ids.size == ids.toSet().size) { "BUILD_TASK_DUPLICATE_ID" }
        val known = ids.toSet()
        require(tasks.all { task -> task.dependsOn.all { it in known } }) {
            "BUILD_TASK_DEPENDENCY_MISSING"
        }
        require(isAcyclic(tasks)) { "BUILD_TASK_DEPENDENCY_CYCLE" }
    }

    private fun isAcyclic(tasks: List<BuildTaskPlan>): Boolean {
        val byId = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return false
            if (!visited.add(id)) return true
            visiting += id
            val acyclic = byId.getValue(id).dependsOn.all(::visit)
            visiting -= id
            return acyclic
        }
        return tasks.all { visit(it.id) }
    }

    override fun equals(other: Any?): Boolean = other is BuildPlan &&
        backend == other.backend && tasks == other.tasks &&
        execution == other.execution && diagnostics == other.diagnostics

    override fun hashCode(): Int = listOf(backend, tasks, execution, diagnostics).hashCode()
}

data class BuildContext(
    val project: EmbeddedProjectModel,
    val outputDirectory: File,
    val requestedTasks: Set<String> = emptySet(),
    val options: Map<String, String> = emptyMap()
) {
    init {
        require(outputDirectory.path.isNotBlank()) { "BUILD_OUTPUT_DIRECTORY_MISSING" }
    }
}

/** Provider-neutral build boundary. Implementations must fail closed. */
interface BuildBackend {
    val id: BuildBackendId

    fun supports(project: EmbeddedProjectModel): Boolean

    fun plan(context: BuildContext): BuildPlan

    fun execute(context: BuildContext, plan: BuildPlan, token: CancellationToken): BuildResult
}

class BuildBackendRegistry(backends: List<BuildBackend>) {
    private val providers = backends.toList()

    init {
        val ids = providers.map { it.id }
        require(ids.size == ids.toSet().size) { "BUILD_BACKEND_DUPLICATE_ID" }
    }

    fun select(project: EmbeddedProjectModel): BuildBackend? =
        providers.firstOrNull { it.supports(project) }

    fun plan(context: BuildContext): BuildPlan {
        val backend = select(context.project)
            ?: return BuildPlan(
                backend = BuildBackendId.UNAVAILABLE,
                tasks = emptyList(),
                execution = BuildExecutionInputs.from(context),
                diagnostics = listOf(
                    BuildDiagnostic(
                        BuildDiagnosticSeverity.ERROR,
                        "BUILD_BACKEND_UNAVAILABLE",
                        "No registered backend supports this project",
                        BuildDiagnosticSource.PROJECT
                    )
                )
            )
        return backend.plan(context)
    }
}

/** Stable identity for a plan. It includes diagnostics because unsupported plans matter. */
internal object BuildFingerprints {
    private const val MAX_FILES = 20_000
    private const val MAX_SINGLE_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 256L * 1024L * 1024L
    private const val MAX_RELATIVE_DEPTH = 64
    private const val CONTENT_BUFFER_BYTES = 64 * 1024

    private class InputManifestFailure(code: String) : IllegalStateException(code)

    private data class InputManifestEntry(
        val file: File,
        val size: Long,
        val fileKey: Any?,
        val lastModifiedTimeMillis: Long,
        val creationTimeMillis: Long?
    )

    private data class ProjectRootPaths(
        val original: Path,
        val canonical: Path
    )

    private data class StableInputPath(
        val canonical: Path,
        val attributes: BasicFileAttributes
    )

    private data class VerifiedInputManifest(
        val projectRoot: ProjectRootPaths,
        val entries: List<InputManifestEntry>
    )

    fun project(project: EmbeddedProjectModel): String {
        // Complete metadata validation and bounded manifest collection before
        // any contentDigest call. A failure is fail-closed with a stable code.
        val manifest = collectInputManifest(project)
        val text = buildString {
            append(project.root.canonicalFile.path).append('\n')
            append(project.settingsScript?.canonicalFile?.path.orEmpty()).append('\n')
            append(project.rootBuildScript?.canonicalFile?.path.orEmpty()).append('\n')
            project.modules.sortedBy { it.gradlePath }.forEach { module ->
                append(module.name).append('|')
                    .append(module.gradlePath).append('|')
                    .append(module.directory.canonicalFile.path).append('|')
                    .append(module.kind.name).append('|')
                    .append(module.namespace.orEmpty()).append('|')
                    .append(module.compileSdk ?: "").append('|')
                    .append(module.minSdk ?: "").append('|')
                    .append(module.targetSdk ?: "").append('|')
                    .append(module.kotlinPlugin).append('|')
                module.pluginDeclarations.sortedWith(compareBy({ it.id }, { it.version.orEmpty() }, { it.form }))
                    .forEach { declaration ->
                        append("plugin=").append(declaration.id).append('@')
                            .append(declaration.version.orEmpty()).append(':')
                            .append(declaration.form).append('|')
                    }
                module.dependencies.sortedWith(compareBy({ it.configuration }, { it.notation }))
                    .forEach { dependency ->
                        append(dependency.configuration).append(':')
                            .append(dependency.notation).append(':')
                            .append(dependency.projectPath.orEmpty()).append('|')
                    }
                module.sourceSet.javaDirectories.map { it.canonicalFile.path }.sorted()
                    .forEach { append("java=").append(it).append('|') }
                module.sourceSet.kotlinDirectories.map { it.canonicalFile.path }.sorted()
                    .forEach { append("kotlin=").append(it).append('|') }
                module.sourceSet.resourceDirectories.map { it.canonicalFile.path }.sorted()
                    .forEach { append("res=").append(it).append('|') }
                module.sourceSet.testJavaDirectories.map { it.canonicalFile.path }.sorted()
                    .forEach { append("test-java=").append(it).append('|') }
                module.sourceSet.testKotlinDirectories.map { it.canonicalFile.path }.sorted()
                    .forEach { append("test-kotlin=").append(it).append('|') }
                module.sourceSet.testResourceDirectories.map { it.canonicalFile.path }.sorted()
                    .forEach { append("test-res=").append(it).append('|') }
                append("manifest=").append(module.sourceSet.manifest?.canonicalFile?.path.orEmpty()).append('\n')
                append("script=").append(module.buildScript?.canonicalFile?.path.orEmpty()).append('\n')
            }
            appendManifestSnapshots(manifest, this)
            project.unsupportedReasons.sorted().forEach { append("reason=").append(it).append('\n') }
        }
        return sha256(text)
    }

    fun plan(
        backend: BuildBackendId,
        tasks: List<BuildTaskPlan>,
        diagnostics: List<BuildDiagnostic>,
        execution: BuildExecutionInputs
    ): String {
        val text = buildString {
            append(backend.name).append('\n')
            append(execution.projectRoot).append('\n')
            append(execution.outputDirectory).append('\n')
            execution.requestedTasks.forEach { append("request=").append(it).append('\n') }
            execution.options.toSortedMap().forEach { (key, value) ->
                append("option=").append(key).append('=').append(value).append('\n')
            }
            append("project=").append(execution.projectFingerprint).append('\n')
            tasks.sortedBy { it.id }.forEach { task ->
                append(task.id).append('|')
                    .append(task.description).append('|')
                    .append(task.dependsOn.sorted().joinToString(",")).append('|')
                    .append(task.optional).append('\n')
            }
            diagnostics.sortedWith(compareBy(
                { it.severity.name }, { it.code }, { it.message }, { it.source.name },
                { it.path.orEmpty() }, { it.line ?: -1 }, { it.column ?: -1 }
            )).forEach { diagnostic ->
                append(diagnostic.severity.name).append('|')
                    .append(diagnostic.code).append('|')
                    .append(diagnostic.message).append('|')
                    .append(diagnostic.source.name).append('|')
                    .append(diagnostic.path.orEmpty()).append('|')
                    .append(diagnostic.line ?: "").append('|')
                    .append(diagnostic.column ?: "").append('\n')
            }
        }
        return sha256(text)
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun establishProjectRoot(project: EmbeddedProjectModel): ProjectRootPaths {
        val original = project.root.toPath().toAbsolutePath().normalize()
        inspectPathComponents(original, expectedDirectory = true)
        val canonical = canonicalPath(original)
        val canonicalAttributes = readNoFollowAttributes(canonical)
        if (!canonicalAttributes.isDirectory) fail("BUILD_INPUT_CHANGED")
        return ProjectRootPaths(original, canonical)
    }

    private fun validateModeledPath(
        projectRoot: ProjectRootPaths,
        candidate: File,
        expectedDirectory: Boolean
    ): StableInputPath {
        val normalized = candidate.toPath().toAbsolutePath().normalize()
        if (!normalized.startsWith(projectRoot.canonical) ||
            !normalized.startsWith(projectRoot.original)
        ) {
            fail("BUILD_INPUT_PATH_UNSAFE")
        }

        val currentCanonicalRoot = canonicalPath(projectRoot.original)
        if (currentCanonicalRoot != projectRoot.canonical) {
            fail("BUILD_INPUT_PATH_UNSAFE")
        }
        inspectPathComponents(projectRoot.original, expectedDirectory = true)
        inspectPathComponents(normalized, expectedDirectory)

        val canonical = canonicalPath(normalized)
        if (!canonical.startsWith(projectRoot.canonical) || Files.isSymbolicLink(canonical)) {
            fail("BUILD_INPUT_PATH_UNSAFE")
        }
        val canonicalAttributes = readNoFollowAttributes(canonical)
        if (expectedDirectory && !canonicalAttributes.isDirectory) {
            fail("BUILD_INPUT_CHANGED")
        }
        if (!expectedDirectory && !canonicalAttributes.isRegularFile) {
            fail("BUILD_INPUT_CHANGED")
        }
        return StableInputPath(canonical, canonicalAttributes)
    }

    private fun inspectPathComponents(
        candidate: Path,
        expectedDirectory: Boolean
    ): BasicFileAttributes {
        val normalized = candidate.toAbsolutePath().normalize()
        val filesystemRoot = normalized.root ?: fail("BUILD_INPUT_PATH_UNSAFE")
        var current = filesystemRoot
        var attributes = readNoFollowAttributes(current)
        for (component in filesystemRoot.relativize(normalized)) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) fail("BUILD_INPUT_PATH_UNSAFE")
            attributes = readNoFollowAttributes(current)
            if (current != normalized && !attributes.isDirectory) {
                fail("BUILD_INPUT_CHANGED")
            }
        }
        if (expectedDirectory && !attributes.isDirectory) {
            fail("BUILD_INPUT_CHANGED")
        }
        if (!expectedDirectory && !attributes.isRegularFile) {
            fail("BUILD_INPUT_CHANGED")
        }
        return attributes
    }

    private fun readNoFollowAttributes(path: Path): BasicFileAttributes {
        if (Files.isSymbolicLink(path)) fail("BUILD_INPUT_PATH_UNSAFE")
        return try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        } catch (_: NoSuchFileException) {
            fail("BUILD_INPUT_CHANGED")
        } catch (_: Exception) {
            fail("BUILD_INPUT_PATH_UNSAFE")
        }
    }

    private fun canonicalPath(path: Path): Path = try {
        path.toFile().canonicalFile.toPath().toAbsolutePath().normalize()
    } catch (_: Exception) {
        fail("BUILD_INPUT_PATH_UNSAFE")
    }

    private fun collectInputManifest(project: EmbeddedProjectModel): VerifiedInputManifest {
        val projectRoot = establishProjectRoot(project)
        val entries = LinkedHashMap<String, InputManifestEntry>()
        var totalBytes = 0L

        fun addFile(file: File?, relativeDepth: Int) {
            if (file == null) return
            val stable = validateModeledPath(projectRoot, file, expectedDirectory = false)
            val canonicalPath = stable.canonical
            val canonicalAttributes = stable.attributes
            if (relativeDepth > MAX_RELATIVE_DEPTH) fail("BUILD_INPUT_DEPTH_LIMIT")

            val size = canonicalAttributes.size()
            if (size > MAX_SINGLE_FILE_BYTES) fail("BUILD_INPUT_FILE_BYTES_LIMIT")

            // Canonical de-duplication happens before file-count and total-byte
            // accounting. Repeated settings/build inputs are therefore counted
            // and read exactly once.
            val key = canonicalPath.toString()
            if (entries.containsKey(key)) return
            if (entries.size >= MAX_FILES) fail("BUILD_INPUT_FILE_COUNT_LIMIT")
            if (size > MAX_TOTAL_BYTES - totalBytes) {
                fail("BUILD_INPUT_TOTAL_BYTES_LIMIT")
            }
            entries[key] = InputManifestEntry(
                file = canonicalPath.toFile(),
                size = size,
                fileKey = canonicalAttributes.fileKey(),
                lastModifiedTimeMillis = canonicalAttributes.lastModifiedTime().toMillis(),
                creationTimeMillis = runCatching {
                    canonicalAttributes.creationTime().toMillis()
                }.getOrNull()
            )
            totalBytes += size
        }

        fun addDirectory(directory: File?) {
            if (directory == null) return
            val stableRoot = validateModeledPath(projectRoot, directory, expectedDirectory = true)
            val root = stableRoot.canonical
            try {
                Files.walkFileTree(
                    root,
                    emptySet<FileVisitOption>(),
                    Int.MAX_VALUE,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            current: Path,
                            attributes: BasicFileAttributes
                        ): FileVisitResult {
                            if (attributes.isSymbolicLink || Files.isSymbolicLink(current) ||
                                !attributes.isDirectory
                            ) {
                                fail("BUILD_INPUT_PATH_UNSAFE")
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            child: Path,
                            attributes: BasicFileAttributes
                        ): FileVisitResult {
                            if (attributes.isSymbolicLink || Files.isSymbolicLink(child) ||
                                !attributes.isRegularFile
                            ) {
                                fail("BUILD_INPUT_PATH_UNSAFE")
                            }
                            addFile(child.toFile(), root.relativize(child).nameCount)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            child: Path,
                            error: java.io.IOException
                        ): FileVisitResult {
                            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                                fail("BUILD_INPUT_CHANGED")
                            }
                            fail("BUILD_INPUT_PATH_UNSAFE")
                        }

                        override fun postVisitDirectory(
                            directory: Path,
                            exception: java.io.IOException?
                        ): FileVisitResult {
                            if (exception != null) {
                                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                                    fail("BUILD_INPUT_CHANGED")
                                }
                                fail("BUILD_INPUT_PATH_UNSAFE")
                            }
                            return FileVisitResult.CONTINUE
                        }
                    }
                )
            } catch (failure: InputManifestFailure) {
                throw failure
            } catch (_: Exception) {
                fail("BUILD_INPUT_PATH_UNSAFE")
            }
        }

        addFile(project.settingsScript, 0)
        addFile(project.rootBuildScript, 0)
        // Lock bytes are build inputs, not untracked cache contents. Absence is
        // represented by no entry; publishing/removing/changing a lock changes
        // the manifest fingerprint and invalidates a previously planned build.
        (listOf(project.root) + project.modules.map { it.directory }).distinct().forEach { owner ->
            val lock = File(owner, ".vega/maven-dependencies.lock")
            if (Files.exists(lock.toPath(), LinkOption.NOFOLLOW_LINKS)) addFile(lock, 0)
        }
        project.modules.sortedBy { it.gradlePath }.forEach { module ->
            addFile(module.buildScript, 0)
            module.sourceSet.javaDirectories.forEach(::addDirectory)
            module.sourceSet.kotlinDirectories.forEach(::addDirectory)
            module.sourceSet.resourceDirectories.forEach(::addDirectory)
            module.sourceSet.testJavaDirectories.forEach(::addDirectory)
            module.sourceSet.testKotlinDirectories.forEach(::addDirectory)
            module.sourceSet.testResourceDirectories.forEach(::addDirectory)
            addFile(module.sourceSet.manifest, 0)
        }
        return VerifiedInputManifest(projectRoot, entries.values.toList())
    }

    private fun appendManifestSnapshots(
        manifest: VerifiedInputManifest,
        output: StringBuilder
    ) {
        // Recheck every path before the first digest so all metadata/path
        // validation is complete before any input content is read.
        manifest.entries.forEach { entry ->
            val stable = validateModeledPath(manifest.projectRoot, entry.file, expectedDirectory = false)
            if (!matchesManifestMetadata(stable.attributes, entry)) {
                fail("BUILD_INPUT_CHANGED")
            }
        }
        manifest.entries.sortedBy { it.file.path }.forEach { entry ->
            val digest = contentDigest(manifest.projectRoot, entry)
            output.append("file=").append(entry.file.path).append('|')
                .append(entry.size).append('|')
                .append(digest)
                .append('\n')
        }
    }

    private fun fail(code: String): Nothing = throw InputManifestFailure(code)

    private fun contentDigest(
        projectRoot: ProjectRootPaths,
        entry: InputManifestEntry
    ): String {
        val stableBefore = validateModeledPath(projectRoot, entry.file, expectedDirectory = false)
        if (!matchesManifestMetadata(stableBefore.attributes, entry)) {
            fail("BUILD_INPUT_CHANGED")
        }
        val path = stableBefore.canonical
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesRead = 0L
        try {
            FileChannel.open(
                path,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            ).use { channel ->
                val buffer = ByteBuffer.allocate(CONTENT_BUFFER_BYTES)
                while (true) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    bytesRead += read.toLong()
                    if (bytesRead > entry.size) fail("BUILD_INPUT_CHANGED")
                    buffer.flip()
                    digest.update(buffer)
                }
            }
        } catch (failure: InputManifestFailure) {
            throw failure
        } catch (_: Exception) {
            val stableAfterFailure = validateModeledPath(
                projectRoot,
                entry.file,
                expectedDirectory = false
            )
            if (!matchesManifestMetadata(stableAfterFailure.attributes, entry)) {
                fail("BUILD_INPUT_CHANGED")
            }
            fail("BUILD_INPUT_CHANGED")
        }

        val stableAfter = validateModeledPath(projectRoot, entry.file, expectedDirectory = false)
        if (!matchesManifestMetadata(stableAfter.attributes, entry) ||
            bytesRead != entry.size
        ) {
            fail("BUILD_INPUT_CHANGED")
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun matchesManifestMetadata(
        attributes: BasicFileAttributes,
        entry: InputManifestEntry
    ): Boolean {
        val creationTimeMillis = runCatching {
            attributes.creationTime().toMillis()
        }.getOrNull()
        return attributes.isRegularFile &&
            attributes.size() == entry.size &&
            attributes.lastModifiedTime().toMillis() == entry.lastModifiedTimeMillis &&
            (entry.creationTimeMillis == null || creationTimeMillis == entry.creationTimeMillis) &&
            (entry.fileKey == null || attributes.fileKey() == entry.fileKey)
    }
}
