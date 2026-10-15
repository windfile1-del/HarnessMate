package dev.wind.agent.phone

internal object PhoneNodeMatcher {
    enum class Tier { EXACT, RELAXED, RELOCATED, POINT }
    data class Match(val tier: Tier, val node: NodeFacts)
    fun iou(a: NodeFacts, b: NodeFacts): Double {
        val width = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val height = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (width <= 0 || height <= 0) return 0.0
        val intersection = width.toLong() * height
        val union = a.area + b.area - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union
    }
    fun relaxed(old: NodeFacts, now: NodeFacts): Boolean = old.actionable && now.actionable &&
        old.cls == now.cls && old.resId == now.resId && old.sameFlags(now) && iou(old, now) >= if (old.anonymous) 0.85 else 0.6
    private fun identity(old: NodeFacts, now: NodeFacts) = when {
        old.text.isNotBlank() -> old.text == now.text
        old.desc.isNotBlank() -> old.desc == now.desc
        old.resId.isNotBlank() -> true
        else -> false
    }
    fun resolve(target: NodeFacts, live: List<NodeFacts>): Match? {
        if (!target.actionable) return null
        val path = live.firstOrNull { it.path == target.path }
        if (path != null && path == target && path.actionable) return Match(Tier.EXACT, path)
        if (path != null && relaxed(target, path)) return Match(Tier.RELAXED, path)
        if (!target.anonymous) {
            val matches = live.filter { it.actionable && it.cls == target.cls && it.resId == target.resId && target.sameFlags(it) && identity(target, it) }
            if (matches.size == 1) return Match(Tier.RELOCATED, matches.single())
            // Ambiguous stable identity never falls back to an arbitrary overlapping item.
            if (matches.size > 1) return null
        }
        return live.filter { it.contains(target.centerX, target.centerY) }.sortedBy { it.area }.take(4)
            .firstOrNull { relaxed(target, it) }?.let { Match(Tier.POINT, it) }
    }
    fun deepestAt(nodes: List<NodeFacts>, x: Int, y: Int): NodeFacts? = nodes.filter { it.visible && it.contains(x, y) }
        .minWithOrNull(compareBy<NodeFacts> { it.area }.thenByDescending { it.path.size })
    fun clickableOwner(node: NodeFacts, nodes: List<NodeFacts>): NodeFacts? = if (node.clickable && node.actionable) node
        else nodes.filter { it.clickable && it.actionable && it.isAncestorOf(node) }.maxByOrNull { it.path.size }
    fun samePointTarget(old: NodeFacts, oldNodes: List<NodeFacts>, now: NodeFacts, nodes: List<NodeFacts>): Boolean =
        relaxed(clickableOwner(old, oldNodes) ?: old, clickableOwner(now, nodes) ?: now)
}
