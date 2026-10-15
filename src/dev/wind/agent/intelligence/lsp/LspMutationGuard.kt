package dev.wind.agent.intelligence.lsp

/**
 * The LSP source-mutation invariant, isolated so it can be tested directly.
 *
 * A language server is a tool, not an agent: it is allowed to read the mirror
 * and never to change it. Any change to the live mirror digest across the
 * window that starts before the server is contacted and ends after it replied —
 * including windows that ended in timeout, crash, or no session at all — means
 * the harness cannot prove the code it is about to describe is the code that is
 * there, so the whole interaction fails closed.
 *
 * An unknown digest cannot prove anything either: the guard reports mutation,
 * which is the fail-closed reading.
 */
class LspMutationGuard(private val liveDigest: () -> String?) {

    data class Decision(
        val mutated: Boolean,
        val digestBefore: String?,
        val digestAfter: String?
    )

    /** Opens the window. Returns null when the digest is unreadable. */
    fun begin(): String? = liveDigest()

    fun finish(digestBefore: String?): Decision {
        val digestAfter = liveDigest()
        val mutated = when {
            digestBefore == null -> true
            digestAfter == null -> true
            else -> digestBefore != digestAfter
        }
        return Decision(mutated = mutated, digestBefore = digestBefore, digestAfter = digestAfter)
    }
}
