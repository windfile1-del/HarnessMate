package dev.wind.agent.intelligence.lsp

import dev.wind.agent.CancellationToken
import dev.wind.agent.coding.Diagnostic
import dev.wind.agent.coding.DiagnosticCategory
import dev.wind.agent.coding.DiagnosticSeverity
import dev.wind.agent.coding.DiagnosticSource
import dev.wind.agent.intelligence.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class LanguageServerManager(
    private val transportFactory: (LspSessionKey) -> LspTransport = { DefaultDisabledLspTransport() }
) {
    private val sessions = ConcurrentHashMap<LspSessionKey, LspSession>()
    private val accessOrder = HashMap<LspSessionKey, Long>()
    private var accessSequence = 0L
    private val restartHistory = ConcurrentHashMap<LspSessionKey, MutableList<Long>>()
    private val lastError = ConcurrentHashMap<LspSessionKey, String>()

    /** Structured reason the most recent session attempt failed, if any. */
    fun lastError(key: LspSessionKey): String? = lastError[key]

    /**
     * ConcurrentHashMap.computeIfAbsent is API 24+ and this app floors at 23,
     * so the bucket is created with the same read-then-synchronise pattern the
     * mirror lock registry uses.
     */
    private fun restartBucket(key: LspSessionKey): MutableList<Long> {
        restartHistory[key]?.let { return it }
        return synchronized(restartHistory) {
            restartHistory[key] ?: mutableListOf<Long>().also { restartHistory[key] = it }
        }
    }

    @Synchronized
    fun getSession(
        key: LspSessionKey,
        currentDigest: String,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): LspOutcome<LspSession> {
        val existing = sessions[key]
        if (existing != null) {
            if (existing.sourceDigest != currentDigest ||
                existing.state != LspState.READY ||
                System.currentTimeMillis() - existing.lastUsedAt > CodeIntelLimits.LSP_IDLE_TIMEOUT_MS) {
                if (existing.state == LspState.BACKOFF) {
                    val history = restartBucket(key)
                    synchronized(history) { history.add(System.currentTimeMillis()) }
                }
                val stopped = existing.markStale()
                if (stopped is LspOutcome.Failure) {
                    lastError[key] = stopped.code.name
                    return stopped
                }
                sessions.remove(key)
                accessOrder.remove(key)
            } else {
                existing.touch()
                accessOrder[key] = ++accessSequence
                return LspOutcome.Success(existing)
            }
        }

        if (!LanguageServerRegistry.isLanguageSupported(key.language)) {
            return LspOutcome.Failure(LspFailureCode.UNAVAILABLE, "language not enabled")
        }

        val now = System.currentTimeMillis()
        val history = restartBucket(key)
        synchronized(history) {
            history.removeAll { now - it > CodeIntelLimits.LSP_RESTART_WINDOW_MS }
            if (history.size >= CodeIntelLimits.LSP_MAX_RESTARTS_PER_WINDOW) {
                return LspOutcome.Failure(LspFailureCode.RESTART_BUDGET_EXHAUSTED)
            }
        }

        // Servers are long-lived runtime commands. Reclaim the least recently
        // used owner before opening another, reserving a command slot for builds.
        // A failed cleanup keeps ownership and prevents a replacement dispatch.
        while (sessions.size >= CodeIntelLimits.MAX_CACHED_LSP_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { accessOrder[it.key] ?: Long.MIN_VALUE } ?: break
            val stopped = oldest.value.stop()
            if (stopped is LspOutcome.Failure) {
                lastError[key] = stopped.code.name
                return stopped
            }
            sessions.remove(oldest.key)
            accessOrder.remove(oldest.key)
        }
        beforeSideEffect?.invoke()
        val session = LspSession(key, currentDigest, transportFactory(key))
        val started = session.start(
            token,
            beforeSideEffect?.let { callback -> ExecutionBoundaryVerifier { callback() } }
        )
        if (started is LspOutcome.Success) {
            // Routine starts after source edits do not consume the crash budget.
            lastError.remove(key)
            sessions[key] = session
            accessOrder[key] = ++accessSequence
            return LspOutcome.Success(session)
        }
        val failure = started as LspOutcome.Failure
        // A failed startup may still own a process whose cleanup was unconfirmed.
        // Retain that owner so stopAll or the next attempt can retry the barrier.
        if (session.state == LspState.DEGRADED) {
            sessions[key] = session
            accessOrder[key] = ++accessSequence
        }
        synchronized(history) { history.add(now) }
        lastError[key] = failure.code.name
        return failure
    }

    fun findDefinitions(
        session: LspSession,
        uri: String,
        line: Int,
        column: Int,
        pathSanitizer: LspPathSanitizer,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): LspOutcome<List<SymbolLocation>> {
        val params = JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))
            .put("position", JSONObject().put("line", line - 1).put("character", column - 1))

        val response = session.request(
            "textDocument/definition",
            params,
            LanguageServerRegistry.getDescriptor(session.key.language)?.requestTimeoutMs
                ?: CodeIntelLimits.LSP_DEFINITION_TIMEOUT_MS,
            token,
            beforeSideEffect?.let { callback -> ExecutionBoundaryVerifier { callback() } }
        )
        if (response is LspOutcome.Failure) return response

        val results = mutableListOf<SymbolLocation>()
        val rawResult = (response as LspOutcome.Success).value.opt("result")
        val executionRoot = session.executionRoot()
        if (rawResult is JSONObject) {
            parseLocation(rawResult, pathSanitizer, executionRoot)?.let { results.add(it) }
        } else if (rawResult is JSONArray) {
            for (i in 0 until rawResult.length()) {
                val loc = rawResult.optJSONObject(i) ?: continue
                parseLocation(loc, pathSanitizer, executionRoot)?.let { results.add(it) }
                if (results.size >= CodeIntelLimits.MAX_DEFINITIONS) break
            }
        }
        return LspOutcome.Success(results)
    }

    fun findReferences(
        session: LspSession,
        uri: String,
        line: Int,
        column: Int,
        includeDeclaration: Boolean,
        pathSanitizer: LspPathSanitizer,
        limit: Int = 50,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): LspOutcome<List<ReferenceLocation>> {
        val params = JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))
            .put("position", JSONObject().put("line", line - 1).put("character", column - 1))
            .put("context", JSONObject().put("includeDeclaration", includeDeclaration))

        val response = session.request(
            "textDocument/references",
            params,
            maxOf(CodeIntelLimits.LSP_REFERENCES_TIMEOUT_MS,
                LanguageServerRegistry.getDescriptor(session.key.language)?.requestTimeoutMs?.times(3)?.div(2) ?: 0L),
            token,
            beforeSideEffect?.let { callback -> ExecutionBoundaryVerifier { callback() } }
        )
        if (response is LspOutcome.Failure) return response

        val results = mutableListOf<ReferenceLocation>()
        val arr = (response as LspOutcome.Success).value.optJSONArray("result")
            ?: return LspOutcome.Success(emptyList())
        val effectiveLimit = minOf(limit, CodeIntelLimits.MAX_REFERENCES)
        val executionRoot = session.executionRoot()

        for (i in 0 until arr.length()) {
            val loc = arr.optJSONObject(i) ?: continue
            val targetUri = loc.optString("uri", "")
            val virtualPath = pathSanitizer.uriToVirtualPath(targetUri, executionRoot) ?: continue
            val rangeObj = loc.optJSONObject("range")
            val range = parseRange(rangeObj)
            results.add(ReferenceLocation(
                path = virtualPath,
                range = range,
                symbol = "",
                refKind = "reference",
                confidence = IntelligenceConfidence.EXACT
            ))
            if (results.size >= effectiveLimit) break
        }
        return LspOutcome.Success(results)
    }

    fun diagnostics(
        session: LspSession,
        uri: String,
        pathSanitizer: LspPathSanitizer,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): LspOutcome<List<Diagnostic>> {
        val params = JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))

        val response = session.request(
            "textDocument/diagnostic",
            params,
            CodeIntelLimits.LSP_DIAGNOSTICS_TIMEOUT_MS,
            token,
            beforeSideEffect?.let { callback -> ExecutionBoundaryVerifier { callback() } }
        )
        if (response is LspOutcome.Failure) return response

        val responseJson = (response as LspOutcome.Success).value
        val items = responseJson.optJSONObject("result")?.optJSONArray("items")
            ?: responseJson.optJSONArray("result")
            ?: return LspOutcome.Success(emptyList())

        val list = mutableListOf<Diagnostic>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val rawMsg = item.optString("message", "")
            val sanitizedMsg = pathSanitizer.sanitizeText(rawMsg)
            val severityInt = item.optInt("severity", 1)
            val severity = when (severityInt) {
                1 -> DiagnosticSeverity.ERROR
                2 -> DiagnosticSeverity.WARNING
                else -> DiagnosticSeverity.INFO
            }
            val rangeObj = item.optJSONObject("range")
            val line = (rangeObj?.optJSONObject("start")?.optInt("line") ?: 0) + 1
            val col = (rangeObj?.optJSONObject("start")?.optInt("character") ?: 0) + 1
            val virtualPath = pathSanitizer.uriToVirtualPath(uri, session.executionRoot())
                ?: continue

            list.add(Diagnostic(
                id = "lsp_$i",
                severity = severity,
                category = DiagnosticCategory.COMPILE,
                path = virtualPath,
                line = line,
                column = col,
                code = null,
                message = sanitizedMsg,
                source = DiagnosticSource.GENERIC
            ))
            if (list.size >= 40) break
        }
        return LspOutcome.Success(list)
    }

    private fun parseLocation(
        loc: JSONObject,
        pathSanitizer: LspPathSanitizer,
        executionRoot: String?
    ): SymbolLocation? {
        val uri = loc.optString("uri", "")
        val virtualPath = pathSanitizer.uriToVirtualPath(uri, executionRoot) ?: return null
        val range = parseRange(loc.optJSONObject("range"))
        return SymbolLocation(
            path = virtualPath,
            range = range,
            symbol = "",
            kind = SymbolKind.UNKNOWN,
            confidence = IntelligenceConfidence.EXACT
        )
    }

    private fun parseRange(rangeObj: JSONObject?): CodeRange {
        if (rangeObj == null) return CodeRange(CodePosition(1, 1), CodePosition(1, 1))
        val startObj = rangeObj.optJSONObject("start")
        val endObj = rangeObj.optJSONObject("end")
        val startLine = (startObj?.optInt("line") ?: 0) + 1
        val startCol = (startObj?.optInt("character") ?: 0) + 1
        val endLine = (endObj?.optInt("line") ?: 0) + 1
        val endCol = (endObj?.optInt("character") ?: 0) + 1
        return CodeRange(CodePosition(startLine, startCol), CodePosition(endLine, endCol))
    }

    @Synchronized
    fun stopAll() {
        sessions.entries.toList().forEach { entry ->
            val stopped = entry.value.stop()
            if (stopped is LspOutcome.Success) {
                sessions.remove(entry.key, entry.value)
                accessOrder.remove(entry.key)
            }
            else lastError[entry.key] = (stopped as LspOutcome.Failure).code.name
        }
        if (sessions.isEmpty()) restartHistory.clear()
    }

    /** Opportunistic cleanup; no background watchdog is needed for P5. */
    @Synchronized
    fun evictIdleSessions(now: Long = System.currentTimeMillis()) {
        sessions.entries.toList().forEach { entry ->
            if (now - entry.value.lastUsedAt > CodeIntelLimits.LSP_IDLE_TIMEOUT_MS) {
                val stopped = entry.value.stop()
                if (stopped is LspOutcome.Success) {
                    sessions.remove(entry.key, entry.value)
                    accessOrder.remove(entry.key)
                } else {
                    lastError[entry.key] = (stopped as LspOutcome.Failure).code.name
                }
            }
        }
    }
}

class DefaultDisabledLspTransport : LspTransport {
    override fun start(language: CodeLanguage, rootUri: String, token: CancellationToken, beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<Unit> =
        LspOutcome.Failure(LspFailureCode.UNAVAILABLE)
    override fun request(method: String, params: JSONObject, timeoutMs: Long, token: CancellationToken, beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<JSONObject> =
        LspOutcome.Failure(LspFailureCode.UNAVAILABLE)
    override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {}
    override fun executionRoot(): String? = null
    override fun stop(): LspOutcome<Unit> = LspOutcome.Success(Unit)
}

/**
 * The transport used when a session's runtime authority cannot be established:
 * the mirror state is missing, corrupt, unstable, or bound to a generation this
 * call does not belong to.
 *
 * It exists as a distinct type rather than as [DefaultDisabledLspTransport]
 * because the two mean different things in a review: "the companion does not
 * implement LSP yet" is a capability statement, while "we could not prove which
 * substrate owns this project" is a fail-closed refusal. Both must answer
 * UNAVAILABLE, and neither may reach Termux.
 */
class AuthorityUnavailableLspTransport(private val reason: String) : LspTransport {
    override fun start(language: CodeLanguage, rootUri: String, token: CancellationToken, beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<Unit> =
        LspOutcome.Failure(LspFailureCode.UNAVAILABLE, reason)
    override fun request(method: String, params: JSONObject, timeoutMs: Long, token: CancellationToken, beforeDispatch: ExecutionBoundaryVerifier?): LspOutcome<JSONObject> =
        LspOutcome.Failure(LspFailureCode.UNAVAILABLE, reason)
    override fun notify(method: String, params: JSONObject, beforeDispatch: ExecutionBoundaryVerifier?) {}
    override fun executionRoot(): String? = null
    override fun stop(): LspOutcome<Unit> = LspOutcome.Success(Unit)
}
