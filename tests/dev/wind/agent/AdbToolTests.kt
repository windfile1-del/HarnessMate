package dev.wind.agent

import dev.wind.agent.adb.*
import dev.wind.agent.capability.*
import dev.wind.agent.permissions.*
import org.json.JSONObject
import java.io.ByteArrayInputStream

object AdbToolTests {
    private var checks = 0
    private fun verify(value: Boolean, reason: String) { checks++; check(value) { reason } }
    private fun rejects(reason: String, block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        verify(rejected, reason)
    }
    private fun a(vararg pairs: Pair<String, Any>) = JSONObject().also { j -> pairs.forEach { j.put(it.first, it.second) } }
    private fun ok(stdout: String = "") = a("status" to "COMPLETED", "submitted" to true, "retry_safe" to false,
        "stdout" to stdout, "stderr" to "", "exit_code" to 0, "truncated" to false)
    @JvmStatic fun main(args: Array<String>) {
        val variants = listOf(
            "adb_status" to a(),
            "adb_package" to a("action" to "list"),
            "adb_package" to a("action" to "info", "package" to "com.example.app"),
            "adb_package" to a("action" to "path", "package" to "com.example.app"),
            "adb_activity" to a("action" to "query", "component" to "com.example.app/.Main"),
            "adb_permission" to a("action" to "query", "package" to "com.example.app", "permission" to "android.permission.CAMERA"),
            "adb_appops" to a("action" to "get", "package" to "com.example.app", "op" to "CAMERA"),
            "adb_settings" to a("action" to "get", "namespace" to "secure", "key" to "test_key"),
            "adb_settings" to a("action" to "list", "namespace" to "system"),
            "adb_system" to a("action" to "properties"),
            "adb_system" to a("action" to "processes"),
            "adb_system" to a("action" to "process", "pid" to 2000),
            "adb_system" to a("action" to "diagnostics", "service" to "battery"),
            "adb_logs" to a())
        val resolver = CapabilityResolver { null }
        val planContext = TaskContext("task", "", 0, CapabilityMode.PLAN)
        for ((name, j) in variants) {
            verify(!AdbCommands.plan(name, j).mutating, "$name recognized read")
            verify(resolver.resolve(planContext, name, j) is CapabilityRequirement.Internal, "$name PLAN read allowed")
            verify(ToolRegistry.isEnabled(name), "$name registered")
        }
        val effects = mutableListOf<Pair<String, JSONObject>>()
        for(permission in listOf("android.permission.ADD_TRUSTED_DISPLAY", "android.permission.INTERNAL_SYSTEM_WINDOW")) {
            for(action in listOf("grant","revoke")) {
                val failure=runCatching { AdbCommands.plan("adb_permission",a("action" to action,"package" to "dev.wind.agent","permission" to permission)) }.exceptionOrNull()
                verify(failure?.message?.startsWith("ADB_PERMISSION_NOT_RUNTIME_GRANTABLE:")==true,"signature permission changes rejected before native dispatch")
            }
            verify(!AdbCommands.plan("adb_permission",a("action" to "query","package" to "dev.wind.agent","permission" to permission)).mutating,"signature permission diagnostics remain available")
        }
        for (action in listOf("enable", "disable", "force_stop", "clear", "uninstall")) effects.add("adb_package" to a("action" to action, "package" to "com.example.app"))
        effects.add("adb_package" to a("action" to "install", "path" to "/storage/emulated/0/Download/app.apk"))
        effects.add("adb_activity" to a("action" to "start", "component" to "com.example.app/.Main"))
        for (action in listOf("grant", "revoke")) effects.add("adb_permission" to a("action" to action, "package" to "com.example.app", "permission" to "android.permission.CAMERA"))
        effects.add("adb_appops" to a("action" to "set", "package" to "com.example.app", "op" to "CAMERA", "mode" to "ignore"))
        effects.add("adb_appops" to a("action" to "reset", "package" to "com.example.app"))
        effects.add("adb_settings" to a("action" to "put", "namespace" to "secure", "key" to "key", "value" to "text"))
        effects.add("adb_settings" to a("action" to "delete", "namespace" to "secure", "key" to "key"))
        effects.add("adb_shell" to a("command" to "echo hello"))
        for ((name, j) in effects) {
            val p = AdbCommands.plan(name, j)
            verify(p.mutating && p.sensitive, "$name sensitive effect")
            verify(resolver.resolve(planContext, name, j) is CapabilityRequirement.Denied, "$name PLAN effect denied")
            verify(AdbTools.requiresApproval(p, ActionPolicy.CONSERVATIVE), "$name conservative approval")
            verify(AdbTools.requiresApproval(p, ActionPolicy.BALANCED), "$name balanced approval")
            verify(!AdbTools.requiresApproval(p, ActionPolicy.AUTONOMOUS), "$name autonomy inside capability")
        }
        verify(AdbTools.requiresApproval(effects.first().let { AdbCommands.plan(it.first, it.second) }, ActionPolicy.AUTONOMOUS, true), "install approval cannot be skipped")
        val hostile = listOf("com.example.app;id", "$(id)", "`id`", "--user", "com.example\napp", "com.example.app'", "com.example/app", "a..b", "")
        for (p in hostile) rejects("hostile package $p") { AdbCommands.plan("adb_package", a("action" to "clear", "package" to p)) }
        for (n in listOf(-1, 1, 10, "0", 0.0, true)) rejects("user ID $n") { AdbCommands.plan("adb_package", a("action" to "list", "user_id" to n)) }
        for (n in listOf(0, -1, 120001, "30", 30.1)) rejects("timeout $n") { AdbCommands.plan("adb_logs", a("timeout_ms" to n)) }
        for (n in listOf(0, -1, 1048577, "2", 2.0)) rejects("output $n") { AdbCommands.plan("adb_logs", a("max_bytes" to n)) }
        for (n in listOf(0, 2001, "200")) rejects("log count $n") { AdbCommands.plan("adb_logs", a("lines" to n)) }
        rejects("unknown tool") { AdbCommands.plan("adb_other", a()) }
        rejects("unknown package action") { AdbCommands.plan("adb_package", a("action" to "install_existing", "package" to "com.example.app")) }
        rejects("unknown fields") { AdbCommands.plan("adb_package", a("action" to "list", "args" to "; id")) }
        rejects("unused hostile fields") { AdbCommands.plan("adb_system", a("action" to "processes", "service" to ";id")) }
        rejects("arbitrary dumpsys") { AdbCommands.plan("adb_system", a("action" to "diagnostics", "service" to "package;id")) }
        rejects("settings key injection") { AdbCommands.plan("adb_settings", a("action" to "get", "namespace" to "secure", "key" to "x;id")) }
        rejects("component injection") { AdbCommands.plan("adb_activity", a("action" to "start", "component" to "com.example.app/.Main;id")) }
        rejects("appops mode injection") { AdbCommands.plan("adb_appops", a("action" to "set", "package" to "com.example.app", "op" to "CAMERA", "mode" to "allow;id")) }
        rejects("raw null") { AdbCommands.plan("adb_shell", a("command" to "echo\u0000id")) }
        for (raw in listOf("su", "su -c id", "/system/xbin/su -c id", "echo a; su -c id", "sudo id")) rejects("explicit root refused") { AdbCommands.plan("adb_shell", a("command" to raw)) }
        val raw = AdbCommands.plan("adb_shell", a("command" to "echo hi; id"))
        verify(raw.capabilities == setOf(AgentCapability.LOCAL_ADB, AgentCapability.RAW_ADB_SHELL), "raw independent broad delegation")
        verify(raw.readPaths.isEmpty() && raw.writePaths.isEmpty(), "raw does not claim reliable path scoping")
        verify(AgentCapability.SCREEN in AdbCommands.plan("adb_system", a("action" to "diagnostics", "service" to "display")).capabilities, "display SCREEN gate")
        verify(AgentCapability.ACCESSIBILITY in AdbCommands.plan("adb_activity", a("action" to "query", "component" to "com.example.app/.Main")).capabilities, "activity accessibility gate")
        val payload = "a'b; $(id) `id` \\ end"
        val put = AdbCommands.plan("adb_settings", a("action" to "put", "namespace" to "secure", "key" to "key", "value" to payload))
        verify(put.command.endsWith(AdbCommands.quote(payload)), "literal POSIX quoted settings")
        rejects("quote control chars") { AdbCommands.quote("a\nb") }
        rejects("empty APK") { AdbTools.readBoundedApk(ByteArrayInputStream(byteArrayOf()), CancellationToken()) }
        verify(AdbTools.readBoundedApk(ByteArrayInputStream(byteArrayOf(1, 2, 3)), CancellationToken()).contentEquals(byteArrayOf(1, 2, 3)), "bounded APK exact bytes")
        rejects("cancelled APK read") { AdbTools.readBoundedApk(ByteArrayInputStream(byteArrayOf(1)), CancellationToken().also { it.cancel() }) }
        rejects("APK size command") { AdbCommands.installCommand(AdbCommands.MAX_APK_BYTES + 1) }
        rejects("private content URI install") { AdbCommands.plan("adb_package", a("action" to "install", "path" to "content://private/app.apk")) }
        verify(AdbCommands.plan("adb_package", a("action" to "install", "path" to "workspace://app.apk")).readPaths == listOf("workspace://app.apk"), "install exact read scope")

        var calls = 0; var approvals = 0
        val store = AgentPermissions()
        store.beginTask("task")
        var grantCheck: (AdbCommandPlan) -> Unit = {}
        var response: (String) -> JSONObject = { ok() }
        var decision: (JSONObject) -> Boolean = { true }
        var apk = byteArrayOf(1, 2, 3)
        var lastInput: ByteArray? = null
        var beforeNative: () -> Unit = {}
        var reviewed = JSONObject()
        val runner = AdbToolRunner(store, "task", { grantCheck(it) }, { cmd, _, _, _, input, guard -> beforeNative(); guard(); calls++; if (input != null) lastInput = input; response(cmd) },
            { _, j, _ -> approvals++; reviewed = JSONObject(j.toString()); decision(j) },
            { _, check -> check(); a("apk_package" to "com.example.app", "apk_version_name" to "1", "apk_version_code" to 1) },
            { _, _, check -> check(); apk })
        decision = { false }
        verify(runner.run("adb_shell", a("command" to "echo hi"), CancellationToken()).getString("status") == "REJECTED" && calls == 0, "denial before native effects")
        decision = { store.configure(policy = ActionPolicy.AUTONOMOUS); true }
        rejects("late approval revoked revision") { runner.run("adb_shell", a("command" to "echo hi"), CancellationToken()) }
        verify(calls == 0, "stale approval never submitted")
        decision = { true }
        response = { throw IllegalStateException("connection lost") }
        val lost = runner.run("adb_shell", a("command" to "echo hi"), CancellationToken())
        verify(lost.getString("status") == "UNKNOWN" && !lost.getBoolean("retry_safe") && calls == 1, "lost mutation submitted once")
        response = { ok().put("timed_out", true) }
        val beforeTimeout = calls
        verify(runner.run("adb_settings", a("action" to "put", "namespace" to "secure", "key" to "key", "value" to "v"), CancellationToken()).getString("verification") == "UNKNOWN" && calls == beforeTimeout + 1, "unknown mutation no verification/retry")
        response = { if (it.contains("'get'")) ok("v\n") else ok() }
        verify(runner.run("adb_settings", a("action" to "put", "namespace" to "secure", "key" to "key", "value" to "v"), CancellationToken()).getString("verification") == "VERIFIED", "settings postcondition")
        response = { if (it.contains("'get'")) ok("wrong\n") else ok() }
        verify(runner.run("adb_settings", a("action" to "put", "namespace" to "secure", "key" to "key", "value" to "v"), CancellationToken()).getString("verification") == "NOT_VERIFIED", "settings mismatch")
        response = { if (it.contains("'list'")) ok("package:com.example.app\n") else ok() }
        verify(runner.run("adb_package", a("action" to "enable", "package" to "com.example.app"), CancellationToken()).getString("verification") == "VERIFIED", "package enable verified")
        verify(runner.run("adb_package", a("action" to "uninstall", "package" to "com.example.app"), CancellationToken()).getString("verification") == "NOT_VERIFIED", "uninstall present fails proof")
        response = { if (it.contains("'list'")) ok().put("truncated", true) else ok() }
        verify(runner.run("adb_package", a("action" to "uninstall", "package" to "com.example.app"), CancellationToken()).getString("verification") == "UNKNOWN", "truncation cannot prove absence")
        response = { when { it.contains("'install'") -> ok("Success\n"); it.contains("'path'") -> ok("package:/data/app/base.apk\n"); else -> ok("  versionCode=1 minSdk=23\n") } }
        val priorApprovals = approvals
        val installed = runner.run("adb_package", a("action" to "install", "path" to "workspace://app.apk"), CancellationToken())
        verify(installed.getString("verification") == "VERIFIED_PACKAGE_VERSION", "install package version verified")
        verify(approvals == priorApprovals + 1 && lastInput!!.contentEquals(apk), "autonomous install exact bytes effect approval")
        verify(reviewed.getInt("apk_bytes") == 3 && reviewed.getString("apk_sha256").length == 64 && reviewed.getString("apk_package") == "com.example.app", "install review exact size hash facts")
        response = { a("status" to "NOT_SUBMITTED", "submitted" to false, "retry_safe" to true) }
        val notSent = runner.run("adb_shell", a("command" to "id"), CancellationToken())
        verify(!notSent.getBoolean("submitted") && notSent.getBoolean("retry_safe"), "native NOT_SUBMITTED preserved")
        response = { ok().put("status", "UNKNOWN") }
        val unknownRead = runner.run("adb_logs", a(), CancellationToken())
        verify(!unknownRead.getBoolean("retry_safe") && unknownRead.getString("verification") == "UNKNOWN", "unknown read is not automatically replayable")
        response = { ok("Failure [INSTALL_FAILED_TEST]\n") }
        val beforeFailure = calls
        verify(runner.run("adb_package", a("action" to "install", "path" to "workspace://app.apk"), CancellationToken()).getString("status") == "FAILED" && calls == beforeFailure + 1, "exit zero textual install failure cannot verify or retry")
        response = { ok("Error: legitimate logged content\nFailure is quoted data\n") }
        verify(runner.run("adb_logs", a(), CancellationToken()).getString("status") == "COMPLETED", "log Error/Failure text is data")
        verify(runner.run("adb_shell", a("command" to "printf 'Error: data'"), CancellationToken()).getString("status") == "COMPLETED", "raw output Error text is data")
        verify(runner.run("adb_settings", a("action" to "get", "namespace" to "secure", "key" to "key"), CancellationToken()).getString("status") == "COMPLETED", "settings Error text is data")
        response = { if (it.contains("'get'")) ok("Error: expected value\n") else ok() }
        verify(runner.run("adb_settings", a("action" to "put", "namespace" to "secure", "key" to "key", "value" to "Error: expected value"), CancellationToken()).getString("verification") == "VERIFIED", "postcondition compares Error text literally")
        response = { if (it.contains("'list'")) ok("Error: malformed package response\n") else ok() }
        verify(runner.run("adb_package", a("action" to "uninstall", "package" to "com.example.app"), CancellationToken()).getString("verification") == "UNKNOWN", "malformed package list cannot prove absence")
        beforeNative = { store.configure(policy = ActionPolicy.BALANCED) }
        val beforeRevoke = calls
        val revoked = runner.run("adb_logs", a(), CancellationToken())
        verify(calls == beforeRevoke && revoked.getString("status") == "UNKNOWN", "native beforeSend catches handoff revision change")
        beforeNative = {}
        grantCheck = { throw SecurityException("missing permission") }
        val beforeGate = calls
        rejects("native boundary gate") { runner.run("adb_logs", a(), CancellationToken()) }
        verify(calls == beforeGate, "permission denial makes no native call")
        grantCheck = {}
        store.endTask("task")
        rejects("ended task") { runner.run("adb_logs", a(), CancellationToken()) }

        // Exercise the main-owned permission resolver, not a duplicate planner-only assertion.
        val permissions = AgentPermissions()
        val gate = AgentPermissionGate(permissions) { null }
        for ((name, j) in variants.filter { it.first != "adb_status" })
            verify(gate.needs(name, j).any { it.capability == AgentCapability.LOCAL_ADB }, "$name main permission gate LOCAL_ADB")
        verify(gate.needs("adb_shell", a("command" to "id")).map { it.capability }.containsAll(raw.capabilities), "main raw both grants")
        verify(gate.needs("adb_system", a("action" to "diagnostics", "service" to "display")).any { it.capability == AgentCapability.SCREEN }, "main SCREEN mapping")
        verify(gate.needs("adb_activity", a("action" to "start", "component" to "com.example.app/.Main")).any { it.capability == AgentCapability.ACCESSIBILITY }, "main accessibility mapping")
        val inputPath = "/storage/emulated/0/Download/app.apk"
        verify(gate.needs("adb_package", a("action" to "install", "path" to inputPath)).any { it.capability == AgentCapability.PATH_READ && it.path.replace('\\', '/').endsWith("/Download/app.apk") }, "main file read mapping")
        verify(gate.error("adb_logs", a(), "task") != null, "ungranted LOCAL_ADB denied")
        permissions.set(AgentCapability.LOCAL_ADB, PermissionScope.GLOBAL, true)
        verify(gate.error("adb_logs", a(), "task") == null, "LOCAL_ADB permits logs")
        verify(gate.error("adb_shell", a("command" to "id"), "task") != null, "LOCAL_ADB never implies RAW_ADB_SHELL")
        permissions.set(AgentCapability.RAW_ADB_SHELL, PermissionScope.TASK, true, taskId = "task")
        verify(gate.error("adb_shell", a("command" to "id"), "task") == null, "raw requires both independent grants")
        verify(gate.error("adb_shell", a("command" to "id"), "other") != null, "raw task isolation")
        verify(gate.error("adb_system", a("action" to "diagnostics", "service" to "display"), "task") != null, "ADB never implies SCREEN")
        permissions.set(AgentCapability.SCREEN, PermissionScope.GLOBAL, true)
        verify(gate.error("adb_system", a("action" to "diagnostics", "service" to "display"), "task") == null, "explicit SCREEN permits display")
        verify(gate.error("adb_activity", a("action" to "start", "component" to "com.example.app/.Main"), "task") != null, "ADB never implies ACCESSIBILITY")
        verify(gate.error("adb_package", a("action" to "install", "path" to inputPath), "task") != null, "ADB never implies source file read")
        val fileNeed = gate.needs("adb_package", a("action" to "install", "path" to inputPath)).single { it.capability == AgentCapability.PATH_READ }
        permissions.set(AgentCapability.PATH_READ, PermissionScope.GLOBAL, true, path = fileNeed.path)
        verify(gate.error("adb_package", a("action" to "install", "path" to inputPath), "task") == null, "install only exact authorized source")
        permissions.endTask("task")
        verify(gate.error("adb_shell", a("command" to "id"), "task") != null, "task end revokes raw shell")
        println("AdbToolTests: $checks checks passed; NO_DEVICE_NO_APK")
    }
}
