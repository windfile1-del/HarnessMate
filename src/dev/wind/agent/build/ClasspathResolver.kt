package dev.wind.agent.build

import java.io.File
import java.nio.file.Files

enum class ClasspathEntryKind {
    ANDROID_PLATFORM,
    PROJECT_OUTPUT,
    PROJECT_LIBRARY,
    BUNDLED_ARTIFACT,
    OFFLINE_CACHE
}

data class ClasspathEntry(
    val file: File,
    val kind: ClasspathEntryKind,
    val coordinate: String? = null
) {
    init {
        require(file.path.isNotBlank()) { "CLASSPATH_ENTRY_PATH_MISSING" }
    }
}

data class ClasspathResolution(
    val moduleName: String,
    val entries: List<ClasspathEntry> = emptyList(),
    val diagnostics: List<BuildDiagnostic> = emptyList()
) {
    init {
        require(moduleName.isNotBlank()) { "CLASSPATH_MODULE_NAME_MISSING" }
    }

    val success: Boolean
        get() = diagnostics.none { it.severity == BuildDiagnosticSeverity.ERROR }
}

/**
 * A platform provider is deliberately local-only. The provider owns the
 * approved roots so a resolver can reject a returned path that escapes them.
 */
interface AndroidPlatformProvider {
    val approvedRoots: List<File>

    fun resolve(compileSdk: Int): File?
}

/**
 * LocalArtifactStore represents bundled resources and an explicitly approved
 * offline cache. It has no network or Maven API by design.
 */
data class LocalArtifact(
    val file: File,
    val kind: ClasspathEntryKind,
    val coordinate: String
) {
    init {
        require(file.path.isNotBlank()) { "LOCAL_ARTIFACT_PATH_MISSING" }
        require(coordinate.isNotBlank()) { "LOCAL_ARTIFACT_COORDINATE_MISSING" }
        require(
            kind == ClasspathEntryKind.BUNDLED_ARTIFACT ||
                kind == ClasspathEntryKind.OFFLINE_CACHE
        ) { "LOCAL_ARTIFACT_KIND_INVALID" }
    }
}

interface LocalArtifactStore {
    val approvedRoots: List<File>

    fun resolve(notation: String): LocalArtifact?
}

/** An offline store that resolves one exact locked graph for all direct roots. */
interface GraphLocalArtifactStore : LocalArtifactStore {
    fun resolveGraph(rootNotations: List<String>): List<LocalArtifact>?
}

interface DependencyIndex {
    fun find(projectPath: String): EmbeddedModule?
}

class ProjectModelDependencyIndex(
    private val project: EmbeddedProjectModel
) : DependencyIndex {
    override fun find(projectPath: String): EmbeddedModule? = project.module(projectPath)
}

/**
 * Resolves only artifacts already present on local, approved roots. It never
 * evaluates Gradle, invokes a process, or opens a network/Maven client.
 */
class ClasspathResolver(
    private val androidPlatformProvider: AndroidPlatformProvider?,
    private val localArtifactStore: LocalArtifactStore?,
    private val dependencyIndex: DependencyIndex
) {
    fun resolve(project: EmbeddedProjectModel, moduleName: String): ClasspathResolution {
        val module = project.module(moduleName)
            ?: return failure(
                moduleName,
                "DEPENDENCY_UNAVAILABLE",
                "Unknown project module: " + moduleName
            )
        return resolve(project, module)
    }

    fun resolve(project: EmbeddedProjectModel, module: EmbeddedModule): ClasspathResolution {
        val diagnostics = mutableListOf<BuildDiagnostic>()
        val candidates = mutableListOf<ClasspathEntry>()
        val projectRoot = canonical(project.root, diagnostics, "project root")
        if (projectRoot == null) {
            return ClasspathResolution(module.name, diagnostics = diagnostics)
        }
        if (!project.supported) {
            diagnostics += dependencyDiagnostic(
                "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                "Cannot resolve classpath for an unsupported project model",
                project.root
            )
            return ClasspathResolution(module.name, diagnostics = diagnostics)
        }
        val moduleDirectory = canonical(module.directory, diagnostics, "module directory")
        if (moduleDirectory == null || !isBelow(projectRoot, moduleDirectory)) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_PATH_UNSAFE",
                "Module directory is outside the project root: " + module.name,
                module.directory
            )
            return ClasspathResolution(module.name, diagnostics = diagnostics)
        }

        if (module.kind != EmbeddedModuleKind.JVM) {
            resolveAndroidPlatform(module, candidates, diagnostics)
        }
        resolveProjectLibraries(module, projectRoot, candidates, diagnostics)

        val graphStore = localArtifactStore as? GraphLocalArtifactStore
        module.dependencies
            .sortedWith(compareBy<EmbeddedDependency> { it.configuration }.thenBy { it.notation })
            .forEach { dependency ->
                if (dependency.projectPath != null) {
                    resolveProjectDependency(
                        dependency,
                        projectRoot,
                        candidates,
                        diagnostics
                    )
                } else if (graphStore == null) {
                    resolveExternalDependency(dependency, candidates, diagnostics)
                }
            }
        if (graphStore != null) {
            val roots = module.dependencies.filter { it.projectPath == null }.map { it.notation }
            if (roots.isNotEmpty()) {
                resolveExternalGraph(graphStore, roots, candidates, diagnostics)
            }
        }

        val hasErrors = diagnostics.any { it.severity == BuildDiagnosticSeverity.ERROR }
        val distinctDiagnostics = diagnostics.distinctBy { Triple(it.code, it.message, it.path) }
        if (hasErrors) {
            return ClasspathResolution(
                moduleName = module.name,
                entries = emptyList(),
                diagnostics = distinctDiagnostics
            )
        }
        val graphOrder = if (graphStore != null) candidates.withIndex()
            .filter { it.value.kind == ClasspathEntryKind.OFFLINE_CACHE }
            .associate { it.value.file.canonicalPath to it.index }
        else emptyMap()
        val entries = candidates
            .groupBy { it.file.canonicalPath }
            .values
            .map { group ->
                group.minWithOrNull(
                    compareBy<ClasspathEntry> { kindPriority(it.kind) }
                        .thenBy { it.coordinate ?: "" }
                )!!
            }
            .sortedWith(
                compareBy<ClasspathEntry> { kindPriority(it.kind) }
                    .thenBy { graphOrder[it.file.canonicalPath] ?: Int.MAX_VALUE }
                    .thenBy { it.file.canonicalPath }
                    .thenBy { it.coordinate ?: "" }
            )
        return ClasspathResolution(
            moduleName = module.name,
            entries = entries,
            diagnostics = distinctDiagnostics
        )
    }

    private fun resolveAndroidPlatform(
        module: EmbeddedModule,
        candidates: MutableList<ClasspathEntry>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val provider = androidPlatformProvider
        val approvedRoots = provider?.let {
            try {
                it.approvedRoots
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
        if (provider == null || approvedRoots.isEmpty()) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "No approved Android platform provider is available for " + module.name,
                null
            )
            return
        }
        val compileSdk = module.compileSdk
        if (compileSdk == null) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Android module has no compileSdk: " + module.name,
                module.buildScript
            )
            return
        }
        val file = runCatching { provider.resolve(compileSdk) }.getOrElse { error ->
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Android platform provider failed: " + (error.message ?: "provider error"),
                null
            )
            null
        }
        if (file == null) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "android.jar is unavailable for compileSdk " + compileSdk,
                null
            )
            return
        }
        addFile(
            file = file,
            kind = ClasspathEntryKind.ANDROID_PLATFORM,
            coordinate = "android-" + compileSdk,
            approvedRoots = approvedRoots,
            candidates = candidates,
            diagnostics = diagnostics
        )
    }

    private fun resolveProjectLibraries(
        module: EmbeddedModule,
        projectRoot: File,
        candidates: MutableList<ClasspathEntry>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val libsDirectory = File(module.directory, "libs")
        if (!libsDirectory.exists()) return
        if (!libsDirectory.isDirectory || Files.isSymbolicLink(libsDirectory.toPath())) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_PATH_UNSAFE",
                "Project libs directory is not a regular directory: " + libsDirectory.path,
                libsDirectory
            )
            return
        }
        val files = libsDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        files.forEach { file ->
            if (Files.isSymbolicLink(file.toPath())) {
                diagnostics += dependencyDiagnostic(
                    "DEPENDENCY_PATH_UNSAFE",
                    "Project library is a symbolic link: " + file.path,
                    file
                )
            } else {
                addFile(
                    file = file,
                    kind = ClasspathEntryKind.PROJECT_LIBRARY,
                    coordinate = file.name,
                    approvedRoots = listOf(projectRoot),
                    candidates = candidates,
                    diagnostics = diagnostics
                )
            }
        }
    }

    private fun resolveProjectDependency(
        dependency: EmbeddedDependency,
        projectRoot: File,
        candidates: MutableList<ClasspathEntry>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val projectPath = dependency.projectPath ?: return
        val target = runCatching { dependencyIndex.find(projectPath) }.getOrElse { error ->
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Project dependency index failed: " + (error.message ?: "index error"),
                null
            )
            null
        }
        if (target == null) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Project dependency is not present in the static model: " + projectPath,
                null
            )
            return
        }
        val targetDirectory = canonical(target.directory, diagnostics, "project dependency")
        if (targetDirectory == null || !isBelow(projectRoot, targetDirectory)) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_PATH_UNSAFE",
                "Project dependency escapes the project root: " + projectPath,
                target.directory
            )
            return
        }
        val outputs = target.outputDirectories
            .mapNotNull { canonical(it, diagnostics, "project output") }
            .filter { it.isDirectory }
            .sortedBy { it.canonicalPath }
        if (outputs.isEmpty()) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "No compiled output exists for project dependency: " + projectPath,
                target.directory
            )
            return
        }
        outputs.forEach { output ->
            addFile(
                file = output,
                kind = ClasspathEntryKind.PROJECT_OUTPUT,
                coordinate = projectPath,
                approvedRoots = listOf(projectRoot),
                candidates = candidates,
                diagnostics = diagnostics,
                requireDirectory = true
            )
        }
    }

    private fun resolveExternalDependency(
        dependency: EmbeddedDependency,
        candidates: MutableList<ClasspathEntry>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val store = localArtifactStore
        val approvedRoots = store?.let {
            try {
                it.approvedRoots
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
        if (store == null || approvedRoots.isEmpty()) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "No approved offline artifact store is available for " + dependency.notation,
                null
            )
            return
        }
        val artifact = runCatching { store.resolve(dependency.notation) }.getOrElse { error ->
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Offline artifact store failed: " + (error.message ?: "store error"),
                null
            )
            null
        }
        if (artifact == null) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Offline artifact is unavailable: " + dependency.notation,
                null
            )
            return
        }
        addFile(
            file = artifact.file,
            kind = artifact.kind,
            coordinate = artifact.coordinate,
            approvedRoots = approvedRoots,
            candidates = candidates,
            diagnostics = diagnostics
        )
    }

    private fun resolveExternalGraph(
        store: GraphLocalArtifactStore,
        roots: List<String>,
        candidates: MutableList<ClasspathEntry>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val approvedRoots = runCatching { store.approvedRoots }.getOrElse { emptyList() }
        if (approvedRoots.isEmpty()) {
            diagnostics += dependencyDiagnostic("DEPENDENCY_UNAVAILABLE",
                "No approved offline Maven cache is available", null)
            return
        }
        val artifacts = runCatching { store.resolveGraph(roots) }.getOrElse { error ->
            diagnostics += dependencyDiagnostic("DEPENDENCY_UNAVAILABLE",
                "Locked Maven graph failed: " + (error.message ?: "store error"), null)
            null
        }
        if (artifacts == null || artifacts.isEmpty() ||
            !roots.all { root -> artifacts.any { it.coordinate == root } } ||
            artifacts.map { it.coordinate }.distinct().size != artifacts.size) {
            diagnostics += dependencyDiagnostic("DEPENDENCY_UNAVAILABLE",
                "Exact locked Maven graph is unavailable", null)
            return
        }
        artifacts.forEach { artifact ->
            addFile(artifact.file, artifact.kind, artifact.coordinate,
                approvedRoots, candidates, diagnostics)
        }
    }

    private fun addFile(
        file: File,
        kind: ClasspathEntryKind,
        coordinate: String?,
        approvedRoots: List<File>,
        candidates: MutableList<ClasspathEntry>,
        diagnostics: MutableList<BuildDiagnostic>,
        requireDirectory: Boolean = false
    ) {
        val canonical = canonical(file, diagnostics, "classpath entry") ?: return
        val validType = if (requireDirectory) canonical.isDirectory else canonical.isFile
        if (!validType) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_UNAVAILABLE",
                "Classpath entry is not a " +
                    (if (requireDirectory) "directory" else "regular file") +
                    ": " + file.path,
                file
            )
            return
        }
        val roots = approvedRoots.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }
        if (roots.isEmpty() || roots.none { isBelow(it, canonical) }) {
            diagnostics += dependencyDiagnostic(
                "DEPENDENCY_PATH_UNSAFE",
                "Classpath entry is outside its approved local roots: " + file.path,
                canonical
            )
            return
        }
        candidates += ClasspathEntry(canonical, kind, coordinate)
    }

    private fun canonical(
        file: File,
        diagnostics: MutableList<BuildDiagnostic>,
        label: String
    ): File? = try {
        file.canonicalFile
    } catch (error: Exception) {
        diagnostics += dependencyDiagnostic(
            "DEPENDENCY_PATH_UNSAFE",
            "Could not canonicalize " + label + ": " + (error.message ?: file.path),
            file
        )
        null
    }

    private fun failure(moduleName: String, code: String, message: String) =
        ClasspathResolution(
            moduleName = moduleName,
            diagnostics = listOf(dependencyDiagnostic(code, message, null))
        )

    private fun dependencyDiagnostic(code: String, message: String, file: File?) =
        BuildDiagnostic(
            severity = BuildDiagnosticSeverity.ERROR,
            code = code,
            message = message,
            source = BuildDiagnosticSource.DEPENDENCY,
            path = file?.path
        )

    private fun kindPriority(kind: ClasspathEntryKind): Int = when (kind) {
        ClasspathEntryKind.ANDROID_PLATFORM -> 0
        ClasspathEntryKind.PROJECT_OUTPUT -> 1
        ClasspathEntryKind.PROJECT_LIBRARY -> 2
        ClasspathEntryKind.BUNDLED_ARTIFACT -> 3
        ClasspathEntryKind.OFFLINE_CACHE -> 4
    }

    private fun isBelow(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar)
        val candidatePath = candidate.canonicalPath
        return candidatePath == rootPath ||
            candidatePath.startsWith(rootPath + File.separator)
    }
}
