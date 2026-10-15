package dev.wind.agent.phone

import org.json.JSONObject

internal object ChatApprovalCenter {
    const val TTL=15*60*1000L
    fun valid(approval: JSONObject, session: JSONObject, now: Long): Boolean =
        approval.optString("state")=="PENDING" && session.optString("state")=="RUNNING" &&
        approval.optString("session_id")==session.optString("id") &&
        approval.optLong("message_version",-1)==session.optLong("message_version",-2) &&
        approval.optString("package")==session.optString("package") && approval.optString("peer")==session.optString("peer") &&
        now>=approval.optLong("created_at",Long.MAX_VALUE) && now<approval.optLong("expires_at",0)
    fun draft(session: JSONObject, batch: JSONObject, result: ChatDecisionResult, now: Long)=JSONObject()
        .put("id",java.util.UUID.randomUUID().toString()).put("session_id",session.getString("id")).put("state","PENDING")
        .put("package",session.getString("package")).put("peer",session.getString("peer")).put("message_version",batch.getLong("message_version"))
        .put("incoming",batch.getString("incoming")).put("reply",result.reply ?: JSONObject.NULL)
        .put("reason",result.reason).put("summary",result.summary).put("created_at",now).put("expires_at",now+TTL)
}
