package dev.wind.agent

import dev.wind.agent.phone.*
import dev.wind.agent.permissions.*
import dev.wind.agent.capability.*
import org.json.JSONObject
import java.io.File

/** Real production guard functions on adversarial scopes/frames, plus existing broker integration. */
object VirtualDisplayTests {
    private var checks=0
    private fun ok(value: Boolean,label: String) { checks++; check(value) { label } }
    private fun refused(label: String,attempt: () -> Unit) { val failure=runCatching(attempt).exceptionOrNull(); ok(failure!=null,label) }
    private fun observation()=JSONObject().put("display_session_id","session-a").put("display_epoch","session-a")
        .put("display_id",7).put("geometry_epoch",1).put("width",720).put("height",1280).put("rotation",0)
        .put("package","org.example.fixture").put("window","uia:12").put("structural_epoch",3).put("event_epoch",8)
    @JvmStatic fun main(args: Array<String>) {
        ok(PhoneBridgeProtocol.VERSION==2,"host and embedded protocol upgraded")
        val health=PhoneBridgeHealth(); health.requested("observe"); health.failed("HEARTBEAT","ADB_RAW_CLOSED"); health.failed("CLIENT","VIRTUAL_DISPLAY_SESSION_LOST")
        ok(health.snapshot().getJSONObject("close_reason").getString("code")=="ADB_RAW_CLOSED","cleanup retains the first bridge failure")
        ok(health.snapshot().getJSONObject("close_reason").getString("method")=="observe","failure retains only operation name")
        val controls=listOf(false to false,true to false,false to true)
        for((cancelled,ended) in controls) {
            val cause=PhoneBridgeHealth.authorityFailure(cancelled,ended,3,3,7,7,true)
            ok(cause==if(cancelled) "PHONE_BRIDGE_CANCELLED" else if(ended) "PHONE_BRIDGE_TASK_ENDED" else null,"task and cancellation have distinct loss reasons")
        }
        ok(PhoneBridgeHealth.authorityFailure(false,false,3,4,7,7,true)=="PHONE_BRIDGE_ADB_EPOCH_CHANGED","ADB reconnection is distinguished")
        ok(PhoneBridgeHealth.authorityFailure(false,false,3,3,7,8,true)=="PHONE_BRIDGE_PERMISSION_REVISION_CHANGED","revision change with grants still enabled explains preflight loss")
        ok(PhoneBridgeHealth.authorityFailure(false,false,3,3,7,7,false)=="PHONE_BRIDGE_PERMISSION_REVOKED","missing effective authority stops the session")
        val diagnostic="state=EXIT\npid=2001\ncode=CONTROL_EOF\nmethod=observe\nat=1791446150233\n"
        ok(PhoneBridgeProtocol.exitDiagnostic(diagnostic)?.getString("code")=="CONTROL_EOF","bounded journal is parsed independently of ADB stderr")
        val released=PhoneBridgeProtocol.exitDiagnostic(diagnostic.replace("state=EXIT","state=DISPLAY_CLOSED").replace("CONTROL_EOF","PHONE_LOCKED"))!!
        ok(released.getString("state")=="DISPLAY_CLOSED" && released.getString("code")=="PHONE_LOCKED","主动释放保留首因且不冒充进程异常退出")
        for(invalid in listOf(diagnostic+"secret=private\n",diagnostic.replace("CONTROL_EOF","raw user content"),diagnostic.replace("pid=2001","pid=0"),diagnostic.replace("at=1791446150233","at=-1"),diagnostic.repeat(100)))
            ok(PhoneBridgeProtocol.exitDiagnostic(invalid)==null,"unexpected/sensitive diagnostic fields are refused")
        val crash=diagnostic.replace("CONTROL_EOF","UNCAUGHT_BRIDGE_FAILURE")+
            "diagnostic_version=1\nthread=vega-phone-ui\nexception_class=java.lang.NoSuchMethodError\ncause_classes=java.lang.SecurityException\nstack_frames=dev.wind.phonebridge.Main#lambda${'$'}new${'$'}0:112;android.os.HandlerThread#run:67\n"
        val parsed=PhoneBridgeProtocol.exitDiagnostic(crash)!!
        ok(parsed.getString("exception_class")=="java.lang.NoSuchMethodError" && parsed.getString("thread")=="vega-phone-ui","crash class and thread reach structured results")
        ok(parsed.getJSONArray("stack_frames").getJSONObject(0).getInt("line")==112 && parsed.getJSONArray("cause_classes").getString(0)=="java.lang.SecurityException","stack location and bounded cause chain parsed")
        for(invalid in listOf(crash+"pid=2001\n",crash.replace("diagnostic_version=1","diagnostic_version=2"),crash.replace("vega-phone-ui","user secret"),
            crash.replace("java.lang.NoSuchMethodError","raw secret text"),crash.replace(":112",":-3"),crash+"message=private\n",
            crash.replace("state=EXIT","state=RUNNING"),crash.replace("cause_classes=java.lang.SecurityException","cause_classes="+List(4){"java.lang.Exception"}.joinToString(";")),
            crash.replace("stack_frames=","stack_frames="+List(9){"a.B#run:1"}.joinToString(";")+";")))
            ok(PhoneBridgeProtocol.exitDiagnostic(invalid)==null,"malformed, oversized or unrecognized crash details refused")
        val secret="test-secret"; val value="test-session:test-challenge:"
        val startup=JSONObject().put("status","START_FAILED").put("error","PHONE_BRIDGE_STARTUP_FAILED")
            .put("diagnostic",JSONObject().put("failure_stage","SYSTEM_CONTEXT").put("exception_class","java.lang.NullPointerException").put("pid",18362).put("android_api",36).put("message","private"))
        val boot=PhoneBridgeProtocol.bootstrapDiagnostic(startup)!!
        ok(boot.getString("failure_stage")=="SYSTEM_CONTEXT" && boot.getInt("pid")==18362 && !boot.toString().contains("private"),"startup framing retains stage/pid/class without exception content")
        for(invalid in listOf(JSONObject(startup.toString()).put("error","unknown"),JSONObject(startup.toString()).put("status","RUNNING"),
            JSONObject(startup.toString()).also { it.getJSONObject("diagnostic").put("failure_stage","raw user text") },
            JSONObject(startup.toString()).also { it.getJSONObject("diagnostic").put("pid",0) }))
            ok(PhoneBridgeProtocol.bootstrapDiagnostic(invalid)==null,"invalid bootstrap diagnostics rejected")
        ok(PhoneBridgeProtocol.exitDiagnostic(crash.replace("UNCAUGHT_BRIDGE_FAILURE","STARTUP_BRIDGE_FAILURE"))?.getString("exception_class")=="java.lang.NoSuchMethodError","startup exception journal is parsed without confusing it with uncaught runtime exit")
        ok(PhoneBridgeProtocol.hmac(secret,value+"control")!=PhoneBridgeProtocol.hmac(secret,value+"lease"),"heartbeat authentication cannot reuse a control proof")
        VirtualDisplayScope.session("session-a","session-a",7); ok(true,"exact own scope")
        for((expected,supplied,display) in listOf(Triple("session-a","session-b",7),Triple(null,"session-a",7),Triple("session-a","",7),Triple("session-a","session-a",0),Triple("session-a","session-a",-1)))
            refused("closed/missing/other/default display denied") { VirtualDisplayScope.session(expected,supplied,display) }
        val scope=JSONObject().put("display_session_id","session-a")
        val frame=observation().put("frame_sequence",11).put("frame_timestamp_ns",1400000000)
        VirtualDisplayScope.frame(scope,frame); ok(true,"bound frame accepted")
        for((key,value) in listOf("display_session_id" to "other","display_id" to 0,"display_epoch" to "prior","geometry_epoch" to 2,"width" to 1280,"height" to 720,"rotation" to 2,"frame_sequence" to 0,"frame_timestamp_ns" to 0))
            refused("frame $key cannot be rebound") { VirtualDisplayScope.frame(scope,JSONObject(frame.toString()).put(key,value)) }
        VirtualDisplayScope.same(observation(),observation()); ok(true,"matching structural scope")
        for(key in listOf("display_session_id","display_id","display_epoch","geometry_epoch","package","window","width","height","rotation","structural_epoch")) {
            refused("new $key invalidates input") { VirtualDisplayScope.same(observation(),JSONObject(observation().toString()).put(key,"changed")) }
            refused("missing $key cannot silently select main") { VirtualDisplayScope.same(observation(),JSONObject(observation().toString()).also { it.remove(key) }) }
        }
        VirtualDisplayScope.same(observation(),observation().put("event_epoch",9)); ok(true,"UI content drift retains structural identity")
        for(action in listOf("home","recents","ime_action","notifications","quick_settings","shell")) refused("global $action refused") { VirtualDisplayScope.action(JSONObject().put("action",action).put("node_id","0.1")) }
        for(action in listOf("set_text","type_text")) {
            VirtualDisplayScope.action(JSONObject().put("action",action)); ok(true,"focused WebView text needs no UI node")
            VirtualDisplayScope.action(JSONObject().put("action",action).put("node_id","0.1")); ok(true,"direct node text path")
        }
        refused("OCR anchors are observation-only in v1") { VirtualDisplayScope.action(JSONObject().put("action","tap").put("visual_id","v0")) }
        for(pkg in listOf("dev.wind.agent","com.android.permissioncontroller","com.android.packageinstaller")) {
            VirtualDisplayScope.packageName(pkg); ok(true,"副屏实际窗口包不按名称拦截")
        }
        refused("invalid package") { VirtualDisplayScope.packageName("org.example;input tap 1 1") }
        val permissions=AgentPermissions(); val gate=AgentPermissionGate(permissions) { null }
        val required=setOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.ACCESSIBILITY,AgentCapability.SCREEN)
        ok(gate.needs("phone_display",JSONObject().put("action","status")).isEmpty(),"status needs no new authority")
        ok(gate.needs("phone_display",JSONObject().put("action","close")).isEmpty(),"revocation cannot prevent cleanup")
        ok(gate.needs("phone_display",JSONObject().put("action","create")).map { it.capability }.toSet()==required,"creation requires four distinct grants")
        for(name in listOf("phone_observe","phone_action","phone_launch","phone_wait","phone_flow")) ok(gate.needs(name,scope).map { it.capability }.toSet()==required,"scoped $name cannot downgrade authority")
        val resolver=CapabilityResolver { null }; val plan=TaskContext("task","",0,CapabilityMode.PLAN)
        ok(resolver.resolve(plan,"phone_display",JSONObject().put("action","status")) is CapabilityRequirement.Internal,"PLAN status permitted")
        for(action in listOf("create","close","probe","allowlist","calibrate")) ok(resolver.resolve(plan,"phone_display",JSONObject().put("action",action)) is CapabilityRequirement.Denied,"PLAN/agent lifecycle $action refused")
        for(action in listOf("create","close")) ok(resolver.resolve(plan.copy(mode=CapabilityMode.ACCEPT),"phone_display",JSONObject().put("action",action)) is CapabilityRequirement.Internal,"normal mode $action uses phone authority")
        permissions.set(AgentCapability.VIRTUAL_DISPLAY,PermissionScope.TASK,true,taskId="task")
        ok(permissions.enabled(AgentCapability.VIRTUAL_DISPLAY,"task") && !permissions.enabled(AgentCapability.VIRTUAL_DISPLAY,"other"),"grant is task-scoped")
        permissions.endTask("task"); ok(!permissions.enabled(AgentCapability.VIRTUAL_DISPLAY,"task"),"task end revokes display grant")
        val adb=JSONObject().put("available",true).put("native_available",true)
        fun readiness(api: Int=35,apps: List<String> = listOf("org.example.fixture"),missing: List<String> = emptyList(),
            connection: JSONObject=adb,task: Boolean=true,state: String="NOT_CREATED",busy: Boolean=false,main: Boolean=false,platform: JSONObject?=null) =
            VirtualDisplayReadiness.evaluate(api,apps,missing,connection,task,state,busy,main,platform)
        ok(PhoneScreenTarget.resolve(JSONObject().put("screen","main"),"virtual")=="main","explicit main overrides virtual default in the same chat")
        ok(PhoneScreenTarget.resolve(JSONObject().put("screen","virtual"),"main")=="virtual","explicit virtual overrides main default")
        ok(PhoneScreenTarget.resolve(JSONObject().put("display_session_id","s"),"main")=="virtual","scope explicitly selects virtual")
        refused("conflicting main/session refused") { PhoneScreenTarget.resolve(JSONObject().put("screen","main").put("display_session_id","s"),"main") }
        refused("invalid screen refused") { PhoneScreenTarget.resolve(JSONObject().put("screen","other"),"main") }
        ok(readiness(main=true).getBoolean("creation_ready"),"a prior main target does not lock future virtual creation")
        val ready=readiness()
        ok(ready.getBoolean("creation_ready") && ready.getBoolean("setup_ready"),"configured task can explicitly create")
        ok(ready.getJSONObject("next_tool").getJSONObject("arguments").getString("action")=="create","agent receives next actual lifecycle call")
        ok(!ready.getBoolean("remote_session_verified"),"local checks never claim remote display acceptance")
        for(blocked in listOf(readiness(api=29),readiness(apps=emptyList()),readiness(missing=listOf("SCREEN")),
            readiness(connection=JSONObject().put("available",false).put("native_available",true)),readiness(connection=JSONObject().put("available",true).put("native_available",false)),
            readiness(task=false),readiness(state="CLOSED_OR_LOST"),readiness(busy=true))) {
            ok(!blocked.getBoolean("creation_ready"),"every unmet prerequisite prevents creation")
            ok(blocked.getJSONArray("blockers").length()>0 && !blocked.has("next_tool"),"blocked agents get guidance instead of an executable create hint")
            ok(blocked.getJSONArray("blockers").getJSONObject(0).getString("user_action").isNotBlank(),"blocked setup identifies a user action")
        }
        ok(readiness(task=false).getBoolean("setup_ready"),"idle user can finish setup without claiming a task")
        ok(!readiness(state="READY").getBoolean("creation_ready"),"existing session cannot be recreated")
        for(cap in required) ok(!readiness(missing=listOf(cap.name)).getBoolean("creation_ready"),"each independent capability still required")
        val shared=JSONObject().put("supported",true).put("focus_isolation_available",false).put("ui_automation_available",true)
        for(api in listOf(30,31,32,33,34,35)) {
            ok(readiness(api=api).getBoolean("creation_ready"),"no calibration or optional inspection gate on API $api")
            ok(readiness(api=api,platform=shared).getBoolean("creation_ready"),"missing independent focus does not block API $api")
        }
        ok(!readiness().getBoolean("calibration_required") && readiness().getBoolean("focus_contention_possible"),"relaxed mode states actual boundary")
        ok(!readiness().getBoolean("app_system_permission_grant_required"),"ordinary app signature grants are not a prerequisite")
        for(error in listOf("VIRTUAL_DISPLAY_SHELL_PERMISSION_DENIED","SHELL_UID_REQUIRED","Permission android.permission.ADD_TRUSTED_DISPLAY requested by dev.wind.agent is not a changeable permission type")) {
            val guidance=VirtualDisplayReadiness.guidance(error)
            ok(guidance.contains("UID 2000") && guidance.contains("不能通过 pm grant") && guidance.contains("failure_stage"),"permission failures identify shell authority and diagnostic stage")
        }
        ok(!readiness(platform=JSONObject().put("supported",false).put("missing_apis",org.json.JSONArray(listOf("android.view.InputEvent.setDisplayId")))).getBoolean("creation_ready"),"required input APIs still gate control")
        ok(!readiness(platform=JSONObject().put("supported",true).put("ui_automation_available",false)).getBoolean("creation_ready"),"unavailable shell automation remains blocked")
        // The new branch's effect surface must remain free of helpers that mutate/capture the main screen.
        val source=File("src/dev/wind/agent/phone/PhoneVirtualTools.kt").readText()
        for(forbidden in listOf("PhoneOverlayScope","yieldToTarget","PhoneAdbIme","PhoneClipboard","takeScreenshot","ScreenShareSession","manager.execute")) ok(!source.contains(forbidden),"virtual effect boundary excludes $forbidden")
        println("VirtualDisplayTests: $checks checks PASS; actual device focus/IME/surface NOT_RUN")
    }
}
