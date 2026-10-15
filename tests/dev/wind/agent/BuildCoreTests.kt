package dev.wind.agent

import dev.wind.agent.build.BuildBackendId
import dev.wind.agent.build.BuildBackendRegistry
import dev.wind.agent.build.BuildContext
import dev.wind.agent.build.BuildExecutionInputs
import dev.wind.agent.build.BuildOutcome
import dev.wind.agent.build.BuildDiagnostic
import dev.wind.agent.build.BuildDiagnosticSeverity
import dev.wind.agent.build.BuildDiagnosticSource
import dev.wind.agent.build.BuildPlan
import dev.wind.agent.build.BuildResult
import dev.wind.agent.build.BuildTaskOutcome
import dev.wind.agent.build.BuildTaskPlan
import dev.wind.agent.build.BuildTaskResult
import dev.wind.agent.build.Aapt2ResourceCompiler
import dev.wind.agent.build.Aapt2InvocationResult
import dev.wind.agent.build.Aapt2Provider
import dev.wind.agent.build.NativeAapt2Provider
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.D8Invocation
import dev.wind.agent.build.D8InvocationResult
import dev.wind.agent.build.D8Provider
import dev.wind.agent.build.EmbeddedCoreLibraryDesugaringSupport
import dev.wind.agent.build.EmbeddedBuildBackend
import dev.wind.agent.build.EmbeddedBuildExecutor
import dev.wind.agent.build.EmbeddedModuleKind
import dev.wind.agent.build.EmbeddedProjectModel
import dev.wind.agent.build.EmbeddedProjectModelParser
import dev.wind.agent.build.EcjCompilerProvider
import dev.wind.agent.build.EcjInvocation
import dev.wind.agent.build.EcjInvocationResult
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.ReflectionEcjCompilerProvider
import dev.wind.agent.build.ReflectionD8Provider
import dev.wind.agent.build.L8Invocation
import dev.wind.agent.build.L8InvocationResult
import dev.wind.agent.build.L8Provider
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.ReflectionL8Provider
import dev.wind.agent.build.ShellBuildBackend
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** P6-BE1/BE2/BE3/BE4 execution plus static model, provider selection and plan contracts. */
object BuildCoreTests {
    @JvmStatic
    fun main(args: Array<String>) {
        staticAndroidModel()
        sourceProfileResourceAndFingerprintTests()
        staticMultiModuleModel()
        dynamicGradleFailsClosed()
        dynamicIncludeFailsClosed()
        lexicalConstructsFailClosed()
        groovySlashyStringsFailClosed()
        metadataExpressionsFailClosed()
        metadataOwnershipAndDialectFailClosed()
        receiverOwnershipFailClosed()
        grammarBoundaryAndProfileTests()
        parserRejectsDynamicExpressionsAndStringLookalikes()
        parserRejectsUnknownDeclarationsAndUnscopedAndroidMetadata()
        parserScopeAndPluginBoundaryTests()
        freezeGrammarAdversarialTests()
        invalidModulePathSegmentsFailClosed()
        missingProjectDependencyFailsClosed()
        dependencyEdgesArePlanned()
        mixedLanguageCompileNodeIsAggregated()
        moduleTaskIdsAreCollisionFree()
        cyclicProjectDependenciesFailClosed()
        symlinkedInputsFailClosed()
        descendantSymlinkInputsFailClosed()
        inputManifestLimitsFailClosed()
        rawByteFingerprintTests()
        pathStabilityFailClosed()
        incompleteAndroidMetadataFailsClosed()
        planIsDeterministicAndProviderNeutral()
        planFingerprintIncludesExecutionInputs()
        planContextMismatchAndProjectFingerprintCoverage()
        immutablePlanSnapshots()
        planRejectsCycles()
        resultRejectsContradictorySuccess()
        embeddedBackendExecutesJavaOnlyPlan()
        androidBuildPlanAndProviderBoundaries()
        androidDexExecutionProviderBoundaries()
        androidCoreLibraryDesugaringBackendBoundaries()
        embeddedBackendExecutesAndroidResourcesAndJava()
        embeddedBackendExecutesAndroidCoreLibraryDesugaring()
        embeddedBackendFailsClosedAndClearsStaleOutput()
        embeddedBackendClearsPartialProviderOutput()
        embeddedBackendRejectsForgedPlanAndOptions()
        embeddedBackendRejectsUnsupportedShapes()
        embeddedBackendRejectsDependenciesAndTaskSelection()
        shellRouteRequiresExplicitSelectionAndHonorsCancellation()
        println("BuildCoreTests: OK")
    }

    private fun staticAndroidModel() {
        val root = Files.createTempDirectory("vega-build-model").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\":app\", \":core\")")
            val app = File(root, "app").apply { mkdirs() }
            val core = File(root, "core").apply { mkdirs() }
            File(app, "build.gradle.kts").writeText(
                """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }
                android {
                    namespace = "example.app"
                    compileSdk = 35
                    defaultConfig { minSdk = 23; targetSdk = 35 }
                }
                dependencies {
                    implementation(project(":core"))
                    implementation("com.example:local:1.0")
                    testImplementation("junit:junit:4.13.2")
                }
                """.trimIndent()
            )
            File(app, "src/main/java/example").mkdirs()
            File(app, "src/main/kotlin/example").mkdirs()
            File(app, "src/main/res/values").mkdirs()
            File(app, "src/main/AndroidManifest.xml").writeText("<manifest />")
            File(core, "build.gradle.kts").writeText("plugins { id(\"java-library\") }")
            File(core, "src/main/java/example").mkdirs()

            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { "static Android project was rejected: ${model.unsupportedReasons}" }
            val module = model.module(":app") ?: error("app module missing")
            check(module.name == ":app") { "module identity changed: ${module.name}" }
            check(module.kind == EmbeddedModuleKind.ANDROID_APPLICATION)
            check(module.namespace == "example.app")
            check(module.compileSdk == 35 && module.minSdk == 23 && module.targetSdk == 35)
            check(module.kotlinPlugin)
            check(module.sourceSet.javaDirectories.isNotEmpty())
            check(module.sourceSet.kotlinDirectories.isNotEmpty())
            check(module.sourceSet.manifest?.isFile == true)
            check(module.dependencies.any { it.projectPath == ":core" })
            check(module.dependencies.any { it.notation == "com.example:local:1.0" })
            check(model.module(":core")?.kind == EmbeddedModuleKind.JVM)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sourceProfileResourceAndFingerprintTests() {
        val jvmRoot = Files.createTempDirectory("vega-build-jvm-resource-profile").toFile()
        try {
            File(jvmRoot, "build.gradle.kts").writeText("plugins { java }")
            val mainResources = File(jvmRoot, "src/main/resources").apply { mkdirs() }
            File(mainResources, "main.properties").writeText("main=one")
            val testResources = File(jvmRoot, "src/test/resources").apply { mkdirs() }
            val testResourceFile = File(testResources, "test.properties")
            testResourceFile.writeText("test=one")
            File(jvmRoot, "src/main/res/values").mkdirs()

            val parser = EmbeddedProjectModelParser()
            val model = parser.parse(jvmRoot)
            check(model.supported) { "JVM resource profile was rejected: ${model.unsupportedReasons}" }
            val module = model.modules.single()
            check(module.kind == EmbeddedModuleKind.JVM)
            check(module.sourceSet.resourceDirectories == listOf(mainResources.canonicalFile)) {
                "JVM main resources were not modelled from src/main/resources"
            }
            check(module.sourceSet.testResourceDirectories == listOf(testResources.canonicalFile)) {
                "JVM test resources were not modelled from src/test/resources"
            }
            check(module.sourceSet.resourceDirectories.none { it.name == "res" }) {
                "JVM src/main/res was treated as a JVM resource directory"
            }
            check(module.sourceSet.manifest == null) { "JVM profile unexpectedly exposed an Android manifest" }

            val firstFingerprint = BuildExecutionInputs.from(
                BuildContext(model, File(jvmRoot, "out"))
            ).projectFingerprint
            testResourceFile.writeText("test=two")
            val secondFingerprint = BuildExecutionInputs.from(
                BuildContext(parser.parse(jvmRoot), File(jvmRoot, "out"))
            ).projectFingerprint
            check(firstFingerprint != secondFingerprint) {
                "JVM test-resource content did not affect the project fingerprint"
            }
        } finally {
            jvmRoot.deleteRecursively()
        }

        val androidRoot = Files.createTempDirectory("vega-build-android-resource-profile").toFile()
        try {
            File(androidRoot, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 35 }"
            )
            val androidResources = File(androidRoot, "src/main/res/values").apply { mkdirs() }.parentFile
            val manifest = File(androidRoot, "src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("<manifest />")
            }
            File(androidRoot, "src/main/resources").mkdirs()

            val model = EmbeddedProjectModelParser().parse(androidRoot)
            check(model.supported) { "Android resource profile was rejected: ${model.unsupportedReasons}" }
            val module = model.modules.single()
            check(module.kind == EmbeddedModuleKind.ANDROID_APPLICATION)
            check(module.sourceSet.resourceDirectories == listOf(androidResources.canonicalFile)) {
                "Android resources were not modelled from src/main/res"
            }
            check(module.sourceSet.testResourceDirectories.isEmpty()) {
                "Android test resources were unexpectedly modelled in this bounded profile"
            }
            check(module.sourceSet.manifest == manifest.canonicalFile) {
                "Android manifest was not modelled from src/main/AndroidManifest.xml"
            }
            check(module.sourceSet.resourceDirectories.none { it.name == "resources" }) {
                "Android src/main/resources was reclassified as Android resources"
            }
        } finally {
            androidRoot.deleteRecursively()
        }
    }

    private fun staticMultiModuleModel() {
        val root = Files.createTempDirectory("vega-build-multi").toFile()
        try {
            File(root, "settings.gradle").writeText("include ':app', ':core'")
            for (name in listOf("app", "core")) {
                val module = File(root, name).apply { mkdirs() }
                File(module, "build.gradle").writeText(
                    if (name == "core") "plugins { java-library }" else "plugins { id 'java-library' }"
                )
                File(module, "src/main/java/example").mkdirs()
            }
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { "multi-module JVM model rejected: ${model.unsupportedReasons}" }
            check(model.modules.map { it.name } == listOf(":app", ":core"))
            check(model.modules.all { it.kind == EmbeddedModuleKind.JVM })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun dynamicGradleFailsClosed() {
        val root = Files.createTempDirectory("vega-build-unsupported").toFile()
        try {
            File(root, "build.gradle.kts").writeText(
                "plugins { java }\nsubprojects { afterEvaluate { println(\"dynamic\") } }"
            )
            val model = EmbeddedProjectModelParser().parse(root)
            check(!model.supported) { "dynamic Gradle was accepted by the static parser" }
            check(model.unsupportedReasons.any { it.contains("EMBEDDED_PROJECT_MODEL_UNSUPPORTED") })
            check(model.diagnostics.any { it.source == BuildDiagnosticSource.PROJECT })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun dynamicIncludeFailsClosed() {
        val root = Files.createTempDirectory("vega-build-dynamic-include").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("val modules = listOf(\":app\")\ninclude(modules[0])")
            File(root, "app").mkdirs()
            File(root, "app/build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(root)
            check(!model.supported)
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_INCLUDE") })
        } finally {
            root.deleteRecursively()
        }

        listOf(
            "if (flag)\n    include(\":app\")",
            "val unused = {\n    include(\":app\")\n}",
            "val module = \":app\"\nval marker = \"${'$'}{include(module)}\"",
            "include(\":app\")include(\":core\")",
            "receiver.include(\":app\")"
        ).forEach { settingsScript ->
            val scopedRoot = Files.createTempDirectory("vega-build-scoped-include").toFile()
            try {
                File(scopedRoot, "settings.gradle.kts").writeText(settingsScript)
                for (module in listOf("app", "core")) {
                    File(scopedRoot, module).mkdirs()
                    File(scopedRoot, "$module/build.gradle.kts").writeText("plugins { java }")
                }
                val model = EmbeddedProjectModelParser().parse(scopedRoot)
                check(!model.supported) { "scoped literal include was accepted: $settingsScript" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_INCLUDE") })
            } finally {
                scopedRoot.deleteRecursively()
            }
        }

        val lexical = Files.createTempDirectory("vega-build-lexical-mask").toFile()
        try {
            File(lexical, "build.gradle.kts").writeText(
                "plugins { java }\n" +
                    "val ordinary = \"()[]{}\"\n" +
                    "val raw = \"\"\"text \\\" } still text\"\"\"\n" +
                    "/* outer /* inner */ } */"
            )
            val model = EmbeddedProjectModelParser().parse(lexical)
            check(!model.supported) { "unmodeled lexical declarations were accepted: ${model.unsupportedReasons}" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_STATEMENT") })
        } finally {
            lexical.deleteRecursively()
        }
    }

    private fun lexicalConstructsFailClosed() {
        listOf(
            "plugins { java }\nval x = \"unterminated",
            "plugins { java }\nval x = 'unterminated",
            "plugins { java }\nval x = \"\"\"unterminated",
            "plugins { java }\n/* unterminated",
            "plugins { java }\n/* outer /* inner */"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-unterminated-lexical").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unterminated lexical construct was accepted: $script" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_UNTERMINATED_LEXICAL_CONSTRUCT")
                }) { "missing lexical completeness diagnostic: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        val settingsRoot = Files.createTempDirectory("vega-build-settings-unterminated").toFile()
        try {
            File(settingsRoot, "settings.gradle.kts").writeText(
                "include(\":app\")\nval marker = \"unterminated"
            )
            File(settingsRoot, "app").mkdirs()
            File(settingsRoot, "app/build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(settingsRoot)
            check(!model.supported) { "unterminated settings lexical construct was accepted" }
            check(model.unsupportedReasons.any {
                it.startsWith("EMBEDDED_UNTERMINATED_LEXICAL_CONSTRUCT")
            })
        } finally {
            settingsRoot.deleteRecursively()
        }

        listOf(
            "plugins { java }\nval value = \"${'$'}π\"",
            "plugins { java }\nval value = \"\"\"${'$'}π\"\"\""
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-unicode-interpolation").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unicode interpolation was accepted: $script" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_DYNAMIC_STRING_INTERPOLATION")
                }) { "missing interpolation diagnostic: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        val escapedDollar = "plugins { java }\nval value = \"\\${'$'}literal\""
        val root = Files.createTempDirectory("vega-build-escaped-dollar").toFile()
        try {
            File(root, "build.gradle.kts").writeText(escapedDollar)
            val model = EmbeddedProjectModelParser().parse(root)
            check(!model.supported) { "escaped literal was accepted outside the bounded grammar: ${model.unsupportedReasons}" }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun groovySlashyStringsFailClosed() {
        listOf(
            "plugins { id 'java' }\ndescription = /plugins { java }/",
            "plugins { id 'java' }\ndescription = /dependencies { implementation \"ghost:fake:1.0\" }/",
            "plugins { id 'java' }\ndescription = /android { compileSdk = 999 }/",
            "plugins { id 'java' }\ndescription = ${'$'}/dependencies { implementation \"ghost:fake:1.0\" }/${'$'}"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-groovy-slashy").toFile()
            try {
                File(root, "build.gradle").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "Groovy slashy string was accepted: $script" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_UNSUPPORTED_GROOVY_SLASHY_STRING")
                }) { "missing slashy diagnostic: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        val multilineOrdinary = "plugins { java }\nval text = \"line one\nline two\""
        val ordinaryRoot = Files.createTempDirectory("vega-build-newline-string").toFile()
        try {
            File(ordinaryRoot, "build.gradle.kts").writeText(multilineOrdinary)
            val model = EmbeddedProjectModelParser().parse(ordinaryRoot)
            check(!model.supported) { "ordinary string newline was accepted" }
            check(model.unsupportedReasons.any {
                it.startsWith("EMBEDDED_UNTERMINATED_LEXICAL_CONSTRUCT")
            })
        } finally {
            ordinaryRoot.deleteRecursively()
        }

        val tripleRoot = Files.createTempDirectory("vega-build-newline-triple").toFile()
        try {
            File(tripleRoot, "build.gradle.kts").writeText(
                "plugins { java }\nval text = \"\"\"line one\nline two\"\"\""
            )
            val model = EmbeddedProjectModelParser().parse(tripleRoot)
            check(!model.supported) { "unmodeled multiline declaration was accepted" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_STATEMENT") })
        } finally {
            tripleRoot.deleteRecursively()
        }
    }

    private fun metadataExpressionsFailClosed() {
        listOf(
            "plugins { id(\"com.android.application\") }\n" +
                "android { namespace = \"com.\" + \"example\"; compileSdk = 35 }",
            "plugins { id(\"com.android.application\") }\n" +
                "android { namespace = \"example.app\"; compileSdk = 30 + 5 }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-android-expression").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "dynamic Android metadata was accepted: $script" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_ANDROID_DSL_UNSUPPORTED")
                }) { "missing Android metadata diagnostic: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins { id(\"org.jetbrains.kotlin.jvm\") version(\"1\" + \"2\") }",
            "plugins { id('org.jetbrains.kotlin.jvm') version('1' + '2') }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-plugin-version-expression").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "dynamic plugin version was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins { java }\nval text = \"line one\\\nline two\"",
            "plugins { java }\nval text = \"line one\\\r\nline two\""
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-escaped-newline").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "escaped ordinary newline was accepted" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_UNTERMINATED_LEXICAL_CONSTRUCT")
                })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun metadataOwnershipAndDialectFailClosed() {
        listOf(
            "plugins { id(\"com.android.application\") }\n" +
                "android { val compileSdk = 35; namespace = \"example.app\" }",
            "plugins { id(\"com.android.application\") }\n" +
                "android { holder.compileSdk = 35; namespace = \"example.app\" }",
            "plugins { id(\"com.android.application\") }\n" +
                "android { compileSdk = 35; compileSdk = 34; namespace = \"example.app\" }",
            "plugins { id(\"com.android.application\") }\n" +
                "android { compileSdk = 35; compileSdkVersion = 34; namespace = \"example.app\" }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-android-ownership").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unowned or duplicate Android metadata was accepted: $script" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_ANDROID_DSL_UNSUPPORTED")
                }) { "missing Android ownership diagnostic: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins { id(\"org.jetbrains.kotlin.jvm\") version('2.0') }",
            "plugins { id(\"com.android.application\") }\n" +
                "android { namespace = 'example.app'; compileSdk = 35 }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-kotlin-dialect").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "Kotlin DSL single-quoted literal was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        val groovyRoot = Files.createTempDirectory("vega-build-groovy-dialect").toFile()
        try {
            File(groovyRoot, "build.gradle").writeText(
                "plugins { id 'org.jetbrains.kotlin.jvm' version '2.0' }"
            )
            val model = EmbeddedProjectModelParser().parse(groovyRoot)
            check(model.supported) {
                "Groovy single-quoted plugin declaration was rejected: ${model.unsupportedReasons}"
            }
            check(model.modules.single().kotlinPlugin)
        } finally {
            groovyRoot.deleteRecursively()
        }

        listOf(
            "plugins { id(\"com.android.application\") }\n" +
                "android { namespace = \"example.app\"; compileSdk 35 }",
            "plugins { id(\"com.android.application\") }\n" +
                "android { namespace \"example.app\"; compileSdk = 35 }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-kotlin-command-metadata").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "Groovy command-style Android metadata was accepted in KTS: $script" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_ANDROID_DSL_UNSUPPORTED")
                })
            } finally {
                root.deleteRecursively()
            }
        }

        val groovyAndroid = Files.createTempDirectory("vega-build-groovy-command-metadata").toFile()
        try {
            File(groovyAndroid, "build.gradle").writeText(
                "plugins { id 'com.android.application' }\n" +
                    "android { namespace 'example.app'; compileSdk 35 }"
            )
            val model = EmbeddedProjectModelParser().parse(groovyAndroid)
            check(model.supported) {
                "Groovy command-style Android metadata was rejected: ${model.unsupportedReasons}"
            }
            val module = model.modules.single()
            check(module.namespace == "example.app" && module.compileSdk == 35)
        } finally {
            groovyAndroid.deleteRecursively()
        }

        val kotlinApply = Files.createTempDirectory("vega-build-kotlin-legacy-apply").toFile()
        try {
            File(kotlinApply, "build.gradle.kts").writeText("apply plugin: \"java\"")
            val model = EmbeddedProjectModelParser().parse(kotlinApply)
            check(!model.supported) { "Groovy legacy apply syntax was accepted in KTS" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_PLUGIN") })
        } finally {
            kotlinApply.deleteRecursively()
        }

        val groovyApply = Files.createTempDirectory("vega-build-groovy-legacy-apply").toFile()
        try {
            File(groovyApply, "build.gradle").writeText("apply plugin: 'java'")
            val model = EmbeddedProjectModelParser().parse(groovyApply)
            check(model.supported) {
                "Groovy legacy apply syntax was rejected: ${model.unsupportedReasons}"
            }
        } finally {
            groovyApply.deleteRecursively()
        }

        val kotlinDependency = Files.createTempDirectory("vega-build-kotlin-command-dependency").toFile()
        try {
            File(kotlinDependency, "build.gradle.kts").writeText(
                "plugins { java }\ndependencies { implementation \"com.example:local:1.0\" }"
            )
            val model = EmbeddedProjectModelParser().parse(kotlinDependency)
            check(!model.supported) { "Groovy command-style dependency syntax was accepted in KTS" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
        } finally {
            kotlinDependency.deleteRecursively()
        }

        val groovyDependency = Files.createTempDirectory("vega-build-groovy-command-dependency").toFile()
        try {
            File(groovyDependency, "build.gradle").writeText(
                "plugins { id 'java' }\ndependencies { implementation 'com.example:local:1.0' }"
            )
            val model = EmbeddedProjectModelParser().parse(groovyDependency)
            check(model.supported) {
                "Groovy command-style dependency syntax was rejected: ${model.unsupportedReasons}"
            }
            check(model.modules.single().dependencies.single().notation == "com.example:local:1.0")
        } finally {
            groovyDependency.deleteRecursively()
        }

        val kotlinInclude = Files.createTempDirectory("vega-build-kotlin-command-include").toFile()
        try {
            File(kotlinInclude, "settings.gradle.kts").writeText("include ':app'")
            File(kotlinInclude, "app").mkdirs()
            File(kotlinInclude, "app/build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(kotlinInclude)
            check(!model.supported) { "Groovy command-style include syntax was accepted in KTS" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_INCLUDE") })
        } finally {
            kotlinInclude.deleteRecursively()
        }

        val groovyInclude = Files.createTempDirectory("vega-build-groovy-command-include").toFile()
        try {
            File(groovyInclude, "settings.gradle").writeText("include ':app'")
            File(groovyInclude, "app").mkdirs()
            File(groovyInclude, "app/build.gradle").writeText("plugins { id 'java' }")
            val model = EmbeddedProjectModelParser().parse(groovyInclude)
            check(model.supported) {
                "Groovy command-style include syntax was rejected: ${model.unsupportedReasons}"
            }
            check(model.modules.single().name == ":app")
        } finally {
            groovyInclude.deleteRecursively()
        }
    }

    private fun receiverOwnershipFailClosed() {
        val qualifiedPlugin = Files.createTempDirectory("vega-build-qualified-plugin").toFile()
        try {
            File(qualifiedPlugin, "build.gradle").writeText(
                "plugins { id 'java' }\n" +
                    "rootProject.plugins { id 'java-library' }"
            )
            val model = EmbeddedProjectModelParser().parse(qualifiedPlugin)
            check(!model.supported) { "qualified plugins block was accepted" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_PLUGIN") })
        } finally {
            qualifiedPlugin.deleteRecursively()
        }

        val qualifiedDependencies = Files.createTempDirectory("vega-build-qualified-dependencies").toFile()
        try {
            File(qualifiedDependencies, "build.gradle").writeText(
                "plugins { id 'java' }\n" +
                    "rootProject.dependencies { implementation 'wrong:owner:1.0' }"
            )
            val model = EmbeddedProjectModelParser().parse(qualifiedDependencies)
            check(!model.supported) { "qualified dependencies block was accepted" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
        } finally {
            qualifiedDependencies.deleteRecursively()
        }

        val qualifiedAndroid = Files.createTempDirectory("vega-build-qualified-android").toFile()
        try {
            File(qualifiedAndroid, "build.gradle").writeText(
                "plugins { id 'com.android.application' }\n" +
                    "rootProject.android { namespace 'wrong.owner'; compileSdk 35 }"
            )
            val model = EmbeddedProjectModelParser().parse(qualifiedAndroid)
            check(!model.supported) { "qualified android block was accepted" }
            check(model.unsupportedReasons.any {
                it.startsWith("EMBEDDED_ANDROID_DSL_UNSUPPORTED")
            })
        } finally {
            qualifiedAndroid.deleteRecursively()
        }

        listOf(
            "rootProject.android { namespace 'wrong.owner'; compileSdk 35 }",
            "android { compileSdk 35 }"
        ).forEach { androidBlock ->
            val jvmRoot = Files.createTempDirectory("vega-build-jvm-android-scope").toFile()
            try {
                File(jvmRoot, "build.gradle").writeText("plugins { id 'java' }\n$androidBlock")
                val model = EmbeddedProjectModelParser().parse(jvmRoot)
                check(!model.supported) { "Android DSL was ignored in a JVM module: $androidBlock" }
                check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_ANDROID_DSL_UNSUPPORTED")
                })
            } finally {
                jvmRoot.deleteRecursively()
            }
        }

        listOf("rootProject.apply plugin: 'java'", "holder.apply plugin: 'java'")
            .forEach { script ->
                val root = Files.createTempDirectory("vega-build-qualified-apply").toFile()
                try {
                    File(root, "build.gradle").writeText(script)
                    val model = EmbeddedProjectModelParser().parse(root)
                    check(!model.supported) { "qualified legacy apply was accepted: $script" }
                    check(model.unsupportedReasons.any {
                        it.startsWith("EMBEDDED_UNSUPPORTED_PLUGIN")
                    })
                } finally {
                    root.deleteRecursively()
                }
            }
    }

    private fun grammarBoundaryAndProfileTests() {
        listOf(
            "plugins { java }\njava { toolchain { languageVersion = 17 } }",
            "plugins { java }\ntasks.named(\"compileJava\") { }",
            "plugins { java }\nrepositories { mavenCentral() }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-unconsumed-top-level").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unconsumed top-level Gradle syntax was accepted: $script" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_STATEMENT") })
            } finally {
                root.deleteRecursively()
            }
        }

        val settingsRoot = Files.createTempDirectory("vega-build-unknown-settings-production").toFile()
        try {
            File(settingsRoot, "settings.gradle.kts").writeText("includeBuild(\"tools\")")
            val model = EmbeddedProjectModelParser().parse(settingsRoot)
            check(!model.supported) { "includeBuild was silently ignored" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_STATEMENT") })
        } finally {
            settingsRoot.deleteRecursively()
        }

        listOf(
            "rootProject.\nplugins { id 'java' }",
            "rootProject.\nandroid { compileSdk 35 }",
            "rootProject.\ndependencies { implementation 'g:a:1.0' }",
            "rootProject.\napply plugin: 'java'"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-receiver-newline").toFile()
            try {
                File(root, "build.gradle").writeText("plugins { id 'java' }\n$script")
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "newline receiver bypass was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        val tripleGroovy = Files.createTempDirectory("vega-build-groovy-triple-single").toFile()
        try {
            File(tripleGroovy, "build.gradle").writeText(
                "plugins { id 'java' }\n" +
                    "dependencies { implementation '''x' ; dependencies { implementation 'ghost:fake:1.0' }; 'y''' }"
            )
            val model = EmbeddedProjectModelParser().parse(tripleGroovy)
            check(!model.supported) { "Groovy triple-single string leaked fake DSL" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
        } finally {
            tripleGroovy.deleteRecursively()
        }

        val topology = Files.createTempDirectory("vega-build-root-topology").toFile()
        try {
            File(topology, "settings.gradle.kts").writeText("rootProject.name = \"sample\"\ninclude(\":app\")")
            File(topology, "build.gradle.kts").writeText("plugins { java }")
            File(topology, "app").mkdirs()
            File(topology, "app/build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(topology)
            check(model.supported) { "root project was not modelled: ${model.unsupportedReasons}" }
            check(model.module(":") != null && model.module(":app") != null)
            check(model.module(":")!!.gradlePath == ":")
        } finally {
            topology.deleteRecursively()
        }

        val sourceFallback = Files.createTempDirectory("vega-build-source-fallback").toFile()
        try {
            File(sourceFallback, "build.gradle.kts").writeText("")
            File(sourceFallback, "src/main/java/example").mkdirs()
            val model = EmbeddedProjectModelParser().parse(sourceFallback)
            check(!model.supported) { "source directories inferred a JVM module without a plugin" }
        } finally {
            sourceFallback.deleteRecursively()
        }

        val androidUnknown = Files.createTempDirectory("vega-build-android-unknown-dsl").toFile()
        try {
            File(androidUnknown, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\n" +
                    "android { compileSdk = 35; compileOptions { sourceCompatibility = JavaVersion.VERSION_17 } }"
            )
            val model = EmbeddedProjectModelParser().parse(androidUnknown)
            check(!model.supported) { "unmodeled Android DSL was silently ignored" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_ANDROID_DSL_UNSUPPORTED") })
        } finally {
            androidUnknown.deleteRecursively()
        }

        val dualScripts = Files.createTempDirectory("vega-build-dual-scripts").toFile()
        try {
            File(dualScripts, "build.gradle.kts").writeText("plugins { java }")
            File(dualScripts, "build.gradle").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(dualScripts)
            check(!model.supported)
            check(model.unsupportedReasons.contains("EMBEDDED_DUAL_GRADLE_SCRIPTS"))
        } finally {
            dualScripts.deleteRecursively()
        }

        val dynamicCoordinate = Files.createTempDirectory("vega-build-dynamic-coordinate").toFile()
        try {
            File(dynamicCoordinate, "build.gradle.kts").writeText(
                "plugins { id(\"java-library\") }\n" +
                    "dependencies { api(\"com.example:lib:[1,2)\") }"
            )
            val model = EmbeddedProjectModelParser().parse(dynamicCoordinate)
            check(!model.supported)
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
        } finally {
            dynamicCoordinate.deleteRecursively()
        }

        val metadataValues = listOf(
            "plugins { id(\"com.android.application\") }\nandroid { compileSdk = \"35\" }",
            "plugins { id(\"com.android.application\") }\nandroid { compileSdk = -1 }",
            "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 35; defaultConfig { minSdk = 30; targetSdk = 29 } }"
        )
        metadataValues.forEach { script ->
            val root = Files.createTempDirectory("vega-build-metadata-values").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "invalid Android metadata value was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun missingProjectDependencyFailsClosed() {
        val root = Files.createTempDirectory("vega-build-missing-dependency").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("include(\":app\")")
            File(root, "app").mkdirs()
            File(root, "app/build.gradle.kts").writeText(
                "plugins { java }\ndependencies { implementation(project(\":missing\")) }"
            )
            val model = EmbeddedProjectModelParser().parse(root)
            check(!model.supported)
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_PROJECT_DEPENDENCY_MISSING") })
            check(model.diagnostics.any { it.code == "EMBEDDED_PROJECT_DEPENDENCY_MISSING" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun dependencyEdgesArePlanned() {
        val root = Files.createTempDirectory("vega-build-dependency-edges").toFile()
        try {
            File(root, "settings.gradle").writeText("include ':app', ':core'")
            for (name in listOf("app", "core")) {
                val module = File(root, name).apply { mkdirs() }
                File(module, "build.gradle").writeText(
                    if (name == "app") "plugins { id 'java' }\ndependencies { implementation project(':core') }"
                    else "plugins { id 'java-library' }"
                )
                File(module, "src/main/java/example").mkdirs()
            }
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { model.unsupportedReasons.toString() }
            val context = BuildContext(model, File(root, "build-out"))
            val plan = dev.wind.agent.build.EmbeddedBuildBackend().plan(context)
            val coreSuffix = moduleSuffix(":core")
            val appSuffix = moduleSuffix(":app")
            val coreCompile = plan.tasks.indexOfFirst { it.id == "compile-java:$coreSuffix" }
            val appResolve = plan.tasks.first { it.id == "resolve-classpath:$appSuffix" }
            check(coreCompile >= 0)
            check("compile-java:$coreSuffix" in appResolve.dependsOn)
            check(coreCompile < plan.tasks.indexOf(appResolve))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun mixedLanguageCompileNodeIsAggregated() {
        val root = Files.createTempDirectory("vega-build-mixed-compile-plan").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("include(\":app\", \":core\", \":kotlin-only\")")
            val app = File(root, "app").apply { mkdirs() }
            File(app, "build.gradle.kts").writeText(
                """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }
                android {
                    namespace = "example.app"
                    compileSdk = 35
                }
                dependencies { implementation(project(":core")) }
                """.trimIndent()
            )
            File(app, "src/main/java/example").mkdirs()
            File(app, "src/main/kotlin/example").mkdirs()
            File(app, "src/main/res/values").mkdirs()
            File(app, "src/main/AndroidManifest.xml").writeText("<manifest />")
            File(app, "src/test/java/example").mkdirs()
            File(app, "src/test/kotlin/example").mkdirs()

            val core = File(root, "core").apply { mkdirs() }
            File(core, "build.gradle.kts").writeText(
                "plugins { id(\"java-library\"); id(\"org.jetbrains.kotlin.jvm\") }"
            )
            File(core, "src/main/java/example").mkdirs()
            File(core, "src/main/kotlin/example").mkdirs()

            val kotlinOnly = File(root, "kotlin-only").apply { mkdirs() }
            File(kotlinOnly, "build.gradle.kts").writeText("plugins { id(\"org.jetbrains.kotlin.jvm\") }")
            File(kotlinOnly, "src/main/kotlin/example").mkdirs()

            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { "mixed compile fixture was rejected: ${model.unsupportedReasons}" }
            val plan = EmbeddedBuildBackend().plan(BuildContext(model, File(root, "build-out")))
            val appSuffix = moduleSuffix(":app")
            val coreSuffix = moduleSuffix(":core")
            val kotlinOnlySuffix = moduleSuffix(":kotlin-only")
            val tasksById = plan.tasks.associateBy { it.id }

            val appCompileId = "compile-mixed:$appSuffix"
            val coreCompileId = "compile-mixed:$coreSuffix"
            val appResolveId = "resolve-classpath:$appSuffix"
            check(tasksById[appCompileId]?.dependsOn == listOf("link-resources:$appSuffix")) {
                "Android mixed sources must share one compile node after resource linking: ${tasksById[appCompileId]}"
            }
            check(tasksById[coreCompileId] != null) { "mixed JVM module compile node missing" }
            check("compile-java:$appSuffix" !in tasksById && "compile-kotlin:$appSuffix" !in tasksById) {
                "Android mixed sources retained split compile tasks"
            }
            check("compile-java:$coreSuffix" !in tasksById && "compile-kotlin:$coreSuffix" !in tasksById) {
                "JVM mixed sources retained split compile tasks"
            }

            val appResolve = tasksById.getValue(appResolveId)
            check(appResolve.dependsOn == listOf(
                "resolve-project", "resolve-classpath:$coreSuffix", coreCompileId
            )) { "consumer classpath must wait for the dependency's aggregated compile node: $appResolve" }
            check(plan.tasks.indexOf(tasksById.getValue(coreCompileId)) < plan.tasks.indexOf(appResolve)) {
                "dependency compile node was planned after its consumer classpath"
            }

            check(tasksById.getValue("compile-resources:$appSuffix").dependsOn ==
                listOf(appResolveId)) {
                "Android resource compilation must follow classpath resolution"
            }
            check(tasksById.getValue("dex:$appSuffix").dependsOn == listOf(appCompileId)) {
                "DEX conversion must consume the one mixed compile output"
            }
            val appTest = tasksById.getValue("run-tests:$appSuffix")
            check(appCompileId in appTest.dependsOn) {
                "test execution must consume the module's aggregated compile node"
            }
            check("compile-java:$appSuffix" !in appTest.dependsOn &&
                "compile-kotlin:$appSuffix" !in appTest.dependsOn
            ) { "test execution retained stale split compile dependencies" }
            check(tasksById.getValue("compile-kotlin:$kotlinOnlySuffix")?.dependsOn ==
                listOf("resolve-classpath:$kotlinOnlySuffix")) {
                "Kotlin-only task ID or dependency changed"
            }
            check("compile-java:$kotlinOnlySuffix" !in tasksById && "compile-mixed:$kotlinOnlySuffix" !in tasksById) {
                "Kotlin-only module received an unrelated compile task"
            }
            check(plan.tasks.map { it.id }.distinct().size == plan.tasks.size)
            check(plan.tasks.all { task -> task.dependsOn.all { dependency -> dependency in tasksById } }) {
                "mixed-language plan contains a dangling dependency"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun cyclicProjectDependenciesFailClosed() {
        val root = Files.createTempDirectory("vega-build-cycle").toFile()
        try {
            File(root, "settings.gradle").writeText("include ':a', ':b'")
            for (name in listOf("a", "b")) {
                val other = if (name == "a") "b" else "a"
                val module = File(root, name).apply { mkdirs() }
                File(module, "build.gradle").writeText(
                    "plugins { id 'java' }\ndependencies { implementation project(':$other') }"
                )
            }
            val model = EmbeddedProjectModelParser().parse(root)
            check(!model.supported)
            check(model.unsupportedReasons.contains("EMBEDDED_PROJECT_DEPENDENCY_CYCLE"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun parserRejectsDynamicExpressionsAndStringLookalikes() {
        val cases = listOf(
            "include(\":app\" + suffix)" to "plugins { java }",
            "include(prefix + \":app\")" to "plugins { java }",
            "include(\":app\", dynamic)" to "plugins { java }",
            "include(\":app\")" to "plugins { java }\ndependencies { implementation(dep) }",
            "include(\":app\")" to "plugins { java }; dependencies { implementation(provider.get()) }",
            "include(\":app\")" to "plugins { java }\ndependencies { api(projectPathVariable) }",
            "include(\":app\")" to "plugins { java }\ndependencies { compileOnly(files(\"libs\")) }"
        )
        cases.forEachIndexed { index, (settings, build) ->
            val root = Files.createTempDirectory("vega-build-dynamic-$index").toFile()
            try {
                File(root, "settings.gradle.kts").writeText(settings)
                File(root, "app").mkdirs()
                File(root, "app/build.gradle.kts").writeText(build)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "dynamic expression was accepted: $settings / $build" }
                if (index >= 3) check(model.unsupportedReasons.any {
                    it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY")
                })
            } finally {
                root.deleteRecursively()
            }
        }

        val lookalikeRoot = Files.createTempDirectory("vega-build-string-lookalike").toFile()
        try {
            File(lookalikeRoot, "build.gradle.kts").writeText(
                "plugins { java }\nprintln(\"compileSdk = 35\")\nval p = \"com.android.application\""
            )
            val model = EmbeddedProjectModelParser().parse(lookalikeRoot)
            check(!model.supported)
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_STATEMENT") })
        } finally {
            lookalikeRoot.deleteRecursively()
        }
    }

    private fun invalidModulePathSegmentsFailClosed() {
        listOf(":a::b", ":a:", "::a").forEach { name ->
            val root = Files.createTempDirectory("vega-build-invalid-module").toFile()
            try {
                File(root, "settings.gradle.kts").writeText("include(\"$name\")")
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported)
                check(model.unsupportedReasons.contains("EMBEDDED_MODULE_PATH_INVALID:$name")) {
                    "invalid module path was normalized: $name -> ${model.unsupportedReasons}"
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun parserRejectsUnknownDeclarationsAndUnscopedAndroidMetadata() {
        listOf(
            "annotationProcessor(\"com.example:processor:1.0\")",
            "kapt(\"com.example:processor:1.0\")",
            "debugImplementation(\"com.example:debug:1.0\")"
        ).forEach { declaration ->
            val root = Files.createTempDirectory("vega-build-unknown-dependency").toFile()
            try {
                File(root, "build.gradle.kts").writeText(
                    "plugins { java }\ndependencies { $declaration }"
                )
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unknown dependency configuration was ignored: $declaration" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins { java; id(\"com.google.protobuf\") }",
            "plugins { id(pluginName); java }"
        ).forEach { plugins ->
            val root = Files.createTempDirectory("vega-build-unknown-plugin").toFile()
            try {
                File(root, "build.gradle.kts").writeText(plugins)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unknown/dynamic plugin was ignored: $plugins" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_PLUGIN") })
            } finally {
                root.deleteRecursively()
            }
        }

        val unscoped = Files.createTempDirectory("vega-build-unscoped-sdk").toFile()
        try {
            File(unscoped, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\nval compileSdk = 35"
            )
            val model = EmbeddedProjectModelParser().parse(unscoped)
            check(!model.supported) { "top-level compileSdk was accepted as Android DSL metadata" }
        } finally {
            unscoped.deleteRecursively()
        }

        val scoped = Files.createTempDirectory("vega-build-scoped-sdk").toFile()
        try {
            File(scoped, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\nandroid { namespace = \"example.app\"; compileSdk = 35 }"
            )
            val model = EmbeddedProjectModelParser().parse(scoped)
            check(model.supported) { "valid Android DSL metadata was rejected: ${model.unsupportedReasons}" }
            check(model.modules.single().compileSdk == 35)
            check(model.modules.single().namespace == "example.app")
        } finally {
            scoped.deleteRecursively()
        }
    }

    private fun parserScopeAndPluginBoundaryTests() {
        listOf(
            "plugins { id(\"java\") apply false }",
            "plugins { java; apply(pluginName) }",
            "plugins { id(\"java\") version(pluginVersion) }",
            "plugins { ksp(\"com.google.devtools.ksp\") }",
            "plugins { id(\"java\") { unknown() } }",
            "apply plugin: \"java\" + suffix",
            "plugins { id(\"org.jetbrains.kotlin.jvm\") version(\"" + '$' + "pluginVersion\") }",
            "plugins { id(\"org.jetbrains.kotlin.jvm\") version(\"1\" + \"2\") }",
            "plugins { id('org.jetbrains.kotlin.jvm') version('1' + '2') }",
            "if (flag) { apply plugin: \"java\" }",
            "if (flag)\n    apply plugin: \"java\"",
            "plugins { version(\"1.0\"); java }",
            "plugins { id(\"java\") version(\"1.0\") }",
            "plugins { id(\"org.jetbrains.kotlin.android\") id(\"com.android.application\") }\nandroid { compileSdk = 35 }"
        ).forEach { plugins ->
            val root = Files.createTempDirectory("vega-build-plugin-boundary").toFile()
            try {
                File(root, "build.gradle.kts").writeText(plugins)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "plugin modifier/dynamic declaration was accepted: $plugins" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_PLUGIN") })
            } finally {
                root.deleteRecursively()
            }
        }

        val positivePlugins = listOf(
            "plugins { id(\"org.jetbrains.kotlin.jvm\") }",
            "plugins { kotlin(\"jvm\") }"
        )
        positivePlugins.forEach { plugins ->
            val root = Files.createTempDirectory("vega-build-plugin-positive").toFile()
            try {
                File(root, "build.gradle.kts").writeText(plugins)
                val model = EmbeddedProjectModelParser().parse(root)
                check(model.supported) { "supported Kotlin plugin form was rejected: $plugins / ${model.unsupportedReasons}" }
                check(model.modules.single().kind == EmbeddedModuleKind.JVM)
                check(model.modules.single().kotlinPlugin)
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins { id(\"org.jetbrains.kotlin.multiplatform\") }",
            "plugins { kotlin(\"multiplatform\") }",
            "plugins { kotlin(\"android\") }"
        ).forEach { plugins ->
            val root = Files.createTempDirectory("vega-build-plugin-profile-negative").toFile()
            try {
                File(root, "build.gradle.kts").writeText(plugins)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unsupported Kotlin profile was accepted: $plugins" }
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins {\n    java\n    kotlin(\"jvm\")\n}",
            "plugins { java; kotlin(\"jvm\") }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-plugin-mixed-positive").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(model.supported) { "bare java plus Kotlin plugin was rejected: ${model.unsupportedReasons}" }
                check(model.modules.single().kind == EmbeddedModuleKind.JVM)
                check(model.modules.single().kotlinPlugin)
            } finally {
                root.deleteRecursively()
            }
        }

        val staticVersion = Files.createTempDirectory("vega-build-static-plugin-version").toFile()
        try {
            File(staticVersion, "build.gradle.kts").writeText(
                "plugins { id(\"org.jetbrains.kotlin.jvm\") version \"2.0\" }"
            )
            val model = EmbeddedProjectModelParser().parse(staticVersion)
            check(model.supported) { "static plugin version was rejected: ${model.unsupportedReasons}" }
            check(model.modules.single().kotlinPlugin)
        } finally {
            staticVersion.deleteRecursively()
        }

        val multilineStaticVersion = Files.createTempDirectory("vega-build-multiline-static-plugin-version").toFile()
        try {
            File(multilineStaticVersion, "build.gradle.kts").writeText(
                "plugins {\n    id(\"org.jetbrains.kotlin.jvm\")\n    version \"2.0\"\n}"
            )
            val model = EmbeddedProjectModelParser().parse(multilineStaticVersion)
            check(model.supported) { "newline-separated static plugin version was rejected: ${model.unsupportedReasons}" }
            check(model.modules.single().kotlinPlugin)
        } finally {
            multilineStaticVersion.deleteRecursively()
        }

        val library = Files.createTempDirectory("vega-build-library-positive").toFile()
        try {
            File(library, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.library\") }\nandroid { compileSdk = 35 }"
            )
            val model = EmbeddedProjectModelParser().parse(library)
            check(model.supported)
            check(model.modules.single().kind == EmbeddedModuleKind.ANDROID_LIBRARY)
        } finally {
            library.deleteRecursively()
        }

        val androidKotlin = Files.createTempDirectory("vega-build-kotlin-android-positive").toFile()
        try {
            File(androidKotlin, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\"); kotlin(\"android\") }\nandroid { compileSdk = 35 }"
            )
            val model = EmbeddedProjectModelParser().parse(androidKotlin)
            check(model.supported) { "supported kotlin(\"android\") form was rejected: ${model.unsupportedReasons}" }
            check(model.modules.single().kind == EmbeddedModuleKind.ANDROID_APPLICATION)
            check(model.modules.single().kotlinPlugin)
        } finally {
            androidKotlin.deleteRecursively()
        }

        listOf(
            "plugins { id(\"com.android.application\") }\nif (false) { android { compileSdk = 35 } }",
            "plugins { id(\"com.android.application\") }\nif (flag)\n    android { compileSdk = 35 }",
            "plugins { id(\"com.android.application\") }\nandroid { defaultConfig { compileSdk = 35 } }",
            "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 35; productFlavors { minSdk = 23 } }",
            "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 35 }\nif (flag) { android { minSdk = 99 } }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-android-scope").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "invalid Android metadata scope was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        val dependencyClosure = Files.createTempDirectory("vega-build-dependency-closure").toFile()
        try {
            File(dependencyClosure, "build.gradle.kts").writeText(
                "plugins { java }\ndependencies { implementation(\"com.example:local:1.0\") { exclude(group = \"x\") } }"
            )
            val model = EmbeddedProjectModelParser().parse(dependencyClosure)
            check(!model.supported) { "dependency closure modifier was silently discarded" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
        } finally {
            dependencyClosure.deleteRecursively()
        }

        listOf(
            "plugins { java }\ndependencies {\n    implementation \"com.example:local:1.0\"\n    { exclude group: \"x\" }\n}",
            "plugins { java }\nif (condition) { dependencies { implementation(\"com.example:local:1.0\") } }",
            "plugins { java }\nif (condition)\n    dependencies { implementation(\"com.example:local:1.0\") }"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-dependency-scope").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unconsumed or dynamically scoped dependency was accepted: $script" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "plugins { id(\"java\" }",
            "plugins { java }\ndependencies { implementation(\"com.example:local:1.0\" }",
            "apply plugin: \"java\" }",
            "plugins { java })",
            "plugins { java ]"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-unclosed-invocation").toFile()
            try {
                File(root, "build.gradle.kts").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unterminated invocation was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun freezeGrammarAdversarialTests() {
        val settingsRoot = Files.createTempDirectory("vega-build-settings-root-name-tail").toFile()
        try {
            File(settingsRoot, "settings.gradle.kts").writeText(
                "rootProject.name = \"safe\" + suffix\ninclude(\":app\")"
            )
            File(settingsRoot, "app").mkdirs()
            File(settingsRoot, "app/build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(settingsRoot)
            check(!model.supported) { "dynamic rootProject.name composition was accepted" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_STATEMENT") }) {
                "missing settings grammar diagnostic: ${model.unsupportedReasons}"
            }
        } finally {
            settingsRoot.deleteRecursively()
        }

        listOf(
            "apply from: 'other.gradle'",
            "apply [plugin: 'java']",
            "apply(plugin: 'java')",
            "apply plugin: pluginName",
            "apply plugin: 'java', version: '1.0'",
            "apply plugin: 'java' + suffix",
            "apply plugin: 'java' extra"
        ).forEach { script ->
            val root = Files.createTempDirectory("vega-build-groovy-apply-boundary").toFile()
            try {
                File(root, "build.gradle").writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "unsupported Groovy apply form was accepted: $script" }
            } finally {
                root.deleteRecursively()
            }
        }

        listOf(
            "build.gradle.kts" to "dependencies { implementation(\"g:a:1\") }\nplugins { java }",
            "build.gradle.kts" to "android { compileSdk = 35 }\nplugins { id(\"com.android.application\") }",
            "build.gradle" to "apply plugin: 'java'\nplugins { id 'java-library' }"
        ).forEach { (fileName, script) ->
            val root = Files.createTempDirectory("vega-build-plugins-order").toFile()
            try {
                File(root, fileName).writeText(script)
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) { "non-plugin production before plugins{} was accepted: $script" }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_UNSUPPORTED_PLUGIN") }) {
                    "missing plugins ordering diagnostic: ${model.unsupportedReasons}"
                }
            } finally {
                root.deleteRecursively()
            }
        }

        val pluginsFirst = Files.createTempDirectory("vega-build-plugins-order-positive").toFile()
        try {
            File(pluginsFirst, "build.gradle.kts").writeText(
                "plugins { java }\ndependencies { implementation(\"g:a:1\") }"
            )
            val model = EmbeddedProjectModelParser().parse(pluginsFirst)
            check(model.supported) { "legal plugins-first form was rejected: ${model.unsupportedReasons}" }
        } finally {
            pluginsFirst.deleteRecursively()
        }

        val extraCoordinate = Files.createTempDirectory("vega-build-coordinate-segments").toFile()
        try {
            File(extraCoordinate, "build.gradle.kts").writeText(
                "plugins { id(\"java-library\") }\n" +
                    "dependencies { api(\"g:a:1:c:extra\") }"
            )
            val model = EmbeddedProjectModelParser().parse(extraCoordinate)
            check(!model.supported) { "five-segment external coordinate was accepted" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
        } finally {
            extraCoordinate.deleteRecursively()
        }

        val fourSegment = Files.createTempDirectory("vega-build-coordinate-four-segment").toFile()
        try {
            File(fourSegment, "build.gradle.kts").writeText(
                "plugins { id(\"java-library\") }\n" +
                    "dependencies { api(\"g:a:1:c\") }"
            )
            val model = EmbeddedProjectModelParser().parse(fourSegment)
            check(model.supported) { "supported four-segment coordinate was rejected: ${model.unsupportedReasons}" }
            check(model.modules.single().dependencies.single().notation == "g:a:1:c")
        } finally {
            fourSegment.deleteRecursively()
        }

        listOf(
            "dependencies { implementation(\"g:a:1\") api(\"g:b:1\") }",
            "dependencies { implementation(\"g:a:1\") /* ; */ api(\"g:b:1\") }",
            "dependencies { implementation(\"g:a:1\") /*\n*/ api(\"g:b:1\") }",
            "dependencies { implementation(\"g:a:1\") \";\" api(\"g:b:1\") }"
        ).forEach { dependencyBlock ->
            val root = Files.createTempDirectory("vega-build-kotlin-adjacent-dependencies").toFile()
            try {
                File(root, "build.gradle.kts").writeText("plugins { java }\n$dependencyBlock")
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported) {
                    "Kotlin dependency invocations without a real boundary were accepted: $dependencyBlock"
                }
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_DYNAMIC_DEPENDENCY") })
            } finally {
                root.deleteRecursively()
            }
        }

        val semicolonSeparated = Files.createTempDirectory("vega-build-kotlin-separated-dependencies").toFile()
        try {
            File(semicolonSeparated, "build.gradle.kts").writeText(
                "plugins { java }\n" +
                    "dependencies { implementation(\"g:a:1\"); runtimeOnly(\"g:b:1\") }"
            )
            val model = EmbeddedProjectModelParser().parse(semicolonSeparated)
            check(model.supported) {
                "semicolon-separated Kotlin dependencies were rejected: ${model.unsupportedReasons}"
            }
        } finally {
            semicolonSeparated.deleteRecursively()
        }

        val newlineSeparated = Files.createTempDirectory("vega-build-kotlin-newline-dependencies").toFile()
        try {
            File(newlineSeparated, "build.gradle.kts").writeText(
                "plugins { java }\n" +
                    "dependencies { implementation(\"g:a:1\")\n    runtimeOnly(\"g:b:1\") }"
            )
            val model = EmbeddedProjectModelParser().parse(newlineSeparated)
            check(model.supported) {
                "newline-separated Kotlin dependencies were rejected: ${model.unsupportedReasons}"
            }
        } finally {
            newlineSeparated.deleteRecursively()
        }
    }

    private fun symlinkedInputsFailClosed() {
        val root = Files.createTempDirectory("vega-build-symlink").toFile()
        val outside = Files.createTempDirectory("vega-build-symlink-outside").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            File(root, "src/main").mkdirs()
            File(outside, "outside.java").writeText("class Outside {}")
            val link = File(root, "src/main/java").toPath()
            val created = runCatching {
                Files.createSymbolicLink(link, outside.toPath())
            }.isSuccess
            if (created) {
                val model = EmbeddedProjectModelParser().parse(root)
                check(!model.supported)
                check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_PATH_ESCAPE") })
            }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun descendantSymlinkInputsFailClosed() {
        listOf(
            "src/main/java/nested/linked.java",
            "src/main/resources/nested/linked.properties",
            "src/test/resources/nested/linked.properties"
        ).forEach { relativeLink ->
            val root = Files.createTempDirectory("vega-build-descendant-symlink").toFile()
            val outside = Files.createTempDirectory("vega-build-descendant-symlink-outside").toFile()
            try {
                File(root, "build.gradle.kts").writeText("plugins { java }")
                val sourceRoot = relativeLink.substringBefore("/nested/")
                File(root, sourceRoot).mkdirs()
                val target = File(outside, "target.txt").apply { writeText("outside") }
                val link = File(root, relativeLink).toPath()
                link.parent.toFile().mkdirs()
                val created = runCatching { Files.createSymbolicLink(link, target.toPath()) }.isSuccess
                if (created) {
                    check(!Files.isSymbolicLink(File(root, sourceRoot).toPath())) {
                        "descendant case unexpectedly replaced the source root"
                    }
                    val model = EmbeddedProjectModelParser().parse(root)
                    check(!model.supported) {
                        "nested source/resource symlink was accepted: $relativeLink"
                    }
                    check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_PATH_ESCAPE") }) {
                        "missing descendant symlink path diagnostic: ${model.unsupportedReasons}"
                    }
                }
            } finally {
                root.deleteRecursively()
                outside.deleteRecursively()
            }
        }
    }

    private fun inputManifestLimitsFailClosed() {
        val countRoot = Files.createTempDirectory("vega-build-input-count-limit").toFile()
        try {
            File(countRoot, "build.gradle.kts").writeText("plugins { java }")
            val resources = File(countRoot, "src/main/resources").apply { mkdirs() }
            repeat(20_001) { index ->
                check(File(resources, "file-$index.txt").createNewFile())
            }
            assertInputManifestFailure(countRoot, "BUILD_INPUT_FILE_COUNT_LIMIT")
        } finally {
            countRoot.deleteRecursively()
        }

        val singleFileRoot = Files.createTempDirectory("vega-build-input-single-limit").toFile()
        try {
            File(singleFileRoot, "build.gradle.kts").writeText("plugins { java }")
            createSparseFile(
                File(singleFileRoot, "src/main/resources/too-large.bin"),
                32L * 1024L * 1024L + 1L
            )
            assertInputManifestFailure(singleFileRoot, "BUILD_INPUT_FILE_BYTES_LIMIT")
        } finally {
            singleFileRoot.deleteRecursively()
        }

        val totalRoot = Files.createTempDirectory("vega-build-input-total-limit").toFile()
        try {
            File(totalRoot, "build.gradle.kts").writeText("plugins { java }")
            repeat(9) { index ->
                createSparseFile(
                    File(totalRoot, "src/main/resources/file-$index.bin"),
                    32L * 1024L * 1024L
                )
            }
            assertInputManifestFailure(totalRoot, "BUILD_INPUT_TOTAL_BYTES_LIMIT")
        } finally {
            totalRoot.deleteRecursively()
        }

        val depthRoot = Files.createTempDirectory("vega-build-input-depth-limit").toFile()
        try {
            File(depthRoot, "build.gradle.kts").writeText("plugins { java }")
            var current = File(depthRoot, "src/main/resources").apply { mkdirs() }
            repeat(64) { index ->
                current = File(current, "level-$index").apply { mkdirs() }
            }
            check(File(current, "too-deep.txt").createNewFile())
            assertInputManifestFailure(depthRoot, "BUILD_INPUT_DEPTH_LIMIT")
        } finally {
            depthRoot.deleteRecursively()
        }

        val duplicateRoot = Files.createTempDirectory("vega-build-input-dedup").toFile()
        try {
            File(duplicateRoot, "settings.gradle.kts").writeText("include(\":a\", \":b\")")
            listOf("a", "b").forEach { moduleName ->
                val module = File(duplicateRoot, moduleName).apply { mkdirs() }
                File(module, "build.gradle.kts").writeText("plugins { java }")
            }
            // 19,997 resources + two module scripts + the one settings script
            // is exactly 20,000 unique canonical files. The settings script is
            // visited for both modules but must be counted and hashed once.
            val resources = File(duplicateRoot, "a/src/main/resources").apply { mkdirs() }
            repeat(19_997) { index ->
                check(File(resources, "file-$index.txt").createNewFile())
            }
            val model = EmbeddedProjectModelParser().parse(duplicateRoot)
            check(model.supported) { "dedup fixture was rejected: ${model.unsupportedReasons}" }
            val fingerprint = runCatching {
                BuildExecutionInputs.from(BuildContext(model, File(duplicateRoot, "out")))
                    .projectFingerprint
            }.getOrElse { error ->
                error("canonical duplicate inputs were over-counted: ${error.message}")
            }
            check(fingerprint.matches(Regex("[0-9a-f]{64}")))
        } finally {
            duplicateRoot.deleteRecursively()
        }
    }

    private fun assertInputManifestFailure(root: File, expectedCode: String) {
        val model = EmbeddedProjectModelParser().parse(root)
        check(model.supported) { "manifest-limit fixture was rejected before fingerprinting: ${model.unsupportedReasons}" }
        val failure = runCatching {
            BuildExecutionInputs.from(BuildContext(model, File(root, "out")))
        }.exceptionOrNull()
        check(failure?.message == expectedCode) {
            "expected $expectedCode, got ${failure?.javaClass?.name}:${failure?.message}"
        }
    }

    private fun createSparseFile(file: File, size: Long) {
        file.parentFile.mkdirs()
        java.io.RandomAccessFile(file, "rw").use { handle -> handle.setLength(size) }
    }

    private fun rawByteFingerprintTests() {
        val root = Files.createTempDirectory("vega-build-raw-byte-fingerprint").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val payloadA = File(root, "src/main/resources/payload-a.bin").apply {
                parentFile.mkdirs()
            }
            val payloadB = File(root, "src/main/resources/payload-b.bin")
            // Both invalid UTF-8 payloads decode to the same replacement text,
            // but their raw bytes must remain distinct to the fingerprint.
            payloadA.writeBytes(byteArrayOf(0x80.toByte()))
            payloadB.writeBytes(byteArrayOf(0x81.toByte()))
            val parser = EmbeddedProjectModelParser()
            val firstModel = parser.parse(root)
            check(firstModel.supported) { "raw-byte fixture was rejected: ${firstModel.unsupportedReasons}" }
            val first = BuildExecutionInputs.from(
                BuildContext(firstModel, File(root, "out"))
            ).projectFingerprint
            val repeated = BuildExecutionInputs.from(
                BuildContext(parser.parse(root), File(root, "out"))
            ).projectFingerprint
            check(first == repeated) { "unchanged binary input was not deterministic" }

            payloadA.writeBytes(byteArrayOf(0x81.toByte()))
            payloadB.writeBytes(byteArrayOf(0x80.toByte()))
            val swapped = BuildExecutionInputs.from(
                BuildContext(parser.parse(root), File(root, "out"))
            ).projectFingerprint
            check(first != swapped) {
                "same-length invalid UTF-8 payloads did not change the raw-byte fingerprint"
            }
            check(swapped == BuildExecutionInputs.from(
                BuildContext(parser.parse(root), File(root, "out"))
            ).projectFingerprint) { "swapped binary input was not deterministic" }

            val backendSource = File("src/dev/wind/agent/build/BuildBackend.kt")
            check(backendSource.isFile) { "BuildBackend.kt source guard could not locate the file" }
            val sourceText = backendSource.readText()
            check("readBytes().toString(Charsets.UTF-8)" !in sourceText) {
                "file-content fingerprint still performs a UTF-8 readBytes round-trip"
            }
            check("sha256(\"<unreadable>\")" !in sourceText) {
                "file-content fingerprint still uses a synthetic unreadable digest"
            }
            check("FileChannel.open" in sourceText && "CONTENT_BUFFER_BYTES" in sourceText) {
                "file-content fingerprint is missing bounded NIO streaming"
            }
            check("lastModifiedTimeMillis" in sourceText && "creationTimeMillis" in sourceText) {
                "file-content identity guard is missing timestamp metadata"
            }
            check("lastModifiedTime().toMillis()" in sourceText &&
                "creationTime().toMillis()" in sourceText
            ) {
                "file-content identity guard does not compare stable timestamp values"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pathStabilityFailClosed() {
        val buildScriptRoot = Files.createTempDirectory("vega-build-stale-script").toFile()
        try {
            File(buildScriptRoot, "build.gradle.kts").writeText("plugins { java }")
            val parser = EmbeddedProjectModelParser()
            val model = parser.parse(buildScriptRoot)
            check(model.supported)
            check(File(buildScriptRoot, "build.gradle.kts").delete())
            assertStaleFingerprintFailure(model, buildScriptRoot, "BUILD_INPUT_CHANGED")
        } finally {
            buildScriptRoot.deleteRecursively()
        }

        val manifestRoot = Files.createTempDirectory("vega-build-stale-manifest").toFile()
        try {
            File(manifestRoot, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }\nandroid { compileSdk = 35 }"
            )
            val manifest = File(manifestRoot, "src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("<manifest />")
            }
            val parser = EmbeddedProjectModelParser()
            val model = parser.parse(manifestRoot)
            check(model.supported)
            check(manifest.delete())
            assertStaleFingerprintFailure(model, manifestRoot, "BUILD_INPUT_CHANGED")
        } finally {
            manifestRoot.deleteRecursively()
        }

        val directoryRoot = Files.createTempDirectory("vega-build-stale-resource-dir").toFile()
        try {
            File(directoryRoot, "build.gradle.kts").writeText("plugins { java }")
            val resources = File(directoryRoot, "src/main/resources").apply { mkdirs() }
            File(resources, "payload.properties").writeText("payload=one")
            val parser = EmbeddedProjectModelParser()
            val model = parser.parse(directoryRoot)
            check(model.supported)
            check(resources.deleteRecursively())
            assertStaleFingerprintFailure(model, directoryRoot, "BUILD_INPUT_CHANGED")
        } finally {
            directoryRoot.deleteRecursively()
        }

        val ancestorRoot = Files.createTempDirectory("vega-build-ancestor-symlink").toFile()
        val outside = Files.createTempDirectory("vega-build-ancestor-symlink-outside").toFile()
        try {
            File(ancestorRoot, "build.gradle.kts").writeText("plugins { java }")
            File(ancestorRoot, "src/main/resources/payload.properties").apply {
                parentFile.mkdirs()
                writeText("payload=inside")
            }
            val parser = EmbeddedProjectModelParser()
            val model = parser.parse(ancestorRoot)
            check(model.supported)
            File(outside, "resources/payload.properties").apply {
                parentFile.mkdirs()
                writeText("payload=outside")
            }
            val mainDirectory = File(ancestorRoot, "src/main")
            check(mainDirectory.deleteRecursively())
            val created = runCatching {
                Files.createSymbolicLink(mainDirectory.toPath(), outside.toPath())
            }.isSuccess
            if (created) {
                check(!Files.isSymbolicLink(File(ancestorRoot, "src/main/resources").toPath())) {
                    "ancestor fixture unexpectedly became a final-component symlink"
                }
                assertStaleFingerprintFailure(model, ancestorRoot, "BUILD_INPUT_PATH_UNSAFE")
            }
        } finally {
            ancestorRoot.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun assertStaleFingerprintFailure(
        model: EmbeddedProjectModel,
        root: File,
        expectedCode: String
    ) {
        val failure = runCatching {
            BuildExecutionInputs.from(BuildContext(model, File(root, "out")))
        }.exceptionOrNull()
        check(failure?.message == expectedCode) {
            "expected $expectedCode, got ${failure?.javaClass?.name}:${failure?.message}"
        }
    }

    private fun incompleteAndroidMetadataFailsClosed() {
        val root = Files.createTempDirectory("vega-build-incomplete").toFile()
        try {
            File(root, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.application\") }"
            )
            val model = EmbeddedProjectModelParser().parse(root)
            check(!model.supported) { "Android module without compileSdk was accepted" }
            check(model.unsupportedReasons.any { it.startsWith("EMBEDDED_PROJECT_MODEL_UNSUPPORTED") })
            check(model.diagnostics.any { it.source == BuildDiagnosticSource.PROJECT })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun moduleTaskIdsAreCollisionFree() {
        val root = Files.createTempDirectory("vega-build-task-collision").toFile()
        try {
            File(root, "settings.gradle.kts").writeText("include(\":a:b\", \":a_b\")")
            val nested = File(root, "a/b").apply { mkdirs() }
            val underscored = File(root, "a_b").apply { mkdirs() }
            listOf(nested, underscored).forEach { module ->
                File(module, "build.gradle.kts").writeText("plugins { java }")
                File(module, "src/main/java/example").mkdirs()
            }
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { model.unsupportedReasons.toString() }
            val plan = EmbeddedBuildBackend().plan(BuildContext(model, File(root, "out")))
            check(plan.tasks.map { it.id }.distinct().size == plan.tasks.size)
            check(plan.tasks.any { it.id == "resolve-classpath:${moduleSuffix(":a:b")}" })
            check(plan.tasks.any { it.id == "resolve-classpath:${moduleSuffix(":a_b")}" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun planIsDeterministicAndProviderNeutral() {
        val root = Files.createTempDirectory("vega-build-plan").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            File(root, "src/main/java/example").mkdirs()
            File(root, "src/test/java/example").mkdirs()
            val model = EmbeddedProjectModelParser().parse(root)
            val context = BuildContext(model, File(root, "build-out"))
            val registry = BuildBackendRegistry(listOf(EmbeddedBuildBackend(), ShellBuildBackend()))
            check(registry.select(model)?.id == BuildBackendId.EMBEDDED)
            val first = registry.plan(context)
            val second = registry.plan(context)
            check(first.backend == BuildBackendId.EMBEDDED)
            check(first.tasks.map { it.id } == listOf(
                "resolve-project", "resolve-classpath:${moduleSuffix(":")}",
                "compile-java:${moduleSuffix(":")}", "run-tests:${moduleSuffix(":")}"
            )) { "unexpected deterministic task plan: ${first.tasks}" }
            check(first.fingerprint == second.fingerprint)
            check(first.tasks.all { task -> task.dependsOn.all { dependency -> first.tasks.any { it.id == dependency } } })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendExecutesJavaOnlyPlan() {
        val root = Files.createTempDirectory("vega-build-java-execute").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val source = File(root, "src/main/java/example/Hello.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package example;
                    public final class Hello {
                        public static String message() { return "hello"; }
                    }
                    """.trimIndent()
                )
            }
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { model.unsupportedReasons.toString() }
            val context = BuildContext(model, File(root, "build-out"))
            val backend = EmbeddedBuildBackend()
            val plan = backend.plan(context)

            val first = backend.execute(context, plan, CancellationToken())
            check(first.outcome == BuildOutcome.SUCCESS) {
                "Expected Java-only build SUCCESS, got ${first.outcome}: ${first.diagnostics}"
            }
            val suffix = moduleSuffix(":")
            val classFile = File(context.outputDirectory, "modules/$suffix/classes/java/main/example/Hello.class")
            check(classFile.isFile) { "ECJ did not produce the expected class file: $classFile" }
            val classBytes = classFile.readBytes()
            check(classBytes.size > 8)
            check(classBytes[0] == 0xCA.toByte() && classBytes[1] == 0xFE.toByte() &&
                classBytes[2] == 0xBA.toByte() && classBytes[3] == 0xBE.toByte()
            ) { "invalid CAFEBABE header" }
            val major = ((classBytes[6].toInt() and 0xff) shl 8) or (classBytes[7].toInt() and 0xff)
            check(major == 61) { "Expected Java 17 class major 61, got $major" }
            check(first.producedArtifacts.single().let { artifact ->
                artifact.kind == "java-class" && artifact.file.canonicalFile == classFile.canonicalFile &&
                    artifact.sha256 == sha256(classFile)
            }) { "BuildResult did not report the real Java class and its SHA-256: ${first.producedArtifacts}" }
            check(first.taskResults.map { it.id to it.outcome } == listOf(
                "resolve-project" to BuildTaskOutcome.EXECUTED,
                "resolve-classpath:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-java:$suffix" to BuildTaskOutcome.EXECUTED
            )) { "Build task results did not reflect the executed DAG: ${first.taskResults.map { it.id to it.outcome }}" }

            val second = backend.execute(context, plan, CancellationToken())
            check(second.outcome == BuildOutcome.SUCCESS)
            check(first.producedArtifacts.single().sha256 == second.producedArtifacts.single().sha256) {
                "Repeated Java-only execution did not produce deterministic class bytes"
            }

            val emptyRoot = Files.createTempDirectory("vega-build-java-empty").toFile()
            try {
                File(emptyRoot, "build.gradle.kts").writeText("plugins { java }")
                File(emptyRoot, "src/main/java").mkdirs()
                val emptyModel = EmbeddedProjectModelParser().parse(emptyRoot)
                val emptyContext = BuildContext(emptyModel, File(emptyRoot, "build-out"))
                val emptyResult = backend.execute(emptyContext, backend.plan(emptyContext), CancellationToken())
                check(emptyResult.outcome == BuildOutcome.SUCCESS)
                check(emptyResult.producedArtifacts.isEmpty())
                check(emptyResult.taskResults.single { it.id.startsWith("compile-java:") }.outcome == BuildTaskOutcome.SKIPPED)
            } finally {
                emptyRoot.deleteRecursively()
            }

            val cancelled = CancellationToken().also { it.cancel() }
            val cancelledContext = BuildContext(model, File(root, "cancelled-out"))
            val cancelledResult = backend.execute(cancelledContext, backend.plan(cancelledContext), cancelled)
            check(cancelledResult.outcome == BuildOutcome.CANCELLED)
            check(cancelledResult.producedArtifacts.isEmpty())
            check(cancelledResult.diagnostics.any { it.code == "BUILD_CANCELLED" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun androidBuildPlanAndProviderBoundaries() {
        val root = Files.createTempDirectory("vega-build-android-plan").toFile()
        try {
            writeAndroidApplicationFixture(root)
            val project = EmbeddedProjectModelParser().parse(root)
            check(project.supported) { "Android plan fixture was rejected: ${project.unsupportedReasons}" }
            val context = BuildContext(project, File(root, "out"))
            val backend = EmbeddedBuildBackend()
            val plan = backend.plan(context)
            val suffix = moduleSuffix(":")
            val expectedIds = listOf(
                "resolve-project",
                "resolve-classpath:$suffix",
                "compile-resources:$suffix",
                "link-resources:$suffix",
                "compile-java:$suffix",
                "dex:$suffix",
                "package-apk:$suffix",
                "sign-apk:$suffix"
            )
            check(plan.tasks.map { it.id } == expectedIds) {
                "Android task order is not resource -> link -> Java -> dex: ${plan.tasks.map { it.id }}"
            }
            check(plan.tasks.single { it.id == "compile-resources:$suffix" }.dependsOn ==
                listOf("resolve-classpath:$suffix"))
            check(plan.tasks.single { it.id == "link-resources:$suffix" }.dependsOn ==
                listOf("compile-resources:$suffix"))
            check(plan.tasks.single { it.id == "compile-java:$suffix" }.dependsOn ==
                listOf("link-resources:$suffix"))
            check(plan.tasks.single { it.id == "dex:$suffix" }.dependsOn ==
                listOf("compile-java:$suffix"))

            val dummyJar = File(root, "android.jar").apply { writeBytes(byteArrayOf(1)) }
            val noAaptBackend = EmbeddedBuildBackend { listOf(dummyJar) }
            val noAaptContext = BuildContext(project, File(root, "no-aapt-out"))
            val noAaptResult = noAaptBackend.execute(noAaptContext, noAaptBackend.plan(noAaptContext), CancellationToken())
            check(noAaptResult.outcome == BuildOutcome.UNSUPPORTED)
            check(noAaptResult.diagnostics.any { it.code == "AAPT2_UNAVAILABLE" })
            check(noAaptResult.taskResults.single { it.id == "compile-resources:$suffix" }.outcome ==
                BuildTaskOutcome.SKIPPED)
            check(noAaptResult.taskResults.drop(2).all { it.outcome == BuildTaskOutcome.SKIPPED })

            val unusedAapt = Aapt2ResourceCompiler(
                NativeAapt2Provider(File(root, "not-invoked-aapt2.exe"))
            )
            val missingPlatformBackend = EmbeddedBuildBackend({ emptyList() }, unusedAapt)
            val missingPlatformContext = BuildContext(project, File(root, "no-platform-out"))
            val missingPlatform = missingPlatformBackend.execute(
                missingPlatformContext,
                missingPlatformBackend.plan(missingPlatformContext),
                CancellationToken()
            )
            check(missingPlatform.outcome == BuildOutcome.UNSUPPORTED)
            check(missingPlatform.diagnostics.any { it.code == "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE" })
            check(missingPlatform.taskResults.first().id == "resolve-project")
            check(missingPlatform.taskResults.first().outcome == BuildTaskOutcome.FAILED)
            check(!File(missingPlatformContext.outputDirectory, "modules/$suffix/resources/compiled/res.zip").exists())

            val libraryRoot = File(root, "android-library").apply { mkdirs() }
            File(libraryRoot, "build.gradle.kts").writeText(
                "plugins { id(\"com.android.library\") }\n" +
                    "android { namespace = \"dev.wind.fixture\"; compileSdk = 35; " +
                    "defaultConfig { minSdk = 23; targetSdk = 35 } }"
            )
            File(libraryRoot, "src/main/res/values").mkdirs()
            File(libraryRoot, "src/main/AndroidManifest.xml").apply {
                parentFile.mkdirs()
                writeText("<manifest package=\"dev.wind.fixture\" />")
            }
            val libraryModel = EmbeddedProjectModelParser().parse(libraryRoot)
            check(libraryModel.supported) { libraryModel.unsupportedReasons.toString() }
            val libraryContext = BuildContext(libraryModel, File(libraryRoot, "out"))
            val libraryBackend = EmbeddedBuildBackend { listOf(dummyJar) }
            val libraryResult = libraryBackend.execute(libraryContext, libraryBackend.plan(libraryContext), CancellationToken())
            check(libraryResult.outcome == BuildOutcome.UNSUPPORTED)
            check(libraryResult.diagnostics.any { it.code == "EMBEDDED_ANDROID_LIBRARY_EXECUTION_UNSUPPORTED" })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun androidDexExecutionProviderBoundaries() {
        val root = Files.createTempDirectory("vega-build-be3-fake-dex").toFile()
        try {
            writeAndroidApplicationFixture(root)
            val androidJar = File("assets/embedded/android-35/android.jar").canonicalFile
            check(androidJar.isFile && androidJar.length() > 0L) { "Android 35 platform jar is missing" }
            val suffix = moduleSuffix(":")
            val aapt2 = Aapt2ResourceCompiler(FakeAndroidAapt2Provider())
            fun backend(provider: D8Provider) = EmbeddedBuildBackend(
                bootClasspath = { listOf(androidJar) },
                aapt2Compiler = aapt2,
                d8Compiler = D8DexCompiler(provider)
            )
            fun taskOutcome(result: BuildResult, prefix: String) =
                result.taskResults.single { it.id.startsWith(prefix) }.outcome

            val unavailable = runAndroidBuild(
                root,
                EmbeddedBuildBackend({ listOf(androidJar) }, aapt2),
                File(root, "be3-out-unavailable")
            )
            val unavailableDex = File(root, "be3-out-unavailable/modules/$suffix/dex/app")
            check(unavailable.outcome == BuildOutcome.UNSUPPORTED)
            check(unavailable.diagnostics.single().let {
                it.code == "D8_UNAVAILABLE" && it.source == BuildDiagnosticSource.DEX
            }) { "D8 unavailability was not preserved: ${unavailable.diagnostics}" }
            check(taskOutcome(unavailable, "dex:") == BuildTaskOutcome.SKIPPED)
            check(taskOutcome(unavailable, "package-apk:") == BuildTaskOutcome.SKIPPED)
            check(taskOutcome(unavailable, "sign-apk:") == BuildTaskOutcome.SKIPPED)
            check(!unavailableDex.exists()) { "D8_UNAVAILABLE unexpectedly prepared a dex output directory" }

            val failureInvocations = mutableListOf<D8Invocation>()
            val failed = runAndroidBuild(
                root,
                backend(object : D8Provider {
                    override val available = true
                    override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
                        failureInvocations += invocation
                        return D8InvocationResult(success = false, diagnostics = "synthetic D8 failure")
                    }
                }),
                File(root, "be3-out-failed")
            )
            check(failed.outcome == BuildOutcome.FAILED)
            check(failed.diagnostics.single().let {
                it.code == "D8_COMPILE_FAILED" && it.message.contains("synthetic D8 failure")
            }) { "D8 failure diagnostic was not preserved: ${failed.diagnostics}" }
            check(failureInvocations.size == 1)
            check(taskOutcome(failed, "dex:") == BuildTaskOutcome.FAILED)
            check(taskOutcome(failed, "package-apk:") == BuildTaskOutcome.SKIPPED)
            check(taskOutcome(failed, "sign-apk:") == BuildTaskOutcome.SKIPPED)
            check(failed.producedArtifacts.isEmpty())

            val successfulInvocations = mutableListOf<D8Invocation>()
            val successfulProvider = object : D8Provider {
                override val available = true
                override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
                    successfulInvocations += invocation
                    File(invocation.outputDirectory, "classes.dex").writeBytes(validDexFixture())
                    return D8InvocationResult(success = true)
                }
            }
            val min23Output = File(root, "be3-out-min23")
            val min23Result = runAndroidBuild(root, backend(successfulProvider), min23Output)
            val moduleOutput = File(min23Output, "modules/$suffix")
            val classes = File(moduleOutput, "classes/java/main")
            val dexDirectory = File(moduleOutput, "dex/app")
            val classesDex = File(dexDirectory, "classes.dex")
            val compiledResources = File(moduleOutput, "resources/compiled/res.zip")
            val linkedResources = File(moduleOutput, "resources/linked/resources.ap_")
            val generatedR = File(moduleOutput, "resources/generated/r/dev/wind/fixture/R.java")
            val symbols = File(moduleOutput, "resources/symbols/R.txt")
            val resourceUserClass = File(classes, "dev/wind/fixture/ResourceUser.class")
            val rClass = File(classes, "dev/wind/fixture/R.class")
            val rStringClass = File(classes, "dev/wind/fixture/R" + "$" + "string.class")
            check(min23Result.outcome == BuildOutcome.UNSUPPORTED)
            check(min23Result.diagnostics.single().code == "EMBEDDED_APK_PACKAGING_UNSUPPORTED") {
                "After D8 success the only stopping diagnostic must be APK packaging: ${min23Result.diagnostics}"
            }
            check(min23Result.producedArtifacts.isEmpty()) {
                "Non-success BuildResult must retain the empty producedArtifacts contract"
            }
            check(min23Result.taskResults.map { it.id to it.outcome } == listOf(
                "resolve-project" to BuildTaskOutcome.EXECUTED,
                "resolve-classpath:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "link-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-java:$suffix" to BuildTaskOutcome.EXECUTED,
                "dex:$suffix" to BuildTaskOutcome.EXECUTED,
                "package-apk:$suffix" to BuildTaskOutcome.FAILED,
                "sign-apk:$suffix" to BuildTaskOutcome.SKIPPED
            )) { "BE3 task outcomes were not preserved: ${min23Result.taskResults.map { it.id to it.outcome }}" }
            listOf(compiledResources, linkedResources, generatedR, symbols,
                resourceUserClass, rClass, rStringClass, classesDex).forEach { file ->
                check(file.isFile && file.length() > 0L) { "Missing BE3 intermediate output: $file" }
            }
            val dexBytes = classesDex.readBytes()
            check(dexBytes.size > 8 && dexBytes.take(4) == listOf(
                'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()
            )) { "Fake provider did not leave a valid dex magic prefix at $classesDex" }
            check(successfulInvocations.size == 1)
            val invocation23 = successfulInvocations.single()
            check(invocation23.minApi == 23 && invocation23.release) {
                "BE3 did not pass minSdk=23 and release=true: $invocation23"
            }
            check(invocation23.libraryFiles.single().canonicalFile == androidJar) {
                "D8 did not receive BE2's canonical Android platform jar"
            }
            check(invocation23.programFiles.isNotEmpty() && invocation23.programFiles.all { file ->
                file.isFile && file.extension == "class" &&
                    file.canonicalFile.toPath().startsWith(classes.canonicalFile.toPath())
            }) { "D8 program inputs escaped the Java output directory: ${invocation23.programFiles}" }
            check(invocation23.outputDirectory.canonicalFile == dexDirectory.canonicalFile) {
                "D8 output directory is not the deterministic app dex location"
            }

            val gradleFile = File(root, "build.gradle.kts")
            gradleFile.writeText(gradleFile.readText().replace("minSdk = 23", "minSdk = 26"))
            val min26Result = runAndroidBuild(root, backend(successfulProvider), File(root, "be3-out-min26"))
            check(min26Result.outcome == BuildOutcome.UNSUPPORTED)
            check(min26Result.diagnostics.single().code == "EMBEDDED_APK_PACKAGING_UNSUPPORTED")
            check(successfulInvocations.size == 2 && successfulInvocations.last().minApi == 26) {
                "BE3 hard-coded minApi instead of passing through minSdk: ${successfulInvocations.map { it.minApi }}"
            }

            val cancellationToken = CancellationToken()
            val cancelled = runAndroidBuild(
                root,
                backend(object : D8Provider {
                    override val available = true
                    override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
                        File(invocation.outputDirectory, "classes.dex").writeBytes(validDexFixture())
                        token.cancel()
                        return D8InvocationResult(success = true)
                    }
                }),
                File(root, "be3-out-cancelled"),
                cancellationToken
            )
            check(cancelled.outcome == BuildOutcome.CANCELLED)
            check(cancelled.diagnostics.any { it.code == "BUILD_CANCELLED" })
            check(taskOutcome(cancelled, "dex:") == BuildTaskOutcome.CANCELLED)
            check(taskOutcome(cancelled, "package-apk:") == BuildTaskOutcome.SKIPPED)
            check(taskOutcome(cancelled, "sign-apk:") == BuildTaskOutcome.SKIPPED)
            check(cancelled.producedArtifacts.isEmpty())
            check(File(root, "be3-out-cancelled/modules/$suffix/dex/app").listFiles().orEmpty().isEmpty()) {
                "D8 cancellation left partial dex output"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun androidCoreLibraryDesugaringBackendBoundaries() {
        val root = Files.createTempDirectory("vega-build-be4-fake-dex").toFile()
        try {
            writeAndroidApplicationFixture(root)
            val androidJar = File("assets/embedded/android-35/android.jar").canonicalFile
            val runtimeJar = File("third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar").canonicalFile
            check(androidJar.isFile && runtimeJar.isFile) { "BE4 pinned Android/desugar inputs are missing" }
            val configurationJson = "{\"configuration_format_version\":200,\"identifier\":\"fixture\"}"
            val suffix = moduleSuffix(":")
            val aapt2 = Aapt2ResourceCompiler(FakeAndroidAapt2Provider())

            class CapturingD8(
                private val keepRules: String? = "-keep class j$.nio.file.** { *; }\n",
                private val failure: String? = null,
                private val afterCompile: (CancellationToken) -> Unit = {}
            ) : D8Provider {
                override val available = true
                val invocations = mutableListOf<D8Invocation>()

                override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
                    invocations += invocation
                    if (failure != null) return D8InvocationResult(success = false, diagnostics = failure)
                    File(invocation.outputDirectory, "classes.dex").writeBytes(validDexFixture())
                    afterCompile(token)
                    return D8InvocationResult(success = true, desugaredLibraryKeepRules = keepRules)
                }
            }

            class CapturingL8(
                override val available: Boolean = true,
                private val failure: String? = null,
                private val afterCompile: (CancellationToken) -> Unit = {}
            ) : L8Provider {
                val invocations = mutableListOf<L8Invocation>()

                override fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult {
                    invocations += invocation
                    if (failure != null) return L8InvocationResult(success = false, diagnostics = failure)
                    File(invocation.outputDirectory, "classes.dex").writeBytes(validDexFixture())
                    afterCompile(token)
                    return L8InvocationResult(success = true)
                }
            }

            fun support(l8: L8Provider) = EmbeddedCoreLibraryDesugaringSupport(
                configurationJson = configurationJson,
                programFiles = listOf(runtimeJar),
                l8Compiler = L8RuntimeDexCompiler(l8)
            )
            fun be4(d8: D8Provider, l8: L8Provider) = EmbeddedBuildBackend(
                bootClasspath = { listOf(androidJar) },
                aapt2Compiler = aapt2,
                d8Compiler = D8DexCompiler(d8),
                coreLibraryDesugaring = support(l8)
            )
            fun taskOutcome(result: BuildResult, prefix: String) =
                result.taskResults.single { it.id.startsWith(prefix) }.outcome
            fun assertDexFailureStopsBeforePackaging(result: BuildResult, outcome: BuildOutcome) {
                check(result.outcome == outcome) { "Unexpected BE4 result: ${result.outcome} ${result.diagnostics}" }
                check(taskOutcome(result, "package-apk:") == BuildTaskOutcome.SKIPPED)
                check(taskOutcome(result, "sign-apk:") == BuildTaskOutcome.SKIPPED)
                check(result.producedArtifacts.isEmpty())
            }

            val d8 = CapturingD8()
            val l8 = CapturingL8()
            val output = File(root, "be4-out-min23")
            val result = runAndroidBuild(root, be4(d8, l8), output)
            val moduleOutput = File(output, "modules/$suffix")
            val classes = File(moduleOutput, "classes/java/main")
            val appDex = File(moduleOutput, "dex/app/classes.dex")
            val keepRules = File(moduleOutput, "dex/desugar/l8.pg")
            val runtimeDex = File(moduleOutput, "dex/desugar-runtime/classes.dex")
            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(result.diagnostics.single().let {
                it.code == "EMBEDDED_APK_PACKAGING_UNSUPPORTED" && it.source == BuildDiagnosticSource.PACKAGING
            }) { "BE4 must stop only at APK packaging after D8/L8: ${result.diagnostics}" }
            check(result.taskResults.map { it.id to it.outcome } == listOf(
                "resolve-project" to BuildTaskOutcome.EXECUTED,
                "resolve-classpath:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "link-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-java:$suffix" to BuildTaskOutcome.EXECUTED,
                "dex:$suffix" to BuildTaskOutcome.EXECUTED,
                "package-apk:$suffix" to BuildTaskOutcome.FAILED,
                "sign-apk:$suffix" to BuildTaskOutcome.SKIPPED
            )) { "Unexpected BE4 task sequence: ${result.taskResults.map { it.id to it.outcome }}" }
            listOf(appDex, keepRules, runtimeDex).forEach { file ->
                check(file.isFile && file.length() > 0L) { "Missing BE4 DEX output: $file" }
            }
            check(d8.invocations.size == 1 && l8.invocations.size == 1)
            val d8Invocation = d8.invocations.single()
            val l8Invocation = l8.invocations.single()
            check(d8Invocation.coreLibraryDesugaringConfigurationJson == configurationJson)
            check(l8Invocation.desugaredLibraryConfigurationJson == configurationJson)
            check(d8Invocation.minApi == 23 && l8Invocation.minApi == 23)
            check(d8Invocation.release && l8Invocation.release)
            check(d8Invocation.libraryFiles.single().canonicalFile == androidJar)
            check(l8Invocation.libraryFiles.single().canonicalFile == androidJar)
            check(l8Invocation.programFiles.map { it.canonicalFile } == listOf(runtimeJar))
            check(l8Invocation.keepRulesFile.canonicalFile == keepRules.canonicalFile && keepRules.length() > 0L)
            check(d8Invocation.outputDirectory.canonicalFile == File(moduleOutput, "dex/app").canonicalFile)
            check(l8Invocation.outputDirectory.canonicalFile == File(moduleOutput, "dex/desugar-runtime").canonicalFile)
            check(d8Invocation.programFiles.any { it.canonicalFile.toPath().startsWith(classes.canonicalFile.toPath()) })

            val min26Gradle = File(root, "build.gradle.kts")
            min26Gradle.writeText(min26Gradle.readText().replace("minSdk = 23", "minSdk = 26"))
            val min26D8 = CapturingD8()
            val min26L8 = CapturingL8()
            val min26Result = runAndroidBuild(root, be4(min26D8, min26L8), File(root, "be4-out-min26"))
            check(min26Result.outcome == BuildOutcome.UNSUPPORTED)
            check(min26D8.invocations.single().minApi == 26 && min26L8.invocations.single().minApi == 26) {
                "BE4 must pass minSdk=26 to both D8 and L8"
            }

            val noRulesOutput = File(root, "be4-out-no-rules")
            val noRulesModule = File(noRulesOutput, "modules/$suffix")
            File(noRulesModule, "dex/desugar/l8.pg").apply { parentFile.mkdirs(); writeText("stale rules") }
            File(noRulesModule, "dex/desugar-runtime/classes.dex").apply {
                parentFile.mkdirs()
                writeBytes(validDexFixture())
            }
            val noRulesD8 = CapturingD8(keepRules = null)
            val noRulesL8 = CapturingL8()
            val noRulesResult = runAndroidBuild(root, be4(noRulesD8, noRulesL8), noRulesOutput)
            check(noRulesResult.outcome == BuildOutcome.UNSUPPORTED && taskOutcome(noRulesResult, "dex:") == BuildTaskOutcome.EXECUTED)
            check(noRulesD8.invocations.single().coreLibraryDesugaringConfigurationJson == configurationJson)
            check(noRulesL8.invocations.isEmpty()) { "L8 ran without D8 keep-rules output" }
            check(!File(noRulesModule, "dex/desugar/l8.pg").exists())
            check(File(noRulesModule, "dex/desugar-runtime").listFiles().orEmpty().isEmpty()) {
                "BE4 retained stale runtime DEX after D8 returned no keep-rules artifact"
            }

            val be3Output = File(root, "be4-to-be3-out")
            val firstL8 = CapturingL8()
            val firstD8 = CapturingD8()
            check(runAndroidBuild(root, be4(firstD8, firstL8), be3Output).outcome == BuildOutcome.UNSUPPORTED)
            val legacyD8 = CapturingD8(keepRules = "ignored because legacy BE3 constructor is opt-out")
            val legacyBackend = EmbeddedBuildBackend(
                { listOf(androidJar) }, aapt2, D8DexCompiler(legacyD8)
            )
            val legacyResult = runAndroidBuild(root, legacyBackend, be3Output)
            val be3Module = File(be3Output, "modules/$suffix")
            check(legacyResult.outcome == BuildOutcome.UNSUPPORTED)
            check(legacyD8.invocations.single().coreLibraryDesugaringConfigurationJson == null)
            check(firstL8.invocations.size == 1)
            check(!File(be3Module, "dex/desugar/l8.pg").exists())
            check(File(be3Module, "dex/desugar-runtime").listFiles().orEmpty().isEmpty()) {
                "BE3 opt-out retained stale BE4 runtime DEX"
            }

            val failedL8Calls = CapturingL8()
            val failedD8 = CapturingD8(failure = "synthetic D8 failure")
            val d8Failure = runAndroidBuild(root, be4(failedD8, failedL8Calls), File(root, "be4-out-d8-failed"))
            assertDexFailureStopsBeforePackaging(d8Failure, BuildOutcome.FAILED)
            check(d8Failure.diagnostics.single().let {
                it.code == "D8_COMPILE_FAILED" && it.message.contains("synthetic D8 failure")
            }) { "BE4 changed the D8 failure diagnostic: ${d8Failure.diagnostics}" }
            check(failedL8Calls.invocations.isEmpty())

            val unavailableD8L8 = CapturingL8()
            val unavailableD8Result = runAndroidBuild(
                root,
                be4(object : D8Provider {
                    override val available = false
                    override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult =
                        error("Unavailable D8 provider must not be invoked")
                }, unavailableD8L8),
                File(root, "be4-out-d8-unavailable")
            )
            assertDexFailureStopsBeforePackaging(unavailableD8Result, BuildOutcome.UNSUPPORTED)
            check(unavailableD8Result.diagnostics.single().code == "D8_UNAVAILABLE")
            check(taskOutcome(unavailableD8Result, "dex:") == BuildTaskOutcome.SKIPPED)
            check(unavailableD8L8.invocations.isEmpty())

            val unavailableL8 = CapturingL8(available = false)
            val unavailableD8 = CapturingD8()
            val l8Unavailable = runAndroidBuild(root, be4(unavailableD8, unavailableL8), File(root, "be4-out-l8-unavailable"))
            assertDexFailureStopsBeforePackaging(l8Unavailable, BuildOutcome.UNSUPPORTED)
            check(l8Unavailable.diagnostics.single().code == "L8_UNAVAILABLE")

            val failedL8 = CapturingL8(failure = "synthetic L8 failure")
            val l8Failure = runAndroidBuild(root, be4(CapturingD8(), failedL8), File(root, "be4-out-l8-failed"))
            assertDexFailureStopsBeforePackaging(l8Failure, BuildOutcome.FAILED)
            check(l8Failure.diagnostics.single().let {
                it.code == "L8_COMPILE_FAILED" && it.message.contains("synthetic L8 failure")
            }) { "BE4 changed the L8 failure diagnostic: ${l8Failure.diagnostics}" }

            val limitedL8 = CapturingL8()
            val limitedD8 = CapturingD8()
            val limitedBackend = EmbeddedBuildBackend(
                { listOf(androidJar) },
                aapt2,
                D8DexCompiler(limitedD8),
                support(limitedL8).copy(programFiles = List(17) { runtimeJar })
            )
            val l8ResourceLimit = runAndroidBuild(root, limitedBackend, File(root, "be4-out-l8-resource-limit"))
            assertDexFailureStopsBeforePackaging(l8ResourceLimit, BuildOutcome.RESOURCE_LIMIT)
            check(taskOutcome(l8ResourceLimit, "dex:") == BuildTaskOutcome.SKIPPED)
            check(l8ResourceLimit.diagnostics.single().code == "L8_PROGRAM_INPUT_LIMIT")
            check(limitedD8.invocations.size == 1 && limitedL8.invocations.isEmpty())

            val d8CancellationToken = CancellationToken()
            val l8AfterD8Cancellation = CapturingL8()
            val d8Cancelled = runAndroidBuild(
                root,
                be4(CapturingD8(afterCompile = { it.cancel() }), l8AfterD8Cancellation),
                File(root, "be4-out-d8-cancelled"),
                d8CancellationToken
            )
            assertDexFailureStopsBeforePackaging(d8Cancelled, BuildOutcome.CANCELLED)
            check(taskOutcome(d8Cancelled, "dex:") == BuildTaskOutcome.CANCELLED)
            check(l8AfterD8Cancellation.invocations.isEmpty())

            val l8CancellationToken = CancellationToken()
            val l8Cancelled = runAndroidBuild(
                root,
                be4(CapturingD8(), CapturingL8(afterCompile = { it.cancel() })),
                File(root, "be4-out-l8-cancelled"),
                l8CancellationToken
            )
            assertDexFailureStopsBeforePackaging(l8Cancelled, BuildOutcome.CANCELLED)
            check(taskOutcome(l8Cancelled, "dex:") == BuildTaskOutcome.CANCELLED)
            check(File(root, "be4-out-l8-cancelled/modules/$suffix/dex/desugar-runtime")
                .listFiles().orEmpty().isEmpty()) { "L8 cancellation left partial runtime dex output" }

            val unsafeOutput = File(root, "be4-out-unsafe")
            val unsafeModule = File(unsafeOutput, "modules/$suffix")
            val outside = File(root, "outside-runtime").apply {
                mkdirs()
                File(this, "sentinel.txt").writeText("preserve")
            }
            val unsafeRuntime = File(unsafeModule, "dex/desugar-runtime")
            unsafeRuntime.parentFile.mkdirs()
            val symlinkWasCreated = runCatching {
                Files.createSymbolicLink(unsafeRuntime.toPath(), outside.toPath())
            }.isSuccess
            if (symlinkWasCreated) {
                val unsafeD8 = CapturingD8()
                val unsafeResult = runAndroidBuild(root, be4(unsafeD8, CapturingL8()), unsafeOutput)
                check(unsafeResult.outcome == BuildOutcome.FAILED)
                check(unsafeResult.diagnostics.single().code == "EMBEDDED_DESUGAR_OUTPUT_UNSAFE")
                check(unsafeD8.invocations.isEmpty()) { "D8 ran despite an unsafe BE4 output symlink" }
                check(File(outside, "sentinel.txt").readText() == "preserve")
            } else {
                println("SKIP BE4 output symlink rejection assertion: symbolic-link creation unavailable")
            }

            val ancestorTarget = File(root, "outside-build-root").apply { mkdirs() }
            val ancestorChild = File(ancestorTarget, "child").apply { mkdirs() }
            val ancestorStale = File(
                ancestorChild,
                "modules/$suffix/dex/desugar-runtime/classes.dex"
            ).apply {
                parentFile.mkdirs()
                writeBytes(validDexFixture())
            }
            val ancestorLink = File(root, "be4-output-link")
            val ancestorLinkCreated = runCatching {
                Files.createSymbolicLink(ancestorLink.toPath(), ancestorTarget.toPath())
            }.isSuccess
            if (ancestorLinkCreated) {
                val ancestorD8 = CapturingD8()
                val ancestorResult = runAndroidBuild(
                    root,
                    be4(ancestorD8, CapturingL8()),
                    File(ancestorLink, "child")
                )
                check(ancestorResult.outcome == BuildOutcome.FAILED)
                check(ancestorResult.diagnostics.isNotEmpty()) {
                    "Unsafe ancestor symlink failed without a diagnostic"
                }
                check(ancestorD8.invocations.isEmpty()) {
                    "D8 ran despite a symlink component in the BE4 build output path"
                }
                check(ancestorStale.isFile) {
                    "BE4 followed a symlinked build-output ancestor and deleted external runtime DEX"
                }
            } else {
                println("SKIP BE4 output ancestor-symlink rejection assertion: symbolic-link creation unavailable")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendExecutesAndroidResourcesAndJava() {
        val executable = System.getenv("VEGA_AAPT2")?.takeIf { it.isNotBlank() }?.let(::File)
        val rawR8Jar = System.getenv("VEGA_R8_JAR")?.takeIf { it.isNotBlank() }
        if (executable == null || rawR8Jar == null) {
            println("SKIP BE3 real Android integration: set both VEGA_AAPT2 and VEGA_R8_JAR")
            return
        }
        val r8Jar = File(rawR8Jar)
        check(r8Jar.isFile) { "VEGA_R8_JAR is not an ordinary file: $r8Jar" }
        val provider = NativeAapt2Provider(executable)
        check(provider.available) { "VEGA_AAPT2 is not an ordinary executable file: $executable" }

        val root = createAsciiNativeIntegrationDirectory()
        var d8Loader: URLClassLoader? = null
        try {
            val versionDirectory = File(root, "aapt2-version").apply { mkdirs() }
            val aapt2VersionResult = provider.execute(listOf("version"), versionDirectory, CancellationToken())
            check(aapt2VersionResult.success) { "Could not read AAPT2 version: $aapt2VersionResult" }
            val aapt2Version = (aapt2VersionResult.stdout + aapt2VersionResult.stderr).trim()
            check(aapt2Version.isNotBlank()) { "AAPT2 version output was empty" }

            val loader = URLClassLoader(arrayOf(r8Jar.toURI().toURL()), BuildCoreTests::class.java.classLoader)
            d8Loader = loader
            val d8Provider = dev.wind.agent.build.ReflectionD8Provider(loader)
            check(d8Provider.available) { "VEGA_R8_JAR does not expose the required D8 API" }
            val d8Version = d8Provider.version ?: error("D8 Version.getVersionString() returned no version")
            println("BE3 real integration tools: aapt2=$aapt2Version; d8=$d8Version")

            val repositoryAndroidJar = File("assets/embedded/android-35/android.jar").canonicalFile
            check(repositoryAndroidJar.isFile && repositoryAndroidJar.length() > 0L) {
                "Repository Android 35 platform jar is missing"
            }
            val androidJar = File(root, "android.jar")
            repositoryAndroidJar.copyTo(androidJar, overwrite = true)
            check(sha256(repositoryAndroidJar) == sha256(androidJar)) {
                "ASCII-path Android platform input differs from the repository asset"
            }

            val javaSource = writeAndroidApplicationFixture(root)
            val nativeAapt2 = Aapt2ResourceCompiler(provider)
            val delegate = ReflectionEcjCompilerProvider()
            var ecjBootClasspath: List<File> = emptyList()
            var ecjSources: List<File> = emptyList()
            val recordingEcj = object : EcjCompilerProvider {
                override val available: Boolean get() = delegate.available

                override fun compile(invocation: EcjInvocation, token: CancellationToken): EcjInvocationResult {
                    ecjBootClasspath = invocation.bootClasspath.toList()
                    ecjSources = invocation.sources.toList()
                    check(ecjBootClasspath.singleOrNull()?.canonicalFile == androidJar.canonicalFile) {
                        "ECJ did not receive the exact Android jar resolved for AAPT2 link"
                    }
                    check(ecjSources.any { it.name == "R.java" }) {
                        "Generated R.java was not included in the ECJ source set"
                    }
                    return delegate.compile(invocation, token)
                }
            }
            val backend = EmbeddedBuildBackend(EmbeddedBuildExecutor(
                javaCompiler = EcjJavaCompiler(recordingEcj),
                bootClasspath = { listOf(androidJar) },
                aapt2Compiler = nativeAapt2,
                d8Compiler = D8DexCompiler(d8Provider)
            ))
            val output = File(root, "build-out")
            val suffix = moduleSuffix(":")
            val moduleOutput = File(output, "modules/$suffix")
            val compiledResources = File(moduleOutput, "resources/compiled/res.zip")
            val linkedResources = File(moduleOutput, "resources/linked/resources.ap_")
            val generatedR = File(moduleOutput, "resources/generated/r/dev/wind/fixture/R.java")
            val symbols = File(moduleOutput, "resources/symbols/R.txt")
            val classes = File(moduleOutput, "classes/java/main")
            val dexDirectory = File(moduleOutput, "dex/app")
            val classesDex = File(dexDirectory, "classes.dex")
            val resourceUserClass = File(classes, "dev/wind/fixture/ResourceUser.class")
            val rClass = File(classes, "dev/wind/fixture/R.class")
            val rStringClass = File(classes, "dev/wind/fixture/R" + "$" + "string.class")

            fun execute(outputDirectory: File = output): BuildResult {
                val model = EmbeddedProjectModelParser().parse(root)
                check(model.supported) { "Android fixture was rejected: ${model.unsupportedReasons}" }
                val context = BuildContext(model, outputDirectory)
                val plan = backend.plan(context)
                return backend.execute(context, plan, CancellationToken())
            }

            val successToDex = execute()
            check(successToDex.outcome == BuildOutcome.UNSUPPORTED) {
                "BE3 should stop at APK packaging after real D8 execution: ${successToDex.outcome} ${successToDex.diagnostics}"
            }
            check(successToDex.diagnostics.single().let {
                it.code == "EMBEDDED_APK_PACKAGING_UNSUPPORTED" && it.source == BuildDiagnosticSource.PACKAGING
            }) { "APK packaging must be the sole BE3 stop diagnostic: ${successToDex.diagnostics}" }
            check(successToDex.producedArtifacts.isEmpty()) {
                "Non-success BuildResult must retain the existing empty-artifacts contract"
            }
            check(successToDex.taskResults.map { it.id to it.outcome } == listOf(
                "resolve-project" to BuildTaskOutcome.EXECUTED,
                "resolve-classpath:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "link-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-java:$suffix" to BuildTaskOutcome.EXECUTED,
                "dex:$suffix" to BuildTaskOutcome.EXECUTED,
                "package-apk:$suffix" to BuildTaskOutcome.FAILED,
                "sign-apk:$suffix" to BuildTaskOutcome.SKIPPED
            )) { "BE3 task outcomes do not match the executed vertical slice: ${successToDex.taskResults.map { it.id to it.outcome }}" }
            listOf(compiledResources, linkedResources, generatedR, symbols,
                resourceUserClass, rClass, rStringClass, classesDex)
                .forEach { check(it.isFile && it.length() > 0L) { "Missing BE3 disk output: $it" } }
            listOf(resourceUserClass, rClass).forEach { classFile ->
                val bytes = classFile.readBytes()
                check(bytes.size > 8 && bytes.take(4) == listOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())) {
                    "ECJ output is not a class file: $classFile"
                }
                val major = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
                check(major == 61) { "Expected Java 17 class major 61 in $classFile, got $major" }
            }
            check(ecjBootClasspath.singleOrNull()?.canonicalFile == androidJar.canonicalFile)
            check(ecjSources.any { it.canonicalFile == javaSource.canonicalFile })
            val dexBytes = classesDex.readBytes()
            check(dexBytes.size > 8 && dexBytes.take(4) == listOf(
                'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte()
            )) { "Real D8 did not leave a valid classes.dex at $classesDex" }
            println("BE3 classes.dex: modules/$suffix/dex/app/classes.dex size=${classesDex.length()} sha256=${sha256(classesDex)}")

            val resourceDirectory = File(root, "src/main/res/values")
            val strings = File(resourceDirectory, "strings.xml")
            strings.writeText("<resources><string name=\"app_name\">broken</resources>")
            val malformedResources = execute()
            check(malformedResources.outcome == BuildOutcome.FAILED)
            check(malformedResources.diagnostics.any { it.code == "AAPT2_COMPILE_FAILED" })
            check(malformedResources.taskResults.single { it.id == "compile-resources:$suffix" }.outcome ==
                BuildTaskOutcome.FAILED)
            check(malformedResources.taskResults.filter { it.id in setOf(
                "link-resources:$suffix", "compile-java:$suffix", "dex:$suffix"
            ) }.all { it.outcome == BuildTaskOutcome.SKIPPED })
            check(malformedResources.producedArtifacts.isEmpty())
            check(!compiledResources.exists()) { "AR1 stale res.zip survived a failing compile" }

            strings.writeText("<resources><string name=\"app_name\">Vega BE3</string></resources>")
            javaSource.parentFile.deleteRecursively()
            val resourcesOnly = execute()
            check(resourcesOnly.outcome == BuildOutcome.UNSUPPORTED)
            check(resourcesOnly.taskResults.single { it.id == "compile-java:$suffix" }.outcome ==
                BuildTaskOutcome.EXECUTED) { "Android without user Java must still compile generated R.java" }
            check(rClass.isFile && rStringClass.isFile)
            check(!resourceUserClass.exists()) { "Resource-only Android compile retained stale user classes" }

            val restoredJava = File(root, "src/main/java/dev/wind/fixture/ResourceUser.java").apply {
                parentFile.mkdirs()
                writeText("package dev.wind.fixture; public final class ResourceUser { public static int label() { return R.string.app_name; } }")
            }
            val manifest = File(root, "src/main/AndroidManifest.xml")
            manifest.writeText("<manifest package=\"dev.wind.fixture\"><application></manifest>")
            val brokenManifest = execute()
            check(brokenManifest.outcome == BuildOutcome.FAILED)
            check(brokenManifest.diagnostics.any { it.code == "AAPT2_LINK_FAILED" })
            check(brokenManifest.taskResults.single { it.id == "compile-resources:$suffix" }.outcome ==
                BuildTaskOutcome.EXECUTED)
            check(brokenManifest.taskResults.single { it.id == "link-resources:$suffix" }.outcome ==
                BuildTaskOutcome.FAILED)
            check(brokenManifest.taskResults.single { it.id == "compile-java:$suffix" }.outcome ==
                BuildTaskOutcome.SKIPPED)

            manifest.writeText(androidManifest())
            restoredJava.writeText(
                "package dev.wind.fixture; public final class ResourceUser { public static int label() { return R.string.missing; } }"
            )
            val missingResourceReference = execute()
            check(missingResourceReference.outcome == BuildOutcome.FAILED)
            check(missingResourceReference.diagnostics.any {
                it.source == BuildDiagnosticSource.JAVA && it.code == "ECJ_COMPILE_FAILED"
            })
            check(missingResourceReference.taskResults.single { it.id == "compile-resources:$suffix" }.outcome ==
                BuildTaskOutcome.EXECUTED)
            check(missingResourceReference.taskResults.single { it.id == "link-resources:$suffix" }.outcome ==
                BuildTaskOutcome.EXECUTED)
            check(missingResourceReference.taskResults.single { it.id == "compile-java:$suffix" }.outcome ==
                BuildTaskOutcome.FAILED)
            check(!resourceUserClass.exists() && !rClass.exists()) {
                "Failed ECJ compile left stale class outputs"
            }

            val noAaptBackend = EmbeddedBuildBackend { listOf(androidJar) }
            val noAaptResult = runAndroidBuild(root, noAaptBackend, File(root, "no-aapt-out"))
            check(noAaptResult.outcome == BuildOutcome.UNSUPPORTED)
            check(noAaptResult.diagnostics.any { it.code == "AAPT2_UNAVAILABLE" })
            check(noAaptResult.taskResults.single { it.id == "compile-resources:$suffix" }.outcome ==
                BuildTaskOutcome.SKIPPED)

            val missingPlatformBackend = EmbeddedBuildBackend({ emptyList() }, nativeAapt2)
            val missingPlatformResult = runAndroidBuild(root, missingPlatformBackend, File(root, "no-platform-out"))
            check(missingPlatformResult.outcome == BuildOutcome.UNSUPPORTED)
            check(missingPlatformResult.diagnostics.any { it.code == "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE" })
            check(!File(root, "no-platform-out/modules/$suffix/resources/compiled/res.zip").exists())
        } finally {
            runCatching { d8Loader?.close() }
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendExecutesAndroidCoreLibraryDesugaring() {
        val executable = System.getenv("VEGA_AAPT2")?.takeIf { it.isNotBlank() }?.let(::File)
        val rawR8Jar = System.getenv("VEGA_R8_JAR")?.takeIf { it.isNotBlank() }
        if (executable == null || rawR8Jar == null) {
            println("SKIP BE4 real Android integration: set both VEGA_AAPT2 and VEGA_R8_JAR")
            return
        }
        val r8Jar = File(rawR8Jar)
        check(r8Jar.isFile) { "VEGA_R8_JAR is not an ordinary file: $r8Jar" }
        val aaptProvider = NativeAapt2Provider(executable)
        check(aaptProvider.available) { "VEGA_AAPT2 is not an ordinary executable file: $executable" }

        val root = createAsciiNativeIntegrationDirectory()
        var r8Loader: URLClassLoader? = null
        try {
            val aaptVersionDirectory = File(root, "aapt2-version").apply { mkdirs() }
            val aaptVersionResult = aaptProvider.execute(listOf("version"), aaptVersionDirectory, CancellationToken())
            check(aaptVersionResult.success) { "Could not read AAPT2 version: $aaptVersionResult" }
            val aaptVersion = (aaptVersionResult.stdout + aaptVersionResult.stderr).trim()
            check(aaptVersion.isNotBlank()) { "AAPT2 version output was empty" }

            val loader = URLClassLoader(arrayOf(r8Jar.toURI().toURL()), BuildCoreTests::class.java.classLoader)
            r8Loader = loader
            val realD8 = ReflectionD8Provider(loader)
            val realL8 = ReflectionL8Provider(loader)
            check(realD8.available && realL8.available) { "VEGA_R8_JAR must expose both D8 and L8 APIs" }
            check(realD8.version?.contains("8.2.33") == true && realL8.version?.contains("8.2.33") == true) {
                "BE4 integration requires matching R8/D8/L8 8.2.33 APIs: D8=${realD8.version}; L8=${realL8.version}"
            }

            val repositoryAndroidJar = File("assets/embedded/android-35/android.jar").canonicalFile
            val runtimeJar = File("third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar").canonicalFile
            val configurationJar = File(
                "third_party/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
            ).canonicalFile
            check(repositoryAndroidJar.isFile && runtimeJar.isFile && configurationJar.isFile) {
                "BE4 pinned Android/desugar inputs are missing"
            }
            check(sha256(repositoryAndroidJar) == "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b")
            check(sha256(runtimeJar) == "25ddad0d96cf7ba284cfa7866f54aa3508468a1df80e79cb44733a66f755d330")
            check(sha256(configurationJar) == "1dd26f9e2440904eab5014a244f500d8b453f3629bfd5478a3f9895838e5c35a")
            val configurationJson = JarFile(configurationJar).use { jar ->
                val entry = jar.getJarEntry("META-INF/desugar/d8/desugar.json")
                    ?: error("Pinned configuration archive lacks META-INF/desugar/d8/desugar.json")
                jar.getInputStream(entry).use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            }
            check(configurationJson.contains("com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1"))
            check(configurationJson.contains("\"configuration_format_version\":200"))
            check(configurationJson.contains("j$/"))

            val androidJar = File(root, "android.jar")
            repositoryAndroidJar.copyTo(androidJar, overwrite = true)
            check(sha256(repositoryAndroidJar) == sha256(androidJar))
            val javaSource = writeAndroidApplicationFixture(root)
            val nioSource = File(root, "src/main/java/dev/wind/fixture/NioUser.java").apply {
                writeText(
                    "package dev.wind.fixture; public final class NioUser { " +
                        "public static boolean exists(String path) { " +
                        "return java.nio.file.Files.exists(java.nio.file.Paths.get(path)); } }"
                )
            }
            val ecjDelegate = ReflectionEcjCompilerProvider()
            val ecjSources = mutableListOf<File>()
            val ecjBootClasspath = mutableListOf<File>()
            val recordingEcj = object : EcjCompilerProvider {
                override val available: Boolean get() = ecjDelegate.available
                override fun compile(invocation: EcjInvocation, token: CancellationToken): EcjInvocationResult {
                    ecjSources += invocation.sources
                    ecjBootClasspath += invocation.bootClasspath
                    check(invocation.bootClasspath.singleOrNull()?.canonicalFile == androidJar.canonicalFile)
                    check(invocation.sources.any { it.canonicalFile == javaSource.canonicalFile })
                    check(invocation.sources.any { it.canonicalFile == nioSource.canonicalFile })
                    check(invocation.sources.any { it.name == "R.java" })
                    return ecjDelegate.compile(invocation, token)
                }
            }
            val d8Invocations = mutableListOf<D8Invocation>()
            val l8Invocations = mutableListOf<L8Invocation>()
            val recordingD8 = object : D8Provider {
                override val available: Boolean get() = realD8.available
                override fun compile(invocation: D8Invocation, token: CancellationToken): D8InvocationResult {
                    d8Invocations += invocation
                    return realD8.compile(invocation, token)
                }
            }
            val recordingL8 = object : L8Provider {
                override val available: Boolean get() = realL8.available
                override fun compile(invocation: L8Invocation, token: CancellationToken): L8InvocationResult {
                    l8Invocations += invocation
                    return realL8.compile(invocation, token)
                }
            }
            val backend = EmbeddedBuildBackend(EmbeddedBuildExecutor(
                javaCompiler = EcjJavaCompiler(recordingEcj),
                bootClasspath = { listOf(androidJar) },
                aapt2Compiler = Aapt2ResourceCompiler(aaptProvider),
                d8Compiler = D8DexCompiler(recordingD8),
                coreLibraryDesugaring = EmbeddedCoreLibraryDesugaringSupport(
                    configurationJson = configurationJson,
                    programFiles = listOf(runtimeJar, configurationJar),
                    l8Compiler = L8RuntimeDexCompiler(recordingL8)
                )
            ))
            val output = File(root, "build-out")
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported) { "BE4 Android fixture rejected: ${model.unsupportedReasons}" }
            val context = BuildContext(model, output)
            val result = backend.execute(context, backend.plan(context), CancellationToken())

            val suffix = moduleSuffix(":")
            val moduleOutput = File(output, "modules/$suffix")
            val compiledResources = File(moduleOutput, "resources/compiled/res.zip")
            val linkedResources = File(moduleOutput, "resources/linked/resources.ap_")
            val generatedR = File(moduleOutput, "resources/generated/r/dev/wind/fixture/R.java")
            val symbols = File(moduleOutput, "resources/symbols/R.txt")
            val classes = File(moduleOutput, "classes/java/main")
            val resourceUserClass = File(classes, "dev/wind/fixture/ResourceUser.class")
            val nioUserClass = File(classes, "dev/wind/fixture/NioUser.class")
            val rClass = File(classes, "dev/wind/fixture/R.class")
            val appDex = File(moduleOutput, "dex/app/classes.dex")
            val keepRules = File(moduleOutput, "dex/desugar/l8.pg")
            val runtimeDex = File(moduleOutput, "dex/desugar-runtime/classes.dex")
            check(result.outcome == BuildOutcome.UNSUPPORTED)
            check(result.diagnostics.single().let {
                it.code == "EMBEDDED_APK_PACKAGING_UNSUPPORTED" && it.source == BuildDiagnosticSource.PACKAGING
            }) { "BE4 must stop at unsupported APK packaging after real D8/L8: ${result.diagnostics}" }
            check(result.producedArtifacts.isEmpty())
            check(result.taskResults.map { it.id to it.outcome } == listOf(
                "resolve-project" to BuildTaskOutcome.EXECUTED,
                "resolve-classpath:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "link-resources:$suffix" to BuildTaskOutcome.EXECUTED,
                "compile-java:$suffix" to BuildTaskOutcome.EXECUTED,
                "dex:$suffix" to BuildTaskOutcome.EXECUTED,
                "package-apk:$suffix" to BuildTaskOutcome.FAILED,
                "sign-apk:$suffix" to BuildTaskOutcome.SKIPPED
            )) { "BE4 real task outcomes differ from the requested vertical slice" }
            listOf(compiledResources, linkedResources, generatedR, symbols, resourceUserClass, nioUserClass,
                rClass, appDex, keepRules, runtimeDex).forEach { file ->
                check(file.isFile && file.length() > 0L) { "BE4 real integration output missing: $file" }
            }
            check(ecjSources.any { it.canonicalFile == javaSource.canonicalFile })
            check(ecjSources.any { it.canonicalFile == nioSource.canonicalFile })
            check(ecjBootClasspath.singleOrNull()?.canonicalFile == androidJar.canonicalFile)
            val d8Invocation = d8Invocations.single()
            val l8Invocation = l8Invocations.single()
            check(d8Invocation.coreLibraryDesugaringConfigurationJson == configurationJson)
            check(l8Invocation.desugaredLibraryConfigurationJson == configurationJson)
            check(d8Invocation.libraryFiles.single().canonicalFile == androidJar.canonicalFile)
            check(l8Invocation.libraryFiles.single().canonicalFile == androidJar.canonicalFile)
            check(d8Invocation.minApi == 23 && l8Invocation.minApi == 23)
            check(d8Invocation.release && l8Invocation.release)
            check(l8Invocation.programFiles.map { it.canonicalFile } == listOf(runtimeJar, configurationJar))
            check(l8Invocation.keepRulesFile.canonicalFile == keepRules.canonicalFile)
            check(l8Invocation.outputDirectory.canonicalFile == File(moduleOutput, "dex/desugar-runtime").canonicalFile)
            val appDexText = String(appDex.readBytes(), StandardCharsets.ISO_8859_1)
            val runtimeDexText = String(runtimeDex.readBytes(), StandardCharsets.ISO_8859_1)
            check(appDexText.startsWith("dex\n") && appDexText.contains("j$/nio/file")) {
                "D8 app DEX lacks NIO core-library desugaring evidence"
            }
            check(runtimeDexText.startsWith("dex\n") && runtimeDexText.contains("j$/nio/file")) {
                "L8 runtime DEX lacks j$/nio/file implementation evidence"
            }
            println(
                "BE4 real integration OK: aapt2=$aaptVersion; r8=${realD8.version}; " +
                    "l8pgSha256=${sha256(keepRules)}; appDexSha256=${sha256(appDex)}; " +
                    "runtimeDexSha256=${sha256(runtimeDex)}"
            )
        } finally {
            runCatching { r8Loader?.close() }
            root.deleteRecursively()
        }
    }

    private fun writeAndroidApplicationFixture(root: File): File {
        File(root, "build.gradle.kts").writeText(
            "plugins { id(\"com.android.application\") }\n" +
                "android { namespace = \"dev.wind.fixture\"; compileSdk = 35; " +
                "defaultConfig { minSdk = 23; targetSdk = 35 } }"
        )
        File(root, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText(androidManifest())
        }
        File(root, "src/main/res/values/strings.xml").apply {
            parentFile.mkdirs()
            writeText("<resources><string name=\"app_name\">Vega BE2</string></resources>")
        }
        return File(root, "src/main/java/dev/wind/fixture/ResourceUser.java").apply {
            parentFile.mkdirs()
            writeText(
                "package dev.wind.fixture; public final class ResourceUser { " +
                    "public static int label() { return R.string.app_name; } }"
            )
        }
    }

    private fun androidManifest(): String =
        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
            "package=\"dev.wind.fixture\"><application android:label=\"@string/app_name\" /></manifest>"

    private fun runAndroidBuild(
        root: File,
        backend: EmbeddedBuildBackend,
        outputDirectory: File,
        token: CancellationToken = CancellationToken()
    ): BuildResult {
        val model = EmbeddedProjectModelParser().parse(root)
        check(model.supported) { "Android fixture was rejected: ${model.unsupportedReasons}" }
        val context = BuildContext(model, outputDirectory)
        return backend.execute(context, backend.plan(context), token)
    }

    private fun createAsciiNativeIntegrationDirectory(): File {
        val candidates = listOf("TEMP", "TMP", "TMPDIR")
            .mapNotNull { name -> System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File) } +
            listOfNotNull(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "Temp") }) +
            listOf(File("C:/Temp"), File("D:/Temp"))
        val parent = candidates.firstOrNull {
            it.isDirectory && it.canWrite() && it.absolutePath.all { character -> character.code < 128 }
        } ?: error("BE3 native AAPT2 integration requires an ASCII writable temporary directory")
        val root = Files.createTempDirectory(parent.toPath(), "vega be3 android integration").toFile()
        check(root.absolutePath.all { it.code < 128 }) {
            "Native AAPT2 integration path must be ASCII on this Windows host: ${root.absolutePath}"
        }
        return root
    }

    private fun embeddedBackendFailsClosedAndClearsStaleOutput() {
        val root = Files.createTempDirectory("vega-build-java-failures").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val source = File(root, "src/main/java/example/Hello.java").apply {
                parentFile.mkdirs()
                writeText("package example; public class Hello { public static int value() { return 7; } }")
            }
            val parser = EmbeddedProjectModelParser()
            val backend = EmbeddedBuildBackend()
            val model = parser.parse(root)
            val context = BuildContext(model, File(root, "build-out"))
            val initial = backend.execute(context, backend.plan(context), CancellationToken())
            check(initial.outcome == BuildOutcome.SUCCESS) { initial.diagnostics.toString() }

            val suffix = moduleSuffix(":")
            val output = File(context.outputDirectory, "modules/$suffix/classes/java/main")
            val fakeOldClass = File(output, "stale/Old.class").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 1, 2))
            }
            check(fakeOldClass.isFile)

            source.writeText("package example; public class Hello { public void broken( { }")
            val failedContext = BuildContext(parser.parse(root), context.outputDirectory)
            val failed = backend.execute(failedContext, backend.plan(failedContext), CancellationToken())
            check(failed.outcome == BuildOutcome.FAILED)
            check(failed.producedArtifacts.isEmpty())
            check(failed.diagnostics.any { it.source == BuildDiagnosticSource.JAVA && it.code == "ECJ_COMPILE_FAILED" }) {
                "Expected original ECJ Java diagnostics: ${failed.diagnostics}"
            }
            check(failed.taskResults.map { it.outcome } == listOf(
                BuildTaskOutcome.EXECUTED,
                BuildTaskOutcome.EXECUTED,
                BuildTaskOutcome.FAILED
            )) { "Compile failure task states are inaccurate: ${failed.taskResults.map { it.outcome }}" }
            check(!fakeOldClass.exists() && output.walkTopDown().none { it.isFile && it.extension == "class" }) {
                "Stale/fake class output survived a failed Java compilation"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendClearsPartialProviderOutput() {
        val root = Files.createTempDirectory("vega-build-java-partial-provider").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            File(root, "src/main/java/example/Hello.java").apply {
                parentFile.mkdirs()
                writeText("package example; public class Hello {}")
            }
            val project = EmbeddedProjectModelParser().parse(root)
            val context = BuildContext(project, File(root, "out"))
            val partialOutput = File(root, "out/modules/${moduleSuffix(":")}/classes/java/main/example/Partial.class")
            val provider = object : EcjCompilerProvider {
                override val available: Boolean = true
                override fun compile(invocation: EcjInvocation, token: CancellationToken): EcjInvocationResult {
                    File(invocation.outputDirectory, "example/Partial.class").apply {
                        parentFile.mkdirs()
                        writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 61, 1, 2))
                    }
                    return EcjInvocationResult(success = false, stderr = "provider failed after partial output")
                }
            }
            val backend = EmbeddedBuildBackend(EmbeddedBuildExecutor(EcjJavaCompiler(provider)))

            val result = backend.execute(context, backend.plan(context), CancellationToken())

            check(result.outcome == BuildOutcome.FAILED)
            check(result.diagnostics.any { it.code == "ECJ_COMPILE_FAILED" })
            check(result.producedArtifacts.isEmpty())
            check(!partialOutput.exists()) { "Partial class output survived an unsuccessful ECJ provider call" }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendRejectsForgedPlanAndOptions() {
        val root = Files.createTempDirectory("vega-build-java-plan-guard").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            File(root, "src/main/java/example/Hello.java").apply {
                parentFile.mkdirs()
                writeText("package example; public class Hello {}")
            }
            val model = EmbeddedProjectModelParser().parse(root)
            val backend = EmbeddedBuildBackend()
            val context = BuildContext(model, File(root, "forged-out"))
            val forged = BuildPlan(
                backend = BuildBackendId.EMBEDDED,
                tasks = listOf(BuildTaskPlan("resolve-project", "forged task list")),
                execution = BuildExecutionInputs.from(context)
            )

            val forgedResult = backend.execute(context, forged, CancellationToken())
            check(forgedResult.outcome == BuildOutcome.FAILED)
            check(forgedResult.diagnostics.any { it.code == "BUILD_PLAN_CONTEXT_MISMATCH" })
            check(forgedResult.producedArtifacts.isEmpty())
            check(!File(context.outputDirectory, "modules/${moduleSuffix(":")}/classes/java/main/example/Hello.class").exists()) {
                "Forged resolve-only plan bypassed Java compilation and reported success"
            }

            val optionContext = BuildContext(
                model,
                File(root, "options-out"),
                options = mapOf("mode" to "release")
            )
            val optionResult = backend.execute(optionContext, backend.plan(optionContext), CancellationToken())
            check(optionResult.outcome == BuildOutcome.UNSUPPORTED)
            check(optionResult.diagnostics.any { it.code == "EMBEDDED_BUILD_OPTIONS_UNSUPPORTED" })
            check(optionResult.producedArtifacts.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendRejectsUnsupportedShapes() {
        val root = Files.createTempDirectory("vega-build-java-unsupported").toFile()
        try {
            val backend = EmbeddedBuildBackend()
            val parser = EmbeddedProjectModelParser()

            val kotlinRoot = File(root, "kotlin").apply { mkdirs() }
            File(kotlinRoot, "build.gradle.kts").writeText("plugins { id(\"org.jetbrains.kotlin.jvm\") }")
            File(kotlinRoot, "src/main/kotlin/example").mkdirs()
            val kotlinModel = parser.parse(kotlinRoot)
            val kotlinContext = BuildContext(kotlinModel, File(kotlinRoot, "out"))
            val kotlinResult = backend.execute(kotlinContext, backend.plan(kotlinContext), CancellationToken())
            check(kotlinResult.outcome == BuildOutcome.UNSUPPORTED)
            check(kotlinResult.diagnostics.any { it.code == "EMBEDDED_KOTLIN_EXECUTION_UNSUPPORTED" })
            check(kotlinResult.producedArtifacts.isEmpty())

            val androidRoot = File(root, "android").apply { mkdirs() }
            File(androidRoot, "build.gradle.kts").writeText(
                """
                plugins { id("com.android.application") }
                android {
                    namespace = "example.app"
                    compileSdk = 35
                    defaultConfig { minSdk = 23; targetSdk = 35 }
                }
                """.trimIndent()
            )
            File(androidRoot, "src/main/java/example").mkdirs()
            File(androidRoot, "src/main/res/values").mkdirs()
            File(androidRoot, "src/main/AndroidManifest.xml").writeText(
                "<manifest package=\"example.app\"><application /></manifest>"
            )
            val androidModel = parser.parse(androidRoot)
            check(androidModel.supported) { "Android negative fixture was rejected during modelling: ${androidModel.unsupportedReasons}" }
            val androidContext = BuildContext(androidModel, File(androidRoot, "out"))
            val androidResult = backend.execute(androidContext, backend.plan(androidContext), CancellationToken())
            check(androidResult.outcome == BuildOutcome.UNSUPPORTED)
            check(androidResult.diagnostics.any { it.code == "EMBEDDED_ANDROID_PLATFORM_UNAVAILABLE" })
            check(androidResult.producedArtifacts.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun embeddedBackendRejectsDependenciesAndTaskSelection() {
        val root = Files.createTempDirectory("vega-build-java-dependencies").toFile()
        try {
            val backend = EmbeddedBuildBackend()
            val parser = EmbeddedProjectModelParser()

            val externalRoot = File(root, "external").apply { mkdirs() }
            File(externalRoot, "build.gradle.kts").writeText(
                "plugins { java }\ndependencies { implementation(\"example:library:1.0\") }"
            )
            File(externalRoot, "src/main/java/example").mkdirs()
            val externalModel = parser.parse(externalRoot)
            check(externalModel.supported) { externalModel.unsupportedReasons.toString() }
            val externalContext = BuildContext(externalModel, File(externalRoot, "out"))
            val externalResult = backend.execute(externalContext, backend.plan(externalContext), CancellationToken())
            val externalSuffix = moduleSuffix(":")
            check(externalResult.outcome == BuildOutcome.UNSUPPORTED)
            check(externalResult.diagnostics.any { it.code == "EMBEDDED_EXTERNAL_DEPENDENCY_UNSUPPORTED" })
            check(externalResult.taskResults.map { it.id to it.outcome } == listOf(
                "resolve-project" to BuildTaskOutcome.EXECUTED,
                "resolve-classpath:$externalSuffix" to BuildTaskOutcome.FAILED,
                "compile-java:$externalSuffix" to BuildTaskOutcome.SKIPPED
            ))

            val multiRoot = File(root, "project-dependency").apply { mkdirs() }
            File(multiRoot, "settings.gradle.kts").writeText("include(\":app\", \":core\")")
            val app = File(multiRoot, "app").apply { mkdirs() }
            val core = File(multiRoot, "core").apply { mkdirs() }
            File(app, "build.gradle.kts").writeText(
                "plugins { java }\ndependencies { implementation(project(\":core\")) }"
            )
            File(core, "build.gradle.kts").writeText("plugins { java }")
            val projectModel = parser.parse(multiRoot)
            check(projectModel.supported) { projectModel.unsupportedReasons.toString() }
            val projectContext = BuildContext(projectModel, File(multiRoot, "out"))
            val projectResult = backend.execute(projectContext, backend.plan(projectContext), CancellationToken())
            check(projectResult.outcome == BuildOutcome.UNSUPPORTED)
            check(projectResult.diagnostics.any { it.code == "EMBEDDED_PROJECT_DEPENDENCY_EXECUTION_UNSUPPORTED" })
            val appResolveId = "resolve-classpath:${moduleSuffix(":app")}"
            check(projectResult.taskResults.single { it.id == appResolveId }.outcome == BuildTaskOutcome.FAILED)
            check(projectResult.taskResults.filter { it.id.startsWith("compile-java:") }
                .all { it.outcome == BuildTaskOutcome.SKIPPED })

            val requested = BuildContext(externalModel, File(externalRoot, "requested-out"), requestedTasks = setOf("test"))
            val requestedResult = backend.execute(requested, backend.plan(requested), CancellationToken())
            check(requestedResult.outcome == BuildOutcome.UNSUPPORTED)
            check(requestedResult.diagnostics.any { it.code == "EMBEDDED_REQUESTED_TASK_EXECUTION_UNSUPPORTED" })
            check(requestedResult.producedArtifacts.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun planFingerprintIncludesExecutionInputs() {
        val root = Files.createTempDirectory("vega-build-fingerprint").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(root)
            val backend = dev.wind.agent.build.EmbeddedBuildBackend()
            val first = backend.plan(BuildContext(model, File(root, "out-a"), options = mapOf("mode" to "debug")))
            val second = backend.plan(BuildContext(model, File(root, "out-b"), options = mapOf("mode" to "debug")))
            val third = backend.plan(BuildContext(model, File(root, "out-a"), options = mapOf("mode" to "release")))
            val fourth = backend.plan(BuildContext(model, File(root, "out-a"), requestedTasks = setOf("test")))
            check(first.fingerprint != second.fingerprint)
            check(first.fingerprint != third.fingerprint)
            check(first.fingerprint != fourth.fingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun planContextMismatchAndProjectFingerprintCoverage() {
        val root = Files.createTempDirectory("vega-build-context").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val parser = EmbeddedProjectModelParser()
            val modelA = parser.parse(root)
            val backend = EmbeddedBuildBackend()
            val contextA = BuildContext(modelA, File(root, "out"))
            val planA = backend.plan(contextA)

            File(root, "src/test/java/example").mkdirs()
            val modelB = parser.parse(root)
            val contextB = BuildContext(modelB, File(root, "out"))
            check(planA.execution.projectFingerprint != BuildExecutionInputs.from(contextB).projectFingerprint)
            val mismatch = backend.execute(contextB, planA, CancellationToken())
            check(mismatch.outcome == BuildOutcome.FAILED)
            check(mismatch.diagnostics.any { it.code == "BUILD_PLAN_CONTEXT_MISMATCH" })

            val execution = BuildExecutionInputs.from(contextB)
            val diagnosticA = BuildDiagnostic(
                BuildDiagnosticSeverity.ERROR, "TEST", "same", BuildDiagnosticSource.PROJECT,
                path = "a.gradle", line = 1, column = 2
            )
            val diagnosticB = diagnosticA.copy(column = 3)
            check(BuildPlan(BuildBackendId.EMBEDDED, emptyList(), execution, listOf(diagnosticA)).fingerprint !=
                BuildPlan(BuildBackendId.EMBEDDED, emptyList(), execution, listOf(diagnosticB)).fingerprint)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun immutablePlanSnapshots() {
        val root = Files.createTempDirectory("vega-build-immutable").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(root)
            val requested = mutableListOf("compile")
            val options = mutableMapOf("mode" to "debug")
            val execution = BuildExecutionInputs(
                root.canonicalPath, File(root, "out").canonicalPath, requested, options,
                BuildExecutionInputs.from(BuildContext(model, File(root, "out"))).projectFingerprint
            )
            val taskInputs = mutableListOf(BuildTaskPlan("one", "one"))
            val diagnosticInputs = mutableListOf<BuildDiagnostic>()
            val plan = BuildPlan(BuildBackendId.EMBEDDED, taskInputs, execution, diagnosticInputs)
            requested += "test"
            options["other"] = "value"
            taskInputs += BuildTaskPlan("two", "two")
            diagnosticInputs += BuildDiagnostic(
                BuildDiagnosticSeverity.ERROR, "LATE", "late", BuildDiagnosticSource.PROJECT
            )
            check(execution.requestedTasks == listOf("compile"))
            check(execution.options == mapOf("mode" to "debug"))
            check(plan.tasks.map { it.id } == listOf("one"))
            check(plan.diagnostics.isEmpty())
            check(runCatching { (execution.requestedTasks as MutableList<String>) += "x" }.isFailure)
            check(runCatching { (plan.tasks as MutableList<BuildTaskPlan>) += BuildTaskPlan("x", "x") }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun planRejectsCycles() {
        val root = Files.createTempDirectory("vega-build-plan-cycle").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(root)
            val execution = BuildExecutionInputs.from(BuildContext(model, File(root, "out")))
            val tasks = listOf(
                BuildTaskPlan("a", "a", listOf("b")),
                BuildTaskPlan("b", "b", listOf("a"))
            )
            check(runCatching { BuildPlan(BuildBackendId.EMBEDDED, tasks, execution) }
                .exceptionOrNull()?.message == "BUILD_TASK_DEPENDENCY_CYCLE")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun resultRejectsContradictorySuccess() {
        val diagnostic = BuildDiagnostic(
            BuildDiagnosticSeverity.ERROR,
            "TEST_ERROR",
            "error",
            BuildDiagnosticSource.TEST
        )
        check(runCatching {
            BuildResult(
                outcome = BuildOutcome.SUCCESS,
                diagnostics = listOf(diagnostic)
            )
        }.exceptionOrNull()?.message == "BUILD_SUCCESS_WITH_ERROR_DIAGNOSTIC")
        check(runCatching {
            BuildResult(
                outcome = BuildOutcome.SUCCESS,
                taskResults = listOf(BuildTaskResult("compile", BuildTaskOutcome.FAILED))
            )
        }.exceptionOrNull()?.message == "BUILD_SUCCESS_WITH_INCOMPLETE_TASK")
        check(runCatching {
            BuildResult(
                outcome = BuildOutcome.SUCCESS,
                taskResults = listOf(
                    BuildTaskResult(
                        "compile",
                        BuildTaskOutcome.EXECUTED,
                        diagnostics = listOf(diagnostic)
                    )
                )
            )
        }.exceptionOrNull()?.message == "BUILD_SUCCESS_WITH_ERROR_DIAGNOSTIC")

        val diagnostics = mutableListOf<BuildDiagnostic>()
        val artifacts = mutableListOf<dev.wind.agent.build.BuildArtifact>()
        val tasks = mutableListOf<BuildTaskResult>()
        val result = BuildResult(
            outcome = BuildOutcome.UNSUPPORTED,
            diagnostics = diagnostics,
            producedArtifacts = artifacts,
            taskResults = tasks
        )
        diagnostics += diagnostic
        artifacts += dev.wind.agent.build.BuildArtifact("apk", File("artifact.apk"))
        tasks += BuildTaskResult("compile", BuildTaskOutcome.EXECUTED)
        check(result.diagnostics.isEmpty() && result.producedArtifacts.isEmpty() && result.taskResults.isEmpty())
        check(runCatching { (result.diagnostics as MutableList<BuildDiagnostic>) += diagnostic }.isFailure)
    }

    private fun shellRouteRequiresExplicitSelectionAndHonorsCancellation() {
        val root = Files.createTempDirectory("vega-build-shell").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(root)
            val context = BuildContext(model, File(root, "build-out"))
            val shell = ShellBuildBackend { _, _ ->
                error("shell executor must not start after cancellation")
            }
            check(BuildBackendRegistry(listOf(shell)).select(model) == null)
            val cancelled = CancellationToken().also { it.cancel() }
            check(shell.execute(context, shell.plan(context), cancelled).outcome == BuildOutcome.CANCELLED)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validDexFixture(): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '9'.code.toByte(), 0, 0x70, 0x00
    )

    private class FakeAndroidAapt2Provider : Aapt2Provider {
        override val available: Boolean = true

        override fun execute(
            arguments: List<String>,
            workingDirectory: File,
            token: CancellationToken
        ): Aapt2InvocationResult {
            if (token.isCancelled) return Aapt2InvocationResult(-1, stderr = "cancelled")
            return when (arguments.firstOrNull()) {
                "compile" -> {
                    val output = optionValue(arguments, "-o")
                    writeZip(output, mapOf("compiled/values_strings.arsc" to "compiled resources"))
                    Aapt2InvocationResult(0)
                }
                "link" -> {
                    val output = optionValue(arguments, "-o")
                    val javaDirectory = optionValue(arguments, "--java")
                    val symbols = optionValue(arguments, "--output-text-symbols")
                    writeZip(output, mapOf(
                        "AndroidManifest.xml" to "<manifest package=dev.wind.fixture />",
                        "resources.arsc" to "compiled table"
                    ))
                    File(javaDirectory, "dev/wind/fixture/R.java").apply {
                        parentFile.mkdirs()
                        writeText(
                            """
                            package dev.wind.fixture;
                            public final class R {
                                public static final class string {
                                    public static final int app_name = 0x7f010001;
                                }
                            }
                            """.trimIndent()
                        )
                    }
                    symbols.apply {
                        parentFile.mkdirs()
                        writeText("int string app_name 0x7f010001\n")
                    }
                    Aapt2InvocationResult(0)
                }
                else -> Aapt2InvocationResult(2, stderr = "unexpected fake AAPT2 operation")
            }
        }

        private fun optionValue(arguments: List<String>, option: String): File {
            val index = arguments.indexOf(option)
            check(index >= 0 && index + 1 < arguments.size) { "Missing fake AAPT2 option $option: $arguments" }
            return File(arguments[index + 1])
        }

        private fun writeZip(output: File, entries: Map<String, String>) {
            output.parentFile.mkdirs()
            ZipOutputStream(output.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    private fun moduleSuffix(name: String): String =
        name.toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
