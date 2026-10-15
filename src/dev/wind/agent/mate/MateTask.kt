package dev.wind.agent.mate

import dev.wind.agent.memory.MemoryRules
import org.json.JSONArray
import org.json.JSONObject

enum class MateTaskState { CREATED, PREPARING, RUNNING, WAITING_FOR_EVENT, WAITING_FOR_USER, PAUSED, NEEDS_REAUTHORIZE, UNKNOWN, COMPLETED, FAILED, CANCELLED }
data class MateResource(val kind: String,val id: String,val state: String) {
    fun json()=JSONObject().put("kind",kind).put("id",id).put("state",state)
    companion object {fun parse(j: JSONObject)=MateResource(j.getString("kind"),j.getString("id"),j.getString("state")).also {
        require(it.kind in setOf("guardian","browser","display") && MemoryRules.id(it.id) && it.state in setOf("ACTIVE","PAUSED","RELEASED","NEEDS_REAUTHORIZE"))
    }}
}
/** Bounded tool-origin evidence, never a model assertion of business success or permission. */
data class ObservationEvidence(val id: String,val tool: String,val status: String,val observedAt: Long,val quote: String,val resultDigest: String) {
    fun json()=JSONObject().put("id",id).put("tool",tool).put("status",status).put("observedAt",observedAt).put("quote",quote).put("resultDigest",resultDigest)
    companion object {fun parse(j: JSONObject)=ObservationEvidence(j.getString("id"),j.getString("tool"),j.getString("status"),j.getLong("observedAt"),j.getString("quote"),j.getString("resultDigest")).also {
        require(MemoryRules.id(it.id) && it.tool.matches(Regex("[a-z_]{1,80}")) && it.status.length<=100 && it.observedAt>0 && it.quote.length<=1200 && it.resultDigest.matches(Regex("[a-f0-9]{64}")))
    }}
}
data class MateTask(val id: String,val chatId: String,val revision: Long,val goal: String,val sourceMessage: String,val state: MateTaskState,
    val createdAt: Long,val updatedAt: Long,val executionId: String="",val continuous: Boolean=false,val needsObservation: Boolean=true,
    val progress: String="",val outcome: String="",val resources: List<MateResource> = emptyList(),val evidence: List<ObservationEvidence> = emptyList(),
    val watermarks: Map<String,Long> = emptyMap(),val seenEvents: List<String> = emptyList(),val notes: List<String> = emptyList(),val wakeAt: Long=0,val plannedWakeAt: Long=0) {
    val terminal get()=state in setOf(MateTaskState.COMPLETED,MateTaskState.CANCELLED,MateTaskState.FAILED)
    fun json()=JSONObject().put("id",id).put("chatId",chatId).put("revision",revision).put("goal",goal).put("sourceMessage",sourceMessage)
        .put("state",state.name).put("createdAt",createdAt).put("updatedAt",updatedAt).put("executionId",executionId).put("continuous",continuous)
        .put("needsObservation",needsObservation).put("progress",progress).put("outcome",outcome).put("resources",JSONArray(resources.map {it.json()}))
        .put("evidence",JSONArray(evidence.map {it.json()})).put("watermarks",JSONObject(watermarks)).put("seenEvents",JSONArray(seenEvents)).put("notes",JSONArray(notes)).put("wakeAt",wakeAt).put("plannedWakeAt",plannedWakeAt)
    /** A restored ID and outcome are data; old sessions, observations and approvals are not authority. */
    fun recovered()=copy(executionId="",needsObservation=true,wakeAt=0,resources=resources.map {it.copy(state=if(it.state=="RELEASED")"RELEASED" else "NEEDS_REAUTHORIZE")},
        state=if(terminal || state==MateTaskState.UNKNOWN)state else MateTaskState.NEEDS_REAUTHORIZE)
    companion object {
        fun parse(j: JSONObject): MateTask {
            val r=j.getJSONArray("resources");val e=j.getJSONArray("evidence");val w=j.getJSONObject("watermarks");val seen=j.getJSONArray("seenEvents");val notes=j.getJSONArray("notes")
            require(r.length()<=12 && e.length()<=24 && w.length()<=64 && seen.length()<=128 && notes.length()<=32)
            val marks=buildMap<String,Long> {val keys=w.keys();while(keys.hasNext()) {val key=keys.next();require(key.length in 1..160 && w.getLong(key)>=0);put(key,w.getLong(key))}}
            return MateTask(j.getString("id"),j.getString("chatId"),j.getLong("revision"),j.getString("goal"),j.getString("sourceMessage"),MateTaskState.valueOf(j.getString("state")),
                j.getLong("createdAt"),j.getLong("updatedAt"),j.optString("executionId"),j.optBoolean("continuous"),j.optBoolean("needsObservation",true),j.optString("progress"),j.optString("outcome"),
                (0 until r.length()).map {MateResource.parse(r.getJSONObject(it))},(0 until e.length()).map {ObservationEvidence.parse(e.getJSONObject(it))},marks,
                (0 until seen.length()).map {seen.getString(it)},(0 until notes.length()).map {notes.getString(it)},j.optLong("wakeAt"),j.optLong("plannedWakeAt")).also {
                require(MemoryRules.id(it.id) && MemoryRules.id(it.chatId) && MemoryRules.id(it.sourceMessage) && it.revision>0 && it.goal.length in 1..2000 && it.progress.length<=1000 && it.outcome.length<=1000)
                require(it.createdAt>0 && it.updatedAt>=it.createdAt && it.executionId.length<=160 && it.seenEvents.all {s->s.matches(Regex("[a-f0-9]{64}"))} && it.notes.all {s->s.length<=500})
                require(it.wakeAt>=0 && it.plannedWakeAt>=0)
            }
        }
    }
}
