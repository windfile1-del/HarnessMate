package dev.wind.agent.phone

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import dev.wind.agent.CancellationToken
import dev.wind.agent.Tools
import dev.wind.agent.screen.ScreenShareSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native Android tools with separate user-selected safe/full phone authority. */
internal class PhoneTools(private val context: Context, private val owner: String) {
    fun run(name: String, arguments: JSONObject, token: CancellationToken, observer: Tools.Observer?): String {
        if(name=="phone_chat_guardian") return ChatGuardianTools.request(context,arguments,token).toString()
        val resolved=ChatSessionManager.controlOwner(context,owner,arguments)
        if(resolved!=owner) return PhoneVirtualTools(context,resolved).run(name,arguments,token,observer)
        val args = VirtualDisplays.scopeArgs(context,owner,name,arguments)
        if(name=="phone_display" || VirtualDisplays.wants(context,owner,args))
            return PhoneVirtualTools(context,owner).run(name,args,token,observer)
        if(VirtualDisplays.sharedMainBridge(owner)!=null && name in setOf("phone_observe","phone_action","phone_wait","phone_launch","phone_flow","phone_sequence")) args.put("backend","adb")
        if(name == "phone_flow") {
            dev.wind.agent.permissions.AgentPermissionGate(context).error(name,args,owner)?.let { error(it) }
            return PhoneWorkflow(context,owner).run(args,token,observer)
        }
        if(name in setOf("phone_action","phone_launch") && !args.has("observe_after"))
            args.put("observe_after",if(PhoneAutomation.mode(context)==PhoneAutomation.Mode.VISUAL) "visual" else "ui")
        if(name=="phone_action" && args.has("target")) return PhoneCoordinator.run(token) {
            require(args.has("expected_package") && !args.has("node_id") && !args.has("visual_id") && !args.has("x") && !args.has("y")) { "PHONE_TARGET_SELECTOR_CONFLICT" }
            check(PhoneAutomation.mode(context)!=PhoneAutomation.Mode.VISUAL) { "PHONE_SELECTOR_REQUIRES_UI" }
            val selector=args.getJSONObject("target"); PhoneWaitTarget.parse(JSONObject().put("target",selector))
            val timeout=args.optLong("target_timeout_ms",2500); require(timeout in 200..5000)
            val ready=PhoneReady.await(
                { JSONObject(run("phone_observe",JSONObject().put("screen","main").put("mode","ui").put("backend",args.optString("backend","auto")).put("fresh",true),token,null)) },
                { token.sleep(it) },{ android.os.SystemClock.elapsedRealtime() },timeout,args.getString("expected_package"),selector)
            val fresh=ready.getJSONObject("observation")
            if(ready.getString("status")!="MET") return@run JSONObject().put("status","NOT_SUBMITTED").put("submitted",false)
                .put("error","PHONE_TARGET_NOT_READY").put("observation",fresh).toString()
            args.remove("target"); args.put("observation_id",fresh.getString("observation_id")).put("node_id",PhoneReady.resolve(fresh,selector))
            if(args.getString("action")=="long_press") {
                val nodes=fresh.getJSONArray("nodes")
                val f=PhoneObservation.facts((0 until nodes.length()).map { nodes.getJSONObject(it) }.single { PhoneReady.nodeId(it)==args.getString("node_id") })
                args.put("x",f.centerX*1000.0/(fresh.getInt("width")-1)).put("y",f.centerY*1000.0/(fresh.getInt("height")-1))
            }
            run(name,args,token,observer)
        }
        try { return execute(name,args,token,observer) }
        catch(e: Exception) {
            token.throwIfCancelled()
            val message=e.message.orEmpty()
            if(name!="phone_action" || !listOf("PHONE_OBSERVATION_REQUIRED", "PHONE_OBSERVATION_STALE", "PHONE_OBSERVATION_EXPIRED", "PHONE_OBSERVATION_SESSION_CHANGED", "PHONE_WINDOW_CHANGED", "PHONE_STRUCTURAL_CHANGED", "TARGET_CHANGED", "PHONE_NODE_UNKNOWN").any { message.startsWith(it) }) throw e
            val result=JSONObject().put("status","NOT_SUBMITTED").put("submitted",false).put("error",message)
                .put("note","Stale target rejected before submission. Use this fresh observation or a unique target selector; do not reuse old coordinates/node IDs.")
            runCatching { JSONObject(run("phone_observe",JSONObject().put("screen","main").put("backend",args.optString("backend","auto")).put("mode",if(PhoneAutomation.mode(context)==PhoneAutomation.Mode.VISUAL) "visual" else "ui"),token,observer)) }
                .onSuccess { result.put("observation",it) }.onFailure { result.put("observation_error",it.message) }
            return result.toString()
        }
    }
    private fun execute(name: String, arguments: JSONObject, token: CancellationToken, observer: Tools.Observer?): String {
        // Copy before approval so the reviewed arguments cannot be changed by another caller.
        val args = JSONObject(arguments.toString())
        dev.wind.agent.permissions.AgentPermissionGate(context).error(name, args, owner)?.let { error(it) }
        if (name in setOf("phone_observe", "phone_action", "phone_wait", "phone_launch", "phone_sequence") && PhoneBackends.wantsAdb(context, args, owner))
            return PhoneAdbTools(context, owner).run(name, args, token, observer)
        if (name in setOf("phone_observe","phone_action","phone_wait","phone_sequence")) PhoneBackends.enterLegacy(context,owner)
        if (name == "phone_sequence") return PhoneLegacySequence(context, owner).run(args, token, observer)
        if (name == "phone_clipboard") {
            val action = args.optString("action", "copy")
            if (action == "paste") return run("phone_action", JSONObject(args.toString()).put("action", "paste_text"), token, observer)
            require(action in setOf("copy", "clear")) { "PHONE_CLIPBOARD_ACTION_INVALID" }
            if (action == "copy") require(args.getString("text").length in 1..16000) { "PHONE_CLIPBOARD_TEXT_INVALID" }
            val ticket = PhoneApprovalPolicy.authorize(context, owner, name, args, null, token) ?: return "REJECTED: clipboard operation not approved"
            token.throwIfCancelled(); PhoneApprovalPolicy.check(ticket)
            if (action == "copy") PhoneClipboard.copy(context, args.getString("text")) else PhoneClipboard.clear(context)
            return JSONObject().put("status", if (action == "copy") "CLIPBOARD_TEXT_COPIED" else "CLIPBOARD_CLEARED").put("sent", false).toString()
        }
        if (name == "phone_chat_watch") return ChatAssist.request(context, args, token).toString()
        if (name == "phone_status") return JSONObject()
            .put("virtual_display",VirtualDisplays.status(context,owner))
            .put("backends", PhoneBackends.status(context, owner))
            .put("mode", PhoneAutomation.mode(context).name.lowercase())
            .put("permission_mode", PhoneApprovalPolicy.mode(context).name.lowercase())
            .put("action_policy", dev.wind.agent.permissions.AgentPermissions.get(context).policy.name)
            .put("screen_permission", dev.wind.agent.permissions.AgentPermissions.get(context).enabled(dev.wind.agent.permissions.AgentCapability.SCREEN, owner))
            .put("accessibility_permission", dev.wind.agent.permissions.AgentPermissions.get(context).enabled(dev.wind.agent.permissions.AgentCapability.ACCESSIBILITY, owner))
            .put("fully_managed", PhoneApprovalPolicy.mode(context) == PhoneApprovalPolicy.Mode.FULL)
            .put("sensitive_grants", PhoneApprovalPolicy.count())
            .put("automatic_keyboard_switch", Build.VERSION.SDK_INT >= 30)
            .put("accessibility_connected", PhoneAutomation.service != null)
            .put("screen_sharing", ScreenShareSession.isSharing)
            .put("automation_ime", PhoneInputService.instance != null)
            .put("automation_ime_active", PhoneInputService.instance?.inputActive == true)
            .put("call_permission", context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            .put("sms_permission", context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
            .put("write_settings_permission", Settings.System.canWrite(context)).also { status ->
                status.put("local_ocr", "tesseract4android-4.9.0:chi_sim+eng")
                    .put("automatic_recovery", PhoneAutomation.recoveryEnabled(context)).put("recovery", PhoneManagedRecovery.status(owner))
                status.put("freshness", if (PhoneAutomation.strictFreshness(context)) "strict" else "tolerant")
                PhoneAutomation.service?.let { service -> runCatching { service.diagnostics(token) }.onSuccess { metadata ->
                    metadata.keys().forEach { key -> status.put(key, metadata.get(key)) }
                }.onFailure { status.put("diagnostics_error", it.message) } }
            }.toString()
        if (name == "phone_operation_status") return PhoneSmsOperations.status(args.getString("operation_id"), owner).toString()
        return PhoneAutomation.serialized(token) {
            if (name == "phone_apps") return@serialized apps(args).toString()
            if (name !in setOf("phone_observe", "phone_action", "phone_wait")) return@serialized native(args, name, token, observer)
            val service = PhoneAutomation.requireEnabled(context)
            if (name == "phone_observe") {
                PhoneAutomation.yieldToTarget(token)
                return@serialized observe(service, args, token, observer)
            }
            if (name == "phone_action") {
                PhoneManagedRecovery.activate(context, owner, token)
                PhoneAutomation.yieldToTarget(token)
                service.checkAction(owner, args, token)
                if (args.has("visual_id")) {
                    require(!args.has("x") && !args.has("y")) { "PHONE_VISUAL_SELECTOR_CONFLICT" }
                    visualCoordinates(service.actionObservation(owner, args, token), args)
                }
            }
            if (name == "phone_wait") {
                PhoneAutomation.yieldToTarget(token)
                return@serialized waitFor(service, args, token, observer).toString()
            }
            validateNative(name, args)
            validateAfter(args)
            if (name == "phone_action" && (args.getString("action") == "dismiss" || args.optBoolean("auto_dismiss"))) {
                check(PhoneApprovalPolicy.mode(context) == PhoneApprovalPolicy.Mode.FULL) { "PHONE_DISMISS_FULL_MODE_REQUIRED" }
            }
            val observation = if (name == "phone_action") service.actionObservation(owner, args, token) else null
            val scope = if (observation != null) PhoneApprovalPolicy.pageScope(observation, args) else PhoneApprovalPolicy.nativeScope(name, args)
            val ticket = PhoneApprovalPolicy.authorize(context, owner, name, args, scope, token)
                ?: return@serialized "REJECTED: phone operation was not approved"
            token.throwIfCancelled()
            PhoneManagedRecovery.activate(context, owner, token)
            check(PhoneAutomation.requireEnabled(context) === service) { "PHONE_SERVICE_CHANGED" }
            if (name == "phone_action") {
                PhoneAutomation.yieldToTarget(token)
                service.checkAction(owner, args, token)

                val observed = observation ?: error("PHONE_OBSERVATION_REQUIRED")
                if (args.has("visual_id")) revalidateVisual(service, observed, args, token)
                val before = service.sample(observed.mode, token)
                val execute = { service.act(owner, args, token, ticket) }
                val result = if (args.getString("action") in setOf("type_text", "ime_action") || args.getString("action") == "paste_text" && observed.mode == PhoneAutomation.Mode.VISUAL)
                    PhoneKeyboard.withEditor(service, observed.packageName, token, ticket, execute) else execute()
                return@serialized afterAction(service, observed, before, args, result, token, observer).toString()
            }
            service.onMain(token, allowAppForeground = true) {
                // All effects require the configured authority and the main-thread commit checks.
                token.throwIfCancelled(); PhoneApprovalPolicy.check(ticket); validateNative(name, args)
                val output = when (name) {
                    "phone_launch" -> {
                        val intent = context.packageManager.getLaunchIntentForPackage(args.getString("package")) ?: error("PHONE_APP_NOT_LAUNCHABLE")
                        launch(service, intent); "APP_LAUNCH_REQUESTED: verify with phone_observe"
                    }
                    "phone_settings" -> settings(service, args)
                    "phone_music" -> music(service, args)
                    "phone_call" -> {
                        val direct = args.optBoolean("direct", false)
                        if (direct) requirePermission(Manifest.permission.CALL_PHONE)
                        launch(service, Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                            Uri.fromParts("tel", number(args), null)))
                        if (direct) "CALL_REQUESTED: connection is not confirmed" else "DIALER_OPENED: call not placed"
                    }
                    "phone_message" -> message(service, args)
                    else -> error("PHONE_TOOL_UNKNOWN")
                }
                service.invalidateObservations(); output
            }
        }
    }

    /** Public Android APIs need capability approval, not an installed AccessibilityService. */
    private fun native(args: JSONObject, name: String, token: CancellationToken, observer: Tools.Observer?): String {
        validateNative(name, args); validateAfter(args)
        val ticket = PhoneApprovalPolicy.authorize(context, owner, name, args, PhoneApprovalPolicy.nativeScope(name, args), token)
            ?: return "REJECTED: phone operation was not approved"
        PhoneManagedRecovery.activate(context,owner,token)
        var output = ""
        PhoneOverlayScope.main(token) {
            token.throwIfCancelled(); PhoneApprovalPolicy.check(ticket); validateNative(name, args)
            check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) { "PHONE_LOCKED" }
            check((context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) { "PHONE_SCREEN_NOT_INTERACTIVE" }
            output = when (name) {
                "phone_launch" -> { launch(context, context.packageManager.getLaunchIntentForPackage(args.getString("package")) ?: error("PHONE_APP_NOT_LAUNCHABLE")); "APP_LAUNCH_REQUESTED: verify with phone_observe" }
                "phone_settings" -> settings(context, args)
                "phone_music" -> music(context, args)
                "phone_call" -> { launch(context, Intent(if (args.optBoolean("direct")) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number(args), null))); "CALL_REQUESTED: connection is not confirmed" }
                "phone_message" -> message(context, args)
                else -> error("PHONE_TOOL_UNKNOWN")
            }
            PhoneAutomation.service?.invalidateObservations(); PhoneObservationStore.invalidate(owner)
        }
        if(name=="phone_launch") {
            val result=JSONObject().put("status","DISPATCHED").put("submitted",true).put("note",output)
            try {
                val pkg=args.getString("package")
                if(pkg==context.packageName) {
                    val start=android.os.SystemClock.elapsedRealtime()
                    while(!ScreenShareSession.appActivitiesVisible && !ScreenShareSession.mainVisible && android.os.SystemClock.elapsedRealtime()-start<2500) token.sleep(90)
                    val visible=ScreenShareSession.appActivitiesVisible || ScreenShareSession.mainVisible
                    result.put("foreground_verified",visible).put("restored_app",visible)
                } else if(args.optString("observe_after","none")!="none") {
                    val ready=PhoneReady.await(
                        { JSONObject(run("phone_observe",JSONObject().put("screen","main").put("mode",if(args.getString("observe_after")=="visual") "visual" else "ui").put("backend","legacy"),token,observer)) },
                        { token.sleep(it) },{ android.os.SystemClock.elapsedRealtime() },2500,pkg)
                    result.put("foreground_verified",ready.getString("status")=="MET").put("observation",ready.getJSONObject("observation"))
                }
            } catch(e: Exception) { result.put("observation_error",e.message).put("foreground_verified",false) }
            return result.toString()
        }
        return output
    }

    private fun observationMode(value: String): PhoneAutomation.Mode = when (value) {
        "visual" -> PhoneAutomation.Mode.VISUAL; "ui" -> PhoneAutomation.Mode.UI; "hybrid" -> PhoneAutomation.Mode.HYBRID
        else -> error("PHONE_OBSERVATION_MODE_INVALID")
    }
    private fun validateAfter(args: JSONObject) {
        require(args.optLong("settle_ms", 800) in 0L..3000L) { "PHONE_SETTLE_TIMEOUT_INVALID" }
        val value = args.optString("observe_after", "none")
        if (value != "none") {
            val mode = observationMode(value)
            val configured = PhoneAutomation.mode(context)
            check(configured == PhoneAutomation.Mode.HYBRID || mode == configured) { "PHONE_MODE_NOT_ENABLED" }
            PhoneImageAnnotator.validate(args, mode)
        }
    }
    private fun observe(service: PhoneControlService, args: JSONObject, token: CancellationToken, observer: Tools.Observer?): String {
        val configured = PhoneAutomation.mode(context)
        // Hybrid is authority to use both paths, not a request to capture every page.
        val defaultMode = if (configured == PhoneAutomation.Mode.HYBRID) "ui" else configured.name.lowercase()
        val mode = observationMode(args.optString("mode", defaultMode))
        PhoneImageAnnotator.validate(args, mode)
        val observation = service.observe(owner, mode, token)
        if (args.optBoolean("auto_dismiss")) observation.json.put("auto_dismiss", "REPORT_ONLY: READ tools never click. Use phone_action action=dismiss or auto_dismiss=true in Full mode.")
        if (mode != PhoneAutomation.Mode.UI) {
            check(observer != null) { "PHONE_IMAGE_CHANNEL_REQUIRED" }
            val sharing = ScreenShareSession.sessionId
            check(sharing != 0L) { "PHONE_SCREEN_SHARE_REQUIRED: start sharing manually" }
            val before = service.contentEpochNow()
            check(dev.wind.agent.permissions.AgentPermissions.get(context).enabled(dev.wind.agent.permissions.AgentCapability.SCREEN, owner)) { "AGENT_PERMISSION_REVOKED" }
            val result = ScreenShareSession.service?.captureBlocking(sharing, token, phoneObservation = true) ?: error("PHONE_SCREEN_SHARE_UNAVAILABLE")
            val image = result.image
            if (image == null) { service.forget(observation.id, token); error("PHONE_CAPTURE_FAILED: " + result.error) }
            // Content changes during capture are diagnostic; structural transitions still reject the snapshot.
            val checked = service.checkAction(owner, JSONObject().put("observation_id", observation.id), token)
            observation.json.put("content_events_during_capture", service.contentEpochNow() - before)
                .put("page_drift", JSONObject().put("content_events", checked.drift))
                .put("image_width", result.width).put("image_height", result.height).put("captured_at", result.capturedAt)
            if (args.optBoolean("ocr", false)) {
                val vision = PhoneVision.recognize(context, image, token)
                service.bindVision(owner, observation, vision, token)
            }
            val annotated = PhoneImageAnnotator.annotate(image, observation, args)
            check(dev.wind.agent.permissions.AgentPermissions.get(context).enabled(dev.wind.agent.permissions.AgentCapability.SCREEN, owner)) { "AGENT_PERMISSION_REVOKED" }
            token.throwIfCancelled()
            service.checkAction(owner, JSONObject().put("observation_id", observation.id), token)
            observer.onPhoneImage(annotated)
        }
        return observation.json.toString()
    }
    private fun visualCoordinates(observed: PhoneControlService.Observation, args: JSONObject) {
        require(args.getString("action") in setOf("tap", "long_press", "dismiss")) { "PHONE_VISUAL_SELECTOR_ACTION_INVALID" }
        val target = observed.visualTargets[args.getString("visual_id")] ?: error("PHONE_VISUAL_TARGET_UNKNOWN: observe with ocr=true")
        args.put("x", target.x).put("y", target.y)
    }
    private fun captureVision(service: PhoneControlService, observed: PhoneControlService.Observation, token: CancellationToken,
        timeout: Long = 3000): PhoneVision.Result {
        val start = android.os.SystemClock.elapsedRealtime()
        service.checkAction(owner, JSONObject().put("observation_id", observed.id), token)
        val sharing = ScreenShareSession.sessionId
        check(sharing != 0L) { "PHONE_SCREEN_SHARE_REQUIRED" }
        check(dev.wind.agent.permissions.AgentPermissions.get(context).enabled(dev.wind.agent.permissions.AgentCapability.SCREEN, owner)) { "AGENT_PERMISSION_REVOKED" }
        val capture = ScreenShareSession.service?.captureBlocking(sharing, token, phoneObservation = true, timeoutMs = timeout) ?: error("PHONE_CAPTURE_UNAVAILABLE")
        val image = capture.image ?: return PhoneVision.Result("CAPTURE_ERROR", error = capture.error)
        val remaining = timeout - (android.os.SystemClock.elapsedRealtime() - start)
        if (remaining <= 0) return PhoneVision.Result("TIMEOUT", error = "Capture exhausted OCR deadline")
        val vision = PhoneVision.recognize(context, image, token, remaining)
        service.checkAction(owner, JSONObject().put("observation_id", observed.id), token)
        return vision
    }
    private fun revalidateVisual(service: PhoneControlService, observed: PhoneControlService.Observation, args: JSONObject, token: CancellationToken) {
        val target = observed.visualTargets[args.getString("visual_id")] ?: error("PHONE_VISUAL_TARGET_UNKNOWN")
        val vision = captureVision(service, observed, token)
        check(vision.status == "OK" && !vision.truncated) { "PHONE_OCR_NOT_READY: ${vision.status}, truncated=${vision.truncated}" }
        val live = PhoneVisual.resolve(target, vision.targets) ?: error("PHONE_VISUAL_TARGET_CHANGED: reobserve")
        if (args.getString("action") == "dismiss") check(PhoneVisual.interruptions(vision.targets).contains(live)) { "PHONE_DISMISS_NOT_WHITELISTED" }
        args.put("x", live.x).put("y", live.y)
    }
    /** WRITE task worker only; does not grant authority and never accepts positive/system permission buttons. */
    internal fun recoverPending(token: CancellationToken, budget: PhoneRecoveryBudget): JSONObject {
        check(PhoneApprovalPolicy.mode(context) == PhoneApprovalPolicy.Mode.FULL) { "PHONE_DISMISS_FULL_MODE_REQUIRED" }
        if (PhoneBackends.wantsAdb(context,JSONObject(),owner)) return PhoneAdbTools(context,owner).recover(token,budget)
        val service = PhoneAutomation.requireEnabled(context)
        val configured = PhoneAutomation.mode(context)
        val mode = if (configured == PhoneAutomation.Mode.VISUAL) configured else PhoneAutomation.Mode.UI
        // Background recovery must not start screenshots/OCR implicitly.
        if (mode == PhoneAutomation.Mode.VISUAL) return JSONObject().put("status", "VISUAL_RECOVERY_REQUIRES_EXPLICIT_OCR_ACTION")
        val observed = service.observe(owner, mode, token)
        val args = JSONObject().put("observation_id", observed.id).put("action", "dismiss")
        val label: String
        if (mode == PhoneAutomation.Mode.VISUAL) {
            val vision = captureVision(service, observed, token)
            if (vision.status != "OK" || vision.truncated) return JSONObject().put("status", "OCR_UNAVAILABLE").put("ocr_status", vision.status)
            service.bindVision(owner, observed, vision, token)
            val automatic = PhoneInterruptions.automatic(vision.targets.filter { it.confidence >= 80f }.map { it.facts() }, false, 1000)
                ?: return JSONObject().put("status", "NONE")
            val candidate = vision.targets.first { it.facts().path == automatic.node.path }
            label = candidate.text; args.put("visual_id", candidate.id)
            visualCoordinates(observed, args)
        } else {
            val candidate = PhoneInterruptions.automatic(observed.nodes.values.map { it.facts }, observed.json.optBoolean("dialog_window"), observed.height)
                ?: return JSONObject().put("status", "NONE")
            label = candidate.text; args.put("node_id", observed.nodes.entries.first { it.value.facts.path == candidate.node.path }.key)
        }
        val key = "${observed.packageName}:${observed.windowId}:${PhoneVisual.key(label)}"
        if (!budget.reserve(key, android.os.SystemClock.elapsedRealtime())) return JSONObject().put("status", "SUPPRESSED")
        val ticket = PhoneApprovalPolicy.authorize(context, owner, "phone_action", args, PhoneApprovalPolicy.pageScope(observed, args), token)
            ?: return JSONObject().put("status", "REJECTED")
        if (mode == PhoneAutomation.Mode.VISUAL) revalidateVisual(service, observed, args, token)
        val outcome = service.act(owner, args, token, ticket)
        // Never retry an unknown result; a later explicit user/model action is a separate decision.
        return JSONObject().put("status", "RECOVERY_ATTEMPTED").put("text", label).put("action_result", outcome)
    }
    private fun attachObservation(service: PhoneControlService, args: JSONObject, result: JSONObject, token: CancellationToken, observer: Tools.Observer?) {
        val mode = args.optString("observe_after", "none")
        if (mode == "none") return
        try { result.put("observation", JSONObject(observe(service, JSONObject(args.toString()).put("mode", mode).removeAutoDismiss(), token, observer))) }
        catch (error: Exception) {
            result.put("observation_error", error.message ?: error.javaClass.simpleName)
                .put("note", "The action status remains authoritative; attached observation failed. Do not replay an executed/unknown action.")
        }
    }
    private fun JSONObject.removeAutoDismiss(): JSONObject { remove("auto_dismiss"); return this }
    private fun afterAction(service: PhoneControlService, observation: PhoneControlService.Observation, before: PhoneControlService.Sample,
        args: JSONObject, result: JSONObject, token: CancellationToken, observer: Tools.Observer?): JSONObject {
        val editor = result.optBoolean("_editor"); result.remove("_editor")
        if (result.optString("status") in setOf("RESULT_UNKNOWN", "GESTURE_SUBMITTED", "GESTURE_CANCELLED")) {
            result.put("no_automatic_retry", true); attachObservation(service, args, result, token, observer); return result
        }
        try {
            result.put("settle", service.settle(observation.mode, args.optLong("settle_ms", 800), token))
            // Always wait through IME animation: being visible does not mean it has settled.
            if (editor)
                result.put("editor_wait", service.stabilizeEditor(observation.packageName, token))
            val after = service.sample(observation.mode, token)
            if (observation.mode != PhoneAutomation.Mode.VISUAL && args.optString("action") in setOf("click", "tap", "dismiss")) {
                val unchanged = before.pkg == after.pkg && before.window == after.window && before.structural == after.structural &&
                    before.ime == after.ime && PhoneSettle.fingerprint(before.nodes) == PhoneSettle.fingerprint(after.nodes)
                result.put("no_visible_effect", unchanged)
                if (unchanged) result.put("suggestion", "No layout/IME transition detected. Reobserve and consider prefer=gesture; do not blindly replay sends or purchases.")
            }
            if (args.optString("action") == "scroll" && observation.mode != PhoneAutomation.Mode.VISUAL) {
                val movement = PhoneSettle.movement(before.nodes.filter { it.top >= observation.height * 0.12 && it.bottom <= observation.height * 0.9 },
                    after.nodes.filter { it.top >= observation.height * 0.12 && it.bottom <= observation.height * 0.9 },
                    args.optString("direction") in setOf("left", "right"))
                result.put("moved_px", movement.movedPx ?: JSONObject.NULL).put("reached_end", movement.reachedEnd ?: JSONObject.NULL)
                    .put("movement_note", "Median matched-node displacement estimate; null means insufficient matching landmarks.")
            }
            if (args.optBoolean("auto_dismiss")) dismissInterruptions(service, observation.mode, args, result, token)
        } catch (error: Exception) {
            result.put("post_action_error", error.message ?: error.javaClass.simpleName)
                .put("note", "Action already submitted; wait/inspection failed, not an action failure. Do not automatically repeat.")
        }
        attachObservation(service, args, result, token, observer)
        return result
    }
    private fun dismissInterruptions(service: PhoneControlService, mode: PhoneAutomation.Mode, original: JSONObject,
        result: JSONObject, token: CancellationToken) {
        check(PhoneApprovalPolicy.mode(context) == PhoneApprovalPolicy.Mode.FULL) { "PHONE_DISMISS_FULL_MODE_REQUIRED" }
        if (mode == PhoneAutomation.Mode.VISUAL) {
            val outcomes = JSONArray(); result.put("dismissed", outcomes)
            val budget = PhoneRecoveryBudget()
            repeat(if (original.optString("action") == "dismiss") 1 else 2) {
                val outcome = recoverPending(token, budget)
                if (outcome.optString("status") != "RECOVERY_ATTEMPTED") return
                outcomes.put(outcome)
                if (outcome.getJSONObject("action_result").optString("status") != "GESTURE_COMPLETED") return
                service.settle(mode, 1500, token)
            }
            return
        }
        val dismissed = JSONArray(); result.put("dismissed", dismissed)
        repeat(if (original.optString("action") == "dismiss") 1 else 2) {
            token.throwIfCancelled()
            val observed = service.observe(owner, mode, token)
            val candidate = observed.json.getJSONArray("interruptions").optJSONObject(0) ?: return
            val args = JSONObject().put("observation_id", observed.id).put("action", "dismiss").put("node_id", candidate.getString("dismiss_node_id"))
            val ticket = PhoneApprovalPolicy.authorize(context, owner, "phone_action", args, PhoneApprovalPolicy.pageScope(observed, args), token) ?: return
            val outcome = service.act(owner, args, token, ticket)
            dismissed.put(JSONObject().put("node_id", candidate.getString("dismiss_node_id")).put("text", candidate.getString("dismiss_text")).put("result", outcome))
            if (outcome.optString("status") !in setOf("NODE_ACTION_ACCEPTED", "GESTURE_COMPLETED")) return
            service.settle(mode, 800, token)
        }
    }
    private fun waitFor(service: PhoneControlService, args: JSONObject, token: CancellationToken, observer: Tools.Observer?): JSONObject {
        validateAfter(args)
        val until = args.optString("until", "idle")
        require(until in setOf("idle", "visible", "text", "gone", "package", "editor")) { "PHONE_WAIT_CONDITION_INVALID" }
        val timeout = args.optLong("timeout_ms", 3000); require(timeout in 0L..10000L) { "PHONE_WAIT_TIMEOUT_INVALID" }
        val mode = PhoneAutomation.mode(context)
        val selector = if (until in setOf("visible", "text", "gone")) PhoneWaitTarget.parse(args) else null
        if (mode == PhoneAutomation.Mode.VISUAL && selector != null) {
            selector.validateVisual()
            require(until != "gone") { "PHONE_VISUAL_ABSENCE_NOT_PROVEN: OCR misses cannot prove disappearance" }
        }
        if (until == "package") require(args.getString("package").isNotBlank()) { "PHONE_PACKAGE_INVALID" }
        val start = android.os.SystemClock.elapsedRealtime(); val stability = PhoneSettle.LayoutStability()
        var previousContent: Long? = null; var matched: NodeFacts? = null; var visual: PhoneVisual.Target? = null
        var visionStatus = ""; var met = false
        do {
            token.throwIfCancelled()
            val current = service.sample(mode, token)
            met = when (until) {
                "visible", "text", "gone" -> {
                    val query = requireNotNull(selector)
                    if (mode == PhoneAutomation.Mode.VISUAL) {
                        var transientObservation: PhoneControlService.Observation? = null
                        try {
                            val observed = service.observe(owner, mode, token); transientObservation = observed
                            val remaining = (timeout - (android.os.SystemClock.elapsedRealtime() - start)).coerceIn(1, 3000)
                            val vision = captureVision(service, observed, token, remaining)
                            visionStatus = vision.status
                            visual = vision.targets.firstOrNull { query.matches(it) }
                            vision.status == "OK" && visual != null
                        } catch (error: Exception) {
                            token.throwIfCancelled()
                            val message = error.message.orEmpty()
                            if (!message.startsWith("PHONE_OBSERVATION_STALE") && !message.startsWith("PHONE_WINDOW_IDENTITY_PENDING") &&
                                !message.startsWith("PHONE_WINDOW_UNKNOWN")) throw error
                            visionStatus = "WINDOW_CHANGED"; visual = null; false
                        } finally { transientObservation?.let { service.forget(it.id, token) } }
                    } else {
                        matched = current.nodes.firstOrNull { query.matches(it) }
                        if (until == "gone") matched == null && !current.truncated else matched != null
                    }
                }
                "package" -> current.pkg == args.getString("package")
                "editor" -> current.editor
                else -> current.quietMs >= 250 && if (mode == PhoneAutomation.Mode.VISUAL) previousContent == current.content else stability.sample(current.nodes)
            }
            previousContent = current.content
            if (met || android.os.SystemClock.elapsedRealtime() - start >= timeout) break
            token.sleep(minOf(if (mode == PhoneAutomation.Mode.VISUAL) 500 else 150,
                timeout - (android.os.SystemClock.elapsedRealtime() - start)))
        } while (true)
        val result = JSONObject().put("status", if (met) "MET" else "TIMEOUT").put("until", until)
            .put("waited_ms", android.os.SystemClock.elapsedRealtime() - start)
        if (visionStatus.isNotEmpty()) result.put("ocr_status", visionStatus)
        matched?.let { result.put("matched_node", JSONObject().put("resource_id", it.resId).put("text", it.text).put("description", it.desc)
            .put("bounds", JSONArray(listOf(it.left, it.top, it.right, it.bottom)))) }
        visual?.let { result.put("matched_visual", PhoneVision.targetJson(it)) }
        attachObservation(service, args, result, token, observer)
        return result
    }

    private fun apps(args: JSONObject): JSONObject {
        val query = args.optString("query").lowercase()
        val start = args.optInt("offset", 0); require(start in 0..10000) { "PHONE_OFFSET_INVALID" }
        val entries = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.map {
                it.activityInfo.packageName to it.loadLabel(context.packageManager).toString()
            }.filter { query.isBlank() || it.first.lowercase().contains(query) || it.second.lowercase().contains(query) }
            .sortedBy { it.first }
        return JSONObject().put("apps", JSONArray(entries.drop(start).take(100).map {
            JSONObject().put("package", it.first).put("label", it.second)
        })).put("total", entries.size).put("next_offset", if (start + 100 < entries.size) start + 100 else -1)
    }

    private fun launch(service: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
    }

    private fun number(args: JSONObject): String {
        val number = args.getString("number").trim()
        require(number.matches(Regex("\\+?[0-9][0-9 ()-]{1,30}"))) { "PHONE_NUMBER_INVALID: use an explicit number, not a contact guess or USSD" }
        return number
    }

    private fun requirePermission(permission: String) {
        check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { "PHONE_RUNTIME_PERMISSION_REQUIRED: grant in phone tools controls" }
    }

    private fun validateNative(name: String, args: JSONObject) {
        when (name) {
            "phone_launch" -> require(args.getString("package").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "PHONE_PACKAGE_INVALID" }
            "phone_call" -> { number(args); if (args.optBoolean("direct", false)) requirePermission(Manifest.permission.CALL_PHONE) }
            "phone_message" -> {
                number(args); require(args.getString("text").length in 1..8000) { "PHONE_MESSAGE_LENGTH_INVALID" }
                if (args.optBoolean("direct", false)) requirePermission(Manifest.permission.SEND_SMS)
            }
            "phone_settings" -> {
                val setting = args.optString("setting")
                when (setting) {
                    "brightness" -> { check(Settings.System.canWrite(context)) { "PHONE_WRITE_SETTINGS_REQUIRED" }; require(args.getInt("value") in 0..255) { "PHONE_BRIGHTNESS_INVALID" } }
                    "volume" -> { require(args.getInt("value") >= 0); require(args.optString("stream", "music") in setOf("music", "ring", "alarm")) }
                    "" -> require(args.getString("panel") in panels.keys) { "PHONE_SETTINGS_PANEL_INVALID" }
                    else -> error("PHONE_SETTING_UNKNOWN")
                }
            }
            "phone_music" -> {
                require(args.optString("action", "search") in setOf("search", "play", "pause", "next", "previous", "stop")) { "PHONE_MUSIC_ACTION_INVALID" }
                if (args.optString("action", "search") == "search") require(args.getString("query").length in 1..500)
            }
            "phone_action" -> require(args.getString("action") in setOf("tap", "long_press", "swipe", "click", "set_text", "paste_text", "type_text", "ime_action", "scroll", "dismiss", "back", "home", "recents", "notifications", "quick_settings")) { "PHONE_ACTION_UNKNOWN" }
            else -> error("PHONE_TOOL_UNKNOWN")
        }
    }

    private fun settings(service: Context, args: JSONObject): String = when (args.optString("setting")) {
        "brightness" -> {
            val manual = Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val changed = manual && Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, args.getInt("value"))
            if (changed) "BRIGHTNESS_SET: manual mode" else "ERROR: brightness update failed; mode may already have changed to manual"
        }
        "volume" -> {
            val stream = when (args.optString("stream", "music")) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; else -> AudioManager.STREAM_MUSIC }
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            require(args.getInt("value") <= audio.getStreamMaxVolume(stream)) { "PHONE_VOLUME_OUT_OF_RANGE" }
            audio.setStreamVolume(stream, args.getInt("value"), 0)
            "VOLUME_SET: ${audio.getStreamVolume(stream)}"
        }
        else -> { launch(service, Intent(panels.getValue(args.getString("panel")))); "SETTINGS_PANEL_OPENED: no toggle changed yet" }
    }

    private fun music(service: Context, args: JSONObject): String {
        val action = args.optString("action", "search")
        if (action == "search") {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra("query", args.getString("query"))
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
            args.optString("package").takeIf { it.isNotBlank() }?.let { intent.setPackage(it) }
            launch(service, intent); return "MUSIC_SEARCH_PLAY_REQUESTED: verify player state"
        }
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        return "MEDIA_KEY_DISPATCHED: playback is not confirmed"
    }

    @Suppress("DEPRECATION")
    private fun message(service: Context, args: JSONObject): String {
        if (!args.optBoolean("direct", false)) {
            launch(service, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number(args), null)).putExtra("sms_body", args.getString("text")))
            return "MESSAGE_COMPOSER_OPENED: message not sent"
        }
        requirePermission(Manifest.permission.SEND_SMS)
        val subscription = args.optInt("subscription_id", -1)
        if (Build.VERSION.SDK_INT >= 24 && subscription < 0) {
            check(SmsManager.getDefaultSmsSubscriptionId() >= 0) { "PHONE_SMS_SIM_REQUIRED: choose a default SMS SIM or pass subscription_id" }
        }
        val manager = if (subscription >= 0) SmsManager.getSmsManagerForSubscriptionId(subscription) else SmsManager.getDefault()
        val parts = manager.divideMessage(args.getString("text"))
        val id = UUID.randomUUID().toString()
        PhoneSmsOperations.create(id, owner, parts.size)
        fun intents(kind: String): ArrayList<PendingIntent> = ArrayList(parts.indices.map { index ->
            PendingIntent.getBroadcast(context, PhoneSmsOperations.nextRequestCode(),
                Intent(context, PhoneSmsResultReceiver::class.java).setAction("dev.wind.agent.SMS_$kind")
                    .putExtra("operation_id", id).putExtra("part", index).putExtra("kind", kind),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        })
        try { manager.sendMultipartTextMessage(number(args), null, parts, intents("SENT"), intents("DELIVERED")) }
        catch (error: Exception) {
            PhoneSmsOperations.fail(id, error.javaClass.simpleName)
            return "ERROR: " + JSONObject().put("operation_id", id).put("status", "FAILED_OR_PARTIAL")
                .put("error", error.javaClass.simpleName).put("note", "Query status; do not automatically resend").toString()
        }
        return JSONObject().put("operation_id", id).put("status", "SUBMITTED")
            .put("note", "Query phone_operation_status for send/delivery callbacks; do not claim delivered yet").toString()
    }

    companion object {
        private val panels = mapOf("wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS, "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "network" to Settings.ACTION_WIRELESS_SETTINGS, "general" to Settings.ACTION_SETTINGS)
    }
}
