package dev.wind.agent.webprovider

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Read-only, explicit snapshot. Never caches events or performs actions/gestures. */
class WebScreenTextService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun readVisible(): String {
        var chosen: AccessibilityNodeInfo? = null
        for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }) {
            val candidate = window.root ?: continue
            if (candidate.packageName?.toString() != packageName) { chosen = candidate; break }
            candidate.recycle()
        }
        val root = chosen ?: rootInActiveWindow ?: throw IllegalStateException("SCREEN_TEXT_UNAVAILABLE")
        if (root.packageName?.toString() == packageName) { root.recycle(); throw IllegalStateException("OPEN_TARGET_APP_FIRST") }
        val result = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var visited = 0; var length = 0
        try {
            while (queue.isNotEmpty() && visited++ < 2000 && length < 23000) {
                val node = queue.removeFirst()
                try {
                    val bounds = Rect(); node.getBoundsInScreen(bounds)
                    if (!node.isVisibleToUser || node.isPassword || bounds.isEmpty) continue
                    val visible = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    if (!Rect.intersects(bounds, visible)) continue
                    listOf(node.text, node.contentDescription).forEach { raw ->
                        val text = raw?.toString()?.trim()?.take(2000)
                        if (!text.isNullOrBlank() && result.add(text)) length += text.length + 1
                    }
                    for (i in 0 until minOf(node.childCount, 200)) node.getChild(i)?.let { queue.add(it) }
                } finally { node.recycle() }
            }
        } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
        return result.joinToString("\n").take(23000).ifBlank { throw IllegalStateException("SCREEN_TEXT_EMPTY_OR_NOT_EXPOSED") }
    }
    companion object {
        @Volatile private var instance: WebScreenTextService? = null
        fun capture(): String = instance?.readVisible() ?: throw IllegalStateException("ENABLE_READ_ONLY_SCREEN_TEXT_SERVICE")
    }
}
