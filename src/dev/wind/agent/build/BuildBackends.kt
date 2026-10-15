package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File

/** Explicit opt-in inputs for the P6-BE4 core-library desugaring slice. */
data class EmbeddedCoreLibraryDesugaringSupport(
    val configurationJson: String,
    val programFiles: List<File>,
    val l8Compiler: L8RuntimeDexCompiler
)

/** Explicit opt-in capability for P6-BE5 APK assembly and alignment. */
data class EmbeddedApkPackagingSupport(
    val alignmentProvider: ApkAlignmentProvider
)

/** Opt-in Android Kotlin-only compiler and its verified app-private stdlib compile classpath. */
data class EmbeddedKotlinCompilationSupport(
    val compiler: KotlinCompilationProvider,
    val stdlibJar: File,
    val packageStdlibInTarget: Boolean = false
)

/** Provides static plans and delegates the supported execution slice. */
class EmbeddedBuildBackend internal constructor(
    private val executor: EmbeddedBuildExecutor
) : BuildBackend {
    constructor() : this(EmbeddedBuildExecutor())
    constructor(bootClasspath: () -> List<File>) :
        this(EmbeddedBuildExecutor(bootClasspath = bootClasspath))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler
    ))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler,
        d8Compiler: D8DexCompiler
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler,
        d8Compiler = d8Compiler
    ))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler,
        d8Compiler: D8DexCompiler,
        coreLibraryDesugaring: EmbeddedCoreLibraryDesugaringSupport
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler,
        d8Compiler = d8Compiler,
        coreLibraryDesugaring = coreLibraryDesugaring
    ))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler,
        d8Compiler: D8DexCompiler,
        apkPackaging: EmbeddedApkPackagingSupport
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler,
        d8Compiler = d8Compiler,
        apkPackaging = apkPackaging
    ))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler,
        d8Compiler: D8DexCompiler,
        coreLibraryDesugaring: EmbeddedCoreLibraryDesugaringSupport,
        apkPackaging: EmbeddedApkPackagingSupport
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler,
        d8Compiler = d8Compiler,
        coreLibraryDesugaring = coreLibraryDesugaring,
        apkPackaging = apkPackaging
    ))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler,
        d8Compiler: D8DexCompiler,
        kotlinCompilation: EmbeddedKotlinCompilationSupport
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler,
        d8Compiler = d8Compiler,
        kotlinCompilation = kotlinCompilation
    ))
    constructor(
        bootClasspath: () -> List<File>,
        aapt2Compiler: Aapt2ResourceCompiler,
        d8Compiler: D8DexCompiler,
        coreLibraryDesugaring: EmbeddedCoreLibraryDesugaringSupport,
        apkPackaging: EmbeddedApkPackagingSupport,
        kotlinCompilation: EmbeddedKotlinCompilationSupport
    ) : this(EmbeddedBuildExecutor(
        bootClasspath = bootClasspath,
        aapt2Compiler = aapt2Compiler,
        d8Compiler = d8Compiler,
        coreLibraryDesugaring = coreLibraryDesugaring,
        apkPackaging = apkPackaging,
        kotlinCompilation = kotlinCompilation
    ))

    override val id: BuildBackendId = BuildBackendId.EMBEDDED

    companion object {
        /** An explicit offline cache and existing providers; no implicit download or activation. */
        fun withMavenDependencies(
            dependencies: EmbeddedMavenDependencySupport,
            bootClasspath: () -> List<File> = { emptyList() },
            aapt2Compiler: Aapt2ResourceCompiler? = null,
            d8Compiler: D8DexCompiler? = null,
            coreLibraryDesugaring: EmbeddedCoreLibraryDesugaringSupport? = null,
            apkPackaging: EmbeddedApkPackagingSupport? = null,
            kotlinCompilation: EmbeddedKotlinCompilationSupport? = null
        ): EmbeddedBuildBackend = EmbeddedBuildBackend(EmbeddedBuildExecutor(
            bootClasspath = bootClasspath, aapt2Compiler = aapt2Compiler, d8Compiler = d8Compiler,
            coreLibraryDesugaring = coreLibraryDesugaring, apkPackaging = apkPackaging,
            kotlinCompilation = kotlinCompilation, mavenDependencies = dependencies
        ))
    }

    override fun supports(project: EmbeddedProjectModel): Boolean = project.supported

    override fun plan(context: BuildContext): BuildPlan {
        if (!supports(context.project)) {
            return BuildPlan(
                backend = id,
                tasks = emptyList(),
                execution = BuildExecutionInputs.from(context),
                diagnostics = context.project.diagnostics.ifEmpty {
                    listOf(BuildDiagnostic(
                        BuildDiagnosticSeverity.ERROR,
                        "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                        context.project.unsupportedReasons.joinToString(", ")
                            .ifBlank { "Project model is unsupported" },
                        BuildDiagnosticSource.PROJECT
                    ))
                }
            )
        }

        val tasks = mutableListOf<BuildTaskPlan>()
        tasks += BuildTaskPlan("resolve-project", "Resolve static Embedded Project Model")
        val orderedModules = runCatching { context.project.topologicallyOrderedModules() }
            .getOrElse {
                return BuildPlan(
                    backend = id,
                    tasks = emptyList(),
                    execution = BuildExecutionInputs.from(context),
                    diagnostics = listOf(BuildDiagnostic(
                        BuildDiagnosticSeverity.ERROR,
                        "EMBEDDED_PROJECT_DEPENDENCY_CYCLE",
                        "Project dependency graph contains a cycle",
                        BuildDiagnosticSource.PROJECT
                    ))
                )
        }
        for (module in orderedModules) {
            val suffix = moduleTaskSuffix(module)
            val resolve = "resolve-classpath:$suffix"
            val dependencyEdges = module.dependencies.mapNotNull { dependency ->
                dependency.projectPath?.let(context.project::module)
            }.flatMap(::compileTaskIds)
            tasks += BuildTaskPlan(
                resolve,
                "Resolve offline classpath for ${module.name}",
                (listOf("resolve-project") + dependencyEdges).distinct()
            )
            val discoveredCompileSources = compileSourceTaskId(module)
            if (module.kind == EmbeddedModuleKind.JVM && discoveredCompileSources != null) {
                val description = when {
                    module.sourceSet.javaDirectories.isNotEmpty() &&
                        module.sourceSet.kotlinDirectories.isNotEmpty() ->
                        "Compile Java and Kotlin sources for ${module.name}"
                    module.sourceSet.javaDirectories.isNotEmpty() ->
                        "Compile Java sources for ${module.name}"
                    else -> "Compile Kotlin sources for ${module.name}"
                }
                tasks += BuildTaskPlan(discoveredCompileSources, description, listOf(resolve))
            }
            if (module.kind != EmbeddedModuleKind.JVM) {
                val compileSources = discoveredCompileSources ?: "compile-java:$suffix"
                val compileResources = "compile-resources:$suffix"
                val linkResources = "link-resources:$suffix"
                tasks += BuildTaskPlan(compileResources, "Compile Android resources for ${module.name}", listOf(resolve))
                tasks += BuildTaskPlan(linkResources, "Link Android resources for ${module.name}", listOf(compileResources))
                val compileDescription = when (compileSources.substringBefore(':')) {
                    "compile-mixed" -> "Compile Java and Kotlin sources for ${module.name}"
                    "compile-kotlin" -> "Compile Kotlin sources for ${module.name}"
                    else -> "Compile Java sources for ${module.name}"
                }
                tasks += BuildTaskPlan(compileSources, compileDescription, listOf(linkResources))
                val dexDeps = listOf(compileSources)
                tasks += BuildTaskPlan("dex:$suffix", "Convert classes to DEX for ${module.name}", dexDeps)
                tasks += BuildTaskPlan("package-apk:$suffix", "Package unsigned APK for ${module.name}", listOf(linkResources, "dex:$suffix"))
                tasks += BuildTaskPlan("sign-apk:$suffix", "Sign APK for ${module.name}", listOf("package-apk:$suffix"))
            }
            if (module.sourceSet.testJavaDirectories.isNotEmpty() || module.sourceSet.testKotlinDirectories.isNotEmpty()) {
                val dependencies = tasks.filter { it.id.endsWith(":$suffix") }.map { it.id }
                tasks += BuildTaskPlan("run-tests:$suffix", "Run JVM tests for ${module.name}", dependencies)
            }
        }
        return BuildPlan(
            backend = id,
            tasks = tasks,
            execution = BuildExecutionInputs.from(context)
        )
    }

    override fun execute(context: BuildContext, plan: BuildPlan, token: CancellationToken): BuildResult {
        val currentPlan = runCatching { this.plan(context) }.getOrNull()
        if (currentPlan == null || plan != currentPlan) {
            val diagnostic = BuildDiagnostic(
                BuildDiagnosticSeverity.ERROR,
                "BUILD_PLAN_CONTEXT_MISMATCH",
                "Build plan does not match the current backend plan and execution context",
                BuildDiagnosticSource.PROJECT
            )
            return BuildResult(
                outcome = BuildOutcome.FAILED,
                diagnostics = listOf(diagnostic),
                taskResults = plan.tasks.map { task ->
                    BuildTaskResult(task.id, BuildTaskOutcome.SKIPPED)
                }
            )
        }
        return executor.execute(context, plan, token)
    }

    private fun compileTaskIds(module: EmbeddedModule): List<String> {
        val suffix = moduleTaskSuffix(module)
        return listOfNotNull("resolve-classpath:$suffix", compileSourceTaskId(module))
    }

    private fun compileSourceTaskId(module: EmbeddedModule): String? {
        val hasJava = module.sourceSet.javaDirectories.isNotEmpty()
        val hasKotlin = module.sourceSet.kotlinDirectories.isNotEmpty()
        val kind = when {
            hasJava && hasKotlin -> "compile-mixed"
            hasJava -> "compile-java"
            hasKotlin -> "compile-kotlin"
            else -> return null
        }
        return "$kind:${moduleTaskSuffix(module)}"
    }

}

/** UTF-8 hex is injective, so module paths can never collide in task IDs and output paths. */
internal fun moduleTaskSuffix(module: EmbeddedModule): String =
    module.gradlePath.toByteArray(Charsets.UTF_8)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** Explicit compatibility seam; P4 routing to it is a later B9 milestone. */
class ShellBuildBackend(
    private val executor: ((BuildPlan, CancellationToken) -> BuildResult)? = null
) : BuildBackend {
    override val id: BuildBackendId = BuildBackendId.SHELL

    /** Shell is never selected implicitly; callers must opt into this route. */
    override fun supports(project: EmbeddedProjectModel): Boolean = false

    override fun plan(context: BuildContext): BuildPlan = BuildPlan(
        backend = id,
        tasks = listOf(BuildTaskPlan("shell-compatibility", "Delegate to explicitly authorized shell provider")),
        execution = BuildExecutionInputs.from(context),
        diagnostics = listOf(BuildDiagnostic(
            BuildDiagnosticSeverity.INFO,
            "SHELL_COMPATIBILITY_BACKEND",
            "Shell execution is an explicit compatibility route; Gradle is not evaluated here",
            BuildDiagnosticSource.PROJECT
        ))
    )

    override fun execute(context: BuildContext, plan: BuildPlan, token: CancellationToken): BuildResult {
        if (plan.backend != id || plan.execution != BuildExecutionInputs.from(context)) {
            return BuildResult(
                outcome = BuildOutcome.FAILED,
                diagnostics = listOf(BuildDiagnostic(
                    BuildDiagnosticSeverity.ERROR,
                    "BUILD_PLAN_CONTEXT_MISMATCH",
                    "Build plan does not belong to this backend and execution context",
                    BuildDiagnosticSource.PROJECT
                ))
            )
        }
        if (token.isCancelled) {
            return BuildResult(
                outcome = BuildOutcome.CANCELLED,
                diagnostics = listOf(BuildDiagnostic(
                    BuildDiagnosticSeverity.ERROR,
                    "BUILD_CANCELLED",
                    "Shell compatibility build was cancelled before execution",
                    BuildDiagnosticSource.TOOLCHAIN
                ))
            )
        }
        val result = executor?.invoke(plan, token) ?: BuildResult(
            outcome = if (token.isCancelled) BuildOutcome.CANCELLED else BuildOutcome.UNSUPPORTED,
            diagnostics = listOf(BuildDiagnostic(
                BuildDiagnosticSeverity.ERROR,
                if (token.isCancelled) "BUILD_CANCELLED" else "SHELL_BACKEND_DELEGATE_REQUIRED",
                if (token.isCancelled) "Shell compatibility build was cancelled" else "No shell executor was supplied",
                BuildDiagnosticSource.TOOLCHAIN
            ))
        )
        return if (token.isCancelled && result.outcome == BuildOutcome.SUCCESS) {
            BuildResult(
                outcome = BuildOutcome.CANCELLED,
                diagnostics = result.diagnostics + BuildDiagnostic(
                    BuildDiagnosticSeverity.ERROR,
                    "BUILD_CANCELLED",
                    "Shell compatibility build was cancelled before completion",
                    BuildDiagnosticSource.TOOLCHAIN
                ),
                producedArtifacts = emptyList(),
                taskResults = result.taskResults,
                durationMs = result.durationMs
            )
        } else {
            result
        }
    }

}
