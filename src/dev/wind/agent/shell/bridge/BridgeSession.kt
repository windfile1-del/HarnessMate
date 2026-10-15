package dev.wind.agent.shell.bridge

enum class BridgeState {
    CREATED,
    PULLED,
    PUSHED,
    CLOSED
}

data class BridgeSession(
    val executionId: String,
    val token: String,
    val port: Int,
    val expiresAt: Long = System.currentTimeMillis() + BridgeLimits.TRANSFER_TIMEOUT_MS
)
