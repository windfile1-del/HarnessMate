package dev.wind.agent.phone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.SmsMessage
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Tracks transport acknowledgements in memory; never stores recipients or message bodies. */
internal object PhoneSmsOperations {
    private data class Operation(val owner: String, val parts: Int, val at: Long,
        val sent: MutableSet<Int> = mutableSetOf(), val delivered: MutableSet<Int> = mutableSetOf(),
        var failure: String? = null, var deliveryReportUnknown: Boolean = false)
    private val operations = LinkedHashMap<String, Operation>()
    private val codes = AtomicInteger(10000)
    fun nextRequestCode(): Int = codes.incrementAndGet()
    @Synchronized fun create(id: String, owner: String, parts: Int) {
        while (operations.size >= 32) operations.remove(operations.keys.first())
        operations[id] = Operation(owner, parts, SystemClock.elapsedRealtime())
    }
    @Synchronized fun fail(id: String, reason: String) { operations[id]?.failure = reason }
    @Synchronized fun deliveryUnknown(id: String) { operations[id]?.deliveryReportUnknown = true }
    @Synchronized fun record(id: String, index: Int, kind: String, code: Int) {
        val op = operations[id] ?: return
        if (index !in 0 until op.parts) return
        if (code != Activity.RESULT_OK) op.failure = "$kind callback code $code for part $index"
        else if (kind == "SENT") op.sent.add(index)
        else if (kind == "DELIVERED") op.delivered.add(index)
    }
    @Synchronized fun status(id: String, owner: String): JSONObject {
        val op = operations[id]?.takeIf { it.owner == owner } ?: error("PHONE_OPERATION_UNKNOWN: status is only retained for this task in this app process")
        val state = when {
            op.failure != null -> "FAILED_OR_PARTIAL"
            op.delivered.size == op.parts -> "DELIVERED"
            op.sent.size == op.parts -> "SENT"
            SystemClock.elapsedRealtime() - op.at > 600000 -> "UNKNOWN"
            else -> "SUBMITTED"
        }
        return JSONObject().put("operation_id", id).put("status", state).put("parts", op.parts)
            .put("sent_parts", op.sent.size).put("delivered_parts", op.delivered.size)
            .put("delivery_report_unknown", op.deliveryReportUnknown)
            .put("error", op.failure ?: "").put("note", "Missing callbacks are unknown; never retry sending without user confirmation")
    }
}

class PhoneSmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: return
        if (intent.action != "dev.wind.agent.SMS_$kind" || kind !in setOf("SENT", "DELIVERED")) return
        val id = intent.getStringExtra("operation_id") ?: return
        val part = intent.getIntExtra("part", -1)
        if (kind == "DELIVERED" && resultCode == Activity.RESULT_OK) {
            // A delivery-report broadcast itself does not prove successful delivery.
            val status = try {
                intent.getByteArrayExtra("pdu")?.let {
                    SmsMessage.createFromPdu(it, intent.getStringExtra("format") ?: "3gpp")?.status
                }
            } catch (_: Exception) { null }
            if (status == 0) PhoneSmsOperations.record(id, part, kind, Activity.RESULT_OK)
            else if (status != null && status in 64..127) PhoneSmsOperations.fail(id, "Delivery report status $status for part $part")
            else PhoneSmsOperations.deliveryUnknown(id)
        } else PhoneSmsOperations.record(id, part, kind, resultCode)
    }
}
