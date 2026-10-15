package dev.wind.agent.phone

import android.content.Context
import android.os.Build
import dev.wind.agent.CancellationToken
import dev.wind.agent.adb.AdbCommands
import dev.wind.agent.permissions.AgentCapability
import dev.wind.agent.permissions.AgentPermissions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One device-wide display; an ended/lost display leaves a task tombstone until task end. */
internal object VirtualDisplays {
    const val POLICY=3
    const val FEATURE_REVISION=12
    
    private val bound=ConcurrentHashMap<String,Session>()
    private val creationFailures=ConcurrentHashMap<String,JSONObject>()
    @Volatile private var active: Session?=null
    internal class CreationFailure(code: String,val diagnostic: JSONObject): IllegalStateException(code)
    internal class Session(val owner: String,val bridge: PhoneBridge,val identity: JSONObject) {
        val id=identity.getString("display_session_id")
        val observations=LinkedHashMap<String,JSONObject>()
        @Volatile var closed=false
        @Volatile var launched=false
        fun valid()=!closed && bridge.valid()
        fun scope(args: JSONObject)=JSONObject(args.toString()).put("display_session_id",id)
        fun close(reason: String="VIRTUAL_DISPLAY_CLIENT_CLOSED") { closed=true; observations.clear(); bridge.close(reason) }
    }
    private fun prefs(c: Context)=c.getSharedPreferences("phone_virtual_display",Context.MODE_PRIVATE)
    fun preference(c: Context)=prefs(c).getString("target","main") ?: "main"
    fun choose(c: Context,value: String) {
        require(value in setOf("main","virtual")); prefs(c).edit().putString("target",value).apply()
    }
    fun compatibility(c: Context): JSONObject? {
        if(prefs(c).getString("compatibility_build","")!=Build.FINGERPRINT) return null
        return runCatching { JSONObject(prefs(c).getString("compatibility_result","").orEmpty()) }
            .getOrNull()?.takeIf { it.optInt("android_api",-1)==Build.VERSION.SDK_INT && it.optInt("feature_revision")==FEATURE_REVISION }
    }
    fun recordCompatibility(c: Context,bridgeStatus: JSONObject): JSONObject {
        val report=JSONObject(bridgeStatus.getJSONObject("virtual_display").toString())
            .put("ui_automation_available",bridgeStatus.optBoolean("ui")).put("checked_at",System.currentTimeMillis()).put("feature_revision",FEATURE_REVISION)
            .put("event_display_routing",bridgeStatus.optString("event_display_routing","UNKNOWN"))
            .put("control_transport",bridgeStatus.optString("control_transport","UNKNOWN")).put("launch_validation","ON_DEMAND_BEFORE_EFFECTS")
        require(report.optInt("android_api",-1)==Build.VERSION.SDK_INT && report.toString().length<=16384) { "VIRTUAL_DISPLAY_CAPABILITY_REPORT_INVALID" }
        val edit=prefs(c).edit().putString("compatibility_build",Build.FINGERPRINT).putString("compatibility_result",report.toString())
        edit.apply(); return report
    }
    /** User-triggered signature check: no display, fixture, screenshot or input is created. */
    fun inspectPlatform(c: Context,token: CancellationToken): JSONObject = PhoneCoordinator.run(token) {
        check(dev.wind.agent.AgentBus.activeTaskId.isNullOrBlank()) { "PHONE_DISPLAY_CHANGE_REQUIRES_IDLE_TASK" }
        for(cap in listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.SCREEN,AgentCapability.ACCESSIBILITY))
            check(AgentPermissions.get(c).enabled(cap)) { "AGENT_PERMISSION_REQUIRED: $cap" }
        PhoneManagedRecovery.stopAll(); PhoneBackends.stopAll()
        val b=PhoneBridge(c.applicationContext,"display-api-check-"+java.util.UUID.randomUUID(),token,virtualOnly=true)
        try { b.start(token); recordCompatibility(c,b.capabilities) } finally { b.close() }
    }
    fun allowed(c: Context): Set<String> = prefs(c).getStringSet("packages",emptySet())?.toSet().orEmpty()
    fun allow(c: Context,packages: Set<String>) {
        check(dev.wind.agent.AgentBus.activeTaskId.isNullOrBlank()) { "PHONE_DISPLAY_CHANGE_REQUIRES_IDLE_TASK" }
        require(packages.size<=16) { "VIRTUAL_DISPLAY_ALLOWLIST_LIMIT" }; packages.forEach(VirtualDisplayScope::packageName)
        stopAll(); prefs(c).edit().putStringSet("packages",packages).apply()
    }
    fun wants(c: Context,owner: String,args: JSONObject)=PhoneScreenTarget.resolve(args,preference(c))=="virtual"
    fun sharedMainBridge(owner: String): PhoneBridge?=bound[owner]?.takeIf { it.valid() }?.bridge
    private val scoped=setOf("phone_observe","phone_action","phone_wait","phone_launch","phone_flow","phone_sequence")
    fun scopeArgs(c: Context,owner: String,name: String,args: JSONObject): JSONObject {
        val out=JSONObject(args.toString())
        if(name in scoped || name=="phone_display" && out.optString("action")=="create") {
            val target=if(name=="phone_display") "virtual" else PhoneScreenTarget.resolve(out,preference(c))
            out.put("screen",target)
            if(target=="virtual") bound[owner]?.let { s ->
                if(out.has("display_session_id")) VirtualDisplayScope.session(s.id,out.getString("display_session_id"),s.identity.getInt("display_id"))
                out.put("display_session_id",s.id)
            }
        }
        return out
    }
    fun blockGlobal(c: Context,owner: String,name: String,args: JSONObject): String? {
        if(name=="phone_chat_guardian") return null
        val virtual=wants(c,owner,args)
        if(name.startsWith("adb_") && (virtual || active?.valid()==true)) {
            if(runCatching { AdbCommands.plan(name,args).mutating }.getOrDefault(true)) return "VIRTUAL_DISPLAY_GLOBAL_OPERATION_BLOCKED"
        }
        if(name.startsWith("phone_") && name !in scoped+setOf("phone_display","phone_status","phone_apps","phone_operation_status") && virtual)
            return "VIRTUAL_DISPLAY_GLOBAL_OPERATION_BLOCKED"
        return null
    }
    fun status(c: Context,owner: String): JSONObject {
        val s=bound[owner]
        val state=if(s==null) "NOT_CREATED" else if(s.valid()) "READY" else "CLOSED_OR_LOST"
        val adb=dev.wind.agent.adb.AdbManager.get(c).status(owner)
        val connection=JSONObject()
        for(key in listOf("native_available","available","connection_state","setup_stage","local_route","vpn_detected","last_error"))
            connection.put(key,adb.opt(key) ?: JSONObject.NULL)
        val missing=listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.SCREEN,AgentCapability.ACCESSIBILITY)
            .filter { !AgentPermissions.get(c).enabled(it,owner) }.map { it.name }
        return JSONObject().put("target",preference(c)).put("calibration_required",false).put("policy_version",POLICY)
            .put("feature_revision",FEATURE_REVISION).put("bridge_protocol",PhoneBridgeProtocol.VERSION)
            .put("android_api",Build.VERSION.SDK_INT).put("allowed_packages",JSONArray(allowed(c).sorted()))
            .put("status",state).put("adb",connection)
            .put("readiness",VirtualDisplayReadiness.evaluate(Build.VERSION.SDK_INT,allowed(c).sorted(),missing,connection,
                owner.isNotBlank(),state,active?.let { it.owner!=owner && it.valid() }==true,false,compatibility(c)))
            .put("platform_apis",compatibility(c) ?: JSONObject.NULL)
            .put("last_creation_failure",creationFailures[owner] ?: JSONObject.NULL)
            .put("display",s?.bridge?.liveDisplay ?: s?.identity ?: JSONObject.NULL).put("main_screen_fallback",false)
            .put("bridge",s?.bridge?.diagnostic() ?: JSONObject.NULL).put("app_launched",s?.launched ?: false)
    }
    fun preflightFailure(c: Context,owner: String,code: String)=JSONObject().put("status","NOT_SUBMITTED").put("submitted",false)
        .put("error",code).put("guidance",VirtualDisplayReadiness.guidance(code)).put("virtual_display",status(c,owner)).put("retry_safe",false)
    fun create(c: Context,owner: String,token: CancellationToken,independentScope: Boolean=false,allowedPackages: Set<String>?=null): Session {
        check(owner.isNotBlank()) { "PHONE_DISPLAY_TASK_REQUIRED" }
        check(bound[owner]==null) { "VIRTUAL_DISPLAY_SESSION_ALREADY_USED" }
        check(active?.valid()!=true) { "VIRTUAL_DISPLAY_DEVICE_BUSY" }
        check(allowed(c).isNotEmpty()) { "VIRTUAL_DISPLAY_ALLOWLIST_REQUIRED" }
        check(allowedPackages==null || allowedPackages.isNotEmpty() && allowed(c).containsAll(allowedPackages)) { "VIRTUAL_DISPLAY_PACKAGE_NOT_ALLOWED" }
        check(Build.VERSION.SDK_INT>=30) { "VIRTUAL_DISPLAY_REQUIRES_ANDROID_11" }
        val permissions=AgentPermissions.get(c)
        for(cap in listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.SCREEN,AgentCapability.ACCESSIBILITY))
            check(permissions.enabled(cap,owner)) { "AGENT_PERMISSION_REQUIRED: $cap" }
        PhoneManagedRecovery.stopAll(); PhoneBackends.stopAll()
        val bridge=PhoneBridge(c.applicationContext,owner,token,virtualOnly=true,independentScope=independentScope,allowedPackages=allowedPackages)
        var stage="START_BRIDGE"
        var remoteDiagnostic: JSONObject?=null
        creationFailures.remove(owner)
        try {
            bridge.start(token)
            stage="CHECK_APIS"
            val platform=recordCompatibility(c,bridge.capabilities)
            check(platform.optBoolean("supported")) { platform.optString("error","VIRTUAL_DISPLAY_REQUIRED_APIS_UNAVAILABLE") }
            stage="CREATE_DISPLAY"
            val created=bridge.rpc("display_create",JSONObject(),token)
            remoteDiagnostic=created.optJSONObject("diagnostic")
            check(created.optString("status")=="CREATED") { created.optString("error","VIRTUAL_DISPLAY_CREATE_FAILED") }
            val s=Session(owner,bridge,created); bound[owner]=s; active=s; return s
        } catch(e: Exception) {
            bridge.close(e.message ?: "VIRTUAL_DISPLAY_CREATE_FAILED"); token.throwIfCancelled()
            val diagnostic=remoteDiagnostic ?: JSONObject().put("failure_stage",stage)
                .put("authority",bridge.capabilities.optJSONObject("virtual_display")?.optJSONObject("authority") ?: JSONObject.NULL)
            diagnostic.put("bridge",bridge.diagnostic())
            creationFailures[owner]=JSONObject(diagnostic.toString()).put("error",e.message ?: "VIRTUAL_DISPLAY_CREATE_FAILED")
                .put("feature_revision",FEATURE_REVISION).put("checked_at",System.currentTimeMillis())
            throw CreationFailure(e.message ?: "VIRTUAL_DISPLAY_CREATE_FAILED",diagnostic)
        }
    }
    fun get(c: Context,owner: String,args: JSONObject,token: CancellationToken): Session {
        token.throwIfCancelled(); val s=bound[owner] ?: error("VIRTUAL_DISPLAY_CREATE_REQUIRED")
        if(!s.valid()) { s.close("VIRTUAL_DISPLAY_SESSION_LOST"); error("VIRTUAL_DISPLAY_SESSION_LOST") }
        if(args.has("display_session_id")) VirtualDisplayScope.session(s.id,args.getString("display_session_id"),s.identity.getInt("display_id"))
        return s
    }
    fun close(owner: String,args: JSONObject,token: CancellationToken): JSONObject {
        val s=bound[owner] ?: error("VIRTUAL_DISPLAY_CREATE_REQUIRED")
        VirtualDisplayScope.session(s.id,args.optString("display_session_id"),s.identity.getInt("display_id"))
        try { if(s.valid()) return s.bridge.rpc("display_close",s.scope(JSONObject()),token) }
        finally { s.close("VIRTUAL_DISPLAY_EXPLICIT_CLOSE"); if(active===s) active=null }
        return JSONObject().put("status","CLOSED").put("display_session_id",s.id)
    }
    fun stopAll() { active?.close("VIRTUAL_DISPLAY_STOP_ALL"); active=null; bound.values.forEach { it.close("VIRTUAL_DISPLAY_STOP_ALL") } }
    fun endTask(owner: String) { bound.remove(owner)?.let { it.close("PHONE_BRIDGE_TASK_ENDED"); if(active===it) active=null }; creationFailures.remove(owner) }
    fun preview(owner: String): Session? = bound[owner]?.takeIf { it.valid() }
    fun mainAllowed(owner: String)=active?.let { it.owner==owner || !it.valid() } ?: true
}
