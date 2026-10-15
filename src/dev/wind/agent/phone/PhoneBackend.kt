package dev.wind.agent.phone

import android.app.KeyguardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import dev.wind.agent.CancellationToken
import dev.wind.agent.Message
import dev.wind.agent.Tools
import dev.wind.agent.adb.AdbCommands
import dev.wind.agent.adb.AdbManager
import dev.wind.agent.permissions.AgentCapability
import dev.wind.agent.permissions.AgentPermissions
import dev.wind.agent.screen.ScreenShareSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A backend selection is independent from ui/visual/hybrid observation mode. */
internal interface PhoneBackend {
    val name: String
    fun observe(mode: String, token: CancellationToken, options: JSONObject = JSONObject()): JSONObject
    fun action(args: JSONObject, token: CancellationToken): JSONObject
    fun capture(token: CancellationToken): ByteArray
}

internal object PhoneCoordinator {
    fun <T> run(token: CancellationToken, operation: () -> T): T = PhoneAutomation.serialized(token, operation)
}

internal data class PhoneObservation(val owner: String, val backend: String, val epoch: Long, val revision: Long,
    val json: JSONObject, val visuals: MutableMap<String, PhoneVisual.Target> = LinkedHashMap()) {
    val id get() = json.getString("observation_id")
    val mode get() = json.getString("mode")
    val pkg get() = json.getString("package")
    val nodes get(): List<Pair<String, NodeFacts>> {
        val array = json.optJSONArray("nodes") ?: JSONArray()
        return (0 until array.length()).map { val n = array.getJSONObject(it); n.optString("node_id",n.optString("id")) to facts(n) }
    }
    companion object {
        fun facts(n: JSONObject): NodeFacts {
            val b = n.getJSONArray("bounds")
            val id=n.optString("node_id",n.optString("id")); require(id.isNotBlank()) { "PHONE_NODE_ID_REQUIRED" }
            return NodeFacts(n.optString("path", id).split('.').map { it.toIntOrNull() ?: 0 },
                n.optString("class"), n.optString("resource_id"), n.optString("text"), n.optString("description"),
                b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3), n.optBoolean("clickable"), n.optBoolean("editable"), n.optBoolean("scrollable"),
                n.optBoolean("enabled", true), n.optBoolean("password"), n.optBoolean("visible", true))
        }
    }
}

internal object PhoneObservationStore {
    private val observations = LinkedHashMap<String, PhoneObservation>()
    @Synchronized fun add(o: PhoneObservation) { observations[o.id] = o; while (observations.size > 64) observations.remove(observations.keys.first()) }
    @Synchronized fun take(id: String, consume: Boolean = false): PhoneObservation? = if (consume) observations.remove(id) else observations[id]
    @Synchronized fun invalidate(owner: String) { observations.entries.removeAll { it.value.owner == owner } }
}

internal class AdbBridgeBackend(private val bridge: PhoneBridge,private val mainTarget: Boolean=false) : PhoneBackend {
    override val name = "adb_bridge"
    private fun scope(a: JSONObject)=JSONObject(a.toString()).also { if(mainTarget) it.put("screen","main") }
    override fun observe(mode: String, token: CancellationToken, options: JSONObject) = bridge.rpc("observe", scope(options).put("mode", mode), token)
    override fun action(args: JSONObject, token: CancellationToken) = bridge.action(scope(args), token)
    override fun capture(token: CancellationToken) = bridge.captureFrame(token,scope(JSONObject())).png
}

/** Compatibility adapter deliberately uses the original authority and observation implementation. */
internal class LegacyBackend(private val context: Context, private val owner: String) : PhoneBackend {
    override val name="legacy"
    override fun observe(mode: String, token: CancellationToken, options: JSONObject): JSONObject =
        JSONObject(PhoneTools(context,owner).run("phone_observe",JSONObject(options.toString()).put("mode",mode).put("screen","main").put("backend","legacy"),token,null))
    override fun action(args: JSONObject, token: CancellationToken): JSONObject =
        JSONObject(PhoneTools(context,owner).run("phone_action",JSONObject(args.toString()).put("screen","main").put("backend","legacy"),token,null))
    override fun capture(token: CancellationToken): ByteArray {
        check(AgentPermissions.get(context).enabled(AgentCapability.SCREEN,owner)) { "AGENT_PERMISSION_REQUIRED: SCREEN" }
        val image=ScreenShareSession.service?.captureBlocking(ScreenShareSession.sessionId,token,phoneObservation=true)?.image ?: error("PHONE_SCREEN_SHARE_REQUIRED")
        return Base64.decode(image.dataUri?.substringAfter(',') ?: error("PHONE_IMAGE_DATA_MISSING"),Base64.DEFAULT)
    }
}

/** Bounded, typed compatibility path. No raw shell fragments or keyevent-based text. */
internal class AdbShellBackend(private val context: Context, private val owner: String) : PhoneBackend {
    override val name = "adb_shell"
    private val manager = AdbManager.get(context)
    private fun execute(command: String, token: CancellationToken, image: Boolean = false): dev.wind.agent.adb.AdbResult =
        manager.execute(command, owner, token, 12000, if (image) PhoneBridgeProtocol.IMAGE_LIMIT else 1048576,
            beforeSend = { PhoneBackends.guard(context, owner, token) }, binaryImage = image)
    private fun output(command: String, token: CancellationToken): String {
        val r = execute(command, token)
        check(r.status == "COMPLETED" && !r.truncated) { "PHONE_ADB_READ_FAILED: ${r.status}" }
        return String(r.stdout, Charsets.UTF_8)
    }
    override fun observe(mode: String, token: CancellationToken, options: JSONObject): JSONObject {
        val window = output("dumpsys window windows", token)
        val focus = Regex("mCurrentFocus=Window\\{([^}]+)\\}").find(window)?.groupValues?.get(1) ?: error("PHONE_FOREGROUND_UNVERIFIED")
        val pkg = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/([^ ]+)").find(focus)?.groupValues?.get(1) ?: error("PHONE_FOREGROUND_UNVERIFIED")
        check(pkg != context.packageName && !pkg.contains("permissioncontroller") && !pkg.contains("packageinstaller")) { "PHONE_PROTECTED_WINDOW" }
        val size = Regex("(?:Physical|Override) size: (\\d+)x(\\d+)").findAll(output("wm size", token)).lastOrNull() ?: error("PHONE_DISPLAY_INVALID")
        val rotation = Regex("(?:rotation|mRotation)[ =]+([0-3])").find(output("dumpsys display", token))?.groupValues?.get(1)?.toInt() ?: error("PHONE_ROTATION_UNVERIFIED")
        var w = size.groupValues[1].toInt(); var h = size.groupValues[2].toInt()
        if (rotation % 2 == 1) { val t = w; w = h; h = t }
        val nodes = JSONArray()
        if (mode != "visual") {
            val legacy = PhoneAutomation.service
            if (legacy != null) {
                val sample = legacy.sample(PhoneAutomation.Mode.UI, token)
                check(sample.pkg == pkg && !sample.truncated) { "PHONE_WINDOW_CHANGED" }
                sample.nodes.take(512).forEach { f -> nodes.put(nodeJson(f)) }
            } else {
                // Shell uiautomator can suppress other accessibility services. Do not use it if ANY are enabled.
                val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_accessibility_services").orEmpty()
                check(enabled.isBlank()) { "PHONE_UIA_COEXISTENCE_UNAVAILABLE: enable legacy service or use visual mode" }
                val id = UUID.randomUUID().toString().replace("-", "")
                val path = "/data/local/tmp/vega-phone-ui-$id.xml"
                val xml = output("uiautomator dump --compressed '$path' >/dev/null && cat '$path'; rm -f '$path'", token)
                val parser = android.util.Xml.newPullParser(); parser.setInput(java.io.StringReader(xml))
                val paths = ArrayList<Int>(); val counters = ArrayList<Int>(); var depth = 0
                while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && nodes.length() < 512) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                        val index = if (depth == 0) 0 else counters[depth - 1]++
                        paths.add(index); counters.add(0); depth++
                        fun a(key: String) = parser.getAttributeValue(null, key).orEmpty()
                        check(a("package").isBlank() || a("package")==pkg) { "PHONE_WINDOW_CHANGED" }
                        val b = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(a("bounds")) ?: error("PHONE_NODE_BOUNDS_INVALID")
                        val password = a("password") == "true"
                        nodes.put(JSONObject().put("node_id", paths.joinToString(".")).put("path", paths.joinToString(".")).put("class", a("class")).put("resource_id", a("resource-id"))
                            .put("text", if (password) "" else a("text")).put("description", if (password) "" else a("content-desc"))
                            .put("bounds", JSONArray(b.groupValues.drop(1).map(String::toInt))).put("password", password).put("enabled", a("enabled") == "true")
                            .put("clickable", a("clickable") == "true").put("scrollable", a("scrollable") == "true").put("editable", a("class").contains("EditText")).put("visible", true))
                    } else if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "node") { depth--; paths.removeAt(depth); counters.removeAt(depth) }
                    parser.next()
                }
            }
        }
        check(Regex("mCurrentFocus=Window\\{([^}]+)\\}").find(output("dumpsys window windows", token))?.groupValues?.get(1) == focus) { "PHONE_WINDOW_CHANGED" }
        return JSONObject().put("status", "COMPLETED").put("observation_id", UUID.randomUUID().toString()).put("mode", mode).put("package", pkg).put("window", focus)
            .put("width", w).put("height", h).put("rotation", rotation).put("display_id", 0).put("captured_at", System.currentTimeMillis())
            .put("nodes", nodes).put("truncated", nodes.length() >= 512).put("quiet_ms", 0).put("ime_visible", output("dumpsys input_method", token).contains("mInputShown=true"))
    }
    override fun action(args: JSONObject, token: CancellationToken): JSONObject {
        val o = PhoneObservationStore.take(args.getString("observation_id")) ?: error("PHONE_OBSERVATION_EXPIRED")
        val fresh = observe(o.mode, token)
        PhoneBackends.samePage(o.json, fresh)
        args.optJSONObject("peer_guard")?.let { peer ->
            check(peer.getString("source")=="ui") { "CHAT_SHELL_OCR_COMMIT_UNSUPPORTED: use bridge or legacy; copy remains available" }
            val facts=PhoneObservation(owner,name,manager.connectionEpoch,0,fresh).nodes.map { it.second }
            val lines=facts.filter { !it.editable && it.visible && !it.password }.map { f -> ChatPageParser.Line(f.text.ifBlank { f.desc },f.left*1000/fresh.getInt("width"),f.top*1000/fresh.getInt("height"),f.right*1000/fresh.getInt("width"),f.bottom*1000/fresh.getInt("height")) }
            check(peer.getString("package")==fresh.getString("package") && ChatPageParser.titleMatches(lines,peer.getString("title"))) { "CHAT_CONVERSATION_NOT_VERIFIED" }
        }
        val action = args.getString("action")
        val node = if (args.has("node_id")) {
            val before = o.nodes.singleOrNull { it.first == args.getString("node_id") }?.second ?: error("PHONE_NODE_UNKNOWN")
            val live = PhoneObservation(owner, name, manager.connectionEpoch, 0, fresh).nodes.map { it.second }
            live.singleOrNull { it == before } ?: error("TARGET_CHANGED")
        } else null
        check(node == null || node.actionable) { "PHONE_TARGET_PROTECTED" }
        val w = fresh.getInt("width"); val h = fresh.getInt("height")
        fun coordinate(key: String, extent: Int) = PhoneApprovalPolicy.normalized(args, key, extent)
        val command = when (action) {
            "tap", "long_press" -> {
                val x = coordinate("x", w); val y = coordinate("y", h)
                if (action == "tap") "input touchscreen tap $x $y" else "input touchscreen swipe $x $y $x $y 650"
            }
            "click" -> { val f = node ?: error("PHONE_NODE_REQUIRED"); "input touchscreen tap ${f.centerX} ${f.centerY}" }
            "swipe" -> "input touchscreen swipe ${coordinate("x", w)} ${coordinate("y", h)} ${coordinate("x2", w)} ${coordinate("y2", h)} ${args.optInt("duration_ms", 300).also { require(it in 1..1500) }}"
            "back" -> "input keyevent 4"; "home" -> "input keyevent 3"; "recents" -> "input keyevent 187"
            "notifications" -> "cmd statusbar expand-notifications"; "quick_settings" -> "cmd statusbar expand-settings"
            else -> return JSONObject().put("status", "NOT_SUBMITTED").put("error", "PHONE_ADB_COMPAT_ACTION_UNSUPPORTED: use bridge or explicit legacy backend").put("submitted", false)
        }
        return execute(command, token).json().put("note", "Dispatch acknowledgement is not a verified application result")
    }
    override fun capture(token: CancellationToken): ByteArray {
        check(AgentPermissions.get(context).enabled(AgentCapability.SCREEN, owner)) { "AGENT_PERMISSION_REQUIRED: SCREEN" }
        val r = execute("screencap -p", token, true)
        check(r.status == "COMPLETED" && !r.truncated) { "PHONE_CAPTURE_FAILED: ${r.status}" }
        return r.stdout
    }
    private fun nodeJson(f: NodeFacts) = JSONObject().put("node_id", f.pathKey).put("path", f.pathKey).put("class", f.cls).put("resource_id", f.resId)
        .put("text", f.text).put("description", f.desc).put("bounds", JSONArray(listOf(f.left, f.top, f.right, f.bottom))).put("clickable", f.clickable)
        .put("editable", f.editable).put("scrollable", f.scrollable).put("enabled", f.enabled).put("password", f.password).put("visible", f.visible)
}

internal object PhoneBackends {
    private val bridges = ConcurrentHashMap<String, PhoneBridge>()
    private val failures = ConcurrentHashMap<String, String>()
    private val cooldowns = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val metrics = java.util.ArrayDeque<JSONObject>()
    private val routes = ConcurrentHashMap<String, String>()
    fun preference(context: Context): String = context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).getString("backend", "auto") ?: "auto"
    fun choose(context: Context, backend: String) {
        require(backend in setOf("auto", "adb", "legacy")); context.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).edit().putString("backend", backend).apply()
        bridges.values.forEach { it.close() }; bridges.clear(); routes.keys.forEach(PhoneObservationStore::invalidate); routes.clear()
        PhoneAutomation.service?.invalidateObservations()
    }
    fun enterLegacy(context: Context, owner: String) {
        val key=context.filesDir.absolutePath+":"+owner
        if(routes.put(key,"legacy") != "legacy") {
            bridges.remove(key)?.close(); PhoneObservationStore.invalidate(owner); PhoneAutomation.service?.invalidateObservations()
        }
    }
    fun wantsAdb(context: Context, args: JSONObject, owner: String): Boolean {
        val value = args.optString("backend", preference(context)); require(value in setOf("auto", "adb", "legacy")) { "PHONE_BACKEND_INVALID" }
        return value == "adb" || value == "auto" && AdbManager.get(context).status(owner).optBoolean("available") && AgentPermissions.get(context).enabled(AgentCapability.LOCAL_ADB, owner)
    }
    fun available(context: Context, owner: String): Boolean = PhoneAutomation.service != null || wantsAdb(context, JSONObject(), owner)
    fun guard(context: Context, owner: String, token: CancellationToken) {
        check(VirtualDisplays.mainAllowed(owner)) { "VIRTUAL_DISPLAY_MAIN_SCREEN_BLOCKED" }
        token.throwIfCancelled(); val p = AgentPermissions.get(context)
        check(!p.taskEnded(owner)) { "PHONE_TASK_ENDED" }
        check((context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) { "PHONE_SCREEN_NOT_INTERACTIVE" }
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) { "PHONE_LOCKED" }
        check(!ScreenShareSession.mainVisible && !ScreenShareSession.controlsVisible && !ScreenShareSession.appActivitiesVisible) { "PHONE_APP_FOREGROUND" }
    }
    fun samePage(before: JSONObject, after: JSONObject) {
        for (k in listOf("package", "window", "width", "height", "rotation", "display_id")) check(before.opt(k).toString() == after.opt(k).toString()) { "PHONE_WINDOW_CHANGED: reobserve" }
        if(before.has("structural_epoch") && after.has("structural_epoch")) check(before.getLong("structural_epoch")==after.getLong("structural_epoch")) { "PHONE_STRUCTURAL_CHANGED: reobserve" }
    }
    @Synchronized fun backend(context: Context, owner: String, token: CancellationToken): PhoneBackend {
        guard(context, owner, token)
        VirtualDisplays.sharedMainBridge(owner)?.let { return AdbBridgeBackend(it,mainTarget=true) }
        val key = context.filesDir.absolutePath + ":" + owner
        // Android permits a single active UiAutomation registration. Never share an owner's lease with another task.
        bridges.entries.filter { it.key != key }.forEach { (oldKey, oldBridge) ->
            oldBridge.close(); bridges.remove(oldKey); PhoneObservationStore.invalidate(oldBridge.owner); routes.remove(oldKey)
        }
        val saved = bridges[key]
        val connectionEpoch = AdbManager.get(context).connectionEpoch
        val cooling = cooldowns[key]?.let { it.first == connectionEpoch && SystemClock.elapsedRealtime() < it.second } == true
        val bridge = if (saved?.valid() == true) saved else if (cooling) null else {
            saved?.close(); bridges.remove(key)
            val candidate = PhoneBridge(context.applicationContext, owner, token)
            try { candidate.start(token).also { bridges[key] = it; failures.remove(key); cooldowns.remove(key) } }
            catch (e: Exception) { candidate.close(); token.throwIfCancelled(); failures[key] = e.message ?: "BRIDGE_UNAVAILABLE"; cooldowns[key] = connectionEpoch to (SystemClock.elapsedRealtime()+30000); null }
        }
        val selected = if (bridge?.capabilities?.optBoolean("ui") == true) AdbBridgeBackend(bridge) else AdbShellBackend(context, owner)
        if (routes.put(key, selected.name) != selected.name) { PhoneObservationStore.invalidate(owner); PhoneAutomation.service?.invalidateObservations() }
        return selected
    }
    fun stop(context: Context, owner: String) {
        val key = context.filesDir.absolutePath + ":" + owner
        bridges.remove(key)?.close(); routes.remove(key); cooldowns.remove(key); failures.remove(key); PhoneObservationStore.invalidate(owner)
    }
    @Synchronized fun stopAll() {
        bridges.values.forEach { it.close(); PhoneObservationStore.invalidate(it.owner) }
        bridges.clear(); routes.clear(); cooldowns.clear(); failures.clear()
    }
    fun status(context: Context, owner: String): JSONObject {
        val key = context.filesDir.absolutePath + ":" + owner; val b = bridges[key]
        return JSONObject().put("preference", preference(context)).put("adb", AdbManager.get(context).status(owner))
            .put("selected_backend", routes[key] ?: if (wantsAdb(context, JSONObject(), owner)) "adb_unprobed" else "legacy")
            .put("bridge_connected", b?.valid() == true).put("bridge_capabilities", if (b?.valid() == true) b.capabilities else JSONObject())
            .put("bridge_error", failures[key] ?: "").put("legacy_available", PhoneAutomation.service != null)
            .put("metrics", synchronized(metrics) { JSONArray(metrics.filter { it.optString("owner") == owner }) })
    }
    fun image(context: Context, owner: String, backend: PhoneBackend, token: CancellationToken): Message.Attachment = PhoneOverlayScope.capture(token) {
        check(AgentPermissions.get(context).enabled(AgentCapability.SCREEN, owner)) { "AGENT_PERMISSION_REQUIRED: SCREEN" }
        val revision = AgentPermissions.get(context).revision
        var bytes = backend.capture(token)
        require(bytes.size in 1..PhoneBridgeProtocol.IMAGE_LIMIT) { "PHONE_IMAGE_LIMIT" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong()*bounds.outHeight <= 33554432) { "PHONE_IMAGE_PIXEL_LIMIT" }
        val edge=maxOf(bounds.outWidth,bounds.outHeight)
        if(edge>2048) {
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("PHONE_IMAGE_DECODE_FAILED")
            var resized: android.graphics.Bitmap?=null
            try {
                val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,(bounds.outWidth*2048/edge).coerceAtLeast(1),(bounds.outHeight*2048/edge).coerceAtLeast(1),true); resized=scaled
                bytes=java.io.ByteArrayOutputStream().use { out -> check(scaled.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)); out.toByteArray() }
                require(bytes.size<=PhoneBridgeProtocol.IMAGE_LIMIT) { "PHONE_IMAGE_LIMIT" }
            } finally { resized?.recycle(); bitmap.recycle() }
        }
        token.throwIfCancelled(); check(AgentPermissions.get(context).revision == revision) { "AGENT_PERMISSION_REVOKED" }
        Message.Attachment("phone-screen.png", null, "image/png", bytes.size.toLong(), "image").also { it.dataUri = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) }
    }
    fun record(owner: String, operation: String, backend: String, start: Long, status: String) = synchronized(metrics) {
        metrics.addLast(JSONObject().put("owner", owner).put("operation", operation).put("backend", backend).put("elapsed_ms", SystemClock.elapsedRealtime()-start).put("status", status))
        while (metrics.size > 100) metrics.removeFirst()
    }
}

/** Capture scope is independent from AccessibilityService and always restores the pet. */
internal object PhoneOverlayScope {
    private var depth = 0 // Main-thread only; nested chat/capture scopes share the same visibility lease.
    fun hide(token: CancellationToken) = main(token) { depth++; ScreenShareSession.service?.setPhoneActionHidden(true) }
    fun restore() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            depth = (depth-1).coerceAtLeast(0); ScreenShareSession.service?.setPhoneActionHidden(depth>0)
        }
    }
    fun main(token: CancellationToken, operation: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1); var failure: Throwable? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { token.throwIfCancelled(); operation() } catch (e: Throwable) { failure = e } finally { latch.countDown() }
        }
        while (!latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) token.throwIfCancelled()
        failure?.let { throw it }
    }
    fun <T> capture(token: CancellationToken, operation: () -> T): T {
        hide(token)
        try { token.sleep(80); return operation() }
        finally { restore() }
    }
}
