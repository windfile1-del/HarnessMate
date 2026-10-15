package dev.wind.agent.intelligence.lsp

/**
 * Uniform lifecycle wrapper for every manager-level LSP operation.  The guard
 * begins after P3 has established a stable revision and the final live digest
 * comparison runs for success, failure, timeout, crash, cancellation and
 * exceptions alike.
 */
class GuardedLspOperation<T>(
    private val guard: LspMutationGuard,
    private val digestBefore: String?,
    private val onMutation: () -> Unit
) {
    sealed class Result<out T> {
        data class Value<T>(val value: T) : Result<T>()
        data class Mutated(val decision: LspMutationGuard.Decision) : Result<Nothing>()
    }

    fun run(block: () -> T): Result<T> {
        return try {
            val value = block()
            finish(value)
        } catch (error: Throwable) {
            // The final check is deliberately performed before rethrowing, so
            // a bridge/start exception cannot bypass the source-mutation fuse.
            val decision = guard.finish(digestBefore)
            if (decision.mutated) onMutation()
            throw error
        }
    }

    private fun finish(value: T): Result<T> {
        val decision = guard.finish(digestBefore)
        if (decision.mutated) {
            onMutation()
            return Result.Mutated(decision)
        }
        return Result.Value(value)
    }
}

