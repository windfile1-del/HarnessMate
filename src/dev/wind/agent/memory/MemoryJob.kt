package dev.wind.agent.memory

import dev.wind.agent.*
import dev.wind.agent.profile.*
import dev.wind.agent.modelprovider.ModelProvider
import org.json.*

/** Non-authoritative originating context; no keys, URLs, cookies or capability grants. */
data class MemoryCapture(val mode: String,val profile: String,val policy: String,val project: String?,val providerId: String?,val providerHash: String,val task: String,val restored: Boolean=false,val restoredBaseline: Boolean=false) {
    fun json()=JSONObject().put("mode",mode).put("profile",profile).put("policy",policy).put("project",project ?: "")
        .put("providerId",providerId ?: "").put("providerHash",providerHash).put("task",task).put("restored",restored).also {if(restoredBaseline)it.put("restoredBaseline",true)}
    companion object {
        fun providerHash(p: ModelProvider)=MemoryRules.hash("${p.id}\u0000${p.url}\u0000${p.model}\u0000${p.protocol}\u0000${p.effort}\u0000${p.subscription}\u0000${p.selected().protocol}\u0000${p.selected().wireIds[p.effort]}\u0000${p.subject}\u0000${p.project}")
        fun capture(chat: Chat,prefs: Prefs): MemoryCapture {
            val p=prefs.selectedModelProvider()
            return MemoryCapture(prefs.mode(),chat.agentProfile.name,chat.memoryPolicy.name,
                chat.projectMemoryKey.takeIf { it!=null && it==chat.projectBindingId },p?.id,p?.let(::providerHash) ?: "",chat.mateTaskId.takeIf {chat.agentProfile==AgentProfile.MATE} ?: chat.id)
        }
        fun parse(j: JSONObject)=MemoryCapture(j.getString("mode"),j.getString("profile"),j.getString("policy"),j.optString("project").ifBlank { null },
            j.optString("providerId").ifBlank { null },j.optString("providerHash"),j.getString("task"),j.optBoolean("restored"),j.optBoolean("restoredBaseline")).also {
            require(it.mode in listOf(Prefs.MODE_AUTO,Prefs.MODE_ACCEPT,Prefs.MODE_PLAN) && it.profile in listOf("CODE","MATE") && it.policy in listOf("NORMAL","READ_ONLY","OFF") && MemoryRules.id(it.task))
            require(it.project==null || MemoryRules.id(it.project))
            require(!it.restoredBaseline || it.restored && it.providerId==null && it.providerHash.isEmpty() && it.policy=="OFF")
        }
    }
}
data class MemoryInput(val messageId: String,val revision: Long,val digest: String,val offset: Int,val total: Int,val text: String,val capture: MemoryCapture,val end: Int=offset+text.length) {
    fun json()=JSONObject().put("messageId",messageId).put("revision",revision).put("digest",digest).put("offset",offset).put("total",total).put("text",text).put("end",end).put("capture",capture.json())
    companion object {
        fun parse(j: JSONObject)=MemoryInput(j.getString("messageId"),j.getLong("revision"),j.getString("digest"),j.getInt("offset"),j.getInt("total"),j.getString("text"),MemoryCapture.parse(j.getJSONObject("capture")),j.getInt("end")).also {
            require(MemoryRules.id(it.messageId) && it.revision>0 && it.digest.matches(Regex("[a-f0-9]{64}")) && it.offset>=0 && it.end>it.offset && it.end<=it.total && it.end-it.offset<=4000 && it.text.length<=12000)
        }
    }
}
data class MemoryJob(val id: String,val chatId: String,val namespace: String,val epoch: Long,val inputs: List<MemoryInput>,val versions: Map<String,Long>,val createdAt: Long,
    val due: Long,val state: String="QUEUED",val lease: String="",val leaseUntil: Long=0,val attempts: Int=0,val error: String="") {
    val capture get()=inputs.first().capture
    fun json()=JSONObject().put("id",id).put("chatId",chatId).put("namespace",namespace).put("epoch",epoch).put("inputs",JSONArray(inputs.map { it.json() }))
        .put("versions",JSONObject(versions)).put("createdAt",createdAt).put("due",due).put("state",state).put("lease",lease).put("leaseUntil",leaseUntil).put("attempts",attempts).put("error",error.take(160))
    companion object {
        fun key(scope: MemoryScope,scopeKey: String,key: String)="${scope.name}:$scopeKey:$key"
        fun parse(j: JSONObject): MemoryJob {
            val a=j.getJSONArray("inputs");val v=j.getJSONObject("versions");val keys=v.keys();val versions=mutableMapOf<String,Long>();while(keys.hasNext()) {val k=keys.next();versions[k]=v.getLong(k)}
            require(a.length() in 1..8 && versions.size<=MemoryRules.MAX_RECORDS && versions.values.all {it>0})
            return MemoryJob(j.getString("id"),j.getString("chatId"),j.getString("namespace"),j.getLong("epoch"),(0 until a.length()).map { MemoryInput.parse(a.getJSONObject(it)) },versions,j.getLong("createdAt"),j.getLong("due"),j.getString("state"),j.optString("lease"),j.optLong("leaseUntil"),j.optInt("attempts"),j.optString("error")).also {
                require(MemoryRules.id(it.id) && MemoryRules.id(it.chatId) && MemoryRules.id(it.namespace) && it.epoch>0 && it.createdAt>=0 && it.due>=0 && it.attempts>=0)
                require(it.state in listOf("QUEUED","RUNNING","RETRY","WAITING","CANCELLED") && it.inputs.sumOf {input->input.text.length}<=8000 && it.inputs.all {input->input.capture==it.capture})
            }
        }
    }
}
data class MemoryProposal(val key: String,val summary: String,val evidence: String,val sourceIndex: Int,val scope: MemoryScope,val kind: String,val value: String,val expires: Long,val correction: Boolean)
