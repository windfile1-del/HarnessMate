package dev.wind.agent.phone

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Metadata only; the first shutdown cause survives subsequent cleanup and failed requests. */
internal class PhoneBridgeHealth {
    private data class Failure(val stage: String,val code: String,val method: String,val at: Long)
    private val failure=AtomicReference<Failure?>()
    @Volatile var lastMethod="START"; private set
    @Volatile var lastHeartbeatAt=0L; private set
    @Volatile var pid=-1
    fun requested(method: String) { lastMethod=method.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "UNKNOWN" }
    fun renewed() { lastHeartbeatAt=System.currentTimeMillis() }
    fun failed(stage: String,code: String) {
        failure.compareAndSet(null,Failure(stage,code.substringBefore(':').takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{2,100}")) } ?: "PHONE_BRIDGE_TRANSPORT_FAILED",lastMethod,System.currentTimeMillis()))
    }
    fun snapshot()=JSONObject().put("pid",if(pid>0) pid else JSONObject.NULL).put("last_method",lastMethod)
        .put("last_heartbeat_at",if(lastHeartbeatAt>0) lastHeartbeatAt else JSONObject.NULL)
        .put("close_reason",failure.get()?.let { JSONObject().put("stage",it.stage).put("code",it.code).put("method",it.method).put("at",it.at) } ?: JSONObject.NULL)
    companion object {
        fun authorityFailure(cancelled: Boolean,taskEnded: Boolean,expectedEpoch: Long,currentEpoch: Long,
            expectedRevision: Long,currentRevision: Long,authorized: Boolean): String? = when {
            cancelled -> "PHONE_BRIDGE_CANCELLED"
            taskEnded -> "PHONE_BRIDGE_TASK_ENDED"
            currentRevision!=expectedRevision -> "PHONE_BRIDGE_PERMISSION_REVISION_CHANGED"
            currentEpoch!=expectedEpoch -> "PHONE_BRIDGE_ADB_EPOCH_CHANGED"
            !authorized -> "PHONE_BRIDGE_PERMISSION_REVOKED"
            else -> null
        }
    }
}
