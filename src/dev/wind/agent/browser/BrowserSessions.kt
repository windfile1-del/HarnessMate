package dev.wind.agent.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.wind.agent.CancellationToken
import dev.wind.agent.Message
import dev.wind.agent.Tools
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** WebView instances belong to browser sessions, never to a global selected chat. */
class BrowserSessions private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val journal = BrowserJournal(app.noBackupFilesDir)
    private val sessions = LinkedHashMap<String, Session>()
    private val adapter by lazy { app.assets.open("browser/adapter.js").bufferedReader().use { it.readText() } }
    class Tab internal constructor(val id: String, val wrapper: MutableContextWrapper, val view: WebView) {
        val key = "__hmBrowser_" + UUID.randomUUID().toString().replace("-", "")
        var generation = 0L
        var snapshot: String? = null
        var loading = false
        var failure: String? = null
        var destroyed = false
    }
    class Session internal constructor(val id: String, val chatId: String, val profile: String, val isolated: Boolean) {
        val tabs = LinkedHashMap<String, Tab>()
        val operation = ReentrantLock()
        var activeTab = ""
        @Volatile var owner: String? = null
        @Volatile var epoch = 0L
        @Volatile var takeover = false
        @Volatile var humanBrowsing = false
        @Volatile var ended = false
        @Volatile var authorize: (String) -> Boolean = { false }
        val blockedDomains = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        var activity: BrowserActivity? = null
        var container: ViewGroup? = null
        var pending: Approval? = null
        var approved: Approval? = null
        var chooser: ValueCallback<Array<Uri>>? = null
        var upload: Array<Uri>? = null
        var stagedUpload: Array<Uri>? = null
        var uploadTab: String? = null
        var uploadConsumed = false
        val retainedUploads = mutableListOf<dev.wind.agent.webprovider.WebUploadStager>()
        var lastLocation = "about:blank"
        var download: JSONObject? = null
        lateinit var receipts: BrowserReceipts
    }
    data class Approval(val id: String, val signature: String, val proof: JSONObject, val action: String, val expires: Long)
    private fun id(prefix: String, length: Int = 32) = prefix + UUID.randomUUID().toString().replace("-", "").take(length)
    private fun isolated(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private fun save(s: Session) {
        val data = s.receipts.json().put("session_id", s.id).put("chat_id", s.chatId).put("profile", s.profile).put("isolated", s.isolated)
            .put("last_location", s.lastLocation)
        journal.write(s.id, data)
    }
    private fun construct(chat: String, sessionId: String?, human: Boolean): Session {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(chat.isNotBlank() && chat.length <= 160) { "BROWSER_CHAT_BINDING_REQUIRED" }
        if (sessionId != null) sessions[sessionId]?.let { check(it.chatId == chat) { "BROWSER_CHAT_MISMATCH" }; return it }
        check(sessions.size < 6) { "BROWSER_SESSION_LIMIT" }
        val stored = sessionId?.let(journal::read)
        if (sessionId != null) check(stored != null && stored.getString("chat_id") == chat) { "BROWSER_SESSION_NOT_FOUND" }
        val support = isolated()
        if (!support) check(sessions.values.none { !it.isolated }) { "BROWSER_COMPATIBILITY_SESSION_BUSY" }
        val session = Session(sessionId ?: id("br_"), chat, stored?.getString("profile") ?: "hm_browser_" + BrowserProtocol.digest(chat).take(12) + "_" + id("",12), support)
        check(stored == null || !stored.optBoolean("isolated") || support) { "BROWSER_PROFILE_ISOLATION_UNAVAILABLE" }
        session.receipts = BrowserReceipts({ save(session) }, stored)
        session.takeover = human
        sessions[session.id] = session
        try { addTab(session); save(session); return session }
        catch (e: Throwable) { sessions.remove(session.id); session.tabs.values.toList().forEach(::destroyTab); throw e }
    }
    fun open(chat: String, owner: String, args: JSONObject, token: CancellationToken, authorizer: (String) -> Boolean): JSONObject = onMain(token) {
        val existing = args.optString("session_id").takeIf { it.isNotEmpty() }
            ?: sessions.values.firstOrNull { it.chatId == chat && (it.owner == null || it.owner == owner) && !it.takeover }?.id
        if(existing!=null)dev.wind.agent.mate.MateTaskCoordinator.assertResourceOwner(app,chat,owner,"browser",existing)
        val s = construct(chat, existing, false)
        check(!s.takeover) { "BROWSER_HUMAN_TAKEOVER" }
        check(s.owner == null || s.owner == owner) { "BROWSER_SESSION_BUSY" }
        s.owner = owner; s.ended = false; s.authorize = authorizer; s.epoch++
        s.tabs.values.forEach { it.snapshot = null; it.view.onResume() }
        if (args.has("url")) navigateMain(s, tab(s, args), args.getString("url"))
        status(s)
    }
    fun createHuman(chat: String): Session = construct(chat, null, true)
    fun findHuman(chat: String, sessionId: String?): Session? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if(sessionId==null)return sessions.values.filter{it.chatId==chat}.lastOrNull{it.pending!=null || it.receipts.unresolved!=null} ?: sessions.values.lastOrNull{it.chatId==chat}
        return (sessions[sessionId] ?: construct(chat, sessionId, true)).also { check(it.chatId == chat) }
    }
    fun list(chat: String, token: CancellationToken): JSONObject = onMain(token) {
        val live = sessions.values.filter { it.chatId == chat }.map(::status)
        val retained = journal.ids().filterNot(sessions::containsKey).mapNotNull { name ->
            val data = runCatching { journal.read(name) }.getOrNull() ?: return@mapNotNull null
            if (data.optString("chat_id") != chat) return@mapNotNull null
            JSONObject().put("session_id", name).put("state", "RESTORE_REQUIRED").put("last_location", data.optString("last_location"))
                .put("note", "Reopen explicitly; old tabs, observations and approvals are not restored.")
        }
        JSONObject().put("sessions", JSONArray(live + retained)).put("backend", "ANDROID_WEBVIEW")
    }
    private fun session(chat: String, owner: String, a: JSONObject, input: Boolean): Session {
        val s = sessions[a.getString("session_id")] ?: error("BROWSER_SESSION_NOT_OPEN")
        BrowserProtocol.checkOwner(s.chatId,s.owner,chat,owner,s.ended,s.takeover)
        return s
    }
    private fun tab(s: Session, a: JSONObject): Tab = s.tabs[a.optString("tab_id", s.activeTab)]?.takeIf { !it.destroyed } ?: error("BROWSER_TAB_NOT_FOUND")
    fun run(chat: String, owner: String, name: String, a: JSONObject, token: CancellationToken, observer: Tools.Observer?): JSONObject {
        if (name == "browser_session" && a.optString("mode", "list") == "list") return list(chat, token)
        if(name=="browser_session" && a.optString("mode")=="status" || name=="browser_tabs" && a.optString("mode","list")=="list")return onMain(token){
            val s=sessions[a.getString("session_id")] ?: error("BROWSER_SESSION_NOT_OPEN");check(s.chatId==chat){"BROWSER_CHAT_MISMATCH"};status(s)
        }
        val s = onMain(token) { session(chat, owner, a, BrowserProtocol.isMutating(name,a)) }
        check(!Looper.getMainLooper().isCurrentThreadCompat()) { "BROWSER_WORKER_REQUIRED" }
        while (!s.operation.tryLock(100, TimeUnit.MILLISECONDS)) token.throwIfCancelled()
        try {
            token.throwIfCancelled()
            val t = onMain(token) { session(chat, owner, a, BrowserProtocol.isMutating(name,a)); tab(s,a) }
            return when (name) {
                "browser_session" -> onMain(token) { when (a.optString("mode","status")) {
                    "close" -> { sessions.remove(s.id); s.epoch++; s.owner=null; s.ended=true; s.tabs.values.toList().forEach(::destroyTab); s.tabs.clear(); save(s); JSONObject().put("status","CLOSED").put("session_id",s.id) }
                    "authorize" -> { s.blockedDomains.clear(); status(s) }
                    "resume" -> { check(!s.takeover) { "BROWSER_USER_RESUME_REQUIRED" }; status(s) }
                    else -> status(s)
                } }
                "browser_navigate" -> onMain(token) { navigateMain(s,t,a.getString("url")); status(s).put("status","NAVIGATION_STARTED") }
                "browser_tabs" -> onMain(token) { when(a.optString("mode","list")) {
                    "new" -> { val fresh=addTab(s); s.activeTab=fresh.id; if(a.has("url")) navigateMain(s,fresh,a.getString("url")); mount(s); status(s) }
                    "switch" -> { s.activeTab=t.id; s.epoch++; s.tabs.values.forEach { it.snapshot=null }; mount(s); status(s) }
                    "close" -> { check(s.tabs.size>1) { "BROWSER_LAST_TAB_USE_SESSION_CLOSE" }; destroyTab(t); s.tabs.remove(t.id); if(s.activeTab==t.id)s.activeTab=s.tabs.keys.first(); s.epoch++; mount(s); status(s) }
                    else -> status(s)
                } }
                "browser_takeover" -> onMain(token) { takeOver(s); BrowserActivity.open(app,s.chatId,s.id); status(s).put("status","HUMAN_TAKEOVER") }
                "browser_observe" -> observe(s,t,a,token,observer)
                "browser_wait" -> wait(s,t,a,token)
                "browser_action", "browser_upload" -> action(s,t,a,token,observer)
                "browser_video" -> evaluate(s,t,"video",JSONObject(),token).put("session_id",s.id).put("tab_id",t.id)
                else -> error("BROWSER_TOOL_UNKNOWN")
            }
        } finally { s.operation.unlock() }
    }
    private fun network(s: Session, raw: String): Boolean {
        if (raw == "about:blank") return true
        return try {
            val url=BrowserProtocol.publicUrl(raw)
            val allowed = s.takeover && s.humanBrowsing && s.activity!=null || !s.ended && s.owner != null && s.authorize(url)
            if(!allowed)synchronized(s.blockedDomains){if(s.blockedDomains.size<32)s.blockedDomains.add(BrowserProtocol.host(url))}
            allowed
        } catch (_:Exception) { false }
    }
    private fun navigateMain(s: Session,t: Tab,url: String) {
        val safe=BrowserProtocol.publicUrl(url); check(network(s,safe)) { "BROWSER_NETWORK_AUTHORIZATION_REQUIRED" }
        t.failure=null; t.snapshot=null; t.generation++; t.view.loadUrl(safe)
    }
    fun humanNavigate(s: Session,url: String) { takeOver(s);s.humanBrowsing=true; navigateMain(s,tab(s,JSONObject()),url) }
    private fun addTab(s: Session): Tab {
        check(s.tabs.size<4) { "BROWSER_TAB_LIMIT" }
        val wrapper=MutableContextWrapper(app); val view=WebView(wrapper)
        val t=Tab(id("tab_",16),wrapper,view)
        try {
            if(s.isolated)WebViewCompat.setProfile(view,s.profile)
            @Suppress("SetJavaScriptEnabled")
            view.settings.apply {
                javaScriptEnabled=true; domStorageEnabled=true; allowFileAccess=false; allowContentAccess=false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs=false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs=false
                mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW; javaScriptCanOpenWindowsAutomatically=false; setSupportMultipleWindows(true); setGeolocationEnabled(false)
                if(Build.VERSION.SDK_INT>=26)safeBrowsingEnabled=true
            }
            view.webViewClient=object: WebViewClient() {
                override fun onPageStarted(v:WebView?,url:String?,favicon:Bitmap?) { t.generation++; t.snapshot=null; t.loading=true; if(url!=null && !network(s,url)){v?.stopLoading();t.failure="BROWSER_NETWORK_AUTHORIZATION_REQUIRED"} }
                override fun onPageFinished(v:WebView?,url:String?) { t.loading=false; s.lastLocation=BrowserProtocol.storedLocation(url); if(!t.destroyed) runCatching { save(s) } }
                override fun shouldOverrideUrlLoading(v:WebView?,request:WebResourceRequest?):Boolean = request?.url?.toString()?.let { !network(s,it) } ?: true
                @Suppress("DEPRECATION") override fun shouldOverrideUrlLoading(v:WebView?,url:String?):Boolean = url?.let { !network(s,it) } ?: true
                override fun shouldInterceptRequest(v:WebView?,request:WebResourceRequest?):WebResourceResponse? {
                    val url=request?.url?.toString() ?: return blocked()
                    // No file/content bridge; data/blob frames are not auto-authorized through this hook.
                    return if(network(s,url))null else blocked()
                }
                override fun onReceivedSslError(v:WebView?,handler:SslErrorHandler?,error:SslError?) {handler?.cancel();t.failure="BROWSER_TLS_ERROR";t.loading=false}
                override fun onReceivedError(v:WebView?,request:WebResourceRequest?,error:WebResourceError?) {if(request?.isForMainFrame==true){t.failure="BROWSER_PAGE_LOAD_FAILED";t.loading=false}}
                override fun onReceivedHttpError(v:WebView?,request:WebResourceRequest?,response:WebResourceResponse?) {if(request?.isForMainFrame==true){t.failure=if(response?.statusCode==429)"BROWSER_RATE_LIMITED" else "BROWSER_HTTP_ERROR"}}
                override fun onRenderProcessGone(v:WebView?,detail:RenderProcessGoneDetail?):Boolean {t.failure="BROWSER_RENDERER_GONE";t.loading=false;t.snapshot=null;destroyTab(t);return true}
            }
            view.webChromeClient=object:WebChromeClient() {
                override fun onPermissionRequest(request:PermissionRequest?) { request?.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin:String?,callback:GeolocationPermissions.Callback?) {callback?.invoke(origin,false,false)}
                override fun onCreateWindow(parent:WebView?,dialog:Boolean,userGesture:Boolean,message:android.os.Message?):Boolean {
                    if(!userGesture || message==null || s.tabs.size>=4 || t.destroyed || (!s.takeover && s.owner==null))return false
                    val child=addTab(s); s.activeTab=child.id; mount(s)
                    (message.obj as? WebView.WebViewTransport)?.webView=child.view; message.sendToTarget(); return true
                }
                override fun onShowFileChooser(v:WebView?,callback:ValueCallback<Array<Uri>>?,params:FileChooserParams?):Boolean {
                    if(t.destroyed)return false
                    if(s.upload!=null && s.uploadTab==t.id && !s.takeover && !s.ended){val uris=s.upload;s.upload=null;s.uploadTab=null;s.uploadConsumed=true;callback?.onReceiveValue(uris);return true}
                    s.chooser?.onReceiveValue(null);s.chooser=callback; takeOver(s); BrowserActivity.open(app,s.chatId,s.id)
                    main.post {s.activity?.chooseFiles(params)};return true
                }
            }
            view.setOnTouchListener{_,event->if(event.action==MotionEvent.ACTION_DOWN && s.takeover && s.activity!=null)s.humanBrowsing=true;false}
            view.setDownloadListener {url,_,_,mime,length -> s.download=JSONObject().put("url",BrowserProtocol.displayUrl(url)).put("mime",mime).put("size",length).put("status","DOWNLOAD_REQUESTED").put("note","Public downloads use download_file; authenticated browser downloads are not implemented.") }
            val metrics=app.resources.displayMetrics
            view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(metrics.heightPixels,View.MeasureSpec.EXACTLY));view.layout(0,0,metrics.widthPixels,metrics.heightPixels)
            view.onResume();s.tabs[t.id]=t;if(s.activeTab.isEmpty())s.activeTab=t.id;view.loadUrl("about:blank")
            return t
        } catch(e:Throwable){view.destroy();throw e}
    }
    private fun blocked()=WebResourceResponse("text/plain","UTF-8",403,"Blocked",emptyMap(),ByteArrayInputStream(ByteArray(0)))
    private fun destroyTab(t:Tab){if(t.destroyed)return;t.destroyed=true;t.generation++;t.snapshot=null;(t.view.parent as? ViewGroup)?.removeView(t.view);t.view.stopLoading();t.view.destroy();t.wrapper.baseContext=app}
    private fun live(s:Session,t:Tab,epoch:Long,generation:Long,token:CancellationToken){token.throwIfCancelled();check(!s.ended && s.owner!=null && !s.takeover && s.epoch==epoch && !t.destroyed && t.generation==generation){"BROWSER_SESSION_CHANGED"};check(t.view.url=="about:blank" || t.view.url?.let{network(s,it)}==true){"BROWSER_NETWORK_AUTHORIZATION_REQUIRED"}}
    private fun evaluate(s:Session,t:Tab,command:String,a:JSONObject,token:CancellationToken):JSONObject {
        val latch=CountDownLatch(1);val value=AtomicReference<JSONObject>();val failure=AtomicReference<Throwable>();val expired=AtomicBoolean()
        val epoch=s.epoch;val generation=onMain(token){t.generation}
        main.post {try {
            if(expired.get())return@post
            live(s,t,epoch,generation,token)
            val code=adapter.replace("__BROWSER_KEY__",JSONObject.quote(t.key))+"\nJSON.stringify(window["+JSONObject.quote(t.key)+"].command("+JSONObject.quote(command)+","+a.toString()+"));"
            t.view.evaluateJavascript(code){raw -> try {
                if(!expired.get()){live(s,t,epoch,generation,token);val decoded=JSONTokener(raw ?: "null").nextValue();check(decoded is String){"BROWSER_ADAPTER_UNAVAILABLE"};value.set(JSONObject(decoded))}
            }catch(e:Throwable){failure.set(e)}finally{latch.countDown()} }
        }catch(e:Throwable){failure.set(e);latch.countDown()} }
        await(latch,token,expired,8000);failure.get()?.let{throw it};return value.get() ?: error("BROWSER_EVALUATION_TIMEOUT")
    }
    private fun checked(result:JSONObject):JSONObject {result.optString("error").takeIf{it.isNotEmpty()}?.let{error(it)};return result}
    private fun observe(s:Session,t:Tab,a:JSONObject,token:CancellationToken,observer:Tools.Observer?):JSONObject {
        val epoch=s.epoch;val generation=onMain(token){t.generation}
        val snapshot=id("snap_");val request=JSONObject(a.toString()).put("snapshot_id",snapshot)
        val result=checked(evaluate(s,t,"observe",request,token))
        onMain(token){live(s,t,epoch,generation,token);t.snapshot=snapshot;recordViewport(s.id,t.id,result.getJSONObject("viewport"))}
        result.put("session_id",s.id).put("tab_id",t.id).put("blocked_domains",JSONArray(synchronized(s.blockedDomains){s.blockedDomains.toList()}))
            .put("storage",if(s.isolated)"ISOLATED_PROFILE" else "SITE_STORAGE_COMPATIBILITY")
        if(a.optBoolean("screenshot",false)) {
            if(result.optBoolean("protected_inputs") || result.optJSONArray("unavailable")?.length() != 0)result.put("screenshot","WITHHELD_PROTECTED_OR_UNREADABLE_CONTENT")
            else if(evaluate(s,t,"prepare",JSONObject().put("snapshot_id",snapshot).put("action","scroll"),token).optString("status")!="READY")result.put("screenshot","WITHHELD_STALE_SNAPSHOT").put("snapshot_stale",true)
            else {val attachment=onMain(token){
                live(s,t,epoch,generation,token);check(t.snapshot==snapshot){"BROWSER_SNAPSHOT_STALE"}
                val bitmap=Bitmap.createBitmap(t.view.width.coerceAtLeast(1),t.view.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                try{t.view.draw(Canvas(bitmap));val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)
                    Message.Attachment("browser.png",null,"image/png",bytes.size().toLong(),"image").apply{dataUri="data:image/png;base64,"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)}
                }finally{bitmap.recycle()}
            };observer?.onPhoneImage(attachment);result.put("screenshot",if(observer==null)"NO_IMAGE_OBSERVER" else "ATTACHED_VIEWPORT").put("screenshot_note","WebView draw may omit hardware video surfaces; inspect actual pixels.")}
        }
        return result
    }
    private fun wait(s:Session,t:Tab,a:JSONObject,token:CancellationToken):JSONObject {
        val deadline=SystemClock.elapsedRealtime()+a.optLong("timeout_ms",5000);var observation=JSONObject()
        val owner=s.owner;val epoch=s.epoch
        do {
            try {observation=observe(s,t,a,token,null)} catch(e: IllegalStateException) {
                // Page loads can advance only the tab generation while JS readback is in flight.
                // Retry a read, never an action or an owner/takeover/authority change.
                val loadingRace=e.message=="BROWSER_SESSION_CHANGED" && onMain(token){s.owner==owner && owner!=null && s.epoch==epoch && !s.ended && !s.takeover && !t.destroyed}
                if(!loadingRace || SystemClock.elapsedRealtime()>=deadline)throw e
                token.sleep(100);continue
            }
            val text=a.optString("text");val met=when(a.optString("until","ready")){
                "ready" -> !onMain(token){t.loading} && observation.optString("ready_state")=="complete"
                "text" -> text.isNotEmpty() && observation.optString("text").contains(text)
                "gone" -> text.isNotEmpty() && !observation.optString("text").contains(text) && !observation.optBoolean("truncated") && observation.optJSONArray("unavailable")?.length()==0 && !observation.optBoolean("challenge_detected") && !onMain(token){t.loading}
                "url" -> a.optString("url_contains").isNotEmpty() && observation.optString("url").contains(a.optString("url_contains"))
                else -> false
            }
            if(met)return JSONObject().put("status","MET").put("observation",observation)
            token.sleep(250)
        }while(SystemClock.elapsedRealtime()<deadline)
        return JSONObject().put("status","TIMEOUT").put("observation",observation).put("note","Timeout does not prove a previous action failed; do not replay a submission.")
    }
    private fun approvalSignature(s:Session,t:Tab,a:JSONObject,p:JSONObject):String = BrowserProtocol.approvalFingerprint(s.id,t.id,t.view.url,a,p)
    private fun action(s:Session,t:Tab,raw:JSONObject,token:CancellationToken,observer:Tools.Observer?):JSONObject {
        val a=JSONObject(raw.toString()).put("tab_id",t.id)
        if(a.has("artifact_uri"))a.put("action","click")
        val request=a.getString("request_id");val fingerprint=BrowserProtocol.fingerprint(a)
        s.receipts.replay(request,fingerprint)?.let{return it}
        check(s.receipts.unresolved==null){"BROWSER_UNKNOWN_ACTION_REQUIRES_USER_REVIEW"}
        onMain(token){check(t.snapshot==a.getString("snapshot_id")){"BROWSER_SNAPSHOT_STALE"}}
        val proof=checked(evaluate(s,t,"prepare",a,token))
        if(a.has("artifact_uri"))check(proof.optString("role")=="file"){"BROWSER_UPLOAD_FILE_INPUT_REQUIRED"}
        if(proof.optString("href").isNotEmpty())onMain(token){check(network(s,proof.getString("href"))){"BROWSER_NETWORK_AUTHORIZATION_REQUIRED"}}
        val signature=onMain(token){approvalSignature(s,t,a,proof)}
        if(a.has("artifact_uri") || proof.optBoolean("requires_approval")){
            val approved=onMain(token){s.approved?.takeIf{it.signature==signature && it.expires>SystemClock.elapsedRealtime()}}
            if(approved==null)return onMain(token){
                s.pending=Approval(id("approval_"),signature,JSONObject(proof.toString()).put("artifact_uri",a.optString("artifact_uri")).put("upload_name",a.optString("filename")),a.getString("action"),SystemClock.elapsedRealtime()+300000)
                takeOver(s);BrowserActivity.open(app,s.chatId,s.id)
                JSONObject().put("status","NEEDS_USER_APPROVAL").put("submitted",false).put("session_id",s.id).put("approval_id",s.pending!!.id)
                    .put("note","Review in the same browser. After approval, observe again and use a fresh snapshot; the model cannot approve or release takeover.")
            }
        }
        s.receipts.begin(request,fingerprint)
        var submitted=false
        try {
            // A new prepare at commit rejects changed text, values, geometry and page versions.
            val current=checked(evaluate(s,t,"prepare",a,token))
            check(onMain(token){approvalSignature(s,t,a,current)}==signature){"BROWSER_TARGET_CHANGED"}
            if(a.has("artifact_uri") || current.optBoolean("requires_approval"))onMain(token){check(s.approved?.signature==signature){"BROWSER_APPROVAL_EXPIRED"};check(s.approved!!.expires>SystemClock.elapsedRealtime());s.approved=null}
            s.receipts.submitting(request)
            val action=a.getString("action")
            val result=if(action in setOf("click","press"))onMain(token){
                token.throwIfCancelled();check(!s.takeover && !s.ended && t.snapshot==a.getString("snapshot_id")){"BROWSER_SESSION_CHANGED"}
                t.view.requestFocus()
                if(a.has("artifact_uri")){check(s.stagedUpload!=null){"BROWSER_UPLOAD_NOT_STAGED"};s.upload=s.stagedUpload;s.uploadTab=t.id}
                val viewport=JSONObject(checkedSnapshotViewport(s,t))
                if(action=="click"){
                    val x=(current.getDouble("x")*t.view.width/viewport.getInt("width")).toFloat();val y=(current.getDouble("y")*t.view.height/viewport.getInt("height")).toFloat()
                    val time=SystemClock.uptimeMillis();val down=fingerEvent(time,time,MotionEvent.ACTION_DOWN,x,y);val up=fingerEvent(time,time+30,MotionEvent.ACTION_UP,x,y)
                    try{t.view.dispatchTouchEvent(down);t.view.dispatchTouchEvent(up)}finally{down.recycle();up.recycle()}
                }else{
                    val key=when(a.getString("key")){"Enter"->KeyEvent.KEYCODE_ENTER;"Tab"->KeyEvent.KEYCODE_TAB;else->KeyEvent.KEYCODE_ESCAPE}
                    t.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key));t.view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,key))
                }
                submitted=true;t.snapshot=null;JSONObject().put("status","DISPATCHED").put("submitted",true).put("input_method","native_webview_input")
            }else{val applied=evaluate(s,t,"act",a,token);submitted=applied.optBoolean("submitted");checked(applied)}
            val receipt=s.receipts.finish(request,result.getString("status"),if(result.getString("status")=="UNKNOWN")null else submitted)
            receipt.put("input_method",result.optString("input_method")).put("business_result","NOT_VERIFIED")
            token.sleep(300)
            runCatching{observe(s,t,JSONObject(),token,observer)}.onSuccess{receipt.put("observation",it)}.onFailure{receipt.put("observation_error",it.message?.take(180))}
            return receipt
        }catch(e:Throwable){
            val uncertain=submitted || s.receipts.unresolved==request
            return s.receipts.finish(request,if(uncertain)"UNKNOWN" else "NOT_SUBMITTED",if(uncertain)null else false).put("error",e.message?.take(180))
        }
    }
    private val viewports=java.util.concurrent.ConcurrentHashMap<String,String>()
    private fun fingerEvent(start: Long,now: Long,action: Int,x: Float,y: Float): MotionEvent {
        val pointer=MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_FINGER}
        val coordinate=MotionEvent.PointerCoords().apply {this.x=x;this.y=y;pressure=if(action==MotionEvent.ACTION_UP)0f else 1f;size=1f}
        return MotionEvent.obtain(start,now,action,1,arrayOf(pointer),arrayOf(coordinate),0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
    }
    private fun checkedSnapshotViewport(s:Session,t:Tab):String=viewports[s.id+":"+t.id] ?: error("BROWSER_VIEWPORT_REQUIRED")
    fun recordViewport(sessionId:String,tabId:String,viewport:JSONObject){viewports[sessionId+":"+tabId]=viewport.toString()}
    fun stageUpload(chat:String,owner:String,a:JSONObject,uris:Array<Uri>,token:CancellationToken):Session=onMain(token){
        val s=session(chat,owner,a,true);check(s.stagedUpload==null && s.upload==null){"BROWSER_UPLOAD_BUSY"};s.stagedUpload=uris;s.uploadConsumed=false;s
    }
    fun finishUpload(s:Session,token:CancellationToken):Boolean=onMain(token){s.upload=null;s.uploadTab=null;s.stagedUpload=null;s.uploadConsumed}
    fun abortUpload(s:Session){main.post{s.upload=null;s.uploadTab=null;s.stagedUpload=null}}
    fun retainUpload(s:Session,stager:dev.wind.agent.webprovider.WebUploadStager,token:CancellationToken)=onMain(token){s.retainedUploads.add(stager);main.postDelayed({stager.close();s.retainedUploads.remove(stager)},30*60*1000L)}
    fun attach(s:Session,activity:BrowserActivity,container:ViewGroup){check(sessions[s.id]===s);takeOver(s);s.activity=activity;s.container=container;mount(s)}
    private fun mount(s:Session){val parent=s.container ?: return;parent.removeAllViews();s.tabs[s.activeTab]?.let{t->(t.view.parent as?ViewGroup)?.removeView(t.view);t.wrapper.baseContext=s.activity ?: app;parent.addView(t.view,ViewGroup.LayoutParams(-1,-1));t.view.onResume()}}
    fun detach(s:Session,activity:BrowserActivity){if(s.activity!==activity)return;s.container?.removeAllViews();s.tabs.values.forEach{it.wrapper.baseContext=app};s.container=null;s.activity=null;s.humanBrowsing=false;s.chooser?.onReceiveValue(null);s.chooser=null}
    fun takeOver(s:Session){s.takeover=true;s.humanBrowsing=false;s.epoch++;s.tabs.values.forEach{it.snapshot=null}}
    fun humanSwitch(s:Session,id:String){check(s.tabs.containsKey(id));takeOver(s);s.activeTab=id;mount(s)}
    fun resumeByUser(s:Session,approve:Boolean=false){
        if(approve){val p=s.pending ?: error("BROWSER_APPROVAL_NOT_FOUND");check(p.expires>SystemClock.elapsedRealtime()){ "BROWSER_APPROVAL_EXPIRED" };s.approved=p;s.pending=null}
        s.takeover=false;s.humanBrowsing=false;s.epoch++;s.tabs.values.forEach{it.snapshot=null}
        dev.wind.agent.mate.MateTaskCoordinator.browserReturned(s.activity ?: app,s.chatId,s.id)
    }
    fun reviewUnknownByUser(s:Session){s.receipts.reviewedByUser();dev.wind.agent.mate.MateTaskCoordinator.browserReturned(s.activity ?: app,s.chatId,s.id,true)}
    fun endTask(owner:String,cancelled:Boolean=false){main.post{sessions.values.filter{it.owner==owner}.forEach{s->s.ended=true;s.owner=null;s.epoch++;if(cancelled){s.approved=null;s.pending=null};s.tabs.values.forEach{it.snapshot=null;it.view.stopLoading();if(s.activity==null)it.view.onPause()};s.upload=null;s.stagedUpload=null;s.uploadTab=null}}}
    fun humanStatus(s:Session):JSONObject=status(s)
    private fun status(s:Session):JSONObject=JSONObject().put("session_id",s.id).put("backend","ANDROID_WEBVIEW").put("state",if(s.takeover)"HUMAN_TAKEOVER" else if(s.ended)"TASK_ENDED" else "READY")
        .put("active_tab",s.activeTab).put("storage",if(s.isolated)"ISOLATED_PROFILE" else "SITE_STORAGE_COMPATIBILITY")
        .put("tabs",JSONArray(s.tabs.values.map{JSONObject().put("tab_id",it.id).put("url",BrowserProtocol.displayUrl(it.view.url)).put("title",it.view.title?.take(240)).put("loading",it.loading).put("error",it.failure ?: JSONObject.NULL).put("destroyed",it.destroyed)}))
        .put("blocked_domains",JSONArray(synchronized(s.blockedDomains){s.blockedDomains.toList()})).put("pending_approval",s.pending?.id ?: JSONObject.NULL).put("unknown_request_id",s.receipts.unresolved ?: JSONObject.NULL).put("download",s.download ?: JSONObject.NULL)
        .put("network_enforcement","WEBVIEW_POLICY_ONLY_NOT_OS_ISOLATION")
    private fun await(latch:CountDownLatch,token:CancellationToken,expired:AtomicBoolean,timeout:Long){val deadline=SystemClock.elapsedRealtime()+timeout;try{while(!latch.await(100,TimeUnit.MILLISECONDS)){token.throwIfCancelled();check(SystemClock.elapsedRealtime()<deadline){"BROWSER_CALLBACK_TIMEOUT"}};token.throwIfCancelled()}catch(e:Throwable){expired.set(true);throw e}}
    fun <T> onMain(token:CancellationToken,action:()->T):T {
        check(Looper.myLooper()!=Looper.getMainLooper()){ "BROWSER_WORKER_REQUIRED" }
        val value=AtomicReference<T>();val failure=AtomicReference<Throwable>();val latch=CountDownLatch(1);val expired=AtomicBoolean()
        main.post{try{if(!expired.get() && !token.isCancelled)value.set(action())}catch(e:Throwable){failure.set(e)}finally{latch.countDown()}}
        await(latch,token,expired,8000);failure.get()?.let{throw it};return value.get()
    }
    companion object {@Volatile private var instance:BrowserSessions?=null;fun get(context:Context):BrowserSessions=instance ?: synchronized(this){instance ?: BrowserSessions(context).also{instance=it}};fun endTaskIfOpen(owner:String,cancelled:Boolean=false){instance?.endTask(owner,cancelled)}}
}
private fun Looper.isCurrentThreadCompat() = Looper.myLooper() == this
