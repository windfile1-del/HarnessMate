package dev.wind.agent.coding

import org.json.JSONArray
import org.json.JSONObject
import dev.wind.agent.isNotBlankJava

/** How much the detector trusts its own answer. */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW
}

/** The build systems P4 knows how to drive. */
enum class ProjectKind {
    ANDROID_GRADLE,
    JVM_GRADLE,
    MAVEN,
    NODE,
    PYTHON,
    RUST,
    GO,
    GENERIC
}

/**
 * What the harness found on disk, before any model was asked anything.
 *
 * The model is never the thing that decides how a project is built. It reads the
 * same files the detector does and can reason about them all it likes, but the
 * commands that actually run come from here, because "the model guessed wrong
 * about which build system this is" is a class of failure with no upside.
 */
data class ProjectProfile(
    val kind: ProjectKind,
    val root: String,
    val modules: List<String> = emptyList(),
    val buildSystem: String? = null,
    val wrapper: String? = null,
    val testFrameworkHints: List<String> = emptyList(),
    val confidence: Confidence = Confidence.LOW,
    val markers: List<String> = emptyList(),
    val androidApplication: Boolean = false,
    val androidLibrary: Boolean = false,
    val kotlinPlugin: Boolean = false
) {
    init {
        require(root.isNotBlankJava()) { "PROJECT_ROOT_MISSING" }
    }

    /** A short, deterministic one-line description for the model and the UI. */
    fun describe(): String {
        val sb = StringBuilder()
        sb.append(kind.name)
        if (modules.isNotEmpty()) {
            sb.append(" (").append(modules.size).append(" module(s): ")
                .append(modules.joinToString(", ")).append(')')
        }
        buildSystem?.let { sb.append(" build=").append(it) }
        wrapper?.let { sb.append(" wrapper=").append(it) }
        if (testFrameworkHints.isNotEmpty()) {
            sb.append(" tests=").append(testFrameworkHints.joinToString(","))
        }
        sb.append(" confidence=").append(confidence.name)
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("root", root)
        .put("modules", JSONArray().apply { modules.forEach { put(it) } })
        .put("buildSystem", buildSystem ?: JSONObject.NULL)
        .put("wrapper", wrapper ?: JSONObject.NULL)
        .put("testFrameworkHints", JSONArray().apply { testFrameworkHints.forEach { put(it) } })
        .put("confidence", confidence.name)
        .put("markers", JSONArray().apply { markers.forEach { put(it) } })
        .put("androidApplication", androidApplication)
        .put("androidLibrary", androidLibrary)
        .put("kotlinPlugin", kotlinPlugin)

    companion object {
        fun fromJson(root: JSONObject): ProjectProfile {
            fun strings(key: String): List<String> {
                val out = mutableListOf<String>()
                val array = root.optJSONArray(key)
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val value = array.optString(i, "")
                        if (value.isNotEmpty()) {
                            out.add(value)
                        }
                    }
                }
                return out
            }
            return ProjectProfile(
                kind = ProjectKind.valueOf(root.getString("kind")),
                root = root.getString("root"),
                modules = strings("modules"),
                buildSystem = root.optString("buildSystem", "").takeIf { it.isNotEmpty() },
                wrapper = root.optString("wrapper", "").takeIf { it.isNotEmpty() },
                testFrameworkHints = strings("testFrameworkHints"),
                confidence = Confidence.valueOf(root.optString("confidence", Confidence.LOW.name)),
                markers = strings("markers"),
                androidApplication = root.optBoolean("androidApplication", false),
                androidLibrary = root.optBoolean("androidLibrary", false),
                kotlinPlugin = root.optBoolean("kotlinPlugin", false)
            )
        }
    }
}
