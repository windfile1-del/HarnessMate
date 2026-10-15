package dev.wind.agent.modelprovider

import dev.wind.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject

object ModelDiscovery {
    fun refresh(store: ModelProviderStore, id: String, token: CancellationToken = CancellationToken()): ModelProvider {
        val original = store.find(id) ?: error("供应商已删除")
        try {
            val secret = SubscriptionOAuth.fresh(store, original, token)
            val p = store.find(id) ?: error("供应商已删除")
            val key = secret.optString(if(p.subscription) "access_token" else "api_key")
            check(key.isNotBlank()) { "请填写密钥或登录订阅账号" }
            val headers = ProviderHttp.headers(p,key)
            val result = mutableListOf<DiscoveredModel>()
            var documented = false
            var cursor = ""; val cursors = mutableSetOf<String>()
            for (page in 0 until 20) {
                val base = p.url.trimEnd('/')
                val url = when(p.protocol) {
                    "antigravity" -> "$base/v1internal:fetchAvailableModels"
                    "gemini" -> "$base/models?pageSize=1000" + if(cursor.isBlank()) "" else "&pageToken=${ProviderHttp.encode(cursor)}"
                    "anthropic" -> "$base/models?limit=100" + if(cursor.isBlank()) "" else "&after_id=${ProviderHttp.encode(cursor)}"
                    else -> "$base/models"
                }
                val j = try { ProviderHttp.json(url, if(p.protocol == "antigravity") "POST" else "GET", headers,
                    if(p.protocol == "antigravity") JSONObject().put("project",p.project).toString() else null, token = token) }
                catch(e: ProviderHttp.Failure) {
                    if(p.preset!="glm" || e.status !in listOf(404,405)) throw e
                    // GLM does not consistently expose the OpenAI model-list RPC.
                    // Fetch its official documentation directory without sending the API key there.
                    result.addAll(glmDocumentedModels(ProviderHttp.text("https://docs.bigmodel.cn/sitemap.xml",token=token)))
                    documented = true; break
                }
                result.addAll(parse(p,j))
                cursor = when(p.protocol) {
                    "gemini" -> j.optString("nextPageToken")
                    "anthropic" -> if(j.optBoolean("has_more")) j.optString("last_id") else ""
                    else -> ""
                }
                if(cursor.isBlank()) break
                check(cursors.add(cursor) && page < 19) { "模型列表分页未正常结束" }
            }
            val models = groupAntigravity(p, result.distinctBy { it.id })
            check(models.isNotEmpty()) { "服务未返回可用聊天模型；可重试或手动填写模型 ID" }
            synchronized(SubscriptionOAuth.lock) {
                val latest = store.find(id) ?: error("供应商已删除")
                val selected = latest.model.takeIf { m -> models.any { it.id == m } } ?: models.first().id
                val selectedInfo = models.first { it.id == selected }
                val next = latest.copy(models=models, model=selected, effort=latest.effort.takeIf { it in selectedInfo.efforts } ?: "default",
                    status=if(documented) "已读取官方文档目录 · ${models.size} 个模型，账号权限待实际请求确认" else "已读取 ${models.size} 个模型", fetchedAt=System.currentTimeMillis())
                store.put(next); return next
            }
        } catch (e: Exception) {
            store.find(id)?.let { store.put(it.copy(status=diagnostic(e))) }
            throw e
        }
    }
    fun diagnostic(e: Exception) = when(e) {
        is ProviderHttp.Failure -> e.message ?: "连接失败"
        is java.net.UnknownHostException -> "无法解析服务地址"
        is java.net.SocketTimeoutException -> "请求超时，请重试"
        is java.net.ConnectException -> "网络连接失败"
        is javax.net.ssl.SSLException -> "TLS 连接失败"
        is CancellationToken.CancelledException -> "操作已取消"
        is IllegalStateException -> e.message ?: "配置或认证不完整"
        else -> "请求或返回格式异常，请检查接口配置"
    }
    fun parse(p: ModelProvider, j: JSONObject): List<DiscoveredModel> {
        if(p.protocol == "antigravity") {
            val map = j.optJSONObject("models") ?: JSONObject()
            return map.keys().asSequence().map { id ->
                val metadata = map.optJSONObject(id) ?: JSONObject()
                DiscoveredModel(id, metadata.optString("displayName",id), protocol="antigravity")
            }.toList()
        }
        val a = j.optJSONArray(if(p.protocol == "gemini" || p.preset == "chatgpt") "models" else "data") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i ->
            val entry = a.getJSONObject(i)
            if(p.preset == "chatgpt" && entry.optString("visibility") != "list") return@mapNotNull null
            if(p.protocol == "gemini") {
                val methods = entry.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                if((0 until methods.length()).none { methods.getString(it) == "generateContent" }) return@mapNotNull null
            }
            val id = when(p.protocol) { "gemini" -> entry.optString("name").removePrefix("models/"); "responses" -> if(p.preset == "chatgpt") entry.optString("slug") else entry.optString("id"); else -> entry.optString("id") }
            if(id.isBlank()) return@mapNotNull null
            // Model catalogs are not universally chat-only. Only filter definitively non-chat families.
            if(p.preset == "openai" && (id.startsWith("text-embedding") || id.startsWith("whisper") || id.startsWith("tts") || id.startsWith("dall-e") || id.startsWith("gpt-image") || id.startsWith("sora") || id.contains("moderation") || id.startsWith("davinci") || id.startsWith("babbage") || id.contains("audio") || id.contains("transcribe") || id.contains("realtime") || id.endsWith("instruct"))) return@mapNotNull null
            val effortArray = entry.optJSONArray("supported_reasoning_efforts") ?: entry.optJSONArray("supported_reasoning_levels")
            val advertised = effortArray?.let { e -> (0 until e.length()).mapNotNull { n -> val v=e.opt(n); if(v is String) v else (v as? JSONObject)?.optString("effort") }.filter { it in setOf("none","minimal","low","medium","high","xhigh","max") } }
            val efforts = if(!advertised.isNullOrEmpty()) listOf("default")+advertised else ReasoningCapabilities.efforts(p.preset,id,p.protocol)
            // Zen is heterogeneous; endpoint format is a property of each model, not the vendor account.
            val protocol = if(p.preset == "opencode") when { id.startsWith("claude") -> "anthropic"; id.startsWith("gemini") -> "gemini"; id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-") -> "responses"; else -> "openai" } else p.protocol
            DiscoveredModel(id,entry.optString("display_name",entry.optString("displayName",id)),efforts,protocol=protocol,
                inputTokenLimit=if (p.protocol == "gemini") entry.optInt("inputTokenLimit").coerceAtLeast(0) else 0)
        }
    }
    fun glmDocumentedModels(xml: String): List<DiscoveredModel> =
        Regex("<loc>https://docs\\.bigmodel\\.cn/cn/guide/models/(?:text|vlm)/(glm-[a-zA-Z0-9._-]+)</loc>")
            .findAll(xml).map { it.groupValues[1] }.filter { !it.contains("ocr") }.distinct()
            .map { DiscoveredModel(it,efforts=ReasoningCapabilities.efforts("glm",it),protocol="openai") }.toList()
    fun groupAntigravity(p: ModelProvider, models: List<DiscoveredModel>): List<DiscoveredModel> {
        if(p.protocol != "antigravity") return models
        val ids = models.map { it.id }.toSet(); val emitted=mutableSetOf<String>(); val out=mutableListOf<DiscoveredModel>()
        for(m in models) {
            if(m.id in listOf("gemini-3.1-pro-low","gemini-pro-agent") && ids.containsAll(listOf("gemini-3.1-pro-low","gemini-pro-agent"))) {
                if(emitted.add("gemini-3.1-pro")) out.add(DiscoveredModel("gemini-3.1-pro","Gemini 3.1 Pro",listOf("default","low","high"),
                    mapOf("default" to "gemini-pro-agent","low" to "gemini-3.1-pro-low","high" to "gemini-pro-agent"),"antigravity"))
                continue
            }
            val base=m.id.replace(Regex("-(low|medium|high)$"), "")
            if(listOf("low","medium","high").all { "$base-$it" in ids }) {
                if(emitted.add(base)) out.add(DiscoveredModel(base,base,listOf("default","low","medium","high"),
                    listOf("low","medium","high").associateWith { "$base-$it" } + mapOf("default" to "$base-medium"), "antigravity"))
            } else if(m.id.endsWith("-tiered") && m.id.startsWith("gemini-")) {
                out.add(m.copy(efforts=listOf("default","low","medium","high")))
            } else if(m.id.startsWith("claude-")) {
                out.add(m.copy(efforts=listOf("default","low","medium","high")))
            } else out.add(m)
        }
        return out
    }
}
