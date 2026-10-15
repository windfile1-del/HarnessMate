package dev.wind.agent

import dev.wind.agent.context.*
import org.json.JSONArray
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Long-context recovery and native Gemini wire regression; no real credentials or device. */
object ContextRecoveryTests {
    private var checks = 0
    private fun verify(ok: Boolean, why: String) { check(ok) { why }; checks++ }
    private fun history(n: Int) = JSONArray().also { a -> repeat(n) { i ->
        a.put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant")
            .put("content", "Fact-$i " + "中".repeat(3000)))
    } }
    @JvmStatic fun main(args: Array<String>) {
        val raw = history(120); val original = raw.toString(); val s = ContextState()
        var requests = 0; val seen = StringBuilder()
        verify(ContextManager.compact("Code agent MUST return tool JSON", raw, s, 20000, 4096, true, true, CancellationToken()) { req ->
            requests++
            verify(ContextManager.estimate(req) <= 10000, "summary request over budget")
            verify(!req.getJSONObject(0).getString("content").contains("MUST return tool JSON"), "coding instructions override summary")
            seen.append(req.toString())
            "Handoff: preserve user constraints. All earlier facts processed. Device tests not run."
        }, "overflow compaction must succeed")
        verify(requests > 1 && seen.contains("Fact-0") && seen.contains("Fact-110"), "only first prefix was summarized")
        verify(s.estimatedTokens < 20000 - 4096, "recovered request still overflowing")
        verify(raw.toString() == original, "transcript mutated")
        val restored = ContextState.parse(s.json())
        verify(ContextManager.view("Code agent MUST return tool JSON", raw, restored).toString() == ContextManager.view("Code agent MUST return tool JSON", raw, s).toString(), "checkpoint reload failed")
        val million = history(360); val ms = ContextState(); var millionParts = 0
        verify(ContextManager.estimate(million) > 1000000, "fixture does not cover million-token history")
        verify(ContextManager.compact("stable", million, ms, 20000, 4096, true, true, CancellationToken()) {
            millionParts++; verify(ContextManager.estimate(it) <= 10000, "million-history summary exceeds budget"); "Task handoff"
        } && millionParts > 50 && ms.estimatedTokens < 15904, "million history did not recover to a usable request")
        val huge = history(20); huge.getJSONObject(0).put("content", "中".repeat(70000) + "END_OF_LARGE_MESSAGE")
        var endSeen = false
        verify(ContextManager.compact("stable", huge, ContextState(), 20000, 4096, true, true, CancellationToken()) {
            verify(ContextManager.estimate(it) <= 10000, "single large message exceeds summary budget")
            endSeen = endSeen || it.toString().contains("END_OF_LARGE_MESSAGE"); "Handoff"
        } && endSeen, "large message tail was silently dropped")
        val failed = ContextState(); var parts = 0
        verify(!ContextManager.compact("stable", raw, failed, 20000, 4096, true, true, CancellationToken()) {
            if (++parts == 2) error("provider refused oversized input")
            "Prior handoff"
        }, "partial summary committed")
        verify(failed.checkpoint == null && failed.status.contains("provider refused"), "failure was hidden or partial state committed")
        val cancelled = CancellationToken(); val cs = ContextState()
        val result = runCatching { ContextManager.compact("stable", raw, cs, 20000, 4096, true, true, cancelled) { cancelled.cancel(); "handoff" } }
        verify(result.exceptionOrNull() is CancellationToken.CancelledException && cs.checkpoint == null && !cs.compacting.get(), "cancel not atomic")
        val calibrated = ContextState(); ContextManager.view("stable", raw, calibrated)
        calibrated.record(TokenUsage(900000, 100, 800000, 0, true))
        verify(calibrated.occupiedTokens() == 900100, "server input did not calibrate trigger")
        var auto = false
        verify(ContextManager.compact("stable", raw, calibrated, 1000000, 4096, false, false, CancellationToken()) { auto = true; "Small handoff" } && auto, "85 percent auto trigger ignored server usage")
        verify(calibrated.occupiedTokens() < 50000, "old service usage kept occupancy full after compaction")
        verify(!ContextManager.compact("stable", history(20), ContextState(), 20000, 4096, true, false, CancellationToken()) {
            """{"tool":"write_file","args":{}}"""
        }, "plain JSON tool output accepted as summary")
        verify(!ContextManager.compact("stable", history(20), ContextState(), 20000, 30000, true, false, CancellationToken()) {
            error("unusable output reservation should not call provider")
        }, "output reserve larger than window was silently clamped")
        val summaryUsage = ContextState(); summaryUsage.estimatedTokens = 10000
        summaryUsage.record(TokenUsage(900000, 1000, 800000, 0, true), summary = true)
        verify(summaryUsage.occupiedTokens() == 10000, "summary usage polluted normal occupancy calibration")
        val low = ContextState(); ContextManager.view("stable", raw, low)
        verify(!ContextManager.compact("stable", raw, low, 1000000, 4096, false, false, CancellationToken()) { error("unexpected") }, "low occupancy triggered")
        val altered = history(20); val asState = ContextState()
        verify(!ContextManager.compact("stable", altered, asState, 20000, 4096, true, true, CancellationToken()) {
            altered.getJSONObject(0).put("content", "changed user constraint")
            "Handoff facts"
        }, "changed history committed a stale handoff")
        verify(asState.checkpoint == null && asState.status.contains("历史已修改"), "history drift not diagnosed")
        val checkpoint = s.checkpoint
        val extra = history(30); repeat(extra.length()) { raw.put(extra.getJSONObject(it)) }
        verify(!ContextManager.compact("stable", raw, s, 20000, 4096, true, true, CancellationToken()) { error("network unavailable") }, "failed re-compaction committed")
        verify(s.checkpoint === checkpoint, "failed re-compaction discarded valid previous checkpoint")
        val usage = TokenUsage.parse(JSONObject("""{"usageMetadata":{"promptTokenCount":900000,"candidatesTokenCount":500,"cachedContentTokenCount":800000}}"""))!!
        val live = ContextState(); live.estimatedTokens = 500000
        live.observe(usage); live.observe(usage); live.record(usage)
        verify(live.describe(1000000).contains("服务端输入：900000"), "stream snapshots double counted totals")
        verify(ContextState.parse(live.json()).describe(1000000) == live.describe(1000000), "live usage not persisted")
        verify(ContextManager.isOverflow("The input token count exceeds the maximum number of tokens allowed (1048576)."), "Gemini overflow unrecognized")
        val delta = TokenUsage.parse(JSONObject("""{"usage":{"output_tokens":150}}"""), usage)!!
        verify(delta.inputTokens == 900000L && delta.cachedTokens == 800000L && delta.outputTokens == 150L, "output-only usage lost cached input")
        val provider = dev.wind.agent.modelprovider.ModelProvider(preset="gemini", label="fixture", url="http://localhost", protocol="gemini")
        val catalog = dev.wind.agent.modelprovider.ModelDiscovery.parse(provider, JSONObject("""{"models":[{"name":"models/gemini-3.8-flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]}]}"""))
        verify(catalog.single().inputTokenLimit == 1048576, "Gemini advertised capacity ignored")
        verify(dev.wind.agent.modelprovider.DiscoveredModel.parse(catalog.single().json()).inputTokenLimit == 1048576, "model capacity not persisted")
        gemini()
        println("PASS ContextRecoveryTests: $checks assertions")
    }
    private fun gemini() {
        NetworkPolicy.allowLocalNetwork = true
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body: JSONObject? = null
        server.createContext("/") { e ->
            body = JSONObject(String(e.requestBody.readBytes()))
            if (body!!.toString().length > 45000) {
                val bytes = """{"error":{"message":"The input token count exceeds the maximum number of tokens allowed (20000)."}}""".toByteArray()
                e.responseHeaders.add("Content-Type", "application/json"); e.sendResponseHeaders(400, bytes.size.toLong())
                e.responseBody.use { it.write(bytes) }; return@createContext
            }
            val payload = "data: " + JSONObject().put("candidates", JSONArray().put(JSONObject()
                .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Handoff facts"))))
                .put("finishReason", "STOP")))
                .put("usageMetadata", JSONObject().put("promptTokenCount", 900000).put("cachedContentTokenCount", 800000)).toString() + "\n\n"
            val bytes = payload.toByteArray(); e.responseHeaders.add("Content-Type", "text/event-stream")
            e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            var snapshot = false; var finalUsage = 0; var error: String? = null
            LlmClient("http://127.0.0.1:${server.address.port}/v1beta", "fixture", "gemini-3.8-flash", "gemini", 60000, 0.2f, "high", 32000)
                .forCompaction().streamChat(JSONArray().put(JSONObject().put("role", "system").put("content", ContextManager.SUMMARY_INSTRUCTION))
                .put(JSONObject().put("role", "user").put("content", "quoted history")), CancellationToken(), object : LlmClient.StreamCallback {
                    override fun onToken(text: String) { verify(snapshot, "usage snapshot delayed until final callback") }
                    override fun onThinking(text: String) {}
                    override fun onDone(text: String?) { verify(text == "Handoff facts", "native Gemini summary parse failed") }
                    override fun onError(message: String) { error = message }
                    override fun onUsageSnapshot(usage: TokenUsage) { snapshot = usage.cachedTokens == 800000L }
                    override fun onUsage(usage: TokenUsage) { finalUsage++ }
                })
            verify(error == null, "Gemini fixture failed: $error")
            verify(body!!.getJSONObject("generationConfig").getInt("maxOutputTokens") == 4096, "summary inherited normal output reservation")
            verify(snapshot && finalUsage == 1, "live Gemini cache usage missing")
            val client = LlmClient("http://127.0.0.1:${server.address.port}/v1beta", "fixture", "gemini-3.8-flash", "gemini", 60000, 0.2f, "high", 32000).forCompaction()
            fun summarize(messages: JSONArray): String {
                var text = ""; var failed: String? = null
                client.streamChat(messages, CancellationToken(), object : LlmClient.StreamCallback {
                    override fun onToken(text: String) {}
                    override fun onThinking(text: String) {}
                    override fun onDone(result: String?) { text = result.orEmpty() }
                    override fun onError(message: String) { failed = message }
                    override fun onTruncated() { failed = "truncated" }
                })
                check(failed == null) { failed.orEmpty() }; return text
            }
            val full = history(30)
            val rejection = runCatching { summarize(JSONArray().put(JSONObject().put("role", "user").put("content", full.toString()))) }
            verify(rejection.isFailure && ContextManager.isOverflow(rejection.exceptionOrNull()?.message.orEmpty()), "provider did not reject overflowing full history")
            val recovery = ContextState(); var nativeParts = 0
            verify(ContextManager.compact("coding instructions", full, recovery, 20000, 4096, true, true, CancellationToken()) {
                nativeParts++; summarize(it)
            } && nativeParts > 1 && recovery.estimatedTokens < 15904, "bounded native Gemini requests did not recover from actual HTTP overflow")
        } finally { server.stop(0) }
    }
}
