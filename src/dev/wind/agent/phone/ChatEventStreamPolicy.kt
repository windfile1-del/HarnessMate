package dev.wind.agent.phone

import java.io.IOException
import java.nio.ByteBuffer
import org.json.JSONObject

/** Metadata traffic has its own deadline and can degrade without revoking a healthy display lease. */
internal object ChatEventStreamPolicy {
    const val LEGACY_WAIT_MS=10000
    const val READ_TIMEOUT_MS=15000
    const val POLL_INTERVAL_MS=10000L
    fun readTimeout(waitMs: Int): Int {
        require(waitMs in 1..10000) { "CHAT_EVENT_WAIT_INVALID" }
        return maxOf(READ_TIMEOUT_MS,waitMs+5000)
    }
    fun delay(attempt: Int): Long=when(attempt) { 1->1000L; 2->3000L; 3->10000L; else->30000L }
    fun state(attempt: Int)=if(attempt<=3) "RECONNECTING" else "POLLING"
    fun fatal(code: String, leaseValid: Boolean)=!leaseValid || code in setOf(
        "CHAT_EVENT_RESPONSE_INVALID","PHONE_BRIDGE_AUTH_FAILED","PHONE_BRIDGE_VERSION_MISMATCH",
        "PHONE_BRIDGE_FRAME_LIMIT","VIRTUAL_DISPLAY_SESSION_CHANGED")
    /** A single total deadline covers both length prefix and body, including partial shell-v2 frames. */
    fun readResponse(read: (Int,Int)->ByteArray, timeoutMs: Int, request: String, session: String, display: Int,
        now: ()->Long = { System.nanoTime()/1000000 }): JSONObject {
        require(timeoutMs in 1..120000)
        val deadline=now()+timeoutMs
        fun remaining(): Int=(deadline-now()).also { if(it<=0) throw IOException("ADB_TIMEOUT") }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val length=ByteBuffer.wrap(read(4,remaining())).int
        require(length in 1..PhoneBridgeProtocol.JSON_LIMIT) { "PHONE_BRIDGE_FRAME_LIMIT" }
        val o=JSONObject(String(read(length,remaining()),Charsets.UTF_8))
        check(o.optInt("version")==PhoneBridgeProtocol.VERSION && o.optString("request_id")==request &&
            o.optString("status")=="COMPLETED" && o.optLong("event_epoch",-1)>=0) { "CHAT_EVENT_RESPONSE_INVALID" }
        VirtualDisplayScope.session(session,o.optString("display_session_id"),o.optInt("display_id",-1))
        check(display>0 && o.optInt("display_id")==display) { "VIRTUAL_DISPLAY_SESSION_CHANGED" }
        return o
    }
}
