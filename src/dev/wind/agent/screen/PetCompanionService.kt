package dev.wind.agent.screen

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.ArrayDeque

/** Optional, event-driven animation hints. No gestures, screenshots, storage, networking or agent tools. */
class PetCompanionService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var pendingPackage = ""
    private var pendingWindowSwitch = false
    private var lastPackage = ""
    private var candidate: PetActivity.State? = null
    private var candidateCount = 0
    private val readHint = Runnable {
        scheduled = false
        if (!PetCompanion.shouldObserve()) { PetCompanion.clear(); return@Runnable }
        val unlocked = !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val awake = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!unlocked || !awake) { PetCompanion.clear(); return@Runnable }
        val pkg = pendingPackage
        val switched = pendingWindowSwitch
        pendingWindowSwitch = false
        val root = externalRoot(pkg)
        if (root == null) {
            // Secure/canvas interfaces can expose only the app identity. Do not retain another app's pose.
            if (switched && pkg != lastPackage) {
                lastPackage = pkg; candidate = null; candidateCount = 0
                PetCompanion.observe(PetCompanion.classify(pkg, ""))
            }
            return@Runnable
        }
        val hints = readMarkers(root)
        val next = PetCompanion.classify(pkg, hints)
        if (pkg != lastPackage) {
            lastPackage = pkg; candidate = next; candidateCount = 1
            PetCompanion.observe(next)
        } else {
            if (candidate == next) candidateCount = minOf(2, candidateCount + 1) else { candidate = next; candidateCount = 1 }
            // Avoid flickering between poses as a page loads or small player controls change.
            if (candidateCount >= 2) PetCompanion.observe(next)
        }
    }

    override fun onServiceConnected() {
        PetCompanion.initialize(this)
        PetCompanion.monitorConnected = true
        instance = this
        requestCurrentHint()
    }

    @Suppress("DEPRECATION")
    private fun requestVisibleHint() {
        if (!PetCompanion.shouldObserve()) return
        try {
            for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }) {
                val node = window.root ?: continue
                val pkg = node.packageName?.toString().orEmpty()
                node.recycle()
                if (pkg.isNotEmpty() && pkg != packageName) {
                    pendingPackage = pkg; lastPackage = ""; candidateCount = 0
                    pendingWindowSwitch = true
                    if (!scheduled) { scheduled = true; main.postDelayed(readHint, 750L) }
                    return
                }
            }
        } catch (_: Exception) { }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !PetCompanion.shouldObserve()) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // Our overlay must not become evidence about the underlying page.
        if (pkg.startsWith("com.android.systemui") || pkg.startsWith("com.android.launcher") ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                main.removeCallbacks(readHint); scheduled = false; lastPackage = ""; PetCompanion.clear()
            }
            return
        }
        if (pendingPackage != pkg) pendingWindowSwitch = false
        pendingPackage = pkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) pendingWindowSwitch = true
        // Fixed coalescing window, not an endlessly postponed timer on chatty video interfaces.
        if (!scheduled) { scheduled = true; main.postDelayed(readHint, 750L) }
    }

    @Suppress("DEPRECATION")
    private fun externalRoot(expectedPackage: String): AccessibilityNodeInfo? {
        return try {
            for (window in windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }) {
                val node = window.root ?: continue
                if (node.packageName?.toString() == packageName || !node.isVisibleToUser) { node.recycle(); continue }
                if (node.packageName?.toString() == expectedPackage) return node
                // A background app's event must not replace the foreground page's companion pose.
                node.recycle()
                return null
            }
            val node = rootInActiveWindow ?: return null
            if (node.packageName?.toString() == expectedPackage && node.packageName?.toString() != packageName) node
            else { node.recycle(); null }
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun readMarkers(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val hints = StringBuilder(); var visited = 0
        try {
            while (queue.isNotEmpty() && visited++ < 120 && hints.length < 6000) {
                val node = queue.removeFirst()
                try {
                    if (!node.isVisibleToUser || node.isPassword || node.isEditable) continue
                    hints.append(node.className?.toString()?.take(120)).append(' ')
                    node.text?.let { hints.append(it.toString().take(160)).append(' ') }
                    node.contentDescription?.let { hints.append(it.toString().take(160)).append(' ') }
                    for (i in 0 until minOf(node.childCount, 32)) node.getChild(i)?.let { queue.add(it) }
                } finally { node.recycle() }
            }
            return hints.toString().take(6000)
        } catch (_: Exception) {
            return ""
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
    }

    override fun onInterrupt() { main.removeCallbacks(readHint); scheduled = false; PetCompanion.clear() }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        PetCompanion.monitorConnected = false; PetCompanion.clear()
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: PetCompanionService? = null
        fun requestCurrentHint() { instance?.let { service -> service.main.post { service.requestVisibleHint() } } }
    }
}
