package dev.wind.agent.intelligence.lsp

/** Explicit result channel for LSP lifecycle and requests. */
enum class LspFailureCode {
    UNAVAILABLE,
    START_FAILED,
    INITIALIZE_FAILED,
    TIMEOUT,
    CRASHED,
    CANCELLED,
    PROTOCOL_INVALID,
    PROTOCOL_LIMIT,
    PATH_REJECTED,
    RESTART_BUDGET_EXHAUSTED
}

sealed class LspOutcome<out T> {
    data class Success<T>(val value: T) : LspOutcome<T>()
    data class Failure(
        val code: LspFailureCode,
        val detail: String = ""
    ) : LspOutcome<Nothing>()

    fun valueOrNull(): T? = (this as? Success<T>)?.value
}

/** Stable identity for one P3 root/task/language execution session. */
data class TrustedLspSessionId(val value: String) {
    init {
        require(value.length == 32 && value.all { it in "0123456789abcdef" }) {
            "LSP_SESSION_ID_INVALID"
        }
    }

    companion object {
        fun forKey(key: LspSessionKey): TrustedLspSessionId {
            val semantic = listOf(key.rootTaskId, key.projectId, key.rootGeneration, key.language.identifier, key.executionPath)
                .joinToString("\u0000")
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(semantic.toByteArray(Charsets.UTF_8))
            val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return TrustedLspSessionId(hex.take(32))
        }
    }
}

/** Lease revalidation immediately before dispatching work into the bound runtime. */
fun interface ExecutionBoundaryVerifier {
    fun verify()
}

