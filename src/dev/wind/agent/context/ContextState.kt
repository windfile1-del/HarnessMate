package dev.wind.agent.context

import org.json.JSONObject

/** A derived model view; original chat messages are never removed. */
data class ContextCheckpoint(val covered: Int, val digest: String, val summary: String, val userAnchors: List<String> = emptyList()) {
    fun json() = JSONObject().put("covered", covered).put("digest", digest).put("summary", summary)
        .put("userAnchors", org.json.JSONArray(userAnchors))
}

class ContextState {
    val compacting = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var checkpoint: ContextCheckpoint? = null
    @Volatile var memoryDigest: String = ""
    @Volatile var memoryEpoch: Long = 0
    @Volatile var memoryNamespace: String = ""
    @Volatile var memoryTokens: Int = 0
    @Volatile var estimatedTokens: Int = 0
    @Volatile var status: String = "未压缩"
    @Volatile var compactions: Int = 0
    @Volatile var localReads: Long = 0
    @Volatile var localBuilds: Long = 0
    private var input: Long = 0
    private var cached: Long = 0
    private var written: Long = 0
    private var reported: Long = 0
    private var cacheReported: Long = 0
    private var knownInput: Long = 0
    @Volatile var latestUsage: TokenUsage? = null
        private set
    @Volatile var latestSummary: Boolean = false
        private set
    @Volatile private var scale: Double = 1.0
    @Volatile private var serverFloorActive: Boolean = false

    fun occupiedTokens(): Int = maxOf(estimatedTokens * scale,
        latestUsage?.takeIf { serverFloorActive && !latestSummary }?.let { (it.inputTokens + it.outputTokens).toDouble() } ?: 0.0)
        .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    fun resetCalibration() { scale = 1.0; serverFloorActive = false }
    @Synchronized fun observe(usage: TokenUsage, summary: Boolean = false) {
        latestUsage = usage; latestSummary = summary
        serverFloorActive = !summary
        if (!summary && estimatedTokens > 0 && usage.inputTokens > 0)
            scale = maxOf(1.0, usage.inputTokens.toDouble() / estimatedTokens)
    }

    @Synchronized fun record(usage: TokenUsage, summary: Boolean = false) {
        observe(usage, summary)
        input += usage.inputTokens; cached += usage.cachedTokens; written += usage.cacheWriteTokens
        reported++; if (usage.cacheKnown) { cacheReported++; knownInput += usage.inputTokens }
    }

    @Synchronized fun describe(window: Int): String =
        "上下文占用：${occupiedTokens()} / $window tokens；本地估算 $estimatedTokens（结合最近服务端输入校准）\n" +
        "自动压缩阈值：85%；输出预留可能使触发提前\n" +
        (latestUsage?.let { "最近${if (latestSummary) "摘要" else "模型"}请求：输入 ${it.inputTokens}；输出 ${it.outputTokens}；缓存读取 ${if (it.cacheKnown) it.cachedTokens.toString() else "未报告"}；缓存写入 ${it.cacheWriteTokens}\n" } ?: "最近请求用量：尚未报告\n") +
        "压缩：$status；累计 $compactions 次；原始聊天记录保留\n" +
        "本地请求消息复用：$localReads 次；重新组装：$localBuilds 次\n" +
        "本轮相关记忆：$memoryTokens tokens；记忆版本：$memoryEpoch\n" +
        "服务端输入：$input tokens；缓存读取：$cached；缓存写入：$written\n" +
        (if (cacheReported > 0 && knownInput > 0) "包含缓存字段请求的读取占比：${cached * 100 / knownInput}%\n" else "服务端尚未返回缓存用量；无法判断实际命中率\n") +
        "用量报告：$reported 次，其中 $cacheReported 次包含缓存字段；包含摘要请求。"

    @Synchronized fun json(): JSONObject = JSONObject().also { j ->
        checkpoint?.let { j.put("checkpoint", it.json()) }
        j.put("memoryDigest", memoryDigest).put("estimatedTokens", estimatedTokens).put("status", status)
        j.put("memoryEpoch",memoryEpoch).put("memoryNamespace",memoryNamespace).put("memoryTokens",memoryTokens)
            .put("compactions", compactions).put("localReads", localReads).put("localBuilds", localBuilds)
            .put("input", input).put("cached", cached).put("written", written)
            .put("reported", reported).put("cacheReported", cacheReported).put("knownInput", knownInput)
        j.put("scale", scale)
        j.put("serverFloorActive", serverFloorActive)
        latestUsage?.let { j.put("latestUsage", JSONObject().put("input", it.inputTokens).put("output", it.outputTokens)
            .put("cached", it.cachedTokens).put("written", it.cacheWriteTokens).put("known", it.cacheKnown)) }
        j.put("latestSummary", latestSummary)
    }

    companion object {
        fun parse(j: JSONObject?): ContextState = ContextState().also { s ->
            if (j != null) {
                j.optJSONObject("checkpoint")?.let { c ->
                    val count = c.optInt("covered"); val digest = c.optString("digest"); val text = c.optString("summary")
                    if (count > 0 && digest.matches(Regex("[a-f0-9]{64}")) && text.isNotBlank() && text.length <= 16000)
                        s.checkpoint = ContextCheckpoint(count, digest, text, c.optJSONArray("userAnchors")?.let { a ->
                            (0 until minOf(a.length(), 3)).map { a.optString(it).take(4000) }
                        } ?: emptyList())
                }
                s.memoryDigest = j.optString("memoryDigest")
                s.memoryEpoch=j.optLong("memoryEpoch").coerceAtLeast(0);s.memoryNamespace=j.optString("memoryNamespace");s.memoryTokens=j.optInt("memoryTokens").coerceAtLeast(0)
                s.estimatedTokens = j.optInt("estimatedTokens").coerceAtLeast(0); s.status = j.optString("status", "未压缩").take(300)
                s.compactions = j.optInt("compactions").coerceAtLeast(0)
                s.localReads = j.optLong("localReads").coerceAtLeast(0); s.localBuilds = j.optLong("localBuilds").coerceAtLeast(0)
                s.input = j.optLong("input").coerceAtLeast(0); s.cached = j.optLong("cached").coerceAtLeast(0)
                s.written = j.optLong("written").coerceAtLeast(0); s.reported = j.optLong("reported").coerceAtLeast(0)
                s.cacheReported = j.optLong("cacheReported").coerceAtLeast(0)
                s.knownInput = j.optLong("knownInput").coerceAtLeast(0)
                s.scale = j.optDouble("scale", 1.0).takeIf { it.isFinite() && it >= 1.0 } ?: 1.0
                s.latestSummary = j.optBoolean("latestSummary")
                s.serverFloorActive = j.optBoolean("serverFloorActive")
                j.optJSONObject("latestUsage")?.let { u -> s.latestUsage = TokenUsage(u.optLong("input"),
                    u.optLong("output"), u.optLong("cached"), u.optLong("written"), u.optBoolean("known")) }
            }
        }
    }
}
