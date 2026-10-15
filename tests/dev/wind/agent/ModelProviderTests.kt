package dev.wind.agent

import android.content.SharedPreferences
import dev.wind.agent.modelprovider.*
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import com.sun.net.httpserver.HttpServer

/** Host fixtures use synthetic credentials and local HTTP only; never contact a model service. */
object ModelProviderTests {
    private var count=0
    private fun verify(ok:Boolean) { check(ok) { "assertion ${count+1} failed" }; count++ }
    private fun fails(action:()->Unit) { verify(runCatching(action).isFailure) }
    private class MemoryPrefs {
        val values=linkedMapOf<String,Any?>()
        val prefs=Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)) { _,method,args ->
            val a=args ?: emptyArray()
            when(method.name) {
                "edit" -> editor()
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(a[0])
                "getString","getBoolean","getInt","getLong","getFloat","getStringSet" -> values[a[0]] ?: a[1]
                "registerOnSharedPreferenceChangeListener","unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "MemoryPreferences"
                else -> error(method.name)
            }
        } as SharedPreferences
        private fun editor():SharedPreferences.Editor {
            val pending=linkedMapOf<String,Any?>(); var clear=false
            lateinit var edit:SharedPreferences.Editor
            edit=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { _,method,args ->
                val a=args ?: emptyArray()
                when {
                    method.name.startsWith("put") -> { pending[a[0] as String]=a[1]; edit }
                    method.name=="remove" -> { pending[a[0] as String]=null; edit }
                    method.name=="clear" -> { clear=true; edit }
                    method.name=="commit" || method.name=="apply" -> {
                        if(clear) values.clear(); pending.forEach { (k,v)-> if(v==null) values.remove(k) else values[k]=v }
                        if(method.name=="commit") true else null
                    }
                    else -> error(method.name)
                }
            } as SharedPreferences.Editor
            return edit
        }
    }
    private fun account(preset:String,model:String,effort:String="default",url:String="https://example.invalid/v1",protocol:String="openai") =
        ModelProvider(preset=preset,label=preset,url=url,protocol=protocol,model=model,effort=effort,
            models=listOf(DiscoveredModel(model,efforts=ReasoningCapabilities.efforts(preset,model),protocol=protocol)))
    @JvmStatic fun main(args:Array<String>) {
        verify(ProviderPresets.all.map { it.id }.toSet().size==10)
        verify(ProviderPresets.get("openai")!!.protocol=="responses")
        verify(ProviderPresets.get("anthropic")!!.url=="https://api.anthropic.com/v1")
        verify(ProviderPresets.get("gemini")!!.protocol=="gemini")
        val a=account("openai","gpt-5.4","low")
        val b=account("anthropic","claude-sonnet-4-6","high",protocol="anthropic")
        verify(ComposerModelChoices.filter(a,"OPENAI").single().id==a.model)
        verify(ComposerModelChoices.filter(a,"5.4").single().id==a.model)
        verify(ComposerModelChoices.filter(a,"missing").isEmpty())
        verify(ComposerModelChoices.effort(b,b.selected())=="high")
        verify(ComposerModelChoices.effort(b,DiscoveredModel("plain",efforts=listOf("default")))=="default")
        verify(ComposerModelChoices.effort(b,DiscoveredModel("fixed",efforts=listOf("low")))=="low")
        verify(ModelProvider.parse(a.json().toString().let(::JSONObject))==a)
        verify(a.json().toString().contains("low")); verify(!a.json().has("api_key"))
        val storage=MemoryPrefs(); val store=ModelProviderStore(storage.prefs)
        store.put(a); store.put(b); store.saveSecret(a.id,JSONObject().put("api_key","synthetic-A")); store.saveSecret(b.id,JSONObject().put("api_key","synthetic-B"))
        verify(store.all().size==2); verify(store.secret(a.id).getString("api_key")=="synthetic-A")
        verify(store.secret(b.id).getString("api_key")=="synthetic-B")
        verify(!storage.values.toString().contains("synthetic-")); verify(!store.protected(a.id))
        store.select(a.id,a.model,"low"); verify(store.active()?.id==a.id); verify(store.active()?.effort=="low")
        store.select(b.id,b.model,"high"); verify(store.active()?.id==b.id); verify(store.find(a.id)?.effort=="low")
        fails { store.select(b.id,b.model,"minimal") }; verify(store.active()?.effort=="high")
        val host=store.hostId(); verify(host==ModelProviderStore(storage.prefs).hostId())
        store.delete(a.id); verify(store.secret(a.id).length()==0); verify(store.active()?.id==b.id); verify(store.secret(b.id).getString("api_key")=="synthetic-B")
        store.delete(b.id); verify(store.active()==null)
        val old=MemoryPrefs(); old.values["model"]="deepseek-flash"; old.values["base_url"]="https://api.deepseek.com/v1"
        SecureStore.encrypt("synthetic-legacy",SecureStore.SLOT_MODEL)
        val migrated=ModelProviderStore(MemoryPrefs().prefs); migrated.migrateLegacy(old.prefs)
        verify(migrated.all().size==1); verify(migrated.active()?.model=="deepseek-flash")
        migrated.migrateLegacy(old.prefs); verify(migrated.all().size==1)
        SecureStore.clearSessionKeys()
        reasoning(); catalogs(); oauth(); wireHttp()
        println("ModelProviderTests PASS ($count assertions; synthetic credentials/local HTTP only)")
    }
    private fun reasoning() {
        val base=JSONObject().put("model","gpt-5.4").put("messages",JSONArray()).put("stream",true).put("reasoning_effort","xhigh")
        verify(ReasoningWire.apply(account("openai","gpt-5.4","low"),JSONObject(base.toString())).getString("reasoning_effort")=="low")
        verify(!ReasoningWire.apply(account("openai","gpt-5.4"),JSONObject(base.toString())).has("reasoning_effort"))
        verify(ReasoningCapabilities.efforts("unknown","new-model")==listOf("default"))
        verify("none" !in ReasoningCapabilities.efforts("glm","glm-5.3"))
        val ds=ReasoningWire.apply(account("deepseek","deepseek-flash","max"),JSONObject())
        verify(ds.getString("reasoning_effort")=="max"); verify(ds.getJSONObject("thinking").getString("type")=="enabled")
        val off=ReasoningWire.apply(account("deepseek","deepseek-flash","none"),JSONObject())
        verify(off.getJSONObject("thinking").getString("type")=="disabled"); verify(!off.has("reasoning_effort"))
        val glm=ReasoningWire.apply(account("glm","glm-5.3","low"),JSONObject())
        verify(glm.getString("reasoning_effort")=="low")
        fails { ReasoningWire.apply(account("glm","glm-5.3","none"),JSONObject()) }
        val claude=ReasoningWire.apply(account("anthropic","claude-opus-5-5","xhigh",protocol="anthropic"),JSONObject().put("max_tokens",4096))
        verify(claude.getJSONObject("thinking").getString("type")=="adaptive")
        verify(claude.getJSONObject("output_config").getString("effort")=="xhigh")
        val legacy=ReasoningWire.apply(account("anthropic","claude-sonnet-4-5","low",protocol="anthropic"),JSONObject().put("max_tokens",1000))
        verify(legacy.getJSONObject("thinking").getInt("budget_tokens")==2048); verify(legacy.getInt("max_tokens")>2048)
        val g=ReasoningWire.apply(account("gemini","gemini-3.8-flash","low",protocol="gemini"),JSONObject())
        verify(g.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel")=="low")
        val g25=ReasoningWire.apply(account("gemini","gemini-2.5-flash","none",protocol="gemini"),JSONObject())
        verify(g25.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget")==0)
        val msgs=JSONArray().put(JSONObject().put("role","user").put("content",JSONArray().put(JSONObject().put("type","text").put("text","describe"))
            .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/png;base64,AA==")))))
        val responses=ReasoningWire.responses(account("chatgpt","gpt-5.4","high",protocol="responses"),msgs,4000)
        verify(!responses.getBoolean("store") && responses.getBoolean("stream"))
        verify(responses.getJSONObject("reasoning").getString("effort")=="high")
        verify(responses.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type")=="input_image")
        val output=StringBuilder(); val stream=SubscriptionStream("responses",{output.append(it)},{})
        stream.consume(JSONObject().put("type","response.output_text.delta").put("delta","partial"))
        verify(!stream.completed); stream.consume(JSONObject().put("type","response.failed").put("response",JSONObject().put("error",JSONObject().put("code","subscription_sharing_usage_limit_exceeded"))))
        verify(!stream.completed && stream.failed!!.contains("额度")); verify(output.toString()=="partial")
        val complete=SubscriptionStream("responses",{},{}); complete.consume(JSONObject().put("type","response.completed"))
        verify(complete.completed)
        val cut=SubscriptionStream("responses",{},{}); cut.consume(JSONObject().put("type","response.incomplete"))
        verify(cut.completed && cut.truncated)
        val google=SubscriptionStream("antigravity",{},{}); google.consume(JSONObject("""{"response":{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}}"""))
        verify(google.completed && google.text.toString()=="ok")
    }
    private fun catalogs() {
        val api=account("openai","gpt-5.4",protocol="responses")
        val parsed=ModelDiscovery.parse(api,JSONObject("""{"data":[{"id":"text-embedding-3-small"},{"id":"gpt-5.4"}]}"""))
        verify(parsed.map { it.id }==listOf("gpt-5.4"))
        val subscription=api.copy(preset="chatgpt")
        val catalog=ModelDiscovery.parse(subscription,JSONObject("""{"models":[{"slug":"gpt-5.4","display_name":"Account Model","visibility":"list","supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}]},{"slug":"internal","visibility":"hide"}]}"""))
        verify(catalog.size==1 && catalog[0].name=="Account Model"); verify(catalog[0].efforts==listOf("default","low","high"))
        val gemini=ModelDiscovery.parse(api.copy(preset="gemini",protocol="gemini"),JSONObject("""{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""))
        verify(gemini.map { it.id }==listOf("gemini-3.8-flash"))
        val zen=ModelDiscovery.parse(api.copy(preset="opencode",protocol="openai"),JSONObject("""{"data":[{"id":"claude-opus-5-5"},{"id":"gemini-3.8-flash"},{"id":"gpt-5.4"}]}"""))
        verify(zen.map { it.protocol }==listOf("anthropic","gemini","responses"))
        val cca=api.copy(preset="antigravity",protocol="antigravity")
        val models=ModelDiscovery.parse(cca,JSONObject("""{"models":{"gemini-test-low":{},"gemini-test-medium":{},"gemini-test-high":{},"new-live-id":{}}}"""))
        val grouped=ModelDiscovery.groupAntigravity(cca,models)
        verify(grouped.size==2); verify(grouped.first().wireIds["low"]=="gemini-test-low"); verify(grouped.last().id=="new-live-id")
        verify(ModelDiscovery.groupAntigravity(cca,listOf(DiscoveredModel("gemini-only-high"))).first().efforts==listOf("default"))
        val glm=ModelDiscovery.glmDocumentedModels("<urlset><loc>https://docs.bigmodel.cn/cn/guide/models/text/glm-5.3</loc><loc>https://docs.bigmodel.cn/cn/guide/models/vlm/glm-ocr</loc><loc>https://evil.example/cn/guide/models/text/glm-fake</loc></urlset>")
        verify(glm.map { it.id }==listOf("glm-5.3")); verify("none" !in glm.single().efforts)
        verify("low" in ReasoningCapabilities.efforts("custom","gpt-5.4","openai"))
        verify("medium" in ReasoningCapabilities.efforts("custom","claude-sonnet-4-6","anthropic"))
        val pro=ModelDiscovery.groupAntigravity(cca,listOf(DiscoveredModel("gemini-3.1-pro-low"),DiscoveredModel("gemini-pro-agent"))).single()
        verify(pro.id=="gemini-3.1-pro" && pro.efforts==listOf("default","low","high"))
        verify(pro.wireIds["default"]=="gemini-pro-agent")
        val claudeCCA=ModelDiscovery.groupAntigravity(cca,listOf(DiscoveredModel("claude-sonnet-4-6"))).single()
        verify(claudeCCA.efforts==listOf("default","low","medium","high"))
        val ccaLow=cca.copy(model=grouped.first().id,models=listOf(grouped.first()),effort="low")
        verify(!ReasoningWire.apply(ccaLow,JSONObject()).getJSONObject("generationConfig").getJSONObject("thinkingConfig").has("thinkingLevel"))
        val ccaPro=cca.copy(model=pro.id,models=listOf(pro),effort="high")
        verify(ReasoningWire.apply(ccaPro,JSONObject()).getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel")=="high")
        verify("minimal" !in ReasoningCapabilities.efforts("gemini","gemini-3.8-flash"))
    }
    private fun oauth() {
        verify(SubscriptionOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")=="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        verify(Base64Url.decode(Base64Url.encode(ByteArray(255){it.toByte()})).contentEquals(ByteArray(255){it.toByte()}))
        val redirect="http://127.0.0.1:1455/auth/callback"
        verify(SubscriptionOAuth.validateCallback("$redirect?code=c&state=s&client_id=oaiapp_test",redirect,"s","")["client_id"]=="oaiapp_test")
        fails { SubscriptionOAuth.validateCallback("$redirect?code=c&state=bad",redirect,"s","") }
        fails { SubscriptionOAuth.validateCallback("http://localhost:1455/auth/callback?code=c&state=s",redirect,"s","") }
        fails { SubscriptionOAuth.validateCallback("http://127.0.0.1:1455/callback?code=c&state=s",redirect,"s","") }
        fails { SubscriptionOAuth.validateCallback("$redirect?code=c&state=s&state=s",redirect,"s","") }
        fails { SubscriptionOAuth.validateCallback("$redirect?error=access_denied&state=s",redirect,"s","") }
        fails { SubscriptionOAuth.validateCallback("$redirect?code=c&state=s&client_id=other",redirect,"s","original") }
        fails { SubscriptionOAuth.checkScope(JSONObject().put("scope","openid profile")) }
        SubscriptionOAuth.checkScope(JSONObject().put("scope","openid chatgpt.tokens.use.direct")); verify(true)
        val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(); val key=pair.public as RSAPublicKey
        val jwks=JSONObject().put("keys",JSONArray().put(JSONObject().put("kid","fixture").put("kty","RSA").put("n",Base64Url.encode(key.modulus.toByteArray())).put("e",Base64Url.encode(key.publicExponent.toByteArray()))))
        val claims=JSONObject().put("iss","https://auth.openai.com").put("sub","subject").put("aud","client").put("exp",2000).put("iat",900).put("nonce","nonce")
        fun jwt(j:JSONObject,alg:String="RS256"):String {
            val h=Base64Url.encode(JSONObject().put("alg",alg).put("kid","fixture").toString().toByteArray())+"."+Base64Url.encode(j.toString().toByteArray())
            val signature=Signature.getInstance("SHA256withRSA"); signature.initSign(pair.private); signature.update(h.toByteArray()); return h+"."+Base64Url.encode(signature.sign())
        }
        verify(SubscriptionOAuth.verifyIdToken(jwt(claims),jwks,"client","nonce",1000).getString("sub")=="subject")
        fails { SubscriptionOAuth.verifyIdToken(jwt(claims),jwks,"other","nonce",1000) }
        fails { SubscriptionOAuth.verifyIdToken(jwt(claims),jwks,"client","bad-nonce",1000) }
        fails { SubscriptionOAuth.verifyIdToken(jwt(claims),jwks,"client","nonce",2001) }
        fails { SubscriptionOAuth.verifyIdToken(jwt(claims,"none"),jwks,"client","nonce",1000) }
        val signed=jwt(claims); fails { SubscriptionOAuth.verifyIdToken(signed.dropLast(5)+"AAAAA",jwks,"client","nonce",1000) }
        SubscriptionOAuth.Attempt(account("chatgpt","gpt-5.4",protocol="responses"),"urn:uuid:fixture").use { attempt ->
            val uri=java.net.URI(attempt.authorizeUrl())
            verify(uri.host=="auth.openai.com" && uri.path=="/api/accounts/authorize")
            verify(uri.rawQuery.contains("dynamic_agent_client") && uri.rawQuery.contains("chatgpt.tokens.use.direct") && uri.rawQuery.contains("agent_name_hint=Harness+Mate"))
            verify(attempt.redirect.startsWith("http://127.0.0.1:") && attempt.redirect.endsWith("/auth/callback"))
        }
    }
    private fun wireHttp() {
        NetworkPolicy.allowLanModelEndpoint=true
        val server=HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        val requests=mutableListOf<Pair<String,JSONObject>>()
        server.createContext("/") { exchange ->
            val path=exchange.requestURI.path; val body=exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val j=if(body.isBlank()) JSONObject() else JSONObject(body)
            synchronized(requests) { requests.add(path to j) }
            verify(exchange.requestHeaders.getFirst("Authorization")=="Bearer synthetic-wire" || exchange.requestHeaders.getFirst("x-api-key")=="synthetic-wire")
            val response=when {
                path.endsWith("/models") -> """{"data":[{"id":"fixture-chat"}]}"""
                path.endsWith("/responses") && j.optString("model")=="fixture-quota" -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
                path.endsWith("/responses") && j.optString("model")=="fixture-eof" -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                path.endsWith("/responses") -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\ndata: {\"type\":\"response.completed\"}\n\n"
                path.endsWith("/messages") -> "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                path.contains("streamGenerateContent") -> "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}\n\n"
                else -> "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
            exchange.responseHeaders.add("Content-Type",if(path.endsWith("/models")) "application/json" else "text/event-stream")
            val bytes=response.toByteArray(); exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        server.start()
        try {
            val url="http://127.0.0.1:${server.address.port}/v1"; val store=ModelProviderStore(MemoryPrefs().prefs)
            val messages=JSONArray().put(JSONObject().put("role","user").put("content","hello"))
            for(proto in listOf("openai","anthropic","responses","antigravity")) {
                val p=account(if(proto=="antigravity") "antigravity" else "custom","fixture-chat",url=url,protocol=proto).copy(project=if(proto=="antigravity") "fixture-project" else "")
                store.put(p); store.saveSecret(p.id,JSONObject().put(if(p.subscription) "access_token" else "api_key","synthetic-wire").put("expires_at",System.currentTimeMillis()+3600000))
                val client=LlmClient(p,store)
                // A new selection does not change the already-created client.
                store.put(p.copy(model="other-model",effort="default"))
                var final=""; var failure:String?=null
                client.streamChat(messages,CancellationToken(),object:LlmClient.StreamCallback {
                    override fun onToken(text:String) {}
                    override fun onThinking(text:String) {}
                    override fun onDone(text:String?) { final=text ?: "" }
                    override fun onError(message:String) { failure=message }
                })
                check(failure==null) { failure ?: "" }; verify(final=="ok")
                val request=requests.last().second; verify(request.getString("model")=="fixture-chat")
                if(proto=="responses") verify(request.getBoolean("stream") && !request.getBoolean("store"))
                if(proto=="antigravity") { verify(request.getString("project")=="fixture-project"); verify(request.getJSONObject("request").has("sessionId")) }
            }
            val p=account("custom","fixture-chat",url=url); store.put(p); store.saveSecret(p.id,JSONObject().put("api_key","synthetic-wire"))
            val refreshed=ModelDiscovery.refresh(store,p.id); verify(refreshed.models.single().id=="fixture-chat")
            verify(requests.size==5)
            for(model in listOf("fixture-quota","fixture-eof")) {
                val failed=account("custom",model,url=url,protocol="responses"); store.put(failed); store.saveSecret(failed.id,JSONObject().put("api_key","synthetic-wire"))
                val before=requests.size; var error=""; var done=false; val partial=StringBuilder()
                LlmClient(failed,store).streamChat(messages,CancellationToken(),object:LlmClient.StreamCallback {
                    override fun onToken(text:String) { partial.append(text) }
                    override fun onThinking(text:String) {}
                    override fun onDone(text:String?) { done=true }
                    override fun onError(message:String) { error=message }
                })
                verify(requests.size==before+1); verify(error.isNotBlank() && !done); verify(partial.toString()=="partial")
            }
        } finally { server.stop(0); NetworkPolicy.allowLanModelEndpoint=false; SecureStore.clearSessionKeys() }
    }
}
