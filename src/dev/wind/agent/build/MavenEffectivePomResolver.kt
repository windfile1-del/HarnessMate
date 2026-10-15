package dev.wind.agent.build

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

data class MavenModelPom(val coordinate: MavenCoordinate, val sha256: String)

/** Exact repository models only; no local relativePath, settings, scripts or profiles. */
internal class MavenEffectivePomResolver(private val source: VerifiedMavenPomSource) {
    data class Dependency(
        val coordinate: MavenCoordinate, val scope: String, val optional: Boolean,
        val exclusions: Set<String>
    )
    data class Model(val packaging: String, val dependencies: List<Dependency>,
        val management: Map<String, Dependency>)
    private data class RawDependency(
        val group: String, val artifact: String, val version: String, val scope: String,
        val optional: String, val type: String, val classifier: String, val systemPath: String,
        val exclusions: List<Pair<String, String>>
    ) {
        val key: String get() = "$group:$artifact:${type.ifEmpty { "jar" }}:$classifier"
        fun inherit(parent: RawDependency): RawDependency = copy(
            version = version.ifEmpty { parent.version }, scope = scope.ifEmpty { parent.scope },
            optional = optional.ifEmpty { parent.optional },
            exclusions = (exclusions + parent.exclusions).distinct())
    }
    private data class RawModel(
        val coordinate: MavenCoordinate, val packaging: String, val properties: Map<String, String>,
        val dependencies: List<RawDependency>, val management: List<RawDependency>
    )
    private val rawModels = mutableMapOf<MavenCoordinate, RawModel>()
    private val models = mutableMapOf<MavenCoordinate, Model>()
    private val loading = mutableSetOf<MavenCoordinate>()
    private val resolving = mutableSetOf<MavenCoordinate>()
    val inputs = linkedMapOf<MavenCoordinate, MavenModelPom>()

    fun model(coordinate: MavenCoordinate): Model {
        models[coordinate]?.let { return it }
        require(resolving.add(coordinate)) { "MAVEN_MODEL_CYCLE:${coordinate.notation}" }
        try {
            require(resolving.size <= 32) { "MAVEN_MODEL_DEPTH_TOO_LARGE" }
            val raw = raw(coordinate)
            val interpolation = Interpolation(raw.properties, coordinate)
            fun resolve(dependency: RawDependency): RawDependency = dependency.copy(
                group = interpolation.expand(dependency.group),
                artifact = interpolation.expand(dependency.artifact),
                version = interpolation.expand(dependency.version),
                scope = interpolation.expand(dependency.scope),
                optional = interpolation.expand(dependency.optional),
                type = interpolation.expand(dependency.type),
                classifier = interpolation.expand(dependency.classifier),
                systemPath = interpolation.expand(dependency.systemPath),
                exclusions = dependency.exclusions.map {
                    interpolation.expand(it.first) to interpolation.expand(it.second)
                })
            val declarations = raw.management.map(::resolve)
            val managed = linkedMapOf<String, Dependency>()
            // Direct (including inherited) management wins over imports regardless of order.
            declarations.filter { it.scope != "import" }.forEach { dependency ->
                val entry = dependency(dependency, null)
                require(managed.put(entry.coordinate.module, entry) == null) {
                    "MAVEN_MANAGEMENT_DUPLICATE:${entry.coordinate.module}"
                }
            }
            declarations.filter { it.scope == "import" }.forEach { imported ->
                require(imported.type == "pom" && imported.classifier.isEmpty() &&
                    imported.systemPath.isEmpty() && imported.optional in listOf("", "false") &&
                    imported.exclusions.isEmpty()) { "MAVEN_BOM_IMPORT_INVALID" }
                val bom = model(MavenCoordinate(imported.group, imported.artifact, imported.version))
                require(bom.packaging == "pom") { "MAVEN_BOM_PACKAGING_INVALID" }
                bom.management.forEach { (key, value) ->
                    if (!managed.containsKey(key)) managed[key] = value
                }
            }
            val dependencies = raw.dependencies.map { declaration ->
                val resolved = resolve(declaration)
                dependency(resolved, managed["${resolved.group}:${resolved.artifact}"])
            }
            require(dependencies.map { it.coordinate.module }.distinct().size == dependencies.size) {
                "MAVEN_DEPENDENCY_DUPLICATE"
            }
            val packaging = interpolation.expand(raw.packaging).ifEmpty { "jar" }
            require(packaging in listOf("jar", "pom")) { "MAVEN_PACKAGING_UNSUPPORTED" }
            return Model(packaging, dependencies, managed).also { models[coordinate] = it }
        } finally {
            resolving.remove(coordinate)
        }
    }

    private fun dependency(raw: RawDependency, managed: Dependency?): Dependency {
        require(raw.type in listOf("", "jar") && raw.classifier.isEmpty() &&
            raw.systemPath.isEmpty()) { "MAVEN_DEPENDENCY_TYPE_UNSUPPORTED" }
        val version = raw.version.ifEmpty { managed?.coordinate?.version.orEmpty() }
        require(version.isNotEmpty()) { "MAVEN_DEPENDENCY_VERSION_MISSING:${raw.group}:${raw.artifact}" }
        val coordinate = MavenCoordinate(raw.group, raw.artifact, version)
        val scope = raw.scope.ifEmpty { managed?.scope ?: "compile" }
        require(scope in listOf("compile", "provided", "runtime", "test")) { "MAVEN_SCOPE_UNSUPPORTED" }
        require(raw.optional in listOf("", "true", "false")) { "MAVEN_OPTIONAL_INVALID" }
        val exclusions = raw.exclusions.map { (group, artifact) ->
            require(group.matches(NAME) && artifact.matches(NAME)) { "MAVEN_EXCLUSION_INVALID" }
            "$group:$artifact"
        }.toSet() + (managed?.exclusions ?: emptySet())
        val optional = if (raw.optional.isEmpty()) managed?.optional ?: false else raw.optional == "true"
        return Dependency(coordinate, scope, optional, exclusions)
    }

    private fun raw(coordinate: MavenCoordinate): RawModel {
        rawModels[coordinate]?.let { return it }
        require(loading.add(coordinate)) { "MAVEN_PARENT_CYCLE:${coordinate.notation}" }
        try {
            require(loading.size <= 32) { "MAVEN_MODEL_DEPTH_TOO_LARGE" }
            val pom = source.get(coordinate) ?: error("MAVEN_POM_MISSING:${coordinate.notation}")
            require(pom.coordinate == coordinate) { "MAVEN_POM_IDENTITY_MISMATCH" }
            inputs[coordinate] = MavenModelPom(coordinate, pom.sha256)
            require(inputs.size <= 2048) { "MAVEN_MODEL_COUNT_TOO_LARGE" }
            val project = parseXml(pom.xml)
            require(project.localName == "project") { "MAVEN_POM_ROOT_INVALID" }
            require(value(project, "modelVersion") == "4.0.0") { "MAVEN_POM_MODEL_UNSUPPORTED" }
            require(child(project, "profiles") == null) { "MAVEN_POM_profiles_UNSUPPORTED" }
            val parent = child(project, "parent")?.let { element ->
                // Never follow repository-supplied relativePath onto an application filesystem.
                val parentCoordinate = MavenCoordinate(value(element, "groupId"),
                    value(element, "artifactId"), value(element, "version"))
                raw(parentCoordinate).also {
                    require(it.packaging == "pom") { "MAVEN_PARENT_PACKAGING_INVALID" }
                }
            }
            require(value(project, "groupId").ifEmpty { parent?.coordinate?.group.orEmpty() } == coordinate.group &&
                value(project, "artifactId") == coordinate.artifact &&
                value(project, "version").ifEmpty { parent?.coordinate?.version.orEmpty() } == coordinate.version) {
                "MAVEN_POM_IDENTITY_MISMATCH"
            }
            val properties = linkedMapOf<String, String>()
            properties.putAll(parent?.properties ?: emptyMap())
            child(project, "properties")?.let { container ->
                val localNames = mutableSetOf<String>()
                elements(container).forEach { element ->
                    val name = element.localName
                    require(name.matches(PROPERTY_NAME) &&
                        listOf("project.", "pom.", "env.", "settings.").none { name.startsWith(it) }) {
                        "MAVEN_PROPERTY_NAME_UNSUPPORTED:$name"
                    }
                    require(elements(element).isEmpty()) { "MAVEN_PROPERTY_VALUE_INVALID:$name" }
                    require(localNames.add(name)) { "MAVEN_PROPERTY_DUPLICATE:$name" }
                    properties[name] = element.textContent.trim()
                }
            }
            val dependencies = declarations(child(project, "dependencies"))
            val management = declarations(child(project, "dependencyManagement")?.let { child(it, "dependencies") })
            return RawModel(coordinate, value(project, "packaging").ifEmpty { "jar" }, properties,
                inherit(dependencies, parent?.dependencies.orEmpty()),
                inherit(management, parent?.management.orEmpty())).also { rawModels[coordinate] = it }
        } finally {
            loading.remove(coordinate)
        }
    }

    private fun inherit(local: List<RawDependency>, inherited: List<RawDependency>): List<RawDependency> {
        require(local.map { it.key }.distinct().size == local.size) { "MAVEN_DEPENDENCY_DUPLICATE" }
        val parent = inherited.associateBy { it.key }
        val result = local.map { entry -> parent[entry.key]?.let(entry::inherit) ?: entry } +
            inherited.filter { parentEntry -> local.none { it.key == parentEntry.key } }
        require(result.size <= 1024) { "MAVEN_MODEL_DEPENDENCIES_TOO_LARGE" }
        return result
    }

    private fun declarations(container: Element?): List<RawDependency> = container?.let {
        val declarations = children(it, "dependency")
        require(declarations.size <= 1024) { "MAVEN_MODEL_DEPENDENCIES_TOO_LARGE" }
        declarations.map { element ->
            require(element.attributes.length == 0) { "MAVEN_DEPENDENCY_ATTRIBUTES_UNSUPPORTED" }
            RawDependency(value(element, "groupId"), value(element, "artifactId"), value(element, "version"),
                value(element, "scope"), value(element, "optional"), value(element, "type"),
                value(element, "classifier"), value(element, "systemPath"),
                child(element, "exclusions")?.let { exclusions ->
                    children(exclusions, "exclusion").map { exclusion ->
                        value(exclusion, "groupId") to value(exclusion, "artifactId")
                    }
                }.orEmpty())
        }
    }.orEmpty()

    private class Interpolation(properties: Map<String, String>, coordinate: MavenCoordinate) {
        private val properties = properties
        private val cache = mutableMapOf<String, String>()
        private val resolving = mutableSetOf<String>()
        private var substitutions = 0
        init {
            require(properties.size <= 256) { "MAVEN_PROPERTY_COUNT_TOO_LARGE" }
            for (prefix in listOf("project", "pom")) {
                cache["$prefix.groupId"] = coordinate.group
                cache["$prefix.artifactId"] = coordinate.artifact
                cache["$prefix.version"] = coordinate.version
            }
            properties.keys.forEach { resolve(it) }
        }
        private fun resolve(name: String): String {
            cache[name]?.let { return it }
            val raw = properties[name] ?: error("MAVEN_PROPERTY_UNRESOLVED:$name")
            require(resolving.add(name)) { "MAVEN_PROPERTY_CYCLE:$name" }
            try {
                require(resolving.size <= 32) { "MAVEN_PROPERTY_DEPTH_TOO_LARGE" }
                return expand(raw).also { cache[name] = it }
            } finally { resolving.remove(name) }
        }
        fun expand(raw: String): String {
            require(raw.length <= 4096) { "MAVEN_PROPERTY_EXPANSION_TOO_LARGE" }
            val output = StringBuilder()
            fun append(text: String) {
                require(text.length <= 4096 - output.length) { "MAVEN_PROPERTY_EXPANSION_TOO_LARGE" }
                output.append(text)
            }
            var cursor = 0
            while (true) {
                val start = raw.indexOf("\${", cursor)
                if (start < 0) break
                append(raw.substring(cursor, start))
                val end = raw.indexOf('}', start + 2)
                require(end >= 0) { "MAVEN_PROPERTY_EXPRESSION_INVALID" }
                val name = raw.substring(start + 2, end)
                require(name.matches(PROPERTY_NAME)) { "MAVEN_PROPERTY_EXPRESSION_INVALID" }
                require(++substitutions <= 4096) { "MAVEN_PROPERTY_EXPANSION_TOO_LARGE" }
                append(resolve(name))
                cursor = end + 1
            }
            append(raw.substring(cursor))
            return output.toString()
        }
    }

    private fun parseXml(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        // Android's XMLConstants omits these JAXP constant fields. Use the
        // exact property names without changing the parser's fail-closed policy.
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> error("MAVEN_XML_EXTERNAL_ENTITY") }
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) { throw exception }
            override fun error(exception: SAXParseException) { throw exception }
            override fun fatalError(exception: SAXParseException) { throw exception }
        })
        return builder.parse(InputSource(StringReader(xml))).documentElement
    }
    private fun value(parent: Element, name: String): String = child(parent, name)?.textContent?.trim().orEmpty()
    private fun child(parent: Element, name: String): Element? {
        val matches = children(parent, name)
        require(matches.size <= 1) { "MAVEN_POM_DUPLICATE_ELEMENT:$name" }
        return matches.firstOrNull()
    }
    private fun children(parent: Element, name: String): List<Element> = elements(parent).filter { it.localName == name }
    private fun elements(parent: Element): List<Element> = (0 until parent.childNodes.length).mapNotNull {
        parent.childNodes.item(it) as? Element
    }
    private companion object {
        val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
        val PROPERTY_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
    }
}
