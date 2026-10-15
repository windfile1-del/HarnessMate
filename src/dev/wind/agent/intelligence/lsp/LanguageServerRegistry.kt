package dev.wind.agent.intelligence.lsp

import dev.wind.agent.intelligence.CodeIntelLimits
import dev.wind.agent.intelligence.CodeLanguage

data class LanguageServerDescriptor(
    val id: String,
    val language: CodeLanguage,
    val enabled: Boolean,
    val startTimeoutMs: Long = CodeIntelLimits.LSP_START_TIMEOUT_MS,
    val requestTimeoutMs: Long = CodeIntelLimits.LSP_DEFINITION_TIMEOUT_MS
)

object LanguageServerRegistry {
    private val allowlist = mapOf(
        CodeLanguage.KOTLIN to LanguageServerDescriptor("kotlin", CodeLanguage.KOTLIN, enabled = true,
            startTimeoutMs = 60000L, requestTimeoutMs = 20000L),
        CodeLanguage.JAVA to LanguageServerDescriptor("java", CodeLanguage.JAVA, enabled = true,
            startTimeoutMs = 60000L, requestTimeoutMs = 20000L),
        CodeLanguage.PYTHON to LanguageServerDescriptor("python", CodeLanguage.PYTHON, enabled = true),
        CodeLanguage.TYPESCRIPT to LanguageServerDescriptor("typescript", CodeLanguage.TYPESCRIPT, enabled = true),
        CodeLanguage.JAVASCRIPT to LanguageServerDescriptor("javascript", CodeLanguage.JAVASCRIPT, enabled = true)
    )

    fun getDescriptor(language: CodeLanguage): LanguageServerDescriptor? {
        return allowlist[language]
    }

    fun isLanguageSupported(language: CodeLanguage): Boolean {
        return allowlist[language]?.enabled == true
    }
}
