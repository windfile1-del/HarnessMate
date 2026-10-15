package dev.wind.agent.shell

object ShellPolicy {

    const val MAX_OUTPUT_BYTES = 1024 * 1024 // 1 MiB cap

    private val BANNED_EXECUTABLES: Set<String> = hashSetOf(
        "su", "sudo", "mount", "umount", "reboot", "shutdown",
        "pm", "am", "settings", "setprop", "iptables", "ip6tables",
        "dd", "mkfs", "fdisk", "adb"
    )

    private val BANNED_PATH_PREFIXES: List<String> = listOf(
        "/storage", "/sdcard", "/data", "/system", "/vendor",
        "/proc", "/sys", "/dev", "/etc"
    )

    private val NETWORK_TOOLS: Set<String> = hashSetOf(
        "curl", "wget", "nc", "ncat", "netcat", "ssh", "scp", "sftp", "ping", "telnet", "ftp", "traceroute"
    )

    fun validateRequest(request: ShellRequest) {
        require(request.timeoutMs in 1L..dev.wind.agent.runtime.RuntimeProcessSupervisor.MAX_TIMEOUT_MS) {
            "RUNTIME_TIMEOUT_INVALID: supported=1..${dev.wind.agent.runtime.RuntimeProcessSupervisor.MAX_TIMEOUT_MS}ms"
        }
        val exe = request.executable.trim().lowercase()
        if (exe.isEmpty()) {
            throw IllegalArgumentException("Executable name is required")
        }
        if (exe.contains("..") || exe.contains("%2e")) {
            throw SecurityException("Directory traversal in executable path is prohibited: ${request.executable}")
        }

        // Check banned binaries
        val simpleName = exe.substringAfterLast('/').substringAfterLast('\\')
        if (BANNED_EXECUTABLES.contains(simpleName)) {
            throw SecurityException("Execution of '$simpleName' is strictly prohibited in Vega Safe for security.")
        }

        // Enforce network policy on network utilities
        if (!request.network && NETWORK_TOOLS.contains(simpleName)) {
            throw SecurityException("Command '$simpleName' requires network access. Set network=true and grant network authorization.")
        }
        if (!request.network && request.networkDomains.isNotEmpty()) {
            throw SecurityException("network_domains requires network=true")
        }

        // Disallow sh -c or bash -c invocation vectors
        if (simpleName == "sh" || simpleName == "bash" || simpleName == "zsh" || simpleName == "dash") {
            if (request.args.any { it.startsWith("-") && !it.startsWith("--") && it.drop(1).contains('c') }) {
                throw SecurityException("Arbitrary 'sh -c' execution is forbidden in V0.2 Coding Alpha. Pass executable and args directly.")
            }
        }

        if (simpleName in setOf("python", "python3", "node", "nodejs") && request.args.any {
            it == "-c" || it.startsWith("-c") || it == "-e" || it.startsWith("--eval") || it == "-p" || it.startsWith("--print")
        }) throw SecurityException("Interpreter command strings are forbidden; use execution_edit_file or a reviewed script file")

        // Check arguments for system path escapes
        for (arg in request.args) {
            val clean = arg.trim()
            if (clean.contains("..") || clean.contains("%2e") || clean.contains("shared_prefs") || clean.contains("databases")) {
                throw SecurityException("Directory traversal '..' or private storage reference in command argument is prohibited: $arg")
            }
            for (banned in BANNED_PATH_PREFIXES) {
                if (clean == banned || clean.startsWith("$banned/")) {
                    throw SecurityException("Command argument references forbidden system path: $arg")
                }
            }
        }
    }

    fun clampOutput(text: String): String {
        if (text.length <= MAX_OUTPUT_BYTES) {
            return text
        }
        return text.substring(0, MAX_OUTPUT_BYTES) + "\n[OUTPUT TRUNCATED: exceeded 1MB limit]"
    }
}
