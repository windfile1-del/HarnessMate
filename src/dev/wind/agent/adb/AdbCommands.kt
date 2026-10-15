package dev.wind.agent.adb

import dev.wind.agent.permissions.AgentCapability
import org.json.JSONObject

data class AdbCommandPlan(
    val command: String,
    val mutating: Boolean,
    val sensitive: Boolean,
    val readPaths: List<String> = emptyList(),
    val writePaths: List<String> = emptyList(),
    val capabilities: Set<AgentCapability> = setOf(AgentCapability.LOCAL_ADB),
    val verificationCommand: String? = null,
    val verificationKind: String = "",
    val expected: String = ""
)

/** Pure, fail-closed planner. Structured calls never accept a shell fragment. */
object AdbCommands {
    const val MAX_APK_BYTES = 128 * 1024 * 1024
    const val MAX_OUTPUT_BYTES = 1024 * 1024
    val names = setOf("adb_status", "adb_package", "adb_activity", "adb_permission", "adb_appops",
        "adb_settings", "adb_system", "adb_logs", "adb_shell")
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val keyPattern = Regex("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,159}")

    /** POSIX single-quote encoding, including apostrophes; never concatenate unquoted input. */
    fun quote(value: String): String {
        require(value.length <= 16384 && value.none { it.code < 32 || it.code == 127 }) { "ADB_TOKEN_INVALID" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    private fun command(vararg tokens: String) = tokens.joinToString(" ", transform = ::quote)
    fun integer(a: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        if (!a.has(key)) return default
        val n = a.get(key)
        require(n is Int || n is Long) { "ADB_${key.uppercase()}_INVALID" }
        val value = (n as Number).toLong()
        require(value in min.toLong()..max.toLong()) { "ADB_${key.uppercase()}_INVALID" }
        return value.toInt()
    }
    private fun text(a: JSONObject, key: String, max: Int = 160, empty: Boolean = false): String {
        val value = a.get(key)
        require(value is String && value.length <= max && (empty || value.isNotEmpty()) &&
            value.none { it.code < 32 || it.code == 127 }) { "ADB_${key.uppercase()}_INVALID" }
        return value
    }
    private fun choice(a: JSONObject, key: String, choices: Set<String>, default: String? = null): String {
        val value = if (!a.has(key) && default != null) default else text(a, key)
        require(value in choices) { "ADB_${key.uppercase()}_INVALID" }
        return value
    }
    private fun pkg(a: JSONObject) = text(a, "package").also { require(packagePattern.matches(it)) { "ADB_PACKAGE_INVALID" } }
    private fun key(a: JSONObject, name: String) = text(a, name).also { require(keyPattern.matches(it)) { "ADB_${name.uppercase()}_INVALID" } }
    private fun user(a: JSONObject): String {
        require(integer(a, "user_id", 0, 0, 0) == 0) { "ADB_USER_ID_INVALID" }
        return "0"
    }
    private fun allowed(a: JSONObject, fields: Set<String>) {
        val keys = a.keys()
        while (keys.hasNext()) require(keys.next() in fields + setOf("timeout_ms", "max_bytes", "user_id")) { "ADB_UNKNOWN_ARGUMENT" }
    }
    fun installCommand(bytes: Int): String {
        require(bytes in 1..MAX_APK_BYTES) { "ADB_APK_SIZE_INVALID" }
        return command("cmd", "package", "install", "--user", "0", "-S", bytes.toString(), "-")
    }
    fun plan(name: String, args: JSONObject): AdbCommandPlan {
        require(name in names) { "ADB_TOOL_UNKNOWN" }
        integer(args, "timeout_ms", 30000, 1, 120000)
        integer(args, "max_bytes", 262144, 1, MAX_OUTPUT_BYTES)
        val uid = user(args)
        return when (name) {
            "adb_status" -> { allowed(args, emptySet()); AdbCommandPlan("", false, false, capabilities = emptySet()) }
            "adb_shell" -> {
                allowed(args, setOf("command"))
                val raw = args.get("command")
                require(raw is String && raw.isNotBlank() && raw.length <= 16384 &&
                    raw.none { it == '\u0000' || it.code == 127 || it.code < 32 && it !in "\n\t\r" }) { "ADB_RAW_COMMAND_INVALID" }
                require(!Regex("(?:^|[\\s;&|()])(?:/[^\\s;&|()]*/)?(?:su|sudo)(?:$|[\\s;&|()])").containsMatchIn(raw)) { "ADB_RAW_ROOT_NOT_AUTHORIZED" }
                // Broad delegation: raw shell cannot reliably enforce fine file/screen/action scopes.
                AdbCommandPlan(raw, true, true, capabilities = setOf(AgentCapability.LOCAL_ADB, AgentCapability.RAW_ADB_SHELL))
            }
            "adb_package" -> {
                allowed(args, setOf("action", "package", "path"))
                val action = choice(args, "action", setOf("list", "info", "path", "enable", "disable", "force_stop", "clear", "uninstall", "install"))
                allowed(args, when(action) { "list" -> setOf("action"); "install" -> setOf("action", "path"); else -> setOf("action", "package") })
                if (action == "list") AdbCommandPlan(command("pm", "list", "packages", "--user", uid), false, false)
                else if (action == "install") {
                    val path = text(args, "path", 4096)
                    require(path.endsWith(".apk", true) && !path.startsWith("artifact:") && !path.startsWith("execution:") &&
                        !path.startsWith("content:") && !path.startsWith("file:") && !path.startsWith("http:") && !path.startsWith("https:")) { "ADB_APK_PATH_INVALID" }
                    // Size is derived from bounded authorized input by AdbTools, never from model arguments.
                    AdbCommandPlan("", true, true, readPaths = listOf(path))
                } else {
                    val p = pkg(args)
                    when (action) {
                        "info" -> AdbCommandPlan(command("dumpsys", "package", p), false, false)
                        "path" -> AdbCommandPlan(command("pm", "path", "--user", uid, p), false, false)
                        "enable", "disable" -> AdbCommandPlan(command("pm", if (action == "disable") "disable-user" else "enable", "--user", uid, p), true, true,
                            verificationCommand = command("pm", "list", "packages", if (action == "enable") "-e" else "-d", "--user", uid, p), verificationKind = "package_present", expected = p)
                        "uninstall" -> AdbCommandPlan(command("pm", "uninstall", "--user", uid, p), true, true,
                            verificationCommand = command("pm", "list", "packages", "--user", uid, p), verificationKind = "package_absent", expected = p)
                        "clear" -> AdbCommandPlan(command("pm", "clear", "--user", uid, p), true, true,
                            verificationCommand = command("dumpsys", "package", p))
                        else -> AdbCommandPlan(command("am", "force-stop", "--user", uid, p), true, true,
                            verificationCommand = command("dumpsys", "package", p))
                    }
                }
            }
            "adb_activity" -> {
                allowed(args, setOf("action", "component"))
                val action = choice(args, "action", setOf("start", "query"))
                val component = text(args, "component", 320)
                val parts = component.split('/')
                require(parts.size == 2 && packagePattern.matches(parts[0]) && Regex("\\.?[A-Za-z_$][A-Za-z0-9_.$]*").matches(parts[1])) { "ADB_COMPONENT_INVALID" }
                val cmd = if (action == "start") command("am", "start", "--user", uid, "-n", component)
                    else command("cmd", "package", "resolve-activity", "--brief", "--user", uid, "-n", component)
                AdbCommandPlan(cmd, action == "start", action == "start", capabilities = setOf(AgentCapability.LOCAL_ADB, AgentCapability.ACCESSIBILITY))
            }
            "adb_permission" -> {
                allowed(args, setOf("action", "package", "permission"))
                val action = choice(args, "action", setOf("query", "grant", "revoke"))
                val p = pkg(args)
                val permission = text(args, "permission").also { require(packagePattern.matches(it)) { "ADB_PERMISSION_INVALID" } }
                require(action == "query" || permission !in setOf("android.permission.ADD_TRUSTED_DISPLAY", "android.permission.INTERNAL_SYSTEM_WINDOW")) {
                    "ADB_PERMISSION_NOT_RUNTIME_GRANTABLE: $permission cannot be changed with pm grant/revoke; use phone_display status/create and shell authority diagnostics"
                }
                val query = command("dumpsys", "package", p)
                AdbCommandPlan(if (action == "query") query else command("pm", action, "--user", uid, p, permission), action != "query", action != "query",
                    verificationCommand = if (action == "query") null else query)
            }
            "adb_appops" -> {
                allowed(args, setOf("action", "package", "op", "mode"))
                val action = choice(args, "action", setOf("get", "set", "reset"))
                allowed(args, when(action) { "reset" -> setOf("action", "package"); "get" -> setOf("action", "package", "op"); else -> setOf("action", "package", "op", "mode") })
                val p = pkg(args)
                val op = if (action == "reset") null else key(args, "op").also {
                    require(Regex("(?:[A-Z][A-Z0-9_]*|android:[a-z][a-z0-9_]*)").matches(it)) { "ADB_OP_INVALID" }
                }
                val query = if (op == null) command("cmd", "appops", "get", "--user", uid, p) else command("cmd", "appops", "get", "--user", uid, p, op)
                val cmd = when (action) {
                    "get" -> query
                    "reset" -> command("cmd", "appops", "reset", "--user", uid, p)
                    else -> command("cmd", "appops", "set", "--user", uid, p, requireNotNull(op), choice(args, "mode", setOf("allow", "ignore", "deny", "default", "foreground")))
                }
                AdbCommandPlan(cmd, action != "get", action != "get", verificationCommand = if (action == "get") null else query)
            }
            "adb_settings" -> {
                allowed(args, setOf("action", "namespace", "key", "value"))
                val action = choice(args, "action", setOf("get", "list", "put", "delete"))
                allowed(args, when(action) { "list" -> setOf("action", "namespace"); "put" -> setOf("action", "namespace", "key", "value"); else -> setOf("action", "namespace", "key") })
                val ns = choice(args, "namespace", setOf("system", "secure", "global"))
                val k = if (action == "list") null else key(args, "key")
                val tokens = mutableListOf("settings", "--user", uid, action, ns)
                k?.let { tokens.add(it) }
                val value = if (action == "put") text(args, "value", 8192, true).also { tokens.add(it) } else ""
                AdbCommandPlan(command(*tokens.toTypedArray()), action in setOf("put", "delete"), action in setOf("put", "delete"),
                    verificationCommand = when(action) { "put" -> command("settings", "--user", uid, "get", ns, requireNotNull(k)); "delete" -> command("settings", "--user", uid, "list", ns); else -> null },
                    verificationKind = when(action) { "put" -> "settings_value"; "delete" -> "settings_absent"; else -> "" }, expected = if (action == "delete") k.orEmpty() else value)
            }
            "adb_system" -> {
                allowed(args, setOf("action", "key", "service", "pid"))
                val action = choice(args, "action", setOf("properties", "processes", "process", "diagnostics"))
                allowed(args, when(action) { "properties" -> setOf("action", "key"); "process" -> setOf("action", "pid"); "diagnostics" -> setOf("action", "service"); else -> setOf("action") })
                when (action) {
                    "properties" -> AdbCommandPlan(if (args.has("key")) command("getprop", key(args, "key")) else command("getprop"), false, false)
                    "processes" -> AdbCommandPlan(command("ps", "-A"), false, false)
                    "process" -> AdbCommandPlan(command("ps", "-p", integer(args, "pid", -1, 1, Int.MAX_VALUE).also { require(it > 0) { "ADB_PID_REQUIRED" } }.toString()), false, false)
                    else -> {
                        val service = choice(args, "service", setOf("battery", "power", "thermalservice", "display", "meminfo", "window", "activity"))
                        val caps = mutableSetOf(AgentCapability.LOCAL_ADB)
                        if (service == "display") caps.add(AgentCapability.SCREEN)
                        if (service in setOf("window", "activity")) caps.add(AgentCapability.ACCESSIBILITY)
                        AdbCommandPlan(command("dumpsys", service), false, false, capabilities = caps)
                    }
                }
            }
            else -> {
                allowed(args, setOf("lines", "buffer", "format"))
                AdbCommandPlan(command("logcat", "-d", "-t", integer(args, "lines", 200, 1, 2000).toString(), "-b",
                    choice(args, "buffer", setOf("main", "system", "crash", "events"), "main"), "-v",
                    choice(args, "format", setOf("threadtime", "brief", "tag", "time"), "threadtime")), false, false)
            }
        }
    }
}
