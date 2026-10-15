package dev.wind.agent.phone

internal object PhoneSettle {
    fun fingerprint(nodes: List<NodeFacts>, excluded: Set<String> = emptySet()): Int = nodes
        .filter { it.visible && (it.clickable || it.editable || it.scrollable || it.text.isNotBlank()) && it.pathKey !in excluded }
        .map { "${it.pathKey}|${it.cls}|${it.resId}|${it.left},${it.top},${it.right},${it.bottom}" }.sorted().hashCode()
    class LayoutStability {
        private val samples = ArrayList<List<NodeFacts>>()
        private var previous: Int? = null
        var hotSpots: Set<String> = emptySet(); private set
        fun sample(nodes: List<NodeFacts>): Boolean {
            samples.add(nodes); while (samples.size > 3) samples.removeAt(0)
            if (samples.size == 3) {
                val maps = samples.map { it.associateBy { n -> n.pathKey } }
                hotSpots = maps[0].keys.filter { key ->
                    val values = maps.mapNotNull { it[key] }
                    values.size == 3 && (0..1).count { i ->
                        val a = values[i]; val b = values[i + 1]
                        listOf(a.left, a.top, a.right, a.bottom) != listOf(b.left, b.top, b.right, b.bottom)
                    } >= 2
                }.toSet()
                return fingerprint(samples[1], hotSpots) == fingerprint(samples[2], hotSpots)
            }
            val next = fingerprint(nodes); val stable = previous == next; previous = next; return stable
        }
    }
    data class Movement(val movedPx: Int?, val reachedEnd: Boolean?)
    fun movement(before: List<NodeFacts>, after: List<NodeFacts>, horizontal: Boolean): Movement {
        fun key(n: NodeFacts) = "${n.resId}|${n.text}|${n.desc}"
        val a = before.filter { it.resId.isNotBlank() && (it.text.isNotBlank() || it.desc.isNotBlank()) }.groupBy(::key)
        val b = after.groupBy(::key)
        val shifts = a.mapNotNull { (key, old) -> if (old.size == 1 && b[key]?.size == 1) {
            val next = b.getValue(key).single(); if (horizontal) next.centerX - old.single().centerX else next.centerY - old.single().centerY
        } else null }.sorted()
        if (shifts.size < 2) return Movement(null, null)
        val median = shifts[shifts.size / 2]
        return Movement(median, kotlin.math.abs(median) <= 3)
    }
}
