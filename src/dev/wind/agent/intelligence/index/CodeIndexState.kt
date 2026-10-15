package dev.wind.agent.intelligence.index

enum class CodeIndexPhase {
    ABSENT,
    BUILDING,
    READY,
    DIRTY,
    REFRESHING,
    DEGRADED,
    REBUILD_REQUIRED
}

data class CodeIndexStatus(
    val phase: CodeIndexPhase,
    val boundDigest: String? = null,
    val boundEpoch: Int = 0,
    val quality: String = "LEXICAL",
    val fileCount: Int = 0,
    val symbolCount: Int = 0,
    val message: String? = null
)
