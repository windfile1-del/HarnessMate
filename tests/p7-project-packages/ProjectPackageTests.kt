package dev.wind.agent.packages

import dev.wind.agent.CancellationToken
import dev.wind.agent.capability.*
import dev.wind.agent.project.MountMode
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.WorkspaceMount
import dev.wind.agent.shell.ShellPolicy
import dev.wind.agent.shell.ShellRequest
import dev.wind.agent.shell.ShellResult
import dev.wind.agent.shell.mirror.WorkspaceIgnorePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host boundary and functional regressions for ProjectPackageCommands, ProjectPackageRunner,
 * WorkspaceIgnorePolicy, and CapabilityResolver project_packages resolution.
 */
object ProjectPackageTests {
    private var assertions = 0

    private fun check(condition: Boolean, message: String) {
        assertions++
        if (!condition) {
            throw AssertionError("ASSERTION_FAILED: " + message)
        }
    }

    private fun expectFailure(expectedMessageSubstring: String, block: () -> Unit) {
        assertions++
        val error = runCatching(block).exceptionOrNull()
            ?: throw AssertionError("EXPECTED_EXCEPTION_NOT_THROWN: expected '" + expectedMessageSubstring + "'")
        val msg = error.message ?: ""
        if (!msg.contains(expectedMessageSubstring)) {
            throw AssertionError("UNEXPECTED_EXCEPTION_MESSAGE: expected substring '" + expectedMessageSubstring + "' but got '" + msg + "'", error)
        }
    }

    private fun npmArgs(
        action: String,
        projectPath: String = "execution://main/subdir",
        packages: List<String>? = null,
        network: Boolean? = null,
        registryUrl: String? = null,
        networkDomains: List<String>? = null,
        allowScripts: Boolean? = null,
        dev: Boolean? = null,
        timeoutSeconds: Long? = null
    ): JSONObject = JSONObject().apply {
        put("manager", "npm")
        put("action", action)
        put("project_path", projectPath)
        if (packages != null) put("packages", JSONArray(packages))
        if (network != null) put("network", network)
        if (registryUrl != null) put("registry_url", registryUrl)
        if (networkDomains != null) put("network_domains", JSONArray(networkDomains))
        if (allowScripts != null) put("allow_scripts", allowScripts)
        if (dev != null) put("dev", dev)
        if (timeoutSeconds != null) put("timeout_seconds", timeoutSeconds)
    }

    private fun pipArgs(
        action: String,
        projectPath: String = "execution://main/subdir",
        packages: List<String>? = null,
        requirementsPath: String? = null,
        network: Boolean? = null,
        registryUrl: String? = null,
        networkDomains: List<String>? = null,
        requireHashes: Boolean? = null,
        wheelOnly: Boolean? = null,
        wheelhouse: String? = null,
        timeoutSeconds: Long? = null
    ): JSONObject = JSONObject().apply {
        put("manager", "pip")
        put("action", action)
        put("project_path", projectPath)
        if (packages != null) put("packages", JSONArray(packages))
        if (requirementsPath != null) put("requirements_path", requirementsPath)
        if (network != null) put("network", network)
        if (registryUrl != null) put("registry_url", registryUrl)
        if (networkDomains != null) put("network_domains", JSONArray(networkDomains))
        if (requireHashes != null) put("require_hashes", requireHashes)
        if (wheelOnly != null) put("wheel_only", wheelOnly)
        if (wheelhouse != null) put("wheelhouse", wheelhouse)
        if (timeoutSeconds != null) put("timeout_seconds", timeoutSeconds)
    }

    // 1. Subdirectory cwd & path preservation
    private fun testSubdirectoryCwd() {
        val path1 = "execution://main/backend/sub"
        val plan1 = ProjectPackageCommands.plan(npmArgs("install", projectPath = path1))
        check(plan1.projectPath == path1, "npm install projectPath mismatch: " + plan1.projectPath)
        check(plan1.commands.all { it.cwd == path1 }, "npm install commands cwd mismatch")

        val path2 = "execution://workspace-app/deep/nested/pkg"
        val plan2 = ProjectPackageCommands.plan(pipArgs("install", projectPath = path2, requirementsPath = "req.txt"))
        check(plan2.projectPath == path2, "pip install projectPath mismatch: " + plan2.projectPath)
        check(plan2.commands.all { it.cwd == path2 }, "pip install commands cwd mismatch")
    }

    // 2. Path validation: traversal, missing alias, illegal characters
    private fun testPathValidation() {
        expectFailure("PACKAGE_EXECUTION_PATH_REQUIRED") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "workspace://main/sub"))
        }
        expectFailure("PACKAGE_EXECUTION_PATH_REQUIRED") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "/local/path/main"))
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "execution://main/../escaped"))
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "execution://main/-bad"))
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "execution://main/foo%2ebar"))
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "execution://main/foo:bar"))
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "execution://main/foo\\bar"))
        }
        expectFailure("PACKAGE_MOUNT_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", projectPath = "execution://_invalid_alias/sub"))
        }
    }

    // 3. Default domains and custom index
    private fun testDefaultDomainsAndCustomIndex() {
        val npmDefault = ProjectPackageCommands.plan(npmArgs("install"))
        check(npmDefault.networkDomains == setOf("registry.npmjs.org"), "npm default domains should be registry.npmjs.org")
        check(npmDefault.commands.any { it.args.contains("--registry") && it.args.contains("https://registry.npmjs.org/") }, "npm registry arg missing")

        val npmCustom = ProjectPackageCommands.plan(npmArgs("install", registryUrl = "https://npm.pkg.github.com/"))
        check(npmCustom.networkDomains == setOf("npm.pkg.github.com"), "npm custom domain mismatch")
        check(npmCustom.commands.any { it.args.contains("https://npm.pkg.github.com/") }, "npm custom registry arg missing")

        val pipDefault = ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "req.txt"))
        check(pipDefault.networkDomains == setOf("pypi.org", "files.pythonhosted.org"), "pip default domains must include pypi.org and files.pythonhosted.org")
        check(pipDefault.commands.any { it.args.contains("--index-url") && it.args.contains("https://pypi.org/simple") }, "pip index-url arg missing")

        val pipCustom = ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "req.txt", registryUrl = "https://pypi.tuna.tsinghua.edu.cn/simple"))
        check(pipCustom.networkDomains == setOf("pypi.tuna.tsinghua.edu.cn"), "pip custom domain mismatch")
        check(pipCustom.commands.any { it.args.contains("https://pypi.tuna.tsinghua.edu.cn/simple") }, "pip custom index arg missing")

        val pipCustomWithExtra = ProjectPackageCommands.plan(pipArgs(
            "install", requirementsPath = "req.txt",
            registryUrl = "https://mirror.example.com/pypi",
            networkDomains = listOf("wheels.extra.org", "cdn.example.com")
        ))
        check(pipCustomWithExtra.networkDomains == setOf("mirror.example.com", "wheels.extra.org", "cdn.example.com"), "pip extra domains should be appended")
    }

    // 4. Registry URL validation: non-HTTPS, userinfo, query, fragment
    private fun testRegistryUrlValidation() {
        expectFailure("PACKAGE_REGISTRY_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", registryUrl = "http://registry.npmjs.org/"))
        }
        expectFailure("PACKAGE_REGISTRY_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", registryUrl = "https://user:pass@registry.npmjs.org/"))
        }
        expectFailure("PACKAGE_REGISTRY_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", registryUrl = "https://registry.npmjs.org/?query=1"))
        }
        expectFailure("PACKAGE_REGISTRY_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", registryUrl = "https://registry.npmjs.org/#frag"))
        }
        expectFailure("PACKAGE_REGISTRY_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", registryUrl = "https://registry.npmjs.org:8080/"))
        }
        expectFailure("PACKAGE_REGISTRY_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", registryUrl = "https://registry.npmjs.org/../escape"))
        }
        expectFailure("PACKAGE_REGISTRY_ACTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("list", registryUrl = "https://registry.npmjs.org/"))
        }
        expectFailure("PACKAGE_REGISTRY_ACTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("freeze", registryUrl = "https://pypi.org/simple"))
        }
    }

    // 5. Offline & Network disallowed on read/remove
    private fun testOfflineAndNetworkDisallowed() {
        val npmOffline = ProjectPackageCommands.plan(npmArgs("install", network = false))
        check(npmOffline.networkDomains.isEmpty(), "offline npm should have empty networkDomains")
        check(npmOffline.commands.all { !it.network }, "offline npm commands should have network=false")
        check(npmOffline.commands.any { it.args.contains("--offline") }, "offline npm should include --offline")

        val pipOffline = ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "requirements.txt", network = false))
        check(pipOffline.networkDomains.isEmpty(), "offline pip should have empty networkDomains")
        check(pipOffline.commands.all { !it.network }, "offline pip commands should have network=false")
        check(pipOffline.commands.any { it.args.contains("--no-index") }, "offline pip should include --no-index")

        expectFailure("PACKAGE_ACTION_NETWORK_INVALID") {
            ProjectPackageCommands.plan(npmArgs("list", network = true))
        }
        expectFailure("PACKAGE_ACTION_NETWORK_INVALID") {
            ProjectPackageCommands.plan(npmArgs("remove", packages = listOf("lodash"), network = true))
        }
        expectFailure("PACKAGE_ACTION_NETWORK_INVALID") {
            ProjectPackageCommands.plan(pipArgs("freeze", network = true))
        }
        expectFailure("PACKAGE_ACTION_NETWORK_INVALID") {
            ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests"), network = true))
        }
    }

    // 6. Explicit scripts: allow_scripts default false -> --ignore-scripts=true
    private fun testNpmAllowScripts() {
        val defaultScripts = ProjectPackageCommands.plan(npmArgs("install"))
        check(defaultScripts.commands.any { it.args.contains("--ignore-scripts=true") }, "default npm install should have --ignore-scripts=true")

        val falseScripts = ProjectPackageCommands.plan(npmArgs("install", allowScripts = false))
        check(falseScripts.commands.any { it.args.contains("--ignore-scripts=true") }, "allow_scripts=false should have --ignore-scripts=true")

        val trueScripts = ProjectPackageCommands.plan(npmArgs("install", allowScripts = true))
        check(trueScripts.commands.any { it.args.contains("--ignore-scripts=false") }, "allow_scripts=true should have --ignore-scripts=false")

        expectFailure("PACKAGE_SCRIPTS_OPTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "req.txt").apply { put("allow_scripts", true) })
        }
        expectFailure("PACKAGE_SCRIPTS_ACTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("list", allowScripts = true))
        }
    }

    // 7. Pinning rejection: latest, git, semver ranges, injection, traversal
    private fun testPinningRejections() {
        // npm valid
        val validNpm = ProjectPackageCommands.plan(npmArgs("add", packages = listOf("lodash@4.17.21", "@types/node@20.11.0", "pkg@1.0.0-rc.1")))
        check(validNpm.commands.any { it.args.contains("--save-exact") && it.args.contains("--save-prod") }, "npm add must save exact")

        val validNpmDev = ProjectPackageCommands.plan(npmArgs("add", packages = listOf("typescript@5.3.3"), dev = true))
        check(validNpmDev.commands.any { it.args.contains("--save-dev") }, "npm add with dev=true must use --save-dev")

        // npm invalid
        val invalidNpmSpecs = listOf(
            "lodash",
            "lodash@latest",
            "lodash@^4.17.21",
            "lodash@~4.17.21",
            "lodash@*",
            "git+https://github.com/lodash/lodash.git",
            "lodash@1.0.0; rm -rf /",
            "../bad@1.0.0",
            "@scope/../bad@1.0.0"
        )
        for (bad in invalidNpmSpecs) {
            expectFailure("PACKAGE_EXACT_VERSION_REQUIRED") {
                ProjectPackageCommands.plan(npmArgs("add", packages = listOf(bad)))
            }
        }

        // pip valid
        val validPip = ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0", "urllib3==2.0.7", "pkg==1.0.0b1")))
        check(validPip.commands.any { it.args.contains("requests==2.31.0") }, "pip add command must include package spec")

        // pip invalid
        val invalidPipSpecs = listOf(
            "requests",
            "requests>=2.31.0",
            "requests~=2.31.0",
            "requests!=2.0.0",
            "git+https://github.com/psf/requests.git",
            "requests==2.31.0; reboot",
            "../../bad==1.0.0"
        )
        for (bad in invalidPipSpecs) {
            expectFailure("PACKAGE_EXACT_VERSION_REQUIRED") {
                ProjectPackageCommands.plan(pipArgs("add", packages = listOf(bad)))
            }
        }

        // remove valid
        val validNpmRemove = ProjectPackageCommands.plan(npmArgs("remove", packages = listOf("lodash", "@types/node")))
        check(validNpmRemove.commands.any { it.args.contains("uninstall") && it.args.contains("lodash") }, "npm remove uninstall args missing")

        val validPipRemove = ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests", "urllib3")))
        check(validPipRemove.commands.any { it.args.contains("uninstall") && it.args.contains("--yes") && it.args.contains("requests") }, "pip remove args missing")

        // remove invalid: must not have version specifiers, traversal, or injection
        expectFailure("PACKAGE_NAME_INVALID") {
            ProjectPackageCommands.plan(npmArgs("remove", packages = listOf("lodash@4.17.21")))
        }
        expectFailure("PACKAGE_NAME_INVALID") {
            ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests==2.31.0")))
        }
        expectFailure("PACKAGE_NAME_INVALID") {
            ProjectPackageCommands.plan(npmArgs("remove", packages = listOf("../lodash")))
        }
        expectFailure("PACKAGE_NAME_INVALID") {
            ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests; rm -rf /")))
        }
    }

    // 8. Python venv creation & isolation (venv not global pip)
    private fun testVenvIsolation() {
        val pipAdd = ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0")))
        check(pipAdd.commands.size == 2, "pip add should produce exactly 2 commands (venv + pip install)")
        val venvCmd = pipAdd.commands[0]
        check(venvCmd.executable == "python3", "venv creation executable must be python3")
        check(venvCmd.args == listOf("-m", "venv", "--copies", ".harnessmate-venv"), "venv creation args mismatch: " + venvCmd.args)
        check(!venvCmd.network, "venv creation must not use network")

        val installCmd = pipAdd.commands[1]
        check(installCmd.executable == "./.harnessmate-venv/bin/python", "pip command must use venv python binary")
        check(installCmd.args.take(6) == listOf("-m", "pip", "--isolated", "--disable-pip-version-check", "--no-input", "install"), "pip isolated invocation mismatch")
        check(installCmd.env["PIP_CONFIG_FILE"] == "/dev/null", "PIP_CONFIG_FILE must be /dev/null")
        check(installCmd.env["PYTHONNOUSERSITE"] == "1", "PYTHONNOUSERSITE must be 1")

        // Read and remove actions must NOT create venv
        for (action in listOf("list", "check", "freeze")) {
            val plan = ProjectPackageCommands.plan(pipArgs(action))
            check(plan.commands.size == 1, "pip " + action + " should have exactly 1 command")
            check(plan.commands[0].executable == "./.harnessmate-venv/bin/python", "pip " + action + " executable mismatch")
            check(!plan.commands[0].network, "pip " + action + " must not use network")
        }
        val removePlan = ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests")))
        check(removePlan.commands.size == 1, "pip remove should have exactly 1 command (no venv creation)")
    }

    // 9. validationCheck semantics: !mutating for list/check/freeze, false for mutating actions
    private fun testValidationCheckClassification() {
        val mutatingPlans = listOf(
            ProjectPackageCommands.plan(npmArgs("install")),
            ProjectPackageCommands.plan(npmArgs("add", packages = listOf("lodash@4.17.21"))),
            ProjectPackageCommands.plan(npmArgs("ci")),
            ProjectPackageCommands.plan(npmArgs("remove", packages = listOf("lodash"))),
            ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "req.txt")),
            ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0"))),
            ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests")))
        )
        for (plan in mutatingPlans) {
            check(plan.mutating, "plan.mutating must be true for ${plan.manager} ${plan.action}")
            check(plan.commands.all { !it.validationCheck }, "validationCheck must be false for mutating action ${plan.manager} ${plan.action}")
        }

        val nonMutatingPlans = listOf(
            ProjectPackageCommands.plan(npmArgs("list")),
            ProjectPackageCommands.plan(npmArgs("check")),
            ProjectPackageCommands.plan(pipArgs("list")),
            ProjectPackageCommands.plan(pipArgs("check")),
            ProjectPackageCommands.plan(pipArgs("freeze"))
        )
        for (plan in nonMutatingPlans) {
            check(!plan.mutating, "plan.mutating must be false for ${plan.manager} ${plan.action}")
            check(plan.commands.all { it.validationCheck }, "validationCheck must be true (!mutating) for read action ${plan.manager} ${plan.action}")
        }
    }

    // 9. Incompatible parameter rejections
    private fun testIncompatibleParameters() {
        expectFailure("PACKAGE_ACTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("freeze"))
        }
        expectFailure("PACKAGE_ACTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("ci"))
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(pipArgs("install")) // missing requirements_path
        }
        expectFailure("PACKAGE_PATH_INVALID") {
            ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "../requirements.txt"))
        }
        expectFailure("PACKAGE_REQUIREMENTS_ACTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0"), requirementsPath = "req.txt"))
        }
        expectFailure("PACKAGE_REQUIREMENTS_ACTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install").apply { put("requirements_path", "req.txt") })
        }
        expectFailure("PACKAGE_PIP_OPTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install").apply { put("wheel_only", true) })
        }
        expectFailure("PACKAGE_PIP_OPTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install").apply { put("require_hashes", true) })
        }
        expectFailure("PACKAGE_HASHES_ACTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0"), requireHashes = true))
        }
        expectFailure("PACKAGE_WHEELS_ACTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("list", wheelOnly = true))
        }
        expectFailure("PACKAGE_WHEELHOUSE_ACTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("list", wheelhouse = "wheels"))
        }
        expectFailure("PACKAGE_DEV_OPTION_INVALID") {
            ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0")).apply { put("dev", true) })
        }
        expectFailure("PACKAGE_DEV_OPTION_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", dev = true))
        }
        expectFailure("PACKAGE_LIST_ACTION_MISMATCH") {
            ProjectPackageCommands.plan(npmArgs("add")) // missing packages
        }
        expectFailure("PACKAGE_LIST_ACTION_MISMATCH") {
            ProjectPackageCommands.plan(npmArgs("install", packages = listOf("lodash@4.17.21")))
        }
    }

    // 10. Timeout boundary
    private fun testTimeoutBoundary() {
        val defaultTimeout = ProjectPackageCommands.plan(npmArgs("install"))
        check(defaultTimeout.commands.all { it.timeoutMs == 300_000L }, "default timeout must be 300_000 ms")

        val minTimeout = ProjectPackageCommands.plan(npmArgs("install", timeoutSeconds = 1L))
        check(minTimeout.commands.all { it.timeoutMs == 1_000L }, "timeout=1s must be 1_000 ms")

        val maxTimeout = ProjectPackageCommands.plan(npmArgs("install", timeoutSeconds = 3600L))
        check(maxTimeout.commands.all { it.timeoutMs == 3600_000L }, "timeout=3600s must be 3600_000 ms")

        expectFailure("PACKAGE_TIMEOUT_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", timeoutSeconds = 0L))
        }
        expectFailure("PACKAGE_TIMEOUT_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", timeoutSeconds = -1L))
        }
        expectFailure("PACKAGE_TIMEOUT_INVALID") {
            ProjectPackageCommands.plan(npmArgs("install", timeoutSeconds = 3601L))
        }
    }

    // 11. ShellPolicy.validateRequest passes on all plan commands
    private fun testShellPolicyCompliance() {
        val plans = listOf(
            ProjectPackageCommands.plan(npmArgs("install")),
            ProjectPackageCommands.plan(npmArgs("add", packages = listOf("lodash@4.17.21"))),
            ProjectPackageCommands.plan(npmArgs("ci")),
            ProjectPackageCommands.plan(npmArgs("remove", packages = listOf("lodash"))),
            ProjectPackageCommands.plan(npmArgs("list")),
            ProjectPackageCommands.plan(npmArgs("check")),
            ProjectPackageCommands.plan(pipArgs("install", requirementsPath = "req.txt")),
            ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0"))),
            ProjectPackageCommands.plan(pipArgs("remove", packages = listOf("requests"))),
            ProjectPackageCommands.plan(pipArgs("list")),
            ProjectPackageCommands.plan(pipArgs("freeze")),
            ProjectPackageCommands.plan(pipArgs("check"))
        )
        for (plan in plans) {
            for (cmd in plan.commands) {
                // Should not throw any exception
                ShellPolicy.validateRequest(cmd)
            }
        }
    }

    // 12. ProjectPackageRunner stop logic (failure/timeout/cancel halts immediately, no retry)
    private fun testProjectPackageRunnerStopLogic() {
        val plan = ProjectPackageCommands.plan(pipArgs("add", packages = listOf("requests==2.31.0")))
        check(plan.commands.size == 2, "plan should have 2 commands")

        // A. Success scenario: both steps execute
        val executedA = mutableListOf<ShellRequest>()
        val resultA = ProjectPackageRunner.run(plan, CancellationToken()) { req ->
            executedA.add(req)
            ShellResult(exitCode = 0, stdout = "OK", stderr = "", elapsedMs = 50, diffSummary = "Execution ID: exec-1234")
        }
        check(executedA.size == 2, "all 2 steps should execute on success")
        check(resultA.getString("state") == "SUCCEEDED", "state must be SUCCEEDED")
        check(resultA.getString("execution_id") == "exec-1234", "execution_id mismatch")
        check(!resultA.getBoolean("automatic_retry"), "automatic_retry must be false")
        check(!resultA.getBoolean("canonical_workspace_modified"), "canonical_workspace_modified must be false")
        check(resultA.getJSONArray("steps").length() == 2, "steps length must be 2")
        check(resultA.getString("next_step").contains("Review the mirror diff"), "next_step recommendation mismatch")

        // B. Step 1 failure (exitCode = 1): step 2 must NEVER run
        val executedB = mutableListOf<ShellRequest>()
        val resultB = ProjectPackageRunner.run(plan, CancellationToken()) { req ->
            executedB.add(req)
            ShellResult(exitCode = 1, stdout = "", stderr = "venv creation failed", elapsedMs = 30)
        }
        check(executedB.size == 1, "step 2 must not run when step 1 fails")
        check(resultB.getString("state") == "FAILED", "state must be FAILED")
        check(resultB.getJSONArray("steps").length() == 1, "steps length must be 1")
        check(resultB.getString("next_step").contains("Inspect the failed step"), "next_step recommendation mismatch")

        // C. Step 1 timeout: halts immediately
        val executedC = mutableListOf<ShellRequest>()
        val resultC = ProjectPackageRunner.run(plan, CancellationToken()) { req ->
            executedC.add(req)
            ShellResult(exitCode = null, stdout = "", stderr = "", elapsedMs = 300000, timedOut = true)
        }
        check(executedC.size == 1, "step 2 must not run on timeout")
        check(resultC.getString("state") == "TIMED_OUT", "state must be TIMED_OUT")

        // D. Step 1 cancelled in result: halts immediately
        val executedD = mutableListOf<ShellRequest>()
        val resultD = ProjectPackageRunner.run(plan, CancellationToken()) { req ->
            executedD.add(req)
            ShellResult(exitCode = null, stdout = "", stderr = "", elapsedMs = 10, cancelled = true)
        }
        check(executedD.size == 1, "step 2 must not run on cancellation")
        check(resultD.getString("state") == "CANCELLED", "state must be CANCELLED")

        // E. Pre-cancelled CancellationToken: halts before step 1
        val cancelledToken = CancellationToken().apply { cancel() }
        var executedE = 0
        try {
            ProjectPackageRunner.run(plan, cancelledToken) { _ ->
                executedE++
                ShellResult(exitCode = 0, stdout = "OK", stderr = "", elapsedMs = 10)
            }
            throw AssertionError("CancellationToken throwIfCancelled should have thrown")
        } catch (_: Exception) {
            check(executedE == 0, "No steps should execute when token is already cancelled")
        }
    }

    // 13. WorkspaceIgnorePolicy verifies managed venv and npm cache ignored, lockfiles kept
    private fun testWorkspaceIgnorePolicy() {
        // Managed directories ignored
        check(WorkspaceIgnorePolicy.isIgnored(".harnessmate-venv"), ".harnessmate-venv must be ignored")
        check(WorkspaceIgnorePolicy.isIgnored(".harnessmate-venv/bin/python"), ".harnessmate-venv/bin/python must be ignored")
        check(WorkspaceIgnorePolicy.isIgnored("subdir/.harnessmate-venv/lib/site-packages"), "nested .harnessmate-venv must be ignored")
        check(WorkspaceIgnorePolicy.isIgnored(".harnessmate-package-cache"), ".harnessmate-package-cache must be ignored")
        check(WorkspaceIgnorePolicy.isIgnored(".harnessmate-package-cache/npm"), ".harnessmate-package-cache/npm must be ignored")
        check(WorkspaceIgnorePolicy.isIgnored("project/.harnessmate-package-cache/npm/cache.tgz"), "nested .harnessmate-package-cache must be ignored")

        // Dependency declarations and lockfiles must NOT be ignored
        check(!WorkspaceIgnorePolicy.isIgnored("package.json"), "package.json must not be ignored")
        check(!WorkspaceIgnorePolicy.isIgnored("package-lock.json"), "package-lock.json must not be ignored")
        check(!WorkspaceIgnorePolicy.isIgnored("requirements.txt"), "requirements.txt must not be ignored")
        check(!WorkspaceIgnorePolicy.isIgnored("requirements.lock"), "requirements.lock must not be ignored")
        check(!WorkspaceIgnorePolicy.isIgnored("backend/sub/package.json"), "backend/sub/package.json must not be ignored")
        check(!WorkspaceIgnorePolicy.isIgnored("backend/sub/requirements.lock"), "backend/sub/requirements.lock must not be ignored")
    }

    // 14. CapabilityResolver project_packages fixture tests
    private fun testCapabilityResolverFixture() {
        val mainMount = WorkspaceMount("m-main", "main", "content://main", "Main", MountMode.READ_WRITE, "main", true)
        val project = VegaProject("p-test", "TestProject", mainMount.id, listOf(mainMount))
        val resolver = CapabilityResolver { id -> if (id == project.id) project else null }
        val task = TaskContext("task-1", project.id, project.generation, CapabilityMode.ACCEPT, "task-1")

        // A. Online npm install: Composite(Execution, Network(registry.npmjs.org))
        val reqNpm = resolver.resolve(task, "project_packages", npmArgs("install", projectPath = "execution://main/app"))
        check(reqNpm is CapabilityRequirement.Composite, "npm install should require Composite capability")
        val compNpm = reqNpm as CapabilityRequirement.Composite
        check(compNpm.requirements.any { it is CapabilityRequirement.Execution }, "Composite must include Execution")
        val netNpm = compNpm.requirements.firstOrNull { it is CapabilityRequirement.Network } as? CapabilityRequirement.Network
        check(netNpm != null && netNpm.domains == setOf("registry.npmjs.org"), "Network domains mismatch for npm: " + netNpm?.domains)

        // B. Custom pip index: Network domain derived
        val reqPip = resolver.resolve(task, "project_packages", pipArgs(
            "install", projectPath = "execution://main/sub", requirementsPath = "req.txt",
            registryUrl = "https://pypi.tuna.tsinghua.edu.cn/simple"
        ))
        check(reqPip is CapabilityRequirement.Composite, "pip install should require Composite capability")
        val compPip = reqPip as CapabilityRequirement.Composite
        val netPip = compPip.requirements.firstOrNull { it is CapabilityRequirement.Network } as? CapabilityRequirement.Network
        check(netPip != null && netPip.domains == setOf("pypi.tuna.tsinghua.edu.cn"), "Network domains mismatch for pip custom: " + netPip?.domains)

        // C. Offline install: Execution ONLY (no Network)
        val reqOffline = resolver.resolve(task, "project_packages", npmArgs("install", projectPath = "execution://main/app", network = false))
        check(reqOffline is CapabilityRequirement.Execution, "offline install should require Execution capability only, got " + reqOffline)

        // D. Read/remove actions: Execution ONLY (no Network)
        val reqList = resolver.resolve(task, "project_packages", npmArgs("list", projectPath = "execution://main/app"))
        check(reqList is CapabilityRequirement.Execution, "npm list should require Execution capability only, got " + reqList)

        val reqRemove = resolver.resolve(task, "project_packages", npmArgs("remove", projectPath = "execution://main/app", packages = listOf("lodash")))
        check(reqRemove is CapabilityRequirement.Execution, "npm remove should require Execution capability only, got " + reqRemove)

        // E. Unknown mount: Denied with PACKAGE_MOUNT_NOT_IN_PROJECT
        val reqUnknownMount = resolver.resolve(task, "project_packages", npmArgs("install", projectPath = "execution://unknown-mount/app"))
        check(reqUnknownMount is CapabilityRequirement.Denied, "unknown mount should be Denied")
        check((reqUnknownMount as CapabilityRequirement.Denied).reason == "PACKAGE_MOUNT_NOT_IN_PROJECT", "Denied reason mismatch")

        // F. Invalid arguments: Denied with PACKAGE_ARGUMENT_INVALID
        val reqInvalidArgs = resolver.resolve(task, "project_packages", npmArgs("add", projectPath = "execution://main/app", packages = listOf("bad@latest")))
        check(reqInvalidArgs is CapabilityRequirement.Denied, "invalid argument should be Denied")
        check((reqInvalidArgs as CapabilityRequirement.Denied).reason.startsWith("PACKAGE_ARGUMENT_INVALID"), "Denied reason mismatch")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("Starting ProjectPackageTests...")
        testSubdirectoryCwd()
        testPathValidation()
        testDefaultDomainsAndCustomIndex()
        testRegistryUrlValidation()
        testOfflineAndNetworkDisallowed()
        testNpmAllowScripts()
        testPinningRejections()
        testVenvIsolation()
        testValidationCheckClassification()
        testIncompatibleParameters()
        testTimeoutBoundary()
        testShellPolicyCompliance()
        testProjectPackageRunnerStopLogic()
        testWorkspaceIgnorePolicy()
        testCapabilityResolverFixture()
        println("ProjectPackageTests PASS (" + assertions + " assertions verified)")
    }
}
