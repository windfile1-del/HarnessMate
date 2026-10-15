package dev.wind.agent.phone

internal object PhoneFreshness {
    const val TTL_MS = 90_000L
    data class Stamp(val owner: String, val pkg: String, val windowId: Int, val width: Int, val height: Int,
        val structural: Long, val content: Long, val at: Long, val rotation: Int = 0)
    sealed class Verdict {
        object Fresh : Verdict()
        data class Drifted(val contentEvents: Long) : Verdict()
        data class Stale(val reason: String, val detail: String) : Verdict()
    }
    fun evaluate(old: Stamp, now: Stamp, strict: Boolean = false): Verdict = when {
        old.owner != now.owner -> Verdict.Stale("owner", "observation belongs to another task")
        now.at < old.at || now.at - old.at > TTL_MS -> Verdict.Stale("expired", "age=${now.at - old.at}ms limit=${TTL_MS}ms")
        old.pkg != now.pkg -> Verdict.Stale("package_changed", "observed=${old.pkg} now=${now.pkg}")
        old.windowId != now.windowId -> Verdict.Stale("window_changed", "observed=#${old.windowId} now=#${now.windowId}")
        old.width != now.width || old.height != now.height || old.rotation != now.rotation -> Verdict.Stale("display_changed", "observed=${old.width}x${old.height}@${old.rotation} now=${now.width}x${now.height}@${now.rotation}")
        old.structural != now.structural -> Verdict.Stale("structural_change", "${now.structural - old.structural} window transition(s)")
        old.content != now.content -> if (strict) Verdict.Stale("content_changed", "strict freshness; ${now.content - old.content} event(s)")
            else Verdict.Drifted(now.content - old.content)
        else -> Verdict.Fresh
    }
}
