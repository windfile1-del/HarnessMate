package dev.wind.agent.shell.termux

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import dev.wind.agent.shell.bridge.VegaBridgeServer
import dev.wind.agent.shell.mirror.TermuxMirrorClient
import dev.wind.agent.shell.mirror.WorkspaceIdentity
import java.io.File
import org.json.JSONObject

enum class TermuxPreflightState {
    NOT_INSTALLED,
    PERMISSION_MISSING,
    RUN_COMMAND_FAILED,
    CALLBACK_FAILED,
    CORE_TOOLS_MISSING,
    BRIDGE_FAILED,
    DEGRADED,
    READY
}

data class ToolProbe(
    val name: String,
    val available: Boolean,
    val path: String = "",
    val version: String = ""
)

data class TermuxStatus(
    val installed: Boolean,
    val runCommandPermission: Boolean,
    val callbackWorking: Boolean,
    val bridgeWorking: Boolean,
    val bash: Boolean,
    val tar: Boolean,
    val curl: Boolean,
    val setsid: Boolean,
    val tools: Map<String, String> = emptyMap(),
    val toolDetails: Map<String, ToolProbe> = emptyMap(),
    val state: TermuxPreflightState,
    val termuxVersion: String = "",
    val checkedAt: Long = System.currentTimeMillis(),
    val probeDurationMs: Long = 0L,
    val diagnostic: String = ""
) {
    val executionReady: Boolean
        get() = installed && runCommandPermission && callbackWorking && bridgeWorking && bash && tar && curl

    val cancellationReady: Boolean
        get() = executionReady && setsid
}

object TermuxPreflight {

    private const val CACHE_MS = 60_000L

    @Volatile
    private var cached: TermuxStatus? = null

    fun probe(context: Context, force: Boolean = false, runner: TermuxProbeRunner? = null): TermuxStatus {
        val old = cached
        if (!force && runner == null && old != null && (System.currentTimeMillis() - old.checkedAt < CACHE_MS)) {
            return old
        }
        val res = runProbe(context.applicationContext, runner ?: AndroidTermuxProbeRunner(context.applicationContext))
        if (runner == null) {
            cached = res
        }
        return res
    }

    fun invalidate() {
        cached = null
    }

    private fun runProbe(context: Context, runner: TermuxProbeRunner): TermuxStatus {
        val start = SystemClock.uptimeMillis()
        val termuxVersion = try {
            context.packageManager.getPackageInfo(TermuxContract.TERMUX_PACKAGE, 0).versionName ?: "unknown"
        } catch (_: Throwable) { "unknown" }

        // 1. Static installation check
        val installed = try {
            context.packageManager.getLaunchIntentForPackage(TermuxContract.TERMUX_PACKAGE) != null
        } catch (_: Throwable) { false }

        if (!installed) {
            return TermuxStatus(
                installed = false,
                runCommandPermission = false,
                callbackWorking = false,
                bridgeWorking = false,
                bash = false,
                tar = false,
                curl = false,
                setsid = false,
                state = TermuxPreflightState.NOT_INSTALLED,
                termuxVersion = termuxVersion,
                diagnostic = "Termux app (com.termux) is not installed on this device.",
                probeDurationMs = SystemClock.uptimeMillis() - start
            )
        }

        // 2. Permission check
        val permission = try {
            if (Build.VERSION.SDK_INT >= 23) {
                context.checkSelfPermission(TermuxContract.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
            } else true
        } catch (_: Throwable) { false }

        if (!permission) {
            return TermuxStatus(
                installed = true,
                runCommandPermission = false,
                callbackWorking = false,
                bridgeWorking = false,
                bash = false,
                tar = false,
                curl = false,
                setsid = false,
                state = TermuxPreflightState.PERMISSION_MISSING,
                termuxVersion = termuxVersion,
                diagnostic = "com.termux.permission.RUN_COMMAND permission is not granted to Vega Safe.",
                probeDurationMs = SystemClock.uptimeMillis() - start
            )
        }

        // 3. Stage 1: Callback test
        val callbackReq = TermuxRunCommandClient.Request(
            commandPath = TermuxContract.TERMUX_BIN_PREFIX + "bash",
            args = listOf("-s"),
            stdin = "export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"\nprintf 'VEGA_PREFLIGHT_V1\n'\nprintf 'CALLBACK_OK\n'\n",
            timeoutMs = 4000L
        )
        val callbackOutcome = runner.run(callbackReq)
        val callbackSuccess = callbackOutcome.callbackReceived &&
            callbackOutcome.result != null &&
            !callbackOutcome.result.hasInternalError &&
            callbackOutcome.result.exitCode == 0 &&
            callbackOutcome.result.stdout.contains("CALLBACK_OK")

        if (!callbackSuccess) {
            val diag = if (callbackOutcome.result != null && callbackOutcome.result.hasInternalError) {
                "Termux internal error: ${callbackOutcome.result.internalErrorMessage}"
            } else if (callbackOutcome.timedOut) {
                "Termux RUN_COMMAND self-test timed out. Check:\n1. Termux >= 0.109\n2. allow-external-apps=true in ~/.termux/termux.properties\n3. Termux background execution permitted"
            } else {
                "Termux RUN_COMMAND dispatch failed: ${callbackOutcome.dispatchError ?: "no callback received"}"
            }
            val state = if (callbackOutcome.result != null && callbackOutcome.result.hasInternalError) {
                TermuxPreflightState.RUN_COMMAND_FAILED
            } else {
                TermuxPreflightState.CALLBACK_FAILED
            }
            return TermuxStatus(
                installed = true,
                runCommandPermission = true,
                callbackWorking = false,
                bridgeWorking = false,
                bash = false,
                tar = false,
                curl = false,
                setsid = false,
                state = state,
                termuxVersion = termuxVersion,
                diagnostic = diag,
                probeDurationMs = SystemClock.uptimeMillis() - start
            )
        }

        // 4. Stage 2: Tool probe
        val toolProbeScript = """
set +e
export PATH="/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:${'$'}PATH"
printf 'VEGA_TOOLS_V1\n'
probe_tool() {
    NAME="${'$'}1"
    PATH_VALUE="$(command -v "${'$'}NAME" 2>/dev/null || true)"
    if [ -z "${'$'}PATH_VALUE" ]; then
        printf 'TOOL\t%s\t0\t\t\n' "${'$'}NAME"
        return
    fi
    case "${'$'}NAME" in
        java|javac)
            VERSION="$("${'$'}PATH_VALUE" -version 2>&1 | head -n 1)"
            ;;
        *)
            VERSION="$("${'$'}PATH_VALUE" --version 2>&1 | head -n 1)"
            ;;
    esac
    VERSION="$(printf '%s' "${'$'}VERSION" | tr '\t\r\n' '   ')"
    printf 'TOOL\t%s\t1\t%s\t%s\n' "${'$'}NAME" "${'$'}PATH_VALUE" "${'$'}VERSION"
}
for TOOL in bash tar curl setsid git python python3 java javac node npm gradle clang cargo; do
    probe_tool "${'$'}TOOL"
done
printf 'VEGA_TOOLS_END\n'
""".trimIndent()

        val toolReq = TermuxRunCommandClient.Request(
            commandPath = TermuxContract.TERMUX_BIN_PREFIX + "bash",
            args = listOf("-s"),
            stdin = toolProbeScript,
            timeoutMs = 6000L
        )
        val toolOutcome = runner.run(toolReq)
        val toolProbes = if (toolOutcome.callbackReceived && toolOutcome.result != null && toolOutcome.result.exitCode == 0) {
            parseToolProbe(toolOutcome.result.stdout)
        } else emptyMap()

        val bash = toolProbes["bash"]?.available == true
        val tar = toolProbes["tar"]?.available == true
        val curl = toolProbes["curl"]?.available == true
        val setsid = toolProbes["setsid"]?.available == true

        val toolsMap = mutableMapOf<String, String>()
        for ((name, probe) in toolProbes) {
            if (probe.available) {
                toolsMap[name] = if (probe.version.isNotBlank()) probe.version else probe.path
            }
        }

        if (!bash || !tar || !curl) {
            val missing = mutableListOf<String>()
            if (!bash) missing.add("bash")
            if (!tar) missing.add("tar")
            if (!curl) missing.add("curl")
            return TermuxStatus(
                installed = true,
                runCommandPermission = true,
                callbackWorking = true,
                bridgeWorking = false,
                bash = bash,
                tar = tar,
                curl = curl,
                setsid = setsid,
                tools = toolsMap,
                toolDetails = toolProbes,
                state = TermuxPreflightState.CORE_TOOLS_MISSING,
                termuxVersion = termuxVersion,
                diagnostic = "Core tools missing in Termux environment: ${missing.joinToString(", ")}. Run 'pkg install ${missing.joinToString(" ")}' in Termux.",
                probeDurationMs = SystemClock.uptimeMillis() - start
            )
        }

        // 5. Stage 3: Bridge self-test
        var bridgeOk = false
        var bridgeDiag = ""
        if (runner is AndroidTermuxProbeRunner) {
            // The active P3 execution path is the persistent mirror protocol.
            // Keep the old tar bridge below only for deterministic pre-P3 test
            // runners and upgrade compatibility; production readiness must
            // exercise the same helper/sink server as shell_exec.
            try {
                val probeId = WorkspaceIdentity.fromUri("content://dev.wind.agent/preflight")
                val result = TermuxMirrorClient(context, probeId).call(
                    "probe", JSONObject(), timeoutMs = 15_000L
                )
                bridgeOk = result.optInt("protocolVersion", 0) == 2
                if (!bridgeOk) bridgeDiag = "Persistent mirror probe returned an unsupported protocol."
            } catch (t: Throwable) {
                bridgeDiag = "Persistent mirror probe failed: " + (t.message ?: t.javaClass.simpleName)
            }
        } else {
            val probeId = "probe-" + java.util.UUID.randomUUID().toString().substring(0, 8)
            val probeBaseDir = File(context.filesDir, "preflight/$probeId")
            val sourceDir = File(probeBaseDir, "source").apply { mkdirs() }
            val returnedDir = File(probeBaseDir, "returned").apply { mkdirs() }
            File(sourceDir, "probe.txt").writeText("vega-bridge-preflight")
            try {
            val server = VegaBridgeServer("preflight-$probeId", sourceDir, returnedDir)
            server.use { srv ->
                val bridgeScript = """
set -eu
export PATH="/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:${'$'}PATH"
PORT="${'$'}1"
TOKEN="${'$'}2"
PROBE_ID="${'$'}3"
ROOT="${'$'}HOME/.vega-safe/preflight/${'$'}PROBE_ID"
PROJECT="${'$'}ROOT/project"
META="${'$'}ROOT/meta"
cleanup() {
    rm -rf "${'$'}ROOT"
}
trap cleanup EXIT
mkdir -p "${'$'}PROJECT" "${'$'}META"
curl -fsS --connect-timeout 5 --max-time 15 "http://127.0.0.1:${'$'}PORT/v1/project/${'$'}TOKEN" | tar -xf - -C "${'$'}PROJECT"
VALUE="$(cat "${'$'}PROJECT/probe.txt")"
if [ "${'$'}VALUE" != "vega-bridge-preflight" ]; then
    printf 'BRIDGE_PULL_INVALID\n' >&2
    exit 30
fi
printf 'termux-return-ok' > "${'$'}PROJECT/termux-return.txt"
tar -C "${'$'}PROJECT" -cf "${'$'}META/project.tar" .
curl -fsS -H "Expect:" --connect-timeout 5 --max-time 15 -T "${'$'}META/project.tar" "http://127.0.0.1:${'$'}PORT/v1/project/${'$'}TOKEN" >/dev/null
printf 'VEGA_BRIDGE_OK\n'
""".trimIndent()

                val bridgeReq = TermuxRunCommandClient.Request(
                    commandPath = TermuxContract.TERMUX_BIN_PREFIX + "bash",
                    args = listOf("-s", "--", srv.port.toString(), srv.token, probeId),
                    stdin = bridgeScript,
                    timeoutMs = 10000L
                )
                val bridgeOutcome = runner.run(bridgeReq)
                val returnedFile = File(returnedDir, "termux-return.txt")
                bridgeOk = bridgeOutcome.callbackReceived &&
                    bridgeOutcome.result?.exitCode == 0 &&
                    bridgeOutcome.result.hasInternalError.not() &&
                    bridgeOutcome.result.stdout.contains("VEGA_BRIDGE_OK") &&
                    srv.pushError == null &&
                    returnedFile.isFile &&
                    returnedFile.readText() == "termux-return-ok"

                if (!bridgeOk) {
                    bridgeDiag = "Bridge test failed: " + (srv.pushError?.message ?: bridgeOutcome.result?.stderr?.ifEmpty { "Transfer incomplete" } ?: "Connection failed")
                }
            }
            } catch (t: Throwable) {
                bridgeOk = false
                bridgeDiag = "Bridge initialization error: " + (t.message ?: t.javaClass.simpleName)
            } finally {
                probeBaseDir.deleteRecursively()
            }
        }

        if (!bridgeOk) {
            return TermuxStatus(
                installed = true,
                runCommandPermission = true,
                callbackWorking = true,
                bridgeWorking = false,
                bash = bash,
                tar = tar,
                curl = curl,
                setsid = setsid,
                tools = toolsMap,
                toolDetails = toolProbes,
                state = TermuxPreflightState.BRIDGE_FAILED,
                termuxVersion = termuxVersion,
                diagnostic = bridgeDiag,
                probeDurationMs = SystemClock.uptimeMillis() - start
            )
        }

        val state = if (setsid) TermuxPreflightState.READY else TermuxPreflightState.DEGRADED
        val diag = if (!setsid) "Commands can execute, but reliable process-group cancellation is unavailable. Install 'util-linux' in Termux to enable setsid." else ""

        return TermuxStatus(
            installed = true,
            runCommandPermission = true,
            callbackWorking = true,
            bridgeWorking = true,
            bash = bash,
            tar = tar,
            curl = curl,
            setsid = setsid,
            tools = toolsMap,
            toolDetails = toolProbes,
            state = state,
            termuxVersion = termuxVersion,
            diagnostic = diag,
            probeDurationMs = SystemClock.uptimeMillis() - start
        )
    }

    internal fun parseToolProbe(stdout: String): Map<String, ToolProbe> {
        val result = linkedMapOf<String, ToolProbe>()
        for (line in stdout.lineSequence()) {
            if (!line.startsWith("TOOL\t")) continue
            val parts = line.split('\t', limit = 5)
            if (parts.size < 3) continue
            val name = parts[1]
            val available = parts[2] == "1"
            val path = parts.getOrNull(3) ?: ""
            val version = parts.getOrNull(4) ?: ""
            result[name] = ToolProbe(name, available, path, version)
        }
        return result
    }
}
