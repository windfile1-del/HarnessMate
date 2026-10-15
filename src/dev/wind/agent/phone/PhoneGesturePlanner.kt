package dev.wind.agent.phone

internal object PhoneGesturePlanner {
    data class Plan(val startX: Float, val startY: Float, val endX: Float, val endY: Float,
        val durationMs: Long, val holdMs: Long, val continued: Boolean)
    fun scroll(width: Int, height: Int, direction: String, amount: String, bounds: NodeFacts?, sdk: Int): Plan {
        require(width > 0 && height > 0 && direction in setOf("up", "down", "left", "right")) { "PHONE_SCROLL_DIRECTION_INVALID" }
        val ratio = when (amount) { "small" -> 0.25f; "half" -> 0.5f; "page" -> 0.8f; else -> error("PHONE_SCROLL_AMOUNT_INVALID") }
        val left = (bounds?.left ?: 0).coerceIn(0, width - 1).toFloat()
        val right = (bounds?.right ?: width).coerceIn(1, width).toFloat()
        val top = (bounds?.top ?: (height * 0.12f).toInt()).coerceIn(0, height - 1).toFloat()
        val bottom = (bounds?.bottom ?: (height * 0.9f).toInt()).coerceIn(1, height).toFloat()
        require(right - left >= 20 && bottom - top >= 20) { "PHONE_SCROLL_REGION_TOO_SMALL" }
        val horizontal = direction == "left" || direction == "right"
        val cx = (left + right) / 2; val cy = (top + bottom) / 2
        val distance = (if (horizontal) right - left else bottom - top) * ratio
        val sign = if (direction == "down" || direction == "right") 1 else -1
        return Plan(if (horizontal) cx + sign * distance / 2 else cx,
            if (horizontal) cy else cy + sign * distance / 2,
            if (horizontal) cx - sign * distance / 2 else cx,
            if (horizontal) cy else cy - sign * distance / 2,
            if (sdk >= 26) 650 else 900, if (sdk >= 26) 120 else 0, sdk >= 26)
    }
}
