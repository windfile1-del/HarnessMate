package dev.wind.agent.phone

/** Durable at-most-once ledger. UNKNOWN and observed sends can never return to a sendable state. */
internal object ChatDeliveryState {
    private val transitions=mapOf(
        "PENDING" to setOf("MODELING","SUPERSEDED"),
        "MODELING" to setOf("PENDING","RESERVED","WAITING","IGNORED","AWAITING_USER","SUPERSEDED"),
        "WAITING" to setOf("MODELING","SUPERSEDED"),
        "AWAITING_USER" to setOf("RESERVED"),
        "RESERVED" to setOf("FILLED","SEND_UNKNOWN"),
        "FILLED" to setOf("DISPATCHING","SEND_UNKNOWN"),
        "DISPATCHING" to setOf("DISPATCHED","SEND_UNKNOWN"),
        "DISPATCHED" to setOf("SELF_MESSAGE_OBSERVED","SEND_UNKNOWN")
    )
    fun allowed(before: String?,after: String): Boolean = if(before==null) after=="PENDING" else before==after || after in transitions[before].orEmpty()
    fun check(before: String?,after: String) { check(allowed(before,after)) { "CHAT_DELIVERY_TRANSITION_REFUSED: $before -> $after" } }
}
