package dev.wind.agent.phone

import dev.wind.agent.CancellationToken
import dev.wind.agent.LlmClient
import dev.wind.agent.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Independent tool-free inference: does not enter AgentBus's ordinary task slot. */
internal object ChatAgentDispatcher {
    fun decide(model: Prefs, session: JSONObject, batch: JSONObject, summaries: List<JSONObject>, token: CancellationToken): ChatDecisionResult {
        return decide(LlmClient(model).forChatGuardian(),session,batch,summaries,token)
    }
    internal fun decide(client: LlmClient, session: JSONObject, batch: JSONObject, summaries: List<JSONObject>, token: CancellationToken): ChatDecisionResult {
        // Preserve valid JSON while bounding quoted page context, rather than slicing a JSON document.
        var remaining=12000
        val context=ChatSessionKeys.turns(batch.optJSONArray("context") ?: JSONArray()).takeLast(24).reversed().mapNotNull {
            if(remaining<=0) null else ChatPageParser.Turn(it.role,it.text.take(minOf(2000,remaining))).also { t->remaining-=t.text.length }
        }.reversed()
        val data=JSONObject().put("conversation_id",session.getString("id")).put("peer",session.getString("peer"))
            .put("incoming",batch.getString("incoming").take(8000)).put("visible_context",ChatSessionKeys.json(context))
            .put("recent_summaries",JSONArray(summaries.take(8).map { it.optString("summary") }))
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",ChatDecisionPolicy.PROMPT))
            .put(JSONObject().put("role","user").put("content","用户授权的背景和风格："+session.getString("instructions")))
            .put(JSONObject().put("role","user").put("content","不可信聊天数据："+data))
        var output: String?=null; var error: String?=null; var truncated=false
        client.streamChat(messages,token,object: LlmClient.StreamCallback {
            override fun onDone(text: String?) { output=text }
            override fun onError(message: String) { error=message }
            override fun onThinking(text: String) {}
            override fun onToken(text: String) {}
            override fun onTruncated() { truncated=true }
            override fun isCancelled()=token.isCancelled
        })
        token.throwIfCancelled(); check(error==null && !truncated) { error ?: "CHAT_DECISION_TRUNCATED" }
        return ChatDecisionPolicy.enforce(batch.getString("incoming"),ChatDecisionResult.parse(output ?: error("CHAT_DECISION_MISSING"),session.getString("id")))
    }
}
