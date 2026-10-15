package dev.wind.agent.modelprovider

import dev.wind.agent.CancellationToken
import dev.wind.agent.LlmClient
import org.json.JSONArray
import org.json.JSONObject

/** Responses and CCA always stream, including callers that only want a final answer. No automatic re-billing retries. */
object SubscriptionClient {
    fun stream(store: ModelProviderStore, p: ModelProvider, messages: JSONArray, googleBody: JSONObject?, maxTokens: Int,
               timeoutSeconds: Int, token: CancellationToken, callback: LlmClient.StreamCallback, cacheScope: String? = null) {
        var terminal = false
        try {
            val secret = SubscriptionOAuth.fresh(store,p,token)
            val key=secret.optString(if(p.subscription) "access_token" else "api_key")
            check(key.isNotBlank()) { "请重新登录或填写密钥" }
            val protocol=p.protocol
            val body = if(protocol=="responses") ReasoningWire.responses(p,messages,maxTokens) else {
                check(p.project.isNotBlank()) { "Google 账户缺少 Cloud Code Assist 项目，请重新登录" }
                val request=ReasoningWire.apply(p,googleBody ?: error("缺少 Google 请求内容"))
                // Stable within this conversation, including agent tool loops. Not an authorization credential.
                val anchor=cacheScope ?: (0 until messages.length()).map { messages.getJSONObject(it) }.firstOrNull { it.optString("role")=="user" }?.opt("content")?.toString() ?: p.id
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest((p.id+":"+anchor).toByteArray())
                val session=java.math.BigInteger(1,digest.copyOfRange(0,8)).and(java.math.BigInteger("7fffffffffffffff",16))
                request.put("sessionId","-$session")
                val model=p.selected().wireIds[p.effort] ?: p.model
                JSONObject().put("model",model).put("project",p.project).put("userAgent","antigravity").put("requestType","agent")
                    .put("requestId","agent-${java.util.UUID.randomUUID()}").put("request",request)
            }
            if (protocol == "responses" && dev.wind.agent.context.PromptCachePolicy.officialOpenAi(p.url) && cacheScope != null)
                body.put("prompt_cache_key", dev.wind.agent.context.PromptCachePolicy.key(p.id + ":" + cacheScope, p.model))
            val endpoint=if(protocol=="responses") p.url.trimEnd('/')+"/responses" else p.url.trimEnd('/')+"/v1internal:streamGenerateContent?alt=sse"
            val c=ProviderHttp.connect(endpoint,"POST",ProviderHttp.headers(p,key),timeoutSeconds.coerceIn(10,1800)*1000)
            val reg=token.watchConnection(c)
            try {
                c.setRequestProperty("Accept","text/event-stream"); c.setRequestProperty("Content-Type","application/json; charset=utf-8")
                c.doOutput=true; token.throwIfCancelled(); c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if(c.responseCode !in 200..299) {
                    // Preserve canonical context errors without returning arbitrary error bodies or credentials.
                    val overflow = if (c.responseCode in listOf(400, 413)) runCatching {
                        val bytes = c.errorStream?.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
                            while (out.size() < 16384) { val n = input.read(buffer, 0, minOf(buffer.size, 16384 - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
                            out.toString("UTF-8")
                        } ?: ""
                        dev.wind.agent.context.ContextManager.isOverflow(bytes)
                    }.getOrDefault(false) else false
                    throw ProviderHttp.Failure(c.responseCode,if (overflow) "CONTEXT_WINDOW_EXCEEDED" else when(c.responseCode) { 401 -> "需要重新登录";403 -> "账户无此模型权限";429 -> "账户额度或速率限制";else -> "服务请求失败" })
                }
                val parser=SubscriptionStream(protocol, callback::onToken,callback::onThinking)
                var usage: dev.wind.agent.context.TokenUsage? = null
                c.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                    val data=StringBuilder()
                    fun flush() {
                        if(data.isEmpty()) return
                        val text=data.toString(); data.setLength(0)
                        if(text!="[DONE]") {
                            val event = JSONObject(text)
                            dev.wind.agent.context.TokenUsage.parse(event, usage)?.let { usage = it; callback.onUsageSnapshot(it) }
                            parser.consume(event)
                        }
                    }
                    while(true) {
                        token.throwIfCancelled()
                        if(callback.isCancelled()) throw CancellationToken.CancelledException()
                        val line=boundedLine(input) ?: break
                        if(line.isEmpty()) { flush(); if(parser.completed || parser.failed!=null) break }
                        else if(line.startsWith("data:")) { if(data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()); check(data.length<=1024*1024) { "流式事件超过大小限制" } }
                    }
                    flush()
                }
                parser.failed?.let { error(it) }
                check(parser.completed) { "连接结束但未收到模型完成事件；请检查网络后重试" }
                if(parser.truncated) callback.onTruncated()
                usage?.let { callback.onUsage(it) }
                terminal=true; callback.onDone(parser.text.toString())
            } finally { reg.close(); c.disconnect() }
        } catch(e: Exception) {
            if(!terminal) {
                terminal=true
                if(token.isCancelled || callback.isCancelled()) callback.onDone("")
                else {
                    val diagnostic=ModelDiscovery.diagnostic(e)
                    runCatching { store.find(p.id)?.let { latest -> store.put(latest.copy(status=diagnostic)) } }
                    callback.onError(diagnostic)
                }
            }
        }
    }
    private fun boundedLine(reader: java.io.BufferedReader): String? {
        val out=StringBuilder()
        while(true) { val b=reader.read(); if(b<0) return out.toString().takeIf { it.isNotEmpty() }; if(b==10) return out.toString().trimEnd('\r'); out.append(b.toChar()); check(out.length<=1024*1024) { "事件行超过大小限制" } }
    }
    fun complete(store: ModelProviderStore,p: ModelProvider,messages: JSONArray,googleBody: JSONObject?,maxTokens: Int,timeout: Int,token: CancellationToken): String {
        var text=""; var failure:String?=null
        stream(store,p,messages,googleBody,maxTokens,timeout,token,object:LlmClient.StreamCallback {
            override fun onToken(text:String) {}
            override fun onThinking(text:String) {}
            override fun onDone(result:String?) { text=result ?: "" }
            override fun onError(message:String) { failure=message }
            override fun onTruncated() { failure="模型回答未完整生成" }
        })
        token.throwIfCancelled(); failure?.let { error(it) }; return text
    }
}
