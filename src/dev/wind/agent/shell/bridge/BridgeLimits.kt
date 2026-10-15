package dev.wind.agent.shell.bridge

object BridgeLimits {
    const val MAX_FILES = 20_000
    const val MAX_PROJECT_BYTES = 256L * 1024 * 1024 // 256 MiB
    const val MAX_SINGLE_FILE_BYTES = 32L * 1024 * 1024 // 32 MiB
    const val CONNECT_TIMEOUT_MS = 10_000
    const val TRANSFER_TIMEOUT_MS = 120_000
}
