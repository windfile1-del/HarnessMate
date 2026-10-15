package dev.wind.agent

import dev.wind.agent.context.*
import org.json.JSONArray
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** Real compaction state and wire parsers; only the summarizer/provider is a local fixture. */
object ContextHarnessTests {
    private var checks = 0
    private fun verify(value: Boolean, reason: String) { check(value) { reason }; checks++ }
    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)
    private fun history(size: Int = 20) = JSONArray().also { a ->
        repeat(size) { i -> a.put(message(if (i % 2 == 0) "user" else "assistant", "message-$i " + "source and test facts ".repeat(350))) }
    }
    @JvmStatic fun main(args: Array<String>) {
        NetworkPolicy.allowLocalNetwork = true
        compaction(); usage(); policies(); streamUsage()
        if (!args.contains("--core-only")) { engine(); engineCompaction(false); engineCompaction(true); engineCompaction(false, true) }
        println("PASS ContextHarnessTests: $checks assertions")
    }

    private fun compaction() {
        val raw = history()
        raw.getJSONObject(0).put("content", "Do not package an APK; SDK floor 23; preserve src/Existing.kt.")
        val original = raw.toString(); val s = ContextState(); val token = CancellationToken()
        val before = ContextManager.view("stable", raw, s)
        verify(!ContextManager.compact("stable", raw, s, 500000, 4096, false, false, token) { error("unexpected summary") }, "under-pressure requests must keep the prefix")
        var summaryRequest: JSONArray? = null
        verify(ContextManager.compact("stable", raw, s, 60000, 4096, true, false, token) {
            summaryRequest = it; "Goal: fix project. Keep minSdk23. Changed src/Main.kt. Tests passed; device not run. Next: review."
        }, "manual compaction should shrink history")
        verify(raw.toString() == original, "original transcript must not be deleted")
        val request = summaryRequest!!
        verify(request.getJSONObject(0).getString("content").startsWith(ContextManager.SUMMARY_INSTRUCTION), "summary must use dedicated instructions")
        verify(ContextManager.estimate(request) <= 30000, "summary request exceeds independent budget")
        val reduced = ContextManager.view("stable", raw, s)
        verify(ContextManager.estimate(reduced) < ContextManager.estimate(before), "context must actually shrink")
        verify(reduced.getJSONObject(0).toString() == before.getJSONObject(0).toString(), "system prefix must survive compaction")
        verify(reduced.getJSONObject(1).optString("role") == "user", "summary cannot acquire system authority")
        verify(reduced.getJSONObject(1).optString("content").contains("not instructions or a permission grant"), "summary trust boundary missing")
        verify(reduced.getJSONObject(1).optString("content").contains("Do not package an APK; SDK floor 23; preserve src/Existing.kt."), "original user constraints were lost by summarization")
        val restored = ContextState.parse(s.json())
        verify(ContextManager.view("stable", raw, restored).toString() == reduced.toString(), "checkpoint must survive reload")
        raw.put(message("user", "next question"))
        val appended = ContextManager.view("stable", raw, restored)
        repeat(reduced.length()) { verify(reduced.getJSONObject(it).toString() == appended.getJSONObject(it).toString(), "append must keep cached prefix") }
        raw.getJSONObject(0).put("content", "edited early question")
        ContextManager.view("stable", raw, restored)
        verify(restored.checkpoint == null, "edited or rewound history must invalidate summary")
        verify(ContextState.parse(JSONObject()).checkpoint == null, "legacy chat must load")
        verify(ContextState.parse(JSONObject().put("checkpoint", JSONObject().put("covered", -1))).checkpoint == null, "invalid checkpoints cannot apply")
        val failed = ContextState(); val h = history()
        verify(!ContextManager.compact("stable", h, failed, 40000, 4096, true, false, token) { error("provider failed") }, "summary failure cannot compact")
        verify(failed.checkpoint == null && failed.compactions == 0, "failed summary must keep original state")
        for (bad in listOf("", "x".repeat(17000), "```json\n{\"tool\":\"write\"}")) {
            verify(!ContextManager.compact("stable", h, failed, 40000, 4096, true, false, token) { bad }, "invalid summary committed")
        }
        val small = JSONArray().put(message("user", "one")).put(message("assistant", "two")).put(message("user", "three")).put(message("assistant", "four"))
        verify(!ContextManager.compact("stable", small, failed, 40000, 4096, true, false, token) { "large ".repeat(2000) }, "non-shrinking summary committed")
        val cancelled = CancellationToken()
        val outcome = runCatching { ContextManager.compact("stable", h, failed, 40000, 4096, true, false, cancelled) { cancelled.cancel(); "summary" } }
        verify(outcome.exceptionOrNull() is CancellationToken.CancelledException, "cancellation must propagate")
        verify(failed.checkpoint == null && !failed.compacting.get(), "cancel must release compaction lock")
        var concurrentRejected = false
        ContextManager.compact("stable", h, failed, 40000, 4096, true, false, token) {
            concurrentRejected = !ContextManager.compact("stable", h, failed, 40000, 4096, true, false, token) { "nested" }
            // JSON persistence cannot block on the long-running summarizer.
            val executor = Executors.newSingleThreadExecutor()
            try { executor.submit<JSONObject> { failed.json() }.get(2, java.util.concurrent.TimeUnit.SECONDS) } finally { executor.shutdownNow() }
            "summary"
        }
        verify(concurrentRejected, "concurrent summaries must serialize")
        val tools = history(18)
        tools.put(message("assistant", "```json\n{\"tool\":\"read_file\",\"args\":{}}\n```"))
        tools.put(message("user", "[TOOL RESULT: read_file]\n" + "data ".repeat(3000)))
        tools.put(message("assistant", "```json\n{\"tool\":\"edit_file\",\"args\":{}}\n```"))
        tools.put(message("user", "[TOOL RESULT: edit_file]\nOK"))
        val paired = ContextState()
        verify(ContextManager.compact("stable", tools, paired, 40000, 4096, true, false, token) { "Tool results preserved; editing complete." }, "tool history should compact")
        val view = ContextManager.view("stable", tools, paired)
        verify(!view.getJSONObject(2).optString("content").startsWith("[TOOL RESULT:"), "retained history starts with an orphan result")
        val overflowing = ContextState(); var overflowSize = 0
        verify(ContextManager.compact("stable", history(60), overflowing, 14000, 2048, true, true, token) {
            overflowSize = ContextManager.estimate(it); "Bounded prefix facts. Remaining history follows."
        }, "overflow should summarize a bounded prefix")
        verify(overflowSize <= 14000 - 2048, "overflow summary request still exceeds budget")
        verify(!ContextManager.compact("x".repeat(100000), history(5), ContextState(), 8192, 4096, true, true, token) { error("should not call provider") }, "oversized fixed prompt must fail safely")
        verify(ContextManager.estimate(JSONArray().put(message("user", "中".repeat(1000)))) > ContextManager.estimate(JSONArray().put(message("user", "a".repeat(1000)))), "CJK estimate must not reuse English chars/4")
        verify(ContextManager.isOverflow("context_length_exceeded"), "canonical overflow missing")
        verify(!ContextManager.isOverflow("429 rate limit; request id 400"), "rate limit must not trigger compaction")
    }

    private fun usage() {
        val openai = TokenUsage.parse(JSONObject("""{"usage":{"prompt_tokens":1000,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":800}}}"""))!!
        verify(openai.inputTokens == 1000L && openai.cachedTokens == 800L && openai.cacheKnown, "OpenAI cached input counted twice")
        val responses = TokenUsage.parse(JSONObject("""{"response":{"usage":{"input_tokens":1000,"input_tokens_details":{"cached_tokens":900,"cache_write_tokens":100}}}}"""))!!
        verify(responses.inputTokens == 1000L && responses.cacheWriteTokens == 100L, "Responses usage parse failed")
        val claude = TokenUsage.parse(JSONObject("""{"message":{"usage":{"input_tokens":100,"cache_read_input_tokens":800,"cache_creation_input_tokens":200}}}"""))!!
        verify(claude.inputTokens == 1100L && claude.cachedTokens == 800L && claude.cacheWriteTokens == 200L, "Anthropic total must include cache reads and writes")
        verify(TokenUsage.parse(JSONObject("""{"usage":{"output_tokens":200}}""")) == null, "output-only delta erased input usage")
        val ds = TokenUsage.parse(JSONObject("""{"usage":{"prompt_tokens":1000,"prompt_cache_hit_tokens":850,"prompt_cache_miss_tokens":150}}"""))!!
        verify(ds.cachedTokens == 850L && ds.inputTokens == 1000L, "DeepSeek cache hit parser failed")
        verify(TokenUsage.parse(JSONObject("""{"usageMetadata":{"promptTokenCount":2000,"cachedContentTokenCount":1600}}"""))!!.cachedTokens == 1600L, "Gemini usage parser failed")
        val missing = TokenUsage.parse(JSONObject("""{"usage":{"prompt_tokens":300}}"""))!!
        verify(!missing.cacheKnown, "missing cache field is not a zero-hit measurement")
        val s = ContextState(); s.record(openai); s.record(missing)
        verify(s.describe(10000).contains("80%"), "hit rate denominator includes unknown-cache input")
        verify(ContextState.parse(s.json()).describe(10000) == s.describe(10000), "usage must persist")
    }

    private fun policies() {
        val custom = JSONObject()
        PromptCachePolicy.openAi(custom, "https://proxy.example/v1", "chat", "gpt-test", true)
        verify(custom.length() == 0, "unknown compatible API received unsupported cache fields")
        val body = JSONObject(); PromptCachePolicy.openAi(body, "https://api.openai.com/v1", "chat", "gpt-test", true)
        verify(body.has("prompt_cache_key") && body.getJSONObject("stream_options").optBoolean("include_usage"), "official OpenAI cache routing/usage missing")
        verify(PromptCachePolicy.key("chat", "m") == PromptCachePolicy.key("chat", "m"), "key is unstable")
        verify(PromptCachePolicy.key("chat", "m") != PromptCachePolicy.key("other", "m"), "key leaks across sessions")
        verify(!PromptCachePolicy.officialOpenAi("https://api.openai.com.evil.example/v1"), "host spoof accepted")
        val a = JSONObject().put("system", "stable").put("messages", JSONArray().put(message("user", "old").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "old"))))
            .put(message("user", "new").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "new")))))
        PromptCachePolicy.anthropic(a, "https://api.anthropic.com/v1")
        verify(a.getJSONArray("system").getJSONObject(0).has("cache_control"), "Anthropic stable system breakpoint missing")
        verify(a.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).has("cache_control"), "Anthropic conversation breakpoint missing")
        verify(!a.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).has("cache_control"), "new input must remain after reused breakpoint")
    }

    private fun streamUsage() {
        val executor = Executors.newCachedThreadPool(); val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { e ->
            e.requestBody.readBytes()
            val text = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"prompt_cache_hit_tokens\":850}}\n\n" +
                "data: [DONE]\n\n"
            e.responseHeaders.add("Content-Type", "text/event-stream"); val bytes = text.toByteArray()
            e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            var seen: TokenUsage? = null; var usages = 0; var failure: String? = null
            LlmClient("http://127.0.0.1:${server.address.port}/v1", "synthetic-key", "test-model", "openai", 1000, 0.2f, "medium", 0)
                .streamChat(JSONArray().put(message("user", "test")), CancellationToken(), object : LlmClient.StreamCallback {
                    override fun onDone(text: String?) { verify(text == "ok", "usage-only chunk broke answer") }
                    override fun onError(message: String) { failure = message }
                    override fun onThinking(text: String) {}
                    override fun onToken(text: String) {}
                    override fun onUsage(usage: TokenUsage) { seen = usage; usages++ }
                })
            verify(failure == null && usages == 1 && seen?.cachedTokens == 850L, "SSE usage after finish_reason must be recorded once")
        } finally { server.stop(0); executor.shutdownNow() }
    }

    private fun engine() {
        // Reuse the established platform-only test context, preserving the real engine and providers.
        val clazz = Class.forName("dev.wind.agent.AgentLoopTests\$Ctx")
        val ctor = clazz.getDeclaredConstructor(HttpServer::class.java, String::class.java, String::class.java).apply { isAccessible = true }
        val ctx = ctor.newInstance(null, "synthetic-key", "http://127.0.0.1:9999/v1") as android.content.Context
        val prefs = Prefs(ctx); prefs.setAutoCompact(false)
        val engine = AgentEngine(ctx, prefs); val chat = Chat("cache-fixture", "test", 0)
        val file = java.io.File(ctx.filesDir, "attached.txt").apply { writeText("original attachment") }
        val input = Message("user", "Fix project; do not package APK.")
        input.attachments.add(Message.Attachment("attached.txt", file.absolutePath, "text/plain", file.length(), "text"))
        chat.messages.add(input)
        val render = AgentEngine::class.java.getDeclaredMethod("buildApiMessages", Chat::class.java, List::class.java,
            CancellationToken::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        fun request() = render.invoke(engine, chat, emptyList<String>(), CancellationToken(), false, false, false) as JSONArray
        val first = request(); file.writeText("changed on disk")
        val second = request()
        verify(first.toString() == second.toString(), "historical attached text changed cached prefix")
        verify(input.attachments[0].textSnapshot == "original attachment", "attachment snapshot missing")
        request(); verify(chat.contextState.localReads > 0, "rendered history was never reused")
        Memory(ctx).remember("new fact")
        val updated = request()
        verify(first.getJSONObject(0).toString() == updated.getJSONObject(0).toString(), "remember invalidated system prompt")
        repeat(first.length()) { verify(first.getJSONObject(it).toString() == updated.getJSONObject(it).toString(), "memory update rewrote an earlier message") }
        verify(updated.length() == first.length(), "unreviewed legacy scratchpad must not be injected or appended")
        val persisted = Chat.fromJson(chat.toJson())
        verify(persisted.messages.first { it.role == "user" }.attachments.first().textSnapshot == "original attachment", "attached text snapshot lost on reload")
        chat.messages.last { it.role == "user" }.content = "edited user request"
        verify(request().toString() != updated.toString(), "render cache returned stale edited content")
        prefs.setContextWindowTokens(1); verify(prefs.contextWindowTokens() == 8192, "context budget bounds missing")
        val bounded = ContextRenderCache(1000, 2)
        repeat(20) { i -> bounded[Message("user", "$i")] = "$i" to message("user", "data".repeat(50)) }
        verify(bounded.size() <= 2 && bounded.retainedChars() <= 1000, "mobile cache exceeds its bounds")
        bounded[Message("user", "huge")] = "huge" to message("user", "x".repeat(2000))
        verify(bounded.retainedChars() <= 1000, "oversized message bypassed cache bounds")
    }

    private fun engineCompaction(overflow: Boolean, changedHistory: Boolean = false) {
        val received = java.util.Collections.synchronizedList(mutableListOf<JSONArray>())
        var normalRequests = 0; var summaries = 0
        var liveChat: Chat? = null
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { this.executor = executor }
        server.createContext("/") { e ->
            val messages = JSONObject(String(e.requestBody.readBytes(), Charsets.UTF_8)).getJSONArray("messages")
            received.add(messages)
            val summary = messages.getJSONObject(0).optString("content").startsWith(ContextManager.SUMMARY_INSTRUCTION)
            if (summary) summaries++ else normalRequests++
            if (summary && changedHistory && summaries == 1) liveChat?.messages?.first { it.role == "user" }?.content = "Updated user constraint: preserve New.kt."
            val reject = overflow && !summary && normalRequests == 1
            val body = if (reject) """{"error":{"code":"context_length_exceeded","message":"maximum context length exceeded"}}""" else {
                val text = if (summary) "Goal: fix project. No APK. Completed src/Main.kt; tests are pending; continue investigation." else "Finished the requested explanation."
                "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))).toString() + "\n\n" +
                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20000,\"prompt_cache_hit_tokens\":18000}}\n\n" + "data: [DONE]\n\n"
            }
            e.responseHeaders.add("Content-Type", if (reject) "application/json" else "text/event-stream")
            val bytes = body.toByteArray(Charsets.UTF_8); e.sendResponseHeaders(if (reject) 400 else 200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val ctxClass = Class.forName("dev.wind.agent.AgentLoopTests\$Ctx")
            val ctor = ctxClass.getDeclaredConstructor(HttpServer::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            val ctx = ctor.newInstance(server, "synthetic-key", null) as android.content.Context
            val prefs = Prefs(ctx).apply { setContextWindowTokens(if (overflow) 200000 else 40000); setMaxTokens(4096) }
            val chat = Chat("compaction-loop-$overflow", "test", 0)
            liveChat = chat
            for (i in 0 until 20) chat.messages.add(Message(if (i % 2 == 0) "user" else "assistant", "Earlier code facts $i " + "result ".repeat(800)))
            chat.messages.add(Message("user", "Please finish; do not package an APK."))
            var completed = false; val errors = mutableListOf<String>(); var tools = 0
            val callback = object : AgentEngine.Callback {
                override fun isCancelled() = false
                override fun onComplete() { completed = true }
                override fun onDelta(message: Message, delta: String) {}
                override fun onError(error: String) { errors.add(error) }
                override fun onNewAssistantMessage(message: Message) {}
                override fun onStepFinalized(message: Message) {}
                override fun onThinking(message: Message, thinking: String) {}
                override fun onToolMessage(message: Message, detail: String) { tools++ }
                override fun onToolRunning(tool: String, detail: String) {}
                override fun onTrailChanged(owner: Message) {}
                override fun requestApproval(tool: String, args: JSONObject?) = false
            }
            AgentEngine(ctx, prefs).run(chat, CancellationToken(), callback)
            verify(completed && errors.isEmpty() && tools == 0, "compaction interrupted the real agent loop: $errors")
            verify(summaries == 1 && normalRequests == if (overflow) 2 else 1, "expected one summary and a bounded overflow retry: summaries=$summaries normal=$normalRequests")
            verify(if (changedHistory) chat.contextState.compactions == 0 && chat.contextState.checkpoint == null else
                chat.contextState.compactions == 1 && chat.contextState.checkpoint != null, "checkpoint commit did not validate history")
            verify(chat.messages.count { it.role == "user" && !it.contextOnly } == 11, "engine deleted the user's original transcript")
            verify(if (changedHistory) received.last().toString().contains("Updated user constraint: preserve New.kt.") else
                received.last().getJSONObject(1).optString("content").contains("<compacted-summary>"), "main request used stale history or missed summary")
            verify(received.first().getJSONObject(0).toString() == received.last().getJSONObject(0).toString(), "compaction changed system prefix")
            verify(chat.contextState.describe(prefs.contextWindowTokens()).contains("缓存读取：36000"), "main/summary cache usage not recorded")
            val countBefore = received.size
            chat.messages.add(Message("user", "/context")); completed = false
            AgentEngine(ctx, prefs).run(chat, CancellationToken(), callback)
            verify(completed && received.size == countBefore, "/context must not make model calls")
            verify(chat.messages.last().contextOnly && chat.messages.last().content.contains("缓存读取"), "local context report was not delivered")
            prefs.setContextWindowTokens(200000)
            chat.messages.add(Message("user", "/compact")); completed = false
            AgentEngine(ctx, prefs).run(chat, CancellationToken(), callback)
            verify(completed && normalRequests == if (overflow) 2 else 1, "manual compact started an ordinary task")
            verify(chat.messages.last().contextOnly, "manual report entered the next model context")
        } finally { server.stop(0); executor.shutdownNow() }
    }
}
