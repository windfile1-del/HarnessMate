package dev.wind.agent.intelligence

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.coding.CodingTaskRuntime
import dev.wind.agent.coding.Diagnostic
import dev.wind.agent.coding.DiagnosticSeverity
import dev.wind.agent.intelligence.dependency.DependencyGraphBuilder
import dev.wind.agent.intelligence.index.*
import dev.wind.agent.intelligence.lsp.*
import dev.wind.agent.intelligence.search.StructuralSemanticSearch
import dev.wind.agent.project.ProjectIdentity
import dev.wind.agent.project.VegaProject
import dev.wind.agent.project.WorkspaceRouter
import dev.wind.agent.project.ProjectPathParser
import dev.wind.agent.runtime.RuntimeProviderId
import dev.wind.agent.shell.mirror.CodeIntelligenceMirror
import dev.wind.agent.runtime.RuntimeBinding
import dev.wind.agent.shell.mirror.WorkspaceIgnorePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only Code Intelligence on top of the P3 execution mirror.
 *
 * Nothing here writes code, and nothing here starts a second model loop or a
 * second execution substrate: the index reads the mirror through P3, and every
 * language server runs inside that same mirror through its authoritative runtime binding.
 */

class CodeIntelligenceManager(
    private val context: Context,
    val mirrorManager: CodeIntelligenceMirror,
    val indexBackend: CodeIndexBackend = MemoryCodeIndexBackend(),
    lspManager: LanguageServerManager? = null,
    val revisionOracle: SourceRevisionOracle = SourceRevisionOracle(mirrorManager),
    /**
     * How a Termux-bound project starts a language server.
     *
     * Injectable so the zero-Termux boundary is provable rather than asserted:
     * a test can supply a factory that fails the test if it is ever reached, and
     * then show that a companion-bound or authority-less project never reaches
     * it. Production keeps the default, so the seam cannot change behaviour.
     */
    private val termuxLspFactory: (LspSessionKey) -> LspTransport = { key ->
        TermuxLspTransport(
            context,
            { activeWorkspaceId(context) },
            TrustedLspSessionId.forKey(key),
            rootTaskId = key.rootTaskId,
            projectId = key.projectId,
            rootGeneration = key.rootGeneration
        )
    },
    private val runtimeLspFactory: (LspSessionKey, RuntimeBinding) -> LspTransport = { key, binding ->
        RuntimeLspTransport(context, binding, key)
    }
) {
    /**
     * The manager every call actually uses. Tests may inject one; otherwise it
     * resolves each session's transport through the durable binding.
     */
    val lspManager: LanguageServerManager = lspManager
        ?: LanguageServerManager { key -> resolveTransport(key) }

    /**
     * Chooses the language-server transport for one session key, failing closed.
     *
     * The provider comes from the durable binding the P3 state machine owns -
     * not from a fresh read of the state file, and never from a default. A
     * project whose authority cannot be established gets a transport that
     * reports unavailable forever, which is the only safe answer: falling back
     * to Termux would start a second execution substrate under a companion-bound
     * project, and the mirror the index just read would not be the mirror that
     * server edits.
     */
    private fun resolveTransport(key: LspSessionKey): LspTransport {
        val binding = runCatching {
            mirrorManager.requireRuntimeBindingForCodeIntelligence(key.projectId, key.rootGeneration)
        }.getOrNull() ?: return AuthorityUnavailableLspTransport("LSP_RUNTIME_AUTHORITY_UNAVAILABLE")
        return when (binding.providerId) {
            RuntimeProviderId.VEGA_COMPANION -> DefaultDisabledLspTransport()
            RuntimeProviderId.VEGA_LINUX -> runtimeLspFactory(key, binding)
            RuntimeProviderId.TERMUX -> termuxLspFactory(key)
        }
    }

    private val semanticSearch = StructuralSemanticSearch(indexBackend)
    private val dependencyGraphBuilder = DependencyGraphBuilder(indexBackend)
    private val revisionFailure = ThreadLocal<String?>()

    fun onToolCompleted(name: String, ok: Boolean) {
        lspManager.evictIdleSessions()
        if (ok && name in MUTATING_TOOL_NAMES) {
            runCatching {
                val router = WorkspaceRouter(context)
                indexBackend.markDirty(router.getActiveProject().id)
            }
        }
    }

    // ------------------------------------------------------------------ tools

    fun findDefinition(
        args: JSONObject,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject {
        val proj = router.getActiveProject()
        val pathMapper = CodePathMapper(proj)
        val rawPath = args.optString("path", "").takeIf { it.isNotBlank() }
        val line = args.optInt("line", -1).takeIf { it > 0 }
        val column = args.optInt("column", -1).takeIf { it > 0 }
        val symbolId = args.optString("symbol_id", "").takeIf { it.isNotBlank() }

        val revision = resolveRevision(router, codingTaskRuntime, token) ?: return revisionUnavailable()
        if (!ensureIndexFresh(revision, codingTaskRuntime, token)) {
            return errorSourceMoved(revisionOracle.currentRevision(router, codingTaskRuntime, token, beforeSideEffect))
        }

        val backend = args.optString("backend", "index").lowercase()
        if (backend !in SUPPORTED_BACKENDS) return error("CODE_INTEL_BACKEND_UNSUPPORTED", revision)
        if (backend == "lsp") {
            if (rawPath == null || line == null) return lspUnavailable()
            val guard = LspMutationGuard { liveDigest(token) }
            val digestBefore = guard.begin()
            if (digestBefore == null) return revisionUnavailable()
            if (digestBefore != revision.sourceDigest) {
                return sourceMovedAfterRevision(revision, router, codingTaskRuntime, token)
            }
            val operation = GuardedLspOperation<LspOutcome<List<SymbolLocation>>>(
                guard, digestBefore
            ) { handleLspMutation(revision, codingTaskRuntime) }
            val guarded = try {
                operation.run {
                    val sessionOutcome = openSession(proj, codingTaskRuntime, rawPath, revision, token, digestBefore, beforeSideEffect)
                    if (sessionOutcome is LspOutcome.Failure) {
                        sessionOutcome
                    } else {
                        val session = (sessionOutcome as LspOutcome.Success).value
                        val sanitizer = LspPathSanitizer(pathMapper)
                        val uri = sanitizer.virtualPathToUri(rawPath, session.executionRoot())
                        if (uri == null) {
                            LspOutcome.Failure(LspFailureCode.PATH_REJECTED)
                        } else {
                            lspManager.findDefinitions(session, uri, line, column ?: 1, sanitizer, token, beforeSideEffect)
                        }
                    }
                }
            } catch (error: Exception) {
                return error("LSP_UNAVAILABLE", revision)
            }
            if (guarded is GuardedLspOperation.Result.Mutated) return error("CODE_INTEL_LSP_MUTATED_SOURCE", revision)
            val defsOutcome: LspOutcome<List<SymbolLocation>> =
                (guarded as GuardedLspOperation.Result.Value<LspOutcome<List<SymbolLocation>>>).value
            if (defsOutcome is LspOutcome.Failure) return lspFailure(defsOutcome, revision, LspPathSanitizer(pathMapper))
            val defs = (defsOutcome as LspOutcome.Success<List<SymbolLocation>>).value
            val array = JSONArray()
            defs.take(CodeIntelLimits.MAX_DEFINITIONS).forEach { array.put(it.toJson()) }
            return result("lsp", "EXACT", revision,
                JSONObject().put("results", array)
                    .put("truncated", defs.size > CodeIntelLimits.MAX_DEFINITIONS))
        }

        val execRelPath = rawPath?.let { pathMapper.toExecutionRelative(it) }
        val defs = indexBackend.findDefinitions(execRelPath, line, column, symbolId, revision)
        stableFailure(revision, codingTaskRuntime, token)?.let { return it }
        val mapped = defs.map { it.copy(path = pathMapper.toVirtualPath(it.path)) }
        val array = JSONArray()
        mapped.take(CodeIntelLimits.MAX_DEFINITIONS).forEach { array.put(it.toJson()) }
        return result("index", "LEXICAL", revision,
            JSONObject().put("results", array)
                .put("truncated", mapped.size > CodeIntelLimits.MAX_DEFINITIONS))
    }

    fun findReferences(
        args: JSONObject,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject {
        val proj = router.getActiveProject()
        val pathMapper = CodePathMapper(proj)
        val rawPath = args.optString("path", "").takeIf { it.isNotBlank() }
        val line = args.optInt("line", -1).takeIf { it > 0 }
        val column = args.optInt("column", -1).takeIf { it > 0 }
        val symbolId = args.optString("symbol_id", "").takeIf { it.isNotBlank() }
        val includeDeclaration = args.optBoolean("include_declaration", false)
        val limit = args.optInt("max_results", 50).coerceIn(1, CodeIntelLimits.MAX_REFERENCES)

        val revision = resolveRevision(router, codingTaskRuntime, token) ?: return revisionUnavailable()
        val cursor = CursorPayload.decode(args.optString("cursor", "").takeIf { it.isNotBlank() })
        val queryFingerprint = CodeIntelQueryFingerprint.of(
            "references", rawPath, line, column, symbolId, includeDeclaration, limit
        )
        if (cursor != null &&
            (cursor.queryFingerprint != queryFingerprint || cursor.sourceDigest != revision.sourceDigest ||
                cursor.mutationEpoch != revision.mutationEpoch)) {
            return error("CODE_INTEL_CURSOR_STALE", revision)
        }
        val offset = cursor?.offset ?: 0
        if (!ensureIndexFresh(revision, codingTaskRuntime, token)) {
            return errorSourceMoved(revisionOracle.currentRevision(router, codingTaskRuntime, token, beforeSideEffect))
        }

        val backend = args.optString("backend", "index").lowercase()
        if (backend !in SUPPORTED_BACKENDS) return error("CODE_INTEL_BACKEND_UNSUPPORTED", revision)
        if (backend == "lsp") {
            if (rawPath == null || line == null) return lspUnavailable()
            val guard = LspMutationGuard { liveDigest(token) }
            val digestBefore = guard.begin()
            if (digestBefore == null) return revisionUnavailable()
            if (digestBefore != revision.sourceDigest) {
                return sourceMovedAfterRevision(revision, router, codingTaskRuntime, token)
            }
            val operation = GuardedLspOperation<LspOutcome<List<ReferenceLocation>>>(
                guard, digestBefore
            ) { handleLspMutation(revision, codingTaskRuntime) }
            val guarded = try {
                operation.run {
                    val sessionOutcome = openSession(proj, codingTaskRuntime, rawPath, revision, token, digestBefore, beforeSideEffect)
                    if (sessionOutcome is LspOutcome.Failure) {
                        sessionOutcome
                    } else {
                        val session = (sessionOutcome as LspOutcome.Success).value
                        val sanitizer = LspPathSanitizer(pathMapper)
                        val uri = sanitizer.virtualPathToUri(rawPath, session.executionRoot())
                        if (uri == null) {
                            LspOutcome.Failure(LspFailureCode.PATH_REJECTED)
                        } else {
                            lspManager.findReferences(
                                session, uri, line, column ?: 1, includeDeclaration, sanitizer, limit, token, beforeSideEffect
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                return error("LSP_UNAVAILABLE", revision)
            }
            if (guarded is GuardedLspOperation.Result.Mutated) return error("CODE_INTEL_LSP_MUTATED_SOURCE", revision)
            val refsOutcome: LspOutcome<List<ReferenceLocation>> =
                (guarded as GuardedLspOperation.Result.Value<LspOutcome<List<ReferenceLocation>>>).value
            if (refsOutcome is LspOutcome.Failure) return lspFailure(refsOutcome, revision, LspPathSanitizer(pathMapper))
            val refs = (refsOutcome as LspOutcome.Success<List<ReferenceLocation>>).value
            val array = JSONArray()
            refs.forEach { array.put(it.toJson()) }
            return result("lsp", "EXACT", revision,
                JSONObject().put("results", array).put("truncated", refs.size >= limit))
        }

        val execRelPath = rawPath?.let { pathMapper.toExecutionRelative(it) }
        val refs = indexBackend.findReferences(
            execRelPath, line, column, symbolId, includeDeclaration, limit + 1, offset, revision
        )
        stableFailure(revision, codingTaskRuntime, token)?.let { return it }
        val mapped = refs.map { it.copy(path = pathMapper.toVirtualPath(it.path)) }
        val hasMore = mapped.size > limit
        val page = mapped.take(limit)
        val nextCursor = if (hasMore) {
            CursorPayload(queryFingerprint, revision.sourceDigest, revision.mutationEpoch, offset + limit).encode()
        } else null
        val array = JSONArray()
        page.forEach { array.put(it.toJson()) }
        return result("index", "LEXICAL", revision,
            JSONObject().put("results", array).put("truncated", hasMore)
                .put("next_cursor", nextCursor ?: JSONObject.NULL))
    }

    fun symbolSearch(
        args: JSONObject,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject {
        val proj = router.getActiveProject()
        val pathMapper = CodePathMapper(proj)
        val kindsArray = args.optJSONArray("kinds")
        val kinds = mutableSetOf<SymbolKind>()
        if (kindsArray != null) {
            for (index in 0 until kindsArray.length()) kinds.add(SymbolKind.fromWire(kindsArray.getString(index)))
        }
        val language = args.optString("language", "").takeIf { it.isNotBlank() }
            ?.let { value -> CodeLanguage.entries.firstOrNull { it.identifier == value.lowercase() } }
        val rawPrefix = args.optString("path_prefix", "").takeIf { it.isNotBlank() }
        val limit = args.optInt("max_results", 50).coerceIn(1, CodeIntelLimits.MAX_SYMBOL_RESULTS)

        val revision = resolveRevision(router, codingTaskRuntime, token) ?: return revisionUnavailable()
        val cursor = CursorPayload.decode(args.optString("cursor", "").takeIf { it.isNotBlank() })
        val queryFingerprint = CodeIntelQueryFingerprint.of(
            "symbols", args.optString("query", ""), kinds.sortedBy { it.wireName }, language?.identifier, rawPrefix, limit
        )
        if (cursor != null &&
            (cursor.queryFingerprint != queryFingerprint || cursor.sourceDigest != revision.sourceDigest ||
                cursor.mutationEpoch != revision.mutationEpoch)) {
            return error("CODE_INTEL_CURSOR_STALE", revision)
        }
        val offset = cursor?.offset ?: 0
        if (!ensureIndexFresh(revision, codingTaskRuntime, token)) {
            return errorSourceMoved(revisionOracle.currentRevision(router, codingTaskRuntime, token, beforeSideEffect))
        }

        val prefix = rawPrefix?.let { pathMapper.toExecutionRelative(it) }
        val symbols = indexBackend.searchSymbols(args.optString("query", ""), kinds, language, prefix, limit + 1, offset, revision)
        stableFailure(revision, codingTaskRuntime, token)?.let { return it }
        val mapped = symbols.map { it.copy(path = pathMapper.toVirtualPath(it.path)) }
        val hasMore = mapped.size > limit
        val page = mapped.take(limit)
        val nextCursor = if (hasMore) {
            CursorPayload(queryFingerprint, revision.sourceDigest, revision.mutationEpoch, offset + limit).encode()
        } else null
        val array = JSONArray()
        page.forEach { array.put(it.toJson()) }
        return result("index", "LEXICAL", revision,
            JSONObject().put("results", array).put("truncated", hasMore)
                .put("next_cursor", nextCursor ?: JSONObject.NULL))
    }

    fun semanticSearch(
        args: JSONObject,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject {
        val proj = router.getActiveProject()
        val pathMapper = CodePathMapper(proj)
        val limit = args.optInt("limit", 20).coerceIn(1, CodeIntelLimits.MAX_SEMANTIC_RESULTS)

        val revision = resolveRevision(router, codingTaskRuntime, token) ?: return revisionUnavailable()
        if (!ensureIndexFresh(revision, codingTaskRuntime, token)) {
            return errorSourceMoved(revisionOracle.currentRevision(router, codingTaskRuntime, token, beforeSideEffect))
        }

        val results = semanticSearch.search(args.optString("query", ""), revision, limit + 1)
        stableFailure(revision, codingTaskRuntime, token)?.let { return it }
        val mapped = results.map { it.copy(path = pathMapper.toVirtualPath(it.path)) }
        val hasMore = mapped.size > limit
        val array = JSONArray()
        mapped.take(limit).forEach { array.put(it.toJson()) }
        return result("structural", "STRUCTURAL", revision,
            JSONObject().put("results", array)
                .put("truncated", hasMore))
    }

    fun dependencyGraph(
        args: JSONObject,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject {
        val proj = router.getActiveProject()
        val pathMapper = CodePathMapper(proj)

        val revision = resolveRevision(router, codingTaskRuntime, token) ?: return revisionUnavailable()
        if (!ensureIndexFresh(revision, codingTaskRuntime, token)) {
            return errorSourceMoved(revisionOracle.currentRevision(router, codingTaskRuntime, token, beforeSideEffect))
        }

        val manifestFiles = mutableListOf<String>()
        runCatching { mirrorManager.getMirrorManifest(token) }.getOrNull()?.let { manifest ->
            for (path in manifest.files.keys) {
                if (WorkspaceIgnorePolicy.isIgnored(path)) continue
                if (MANIFEST_NAMES.any { path.lowercase().endsWith(it) }) manifestFiles.add(path)
            }
        }
        val fileReader: (String) -> String? = { path ->
            runCatching { mirrorManager.readMirrorText(path, token) }.getOrNull()
        }
        val graph = dependencyGraphBuilder.build(revision, fileReader, manifestFiles)
        val mappedNodes = graph.nodes.map { node ->
            if (node.kind == DependencyNodeKind.FILE && node.path != null) {
                node.copy(path = pathMapper.toVirtualPath(node.path))
            } else node
        }
        val finalGraph = DependencyGraph(mappedNodes, graph.edges, graph.truncated)
        stableFailure(revision, codingTaskRuntime, token)?.let { return it }
        return result("index", "STRUCTURAL", revision, JSONObject().put("graph", finalGraph.toJson()))
    }

    fun diagnostics(
        args: JSONObject,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): JSONObject {
        val source = args.optString("source", "validation").lowercase()
        val rawPath = args.optString("path", "").takeIf { it.isNotBlank() }

        if (source == "validation") {
            val report = codingTaskRuntime?.currentReport()
            return enforceResultBudget(
                JSONObject().put("ok", true).put("source", "validation")
                    .put("report", report?.toJson() ?: JSONObject.NULL))
        }

        val proj = router.getActiveProject()
        val pathMapper = CodePathMapper(proj)
        val revision = resolveRevision(router, codingTaskRuntime, token) ?: return revisionUnavailable()
        val merged = mutableListOf<Diagnostic>()
        if (source == "all") {
            codingTaskRuntime?.currentReport()?.let {
                merged.addAll(it.errors)
                merged.addAll(it.warnings)
            }
        }

        if (source == "lsp" || source == "all") {
            if (rawPath == null) return lspUnavailable()
            // The guard opens only after the stable revision exists and before
            // any language server is contacted; diagnostics therefore cannot
            // mistake a normal prepare for a language-server mutation.
            val guard = LspMutationGuard { liveDigest(token) }
            val digestBefore = guard.begin()
            if (digestBefore == null) return revisionUnavailable()
            if (digestBefore != revision.sourceDigest) {
                return sourceMovedAfterRevision(revision, router, codingTaskRuntime, token)
            }
            val operation = GuardedLspOperation<LspOutcome<List<Diagnostic>>>(
                guard, digestBefore
            ) { handleLspMutation(revision, codingTaskRuntime) }
            val guarded = try {
                operation.run {
                    val sessionOutcome = openSession(proj, codingTaskRuntime, rawPath, revision, token, digestBefore, beforeSideEffect)
                    if (sessionOutcome is LspOutcome.Failure) {
                        sessionOutcome
                    } else {
                        val session = (sessionOutcome as LspOutcome.Success<LspSession>).value
                        val sanitizer = LspPathSanitizer(pathMapper)
                        val uri = sanitizer.virtualPathToUri(rawPath, session.executionRoot())
                        if (uri == null) LspOutcome.Failure(LspFailureCode.PATH_REJECTED)
                        else lspManager.diagnostics(session, uri, sanitizer, token, beforeSideEffect)
                    }
                }
            } catch (error: Exception) {
                return error("LSP_UNAVAILABLE", revision)
            }
            if (guarded is GuardedLspOperation.Result.Mutated) return error("CODE_INTEL_LSP_MUTATED_SOURCE", revision)
            val diagnosticOutcome: LspOutcome<List<Diagnostic>> =
                (guarded as GuardedLspOperation.Result.Value<LspOutcome<List<Diagnostic>>>).value
            if (diagnosticOutcome is LspOutcome.Failure) return lspFailure(diagnosticOutcome, revision, LspPathSanitizer(pathMapper))
            merged.addAll((diagnosticOutcome as LspOutcome.Success<List<Diagnostic>>).value)
        }

        val errors = merged.filter { it.severity == DiagnosticSeverity.ERROR }.take(40)
        val warnings = merged.filter { it.severity == DiagnosticSeverity.WARNING }.take(10)
        val other = merged.filter {
            it.severity != DiagnosticSeverity.ERROR && it.severity != DiagnosticSeverity.WARNING
        }.take(10)
        val distinct = (errors + warnings + other).distinctBy { it.path + ":" + it.line + ":" + it.message }
        val array = JSONArray()
        distinct.forEach { array.put(it.toJson()) }
        return enforceResultBudget(
            JSONObject().put("ok", true).put("source", source).put("diagnostics", array))
    }

    // -------------------------------------------------------------- internals

    private fun liveDigest(token: CancellationToken): String? =
        runCatching { mirrorManager.currentMirrorDigest(token) }.getOrNull()

    /**
     * The LSP guard opens after [SourceRevisionOracle] has established a
     * stable revision.  If the first live digest differs, do not start a
     * language server against a different snapshot and then label its result
     * with the stale revision token.  Re-observe once to return the newest
     * token when possible; failure remains fail-closed.
     */
    private fun sourceMovedAfterRevision(
        revision: SourceRevision,
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime?,
        token: CancellationToken
    ): JSONObject {
        val latest = runCatching {
            revisionOracle.currentRevision(router, codingTaskRuntime, token, null)
        }.getOrNull()
        return latest?.let { errorSourceMoved(it) } ?: errorSourceMoved(revision)
    }

    private fun stableFailure(
        revision: SourceRevision,
        codingTaskRuntime: CodingTaskRuntime?,
        token: CancellationToken
    ): JSONObject? {
        val guard = StableRevisionGuard(
            liveDigest = { liveDigest(token) },
            liveEpoch = { codingTaskRuntime?.mutationEpoch ?: 0 }
        )
        if (guard.isStable(revision)) return null
        val latest = runCatching { revisionOracle.currentRevision(WorkspaceRouter(context), codingTaskRuntime, token, null) }
            .getOrNull()
        return if (latest != null) errorSourceMoved(latest) else mirrorNotPrepared()
    }

    private fun openSession(
        proj: VegaProject,
        codingTaskRuntime: CodingTaskRuntime?,
        rawPath: String,
        revision: SourceRevision,
        token: CancellationToken,
        digestBefore: String?,
        beforeSideEffect: (() -> Unit)?
    ): LspOutcome<LspSession> {
        if (digestBefore == null) return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "digest unavailable")
        val key = LspSessionKey(
            rootTaskId = codingTaskRuntime?.taskId ?: "lead",
            projectId = proj.id,
            rootGeneration = proj.generation,
            language = CodeLanguage.fromPath(rawPath),
            executionPath = proj.findMount(ProjectPathParser.parse(rawPath, proj).mountAlias)?.executionPath
                ?: return LspOutcome.Failure(LspFailureCode.PATH_REJECTED)
        )
        return lspManager.getSession(key, revision.sourceDigest, token, beforeSideEffect)
    }

    /**
     * Turns "the mirror moved during this LSP window" into a terminal failure:
     * session killed, index dirty, P4 stamp invalidated, mirror marked BROKEN.
     */
    private fun mutationCheckpoint(
        guard: LspMutationGuard,
        digestBefore: String?,
        revision: SourceRevision,
        codingTaskRuntime: CodingTaskRuntime?
    ): Boolean {
        if (!guard.finish(digestBefore).mutated) return false
        handleLspMutation(revision, codingTaskRuntime)
        return true
    }

    private fun handleLspMutation(
        revision: SourceRevision,
        codingTaskRuntime: CodingTaskRuntime?
    ) {
        lspManager.stopAll()
        indexBackend.markDirty(revision.projectId)
        codingTaskRuntime?.refreshSourceFingerprint()
        codingTaskRuntime?.invalidateExternalSourceIntegrity("LSP_MUTATED_SOURCE")
        runCatching { mirrorManager.markUnexpectedMirrorMutation("LSP_MUTATED_SOURCE") }
    }

    /**
     * Establishes the source revision, or null when the mirror cannot back it.
     *
     * P5 never prepares or synchronises. A read-only tool that silently synced
     * would be acting with mutation authority it was never granted; the model
     * calls execution_prepare for that, and that call holds the authority.
     */
    private fun resolveRevision(
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime?,
        token: CancellationToken
    ): SourceRevision? {
        revisionFailure.set(null)
        return try {
            revisionOracle.currentRevision(router, codingTaskRuntime, token, null)
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            revisionFailure.set(message)
            if (message.contains("CODE_INTEL_MIRROR_INTEGRITY_LOST")) {
                indexBackend.markDirty(router.getActiveProject().id)
                codingTaskRuntime?.invalidateExternalSourceIntegrity(message)
            }
            null
        }
    }

    private fun mirrorNotPrepared(): JSONObject = enforceResultBudget(
        JSONObject().put("ok", false)
            .put("error", "CODE_INTEL_MIRROR_NOT_PREPARED")
            .put("hint", "call execution_prepare first; code intelligence never syncs the mirror itself"))

    private fun revisionUnavailable(): JSONObject {
        val failure = revisionFailure.get().orEmpty()
        return if (failure.contains("CODE_INTEL_MIRROR_INTEGRITY_LOST")) {
            enforceResultBudget(JSONObject().put("ok", false)
                .put("error", "CODE_INTEL_MIRROR_INTEGRITY_LOST")
                .put("hint", "execution mirror integrity is broken; run execution_recover or prepare again"))
        } else {
            mirrorNotPrepared()
        }
    }

    private fun lspUnavailable(): JSONObject = enforceResultBudget(
        JSONObject().put("ok", false)
            .put("error", "LSP_UNAVAILABLE")
            .put("fallback_available", "index"))

    private fun lspFailure(
        failure: LspOutcome.Failure,
        revision: SourceRevision,
        pathSanitizer: LspPathSanitizer
    ): JSONObject {
        val code = when (failure.code) {
            LspFailureCode.UNAVAILABLE -> "LSP_UNAVAILABLE"
            LspFailureCode.START_FAILED -> "LSP_START_FAILED"
            LspFailureCode.INITIALIZE_FAILED -> "LSP_INITIALIZE_FAILED"
            LspFailureCode.TIMEOUT -> "LSP_TIMEOUT"
            LspFailureCode.CRASHED -> "LSP_CRASHED"
            LspFailureCode.CANCELLED -> "LSP_CANCELLED"
            LspFailureCode.PROTOCOL_INVALID -> "LSP_PROTOCOL_INVALID"
            LspFailureCode.PROTOCOL_LIMIT -> "LSP_PROTOCOL_LIMIT"
            LspFailureCode.PATH_REJECTED -> "LSP_PATH_REJECTED"
            LspFailureCode.RESTART_BUDGET_EXHAUSTED -> "LSP_RESTART_BUDGET_EXHAUSTED"
        }
        // Bridge/helper/server details are untrusted text and may contain a
        // physical Termux path, content URI or an arbitrary host scheme.  The
        // manager is the last model-visible boundary, so sanitize here even
        // when a lower layer forgot to map a diagnostic.
        val detail = pathSanitizer.sanitizeText(failure.detail)
        return enforceResultBudget(JSONObject().put("ok", false).put("error", code)
            .put("detail", detail).put("revision_token", revision.toToken()))
    }

    private fun error(code: String, revision: SourceRevision): JSONObject = enforceResultBudget(
        JSONObject().put("ok", false).put("error", code).put("revision_token", revision.toToken()))

    private fun errorSourceMoved(latest: SourceRevision): JSONObject = enforceResultBudget(
        JSONObject().put("ok", false)
            .put("error", "CODE_INTEL_SOURCE_MOVED")
            .put("revision_token", latest.toToken()))

    private fun result(
        backend: String,
        quality: String,
        revision: SourceRevision,
        payload: JSONObject
    ): JSONObject {
        val envelope = JSONObject()
            .put("ok", true)
            .put("backend", backend)
            .put("quality", quality)
            .put("revision_token", revision.toToken())
            .put("warnings", JSONArray())
        payload.keys().forEach { key -> envelope.put(key, payload.get(key)) }
        return enforceResultBudget(envelope)
    }

    /**
     * Builds an index candidate and publishes it only when the mirror is
     * provably unchanged. The probe is pure observation — no prepare, no sync,
     * no baseline refresh — so a swap cannot be hidden by the check meant to
     * catch it.
     */
    internal fun ensureIndexFresh(
        revision: SourceRevision,
        codingTaskRuntime: CodingTaskRuntime?,
        token: CancellationToken
    ): Boolean {
        val stable = StableRevisionGuard(
            liveDigest = { liveDigest(token) },
            liveEpoch = { codingTaskRuntime?.mutationEpoch ?: 0 }
        )
        if (!stable.isStable(revision)) return false
        if (indexBackend.status(revision).phase == CodeIndexPhase.READY) return true
        val candidateId = "cand-" + java.util.UUID.randomUUID().toString()
        val manifestFiles = runCatching { mirrorManager.getMirrorManifest(token) }.getOrNull()
            ?.files?.filterKeys { !WorkspaceIgnorePolicy.isIgnored(it) }
            ?.map { (path, entry) -> IndexManifestFile(path, entry.size, entry.sha256) }
            ?: emptyList()
        val fileReader: (String) -> ByteArray? = { path ->
            runCatching { mirrorManager.readMirrorText(path, token)?.toByteArray(Charsets.UTF_8) }.getOrNull()
        }
        val built = indexBackend.buildCandidateWithManifest(candidateId, revision, fileReader, manifestFiles, token)
        if (!built || token.isCancelled) {
            indexBackend.discardCandidate(candidateId)
            return false
        }
        if (stable.isStable(revision)) {
            return indexBackend.commitCandidate(candidateId, revision, revision)
        }
        indexBackend.discardCandidate(candidateId)
        return false
    }

    /**
     * Hard post-condition for every P5 result; see [CodeIntelResultBudget].
     */
    internal fun enforceResultBudget(json: JSONObject): JSONObject =
        CodeIntelResultBudget.enforce(json)

    companion object {
        /**
         * Static production boundary used by the offline regression suite.
         * Keeping the exact same post-condition available without constructing
         * a workspace-backed manager lets the test drive the real finalizer,
         * rather than merely inspecting a serialized over-limit payload.
         */
        @JvmStatic
        internal fun enforceResultBudgetBoundary(json: JSONObject): JSONObject =
            CodeIntelResultBudget.enforce(json)

        private val MUTATING_TOOL_NAMES = setOf(
            "write_file", "edit_file", "delete_path", "move_path",
            "execution_write_file", "execution_edit_file", "execution_delete_file",
            "execution_make_dir", "execution_import_archive", "shell_exec"
        )
        private val MANIFEST_NAMES = setOf(
            "build.gradle", "build.gradle.kts", "pom.xml", "package.json",
            "pyproject.toml", "requirements.txt", "poetry.lock", "Pipfile"
        )
        private val SUPPORTED_BACKENDS = setOf("index", "lsp")
        private fun activeWorkspaceId(context: Context): String? = runCatching {
            ProjectIdentity.remoteId(WorkspaceRouter(context).getActiveProject().id)
        }.getOrNull()
    }

}
