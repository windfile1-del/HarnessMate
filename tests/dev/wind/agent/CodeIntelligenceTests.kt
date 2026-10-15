package dev.wind.agent

import dev.wind.agent.coding.*
import dev.wind.agent.intelligence.*
import dev.wind.agent.intelligence.index.*
import dev.wind.agent.intelligence.lsp.*
import dev.wind.agent.project.*
import dev.wind.agent.shell.mirror.*
import dev.wind.agent.capability.CapabilityMode
import dev.wind.agent.capability.TaskContext
import dev.wind.agent.runtime.RuntimeBinding
import dev.wind.agent.runtime.RuntimeProviderId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CodeIntelligenceTests {
    private var passed = 0

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError("ASSERTION FAILED: $message")
        passed++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("== Running CodeIntelligenceTests ==")
        testDataModelsAndLimits()
        testPathMapper()
        testStaticParsers()
        testIncrementalIndexing()
        testFreshnessAndSourceMoved()
        testCursorStaleness()
        testSemanticSearch()
        testDependencyGraph()
        testLspProtocolAndSanitizer()
        testLspSessionAndManager()
        testDiagnosticsSourceCompatibility()
        testPathSanitizerFailsClosed()
        testLspRestartBudgetIsEnforcedPerManager()
        testLspMutationGuard()
        testGuardedLspFailureAndBoundary()
        testManagerLspLifecycleGuards()
        testManagerLspRevisionToctou()
        testManagerBoundaryPostConditions()
        testResultBudgetIsAFinalPostCondition()
        testCandidateObservationIntegrity()
        testManagerLinuxRuntimeLspPositiveRegression()
        testLspSessionQuotaAndLruEviction()
        println("PASS CodeIntelligenceTests: $passed assertions")
    }

    private val main = WorkspaceMount(
        id = "m-main", alias = "main", treeUri = "content://dummy/main",
        displayName = "main", mode = MountMode.READ_WRITE, executionPath = "main", isPrimary = true
    )
    private val shared = WorkspaceMount(
        id = "m-shared", alias = "shared", treeUri = "content://dummy/shared",
        displayName = "shared", mode = MountMode.READ_WRITE, executionPath = "shared", isPrimary = false
    )

    private fun project(): VegaProject =
        VegaProject("proj-1", "proj-1", main.id, listOf(main, shared), 1L)

    private fun testDataModelsAndLimits() {
        check(CodeIntelLimits.MAX_DEFINITIONS == 8, "MAX_DEFINITIONS must be 8")
        check(CodeIntelLimits.MAX_REFERENCES == 50, "MAX_REFERENCES must be 50")
        check(CodeIntelLimits.MAX_SYMBOL_RESULTS == 50, "MAX_SYMBOL_RESULTS must be 50")
        check(CodeIntelLimits.MAX_SEMANTIC_RESULTS == 20, "MAX_SEMANTIC_RESULTS must be 20")
        check(CodeIntelLimits.MAX_TOOL_RESULT_BYTES == 64 * 1024, "MAX_TOOL_RESULT_BYTES must be 64KiB")

        val first = SourceRevision("p1", 1L, "digest-a", 0)
        val rebound = SourceRevision("p1", 1L, "digest-a", 1)
        val rebuilt = SourceRevision("p1", 1L, "digest-b", 0)
        check(first.toSnapshotId() == rebound.toSnapshotId(), "epoch change reuses the snapshot")
        check(first.toSnapshotId() != rebuilt.toSnapshotId(), "digest change needs a new snapshot")
        check(first.toToken().contains("digest-a"), "token encodes the digest")
    }

    private fun testPathMapper() {
        val mapper = CodePathMapper(project())
        check(mapper.toExecutionRelative("workspace://src/Foo.kt") == "main/src/Foo.kt", "workspace path maps to the primary mount")
        check(mapper.toExecutionRelative("project://shared/lib/Bar.java") == "shared/lib/Bar.java", "project path maps to the alias")
        check(mapper.toVirtualPath("main/src/Foo.kt") == "workspace://src/Foo.kt", "primary execution path maps back")
        check(mapper.toVirtualPath("shared/lib/Bar.java") == "project://shared/lib/Bar.java", "secondary execution path maps back")

        assertThrows<SecurityException>("reject execution://") { mapper.toExecutionRelative("execution://main/Foo.kt") }
        assertThrows<SecurityException>("reject file://") { mapper.toExecutionRelative("file:///data/Foo.kt") }
        assertThrows<SecurityException>("reject content://") { mapper.toExecutionRelative("content://com.android/Foo.kt") }
        assertThrows<SecurityException>("reject traversal") { mapper.toExecutionRelative("workspace://../escape.kt") }
        assertThrows<SecurityException>("reject Windows drive") { mapper.toExecutionRelative("C:\\escape.kt") }
        assertThrows<SecurityException>("reject absolute POSIX") { mapper.toExecutionRelative("/data/escape.kt") }
        assertThrows<SecurityException>("reject NUL") { mapper.toExecutionRelative("workspace://foo\u0000bar.kt") }
        assertThrows<SecurityException>("reject bare relative path") { mapper.toExecutionRelative("src/Foo.kt") }
        assertThrows<IllegalArgumentException>("reject unknown execution path") { mapper.toVirtualPath("elsewhere/Foo.kt") }
    }

    private fun testStaticParsers() {
        val kotlin = """
            package dev.wind.test
            import java.util.List
            class EngineService {
                val status: String = "active"
                fun startEngine(param: Int): Boolean {
                    return status == "active"
                }
            }
        """.trimIndent()
        val parsedKotlin = StaticParsers.parse("src/Engine.kt", kotlin)
        check(parsedKotlin.symbols.any { it.name == "EngineService" && it.kind == SymbolKind.CLASS }, "Kotlin class parsed")
        check(parsedKotlin.symbols.any { it.name == "startEngine" && it.kind == SymbolKind.METHOD }, "Kotlin method parsed")
        check(parsedKotlin.symbols.any { it.name == "status" && it.kind == SymbolKind.FIELD }, "Kotlin field parsed")
        check(parsedKotlin.references.any { it.name == "status" }, "Kotlin reference extracted")
        check(parsedKotlin.references.none { it.name == "active" }, "string literal is not a reference")
        check(parsedKotlin.imports.any { it.moduleName == "java.util" }, "Kotlin import extracted")

        val python = """
            import os
            class Worker:
                def run(self):
                    pass
            TOP_LEVEL = 123
        """.trimIndent()
        val parsedPython = StaticParsers.parse("script.py", python)
        check(parsedPython.symbols.any { it.name == "Worker" && it.kind == SymbolKind.CLASS }, "Python class parsed")
        check(parsedPython.symbols.any { it.name == "run" && it.kind == SymbolKind.METHOD }, "Python method parsed")
        check(parsedPython.symbols.any { it.name == "TOP_LEVEL" && it.kind == SymbolKind.VARIABLE }, "Python top-level assignment parsed")

        val typescript = """
            import { readFile } from 'fs';
            export interface Config { timeout: number; }
            export class Server {
                listen() {}
            }
            const port = 8080;
        """.trimIndent()
        val parsedTypeScript = StaticParsers.parse("server.ts", typescript)
        check(parsedTypeScript.symbols.any { it.name == "Config" && it.kind == SymbolKind.INTERFACE }, "TS interface parsed")
        check(parsedTypeScript.symbols.any { it.name == "Server" && it.kind == SymbolKind.CLASS }, "TS class parsed")
        check(parsedTypeScript.symbols.any { it.name == "listen" }, "TS method parsed")
        check(parsedTypeScript.symbols.any { it.name == "port" }, "TS const parsed")

        val java = """
            package com.example;
            import java.util.List;
            public class Service {
                private int count;
                public void run() {}
            }
        """.trimIndent()
        val parsedJava = StaticParsers.parse("Service.java", java)
        check(parsedJava.symbols.any { it.name == "Service" && it.kind == SymbolKind.CLASS }, "Java class parsed")
        check(parsedJava.symbols.any { it.name == "run" }, "Java method parsed")

        val huge = StaticParsers.parse("big.kt", "x".repeat(1024 * 1024 + 5))
        check(huge.symbols.isEmpty(), "oversized file is skipped instead of parsed")
    }

    private fun testIncrementalIndexing() {
        val backend = MemoryCodeIndexBackend()
        val first = SourceRevision("p1", 1L, "digest-1", 0)
        val files = mutableMapOf(
            "main/src/A.kt" to "class Alpha {}".toByteArray(Charsets.UTF_8),
            "main/src/B.kt" to "class Beta {}".toByteArray(Charsets.UTF_8)
        )
        check(backend.ensureFresh(first, { files[it] }, files.keys.toList()), "initial index built")
        val status = backend.status(first)
        check(status.phase == CodeIndexPhase.READY, "index reports READY")
        check(status.fileCount == 2, "both files indexed")
        check(backend.searchSymbols("Alpha", emptySet(), null, null, 10, 0, first).size == 1, "Alpha found")

        val second = SourceRevision("p1", 1L, "digest-2", 1)
        files["main/src/B.kt"] = "class BetaRenamed {}".toByteArray(Charsets.UTF_8)
        check(backend.ensureFresh(second, { files[it] }, files.keys.toList()), "incremental refresh built")
        check(backend.searchSymbols("BetaRenamed", emptySet(), null, null, 10, 0, second).size == 1, "edited file re-parsed")
        check(backend.searchSymbols("Alpha", emptySet(), null, null, 10, 0, second).size == 1, "unchanged file reused")
        val atPosition = backend.findDefinitions("main/src/A.kt", 1, 7, null, second)
        check(atPosition.size == 1 && atPosition[0].symbol == "Alpha", "definition at position resolves")

        val third = SourceRevision("p1", 1L, "digest-3", 2)
        files.remove("main/src/B.kt")
        check(backend.ensureFresh(third, { files[it] }, files.keys.toList()), "deletion refresh built")
        check(backend.searchSymbols("BetaRenamed", emptySet(), null, null, 10, 0, third).isEmpty(), "deleted file dropped")
    }

    private fun testFreshnessAndSourceMoved() {
        val backend = MemoryCodeIndexBackend()
        val revision = SourceRevision("p1", 1L, "digest-1", 0)
        backend.markDirty("p1")
        check(backend.status(revision).phase == CodeIndexPhase.DIRTY, "dirty index is never READY")
        backend.invalidateAll("p1")
        check(backend.status(revision).phase != CodeIndexPhase.READY, "invalidated index is not READY")
    }

    private fun testCursorStaleness() {
        val cursor = CursorPayload("refs", "digest-1", 3, 50)
        val decoded = CursorPayload.decode(cursor.encode())
        check(decoded != null && decoded.offset == 50 && decoded.sourceDigest == "digest-1", "cursor round-trips")
        check(CursorPayload.decode("not-a-cursor") == null, "malformed cursor rejected")
    }

    private fun testSemanticSearch() {
        val backend = MemoryCodeIndexBackend()
        val revision = SourceRevision("p1", 1L, "digest-sem", 0)
        val files = mapOf(
            "main/src/HttpAuth.kt" to """
                class HttpAuthenticationService {
                    fun verifyToken() {}
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        backend.ensureFresh(revision, { files[it] }, files.keys.toList())
        val results = backend.semanticSearch("http authentication", 10, revision)
        check(results.isNotEmpty(), "semantic search scored something")
        check(results[0].symbol == "HttpAuthenticationService", "best match is the auth service")
        check(results[0].reasons.isNotEmpty(), "each hit states why it matched")
        check(backend.semanticSearch("", 10, revision).isEmpty(), "empty query returns nothing")
    }

    private fun testDependencyGraph() {
        val backend = MemoryCodeIndexBackend()
        val revision = SourceRevision("p1", 1L, "digest-dep", 0)
        val files = mapOf(
            "main/src/App.kt" to "package com.app\nimport com.core.Utils\nclass App {}".toByteArray(Charsets.UTF_8)
        )
        backend.ensureFresh(revision, { files[it] }, files.keys.toList())
        val graph = backend.dependencyGraph(revision)
        check(graph.nodes.any { it.kind == DependencyNodeKind.MODULE }, "graph has a module node")
        check(graph.nodes.any { it.kind == DependencyNodeKind.FILE }, "graph has a file node")
        check(graph.nodes.any { it.kind == DependencyNodeKind.PACKAGE && it.label == "com.core" }, "graph has the import node")

        val builder = dev.wind.agent.intelligence.dependency.DependencyGraphBuilder(backend)
        val manifests = mapOf(
            "main/build.gradle.kts" to """
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                    implementation(project(":core"))
                }
            """.trimIndent(),
            "main/pom.xml" to "<dependency><groupId>junit</groupId><artifactId>junit</artifactId></dependency>",
            "main/package.json" to "{\"dependencies\":{\"express\":\"^4.18.2\"}}",
            "main/requirements.txt" to "requests>=2.31.0"
        )
        val full = builder.build(revision, { manifests[it] }, manifests.keys.toList())
        check(full.nodes.any { it.label == "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3" }, "Gradle coordinate parsed")
        check(full.nodes.any { it.label == "core" && it.kind == DependencyNodeKind.MODULE }, "Gradle project(:core) parsed")
        check(full.nodes.any { it.label == "junit:junit" }, "Maven coordinate parsed")
        check(full.nodes.any { it.label == "express" }, "npm dependency parsed")
        check(full.nodes.any { it.label == "requests" }, "Python requirement parsed")
        check(full.nodes.none { it.path != null && it.path!!.contains("/data/") }, "no host path reaches the graph")
    }

    private fun testLspProtocolAndSanitizer() {
        val mapper = CodePathMapper(project())
        val sanitizer = LspPathSanitizer(mapper)
        val root = "/data/data/com.termux/files/home/.vega-safe/workspaces/abc123/project"

        check(
            sanitizer.uriToVirtualPath("file://$root/main/src/App.kt", root) == "workspace://src/App.kt",
            "a location under the reported execution root maps to workspace://"
        )
        check(
            sanitizer.uriToVirtualPath("file://$root/shared/lib/Bar.java", root) == "project://shared/lib/Bar.java",
            "a secondary mount maps to project://"
        )
        check(
            sanitizer.uriToVirtualPath("file:///data/data/com.termux/files/home/.gradle/caches/lib.jar", root) == null,
            "a path outside the execution root is rejected"
        )
        check(
            sanitizer.uriToVirtualPath("file://$root/main/src/App.kt", null) == null,
            "an unknown execution root fails closed"
        )
        check(
            sanitizer.virtualPathToUri("workspace://src/App.kt", root) == "file://$root/main/src/App.kt",
            "virtual path becomes a request URI inside the mirror"
        )
        check(
            sanitizer.virtualPathToUri("workspace://src/App.kt", null) == null,
            "no execution root means no request URI"
        )
    }

    private fun testPathSanitizerFailsClosed() {
        val sanitizer = LspPathSanitizer(CodePathMapper(project()))
        val root = "/data/data/com.termux/files/home/.vega-safe/workspaces/abc123/project"

        check(sanitizer.uriToVirtualPath("http://$root/main/src/App.kt", root) == null, "non-file scheme rejected")
        check(sanitizer.uriToVirtualPath("file:///tmp/project/main/src/App.kt", root) == null, "another root rejected")
        check(sanitizer.uriToVirtualPath("file://$root/main/../secret.kt", root) == null, "escape inside the root rejected")

        // Any absolute host path must be replaced, not just the ones with a
        // familiar first segment.
        for (path in listOf(
            "/root/.gradle/caches/x.jar", "/bin/sh", "/sbin/init", "/lib/x86/libc.so",
            "/lib64/ld.so", "/boot/vmlinuz", "/srv/www/index.html", "/usr/lib/stdio.h",
            "/tmp/scratch", "/data/user/0/app/cache", "/project/leak.kt"
        )) {
            val sanitized = sanitizer.sanitizeText("error while reading $path: permission denied")
            check(!sanitized.contains(path), "absolute path cleaned from text: $path")
            check(sanitized.contains("<sanitized-path>"), "replacement marker present for $path")
        }
        check(
            !sanitizer.sanitizeText("open /data/data/com.termux/files/home/x").contains("/data/data/com.termux"),
            "termux path cleaned"
        )
        check(
            !sanitizer.sanitizeText("failed at C:\\Users\\me\\project\\a.kt").contains("C:\\Users"),
            "windows path cleaned"
        )
        check(
            !sanitizer.sanitizeText("failed at C:/Users/me/project/a.kt").contains("C:/Users"),
            "forward-slash windows path cleaned"
        )
        check(
            !sanitizer.sanitizeText("failed at \\\\host\\share\\secret.kt").contains("\\\\host\\share"),
            "UNC windows path cleaned"
        )
        check(
            !sanitizer.sanitizeText("fetched file:///etc/hosts").contains("file:///etc"),
            "file:// URI cleaned"
        )
        check(
            !sanitizer.sanitizeText("network path //srv/build/root/Foo.kt").contains("//srv/build"),
            "POSIX network-path form cleaned"
        )
        for (uri in listOf(
            "content://com.termux.provider/root/data.kt",
            "binder://127.0.0.1:9000/session",
            "termux://localhost/data/data/com.termux/files/home/x",
            "custom+host://device/private/path",
            // URI schemes do not require an authority separator.  These
            // spellings must be covered because stderr/detail can contain
            // file:/, content:/, nested jar:file:/, or arbitrary custom:/
            // forms that would otherwise bypass a ://-only scrubber.
            "file:/data/data/com.termux/files/home/x",
            "file:C:/Users/me/project/a.kt",
            "content:/private/path",
            "binder:/session/private",
            "termux:/data/data/com.termux/files/home/x",
            "custom:/srv/build/root/Foo.kt",
            "jar:file:/data/data/com.termux/files/home/x.jar!/x.kt"
        )) {
            check(!sanitizer.sanitizeText("server reported $uri").contains(uri),
                "arbitrary URI scheme cleaned: $uri")
        }
        check(
            !sanitizer.sanitizeText("server reported workspace:///data/data/com.termux/files/home/x")
                .contains("workspace:///data/data/com.termux"),
            "a forged workspace scheme cannot carry a physical path"
        )
        check(
            sanitizer.sanitizeText("workspace://src/App.kt project://shared/Lib.kt") ==
                "workspace://src/App.kt project://shared/Lib.kt",
            "virtual workspace URIs remain visible"
        )
        check(
            sanitizer.sanitizeText("hard to parse: 3/4 of the tokens").contains("3/4"),
            "ordinary text is left alone"
        )
    }

    private class FakeLspTransport(
        private val root: String? = "/mirror",
        private val alive: Boolean = true,
        private val starts: Boolean = true
    ) : LspTransport {
        override fun start(
            language: CodeLanguage,
            rootUri: String,
            token: CancellationToken,
            beforeDispatch: ExecutionBoundaryVerifier?
        ): LspOutcome<Unit> {
            if (starts) {
                beforeDispatch?.verify()
                return LspOutcome.Success(Unit)
            }
            return LspOutcome.Failure(LspFailureCode.START_FAILED)
        }
        override fun executionRoot(): String? = root
        override fun request(
            method: String,
            params: JSONObject,
            timeoutMs: Long,
            token: CancellationToken,
            beforeDispatch: ExecutionBoundaryVerifier?
        ): LspOutcome<JSONObject> {
            beforeDispatch?.verify()
            return when (method) {
                "textDocument/definition" -> LspOutcome.Success(JSONObject().put("result", location("file://" + root + "/main/src/Foo.kt", 10, 4)))
                "textDocument/references" -> LspOutcome.Success(JSONObject().put("result", JSONArray().put(location("file://" + root + "/main/src/Foo.kt", 20, 8))))
                "textDocument/diagnostic" -> LspOutcome.Success(JSONObject().put("result", JSONArray().put(
                    JSONObject().put("message", "unused variable at /root/.gradle/caches/x.jar")
                        .put("severity", 2)
                        .put("range", JSONObject().put("start", JSONObject().put("line", 3).put("character", 1)))
                )))
                else -> LspOutcome.Failure(LspFailureCode.UNAVAILABLE)
            }
        }

        private fun location(uri: String, line: Int, column: Int): JSONObject =
            JSONObject().put("uri", uri).put("range", JSONObject()
                .put("start", JSONObject().put("line", line).put("character", column))
                .put("end", JSONObject().put("line", line).put("character", column + 3)))

        override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {}
        override fun stop(): LspOutcome<Unit> = LspOutcome.Success(Unit)
    }

    private fun testLspSessionAndManager() {
        val manager = LanguageServerManager(transportFactory = { FakeLspTransport() })
        val key = LspSessionKey("task-1", "p1", 1L, CodeLanguage.KOTLIN)
        val sessionResult = manager.getSession(key, "digest-1")
        check(sessionResult is LspOutcome.Success, "session acquired")
        val session = (sessionResult as LspOutcome.Success).value
        val root = session.executionRoot()!!
        val sanitizer = LspPathSanitizer(CodePathMapper(project()))

        val definitions = (manager.findDefinitions(session, "file://$root/main/src/Foo.kt", 5, 2, sanitizer)
            as LspOutcome.Success).value
        check(definitions.size == 1, "definition returned")
        check(definitions[0].confidence == IntelligenceConfidence.EXACT, "LSP result is EXACT")
        check(definitions[0].path == "workspace://src/Foo.kt", "definition translated to a virtual path")

        val references = ((manager.findReferences(session, "file://$root/main/src/Foo.kt", 5, 2, false, sanitizer)
            as LspOutcome.Success).value)
        check(references.size == 1 && references[0].path == "workspace://src/Foo.kt", "references translated")

        val diagnostics = ((manager.diagnostics(session, "file://$root/main/src/Foo.kt", sanitizer)
            as LspOutcome.Success).value)
        check(diagnostics.size == 1, "diagnostic returned")
        check(!diagnostics[0].message.contains("/root/"), "diagnostic text is path-clean")
        check(diagnostics[0].path == "workspace://src/Foo.kt", "diagnostic path is virtual")

        val staleResult = manager.getSession(key, "digest-2")
        check(staleResult is LspOutcome.Success && (staleResult as LspOutcome.Success).value.sourceDigest == "digest-2", "digest change restarts the session")
        manager.stopAll()
    }

    private fun testDiagnosticsSourceCompatibility() {
        check(ToolRegistry.isKnown("diagnostics"), "diagnostics registered")
        for (tool in listOf("find_definition", "find_references", "symbol_search", "semantic_search", "dependency_graph")) {
            check(ToolRegistry.isKnown(tool), "$tool registered")
            check(ToolRegistry.isEnabled(tool), "$tool enabled")
        }
        check(ToolRegistry.spec("find_definition")?.effect == ToolEffect.MIRROR_READ, "index lookups hold MIRROR_READ")
        // diagnostics is source-dependent (validation is internal, lsp/all needs a
        // server), so its authority is asserted through the resolver below rather
        // than through a single registry effect.
        check(ToolRegistry.spec("diagnostics")?.enabled == true, "diagnostics stays available")

        val resolver = dev.wind.agent.capability.CapabilityResolver({ null })
        val context = TaskContext("t1", "p1", 1L, CapabilityMode.ACCEPT, "t1")
        check(
            resolver.resolve(context, "diagnostics", JSONObject()) is dev.wind.agent.capability.CapabilityRequirement.Internal,
            "diagnostics {} stays internal"
        )
        for (source in listOf("lsp", "all")) {
            check(
                resolver.resolve(context, "diagnostics", JSONObject().put("source", source))
                    is dev.wind.agent.capability.CapabilityRequirement.Composite,
                "diagnostics(source=$source) needs execution authority for the server"
            )
        }
        for (tool in listOf("symbol_search", "semantic_search", "dependency_graph")) {
            check(
                resolver.resolve(context, tool, JSONObject()) is dev.wind.agent.capability.CapabilityRequirement.MirrorRead,
                "$tool only needs a mirror read"
            )
        }
        check(
            resolver.resolve(context, "find_definition", JSONObject())
                is dev.wind.agent.capability.CapabilityRequirement.MirrorRead,
            "index definition only needs a mirror read"
        )
        check(
            resolver.resolve(context, "find_references", JSONObject().put("backend", "lsp"))
                is dev.wind.agent.capability.CapabilityRequirement.Composite,
            "lsp lookup needs execution authority"
        )
    }

    private fun testLspRestartBudgetIsEnforcedPerManager() {
        val manager = LanguageServerManager(transportFactory = { FakeLspTransport(starts = false) })
        val key = LspSessionKey("task-budget", "p1", 1L, CodeLanguage.KOTLIN)
        repeat(CodeIntelLimits.LSP_MAX_RESTARTS_PER_WINDOW) {
            check(manager.getSession(key, "digest-1") is LspOutcome.Failure, "failed start returns failure")
        }
        val exhausted = manager.getSession(key, "digest-1")
        check(
            exhausted is LspOutcome.Failure &&
                exhausted.code == LspFailureCode.RESTART_BUDGET_EXHAUSTED,
            "start after configured failure limit is rejected by the production restart budget"
        )
        manager.stopAll()
        var failStart = false
        val evolving = LanguageServerManager(transportFactory = { FakeLspTransport(starts = !failStart) })
        for (revision in 1..6) {
            check(evolving.getSession(key, "edit-$revision") is LspOutcome.Success,
                "routine source edit $revision does not consume crash budget")
        }
        failStart = true
        repeat(CodeIntelLimits.LSP_MAX_RESTARTS_PER_WINDOW - 1) {
            check(evolving.getSession(key, "failed-edit") is LspOutcome.Failure, "failed start counted")
        }
        failStart = false
        check(evolving.getSession(key, "recovered-edit") is LspOutcome.Success, "restart can recover within budget")
        failStart = true
        check(evolving.getSession(key, "next-edit") is LspOutcome.Failure, "last allowed failed start counted")
        val afterRecovery = evolving.getSession(key, "next-edit")
        check(afterRecovery is LspOutcome.Failure && afterRecovery.code == LspFailureCode.RESTART_BUDGET_EXHAUSTED,
            "one successful recovery does not erase recent crash history")
        evolving.stopAll()
    }

    private fun testLspMutationGuard() {
        var digest = "digest-a"
        val guard = LspMutationGuard { digest }
        val before = guard.begin()
        check(!guard.finish(before).mutated, "stable digest is not a mutation")

        digest = "digest-a"
        val beforeChange = guard.begin()
        digest = "digest-b"
        val changed = guard.finish(beforeChange)
        check(changed.mutated, "digest change across the window is a mutation")
        check(changed.digestAfter == "digest-b", "the guard reports what it saw")

        digest = "digest-a"
        val beforeLoss = guard.begin()
        digest = "digest-a"
        check(!guard.finish(beforeLoss).mutated, "re-reading the same digest is not a mutation")

        val unreadable = LspMutationGuard { null }
        check(unreadable.finish(null).mutated, "an unreadable window fails closed")
        val lossy = LspMutationGuard { null }
        check(lossy.finish("digest-a").mutated, "losing the digest mid-window fails closed")
    }

    private fun testGuardedLspFailureAndBoundary() {
        var digest = "stable"
        var cleanups = 0
        var beforeDispatch = 0
        val operation = GuardedLspOperation<Unit>(
            LspMutationGuard { digest },
            "stable"
        ) { cleanups++ }
        val failed = runCatching {
            operation.run {
                digest = "mutated-during-initialize"
                error("fake initialize failure")
            }
        }
        check(failed.isFailure && cleanups == 1, "guard runs final digest check on start/init failure")

        val transport = object : LspTransport {
            override fun start(language: CodeLanguage, rootUri: String, token: CancellationToken, beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<Unit> {
                beforeDispatch?.verify()
                return LspOutcome.Success(Unit)
            }
            override fun request(method: String, params: JSONObject, timeoutMs: Long, token: CancellationToken, beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<JSONObject> {
                beforeDispatch?.verify()
                return LspOutcome.Success(JSONObject().put("result", JSONObject.NULL))
            }
            override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {}
            override fun executionRoot(): String? = "/mirror"
            override fun stop(): LspOutcome<Unit> = LspOutcome.Success(Unit)
        }
        val session = LspSession(
            LspSessionKey("t-boundary", "p-boundary", 1L, CodeLanguage.KOTLIN),
            "stable", transport
        )
        val started = session.start(beforeDispatch = ExecutionBoundaryVerifier { beforeDispatch++ })
        check(started is LspOutcome.Success && beforeDispatch == 1, "start revalidates immediately at dispatch")
        val requested = session.request("hover", JSONObject(), 1000L, CancellationToken(), ExecutionBoundaryVerifier { beforeDispatch++ })
        check(requested is LspOutcome.Success && beforeDispatch == 2, "request revalidates immediately at dispatch")
    }

    /**
     * Drives the production manager boundary with a fake P3 mirror and a real
     * LanguageServerManager, so start/init mutation and request mutation cannot
     * regress into a unit-only guard test.  The third case models an ordinary
     * prepare transition before the guard opens; it must not be reported as an
     * LSP mutation by diagnostics.
     */
    private fun testManagerLspLifecycleGuards() {
        val context = IntelligenceContext()
        val store = ProjectStore.get(context)
        store.saveProject(project())
        val router = WorkspaceRouter(context)
        val args = JSONObject()
            .put("backend", "lsp")
            .put("path", "workspace://src/Foo.kt")
            .put("line", 1)

        val requestMirror = FakeCodeIntelligenceMirror()
        val requestManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = requestMirror,
            indexBackend = MemoryCodeIndexBackend(),
            lspManager = LanguageServerManager {
                ManagerTestTransport(onRequest = { requestMirror.digest = "changed" })
            }
        )
        val requestResult = requestManager.findDefinition(args, router)
        check(requestResult.optString("error") == "CODE_INTEL_LSP_MUTATED_SOURCE",
            "manager rejects a source mutation after a normal LSP response")
        check(requestMirror.mutationMarks == 1, "manager invokes the mutation fuse exactly once")

        val startMirror = FakeCodeIntelligenceMirror()
        val startManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = startMirror,
            indexBackend = MemoryCodeIndexBackend(),
            lspManager = LanguageServerManager {
                ManagerTestTransport(
                    onStart = { startMirror.digest = "changed-during-initialize" },
                    startFailure = LspFailureCode.INITIALIZE_FAILED
                )
            }
        )
        val startResult = startManager.findDefinition(args, router)
        check(startResult.optString("error") == "CODE_INTEL_LSP_MUTATED_SOURCE",
            "manager checks the digest even when start/initialize fails")
        check(startMirror.mutationMarks == 1, "start failure reaches the same cleanup fuse")

        val prepareMirror = FakeCodeIntelligenceMirror().also {
            it.digest = "prepare-before"
            it.transitionOnObserve = true
        }
        val diagnosticsManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = prepareMirror,
            indexBackend = MemoryCodeIndexBackend(),
            lspManager = LanguageServerManager { ManagerTestTransport() }
        )
        val diagnostics = diagnosticsManager.diagnostics(
            JSONObject().put("source", "lsp").put("path", "workspace://src/Foo.kt"), router
        )
        check(diagnostics.optBoolean("ok") &&
            diagnostics.optString("error") != "CODE_INTEL_LSP_MUTATED_SOURCE",
            "normal prepare transition is outside the LSP mutation window")
    }

    /**
     * The source revision is established before the LSP guard opens.  A live
     * mirror change in that narrow interval must stop the request before the
     * transport is started; otherwise a B-snapshot response could be labelled
     * with the A-snapshot revision token.
     */
    private fun testManagerLspRevisionToctou() {
        val context = IntelligenceContext()
        val store = ProjectStore.get(context)
        store.saveProject(project())
        val router = WorkspaceRouter(context)
        val mirror = FakeCodeIntelligenceMirror().also {
            it.changeDigestOnReadNumber = 3
            it.nextDigestOnRead = "changed-before-lsp"
        }
        var starts = 0
        val manager = CodeIntelligenceManager(
            context = context,
            mirrorManager = mirror,
            indexBackend = MemoryCodeIndexBackend(),
            lspManager = LanguageServerManager {
                starts++
                ManagerTestTransport()
            }
        )
        val result = manager.findDefinition(
            JSONObject().put("backend", "lsp").put("path", "workspace://src/Foo.kt").put("line", 1),
            router
        )
        check(result.optString("error") == "CODE_INTEL_SOURCE_MOVED",
            "a revision change before guard.begin must fail closed as source moved")
        check(result.optString("revision_token").contains("changed-before-lsp"),
            "source-moved result must carry the newly observed revision")
        check(starts == 0, "LSP transport must not start after the revision TOCTOU is detected")
    }

    /**
     * Drives the manager's real tool methods at the two remaining freeze
     * boundaries: cursor validation/source movement and the final result-size
     * post-condition.  These assertions intentionally do not call the budget
     * helper directly; deleting the manager return gate must make them fail.
     */
    private fun testManagerBoundaryPostConditions() {
        val context = IntelligenceContext()
        val store = ProjectStore.get(context)
        store.saveProject(project())
        val router = WorkspaceRouter(context)

        val cursorManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = FakeCodeIntelligenceMirror(),
            indexBackend = MemoryCodeIndexBackend()
        )
        val stale = cursorManager.findReferences(
            JSONObject()
                .put("cursor", CursorPayload("different-query", "stable", 0, 0).encode()),
            router
        )
        check(stale.optString("error") == "CODE_INTEL_CURSOR_STALE",
            "manager rejects a stale cursor at the production tool boundary")

        val movedMirror = FakeCodeIntelligenceMirror().also {
            it.digest = "before-move"
            it.changeDigestOnCurrentRead = "after-move"
        }
        val movedManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = movedMirror,
            indexBackend = MemoryCodeIndexBackend()
        )
        val moved = movedManager.symbolSearch(JSONObject().put("query", "x"), router)
        check(moved.optString("error") == "CODE_INTEL_SOURCE_MOVED",
            "manager reports source movement when the live mirror changes")
        check(moved.optString("revision_token").contains("after-move"),
            "source-moved result is rebound to the latest observed revision")

        val oversizedBackend = OversizedSymbolBackend()
        val budgetManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = FakeCodeIntelligenceMirror(),
            indexBackend = oversizedBackend
        )
        val bounded = budgetManager.symbolSearch(JSONObject().put("query", "x"), router)
        check(oversizedBackend.searchCalls > 0,
            "manager budget test reached the real index backend")
        check(CodeIntelResultBudget.size(bounded) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES,
            "manager symbolSearch output is bounded at the production return")
        check(bounded.optBoolean("truncated"),
            "manager symbolSearch states truncation after shrinking an oversized result")

        val graphManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = FakeCodeIntelligenceMirror(),
            indexBackend = oversizedBackend
        )
        val boundedGraph = graphManager.dependencyGraph(JSONObject(), router)
        check(CodeIntelResultBudget.size(boundedGraph) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES,
            "manager dependencyGraph output is bounded at the production return")
        check(boundedGraph.optJSONObject("graph")?.optBoolean("truncated") == true,
            "manager dependencyGraph states truncation after shrinking an oversized graph")

        val diagnosticManager = CodeIntelligenceManager(
            context = context,
            mirrorManager = FakeCodeIntelligenceMirror(),
            indexBackend = MemoryCodeIndexBackend(),
            lspManager = LanguageServerManager {
                ManagerTestTransport(diagnosticCount = 60, diagnosticMessageSize = 2200)
            }
        )
        val boundedDiagnostics = diagnosticManager.diagnostics(
            JSONObject().put("source", "lsp").put("path", "workspace://src/Foo.kt"), router
        )
        check(CodeIntelResultBudget.size(boundedDiagnostics) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES,
            "manager diagnostics output is bounded at the production return")
        check(boundedDiagnostics.optBoolean("truncated"),
            "manager diagnostics states truncation after shrinking an oversized result")
    }

    private fun testResultBudgetIsAFinalPostCondition() {
        fun oversized(key: String): JSONObject {
            val array = JSONArray()
            for (index in 0 until 200) {
                array.put(JSONObject().put("path", "workspace://src/File$index.kt")
                    .put("message", "x".repeat(2000)))
            }
            return JSONObject().put("ok", true).put("truncated", false).put(key, array)
        }

        val results = oversized("results")
        check(CodeIntelResultBudget.size(results) > CodeIntelLimits.MAX_TOOL_RESULT_BYTES, "payload starts over budget")
        val boundedResults = CodeIntelligenceManager.enforceResultBudgetBoundary(results)
        check(CodeIntelResultBudget.size(boundedResults) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES, "results payload shrunk under budget")
        check(boundedResults.optBoolean("truncated"), "results truncation is stated")

        val diagnostics = oversized("diagnostics")
        val boundedDiagnostics = CodeIntelResultBudget.enforce(diagnostics)
        check(CodeIntelResultBudget.size(boundedDiagnostics) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES, "diagnostics payload shrunk under budget")
        check(boundedDiagnostics.optBoolean("truncated"), "diagnostics truncation is stated")

        val graph = JSONObject().put("ok", true).put("graph", JSONObject()
            .put("nodes", JSONArray().apply { for (i in 0 until 400) put(JSONObject().put("label", "node$i")) })
            .put("edges", JSONArray().apply { for (i in 0 until 400) put(JSONObject().put("label", "edge$i")) }))
        val boundedGraph = CodeIntelResultBudget.enforce(graph)
        check(CodeIntelResultBudget.size(boundedGraph) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES, "graph payload shrunk under budget")

        val report = JSONObject().put("ok", true).put("source", "validation").put("report", JSONObject()
            .put("errors", JSONArray().apply { for (i in 0 until 200) put(JSONObject().put("message", "y".repeat(1000))) })
            .put("warnings", JSONArray())
            .put("rawTail", "z".repeat(60000)))
        val boundedReport = CodeIntelResultBudget.enforce(report)
        check(CodeIntelResultBudget.size(boundedReport) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES, "validation report payload shrunk under budget")

        val unbounded = JSONObject().put("ok", true).put("blob", "q".repeat(CodeIntelLimits.MAX_TOOL_RESULT_BYTES + 100))
        val refused = CodeIntelResultBudget.enforce(unbounded)
        check(refused.optString("error") == "RESULT_BUDGET_EXCEEDED", "an unshrinkable payload becomes an explicit failure")
        check(CodeIntelResultBudget.size(refused) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES, "the refusal itself is small")
    }

    private fun testCandidateObservationIntegrity() {
        val backend = MemoryCodeIndexBackend()
        val revision = SourceRevision("p1", 1L, "digest-clean", 0)
        val files = mapOf("main/src/Foo.kt" to "class Foo {}".toByteArray(Charsets.UTF_8))
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(files.getValue("main/src/Foo.kt"))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(
            backend.buildCandidateWithManifest(
                "cand-manifest-ok", revision,
                { files[it] },
                listOf(IndexManifestFile("main/src/Foo.kt", files.getValue("main/src/Foo.kt").size.toLong(), sha))
            ),
            "candidate accepts a matching P3 manifest entry"
        )
        backend.discardCandidate("cand-manifest-ok")
        check(
            !backend.buildCandidateWithManifest(
                "cand-manifest-bad", revision,
                { files[it] },
                listOf(IndexManifestFile("main/src/Foo.kt", files.getValue("main/src/Foo.kt").size.toLong(), "0".repeat(64)))
            ),
            "candidate rejects a file whose live bytes do not match the P3 manifest"
        )
        val candidate = "cand-test"
        check(backend.buildCandidate(candidate, revision, { files[it] }, files.keys.toList()), "candidate built")
        check(
            !backend.commitCandidate(candidate, revision, SourceRevision("p1", 1L, "digest-moved", 0)),
            "candidate refused when the digest moved during the build"
        )
        backend.discardCandidate(candidate)
        check(backend.status(revision).phase != CodeIndexPhase.READY, "a discarded candidate never becomes the index")
    }

    /**
     * Positive regression for manager's real tool entry with Linux runtime LSP transport.
     * Validates that when mirror binding is VEGA_LINUX:
     * 1. runtimeLspFactory is selected and receives exact session key & runtime binding;
     * 2. termuxLspFactory is never invoked (does not fall back to Termux);
     * 3. nested mount with spaces and Unicode in executionPath correctly produces exact key.executionPath;
     * 4. fakeMirror.phase = MirrorPhase.PENDING proves manager supports edited mirror state;
     * 5. both diagnostics(source=lsp) and findDefinition(backend=lsp) succeed through the Linux transport.
     */
    private fun testManagerLinuxRuntimeLspPositiveRegression() {
        val context = IntelligenceContext()
        val store = ProjectStore.get(context)

        val nestedUnicodeMount = WorkspaceMount(
            id = "m-nested-unicode",
            alias = "nested",
            treeUri = "content://dummy/nested%20app",
            displayName = "Nested App 模块",
            mode = MountMode.READ_WRITE,
            executionPath = "modules/app space 模块",
            isPrimary = false
        )
        val draft = VegaProject(
            id = "proj-linux-pos",
            name = "Linux Pos Project",
            primaryMountId = main.id,
            mounts = listOf(main, nestedUnicodeMount),
            generation = 77L
        )
        val proj = store.saveProject(draft)
        store.setActiveProject(proj.id)
        val router = WorkspaceRouter(context)

        val linuxMirror = FakeCodeIntelligenceMirror().also {
            it.digest = "stable-linux-digest"
            it.phase = MirrorPhase.PENDING
            it.binding = RuntimeBinding(
                providerId = RuntimeProviderId.VEGA_LINUX,
                workspaceId = ProjectIdentity.remoteId(proj.id),
                projectId = proj.id,
                rootGeneration = proj.generation,
                runtimeInstanceId = "linux-instance-xyz-987"
            )
        }

        var termuxFactoryCalled = false
        var capturedKey: LspSessionKey? = null
        var capturedBinding: RuntimeBinding? = null
        var transportStarts = 0
        var transportRequests = 0

        val manager = CodeIntelligenceManager(
            context = context,
            mirrorManager = linuxMirror,
            indexBackend = MemoryCodeIndexBackend(),
            termuxLspFactory = { key ->
                termuxFactoryCalled = true
                throw AssertionError("termuxLspFactory must never be reached for VEGA_LINUX binding: $key")
            },
            runtimeLspFactory = { key, binding ->
                capturedKey = key
                capturedBinding = binding
                ManagerTestTransport(
                    onStart = { transportStarts++ },
                    onRequest = { transportRequests++ },
                    diagnosticCount = 2,
                    diagnosticMessageSize = 30
                )
            }
        )

        // 1. Test diagnostics via lsp on nested Unicode executionPath mount
        val diagResult = manager.diagnostics(
            JSONObject()
                .put("source", "lsp")
                .put("path", "project://nested/src/Main.kt"),
            router
        )
        check(diagResult.optBoolean("ok") && diagResult.getJSONArray("diagnostics").length() == 2,
            "diagnostics via Linux transport must return actual diagnostic results")
        check(!termuxFactoryCalled, "must not fall back to Termux for diagnostics")
        check(transportStarts > 0, "Linux transport must be started for diagnostics")
        check(capturedKey != null, "runtimeLspFactory must capture session key")
        check(capturedKey?.projectId == proj.id, "capturedKey.projectId must match active project")
        check(capturedKey?.rootGeneration == proj.generation, "capturedKey.rootGeneration must match active project")
        check(capturedKey?.language == CodeLanguage.KOTLIN, "capturedKey.language must match file extension")
        check(capturedKey?.executionPath == "modules/app space 模块",
            "capturedKey.executionPath must match nested mount executionPath with spaces and Unicode")
        check(capturedBinding?.providerId == RuntimeProviderId.VEGA_LINUX,
            "capturedBinding.providerId must be VEGA_LINUX")
        check(capturedBinding?.workspaceId == ProjectIdentity.remoteId(proj.id),
            "capturedBinding.workspaceId must match project workspaceId")
        check(capturedBinding?.runtimeInstanceId == "linux-instance-xyz-987",
            "capturedBinding.runtimeInstanceId must match binding identity")

        // 2. Test findDefinition via lsp on nested mount
        val defResult = manager.findDefinition(
            JSONObject()
                .put("backend", "lsp")
                .put("path", "project://nested/src/Main.kt")
                .put("line", 10),
            router
        )
        check(defResult.optString("error").isEmpty(), "findDefinition via Linux transport must not error")
        check(!termuxFactoryCalled, "must not fall back to Termux for findDefinition")
        check(capturedKey?.executionPath == "modules/app space 模块",
            "findDefinition must route to exact nested mount executionPath")
        check(transportRequests == 2, "both diagnostic and definition requests reached the Linux transport")
        manager.lspManager.stopAll()
    }

    /**
     * Quota and LRU eviction regression test for LanguageServerManager.
     * Verifies:
     * 1. 5 supported languages sequential getSession calls bound active sessions <= 3 (leaving 1 slot for build/test);
     * 2. All 5 languages are dispatched over time with LRU eviction of older servers;
     * 3. Cache hits reuse existing sessions without instantiating new transports;
     * 4. If eviction stop fails, replacement factory is never invoked and restart budget is not consumed;
     * 5. stopAll retries and cleans up remaining sessions.
     */
    private fun testLspSessionQuotaAndLruEviction() {
        var active = 0
        var maxActive = 0
        var totalStarts = 0
        val stoppedLanguages = mutableListOf<CodeLanguage>()
        val transports = mutableMapOf<CodeLanguage, QuotaTrackingTransport>()
        var factoryCalls = 0

        val manager = LanguageServerManager(transportFactory = { key ->
            factoryCalls++
            val transport = QuotaTrackingTransport(
                language = key.language,
                onStart = {
                    active++
                    totalStarts++
                    if (active > maxActive) maxActive = active
                },
                onStop = {
                    active--
                    stoppedLanguages.add(key.language)
                }
            )
            transports[key.language] = transport
            transport
        })

        val languages = listOf(
            CodeLanguage.KOTLIN,
            CodeLanguage.JAVA,
            CodeLanguage.PYTHON,
            CodeLanguage.TYPESCRIPT,
            CodeLanguage.JAVASCRIPT
        )

        // 1. Sequentially request all 5 languages
        for (lang in languages) {
            val key = LspSessionKey("task-quota", "p1", 1L, lang)
            val sessionOutcome = manager.getSession(key, "digest-quota")
            check(sessionOutcome is LspOutcome.Success, "getSession for $lang must succeed")
            check(active <= CodeIntelLimits.MAX_CACHED_LSP_SESSIONS,
                "active LSP sessions ($active) must not exceed limit (" + CodeIntelLimits.MAX_CACHED_LSP_SESSIONS + ")")
            check(maxActive <= CodeIntelLimits.MAX_CACHED_LSP_SESSIONS,
                "max concurrent active sessions ($maxActive) must stay <= " + CodeIntelLimits.MAX_CACHED_LSP_SESSIONS)
        }

        // Check quota invariants after 5 dispatches
        check(maxActive == CodeIntelLimits.MAX_CACHED_LSP_SESSIONS,
            "maxActive reached exact cache quota limit (" + CodeIntelLimits.MAX_CACHED_LSP_SESSIONS + ")")
        check(active == CodeIntelLimits.MAX_CACHED_LSP_SESSIONS,
            "currently active sessions must be exactly 3, leaving at least 1 slot available in supervisor")
        check(active < dev.wind.agent.runtime.RuntimeProcessSupervisor.MAX_ACTIVE_EXECUTIONS,
            "cached servers must leave a slot in the production supervisor")
        check(totalStarts == 5, "all 5 languages must have been dispatched over time")
        check(stoppedLanguages.size == 2, "earliest 2 languages must have been LRU-evicted and stopped")
        check(stoppedLanguages == listOf(CodeLanguage.KOTLIN, CodeLanguage.JAVA),
            "evicted languages must strictly follow LRU order (KOTLIN first, then JAVA)")

        // 2. Cache reuse: querying an active cached session does not invoke factory or create new start
        val jsKey = LspSessionKey("task-quota", "p1", 1L, CodeLanguage.JAVASCRIPT)
        val startsBeforeReuse = totalStarts
        val factoryCallsBefore = factoryCalls
        val reuseOutcome = manager.getSession(jsKey, "digest-quota")
        check(reuseOutcome is LspOutcome.Success, "reused session must succeed")
        check(totalStarts == startsBeforeReuse, "cached session reuse must not trigger new start")
        check(factoryCalls == factoryCallsBefore, "cached session reuse must not call transport factory")

        // 3. Eviction failure: if LRU candidate fails to stop, replacement factory must NOT be called
        // Currently cached in LRU order: PYTHON (oldest), TYPESCRIPT, JAVASCRIPT
        val pythonTransport = transports[CodeLanguage.PYTHON]
        check(pythonTransport != null, "python transport must exist in cache")
        pythonTransport?.failStop = true

        val reintroduceKotlinKey = LspSessionKey("task-quota", "p1", 1L, CodeLanguage.KOTLIN)
        val factoryCallsBeforeFail = factoryCalls
        val evictFailOutcome = manager.getSession(reintroduceKotlinKey, "digest-quota")
        check(evictFailOutcome is LspOutcome.Failure && evictFailOutcome.detail == "stop failed",
            "getSession must fail closed when LRU eviction stop fails")
        check(factoryCalls == factoryCallsBeforeFail,
            "new transport factory must not be called when eviction stop fails")

        // And verify restart budget was NOT consumed by the eviction stop failure
        pythonTransport?.failStop = false
        val retryKotlinOutcome = manager.getSession(reintroduceKotlinKey, "digest-quota")
        check(retryKotlinOutcome is LspOutcome.Success,
            "retry after fixing stop must succeed without hitting restart budget exhaustion")

        // 4. stopAll clean retry and shutdown
        manager.stopAll()
        check(active == 0, "stopAll must stop all remaining active sessions")
    }

    private class QuotaTrackingTransport(
        val language: CodeLanguage,
        private val onStart: () -> Unit,
        private val onStop: () -> Unit,
        var failStop: Boolean = false
    ) : LspTransport {
        override fun start(
            language: CodeLanguage,
            rootUri: String,
            token: CancellationToken,
            beforeDispatch: ExecutionBoundaryVerifier?
        ): LspOutcome<Unit> {
            onStart()
            return LspOutcome.Success(Unit)
        }

        override fun request(
            method: String,
            params: JSONObject,
            timeoutMs: Long,
            token: CancellationToken,
            beforeDispatch: ExecutionBoundaryVerifier?
        ): LspOutcome<JSONObject> = LspOutcome.Success(JSONObject())

        override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {}
        override fun executionRoot(): String = "/mirror"

        override fun stop(): LspOutcome<Unit> {
            if (failStop) {
                return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "stop failed")
            }
            onStop()
            return LspOutcome.Success(Unit)
        }
    }

    /** Backend decorator that makes the manager produce a genuinely oversized
     * symbol result while retaining the normal candidate/ready lifecycle. */
    private class OversizedSymbolBackend : CodeIndexBackend {
        private val delegate = MemoryCodeIndexBackend()
        var searchCalls: Int = 0

        override fun status(revision: SourceRevision): CodeIndexStatus = delegate.status(revision)

        override fun buildCandidate(
            candidateId: String,
            revision: SourceRevision,
            fileReader: (String) -> ByteArray?,
            manifestFiles: List<String>,
            token: CancellationToken
        ): Boolean = delegate.buildCandidate(candidateId, revision, fileReader, manifestFiles, token)

        override fun commitCandidate(
            candidateId: String,
            revisionBefore: SourceRevision,
            revisionAfter: SourceRevision
        ): Boolean = delegate.commitCandidate(candidateId, revisionBefore, revisionAfter)

        override fun discardCandidate(candidateId: String) = delegate.discardCandidate(candidateId)

        override fun ensureFresh(
            revision: SourceRevision,
            fileReader: (String) -> ByteArray?,
            manifestFiles: List<String>,
            token: CancellationToken
        ): Boolean = delegate.ensureFresh(revision, fileReader, manifestFiles, token)

        override fun searchSymbols(
            query: String,
            kinds: Set<SymbolKind>,
            language: CodeLanguage?,
            pathPrefix: String?,
            limit: Int,
            offset: Int,
            revision: SourceRevision
        ): List<SymbolLocation> {
            searchCalls++
            val count = maxOf(limit, CodeIntelLimits.MAX_SYMBOL_RESULTS + 1)
            return (0 until count).map { index ->
                SymbolLocation(
                    path = "main/src/File$index.kt",
                    range = CodeRange(CodePosition(1, 1), CodePosition(1, 2)),
                    symbol = "x$index",
                    kind = SymbolKind.CLASS,
                    confidence = IntelligenceConfidence.LEXICAL,
                    snippet = "oversized-${"x".repeat(2200)}"
                )
            }
        }

        override fun findDefinitions(
            path: String?, line: Int?, column: Int?, symbolId: String?, revision: SourceRevision
        ): List<SymbolLocation> = delegate.findDefinitions(path, line, column, symbolId, revision)

        override fun findReferences(
            path: String?, line: Int?, column: Int?, symbolId: String?, includeDeclaration: Boolean,
            limit: Int, offset: Int, revision: SourceRevision
        ): List<ReferenceLocation> = delegate.findReferences(
            path, line, column, symbolId, includeDeclaration, limit, offset, revision
        )

        override fun semanticSearch(
            query: String, limit: Int, revision: SourceRevision
        ): List<SemanticSearchResult> = delegate.semanticSearch(query, limit, revision)

        override fun dependencyGraph(revision: SourceRevision): DependencyGraph {
            val nodes = (0 until 1000).map { index ->
                DependencyNode(
                    id = "node-$index",
                    kind = DependencyNodeKind.EXTERNAL_DEPENDENCY,
                    label = "node-${"x".repeat(180)}-$index"
                )
            }
            val edges = (0 until 1000).map { index ->
                DependencyEdge(
                    sourceId = "node-$index",
                    targetId = "node-${(index + 1) % 1000}",
                    kind = DependencyEdgeKind.DEPENDS_ON,
                    label = "edge-${"y".repeat(180)}"
                )
            }
            return DependencyGraph(nodes, edges)
        }

        override fun markDirty(projectId: String) = delegate.markDirty(projectId)

        override fun invalidateAll(projectId: String) = delegate.invalidateAll(projectId)
    }

    private class ManagerTestTransport(
        private val onStart: () -> Unit = {},
        private val onRequest: () -> Unit = {},
        private val startFailure: LspFailureCode? = null,
        private val diagnosticCount: Int = 0,
        private val diagnosticMessageSize: Int = 0
    ) : LspTransport {
        override fun start(
            language: CodeLanguage,
            rootUri: String,
            token: CancellationToken,
            beforeDispatch: ExecutionBoundaryVerifier?
        ): LspOutcome<Unit> {
            beforeDispatch?.verify()
            onStart()
            return startFailure?.let { LspOutcome.Failure(it) } ?: LspOutcome.Success(Unit)
        }

        override fun request(
            method: String,
            params: JSONObject,
            timeoutMs: Long,
            token: CancellationToken,
            beforeDispatch: ExecutionBoundaryVerifier?
        ): LspOutcome<JSONObject> {
            beforeDispatch?.verify()
            onRequest()
            if (method == "textDocument/diagnostic" && diagnosticCount > 0) {
                val items = JSONArray()
                repeat(diagnosticCount) { index ->
                    items.put(JSONObject()
                        .put("message", "diagnostic-$index-${"d".repeat(diagnosticMessageSize)}")
                        .put("severity", 1)
                        .put("range", JSONObject().put(
                            "start", JSONObject().put("line", 0).put("character", 0))))
                }
                return LspOutcome.Success(JSONObject().put("result", items))
            }
            return LspOutcome.Success(JSONObject().put("result", JSONArray()))
        }

        override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {}
        override fun executionRoot(): String = "/mirror"
        override fun stop(): LspOutcome<Unit> = LspOutcome.Success(Unit)
    }

    private class FakeCodeIntelligenceMirror : CodeIntelligenceMirror {
        var digest: String = "stable"
        var transitionOnObserve: Boolean = false
        var changeDigestOnCurrentRead: String? = null
        var changeDigestOnReadNumber: Int? = null
        var nextDigestOnRead: String = "changed"
        private var currentReadCount: Int = 0
        var mutationMarks: Int = 0
        var phase: MirrorPhase = MirrorPhase.CLEAN

        override fun observeForCodeIntelligence(
            router: WorkspaceRouter,
            token: CancellationToken
        ): ObservedMirrorRevision {
            if (transitionOnObserve) {
                transitionOnObserve = false
                digest = "stable-after-prepare"
            }
            val active = router.getActiveProject()
            return ObservedMirrorRevision(active.id, active.generation, phase, digest, digest)
        }

        override fun markUnexpectedMirrorMutation(reason: String) {
            mutationMarks++
        }

        override fun currentMirrorDigest(token: CancellationToken?): String {
            currentReadCount++
            if (changeDigestOnReadNumber == currentReadCount) {
                digest = nextDigestOnRead
            }
            changeDigestOnCurrentRead?.let {
                digest = it
                changeDigestOnCurrentRead = null
            }
            return digest
        }
        override fun getMirrorManifest(token: CancellationToken?): MirrorManifestSummary =
            MirrorManifestSummary(digest, emptyMap())
        override fun readMirrorText(executionRelativePath: String, token: CancellationToken?): String? = null

        /**
         * Code Intelligence resolves its provider through this, so a fake has to
         * answer it too. The default is a Termux binding, which is what every
         * pre-Closure-2 test assumed; [bindingFailure] is how the fail-closed
         * tests make authority resolution itself fail.
         */
        var binding: RuntimeBinding = RuntimeBinding(
            providerId = RuntimeProviderId.TERMUX,
            workspaceId = "0123456789abcdef01234567",
            projectId = "project-1",
            rootGeneration = 1L,
            runtimeInstanceId = "termux-instance"
        )
        var bindingFailure: String? = null
        var bindingRequests: Int = 0

        override fun requireRuntimeBindingForCodeIntelligence(
            projectId: String,
            rootGeneration: Long
        ): RuntimeBinding {
            bindingRequests++
            bindingFailure?.let { throw IllegalStateException(it) }
            return binding
        }
    }

    /** Minimal context used to give ProjectStore a durable temp directory in manager tests. */
    private class IntelligenceContext : android.content.ContextWrapper(null) {
        private val dir = File(
            System.getProperty("java.io.tmpdir"), "vega-intelligence-" + System.nanoTime()
        ).also { it.mkdirs() }
        private val preferences = IntelligencePreferences()

        override fun getApplicationContext(): android.content.Context = this
        override fun getFilesDir(): File = dir
        override fun getCacheDir(): File = dir
        override fun getExternalFilesDir(type: String?): File = dir
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = preferences
        override fun getPackageName(): String = "dev.wind.agent"
    }

    private class IntelligencePreferences : android.content.SharedPreferences {
        private val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): android.content.SharedPreferences.Editor = Editor(values)

        private class Editor(private val values: MutableMap<String, Any?>) : android.content.SharedPreferences.Editor {
            override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putStringSet(key: String, value: MutableSet<String>?): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putFloat(key: String, value: Float): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): android.content.SharedPreferences.Editor { values[key] = value; return this }
            override fun remove(key: String): android.content.SharedPreferences.Editor { values.remove(key); return this }
            override fun clear(): android.content.SharedPreferences.Editor { values.clear(); return this }
            override fun apply() {}
            override fun commit(): Boolean = true
        }
    }

    private inline fun <reified T : Throwable> assertThrows(message: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected ${T::class.simpleName}, nothing thrown: $message")
        } catch (error: Throwable) {
            if (error !is T) {
                throw AssertionError("expected ${T::class.simpleName}, caught ${error::class.simpleName}: $message", error)
            }
            passed++
        }
    }
}
