package dev.wind.agent

import dev.wind.agent.build.AndroidPlatformProvider
import dev.wind.agent.build.ClasspathEntryKind
import dev.wind.agent.build.ClasspathResolver
import dev.wind.agent.build.EmbeddedProjectModel
import dev.wind.agent.build.EmbeddedProjectModelParser
import dev.wind.agent.build.LocalArtifact
import dev.wind.agent.build.LocalArtifactStore
import dev.wind.agent.build.ProjectModelDependencyIndex
import java.io.File
import java.nio.file.Files

/** B2 offline classpath resolution and fail-closed path contracts. */
object ClasspathResolverTests {
    @JvmStatic
    fun main(args: Array<String>) {
        resolvesApprovedOfflineSources()
        resolutionIsDeterministicAndDeduplicated()
        missingArtifactFailsClosed()
        unsafePlatformPathFailsClosed()
        missingProjectOutputFailsClosed()
        jvmWithoutDependenciesNeedsNoProviders()
        println("ClasspathResolverTests: OK")
    }

    private fun resolvesApprovedOfflineSources() {
        val fixture = Fixture.create()
        try {
            val app = fixture.model.module(":app")!!
            val result = fixture.resolver().resolve(fixture.model, app)
            check(result.success) {
                "approved offline classpath was rejected: " + result.diagnostics
            }
            check(result.entries.map { it.kind } == listOf(
                ClasspathEntryKind.ANDROID_PLATFORM,
                ClasspathEntryKind.PROJECT_OUTPUT,
                ClasspathEntryKind.PROJECT_LIBRARY,
                ClasspathEntryKind.PROJECT_LIBRARY,
                ClasspathEntryKind.BUNDLED_ARTIFACT
            )) { "unexpected entry kinds: " + result.entries }
            check(result.entries[0].file.name == "android.jar")
            check(result.entries[1].coordinate == ":core")
            check(result.entries[2].file.name == "a.jar")
            check(result.entries[3].file.name == "z.jar")
            check(result.entries[4].coordinate == "com.example:local:1.0")
        } finally {
            fixture.close()
        }
    }

    private fun resolutionIsDeterministicAndDeduplicated() {
        val fixture = Fixture.create()
        try {
            val first = fixture.resolver().resolve(fixture.model, ":app")
            val second = fixture.resolver().resolve(fixture.model, ":app")
            check(first == second) { "classpath resolution is not deterministic" }

            val duplicateStore = object : LocalArtifactStore {
                override val approvedRoots = listOf(fixture.root)
                override fun resolve(notation: String): LocalArtifact? =
                    LocalArtifact(
                        fixture.appLibA,
                        ClasspathEntryKind.BUNDLED_ARTIFACT,
                        notation
                    )
            }
            val duplicate = ClasspathResolver(
                fixture.platformProvider(),
                duplicateStore,
                ProjectModelDependencyIndex(fixture.model)
            ).resolve(fixture.model, ":app")
            check(duplicate.success)
            check(duplicate.entries.count {
                it.file.canonicalFile == fixture.appLibA.canonicalFile
            } == 1)
            check(duplicate.entries.single {
                it.file.canonicalFile == fixture.appLibA.canonicalFile
            }.kind == ClasspathEntryKind.PROJECT_LIBRARY)
        } finally {
            fixture.close()
        }
    }

    private fun missingArtifactFailsClosed() {
        val fixture = Fixture.create()
        try {
            val missingStore = object : LocalArtifactStore {
                override val approvedRoots = listOf(fixture.offlineRoot)
                override fun resolve(notation: String): LocalArtifact? = null
            }
            val result = ClasspathResolver(
                fixture.platformProvider(),
                missingStore,
                ProjectModelDependencyIndex(fixture.model)
            ).resolve(fixture.model, ":app")
            check(!result.success)
            check(result.entries.isEmpty())
            check(result.diagnostics.any { it.code == "DEPENDENCY_UNAVAILABLE" })
        } finally {
            fixture.close()
        }
    }

    private fun unsafePlatformPathFailsClosed() {
        val fixture = Fixture.create()
        try {
            val outside = File(fixture.root.parentFile, "vega-unsafe-android.jar").apply {
                writeText("not approved")
            }
            val provider = object : AndroidPlatformProvider {
                override val approvedRoots = listOf(fixture.platformRoot)
                override fun resolve(compileSdk: Int): File = outside
            }
            val result = ClasspathResolver(
                provider,
                fixture.store,
                ProjectModelDependencyIndex(fixture.model)
            ).resolve(fixture.model, ":app")
            check(!result.success)
            check(result.entries.isEmpty())
            check(result.diagnostics.any { it.code == "DEPENDENCY_PATH_UNSAFE" })
            outside.delete()
        } finally {
            fixture.close()
        }
    }

    private fun missingProjectOutputFailsClosed() {
        val fixture = Fixture.create()
        try {
            fixture.coreOutput.deleteRecursively()
            val result = fixture.resolver().resolve(fixture.model, ":app")
            check(!result.success)
            check(result.entries.isEmpty())
            check(result.diagnostics.any { it.code == "DEPENDENCY_UNAVAILABLE" })
        } finally {
            fixture.close()
        }
    }

    private fun jvmWithoutDependenciesNeedsNoProviders() {
        val root = Files.createTempDirectory("vega-jvm-classpath").toFile()
        try {
            File(root, "build.gradle.kts").writeText("plugins { java }")
            val model = EmbeddedProjectModelParser().parse(root)
            check(model.supported)
            val result = ClasspathResolver(
                androidPlatformProvider = null,
                localArtifactStore = null,
                dependencyIndex = ProjectModelDependencyIndex(model)
            ).resolve(model, model.modules.single())
            check(result.success)
            check(result.entries.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture private constructor(
        val root: File,
        val model: EmbeddedProjectModel,
        val platformRoot: File,
        val offlineRoot: File,
        val appLibA: File,
        val store: LocalArtifactStore,
        val coreOutput: File
    ) {
        fun platformProvider() = object : AndroidPlatformProvider {
            override val approvedRoots = listOf(platformRoot)
            override fun resolve(compileSdk: Int): File = File(platformRoot, "android.jar")
        }

        fun resolver() = ClasspathResolver(
            platformProvider(),
            store,
            ProjectModelDependencyIndex(model)
        )

        fun close() {
            root.deleteRecursively()
        }

        companion object {
            fun create(): Fixture {
                val root = Files.createTempDirectory("vega-classpath").toFile()
                val platformRoot = File(root, "platform").apply { mkdirs() }
                val offlineRoot = File(root, "offline").apply { mkdirs() }
                File(platformRoot, "android.jar").writeText("android")
                val bundled = File(offlineRoot, "local.jar").apply { writeText("local") }

                File(root, "settings.gradle.kts").writeText("include(\":app\", \":core\")")
                val app = File(root, "app").apply { mkdirs() }
                File(app, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                    }
                    android {
                        namespace = "example.app"
                        compileSdk = 35
                    }
                    dependencies {
                        implementation(project(":core"))
                        implementation("com.example:local:1.0")
                    }
                    """.trimIndent()
                )
                File(app, "src/main/java/example").mkdirs()
                val appLibs = File(app, "libs").apply { mkdirs() }
                val appLibA = File(appLibs, "a.jar").apply { writeText("a") }
                File(appLibs, "z.jar").writeText("z")

                val core = File(root, "core").apply { mkdirs() }
                File(core, "build.gradle").writeText("plugins { id 'java-library' }")
                File(core, "src/main/java/example").mkdirs()
                val coreOutput = File(core, "build/classes/java/main").apply { mkdirs() }
                File(coreOutput, "Example.class").writeText("class")

                val model = EmbeddedProjectModelParser().parse(root)
                check(model.supported) {
                    "fixture model rejected: " + model.unsupportedReasons
                }
                val store = object : LocalArtifactStore {
                    override val approvedRoots = listOf(offlineRoot)
                    override fun resolve(notation: String): LocalArtifact? =
                        if (notation == "com.example:local:1.0") {
                            LocalArtifact(bundled, ClasspathEntryKind.BUNDLED_ARTIFACT, notation)
                        } else {
                            null
                        }
                }
                return Fixture(root, model, platformRoot, offlineRoot, appLibA, store, coreOutput)
            }
        }
    }
}
