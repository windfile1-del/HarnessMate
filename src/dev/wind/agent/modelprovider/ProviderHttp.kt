package dev.wind.agent.modelprovider

import dev.wind.agent.CancellationToken
import dev.wind.agent.NetworkPolicy
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Credentials never follow redirects; server bodies are not included in exception diagnostics. */
object ProviderHttp {
    fun form(values: Map<String,String>) = values.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
    fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    class Failure(val status: Int, val code: String) : Exception("请求失败 HTTP $status · $code")
    fun connect(url: String, method: String, headers: Map<String,String>, timeout: Int = 30000): HttpURLConnection {
        NetworkPolicy.requireUserEndpoint(url)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; instanceFollowRedirects = false; connectTimeout = minOf(timeout,25000); readTimeout = timeout
            headers.forEach { (k,v) -> setRequestProperty(k,v) }; setRequestProperty("Accept", "application/json")
        }
    }
    fun json(url: String, method: String = "GET", headers: Map<String,String> = emptyMap(), body: String? = null,
             contentType: String = "application/json", token: CancellationToken = CancellationToken()): JSONObject {
        val text = text(url,method,headers,body,contentType,token)
        return if(text.isBlank()) JSONObject() else JSONObject(text)
    }
    fun text(url: String, method: String = "GET", headers: Map<String,String> = emptyMap(), body: String? = null,
             contentType: String = "application/json", token: CancellationToken = CancellationToken()): String {
        val c = connect(url,method,headers); val registration = token.watchConnection(c)
        try {
            token.throwIfCancelled()
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType); c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
            val status = c.responseCode
            if (status !in 200..299) throw Failure(status, when(status) { 401 -> "需要重新登录或检查密钥";403 -> "账号无访问权限";429 -> "额度或速率限制";else -> "服务暂不可用" })
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192)
                while(true) { token.throwIfCancelled(); val n = input.read(b); if(n < 0) break; check(out.size()+n <= 4*1024*1024) { "模型目录超过大小限制" }; out.write(b,0,n) }
                out.toByteArray()
            }
            return String(bytes,Charsets.UTF_8)
        } finally { registration.close(); c.disconnect() }
    }
    fun headers(p: ModelProvider, key: String): Map<String,String> = when(p.protocol) {
        "anthropic" -> mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        "gemini" -> mapOf("x-goog-api-key" to key)
        "antigravity" -> mapOf("Authorization" to "Bearer $key", "User-Agent" to ANTIGRAVITY_UA)
        else -> mapOf("Authorization" to "Bearer $key")
    }
    // Pinned compatibility fingerprint follows OpenCodex 2.69.0, not a claim of official Android support.
    const val ANTIGRAVITY_UA = "antigravity/ide/2.5.5 (os_type=windows; arch=amd64; aidev_client; auth_method=oauth)"
}
