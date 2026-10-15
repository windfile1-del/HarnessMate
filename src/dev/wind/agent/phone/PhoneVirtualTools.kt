package dev.wind.agent.phone

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import dev.wind.agent.CancellationToken
import dev.wind.agent.Message
import dev.wind.agent.Tools
import org.json.JSONArray
import org.json.JSONObject

/** Separate execution path: no main overlay, MediaProjection, shell input, clipboard or IME helpers. */
internal class PhoneVirtualTools(private val context: Context,private val owner: String) {
    fun run(name: String,arguments: JSONObject,token: CancellationToken,observer: Tools.Observer?): String = PhoneCoordinator.run(token) {
        val args=VirtualDisplays.scopeArgs(context,owner,name,arguments)
        VirtualDisplays.blockGlobal(context,owner,name,args)?.let { error(it) }
        dev.wind.agent.permissions.AgentPermissionGate(context).error(name,args,owner)?.let {
            return@run VirtualDisplays.preflightFailure(context,owner,it).toString()
        }
        if(name=="phone_status") return@run VirtualDisplays.status(context,owner).toString()
        if(name=="phone_apps") return@run apps(args).toString()
        if(name=="phone_display") return@run when(args.optString("action","status")) {
            "status" -> VirtualDisplays.status(context,owner)
            "close" -> VirtualDisplays.close(owner,args,token)
            "create" -> {
                val readiness=VirtualDisplays.status(context,owner).getJSONObject("readiness")
                if(!readiness.getBoolean("creation_ready")) return@run VirtualDisplays.preflightFailure(context,owner,
                    readiness.getJSONArray("blockers").optJSONObject(0)?.optString("code") ?: "VIRTUAL_DISPLAY_SESSION_ALREADY_USED").toString()
                val ticket=PhoneApprovalPolicy.authorize(context,owner,name,args,null,token)
                if(ticket==null) JSONObject().put("status","REJECTED").put("submitted",false)
                else {
                    PhoneApprovalPolicy.check(ticket)
                    try { VirtualDisplays.create(context,owner,token).identity } catch(e: Exception) {
                        token.throwIfCancelled()
                        // Creation cleans up its bridge on failure; no app effect has been requested.
                        JSONObject().put("status","ERROR").put("error",e.message ?: "VIRTUAL_DISPLAY_CREATE_FAILED")
                            .put("diagnostic",(e as? VirtualDisplays.CreationFailure)?.diagnostic ?: JSONObject.NULL)
                            .put("guidance",VirtualDisplayReadiness.guidance(e.message.orEmpty()))
                            .put("virtual_display",VirtualDisplays.status(context,owner)).put("retry_safe",false)
                    }
                }
            }
            else -> error("PHONE_DISPLAY_ACTION_INVALID")
        }.toString()
        val s=try { VirtualDisplays.get(context,owner,args,token) } catch(e: IllegalStateException) {
            token.throwIfCancelled()
            if(e.message !in setOf("VIRTUAL_DISPLAY_CREATE_REQUIRED","VIRTUAL_DISPLAY_SESSION_LOST")) throw e
            return@run VirtualDisplays.preflightFailure(context,owner,e.message!!).toString()
        }
        if(!s.launched && name in setOf("phone_observe","phone_action","phone_wait","phone_sequence"))
            return@run VirtualDisplays.preflightFailure(context,owner,"VIRTUAL_DISPLAY_APP_REQUIRED").put("display_session_id",s.id)
                .put("next_step","调用 phone_launch，选择 allowed_packages 中的实际包名；空副屏尚无应用窗口。不要重新 create。").toString()
        try { when(name) {
            "phone_observe" -> observe(s,args,token,observer)
            "phone_action" -> action(s,args,token,observer)
            "phone_launch" -> launch(s,args,token,observer)
            "phone_wait" -> wait(s,args,token,observer)
            "phone_flow" -> flow(s,args,token,observer)
            "phone_sequence" -> sequence(s,args,token,observer)
            "phone_operation_status" -> s.bridge.rpc("receipt",s.scope(JSONObject()),token,args.getString("request_id"))
            else -> error("VIRTUAL_DISPLAY_GLOBAL_OPERATION_BLOCKED")
        } } catch(e: ObservationFailure) {
            JSONObject(e.reply.toString()).put("display_session_id",s.id).put("retry_safe",false)
        }.toString()
    }
    private fun checked(result: JSONObject): JSONObject {
        if(result.optString("status")!="COMPLETED") throw ObservationFailure(result)
        return result
    }
    private class ObservationFailure(val reply: JSONObject) : IllegalStateException(reply.optString("error","PHONE_PREFLIGHT_FAILED"))
    private fun read(s: VirtualDisplays.Session,args: JSONObject,token: CancellationToken): JSONObject {
        check(s.valid()) { "VIRTUAL_DISPLAY_SESSION_LOST" }
        val mode=args.optString("mode","ui"); require(mode in setOf("ui","visual","hybrid")) { "PHONE_MODE_INVALID" }
        val result=checked(s.bridge.rpc("observe",s.scope(args).put("mode",mode),token))
        VirtualDisplayScope.session(s.id,result.optString("display_session_id"),result.optInt("display_id",-1))
        s.observations[result.getString("observation_id")]=JSONObject(result.toString())
        while(s.observations.size>24) s.observations.remove(s.observations.keys.first())
        return result.put("backend","adb_virtual").put("coordinate_space","display_normalized_0_1000")
    }
    private fun readReady(s: VirtualDisplays.Session,args: JSONObject,token: CancellationToken): JSONObject {
        val deadline=SystemClock.elapsedRealtime()+2500
        while(true) try { return read(s,args,token) } catch(e: Exception) {
            token.throwIfCancelled()
            if(e.message !in setOf("VIRTUAL_DISPLAY_WINDOW_NOT_READY","VIRTUAL_DISPLAY_ROOT_UNAVAILABLE","VIRTUAL_DISPLAY_PACKAGE_NOT_READY","PHONE_WINDOW_CHANGED") || SystemClock.elapsedRealtime()>=deadline) throw e
            token.sleep(90)
        }
    }
    private fun observe(s: VirtualDisplays.Session,args: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        val mode=args.optString("mode","ui")
        if(mode!="ui") check(observer!=null) { "PHONE_IMAGE_CHANNEL_REQUIRED" }
        if(mode=="ui") require(!args.optBoolean("ocr") && !args.optBoolean("marks") && !args.optBoolean("grid") && !args.has("region")) { "PHONE_IMAGE_MODE_REQUIRED" }
        val o=readReady(s,args,token)
        if(mode!="ui") {
            val frame=s.bridge.captureFrame(token,s.scope(JSONObject()))
            VirtualDisplayScope.same(o,frame.metadata)
            check(o.getLong("event_epoch")==frame.metadata.getLong("event_epoch")) { "PHONE_CONTENT_CHANGED" }
            val image=attachment(frame,s.id)
            val visuals=LinkedHashMap<String,PhoneVisual.Target>()
            if(args.optBoolean("ocr")) {
                val vision=PhoneVision.recognize(context,image,token); o.put("ocr",vision.json()); vision.targets.forEach { visuals[it.id]=it }
            }
            val annotationNodes=PhoneObservation(owner,"adb_virtual",0,0,o).nodes.associate { (id,f) -> id to PhoneControlService.Node(f,JSONObject()) }
            val stamp=PhoneFreshness.Stamp(owner,o.getString("package"),o.getString("window").hashCode(),720,1280,0,0,SystemClock.elapsedRealtime(),0)
            val annotation=PhoneControlService.Observation(o.getString("observation_id"),PhoneAutomation.Mode.valueOf(mode.uppercase()),stamp,annotationNodes,o,visuals)
            val rendered=PhoneImageAnnotator.annotate(image,annotation,args)
            val after=read(s,JSONObject().put("mode","visual").put("fresh",true),token)
            VirtualDisplayScope.same(o,after)
            check(o.getLong("event_epoch")==after.getLong("event_epoch")) { "PHONE_CONTENT_CHANGED" }
            observer!!.onPhoneImage(rendered)
            o.put("image_captured",true).put("frame",frame.metadata)
        }
        return o
    }
    private fun owned(s: VirtualDisplays.Session,args: JSONObject): JSONObject {
        val old=s.observations.remove(args.getString("observation_id")) ?: error("PHONE_OBSERVATION_EXPIRED")
        VirtualDisplayScope.session(s.id,old.optString("display_session_id"),old.optInt("display_id",-1))
        check(System.currentTimeMillis()-old.getLong("captured_at") in 0..15000) { "PHONE_OBSERVATION_EXPIRED" }
        return old
    }
    private fun action(s: VirtualDisplays.Session,arguments: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        val args=s.scope(arguments)
        if(args.has("target")) {
            require(args.has("expected_package") && !listOf("node_id","observation_id","visual_id","x","y").any(args::has)) { "PHONE_TARGET_SELECTOR_CONFLICT" }
            val target=args.getJSONObject("target"); PhoneWaitTarget.parse(JSONObject().put("target",target))
            val timeout=args.optLong("target_timeout_ms",2500); require(timeout in 200..5000)
            val ready=PhoneReady.await({readReady(s,JSONObject().put("fresh",true),token)},{token.sleep(it)},{SystemClock.elapsedRealtime()},timeout,args.getString("expected_package"),target)
            if(ready.optString("status")!="MET") return JSONObject().put("status","NOT_SUBMITTED").put("submitted",false).put("error","PHONE_TARGET_NOT_READY").put("observation",ready.getJSONObject("observation"))
            val o=ready.getJSONObject("observation"); val id=PhoneReady.resolve(o,target) ?: error("PHONE_TARGET_NOT_UNIQUE")
            args.remove("target"); args.put("node_id",id).put("observation_id",o.getString("observation_id"))
            if(args.optString("action")=="long_press") {
                val n=(0 until o.getJSONArray("nodes").length()).map { o.getJSONArray("nodes").getJSONObject(it) }.single { PhoneReady.nodeId(it)==id }
                val f=PhoneObservation.facts(n); args.put("x",f.centerX*1000.0/719).put("y",f.centerY*1000.0/1279)
            }
        }
        VirtualDisplayScope.action(args); PhoneAdbTools(context,owner).validateAction(args)
        if(args.optString("observe_after","ui") in setOf("visual","hybrid")) check(observer!=null) { "PHONE_IMAGE_CHANNEL_REQUIRED" }
        val old=owned(s,args); val mode=old.getString("mode")
        val facts=nodes(old); val target=args.optString("node_id").takeIf { it.isNotEmpty() }?.let { id ->
            val a=old.getJSONArray("nodes"); (0 until a.length()).map { a.getJSONObject(it) }.singleOrNull { PhoneReady.nodeId(it)==id }?.let(PhoneObservation::facts)
                ?: error("PHONE_NODE_UNKNOWN")
        } ?: if(args.has("x") && args.has("y")) PhoneNodeMatcher.deepestAt(facts,PhoneApprovalPolicy.normalized(args,"x",720),PhoneApprovalPolicy.normalized(args,"y",1280)) else null
        if(args.optString("action")=="dismiss") check(PhoneApprovalPolicy.mode(context)==PhoneApprovalPolicy.Mode.FULL && target!=null && PhoneInterruptions.negativeButton(target)) { "PHONE_DISMISS_NEGATIVE_UI_NODE_REQUIRED" }
        args.optString("expected_package").takeIf { it.isNotEmpty() }?.let { check(old.getString("package")==it) { "PHONE_WINDOW_CHANGED" } }
        val categories=PhoneApprovalPolicy.categories(old.getString("package"),args.getString("action"),args,target,facts.joinToString(" ") { it.text+" "+it.desc },mode=="visual")
        val scope=if(categories.isEmpty()) null else PhoneApprovalPolicy.Scope(categories.sorted().joinToString("+"),old.getString("package")+":"+args.optString("node_id"))
        val ticket=PhoneApprovalPolicy.authorize(context,owner,"phone_action",args,scope,token)
            ?: return JSONObject().put("status","REJECTED").put("submitted",false)
        token.throwIfCancelled(); PhoneApprovalPolicy.check(ticket)
        val live=read(s,JSONObject().put("mode",mode).put("fresh",true),token); VirtualDisplayScope.same(old,live)
        if(mode=="visual") check(old.getLong("event_epoch")==live.getLong("event_epoch")) { "PHONE_CONTENT_CHANGED" }
        val fresh=nodes(live); val now=target?.let { n -> fresh.singleOrNull { it==n } ?: error("TARGET_CHANGED") }
        PhoneApprovalPolicy.recheck(ticket,old.getString("package"),args.getString("action"),args,now,fresh,mode=="visual")
        // Approval may take longer than the bridge's snapshot TTL. Use the freshly rechecked
        // identity/target for prepare, while preserving the exact effect reviewed by the user.
        args.put("observation_id",live.getString("observation_id"))
        PhoneApprovalPolicy.check(ticket); check(s.valid()) { "VIRTUAL_DISPLAY_SESSION_LOST" }
        val result=s.bridge.action(args,token).put("display_session_id",s.id).put("display_id",s.identity.getInt("display_id"))
        s.observations.clear()
        if(result.optString("status")=="UNKNOWN") s.close()
        return after(s,args,result,token,observer)
    }
    private fun nodes(o: JSONObject): List<NodeFacts> {
        val a=o.optJSONArray("nodes") ?: JSONArray(); return (0 until a.length()).map { PhoneObservation.facts(a.getJSONObject(it)) }
    }
    private fun launch(s: VirtualDisplays.Session,arguments: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        val args=s.scope(arguments); val pkg=args.getString("package"); VirtualDisplayScope.packageName(pkg)
        require(args.optString("observe_after","ui") in setOf("none","ui","visual","hybrid")) { "PHONE_MODE_INVALID" }
        if(args.optString("observe_after","ui") in setOf("visual","hybrid")) check(observer!=null) { "PHONE_IMAGE_CHANNEL_REQUIRED" }
        check(pkg in VirtualDisplays.allowed(context)) { "VIRTUAL_DISPLAY_PACKAGE_NOT_ALLOWED" }
        val component=context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString() ?: error("PHONE_APP_NOT_LAUNCHABLE")
        args.put("action","launch").put("component",component)
        val ticket=PhoneApprovalPolicy.authorize(context,owner,"phone_launch",args,null,token)
            ?: return JSONObject().put("status","REJECTED").put("submitted",false)
        token.throwIfCancelled(); PhoneApprovalPolicy.check(ticket)
        val result=s.bridge.action(args,token).put("display_session_id",s.id).put("retry_safe",false).put("foreground_verified",false)
        s.observations.clear()
        if(result.optString("status")=="DISPATCHED") s.launched=true
        if(result.optString("status")=="UNKNOWN") s.close("PHONE_OPERATION_COMPLETION_UNKNOWN")
        return after(s,args,result,token,observer).also { r -> r.optJSONObject("observation")?.let { r.put("foreground_verified",it.optString("package")==pkg) } }
    }
    private fun after(s: VirtualDisplays.Session,args: JSONObject,result: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        val mode=args.optString("observe_after","ui"); require(mode in setOf("none","ui","visual","hybrid"))
        result.put("business_success_verified",false)
        if(result.optString("status")=="DISPATCHED" && mode!="none") {
            try {
                val ready=PhoneReady.await({readReady(s,JSONObject().put("mode",mode).put("fresh",true),token)}, {token.sleep(it)},{SystemClock.elapsedRealtime()},1500)
                val o=if(mode=="ui") ready.getJSONObject("observation") else observe(s,JSONObject(args.toString()).put("mode",mode),token,observer)
                result.put("observation",o).put("settle_status",ready.getString("status"))
            } catch(e: Exception) {
                result.put("observation_error",e.message ?: "PHONE_OBSERVATION_FAILED")
                if(e is ObservationFailure) result.put("observation_diagnostic",e.reply.optJSONObject("diagnostic") ?: JSONObject.NULL)
            }
        }
        return result
    }
    private fun wait(s: VirtualDisplays.Session,args: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        val until=args.optString("until","idle"); require(until in setOf("idle","visible","text","gone","package","editor")) { "PHONE_WAIT_UNKNOWN" }
        require(args.optString("mode","ui")=="ui") { "VIRTUAL_DISPLAY_WAIT_UI_REQUIRED" }
        val target=if(until in setOf("visible","text","gone")) PhoneWaitTarget.parse(args) else null
        val timeout=args.optLong("timeout_ms",3000); require(timeout in 0..10000)
        val start=SystemClock.elapsedRealtime(); var previous=""; var since=start
        while(true) {
            val o=readReady(s,JSONObject().put("fresh",true),token); val now=SystemClock.elapsedRealtime(); val key=PhoneReady.key(o)
            if(previous!=key) { previous=key; since=now }
            val ns=nodes(o); val found=target?.let { t -> ns.any(t::matches) } ?: false
            val met=when(until) {
                "idle" -> now-since>=args.optLong("stable_ms",200).coerceIn(100,1500)
                "package" -> o.getString("package")==args.getString("package")
                "editor" -> ns.any { it.editable && it.actionable }
                "gone" -> !o.optBoolean("truncated") && !found
                else -> found
            }
            if(met || now-start>=timeout) {
                val result=JSONObject().put("status",if(met) "MET" else "TIMEOUT").put("waited_ms",now-start).put("observation",o)
                if(args.optString("observe_after","none") in setOf("visual","hybrid")) result.put("observation",observe(s,JSONObject(args.toString()).put("mode",args.getString("observe_after")),token,observer))
                return result
            }
            token.sleep(minOf(90,(timeout-(now-start)).coerceAtLeast(0)))
        }
    }
    private fun flow(s: VirtualDisplays.Session,args: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        PhoneFlowRunner.validate(args).forEach { step ->
            val action=step.getString("action")
            check(action=="wait" || action=="launch" || action in VirtualDisplayScope.actions) { "VIRTUAL_DISPLAY_ACTION_UNSUPPORTED" }
            val pkg=step.getString(if(action=="launch") "package" else "expected_package"); VirtualDisplayScope.packageName(pkg)
            if(action=="launch") check(pkg in VirtualDisplays.allowed(context)) { "VIRTUAL_DISPLAY_PACKAGE_NOT_ALLOWED" }
        }
        return PhoneFlowRunner({readReady(s,JSONObject().put("fresh",true),token)},
            { a -> if(a.getString("action")=="launch") launch(s,a,token,observer) else action(s,a,token,observer) },
            {token.sleep(it)},{SystemClock.elapsedRealtime()}).run(args).put("display_session_id",s.id)
    }
    private fun sequence(s: VirtualDisplays.Session,args: JSONObject,token: CancellationToken,observer: Tools.Observer?): JSONObject {
        val initial=s.observations[args.getString("observation_id")] ?: error("PHONE_OBSERVATION_EXPIRED")
        val steps=args.getJSONArray("steps"); require(steps.length() in 1..8)
        val validated=(0 until steps.length()).map { JSONObject(steps.getJSONObject(it).toString()).also { a ->
            check(a.optString("action") in VirtualDisplayScope.actions) { "VIRTUAL_DISPLAY_ACTION_UNSUPPORTED" }
            check(a.getString("expected_package")==initial.getString("package")) { "PHONE_SEQUENCE_PACKAGE_CHANGED" }
            val validation=JSONObject(a.toString())
            if(a.has("target")) {
                PhoneWaitTarget.parse(a); validation.put("node_id","0")
                if(a.optString("action")=="long_press") validation.put("x",0).put("y",0)
            }
            VirtualDisplayScope.action(validation); PhoneAdbTools(context,owner).validateAction(validation)
        } }
        val receipts=JSONArray()
        for(a in validated) {
            try {
                val fresh=readReady(s,JSONObject().put("fresh",true),token); VirtualDisplayScope.same(initial,fresh)
                if(!a.has("target")) a.put("observation_id",fresh.getString("observation_id"))
                a.put("observe_after","none")
                val r=action(s,a,token,observer); receipts.put(r)
                if(r.optString("status")!="DISPATCHED") break
            } catch(e: Exception) {
                receipts.put(JSONObject().put("status","NOT_SUBMITTED").put("submitted",false).put("error",e.message ?: "PHONE_SEQUENCE_PREFLIGHT_FAILED")); break
            }
        }
        return JSONObject().put("status",if(receipts.length()==steps.length() && receipts.getJSONObject(receipts.length()-1).optString("status")=="DISPATCHED") "DISPATCHED" else "STOPPED")
            .put("steps",receipts).put("display_session_id",s.id).put("retry_safe",false)
    }
    private fun apps(args: JSONObject): JSONObject {
        val allowed=VirtualDisplays.allowed(context); val query=args.optString("query").lowercase(); val offset=args.optInt("offset",0); require(offset in 0..10000)
        val apps=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .distinctBy { it.activityInfo.packageName }.map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
            .filter { query.isBlank() || it.first.lowercase().contains(query) || it.second.lowercase().contains(query) }.sortedBy { it.first }
        return JSONObject().put("apps",JSONArray(apps.drop(offset).take(100).map { JSONObject().put("package",it.first).put("label",it.second).put("virtual_allowed",it.first in allowed) }))
            .put("total",apps.size).put("next_offset",if(offset+100<apps.size) offset+100 else -1)
    }
    companion object {
        fun attachment(frame: PhoneFrame,id: String)=Message.Attachment("virtual-$id-${frame.metadata.optLong("frame_sequence")}.png",null,"image/png",frame.png.size.toLong(),"image").apply {
            dataUri="data:image/png;base64,"+Base64.encodeToString(frame.png,Base64.NO_WRAP)
        }
    }
}
