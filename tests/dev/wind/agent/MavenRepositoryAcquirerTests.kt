package dev.wind.agent

import dev.wind.agent.artifact.ArtifactHttpResponse
import dev.wind.agent.artifact.ArtifactHttpTransport
import dev.wind.agent.artifact.MavenRepository
import dev.wind.agent.artifact.MavenRepositoryAcquirer
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.build.CasMavenPomSource
import dev.wind.agent.build.MavenCompileGraphPlanner
import dev.wind.agent.build.MavenCoordinate
import dev.wind.agent.build.MavenDependencyLock
import dev.wind.agent.build.MavenDependencyLockFile
import dev.wind.agent.build.MavenProjectDependencyInstaller
import dev.wind.agent.build.MavenLockedArtifactStore
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object MavenRepositoryAcquirerTests {
    @JvmStatic fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-maven-acquire-")
        try {
            downloadsPinnedPairAndReopensPom(root)
            explicitPinsDoNotNeedSidecars(root)
            localPropertyPomDownloadsAndPlans(root)
            parentBomGraphDownloadsLocksAndInstalls(root)
            missingModelChecksumStopsBeforeJar(root)
            missingChecksumFailsBeforeArtifactDownload(root)
            explicitLegacySha1ModeChecksBytes(root)
            wrongChecksumDoesNotPublishJar(root)
            wrongPomIdentityStopsBeforeJar(root)
            crossRepositoryRedirectIsDenied(root)
            rejectsUnsafeRepositoryAndCoordinate()
            if (args.contains("--live")) liveCentralSmoke(root)
            println("MavenRepositoryAcquirerTests: OK")
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private val coordinate = MavenCoordinate.parse("org.example:tiny:1.0")
    private val repository = MavenRepository("test", "https://repo.example/maven2/")
    private val pom = ("<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>" +
        "<artifactId>tiny</artifactId><version>1.0</version></project>").toByteArray(StandardCharsets.UTF_8)
    private val jar = byteArrayOf(0x50, 0x4b, 3, 4, 0, 0)

    private fun downloadsPinnedPairAndReopensPom(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("ok-cas").toFile())
        val responses = fixtures()
        val seen = mutableListOf<String>()
        val acquirer = MavenRepositoryAcquirer(repository, store, root.resolve("ok-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                val bytes = responses[uri.toString()] ?: error("unexpected URL: $uri")
                response(200, bytes)
            }) { true }
        val acquired = acquirer.acquire(coordinate, CancellationToken())
        check(acquired.pomPin.sha256 == sha256(pom))
        check(acquired.jar.sha256 == sha256(jar))
        check(seen.size == 4 && seen[0].endsWith(".pom.sha256") &&
            seen[1].endsWith(".jar.sha256") && seen[2].endsWith(".pom") &&
            seen[3].endsWith(".jar"))
        val graph = MavenCompileGraphPlanner(CasMavenPomSource(store, listOf(acquired.pomPin)))
            .plan(listOf(coordinate))
        check(graph.nodes.single().coordinate == coordinate)
    }

    private fun explicitPinsDoNotNeedSidecars(root: Path) {
        val pomUrl = repository.urlFor(coordinate, "pom")
        val jarUrl = repository.urlFor(coordinate, "jar")
        val contents = mapOf(pomUrl to pom, jarUrl to jar)
        val seen = mutableListOf<String>()
        val store = VerifiedArtifactStore(root.resolve("pins-cas").toFile())
        val acquirer = MavenRepositoryAcquirer(repository, store,
            root.resolve("pins-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                response(200, contents[uri.toString()] ?: error("unexpected URL: $uri"))
            }) { true }
        val result = acquirer.acquire(coordinate, CancellationToken(),
            expectedPomSha256 = sha256(pom), expectedJarSha256 = sha256(jar))
        check(result.pomChecksumKind == dev.wind.agent.artifact.MavenChecksumKind.EXPECTED_SHA256)
        check(result.jarChecksumKind == dev.wind.agent.artifact.MavenChecksumKind.EXPECTED_SHA256)
        check(seen == listOf(pomUrl, jarUrl))
    }

    private fun missingChecksumFailsBeforeArtifactDownload(root: Path) {
        val seen = mutableListOf<String>()
        val acquirer = MavenRepositoryAcquirer(repository,
            VerifiedArtifactStore(root.resolve("missing-cas").toFile()),
            root.resolve("missing-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                response(404, byteArrayOf())
            }) { true }
        expectFailure("MAVEN_SHA256_REQUIRED") {
            acquirer.acquire(coordinate, CancellationToken())
        }
        check(seen.size == 1 && seen.single().endsWith(".pom.sha256"))
    }

    private fun localPropertyPomDownloadsAndPlans(root: Path) {
        val xml = String(pom, StandardCharsets.UTF_8).replace("</project>",
            "<properties><v>\${project.version}</v></properties><dependencies>" +
                "<dependency><groupId>org.example</groupId><artifactId>unused</artifactId>" +
                "<version>\${v}</version><optional>true</optional></dependency>" +
                "</dependencies></project>").toByteArray(StandardCharsets.UTF_8)
        val contents = mapOf(repository.urlFor(coordinate, "pom") to xml,
            repository.urlFor(coordinate, "jar") to jar)
        val store = VerifiedArtifactStore(root.resolve("properties-cas").toFile())
        val acquired = MavenRepositoryAcquirer(repository, store,
            root.resolve("properties-q").toFile(), ArtifactHttpTransport { uri, _ ->
                response(200, contents[uri.toString()] ?: error("unexpected URL: $uri"))
            }) { true }.acquire(coordinate, CancellationToken(),
                expectedPomSha256 = sha256(xml), expectedJarSha256 = sha256(jar))
        val graph = MavenCompileGraphPlanner(CasMavenPomSource(store, listOf(acquired.pomPin)))
            .plan(listOf(coordinate))
        check(graph.nodes.single().coordinate == coordinate)
        check(graph.nodes.single().pomSha256 == sha256(xml))
        check(acquired.jar.sha256 == sha256(jar))
    }

    private fun parentBomGraphDownloadsLocksAndInstalls(root: Path) {
        val a = MavenCoordinate.parse("g:a:1")
        val parent = MavenCoordinate.parse("g:parent:1")
        val bom = MavenCoordinate.parse("g:bom:1")
        val b = MavenCoordinate.parse("g:b:2")
        val d = MavenCoordinate.parse("g:d:3")
        fun dependency(artifact: String, version: String = "") =
            "<dependency><groupId>g</groupId><artifactId>$artifact</artifactId>" +
                (if (version.isEmpty()) "" else "<version>$version</version>") + "</dependency>"
        fun xml(coordinate: MavenCoordinate, body: String) =
            "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>" +
                "<artifactId>${coordinate.artifact}</artifactId><version>${coordinate.version}</version>" +
                "$body</project>"
        val poms = mapOf(a to xml(a, "<parent><groupId>g</groupId><artifactId>parent</artifactId>" +
            "<version>1</version></parent><dependencyManagement><dependencies>" +
            "<dependency><groupId>g</groupId><artifactId>bom</artifactId><version>1</version>" +
            "<type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>" +
            "<dependencies>${dependency("b")}${dependency("d")}</dependencies>"),
            parent to xml(parent, "<packaging>pom</packaging><properties><v>2</v></properties>" +
                "<dependencyManagement><dependencies>${dependency("b", "\${v}")}" +
                "</dependencies></dependencyManagement>"),
            bom to xml(bom, "<packaging>pom</packaging><dependencyManagement><dependencies>" +
                "${dependency("b", "9")}${dependency("d", "3")}</dependencies></dependencyManagement>"),
            b to xml(b, ""), d to xml(d, ""))
        val responses = mutableMapOf<String, ByteArray>()
        poms.forEach { (coordinate, xml) ->
            val url = repository.urlFor(coordinate, "pom")
            val bytes = xml.toByteArray(StandardCharsets.UTF_8)
            responses[url] = bytes
            responses["$url.sha256"] = sha256(bytes).toByteArray(StandardCharsets.US_ASCII)
        }
        val jarBytes = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("META-INF/fixture.txt"))
                zip.write("test artifact".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        listOf(a, b, d).forEach { coordinate ->
            val url = repository.urlFor(coordinate, "jar")
            responses[url] = jarBytes
            responses["$url.sha256"] = sha256(jarBytes).toByteArray(StandardCharsets.US_ASCII)
        }
        val seen = mutableListOf<String>()
        val store = VerifiedArtifactStore(root.resolve("graph-cas").toFile())
        val acquisition = MavenRepositoryAcquirer(repository, store, root.resolve("graph-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                response(200, responses[uri.toString()] ?: error("unexpected graph URL: $uri"))
            }) { true }.acquireGraph(listOf(a), CancellationToken())
        check(acquisition.graph.nodes.map { it.coordinate } == listOf(a, b, d))
        check(acquisition.supportingPoms.map { it.pin.coordinate } == listOf(parent, bom))
        check(seen.none { it == repository.urlFor(parent, "jar") || it == repository.urlFor(bom, "jar") })
        check(seen.count { it.endsWith(".jar") } == 3)
        expectFailure("MAVEN_LOCK_MODEL_INPUT_MISSING") {
            MavenDependencyLock.fromGraph(listOf(a), acquisition.graph, acquisition.modules)
        }
        expectFailure("MAVEN_LOCK_MODEL_INPUT_MISMATCH") {
            MavenDependencyLock.fromGraph(listOf(a), acquisition.graph.copy(supportingPoms =
                acquisition.graph.supportingPoms.map { it.copy(sha256 = "f".repeat(64)) }),
                acquisition.modules, acquisition.supportingPoms)
        }
        val lock = MavenDependencyLock.fromGraph(listOf(a), acquisition.graph, acquisition.modules,
            acquisition.supportingPoms)
        check(String(lock.encode(), StandardCharsets.UTF_8).startsWith("vega-maven-lock-v2\n"))
        check(MavenDependencyLock.decode(lock.encode()) == lock)
        val offlineGraph = MavenCompileGraphPlanner(CasMavenPomSource(store,
            acquisition.modules.map { it.pomPin } + acquisition.supportingPoms.map { it.pin }))
            .plan(listOf(a))
        check(offlineGraph == acquisition.graph)
        val project = Files.createDirectory(root.resolve("graph-project"))
        val installer = MavenProjectDependencyInstaller(project.toFile(), store)
        installer.install(lock, CancellationToken())
        val offline = MavenLockedArtifactStore(project.toFile()).resolveGraph(listOf(a.notation))
            ?: error("locked offline graph missing")
        check(offline.map { it.coordinate } == listOf(a.notation, b.notation, d.notation))
        check(offline.all { sha256(it.file.readBytes()) == sha256(jarBytes) })
        val damaged = lock.copy(modelPoms = lock.modelPoms.map {
            if (it.pin.coordinate == parent) it.copy(pin = it.pin.copy(sha256 = "f".repeat(64),
                artifactUri = "artifact://sha256/${"f".repeat(64)}")) else it
        })
        expectFailure("ARTIFACT_NOT_FOUND") { installer.install(damaged, CancellationToken()) }
        check(MavenDependencyLockFile(project.toFile()).read() == lock)
        // Read-only project cache must be made writable for this disposable host fixture's cleanup.
        offline.forEach { it.file.setWritable(true) }
    }

    private fun missingModelChecksumStopsBeforeJar(root: Path) {
        val xml = String(pom, StandardCharsets.UTF_8).replace("</project>",
            "<parent><groupId>org.example</groupId><artifactId>parent</artifactId>" +
                "<version>1.0</version></parent></project>").toByteArray(StandardCharsets.UTF_8)
        val seen = mutableListOf<String>()
        val acquirer = MavenRepositoryAcquirer(repository,
            VerifiedArtifactStore(root.resolve("missing-parent-cas").toFile()),
            root.resolve("missing-parent-q").toFile(), ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                if (uri.toString() == repository.urlFor(coordinate, "pom")) response(200, xml)
                else response(404, byteArrayOf())
            }) { true }
        expectFailure("MAVEN_SHA256_REQUIRED") {
            acquirer.acquireGraph(listOf(coordinate), CancellationToken(),
                expectedPomSha256 = mapOf(coordinate to sha256(xml)))
        }
        check(seen.none { it.endsWith(".jar") || it.endsWith(".jar.sha256") })
    }

    private fun explicitLegacySha1ModeChecksBytes(root: Path) {
        val pomUrl = repository.urlFor(coordinate, "pom")
        val jarUrl = repository.urlFor(coordinate, "jar")
        val contents = mapOf(
            "$pomUrl.sha1" to sha1(pom).toByteArray(StandardCharsets.US_ASCII),
            "$jarUrl.sha1" to sha1(jar).toByteArray(StandardCharsets.US_ASCII),
            pomUrl to pom,
            jarUrl to jar
        )
        val store = VerifiedArtifactStore(root.resolve("legacy-cas").toFile())
        val transport = ArtifactHttpTransport { uri, _ ->
            val bytes = contents[uri.toString()]
            if (bytes == null) response(404, byteArrayOf()) else response(200, bytes)
        }
        val acquirer = MavenRepositoryAcquirer(repository, store,
            root.resolve("legacy-q").toFile(), transport, true) { true }
        val result = acquirer.acquire(coordinate, CancellationToken())
        check(result.pomChecksumKind == dev.wind.agent.artifact.MavenChecksumKind.REPOSITORY_SHA1)
        check(result.jarChecksumKind == dev.wind.agent.artifact.MavenChecksumKind.REPOSITORY_SHA1)
        check(result.jar.sha256 == sha256(jar))

        val wrong = contents.toMutableMap()
        wrong["$jarUrl.sha1"] = "0".repeat(40).toByteArray(StandardCharsets.US_ASCII)
        val reject = MavenRepositoryAcquirer(repository,
            VerifiedArtifactStore(root.resolve("legacy-wrong-cas").toFile()),
            root.resolve("legacy-wrong-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                val bytes = wrong[uri.toString()]
                if (bytes == null) response(404, byteArrayOf()) else response(200, bytes)
            }, true) { true }
        expectFailure("MAVEN_SHA1_MISMATCH") {
            reject.acquire(coordinate, CancellationToken())
        }
    }

    private fun wrongChecksumDoesNotPublishJar(root: Path) {
        val responses = fixtures().toMutableMap()
        responses[repository.urlFor(coordinate, "pom") + ".sha256"] =
            "0".repeat(64).toByteArray(StandardCharsets.US_ASCII)
        val store = VerifiedArtifactStore(root.resolve("wrong-cas").toFile())
        val acquirer = MavenRepositoryAcquirer(repository, store, root.resolve("wrong-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                response(200, responses[uri.toString()] ?: error("missing fixture: $uri"))
            }) { true }
        expectFailure("DOWNLOAD_SHA256_MISMATCH") {
            acquirer.acquire(coordinate, CancellationToken())
        }
        expectFailure("ARTIFACT_NOT_FOUND") {
            store.openVerified("artifact://sha256/${sha256(jar)}")
        }
    }

    private fun wrongPomIdentityStopsBeforeJar(root: Path) {
        val wrongPom = String(pom, StandardCharsets.UTF_8)
            .replace("<artifactId>tiny</artifactId>", "<artifactId>other</artifactId>")
            .toByteArray(StandardCharsets.UTF_8)
        val responses = fixtures().toMutableMap()
        responses[repository.urlFor(coordinate, "pom")] = wrongPom
        responses[repository.urlFor(coordinate, "pom") + ".sha256"] =
            sha256(wrongPom).toByteArray(StandardCharsets.US_ASCII)
        val seen = mutableListOf<String>()
        val acquirer = MavenRepositoryAcquirer(repository,
            VerifiedArtifactStore(root.resolve("identity-cas").toFile()),
            root.resolve("identity-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                response(200, responses[uri.toString()] ?: error("missing fixture: $uri"))
            }) { true }
        expectFailure("MAVEN_POM_IDENTITY_MISMATCH") {
            acquirer.acquire(coordinate, CancellationToken())
        }
        check(seen.none { it.endsWith(".jar") })
    }

    private fun crossRepositoryRedirectIsDenied(root: Path) {
        val seen = mutableListOf<String>()
        val acquirer = MavenRepositoryAcquirer(repository,
            VerifiedArtifactStore(root.resolve("redirect-cas").toFile()),
            root.resolve("redirect-q").toFile(),
            ArtifactHttpTransport { uri, _ ->
                seen += uri.toString()
                response(302, byteArrayOf(), "https://outside.example/checksum")
            }) { true }
        expectFailure("FETCH_HOP_NOT_AUTHORIZED") {
            acquirer.acquire(coordinate, CancellationToken())
        }
        check(seen.size == 1 && seen.single().startsWith(repository.baseUrl))
    }

    private fun rejectsUnsafeRepositoryAndCoordinate() {
        expectFailure("MAVEN_REPOSITORY_URL_INVALID") {
            MavenRepository("bad", "https://repo.example/maven2/%2e%2e/")
        }
        expectFailure("MAVEN_COORDINATE_UNPINNED") {
            MavenCoordinate.parse("org.example:tiny:1.0-SNAPSHOT")
        }
    }

    private fun liveCentralSmoke(root: Path) {
        val central = MavenRepository("maven-central", "https://repo.maven.apache.org/maven2/")
        val store = VerifiedArtifactStore(root.resolve("central-cas").toFile())
        val acquirer = MavenRepositoryAcquirer(central, store,
            root.resolve("central-q").toFile(), true) { uri ->
            uri.host == "repo.maven.apache.org"
        }
        val result = acquirer.acquire(MavenCoordinate.parse("org.jetbrains:annotations:24.0.1"),
            CancellationToken(), maxJarBytes = 1024 * 1024)
        val graph = MavenCompileGraphPlanner(CasMavenPomSource(store, listOf(result.pomPin)))
            .plan(listOf(result.coordinate))
        check(graph.nodes.size == 1)
        println("Maven Central smoke: pom=${result.pomPin.sha256} " +
            "jar=${result.jar.sha256} checksum=${result.jarChecksumKind}")
    }

    private fun fixtures(): Map<String, ByteArray> {
        val pomUrl = repository.urlFor(coordinate, "pom")
        val jarUrl = repository.urlFor(coordinate, "jar")
        return mapOf(
            "$pomUrl.sha256" to sha256(pom).toByteArray(StandardCharsets.US_ASCII),
            "$jarUrl.sha256" to sha256(jar).toByteArray(StandardCharsets.US_ASCII),
            pomUrl to pom,
            jarUrl to jar
        )
    }

    private fun response(status: Int, bytes: ByteArray, location: String? = null) =
        ArtifactHttpResponse(status, bytes.size.toLong(), location, ByteArrayInputStream(bytes),
            if (status == 200) "text/plain" else null) { }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun expectFailure(message: String, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure?.message?.contains(message) == true) { "expected $message, got $failure" }
    }
}
