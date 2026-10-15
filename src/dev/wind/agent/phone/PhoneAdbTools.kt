package dev.wind.agent.phone

import android.content.Context
import android.os.SystemClock
import dev.wind.agent.CancellationToken
import dev.wind.agent.Tools
import dev.wind.agent.adb.AdbCommands
import dev.wind.agent.adb.AdbManager
import dev.wind.agent.permissions.AgentCapability
import dev.wind.agent.permissions.AgentPermissions
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Unified phone contract; an accepted mutation never triggers backend fallback. */
internal class PhoneAdbTools(private val context: Context, private val owner: String) {
    private val permissions = AgentPermissions.get(context)
    private val manager = AdbManager.get(context)
    fun run(name: String, args: JSONObject, token: CancellationToken, observer: Tools.Observer?): String = PhoneCoordinator.run(token) {
        if(name=="phone_observe" && args.optString("mode",if(PhoneAutomation.mode(context)==PhoneAutomation.Mode.VISUAL) "visual" else "ui")!="ui") check(observer!=null) { "PHONE_IMAGE_CHANNEL_REQUIRED" }
        PhoneAutomation.yieldToTarget(token)
        val start = SystemClock.elapsedRealtime()
        val backend = PhoneBackends.backend(context, owner, token)
        if (name in setOf("phone_action","phone_launch","phone_sequence")) PhoneManagedRecovery.activate(context, owner, token)
        val result = when (name) {
            "phone_observe" -> observe(backend, args, token, observer).json
            "phone_action" -> action(backend, args, token, observer)
            "phone_wait" -> wait(backend, args, token, observer)
            "phone_launch" -> launch(backend, args, token, observer)
            "phone_sequence" -> sequence(backend, args, token, observer)
            else -> error("PHONE_ADB_TOOL_UNSUPPORTED")
        }
        PhoneBackends.record(owner, name, backend.name, start, result.optString("status"))
        result.toString()
    }
    internal fun observe(backend: PhoneBackend, args: JSONObject, token: CancellationToken, observer: Tools.Observer? = null): PhoneObservation {
        val default = if (PhoneAutomation.mode(context) == PhoneAutomation.Mode.VISUAL) "visual" else "ui"
        val mode = args.optString("mode", default)
        require(mode in setOf("ui", "visual", "hybrid")) { "PHONE_MODE_INVALID" }
        val enum = PhoneAutomation.Mode.valueOf(mode.uppercase())
        PhoneImageAnnotator.validate(args, enum)
        check(permissions.enabled(if (mode == "visual") AgentCapability.SCREEN else AgentCapability.ACCESSIBILITY, owner)) { "AGENT_PERMISSION_REQUIRED" }
        val revision=permissions.revision
        val result = backend.observe(mode, token, args)
        token.throwIfCancelled(); check(permissions.revision==revision && !permissions.taskEnded(owner)) { "AGENT_PERMISSION_REVOKED" }
        check(result.optString("status") == "COMPLETED") { result.optString("error", "PHONE_OBSERVE_FAILED") }
        val o = PhoneObservation(owner, backend.name, manager.connectionEpoch, permissions.revision, result)
        result.put("backend", backend.name).put("session_epoch", o.epoch).put("owner", owner).put("content_is_untrusted", true)
            .put("coordinate_space", "full_screen_normalized_0_1000").put("image_captured", false)
        if (mode != "ui" && observer != null) {
            val image = PhoneBackends.image(context, owner, backend, token)
            val fresh = backend.observe("visual", token); PhoneBackends.samePage(result, fresh)
            if (args.optBoolean("ocr")) {
                val vision = PhoneVision.recognize(context, image, token)
                result.put("ocr", vision.json()); vision.targets.forEach { o.visuals[it.id] = it }
            }
            // Reuse the established annotation implementation with value-only facts.
            val nodes = o.nodes.associate { (id, f) -> id to PhoneControlService.Node(f, JSONObject()) }
            val stamp = PhoneFreshness.Stamp(owner, o.pkg, result.getString("window").hashCode(), result.getInt("width"), result.getInt("height"), 0, 0, SystemClock.elapsedRealtime(), result.getInt("rotation"))
            val annotation = PhoneControlService.Observation(o.id, enum, stamp, nodes, result, o.visuals)
            check(permissions.revision==revision && permissions.enabled(AgentCapability.SCREEN,owner)) { "AGENT_PERMISSION_REVOKED" }
            PhoneBackends.samePage(result,backend.observe("visual",token))
            token.throwIfCancelled()
            observer.onPhoneImage(PhoneImageAnnotator.annotate(image, annotation, args))
            result.put("image_captured", true)
        } else check(mode == "ui" || observer == null) { "PHONE_IMAGE_CHANNEL_REQUIRED" }
        PhoneObservationStore.add(o)
        return o
    }
    private fun owned(backend: PhoneBackend, args: JSONObject): PhoneObservation {
        val o = PhoneObservationStore.take(args.getString("observation_id")) ?: error("PHONE_OBSERVATION_EXPIRED: reobserve after switching backend")
        check(o.owner == owner && o.backend == backend.name && o.epoch == manager.connectionEpoch && o.revision == permissions.revision) { "PHONE_OBSERVATION_SESSION_CHANGED" }
        check(System.currentTimeMillis()-o.json.getLong("captured_at") in 0..15000) { "PHONE_OBSERVATION_EXPIRED" }
        return o
    }
    internal fun validateAction(args: JSONObject) {
        require(args.getString("action") in setOf("tap", "long_press", "swipe", "click", "dismiss", "set_text", "paste_text", "type_text", "ime_action", "scroll", "back", "home", "recents", "notifications", "quick_settings")) { "PHONE_ACTION_UNSUPPORTED" }
        args.optString("text").let { require(it.length <= 16000 && !it.contains('\u0000')) { "PHONE_TEXT_INVALID" } }
        for (key in listOf("x", "y", "x2", "y2")) if (args.has(key)) require(args.getDouble(key).isFinite() && args.getDouble(key) in 0.0..1000.0) { "PHONE_COORDINATE_INVALID" }
        require(args.optInt("duration_ms", 300) in 1..1500 && args.optLong("settle_ms", 150) in 0..3000) { "PHONE_DURATION_INVALID" }
        require(args.optString("observe_after", "none") in setOf("none", "ui", "visual", "hybrid")) { "PHONE_MODE_INVALID" }
        when (args.getString("action")) {
            "tap", "long_press" -> require(args.has("visual_id") || args.has("x") && args.has("y")) { "PHONE_COORDINATE_REQUIRED" }
            "swipe" -> require(listOf("x", "y", "x2", "y2").all(args::has)) { "PHONE_COORDINATE_REQUIRED" }
            "click", "scroll", "dismiss" -> require(args.has("node_id")) { "PHONE_NODE_REQUIRED" }
            "set_text", "paste_text", "type_text" -> require(args.has("text") && args.getString("text").isNotEmpty()) { "PHONE_TEXT_REQUIRED" }
        }
        if (args.getString("action") == "scroll") require(args.optString("direction","down") in setOf("up","down","left","right","forward","backward")) { "PHONE_SCROLL_DIRECTION_INVALID" }
    }
    internal fun action(backend: PhoneBackend, arguments: JSONObject, token: CancellationToken, observer: Tools.Observer? = null,
        authority: (() -> Unit)? = null, beforeText: (() -> JSONObject)? = null): JSONObject {
        val args = JSONObject(arguments.toString()); validateAction(args)
        var o = owned(backend, args)
        if (args.getString("action") in setOf("set_text","paste_text","type_text") && o.mode != "visual") require(args.has("node_id")) { "PHONE_NODE_REQUIRED" }
        val target = args.optString("node_id").takeIf { it.isNotEmpty() }?.let { id -> o.nodes.singleOrNull { it.first == id }?.second ?: error("PHONE_NODE_UNKNOWN") }
            ?: if (args.has("x") && args.has("y") && o.mode != "visual") PhoneNodeMatcher.deepestAt(o.nodes.map { it.second }, PhoneApprovalPolicy.normalized(args,"x",o.json.getInt("width")), PhoneApprovalPolicy.normalized(args,"y",o.json.getInt("height"))) else null
        if(args.getString("action")=="dismiss") {
            check(PhoneApprovalPolicy.mode(context)==PhoneApprovalPolicy.Mode.FULL && o.mode!="visual" && target!=null && PhoneInterruptions.negativeButton(target)) { "PHONE_DISMISS_NEGATIVE_UI_NODE_REQUIRED: visual dismissal can use explicit legacy backend" }
        }
        if (args.has("visual_id")) {
            require(!args.has("x") && !args.has("y") && args.getString("action") in setOf("tap", "long_press")) { "PHONE_VISUAL_SELECTOR_CONFLICT" }
            val old = o.visuals[args.getString("visual_id")] ?: error("PHONE_VISUAL_TARGET_UNKNOWN")
            val image = PhoneBackends.image(context, owner, backend, token)
            val live = PhoneVision.recognize(context, image, token)
            check(live.status == "OK" && !live.truncated) { "PHONE_OCR_NOT_READY" }
            val now = PhoneVisual.resolve(old, live.targets) ?: error("PHONE_VISUAL_TARGET_CHANGED")
            args.put("x", now.x).put("y", now.y)
        }
        val categories = PhoneApprovalPolicy.categories(o.pkg, args.getString("action"), args, target,
            o.nodes.joinToString(" ") { it.second.text + " " + it.second.desc }, o.mode == "visual")
        val scope = if (categories.isEmpty()) null else PhoneApprovalPolicy.Scope(categories.sorted().joinToString("+"), o.pkg + ":" + args.optString("node_id"))
        val ticket = if (authority == null) PhoneApprovalPolicy.authorize(context, owner, "phone_action", args.put("backend", "adb"), scope, token)
            ?: return JSONObject().put("status", "REJECTED").put("submitted", false) else null
        PhoneAutomation.yieldToTarget(token)
        fun checkAuthority() { token.throwIfCancelled(); authority?.invoke(); ticket?.let(PhoneApprovalPolicy::check); PhoneBackends.guard(context, owner, token) }
        checkAuthority()
        if (ticket != null && o.mode != "visual") {
            val live = backend.observe("ui", token)
            check(live.optString("status") == "COMPLETED") { "PHONE_PREFLIGHT_FAILED" }; PhoneBackends.samePage(o.json,live)
            val facts = PhoneObservation(owner,backend.name,o.epoch,o.revision,live).nodes.map { it.second }
            val now = target?.let { old -> facts.singleOrNull { it == old } ?: error("TARGET_CHANGED") }
            PhoneApprovalPolicy.recheck(ticket,o.pkg,args.getString("action"),args,now,facts,false)
        }
        var acceptedEffect: JSONObject? = null
        PhoneOverlayScope.hide(token)
        try {
            var result: JSONObject
            if (args.getString("action") in setOf("paste_text", "type_text") && o.mode == "visual" && target == null) {
                var begun = false
                try {
                    checkAuthority(); begun = true
                    val verified = PhoneAdbIme.paste(context, owner, o.pkg, args.getString("text"), args.optBoolean("replace", false), token, ::checkAuthority)
                    result = JSONObject().put("status", if (verified) "COMPLETED" else "UNKNOWN").put("submitted", true).put("text_verified", verified)
                } catch (e: Exception) { result = JSONObject().put("status", if (begun) "UNKNOWN" else "NOT_SUBMITTED").put("submitted", begun).put("error", e.message) }
                PhoneObservationStore.take(o.id, true)
            } else if (args.getString("action") in setOf("set_text", "paste_text", "type_text") && target != null) {
                check(target.editable && target.actionable) { "PHONE_EDITOR_REQUIRED" }
                // Focus may start the real keyboard panel. Reobserve only BEFORE text submission.
                val focus = JSONObject().put("observation_id", o.id).put("action", "click").put("node_id", args.getString("node_id"))
                val focused = backend.action(focus, token)
                if (focused.optBoolean("submitted")) acceptedEffect = focused
                PhoneObservationStore.take(o.id, true)
                if (focused.optString("status") !in setOf("DISPATCHED", "COMPLETED")) return focused
                val started = SystemClock.elapsedRealtime(); var previous = ""; var equalSince = started
                do {
                    token.sleep(70); checkAuthority()
                    val fresh = observe(backend, JSONObject().put("mode", "ui"), token)
                    check(fresh.pkg == o.pkg) { "PHONE_EDITOR_PACKAGE_CHANGED" }
                    val matches = fresh.nodes.filter { (_, f) -> f.editable && f.actionable && f.resId == target.resId && f.cls == target.cls }
                    check(matches.size == 1 && !fresh.json.optBoolean("truncated")) { "PHONE_EDITOR_NOT_UNIQUE" }
                    val signature = fresh.json.optString("window") + fresh.json.optInt("rotation") + fresh.json.optBoolean("ime_visible") + matches.single().second.let { "${it.left}:${it.top}:${it.right}:${it.bottom}" }
                    val now = SystemClock.elapsedRealtime(); if (signature != previous) { previous = signature; equalSince = now }
                    o = fresh; args.put("observation_id", o.id).put("node_id", matches.single().first)
                    if (now-started >= 450 && now-equalSince >= 200) break
                    check(now-started < 1500) { "PHONE_EDITOR_PANEL_NOT_STABLE" }
                } while (true)
                checkAuthority()
                beforeText?.invoke()?.let { args.put("peer_guard",it) }
                result = backend.action(args, token)
                if (result.optBoolean("submitted")) acceptedEffect = result
                PhoneObservationStore.take(o.id, true)
                if (result.optString("status") in setOf("DISPATCHED", "COMPLETED")) {
                    var verified = false; val until = SystemClock.elapsedRealtime()+1200
                    do {
                        token.sleep(80); checkAuthority()
                        val fresh = observe(backend, JSONObject().put("mode", "ui"), token)
                    val expected = if (args.getString("action") == "type_text") target.text + args.getString("text") else args.getString("text")
                    if (fresh.pkg == o.pkg && !fresh.json.optBoolean("truncated") && fresh.nodes.count { (_, f) -> f.editable && f.actionable && f.text.replace("\r\n", "\n") == expected.replace("\r\n", "\n") } == 1) { verified = true; break }
                    } while (SystemClock.elapsedRealtime()<until)
                    result.put("text_verified", verified)
                    if (!verified) result.put("status", "UNKNOWN").put("error", "PHONE_TEXT_COMPLETION_UNKNOWN: inspect; do not resend")
                }
            } else {
                checkAuthority(); result = backend.action(args, token); if (result.optBoolean("submitted")) acceptedEffect = result; PhoneObservationStore.take(o.id, true)
            }
            result.put("backend", backend.name).put("retry_safe", !result.optBoolean("submitted"))
                .put("note", "Dispatch is not verified delivery/business success; UNKNOWN must not be retried")
            if (result.optString("status") in setOf("DISPATCHED", "COMPLETED")) {
                if (args.optString("observe_after", "none") != "none") {
                    try {
                        val mode=args.getString("observe_after")
                        val ready=PhoneReady.await(
                            { checkAuthority(); observe(backend,JSONObject().put("mode",if(mode=="visual") "visual" else "ui").put("fresh",true),token).json },
                            { token.sleep(it) },{ SystemClock.elapsedRealtime() },args.optLong("settle_ms",1200))
                        val fresh=ready.getJSONObject("observation")
                        result.put("settle",JSONObject().put("settled",ready.getString("status")=="MET").put("waited_ms",ready.getLong("waited_ms")))
                        result.put("observation",if(mode=="ui") fresh else observe(backend,JSONObject(args.toString()).put("mode",mode),token,observer).json)
                        result.put("no_visible_effect",o.pkg==fresh.optString("package") && o.json.opt("nodes").toString()==fresh.opt("nodes").toString() && PhoneReady.key(o.json)==PhoneReady.key(fresh))
                    }
                    catch (e: Exception) { result.put("observation_error",e.message).put("retry_safe",false) }
                } else token.sleep(args.optLong("settle_ms",150))
            }
            return result
        } catch (e: Exception) {
            if (acceptedEffect != null) {
                PhoneObservationStore.take(o.id,true)
                return JSONObject().put("status","UNKNOWN").put("submitted",true).put("retry_safe",false).put("last_receipt",acceptedEffect)
                    .put("error",e.message).put("note","A prior effect was accepted; inspect before any new action. No replay or backend fallback.")
            }
            throw e
        } finally { PhoneOverlayScope.restore() }
    }
    private fun launch(backend: PhoneBackend, args: JSONObject, token: CancellationToken, observer: Tools.Observer?): JSONObject {
        val pkg = args.getString("package"); require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "PHONE_PACKAGE_INVALID" }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: error("PHONE_APP_NOT_LAUNCHABLE")
        val component = intent.component?.flattenToShortString() ?: error("PHONE_APP_COMPONENT_MISSING")
        val ticket = PhoneApprovalPolicy.authorize(context, owner, "phone_launch", args, null, token) ?: return JSONObject().put("status", "REJECTED")
        val result = manager.execute("am start --display 0 --user 0 -n " + AdbCommands.quote(component), owner, token, beforeSend = { PhoneApprovalPolicy.check(ticket); PhoneBackends.guard(context, owner, token) })
        PhoneObservationStore.invalidate(owner)
        val output=result.json().put("note", "Launch dispatch is not foreground verification")
        if(result.status!="COMPLETED") return output
        try {
            if(pkg==context.packageName) {
                val start=SystemClock.elapsedRealtime()
                while(!dev.wind.agent.screen.ScreenShareSession.appActivitiesVisible && !dev.wind.agent.screen.ScreenShareSession.mainVisible && SystemClock.elapsedRealtime()-start<2500) token.sleep(90)
                val visible=dev.wind.agent.screen.ScreenShareSession.appActivitiesVisible || dev.wind.agent.screen.ScreenShareSession.mainVisible
                output.put("foreground_verified",visible).put("restored_app",visible).put("waited_ms",SystemClock.elapsedRealtime()-start)
                    .put("note","Vega foreground restoration uses lifecycle evidence; no self-control snapshot or corrective coordinates")
            } else if(args.optString("observe_after","none")!="none") {
                val mode=args.getString("observe_after")
                val ready=PhoneReady.await({ observe(backend,JSONObject().put("mode",if(mode=="visual") "visual" else "ui").put("fresh",true),token).json },
                    { token.sleep(it) },{ SystemClock.elapsedRealtime() },2500,pkg)
                output.put("foreground_verified",ready.getString("status")=="MET").put("waited_ms",ready.getLong("waited_ms"))
                    .put("observation",if(mode=="ui") ready.getJSONObject("observation") else observe(backend,JSONObject(args.toString()).put("mode",mode),token,observer).json)
            }
        } catch(e: Exception) { output.put("foreground_verified",false).put("observation_error",e.message).put("retry_safe",false) }
        return output
    }
    private fun matches(f: NodeFacts, target: JSONObject): Boolean {
        return PhoneWaitTarget.parse(JSONObject().put("target",target)).matches(f)
    }
    internal fun recover(token: CancellationToken, budget: PhoneRecoveryBudget): JSONObject {
        check(PhoneApprovalPolicy.mode(context) == PhoneApprovalPolicy.Mode.FULL) { "PHONE_FULL_MODE_REQUIRED" }
        if (PhoneAutomation.mode(context) == PhoneAutomation.Mode.VISUAL) return JSONObject().put("status","VISUAL_RECOVERY_REQUIRES_EXPLICIT_OCR_ACTION")
        val backend = PhoneBackends.backend(context,owner,token)
        val o = observe(backend,JSONObject().put("mode","ui"),token)
        if (o.json.optBoolean("truncated")) return JSONObject().put("status","NONE")
        val candidate = PhoneInterruptions.automatic(o.nodes.map { it.second },false,o.json.getInt("height")) ?: return JSONObject().put("status","NONE")
        val key = "${o.pkg}:${o.json.getString("window")}:${PhoneVisual.key(candidate.text)}"
        if (!budget.reserve(key,SystemClock.elapsedRealtime())) return JSONObject().put("status","SUPPRESSED")
        val id = o.nodes.single { it.second == candidate.node }.first
        val result = action(backend,JSONObject().put("observation_id",o.id).put("action","click").put("node_id",id).put("backend","adb"),token)
        return JSONObject().put("status","RECOVERY_ATTEMPTED").put("action_result",result)
    }
    private fun wait(backend: PhoneBackend, args: JSONObject, token: CancellationToken, observer: Tools.Observer?): JSONObject {
        val until = args.optString("until", "idle"); require(until in setOf("idle", "visible", "text", "gone", "package", "editor")) { "PHONE_WAIT_UNKNOWN" }
        val timeout = args.optLong("timeout_ms", 3000); require(timeout in 0..15000)
        val target = args.optJSONObject("target") ?: JSONObject().also { if (args.has("target")) it.put("text", args.getString("target")) }
        if (until in setOf("visible", "text", "gone")) require(target.length() > 0) { "PHONE_WAIT_TARGET_REQUIRED" }
        val start = SystemClock.elapsedRealtime(); var signature = ""; var since = start; var last: PhoneObservation
        val mode=args.optString("mode",if(PhoneAutomation.mode(context)==PhoneAutomation.Mode.VISUAL) "visual" else "ui")
        val visual=mode=="visual"
        if(visual && until in setOf("visible","text","gone")) {
            require(args.optBoolean("ocr",false)) { "PHONE_VISUAL_WAIT_REQUIRES_EXPLICIT_OCR" }
            PhoneWaitTarget.parse(args).validateVisual()
        }
        do {
            last = observe(backend, JSONObject().put("mode", mode), token)
            val current = last.json.optString("window") + if(visual) last.json.optLong("event_epoch").toString() else last.nodes.joinToString { it.second.toString() }; val now = SystemClock.elapsedRealtime()
            if (signature != current) { signature = current; since = now }
            val found = if(until in setOf("visible","text","gone")) last.nodes.filter { it.second.visible && matches(it.second, target) } else emptyList()
            val vision=if(visual && until in setOf("visible","text","gone")) PhoneVision.recognize(context,PhoneBackends.image(context,owner,backend,token),token) else null
            if(vision!=null) check(vision.status=="OK" && !vision.truncated) { "PHONE_OCR_NOT_READY" }
            val visualFound=vision?.targets?.filter { PhoneWaitTarget.parse(args).matches(it) }.orEmpty()
            val met = when (until) {
                "idle" -> now-since >= args.optLong("stable_ms", 200).coerceIn(100,1500)
                "package" -> last.pkg == args.getString("package")
                "editor" -> if(visual) PhoneInputService.instance?.accepts(last.pkg)==true else last.nodes.any { it.second.editable && it.second.actionable }
                "gone" -> !last.json.optBoolean("truncated") && if(visual) visualFound.isEmpty() else found.isEmpty()
                else -> if(visual) visualFound.isNotEmpty() else found.isNotEmpty()
            }
            if (met || now-start >= timeout) return JSONObject().put("status", if (met) "MET" else "TIMEOUT").put("waited_ms", now-start).put("observation", last.json)
            token.sleep(minOf(if(visual) 300 else 100, (timeout-(SystemClock.elapsedRealtime()-start)).coerceAtLeast(0)))
        } while (true)
    }
    private fun sequence(backend: PhoneBackend, args: JSONObject, token: CancellationToken, observer: Tools.Observer?): JSONObject {
        val steps = args.getJSONArray("steps"); require(steps.length() in 1..8) { "PHONE_SEQUENCE_LIMIT" }
        val initial = owned(backend, args)
        require(initial.mode=="ui" && !initial.json.optBoolean("truncated") && header(initial).isNotBlank()) { "PHONE_SEQUENCE_VERIFIABLE_UI_HEADER_REQUIRED: use individual actions for visual/custom pages" }
        // Validate the WHOLE sequence before any mutation. Only same-page typed operations are accepted.
        val validated = (0 until steps.length()).map { i -> JSONObject(steps.getJSONObject(i).toString()).also { s ->
            validateAction(JSONObject(s.toString()).put("node_id", "validate_only")); require(s.getString("action") in setOf("tap", "click", "set_text", "paste_text", "scroll", "swipe")) { "PHONE_SEQUENCE_NAVIGATION_FORBIDDEN" }
            require(s.has("expected_package") && s.getString("expected_package") == initial.pkg) { "PHONE_SEQUENCE_PACKAGE_GUARD_REQUIRED" }
            require(s.has("target") || s.getString("action") in setOf("tap", "swipe")) { "PHONE_SEQUENCE_SELECTOR_REQUIRED" }
            s.remove("observation_id"); s.remove("node_id")
        } }
        val results = JSONArray()
        for (step in validated) {
            val fresh = observe(backend, JSONObject().put("mode", initial.mode), token)
            if(fresh.json.optBoolean("truncated")) return JSONObject().put("status","STOPPED").put("error","PHONE_SEQUENCE_TRUNCATED").put("steps",results)
            try { PhoneBackends.samePage(initial.json, fresh.json) }
            catch (e: Exception) { return JSONObject().put("status","STOPPED").put("error",e.message).put("steps",results) }
            if (header(initial) != header(fresh)) return JSONObject().put("status","STOPPED").put("error","PHONE_SEQUENCE_PAGE_IDENTITY_CHANGED").put("steps",results)
            val target = step.optJSONObject("target")
            if (target != null) {
                val matches = fresh.nodes.filter { it.second.actionable && matches(it.second, target) }
                if (matches.size != 1) return JSONObject().put("status", "STOPPED").put("error", "PHONE_SEQUENCE_TARGET_NOT_UNIQUE").put("steps", results)
                step.put("node_id", matches.single().first)
            }
            step.put("observation_id", fresh.id).put("backend", "adb")
            val result = action(backend, step, token, observer); results.put(result)
            if (result.optString("status") !in setOf("DISPATCHED", "COMPLETED")) return JSONObject().put("status", "STOPPED").put("steps", results)
        }
        return JSONObject().put("status", "DISPATCHED").put("steps", results).put("business_success_verified", false)
    }
    private fun header(o: PhoneObservation): String = o.nodes.map { it.second }.filter { it.visible && !it.editable && it.top>=o.json.getInt("height")/25 &&
        it.bottom<=o.json.getInt("height")/5 && (it.text.isNotBlank() || it.desc.isNotBlank()) }.joinToString("|") { it.resId+":"+it.text+":"+it.desc }
}
