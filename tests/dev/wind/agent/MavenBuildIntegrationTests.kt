package dev.wind.agent

import dev.wind.agent.artifact.*
import dev.wind.agent.build.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Actual host ECJ + locked private JARs. No external compiler process/network. */
object MavenBuildIntegrationTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "maven-build-").toFile().canonicalFile
        try {
            val fixture = Fixture(root)
            offlineCompileUpdateRollbackAndDex(fixture)
            stalePlansAndCorruptCacheFailClosed(fixture)
            unsupportedAndCancellation(fixture)
            println("MavenBuildIntegrationTests: OK (real ECJ/offline lock update/rollback/D8)")
        } finally {
            Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
                it.toFile().setWritable(true)
                Files.deleteIfExists(it)
            } }
        }
    }

    private class Fixture(val root: File) {
        val project = File(root, "project").apply { mkdir() }
        val privateRoot = File(root, "private").apply { mkdir() }
        val cache = File(privateRoot, "p7-maven-cache")
        val cas = VerifiedArtifactStore(File(privateRoot, "cas"))
        val coordinate = MavenCoordinate.parse("example:library:1")
        val source = File(project, "src/main/java/app/User.java").apply {
            parentFile.mkdirs()
            writeText("package app; public class User { public static int answer() { return dep.Library.answer(); } }")
        }
        init {
            File(project, "build.gradle.kts").writeText("plugins { java }\ndependencies { implementation(\"example:library:1\") }")
        }
        val model get() = EmbeddedProjectModelParser().parse(project).also { check(it.supported) { it.unsupportedReasons.toString() } }
        val support = EmbeddedMavenDependencySupport(cache)
        val backend = EmbeddedBuildBackend.withMavenDependencies(support)
        fun context(name: String) = BuildContext(model, File(root, name))
        fun build(name: String): BuildResult = context(name).let { backend.execute(it, backend.plan(it), CancellationToken()) }
        fun library(answer: Int): MavenDependencyLock {
            val input = File(root, "library-$answer/Library.java").apply {
                parentFile.mkdirs()
                writeText("package dep; public class Library { public static int answer() { return $answer; } }")
            }
            val output = File(root, "library-$answer/classes")
            val compiled = EcjJavaCompiler().compile(EcjCompileRequest(listOf(input), output), CancellationToken())
            check(compiled.success) { compiled.diagnostics.toString() }
            val bytes = jar("dep/Library.class", File(output, "dep/Library.class").readBytes())
            val artifact = cas.importQuarantined(bytes.inputStream(), MavenDependencyInstallPlan.digest(bytes),
                bytes.size.toLong(), ArtifactProvenance("https://repo.example/"))
            val xml = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>" +
                "<artifactId>library</artifactId><version>1</version></project>"
            val pomBytes = xml.toByteArray()
            val pom = cas.importQuarantined(pomBytes.inputStream(), MavenDependencyInstallPlan.digest(pomBytes),
                pomBytes.size.toLong(), ArtifactProvenance("https://repo.example/"))
            val pin = MavenPomPin(coordinate, "fixture", pom.uri, pom.sha256, pom.byteSize.toInt())
            val downloaded = MavenDownloadedModule(coordinate, "fixture", pin, artifact,
                "https://repo.example/example/library/1/library-1.pom", "https://repo.example/example/library/1/library-1.jar",
                MavenChecksumKind.EXPECTED_SHA256, MavenChecksumKind.EXPECTED_SHA256)
            val graph = MavenCompileGraphPlanner(CasMavenPomSource(cas, listOf(pin))).plan(listOf(coordinate))
            return MavenDependencyLock.fromGraph(listOf(coordinate), graph, listOf(downloaded))
        }
        fun install(lock: MavenDependencyLock) = MavenProjectDependencyInstaller(project, cas, cache).install(lock, CancellationToken())
    }

    private fun offlineCompileUpdateRollbackAndDex(f: Fixture) {
        val absentPlan = f.context("before-lock").let { f.backend.plan(it) }
        val old = f.library(42)
        f.install(old)
        val firstContext = f.context("old")
        val firstPlan = f.backend.plan(firstContext)
        check(firstPlan.execution.projectFingerprint != absentPlan.execution.projectFingerprint)
        val first = f.backend.execute(firstContext, firstPlan, CancellationToken())
        assertAnswer(first, f.cache, old, 42)
        check(!File(f.project, ".vega/maven-cache").exists())
        val revised = f.library(43)
        f.install(revised)
        val second = f.build("new")
        assertAnswer(second, f.cache, revised, 43)
        check(f.backend.plan(firstContext).fingerprint != firstPlan.fingerprint)
        f.install(old)
        val reverted = f.build("rollback")
        assertAnswer(reverted, f.cache, old, 42)
        check(f.cache.listFiles { file -> file.extension == "jar" }?.size == 2)
        val classRoot = reverted.producedArtifacts.single { it.file.name == "User.class" }.file.parentFile.parentFile
        val android = File("assets/embedded/android-35/android.jar")
        check(android.isFile) { "Pinned Android 35 platform missing: $android" }
        val dex = D8DexCompiler(ReflectionD8Provider()).compile(D8CompileRequest(
            classDirectories = listOf(classRoot), libraryFiles = listOf(android),
            outputDirectory = File(f.root, "dependency-dex"), minApi = 23,
            programArchives = listOf(File(f.cache, "${old.modules.single().jarSha256}.jar"))
        ), CancellationToken())
        check(dex.outcome == BuildOutcome.SUCCESS) { dex.diagnostics.toString() }
        val text = String(dex.artifacts.single { it.file.extension == "dex" }.file.readBytes(), Charsets.ISO_8859_1)
        check("Ldep/Library;" in text && "Lapp/User;" in text)
    }

    private fun stalePlansAndCorruptCacheFailClosed(f: Fixture) {
        val old = f.library(42)
        f.install(old)
        val context = f.context("stale")
        val plan = f.backend.plan(context)
        f.install(f.library(43))
        val stale = f.backend.execute(context, plan, CancellationToken())
        check(stale.outcome == BuildOutcome.FAILED && stale.diagnostics.any { it.code == "BUILD_PLAN_CONTEXT_MISMATCH" })
        check(stale.producedArtifacts.isEmpty())
        f.install(old)
        val cacheJar = File(f.cache, "${old.modules.single().jarSha256}.jar")
        cacheJar.setWritable(true)
        cacheJar.writeBytes(cacheJar.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        val corrupt = f.build("corrupt")
        check(corrupt.outcome == BuildOutcome.FAILED && corrupt.diagnostics.any { it.code == "MAVEN_CACHE_DIGEST_MISMATCH" })
        check(corrupt.taskResults.filter { it.id.startsWith("compile-java:") }.all { it.outcome == BuildTaskOutcome.SKIPPED })
        cacheJar.delete() // fixture-owned corrupted object; installer can republish verified CAS bytes.
        f.install(old)
    }

    private fun unsupportedAndCancellation(f: Fixture) {
        val context = f.context("no-support")
        val default = EmbeddedBuildBackend()
        check(default.execute(context, default.plan(context), CancellationToken()).outcome == BuildOutcome.UNSUPPORTED)
        val unsupportedModel = f.model.copy(modules = f.model.modules.map { module -> module.copy(
            dependencies = module.dependencies.map { it.copy(configuration = "compileOnly") }) })
        val unsupported = BuildContext(unsupportedModel, File(f.root, "compile-only"))
        val result = f.backend.execute(unsupported, f.backend.plan(unsupported), CancellationToken())
        check(result.diagnostics.any { it.code == "MAVEN_BUILD_CONFIGURATION_UNSUPPORTED" })
        val cancelled = f.backend.execute(context, f.backend.plan(context), CancellationToken().also { it.cancel() })
        check(cancelled.outcome == BuildOutcome.CANCELLED)
        val executor = File("src/dev/wind/agent/build/EmbeddedBuildExecutor.kt").readText()
        check("classpath = dependencyJars(module, state, token)" in executor)
        check("listOf(stdlib) + dependencyJars" in executor && "listOfNotNull(targetStdlib) + dependencyArchives" in executor)
        check("dependencyResourceArchives = dependencyJars(module, state, token)" in executor)
    }

    private fun assertAnswer(result: BuildResult, cache: File, lock: MavenDependencyLock, expected: Int) {
        check(result.success) { "${result.outcome}: ${result.diagnostics}" }
        val cls = result.producedArtifacts.single { it.file.name == "User.class" }.file
        URLClassLoader(arrayOf(cls.parentFile.parentFile.toURI().toURL(),
            File(cache, "${lock.modules.single().jarSha256}.jar").toURI().toURL()), null).use { loader ->
            check(loader.loadClass("app.User").getMethod("answer").invoke(null) == expected)
        }
    }

    private fun jar(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }.toByteArray()
}
