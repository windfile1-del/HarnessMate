package dev.wind.agent.build

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

data class MavenCoordinate(val group: String, val artifact: String, val version: String) {
    init {
        require(listOf(group, artifact, version).all { part ->
            part.matches(PART) && part.split('.').none { it.isEmpty() || it == ".." }
        } && !version.endsWith("-SNAPSHOT", true) && !version.contains("LATEST", true) &&
            !version.contains("RELEASE", true)) { "MAVEN_COORDINATE_UNPINNED" }
    }
    val notation: String get() = "$group:$artifact:$version"
    val module: String get() = "$group:$artifact"
    companion object {
        private val PART = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        fun parse(notation: String): MavenCoordinate {
            val parts = notation.split(':')
            require(parts.size == 3) { "MAVEN_COORDINATE_INVALID" }
            return MavenCoordinate(parts[0], parts[1], parts[2])
        }
    }
}

data class VerifiedMavenPom(val coordinate: MavenCoordinate, val sha256: String, val xml: String) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "MAVEN_POM_DIGEST_INVALID" }
        require(xml.length <= 4 * 1024 * 1024) { "MAVEN_POM_TOO_LARGE" }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(xml.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == sha256) { "MAVEN_POM_DIGEST_MISMATCH" }
    }
}

fun interface VerifiedMavenPomSource {
    fun get(coordinate: MavenCoordinate): VerifiedMavenPom?
}

data class MavenCompileNode(val coordinate: MavenCoordinate, val pomSha256: String, val depth: Int)
data class MavenCompileGraph(
    val nodes: List<MavenCompileNode>,
    /** Parent/BOM model inputs which are not themselves compile-classpath modules. */
    val supportingPoms: List<MavenModelPom> = emptyList()
)

/** Nearest-definition BFS with exact, verified parent/BOM models. */
class MavenCompileGraphPlanner(private val source: VerifiedMavenPomSource) {
    /** Validate a JAR POM, returning every parent/BOM input needed to interpret it. */
    fun validatePom(pom: VerifiedMavenPom): List<MavenModelPom> {
        val resolver = MavenEffectivePomResolver(VerifiedMavenPomSource {
            if (it == pom.coordinate) pom else source.get(it)
        })
        require(resolver.model(pom.coordinate).packaging == "jar") { "MAVEN_PACKAGING_UNSUPPORTED" }
        return resolver.inputs.values.filter { it.coordinate != pom.coordinate }
    }

    fun plan(roots: List<MavenCoordinate>): MavenCompileGraph {
        require(roots.isNotEmpty() && roots.size <= 128) { "MAVEN_ROOT_COUNT_INVALID" }
        data class Candidate(val coordinate: MavenCoordinate, val depth: Int, val exclusions: Set<String>)
        val resolver = MavenEffectivePomResolver(source)
        val queue = ArrayDeque<Candidate>()
        roots.forEach { queue.add(Candidate(it, 0, emptySet())) }
        val selected = linkedMapOf<String, MavenCompileNode>()
        val expanded = mutableSetOf<Pair<MavenCoordinate, Set<String>>>()
        var visited = 0
        while (queue.isNotEmpty()) {
            require(++visited <= 4096) { "MAVEN_GRAPH_TOO_LARGE" }
            val candidate = queue.removeFirst()
            val coordinate = candidate.coordinate
            if (coordinate.module in candidate.exclusions) continue
            val chosen = selected[coordinate.module]
            if (chosen != null && chosen.coordinate != coordinate) continue
            if (!expanded.add(coordinate to candidate.exclusions)) continue
            val model = resolver.model(coordinate)
            require(model.packaging == "jar") { "MAVEN_PACKAGING_UNSUPPORTED" }
            if (chosen == null) {
                selected[coordinate.module] = MavenCompileNode(coordinate,
                    resolver.inputs.getValue(coordinate).sha256, candidate.depth)
                require(selected.size <= 1024) { "MAVEN_GRAPH_TOO_LARGE" }
            }
            model.dependencies.filter { it.scope == "compile" && !it.optional }.forEach { edge ->
                queue.add(Candidate(edge.coordinate, candidate.depth + 1,
                    candidate.exclusions + edge.exclusions))
            }
        }
        val nodes = selected.values.toList()
        val moduleCoordinates = nodes.map { it.coordinate }.toSet()
        return MavenCompileGraph(nodes, resolver.inputs.values.filter { it.coordinate !in moduleCoordinates })
    }
}
