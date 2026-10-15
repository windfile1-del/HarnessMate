package dev.wind.agent.shell.backends

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.*
import dev.wind.agent.shell.mirror.TermuxMirrorClient
import dev.wind.agent.shell.mirror.WorkspaceIdentity
import dev.wind.agent.shell.termux.TermuxPreflight
import org.json.JSONArray
import org.json.JSONObject

/** Runs argv in a prepared persistent mirror. No project GET/PUT or local staging. */
class TermuxBackend(private val context: Context) : ShellBackend {
    override val id = "termux"
    override val displayName = "Termux Persistent Coding Mirror"
    override fun isAvailable(): Boolean = TermuxPreflight.probe(context).executionReady

    override fun execute(request: ShellRequest, target: ExecutionTarget, token: CancellationToken): ShellResult {
        ShellPolicy.validateRequest(request)
        val result = TermuxMirrorClient(context, target.workspaceId).call("exec", JSONObject()
            .put("executable", request.executable).put("args", JSONArray().apply { request.args.forEach { put(it) } })
            .put("cwd", target.cwdRelative)
            .put("timeoutMs", request.timeoutMs)
            // Keep the broker's network decision visible to the backend. Termux
            // currently cannot enforce per-domain isolation, so this is explicit
            // POLICY_ONLY metadata rather than an unsafe claim of HARD isolation.
            .put("network", request.network)
            .put("networkDomains", JSONArray().apply { request.networkDomains.sorted().forEach { put(it) } })
            .put("networkIsolation", request.networkIsolation), token = token, timeoutMs = request.timeoutMs)
        return ShellResult(result.getInt("exitCode"), result.optString("stdout"), result.optString("stderr"),
            result.optLong("elapsedMs"), result.optBoolean("timedOut"), result.optBoolean("cancelled"))
    }

    override fun detectCapabilities(): Map<String, String> {
        val status = TermuxPreflight.probe(context)
        val caps = linkedMapOf("backend" to displayName, "preflight" to status.state.name,
            "termux_version" to status.termuxVersion.ifEmpty { "unknown" },
            "run_command" to if (status.runCommandPermission) "ready" else "permission missing",
            "callback" to if (status.callbackWorking) "ready" else "failed",
            "bridge" to "persistent mirror RPC (verified by execution tools; requires python3)",
            "network_isolation" to "POLICY_ONLY (Termux backend; not OS-level HARD isolation)",
            "cancellation" to "trusted helper terminates command process group")
        for ((name, tool) in status.toolDetails) caps[name] =
            if (!tool.available) "unavailable" else tool.version.ifBlank { tool.path }
        if (status.diagnostic.isNotBlank()) caps["diagnostic"] = status.diagnostic
        return caps
    }
}
