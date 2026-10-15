package dev.wind.agent.build

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class EmbeddedModuleKind {
    JVM,
    ANDROID_APPLICATION,
    ANDROID_LIBRARY
}

data class EmbeddedSourceSet(
    val javaDirectories: List<File>,
    val kotlinDirectories: List<File>,
    val resourceDirectories: List<File>,
    val manifest: File?,
    val testJavaDirectories: List<File>,
    val testKotlinDirectories: List<File>,
    val testResourceDirectories: List<File>
)

data class EmbeddedDependency(
    val configuration: String,
    val notation: String,
    val projectPath: String? = null
) {
    init {
        require(configuration.isNotBlank()) { "EMBEDDED_DEPENDENCY_CONFIGURATION_MISSING" }
        require(notation.isNotBlank()) { "EMBEDDED_DEPENDENCY_NOTATION_MISSING" }
        if (projectPath != null) require(projectPath.startsWith(":")) {
            "EMBEDDED_PROJECT_DEPENDENCY_INVALID"
        }
    }
}

data class EmbeddedPluginDeclaration(
    val id: String,
    val version: String? = null,
    val form: String = "invocation"
) {
    init {
        require(id.isNotBlank()) { "EMBEDDED_PLUGIN_ID_MISSING" }
        if (version != null) require(version.isNotBlank()) { "EMBEDDED_PLUGIN_VERSION_MISSING" }
    }
}

data class EmbeddedModule(
    val name: String,
    val directory: File,
    val kind: EmbeddedModuleKind,
    val namespace: String? = null,
    val compileSdk: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val kotlinPlugin: Boolean = false,
    val sourceSet: EmbeddedSourceSet,
    val dependencies: List<EmbeddedDependency> = emptyList(),
    val buildScript: File? = null,
    /** Gradle project path; [name] remains the stable display name for compatibility. */
    val gradlePath: String = name,
    val pluginDeclarations: List<EmbeddedPluginDeclaration> = emptyList()
) {
    init {
        require(name.isNotBlank()) { "EMBEDDED_MODULE_NAME_MISSING" }
        require(directory.path.isNotBlank()) { "EMBEDDED_MODULE_DIRECTORY_MISSING" }
        require(gradlePath == ":" || gradlePath.startsWith(":")) {
            "EMBEDDED_MODULE_PATH_INVALID"
        }
        if (kind != EmbeddedModuleKind.JVM) require(compileSdk != null) {
            "EMBEDDED_COMPILE_SDK_MISSING"
        }
    }

    val outputDirectories: List<File> = listOf(
        File(directory, "build/classes/java/main"),
        File(directory, "build/classes/kotlin/main"),
        File(directory, "build/intermediates/javac/debug/classes")
    )
}

/** Static project model. It never evaluates Gradle or runs a project script. */
data class EmbeddedProjectModel(
    val root: File,
    val modules: List<EmbeddedModule>,
    val settingsScript: File?,
    val rootBuildScript: File?,
    val unsupportedReasons: List<String> = emptyList(),
    val diagnostics: List<BuildDiagnostic> = emptyList()
) {
    init {
        require(root.path.isNotBlank()) { "EMBEDDED_PROJECT_ROOT_MISSING" }
        val names = modules.map { it.name }
        require(names.size == names.toSet().size) { "EMBEDDED_MODULE_DUPLICATE_NAME" }
    }

    val supported: Boolean get() = modules.isNotEmpty() && unsupportedReasons.isEmpty()

    fun module(name: String): EmbeddedModule? =
        modules.firstOrNull { it.name == name || it.gradlePath == name }

    /** Deterministic dependency order used by BuildPlan; rejects unknown edges/cycles. */
    fun topologicallyOrderedModules(): List<EmbeddedModule> {
        val byName = modules.associateBy { it.gradlePath }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val ordered = mutableListOf<EmbeddedModule>()
        fun visit(name: String) {
            require(name in byName) { "EMBEDDED_PROJECT_DEPENDENCY_MISSING:$name" }
            if (name in visiting) error("EMBEDDED_PROJECT_DEPENDENCY_CYCLE")
            if (!visited.add(name)) return
            visiting += name
            byName.getValue(name).dependencies.mapNotNull { it.projectPath }
                .sorted()
                .forEach(::visit)
            visiting -= name
            ordered += byName.getValue(name)
        }
        modules.map { it.gradlePath }.sorted().forEach(::visit)
        return ordered
    }
}

/**
 * B1's deliberately small Gradle subset parser. Unsupported dynamic constructs
 * produce a model diagnostic; there is no hidden Gradle or Termux fallback.
 */
class EmbeddedProjectModelParser(
    private val maxScriptBytes: Int = DEFAULT_MAX_SCRIPT_BYTES
) {
    private enum class ScriptRole { SETTINGS, PROJECT }

    fun parse(root: File): EmbeddedProjectModel {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val reasons = mutableListOf<String>()
        val diagnostics = mutableListOf<BuildDiagnostic>()
        if (!canonicalRoot.isDirectory) {
            return unsupported(canonicalRoot, null, null, "EMBEDDED_PROJECT_ROOT_INVALID")
        }

        val settings = firstExisting(canonicalRoot, reasons, diagnostics, "settings.gradle.kts", "settings.gradle")
        val rootBuild = firstExisting(canonicalRoot, reasons, diagnostics, "build.gradle.kts", "build.gradle")
        rejectDualGradleScripts(canonicalRoot, "settings", reasons, diagnostics)
        rejectDualGradleScripts(canonicalRoot, "build", reasons, diagnostics)
        if (settings == null && rootBuild == null) {
            return unsupported(canonicalRoot, null, null, "EMBEDDED_PROJECT_MODEL_UNSUPPORTED")
        }

        val settingsText = readBounded(settings, reasons, diagnostics)
        val rootBuildText = readBounded(rootBuild, reasons, diagnostics)
        detectUnsupported(settingsText, reasons, diagnostics)
        detectUnsupported(rootBuildText, reasons, diagnostics)
        validateSupportedScript(
            settingsText,
            scriptDialect(settings),
            ScriptRole.SETTINGS,
            reasons,
            diagnostics
        )
        validateSupportedScript(
            rootBuildText,
            scriptDialect(rootBuild),
            ScriptRole.PROJECT,
            reasons,
            diagnostics
        )
        validateDelimiterBalance(settingsText, reasons, diagnostics)
        validateDelimiterBalance(rootBuildText, reasons, diagnostics)

        val includes = parseIncludes(settingsText, scriptDialect(settings))
        if (includes.dynamic) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_DYNAMIC_INCLUDE",
                "Only quoted literal include paths are supported"
            )
        }
        val included = includes.paths
        val moduleNames = if (included.isEmpty()) {
            listOf(":")
        } else {
            buildList {
                // A non-empty root build is a real Gradle project, even when
                // settings also includes child projects. Never drop it from
                // the model merely because it is not listed in include().
                if (rootBuild != null && rootBuildText.isNotBlank()) add(":")
                addAll(included.distinct().sorted())
            }
        }
        val modules = mutableListOf<EmbeddedModule>()
        for (name in moduleNames) {
            val isRootModule = name == ":"
            val explicitInclude = !isRootModule
            val moduleDirectory = if (isRootModule) {
                canonicalRoot
            } else {
                resolveModuleDirectory(canonicalRoot, name, reasons)
            } ?: continue
            val buildScript = if (isRootModule) {
                rootBuild
            } else {
                firstExisting(moduleDirectory, reasons, diagnostics, "build.gradle.kts", "build.gradle")
            }
            if (!isRootModule) {
                rejectDualGradleScripts(moduleDirectory, "build", reasons, diagnostics)
            }
            if (explicitInclude && buildScript == null) {
                reasons.add("EMBEDDED_MODULE_BUILD_SCRIPT_MISSING:$name")
                diagnostics.add(diagnostic(
                    "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                    "Included module has no build.gradle(.kts): $name"
                ))
                continue
            }
            val scriptText = readBounded(buildScript, reasons, diagnostics)
            detectUnsupported(scriptText, reasons, diagnostics)
            validateSupportedScript(
                scriptText,
                scriptDialect(buildScript ?: rootBuild),
                ScriptRole.PROJECT,
                reasons,
                diagnostics
            )
            validateDelimiterBalance(scriptText, reasons, diagnostics)
            val mergedText = if (isRootModule) rootBuildText else scriptText
            val scriptDialect = scriptDialect(buildScript ?: rootBuild)
            if (hasUnsupportedPluginDeclaration(mergedText, scriptDialect)) {
                addUnsupported(
                    reasons,
                    diagnostics,
                    "EMBEDDED_UNSUPPORTED_PLUGIN",
                    "Only the allow-listed static plugin declarations are supported: $name"
                )
            }
            val pluginResult = parsePluginDeclarations(mergedText, scriptDialect)
            val kind = moduleKind(mergedText, scriptDialect)
            if (kind == null) {
                reasons.add("EMBEDDED_MODULE_KIND_UNSUPPORTED:$name")
                diagnostics.add(diagnostic(
                    "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                    "Could not determine a supported JVM/Android module kind: $name"
                ))
                continue
            }
            val dependencies = parseDependencies(
                mergedText,
                scriptDialect,
                allowedDependencyConfigurations(pluginResult.appliedIds, kind)
            )
            if (dependencies.unsupported) {
                addUnsupported(
                    reasons,
                    diagnostics,
                    "EMBEDDED_DYNAMIC_DEPENDENCY",
                    "Only literal external or project dependency notation is supported: $name"
                )
            }
            val allAndroidBlocks = blockBodies(mergedText, "android")
            val androidBlocks = topLevelBlockBodies(mergedText, "android")
            val androidScopeSupported = !hasUnmodeledControlFlow(mergedText) &&
                allAndroidBlocks.size == androidBlocks.size &&
                androidBlocks.size <= 1 &&
                (androidBlocks.isEmpty() || androidMetadataScopeSupported(androidBlocks.single(), scriptDialect))
            if (allAndroidBlocks.isNotEmpty() &&
                (kind == EmbeddedModuleKind.JVM || !androidScopeSupported)
            ) {
                addUnsupported(
                    reasons,
                    diagnostics,
                    "EMBEDDED_ANDROID_DSL_UNSUPPORTED",
                    "Android DSL must belong to an Android module with one owned top-level block: $name"
                )
            }
            val androidMetadata = if (kind != EmbeddedModuleKind.JVM &&
                androidScopeSupported && androidBlocks.size == 1
            ) {
                androidMetadataValues(androidBlocks.single(), scriptDialect)
            } else null
            val module = runCatching {
                EmbeddedModule(
                    name = if (isRootModule) canonicalRoot.name.ifBlank { "root" } else name,
                    directory = moduleDirectory,
                    kind = kind,
                    namespace = androidMetadata?.namespace,
                    compileSdk = androidMetadata?.compileSdk,
                    minSdk = androidMetadata?.minSdk,
                    targetSdk = androidMetadata?.targetSdk,
                    kotlinPlugin = hasKotlinPlugin(mergedText, scriptDialect),
                    sourceSet = sourceSet(moduleDirectory, kind, reasons, diagnostics),
                    dependencies = dependencies.values,
                    buildScript = buildScript,
                    gradlePath = name,
                    pluginDeclarations = pluginResult.declarations
                )
            }.getOrElse { error ->
                val reason = "EMBEDDED_PROJECT_MODEL_UNSUPPORTED:$name"
                reasons.add(reason)
                diagnostics.add(diagnostic(
                    "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                    "Module metadata is not supported for $name: " +
                        (error.message ?: "invalid metadata")
                ))
                null
            }
            if (module != null) modules.add(module)
        }

        val model = EmbeddedProjectModel(
            root = canonicalRoot,
            modules = modules,
            settingsScript = settings,
            rootBuildScript = rootBuild,
            unsupportedReasons = reasons.distinct().sorted(),
            diagnostics = diagnostics.distinct()
        )
        val missingDependencies = model.modules.flatMap { module ->
            module.dependencies.mapNotNull { dependency ->
                dependency.projectPath?.takeUnless { model.module(it) != null }
                    ?.let { missing -> module.name to missing }
            }
        }
        val graphModel = if (missingDependencies.isNotEmpty()) {
            val extraReasons = missingDependencies.map { (module, dependency) ->
                "EMBEDDED_PROJECT_DEPENDENCY_MISSING:$module->$dependency"
            }
            val extraDiagnostics = missingDependencies.map { (module, dependency) ->
                diagnostic(
                    "EMBEDDED_PROJECT_DEPENDENCY_MISSING",
                    "Module $module references missing project dependency $dependency"
                )
            }
            model.copy(
                unsupportedReasons = (model.unsupportedReasons + extraReasons).distinct().sorted(),
                diagnostics = (model.diagnostics + extraDiagnostics).distinct()
            )
        } else {
            model
        }
        val cycleModel = runCatching {
            graphModel.topologicallyOrderedModules()
            graphModel
        }.getOrElse {
            graphModel.copy(
                unsupportedReasons = (graphModel.unsupportedReasons + "EMBEDDED_PROJECT_DEPENDENCY_CYCLE")
                    .distinct().sorted(),
                diagnostics = graphModel.diagnostics + diagnostic(
                    "EMBEDDED_PROJECT_DEPENDENCY_CYCLE",
                    "Project dependency graph contains a cycle"
                )
            )
        }
        return if (cycleModel.modules.isEmpty() && cycleModel.unsupportedReasons.isEmpty()) {
            cycleModel.copy(
                unsupportedReasons = listOf("EMBEDDED_PROJECT_MODEL_UNSUPPORTED"),
                diagnostics = cycleModel.diagnostics + diagnostic(
                    "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                    "No supported JVM or Android module was found"
                )
            )
        } else {
            cycleModel
        }
    }

    private fun unsupported(
        root: File,
        settings: File?,
        build: File?,
        reason: String
    ) = EmbeddedProjectModel(
        root = root,
        modules = emptyList(),
        settingsScript = settings,
        rootBuildScript = build,
        unsupportedReasons = listOf(reason),
        diagnostics = listOf(diagnostic(reason, "Project is not a supported static Embedded Build project"))
    )

    /**
     * Parse only the top-level bounded grammar.  Anything that is not one of
     * the explicitly modelled productions is rejected; it is never silently
     * dropped by a regex scanner. Nested bodies are consumed as balanced token
     * ranges and validated by their dedicated parsers below.
     */
    private fun validateSupportedScript(
        script: String,
        dialect: EmbeddedScriptDialect,
        role: ScriptRole,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        if (script.isBlank()) return
        val lexical = EmbeddedGradleLexer(dialect).lex(script)
        if (!lexical.complete) return
        if (lexical.unsupportedStringForm) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_UNSUPPORTED_GROOVY_SLASHY_STRING",
                "Groovy slashy and dollar-slashy strings are outside the static Embedded subset"
            )
        }
        if (lexical.unsupportedCommentForm) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_UNSUPPORTED_GROOVY_NESTED_COMMENT",
                "Groovy nested block-comment syntax is outside the static Embedded subset"
            )
        }
        val tokens = lexical.tokens.filter { it.kind != EmbeddedTokenKind.COMMENT }
        var index = 0
        var nonPluginProductionSeen = false
        while (true) {
            while (index < tokens.size &&
                (tokens[index].kind == EmbeddedTokenKind.NEWLINE ||
                    tokens[index].kind == EmbeddedTokenKind.SEMICOLON)
            ) index++
            if (index >= tokens.size) break
            val start = index
            val next = when (role) {
                ScriptRole.SETTINGS -> consumeSettingsProduction(tokens, index, dialect)
                ScriptRole.PROJECT -> consumeProjectProduction(tokens, index, dialect)
            }
            if (next == null || next <= start) {
                val token = tokens.getOrNull(index)
                addUnsupported(
                    reasons,
                    diagnostics,
                    "EMBEDDED_UNSUPPORTED_STATEMENT",
                    "Unsupported top-level Gradle statement near " + (token?.text ?: "end of script")
                )
                break
            }
            if (role == ScriptRole.PROJECT) {
                if (tokens[start].text == "plugins") {
                    if (nonPluginProductionSeen) {
                        addUnsupported(
                            reasons,
                            diagnostics,
                            "EMBEDDED_UNSUPPORTED_PLUGIN",
                            "The Gradle plugins block must precede other supported project productions"
                        )
                        break
                    }
                } else {
                    nonPluginProductionSeen = true
                }
            }
            if (next < tokens.size &&
                tokens[next].kind != EmbeddedTokenKind.NEWLINE &&
                tokens[next].kind != EmbeddedTokenKind.SEMICOLON
            ) {
                addUnsupported(
                    reasons,
                    diagnostics,
                    "EMBEDDED_UNSUPPORTED_STATEMENT",
                    "Top-level Gradle productions must be separated by a statement boundary"
                )
                break
            }
            index = next
        }
    }

    private fun consumeSettingsProduction(
        tokens: List<EmbeddedGradleToken>,
        start: Int,
        dialect: EmbeddedScriptDialect
    ): Int? {
        val first = tokens.getOrNull(start) ?: return null
        if (first.kind != EmbeddedTokenKind.IDENTIFIER) return null
        return when (first.text) {
            "rootProject" -> {
                if (tokenText(tokens, start + 1) != "." ||
                    tokenText(tokens, start + 2) != "name" ||
                    tokenText(tokens, start + 3) != "=" ||
                    tokens.getOrNull(start + 4)?.kind != EmbeddedTokenKind.STRING ||
                    !isAllowedStaticString(tokens[start + 4], dialect)
                ) return null
                val next = start + 5
                if (next < tokens.size &&
                    tokens[next].kind != EmbeddedTokenKind.NEWLINE &&
                    tokens[next].kind != EmbeddedTokenKind.SEMICOLON
                ) return null
                next
            }
            "include" -> consumeIncludeProduction(tokens, start)
            else -> null
        }
    }

    private fun isAllowedStaticString(
        token: EmbeddedGradleToken,
        dialect: EmbeddedScriptDialect
    ): Boolean {
        if (token.kind != EmbeddedTokenKind.STRING || token.text.contains('\\') || token.text.contains('$')) {
            return false
        }
        if (dialect == EmbeddedScriptDialect.KOTLIN && token.text.startsWith("'")) return false
        if (token.triple) return false
        return true
    }

    private fun consumeIncludeProduction(tokens: List<EmbeddedGradleToken>, start: Int): Int? {
        val next = start + 1
        return when (tokenText(tokens, next)) {
            "(" -> consumeBalanced(tokens, next, "(", ")")?.plus(1)
            else -> {
                if (tokens.getOrNull(next)?.kind != EmbeddedTokenKind.STRING) null
                else consumeToStatementBoundary(tokens, next)
            }
        }
    }

    private fun consumeProjectProduction(
        tokens: List<EmbeddedGradleToken>,
        start: Int,
        dialect: EmbeddedScriptDialect
    ): Int? {
        val first = tokens.getOrNull(start) ?: return null
        if (first.kind != EmbeddedTokenKind.IDENTIFIER) return null
        if (first.text == "apply") {
            if (dialect != EmbeddedScriptDialect.GROOVY ||
                tokenText(tokens, start + 1) != "plugin" ||
                tokenText(tokens, start + 2) != ":" ||
                tokens.getOrNull(start + 3)?.kind != EmbeddedTokenKind.STRING ||
                !isAllowedStaticString(tokens[start + 3], dialect)
            ) return null
            val next = start + 4
            if (next < tokens.size &&
                tokens[next].kind != EmbeddedTokenKind.NEWLINE &&
                tokens[next].kind != EmbeddedTokenKind.SEMICOLON
            ) return null
            return next
        }
        if (first.text !in setOf("plugins", "android", "dependencies")) return null
        if (tokenText(tokens, start + 1) != "{") return null
        return consumeBalanced(tokens, start + 1, "{", "}")?.plus(1)
    }

    private fun consumeToStatementBoundary(tokens: List<EmbeddedGradleToken>, start: Int): Int {
        var index = start + 1
        var paren = 0
        var bracket = 0
        var brace = 0
        while (index < tokens.size) {
            when (tokens[index].text) {
                "(" -> paren++
                ")" -> paren--
                "[" -> bracket++
                "]" -> bracket--
                "{" -> brace++
                "}" -> {
                    if (brace == 0 && paren == 0 && bracket == 0) return index
                    brace--
                }
            }
            if ((tokens[index].kind == EmbeddedTokenKind.NEWLINE ||
                    tokens[index].kind == EmbeddedTokenKind.SEMICOLON) &&
                paren == 0 && bracket == 0 && brace == 0
            ) return index
            index++
        }
        return index
    }

    private fun consumeBalanced(
        tokens: List<EmbeddedGradleToken>,
        openIndex: Int,
        open: String,
        close: String
    ): Int? {
        if (tokenText(tokens, openIndex) != open) return null
        var depth = 0
        for (index in openIndex until tokens.size) {
            when (tokens[index].text) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun tokenText(tokens: List<EmbeddedGradleToken>, index: Int): String? =
        tokens.getOrNull(index)?.text

    private fun rejectDualGradleScripts(
        root: File,
        kind: String,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val kts = File(root, if (kind == "settings") "settings.gradle.kts" else "build.gradle.kts")
        val groovy = File(root, if (kind == "settings") "settings.gradle" else "build.gradle")
        if (kts.isFile && groovy.isFile) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_DUAL_GRADLE_SCRIPTS",
                "Both Kotlin and Groovy $kind scripts exist; dialect selection is ambiguous"
            )
        }
    }

    private fun readBounded(
        file: File?,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ): String {
        if (file == null) return ""
        return try {
            if (!file.isFile || file.length() > maxScriptBytes) {
                reasons.add("EMBEDDED_PROJECT_SCRIPT_TOO_LARGE:${file.name}")
                diagnostics.add(diagnostic(
                    "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                    "Build script is missing or exceeds the static parser limit: ${file.name}"
                ))
                ""
            } else {
                file.readText(Charsets.UTF_8)
            }
        } catch (error: Exception) {
            reasons.add("EMBEDDED_PROJECT_SCRIPT_UNREADABLE:${file.name}")
            diagnostics.add(diagnostic(
                "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                "Could not read build script ${file.name}: ${error.message ?: "read failed"}"
            ))
            ""
        }
    }

    private fun resolveModuleDirectory(root: File, name: String, reasons: MutableList<String>): File? {
        if (!name.startsWith(":")) {
            reasons.add("EMBEDDED_MODULE_PATH_INVALID:$name")
            return null
        }
        val relative = name.removePrefix(":").split(':')
        if (relative.isEmpty() || relative.any {
                it.isBlank() || it == "." || it == ".." || it.contains('/') || it.contains('\\')
            }) {
            reasons.add("EMBEDDED_MODULE_PATH_INVALID:$name")
            return null
        }
        val candidate = try {
            File(root, relative.joinToString(File.separator)).canonicalFile
        } catch (_: Exception) {
            reasons.add("EMBEDDED_MODULE_PATH_INVALID:$name")
            return null
        }
        if (hasSymbolicLinkComponent(root, relative.joinToString(File.separator)) ||
            !isBelow(root, candidate)
        ) {
            reasons.add("EMBEDDED_MODULE_PATH_ESCAPE:$name")
            return null
        }
        return candidate
    }

    private fun sourceSet(
        directory: File,
        kind: EmbeddedModuleKind,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ): EmbeddedSourceSet {
        val android = kind != EmbeddedModuleKind.JVM
        return EmbeddedSourceSet(
            javaDirectories = existingDirectories(directory, "src/main/java", reasons, diagnostics),
            kotlinDirectories = existingDirectories(directory, "src/main/kotlin", reasons, diagnostics),
            resourceDirectories = existingDirectories(
                directory,
                if (android) "src/main/res" else "src/main/resources",
                reasons,
                diagnostics
            ),
            manifest = if (android) {
                firstExisting(directory, reasons, diagnostics, "src/main/AndroidManifest.xml")
            } else {
                null
            },
            testJavaDirectories = existingDirectories(directory, "src/test/java", reasons, diagnostics),
            testKotlinDirectories = existingDirectories(directory, "src/test/kotlin", reasons, diagnostics),
            testResourceDirectories = if (android) {
                emptyList()
            } else {
                existingDirectories(directory, "src/test/resources", reasons, diagnostics)
            }
        )
    }

    private fun existingDirectories(
        root: File,
        relative: String,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ): List<File> {
        val raw = File(root, relative)
        if (!raw.isDirectory) return emptyList()
        if (hasSymbolicLinkComponent(root, relative)) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_PATH_ESCAPE",
                "Source/resource directory is a symbolic link: $relative"
            )
            return emptyList()
        }
        val canonical = runCatching { raw.canonicalFile }.getOrElse {
            addUnsupported(reasons, diagnostics, "EMBEDDED_PATH_UNSAFE", "Could not canonicalize $relative")
            return emptyList()
        }
        if (!isBelow(root, canonical)) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_PATH_ESCAPE",
                "Source/resource directory escapes module root: $relative"
            )
            return emptyList()
        }
        if (!hasNoSymbolicLinkDescendants(canonical, relative, reasons, diagnostics)) {
            return emptyList()
        }
        return listOf(canonical)
    }

    private fun hasNoSymbolicLinkDescendants(
        root: File,
        relative: String,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ): Boolean {
        return try {
            var safe = true
            Files.walkFileTree(
                root.toPath(),
                emptySet<FileVisitOption>(),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes
                    ): FileVisitResult {
                        if (attributes.isSymbolicLink || Files.isSymbolicLink(directory)) {
                            addUnsupported(
                                reasons,
                                diagnostics,
                                "EMBEDDED_PATH_ESCAPE",
                                "Source/resource tree contains a symbolic link: $directory"
                            )
                            safe = false
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes
                    ): FileVisitResult {
                        if (attributes.isSymbolicLink || Files.isSymbolicLink(file)) {
                            addUnsupported(
                                reasons,
                                diagnostics,
                                "EMBEDDED_PATH_ESCAPE",
                                "Source/resource tree contains a symbolic link: $file"
                            )
                            safe = false
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                        addUnsupported(
                            reasons,
                            diagnostics,
                            "EMBEDDED_PATH_UNSAFE",
                            "Could not traverse source/resource tree $relative: ${error.message ?: "read failed"}"
                        )
                        safe = false
                        return FileVisitResult.TERMINATE
                    }
                }
            )
            safe
        } catch (error: Exception) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_PATH_UNSAFE",
                "Could not traverse source/resource tree $relative: ${error.message ?: "read failed"}"
            )
            false
        }
    }

    private fun moduleKind(
        script: String,
        dialect: EmbeddedScriptDialect
    ): EmbeddedModuleKind? {
        val plugins = parsePluginDeclarations(script, dialect)
        val application = "com.android.application" in plugins.appliedIds
        val library = "com.android.library" in plugins.appliedIds
        if (application && library) return null
        val hasKotlinJvm = "org.jetbrains.kotlin.jvm" in plugins.appliedIds
        val hasKotlinAndroid = "org.jetbrains.kotlin.android" in plugins.appliedIds
        val hasJava = plugins.appliedIds.any { it in JVM_PLUGIN_IDS }
        val hasAndroid = application || library
        // Kotlin Android is an Android companion plugin, never a JVM fallback.
        if (hasKotlinAndroid && !hasAndroid) return null
        if ((hasKotlinJvm || hasJava) && hasAndroid) return null
        if (application) return EmbeddedModuleKind.ANDROID_APPLICATION
        if (library) return EmbeddedModuleKind.ANDROID_LIBRARY
        if (hasJava || hasKotlinJvm) return EmbeddedModuleKind.JVM
        return null
    }

    private fun hasPlugin(script: String, id: String, dialect: EmbeddedScriptDialect): Boolean =
        id in parsePluginDeclarations(script, dialect).appliedIds

    private fun hasKotlinPlugin(script: String, dialect: EmbeddedScriptDialect): Boolean =
        parsePluginDeclarations(script, dialect).appliedIds.any { it in KOTLIN_PLUGIN_IDS }

    private fun scriptDialect(file: File?): EmbeddedScriptDialect =
        if (file?.name?.endsWith(".gradle") == true) {
            EmbeddedScriptDialect.GROOVY
        } else {
            EmbeddedScriptDialect.KOTLIN
        }

    private fun hasUnsupportedPluginDeclaration(
        script: String,
        dialect: EmbeddedScriptDialect
    ): Boolean = parsePluginDeclarations(script, dialect).unsupported

    /**
     * Parse plugin declarations once for both identity and validation. A
     * declaration with an unsupported modifier never contributes an applied
     * plugin ID, so `apply false` cannot masquerade as an applied plugin.
     */
    private fun parsePluginDeclarations(
        script: String,
        dialect: EmbeddedScriptDialect
    ): PluginParseResult {
        val supported = SUPPORTED_PLUGIN_IDS
        val applied = linkedSetOf<String>()
        val declarations = mutableListOf<EmbeddedPluginDeclaration>()
        var unsupported = false
        if (hasUnmodeledControlFlow(script)) unsupported = true
        val allBlocks = blockBodies(script, "plugins")
        val topBlocks = topLevelBlockBodies(script, "plugins")
        if (allBlocks.size != topBlocks.size) unsupported = true

        topBlocks.forEach { body ->
            val bodyIds = linkedSetOf<String>()
            val bodyDeclarations = mutableListOf<EmbeddedPluginDeclaration>()
            var bodySupported = true
            val invocations = pluginInvocations(body, dialect)
            if (body.trim().isNotEmpty() && invocations.isEmpty()) bodySupported = false
            if (hasUnconsumedTopLevelText(body)) bodySupported = false
            var previous: Invocation? = null
            var previousPluginId: String? = null
            var versionUsed = false
            invocations.forEach { invocation ->
                if (invocation.hasTrailingClosure || !invocation.syntaxComplete) bodySupported = false
                val gap = previous?.let { body.substring(it.end.coerceAtLeast(0), invocation.start.coerceAtLeast(0)) }
                    ?: ""
                val chainedToPrevious = previous != null &&
                    gap.any { it.isWhitespace() } &&
                    gap.none { it == ';' }
                val startsNewDeclaration = invocation.name == "id" ||
                    invocation.name == "kotlin" ||
                    invocation.name == "java" ||
                    invocation.name == "java-library"
                if (startsNewDeclaration && previous != null &&
                    gap.none { it == '\n' || it == '\r' || it == ';' }
                ) {
                    bodySupported = false
                }
                when (invocation.name) {
                    "id" -> {
                        val id = quotedLiteral(invocation.body.trim(), dialect)
                        if (id == null || id !in supported) bodySupported = false
                        else {
                            bodyIds += id
                            bodyDeclarations += EmbeddedPluginDeclaration(id, form = "id")
                            previousPluginId = id
                            versionUsed = false
                        }
                    }
                    "kotlin" -> {
                        val id = when (quotedLiteral(invocation.body.trim(), dialect)) {
                            "jvm" -> "org.jetbrains.kotlin.jvm"
                            "android" -> "org.jetbrains.kotlin.android"
                            else -> null
                        }
                        if (id == null || id !in supported) bodySupported = false else {
                            bodyIds += id
                            bodyDeclarations += EmbeddedPluginDeclaration(id, form = "kotlin")
                            previousPluginId = id
                            versionUsed = false
                        }
                    }
                    "version" -> {
                        if (!chainedToPrevious || previousPluginId == null ||
                            previousPluginId in JVM_PLUGIN_IDS || versionUsed ||
                            !isStaticPluginVersion(invocation.body.trim(), dialect)
                        ) {
                            bodySupported = false
                        } else {
                            versionUsed = true
                            val last = bodyDeclarations.lastOrNull { it.id == previousPluginId }
                            if (last == null) {
                                bodySupported = false
                            } else {
                                val position = bodyDeclarations.indexOf(last)
                                bodyDeclarations[position] = last.copy(
                                    version = quotedLiteral(invocation.body.trim(), dialect)
                                )
                            }
                        }
                    }
                    "java", "java-library" -> {
                        if (invocation.body.trim().isNotEmpty() || invocation.name !in supported) {
                            bodySupported = false
                        } else {
                            bodyIds += invocation.name
                            bodyDeclarations += EmbeddedPluginDeclaration(invocation.name, form = "bare")
                            previousPluginId = invocation.name
                            versionUsed = false
                        }
                    }
                    // apply false is deliberately outside this bounded
                    // subset; it must not apply the preceding declaration.
                    "apply" -> bodySupported = false
                    else -> bodySupported = false
                }
                previous = invocation
            }
            if (bodySupported && bodyIds.size == bodyDeclarations.map { it.id }.toSet().size) {
                if (bodyDeclarations.groupBy { it.id }.values.any { it.size > 1 }) {
                    bodySupported = false
                }
            }
            if (bodySupported) {
                applied += bodyIds
                declarations += bodyDeclarations
            } else unsupported = true
        }

        val code = stripComments(script)
        val masked = maskStrings(code)
        // Stop at the colon because string masking turns the following literal
        // into whitespace; consuming that whitespace would lose its source
        // position before the literal can be recovered from the original code.
        val applyPattern = Regex("\\bapply\\s+plugin\\s*:")
        applyPattern.findAll(masked).forEach { match ->
            if (dialect != EmbeddedScriptDialect.GROOVY) {
                unsupported = true
                return@forEach
            }
            if (!isTopLevelPosition(code, match.range.first)) {
                unsupported = true
                return@forEach
            }
            if (!isIndependentStatementStart(code, match.range.first)) {
                unsupported = true
                return@forEach
            }
            var cursor = match.range.last + 1
            while (cursor < code.length && code[cursor].isWhitespace()) cursor++
            val literal = stringLiterals(code).firstOrNull { it.start == cursor }
            if (literal == null || literal.value !in supported || literal.value.contains('$') ||
                !onlyStatementWhitespace(code, literal.end)
            ) {
                unsupported = true
            } else if (!isInsidePluginsBlock(code, literal.start)) {
                applied += literal.value
                declarations += EmbeddedPluginDeclaration(literal.value, form = "apply")
            }
        }
        if (Regex("\\bapply\\s*\\(").findAll(masked).any()) unsupported = true
        if (declarations.groupBy { it.id }.values.any { it.size > 1 }) unsupported = true
        return PluginParseResult(applied, declarations, unsupported)
    }

    /**
     * Groovy permits the command-style form `id 'plugin' version 'x'`.
     * The generic invocation scanner intentionally treats a bare invocation's
     * whole statement as its body, so split this one bounded plugin chain back
     * into the same state-machine events used by Kotlin-style declarations.
     */
    private fun pluginInvocations(
        script: String,
        dialect: EmbeddedScriptDialect
    ): List<Invocation> {
        val parsed = topLevelInvocations(script)
        if (dialect != EmbeddedScriptDialect.GROOVY) return parsed
        val out = mutableListOf<Invocation>()
        parsed.forEach { invocation ->
            val chain = if (invocation.name == "id" || invocation.name == "kotlin") {
                groovyPluginVersionChain(invocation)
            } else {
                null
            }
            if (chain == null) {
                out += invocation
                return@forEach
            }
            val bodyStart = invocation.end - invocation.body.length
            val rawStart = bodyStart + chain.rawStart
            out += Invocation(
                name = invocation.name,
                body = chain.pluginLiteral,
                start = invocation.start,
                end = rawStart + chain.pluginEnd
            )
            out += Invocation(
                name = "version",
                body = chain.versionLiteral,
                start = rawStart + chain.versionStart,
                end = invocation.end
            )
        }
        return out
    }

    private data class GroovyPluginVersionChain(
        val rawStart: Int,
        val pluginLiteral: String,
        val pluginEnd: Int,
        val versionStart: Int,
        val versionLiteral: String
    )

    private fun groovyPluginVersionChain(invocation: Invocation): GroovyPluginVersionChain? {
        if (invocation.hasTrailingClosure || !invocation.syntaxComplete) return null
        val body = invocation.body
        var index = 0
        while (index < body.length && body[index].isWhitespace()) index++
        val rawStart = index
        val pluginEnd = quotedTokenEnd(body, index) ?: return null
        index = pluginEnd
        val versionKeywordStart = index
        while (index < body.length && body[index].isWhitespace()) index++
        if (index == versionKeywordStart || !body.startsWith("version", index)) return null
        val keywordEnd = index + "version".length
        if (keywordEnd < body.length &&
            (body[keywordEnd].isLetterOrDigit() || body[keywordEnd] == '_')
        ) return null
        index = keywordEnd
        val versionStart = index
        while (index < body.length && body[index].isWhitespace()) index++
        if (index == versionStart) return null
        val literalStart = index
        val literalEnd = quotedTokenEnd(body, literalStart) ?: return null
        if (body.substring(literalEnd).any { !it.isWhitespace() }) return null
        return GroovyPluginVersionChain(
            rawStart = rawStart,
            pluginLiteral = body.substring(rawStart, pluginEnd),
            pluginEnd = pluginEnd,
            versionStart = literalStart,
            versionLiteral = body.substring(literalStart, literalEnd)
        )
    }

    private fun quotedTokenEnd(text: String, start: Int): Int? {
        if (start >= text.length || (text[start] != '\'' && text[start] != '"')) return null
        val quote = text[start]
        var index = start + 1
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                return index + 1
            }
            index++
        }
        return null
    }

    private fun blockBodies(script: String, name: String): List<String> {
        val code = stripComments(script)
        return blockRanges(code, name).map { range -> code.substring(range.first, range.last + 1) }
    }

    private fun topLevelBlockBodies(
        script: String,
        name: String,
        preserveComments: Boolean = false
    ): List<String> {
        val code = stripComments(script)
        val source = if (preserveComments) script else code
        val masked = maskStrings(code)
        val out = mutableListOf<String>()
        var index = 0
        var depth = 0
        while (index < code.length) {
            val char = masked[index]
            if (depth == 0 && (char.isLetter() || char == '_')) {
                val start = index
                index++
                while (index < code.length &&
                    (masked[index].isLetterOrDigit() || masked[index] == '_')
                ) index++
                if (code.substring(start, index) == name) {
                    if (!isIndependentStatementStart(code, start)) {
                        continue
                    }
                    var cursor = index
                    while (cursor < code.length && code[cursor].isWhitespace()) cursor++
                    if (cursor < code.length && masked[cursor] == '{') {
                        val close = matchingDelimiter(code, cursor, '{', '}')
                        if (close < 0) {
                            out += source.substring(cursor + 1)
                            break
                        }
                        out += source.substring(cursor + 1, close)
                        index = close + 1
                        continue
                    }
                }
                continue
            }
            when (char) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
            index++
        }
        return out
    }

    private fun isIndependentStatementStart(code: String, start: Int): Boolean {
        var cursor = start - 1
        while (cursor >= 0 && (code[cursor] == ' ' || code[cursor] == '\t')) {
            cursor--
        }
        if (cursor >= 0 && code[cursor] == '.') return false
        if (cursor < 0 || code[cursor] == ';') return true
        if (code[cursor] != '\n' && code[cursor] != '\r') return false
        var before = cursor - 1
        if (code[cursor] == '\n' && before >= 0 && code[before] == '\r') before--
        while (before >= 0 && (code[before] == ' ' || code[before] == '\t')) before--
        return before < 0 || code[before] != '.'
    }

    private data class AndroidMetadataValues(
        val namespace: String?,
        val compileSdk: Int,
        val minSdk: Int?,
        val targetSdk: Int?
    )

    private fun androidMetadataScopeSupported(
        androidBlock: String,
        dialect: EmbeddedScriptDialect
    ): Boolean {
        if (!androidBodySupported(androidBlock, dialect)) return false
        val full = maskStrings(stripComments(androidBlock))
        val topLevel = topLevelOnlyText(androidBlock)
        val defaultConfig = topLevelBlockBodies(androidBlock, "defaultConfig")
            .joinToString("\n") { topLevelOnlyText(it) }
        val keys = listOf(
            "namespace", "compileSdk", "compileSdkVersion", "minSdk", "minSdkVersion",
            "targetSdk", "targetSdkVersion"
        )
        val scopeSupported = keys.all { key ->
            val allowed = if (key == "minSdk" || key == "minSdkVersion" ||
                key == "targetSdk" || key == "targetSdkVersion"
            ) {
                "$topLevel\n$defaultConfig"
            } else {
                topLevel
            }
            countKey(full, key) == countKey(maskStrings(allowed), key)
        }
        return scopeSupported && androidMetadataValues(androidBlock, dialect) != null
    }

    /** Positive grammar for the intentionally tiny Android metadata subset. */
    private fun androidBodySupported(
        body: String,
        dialect: EmbeddedScriptDialect,
        defaultConfig: Boolean = false
    ): Boolean {
        val tokens = EmbeddedGradleLexer(dialect).lex(body).tokens
            .filter { it.kind != EmbeddedTokenKind.COMMENT }
        var index = 0
        val allowed = if (defaultConfig) {
            setOf("minSdk", "minSdkVersion", "targetSdk", "targetSdkVersion")
        } else {
            setOf("namespace", "compileSdk", "compileSdkVersion")
        }
        while (true) {
            while (index < tokens.size &&
                (tokens[index].kind == EmbeddedTokenKind.NEWLINE ||
                    tokens[index].kind == EmbeddedTokenKind.SEMICOLON)
            ) index++
            if (index >= tokens.size) return true
            val name = tokens[index]
            if (name.kind != EmbeddedTokenKind.IDENTIFIER) return false
            if (!defaultConfig && name.text == "defaultConfig") {
                if (tokens.getOrNull(index + 1)?.text != "{") return false
                val close = consumeBalanced(tokens, index + 1, "{", "}") ?: return false
                val nestedText = if (index + 2 < close) {
                    body.substring(tokens[index + 2].start, tokens[close].start)
                } else {
                    ""
                }
                if (!androidBodySupported(nestedText, dialect, defaultConfig = true)) return false
                index = close + 1
                continue
            }
            if (name.text !in allowed) return false
            index++
            if (dialect == EmbeddedScriptDialect.KOTLIN) {
                if (tokens.getOrNull(index)?.text != "=") return false
                index++
            } else if (tokens.getOrNull(index)?.text == "=") {
                index++
            }
            val value = tokens.getOrNull(index) ?: return false
            val validValue = when {
                name.text == "namespace" -> value.kind == EmbeddedTokenKind.STRING
                else -> value.kind == EmbeddedTokenKind.NUMBER && value.text.matches(Regex("[0-9]+"))
            }
            if (!validValue) return false
            index++
            if (index < tokens.size && tokens[index].kind != EmbeddedTokenKind.NEWLINE &&
                tokens[index].kind != EmbeddedTokenKind.SEMICOLON
            ) return false
        }
    }

    private fun androidMetadataValues(
        androidBlock: String,
        dialect: EmbeddedScriptDialect
    ): AndroidMetadataValues? {
        val text = androidMetadataText(androidBlock)
        val namespaceAssignments = metadataAssignmentValues(text, "namespace", dialect)
        val compileAssignments = metadataAssignmentValues(text, "compileSdk", dialect) +
            metadataAssignmentValues(text, "compileSdkVersion", dialect)
        val minAssignments = metadataAssignmentValues(text, "minSdk", dialect) +
            metadataAssignmentValues(text, "minSdkVersion", dialect)
        val targetAssignments = metadataAssignmentValues(text, "targetSdk", dialect) +
            metadataAssignmentValues(text, "targetSdkVersion", dialect)
        val families = listOf(
            listOf("namespace") to namespaceAssignments,
            listOf("compileSdk", "compileSdkVersion") to compileAssignments,
            listOf("minSdk", "minSdkVersion") to minAssignments,
            listOf("targetSdk", "targetSdkVersion") to targetAssignments
        )
        if (families.any { (keys, values) ->
                keys.sumOf { key -> metadataKeyOccurrences(text, key) } != values.size
            }) return null
        if (namespaceAssignments.size > 1 || compileAssignments.size != 1 ||
            minAssignments.size > 1 || targetAssignments.size > 1
        ) return null

        val namespace = namespaceAssignments.singleOrNull()?.let {
            val value = quotedLiteral(it, dialect) ?: return null
            if (!value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) {
                return null
            }
            value
        }
        val compileSdk = parseMetadataInt(compileAssignments.single(), dialect) ?: return null
        val minSdk = minAssignments.singleOrNull()?.let {
            parseMetadataInt(it, dialect) ?: return null
        }
        val targetSdk = targetAssignments.singleOrNull()?.let {
            parseMetadataInt(it, dialect) ?: return null
        }
        if (compileSdk <= 0 || minSdk?.let { it <= 0 } == true || targetSdk?.let { it <= 0 } == true) {
            return null
        }
        if (minSdk != null && targetSdk != null && minSdk > targetSdk) return null
        if (targetSdk != null && targetSdk > compileSdk) return null
        return AndroidMetadataValues(namespace, compileSdk, minSdk, targetSdk)
    }

    private fun metadataAssignmentValues(
        text: String,
        key: String,
        dialect: EmbeddedScriptDialect
    ): List<String> {
        val code = stripComments(text)
        val assignment = if (dialect == EmbeddedScriptDialect.KOTLIN) "=" else "(?:=)?"
        return Regex("(?m)(?:^|;)[ \\t]*${Regex.escape(key)}\\b[ \\t]*$assignment[ \\t]*([^;\\r\\n}]*)")
            .findAll(code)
            .filter { !isInsideString(code, it.range.first) }
            .map { it.groupValues[1].trim() }
            .toList()
    }

    private fun metadataKeyOccurrences(text: String, key: String): Int {
        val masked = maskStrings(stripComments(text))
        return countKey(masked, key)
    }

    private fun parseMetadataInt(expression: String, dialect: EmbeddedScriptDialect): Int? =
        expression.toIntOrNull()?.takeIf { it > 0 } ?: if (dialect == EmbeddedScriptDialect.GROOVY) {
            quotedLiteral(expression, dialect)?.toIntOrNull()?.takeIf { it > 0 }
        } else null

    private fun androidMetadataText(androidBlock: String): String {
        val topLevel = topLevelOnlyText(androidBlock)
        val defaults = topLevelBlockBodies(androidBlock, "defaultConfig")
            .joinToString("\n") { topLevelOnlyText(it) }
        return if (defaults.isBlank()) topLevel else "$topLevel\n$defaults"
    }

    private fun topLevelOnlyText(script: String): String {
        val code = stripComments(script)
        val masked = maskStrings(code)
        return buildString(code.length) {
            var depth = 0
            code.indices.forEach { index ->
                val char = masked[index]
                append(if (depth == 0 || char == '\n' || char == '\r') code[index] else ' ')
                when (char) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private fun countKey(text: String, key: String): Int =
        Regex("\\b${Regex.escape(key)}\\b").findAll(text).count()

    private fun blockRanges(code: String, name: String): List<IntRange> {
        val masked = maskStrings(code)
        val ranges = mutableListOf<IntRange>()
        Regex("\\b${Regex.escape(name)}\\s*\\{").findAll(masked).forEach { match ->
            val brace = masked.indexOf('{', match.range.first)
            if (brace < 0) return@forEach
            val close = matchingDelimiter(masked, brace, '{', '}')
            if (close >= brace) ranges += (brace + 1 until close)
        }
        return ranges
    }

    /** Scans top-level DSL declarations while skipping nested call arguments. */
    private fun topLevelInvocations(script: String): List<Invocation> {
        val code = stripComments(script)
        val masked = maskStrings(code)
        val out = mutableListOf<Invocation>()
        var index = 0
        var depth = 0
        while (index < code.length) {
            val char = masked[index]
            if (char == '(' || char == '[' || char == '{') {
                depth++
                index++
                continue
            }
            if (char == ')' || char == ']' || char == '}') {
                depth = (depth - 1).coerceAtLeast(0)
                index++
                continue
            }
            if (depth != 0 || !char.isLetter() && char != '_') {
                index++
                continue
            }
            val start = index
            index++
            while (index < code.length &&
                (masked[index].isLetterOrDigit() || masked[index] == '_' ||
                    (masked[index] == '-' && index + 1 < code.length && masked[index + 1].isLetter()))
            ) index++
            val name = code.substring(start, index)
            var cursor = index
            var crossedLine = false
            while (cursor < code.length && code[cursor].isWhitespace()) {
                if (code[cursor] == '\n' || code[cursor] == '\r') crossedLine = true
                cursor++
            }
            if (crossedLine && (cursor >= code.length ||
                    (masked[cursor] != '(' && masked[cursor] != '{'))
            ) {
                out += Invocation(name, "", start = start, end = index)
                continue
            }
            if (cursor < code.length && code[cursor] == ';') {
                out += Invocation(name, "", start = start, end = index)
                continue
            }
            when {
                cursor >= code.length -> out += Invocation(name, "", start = start, end = index)
                masked[cursor] == '(' -> {
                    val close = matchingDelimiter(code, cursor, '(', ')')
                    if (close < 0) {
                        out += Invocation(
                            name,
                            code.substring(cursor + 1),
                            start = start,
                            end = code.length,
                            syntaxComplete = false
                        )
                        index = code.length
                    } else {
                        var after = close + 1
                        while (after < code.length && code[after].isWhitespace()) after++
                        out += Invocation(
                            name,
                            code.substring(cursor + 1, close),
                            after < code.length && code[after] == '{',
                            start = start,
                            end = close + 1
                        )
                        index = close + 1
                    }
                }
                masked[cursor] == '{' -> {
                    val close = matchingDelimiter(code, cursor, '{', '}')
                    if (close < 0) {
                        out += Invocation(
                            name,
                            code.substring(cursor + 1),
                            hasTrailingClosure = true,
                            start = start,
                            end = code.length,
                            syntaxComplete = false
                        )
                        index = code.length
                    } else {
                        out += Invocation(
                            name,
                            code.substring(cursor + 1, close),
                            hasTrailingClosure = true,
                            start = start,
                            end = close + 1
                        )
                        index = close + 1
                    }
                }
                code[cursor] == '\"' || code[cursor] == '\'' ||
                    masked[cursor].isLetterOrDigit() || masked[cursor] == '_' -> {
                    val end = (cursor until code.length).firstOrNull { offset ->
                        code[offset] == '\n' || code[offset] == '\r' || code[offset] == ';'
                    } ?: code.length
                    var after = end
                    while (after < code.length && code[after].isWhitespace()) after++
                    val inlineClosure = (cursor until end).firstOrNull { offset -> masked[offset] == '{' }
                    val closureOpen = inlineClosure ?: after.takeIf { it < code.length && masked[it] == '{' }
                    if (closureOpen != null) {
                        val close = matchingDelimiter(code, closureOpen, '{', '}')
                        if (close < 0) {
                            out += Invocation(
                                name,
                                code.substring(cursor, closureOpen),
                                hasTrailingClosure = true,
                                start = start,
                                end = code.length,
                                syntaxComplete = false
                            )
                            index = code.length
                        } else {
                            out += Invocation(
                                name,
                                code.substring(cursor, inlineClosure ?: end),
                                hasTrailingClosure = true,
                                start = start,
                                end = close + 1
                            )
                            index = close + 1
                        }
                    } else {
                        out += Invocation(
                            name,
                            code.substring(cursor, end),
                            start = start,
                            end = end
                        )
                        index = end
                    }
                }
            }
        }
        return out
    }

    private fun invocationUsesParentheses(script: String, invocation: Invocation): Boolean {
        val code = stripComments(script)
        var cursor = invocation.start + invocation.name.length
        while (cursor < code.length && code[cursor].isWhitespace()) cursor++
        return cursor < code.length && code[cursor] == '('
    }

    /**
     * Returns true when top-level text in a bounded DSL block is not owned by
     * an invocation or a harmless separator. This prevents punctuation,
     * standalone closures, and chained modifiers from disappearing between
     * scanner iterations.
     */
    private fun hasUnconsumedTopLevelText(script: String): Boolean {
        val code = stripComments(script)
        val masked = maskStrings(code)
        val invocations = topLevelInvocations(script).sortedBy { it.start }
        var cursor = 0
        fun gapHasSyntax(start: Int, end: Int): Boolean {
            if (end <= start) return false
            return masked.substring(start, end).any { !it.isWhitespace() && it != ';' }
        }
        for (invocation in invocations) {
            if (invocation.start < 0 || invocation.end < invocation.start) continue
            if (gapHasSyntax(cursor, invocation.start)) return true
            cursor = maxOf(cursor, invocation.end)
        }
        return gapHasSyntax(cursor, code.length)
    }

    private fun hasLegalKotlinInvocationBoundaries(
        script: String,
        invocations: List<Invocation>
    ): Boolean {
        if (invocations.size < 2) return true
        val lexical = EmbeddedGradleLexer(EmbeddedScriptDialect.KOTLIN).lex(script)
        if (!lexical.complete) return false
        val boundaries = lexical.tokens.filter {
            it.kind == EmbeddedTokenKind.NEWLINE ||
                it.kind == EmbeddedTokenKind.SEMICOLON
        }
        return invocations.zipWithNext().all { (previous, next) ->
            boundaries.any { boundary ->
                boundary.start >= previous.end && boundary.end <= next.start
            }
        }
    }

    private data class IncludeParseResult(val paths: List<String>, val dynamic: Boolean)

    private fun parseIncludes(
        settings: String,
        dialect: EmbeddedScriptDialect
    ): IncludeParseResult {
        val out = linkedSetOf<String>()
        val allInvocations = invocationBodies(settings, "include")
        val topLevelIncludes = topLevelInvocations(settings).filter { it.name == "include" }
        // Settings is a static model boundary too. A literal include nested in
        // a conditional, lambda, function, or another call must not silently
        // become an unconditional module edge.
        val code = stripComments(settings)
        var dynamic = hasUnmodeledControlFlow(settings) ||
            hasStringInterpolation(settings) ||
            allInvocations.size != topLevelIncludes.size
        var previousEnd: Int? = null
        topLevelIncludes.forEach { invocation ->
            val statementStart = maxOf(
                code.lastIndexOf('\n', invocation.start - 1),
                code.lastIndexOf('\r', invocation.start - 1),
                code.lastIndexOf(';', invocation.start - 1)
            ) + 1
            if (code.substring(statementStart, invocation.start).any { !it.isWhitespace() }) {
                dynamic = true
            }
            previousEnd?.let { end ->
                val gap = code.substring(end.coerceAtLeast(0), invocation.start.coerceAtLeast(0))
                if (gap.none { it == '\n' || it == '\r' || it == ';' }) dynamic = true
            }
            var before = invocation.start - 1
            while (before >= 0 && code[before].isWhitespace()) before--
            if (before >= 0 && code[before] == '.') dynamic = true
            previousEnd = invocation.end
            if (invocation.hasTrailingClosure || !invocation.syntaxComplete) {
                dynamic = true
                return@forEach
            }
            if (dialect == EmbeddedScriptDialect.KOTLIN &&
                !invocationUsesParentheses(settings, invocation)
            ) {
                dynamic = true
                return@forEach
            }
            val arguments = splitArguments(invocation.body)
            if (arguments.isEmpty()) {
                dynamic = true
                return@forEach
            }
            arguments.forEach { argument ->
                val path = quotedLiteral(argument.trim(), dialect)
                if (path == null || !path.startsWith(":")) {
                    dynamic = true
                } else {
                    out += path
                }
            }
        }
        return IncludeParseResult(out.toList(), dynamic)
    }

    private data class DependencyParseResult(
        val values: List<EmbeddedDependency>,
        val unsupported: Boolean
    )

    private fun parseDependencies(
        script: String,
        dialect: EmbeddedScriptDialect,
        supportedConfigurations: Set<String>
    ): DependencyParseResult {
        val out = mutableListOf<EmbeddedDependency>()
        var unsupported = false
        val allBlocks = blockBodies(script, "dependencies")
        val blocks = topLevelBlockBodies(script, "dependencies", preserveComments = true)
        // A dependencies block nested in if/function/another DSL block is
        // dynamic from the static model's perspective. Do not extract it as
        // though it were an unconditional top-level block.
        if (allBlocks.size != blocks.size || hasUnmodeledControlFlow(script)) unsupported = true
        if (blocks.isEmpty()) {
            // The script-level positive grammar has already rejected any
            // dependency-like call outside a dependencies block. There is no
            // scanner fallback here that could silently reinterpret it.
            return DependencyParseResult(emptyList(), unsupported)
        }
        blocks.forEach { block ->
            if (hasUnconsumedTopLevelText(block)) unsupported = true
            val invocations = topLevelInvocations(block)
            if (dialect == EmbeddedScriptDialect.KOTLIN &&
                !hasLegalKotlinInvocationBoundaries(block, invocations)
            ) unsupported = true
            invocations.forEach { invocation ->
                if (invocation.name !in supportedConfigurations) {
                    unsupported = true
                    return@forEach
                }
                if (invocation.hasTrailingClosure) {
                    unsupported = true
                    return@forEach
                }
                if (!invocation.syntaxComplete) {
                    unsupported = true
                    return@forEach
                }
                if (dialect == EmbeddedScriptDialect.KOTLIN &&
                    !invocationUsesParentheses(block, invocation)
                ) {
                    unsupported = true
                    return@forEach
                }
                val configuration = invocation.name
                val body = invocation.body.trim().removeSuffix(";").trim()
                val project = Regex("^\\(?\\s*project\\s*\\(\\s*(.*?)\\s*\\)\\s*\\)?$")
                    .matchEntire(body)
                val projectPath = project?.groupValues?.get(1)?.let {
                    quotedLiteral(it.trim(), dialect)
                }
                val external = quotedLiteral(body.removeSurrounding("(", ")").trim(), dialect)
                when {
                    projectPath != null && projectPath.startsWith(":") ->
                        out.add(EmbeddedDependency(configuration, "project($projectPath)", projectPath))
                    external != null && isStaticExternalNotation(external) ->
                        out.add(EmbeddedDependency(configuration, external))
                    else -> unsupported = true
                }
            }
        }
        return DependencyParseResult(
            out.distinctBy { Triple(it.configuration, it.notation, it.projectPath) }
                .sortedWith(compareBy<EmbeddedDependency> { it.configuration }.thenBy { it.notation }),
            unsupported
        )
    }

    private fun allowedDependencyConfigurations(
        pluginIds: Set<String>,
        kind: EmbeddedModuleKind?
    ): Set<String> {
        if (kind == null) return emptySet()
        val common = setOf("implementation", "compileOnly", "runtimeOnly", "testImplementation", "testRuntimeOnly")
        return when {
            kind == EmbeddedModuleKind.JVM && "java-library" in pluginIds -> common + "api"
            kind == EmbeddedModuleKind.JVM && "java" in pluginIds -> common
            kind != EmbeddedModuleKind.JVM -> common + "api"
            else -> emptySet()
        }
    }

    private fun detectUnsupported(
        script: String,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        val lexical = scanLexical(script)
        if (!lexical.complete) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_UNTERMINATED_LEXICAL_CONSTRUCT",
                "Build script contains an unterminated string or block comment"
            )
        }
        if (lexical.hasGroovySlashyString) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_UNSUPPORTED_GROOVY_SLASHY_STRING",
                "Groovy slashy and dollar-slashy strings are outside the static Embedded subset"
            )
        }
        val code = lexical.commentsAndStringsMasked
        listOf(
            "afterEvaluate(", "subprojects {", "allprojects {", "apply from:",
            "plugins.withId(", "gradle.beforeProject", "eval(", "buildSrc",
            "projectDir", "sourceSets", "versionCatalogs", "libs."
        ).forEach { marker ->
            if (code.contains(marker)) {
                val code = "EMBEDDED_PROJECT_MODEL_UNSUPPORTED:$marker"
                reasons.add(code)
                diagnostics.add(diagnostic(
                    "EMBEDDED_PROJECT_MODEL_UNSUPPORTED",
                    "Static Embedded parser rejected dynamic Gradle construct: $marker"
                ))
            }
        }
        if (hasStringInterpolation(script)) {
            addUnsupported(
                reasons,
                diagnostics,
                "EMBEDDED_DYNAMIC_STRING_INTERPOLATION",
                "Executable string interpolation is outside the static Embedded model"
            )
        }
    }

    private data class StringLiteral(val start: Int, val end: Int, val value: String)

    private data class Invocation(
        val name: String,
        val body: String,
        val hasTrailingClosure: Boolean = false,
        val start: Int = -1,
        val end: Int = -1,
        val syntaxComplete: Boolean = true
    )

    private data class PluginParseResult(
        val appliedIds: Set<String>,
        val declarations: List<EmbeddedPluginDeclaration>,
        val unsupported: Boolean
    )

    private val SUPPORTED_PLUGIN_IDS = setOf(
        "java", "java-library", "com.android.application", "com.android.library",
        "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android"
    )

    private val JVM_PLUGIN_IDS = setOf("java", "java-library")

    private val KOTLIN_PLUGIN_IDS = setOf(
        "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android"
    )

    private fun quotedLiteral(text: String, dialect: EmbeddedScriptDialect): String? {
        if (text.length < 2) return null
        if (text.startsWith("\"\"\"") || text.startsWith("'''") ||
            text.endsWith("\"\"\"") || text.endsWith("'''")
        ) return null
        val quote = text.first()
        if ((quote != '"' && quote != '\'') || text.last() != quote) return null
        if (dialect == EmbeddedScriptDialect.KOTLIN && quote == '\'') return null
        val value = StringBuilder()
        for (index in 1 until text.lastIndex) {
            val char = text[index]
            if (char == '\\' || char == quote || char == '$') {
                return null
            } else {
                value.append(char)
            }
        }
        return value.toString()
    }

    private fun isQuotedLiteral(text: String, dialect: EmbeddedScriptDialect): Boolean =
        quotedLiteral(text, dialect) != null

    private fun isStaticPluginVersion(text: String, dialect: EmbeddedScriptDialect): Boolean {
        val value = quotedLiteral(text, dialect) ?: return false
        return value.isNotBlank() &&
            !value.contains('+') &&
            !value.contains('$') &&
            !value.contains("latest", ignoreCase = true) &&
            !value.contains('[') && !value.contains(']') &&
            !value.contains('(') && !value.contains(')')
    }

    /**
     * The bounded parser does not evaluate control flow or user-defined
     * functions. Reject those constructs before any delimiter-depth scanner
     * can mistake a conditional single statement for an unconditional DSL
     * declaration (for example: `if (flag) android { ... }`).
     */
    private fun hasUnmodeledControlFlow(script: String): Boolean {
        val code = stripCommentsAndStrings(script)
        return Regex("\\b(if|for|while|when|switch|try|catch|else|do|fun|def)\\b")
            .containsMatchIn(code)
    }

    /**
     * String interpolation executes an embedded expression. The bounded
     * parser does not evaluate those expressions, so reject them instead of
     * masking an include/plugin/dependency call as inert string content.
     */
    private fun hasStringInterpolation(script: String): Boolean {
        val code = stripComments(script)
        var index = 0
        while (index < code.length) {
            if (code.startsWith("\"\"\"", index)) {
                index += 3
                val start = index
                while (index < code.length && !code.startsWith("\"\"\"", index)) index++
                val value = code.substring(start, index)
                if (containsInterpolation(value)) return true
                if (index < code.length) index += 3
                continue
            }
            if (code.startsWith("'''", index)) {
                index += 3
                while (index < code.length && !code.startsWith("'''", index)) index++
                if (index < code.length) index += 3
                continue
            }
            val quote = code[index]
            if (quote != '\"' && quote != '\'') {
                index++
                continue
            }
            val interpolates = quote == '\"'
            index++
            var escaped = false
            while (index < code.length) {
                val char = code[index++]
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    break
                } else if (interpolates && char == '$' && index < code.length &&
                    (code[index] == '{' || isInterpolationIdentifierStart(code[index]))
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun containsInterpolation(value: String): Boolean {
        for (index in value.indices) {
            if (value[index] == '$' && index + 1 < value.length &&
                (value[index + 1] == '{' || isInterpolationIdentifierStart(value[index + 1]))) {
                return true
            }
        }
        return false
    }

    private fun isInterpolationIdentifierStart(char: Char): Boolean =
        char.isLetter() || char == '_'

    /** Reject both unmatched opening and unmatched/mismatched closing delimiters. */
    private fun validateDelimiterBalance(
        script: String,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>
    ) {
        if (script.isBlank() || delimitersBalanced(script)) return
        addUnsupported(
            reasons,
            diagnostics,
            "EMBEDDED_UNBALANCED_DELIMITER",
            "Build script contains an unmatched or mismatched (), [] or {} delimiter"
        )
    }

    private fun delimitersBalanced(script: String): Boolean {
        val masked = scanLexical(script).commentsAndStringsMasked
        val stack = mutableListOf<Char>()
        masked.forEach { char ->
            when (char) {
                '(', '[', '{' -> stack += char
                ')', ']', '}' -> {
                    val expected = when (char) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (stack.isEmpty() || stack.removeAt(stack.lastIndex) != expected) return false
                }
            }
        }
        return stack.isEmpty()
    }

    private fun onlyStatementWhitespace(code: String, start: Int): Boolean {
        var index = start
        while (index < code.length && code[index] != '\n' && code[index] != '\r' &&
            code[index] != ';' && code[index] != '}'
        ) {
            if (!code[index].isWhitespace()) return false
            index++
        }
        return true
    }

    private fun isTopLevelPosition(code: String, index: Int): Boolean {
        val masked = maskStrings(code)
        var depth = 0
        for (cursor in 0 until index.coerceAtMost(masked.length)) {
            when (masked[cursor]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth == 0
    }

    /**
     * Returns calls whose name is one of [names]. The scan is performed over a
     * string-masked copy, so a dependency-looking token in a quoted message is
     * never treated as Gradle syntax. The original (unmasked) text is retained
     * for parsing the literal argument and for rejecting dynamic expressions.
     */
    private fun invocationBodies(script: String, names: String): List<Invocation> {
        val code = stripComments(script)
        val masked = maskStrings(code)
        val out = mutableListOf<Invocation>()
        Regex("\\b($names)\\b").findAll(masked).forEach { match ->
            var cursor = match.range.last + 1
            while (cursor < code.length && code[cursor].isWhitespace()) cursor++
            if (cursor < code.length && code[cursor] == '(') {
                val close = matchingDelimiter(code, cursor, '(', ')')
                if (close < 0) {
                    out += Invocation(match.groupValues[1], code.substring(cursor + 1))
                } else {
                    out += Invocation(match.groupValues[1], code.substring(cursor + 1, close))
                }
            } else {
                val end = (cursor until code.length).firstOrNull { index ->
                    code[index] == '\n' || code[index] == '\r' || code[index] == ';' || code[index] == '}'
                } ?: code.length
                out += Invocation(match.groupValues[1], code.substring(cursor, end))
            }
        }
        return out
    }

    private fun splitArguments(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var depth = 0
        val masked = maskStrings(text)
        masked.forEachIndexed { index, char ->
            if (char == '(' || char == '[' || char == '{') {
                depth++
            } else if (char == ')' || char == ']' || char == '}') {
                depth = (depth - 1).coerceAtLeast(0)
            } else if (char == ',' && depth == 0) {
                out += text.substring(start, index).trim()
                start = index + 1
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty() || out.isNotEmpty()) out += tail
        return out
    }

    private fun matchingDelimiter(text: String, openIndex: Int, open: Char, close: Char): Int {
        val masked = maskStrings(text)
        var depth = 0
        for (index in openIndex until text.length) {
            val char = masked[index]
            if (char == open) {
                depth++
            } else if (char == close) {
                depth--
                if (depth == 0) return index
            }
        }
        return -1
    }

    private fun stringLiterals(code: String): List<StringLiteral> {
        val out = mutableListOf<StringLiteral>()
        var index = 0
        while (index < code.length) {
            if (code.startsWith("\"\"\"", index) || code.startsWith("'''", index)) {
                val start = index
                val quote = code.substring(index, index + 3)
                index += 3
                val valueStart = index
                while (index < code.length && !code.startsWith(quote, index)) index++
                val valueEnd = index
                if (index < code.length) {
                    index += 3
                    out += StringLiteral(start, index, code.substring(valueStart, valueEnd))
                }
                continue
            }
            val quote = code[index]
            if (quote != '\"' && quote != '\'') {
                index++
                continue
            }
            val start = index
            index++
            val value = StringBuilder()
            var escaped = false
            while (index < code.length) {
                val char = code[index]
                if (escaped) {
                    value.append(char)
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    out += StringLiteral(start, index + 1, value.toString())
                    index++
                    break
                } else {
                    value.append(char)
                }
                index++
            }
        }
        return out
    }

    private fun isInsideString(code: String, index: Int): Boolean =
        stringLiterals(code).any { index >= it.start && index < it.end }

    private fun maskStrings(code: String): String = scanLexical(code).stringsMasked

    private fun pluginBlocks(code: String): List<IntRange> = blockRanges(code, "plugins")

    private fun isInsidePluginsBlock(code: String, index: Int): Boolean =
        pluginBlocks(code).any { index in it }

    private fun barePluginInBlock(code: String, id: String): Boolean =
        pluginBlocks(code).any { range ->
            val masked = maskStrings(code.substring(range.first, range.last + 1))
            Regex("\\b${Regex.escape(id)}\\b").containsMatchIn(masked)
        }

    private fun kotlinPluginCall(script: String): Boolean {
        val code = stripComments(script)
        val masked = maskStrings(code)
        return Regex("\\bkotlin\\s*\\(").findAll(masked).any { isInsidePluginsBlock(code, it.range.first) }
    }

    private fun firstExisting(
        root: File,
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>,
        vararg names: String
    ): File? {
        for (name in names) {
            val raw = File(root, name)
            if (!raw.exists()) continue
            if (!raw.isFile) continue
            if (hasSymbolicLinkComponent(root, name)) {
                addUnsupported(reasons, diagnostics, "EMBEDDED_PATH_ESCAPE", "File is a symbolic link: $name")
                return null
            }
            val canonical = runCatching { raw.canonicalFile }.getOrElse {
                addUnsupported(reasons, diagnostics, "EMBEDDED_PATH_UNSAFE", "Could not canonicalize $name")
                return null
            }
            if (!isBelow(root, canonical)) {
                addUnsupported(reasons, diagnostics, "EMBEDDED_PATH_ESCAPE", "File escapes project root: $name")
                return null
            }
            return canonical
        }
        return null
    }

    private fun addUnsupported(
        reasons: MutableList<String>,
        diagnostics: MutableList<BuildDiagnostic>,
        code: String,
        message: String
    ) {
        reasons += code
        diagnostics += diagnostic(code, message)
    }

    private fun isStaticExternalNotation(notation: String): Boolean {
        if (notation.isBlank() || notation.contains('$') || notation.contains('+')) return false
        if (notation.contains("latest", ignoreCase = true) ||
            notation.contains("release", ignoreCase = true) ||
            notation.contains('[') || notation.contains(']') ||
            notation.contains('(') || notation.contains(')')
        ) return false
        val pieces = notation.split(':')
        if (pieces.size !in 3..4 || pieces.any { it.isBlank() }) return false
        return pieces.all { it.matches(Regex("[A-Za-z0-9_.-]+")) }
    }

    private fun hasSymbolicLinkComponent(root: File, relative: String): Boolean {
        var current = root
        for (part in relative.split('/', '\\').filter { it.isNotEmpty() }) {
            current = File(current, part)
            if (Files.isSymbolicLink(current.toPath())) return true
        }
        return false
    }

    private data class LexicalScanResult(
        val commentsStripped: String,
        val stringsMasked: String,
        val commentsAndStringsMasked: String,
        val complete: Boolean,
        val hasGroovySlashyString: Boolean
    )

    /**
     * Shared bounded lexer. It preserves source offsets in every view and carries
     * lexical completeness so callers cannot silently accept an unterminated
     * string or nested block comment as a valid static model.
     */
    private fun scanLexical(script: String): LexicalScanResult {
        val commentsStripped = StringBuilder(script.length)
        val stringsMasked = StringBuilder(script.length)
        val commentsAndStringsMasked = StringBuilder(script.length)
        var complete = true
        var hasGroovySlashyString = false

        fun masked(char: Char): Char = if (char == '\n' || char == '\r') char else ' '
        fun appendNormal(char: Char) {
            commentsStripped.append(char)
            stringsMasked.append(char)
            commentsAndStringsMasked.append(char)
        }
        fun appendString(char: Char) {
            commentsStripped.append(char)
            val value = masked(char)
            stringsMasked.append(value)
            commentsAndStringsMasked.append(value)
        }
        fun appendComment(char: Char) {
            val value = masked(char)
            commentsStripped.append(value)
            stringsMasked.append(char)
            commentsAndStringsMasked.append(value)
        }

        var index = 0
        while (index < script.length) {
            if (script.startsWith("\"\"\"", index) || script.startsWith("'''", index)) {
                val quote = script.substring(index, index + 3)
                repeat(3) { appendString(script[index++]) }
                var closed = false
                while (index < script.length) {
                    if (script.startsWith(quote, index)) {
                        repeat(3) { appendString(script[index++]) }
                        closed = true
                        break
                    }
                    appendString(script[index++])
                }
                if (!closed) complete = false
                continue
            }

            val char = script[index]
            if (char == '"' || char == '\'') {
                appendString(script[index++])
                var escaped = false
                var closed = false
                while (index < script.length) {
                    val valueChar = script[index++]
                    appendString(valueChar)
                    if (valueChar == '\n' || valueChar == '\r') {
                        complete = false
                    }
                    if (escaped) {
                        escaped = false
                    } else if (valueChar == '\\') {
                        escaped = true
                    } else if (valueChar == char) {
                        closed = true
                        break
                    }
                }
                if (!closed) complete = false
                continue
            }

            if (char == '$' && index + 1 < script.length && script[index + 1] == '/') {
                hasGroovySlashyString = true
                appendString(script[index++])
                appendString(script[index++])
                var closed = false
                while (index < script.length) {
                    if (script.startsWith("/$", index)) {
                        appendString(script[index++])
                        appendString(script[index++])
                        closed = true
                        break
                    }
                    appendString(script[index++])
                }
                if (!closed) complete = false
                continue
            }

            if (char == '/' && index + 1 < script.length && script[index + 1] == '/') {
                appendComment(script[index++])
                appendComment(script[index++])
                while (index < script.length && script[index] != '\n' && script[index] != '\r') {
                    appendComment(script[index++])
                }
                continue
            }

            if (char == '/' && index + 1 < script.length && script[index + 1] == '*') {
                appendComment(script[index++])
                appendComment(script[index++])
                var depth = 1
                while (index < script.length && depth > 0) {
                    when {
                        script.startsWith("/*", index) -> {
                            appendComment(script[index++])
                            appendComment(script[index++])
                            depth++
                        }
                        script.startsWith("*/", index) -> {
                            appendComment(script[index++])
                            appendComment(script[index++])
                            depth--
                        }
                        else -> appendComment(script[index++])
                    }
                }
                if (depth > 0) complete = false
                continue
            }

            if (char == '/') {
                // A non-comment slash is conservatively treated as a Groovy
                // slashy literal/expression marker. The bounded subset does
                // not model Groovy slashy semantics, so mask it and reject
                // the script instead of exposing DSL-looking text inside it.
                hasGroovySlashyString = true
                appendString(script[index++])
                var escaped = false
                var closed = false
                while (index < script.length) {
                    val valueChar = script[index++]
                    appendString(valueChar)
                    if (escaped) {
                        escaped = false
                    } else if (valueChar == '\\') {
                        escaped = true
                    } else if (valueChar == '/') {
                        closed = true
                        break
                    }
                }
                if (!closed) complete = false
                continue
            }

            appendNormal(script[index++])
        }

        return LexicalScanResult(
            commentsStripped.toString(),
            stringsMasked.toString(),
            commentsAndStringsMasked.toString(),
            complete,
            hasGroovySlashyString
        )
    }

    private fun stripComments(script: String): String = scanLexical(script).commentsStripped

    private fun stripCommentsAndStrings(script: String): String {
        return scanLexical(script).commentsAndStringsMasked
    }

    private fun isBelow(root: File, candidate: File): Boolean {
        val prefix = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        return candidate.canonicalPath == root.canonicalPath || candidate.canonicalPath.startsWith(prefix)
    }

    private fun diagnostic(code: String, message: String) = BuildDiagnostic(
        BuildDiagnosticSeverity.ERROR,
        code,
        message,
        BuildDiagnosticSource.PROJECT
    )

    companion object {
        const val DEFAULT_MAX_SCRIPT_BYTES = 512 * 1024
    }
}
