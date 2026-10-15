package dev.wind.agent.phone

/** Tool-call snapshot values only: never retains Android nodes or Rects. */
internal data class NodeFacts(
    val path: List<Int>, val cls: String, val resId: String, val text: String, val desc: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val clickable: Boolean, val editable: Boolean, val scrollable: Boolean,
    val enabled: Boolean = true, val password: Boolean = false, val visible: Boolean = true
) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
    val area get() = maxOf(0, width).toLong() * maxOf(0, height)
    val pathKey get() = path.joinToString(".")
    val anonymous get() = resId.isBlank() && text.isBlank() && desc.isBlank()
    val actionable get() = visible && enabled && !password && area > 0
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    fun sameFlags(other: NodeFacts) = clickable == other.clickable && editable == other.editable && scrollable == other.scrollable
    fun isAncestorOf(other: NodeFacts) = path.size < other.path.size && other.path.take(path.size) == path
    fun brief() = "class=$cls id=$resId text=${text.take(60)} bounds=[$left,$top,$right,$bottom]"
}
