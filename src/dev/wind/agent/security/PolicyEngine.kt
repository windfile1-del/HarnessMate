package dev.wind.agent.security

import dev.wind.agent.ToolRegistry
import org.json.JSONObject

object PolicyEngine {

    private val sessionApprovedRisks = mutableSetOf<ToolRisk>()

    fun clearSessionApprovals() {
        sessionApprovedRisks.clear()
    }

    fun classifyRisk(toolName: String, args: JSONObject? = null): ToolRisk {
        val spec = ToolRegistry.spec(toolName)
        if (spec == null || !spec.enabled) {
            return ToolRisk.DENY
        }
        if (toolName == "shell_exec" && args?.optBoolean("network", false) == true) {
            return ToolRisk.NETWORK_EXECUTE
        }
        return spec.risk
    }

    fun evaluatePolicy(toolName: String, args: JSONObject?): PolicyAction {
        val risk = classifyRisk(toolName, args)
        if (risk == ToolRisk.DENY) {
            return PolicyAction.DENY
        }
        if (risk == ToolRisk.READ || risk == ToolRisk.INTERNAL) {
            return PolicyAction.ALLOW
        }
        if (risk == ToolRisk.DELETE) {
            return PolicyAction.ASK
        }
        if (sessionApprovedRisks.contains(risk)) {
            return PolicyAction.ALLOW
        }
        return PolicyAction.ASK
    }

    fun recordDecision(risk: ToolRisk, decision: ToolApprovalDecision) {
        if (decision.approved && decision.scope == ApprovalScope.SESSION && risk != ToolRisk.DELETE && risk != ToolRisk.DENY) {
            sessionApprovedRisks.add(risk)
        }
    }

    fun createRequest(toolName: String, args: JSONObject?): ToolApprovalRequest {
        val risk = classifyRisk(toolName, args)
        val path = args?.optString("path", args.optString("file", ""))
        val summary = when (toolName) {
            "edit_file" -> "Edit file: $path"
            "write_file" -> "Write file: $path"
            "delete_path" -> "Delete file or directory: $path"
            "make_dir" -> "Create directory: $path"
            "move_path" -> "Move path: $path"
            "web_fetch" -> "Fetch web page: " + args?.optString("url", "")
            "download_file" -> "Download file from: " + args?.optString("url", "")
            "shell_exec" -> "Execute command: " + args?.optString("executable", "") + " " + (args?.optJSONArray("args")?.let { arr ->
                (0 until arr.length()).joinToString(" ") { arr.optString(it) }
            } ?: "")
            else -> "Execute tool $toolName"
        }
        return ToolApprovalRequest(toolName, args, summary, risk, path)
    }
}
