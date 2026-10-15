package dev.wind.agent.phone

/** Route explicit guardian leases before ordinary-task tombstones can rewrite their identity. */
internal object ChatLeaseRouting {
    fun owner(caller: String,requested: String,lease: String?,guardian: String,authorizedChat: String,callerChat: String?,state: String): String {
        if(lease.isNullOrBlank() || requested!=lease) return caller
        check(authorizedChat.isNotBlank() && authorizedChat==callerChat) { "CHAT_AI_SESSION_MISMATCH" }
        check(state in setOf("SETUP","PAUSED")) { "CHAT_GUARDIAN_CONTROL_BUSY: pause before navigation" }
        return guardian
    }
}
