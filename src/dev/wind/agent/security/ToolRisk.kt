package dev.wind.agent.security

enum class ToolRisk(val label: String, val weight: Int) {
    INTERNAL("Internal Workflow", 0),
    READ("Read-Only", 1),
    NETWORK("Network Access", 2),
    WRITE("File Mutation", 3),
    EXECUTE("Command Execution", 4),
    NETWORK_EXECUTE("Network Command", 5),
    DELETE("Destructive", 6),
    DENY("Forbidden", 99)
}
