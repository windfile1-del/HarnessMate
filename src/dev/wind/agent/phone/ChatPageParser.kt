package dev.wind.agent.phone

/** UI and OCR converge here; geometry is normalized to the whole display. */
internal object ChatPageParser {
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float = 100f)
    data class Turn(val role: String, val text: String) { val key get() = role + ":" + PhoneVisual.key(text) }
    fun titleMatches(lines: List<Line>, title: String): Boolean = lines.count {
        it.top in 20..225 && it.confidence >= 65 && PhoneVisual.key(it.text) == PhoneVisual.key(title)
    } == 1
    private fun decoration(text: String) = text in setOf("发送", "Send", "按住说话", "以下是新消息", "查看更多消息", "你已添加了", "正在输入") ||
        Regex("^(?:上午|下午|晚上|早上|昨天|今天|星期[一二三四五六日天])?\\s*\\d{1,2}:\\d{2}$|^\\d{1,4}[年/-]\\d{1,2}[月/-]\\d{1,2}日?$").matches(text)
    fun turns(lines: List<Line>, title: String, composerY: Int, verifiedHeaderBottom: Int?=null, mergeAdjacentLines: Boolean=true): List<Turn> {
        val headerBottom = verifiedHeaderBottom ?: lines.filter { it.top <= 225 && PhoneVisual.key(it.text) == PhoneVisual.key(title) }.maxOfOrNull { it.bottom } ?: 160
        val body = lines.filter { it.top >= headerBottom + 25 && it.bottom < composerY - 30 && it.confidence >= 60 &&
            it.text.isNotBlank() && !decoration(it.text.trim()) }.sortedWith(compareBy<Line> { it.top }.thenBy { it.left })
        val out = mutableListOf<Turn>()
        var previous: Line? = null
        for (line in body) {
            // Full-width labels are not evidence of who sent a message.
            val role = when {
                line.left in 45..185 && line.right < 860 -> "peer"
                line.left >= 190 && line.right > 350 -> "self"
                else -> "unknown"
            }
            val before = previous
            if (mergeAdjacentLines && before != null && out.isNotEmpty() && role == out.last().role && line.top - before.bottom in -3..20 && kotlin.math.abs(line.left - before.left) <= 35) {
                val last = out.removeAt(out.lastIndex)
                out.add(Turn(role, (last.text + "\n" + line.text.trim()).take(4000)))
            } else out.add(Turn(role, line.text.trim().take(4000)))
            previous = line
        }
        return out.takeLast(30)
    }
    fun incoming(previous: List<Turn>,current: List<Turn>,seed: Boolean,emptyBaselinePending: Boolean): List<Turn> =
        if(seed) emptyList() else if(previous.isEmpty() && emptyBaselinePending) current.filter { it.role=="peer" } else appended(previous,current)
    /** Only append changes trigger work; entering a page or scrolling to an unrelated slice seeds a baseline. */
    fun appended(previous: List<Turn>, current: List<Turn>): List<Turn> {
        if (previous.isEmpty() || current.isEmpty() || previous.map { it.key } == current.map { it.key }) return emptyList()
        val max = minOf(previous.size, current.size)
        val overlap = (max downTo 1).firstOrNull { previous.takeLast(it).map { t -> t.key } == current.take(it).map { t -> t.key } } ?: return emptyList()
        return current.drop(overlap).filter { it.role == "peer" }
    }
}
