package dev.wind.agent.modelprovider

import org.json.JSONArray
import org.json.JSONObject

object ReasoningWire {
    fun budget(e: String) = when(e) { "none" -> 0; "low" -> 2048; "medium" -> 8192; "high" -> 16384; "xhigh" -> 32768; "max" -> 60000; else -> -1 }
    fun apply(p: ModelProvider, body: JSONObject): JSONObject {
        check(ReasoningCapabilities.valid(p,p.effort)) { "模型不支持当前推理强度，请重新选择" }
        val e=p.effort
        when(p.protocol) {
            "openai" -> {
                body.remove("reasoning_effort")
                if(p.selected().efforts.size>1 && (p.model.startsWith("gpt-") || p.model.startsWith("o3") || p.model.startsWith("o4"))) {
                    body.remove("temperature")
                    if(body.has("max_tokens")) body.put("max_completion_tokens",body.remove("max_tokens"))
                }
                if(p.preset in listOf("deepseek","kimi","glm") && p.selected().efforts.size>1) {
                    if(e!="default") body.put("thinking",JSONObject().put("type",if(e=="none") "disabled" else "enabled"))
                    if(e in listOf("low","high","max") && (p.preset=="deepseek" && (p.model.startsWith("deepseek-v4") || p.model=="deepseek-flash") || p.preset=="glm" && p.model.startsWith("glm-5.3"))) body.put("reasoning_effort",e)
                    if(e!="none") body.remove("temperature")
                } else if(e!="default") { body.put("reasoning_effort",e); body.remove("temperature") }
            }
            "anthropic" -> {
                body.remove("thinking"); body.remove("output_config")
                if(ReasoningCapabilities.adaptiveClaude(p.model)) body.remove("temperature")
                if(e=="none") body.put("thinking",JSONObject().put("type","disabled"))
                else if(e!="default") {
                    val adaptive=ReasoningCapabilities.adaptiveClaude(p.model)
                    if(adaptive) {
                        body.put("thinking",JSONObject().put("type","adaptive"))
                        body.put("output_config",JSONObject().put("effort",e)); body.remove("temperature")
                    } else {
                        val b=budget(e); body.put("thinking",JSONObject().put("type","enabled").put("budget_tokens",b))
                        body.put("max_tokens",maxOf(body.optInt("max_tokens",4096),b+4096)); body.put("temperature",1.0)
                    }
                }
            }
            "gemini", "antigravity" -> {
                val g=body.optJSONObject("generationConfig") ?: JSONObject(); g.remove("thinkingConfig")
                val wire=p.selected().wireIds[e] ?: p.model
                val suffix=Regex("-(low|medium|high)$").containsMatchIn(wire)
                if(p.selected().efforts.size>1 && (p.model.startsWith("gemini-") || e!="default")) {
                    val tc=JSONObject()
                    if(p.model.startsWith("gemini-")) tc.put("includeThoughts",true)
                    if(e!="default" && !(p.protocol=="antigravity" && suffix)) {
                        if(p.protocol=="antigravity" || p.model.startsWith("gemini-3")) tc.put("thinkingLevel",e)
                        else tc.put("thinkingBudget",minOf(budget(e),if(p.model.contains("flash")) 24576 else 32768))
                    }
                    g.put("thinkingConfig",tc)
                }
                body.put("generationConfig",g)
            }
        }
        return body
    }
    fun responses(p: ModelProvider, messages: JSONArray, maxTokens: Int): JSONObject {
        check(ReasoningCapabilities.valid(p,p.effort)) { "推理强度不受此模型支持" }
        val input=JSONArray()
        for(i in 0 until messages.length()) {
            val m=messages.getJSONObject(i); val role=m.optString("role","user"); val raw=m.opt("content")
            val content = if(raw is JSONArray) JSONArray().also { a ->
                for(n in 0 until raw.length()) {
                    val part=raw.getJSONObject(n)
                    when(part.optString("type")) {
                        "text" -> a.put(JSONObject().put("type",if(role=="assistant") "output_text" else "input_text").put("text",part.optString("text")))
                        "image_url" -> { check(role!="assistant"); a.put(JSONObject().put("type","input_image").put("image_url",part.getJSONObject("image_url").getString("url"))) }
                        else -> error("Responses 不支持此附件类型")
                    }
                }
            } else raw?.toString() ?: ""
            input.put(JSONObject().put("role",role).put("content",content))
        }
        val body=JSONObject().put("model",p.model).put("input",input).put("stream",true).put("store",false)
        if(maxTokens>0) body.put("max_output_tokens",maxTokens)
        if(p.effort!="default") body.put("reasoning",JSONObject().put("effort",p.effort))
        return body
    }
}

/** Strict incremental parsers are also used in host fixtures; EOF alone is not success. */
class SubscriptionStream(private val protocol: String, private val token: (String)->Unit, private val thought: (String)->Unit) {
    private var totalChars=0
    val text=StringBuilder(); var completed=false; private set
    var truncated=false; private set
    var failed: String?=null; private set
    fun consume(j: JSONObject) {
        if(completed || failed!=null) return
        if(protocol=="responses") {
            when(j.optString("type")) {
                "response.output_text.delta" -> append(j.optString("delta"),false)
                "response.reasoning_summary_text.delta" -> append(j.optString("delta"),true)
                "response.completed" -> {
                    val r=j.optJSONObject("response")
                    if(r!=null && r.optString("status","completed")!="completed") { failed="模型未成功完成响应"; return }
                    if(text.isEmpty()) {
                        val out=r?.optJSONArray("output") ?: JSONArray()
                        for(i in 0 until out.length()) {
                            val content=out.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for(n in 0 until content.length()) { val c=content.getJSONObject(n); if(c.optString("type")=="output_text") append(c.optString("text"),false) }
                        }
                    }; completed=true
                }
                "response.incomplete" -> { truncated=true; completed=true }
                "response.failed", "error" -> {
                    val code=j.optJSONObject("response")?.optJSONObject("error")?.optString("code") ?: j.optString("code","unknown_error")
                    failed=when(code) {
                        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 订阅共享额度已用完，请到 ChatGPT 设置查看用量"
                        "subscription_sharing_usage_unavailable" -> "此账户的 ChatGPT 订阅共享暂不可用"
                        else -> "模型生成失败 · ${code.take(100).replace(Regex("[^a-zA-Z0-9_.-]"), "") }"
                    }
                }
            }
        } else {
            val r=j.optJSONObject("response") ?: j
            if(r.has("error")) { failed="Google 模型生成失败"; return }
            val candidates=r.optJSONArray("candidates") ?: return
            if(candidates.length()==0) return
            val c=candidates.getJSONObject(0); val parts=c.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            for(i in 0 until parts.length()) { val part=parts.getJSONObject(i); if(part.has("text")) append(part.optString("text"),part.optBoolean("thought")) }
            when(c.optString("finishReason")) {
                "STOP" -> completed=true
                "MAX_TOKENS" -> { completed=true; truncated=true }
                "" -> {}
                else -> failed="Google 回答被终止 · ${c.optString("finishReason").take(64)}"
            }
        }
    }
    private fun append(s: String, thinking: Boolean) {
        if(s.isEmpty()) return
        check(totalChars+s.length<=8*1024*1024) { "模型输出超过大小限制" }; totalChars+=s.length
        if(thinking) thought(s) else { text.append(s); token(s) }
    }
}
