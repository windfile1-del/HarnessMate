package dev.wind.agent.intelligence.dependency

import dev.wind.agent.intelligence.*
import dev.wind.agent.intelligence.index.CodeIndexBackend
import org.json.JSONObject

class DependencyGraphBuilder(
    private val indexBackend: CodeIndexBackend
) {
    fun build(
        revision: SourceRevision,
        fileReader: (String) -> String? = { null },
        manifestFiles: List<String> = emptyList()
    ): DependencyGraph {
        val baseGraph = indexBackend.dependencyGraph(revision)
        val nodes = baseGraph.nodes.toMutableList()
        val edges = baseGraph.edges.toMutableList()
        val seenNodeIds = nodes.map { it.id }.toMutableSet()

        for (file in manifestFiles) {
            val lower = file.lowercase()
            val text = fileReader(file) ?: continue
            when {
                lower.endsWith("package.json") -> parsePackageJson(file, text, nodes, edges, seenNodeIds, revision.projectId)
                lower.endsWith("build.gradle") || lower.endsWith("build.gradle.kts") -> parseGradle(file, text, nodes, edges, seenNodeIds, revision.projectId)
                lower.endsWith("pyproject.toml") || lower.endsWith("requirements.txt") ||
                    lower.endsWith("poetry.lock") || lower.endsWith("pipfile") ->
                    parsePythonDeps(file, text, nodes, edges, seenNodeIds, revision.projectId)
                lower.endsWith("pom.xml") -> parsePomXml(file, text, nodes, edges, seenNodeIds, revision.projectId)
            }
        }

        val truncated = baseGraph.truncated || nodes.size >= CodeIntelLimits.MAX_GRAPH_NODES || edges.size >= CodeIntelLimits.MAX_GRAPH_EDGES
        return DependencyGraph(
            nodes = nodes.take(CodeIntelLimits.MAX_GRAPH_NODES),
            edges = edges.take(CodeIntelLimits.MAX_GRAPH_EDGES),
            truncated = truncated
        )
    }

    private fun parsePackageJson(
        file: String,
        text: String,
        nodes: MutableList<DependencyNode>,
        edges: MutableList<DependencyEdge>,
        seen: MutableSet<String>,
        projectId: String
    ) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val modId = "mod:" + projectId
        val depKeys = listOf(
            "dependencies", "devDependencies", "peerDependencies", "optionalDependencies"
        )
        for (key in depKeys) {
            val obj = json.optJSONObject(key) ?: continue
            val it = obj.keys()
            while (it.hasNext()) {
                val pkgName = it.next()
                val depId = "ext:npm:" + pkgName
                if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(depId)) {
                    nodes.add(DependencyNode(id = depId, kind = DependencyNodeKind.EXTERNAL_DEPENDENCY, label = pkgName))
                }
                if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                    edges.add(DependencyEdge(sourceId = modId, targetId = depId, kind = DependencyEdgeKind.DEPENDS_ON, label = key))
                }
            }
        }

        // npm/yarn/pnpm workspaces are project-local modules, not external
        // packages. Preserve the declaration without resolving the filesystem
        // or running a package manager.
        val workspaceValues = mutableListOf<String>()
        val workspaces = json.opt("workspaces")
        when (workspaces) {
            is org.json.JSONArray -> for (i in 0 until workspaces.length()) workspaceValues.add(workspaces.optString(i, ""))
            is JSONObject -> workspaces.optJSONArray("packages")?.let { values ->
                for (i in 0 until values.length()) workspaceValues.add(values.optString(i, ""))
            }
        }
        workspaceValues.filter { it.isNotBlank() }.forEach { workspace ->
            val moduleId = "mod:workspace:" + workspace
            if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(moduleId)) {
                nodes.add(DependencyNode(moduleId, DependencyNodeKind.MODULE, workspace))
            }
            if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                edges.add(DependencyEdge(modId, moduleId, DependencyEdgeKind.DECLARES, "workspace"))
            }
        }
    }

    private fun parseGradle(
        file: String,
        text: String,
        nodes: MutableList<DependencyNode>,
        edges: MutableList<DependencyEdge>,
        seen: MutableSet<String>,
        projectId: String
    ) {
        val modId = "mod:" + projectId
        val depRegex = Regex("""\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|kapt|annotationProcessor)\s*\(?\s*['"]([^'"]+)['"]\s*\)?""")
        depRegex.findAll(text).forEach { match ->
            val depCoord = match.groupValues[1]
            if (!depCoord.startsWith(":") && depCoord.contains(":")) {
                val depId = "ext:maven:" + depCoord
                if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(depId)) {
                    nodes.add(DependencyNode(id = depId, kind = DependencyNodeKind.EXTERNAL_DEPENDENCY, label = depCoord))
                }
                if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                    edges.add(DependencyEdge(sourceId = modId, targetId = depId, kind = DependencyEdgeKind.DEPENDS_ON, label = "gradle"))
                }
            }
        }

        val projectDepRegex = Regex("""\bproject\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        projectDepRegex.findAll(text).forEach { match ->
            val subName = match.groupValues[1].removePrefix(":")
            val subId = "mod:" + subName
            if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(subId)) {
                nodes.add(DependencyNode(id = subId, kind = DependencyNodeKind.MODULE, label = subName))
            }
            if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                edges.add(DependencyEdge(sourceId = modId, targetId = subId, kind = DependencyEdgeKind.DEPENDS_ON, label = "project"))
            }
        }
        val projectPathRegex = Regex("""\bproject\s*\(\s*path\s*:\s*['"]([^'"]+)['"]""")
        projectPathRegex.findAll(text).forEach { match ->
            val subName = match.groupValues[1].removePrefix(":")
            val subId = "mod:" + subName
            if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(subId)) {
                nodes.add(DependencyNode(subId, DependencyNodeKind.MODULE, subName))
            }
            if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                edges.add(DependencyEdge(modId, subId, DependencyEdgeKind.DEPENDS_ON, "project"))
            }
        }
    }

    private fun parsePythonDeps(
        file: String,
        text: String,
        nodes: MutableList<DependencyNode>,
        edges: MutableList<DependencyEdge>,
        seen: MutableSet<String>,
        projectId: String
    ) {
        var section = ""
        val requirement = Regex("""[\"']([A-Za-z][A-Za-z0-9_.-]*)(?:\[[^\"']+\])?(?:[<>=~!].*)?[\"']""")
        val key = Regex("""^([A-Za-z][A-Za-z0-9_.-]*)\s*=""")
        for (line in text.lines()) {
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) continue
            if (clean.startsWith("[") && clean.endsWith("]")) {
                section = clean.lowercase()
                continue
            }

            val lowerFile = file.lowercase()
            when {
                lowerFile.endsWith("requirements.txt") -> {
                    if (clean.startsWith("-") || clean.startsWith("[") || clean.contains("://")) continue
                    val pkgName = clean.split(Regex("[<>=~!; ]+"))[0]
                    addPythonDependency(pkgName, nodes, edges, seen, projectId)
                }
                lowerFile.endsWith("pyproject.toml") -> {
                    // PEP 621 uses a quoted array on the project.dependencies
                    // line; Poetry uses package-name keys in its dependency
                    // tables. Do not scrape unrelated quoted metadata.
                    if (section == "[project]" && clean.startsWith("dependencies")) {
                        requirement.findAll(clean).forEach { match ->
                            addPythonDependency(match.groupValues[1], nodes, edges, seen, projectId)
                        }
                    } else if (section.startsWith("[tool.poetry.dependencies") ||
                        section.startsWith("[tool.poetry.group.") && section.endsWith(".dependencies]")) {
                        key.find(clean)?.groupValues?.get(1)?.takeIf { !it.equals("python", true) }
                            ?.let { addPythonDependency(it, nodes, edges, seen, projectId) }
                    }
                }
                lowerFile.endsWith("poetry.lock") -> {
                    if (clean.startsWith("name")) {
                        Regex("""[\"']([A-Za-z][A-Za-z0-9_.-]*)[\"']""").find(clean)?.groupValues?.get(1)
                            ?.let { addPythonDependency(it, nodes, edges, seen, projectId) }
                    }
                }
                lowerFile.endsWith("pipfile") -> {
                    if (section == "[packages]" || section == "[dev-packages]") {
                        key.find(clean)?.groupValues?.get(1)
                            ?.let { addPythonDependency(it, nodes, edges, seen, projectId) }
                    }
                }
            }
        }
    }

    private fun addPythonDependency(
        pkgName: String,
        nodes: MutableList<DependencyNode>,
        edges: MutableList<DependencyEdge>,
        seen: MutableSet<String>,
        projectId: String
    ) {
        if (pkgName.isBlank() || !pkgName.matches(Regex("""^[A-Za-z0-9_.-]+$"""))) return
        val depId = "ext:pypi:" + pkgName
        if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(depId)) {
            nodes.add(DependencyNode(id = depId, kind = DependencyNodeKind.EXTERNAL_DEPENDENCY, label = pkgName))
        }
        if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
            edges.add(DependencyEdge(sourceId = "mod:" + projectId, targetId = depId, kind = DependencyEdgeKind.DEPENDS_ON, label = "python"))
        }
    }

    private fun parsePomXml(
        file: String,
        text: String,
        nodes: MutableList<DependencyNode>,
        edges: MutableList<DependencyEdge>,
        seen: MutableSet<String>,
        projectId: String
    ) {
        val modId = "mod:" + projectId
        val depBlockRegex = Regex("""<dependency>(.*?)</dependency>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val groupRegex = Regex("""<groupId>([^<]+)</groupId>""")
        val artifactRegex = Regex("""<artifactId>([^<]+)</artifactId>""")

        depBlockRegex.findAll(text).forEach { match ->
            val block = match.groupValues[1]
            val group = groupRegex.find(block)?.groupValues?.get(1)?.trim()
            val artifact = artifactRegex.find(block)?.groupValues?.get(1)?.trim()
            if (!group.isNullOrEmpty() && !artifact.isNullOrEmpty()) {
                val coord = group + ":" + artifact
                val depId = "ext:maven:" + coord
                if (nodes.size < CodeIntelLimits.MAX_GRAPH_NODES && seen.add(depId)) {
                    nodes.add(DependencyNode(id = depId, kind = DependencyNodeKind.EXTERNAL_DEPENDENCY, label = coord))
                }
                if (edges.size < CodeIntelLimits.MAX_GRAPH_EDGES) {
                    edges.add(DependencyEdge(sourceId = modId, targetId = depId, kind = DependencyEdgeKind.DEPENDS_ON, label = "maven"))
                }
            }
        }
    }
}

