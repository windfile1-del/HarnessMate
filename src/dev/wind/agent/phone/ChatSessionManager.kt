package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import dev.wind.agent.Prefs
import dev.wind.agent.permissions.AgentCapability
import dev.wind.agent.permissions.AgentPermissions
import org.json.JSONObject

internal object ChatSessionManager {
    class Runtime(val id: String, val owner: String, val token: CancellationToken, val model: Prefs) {
        @Volatile var lease: VirtualDisplays.Session?=null
        @Volatile var modelToken: CancellationToken?=null
        @Volatile var deliveryToken: CancellationToken?=null
    }
    @Volatile var active: Runtime?=null; private set
    val required=listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.ACCESSIBILITY,AgentCapability.SCREEN)
    fun checkPermissions(c: Context) {
        required.forEach { check(AgentPermissions.get(c).enabled(it)) { "CHAT_GLOBAL_PERMISSION_REQUIRED: $it" } }
    }
    @Synchronized fun begin(c: Context,id: String): Runtime {
        check(active==null) { "CHAT_GUARDIAN_DEVICE_BUSY" }; checkPermissions(c)
        val s=ChatConversationStore.get(c).session(id)
        check(s.getString("state")=="STARTING") { "CHAT_NEW_USER_AUTHORIZATION_REQUIRED" }
        val prefs=Prefs(c).freezeModel()
        check(modelKey(prefs)==s.getString("model_key")) { "CHAT_MODEL_CHANGED_REAUTHORIZE" }
        return Runtime(id,"chat-guardian:"+java.util.UUID.randomUUID(),CancellationToken(),prefs).also { active=it }
    }
    fun modelKey(p: Prefs)=p.selectedModelProvider()?.let { it.id+":"+it.model } ?: "legacy:"+p.model()
    fun open(c: Context,r: Runtime): VirtualDisplays.Session = PhoneCoordinator.run(r.token) {
        checkPermissions(c)
        val s=ChatConversationStore.get(c).session(r.id); val pkg=s.getString("package")
        check(pkg in VirtualDisplays.allowed(c)) { "CHAT_PACKAGE_NOT_ALLOWED" }
        val lease=VirtualDisplays.create(c,r.owner,r.token,independentScope=true,allowedPackages=setOf(pkg)); r.lease=lease
        val component=c.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString() ?: error("CHAT_APP_NOT_LAUNCHABLE")
        val result=lease.bridge.action(lease.scope(JSONObject().put("screen","virtual").put("action","launch").put("package",pkg)
            .put("component",component).put("task_policy","reuse_or_move")),r.token)
        check(result.optString("status")=="DISPATCHED") { "CHAT_LAUNCH_RESULT_UNKNOWN" }
        lease.launched=true
        ChatConversationStore.get(c).change(r.id) { it.put("state","SETUP").put("display_session_id",lease.id).put("display_id",lease.identity.getInt("display_id")) }
        lease
    }
    fun authority(c: Context,r: Runtime,version: Long?=null) {
        r.token.throwIfCancelled(); check(active===r && r.lease?.valid()==true) { "CHAT_LEASE_REVOKED" }; checkPermissions(c)
        val s=ChatConversationStore.get(c).session(r.id)
        check(s.optString("state")=="RUNNING") { "CHAT_SESSION_NOT_RUNNING" }
        check(version==null || s.optLong("message_version")==version) { "CHAT_MESSAGE_VERSION_CHANGED" }
    }
    /** Only the original AI conversation may prepare/navigate a paused lease. Running control is exclusive. */
    fun controlOwner(c: Context,caller: String,args: JSONObject): String {
        val r=active ?: return caller
        if(args.optString("display_session_id")!=r.lease?.id) return caller
        val s=ChatConversationStore.get(c).session(r.id)
        return ChatLeaseRouting.owner(caller,args.optString("display_session_id"),r.lease?.id,r.owner,s.optString("chat_id"),AgentBus.activeChatId,s.optString("state"))
    }
    @Synchronized fun close(id: String) {
        val r=active?.takeIf { it.id==id } ?: return
        active=null; r.modelToken?.cancel(); r.deliveryToken?.cancel(); r.token.cancel(); VirtualDisplays.endTask(r.owner)
    }
}
