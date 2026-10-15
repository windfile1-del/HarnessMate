package dev.wind.agent

import dev.wind.agent.build.MavenCompileGraphPlanner
import dev.wind.agent.build.MavenCoordinate
import dev.wind.agent.build.VerifiedMavenPom
import dev.wind.agent.build.VerifiedMavenPomSource
import dev.wind.agent.build.CasMavenPomSource
import dev.wind.agent.build.MavenPomPin
import dev.wind.agent.artifact.ArtifactProvenance
import dev.wind.agent.artifact.VerifiedArtifactStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

object MavenCompileGraphPlannerTests {
    @JvmStatic fun main(args: Array<String>) {
        nearestVersionAndDeclarationOrder()
        optionalScopeAndExclusion()
        sharedModuleReexpandsForDifferentExclusions()
        localPropertiesAndProjectAliases()
        invalidPropertiesFailClosed()
        parentAndBomManagement()
        nestedBomAndIsolation()
        modelInputsFailClosed()
        unsupportedPomFailsClosed()
        externalEntityFailsClosed()
        digestMismatchFailsClosed()
        casPinRevalidatesBytes(args.firstOrNull())
        println("MavenCompileGraphPlannerTests: OK")
    }

    private fun nearestVersionAndDeclarationOrder() {
        val poms = mapOf(
            "g:a:1" to pom("g:a:1", dependency("g:b:1") + dependency("g:c:1")),
            "g:b:1" to pom("g:b:1", dependency("g:d:1")),
            "g:c:1" to pom("g:c:1", dependency("g:d:2")),
            "g:d:1" to pom("g:d:1"),
            "g:d:2" to pom("g:d:2")
        )
        val graph = planner(poms).plan(listOf(MavenCoordinate.parse("g:a:1")))
        check(graph.nodes.map { it.coordinate.notation } ==
            listOf("g:a:1", "g:b:1", "g:c:1", "g:d:1"))
        check(graph.nodes.map { it.depth } == listOf(0, 1, 1, 2))
    }

    private fun optionalScopeAndExclusion() {
        val poms = mapOf(
            "g:a:1" to pom("g:a:1", dependency("g:b:1", exclusions = "g:d") +
                dependency("g:x:1", scope = "runtime") +
                dependency("g:y:1", optional = true)),
            "g:b:1" to pom("g:b:1", dependency("g:d:1"))
        )
        val graph = planner(poms).plan(listOf(MavenCoordinate.parse("g:a:1")))
        check(graph.nodes.map { it.coordinate.notation } == listOf("g:a:1", "g:b:1"))
    }

    private fun sharedModuleReexpandsForDifferentExclusions() {
        val poms = mapOf(
            "g:a:1" to pom("g:a:1", dependency("g:x:1") + dependency("g:y:1")),
            "g:x:1" to pom("g:x:1", dependency("g:b:1", exclusions = "g:d")),
            "g:y:1" to pom("g:y:1", dependency("g:b:1")),
            "g:b:1" to pom("g:b:1", dependency("g:d:1")),
            "g:d:1" to pom("g:d:1")
        )
        val graph = planner(poms).plan(listOf(MavenCoordinate.parse("g:a:1")))
        check(graph.nodes.map { it.coordinate.notation } ==
            listOf("g:a:1", "g:x:1", "g:y:1", "g:b:1", "g:d:1"))
    }

    private fun unsupportedPomFailsClosed() {
        val parent = pom("g:a:1").replace("<dependencies>",
            "<parent><groupId>g</groupId><artifactId>p</artifactId><version>1</version></parent><dependencies>")
        expectFailure("MAVEN_POM_MISSING:g:p:1") {
            planner(mapOf("g:a:1" to parent)).plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
        expectFailure("MAVEN_COORDINATE_UNPINNED") { MavenCoordinate.parse("g:a:1-SNAPSHOT") }
        expectFailure("MAVEN_COORDINATE_UNPINNED") { MavenCoordinate.parse("g:a:1-snapshot") }
        expectFailure("MAVEN_COORDINATE_UNPINNED") { MavenCoordinate.parse("g:..:1") }
        expectFailure("MAVEN_DEPENDENCY_TYPE_UNSUPPORTED") {
            planner(mapOf("g:a:1" to pom("g:a:1", dependency("g:b:1")
                .replace("</dependency>", "<classifier>tests</classifier></dependency>"))))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
    }

    private fun localPropertiesAndProjectAliases() {
        val properties = "<properties><dep.version>\${project.version}</dep.version>" +
            "<nested>\${dep.version}</nested><dep.group>\${pom.groupId}</dep.group>" +
            "<dep.artifact>b</dep.artifact><exclude>d</exclude><kind>jar</kind>" +
            "<selected.scope>compile</selected.scope><is.optional>false</is.optional></properties>"
        val edge = dependency("g:b:1", exclusions = "g:d")
            .replace("<groupId>g</groupId>", "<groupId>\${dep.group}</groupId>")
            .replace("<artifactId>b</artifactId>", "<artifactId>\${dep.artifact}</artifactId>")
            .replace("<version>1</version>", "<version>\${nested}</version>")
            .replace("<scope>compile</scope>", "<scope>\${selected.scope}</scope>")
            .replace("<optional>false</optional>", "<optional>\${is.optional}</optional>")
            .replace("<artifactId>d</artifactId>", "<artifactId>\${exclude}</artifactId>")
            .replace("</dependency>", "<type>\${kind}</type></dependency>")
        val root = pom("g:a:1", edge).replace("<dependencies>", "$properties<dependencies>")
        val poms = mapOf("g:a:1" to root, "g:b:1" to pom("g:b:1", dependency("g:d:1")))
        val graph = planner(poms).plan(listOf(MavenCoordinate.parse("g:a:1")))
        check(graph.nodes.map { it.coordinate.notation } == listOf("g:a:1", "g:b:1"))
        check(graph == planner(poms).plan(listOf(MavenCoordinate.parse("g:a:1"))))
        // Properties belong to their own POM; they cannot leak down the dependency graph.
        expectFailure("MAVEN_PROPERTY_UNRESOLVED:nested") {
            planner(poms + ("g:b:1" to pom("g:b:1", dependency("g:c:1")
                .replace("<version>1</version>", "<version>\${nested}</version>"))))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
    }

    private fun invalidPropertiesFailClosed() {
        fun fails(properties: String, message: String) {
            expectFailure(message) {
                val xml = pom("g:a:1").replace("<dependencies>",
                    "<properties>$properties</properties><dependencies>")
                planner(mapOf("g:a:1" to xml)).plan(listOf(MavenCoordinate.parse("g:a:1")))
            }
        }
        fails("<a>\${b}</a><b>\${a}</b>", "MAVEN_PROPERTY_CYCLE")
        fails("<a>\${a}</a>", "MAVEN_PROPERTY_CYCLE")
        fails("<a>\${missing}</a>", "MAVEN_PROPERTY_UNRESOLVED:missing")
        fails("<a>\${env.PATH}</a>", "MAVEN_PROPERTY_UNRESOLVED:env.PATH")
        fails("<a>\${java.version}</a>", "MAVEN_PROPERTY_UNRESOLVED:java.version")
        fails("<project.version>2</project.version>", "MAVEN_PROPERTY_NAME_UNSUPPORTED")
        fails("<a>1</a><a>2</a>", "MAVEN_PROPERTY_DUPLICATE")
        fails("<a><b>1</b></a>", "MAVEN_PROPERTY_VALUE_INVALID")
        fails("<a>\${unterminated</a>", "MAVEN_PROPERTY_EXPRESSION_INVALID")
        fails("<a>\${bad name}</a>", "MAVEN_PROPERTY_EXPRESSION_INVALID")
        fails("<a>${"x".repeat(4097)}</a>", "MAVEN_PROPERTY_EXPANSION_TOO_LARGE")
        fails("<a>${"x".repeat(2049)}</a><b>\${a}\${a}</b>",
            "MAVEN_PROPERTY_EXPANSION_TOO_LARGE")
        fails((1..33).joinToString("") { index ->
            "<p$index>" + (if (index == 33) "1" else "\${p${index + 1}}") + "</p$index>"
        }, "MAVEN_PROPERTY_DEPTH_TOO_LARGE")
        fails((1..257).joinToString("") { "<p$it>1</p$it>" }, "MAVEN_PROPERTY_COUNT_TOO_LARGE")
        expectFailure("MAVEN_COORDINATE_UNPINNED") {
            val xml = pom("g:a:1", dependency("g:b:1")
                .replace("<version>1</version>", "<version>\${v}</version>"))
                .replace("<dependencies>", "<properties><v>[1,2)</v></properties><dependencies>")
            planner(mapOf("g:a:1" to xml)).plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
    }

    private fun parentAndBomManagement() {
        fun noVersion(notation: String) = dependency(notation)
            .replace(Regex("<version>[^<]+</version>"), "")
        val parent = pom("g:parent:2", noVersion("g:b:1"))
            .replace("<dependencies>", "<packaging>pom</packaging>" +
                "<properties><v>1</v></properties><dependencyManagement><dependencies>" +
                dependency("g:b:1", exclusions = "g:x")
                    .replace("<version>1</version>", "<version>\${v}</version>") +
                "</dependencies></dependencyManagement><dependencies>")
        fun bom(name: String, dependencies: String) = pom("g:$name:1")
            .replace("<dependencies>", "<packaging>pom</packaging>" +
                "<dependencyManagement><dependencies>$dependencies</dependencies>" +
                "</dependencyManagement><dependencies>")
        val import1 = dependency("g:bom:1", scope = "import")
            .replace("<optional>false</optional>", "<type>pom</type>")
        val import2 = import1.replace("<artifactId>bom</artifactId>", "<artifactId>other</artifactId>")
        val root = pom("g:a:2", noVersion("g:b:2") + noVersion("g:c:3") + noVersion("g:d:1"))
            .replace("<groupId>g</groupId><artifactId>a</artifactId><version>2</version>",
                "<parent><groupId>g</groupId><artifactId>parent</artifactId><version>2</version>" +
                    "<relativePath>../../untrusted/pom.xml</relativePath></parent><artifactId>a</artifactId>")
            .replace("<dependencies>", "<properties><v>2</v></properties><dependencyManagement>" +
                "<dependencies>$import1$import2${dependency("g:c:3")}</dependencies>" +
                "</dependencyManagement><dependencies>")
        val poms = mapOf("g:a:2" to root, "g:parent:2" to parent,
            "g:bom:1" to bom("bom", dependency("g:c:1") + dependency("g:d:1")),
            "g:other:1" to bom("other", dependency("g:d:2")),
            "g:b:2" to pom("g:b:2", dependency("g:x:1")),
            "g:c:3" to pom("g:c:3"), "g:d:1" to pom("g:d:1"))
        val graph = planner(poms).plan(listOf(MavenCoordinate.parse("g:a:2")))
        check(graph.nodes.map { it.coordinate.notation } == listOf("g:a:2", "g:b:2", "g:c:3", "g:d:1"))
        check(graph.supportingPoms.map { it.coordinate.notation } == listOf("g:parent:2", "g:bom:1", "g:other:1"))
        check(graph.supportingPoms.all { it.sha256.length == 64 })
        check(graph == planner(poms).plan(listOf(MavenCoordinate.parse("g:a:2"))))
    }

    private fun modelInputsFailClosed() {
        fun parentXml(name: String, parent: String): String = pom("g:$name:1")
            .replace("<dependencies>", "<packaging>pom</packaging><parent><groupId>g</groupId>" +
                "<artifactId>$parent</artifactId><version>1</version></parent><dependencies>")
        expectFailure("MAVEN_PARENT_CYCLE") {
            planner(mapOf("g:p:1" to parentXml("p", "q"), "g:q:1" to parentXml("q", "p")))
                .plan(listOf(MavenCoordinate.parse("g:p:1")))
        }
        expectFailure("MAVEN_MODEL_DEPTH_TOO_LARGE") {
            planner((1..33).associate { index ->
                "g:p$index:1" to parentXml("p$index", "p${index + 1}")
            }).plan(listOf(MavenCoordinate.parse("g:p1:1")))
        }
        expectFailure("MAVEN_MODEL_DEPENDENCIES_TOO_LARGE") {
            planner(mapOf("g:a:1" to pom("g:a:1", (1..1025).joinToString("") {
                dependency("g:d$it:1")
            }))).plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
        val imported = dependency("g:bom:1", scope = "import")
            .replace("<optional>false</optional>", "<type>pom</type>")
        fun withImport(xml: String) = xml.replace("<dependencies>",
            "<dependencyManagement><dependencies>$imported</dependencies></dependencyManagement><dependencies>")
        expectFailure("MAVEN_MODEL_CYCLE") {
            planner(mapOf("g:a:1" to withImport(pom("g:a:1")),
                "g:bom:1" to withImport(pom("g:bom:1").replace("<dependencies>",
                    "<packaging>pom</packaging><dependencies>"))))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
        expectFailure("MAVEN_BOM_PACKAGING_INVALID") {
            planner(mapOf("g:a:1" to withImport(pom("g:a:1")), "g:bom:1" to pom("g:bom:1")))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
        expectFailure("MAVEN_DEPENDENCY_VERSION_MISSING") {
            planner(mapOf("g:a:1" to pom("g:a:1", dependency("g:b:1")
                .replace("<version>1</version>", ""))))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
        expectFailure("MAVEN_POM_profiles_UNSUPPORTED") {
            planner(mapOf("g:a:1" to pom("g:a:1").replace("</project>", "<profiles/></project>")))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
    }

    private fun nestedBomAndIsolation() {
        fun imported(name: String) = dependency("g:$name:1", scope = "import")
            .replace("<optional>false</optional>", "<type>pom</type><optional>false</optional>")
        fun managed(xml: String, declarations: String) = xml.replace("<dependencies>",
            "<dependencyManagement><dependencies>$declarations</dependencies></dependencyManagement><dependencies>")
        val root = managed(pom("g:a:1", dependency("g:d:1").replace("<version>1</version>", "")
            .replace("<scope>compile</scope>", "")), imported("bom"))
        val bom = managed(pom("g:bom:1").replace("<dependencies>",
            "<packaging>pom</packaging><properties><privateVersion>1</privateVersion></properties><dependencies>"),
            imported("nested"))
        val nested = managed(pom("g:nested:1").replace("<dependencies>", "<packaging>pom</packaging><dependencies>"),
            dependency("g:d:2", scope = "provided"))
        val poms = mapOf("g:a:1" to root, "g:bom:1" to bom, "g:nested:1" to nested)
        val graph = planner(poms).plan(listOf(MavenCoordinate.parse("g:a:1")))
        check(graph.nodes.map { it.coordinate.notation } == listOf("g:a:1"))
        check(graph.supportingPoms.map { it.coordinate.notation } == listOf("g:bom:1", "g:nested:1"))
        expectFailure("MAVEN_PROPERTY_UNRESOLVED:privateVersion") {
            planner(poms + ("g:a:1" to root.replace("<artifactId>d</artifactId>",
                "<artifactId>\${privateVersion}</artifactId>")))
                .plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
    }

    private fun externalEntityFailsClosed() {
        val xml = "<!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
            pom("g:a:1").replace("<artifactId>a</artifactId>", "<artifactId>&xxe;</artifactId>")
        expectFailure(null) {
            planner(mapOf("g:a:1" to xml)).plan(listOf(MavenCoordinate.parse("g:a:1")))
        }
    }

    private fun digestMismatchFailsClosed() {
        expectFailure("MAVEN_POM_DIGEST_MISMATCH") {
            VerifiedMavenPom(MavenCoordinate.parse("g:a:1"), "a".repeat(64), pom("g:a:1"))
        }
    }

    private fun casPinRevalidatesBytes(scratch: String?) {
        val base = scratch?.let { java.io.File(it).toPath() }
            ?: java.io.File(System.getProperty("java.io.tmpdir")).toPath()
        val root = Files.createTempDirectory(base, "p7-maven-pom-")
        try {
            val xml = pom("g:a:1")
            val bytes = xml.toByteArray(StandardCharsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val store = VerifiedArtifactStore(root.resolve("cas").toFile())
            val artifact = store.importQuarantined(bytes.inputStream(), digest, bytes.size.toLong(),
                ArtifactProvenance("https://repo.example/g/a/1/a-1.pom"))
            val coordinate = MavenCoordinate.parse("g:a:1")
            val pin = MavenPomPin(coordinate, "test-repo", artifact.uri, digest, bytes.size)
            val graph = MavenCompileGraphPlanner(CasMavenPomSource(store, listOf(pin)))
                .plan(listOf(coordinate))
            check(graph.nodes.single().pomSha256 == digest)
            expectFailure("MAVEN_POM_PIN_INVALID") {
                MavenPomPin(coordinate, "test-repo", artifact.uri, "b".repeat(64), bytes.size)
            }
            expectFailure("MAVEN_POM_SIZE_MISMATCH") {
                MavenCompileGraphPlanner(CasMavenPomSource(store,
                    listOf(pin.copy(byteSize = bytes.size - 1)))).plan(listOf(coordinate))
            }
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun planner(poms: Map<String, String>): MavenCompileGraphPlanner =
        MavenCompileGraphPlanner(VerifiedMavenPomSource { coordinate ->
            poms[coordinate.notation]?.let { xml ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(xml.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                VerifiedMavenPom(coordinate, digest, xml)
            }
        })

    private fun pom(coordinate: String, dependencies: String = ""): String {
        val (group, artifact, version) = coordinate.split(':')
        return "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId>" +
            "<artifactId>$artifact</artifactId><version>$version</version>" +
            "<dependencies>$dependencies</dependencies></project>"
    }

    private fun dependency(coordinate: String, scope: String = "compile",
        optional: Boolean = false, exclusions: String? = null): String {
        val (group, artifact, version) = coordinate.split(':')
        val exclusion = exclusions?.split(':')?.let { (g, a) ->
            "<exclusions><exclusion><groupId>$g</groupId><artifactId>$a</artifactId>" +
                "</exclusion></exclusions>"
        }.orEmpty()
        return "<dependency><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
            "<version>$version</version><scope>$scope</scope><optional>$optional</optional>" +
            "$exclusion</dependency>"
    }

    private fun expectFailure(message: String?, action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        check(failure != null) { "expected failure: $message" }
        if (message != null) check(failure.message?.contains(message) == true) { failure.toString() }
    }
}
