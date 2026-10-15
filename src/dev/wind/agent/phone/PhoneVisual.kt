package dev.wind.agent.phone

/** Screenshot-derived anchors only. Bounds are always full-screen normalized coordinates. */
internal object PhoneVisual {
    data class Target(val id: String, val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
        val confidence: Float) {
        val x get() = (left + right) / 2
        val y get() = (top + bottom) / 2
        fun facts() = NodeFacts(listOf(id.removePrefix("v").toIntOrNull() ?: 0), "ocr_text", "", text, "",
            left, top, right, bottom, true, false, false)
    }
    fun normalized(id: String, text: String, box: IntArray, width: Int, height: Int, confidence: Float): Target? {
        if (box.size != 4 || width <= 0 || height <= 0 || !confidence.isFinite() || confidence < 55f || text.isBlank()) return null
        fun x(n: Int) = (n.toLong() * 1000 / width).toInt().coerceIn(0, 1000)
        fun y(n: Int) = (n.toLong() * 1000 / height).toInt().coerceIn(0, 1000)
        val cleaned = text.trim().replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
        val target = Target(id, cleaned.take(350), x(box[0]), y(box[1]), x(box[2]), y(box[3]), confidence)
        return target.takeIf { it.right > it.left && it.bottom > it.top }
    }
    fun key(text: String) = text.lowercase().filterNot { it.isWhitespace() }
    fun resolve(old: Target, live: List<Target>): Target? {
        // Identity must be unique across the whole captured screen, even when another match is far away.
        val same = live.filter { key(it.text) == key(old.text) }
        return same.singleOrNull()?.takeIf { PhoneNodeMatcher.iou(old.facts(), it.facts()) >= 0.5 }
    }
    fun interruptions(targets: List<Target>) = PhoneInterruptions.detect(targets.map { it.facts() }, false)
        .mapNotNull { candidate -> targets.firstOrNull { it.facts().path == candidate.node.path } }
    fun queryMatches(target: Target, text: String, exact: Boolean) =
        if (exact) key(target.text) == key(text) else key(target.text).contains(key(text))
}
