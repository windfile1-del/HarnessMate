package dev.wind.agent.adb

import android.content.Context
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import dev.wind.agent.capability.*
import dev.wind.agent.permissions.*
import dev.wind.agent.workspace.WorkspaceTools
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Device commands have their own capability and effect boundary, independent of phone UI mode. */
class AdbTools(private val context: Context, private val taskId: String, private val workspace: WorkspaceTools) {
    fun run(name: String, args: JSONObject, token: CancellationToken): String {
        val store = AgentPermissions.get(context)
        val gate = AgentPermissionGate(context)
        val runner = AdbToolRunner(store, taskId,
            checkPlan = { plan ->
                plan.capabilities.forEach { check(PermissionNeed(it).satisfied(store, taskId)) { "AGENT_PERMISSION_REQUIRED: $it" } }
                plan.readPaths.forEach { check(gate.directory(it, false).satisfied(store, taskId)) { "AGENT_PERMISSION_REQUIRED: PATH_READ $it" } }
                plan.writePaths.forEach { check(gate.directory(it, true).satisfied(store, taskId)) { "AGENT_PERMISSION_REQUIRED: PATH_WRITE $it" } }
            },
            execute = { cmd, cancellation, timeout, max, input, beforeSend ->
                AdbManager.get(context).execute(cmd, taskId, cancellation, timeout, max, input, beforeSend = beforeSend).json()
            },
            approve = { tool, reviewed, cancellation ->
                val request = CapabilityRequest("adb-effect-" + UUID.randomUUID(), tool, reviewed,
                    TaskContext(taskId, "", 0), CapabilityRequirement.Internal(), "adb-effect",
                    "ADB effect for user 0: " + reviewed.optString("command") +
                        if (reviewed.has("apk_sha256")) "; APK ${reviewed.optString("path")}; package ${reviewed.optString("apk_package")}; version ${reviewed.optString("apk_version_name")} (${reviewed.optLong("apk_version_code")}); ${reviewed.optInt("apk_bytes")} bytes; SHA-256 ${reviewed.optString("apk_sha256")}" else "",
                    System.currentTimeMillis(), allowedScopes = setOf(GrantScope.ONCE))
                val decision = AgentBus.awaitCapability(request, cancellation)
                decision is CapabilityDecision.Allow && decision.scope == GrantScope.ONCE
            },
            inspectApk = { bytes, checkBoundary ->
                checkBoundary()
                val file = File.createTempFile("adb-review-", ".apk", context.cacheDir)
                try {
                    file.outputStream().use { it.write(bytes) }
                    checkBoundary()
                    val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                        ?: error("ADB_APK_METADATA_INVALID")
                    JSONObject().put("apk_package", info.packageName).put("apk_version_name", info.versionName)
                        .put("apk_version_code", if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
                } finally { check(file.delete() || !file.exists()) { "ADB_REVIEW_CLEANUP_FAILED" } }
            },
            readApk = { path, cancellation, checkBoundary ->
                checkBoundary()
                val source = workspace.router.resolve(path, false)
                source.fs.openInputStream(source.relativePath).use { readBoundedApk(it, cancellation, checkBoundary) }
            })
        return if (name == "adb_status") {
            AdbCommands.plan(name, JSONObject(args.toString()))
            token.throwIfCancelled()
            AdbManager.get(context).status(taskId).toString()
        } else runner.run(name, args, token).toString()
    }

    companion object {
        fun requiresApproval(plan: AdbCommandPlan, policy: ActionPolicy, install: Boolean = false): Boolean =
            install || plan.mutating && (policy == ActionPolicy.CONSERVATIVE || policy == ActionPolicy.BALANCED && plan.sensitive)

        /** Checks actual stream length, not untrusted metadata; never reads a whole unbounded file. */
        fun readBoundedApk(input: InputStream, token: CancellationToken, checkBoundary: () -> Unit = {}): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            while (true) {
                token.throwIfCancelled(); checkBoundary()
                val count = input.read(buffer, 0, minOf(buffer.size, AdbCommands.MAX_APK_BYTES - output.size() + 1))
                if (count < 0) break
                if (count == 0) continue
                require(output.size().toLong() + count <= AdbCommands.MAX_APK_BYTES) { "ADB_APK_SIZE_INVALID" }
                output.write(buffer, 0, count)
            }
            require(output.size() > 0) { "ADB_APK_EMPTY" }
            return output.toByteArray()
        }
    }
}

/** Injectable host-test boundary; each mutation is submitted at most once, even after transport loss. */
internal class AdbToolRunner(
    private val store: AgentPermissions,
    private val taskId: String,
    private val checkPlan: (AdbCommandPlan) -> Unit,
    private val execute: (String, CancellationToken, Int, Int, ByteArray?, () -> Unit) -> JSONObject,
    private val approve: (String, JSONObject, CancellationToken) -> Boolean,
    private val inspectApk: (ByteArray, () -> Unit) -> JSONObject,
    private val readApk: (String, CancellationToken, () -> Unit) -> ByteArray
) {
    fun run(name: String, arguments: JSONObject, token: CancellationToken): JSONObject {
        val args = JSONObject(arguments.toString())
        val plan = AdbCommands.plan(name, args)
        require(name != "adb_status") { "ADB_STATUS_USES_MANAGER" }
        token.throwIfCancelled(); checkPlan(plan)
        val revision = store.revision
        fun checkBoundary() {
            token.throwIfCancelled()
            check(!store.taskEnded(taskId)) { "ADB_TASK_ENDED" }
            check(store.revision == revision) { "AGENT_PERMISSION_REVOKED" }
            checkPlan(plan)
        }
        val install = name == "adb_package" && args.optString("action") == "install"
        val input = if (install) readApk(plan.readPaths.single(), token, ::checkBoundary).also {
            require(it.size in 1..AdbCommands.MAX_APK_BYTES) { "ADB_APK_SIZE_INVALID" }
        } else null
        val command = if (input != null) AdbCommands.installCommand(input.size) else plan.command
        val reviewed = JSONObject(args.toString()).put("command", command).put("user_id", 0)
        if (input != null) reviewed.put("apk_bytes", input.size).put("apk_sha256",
            MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it.toInt() and 255) })
        if (input != null) {
            val facts = inspectApk(input, ::checkBoundary)
            require(facts.has("apk_package") && facts.getString("apk_package").isNotBlank()) { "ADB_APK_METADATA_INVALID" }
            for (key in listOf("apk_package", "apk_version_name", "apk_version_code")) if (facts.has(key)) reviewed.put(key, facts.get(key))
        }
        checkBoundary()
        if (AdbTools.requiresApproval(plan, store.policy, install) && !approve(name, reviewed, token))
            return JSONObject().put("status", "REJECTED").put("submitted", false)
        checkBoundary() // Delayed approval, task end, or revocation cannot authorize a later effect.
        val timeout = AdbCommands.integer(args, "timeout_ms", 30000, 1, 120000)
        val max = AdbCommands.integer(args, "max_bytes", 262144, 1, AdbCommands.MAX_OUTPUT_BYTES)
        val result = try { execute(command, token, timeout, max, input, ::checkBoundary) }
        catch (error: Exception) {
            return JSONObject().put("status", "UNKNOWN").put("submitted", true).put("may_have_executed", true)
                .put("error", error.message).put("retry_safe", false)
        }
        result.put("mutating", plan.mutating)
        if (name == "adb_package" && plan.mutating && result.optString("status") == "COMPLETED" && textFailure(result))
            return result.put("status", "FAILED").put("error", "ADB_COMMAND_TEXT_FAILURE").put("verification", "NOT_VERIFIED")
        if (!completed(result)) return result.put("verification", "UNKNOWN")
        if (install) {
            result.put("apk_sha256", reviewed.getString("apk_sha256")).put("apk_bytes", input!!.size)
                .put("apk_package", reviewed.getString("apk_package"))
            // Exit 0 alone is insufficient: PackageManager can report Failure in stdout.
            if (result.optString("stdout").lineSequence().none { it.trim() == "Success" }) return result.put("verification", "NOT_VERIFIED")
            try {
                val pkg = reviewed.getString("apk_package")
                val path = AdbCommands.plan("adb_package", JSONObject().put("action", "path").put("package", pkg))
                val info = AdbCommands.plan("adb_package", JSONObject().put("action", "info").put("package", pkg))
                checkBoundary()
                val installedPath = execute(path.command, token, timeout, max, null, ::checkBoundary)
                result.put("installed_path", installedPath)
                if (!completed(installedPath)) return result.put("verification", "UNKNOWN")
                checkBoundary()
                val installedInfo = execute(info.command, token, timeout, max, null, ::checkBoundary)
                result.put("installed_package", installedInfo)
                if (!completed(installedInfo)) return result.put("verification", "UNKNOWN")
                val present = installedPath.optString("stdout").lineSequence().any { it.trim().startsWith("package:/") }
                val code = Regex("(?m)^\\s*versionCode=([0-9]+)\\b").find(installedInfo.optString("stdout"))?.groupValues?.get(1)?.toLongOrNull()
                result.put("verification", if (present && reviewed.has("apk_version_code") && code == reviewed.getLong("apk_version_code")) "VERIFIED_PACKAGE_VERSION" else "NOT_VERIFIED")
            } catch (error: Exception) { result.put("verification", "UNKNOWN").put("postcondition_error", error.message) }
            return result
        }
        val query = plan.verificationCommand ?: return result.put("verification", if (plan.mutating) "NOT_PROVEN" else "NOT_REQUIRED")
        try {
            checkBoundary()
            val observed = execute(query, token, timeout, max, null, ::checkBoundary)
            result.put("postcondition", observed)
            result.put("verification", if (!completed(observed)) "UNKNOWN" else when(plan.verificationKind) {
                "settings_value" -> if (observed.optString("stdout").removeSuffix("\n").removeSuffix("\r") == plan.expected) "VERIFIED" else "NOT_VERIFIED"
                "settings_absent" -> {
                    val lines = observed.optString("stdout").lineSequence().filter { it.isNotEmpty() }.toList()
                    if (lines.any { !it.contains('=') }) "UNKNOWN"
                    else if (lines.none { it.substringBefore('=') == plan.expected }) "VERIFIED" else "NOT_VERIFIED"
                }
                "package_present", "package_absent" -> {
                    val lines = observed.optString("stdout").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    val found = lines.any { it == "package:${plan.expected}" }
                    if (lines.any { !Regex("package:[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(it) }) "UNKNOWN"
                    else if (found == (plan.verificationKind == "package_present")) "VERIFIED" else "NOT_VERIFIED"
                }
                else -> "NOT_PROVEN"
            })
        } catch (error: Exception) {
            result.put("verification", "UNKNOWN").put("postcondition_error", error.message)
        }
        return result
    }

    private fun completed(result: JSONObject): Boolean = result.optString("status") == "COMPLETED" &&
        result.has("exit_code") && !result.isNull("exit_code") &&
        result.optInt("exit_code", -1) == 0 && !result.optBoolean("timed_out") && !result.optBoolean("cancelled") &&
        !result.optBoolean("truncated") && !result.optBoolean("output_truncated") && result.has("stdout")

    private fun textFailure(result: JSONObject): Boolean =
        Regex("(?im)^\\s*(?:Failure\\b|Error:|SecurityException\\b|Exception occurred)").containsMatchIn(result.optString("stdout") + "\n" + result.optString("stderr"))
}
