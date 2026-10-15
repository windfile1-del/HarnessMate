package dev.wind.agent

import dev.wind.agent.artifact.ArtifactProvenance
import dev.wind.agent.artifact.MavenChecksumKind
import dev.wind.agent.artifact.MavenDownloadedModule
import dev.wind.agent.artifact.VerifiedArtifact
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.build.MavenCompileGraph
import dev.wind.agent.build.MavenCompileNode
import dev.wind.agent.build.MavenCoordinate
import dev.wind.agent.build.MavenDependencyLock
import dev.wind.agent.build.MavenDependencyLockFile
import dev.wind.agent.build.MavenLockedArtifactStore
import dev.wind.agent.build.MavenPomPin
import dev.wind.agent.build.MavenProjectDependencyInstaller
import dev.wind.agent.build.ClasspathResolver
import dev.wind.agent.build.ClasspathEntryKind
import dev.wind.agent.build.EmbeddedDependency
import dev.wind.agent.build.EmbeddedModule
import dev.wind.agent.build.EmbeddedModuleKind
import dev.wind.agent.build.EmbeddedProjectModel
import dev.wind.agent.build.EmbeddedSourceSet
import dev.wind.agent.build.ProjectModelDependencyIndex
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MavenProjectDependencyInstallerTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-maven-install-")
        try {
            installsOfflineGraphAndDetectsTamper(root)
            preservesLockedGraphClasspathOrder(root)
            separatesProjectLockFromPrivateCache(root)
            failedUpgradePreservesOldLock(root)
            println("MavenProjectDependencyInstallerTests: OK")
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    path.toFile().setWritable(true)
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun installsOfflineGraphAndDetectsTamper(root: Path) {
        val project = Files.createDirectory(root.resolve("project-a"))
        val cas = VerifiedArtifactStore(root.resolve("cas-a").toFile())
        val coordinate = MavenCoordinate.parse("org.example:tiny:1")
        val jarBytes = jar("tiny")
        val jar = import(cas, jarBytes)
        val lock = lock(coordinate, jar)
        val installer = MavenProjectDependencyInstaller(project.toFile(), cas)
        val lockPath = installer.install(lock, CancellationToken())
        check(lockPath.isFile)
        check(MavenDependencyLockFile(project.toFile()).read() == lock)
        val offline = MavenLockedArtifactStore(project.toFile())
        val artifacts = offline.resolveGraph(listOf(coordinate.notation)) ?: error("offline graph missing")
        check(artifacts.single().coordinate == coordinate.notation)
        val model = EmbeddedProjectModel(project.toFile(), listOf(
            EmbeddedModule("app", project.toFile(), EmbeddedModuleKind.JVM,
                sourceSet = EmbeddedSourceSet(emptyList(), emptyList(), emptyList(), null,
                    emptyList(), emptyList(), emptyList()),
                dependencies = listOf(EmbeddedDependency("implementation", coordinate.notation)),
                gradlePath = ":app")
        ), null, null)
        val resolver = ClasspathResolver(null, offline, ProjectModelDependencyIndex(model))
        val classpath = resolver.resolve(model, ":app")
        check(classpath.success && classpath.entries.single().kind == ClasspathEntryKind.OFFLINE_CACHE)
        check(offline.resolveGraph(listOf("org.example:other:1")) == null)
        val cached = artifacts.single().file
        cached.setWritable(true)
        cached.writeBytes(jar("tampered"))
        expectFailure("MAVEN_CACHE") { offline.resolveGraph(listOf(coordinate.notation)) }
        val failedClasspath = resolver.resolve(model, ":app")
        check(!failedClasspath.success && failedClasspath.entries.isEmpty())
        check(MavenDependencyLockFile(project.toFile()).read() == lock)
    }

    private fun failedUpgradePreservesOldLock(root: Path) {
        val project = Files.createDirectory(root.resolve("project-b"))
        val cas = VerifiedArtifactStore(root.resolve("cas-b").toFile())
        val coordinate = MavenCoordinate.parse("org.example:tiny:1")
        val old = lock(coordinate, import(cas, jar("old")))
        val installer = MavenProjectDependencyInstaller(project.toFile(), cas)
        installer.install(old, CancellationToken())
        val unavailable = VerifiedArtifact("artifact://sha256/${"f".repeat(64)}", "f".repeat(64), 123)
        val next = lock(MavenCoordinate.parse("org.example:tiny:2"), unavailable)
        expectFailure("ARTIFACT_NOT_FOUND") { installer.install(next, CancellationToken()) }
        check(MavenDependencyLockFile(project.toFile()).read() == old)
    }

    private fun preservesLockedGraphClasspathOrder(root: Path) {
        val project = Files.createDirectory(root.resolve("project-order"))
        val cas = VerifiedArtifactStore(root.resolve("cas-order").toFile())
        val rootCoordinate = MavenCoordinate.parse("org.example:root:1")
        val childCoordinate = MavenCoordinate.parse("org.example:child:1")
        val firstJar = import(cas, jar("root"))
        val secondJar = import(cas, jar("child"))
        val rootJar = if (firstJar.sha256 > secondJar.sha256) firstJar else secondJar
        val childJar = if (rootJar == firstJar) secondJar else firstJar
        val rootDownload = download(rootCoordinate, rootJar)
        val childDownload = download(childCoordinate, childJar)
        val lock = MavenDependencyLock.fromGraph(listOf(rootCoordinate),
            MavenCompileGraph(listOf(MavenCompileNode(rootCoordinate, "a".repeat(64), 0),
                MavenCompileNode(childCoordinate, "a".repeat(64), 1))),
            listOf(rootDownload, childDownload))
        MavenProjectDependencyInstaller(project.toFile(), cas).install(lock, CancellationToken())
        val model = EmbeddedProjectModel(project.toFile(), listOf(
            EmbeddedModule("app", project.toFile(), EmbeddedModuleKind.JVM,
                sourceSet = EmbeddedSourceSet(emptyList(), emptyList(), emptyList(), null,
                    emptyList(), emptyList(), emptyList()),
                dependencies = listOf(EmbeddedDependency("implementation", rootCoordinate.notation)),
                gradlePath = ":app")
        ), null, null)
        val result = ClasspathResolver(null, MavenLockedArtifactStore(project.toFile()),
            ProjectModelDependencyIndex(model)).resolve(model, ":app")
        check(result.success)
        check(result.entries.map { it.coordinate } ==
            listOf(rootCoordinate.notation, childCoordinate.notation))
    }

    private fun separatesProjectLockFromPrivateCache(root: Path) {
        val project = Files.createDirectory(root.resolve("project-separate"))
        val privateParent = Files.createDirectory(root.resolve("app-private"))
        val privateCache = privateParent.resolve("maven-cache")
        val cas = VerifiedArtifactStore(root.resolve("cas-separate").toFile())
        val coordinate = MavenCoordinate.parse("org.example:separate:1")
        val lock = lock(coordinate, import(cas, jar("separate")))
        MavenProjectDependencyInstaller(project.toFile(), cas, privateCache.toFile())
            .install(lock, CancellationToken())
        check(Files.isRegularFile(project.resolve(".vega/maven-dependencies.lock")))
        check(!Files.exists(project.resolve(".vega/maven-cache")))
        val artifact = MavenLockedArtifactStore(project.toFile(), privateCache.toFile())
            .resolveGraph(listOf(coordinate.notation))?.single()
            ?: error("separate cache not resolved")
        check(artifact.file.toPath().startsWith(privateCache))
    }

    private fun lock(coordinate: MavenCoordinate, jar: VerifiedArtifact): MavenDependencyLock {
        val pomHash = "a".repeat(64)
        val downloaded = download(coordinate, jar)
        return MavenDependencyLock.fromGraph(listOf(coordinate),
            MavenCompileGraph(listOf(MavenCompileNode(coordinate, pomHash, 0))),
            listOf(downloaded))
    }

    private fun download(coordinate: MavenCoordinate, jar: VerifiedArtifact): MavenDownloadedModule {
        val pomHash = "a".repeat(64)
        val pomPin = MavenPomPin(coordinate, "test-repo", "artifact://sha256/$pomHash", pomHash, 128)
        val base = "https://repo.example/maven2/${coordinate.group.replace('.', '/')}/" +
            "${coordinate.artifact}/${coordinate.version}/${coordinate.artifact}-${coordinate.version}"
        return MavenDownloadedModule(coordinate, "test-repo", pomPin, jar,
            "$base.pom", "$base.jar", MavenChecksumKind.EXPECTED_SHA256,
            MavenChecksumKind.EXPECTED_SHA256)
    }

    private fun import(cas: VerifiedArtifactStore, bytes: ByteArray): VerifiedArtifact {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return cas.importQuarantined(bytes.inputStream(), digest, bytes.size.toLong(),
            ArtifactProvenance("https://repo.example/example.jar"))
    }

    private fun jar(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("example.txt"))
            zip.write(value.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun expectFailure(message: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure?.message?.contains(message) == true) { "expected $message, got $failure" }
    }
}
