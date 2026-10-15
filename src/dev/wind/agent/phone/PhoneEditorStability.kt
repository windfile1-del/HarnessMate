package dev.wind.agent.phone

/** Bounded grace plus stable editor/panel geometry. Visibility alone is insufficient. */
internal class PhoneEditorStability(private val startedAt: Long) {
    private var prior: String? = null
    private var equalSince = startedAt
    fun ready(now: Long, signature: String, hasEditor: Boolean, quietMs: Long): Boolean {
        if (signature != prior) { prior = signature; equalSince = now }
        return hasEditor && now - startedAt >= 450 && now - equalSince >= 200 && quietMs >= 200
    }
    fun expired(now: Long) = now - startedAt >= 1200
}
