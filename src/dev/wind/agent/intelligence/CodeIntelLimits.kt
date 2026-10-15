package dev.wind.agent.intelligence

object CodeIntelLimits {
    const val MAX_TOOL_RESULT_BYTES: Int = 64 * 1024
    const val MAX_DEFINITIONS: Int = 8
    const val MAX_REFERENCES: Int = 50
    const val MAX_SYMBOL_RESULTS: Int = 50
    const val MAX_SEMANTIC_RESULTS: Int = 20

    const val MAX_GRAPH_NODES: Int = 100
    const val MAX_GRAPH_EDGES: Int = 200
    const val MAX_GRAPH_DEPTH: Int = 3

    const val MAX_SNIPPET_BYTES: Int = 1024
    const val MAX_SNIPPET_LINES: Int = 12

    const val MAX_LSP_FRAME_BYTES: Int = 1024 * 1024
    const val MAX_LSP_STDERR_BYTES: Int = 16 * 1024

    const val MAX_INDEX_SOURCE_FILE: Long = 1024 * 1024L
    const val MAX_SYMBOLS_PER_FILE: Int = 5000
    const val MAX_REFERENCES_PER_FILE: Int = 20000

    const val LSP_DEFINITION_TIMEOUT_MS: Long = 5000L
    const val LSP_REFERENCES_TIMEOUT_MS: Long = 10000L
    const val LSP_DIAGNOSTICS_TIMEOUT_MS: Long = 15000L
    const val LSP_START_TIMEOUT_MS: Long = 10000L
    const val LSP_IDLE_TIMEOUT_MS: Long = 120000L
    // The runtime owns four command slots; leave one available for build/test.
    // This bounds reusable servers, not the languages a task may query.
    const val MAX_CACHED_LSP_SESSIONS: Int = 3
    const val LSP_MAX_RESTARTS_PER_WINDOW: Int = 2
    const val LSP_RESTART_WINDOW_MS: Long = 60000L
}
