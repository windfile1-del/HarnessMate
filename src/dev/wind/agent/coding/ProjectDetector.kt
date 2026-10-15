package dev.wind.agent.coding

import dev.wind.agent.project.WorkspaceRouter
import dev.wind.agent.isBlankJava
import org.json.JSONObject

/**
 * Decides what kind of project this is, deterministically.
 *
 * The classification half is a pure function over a directory listing and the
 * contents of a handful of marker files, so it is testable without SAF and a
 * wrong answer is a unit test failure rather than a device mystery. The read
 * half is bounded: the project root plus one level of children, which is what
 * every build system here needs and nothing like the cost of walking a tree.
 */
object ProjectDetector {
    /** Reads a prepared mirror through bounded metadata and marker callbacks. */
    fun detectMirror(
        paths: Set<String>,
        primaryRoot: String,
        requestedRoot: String? = null,
        read: (String) -> String
    ): ProjectProfile {
        val primary = primaryRoot.trim('/')
        val requested = requestedRoot?.removePrefix("execution://")?.trim('/')
        require(requested == null || requested == primary || requested.startsWith("$primary/")) {
            "P4_PROJECT_PATH_OUTSIDE_PRIMARY_MOUNT"
        }
        val root = requested ?: primary
        val prefix = "$root/"
        val relative = paths.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
        val children = relative.filter { '/' in it }.map { it.substringBefore('/') }.distinct().sorted()
        val own = relative.filter { it.substringBefore('/') in MARKERS || '/' !in it || it.startsWith("gradle/") }.toSet()
        val roots = if (requested != null || MARKERS.any { it in own }) listOf(root)
            else children.filter { child -> MARKERS.any { "$child/$it" in relative } }.map { "$root/$it" }
        require(roots.size <= 1) { "P4_MULTIPLE_PROJECT_ROOTS: specify project_path under execution://$primary/" }
        val selected = roots.firstOrNull() ?: root
        val selectedPrefix = "$selected/"
        val entries = paths.filter { it.startsWith(selectedPrefix) }
            .map { it.removePrefix(selectedPrefix) }.filter { it.count { c -> c == '/' } <= 2 }.toSet()
        val files = MARKERS.filter { it in entries }.associateWith { read(selectedPrefix + it).take(MAX_MARKER_CHARS) }
        return classify("execution://$selected", entries, files)
    }

    /** Marker files that decide the kind, checked in this order. */
    private val MARKERS = listOf(
        "settings.gradle.kts", "settings.gradle",
        "build.gradle.kts", "build.gradle",
        "gradlew", "gradle/wrapper/gradle-wrapper.properties",
        "pom.xml",
        "package.json",
        "pyproject.toml", "requirements.txt", "setup.py", "pytest.ini",
        "Cargo.toml",
        "go.mod"
    )

    /**
     * Classifies from raw inputs. [files] maps a project-relative path to its
     * text content for the markers that exist; [entries] is the set of every
     * relative path directly under the root (files and directories).
     */
    fun classify(root: String, entries: Set<String>, files: Map<String, String>): ProjectProfile {
        val present = entries.map { it.replace('\\', '/').trim('/') }.toSet()
        fun has(vararg names: String): Boolean = names.any { normalizedOf(it) in present }
        fun text(vararg names: String): String =
            names.firstNotNullOfOrNull { files[normalizedOf(it)] } ?: ""

        val markers = MARKERS.filter { normalizedOf(it) in present || it in files.keys.map { key -> normalizedOf(key) } }
        val gradleSettings = text("settings.gradle.kts", "settings.gradle")
        val gradleRoot = text("build.gradle.kts", "build.gradle")
        val gradleAll = gradleSettings + "\n" + gradleRoot
        val modules = gradleModules(gradleSettings)
        val wrapper = wrapperPath(present)

        val androidApplication = gradleAll.contains("com.android.application")
        val androidLibrary = gradleAll.contains("com.android.library")
        val kotlinPlugin = gradleAll.contains("org.jetbrains.kotlin.android") ||
            gradleAll.contains("org.jetbrains.kotlin.jvm") ||
            gradleAll.contains("kotlin(\"android\")") ||
            gradleAll.contains("kotlin(\"jvm\")")

        val hasGradle = has("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle") ||
            present.any { it == "gradlew" || it == "gradlew.bat" }

        val kind: ProjectKind
        val buildSystem: String?
        val confidence: Confidence
        when {
            hasGradle && (androidApplication || androidLibrary) -> {
                kind = ProjectKind.ANDROID_GRADLE
                buildSystem = "gradle"
                confidence = if (modules.isNotEmpty() || gradleAll.isNotEmpty()) Confidence.HIGH else Confidence.MEDIUM
            }
            hasGradle -> {
                kind = ProjectKind.JVM_GRADLE
                buildSystem = "gradle"
                confidence = if (gradleAll.isNotEmpty()) Confidence.HIGH else Confidence.MEDIUM
            }
            has("pom.xml") -> {
                kind = ProjectKind.MAVEN
                buildSystem = "maven"
                confidence = Confidence.HIGH
            }
            has("package.json") -> {
                kind = ProjectKind.NODE
                buildSystem = "npm"
                confidence = Confidence.HIGH
            }
            has("pyproject.toml", "requirements.txt", "setup.py", "pytest.ini") -> {
                kind = ProjectKind.PYTHON
                buildSystem = "python"
                confidence = Confidence.MEDIUM
            }
            has("Cargo.toml") -> {
                kind = ProjectKind.RUST
                buildSystem = "cargo"
                confidence = Confidence.HIGH
            }
            has("go.mod") -> {
                kind = ProjectKind.GO
                buildSystem = "go"
                confidence = Confidence.HIGH
            }
            else -> {
                kind = ProjectKind.GENERIC
                buildSystem = null
                confidence = Confidence.LOW
            }
        }

        val testHints = mutableListOf<String>()
        if (kind == ProjectKind.ANDROID_GRADLE || kind == ProjectKind.JVM_GRADLE) {
            if (gradleAll.contains("testImplementation") || gradleAll.contains("junit")) {
                testHints.add("junit")
            }
            if (present.any { it.startsWith("src/test/") } || present.contains("src")) {
                testHints.add("src/test")
            }
        }
        if (kind == ProjectKind.NODE) {
            val pkg = text("package.json")
            // The test SCRIPT specifically. A substring search for the word
            // test matched any field of that name (a devDependency, a config
            // block, a documentation string), and the planner then promised to
            // run npm test on a project that has no test script at all.
            if (hasTestScript(pkg)) testHints.add("npm-test")
            if (pkg.contains("vitest")) testHints.add("vitest")
            if (pkg.contains("jest")) testHints.add("jest")
            if (pkg.contains("typescript")) testHints.add("typescript")
        }
        if (kind == ProjectKind.PYTHON) {
            val py = text("pyproject.toml") + "\n" + text("pytest.ini") + "\n" + text("requirements.txt")
            if (py.contains("pytest") || present.contains("pytest.ini")) testHints.add("pytest")
        }

        return ProjectProfile(
            kind = kind,
            root = root,
            modules = modules,
            buildSystem = buildSystem,
            wrapper = wrapper,
            testFrameworkHints = testHints,
            confidence = confidence,
            markers = markers,
            androidApplication = androidApplication,
            androidLibrary = androidLibrary,
            kotlinPlugin = kotlinPlugin
        )
    }

    /**
     * Reads the project root through the router and classifies it.
     *
     * Every read is best-effort: a marker file that cannot be read is treated as
     * absent rather than as a fatal error, because a detector that refuses to
     * answer leaves the whole task with nothing to plan against. The bound on how
     * much is read is what keeps that from meaning "read the repo".
     */
    fun detect(router: WorkspaceRouter): ProjectProfile {
        val project = router.getActiveProject()
        val primary = project.primaryMount()
            ?: throw IllegalStateException("PROJECT_WITHOUT_PRIMARY_MOUNT")
        val fs = router.getFsForMount(primary)
        val rootLabel = "workspace://"

        val entries = linkedSetOf<String>()
        try {
            for (item in fs.list("")) {
                entries.add(item.path)
                if (!item.isDirectory) {
                    continue
                }
                // One level down is enough for src/, gradle/, app/ and friends.
                if (entries.size >= MAX_ENTRIES) {
                    break
                }
                try {
                    for (child in fs.list(item.path)) {
                        entries.add(child.path)
                        if (entries.size >= MAX_ENTRIES) {
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        val files = linkedMapOf<String, String>()
        for (marker in MARKERS) {
            if (marker !in entries) {
                continue
            }
            try {
                val text = fs.readText(marker)
                files[marker] = text.take(MAX_MARKER_CHARS)
            } catch (_: Exception) {
            }
        }
        return classify(rootLabel, entries, files)
    }

    /** Extracts include(":app") / include(":app", ":lib") module names. */
    fun gradleModules(settings: String): List<String> {
        if (settings.isBlankJava()) {
            return emptyList()
        }
        val out = linkedSetOf<String>()
        for (match in Regex("include\\s*\\(([^)]*)\\)").findAll(settings)) {
            for (quoted in Regex("[:\"']([^\"']+)[\"']").findAll(match.groupValues[1])) {
                val name = quoted.groupValues[1].removePrefix(":")
                if (name.isNotEmpty() && name.none { it <= ' ' }) {
                    out.add(name)
                }
            }
        }
        return out.sorted()
    }

    /**
     * True when package.json declares a test script.
     *
     * Parsed rather than searched: the scripts object is located first and the
     * key looked up inside it, because a plain substring search matched ANY
     * field of that name - a devDependency, a config block, a documentation
     * string - and the planner then promised to run the package manager's test
     * command on a project that has no test script at all.
     */
    fun hasTestScript(packageJson: String): Boolean {
        return try {
            val scripts = JSONObject(packageJson).optJSONObject("scripts")
            val test = scripts?.opt("test")
            test is String && !test.isBlankJava()
        } catch (_: Exception) {
            false
        }
    }

    private fun wrapperPath(present: Set<String>): String? = when {
        "gradlew" in present -> "gradlew"
        "gradlew.bat" in present -> "gradlew.bat"
        else -> null
    }

    private fun normalizedOf(name: String): String = name.replace('\\', '/').trim('/')

    private const val MAX_ENTRIES = 400
    private const val MAX_MARKER_CHARS = 200_000
}
