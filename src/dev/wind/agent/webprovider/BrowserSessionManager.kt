package dev.wind.agent.webprovider

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.wind.agent.CancellationToken
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONTokener

/** Browser state is process-local; only sanitized session references leave this layer. */
class BrowserSessionManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = WebProviderPrefs(app)
    private val sessions = LinkedHashMap<String, Session>()
    private val script by lazy { app.assets.open("webproviders/adapter.js").bufferedReader().use { it.readText() } }
    @Volatile private var activeProfile: String? = null
    @Volatile var browserSupport: String = "NOT_CHECKED"
        private set

    class Session internal constructor(
        val provider: WebProviderDefinition, val profileName: String,
        val context: MutableContextWrapper, val view: WebView, val profile: Profile?,
        val key: String = "__vegaWeb_" + UUID.randomUUID().toString().replace("-", "")
    ) {
        @Volatile var state: String = "LOADING"
        @Volatile var error: String? = null
        @Volatile var progress: Int = 0
        @Volatile var destroyed: Boolean = false
        @Volatile var takeover: Boolean = false
        @Volatile var resumed: Long = 0L
        @Volatile var approvedAttachments: Boolean = false
        @Volatile var localChatId: String? = null
        @Volatile var requestId: String? = null
        @Volatile var uploads: List<WebUploadStager.Upload> = emptyList()
        @Volatile var chooserConsumed: Boolean = false
        @Volatile var blockedUrl: String? = null
        var chooser: ValueCallback<Array<Uri>>? = null
        var activity: WebProviderBrowserActivity? = null
        val approvedLoginHosts = HashSet<String>()
        val cookies: CookieManager get() = profile?.cookieManager ?: CookieManager.getInstance()
    }

    /** Older WebViews use normal origin-scoped site storage; UI exposes its weaker isolation. */
    fun supported(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return try {
            val ready = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            browserSupport = if (ready) "MULTI_PROFILE" else "SITE_STORAGE_COMPATIBILITY"
            true
        } catch (_: Throwable) { browserSupport = "WEBVIEW_UNAVAILABLE"; false }
    }

    fun open(provider: WebProviderDefinition, binding: WebConversationBinding, chatId: String?): Session {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(provider.configured) { "PROVIDER_ENTRY_UNCONFIGURED" }
        if (activeProfile != null && (activeProfile != binding.profileName || sessions[activeProfile]?.localChatId != chatId))
            throw IllegalStateException("WEB_PROVIDER_BUSY")
        require(binding.providerId == provider.id && binding.profileName == prefs.profile(provider.id)) { "PROFILE_BINDING_EXPIRED" }
        check(supported()) { browserSupport }
        sessions[binding.profileName]?.takeIf { !it.destroyed }?.let { existing ->
            if (activeProfile != null && (activeProfile != existing.profileName || existing.localChatId != chatId))
                throw IllegalStateException("WEB_PROVIDER_BUSY")
            if (existing.localChatId != chatId) {
                existing.localChatId = chatId
                existing.error = null
                existing.view.loadUrl(binding.remoteUrl?.takeIf { provider.owns(it) } ?: provider.home)
            }
            return existing
        }
        if (browserSupport == "MULTI_PROFILE") cleanRetiredProfiles()
        // Keep only one idle browser. Persistent Profile data survives WebView destruction.
        sessions.values.filter { it.profileName != activeProfile && it.activity == null }.toList().forEach { destroy(it) }
        val wrapper = MutableContextWrapper(app)
        val view = WebView(wrapper)
        try {
            if (browserSupport == "MULTI_PROFILE") WebViewCompat.setProfile(view, binding.profileName)
            val profile = if (browserSupport == "MULTI_PROFILE") WebViewCompat.getProfile(view) else null
            if (profile == null) prefs.usedCompatibility(true)
            val session = Session(provider, binding.profileName, wrapper, view, profile)
            session.localChatId = chatId
            configure(session)
            // Hidden WebViews still need a viewport for responsive layout and DOM visibility checks.
            val metrics = app.resources.displayMetrics
            view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            view.onResume()
            sessions[binding.profileName] = session
            view.loadUrl(binding.remoteUrl?.takeIf { provider.owns(it) } ?: provider.home)
            return session
        } catch (failure: Throwable) { view.destroy(); throw failure }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configure(session: Session) {
        val view = session.view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
        }
        session.cookies.setAcceptCookie(true)
        session.cookies.setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(parent: WebView?, dialog: Boolean, userGesture: Boolean, message: android.os.Message?): Boolean {
                // Follow explicit login popup clicks in the same session, not in an unprofiled browser.
                if (!userGesture || !session.takeover || message == null) return false
                val child = WebView(session.context)
                if (session.profile != null) WebViewCompat.setProfile(child, session.profileName)
                child.webViewClient = object : WebViewClient() {
                    private fun follow(url: String?): Boolean {
                        if (url != null && !navigate(session, url)) session.view.loadUrl(url)
                        main.post { child.destroy() }
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = follow(request?.url?.toString())
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = follow(url)
                }
                (message.obj as? WebView.WebViewTransport)?.webView = child
                message.sendToTarget(); return true
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) { session.progress = newProgress }
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                session.chooser?.onReceiveValue(null)
                session.chooser = null
                if (!session.provider.owns(view?.url)) { callback?.onReceiveValue(null); return true }
                val fixed = session.uploads
                if (fixed.isNotEmpty() && !session.chooserConsumed) {
                    val accepted = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                    val multiple = params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    val compatible = fixed.all { f -> accepted.isEmpty() || accepted.any { type ->
                        type == "*/*" || type == f.mime || (type.endsWith("/*") && f.mime.startsWith(type.substringBefore('/'))) ||
                            (type.startsWith('.') && f.name.endsWith(type, true))
                    } }
                    if (!compatible || (!multiple && fixed.size > 1)) {
                        session.error = "ATTACHMENT_TYPES_OR_COUNT_UNSUPPORTED"; callback?.onReceiveValue(null); return true
                    }
                    session.chooserConsumed = true
                    callback?.onReceiveValue(fixed.map { it.uri }.toTypedArray())
                    return true
                }
                val activity = session.activity
                if (activity != null && session.takeover) {
                    session.chooser = callback
                    activity.chooseFiles(params)
                } else { callback?.onReceiveValue(null); session.state = "UPLOAD_REQUIRES_TAKEOVER" }
                return true
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> session.state = "DOWNLOAD_REQUIRES_EXTERNAL_BROWSER" }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = navigate(session, request?.url?.toString())
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = navigate(session, url)
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                session.state = "LOADING"; session.error = null; session.approvedAttachments = false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (session.destroyed) return
                if (session.error == null) session.state = if (session.provider.owns(url)) "PAGE_LOADED" else "LOGIN_REQUIRED"
                session.cookies.flush()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) { session.error = "PAGE_LOAD_FAILED"; session.state = "NETWORK_FAILED" }
            }
            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, code: Int, description: String?, failingUrl: String?) {
                if (Build.VERSION.SDK_INT < 23) { session.error = "PAGE_LOAD_FAILED"; session.state = "NETWORK_FAILED" }
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                if (request?.isForMainFrame == true && (response?.statusCode ?: 0) >= 400) {
                    session.error = if (response?.statusCode == 429) "RATE_LIMITED" else "PAGE_HTTP_ERROR"
                    session.state = session.error!!
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel(); session.error = "TLS_ERROR"; session.state = "TLS_ERROR"
            }
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                if (uri.scheme in setOf("data", "blob", "about")) return null
                if (session.provider.id == "fixture" && uri.host == "web-provider.vega.invalid") {
                    if (uri.path == "/fixture") return WebResourceResponse("text/html", "UTF-8", app.assets.open("webproviders/fixture.html"))
                    return blockedResource()
                }
                val host = uri.host?.lowercase() ?: ""
                if (uri.scheme != "https" || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                    host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || host == "::1" || host == "[::1]") return blockedResource()
                return null
            }
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                session.error = "RENDERER_GONE"; session.state = "RENDERER_GONE"
                destroy(session)
                return true
            }
        }
    }

    private fun blockedResource() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
    private fun navigate(session: Session, url: String?): Boolean {
        if (session.provider.canNavigate(url)) return false
        if (WebUrlPolicy.publicHttps(url) && java.net.URI(url).host.lowercase(java.util.Locale.ROOT) in session.approvedLoginHosts) return false
        session.blockedUrl = url?.takeIf { it.startsWith("https://") }
        session.state = "EXTERNAL_NAVIGATION_BLOCKED"
        if (session.takeover && WebUrlPolicy.publicHttps(url)) session.activity?.confirmNavigation(url!!)
        return true
    }

    fun attach(session: Session, activity: WebProviderBrowserActivity, container: ViewGroup) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!session.destroyed) { "RENDERER_GONE" }
        (session.view.parent as? ViewGroup)?.removeView(session.view)
        session.context.baseContext = activity
        session.activity = activity; session.takeover = true
        container.addView(session.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        session.view.onResume()
    }
    fun detach(session: Session, activity: Activity) {
        if (session.activity !== activity) return
        (session.view.parent as? ViewGroup)?.removeView(session.view)
        session.context.baseContext = app
        session.activity = null; session.takeover = false
        session.chooser?.onReceiveValue(null); session.chooser = null
        // Do not call pauseTimers(): it would pause every Provider in this process.
    }
    fun resume(session: Session) {
        session.approvedAttachments = session.state in setOf("UPLOAD_UNCONFIRMED", "UPLOAD_CONTROL_NOT_FOUND")
        session.takeover = false; session.resumed++
    }
    fun acquire(session: Session, requestId: String) {
        check(activeProfile == null) { "WEB_PROVIDER_BUSY" }
        activeProfile = session.profileName; session.requestId = requestId
    }
    fun release(session: Session) {
        if (activeProfile == session.profileName) activeProfile = null
        session.requestId = null; session.uploads = emptyList(); session.chooserConsumed = false
    }
    fun sessionFor(providerId: String): Session? = sessions.values.firstOrNull { it.provider.id == providerId && !it.destroyed }
    fun active(): Session? = sessions[activeProfile]
    fun isBusy(): Boolean = activeProfile != null

    fun destroy(session: Session) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (session.destroyed) return
        session.destroyed = true
        (session.view.parent as? ViewGroup)?.removeView(session.view)
        session.chooser?.onReceiveValue(null); session.chooser = null
        session.context.baseContext = app; session.activity = null
        session.view.stopLoading(); session.view.destroy()
        sessions.remove(session.profileName)
    }

    /** No credential reading occurs here. New Profile epoch prevents reuse of retired data. */
    fun clearProvider(id: String, done: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (isBusy()) { done(false); return }
        val oldName = prefs.profile(id)
        val existing = sessions[oldName]
        if (existing?.profile == null && (existing != null || supported() && browserSupport == "SITE_STORAGE_COMPATIBILITY")) {
            // Clearing default cookies would sign out OTHER providers as well. Require the all-data action instead.
            prefs.status(id, "COMPATIBILITY_CLEANUP_REQUIRES_ALL"); done(false); return
        }
        val profile = existing?.profile ?: if (supported()) ProfileStore.getInstance().getProfile(oldName) else null
        existing?.let { destroy(it) }
        if (profile == null) { clearMappings(id); prefs.rotateProfile(id); done(true); return }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            WebStorageCompat.deleteBrowsingData(profile.webStorage, Runnable {
                clearMappings(id); prefs.rotateProfile(id); retire(oldName); done(true)
            })
        } else {
            profile.cookieManager.removeAllCookies {
                profile.cookieManager.flush(); profile.webStorage.deleteAllData(); profile.geolocationPermissions.clearAll()
                clearMappings(id); prefs.rotateProfile(id); retire(oldName)
                prefs.status(id, "CLEANUP_PENDING_RESTART"); done(false)
            }
        }
    }
    fun clearAll(done: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (isBusy()) { done(false); return }
        if (!supported()) { done(false); return }
        if (browserSupport == "MULTI_PROFILE") {
            val providers = WebProviderRegistry.all.filter { it.configured }
            var ok = true
            fun next(i: Int) {
                if (i == providers.size) {
                    if (prefs.usedCompatibility()) clearDefaultData { done(ok) } else done(ok)
                    return
                }
                clearProvider(providers[i].id) { success -> ok = ok && success; next(i + 1) }
            }
            next(0); return
        }
        sessions.values.toList().forEach { it.view.clearCache(true); destroy(it) }
        val finish = Runnable {
            WebProviderRegistry.all.filter { it.configured }.forEach { clearMappings(it.id); prefs.rotateProfile(it.id) }
            done(true)
        }
        clearDefaultData { finish.run() }
    }
    private fun clearDefaultData(done: () -> Unit) {
        // Also clear persistent data after an app restart, when no WebView session exists yet.
        val temporary = WebView(app)
        temporary.clearCache(true); temporary.destroy()
        val finish = Runnable { prefs.usedCompatibility(false); done() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA))
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), finish)
        else CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData()
            android.webkit.GeolocationPermissions.getInstance().clearAll(); finish.run()
        }
    }
    private fun clearMappings(id: String) {
        WebProviderStore(app).clearProvider(id)
        val store = dev.wind.agent.ChatStore(app)
        store.list().filter { it.webBinding?.providerId == id || it.webBindings.containsKey(id) }.forEach { chat ->
            chat.webBindings[id]?.let { it.remoteUrl = null; it.remoteId = null; it.updatedAt = 0 }
            chat.webBinding?.takeIf { it.providerId == id }?.let { it.remoteUrl = null; it.remoteId = null; it.updatedAt = 0 }
            store.saveNow(chat)
        }
        dev.wind.agent.screen.ScreenShareSession.chat?.webBinding?.takeIf { it.providerId == id }?.let {
            it.remoteUrl = null; it.remoteId = null; it.updatedAt = 0
        }
        dev.wind.agent.screen.ScreenShareSession.chat?.webBindings?.get(id)?.let {
            it.remoteUrl = null; it.remoteId = null; it.updatedAt = 0
        }
    }
    private fun retire(name: String) {
        val sp = app.getSharedPreferences("vega_web_retired", Context.MODE_PRIVATE)
        val names = HashSet(sp.getStringSet("names", emptySet()) ?: emptySet()); names.add(name)
        sp.edit().putStringSet("names", names).commit()
    }
    private fun cleanRetiredProfiles() {
        val sp = app.getSharedPreferences("vega_web_retired", Context.MODE_PRIVATE)
        val names = HashSet(sp.getStringSet("names", emptySet()) ?: emptySet())
        val remaining = HashSet<String>()
        names.filter { it.matches(Regex("vega_web_[a-z0-9_]{1,80}")) }.forEach {
            try { ProfileStore.getInstance().deleteProfile(it) } catch (_: IllegalStateException) { remaining.add(it) }
        }
        sp.edit().putStringSet("names", remaining).commit()
    }

    fun <T> onMain(token: CancellationToken, action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Browser worker cannot block main thread" }
        val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>(); val latch = CountDownLatch(1)
        val expired = AtomicBoolean(false)
        main.post { try { if (!expired.get() && !token.isCancelled) result.set(action()) } catch (e: Throwable) { failure.set(e) } finally { latch.countDown() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            if (token.isCancelled || System.nanoTime() >= deadline) {
                expired.set(true); token.throwIfCancelled(); throw IllegalStateException("BROWSER_MAIN_THREAD_TIMEOUT")
            }
        }
        token.throwIfCancelled(); failure.get()?.let { throw it }; return result.get()
    }

    fun evaluate(session: Session, command: String, args: JSONObject, token: CancellationToken): JSONObject {
        val latch = CountDownLatch(1); val raw = AtomicReference<String?>(); val failure = AtomicReference<Throwable?>()
        val expired = AtomicBoolean(false)
        main.post {
            try {
                if (expired.get() || token.isCancelled) { latch.countDown(); return@post }
                check(!session.destroyed && session.provider.owns(session.view.url)) { "UNTRUSTED_PAGE_ORIGIN" }
                val config = JSONObject().put("adapter", session.provider.adapter).put("hosts", org.json.JSONArray(session.provider.hosts.toList()))
                    .put("version", WebProviderRegistry.ADAPTER_VERSION)
                val bootstrap = script.replace("__HARNESS_KEY__", JSONObject.quote(session.key)).replace("__HARNESS_CONFIG__", config.toString())
                val js = bootstrap + "\nJSON.stringify(window[" + JSONObject.quote(session.key) + "].command(" + JSONObject.quote(command) + "," + args.toString() + "));"
                session.view.evaluateJavascript(js) { value ->
                    if (session.provider.owns(session.view.url) && !session.destroyed) raw.set(value) else failure.set(IllegalStateException("PAGE_CHANGED_DURING_OPERATION"))
                    latch.countDown()
                }
            } catch (e: Throwable) { failure.set(e); latch.countDown() }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            if (token.isCancelled || System.nanoTime() >= deadline) {
                expired.set(true); token.throwIfCancelled(); throw IllegalStateException("BROWSER_EVALUATION_TIMEOUT")
            }
        }
        token.throwIfCancelled(); failure.get()?.let { throw it }
        val value = raw.get() ?: throw IllegalStateException("EMPTY_BROWSER_RESULT")
        require(value.length <= 1_500_000) { "BROWSER_RESULT_TOO_LARGE" }
        val decoded = JSONTokener(value).nextValue() as? String ?: throw IllegalStateException("ADAPTER_RESULT_UNAVAILABLE")
        val json = JSONObject(decoded)
        require(!json.has("cookie") && !json.has("token")) { "INVALID_ADAPTER_RESULT" }
        return json
    }
    companion object {
        @Volatile private var instance: BrowserSessionManager? = null
        fun get(context: Context): BrowserSessionManager = instance ?: synchronized(this) {
            instance ?: BrowserSessionManager(context).also { instance = it }
        }
    }
}
