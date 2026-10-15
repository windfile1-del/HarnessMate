package dev.wind.agent.packages

import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.ShellRequest
import dev.wind.agent.shell.ShellResult
import org.json.JSONArray
import org.json.JSONObject

/** Commands run once in the existing mirror transaction path; never retries a partial install. */
object ProjectPackageRunner {
    fun run(plan: ProjectPackagePlan, token: CancellationToken,
            execute: (ShellRequest) -> ShellResult): JSONObject {
        val steps = JSONArray()
        var state = "SUCCEEDED"
        var executionId = ""
        for ((index, command) in plan.commands.withIndex()) {
            token.throwIfCancelled()
            val result = execute(command)
            val id = Regex("Execution ID: ([A-Za-z0-9_-]+)").find(result.diffSummary)?.groupValues?.get(1)
            if (id != null) executionId = id
            steps.put(JSONObject().put("step", index + 1).put("executable", command.executable)
                .put("args", JSONArray(command.args)).put("exit_code", result.exitCode ?: JSONObject.NULL)
                .put("stdout", result.stdout).put("stderr", result.stderr).put("elapsed_ms", result.elapsedMs)
                .put("timed_out", result.timedOut).put("cancelled", result.cancelled)
                .put("error", result.error ?: JSONObject.NULL).put("diff", result.diffSummary))
            state = when {
                result.cancelled -> "CANCELLED"
                result.timedOut -> "TIMED_OUT"
                result.error != null || result.exitCode != 0 -> "FAILED"
                else -> "SUCCEEDED"
            }
            if (state != "SUCCEEDED") break
        }
        return JSONObject().put("state", state).put("manager", plan.manager).put("action", plan.action)
            .put("project_path", plan.projectPath).put("python_environment", if (plan.manager == "pip") ProjectPackageCommands.VENV else JSONObject.NULL)
            .put("network_domains", JSONArray(plan.networkDomains.toList())).put("network_isolation", "POLICY_ONLY")
            .put("steps", steps).put("execution_id", executionId)
            .put("canonical_workspace_modified", false).put("automatic_retry", false)
            .put("next_step", if (state == "SUCCEEDED" && plan.mutating)
                "Review the mirror diff and use shell_apply_changes to save dependency declarations/lockfiles. Installed caches and the managed Python environment stay in the mirror."
                else if (state != "SUCCEEDED") "Inspect the failed step and current mirror diff before any retry; partial package-manager changes may remain."
                else "Use the reported package-manager result; no build or test has been run.")
    }
}
