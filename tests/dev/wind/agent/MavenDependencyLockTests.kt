package dev.wind.agent

import dev.wind.agent.artifact.MavenChecksumKind
import dev.wind.agent.artifact.MavenDownloadedModule
import dev.wind.agent.artifact.VerifiedArtifact
import dev.wind.agent.build.MavenCompileGraph
import dev.wind.agent.build.MavenCompileNode
import dev.wind.agent.build.MavenCoordinate
import dev.wind.agent.build.MavenDependencyLock
import dev.wind.agent.build.MavenDependencyLockFile
import dev.wind.agent.build.MavenPomPin
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object MavenDependencyLockTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        val project = Files.createTempDirectory(base, "p7-maven-lock-")
        try {
            canonicalRoundTripAndAtomicPublication(project)
            incompleteGraphFailsClosed()
            malformedLockFailsClosed()
            println("MavenDependencyLockTests: OK")
        } finally {
            Files.walk(project).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private val a = MavenCoordinate.parse("org.example:a:1")
    private val b = MavenCoordinate.parse("org.example:b:2")
    private val aPom = "a".repeat(64)
    private val aJar = "b".repeat(64)
    private val bPom = "c".repeat(64)
    private val bJar = "d".repeat(64)

    private fun canonicalRoundTripAndAtomicPublication(project: Path) {
        val lock = lock()
        val encoded = lock.encode()
        val decoded = MavenDependencyLock.decode(encoded)
        check(decoded == lock)
        check(decoded.fingerprint == lock.fingerprint)
        val file = MavenDependencyLockFile(project.toFile())
        check(file.read() == null)
        check(file.publish(lock).isFile)
        check(file.read() == lock)
        val revised = lock.copy(roots = listOf(a, b),
            modules = lock.modules.map { module ->
                if (module.coordinate == b) module.copy(depth = 0) else module
            })
        file.publish(revised)
        check(file.read() == revised)
        check(revised.fingerprint != lock.fingerprint)
    }

    private fun incompleteGraphFailsClosed() {
        val graph = MavenCompileGraph(listOf(MavenCompileNode(a, aPom, 0),
            MavenCompileNode(b, bPom, 1)))
        expectFailure("MAVEN_LOCK_DOWNLOADS_INCOMPLETE") {
            MavenDependencyLock.fromGraph(listOf(a), graph, listOf(download(a, aPom, aJar)))
        }
        expectFailure("MAVEN_LOCK_IDENTITY_MISMATCH") {
            MavenDependencyLock.fromGraph(listOf(a), graph,
                listOf(download(a, aPom, aJar), download(b, "e".repeat(64), bJar)))
        }
    }

    private fun malformedLockFailsClosed() {
        val valid = lock().encode()
        expectFailure("MAVEN_LOCK_NONCANONICAL") {
            MavenDependencyLock.decode(String(valid, StandardCharsets.UTF_8)
                .replace("module\torg.example:a:1\t0\t", "module\torg.example:a:1\t00\t")
                .toByteArray(StandardCharsets.UTF_8))
        }
        expectFailure("MAVEN_LOCK_FORMAT_INVALID") {
            MavenDependencyLock.decode("vega-maven-lock-v999\n".toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun lock(): MavenDependencyLock {
        val graph = MavenCompileGraph(listOf(MavenCompileNode(a, aPom, 0),
            MavenCompileNode(b, bPom, 1)))
        return MavenDependencyLock.fromGraph(listOf(a), graph,
            listOf(download(a, aPom, aJar), download(b, bPom, bJar)))
    }

    private fun download(coordinate: MavenCoordinate, pom: String, jar: String): MavenDownloadedModule {
        val base = "https://repo.example/maven2/${coordinate.group.replace('.', '/')}/" +
            "${coordinate.artifact}/${coordinate.version}/${coordinate.artifact}-${coordinate.version}"
        return MavenDownloadedModule(coordinate, "test-repo",
            MavenPomPin(coordinate, "test-repo", "artifact://sha256/$pom", pom, 123),
            VerifiedArtifact("artifact://sha256/$jar", jar, 456),
            "$base.pom", "$base.jar", MavenChecksumKind.REPOSITORY_SHA256,
            MavenChecksumKind.REPOSITORY_SHA1)
    }

    private fun expectFailure(message: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure?.message?.contains(message) == true) { "expected $message, got $failure" }
    }
}
