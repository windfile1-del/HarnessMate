package dev.wind.agent.profile

enum class AgentProfile(val key: String, val label: String) {
    CODE("code", "Code"), MATE("mate", "Mate");
    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.key == value } ?: CODE
    }
}

enum class MemorySessionPolicy { NORMAL, READ_ONLY, OFF }
enum class RetentionPolicy { PERSISTENT, TEMPORARY }

/** Immutable per-run positioning. None of these fields represent a capability grant. */
data class RunProfileSnapshot(
    val profile: AgentProfile,
    val executionMode: String,
    val memoryPolicy: MemorySessionPolicy = MemorySessionPolicy.NORMAL,
    val projectMemoryKey: String? = null,
    val promptVersion: String = "mate-p3-v1",
    val mateTaskId: String? = null
) {
    companion object {
        fun capture(chat: dev.wind.agent.Chat, prefs: dev.wind.agent.Prefs) = RunProfileSnapshot(
            chat.agentProfile, prefs.mode(), chat.memoryPolicy, chat.projectMemoryKey.takeIf { it!=null && it==chat.projectBindingId },mateTaskId=chat.mateTaskId.takeIf {chat.agentProfile==AgentProfile.MATE}
        )
    }
}
