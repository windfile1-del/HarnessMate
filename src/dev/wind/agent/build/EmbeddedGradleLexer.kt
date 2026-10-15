package dev.wind.agent.build

/**
 * The lexer used by the B1.1 bounded Gradle grammar.  It is deliberately not
 * a Gradle evaluator: it only preserves token boundaries, source offsets and
 * dialect-specific lexical rules so the model parser can prove that it did
 * not silently skip executable syntax.
 */
internal enum class EmbeddedScriptDialect {
    KOTLIN,
    GROOVY
}

internal enum class EmbeddedTokenKind {
    IDENTIFIER,
    NUMBER,
    STRING,
    SYMBOL,
    NEWLINE,
    SEMICOLON,
    COMMENT
}

internal data class EmbeddedGradleToken(
    val kind: EmbeddedTokenKind,
    val text: String,
    val start: Int,
    val end: Int,
    val triple: Boolean = false
)

internal data class EmbeddedLexResult(
    val tokens: List<EmbeddedGradleToken>,
    val complete: Boolean,
    val unsupportedStringForm: Boolean,
    val unsupportedCommentForm: Boolean
)

internal class EmbeddedGradleLexer(
    private val dialect: EmbeddedScriptDialect
) {
    fun lex(script: String): EmbeddedLexResult {
        val tokens = mutableListOf<EmbeddedGradleToken>()
        var index = 0
        var complete = true
        var unsupportedStringForm = false
        var unsupportedCommentForm = false

        fun add(kind: EmbeddedTokenKind, start: Int, end: Int, triple: Boolean = false) {
            tokens += EmbeddedGradleToken(kind, script.substring(start, end), start, end, triple)
        }

        while (index < script.length) {
            val char = script[index]
            when {
                char == ' ' || char == '\t' || char == '\u000B' || char == '\u000C' -> index++
                char == '\r' -> {
                    val start = index++
                    if (index < script.length && script[index] == '\n') index++
                    add(EmbeddedTokenKind.NEWLINE, start, index)
                }
                char == '\n' -> {
                    val start = index++
                    add(EmbeddedTokenKind.NEWLINE, start, index)
                }
                char == ';' -> {
                    val start = index++
                    add(EmbeddedTokenKind.SEMICOLON, start, index)
                }
                script.startsWith("//", index) -> {
                    val start = index
                    index += 2
                    while (index < script.length && script[index] != '\n' && script[index] != '\r') index++
                    add(EmbeddedTokenKind.COMMENT, start, index)
                }
                script.startsWith("/*", index) -> {
                    val start = index
                    index += 2
                    var depth = 1
                    while (index < script.length && depth > 0) {
                        when {
                            dialect == EmbeddedScriptDialect.KOTLIN && script.startsWith("/*", index) -> {
                                depth++
                                index += 2
                            }
                            dialect == EmbeddedScriptDialect.GROOVY && script.startsWith("/*", index) -> {
                                // Groovy block comments are non-nested. A
                                // nested opener is outside the bounded grammar
                                // even though the first */ would end the
                                // lexical comment in the real language.
                                unsupportedCommentForm = true
                                index += 2
                            }
                            script.startsWith("*/", index) -> {
                                depth--
                                index += 2
                            }
                            else -> index++
                        }
                    }
                    if (depth > 0) complete = false
                    add(EmbeddedTokenKind.COMMENT, start, index)
                }
                dialect == EmbeddedScriptDialect.GROOVY && script.startsWith("$/", index) -> {
                    val start = index
                    index += 2
                    var escaped = false
                    var closed = false
                    while (index < script.length) {
                        val c = script[index++]
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (script.startsWith("/$", index - 1)) {
                            index++
                            closed = true
                            break
                        }
                    }
                    if (!closed) complete = false
                    unsupportedStringForm = true
                    add(EmbeddedTokenKind.STRING, start, index)
                }
                dialect == EmbeddedScriptDialect.GROOVY && char == '/' &&
                    index + 1 < script.length && script[index + 1] != '/' && script[index + 1] != '*' -> {
                    val start = index++
                    var escaped = false
                    var closed = false
                    while (index < script.length) {
                        val c = script[index++]
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == '/') {
                            closed = true
                            break
                        }
                    }
                    if (!closed) complete = false
                    unsupportedStringForm = true
                    add(EmbeddedTokenKind.STRING, start, index)
                }
                script.startsWith("\"\"\"", index) ||
                    (dialect == EmbeddedScriptDialect.GROOVY && script.startsWith("'''", index)) -> {
                    val start = index
                    val quote = script.substring(index, index + 3)
                    index += 3
                    var closed = false
                    while (index < script.length) {
                        if (script.startsWith(quote, index)) {
                            index += 3
                            closed = true
                            break
                        }
                        index++
                    }
                    if (!closed) complete = false
                    add(EmbeddedTokenKind.STRING, start, index, triple = true)
                }
                char == '\'' || char == '"' -> {
                    val start = index++
                    val quote = char
                    var escaped = false
                    var closed = false
                    while (index < script.length) {
                        val c = script[index++]
                        if (c == '\n' || c == '\r') complete = false
                        if (escaped) escaped = false
                        else if (c == '\\') escaped = true
                        else if (c == quote) {
                            closed = true
                            break
                        }
                    }
                    if (!closed) complete = false
                    add(EmbeddedTokenKind.STRING, start, index)
                }
                char.isLetter() || char == '_' -> {
                    val start = index++
                    while (index < script.length &&
                        (script[index].isLetterOrDigit() || script[index] == '_' || script[index] == '$')
                    ) index++
                    add(EmbeddedTokenKind.IDENTIFIER, start, index)
                }
                char.isDigit() -> {
                    val start = index++
                    while (index < script.length && (script[index].isDigit() || script[index] == '.')) index++
                    add(EmbeddedTokenKind.NUMBER, start, index)
                }
                else -> {
                    val start = index++
                    add(EmbeddedTokenKind.SYMBOL, start, index)
                }
            }
        }
        return EmbeddedLexResult(tokens, complete, unsupportedStringForm, unsupportedCommentForm)
    }
}
