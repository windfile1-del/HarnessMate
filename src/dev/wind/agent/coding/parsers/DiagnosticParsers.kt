package dev.wind.agent.coding.parsers

import dev.wind.agent.coding.Diagnostic
import dev.wind.agent.coding.DiagnosticCategory
import dev.wind.agent.coding.DiagnosticPathMapper
import dev.wind.agent.coding.DiagnosticSeverity
import dev.wind.agent.coding.DiagnosticSource
import dev.wind.agent.trimJavaOrEmpty

/**
 * Language-specific readers for compiler and test output.
 *
 * Every parser is a pure regex over text: no file system, no state, no model.
 * That is what makes "does the harness understand this error" a unit test rather
 * than a device experiment, and it is also why the generic fallback exists —
 * output nobody recognises still has to arrive, bounded, instead of vanishing.
 */
object Diagnostics {

    private val KOTLIN = Regex("^([a-z]:)\\s+(.+?\\.kt):(\\d+):(\\d+)\\s+(.*)$")
    private val KOTLIN_FILE_URI = Regex("^([a-z]:)\\s+file://(.+?\\.kt):(\\d+):(\\d+)\\s+(.*)$")
    private val KOTLIN_POSITION = Regex("^([a-z]:)\\s+(?:file://)?(.+?\\.kt):?\\s*\\((\\d+),\\s*(\\d+)\\):?\\s+(.*)$")
    private val JAVAC = Regex("^(\\S+?\\.java):(\\d+):\\s*(error|warning):\\s*(.*)$")
    private val GRADLE_FAILURE = Regex("^(?:FAILURE|What went wrong|Execution failed)[:\\s]*(.*)$")
    private val GRADLE_TASK = Regex("^> Task (.+) FAILED$")
    private val GRADLE_DEPENDENCY = Regex("Could not (?:resolve|find|download) (?:all )?(?:files|dependencies|artifact)[^\\n]*", RegexOption.IGNORE_CASE)
    private val TEST_JUNIT = Regex("^(\\S+?)\\s*>\\s*(\\S+?)\\s+FAILED\\s*$")
    private val TEST_PYTEST = Regex("^(?:FAILED|ERROR)\\s+(\\S+?)(?:::\\S+)?\\s+-\\s+(.*)$")
    private val TEST_GENERIC = Regex("^(?:FAIL|FAILED|ERROR|not ok)\\b[:\\s]*(.*)$")
    private val TOOLCHAIN = Regex(
        "(command not found|no such file or directory|is not recognized|could not determine java|" +
            "JAVA_HOME|ANDROID_HOME|SDK location not found|unable to locate|not installed|" +
            "permission denied|EACCES|Operation not permitted|network is unreachable|" +
            "could not resolve host|Connection refused|timed out|Timeout waiting|R2_JVM_|R2_GRADLE_VERSION_|R2_GRADLE_DISTRIBUTION_)",
        RegexOption.IGNORE_CASE
    )

    /** Kotlin: "e: file:///path/Foo.kt:42:17 Unresolved reference: bar". */
    fun parseKotlin(line: String): Diagnostic? {
        val match = KOTLIN_FILE_URI.matchEntire(line) ?: KOTLIN.matchEntire(line) ?: KOTLIN_POSITION.matchEntire(line) ?: return null
        val severity = if (match.groupValues[1] == "w:") DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR
        return Diagnostic(
            id = "kotlin:" + match.groupValues[3] + ":" + match.groupValues[4],
            severity = severity,
            category = DiagnosticCategory.COMPILE,
            path = DiagnosticPathMapper.map(match.groupValues[2]),
            line = match.groupValues[3].toIntOrNull(),
            column = match.groupValues[4].toIntOrNull(),
            code = null,
            // The message is free text a compiler wrote, and a compiler is quite
            // capable of naming a second device path inside it. Scrubbed for the
            // same reason the Gradle and Generic parsers are: one mapped path
            // field is not the only way a device layout can reach the model.
            message = DiagnosticPathMapper.sanitizeText(match.groupValues[5].trimJavaOrEmpty()),
            source = DiagnosticSource.KOTLIN
        )
    }

    /** javac: "/path/Foo.java:42: error: cannot find symbol". */
    fun parseJavac(line: String): Diagnostic? {
        val match = JAVAC.matchEntire(line) ?: return null
        return Diagnostic(
            id = "javac:" + match.groupValues[2],
            severity = if (match.groupValues[3] == "warning") DiagnosticSeverity.WARNING else DiagnosticSeverity.ERROR,
            category = DiagnosticCategory.COMPILE,
            path = DiagnosticPathMapper.map(match.groupValues[1]),
            line = match.groupValues[2].toIntOrNull(),
            column = null,
            code = null,
            message = DiagnosticPathMapper.sanitizeText(match.groupValues[4].trimJavaOrEmpty()),
            source = DiagnosticSource.JAVAC
        )
    }

    /** JUnit: "FooTest > shouldCreateUser FAILED". */
    fun parseTest(line: String): Diagnostic? {
        TEST_JUNIT.matchEntire(line)?.let { match ->
            return Diagnostic(
                id = "test:" + match.groupValues[1] + "." + match.groupValues[2],
                severity = DiagnosticSeverity.ERROR,
                category = DiagnosticCategory.TEST_FAILURE,
                path = DiagnosticPathMapper.map(match.groupValues[1]),
                line = null,
                column = null,
                code = match.groupValues[2],
                message = "test failed: " + match.groupValues[1] + "." + match.groupValues[2],
                source = DiagnosticSource.TEST
            )
        }
        TEST_PYTEST.matchEntire(line)?.let { match ->
            return Diagnostic(
                id = "pytest:" + match.groupValues[1],
                severity = DiagnosticSeverity.ERROR,
                category = DiagnosticCategory.TEST_FAILURE,
                path = DiagnosticPathMapper.map(match.groupValues[1]),
                line = null,
                column = null,
                code = null,
                message = DiagnosticPathMapper.sanitizeText(
                    match.groupValues[2].trimJavaOrEmpty().ifEmpty { "test failed" }
                ),
                source = DiagnosticSource.TEST
            )
        }
        return null
    }

    /** Gradle task/dependency failures that are not attached to a source file. */
    fun parseGradle(line: String): Diagnostic? {
        GRADLE_TASK.matchEntire(line)?.let { match ->
            return Diagnostic(
                id = "gradle-task:" + match.groupValues[1],
                severity = DiagnosticSeverity.ERROR,
                category = categoryOf(line),
                path = null,
                line = null,
                column = null,
                code = match.groupValues[1],
                message = "task failed: " + match.groupValues[1],
                source = DiagnosticSource.GRADLE
            )
        }
        if (GRADLE_DEPENDENCY.containsMatchIn(line)) {
            return Diagnostic(
                id = "gradle-dep:" + Digests.short(line),
                severity = DiagnosticSeverity.ERROR,
                category = DiagnosticCategory.DEPENDENCY,
                path = null,
                line = null,
                column = null,
                code = null,
                message = DiagnosticPathMapper.sanitizeText(line.trimJavaOrEmpty()),
                source = DiagnosticSource.GRADLE
            )
        }
        GRADLE_FAILURE.matchEntire(line)?.let { match ->
            val detail = match.groupValues[1].trimJavaOrEmpty()
            if (detail.isEmpty()) {
                return null
            }
            // Dependency and failure text is free-form build output, so it is
            // scrubbed: it is exactly where a device path turns up as prose
            // rather than as a structured path field.
            return Diagnostic(
                id = "gradle:" + Digests.short(detail),
                severity = DiagnosticSeverity.ERROR,
                category = categoryOf(detail),
                path = null,
                line = null,
                column = null,
                code = null,
                message = DiagnosticPathMapper.sanitizeText(detail),
                source = DiagnosticSource.GRADLE
            )
        }
        return null
    }

    /**
     * The last resort: keep the line, classify it as well as it can be done.
     *
     * Only lines that look like failures are kept, so ordinary progress output
     * does not turn into a hundred "diagnostics" the model is then asked to fix.
     */
    fun parseGeneric(line: String): Diagnostic? {
        val trimmed = line.trimJavaOrEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        TEST_GENERIC.matchEntire(trimmed)?.let { match ->
            val rawDetail = match.groupValues[1].trimJavaOrEmpty().ifEmpty { trimmed }
            return Diagnostic(
                id = "generic:" + Digests.short(trimmed),
                severity = DiagnosticSeverity.ERROR,
                category = categoryOf(trimmed),
                path = null,
                line = null,
                column = null,
                code = null,
                message = DiagnosticPathMapper.sanitizeText(rawDetail),
                source = DiagnosticSource.GENERIC
            )
        }
        return null
    }

    /**
     * Classifies an environment failure that the model must NOT try to fix in
     * source: a missing toolchain, a denied path, a blocked download. Getting
     * this wrong is what sends a model off editing correct code because Gradle
     * could not be executed.
     */
    fun categoryOf(text: String): DiagnosticCategory = when {
        TOOLCHAIN.containsMatchIn(text) -> DiagnosticCategory.TOOLCHAIN
        text.contains("Could not resolve", true) || text.contains("Could not find", true) -> DiagnosticCategory.DEPENDENCY
        text.contains("could not compile", true) || text.contains("Compilation error", true) -> DiagnosticCategory.COMPILE
        text.contains("What went wrong", true) || text.contains("A problem occurred", true) -> DiagnosticCategory.BUILD_CONFIG
        else -> DiagnosticCategory.UNKNOWN
    }

    internal object Digests {
        fun short(value: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(value.toByteArray(Charsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
