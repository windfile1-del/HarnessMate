package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Executes the deliberately small P6-BE1 JVM and P6-BE2/BE3/BE4 Android task slices. */
internal class EmbeddedBuildExecutor(
    private val javaCompiler: EcjJavaCompiler = EcjJavaCompiler(),
    private val bootClasspath: () -> List<File> = { emptyList() },
    private val aapt2Compiler: Aapt2ResourceCompiler? = null,
    private val d8Compiler: D8DexCompiler? = null,
    private val coreLibraryDesugaring: EmbeddedCoreLibraryDesugaringSupport? = null,
    private val apkPackaging: EmbeddedApkPackagingSupport? = null,
    private val kotlinCompilation: EmbeddedKotlinCompilationSupport? = null,
    private val mavenDependencies: EmbeddedMavenDependencySupport? = null
) {
    fun execute(
        context: BuildContext,
        plan: BuildPlan,
        token: CancellationToken
    ): BuildResult {
        val started = System.nanoTime()
        val taskResults = mutableListOf<BuildTaskResult>()
        val taskOutcomes = linkedMapOf<String, BuildTaskOutcome>()
        val artifacts = mutableListOf<BuildArtifact>()
        val diagnostics = mutableListOf<BuildDiagnostic>()

        fun finish(outcome: BuildOutcome): BuildResult = BuildResult(
            outcome = outcome,
            diagnostics = diagnostics,
            producedArtifacts = if (outcome == BuildOutcome.SUCCESS) artifacts else emptyList(),
            taskResults = taskResults,
            durationMs = elapsedMillis(started)
        )

        val expectedExecution = runCatching { BuildExecutionInputs.from(context) }.getOrNull()
        if (plan.backend != BuildBackendId.EMBEDDED || expectedExecution == null ||
            plan.execution != expectedExecution
        ) {
            val diagnostic = diagnostic(
                "BUILD_PLAN_CONTEXT_MISMATCH",
                "Build plan does not belong to this backend and execution context",
                BuildDiagnosticSource.PROJECT
            )
            diagnostics += diagnostic
            appendSkipped(plan.tasks, taskResults, taskOutcomes)
            return finish(BuildOutcome.FAILED)
        }

        if (token.isCancelled) {
            val diagnostic = diagnostic(
                "BUILD_CANCELLED",
                "Embedded build was cancelled before task execution",
                BuildDiagnosticSource.TOOLCHAIN
            )
            diagnostics += diagnostic
            appendCancelled(plan.tasks, taskResults, taskOutcomes, diagnostic)
            return finish(BuildOutcome.CANCELLED)
        }

        if (!context.project.supported && plan.tasks.isEmpty()) {
            diagnostics += plan.diagnostics.ifEmpty {
                context.project.diagnostics.ifEmpty {
                    listOf(diagnostic(
                        "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                        context.project.unsupportedReasons.joinToString(", ")
                            .ifBlank { "Project model is unsupported" },
                        BuildDiagnosticSource.PROJECT
                    ))
                }
            }
            return finish(BuildOutcome.UNSUPPORTED)
        }

        if (plan.tasks.isEmpty()) {
            val diagnostic = diagnostic(
                "BUILD_PLAN_EMPTY",
                "Supported build plan contains no executable tasks",
                BuildDiagnosticSource.PROJECT
            )
            diagnostics += diagnostic
            return finish(BuildOutcome.FAILED)
        }

        if (context.requestedTasks.isNotEmpty()) {
            val diagnostic = diagnostic(
                "EMBEDDED_REQUESTED_TASK_EXECUTION_UNSUPPORTED",
                "Embedded execution supports only the complete planned build; requested task selection is not supported",
                BuildDiagnosticSource.PROJECT
            )
            diagnostics += diagnostic
            appendFailureThenSkipped(
                plan.tasks,
                taskResults,
                taskOutcomes,
                diagnostic
            )
            return finish(BuildOutcome.UNSUPPORTED)
        }

        if (context.options.isNotEmpty()) {
            val diagnostic = diagnostic(
                "EMBEDDED_BUILD_OPTIONS_UNSUPPORTED",
                "Build options are not interpreted by the BE1/BE2/BE3 executor",
                BuildDiagnosticSource.PROJECT
            )
            diagnostics += diagnostic
            appendFailureThenSkipped(plan.tasks, taskResults, taskOutcomes, diagnostic)
            return finish(BuildOutcome.UNSUPPORTED)
        }

        val executionState = ExecutionState()
        var stoppedOutcome: BuildOutcome? = null
        for ((index, task) in plan.tasks.withIndex()) {
            if (stoppedOutcome != null) {
                appendSkipped(plan.tasks.drop(index), taskResults, taskOutcomes)
                break
            }
            if (token.isCancelled) {
                val cancelled = diagnostic(
                    "BUILD_CANCELLED",
                    "Embedded build was cancelled before ${task.id}",
                    BuildDiagnosticSource.TOOLCHAIN
                )
                diagnostics += cancelled
                appendCancelled(plan.tasks.drop(index), taskResults, taskOutcomes, cancelled)
                stoppedOutcome = BuildOutcome.CANCELLED
                break
            }

            val unavailableDependencies = task.dependsOn.filter {
                taskOutcomes[it] != BuildTaskOutcome.EXECUTED
            }
            val taskStarted = System.nanoTime()
            val step = if (unavailableDependencies.isNotEmpty()) {
                val error = diagnostic(
                    "BUILD_PLAN_DEPENDENCY_NOT_EXECUTED",
                    "Task ${task.id} depends on tasks that did not execute: ${unavailableDependencies.joinToString()}",
                    BuildDiagnosticSource.PROJECT
                )
                TaskStep(BuildTaskOutcome.FAILED, BuildOutcome.FAILED, listOf(error))
            } else {
                try {
                    executeTask(context, task.id, token, executionState)
                } catch (cancelled: CancellationToken.CancelledException) {
                    TaskStep(BuildTaskOutcome.CANCELLED, BuildOutcome.CANCELLED,
                        listOf(diagnostic("BUILD_CANCELLED", "Dependency verification was cancelled", BuildDiagnosticSource.DEPENDENCY)))
                } catch (cancelled: CancellationException) {
                    val error = diagnostic(
                        "BUILD_CANCELLED",
                        cancelled.message ?: "Embedded build was cancelled",
                        BuildDiagnosticSource.TOOLCHAIN
                    )
                    TaskStep(BuildTaskOutcome.CANCELLED, BuildOutcome.CANCELLED, listOf(error))
                } catch (problem: ExecutionProblem) {
                    TaskStep(
                        if (problem.outcome == BuildOutcome.UNSUPPORTED ||
                            problem.outcome == BuildOutcome.RESOURCE_LIMIT
                        ) BuildTaskOutcome.SKIPPED else BuildTaskOutcome.FAILED,
                        problem.outcome,
                        listOf(problem.diagnostic)
                    )
                } catch (error: Exception) {
                    val dependencyCode = error.message?.takeIf {
                        it.matches(Regex("(?:MAVEN_[A-Z_]+|DEPENDENCY_UNAVAILABLE)"))
                    }
                    val diagnostic = diagnostic(
                        dependencyCode ?: "EMBEDDED_BUILD_EXECUTION_FAILED",
                        error.javaClass.simpleName + ": " + (error.message ?: "task execution failed"),
                        if (dependencyCode != null) BuildDiagnosticSource.DEPENDENCY else BuildDiagnosticSource.TOOLCHAIN
                    )
                    TaskStep(BuildTaskOutcome.FAILED, BuildOutcome.FAILED, listOf(diagnostic))
                }
            }

            taskOutcomes[task.id] = step.taskOutcome
            diagnostics += step.diagnostics
            taskResults += BuildTaskResult(
                id = task.id,
                outcome = step.taskOutcome,
                durationMs = elapsedMillis(taskStarted),
                diagnostics = step.diagnostics
            )
            artifacts += step.artifacts
            if (step.buildOutcome != null) stoppedOutcome = step.buildOutcome
        }

        return finish(stoppedOutcome ?: BuildOutcome.SUCCESS)
    }

    private fun executeTask(
        context: BuildContext,
        taskId: String,
        token: CancellationToken,
        state: ExecutionState
    ): TaskStep = when {
        taskId == "resolve-project" -> resolveProject(context, state)
        taskId.startsWith("resolve-classpath:") -> resolveClasspath(context, taskId, token, state)
        taskId.startsWith("compile-java:") -> compileJava(context, taskId, token, state)
        taskId.startsWith("compile-kotlin:") -> compileKotlin(context, taskId, token, state)
        taskId.startsWith("compile-resources:") -> compileAndroidResources(context, taskId, token)
        taskId.startsWith("link-resources:") -> linkAndroidResources(context, taskId, token, state)
        taskId.startsWith("compile-mixed:") -> unsupported(
            "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED",
            "Kotlin and mixed-language compilation are outside the BE2 execution slice",
            BuildDiagnosticSource.KOTLIN
        )
        taskId.startsWith("run-tests:") -> unsupported(
            "EMBEDDED_TEST_EXECUTION_UNSUPPORTED",
            "Test source compilation and execution are outside the BE2 execution slice",
            BuildDiagnosticSource.TEST
        )
        taskId.startsWith("dex:") -> dexAndroidClasses(context, taskId, token, state)
        taskId.startsWith("package-apk:") -> packageAndroidApk(context, taskId, token, state)
        taskId.startsWith("sign-apk:") -> unsupported(
            "EMBEDDED_APK_SIGNING_UNSUPPORTED",
            "APK signing is outside the current BE5 packaging slice",
            BuildDiagnosticSource.SIGNING
        )
        else -> unsupported(
            "EMBEDDED_BUILD_TASK_EXECUTION_UNSUPPORTED",
            "Build task $taskId is not supported by the BE1/BE2/BE3/BE4 executor",
            BuildDiagnosticSource.TOOLCHAIN
        )
    }

    private fun resolveProject(context: BuildContext, state: ExecutionState): TaskStep {
        val project = context.project
        if (!project.supported) {
            val errors = project.diagnostics.filter { it.severity == BuildDiagnosticSeverity.ERROR }
            return TaskStep(
                BuildTaskOutcome.FAILED,
                BuildOutcome.UNSUPPORTED,
                errors.ifEmpty {
                    listOf(diagnostic(
                        "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                        project.unsupportedReasons.joinToString(", ").ifBlank { "Project model is unsupported" },
                        BuildDiagnosticSource.PROJECT
                    ))
                }
            )
        }

        val modules = project.modules
        val androidModule = modules.firstOrNull { it.kind != EmbeddedModuleKind.JVM }
        if (androidModule != null) {
            if (modules.size != 1) return unsupported(
                "EMBEDDED_MULTI_MODULE_EXECUTION_UNSUPPORTED",
                "BE2 supports one Android application module only",
                BuildDiagnosticSource.PROJECT
            )
            if (androidModule.kind == EmbeddedModuleKind.ANDROID_LIBRARY) return unsupported(
                "EMBEDDED_ANDROID_LIBRARY_EXECUTION_UNSUPPORTED",
                "Android library modules are outside the BE2 execution slice",
                BuildDiagnosticSource.PROJECT
            )
            if (androidModule.kind != EmbeddedModuleKind.ANDROID_APPLICATION) return unsupported(
                "EMBEDDED_ANDROID_EXECUTION_UNSUPPORTED",
                "Unsupported Android module kind for ${androidModule.gradlePath}",
                BuildDiagnosticSource.PROJECT
            )

            val kotlinModule = modules.firstOrNull { module ->
                module.kotlinPlugin || module.sourceSet.kotlinDirectories.isNotEmpty() ||
                    module.pluginDeclarations.any { it.id.contains("kotlin", ignoreCase = true) }
            }
            if (kotlinModule != null) {
                val kotlinOnly = kotlinModule.sourceSet.kotlinDirectories.isNotEmpty() &&
                    kotlinModule.sourceSet.javaDirectories.isEmpty()
                if (!kotlinOnly || kotlinCompilation == null) return unsupported(
                    "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED",
                    "Android Kotlin-only compilation requires an explicitly supplied in-process compiler; mixed compilation is not enabled",
                    BuildDiagnosticSource.KOTLIN
                )
            }

            val testModule = modules.firstOrNull { module ->
                module.sourceSet.testJavaDirectories.isNotEmpty() ||
                    module.sourceSet.testKotlinDirectories.isNotEmpty() ||
                    module.sourceSet.testResourceDirectories.isNotEmpty()
            }
            if (testModule != null) return unsupported(
                "EMBEDDED_TEST_EXECUTION_UNSUPPORTED",
                "Android test sources are outside the BE2 execution slice",
                BuildDiagnosticSource.TEST
            )

            if (androidModule.compileSdk != 35) return unsupported(
                "EMBEDDED_ANDROID_COMPILE_SDK_UNAVAILABLE",
                "BE2 requires packaged Android compileSdk 35",
                BuildDiagnosticSource.PROJECT
            )
            if (androidModule.minSdk == null || androidModule.targetSdk == null) return unsupported(
                "EMBEDDED_ANDROID_SDK_CONFIGURATION_UNSUPPORTED",
                "BE2 requires declared minSdk and targetSdk values",
                BuildDiagnosticSource.PROJECT
            )
            if (androidModule.sourceSet.manifest == null) return unsupported(
                "EMBEDDED_ANDROID_MANIFEST_REQUIRED",
                "Android application module must have src/main/AndroidManifest.xml",
                BuildDiagnosticSource.PROJECT
            )
            when (androidModule.sourceSet.resourceDirectories.size) {
                0 -> return unsupported(
                    "EMBEDDED_ANDROID_RESOURCES_REQUIRED",
                    "Android application module must have exactly one src/main/res directory",
                    BuildDiagnosticSource.PROJECT
                )
                1 -> Unit
                else -> return unsupported(
                    "EMBEDDED_ANDROID_RESOURCE_LAYOUT_UNSUPPORTED",
                    "BE2 supports exactly one Android main resource directory",
                    BuildDiagnosticSource.PROJECT
                )
            }

            state.androidJar = resolveAndroidPlatformJar() ?: return unsupported(
                "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE",
                "BE2 requires exactly one ordinary, non-empty android.jar on the supplied boot classpath",
                BuildDiagnosticSource.TOOLCHAIN
            )
            return TaskStep(BuildTaskOutcome.EXECUTED)
        }

        val kotlinModule = modules.firstOrNull { module ->
            module.kotlinPlugin || module.sourceSet.kotlinDirectories.isNotEmpty() ||
                module.pluginDeclarations.any { it.id.contains("kotlin", ignoreCase = true) }
        }
        if (kotlinModule != null) return unsupported(
            "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED",
            "Kotlin module ${kotlinModule.gradlePath} is outside the embedded Java-only execution slice",
            BuildDiagnosticSource.KOTLIN
        )

        val testModule = modules.firstOrNull { module ->
            module.sourceSet.testJavaDirectories.isNotEmpty() ||
                module.sourceSet.testKotlinDirectories.isNotEmpty() ||
                module.sourceSet.testResourceDirectories.isNotEmpty()
        }
        if (testModule != null) return unsupported(
            "EMBEDDED_TEST_EXECUTION_UNSUPPORTED",
            "Test sources in ${testModule.gradlePath} are outside the embedded execution slice",
            BuildDiagnosticSource.TEST
        )

        val resourceModule = modules.firstOrNull {
            it.kind == EmbeddedModuleKind.JVM && it.sourceSet.resourceDirectories.isNotEmpty()
        }
        if (resourceModule != null) return unsupported(
            "EMBEDDED_RESOURCE_EXECUTION_UNSUPPORTED",
            "Resource processing in ${resourceModule.gradlePath} is outside the BE1 execution slice",
            BuildDiagnosticSource.PROJECT
        )

        if (modules.size > 1 && modules.none { module ->
                module.dependencies.any { it.projectPath != null }
            }
        ) return unsupported(
            "EMBEDDED_MULTI_MODULE_EXECUTION_UNSUPPORTED",
            "BE1 supports a single JVM module only",
            BuildDiagnosticSource.PROJECT
        )

        return TaskStep(BuildTaskOutcome.EXECUTED)
    }

    private fun resolveClasspath(context: BuildContext, taskId: String, token: CancellationToken, state: ExecutionState): TaskStep {
        val module = moduleForTask(context, taskId, "resolve-classpath:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        val projectDependencies = module.dependencies.filter { it.projectPath != null }
        if (projectDependencies.isNotEmpty()) return unsupported(
            "EMBEDDED_PROJECT_DEPENDENCY_EXECUTION_UNSUPPORTED",
            "Project dependencies are not resolved by the embedded executor: ${projectDependencies.joinToString { it.notation }}",
            BuildDiagnosticSource.DEPENDENCY
        )
        if (module.dependencies.isNotEmpty() && mavenDependencies == null) return unsupported(
            "EMBEDDED_EXTERNAL_DEPENDENCY_UNSUPPORTED",
            "External dependencies are not resolved by the embedded executor: ${module.dependencies.joinToString { it.notation }}",
            BuildDiagnosticSource.DEPENDENCY
        )
        if (module.dependencies.isNotEmpty()) {
            state.mavenInputsByModule[moduleTaskSuffix(module)] =
                (mavenDependencies ?: error("DEPENDENCY_UNAVAILABLE")).resolve(context.project, module, token)
        }
        return TaskStep(BuildTaskOutcome.EXECUTED)
    }

    private fun dependencyJars(module: EmbeddedModule, state: ExecutionState, token: CancellationToken): List<File> {
        if (module.dependencies.isEmpty()) return emptyList()
        val inputs = state.mavenInputsByModule[moduleTaskSuffix(module)] ?: error("MAVEN_BUILD_INPUTS_MISSING")
        return (mavenDependencies ?: error("DEPENDENCY_UNAVAILABLE")).verify(inputs, token)
    }

    private fun compileAndroidResources(
        context: BuildContext,
        taskId: String,
        token: CancellationToken
    ): TaskStep {
        val module = moduleForTask(context, taskId, "compile-resources:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        if (module.kind != EmbeddedModuleKind.ANDROID_APPLICATION) return unsupported(
            "EMBEDDED_ANDROID_EXECUTION_UNSUPPORTED",
            "Resource compilation is only enabled for a single Android application module",
            BuildDiagnosticSource.PROJECT
        )
        val compiler = aapt2Compiler ?: return unsupportedSkipped(
            "AAPT2_UNAVAILABLE",
            "No AAPT2 compiler was supplied to the embedded backend",
            BuildDiagnosticSource.AAPT2
        )
        val resourceDirectory = module.sourceSet.resourceDirectories.singleOrNull()
            ?: return unsupported(
                "EMBEDDED_ANDROID_RESOURCE_LAYOUT_UNSUPPORTED",
                "BE2 requires exactly one Android main resource directory",
                BuildDiagnosticSource.PROJECT
            )
        return resourceResultToTaskStep(compiler.compile(
            Aapt2CompileRequest(
                resourceDirectory = resourceDirectory,
                outputArchive = compiledResourcesFile(context.outputDirectory, module)
            ),
            token
        ))
    }

    private fun linkAndroidResources(
        context: BuildContext,
        taskId: String,
        token: CancellationToken,
        state: ExecutionState
    ): TaskStep {
        val module = moduleForTask(context, taskId, "link-resources:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        if (module.kind != EmbeddedModuleKind.ANDROID_APPLICATION) return unsupported(
            "EMBEDDED_ANDROID_EXECUTION_UNSUPPORTED",
            "Resource linking is only enabled for a single Android application module",
            BuildDiagnosticSource.PROJECT
        )
        val compiler = aapt2Compiler ?: return unsupportedSkipped(
            "AAPT2_UNAVAILABLE",
            "No AAPT2 compiler was supplied to the embedded backend",
            BuildDiagnosticSource.AAPT2
        )
        val manifest = module.sourceSet.manifest ?: return unsupported(
            "EMBEDDED_ANDROID_MANIFEST_REQUIRED",
            "Android application module must have src/main/AndroidManifest.xml",
            BuildDiagnosticSource.PROJECT
        )
        val androidJar = state.androidJar ?: return unsupported(
            "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE",
            "The Android platform jar was not resolved before resource linking",
            BuildDiagnosticSource.TOOLCHAIN
        )
        val minSdk = module.minSdk ?: return unsupported(
            "EMBEDDED_ANDROID_SDK_CONFIGURATION_UNSUPPORTED",
            "BE2 requires a declared minSdk value",
            BuildDiagnosticSource.PROJECT
        )
        val targetSdk = module.targetSdk ?: return unsupported(
            "EMBEDDED_ANDROID_SDK_CONFIGURATION_UNSUPPORTED",
            "BE2 requires a declared targetSdk value",
            BuildDiagnosticSource.PROJECT
        )
        return resourceResultToTaskStep(compiler.link(
            Aapt2LinkRequest(
                compiledResources = compiledResourcesFile(context.outputDirectory, module),
                manifest = manifest,
                androidJar = androidJar,
                outputPackage = linkedResourcesFile(context.outputDirectory, module),
                generatedJavaDirectory = generatedRDirectory(context.outputDirectory, module),
                symbolOutput = resourceSymbolsFile(context.outputDirectory, module),
                minSdk = minSdk,
                targetSdk = targetSdk
            ),
            token
        ))
    }

    private fun resourceResultToTaskStep(result: BuildResult): TaskStep = when (result.outcome) {
        BuildOutcome.SUCCESS -> TaskStep(
            BuildTaskOutcome.EXECUTED,
            diagnostics = result.diagnostics,
            artifacts = result.producedArtifacts
        )
        BuildOutcome.FAILED -> TaskStep(
            BuildTaskOutcome.FAILED,
            BuildOutcome.FAILED,
            result.diagnostics
        )
        BuildOutcome.CANCELLED -> TaskStep(
            BuildTaskOutcome.CANCELLED,
            BuildOutcome.CANCELLED,
            result.diagnostics
        )
        BuildOutcome.UNSUPPORTED -> TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.UNSUPPORTED,
            result.diagnostics
        )
        BuildOutcome.RESOURCE_LIMIT -> TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.RESOURCE_LIMIT,
            result.diagnostics
        )
    }

    private fun resolveAndroidPlatformJar(): File? {
        val supplied = runCatching { bootClasspath().toList() }.getOrNull() ?: return null
        if (supplied.size != 1) return null
        val file = supplied.single()
        val path = runCatching { file.toPath().toAbsolutePath().normalize() }.getOrNull() ?: return null
        val fileSize = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (file.name != "android.jar" ||
            Files.isSymbolicLink(path) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            fileSize <= 0L
        ) return null
        return runCatching { path.toRealPath().toFile() }.getOrNull()
    }

    private fun compileJava(
        context: BuildContext,
        taskId: String,
        token: CancellationToken,
        state: ExecutionState
    ): TaskStep {
        val module = moduleForTask(context, taskId, "compile-java:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        val androidJar = when (module.kind) {
            EmbeddedModuleKind.JVM -> null
            EmbeddedModuleKind.ANDROID_APPLICATION -> state.androidJar ?: return unsupported(
                "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE",
                "The Android platform jar was not resolved before Java compilation",
                BuildDiagnosticSource.TOOLCHAIN
            )
            EmbeddedModuleKind.ANDROID_LIBRARY -> return unsupported(
                "EMBEDDED_ANDROID_LIBRARY_EXECUTION_UNSUPPORTED",
                "Android library Java compilation is outside the BE2 execution slice",
                BuildDiagnosticSource.PROJECT
            )
        }
        if (module.sourceSet.kotlinDirectories.isNotEmpty() || module.kotlinPlugin) return unsupported(
            "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED",
            "Mixed or Kotlin compilation is outside the BE2 execution slice",
            BuildDiagnosticSource.KOTLIN
        )

        val output = prepareTaskOutput(context.outputDirectory, module)
        if (token.isCancelled) throw CancellationException("Embedded build was cancelled before Java compilation")
        val sources = collectJavaSources(
            module,
            token,
            if (androidJar == null) null else generatedRDirectory(context.outputDirectory, module)
        )
        if (sources.isEmpty()) {
            if (androidJar != null) return cleanupOutputAfterNonSuccess(
                output,
                BuildTaskOutcome.FAILED,
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "AAPT2_GENERATED_R_MISSING",
                    "Android resource linking produced no Java sources for ECJ",
                    BuildDiagnosticSource.AAPT2
                ))
            )
            return TaskStep(BuildTaskOutcome.SKIPPED)
        }
        if (token.isCancelled) throw CancellationException("Embedded build was cancelled before Java compilation")

        val compileResult = javaCompiler.compile(
            EcjCompileRequest(
                sources = sources,
                outputDirectory = output.toFile(),
                classpath = dependencyJars(module, state, token),
                sourceLevel = "17",
                targetLevel = "17",
                bootClasspath = if (androidJar == null) bootClasspath().toList() else listOf(androidJar)
            ),
            token
        )

        if (token.isCancelled || compileResult.outcome == BuildOutcome.CANCELLED) {
            val cleanup = runCatching { clearTaskOutput(output) }.exceptionOrNull()
            val errors = mutableListOf(diagnostic(
                "BUILD_CANCELLED",
                "Embedded build was cancelled during Java compilation",
                BuildDiagnosticSource.TOOLCHAIN
            ))
            if (cleanup != null) errors += diagnostic(
                "EMBEDDED_OUTPUT_CLEANUP_FAILED",
                cleanup.message ?: "Could not remove cancelled Java output",
                BuildDiagnosticSource.TOOLCHAIN
            )
            return TaskStep(BuildTaskOutcome.CANCELLED, BuildOutcome.CANCELLED, errors)
        }

        if (compileResult.outcome != BuildOutcome.SUCCESS) {
            val taskOutcome = when (compileResult.outcome) {
                BuildOutcome.UNSUPPORTED, BuildOutcome.RESOURCE_LIMIT -> BuildTaskOutcome.SKIPPED
                BuildOutcome.CANCELLED -> BuildTaskOutcome.CANCELLED
                BuildOutcome.FAILED -> BuildTaskOutcome.FAILED
                BuildOutcome.SUCCESS -> error("handled above")
            }
            return cleanupOutputAfterNonSuccess(
                output,
                taskOutcome,
                compileResult.outcome,
                compileResult.diagnostics
            )
        }

        val outputArtifacts = try {
            scanClassArtifacts(output, token)
        } catch (cancelled: CancellationException) {
            return cleanupOutputAfterNonSuccess(
                output,
                BuildTaskOutcome.CANCELLED,
                BuildOutcome.CANCELLED,
                listOf(diagnostic(
                    "BUILD_CANCELLED",
                    cancelled.message ?: "Embedded build was cancelled while collecting Java artifacts",
                    BuildDiagnosticSource.TOOLCHAIN
                ))
            )
        } catch (problem: ExecutionProblem) {
            return cleanupOutputAfterNonSuccess(
                output,
                BuildTaskOutcome.FAILED,
                problem.outcome,
                listOf(problem.diagnostic)
            )
        } catch (error: Exception) {
            return cleanupOutputAfterNonSuccess(
                output,
                BuildTaskOutcome.FAILED,
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_JAVA_ARTIFACT_SCAN_FAILED",
                    error.message ?: "Could not verify ECJ output artifacts",
                    BuildDiagnosticSource.JAVA
                ))
            )
        }
        if (token.isCancelled) {
            return cleanupOutputAfterNonSuccess(
                output,
                BuildTaskOutcome.CANCELLED,
                BuildOutcome.CANCELLED,
                listOf(diagnostic(
                    "BUILD_CANCELLED",
                    "Embedded build was cancelled while collecting Java artifacts",
                    BuildDiagnosticSource.TOOLCHAIN
                ))
            )
        }
        if (outputArtifacts.isEmpty()) return cleanupOutputAfterNonSuccess(
            output,
            BuildTaskOutcome.FAILED,
            BuildOutcome.FAILED,
            listOf(diagnostic(
                "EMBEDDED_JAVA_ARTIFACT_MISSING",
                "ECJ completed without producing a valid Java class file",
                BuildDiagnosticSource.JAVA
            ))
        )
        return TaskStep(BuildTaskOutcome.EXECUTED, artifacts = outputArtifacts)
    }

    private fun compileKotlin(
        context: BuildContext,
        taskId: String,
        token: CancellationToken,
        state: ExecutionState
    ): TaskStep {
        val module = moduleForTask(context, taskId, "compile-kotlin:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        if (module.kind != EmbeddedModuleKind.ANDROID_APPLICATION ||
            module.sourceSet.javaDirectories.isNotEmpty()
        ) return unsupported(
            "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED",
            "Only Kotlin-only Android application modules are supported",
            BuildDiagnosticSource.KOTLIN
        )
        val configured = kotlinCompilation ?: return unsupported(
            "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED",
            "No in-process Kotlin compiler was supplied to this backend",
            BuildDiagnosticSource.KOTLIN
        )
        val androidJar = state.androidJar ?: return unsupported(
            "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE",
            "Android platform jar was not resolved before Kotlin compilation",
            BuildDiagnosticSource.TOOLCHAIN
        )
        val stdlib = configured.stdlibJar.absoluteFile
        if (!stdlib.isFile || Files.isSymbolicLink(stdlib.toPath()) || stdlib.canonicalFile != stdlib) {
            return unsupported("EMBEDDED_KOTLIN_STDLIB_UNAVAILABLE",
                "Kotlin compilation requires a verified ordinary stdlib JAR", BuildDiagnosticSource.KOTLIN)
        }
        val sources = collectKotlinSources(module, token)
        if (sources.isEmpty()) return failedTask(
            "EMBEDDED_KOTLIN_SOURCES_MISSING", "Kotlin source roots contain no ordinary .kt files",
            BuildDiagnosticSource.KOTLIN
        )
        val generatedJava = collectJavaSources(module, token, generatedRDirectory(context.outputDirectory, module))
        if (generatedJava.isEmpty()) return failedTask(
            "AAPT2_GENERATED_R_MISSING", "Android resource linking produced no Java sources for Kotlin compilation",
            BuildDiagnosticSource.AAPT2
        )
        // The primitive publishes into a fresh path after validation; never hand
        // it the pre-created directory used by the ECJ-only node.
        val output = prepareTaskOutput(context.outputDirectory, module, createLeaf = false)
        if (token.isCancelled) throw CancellationException("Embedded build was cancelled before Kotlin compilation")
        val result = configured.compiler.compile(KotlinCompileRequest(
            sources = sources,
            outputDirectory = output.toFile(),
            bootClasspath = listOf(androidJar),
            classpath = (listOf(stdlib) + dependencyJars(module, state, token)).distinct(),
            javaSources = generatedJava
        ), token)
        if (token.isCancelled || result.outcome != BuildOutcome.SUCCESS) {
            val outcome = if (token.isCancelled) BuildOutcome.CANCELLED else result.outcome
            return cleanupOutputAfterNonSuccess(output,
                when (outcome) {
                    BuildOutcome.CANCELLED -> BuildTaskOutcome.CANCELLED
                    BuildOutcome.UNSUPPORTED, BuildOutcome.RESOURCE_LIMIT -> BuildTaskOutcome.SKIPPED
                    BuildOutcome.FAILED -> BuildTaskOutcome.FAILED
                    BuildOutcome.SUCCESS -> error("handled above")
                }, outcome, result.diagnostics)
        }
        val classArtifacts = try { scanClassArtifacts(output, token, "kotlin-class") }
        catch (error: Exception) {
            return cleanupOutputAfterNonSuccess(output, BuildTaskOutcome.FAILED, BuildOutcome.FAILED,
                listOf(diagnostic("EMBEDDED_KOTLIN_ARTIFACT_INVALID",
                    error.message ?: "Kotlin class output could not be verified", BuildDiagnosticSource.KOTLIN)))
        }
        if (classArtifacts.isEmpty() || token.isCancelled) return cleanupOutputAfterNonSuccess(
            output, if (token.isCancelled) BuildTaskOutcome.CANCELLED else BuildTaskOutcome.FAILED,
            if (token.isCancelled) BuildOutcome.CANCELLED else BuildOutcome.FAILED,
            listOf(diagnostic(if (token.isCancelled) "BUILD_CANCELLED" else "EMBEDDED_KOTLIN_ARTIFACT_MISSING",
                if (token.isCancelled) "Kotlin compilation was cancelled" else "Kotlin compiler produced no valid class files",
                BuildDiagnosticSource.KOTLIN)))
        return TaskStep(BuildTaskOutcome.EXECUTED, diagnostics = result.diagnostics, artifacts = classArtifacts)
    }

    private fun dexAndroidClasses(
        context: BuildContext,
        taskId: String,
        token: CancellationToken,
        state: ExecutionState
    ): TaskStep {
        val module = moduleForTask(context, taskId, "dex:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        val moduleSuffix = moduleTaskSuffix(module)
        state.runtimeDexDirectoriesByModule.remove(moduleSuffix)
        if (module.kind != EmbeddedModuleKind.ANDROID_APPLICATION) return unsupportedSkipped(
            "EMBEDDED_ANDROID_EXECUTION_UNSUPPORTED",
            "DEX execution in ${module.gradlePath} requires an Android application module",
            BuildDiagnosticSource.PROJECT
        )
        val targetStdlib = if (module.sourceSet.kotlinDirectories.isNotEmpty()) {
            val support = kotlinCompilation
            if (support?.packageStdlibInTarget != true) return unsupported(
                "EMBEDDED_KOTLIN_RUNTIME_PACKAGING_UNSUPPORTED",
                "Kotlin app DEX requires explicit verified stdlib packaging before publication",
                BuildDiagnosticSource.DEX
            )
            if ((module.minSdk ?: 23) < 26 && coreLibraryDesugaring == null) return unsupported(
                "EMBEDDED_KOTLIN_DESUGAR_UNAVAILABLE",
                "Kotlin target DEX for API 23 requires core-library desugaring support",
                BuildDiagnosticSource.DEX
            )
            val stdlib = support.stdlibJar.absoluteFile
            if (!verifyPinnedKotlinStdlib(stdlib, token)) return unsupported(
                "EMBEDDED_KOTLIN_STDLIB_INVALID",
                "Target Kotlin stdlib does not match the packaged 1.9.20 pin",
                BuildDiagnosticSource.DEX
            )
            stdlib
        } else null
        val desugarOutputs = try {
            clearOwnedDesugaringOutputs(context.outputDirectory, module)
        } catch (error: Exception) {
            return TaskStep(
                BuildTaskOutcome.FAILED,
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_DESUGAR_OUTPUT_UNSAFE",
                    error.message ?: "Owned core-library desugaring outputs could not be safely cleared",
                    BuildDiagnosticSource.DEX
                ))
            )
        }
        val compiler = d8Compiler ?: return unsupportedSkipped(
            "D8_UNAVAILABLE",
            "The current embedded runtime did not supply a D8 provider",
            BuildDiagnosticSource.DEX
        )
        val androidJar = state.androidJar ?: return unsupportedSkipped(
            "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE",
            "The Android platform jar was not resolved before D8 execution",
            BuildDiagnosticSource.TOOLCHAIN
        )
        val minApi = module.minSdk ?: return unsupportedSkipped(
            "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE",
            "Android minSdk metadata was not resolved before D8 execution",
            BuildDiagnosticSource.TOOLCHAIN
        )
        val support = coreLibraryDesugaring
        val dependencyArchives = dependencyJars(module, state, token)
        val d8Result = compiler.compile(
            D8CompileRequest(
                classDirectories = listOf(javaClassesDirectory(context.outputDirectory, module)),
                libraryFiles = listOf(androidJar),
                programArchives = (listOfNotNull(targetStdlib) + dependencyArchives).distinct(),
                outputDirectory = appDexDirectory(context.outputDirectory, module),
                minApi = minApi,
                release = true,
                coreLibraryDesugaring = support?.let { configured ->
                    D8CoreLibraryDesugaringRequest(
                        configurationJson = configured.configurationJson,
                        keepRulesOutput = desugarOutputs.keepRulesFile
                    )
                }
            ),
            token
        )
        if (d8Result.outcome != BuildOutcome.SUCCESS) return dexResultToTaskStep(d8Result)
        if (support == null) return dexResultToTaskStep(d8Result)

        val keepRules = d8Result.artifacts.filter { it.kind == "android-desugar-keep-rules" }
        if (keepRules.size > 1) return failedTask(
            "EMBEDDED_DESUGAR_KEEP_RULES_INVALID",
            "D8 returned multiple core-library desugaring keep-rules artifacts",
            BuildDiagnosticSource.DEX
        )
        val keepRulesArtifact = keepRules.singleOrNull()
            ?: return TaskStep(
                BuildTaskOutcome.EXECUTED,
                diagnostics = d8Result.diagnostics,
                artifacts = d8Result.artifacts
            )

        val l8Result = support.l8Compiler.compile(
            L8CompileRequest(
                desugaredLibraryConfigurationJson = support.configurationJson,
                keepRulesFile = keepRulesArtifact.file,
                programFiles = support.programFiles,
                libraryFiles = listOf(androidJar),
                outputDirectory = desugarOutputs.runtimeDexDirectory.toFile(),
                minApi = minApi,
                release = true
            ),
            token
        )
        if (l8Result.outcome != BuildOutcome.SUCCESS) return l8ResultToTaskStep(l8Result)
        val runtimeDexArtifacts = l8Result.artifacts.filter { it.kind == "android-desugar-runtime-dex" }
        if (runtimeDexArtifacts.isEmpty()) return failedTask(
            "EMBEDDED_DESUGAR_RUNTIME_ARTIFACT_MISSING",
            "L8 reported success without an android-desugar-runtime-dex artifact",
            BuildDiagnosticSource.DEX
        )
        try {
            val runtimeRoot = desugarOutputs.runtimeDexDirectory.toAbsolutePath().normalize()
            rejectOwnedOutputSymlinkComponents(runtimeRoot)
            if (!Files.isDirectory(runtimeRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(runtimeRoot)) {
                throw java.io.IOException("L8 runtime DEX output is not an ordinary directory")
            }
            runtimeDexArtifacts.forEach { artifact ->
                val artifactPath = artifact.file.absoluteFile.toPath().normalize()
                rejectOwnedOutputSymlinkComponents(artifactPath)
                if (artifactPath == runtimeRoot || !artifactPath.startsWith(runtimeRoot) ||
                    !Files.isRegularFile(artifactPath, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(artifactPath)
                ) {
                    throw java.io.IOException("L8 runtime DEX artifact is outside its current-run output directory")
                }
            }
            state.runtimeDexDirectoriesByModule[moduleSuffix] = runtimeRoot.toFile()
        } catch (error: Exception) {
            return failedTask(
                "EMBEDDED_DESUGAR_RUNTIME_ARTIFACT_INVALID",
                error.message ?: "L8 runtime DEX artifact could not be verified",
                BuildDiagnosticSource.DEX
            )
        }
        return TaskStep(
            BuildTaskOutcome.EXECUTED,
            diagnostics = d8Result.diagnostics + l8Result.diagnostics,
            artifacts = d8Result.artifacts + l8Result.artifacts
        )
    }

    private fun packageAndroidApk(
        context: BuildContext,
        taskId: String,
        token: CancellationToken,
        state: ExecutionState
    ): TaskStep {
        val module = moduleForTask(context, taskId, "package-apk:")
            ?: return failedTask("BUILD_PLAN_TASK_MODULE_MISMATCH", "Unknown module for task $taskId")
        if (module.kind != EmbeddedModuleKind.ANDROID_APPLICATION) return unsupportedSkipped(
            "EMBEDDED_ANDROID_EXECUTION_UNSUPPORTED",
            "APK packaging in ${module.gradlePath} requires an Android application module",
            BuildDiagnosticSource.PROJECT
        )
        val packaging = apkPackaging ?: return unsupported(
            "EMBEDDED_APK_PACKAGING_UNSUPPORTED",
            "APK packaging is not enabled for this embedded backend",
            BuildDiagnosticSource.PACKAGING
        )
        val outputs = try {
            clearOwnedApkOutputs(context.outputDirectory, module)
        } catch (error: Exception) {
            return failedTask(
                "EMBEDDED_APK_OUTPUT_UNSAFE",
                error.message ?: "Package-owned APK outputs could not be safely cleared",
                BuildDiagnosticSource.PACKAGING
            )
        }

        fun nonSuccess(outcome: BuildOutcome, initialDiagnostics: List<BuildDiagnostic>): TaskStep {
            val diagnostics = initialDiagnostics.toMutableList()
            var effectiveOutcome = outcome
            try {
                clearOwnedApkOutputs(context.outputDirectory, module)
            } catch (error: Exception) {
                effectiveOutcome = BuildOutcome.FAILED
                diagnostics += diagnostic(
                    "EMBEDDED_APK_OUTPUT_CLEANUP_FAILED",
                    error.message ?: "Package-owned APK outputs could not be safely rolled back",
                    BuildDiagnosticSource.PACKAGING
                )
            }
            if (diagnostics.isEmpty()) diagnostics += diagnostic(
                "EMBEDDED_APK_PACKAGING_FAILED",
                "APK packaging did not complete successfully",
                BuildDiagnosticSource.PACKAGING
            )
            val taskOutcome = when (effectiveOutcome) {
                BuildOutcome.CANCELLED -> BuildTaskOutcome.CANCELLED
                BuildOutcome.UNSUPPORTED, BuildOutcome.RESOURCE_LIMIT -> BuildTaskOutcome.SKIPPED
                BuildOutcome.FAILED -> BuildTaskOutcome.FAILED
                BuildOutcome.SUCCESS -> BuildTaskOutcome.FAILED
            }
            val buildOutcome = if (effectiveOutcome == BuildOutcome.SUCCESS) BuildOutcome.FAILED else effectiveOutcome
            return TaskStep(taskOutcome, buildOutcome, diagnostics)
        }

        if (token.isCancelled) return nonSuccess(
            BuildOutcome.CANCELLED,
            listOf(diagnostic("BUILD_CANCELLED", "APK packaging was cancelled before assembly", BuildDiagnosticSource.PACKAGING))
        )

        val runtimeDexDirectory = state.runtimeDexDirectoriesByModule[moduleTaskSuffix(module)]
        val assembly = try {
            ApkAssembler().assemble(
                ApkAssemblyRequest(
                    baseResourcePackage = linkedResourcesFile(context.outputDirectory, module),
                    appDexDirectory = appDexDirectory(context.outputDirectory, module),
                    runtimeDexDirectory = runtimeDexDirectory,
                    dependencyResourceArchives = dependencyJars(module, state, token),
                    outputApk = outputs.unalignedApk.toFile()
                ),
                token
            )
        } catch (error: Exception) {
            return nonSuccess(
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_APK_PACKAGING_FAILED",
                    "APK assembly failed: ${error.message ?: error.javaClass.simpleName}",
                    BuildDiagnosticSource.PACKAGING
                ))
            )
        } catch (error: LinkageError) {
            return nonSuccess(
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_APK_PACKAGING_FAILED",
                    "APK assembly linkage failed: ${error.message ?: error.javaClass.simpleName}",
                    BuildDiagnosticSource.PACKAGING
                ))
            )
        }
        if (assembly.outcome != BuildOutcome.SUCCESS) return nonSuccess(assembly.outcome, assembly.diagnostics)
        if (assembly.artifacts.singleOrNull()?.file?.toPath()?.toAbsolutePath()?.normalize() != outputs.unalignedApk) {
            return nonSuccess(
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_APK_ASSEMBLY_ARTIFACT_INVALID",
                    "AP1 did not report the package-owned unaligned APK output",
                    BuildDiagnosticSource.PACKAGING
                ))
            )
        }
        if (token.isCancelled) return nonSuccess(
            BuildOutcome.CANCELLED,
            listOf(diagnostic("BUILD_CANCELLED", "APK packaging was cancelled after assembly", BuildDiagnosticSource.PACKAGING))
        )

        val alignment = try {
            ApkAligner(packaging.alignmentProvider).align(
                ApkAlignmentRequest(
                    inputApk = outputs.unalignedApk.toFile(),
                    outputApk = outputs.alignedApk.toFile()
                ),
                token
            )
        } catch (error: Exception) {
            return nonSuccess(
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_APK_ALIGNMENT_FAILED",
                    "APK alignment failed: ${error.message ?: error.javaClass.simpleName}",
                    BuildDiagnosticSource.PACKAGING
                ))
            )
        } catch (error: LinkageError) {
            return nonSuccess(
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_APK_ALIGNMENT_FAILED",
                    "APK alignment linkage failed: ${error.message ?: error.javaClass.simpleName}",
                    BuildDiagnosticSource.PACKAGING
                ))
            )
        }
        if (alignment.outcome != BuildOutcome.SUCCESS) return nonSuccess(alignment.outcome, alignment.diagnostics)
        if (token.isCancelled) return nonSuccess(
            BuildOutcome.CANCELLED,
            listOf(diagnostic("BUILD_CANCELLED", "APK packaging was cancelled after alignment", BuildDiagnosticSource.PACKAGING))
        )
        if (alignment.artifacts.singleOrNull()?.file?.toPath()?.toAbsolutePath()?.normalize() != outputs.alignedApk) {
            return nonSuccess(
                BuildOutcome.FAILED,
                listOf(diagnostic(
                    "EMBEDDED_APK_ALIGNMENT_ARTIFACT_INVALID",
                    "AP2 did not report the package-owned aligned APK output",
                    BuildDiagnosticSource.PACKAGING
                ))
            )
        }
        return TaskStep(
            BuildTaskOutcome.EXECUTED,
            diagnostics = assembly.diagnostics + alignment.diagnostics,
            artifacts = assembly.artifacts + alignment.artifacts
        )
    }

    /** Pre-cleans or rolls back only the two files owned by package-apk. */
    private fun clearOwnedApkOutputs(outputDirectory: File, module: EmbeddedModule): ApkOutputPaths {
        val rawRoot = outputDirectory.absoluteFile.toPath().normalize()
        rejectOwnedOutputSymlinkComponents(rawRoot)
        val root = outputDirectory.canonicalFile.toPath().normalize()
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw java.io.IOException("Build output root is not an ordinary directory: $root")
        }
        val apkDirectory = root.resolve("modules").resolve(moduleTaskSuffix(module)).resolve("apk").normalize()
        val outputs = ApkOutputPaths(
            apkDirectory.resolve("unsigned-unaligned.apk"),
            apkDirectory.resolve("unsigned-aligned.apk")
        )
        validateOwnedApkOutputPath(root, outputs.unalignedApk)
        validateOwnedApkOutputPath(root, outputs.alignedApk)
        Files.deleteIfExists(outputs.unalignedApk)
        Files.deleteIfExists(outputs.alignedApk)
        return outputs
    }

    private fun validateOwnedApkOutputPath(root: Path, target: Path) {
        if (target == root || !target.startsWith(root)) {
            throw java.io.IOException("Package-owned APK output escapes the build root: $target")
        }
        var current = root
        val relative = root.relativize(target)
        relative.forEachIndexed { index, component ->
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return@forEachIndexed
            if (Files.isSymbolicLink(current)) {
                throw java.io.IOException("Package-owned APK output contains a symbolic link: $current")
            }
            val isTarget = index == relative.nameCount - 1
            if (isTarget) {
                if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw java.io.IOException("Package-owned APK output is not an ordinary file: $current")
                }
            } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.IOException("Package-owned APK output parent is not an ordinary directory: $current")
            }
        }
    }

    private fun dexResultToTaskStep(result: D8CompileResult): TaskStep = when (result.outcome) {
        BuildOutcome.SUCCESS -> TaskStep(
            BuildTaskOutcome.EXECUTED,
            diagnostics = result.diagnostics,
            artifacts = result.artifacts
        )
        BuildOutcome.FAILED -> TaskStep(
            BuildTaskOutcome.FAILED,
            BuildOutcome.FAILED,
            result.diagnostics
        )
        BuildOutcome.CANCELLED -> TaskStep(
            BuildTaskOutcome.CANCELLED,
            BuildOutcome.CANCELLED,
            result.diagnostics
        )
        BuildOutcome.UNSUPPORTED -> TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.UNSUPPORTED,
            result.diagnostics
        )
        BuildOutcome.RESOURCE_LIMIT -> TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.RESOURCE_LIMIT,
            result.diagnostics
        )
    }

    private fun l8ResultToTaskStep(result: L8CompileResult): TaskStep = when (result.outcome) {
        BuildOutcome.SUCCESS -> TaskStep(
            BuildTaskOutcome.EXECUTED,
            diagnostics = result.diagnostics,
            artifacts = result.artifacts
        )
        BuildOutcome.FAILED -> TaskStep(
            BuildTaskOutcome.FAILED,
            BuildOutcome.FAILED,
            result.diagnostics
        )
        BuildOutcome.CANCELLED -> TaskStep(
            BuildTaskOutcome.CANCELLED,
            BuildOutcome.CANCELLED,
            result.diagnostics
        )
        BuildOutcome.UNSUPPORTED -> TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.UNSUPPORTED,
            result.diagnostics
        )
        BuildOutcome.RESOURCE_LIMIT -> TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.RESOURCE_LIMIT,
            result.diagnostics
        )
    }

    /** Clears only the two BE4-owned auxiliary targets and never traverses a symlink. */
    private fun clearOwnedDesugaringOutputs(
        outputDirectory: File,
        module: EmbeddedModule
    ): DesugaringOutputPaths {
        val rawRoot = outputDirectory.absoluteFile.toPath().normalize()
        rejectOwnedOutputSymlinkComponents(rawRoot)
        val root = outputDirectory.canonicalFile.toPath().normalize()
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw java.io.IOException("Build output root is not an ordinary directory: $root")
        }
        val moduleRoot = root.resolve("modules").resolve(moduleTaskSuffix(module)).normalize()
        val keepRulesFile = moduleRoot.resolve("dex").resolve("desugar").resolve("l8.pg").normalize()
        val runtimeDexDirectory = moduleRoot.resolve("dex").resolve("desugar-runtime").normalize()
        validateOwnedOutputPath(root, keepRulesFile, expectDirectory = false)
        validateOwnedOutputPath(root, runtimeDexDirectory, expectDirectory = true)

        if (Files.exists(keepRulesFile, LinkOption.NOFOLLOW_LINKS)) Files.delete(keepRulesFile)
        if (Files.exists(runtimeDexDirectory, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(runtimeDexDirectory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    if (dir != runtimeDexDirectory) Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return DesugaringOutputPaths(keepRulesFile.toFile(), runtimeDexDirectory)
    }

    private fun rejectOwnedOutputSymlinkComponents(path: Path) {
        val root = path.root
            ?: throw java.io.IOException("Build output path has no filesystem root: $path")
        var current = root
        for (component in path) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw java.io.IOException("Build output path contains a symbolic link: $current")
            }
        }
    }

    private fun validateOwnedOutputPath(root: Path, target: Path, expectDirectory: Boolean) {
        if (target == root || !target.startsWith(root)) {
            throw java.io.IOException("Owned desugaring output escapes the build root: $target")
        }
        var current = root
        val relative = root.relativize(target)
        relative.forEachIndexed { index, component ->
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return@forEachIndexed
            if (Files.isSymbolicLink(current)) {
                throw java.io.IOException("Owned desugaring output contains a symbolic link: $current")
            }
            val isTarget = index == relative.nameCount - 1
            if (!isTarget || expectDirectory) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw java.io.IOException("Owned desugaring output parent is not an ordinary directory: $current")
                }
            } else if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.IOException("Keep-rules target is not an ordinary file: $current")
            }
        }
    }

    private fun cleanupOutputAfterNonSuccess(
        output: Path,
        taskOutcome: BuildTaskOutcome,
        buildOutcome: BuildOutcome,
        diagnostics: List<BuildDiagnostic>
    ): TaskStep {
        val resultDiagnostics = diagnostics.toMutableList()
        val cleanup = runCatching { clearTaskOutput(output) }.exceptionOrNull()
        if (cleanup != null) resultDiagnostics += diagnostic(
            "EMBEDDED_OUTPUT_CLEANUP_FAILED",
            cleanup.message ?: "Could not remove unsuccessful Java output",
            BuildDiagnosticSource.TOOLCHAIN
        )
        return TaskStep(taskOutcome, buildOutcome, resultDiagnostics)
    }

    private fun collectJavaSources(
        module: EmbeddedModule,
        token: CancellationToken,
        generatedJavaDirectory: File? = null
    ): List<File> {
        data class Source(val root: Path, val relative: String, val file: Path)

        val sourceEntries = linkedMapOf<Path, Source>()
        val moduleRoot = module.directory.canonicalFile.toPath().normalize()
        val projectRoots = module.sourceSet.javaDirectories
            .map { directory ->
                val sourcePath = directory.toPath().toAbsolutePath().normalize()
                if (Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourcePath))
                ) {
                    throw ExecutionProblem(
                        BuildOutcome.FAILED,
                        diagnostic("EMBEDDED_SOURCE_DIRECTORY_INVALID", "Java source root is not an ordinary directory: $directory", BuildDiagnosticSource.JAVA)
                    )
                }
                if (!Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) null
                else {
                    val canonical = directory.canonicalFile.toPath().normalize()
                    if (!canonical.startsWith(moduleRoot)) {
                        throw ExecutionProblem(
                            BuildOutcome.FAILED,
                            diagnostic("EMBEDDED_SOURCE_PATH_ESCAPE", "Java source root escapes its module: $directory", BuildDiagnosticSource.JAVA)
                        )
                    }
                    canonical
                }
            }
            .filterNotNull()
            .distinct()
            .sortedBy { it.toString() }
        val generatedRoot = generatedJavaDirectory?.let { directory ->
            val path = directory.toPath().toAbsolutePath().normalize()
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw ExecutionProblem(
                    BuildOutcome.FAILED,
                    diagnostic(
                        "AAPT2_GENERATED_R_MISSING",
                        "Generated Android Java root is not an ordinary directory: $directory",
                        BuildDiagnosticSource.AAPT2
                    )
                )
            }
            val canonical = directory.canonicalFile.toPath().normalize()
            if (canonical != path) {
                throw ExecutionProblem(
                    BuildOutcome.FAILED,
                    diagnostic(
                        "EMBEDDED_SOURCE_PATH_ESCAPE",
                        "Generated Android Java root resolves outside its requested path: $directory",
                        BuildDiagnosticSource.AAPT2
                    )
                )
            }
            canonical
        }
        val roots = (projectRoots + listOfNotNull(generatedRoot)).distinct().sortedBy { it.toString() }
        val generatedRoots = generatedRoot?.let { setOf(it) } ?: emptySet()

        for (root in roots) {
            if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Java sources")
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Java sources")
                    if (attrs.isSymbolicLink || !attrs.isDirectory) {
                        throw ExecutionProblem(
                            BuildOutcome.FAILED,
                            diagnostic("EMBEDDED_SOURCE_PATH_UNSAFE", "Java source tree contains a non-directory entry: $dir", BuildDiagnosticSource.JAVA)
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Java sources")
                    val isJavaSource = file.fileName.toString().endsWith(".java")
                    if (root in generatedRoots && isJavaSource && (attrs.isSymbolicLink || !attrs.isRegularFile)) {
                        throw ExecutionProblem(
                            BuildOutcome.FAILED,
                            diagnostic("EMBEDDED_SOURCE_PATH_UNSAFE", "Generated Java source is not an ordinary file: $file", BuildDiagnosticSource.AAPT2)
                        )
                    }
                    if (!isJavaSource || attrs.isSymbolicLink || !attrs.isRegularFile) {
                        return FileVisitResult.CONTINUE
                    }
                    val canonical = file.toFile().canonicalFile.toPath().normalize()
                    if (!canonical.startsWith(root) || !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) {
                        throw ExecutionProblem(
                            BuildOutcome.FAILED,
                            diagnostic("EMBEDDED_SOURCE_PATH_ESCAPE", "Java source escapes its source root: $file", BuildDiagnosticSource.JAVA)
                        )
                    }
                    val source = Source(
                        root = root,
                        relative = root.relativize(canonical).toString().replace(File.separatorChar, '/'),
                        file = canonical
                    )
                    val previous = sourceEntries[canonical]
                    if (previous == null || compareValuesBy(
                            source,
                            previous,
                            { it.relative },
                            { it.root.toString() },
                            { it.file.toString() }
                        ) < 0
                    ) sourceEntries[canonical] = source
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                    throw ExecutionProblem(
                        BuildOutcome.FAILED,
                        diagnostic("EMBEDDED_SOURCE_COLLECTION_FAILED", "Could not read Java source path $file: ${error.message ?: "I/O error"}", BuildDiagnosticSource.JAVA)
                    )
                }
            })
        }

        return sourceEntries.values
            .sortedWith(compareBy<Source>({ it.relative }, { it.root.toString() }, { it.file.toString() }))
            .map { it.file.toFile() }
    }

    private fun collectKotlinSources(module: EmbeddedModule, token: CancellationToken): List<File> {
        val moduleRoot = module.directory.canonicalFile.toPath().normalize()
        val sources = linkedMapOf<Path, Pair<String, Path>>()
        val roots = module.sourceSet.kotlinDirectories.mapNotNull { directory ->
            val path = directory.absoluteFile.toPath().normalize()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw ExecutionProblem(BuildOutcome.FAILED, diagnostic("EMBEDDED_KOTLIN_SOURCE_DIRECTORY_INVALID",
                    "Kotlin source root is not an ordinary directory: $directory", BuildDiagnosticSource.KOTLIN))
            }
            val canonical = directory.canonicalFile.toPath().normalize()
            if (!canonical.startsWith(moduleRoot) || canonical != path) {
                throw ExecutionProblem(BuildOutcome.FAILED, diagnostic("EMBEDDED_KOTLIN_SOURCE_PATH_ESCAPE",
                    "Kotlin source root is not a canonical in-module directory: $directory", BuildDiagnosticSource.KOTLIN))
            }
            canonical
        }.distinct().sortedBy { it.toString() }
        for (root in roots) {
            if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Kotlin sources")
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Kotlin sources")
                    if (attrs.isSymbolicLink || !attrs.isDirectory) {
                        throw ExecutionProblem(BuildOutcome.FAILED, diagnostic("EMBEDDED_KOTLIN_SOURCE_PATH_UNSAFE",
                            "Kotlin source tree contains a non-directory entry: $dir", BuildDiagnosticSource.KOTLIN))
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Kotlin sources")
                    if (!file.fileName.toString().endsWith(".kt")) return FileVisitResult.CONTINUE
                    if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                        throw ExecutionProblem(BuildOutcome.FAILED, diagnostic("EMBEDDED_KOTLIN_SOURCE_PATH_UNSAFE",
                            "Kotlin source must be an ordinary file: $file", BuildDiagnosticSource.KOTLIN))
                    }
                    val canonical = file.toFile().canonicalFile.toPath().normalize()
                    if (canonical != file.toAbsolutePath().normalize() || !canonical.startsWith(root)) {
                        throw ExecutionProblem(BuildOutcome.FAILED, diagnostic("EMBEDDED_KOTLIN_SOURCE_PATH_ESCAPE",
                            "Kotlin source escapes its root: $file", BuildDiagnosticSource.KOTLIN))
                    }
                    if (!sources.containsKey(canonical)) {
                        sources[canonical] = root.relativize(canonical).toString().replace(File.separatorChar, '/') to root
                    }
                    if (sources.size > 512) {
                        throw ExecutionProblem(BuildOutcome.RESOURCE_LIMIT, diagnostic("EMBEDDED_KOTLIN_SOURCE_LIMIT",
                            "Kotlin source count exceeds the 512-file budget", BuildDiagnosticSource.KOTLIN))
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return sources.entries.sortedWith(compareBy<Map.Entry<Path, Pair<String, Path>>>(
            { it.value.first }, { it.value.second.toString() }, { it.key.toString() }))
            .map { it.key.toFile() }
    }

    private fun prepareTaskOutput(outputDirectory: File, module: EmbeddedModule, createLeaf: Boolean = true): Path {
        val rawRoot = outputDirectory.absoluteFile.toPath().normalize()
        if (Files.exists(rawRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(rawRoot)) {
            throw ExecutionProblem(
                BuildOutcome.FAILED,
                diagnostic("EMBEDDED_OUTPUT_DIRECTORY_UNSAFE", "Build output directory is a symbolic link", BuildDiagnosticSource.TOOLCHAIN)
            )
        }
        val root = outputDirectory.canonicalFile.toPath().normalize()
        try {
            Files.createDirectories(root)
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw java.io.IOException("Build output root is not an ordinary directory")
            }
            val target = javaClassesDirectory(root.toFile(), module).toPath().normalize()
            if (!target.startsWith(root)) throw java.io.IOException("Java output escapes the build output directory")

            var current = root
            val relativeParent = root.relativize(target.parent)
            for (component in relativeParent) {
                current = current.resolve(component)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw java.io.IOException("Java output parent is not an ordinary directory: $current")
                    }
                } else {
                    Files.createDirectory(current)
                }
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw java.io.IOException("Java output path is not an ordinary directory: $target")
                }
                clearTaskOutput(target)
            }
            if (createLeaf) Files.createDirectory(target)
            return target
        } catch (problem: ExecutionProblem) {
            throw problem
        } catch (error: Exception) {
            throw ExecutionProblem(
                BuildOutcome.FAILED,
                diagnostic("EMBEDDED_OUTPUT_DIRECTORY_UNSAFE", error.message ?: "Could not prepare Java output directory", BuildDiagnosticSource.TOOLCHAIN)
            )
        }
    }

    private fun clearTaskOutput(output: Path) {
        if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw java.io.IOException("Refusing to clean a non-directory Java output path")
        }
        Files.walkFileTree(output, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun verifyPinnedKotlinStdlib(file: File, token: CancellationToken): Boolean {
        return try {
            val pin = PackagedKotlinRuntimePins.STDLIB_ARTIFACT
            val path = file.toPath()
            if (file.canonicalFile != file || Files.isSymbolicLink(path) ||
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != pin.sizeBytes
            ) return false
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    if (token.isCancelled) throw CancellationException("Embedded build was cancelled while verifying Kotlin stdlib")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) } == pin.sha256
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun scanClassArtifacts(output: Path, token: CancellationToken, kind: String = "java-class"): List<BuildArtifact> {
        val classes = mutableListOf<Path>()
        Files.walkFileTree(output, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (token.isCancelled) throw CancellationException("Embedded build was cancelled while collecting Java artifacts")
                if (attrs.isRegularFile && !attrs.isSymbolicLink &&
                    file.fileName.toString().endsWith(".class")
                ) classes.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return classes
            .map { it.toFile().canonicalFile.toPath().normalize() }
            .distinct()
            .sortedBy { output.relativize(it).toString().replace(File.separatorChar, '/') }
            .map { classFile ->
                if (!classFile.startsWith(output) || !Files.isRegularFile(classFile, LinkOption.NOFOLLOW_LINKS) ||
                    Files.size(classFile) <= MIN_CLASS_BYTES
                ) {
                    throw ExecutionProblem(
                        BuildOutcome.FAILED,
                        diagnostic("EMBEDDED_JAVA_ARTIFACT_MISSING", "ECJ output is not a non-empty ordinary class file: $classFile", BuildDiagnosticSource.JAVA)
                    )
                }
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(classFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val header = ByteArray(CLASS_MAGIC.size)
                    readFully(input, header)
                    if (!header.contentEquals(CLASS_MAGIC)) {
                        throw ExecutionProblem(
                            BuildOutcome.FAILED,
                            diagnostic("EMBEDDED_JAVA_ARTIFACT_MISSING", "ECJ output has an invalid class-file header: $classFile", BuildDiagnosticSource.JAVA)
                        )
                    }
                    digest.update(header)
                    val buffer = ByteArray(HASH_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                }
                BuildArtifact(
                    kind = kind,
                    file = classFile.toFile(),
                    sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                )
            }
    }

    private fun moduleForTask(context: BuildContext, taskId: String, prefix: String): EmbeddedModule? =
        context.project.modules.singleOrNull { taskId == prefix + moduleTaskSuffix(it) }

    private fun moduleResourceRoot(outputDirectory: File, module: EmbeddedModule): File =
        File(File(File(outputDirectory, "modules"), moduleTaskSuffix(module)), "resources")

    private fun javaClassesDirectory(outputDirectory: File, module: EmbeddedModule): File =
        File(File(File(File(outputDirectory, "modules"), moduleTaskSuffix(module)), "classes"), "java/main")

    private fun appDexDirectory(outputDirectory: File, module: EmbeddedModule): File =
        File(File(File(outputDirectory, "modules"), moduleTaskSuffix(module)), "dex/app")

    private fun compiledResourcesFile(outputDirectory: File, module: EmbeddedModule): File =
        File(File(moduleResourceRoot(outputDirectory, module), "compiled"), "res.zip")

    private fun linkedResourcesFile(outputDirectory: File, module: EmbeddedModule): File =
        File(File(moduleResourceRoot(outputDirectory, module), "linked"), "resources.ap_")

    private fun generatedRDirectory(outputDirectory: File, module: EmbeddedModule): File =
        File(File(moduleResourceRoot(outputDirectory, module), "generated"), "r")

    private fun resourceSymbolsFile(outputDirectory: File, module: EmbeddedModule): File =
        File(File(moduleResourceRoot(outputDirectory, module), "symbols"), "R.txt")

    private fun unsupported(code: String, message: String, source: BuildDiagnosticSource) =
        TaskStep(
            BuildTaskOutcome.FAILED,
            BuildOutcome.UNSUPPORTED,
            listOf(diagnostic(code, message, source))
        )

    private fun unsupportedSkipped(code: String, message: String, source: BuildDiagnosticSource) =
        TaskStep(
            BuildTaskOutcome.SKIPPED,
            BuildOutcome.UNSUPPORTED,
            listOf(diagnostic(code, message, source))
        )

    private fun failedTask(
        code: String,
        message: String,
        source: BuildDiagnosticSource = BuildDiagnosticSource.PROJECT
    ) = TaskStep(
        BuildTaskOutcome.FAILED,
        BuildOutcome.FAILED,
        listOf(diagnostic(code, message, source))
    )

    private fun diagnostic(code: String, message: String, source: BuildDiagnosticSource) = BuildDiagnostic(
        severity = BuildDiagnosticSeverity.ERROR,
        code = code,
        message = message,
        source = source
    )

    private fun appendSkipped(
        tasks: List<BuildTaskPlan>,
        results: MutableList<BuildTaskResult>,
        outcomes: MutableMap<String, BuildTaskOutcome>
    ) {
        tasks.forEach { task ->
            outcomes[task.id] = BuildTaskOutcome.SKIPPED
            results += BuildTaskResult(task.id, BuildTaskOutcome.SKIPPED)
        }
    }

    private fun appendCancelled(
        tasks: List<BuildTaskPlan>,
        results: MutableList<BuildTaskResult>,
        outcomes: MutableMap<String, BuildTaskOutcome>,
        diagnostic: BuildDiagnostic
    ) {
        tasks.forEachIndexed { index, task ->
            val outcome = if (index == 0) BuildTaskOutcome.CANCELLED else BuildTaskOutcome.SKIPPED
            outcomes[task.id] = outcome
            results += BuildTaskResult(
                id = task.id,
                outcome = outcome,
                diagnostics = if (index == 0) listOf(diagnostic) else emptyList()
            )
        }
    }

    private fun appendFailureThenSkipped(
        tasks: List<BuildTaskPlan>,
        results: MutableList<BuildTaskResult>,
        outcomes: MutableMap<String, BuildTaskOutcome>,
        diagnostic: BuildDiagnostic
    ) {
        tasks.forEachIndexed { index, task ->
            val outcome = if (index == 0) BuildTaskOutcome.FAILED else BuildTaskOutcome.SKIPPED
            outcomes[task.id] = outcome
            results += BuildTaskResult(
                id = task.id,
                outcome = outcome,
                diagnostics = if (index == 0) listOf(diagnostic) else emptyList()
            )
        }
    }

    private fun elapsedMillis(started: Long): Long =
        ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = input.read(destination, offset, destination.size - offset)
            if (count < 0) throw ExecutionProblem(
                BuildOutcome.FAILED,
                diagnostic("EMBEDDED_JAVA_ARTIFACT_MISSING", "ECJ output class file is truncated", BuildDiagnosticSource.JAVA)
            )
            if (count > 0) offset += count
        }
    }

    private data class TaskStep(
        val taskOutcome: BuildTaskOutcome,
        val buildOutcome: BuildOutcome? = null,
        val diagnostics: List<BuildDiagnostic> = emptyList(),
        val artifacts: List<BuildArtifact> = emptyList()
    )

    private data class DesugaringOutputPaths(
        val keepRulesFile: File,
        val runtimeDexDirectory: Path
    )

    private data class ApkOutputPaths(
        val unalignedApk: Path,
        val alignedApk: Path
    )

    private data class ExecutionState(
        var androidJar: File? = null,
        val mavenInputsByModule: MutableMap<String, LockedMavenBuildInputs> = linkedMapOf(),
        val runtimeDexDirectoriesByModule: MutableMap<String, File> = linkedMapOf()
    )

    private class ExecutionProblem(
        val outcome: BuildOutcome,
        val diagnostic: BuildDiagnostic
    ) : RuntimeException(diagnostic.message)

    private companion object {
        const val MIN_CLASS_BYTES = 8L
        const val HASH_BUFFER_BYTES = 16 * 1024
        val CLASS_MAGIC = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
    }
}
