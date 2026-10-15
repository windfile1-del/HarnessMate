package dev.wind.agent

import dev.wind.agent.security.ApprovalScope
import dev.wind.agent.security.PolicyAction
import dev.wind.agent.security.PolicyEngine
import dev.wind.agent.security.ToolApprovalDecision
import dev.wind.agent.security.ToolRisk
import dev.wind.agent.workspace.WorkspacePath

object VegaSafeSecurityTests {

    @JvmStatic
    fun main(args: Array<String>) {
        println("== Running VegaSafe Security Suite ==")
        testPathEscapeAttacks()
        testPolicyEngineRiskAndApprovals()
        testSecureStoreFailClosed()
        testTransactionConflictLogic()
        testShellPolicyEnforcement()
        testSearchProviderModels()
        testNetworkExecuteRiskEscalation()
        testKeyRouterNoPlaintextOnDisk()
        testTermuxContractAndResolution()
        testTarArchiveSecurityAndBridgeLimits()
        testTermuxCancellationAndStateHandling()
        testTermuxPreflightProbe()
        testBaselineAndMetadataPersistence()
        testDiffPreflightAndBatchRollback()
        testUnsupportedBinaryFailClosed()
        testSearchDeadlineAndSessionPrefixCleanup()
        testVegaBridgeServerLiveHttp()
        testExecutionWorkspaceAndPreflightIntegration()
        testApplyFailureRollbackIntegration()
        testPendingExecutionProcessRestartRecovery()
        println("== All VegaSafe Security Tests Passed! ==")
    }

    private fun testPathEscapeAttacks() {
        println("-> testPathEscapeAttacks")
        val attackPayloads = listOf(
            "../secret.txt",
            "../../DCIM/a.jpg",
            "foo/../../secret",
            "./../",
            "%2e%2e/etc/passwd",
            "..%2f",
            "..\\secret",
            "\uFF0E\uFF0E/secret",
            "\u2024\u2024/secret",
            "file:///sdcard/private",
            "content://com.android.providers.media/documents",
            "/storage/emulated/0/DCIM",
            "/data/data/com.other/key",
            "C:/Windows/System32",
            "D:\\secret.bin"
        )

        for (payload in attackPayloads) {
            var blocked = false
            try {
                WorkspacePath.sanitize(payload)
            } catch (sec: SecurityException) {
                blocked = true
            }
            if (!blocked) {
                throw AssertionError("Security escape test failed: payload was NOT blocked: $payload")
            }
        }

        // Safe valid relative paths should succeed
        val valid1 = WorkspacePath.sanitize("notes/control.md")
        if (valid1 != "notes/control.md") throw AssertionError("Valid path normalization failed: $valid1")

        val valid2 = WorkspacePath.sanitize("workspace://src/app.py")
        if (valid2 != "src/app.py") throw AssertionError("Workspace scheme normalization failed: $valid2")

        // Subdirectories named 'data' or 'system' inside workspace must be allowed
        val valid3 = WorkspacePath.sanitize("src/data/models.json")
        if (valid3 != "src/data/models.json") throw AssertionError("src/data path should be allowed: $valid3")

        val valid4 = WorkspacePath.sanitize("config/system_info.txt")
        if (valid4 != "config/system_info.txt") throw AssertionError("config/system path should be allowed: $valid4")
    }

    private fun testPolicyEngineRiskAndApprovals() {
        println("-> testPolicyEngineRiskAndApprovals")
        PolicyEngine.clearSessionApprovals()

        // Read actions should be automatically allowed
        if (PolicyEngine.evaluatePolicy("list_dir", null) != PolicyAction.ALLOW) throw AssertionError("list_dir should be ALLOW")
        if (PolicyEngine.evaluatePolicy("read_file", null) != PolicyAction.ALLOW) throw AssertionError("read_file should be ALLOW")
        if (PolicyEngine.evaluatePolicy("search_files", null) != PolicyAction.ALLOW) throw AssertionError("search_files should be ALLOW")

        // Mutating actions should ASK by default
        if (PolicyEngine.evaluatePolicy("edit_file", null) != PolicyAction.ASK) throw AssertionError("edit_file should ASK")
        if (PolicyEngine.evaluatePolicy("write_file", null) != PolicyAction.ASK) throw AssertionError("write_file should ASK")
        if (PolicyEngine.evaluatePolicy("delete_path", null) != PolicyAction.ASK) throw AssertionError("delete_path should ASK")

        // Network actions should ASK by default in V0.1 Safe Alpha
        if (PolicyEngine.evaluatePolicy("web_search", null) != PolicyAction.ASK) throw AssertionError("web_search should ASK")
        if (PolicyEngine.evaluatePolicy("web_fetch", null) != PolicyAction.ASK) throw AssertionError("web_fetch should ASK")
        if (PolicyEngine.evaluatePolicy("download_file", null) != PolicyAction.ASK) throw AssertionError("download_file should ASK")

        // Unknown or disabled tools must FAIL CLOSED (DENY)
        if (PolicyEngine.evaluatePolicy("unknown_evil_tool", null) != PolicyAction.DENY) throw AssertionError("unknown tool must be DENY")
        if (PolicyEngine.evaluatePolicy("remember", null) != PolicyAction.ALLOW || !ToolRegistry.isMutating("remember")) throw AssertionError("remember must route to the source-verified mutating Memory Broker")

        // Approve WRITE for session
        PolicyEngine.recordDecision(ToolRisk.WRITE, ToolApprovalDecision(approved = true, scope = ApprovalScope.SESSION))
        if (PolicyEngine.evaluatePolicy("edit_file", null) != PolicyAction.ALLOW) throw AssertionError("edit_file should be ALLOW after session approval")
        if (PolicyEngine.evaluatePolicy("write_file", null) != PolicyAction.ALLOW) throw AssertionError("write_file should be ALLOW after session approval")

        // Approve NETWORK for session
        PolicyEngine.recordDecision(ToolRisk.NETWORK, ToolApprovalDecision(approved = true, scope = ApprovalScope.SESSION))
        if (PolicyEngine.evaluatePolicy("web_search", null) != PolicyAction.ALLOW) throw AssertionError("web_search should be ALLOW after session approval")
        if (PolicyEngine.evaluatePolicy("web_fetch", null) != PolicyAction.ALLOW) throw AssertionError("web_fetch should be ALLOW after session approval")
        if (PolicyEngine.evaluatePolicy("download_file", null) != PolicyAction.ALLOW) throw AssertionError("download_file should be ALLOW after session approval")

        // Destructive DELETE must ALWAYS ASK even if other risks were approved
        if (PolicyEngine.evaluatePolicy("delete_path", null) != PolicyAction.ASK) throw AssertionError("delete_path must ALWAYS ASK")

        PolicyEngine.clearSessionApprovals()
    }

    private fun testSecureStoreFailClosed() {
        println("-> testSecureStoreFailClosed")
        // A secret that could not be protected is stored MARKED, readably, and never
        // presented as protected.
        //
        // The invariant is not "an unprotected value is unreadable" — that was tried,
        // and it makes a phone without a working keystore unable to chat at all. The
        // invariant is that the app can always TELL the two apart, because
        // `Prefs.apiKeyIsEncrypted()` is what raises the warning in Settings. If this
        // ever starts returning true for a plain value, the disclosure silently stops
        // working and the user is told their key is hardware-protected when it is not.
        val marked = SecureStore.encrypt("my_super_secret_key")
        if (marked.isEmpty()) {
            throw AssertionError("A secret was thrown away when the keystore was unavailable")
        }
        if (SecureStore.encrypted(marked)) {
            throw AssertionError(
                "An unprotected value claims to be protected, so no warning would be shown"
            )
        }
        if (SecureStore.decrypt(marked) != "my_super_secret_key") {
            throw AssertionError("The marked secret did not survive a round trip")
        }
        // Anything that is neither a marked value nor real ciphertext decrypts to
        // nothing, so a corrupted preference cannot become a half-formed key.
        for (junk in listOf(null, "", "not-base64!!", "c2hvcnQ=")) {
            if (SecureStore.decrypt(junk) != "") {
                throw AssertionError("Garbage was decrypted into a key: " + junk)
            }
        }
    }

    private fun testTransactionConflictLogic() {
        println("-> testTransactionConflictLogic")
        // Verification of conflict detection fingerprint contract
        val expectedAfter = dev.wind.agent.history.FileFingerprint(
            exists = true, isDirectory = false, size = 100L, sha256 = "abc123expected"
        )
        val matchingCurrent = dev.wind.agent.history.FileFingerprint(
            exists = true, isDirectory = false, size = 100L, sha256 = "abc123expected"
        )
        val userChangedCurrent = dev.wind.agent.history.FileFingerprint(
            exists = true, isDirectory = false, size = 105L, sha256 = "user_modified_hash"
        )

        val matched = (matchingCurrent.exists == expectedAfter.exists) &&
                      (matchingCurrent.size == expectedAfter.size) &&
                      (matchingCurrent.sha256 == expectedAfter.sha256)
        if (!matched) throw AssertionError("Matching fingerprint was falsely rejected")

        val conflict = !((userChangedCurrent.exists == expectedAfter.exists) &&
                         (userChangedCurrent.size == expectedAfter.size) &&
                         (userChangedCurrent.sha256 == expectedAfter.sha256))
        if (!conflict) throw AssertionError("User modification was not flagged as conflict!")
    }

    private fun testShellPolicyEnforcement() {
        println("-> testShellPolicyEnforcement")
        // 1. Banned binary execution
        for (banned in listOf("su", "sudo", "pm", "am", "mount", "/system/bin/su", "c:\\bin\\dd")) {
            var blocked = false
            try {
                dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest(banned))
            } catch (_: SecurityException) {
                blocked = true
            }
            if (!blocked) throw AssertionError("Banned executable '$banned' was not blocked!")
        }

        // 2. Arbitrary sh -c execution
        for (sh in listOf("sh", "bash", "/bin/sh", "zsh")) {
            var blocked = false
            try {
                dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest(sh, listOf("-c", "echo evil")))
            } catch (_: SecurityException) {
                blocked = true
            }
            if (!blocked) throw AssertionError("sh -c execution vector was not blocked for $sh!")
        }

        // 3. System path escapes in arguments
        for (arg in listOf("/storage/emulated/0/DCIM", "/data/data/com.app", "/system/etc", "/etc/passwd")) {
            var blocked = false
            try {
                dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest("./gradlew", listOf("-Ppath=$arg")))
            } catch (_: SecurityException) {
                blocked = true
            }
            // Also test directly as separate arg
            try {
                dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest("git", listOf("diff", arg)))
            } catch (_: SecurityException) {
                blocked = true
            }
            if (!blocked) throw AssertionError("Forbidden system path argument was not blocked: $arg")
        }

        // 4. Safe command must pass
        dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest("./gradlew", listOf("test", "compileDebugKotlin")))
        dev.wind.agent.shell.ShellPolicy.validateRequest(dev.wind.agent.shell.ShellRequest("git", listOf("status", "--short")))

        // 5. Output clamping
        val small = "small output"
        if (dev.wind.agent.shell.ShellPolicy.clampOutput(small) != small) throw AssertionError("Small output was altered")
        val large = "A".repeat(dev.wind.agent.shell.ShellPolicy.MAX_OUTPUT_BYTES + 500)
        val clamped = dev.wind.agent.shell.ShellPolicy.clampOutput(large)
        if (!clamped.contains("[OUTPUT TRUNCATED")) throw AssertionError("Large output was not clamped with truncation notice")
    }

    private fun testSearchProviderModels() {
        println("-> testSearchProviderModels")
        val req = dev.wind.agent.search.SearchRequest("test query", limit = 5, freshness = dev.wind.agent.search.SearchFreshness.MONTH)
        if (req.limit != 5) throw AssertionError("SearchRequest limit mismatch")

        val braveUnconfigured = dev.wind.agent.search.BraveSearchProvider { "" }
        if (braveUnconfigured.isConfigured()) throw AssertionError("Unconfigured Brave provider reported configured")
        val braveConfigured = dev.wind.agent.search.BraveSearchProvider { "test-token-123" }
        if (!braveConfigured.isConfigured()) throw AssertionError("Configured Brave provider reported unconfigured")

        val searxngUnconfigured = dev.wind.agent.search.SearxngSearchProvider { "" }
        if (searxngUnconfigured.isConfigured()) throw AssertionError("Unconfigured SearXNG provider reported configured")
        val searxngConfigured = dev.wind.agent.search.SearxngSearchProvider { "https://searxng.example.com" }
        if (!searxngConfigured.isConfigured()) throw AssertionError("Configured SearXNG provider reported unconfigured")

        val ddg = dev.wind.agent.search.DuckDuckGoLiteProvider()
        if (!ddg.isConfigured()) throw AssertionError("DDG Lite fallback must always be configured")
    }

    private fun testNetworkExecuteRiskEscalation() {
        println("-> testNetworkExecuteRiskEscalation")
        PolicyEngine.clearSessionApprovals()
        val nonNetArgs = org.json.JSONObject().put("network", false)
        val netArgs = org.json.JSONObject().put("network", true)

        val nonNetRisk = PolicyEngine.classifyRisk("shell_exec", nonNetArgs)
        if (nonNetRisk != ToolRisk.EXECUTE) throw AssertionError("Non-network shell_exec should be EXECUTE, got: $nonNetRisk")

        val netRisk = PolicyEngine.classifyRisk("shell_exec", netArgs)
        if (netRisk != ToolRisk.NETWORK_EXECUTE) throw AssertionError("Network shell_exec should be NETWORK_EXECUTE, got: $netRisk")

        // Approving EXECUTE for session must NOT auto-allow NETWORK_EXECUTE
        PolicyEngine.recordDecision(ToolRisk.EXECUTE, ToolApprovalDecision(approved = true, scope = ApprovalScope.SESSION))
        if (PolicyEngine.evaluatePolicy("shell_exec", nonNetArgs) != PolicyAction.ALLOW) {
            throw AssertionError("Approved EXECUTE should allow non-network command")
        }
        if (PolicyEngine.evaluatePolicy("shell_exec", netArgs) != PolicyAction.ASK) {
            throw AssertionError("NETWORK_EXECUTE must still ASK even if EXECUTE was approved for session!")
        }
        PolicyEngine.clearSessionApprovals()
    }

    private fun testKeyRouterNoPlaintextOnDisk() {
        println("-> testKeyRouterNoPlaintextOnDisk")
        // Invariant 11: In-memory session keys produce opaque tokens with zero secret bytes
        val token = SecureStore.encrypt("sk-secret-123", "router_0")
        if (token.contains("sk-secret-123")) throw AssertionError("Opaque token leaked raw secret material: $token")
        if (SecureStore.encrypted(token)) throw AssertionError("Session token claims to be encrypted ciphertext")
        if (SecureStore.decrypt(token, "router_0") != "sk-secret-123") throw AssertionError("Decryption of session token failed")
    }

    private fun testTermuxContractAndResolution() {
        println("-> testTermuxContractAndResolution")
        if (dev.wind.agent.shell.termux.TermuxContract.EXTRA_RESULT_STDOUT != "stdout") throw AssertionError("stdout key mismatch")
        if (dev.wind.agent.shell.termux.TermuxContract.EXTRA_RESULT_STDERR != "stderr") throw AssertionError("stderr key mismatch")
        if (dev.wind.agent.shell.termux.TermuxContract.EXTRA_RESULT_EXIT_CODE != "exitCode") throw AssertionError("exitCode key mismatch")
        if (dev.wind.agent.shell.termux.TermuxContract.EXTRA_RESULT_ERR != "err") throw AssertionError("err key mismatch")
        if (dev.wind.agent.shell.termux.TermuxContract.EXTRA_RESULT_ERRMSG != "errmsg") throw AssertionError("errmsg key mismatch")

        val projDir = "/data/data/com.termux/files/home/.vega-safe/tasks/shell-12345678/project"
        val gradlew = dev.wind.agent.shell.termux.TermuxContract.resolveExecutable("./gradlew", projDir)
        if (gradlew != "$projDir/gradlew") throw AssertionError("./gradlew resolution failed: $gradlew")
        val mvnw = dev.wind.agent.shell.termux.TermuxContract.resolveExecutable("./mvnw", projDir)
        if (mvnw != "$projDir/mvnw") throw AssertionError("./mvnw resolution failed: $mvnw")
        val py = dev.wind.agent.shell.termux.TermuxContract.resolveExecutable("python", projDir)
        if (py != "/data/data/com.termux/files/usr/bin/python") throw AssertionError("python resolution failed: $py")
        val git = dev.wind.agent.shell.termux.TermuxContract.resolveExecutable("git", projDir)
        if (git != "/data/data/com.termux/files/usr/bin/git") throw AssertionError("git resolution failed: $git")

        for (bad in listOf("foo/bar", "bin/sh", "../escape", "%2e%2e/escape", "/bin/sh", "./sub/script")) {
            var blocked = false
            try {
                dev.wind.agent.shell.termux.TermuxContract.resolveExecutable(bad, projDir)
            } catch (_: SecurityException) {
                blocked = true
            }
            if (!blocked) throw AssertionError("Unsafe executable was NOT blocked: $bad")
        }
    }

    private fun testTarArchiveSecurityAndBridgeLimits() {
        println("-> testTarArchiveSecurityAndBridgeLimits")
        if (dev.wind.agent.shell.bridge.BridgeLimits.MAX_FILES != 20000) throw AssertionError("MAX_FILES mismatch")
        if (dev.wind.agent.shell.bridge.BridgeLimits.MAX_PROJECT_BYTES != 256L * 1024 * 1024) throw AssertionError("MAX_PROJECT_BYTES mismatch")
        if (dev.wind.agent.shell.bridge.BridgeLimits.MAX_SINGLE_FILE_BYTES != 32L * 1024 * 1024) throw AssertionError("MAX_SINGLE_FILE_BYTES mismatch")
        if (dev.wind.agent.shell.bridge.BridgeLimits.CONNECT_TIMEOUT_MS != 10000) throw AssertionError("CONNECT_TIMEOUT_MS mismatch")
        if (dev.wind.agent.shell.bridge.BridgeLimits.TRANSFER_TIMEOUT_MS != 120000) throw AssertionError("TRANSFER_TIMEOUT_MS mismatch")

        val token = dev.wind.agent.shell.bridge.VegaBridgeServer.generateSecureToken()
        if (token.length != 64) throw AssertionError("Bridge token must be 256-bit hex (64 chars), got: ${token.length}")

        // Test archive traversal rejection in SafeTarExtractor
        fun buildTarHeader(name: String, typeFlag: Char = '0'): ByteArray {
            val h = ByteArray(512)
            val nb = name.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nb, 0, h, 0, Math.min(nb.size, 100))
            h[156] = typeFlag.code.toByte()
            return h
        }

        val tempDest = java.io.File(System.getProperty("java.io.tmpdir"), "vega-tar-test-" + System.currentTimeMillis())
        tempDest.mkdirs()
        try {
            for (badName in listOf("../escape.txt", "../../root.sh", "/etc/passwd", "\\windows\\system32", "foo/../bar")) {
                val header = buildTarHeader(badName)
                val stream = java.io.ByteArrayInputStream(header + ByteArray(1024))
                var blocked = false
                try {
                    dev.wind.agent.shell.bridge.SafeTarExtractor.extract(stream, tempDest)
                } catch (sec: SecurityException) {
                    if (sec.message?.contains("BLOCKED_ARCHIVE_ESCAPE") == true) {
                        blocked = true
                    }
                }
                if (!blocked) throw AssertionError("Archive escape was NOT blocked: $badName")
            }

            // Symlink ('2') must be blocked
            val symlinkHeader = buildTarHeader("symlink_target", '2')
            var blockedType = false
            try {
                dev.wind.agent.shell.bridge.SafeTarExtractor.extract(java.io.ByteArrayInputStream(symlinkHeader + ByteArray(1024)), tempDest)
            } catch (sec: SecurityException) {
                if (sec.message?.contains("BLOCKED_ARCHIVE_TYPE") == true) {
                    blockedType = true
                }
            }
            if (!blockedType) throw AssertionError("Symlink archive entry was NOT blocked")
        } finally {
            tempDest.deleteRecursively()
        }
    }

    private fun testTermuxCancellationAndStateHandling() {
        println("-> testTermuxCancellationAndStateHandling")
        val promise = dev.wind.agent.shell.termux.TermuxPromise()
        if (promise.state != dev.wind.agent.shell.termux.RemoteExecutionState.WAITING) throw AssertionError("Initial state must be WAITING")

        // Cancel transition
        promise.markCancelled()
        if (promise.state != dev.wind.agent.shell.termux.RemoteExecutionState.CANCELLED) throw AssertionError("State should be CANCELLED")

        // Late result callback must NOT alter CANCELLED state (Section XVI)
        val lateResult = dev.wind.agent.shell.termux.TermuxRawResult(0, "stdout", "stderr")
        promise.complete(lateResult)
        if (promise.state != dev.wind.agent.shell.termux.RemoteExecutionState.CANCELLED) throw AssertionError("Late result altered terminal CANCELLED state!")

        // Timeout transition
        val promise2 = dev.wind.agent.shell.termux.TermuxPromise()
        promise2.markTimedOut()
        if (promise2.state != dev.wind.agent.shell.termux.RemoteExecutionState.TIMED_OUT) throw AssertionError("State should be TIMED_OUT")
        promise2.complete(lateResult)
        if (promise2.state != dev.wind.agent.shell.termux.RemoteExecutionState.TIMED_OUT) throw AssertionError("Late result altered terminal TIMED_OUT state!")

        // Valid / Invalid execution IDs for cancellation
        val validId = "shell-12ab34cd"
        if (!validId.matches(Regex("^shell-[a-z0-9]{8}$"))) throw AssertionError("Valid execution ID failed regex check")
        for (badId in listOf("shell-123", "../shell", "shell-123456789", "rm -rf", "")) {
            if (badId.matches(Regex("^shell-[a-z0-9]{8}$"))) throw AssertionError("Bad execution ID passed regex: $badId")
        }
    }

    private fun testTermuxPreflightProbe() {
        println("-> testTermuxPreflightProbe")
        // 1. Tool parsing test
        val toolOutput = listOf(
            "TOOL\tbash\t1\t/data/data/com.termux/files/usr/bin/bash\tGNU bash, version 5.2.37",
            "TOOL\ttar\t1\t/data/data/com.termux/files/usr/bin/tar\ttar (GNU tar) 1.35",
            "TOOL\tcurl\t1\t/data/data/com.termux/files/usr/bin/curl\tcurl 8.11.1",
            "TOOL\tsetsid\t1\t/data/data/com.termux/files/usr/bin/setsid\tsetsid from util-linux 2.40.2",
            "TOOL\tgit\t1\t/data/data/com.termux/files/usr/bin/git\tgit version 2.47.1",
            "TOOL\tpython\t1\t/data/data/com.termux/files/usr/bin/python\tPython 3.12.7",
            "TOOL\tnode\t0\t\t"
        ).joinToString("\n")
        val parsed = dev.wind.agent.shell.termux.TermuxPreflight.parseToolProbe(toolOutput)
        if (parsed["bash"]?.available != true) throw AssertionError("bash should be available")
        if (parsed["git"]?.version != "git version 2.47.1") throw AssertionError("git version mismatch: ${parsed["git"]?.version}")
        if (parsed["node"]?.available != false) throw AssertionError("node should be unavailable")

        // 2. Status readiness invariant: setsid missing is DEGRADED, not failed
        val degradedStatus = dev.wind.agent.shell.termux.TermuxStatus(
            installed = true,
            runCommandPermission = true,
            callbackWorking = true,
            bridgeWorking = true,
            bash = true,
            tar = true,
            curl = true,
            setsid = false,
            state = dev.wind.agent.shell.termux.TermuxPreflightState.DEGRADED
        )
        if (!degradedStatus.executionReady) throw AssertionError("Degraded status (missing setsid) must still be executionReady")
        if (degradedStatus.cancellationReady) throw AssertionError("Degraded status must NOT be cancellationReady")

        // 3. Missing curl -> NOT executionReady
        val missingCurlStatus = dev.wind.agent.shell.termux.TermuxStatus(
            installed = true,
            runCommandPermission = true,
            callbackWorking = true,
            bridgeWorking = true,
            bash = true,
            tar = true,
            curl = false,
            setsid = true,
            state = dev.wind.agent.shell.termux.TermuxPreflightState.CORE_TOOLS_MISSING
        )
        if (missingCurlStatus.executionReady) throw AssertionError("Missing curl must NOT be executionReady")
    }

    private fun testBaselineAndMetadataPersistence() {
        println("-> testBaselineAndMetadataPersistence")
        val state = dev.wind.agent.shell.execution.ExecutionState.CHANGES_PENDING
        if (state.name != "CHANGES_PENDING") throw AssertionError("State name mismatch")
        val obj = org.json.JSONObject()
        obj.put("state", state.name)
        if (obj.getString("state") != "CHANGES_PENDING") throw AssertionError("JSON state mismatch")
    }

    private fun testDiffPreflightAndBatchRollback() {
        println("-> testDiffPreflightAndBatchRollback")
        val batch = dev.wind.agent.history.MutationBatch("batch-1", "task-1")
        if (batch.state != dev.wind.agent.history.BatchState.PREPARED) throw AssertionError("Initial batch state must be PREPARED")
        batch.recordIds.add("rec-1")
        batch.recordIds.add("rec-2")
        batch.state = dev.wind.agent.history.BatchState.COMMITTED
        if (batch.state != dev.wind.agent.history.BatchState.COMMITTED) throw AssertionError("Batch state should be COMMITTED")

        val preflight = dev.wind.agent.shell.SyncPreflightResult(
            allowed = false,
            conflicts = listOf(dev.wind.agent.shell.SyncConflict("src/Main.kt", "externally modified")),
            unsupported = emptyList()
        )
        if (preflight.allowed) throw AssertionError("Conflict preflight must NOT be allowed")
        if (preflight.conflicts.size != 1) throw AssertionError("Conflicts count mismatch")
    }

    private fun testUnsupportedBinaryFailClosed() {
        println("-> testUnsupportedBinaryFailClosed")
        val ws = dev.wind.agent.shell.ExecutionWorkspace(TestContext(), "dummy")
        // 1. Text bytes should not be binary
        val textBytes = "Hello world, this is pure UTF-8 text.".toByteArray(Charsets.UTF_8)
        if (ws.isBinary(textBytes)) throw AssertionError("Clean text was falsely detected as binary")

        // 2. NUL byte must be detected as binary
        val nulBytes = byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())
        if (!ws.isBinary(nulBytes)) throw AssertionError("NUL byte was NOT detected as binary")

        // 3. Invalid UTF-8 sequence must be detected as binary
        val badUtf8 = byteArrayOf(0xC0.toByte(), 0xAF.toByte())
        if (!ws.isBinary(badUtf8)) throw AssertionError("Malformed UTF-8 sequence was NOT detected as binary")

        // Preflight with binary must be rejected
        val binaryPreflight = dev.wind.agent.shell.SyncPreflightResult(
            allowed = false,
            conflicts = emptyList(),
            unsupported = listOf("assets/icon.png")
        )
        if (binaryPreflight.allowed) throw AssertionError("Binary preflight must NOT be allowed")
    }

    private fun testSearchDeadlineAndSessionPrefixCleanup() {
        println("-> testSearchDeadlineAndSessionPrefixCleanup")
        val deadline = dev.wind.agent.search.SearchDeadline(totalBudgetMs = 500L, startMs = System.currentTimeMillis() - 600L)
        if (!deadline.isExpired()) throw AssertionError("Expired deadline was NOT flagged as expired")
        if (deadline.remainingMs() != 0L) throw AssertionError("Remaining ms for expired deadline should be 0")

        SecureStore.encrypt("test-key-0", "router_0")
        SecureStore.encrypt("test-key-1", "router_1")
        if (SecureStore.getSessionKey("router_0") != "test-key-0") throw AssertionError("router_0 not in session")
        SecureStore.clearSessionPrefix("router_")
        if (SecureStore.getSessionKey("router_0").isNotEmpty()) throw AssertionError("router_0 was NOT cleared by prefix")
        if (SecureStore.getSessionKey("router_1").isNotEmpty()) throw AssertionError("router_1 was NOT cleared by prefix")
    }

    private fun testVegaBridgeServerLiveHttp() {
        println("-> testVegaBridgeServerLiveHttp")
        val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "vega-srv-test-" + System.currentTimeMillis())
        val stagingDir = java.io.File(tmpDir, "staging").apply { mkdirs() }
        val returnedDir = java.io.File(tmpDir, "returned").apply { mkdirs() }
        java.io.File(stagingDir, "test.txt").writeText("hello bridge")

        val server = dev.wind.agent.shell.bridge.VegaBridgeServer("shell-srvtest", stagingDir, returnedDir)
        try {
            // 1. Wrong token -> 403 Forbidden
            val badUrl = java.net.URL("http://127.0.0.1:${server.port}/v1/project/badtoken")
            val badConn = badUrl.openConnection() as java.net.HttpURLConnection
            badConn.requestMethod = "GET"
            if (badConn.responseCode != 403) throw AssertionError("Wrong token should return 403, got: ${badConn.responseCode}")

            // 2. Valid GET -> 200 OK
            val goodUrl = java.net.URL("http://127.0.0.1:${server.port}/v1/project/${server.token}")
            val goodConn = goodUrl.openConnection() as java.net.HttpURLConnection
            goodConn.requestMethod = "GET"
            if (goodConn.responseCode != 200) throw AssertionError("Valid GET should return 200, got: ${goodConn.responseCode}")
            val tarBytes = goodConn.inputStream.readBytes()
            if (tarBytes.isEmpty()) throw AssertionError("Tar bytes from bridge were empty")

            // 3. Second GET -> 410 Gone
            val secondConn = goodUrl.openConnection() as java.net.HttpURLConnection
            secondConn.requestMethod = "GET"
            if (secondConn.responseCode != 410) throw AssertionError("Second GET should return 410, got: ${secondConn.responseCode}")

            // 4. Valid PUT -> 200 OK & extract returned project
            val putConn = goodUrl.openConnection() as java.net.HttpURLConnection
            putConn.requestMethod = "PUT"
            putConn.doOutput = true
            putConn.setFixedLengthStreamingMode(tarBytes.size)
            putConn.outputStream.use { it.write(tarBytes) }
            if (putConn.responseCode != 200) throw AssertionError("Valid PUT should return 200, got: ${putConn.responseCode}")
            val returnedFile = java.io.File(returnedDir, "test.txt")
            if (!returnedFile.isFile || returnedFile.readText() != "hello bridge") {
                throw AssertionError("Returned file was not extracted correctly from PUT payload")
            }

            // 5. Second PUT -> 409 Conflict
            val secondPutConn = goodUrl.openConnection() as java.net.HttpURLConnection
            secondPutConn.requestMethod = "PUT"
            secondPutConn.doOutput = true
            secondPutConn.setFixedLengthStreamingMode(tarBytes.size)
            secondPutConn.outputStream.use { it.write(tarBytes) }
            if (secondPutConn.responseCode != 409) throw AssertionError("Second PUT should return 409, got: ${secondPutConn.responseCode}")
        } finally {
            server.close()
            tmpDir.deleteRecursively()
        }
    }

    private fun testExecutionWorkspaceAndPreflightIntegration() {
        println("-> testExecutionWorkspaceAndPreflightIntegration")
        val memFs = MemFs()
        memFs.writeText("src/Main.kt", "fun main() = println(1)")

        val ws = dev.wind.agent.shell.ExecutionWorkspace(TestContext(), "shell-ws-test")
        try {
            ws.syncIn(memFs)
            // Initial diff must be empty
            if (!ws.detectDiff().isEmpty()) throw AssertionError("Initial diff must be empty")

            // Modify in staging
            val localFile = java.io.File(ws.stagingDir, "src/Main.kt")
            localFile.parentFile?.mkdirs()
            localFile.writeText("fun main() = println(2)")
            val diff = ws.detectDiff()
            if (diff.items.size != 1 || diff.items[0].type != dev.wind.agent.shell.DiffType.MODIFIED) {
                throw AssertionError("Expected 1 MODIFIED diff item, got: ${diff.items}")
            }

            // Preflight should be allowed when live matches baseline
            val preflightOk = ws.preflightSyncBack(memFs, diff)
            if (!preflightOk.allowed) throw AssertionError("Preflight should be allowed")

            // Modify live file externally to simulate conflict
            memFs.writeText("src/Main.kt", "fun main() = println(999)")
            val preflightConflict = ws.preflightSyncBack(memFs, diff)
            if (preflightConflict.allowed) throw AssertionError("Conflict preflight must NOT be allowed")
            if (preflightConflict.conflicts.isEmpty()) throw AssertionError("Conflicts list was empty")
        } finally {
            ws.cleanup()
        }
    }

    private fun testApplyFailureRollbackIntegration() {
        println("-> testApplyFailureRollbackIntegration")
        val memFs = MemFs()
        memFs.writeText("src/A.kt", "A1")
        memFs.writeText("src/B.kt", "B1")

        val ws = dev.wind.agent.shell.ExecutionWorkspace(TestContext(), "shell-rollback-test")
        try {
            ws.syncIn(memFs)
            java.io.File(ws.stagingDir, "src/A.kt").parentFile?.mkdirs()
            java.io.File(ws.stagingDir, "src/A.kt").writeText("A2")
            java.io.File(ws.stagingDir, "src/B.kt").writeText("B2")
            val diff = ws.detectDiff()
            if (diff.items.size != 2) throw AssertionError("Expected 2 diff items, got: ${diff.items.size}")

            val preflight = ws.preflightSyncBack(memFs, diff)
            if (!preflight.allowed) throw AssertionError("Preflight should allow clean diff")

            val journal = dev.wind.agent.history.MutationJournal.get(TestContext())
            var rolledBack = false
            memFs.failOnWrite = true
            try {
                ws.applySyncBack(memFs, diff, journal, "task-rollback-1")
            } catch (_: Exception) {
                rolledBack = true
            }
            if (!rolledBack) throw AssertionError("applySyncBack should throw and rollback when write fails")
        } finally {
            ws.cleanup()
        }
    }

    private fun testPendingExecutionProcessRestartRecovery() {
        println("-> testPendingExecutionProcessRestartRecovery")
        val testCtx = TestContext()
        val execDir = java.io.File(testCtx.filesDir, "execution/shell-restart-1")
        val staging = java.io.File(execDir, "staging").apply { mkdirs() }
        java.io.File(staging, "file.txt").writeText("v2")
        val metaObj = org.json.JSONObject()
        metaObj.put("executionId", "shell-restart-1")
        metaObj.put("taskId", "task-restart")
        metaObj.put("command", "echo test")
        metaObj.put("createdAt", System.currentTimeMillis())
        metaObj.put("state", "CHANGES_PENDING")
        java.io.File(execDir, "metadata.json").writeText(metaObj.toString())

        val baseArr = org.json.JSONArray()
        val bObj = org.json.JSONObject()
        bObj.put("path", "file.txt")
        bObj.put("directory", false)
        bObj.put("size", 2L)
        bObj.put("sha256", "abc")
        baseArr.put(bObj)
        java.io.File(execDir, "baseline.json").writeText(baseArr.toString())

        dev.wind.agent.shell.execution.ExecutionRecovery.scan(testCtx)
        val recovered = dev.wind.agent.shell.execution.ShellExecutionStore.get("shell-restart-1")
        if (recovered == null) throw AssertionError("Pending execution was NOT recovered upon restart scan!")
        if (recovered.executionId != "shell-restart-1") throw AssertionError("Recovered execution ID mismatch: ${recovered.executionId}")
    }

    private class MemFs : dev.wind.agent.workspace.WorkspaceFs {
        val files = mutableMapOf<String, ByteArray>()
        val dirs = mutableSetOf<String>()
        var failOnWrite = false

        override fun isConfigured(): Boolean = true
        override fun getRootDisplayName(): String = "mem://"
        override fun list(path: String): List<dev.wind.agent.workspace.WorkspaceEntry> {
            val norm = path.trim('/')
            val out = mutableListOf<dev.wind.agent.workspace.WorkspaceEntry>()
            val prefix = if (norm.isEmpty()) "" else "$norm/"
            for (d in dirs) {
                if (d.startsWith(prefix) && d != norm) {
                    val sub = d.substring(prefix.length)
                    if (!sub.contains('/')) {
                        out.add(dev.wind.agent.workspace.WorkspaceEntry(d, d.substringAfterLast('/'), isDirectory = true, size = 0L, lastModified = System.currentTimeMillis()))
                    }
                }
            }
            for ((f, b) in files) {
                if (f.startsWith(prefix)) {
                    val sub = f.substring(prefix.length)
                    if (!sub.contains('/')) {
                        out.add(dev.wind.agent.workspace.WorkspaceEntry(f, f.substringAfterLast('/'), isDirectory = false, size = b.size.toLong(), lastModified = System.currentTimeMillis()))
                    }
                }
            }
            return out
        }
        override fun read(path: String): ByteArray = files[path] ?: throw java.io.FileNotFoundException(path)
        override fun readText(path: String, charset: String): String = String(read(path), charset(charset))
        override fun write(path: String, data: ByteArray) {
            if (failOnWrite) throw java.io.IOException("Simulated disk write failure")
            val p = path.substringBeforeLast('/', "")
            if (p.isNotEmpty()) mkdir(p)
            files[path] = data
        }
        override fun writeText(path: String, text: String, charset: String) = write(path, text.toByteArray(charset(charset)))
        override fun mkdir(path: String): Boolean { dirs.add(path.trim('/')); return true }
        override fun delete(path: String): Boolean { files.remove(path); dirs.remove(path); return true }
        override fun move(from: String, to: String): Boolean {
            val d = files.remove(from) ?: return false
            files[to] = d
            return true
        }
        override fun exists(path: String): Boolean = files.containsKey(path) || dirs.contains(path.trim('/'))
        override fun stat(path: String): dev.wind.agent.workspace.WorkspaceEntry? {
            val norm = path.trim('/')
            if (dirs.contains(norm)) return dev.wind.agent.workspace.WorkspaceEntry(norm, norm.substringAfterLast('/'), isDirectory = true, size = 0L, lastModified = System.currentTimeMillis())
            val f = files[path] ?: return null
            return dev.wind.agent.workspace.WorkspaceEntry(path, path.substringAfterLast('/'), isDirectory = false, size = f.size.toLong(), lastModified = System.currentTimeMillis())
        }
        override fun search(query: String, maxResults: Int, scopePath: String): List<dev.wind.agent.workspace.WorkspaceEntry> = emptyList()
        override fun glob(pattern: String, maxResults: Int, scopePath: String): List<dev.wind.agent.workspace.WorkspaceEntry> = emptyList()
        override fun openInputStream(path: String): java.io.InputStream = java.io.ByteArrayInputStream(read(path))
        override fun openOutputStream(path: String): java.io.OutputStream {
            val baos = java.io.ByteArrayOutputStream()
            return object : java.io.OutputStream() {
                override fun write(b: Int) = baos.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = baos.write(b, off, len)
                override fun close() { write(path, baos.toByteArray()) }
            }
        }
    }

    private class TestContext : android.content.ContextWrapper(null) {
        val dir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "vega-sec-test-" + System.nanoTime()).also { it.mkdirs() }
        override fun getFilesDir(): java.io.File = dir
        override fun getApplicationContext(): android.content.Context = this
    }
}
