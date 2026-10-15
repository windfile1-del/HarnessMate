package dev.wind.agent.packages

import dev.wind.agent.shell.ShellPolicy
import dev.wind.agent.shell.ShellRequest
import org.json.JSONObject
import java.net.URI

data class ProjectPackagePlan(
    val manager: String,
    val action: String,
    val projectPath: String,
    val networkDomains: Set<String>,
    val commands: List<ShellRequest>,
    val mutating: Boolean,
    /** App-authored support files, staged inside the same command transaction. */
    val preparedFiles: Map<String, String> = emptyMap()
)

/** Thin adapter to the installed npm/pip, shared by approval and execution. No shell strings. */
object ProjectPackageCommands {
    const val VENV = ".harnessmate-venv"
    private val npmName = Regex("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
    private val pipName = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val npmVersion = Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
    private val pipVersion = Regex("[0-9]+(?:\\.[0-9]+)*(?:(?:a|b|rc)[0-9]+)?(?:\\.post[0-9]+)?(?:\\.dev[0-9]+)?(?:\\+[a-zA-Z0-9]+(?:[._-][a-zA-Z0-9]+)*)?")

    private fun relative(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 1024 && !raw.startsWith('/') &&
            !raw.contains('\\') && !raw.contains(':') && !raw.contains('%') &&
            raw.none { it.code < 32 || it.code == 127 }) { "PACKAGE_PATH_INVALID" }
        require(raw.split('/').all { it.isNotBlank() && it != "." && !it.contains("..") && !it.startsWith('-') }) { "PACKAGE_PATH_INVALID" }
        return raw
    }

    private fun registry(raw: String): Pair<String, String> {
        val uri = try { URI(raw) } catch (_: Exception) { error("PACKAGE_REGISTRY_INVALID") }
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && host.isNotBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port == 443) &&
            uri.rawPath.orEmpty().split('/').none { it == ".." || it.contains('%') } &&
            host.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) { "PACKAGE_REGISTRY_INVALID" }
        return raw to host
    }

    fun plan(args: JSONObject): ProjectPackagePlan {
        val manager = args.getString("manager")
        if (manager == "gradle") return GradleProjectDependencies.plan(args)
        require(!args.has("module") && !args.has("configurations")) { "PACKAGE_GRADLE_OPTION_INVALID" }
        val action = args.optString("action", "install")
        require(manager in setOf("npm", "pip")) { "PACKAGE_MANAGER_INVALID" }
        val supported = if (manager == "npm") setOf("install", "add", "ci", "remove", "list", "check")
            else setOf("install", "add", "remove", "list", "check", "freeze")
        require(action in supported) { "PACKAGE_ACTION_INVALID" }
        val path = args.getString("project_path")
        require(path.startsWith("execution://")) { "PACKAGE_EXECUTION_PATH_REQUIRED" }
        val virtual = path.removePrefix("execution://").trimEnd('/')
        relative(virtual)
        require(virtual.substringBefore('/').matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "PACKAGE_MOUNT_INVALID" }
        val projectPath = "execution://$virtual"
        val installing = action in setOf("install", "add", "ci")
        val mutating = installing || action == "remove"
        val network = args.optBoolean("network", installing)
        require(installing || !network) { "PACKAGE_ACTION_NETWORK_INVALID" }
        val timeoutSeconds = args.optLong("timeout_seconds", 300)
        require(timeoutSeconds in 1..3600) { "PACKAGE_TIMEOUT_INVALID" }
        val packageArray = args.optJSONArray("packages")
        val packages = packageArray?.let { a ->
            require(a.length() in 1..128) { "PACKAGE_LIST_INVALID" }
            (0 until a.length()).map { a.getString(it).also { p ->
                require(p.length <= 256 && p.isNotBlank() && p == p.trim()) { "PACKAGE_SPEC_INVALID" }
            } }
        }.orEmpty()
        require((action in setOf("add", "remove")) == packages.isNotEmpty()) { "PACKAGE_LIST_ACTION_MISMATCH" }
        for (pkg in packages) {
            if (action == "remove") require((if (manager == "npm") npmName else pipName).matches(pkg)) { "PACKAGE_NAME_INVALID" }
            else if (manager == "npm") {
                val split = pkg.lastIndexOf('@')
                require(split > 0 && npmName.matches(pkg.substring(0, split)) && npmVersion.matches(pkg.substring(split + 1))) { "PACKAGE_EXACT_VERSION_REQUIRED" }
            } else {
                val split = pkg.split("==")
                require(split.size == 2 && pipName.matches(split[0]) && pipVersion.matches(split[1])) { "PACKAGE_EXACT_VERSION_REQUIRED" }
            }
        }
        val requirements = args.optString("requirements_path", "")
        require(requirements.isEmpty() || manager == "pip" && action == "install") { "PACKAGE_REQUIREMENTS_ACTION_INVALID" }
        if (manager == "pip" && action == "install") relative(requirements)
        val wheelhouse = args.optString("wheelhouse", "")
        require(wheelhouse.isEmpty() || manager == "pip" && installing) { "PACKAGE_WHEELHOUSE_ACTION_INVALID" }
        if (wheelhouse.isNotEmpty()) relative(wheelhouse)
        require(manager == "npm" || !args.has("allow_scripts")) { "PACKAGE_SCRIPTS_OPTION_INVALID" }
        require(manager == "pip" || !args.has("wheel_only") && !args.has("require_hashes")) { "PACKAGE_PIP_OPTION_INVALID" }
        require(!args.has("require_hashes") || action == "install") { "PACKAGE_HASHES_ACTION_INVALID" }
        require(!args.has("allow_scripts") || mutating) { "PACKAGE_SCRIPTS_ACTION_INVALID" }
        require(!args.has("wheel_only") || installing) { "PACKAGE_WHEELS_ACTION_INVALID" }
        require(manager == "npm" && action == "add" || !args.has("dev")) { "PACKAGE_DEV_OPTION_INVALID" }
        val selected = registry(args.optString("registry_url", if (manager == "npm") "https://registry.npmjs.org/" else "https://pypi.org/simple"))
        require(installing || !args.has("registry_url")) { "PACKAGE_REGISTRY_ACTION_INVALID" }
        val domains = linkedSetOf<String>()
        if (network) {
            domains.add(selected.second)
            if (manager == "pip" && selected.second == "pypi.org") domains.add("files.pythonhosted.org")
        }
        args.optJSONArray("network_domains")?.let { a ->
            require(network && a.length() <= 32) { "PACKAGE_NETWORK_DOMAINS_INVALID" }
            for (i in 0 until a.length()) {
                val domain = a.getString(i).lowercase()
                require(domain.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*"))) { "PACKAGE_NETWORK_DOMAIN_INVALID" }
                domains.add(domain)
            }
        }
        val commands = mutableListOf<ShellRequest>()
        fun command(executable: String, argv: List<String>, useNetwork: Boolean = network, env: Map<String, String> = emptyMap()) {
            commands.add(ShellRequest(executable, argv, projectPath, env, timeoutSeconds * 1000,
                useNetwork, if (useNetwork) domains else emptySet(), validationCheck = !mutating))
        }
        if (manager == "npm") {
            val argv = mutableListOf(when (action) { "add" -> "install"; "remove" -> "uninstall"; "list", "check" -> "ls"; else -> action })
            argv.addAll(listOf("--global=false", "--prefix", ".", "--cache", ".harnessmate-package-cache/npm", "--no-audit", "--no-fund"))
            if (mutating) argv.add("--ignore-scripts=${!args.optBoolean("allow_scripts", false)}")
            if (installing) {
                argv.addAll(listOf("--registry", selected.first))
                if (!network) argv.add("--offline")
                if (action == "add") argv.addAll(listOf("--save-exact", if (args.optBoolean("dev", false)) "--save-dev" else "--save-prod"))
            }
            if (action == "list") argv.addAll(listOf("--json", "--depth=0"))
            if (action == "check") argv.addAll(listOf("--json", "--all"))
            if (packages.isNotEmpty()) argv.addAll(listOf("--") + packages)
            command("npm", argv, env = mapOf("NPM_CONFIG_USERCONFIG" to "/dev/null"))
        } else {
            if (installing) command("python3", listOf("-m", "venv", "--copies", VENV), false)
            val argv = mutableListOf("-m", "pip", "--isolated", "--disable-pip-version-check", "--no-input",
                when (action) { "add" -> "install"; "remove" -> "uninstall"; else -> action })
            if (installing) {
                argv.addAll(listOf("--cache-dir", "$VENV/.cache/pip"))
                if (network) argv.addAll(listOf("--index-url", selected.first)) else argv.add("--no-index")
                if (wheelhouse.isNotEmpty()) argv.addAll(listOf("--find-links", "./$wheelhouse"))
                if (args.optBoolean("wheel_only", false)) argv.add("--only-binary=:all:")
                if (action == "install") {
                    if (args.optBoolean("require_hashes", true)) argv.add("--require-hashes")
                    argv.addAll(listOf("-r", "./$requirements"))
                }
            }
            if (action == "remove") argv.add("--yes")
            if (action == "list") argv.add("--format=json")
            if (action == "freeze") argv.add("--all")
            argv.addAll(packages)
            command("./$VENV/bin/python", argv, env = mapOf("PIP_CONFIG_FILE" to "/dev/null", "PYTHONNOUSERSITE" to "1"))
        }
        commands.forEach(ShellPolicy::validateRequest)
        return ProjectPackagePlan(manager, action, projectPath, domains, commands, mutating)
    }
}
