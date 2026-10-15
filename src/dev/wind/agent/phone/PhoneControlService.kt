package dev.wind.agent.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import dev.wind.agent.CancellationToken
import dev.wind.agent.screen.ScreenShareSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Event handlers collect identity/counters only. All UI reads are restricted to tool calls. */
class PhoneControlService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var foregroundPackage = ""
    @Volatile private var foregroundWindow = -1
    @Volatile private var structuralEpoch = 0L
    @Volatile private var contentEpoch = 0L
    private var lastStructuralAt = 0L
    private var imeCache = emptySet<String>()
    private var imeCacheAt = -30_001L
    private var applicationWindows = emptySet<Int>()
    private var identityKnown = false
    private val structuralLog = java.util.ArrayDeque<JSONObject>()
    private val observations = LinkedHashMap<String, Observation>()
    private val consumed = LinkedHashSet<String>()

    internal data class Node(val facts: NodeFacts, val json: JSONObject)
    internal data class Observation(val id: String, val mode: PhoneAutomation.Mode,
        val stamp: PhoneFreshness.Stamp, val nodes: Map<String, Node>, val json: JSONObject,
        val visualTargets: MutableMap<String, PhoneVisual.Target> = LinkedHashMap()) {
        val owner get() = stamp.owner
        val packageName get() = stamp.pkg
        val windowId get() = stamp.windowId
        val width get() = stamp.width
        val height get() = stamp.height
    }
    internal data class Checked(val observation: Observation, val drift: Long)
    private data class Snapshot(val pkg: String, val window: Int, val nodes: List<NodeFacts>, val truncated: Boolean)
    internal data class Sample(val pkg: String, val window: Int, val structural: Long, val content: Long,
        val quietMs: Long, val ime: Boolean, val editor: Boolean, val nodes: List<NodeFacts>, val truncated: Boolean)

    override fun onServiceConnected() { super.onServiceConnected(); PhoneAutomation.service = this; imes(); ChatForegroundMonitor.start(this) }
    override fun onInterrupt() { invalidateObservations() }
    override fun onDestroy() {
        PhoneManagedRecovery.stopAll()
        if (PhoneAutomation.service === this) PhoneAutomation.service = null
        PhoneApprovalPolicy.clear(); observations.clear(); main.removeCallbacksAndMessages(null); super.onDestroy()
    }
    private fun imes(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - imeCacheAt >= 30_000L) {
            imeCache = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList.map { it.packageName }.toSet()
            imeCacheAt = now
        }
        return imeCache
    }
    private fun ownVisible() = ScreenShareSession.mainVisible || ScreenShareSession.controlsVisible || ScreenShareSession.appActivitiesVisible
    private val resolveActiveWindow = Runnable { refreshWindows() }
    /** Window metadata only; never getRoot(), including in visual mode. */
    private fun refreshWindows(): Boolean {
        if (ownVisible()) return false
        val list = try { windows } catch (_: Exception) { return false }
        try {
            val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val ids = apps.map { it.id }.toSet()
            val active = apps.firstOrNull { it.isActive }
            if (active != null && foregroundWindow >= 0 && active.id != foregroundWindow) {
                structural("active_window", foregroundPackage, active.id); identityKnown = false
                PhoneManagedRecovery.signal()
            } else if (applicationWindows.isNotEmpty() && ids != applicationWindows) {
                structural("application_windows", foregroundPackage, foregroundWindow)
                PhoneManagedRecovery.signal()
            }
            applicationWindows = ids
            return list.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } finally { list.forEach { it.recycle() } }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if(android.os.Build.VERSION.SDK_INT>=30 && e.displayId!=0) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            main.removeCallbacks(resolveActiveWindow); main.postDelayed(resolveActiveWindow, 80); return
        }
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) ChatForegroundMonitor.signal(this, pkg)
        if (pkg.isBlank() || pkg == packageName || e.windowId < 0 || pkg in imes()) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val changed = pkg != foregroundPackage || e.windowId != foregroundWindow
                if (changed) structural("window_state", pkg, e.windowId)
                identityKnown = true
                if (changed || e.className?.toString()?.contains("dialog", true) == true) PhoneManagedRecovery.signal()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == foregroundPackage && e.windowId == foregroundWindow) contentEpoch++
        }
    }
    private fun structural(kind: String, pkg: String, window: Int) {
        structuralEpoch++; lastStructuralAt = SystemClock.elapsedRealtime(); foregroundPackage = pkg; foregroundWindow = window
        structuralLog.addLast(JSONObject().put("at", lastStructuralAt).put("kind", kind).put("package", pkg).put("window", window))
        while (structuralLog.size > 10) structuralLog.removeFirst()
    }
    internal fun invalidateObservations() { main.post { consumeAll(); structural("invalidate", foregroundPackage, foregroundWindow) } }
    private fun consumeAll() {
        consumed.addAll(observations.keys); observations.clear()
        while (consumed.size > 32) consumed.remove(consumed.first())
    }
    internal fun contentEpochNow() = contentEpoch
    internal fun guard(allowAppForeground: Boolean = false) {
        check(VirtualDisplays.mainAllowed(dev.wind.agent.AgentBus.activeTaskId.orEmpty())) { "VIRTUAL_DISPLAY_MAIN_SCREEN_BLOCKED" }
        check(PhoneAutomation.service === this) { "PHONE_SERVICE_DISCONNECTED" }
        check(PhoneAutomation.mode(this) != PhoneAutomation.Mode.OFF) { "PHONE_AUTOMATION_OFF" }
        check(!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked &&
            (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) { "PHONE_LOCKED" }
        check(allowAppForeground || !ownVisible()) { "PHONE_APP_FOREGROUND: move Vega to background" }
    }
    internal fun <T> onMain(token: CancellationToken, allowAppForeground: Boolean = false, operation: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PHONE_WORKER_REQUIRED" }
        val latch = CountDownLatch(1); val expired = AtomicBoolean(false); val started = AtomicBoolean(false)
        val result = AtomicReference<T?>(); val failure = AtomicReference<Throwable?>()
        val work = Runnable {
            if (!expired.get()) try {
                token.throwIfCancelled(); guard(allowAppForeground); started.set(true); result.set(operation())
            } catch (error: Throwable) { failure.set(error) }
            latch.countDown()
        }
        main.post(work)
        val deadline = SystemClock.elapsedRealtime() + 5000
        try {
            while (!latch.await(50, TimeUnit.MILLISECONDS)) {
                token.throwIfCancelled()
                check(SystemClock.elapsedRealtime() < deadline) {
                    if (started.get()) "PHONE_RESULT_UNKNOWN: operation started; do not automatically retry" else "PHONE_MAIN_TIMEOUT: not started"
                }
            }
            failure.get()?.let { throw it }
            @Suppress("UNCHECKED_CAST") return result.get() as T
        } finally { expired.set(true); main.removeCallbacks(work) }
    }
    @Suppress("DEPRECATION")
    private fun size(): IntArray {
        val metrics = DisplayMetrics()
        val display = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        display.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels, display.rotation)
    }
    private fun blockedPackage(pkg: String) = pkg == packageName || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")
    private fun facts(node: AccessibilityNodeInfo, path: List<Int>): NodeFacts {
        val r = Rect(); node.getBoundsInScreen(r)
        return NodeFacts(path, node.className?.toString().orEmpty().take(100), node.viewIdResourceName.orEmpty().take(180),
            if (node.isPassword) "" else node.text?.toString().orEmpty().take(350),
            if (node.isPassword) "" else node.contentDescription?.toString().orEmpty().take(350),
            r.left, r.top, r.right, r.bottom, node.isClickable, node.isEditable, node.isScrollable, node.isEnabled, node.isPassword, node.isVisibleToUser)
    }
    /** Live facts are bounded and only requested by non-visual tool paths. */
    @Suppress("DEPRECATION")
    private fun liveFacts(token: CancellationToken, expected: Observation? = null, limit: Int = 600): Snapshot {
        val root = rootInActiveWindow ?: error("PHONE_UI_UNAVAILABLE")
        val out = ArrayList<NodeFacts>(); val d = size(); var visited = 0; var truncated = false
        val until = SystemClock.elapsedRealtime() + 1500
        try {
            val pkg = root.packageName?.toString().orEmpty()
            check(pkg.isNotBlank() && !blockedPackage(pkg)) { "PHONE_PROTECTED_WINDOW" }
            if (expected != null) check(pkg == expected.packageName && root.windowId == expected.windowId) { "PHONE_WINDOW_CHANGED" }
            fun walk(node: AccessibilityNodeInfo, path: List<Int>, depth: Int) {
                token.throwIfCancelled()
                if (++visited > limit || depth > 24 || SystemClock.elapsedRealtime() >= until) { truncated = true; return }
                if (!node.isVisibleToUser) return
                val f = facts(node, path)
                if (f.area <= 0 || f.right <= 0 || f.bottom <= 0 || f.left >= d[0] || f.top >= d[1]) return
                out.add(f) // password geometry only; no text is read, never emitted to model
                if (f.password) return
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try { walk(child, path + i, depth + 1) } finally { child.recycle() }
                    if (truncated) break
                }
            }
            walk(root, emptyList(), 0)
            return Snapshot(pkg, root.windowId, out, truncated)
        } finally { root.recycle() }
    }
    private fun nodeJson(f: NodeFacts, id: String) = JSONObject().put("id", id).put("text", f.text).put("description", f.desc)
        .put("resource_id", f.resId).put("class", f.cls).put("bounds", JSONArray(listOf(f.left, f.top, f.right, f.bottom)))
        .put("clickable", f.clickable).put("editable", f.editable).put("scrollable", f.scrollable).put("enabled", f.enabled)
    internal fun observe(owner: String, mode: PhoneAutomation.Mode, token: CancellationToken): Observation = onMain(token) {
        check(dev.wind.agent.permissions.AgentPermissionGate(this).error("phone_observe", JSONObject().put("mode", mode.name.lowercase()), owner) == null) { "AGENT_PERMISSION_REVOKED" }
        val configured = PhoneAutomation.mode(this)
        check(configured == PhoneAutomation.Mode.HYBRID || configured == mode) { "PHONE_MODE_NOT_ENABLED" }
        refreshWindows()
        val d = size(); var pkg = foregroundPackage; var window = foregroundWindow; var truncated = false
        val nodes = LinkedHashMap<String, Node>(); var chars = 0; var encoded = 0
        if (mode != PhoneAutomation.Mode.VISUAL) {
            val live = liveFacts(token, limit = 240)
            pkg = live.pkg; window = live.window; truncated = live.truncated
            for (f in live.nodes.filter { !it.password }) {
                val id = "n" + nodes.size; val json = nodeJson(f, id)
                val length = json.toString().length + 1
                if (chars + f.text.length + f.desc.length > 18000 || encoded + length > 39000) { truncated = true; break }
                chars += f.text.length + f.desc.length; encoded += length; nodes[id] = Node(f, json)
            }
            if (pkg != foregroundPackage || window != foregroundWindow) structural("observe_identity", pkg, window)
            identityKnown = true
        } else check(identityKnown) { "PHONE_WINDOW_IDENTITY_PENDING: wait for a target window event" }
        check(pkg.isNotBlank() && window >= 0 && !blockedPackage(pkg)) { "PHONE_WINDOW_UNKNOWN_OR_PROTECTED" }
        val id = UUID.randomUUID().toString()
        val stamp = PhoneFreshness.Stamp(owner, pkg, window, d[0], d[1], structuralEpoch, contentEpoch, SystemClock.elapsedRealtime(), d[2])
        val interruptions = if (mode == PhoneAutomation.Mode.VISUAL) emptyList() else PhoneInterruptions.detect(nodes.values.map { it.facts }, dialogWindow(window, d))
        val json = JSONObject().put("observation_id", id).put("mode", mode.name.lowercase()).put("package", pkg).put("window", window)
            .put("dialog_window", mode != PhoneAutomation.Mode.VISUAL && dialogWindow(window, d))
            .put("width", d[0]).put("height", d[1]).put("coordinate_space", "normalized_0_1000").put("captured_at", System.currentTimeMillis())
            .put("expires_in_seconds", 90).put("truncated", truncated).put("untrusted_page_content", true)
            .put("structural_epoch", structuralEpoch).put("content_epoch", contentEpoch)
            .put("ime_action", PhoneInputService.instance?.editorAction(pkg) ?: "").put("nodes", JSONArray(nodes.values.map { it.json }))
            .put("interruptions", JSONArray(interruptions.map { candidate -> JSONObject().put("kind", candidate.kind)
                .put("dismiss_node_id", nodes.entries.first { it.value.facts.path == candidate.node.path }.key)
                .put("dismiss_text", candidate.text).put("confidence", candidate.confidence) }))
        while (observations.size >= 8) observations.remove(observations.keys.first())
        Observation(id, mode, stamp, nodes, json).also { observations[id] = it }
    }
    @Suppress("DEPRECATION")
    private fun dialogWindow(id: Int, dimensions: IntArray): Boolean {
        val list = windows
        return try { list.firstOrNull { it.id == id }?.let {
            val r = Rect(); it.getBoundsInScreen(r)
            list.none { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } &&
                r.width().toLong() * r.height() < dimensions[0].toLong() * dimensions[1] * 9 / 10
        } ?: false } finally { list.forEach { it.recycle() } }
    }
    internal fun forget(id: String, token: CancellationToken) = onMain(token) { observations.remove(id); Unit }
    private fun requireObservation(owner: String, args: JSONObject): Checked {
        refreshWindows()
        val id = args.optString("observation_id")
        val old = observations[id] ?: error("PHONE_OBSERVATION_REQUIRED: reason=" + if (id in consumed) "consumed; observe again" else "unknown; observe again")
        val d = size()
        val now = PhoneFreshness.Stamp(owner, foregroundPackage, foregroundWindow, d[0], d[1], structuralEpoch, contentEpoch, SystemClock.elapsedRealtime(), d[2])
        val drift = when (val verdict = PhoneFreshness.evaluate(old.stamp, now, PhoneAutomation.strictFreshness(this))) {
            is PhoneFreshness.Verdict.Stale -> error("PHONE_OBSERVATION_STALE: reason=" + verdict.reason + " " + verdict.detail + "; observe again")
            is PhoneFreshness.Verdict.Drifted -> verdict.contentEvents
            PhoneFreshness.Verdict.Fresh -> 0L
        }
        val configured = PhoneAutomation.mode(this)
        check(configured == PhoneAutomation.Mode.HYBRID || configured == old.mode) { "PHONE_MODE_CHANGED" }
        return Checked(old, drift)
    }
    internal fun checkAction(owner: String, args: JSONObject, token: CancellationToken): Checked = onMain(token) { requireObservation(owner, args) }
    internal fun actionObservation(owner: String, args: JSONObject, token: CancellationToken): Observation = checkAction(owner, args, token).observation
    internal fun bindVision(owner: String, observation: Observation, result: PhoneVision.Result, token: CancellationToken) = onMain(token) {
        requireObservation(owner, JSONObject().put("observation_id", observation.id))
        observation.visualTargets.clear()
        result.targets.forEach { observation.visualTargets[it.id] = it }
        observation.json.put("ocr", result.json())
        if (observation.mode == PhoneAutomation.Mode.VISUAL) observation.json.put("interruptions", JSONArray(
            PhoneVisual.interruptions(result.targets).map { PhoneVision.targetJson(it).put("kind", "visual_negative_prompt") }))
    }
    internal fun diagnostics(token: CancellationToken): JSONObject = onMain(token, true) {
        JSONObject().put("freshness", if (PhoneAutomation.strictFreshness(this)) "strict" else "tolerant")
            .put("structural_epoch", structuralEpoch).put("content_epoch", contentEpoch)
            .put("foreground", JSONObject().put("package", foregroundPackage).put("window", foregroundWindow))
            .put("recent_structural_changes", JSONArray(structuralLog.toList()))
    }
    internal fun sample(mode: PhoneAutomation.Mode, token: CancellationToken): Sample = onMain(token) {
        val permissions = dev.wind.agent.permissions.AgentPermissions.get(this)
        val owner = dev.wind.agent.AgentBus.activeTaskId.orEmpty()
        check(mode == PhoneAutomation.Mode.VISUAL || permissions.enabled(dev.wind.agent.permissions.AgentCapability.ACCESSIBILITY, owner)) { "AGENT_PERMISSION_REVOKED" }
        check(mode == PhoneAutomation.Mode.UI || permissions.enabled(dev.wind.agent.permissions.AgentCapability.SCREEN, owner)) { "AGENT_PERMISSION_REVOKED" }
        val ime = refreshWindows()
        val live = if (mode == PhoneAutomation.Mode.VISUAL) null else liveFacts(token)
        val pkg = live?.pkg ?: foregroundPackage
        if (live != null && (live.pkg != foregroundPackage || live.window != foregroundWindow)) structural("sample_identity", live.pkg, live.window)
        Sample(pkg, foregroundWindow, structuralEpoch, contentEpoch, SystemClock.elapsedRealtime() - lastStructuralAt,
            ime, PhoneInputService.instance?.accepts(pkg) == true || ime, live?.nodes.orEmpty(), live?.truncated == true)
    }
    internal fun settle(mode: PhoneAutomation.Mode, timeout: Long, token: CancellationToken, editor: Boolean = false): JSONObject {
        val start = SystemClock.elapsedRealtime(); val layout = PhoneSettle.LayoutStability()
        var previousContent: Long? = null; var result: Sample? = null; var reason = "layout_unstable"
        do {
            token.throwIfCancelled(); val current = sample(mode, token); result = current
            val stable = if (mode == PhoneAutomation.Mode.VISUAL) previousContent == current.content else layout.sample(current.nodes)
            previousContent = current.content
            if (current.quietMs >= 250 && stable && (!editor || current.editor))
                return JSONObject().put("settled", true).put("waited_ms", SystemClock.elapsedRealtime() - start).put("ime_visible", current.ime)
                    .put("hotspots_excluded", layout.hotSpots.size)
            reason = if (editor && !current.editor) "editor_not_active" else if (current.quietMs < 250) "structural_updates"
                else if (mode == PhoneAutomation.Mode.VISUAL) "continuous_content_updates" else "layout_unstable"
            if (SystemClock.elapsedRealtime() - start >= timeout) break
            token.sleep(minOf(150, timeout - (SystemClock.elapsedRealtime() - start)))
        } while (true)
        return JSONObject().put("settled", false).put("reason", reason).put("waited_ms", SystemClock.elapsedRealtime() - start).put("ime_visible", result?.ime == true)
    }

    internal fun chatPage(expectedPackage: String, title: String, token: CancellationToken): Sample {
        check(PhoneAutomation.mode(this) in setOf(PhoneAutomation.Mode.UI, PhoneAutomation.Mode.HYBRID)) { "CHAT_FILL_UI_MODE_REQUIRED" }
        val current = sample(PhoneAutomation.Mode.UI, token)
        check(current.pkg == expectedPackage && !current.truncated) { "CHAT_FILL_WRONG_OR_INCOMPLETE_PAGE" }
        val dimensions = onMain(token) { size() }
        check(current.nodes.count { !it.editable && it.visible && !it.password && (it.text == title || it.desc == title) && it.top < dimensions[1] * .25 } == 1) { "CHAT_FILL_CONVERSATION_NOT_VERIFIED" }
        return current
    }
    internal fun chatGeometry(token: CancellationToken): IntArray = onMain(token) {
        val d = size()
        val list = windows
        val imeTop = try { list.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.map { Rect().also(it::getBoundsInScreen).top }
            .filter { it > 0 }.minOrNull() } finally { list.forEach { it.recycle() } }
        val nav = if (Build.VERSION.SDK_INT >= 30) runCatching {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom
        }.getOrDefault((24 * resources.displayMetrics.density).toInt()) else (24 * resources.displayMetrics.density).toInt()
        val panel = imeTop ?: (d[1] - nav)
        val composer = panel - (30 * resources.displayMetrics.density).toInt()
        intArrayOf(d[0], d[1], d[2], composer * 1000 / d[1], panel * 1000 / d[1])
    }
    internal fun chatTap(pkg: String, window: Int, x: Int, y: Int, token: CancellationToken) {
        require(x in 0..1000 && y in 0..1000)
        val complete = CountDownLatch(1); val status = AtomicReference("GESTURE_SUBMITTED")
        onMain(token) {
            refreshWindows()
            check(foregroundPackage == pkg && foregroundWindow == window) { "CHAT_PAGE_CHANGED_BEFORE_TAP" }
            val d = size()
            dispatchPlan(PhoneGesturePlanner.Plan((x * d[0] / 1000).toFloat(), (y * d[1] / 1000).toFloat(),
                (x * d[0] / 1000).toFloat(), (y * d[1] / 1000).toFloat(), 70, 0, false), complete, status)
            consumeAll()
        }
        val until = SystemClock.elapsedRealtime() + 2500
        while (!complete.await(50, TimeUnit.MILLISECONDS)) {
            token.throwIfCancelled()
            check(SystemClock.elapsedRealtime() < until) { "PHONE_RESULT_UNKNOWN: chat tap was submitted; do not replay" }
        }
        check(status.get() == "GESTURE_COMPLETED") { "PHONE_RESULT_UNKNOWN: chat gesture ${status.get()}" }
    }
    internal fun chatContext(expectedPackage: String, title: String, token: CancellationToken): List<String> {
        val current = chatPage(expectedPackage, title, token)
        val height = onMain(token) { size()[1] }
        return current.nodes.filter { it.visible && !it.password && !it.editable && it.text.isNotBlank() && it.top > height * .16 && it.bottom < height * .85 }
            .sortedBy { it.top }.map { it.text.take(700) }.distinct().takeLast(16)
    }
    private fun verifyChatLive(expectedPackage: String, title: String, token: CancellationToken) {
        val live = liveFacts(token)
        check(live.pkg == expectedPackage && !live.truncated) { "CHAT_FILL_PAGE_CHANGED" }
        check(live.nodes.count { !it.editable && it.visible && !it.password && (it.text == title || it.desc == title) && it.top < size()[1] * .25 } == 1) { "CHAT_FILL_PEER_CHANGED" }
    }
    internal fun fillChat(expectedPackage: String, title: String, text: String, token: CancellationToken) {
        require(text.isNotBlank() && text.length <= 1000)
        val owner = "chat-fill-" + java.util.UUID.randomUUID()
        fun target(): Pair<Observation, String> {
            chatPage(expectedPackage, title, token)
            val o = observe(owner, PhoneAutomation.Mode.UI, token)
            val editor = o.nodes.entries.filter { it.value.facts.editable && it.value.facts.actionable }.singleOrNull()
                ?: error("CHAT_FILL_EDITOR_AMBIGUOUS")
            return o to editor.key
        }
        val (o, id) = target()
        // This tap is user authorization to fill only, not authority to send or navigate.
        onMain(token) {
            verifyChatLive(expectedPackage, title, token)
            val node = nodeAt(o.nodes.getValue(id).facts.path, o)
            try {
                check(facts(node, o.nodes.getValue(id).facts.path) == o.nodes.getValue(id).facts) { "CHAT_EDITOR_CHANGED" }
                if (!node.isFocused) check(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "CHAT_EDITOR_FOCUS_REFUSED" }
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            finally { node.recycle() }
        }
        check(stabilizeEditor(expectedPackage, token).optBoolean("settled")) { "CHAT_EDITOR_NOT_STABLE" }
        val (fresh, freshId) = target()
        onMain(token) {
            verifyChatLive(expectedPackage, title, token)
            val f = fresh.nodes.getValue(freshId).facts
            val node = nodeAt(f.path, fresh)
            try {
                check(facts(node, f.path) == f) { "CHAT_EDITOR_CHANGED" }
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                })) { "CHAT_FILL_REFUSED" }
            } finally { node.recycle(); consumeAll() }
        }
        // Intentionally no IME_ACTION_SEND, Send-button click, RemoteInput or ACTION_PASTE fallback.
    }

    internal fun stabilizeEditor(expectedPackage: String, token: CancellationToken): JSONObject {
        val start = SystemClock.elapsedRealtime()
        val stability = PhoneEditorStability(start)
        do {
            token.throwIfCancelled()
            val current = sample(PhoneAutomation.Mode.UI, token)
            check(current.pkg == expectedPackage) { "PHONE_EDITOR_PACKAGE_CHANGED" }
            val editors = current.nodes.filter { it.editable && it.actionable }
            val panels = onMain(token) {
                val list = windows
                try { list.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.joinToString { window ->
                    val bounds = Rect(); window.getBoundsInScreen(bounds); bounds.toShortString()
                } } finally { list.forEach { it.recycle() } }
            }
            val signature = "${current.window}:${current.ime}:$panels:" + editors.joinToString { "${it.resId}:${it.pathKey}:${it.left},${it.top},${it.right},${it.bottom}" }
            val now = SystemClock.elapsedRealtime()
            // A hardware keyboard may have no IME window. Minimum grace permits delayed panels.
            if (stability.ready(now, signature, editors.isNotEmpty(), current.quietMs))
                return JSONObject().put("settled", true).put("waited_ms", now - start).put("ime_visible", current.ime)
            if (stability.expired(now)) break
            token.sleep(70)
        } while (true)
        return JSONObject().put("settled", false).put("reason", "editor_panel_unstable").put("waited_ms", SystemClock.elapsedRealtime() - start)
    }
    internal fun replyChat(expectedPackage: String, title: String, text: String, token: CancellationToken) {
        fillChat(expectedPackage, title, text, token)
        val owner = "chat-send-" + UUID.randomUUID()
        chatPage(expectedPackage, title, token)
        val o = observe(owner, PhoneAutomation.Mode.UI, token)
        val candidates = o.nodes.values.map { it.facts }.filter { it.actionable && it.clickable && (it.text == "发送" || it.text == "Send" || it.desc == "发送" || it.desc == "Send") }
        val f = candidates.singleOrNull() ?: error("CHAT_SEND_BUTTON_AMBIGUOUS: text filled, not sent")
        onMain(token) {
            verifyChatLive(expectedPackage, title, token)
            val live = liveFacts(token, o)
            check(live.nodes.count { it.editable && it.actionable && it.text == text } == 1) { "CHAT_TEXT_CHANGED: not sent" }
            val node = nodeAt(f.path, o)
            try {
                check(facts(node, f.path) == f) { "CHAT_SEND_TARGET_CHANGED" }
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "CHAT_SEND_REFUSED" }
            } finally { node.recycle(); consumeAll() }
        }
    }

    internal fun act(owner: String, args: JSONObject, token: CancellationToken, ticket: PhoneApprovalPolicy.Ticket): JSONObject {
        if (args.optString("action") in setOf("set_text", "paste_text") && actionObservation(owner, args, token).mode != PhoneAutomation.Mode.VISUAL) {
            val expected = actionObservation(owner, args, token).packageName
            check(stabilizeEditor(expected, token).optBoolean("settled")) { "PHONE_EDITOR_NOT_STABLE: no text submitted" }
            // Recheck the original snapshot after waiting; never relax cross-page identity.
            checkAction(owner, args, token)
        }
        onMain(token) { ScreenShareSession.service?.setPhoneActionHidden(true) }
        try { token.throwIfCancelled(); token.sleep(180); return perform(owner, args, token, ticket) }
        finally { main.post { ScreenShareSession.service?.setPhoneActionHidden(false) } }
    }
    @Suppress("DEPRECATION")
    private fun nodeAt(path: List<Int>, observation: Observation): AccessibilityNodeInfo {
        var node = rootInActiveWindow ?: error("PHONE_UI_UNAVAILABLE")
        try {
            check(node.packageName?.toString() == observation.packageName && node.windowId == observation.windowId) { "PHONE_WINDOW_CHANGED" }
            for (i in path) {
                val next = node.getChild(i) ?: error("PHONE_NODE_STALE")
                node.recycle(); node = next
            }
            check(node.refresh() && node.isVisibleToUser && node.isEnabled && !node.isPassword) { "PHONE_NODE_STALE_OR_PROTECTED" }
            return node
        } catch (error: Throwable) { node.recycle(); throw error }
    }

    /** Callback continuation holds the final point before release; never replays an uncertain action. */
    private fun dispatchPlan(plan: PhoneGesturePlanner.Plan, complete: CountDownLatch, status: AtomicReference<String>) {
        check(Build.VERSION.SDK_INT >= 24) { "PHONE_GESTURES_REQUIRE_ANDROID_7" }
        val path = Path().apply { moveTo(plan.startX, plan.startY); if (plan.startX != plan.endX || plan.startY != plan.endY) lineTo(plan.endX, plan.endY) }
        val stroke = if (Build.VERSION.SDK_INT >= 26 && plan.continued) GestureDescription.StrokeDescription(path, 0, plan.durationMs, true)
            else GestureDescription.StrokeDescription(path, 0, plan.durationMs)
        val release = object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) { status.set("GESTURE_COMPLETED"); complete.countDown() }
            override fun onCancelled(gesture: GestureDescription?) { status.set("GESTURE_CANCELLED"); complete.countDown() }
        }
        val callback = if (Build.VERSION.SDK_INT >= 26 && plan.continued) object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) {
                try {
                    val hold = Path().apply { moveTo(plan.endX, plan.endY) }
                    val finish = stroke.continueStroke(hold, 0, plan.holdMs, false)
                    if (!dispatchGesture(GestureDescription.Builder().addStroke(finish).build(), release, main)) {
                        status.set("RESULT_UNKNOWN"); complete.countDown()
                    }
                } catch (_: Exception) { status.set("RESULT_UNKNOWN"); complete.countDown() }
            }
            override fun onCancelled(gesture: GestureDescription?) { status.set("GESTURE_CANCELLED"); complete.countDown() }
        } else release
        check(dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), callback, main)) { "PHONE_GESTURE_REFUSED" }
    }

    private fun perform(owner: String, args: JSONObject, token: CancellationToken, ticket: PhoneApprovalPolicy.Ticket): JSONObject {
        val completed = CountDownLatch(1); val gestureStatus = AtomicReference("GESTURE_SUBMITTED")
        val action = args.getString("action")
        val output = onMain(token) {
            val checked = requireObservation(owner, args); val o = checked.observation
            token.throwIfCancelled(); PhoneApprovalPolicy.check(ticket)
            val result = JSONObject().put("action", action).put("resolution", "coordinate").put("via", "gesture")
                .put("verification", if (o.mode == PhoneAutomation.Mode.VISUAL) "visual_mode" else "same_target")
                .put("page_drift", JSONObject().put("content_events", checked.drift))
                .put("note", "submission is not verified business success; inspect the next observation; never replay unknown completion")
            val semanticScroll = action == "scroll" && args.optString("direction") in setOf("up", "down", "left", "right")
            val liveSnapshot = if (o.mode == PhoneAutomation.Mode.VISUAL) null else liveFacts(token, o)
            val live = liveSnapshot?.nodes.orEmpty()
            val observed = o.nodes.values.map { it.facts }
            var editor = false
            fun verify(x: Int, y: Int): NodeFacts? {
                if (o.mode == PhoneAutomation.Mode.VISUAL) return null
                val then = PhoneNodeMatcher.deepestAt(observed, x, y)
                val now = PhoneNodeMatcher.deepestAt(live, x, y)
                check(now?.password != true && now?.enabled != false) { "PHONE_PROTECTED_TARGET" }
                if (then == null) {
                    result.put("verification", "no_observed_node_at_point")
                } else check(now != null && PhoneNodeMatcher.samePointTarget(then, observed, now, live)) {
                    "PHONE_TARGET_CHANGED: observed=" + then.brief() + " now=" + now?.brief() + "; observe again"
                }
                val target = now?.let { PhoneNodeMatcher.clickableOwner(it, live) ?: it }
                PhoneApprovalPolicy.recheck(ticket, o.packageName, action, args, target, live, false)
                return now
            }
            fun gesture(plan: PhoneGesturePlanner.Plan) {
                dispatchPlan(plan, completed, gestureStatus); result.put("status", "GESTURE_SUBMITTED")
            }
            when {
                action in setOf("tap", "long_press", "swipe") || action == "dismiss" && o.mode == PhoneAutomation.Mode.VISUAL -> {
                    if (action == "dismiss") check(PhoneApprovalPolicy.mode(this) == PhoneApprovalPolicy.Mode.FULL &&
                        PhoneVisual.interruptions(o.visualTargets.values.toList()).any { it.id == args.optString("visual_id") }) {
                        "PHONE_DISMISS_NOT_WHITELISTED"
                    }
                    val x = PhoneApprovalPolicy.normalized(args, "x", o.width); val y = PhoneApprovalPolicy.normalized(args, "y", o.height)
                    val hit = verify(x, y); editor = hit?.editable == true
                    if (o.mode == PhoneAutomation.Mode.VISUAL) PhoneApprovalPolicy.recheck(ticket, o.packageName, action, args, null, emptyList(), true)
                    val endX = if (action == "swipe") PhoneApprovalPolicy.normalized(args, "end_x", o.width) else x
                    val endY = if (action == "swipe") PhoneApprovalPolicy.normalized(args, "end_y", o.height) else y
                    val duration = args.optLong("duration_ms", if (action == "long_press") 650 else if (action == "swipe") 400 else 70)
                    require(duration in 40L..1800L) { "PHONE_DURATION_INVALID" }
                    gesture(PhoneGesturePlanner.Plan(x.toFloat(), y.toFloat(), endX.toFloat(), endY.toFloat(), duration, 0, false))
                }
                action == "type_text" || action == "ime_action" || action == "paste_text" && o.mode == PhoneAutomation.Mode.VISUAL -> {
                    PhoneApprovalPolicy.recheck(ticket, o.packageName, action, args, null, live, o.mode == PhoneAutomation.Mode.VISUAL)
                    val ok = if (action == "paste_text") PhoneInputService.instance?.paste(args.getString("text"), o.packageName, args.optBoolean("replace", false))
                        else if (action == "type_text") PhoneInputService.instance?.commit(args.getString("text"), o.packageName)
                        else PhoneInputService.instance?.performAction(o.packageName, args.optString("editor_action", "auto"))
                    check(ok == true) { "PHONE_IME_ACTION_REFUSED" }
                    result.put("status", if (action == "type_text") "TEXT_INPUT_ACCEPTED" else "IME_ACTION_ACCEPTED").put("via", "ime")
                }
                action in setOf("click", "set_text", "paste_text", "scroll", "dismiss") -> {
                    val entry = args.optString("node_id").takeIf { it.isNotBlank() }?.let { o.nodes[it]?.facts ?: error("PHONE_NODE_UNKNOWN") }
                    check(entry == null && semanticScroll || o.mode != PhoneAutomation.Mode.VISUAL) { "PHONE_VISUAL_HAS_NO_NODES" }
                    val match = entry?.let { PhoneNodeMatcher.resolve(it, live) ?: error("PHONE_NODE_STALE: " + it.brief() + "; reobserve before trying bounds center") }
                    check(match?.tier != PhoneNodeMatcher.Tier.RELOCATED || liveSnapshot?.truncated != true) { "PHONE_NODE_MATCH_UNPROVEN: truncated live tree cannot prove unique relocation" }
                    if (semanticScroll) {
                        val plan = PhoneGesturePlanner.scroll(o.width, o.height, args.getString("direction"), args.optString("amount", "half"), match?.node, Build.VERSION.SDK_INT)
                        verify(plan.startX.toInt(), plan.startY.toInt())
                        result.put("resolution", match?.tier?.name?.lowercase() ?: "viewport")
                        gesture(plan)
                    } else {
                        check(match != null) { "PHONE_NODE_REQUIRED" }
                        var effective = match.node
                        val prefer = args.optString("prefer", "node"); require(prefer in setOf("node", "gesture")) { "PHONE_PREFER_INVALID" }
                        if (action == "click" && prefer == "node" && !effective.clickable) {
                            effective = PhoneNodeMatcher.clickableOwner(effective, live)?.takeIf { it.path.size >= match.node.path.size - 4 } ?: effective
                        }
                        if (action == "dismiss") {
                            check(PhoneApprovalPolicy.mode(this) == PhoneApprovalPolicy.Mode.FULL) { "PHONE_DISMISS_FULL_MODE_REQUIRED" }
                            check(PhoneInterruptions.negativeButton(effective) &&
                                PhoneInterruptions.detect(live, dialogWindow(o.windowId, intArrayOf(o.width, o.height))).any { it.node.path == effective.path }) { "PHONE_DISMISS_NOT_WHITELISTED" }
                        }
                        PhoneApprovalPolicy.recheck(ticket, o.packageName, action, args, effective, live, false)
                        val node = nodeAt(effective.path, o)
                        try {
                            // Reacquired live geometry/text must still equal the facts used in risk review.
                            check(facts(node, effective.path) == effective) { "PHONE_NODE_CHANGED_DURING_COMMIT" }
                            result.put("resolution", match.tier.name.lowercase())
                            editor = effective.editable && action == "click"
                            if (action in setOf("click", "dismiss")) {
                                if (prefer == "node" && effective.clickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    result.put("status", "NODE_ACTION_ACCEPTED").put("via", if (effective.path == match.node.path) "node" else "ancestor")
                                } else {
                                    verify(match.node.centerX, match.node.centerY)
                                    gesture(PhoneGesturePlanner.Plan(match.node.centerX.toFloat(), match.node.centerY.toFloat(),
                                        match.node.centerX.toFloat(), match.node.centerY.toFloat(), 70, 0, false))
                                }
                            } else {
                                val accepted = if (action in setOf("set_text", "paste_text")) {
                                    check(effective.editable) { "PHONE_NODE_NOT_EDITABLE" }
                                    val text = args.getString("text"); require(text.length <= 8000) { "PHONE_TEXT_TOO_LONG" }
                                    if (action == "paste_text") {
                                        PhoneClipboard.copy(this, text)
                                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                                        if (args.optBoolean("replace", false)) check(node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,
                                            Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0) })) { "PHONE_SELECTION_REFUSED" }
                                        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                                    } else node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
                                } else {
                                    check(effective.scrollable) { "PHONE_NODE_NOT_SCROLLABLE" }
                                    val direction = args.getString("direction"); require(direction in setOf("forward", "backward")) { "PHONE_SCROLL_DIRECTION_INVALID" }
                                    node.performAction(if (direction == "forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                                }
                                check(accepted) { "PHONE_NODE_ACTION_REFUSED" }
                                result.put("status", "NODE_ACTION_ACCEPTED").put("via", "node")
                            }
                        } finally { node.recycle() }
                    }
                }
                else -> {
                    PhoneApprovalPolicy.recheck(ticket, o.packageName, action, args, null, live, o.mode == PhoneAutomation.Mode.VISUAL)
                    val global = when (action) {
                        "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
                        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                        else -> error("PHONE_ACTION_UNKNOWN")
                    }
                    check(performGlobalAction(global)) { "PHONE_GLOBAL_ACTION_REFUSED" }
                    result.put("status", "GLOBAL_ACTION_ACCEPTED").put("via", "global")
                }
            }
            consumeAll()
            result.put("_editor", editor)
            result
        }
        if (output.optString("status") == "GESTURE_SUBMITTED") {
            val until = SystemClock.elapsedRealtime() + 3500
            while (!completed.await(50, TimeUnit.MILLISECONDS)) {
                if (token.isCancelled || SystemClock.elapsedRealtime() >= until) {
                    output.put("status", "RESULT_UNKNOWN"); return output
                }
            }
            output.put("status", gestureStatus.get())
        }
        return output
    }
}
