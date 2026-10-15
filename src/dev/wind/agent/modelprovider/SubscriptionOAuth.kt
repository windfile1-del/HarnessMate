package dev.wind.agent.modelprovider

import dev.wind.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** PKCE public-client OAuth. Nothing is scraped from a browser or another app's credential store. */
object SubscriptionOAuth {
    val lock = Any()
    const val OPENAI_TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
    const val RESOURCE = "https://api.openai.com/v1"
    const val APPLICATION_NAME = "Harness Mate"
    const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    // Public source snapshot: no bundled third-party OAuth application credentials.
    const val GOOGLE_CLIENT = ""
    private const val GOOGLE_PUBLIC_SECRET = ""
    private fun random() = Base64Url.encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
    fun challenge(verifier: String) = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    class Attempt(val provider: ModelProvider, val host: String, private val oldSecret: JSONObject = JSONObject()) : java.io.Closeable {
        val state = random(); val nonce = random(); val verifier = random()
        private val server = ServerSocket().apply {
            reuseAddress = false
            val port = if(provider.preset == "chatgpt") 0 else 51121
            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"),port)); soTimeout = 1000
        }
        val path = if(provider.preset == "chatgpt") "/auth/callback" else "/callback"
        val redirect = "http://127.0.0.1:${server.localPort}$path"
        fun authorizeUrl(): String {
            val openai = provider.preset == "chatgpt"
            if(!openai) check(GOOGLE_CLIENT.isNotBlank() && GOOGLE_PUBLIC_SECRET.isNotBlank()) { "Google OAuth app credentials are not bundled in this source release. Configure your own OAuth client locally." }
            val q = linkedMapOf("client_id" to if(openai) provider.clientId.ifBlank { "dynamic_agent_client" } else GOOGLE_CLIENT,
                "response_type" to "code", "redirect_uri" to redirect, "state" to state,
                "code_challenge_method" to "S256", "code_challenge" to challenge(verifier))
            if(openai) {
                q["nonce"] = nonce; q["ext_agent_host_id"] = host; q["resource"] = RESOURCE
                q["scope"] = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
                if(provider.clientId.isBlank()) q["agent_name_hint"] = APPLICATION_NAME
                else {
                    oldSecret.optString("id_token").takeIf { it.isNotBlank() }?.let { q["id_token_hint"] = it }
                    provider.email.takeIf { it.isNotBlank() }?.let { q["login_hint"] = it }
                }
            } else {
                q["scope"] = listOf("cloud-platform","userinfo.email","userinfo.profile","cclog","experimentsandconfigs")
                    .joinToString(" ") { "https://www.googleapis.com/auth/$it" }
                q["access_type"] = "offline"; q["prompt"] = "consent select_account"
            }
            return (if(openai) "https://auth.openai.com/api/accounts/authorize" else "https://accounts.google.com/o/oauth2/v2/auth") + "?" + ProviderHttp.form(q)
        }
        fun await(token: CancellationToken): Map<String,String> {
            val end = System.currentTimeMillis()+5*60*1000
            val registration = token.onCancel { close() }
            try {
                while(System.currentTimeMillis()<end) {
                    token.throwIfCancelled()
                    val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    socket.use { s ->
                        s.soTimeout=3000
                        val input=s.getInputStream(); val bytes=java.io.ByteArrayOutputStream()
                        while(bytes.size()<8192) { val b=input.read(); if(b<0 || b==10) break; bytes.write(b) }
                        val line=String(bytes.toByteArray(),Charsets.US_ASCII).trim()
                        val target=line.split(' ').getOrNull(1) ?: ""
                        var denied=false
                        val result=try { validateCallback(redirect + target.removePrefix(path), redirect, state, provider.clientId, target) }
                            catch(e: IllegalStateException) { if(e.message=="用户取消授权或授权未完成") denied=true; null }
                            catch (_: Exception) { null }
                        val ok=result!=null
                        val text=if(ok) "Authorization received. You may return to Harness." else if(denied) "Authorization declined. You may return to Harness." else "Invalid authorization callback. Return to the original sign-in window."
                        val response="HTTP/1.1 ${if(ok) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${text.toByteArray().size}\r\n\r\n$text"
                        s.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                        if(denied) error("用户取消授权或授权未完成")
                        if(result!=null) return result
                    }
                }
                error("登录等待超时，请重新开始")
            } finally { registration.close(); close() }
        }
        override fun close() { try { server.close() } catch (_: Exception) {} }
    }
    /** Duplicate parameters, wrong callback path/state and account substitution are rejected. */
    fun validateCallback(url: String, redirect: String, state: String, previousClient: String, target: String? = null): Map<String,String> {
        val uri=URI(url); val expected=URI(redirect)
        check(uri.scheme==expected.scheme && uri.host==expected.host && uri.port==expected.port && uri.path==expected.path && uri.fragment==null) { "登录回调地址不匹配" }
        if(target!=null) check(target.startsWith(expected.path+"?") && !target.startsWith("//")) { "登录回调路径不匹配" }
        val q=linkedMapOf<String,String>()
        for(part in (uri.rawQuery ?: "").split('&')) {
            val pair=part.split('=',limit=2); val name=URLDecoder.decode(pair[0],"UTF-8")
            check(!q.containsKey(name)) { "重复登录参数" }; q[name]=URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
        }
        check(MessageDigest.isEqual(q["state"]?.toByteArray() ?: ByteArray(0),state.toByteArray())) { "登录状态不匹配" }
        if(previousClient.isNotBlank() && q.containsKey("client_id")) check(q["client_id"]==previousClient) { "登录账户注册不匹配" }
        check(!q.containsKey("error")) { "用户取消授权或授权未完成" }
        check(!q["code"].isNullOrBlank()) { "登录授权码缺失" }
        return q
    }
    fun finish(store: ModelProviderStore, attempt: Attempt, callback: Map<String,String>, token: CancellationToken): ModelProvider {
        val p=attempt.provider; val isOpenAI=p.preset=="chatgpt"
        val client=if(isOpenAI) p.clientId.ifBlank { callback["client_id"] ?: "" } else GOOGLE_CLIENT
        check(client.isNotBlank() && client!="dynamic_agent_client") { "登录注册未完成：未返回应用 ID" }
        check(store.find(p.id)!=null) { "登录账户已移除，请重新添加" }
        if(isOpenAI && p.clientId.isBlank()) {
            // Preserve the issued registration after a failed/expired code exchange, without activating an unverified account.
            store.put(p.copy(clientId=client,status="已收到应用注册，请完成或重新登录"))
        }
        val form=linkedMapOf("grant_type" to "authorization_code","client_id" to client,"code" to (callback["code"] ?: ""),
            "code_verifier" to attempt.verifier,"redirect_uri" to attempt.redirect)
        if(isOpenAI) form["resource"]=RESOURCE else form["client_secret"]=GOOGLE_PUBLIC_SECRET
        val j=ProviderHttp.json(if(isOpenAI) OPENAI_TOKEN else GOOGLE_TOKEN,"POST",body=ProviderHttp.form(form),contentType="application/x-www-form-urlencoded",token=token)
        check(j.optString("access_token").isNotBlank() && j.optString("refresh_token").isNotBlank()) { "授权未返回完整凭据" }
        check(j.optString("token_type").equals("Bearer",true)) { "授权凭据类型不支持" }
        val next = if(isOpenAI) {
            checkScope(j)
            val jwks=ProviderHttp.json("https://auth.openai.com/.well-known/jwks.json",token=token)
            val identity=verifyIdToken(j.getString("id_token"),jwks,client,attempt.nonce)
            check(p.subject.isBlank() || identity.getString("sub")==p.subject) { "请选择原来的 ChatGPT 账户；添加新账户请使用新增入口" }
            p.copy(clientId=client,subject=identity.getString("sub"),email=identity.optString("email"),status="已登录，正在读取模型")
        } else {
            val identity=ProviderHttp.json("https://www.googleapis.com/oauth2/v2/userinfo",headers=mapOf("Authorization" to "Bearer ${j.getString("access_token")}"),token=token)
            check(identity.optString("id").isNotBlank()) { "Google 未返回账户身份" }
            check(p.subject.isBlank() || identity.optString("id")==p.subject) { "请选择原 Google 账号；新账号请单独添加" }
            val project=discoverProject(j.getString("access_token"),token)
            p.copy(clientId=client,subject=identity.optString("id"),email=identity.optString("email"),project=project,status="已登录，正在读取模型")
        }
        j.put("expires_at",System.currentTimeMillis()+j.optLong("expires_in",3600)*1000)
        token.throwIfCancelled()
        synchronized(lock) { check(store.find(p.id)!=null) { "登录账户已移除" }; store.saveSecret(p.id,j); store.put(next) }
        return next
    }
    fun checkScope(j: JSONObject) {
        check("chatgpt.tokens.use.direct" in j.optString("scope").split(' ')) { "登录成功，但未授权使用 ChatGPT 订阅额度，请重新授权" }
    }
    /** Signature is checked before identity claims. No decode-only authentication. */
    fun verifyIdToken(jwt: String, jwks: JSONObject, client: String, nonce: String?, now: Long = System.currentTimeMillis()/1000): JSONObject {
        val parts=jwt.split('.'); check(parts.size==3) { "ID Token 格式错误" }
        val header=JSONObject(String(Base64Url.decode(parts[0]),Charsets.UTF_8))
        check(header.optString("alg")=="RS256") { "ID Token 签名算法不支持" }
        val keys=jwks.getJSONArray("keys")
        val key=(0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull {
            it.optString("kid")==header.optString("kid") && it.optString("kty")=="RSA" && it.optString("use","sig")=="sig"
        } ?: error("ID Token 签名密钥不存在")
        val publicKey=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,Base64Url.decode(key.getString("n"))),BigInteger(1,Base64Url.decode(key.getString("e")))))
        val signature=Signature.getInstance("SHA256withRSA"); signature.initVerify(publicKey)
        signature.update((parts[0]+"."+parts[1]).toByteArray(Charsets.US_ASCII))
        check(signature.verify(Base64Url.decode(parts[2]))) { "ID Token 签名无效" }
        val j=JSONObject(String(Base64Url.decode(parts[1]),Charsets.UTF_8))
        val aud=j.opt("aud"); val audience=aud==client || (aud is JSONArray && (0 until aud.length()).any { aud.optString(it)==client })
        check(j.optString("iss")=="https://auth.openai.com" && audience && j.optLong("exp")>now && j.optLong("iat")<=now+60 && j.optLong("nbf")<=now+60 && j.optString("sub").isNotBlank()) { "ID Token 身份声明无效" }
        if(aud is JSONArray && aud.length()>1) check(j.optString("azp")==client) { "ID Token 授权方不匹配" }
        if(nonce!=null) check(j.optString("nonce")==nonce) { "ID Token nonce 不匹配" }
        return j
    }
    fun fresh(store: ModelProviderStore, p: ModelProvider, token: CancellationToken): JSONObject = synchronized(lock) {
        val live=store.find(p.id) ?: error("供应商已删除")
        val old=store.secret(p.id)
        if(!live.subscription) return@synchronized old
        check(old.optString("access_token").isNotBlank()) { "需要登录订阅账号" }
        if(old.optLong("expires_at")>System.currentTimeMillis()+60000) return@synchronized old
        check(old.optString("refresh_token").isNotBlank()) { "订阅登录已失效，请重新登录" }
        val openai=live.preset=="chatgpt"
        val form=linkedMapOf("grant_type" to "refresh_token","client_id" to live.clientId,"refresh_token" to old.getString("refresh_token"))
        if(openai) form["resource"]=RESOURCE else form["client_secret"]=GOOGLE_PUBLIC_SECRET
        val j=ProviderHttp.json(if(openai) OPENAI_TOKEN else GOOGLE_TOKEN,"POST",body=ProviderHttp.form(form),contentType="application/x-www-form-urlencoded",token=token)
        check(j.optString("access_token").isNotBlank()) { "刷新登录凭据失败" }
        if(openai) {
            if(!j.has("scope")) j.put("scope",old.optString("scope")); checkScope(j)
            if(j.optString("id_token").isNotBlank()) {
                val identity=verifyIdToken(j.getString("id_token"),ProviderHttp.json("https://auth.openai.com/.well-known/jwks.json",token=token),live.clientId,null)
                check(identity.getString("sub")==live.subject) { "刷新登录身份不匹配" }
            }
            check(j.optString("refresh_token").isNotBlank()) { "服务未返回轮换刷新凭据" }
        } else if(!j.has("refresh_token")) j.put("refresh_token",old.getString("refresh_token"))
        if(!j.has("id_token") && old.has("id_token")) j.put("id_token",old.getString("id_token"))
        j.put("expires_at",System.currentTimeMillis()+j.optLong("expires_in",3600)*1000)
        store.saveSecret(live.id,j); j
    }
    private fun discoverProject(access: String, token: CancellationToken): String {
        val headers=mapOf("Authorization" to "Bearer $access","User-Agent" to ProviderHttp.ANTIGRAVITY_UA)
        fun project(j: JSONObject): String {
            for(k in listOf("cloudaicompanionProject","projectId","project")) {
                val v=j.opt(k); if(v is String && v.isNotBlank()) return v
                if(v is JSONObject && v.optString("id").isNotBlank()) return v.getString("id")
            }; return ""
        }
        val load=ProviderHttp.json("https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist","POST",headers,
            JSONObject().put("metadata",JSONObject().put("ideType","ANTIGRAVITY")).toString(),token=token)
        project(load).takeIf { it.isNotBlank() }?.let { return it }
        val body=JSONObject().put("tier_id","free-tier").put("metadata",JSONObject().put("ide_type","ANTIGRAVITY").put("ide_name","antigravity").put("ide_version","2.5.5"))
        repeat(5) { i ->
            val response=ProviderHttp.json("https://daily-cloudcode-pa.googleapis.com/v1internal:onboardUser","POST",headers,body.toString(),token=token)
            project(response.optJSONObject("response") ?: response).takeIf { it.isNotBlank() }?.let { return it }
            if(i<4) { token.sleep(2000); token.throwIfCancelled() }
        }
        error("Google 账户未返回 Cloud Code Assist 项目；AI Pro 订阅不保证此接口可用")
    }
    /** Return whether remote revocation was confirmed, then forget local tokens regardless. */
    fun signOut(store: ModelProviderStore, p: ModelProvider, token: CancellationToken): Boolean {
        var revoked=false
        synchronized(lock) {
            val s=store.secret(p.id)
            try {
                if(p.preset=="chatgpt" && s.optString("refresh_token").isNotBlank()) {
                    val config=ProviderHttp.json("https://auth.openai.com/.well-known/openid-configuration",token=token)
                    val endpoint=config.getString("revocation_endpoint"); val uri=URI(endpoint)
                    check(uri.scheme=="https" && uri.host=="auth.openai.com")
                    ProviderHttp.json(endpoint,"POST",body=ProviderHttp.form(mapOf("token" to s.getString("refresh_token"),"token_type_hint" to "refresh_token","client_id" to p.clientId)),contentType="application/x-www-form-urlencoded",token=token)
                    revoked=true
                } else if(p.preset=="antigravity" && s.optString("refresh_token").isNotBlank()) {
                    ProviderHttp.json("https://oauth2.googleapis.com/revoke","POST",body=ProviderHttp.form(mapOf("token" to s.getString("refresh_token"))),contentType="application/x-www-form-urlencoded",token=token); revoked=true
                }
            } catch (_: Exception) { }
            store.saveSecret(p.id,JSONObject()); store.put(p.copy(status="已退出登录"))
        }; return revoked
    }
}

/** API 23 compatible base64url; avoids java.util.Base64's Android API 26 floor. */
object Base64Url {
    private const val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    fun encode(bytes: ByteArray): String {
        val out=StringBuilder(); var buffer=0; var bits=0
        for(b in bytes) { buffer=(buffer shl 8) or (b.toInt() and 255); bits+=8; while(bits>=6) { bits-=6; out.append(alphabet[(buffer ushr bits) and 63]) } }
        if(bits>0) out.append(alphabet[(buffer shl (6-bits)) and 63]); return out.toString()
    }
    fun decode(s: String): ByteArray {
        val out=java.io.ByteArrayOutputStream(); var buffer=0; var bits=0
        for(c in s.trimEnd('=')) { val n=alphabet.indexOf(c); check(n>=0) { "无效 Base64URL" }; buffer=(buffer shl 6) or n; bits+=6; if(bits>=8) { bits-=8; out.write((buffer ushr bits) and 255) } }
        return out.toByteArray()
    }
}
