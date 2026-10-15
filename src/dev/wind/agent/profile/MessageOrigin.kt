package dev.wind.agent.profile

/** Set by trusted input paths; model arguments cannot promote an external message to user intent. */
enum class MessageOrigin {
    USER_DIRECT, USER_QUOTED, PROGRAMMATIC, THIRD_PARTY_CHAT, TOOL,
    MEMORY_CONTEXT, SKILL_CONTEXT, ASSISTANT, LEGACY_UNKNOWN;
    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: LEGACY_UNKNOWN
    }
}
