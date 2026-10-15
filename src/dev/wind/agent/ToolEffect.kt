package dev.wind.agent

/**
 * Real authority effect of a tool. This is intentionally independent of
 * [dev.wind.agent.security.ToolRisk], which remains a UI danger label.
 */
enum class ToolEffect {
    PROJECT_READ,
    MIRROR_READ,
    MIRROR_MUTATE,
    EXECUTE,
    CANONICAL_WRITE,
    CANONICAL_DELETE,
    NETWORK,
    CAPABILITY_EXPANSION,
    PHONE_READ,
    PHONE_CONTROL,
    INTERNAL
}
