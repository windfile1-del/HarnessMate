package dev.wind.agent.security

import org.json.JSONObject

enum class PolicyAction {
    ALLOW,
    ASK,
    DENY
}

data class ToolApprovalRequest(
    val toolName: String,
    val arguments: JSONObject?,
    val summary: String,
    val risk: ToolRisk,
    val affectedPath: String? = null
)

data class ToolApprovalDecision(
    val approved: Boolean,
    val scope: ApprovalScope = ApprovalScope.ONCE,
    val feedback: String? = null
)
