package dev.wind.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/** Offline, display-only rich output; model HTML and network subresources never execute. */
object RichResponseView {
    private const val PAGE = "https://appassets.androidplatform.net/assets/chat-render/index.html"
    private val formatting = Regex("(?m)^(?:#{1,6}\\s|>\\s|[-*+]\\s|[0-9]+[.)]\\s| {4}|\\t|---+\\s*$)")

    internal fun needsRichRendering(source: String): Boolean = formatting.containsMatchIn(source) ||
        source.any { it in "*_~`$[]<>|=" } || source.contains("\\(")

    @SuppressLint("SetJavaScriptEnabled")
    fun tryRender(context: Context, container: LinearLayout, source: String): Boolean {
        if (!needsRichRendering(source)) return false
        val web = try { DisplayWebView(context) } catch (_: Exception) { return false }
        try {
            // Keep readable native output until local scripts have actually rendered successfully.
            MarkdownRenderer.renderNative(context, container, source)
            web.alpha = 0f
            web.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            web.tag = MarkdownRenderer.TAG_CODE_CARD
            web.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/",
                WebViewAssetLoader.AssetsPathHandler(context)).build()
            web.addJavascriptInterface(object {
                @JavascriptInterface fun height(cssPixels: Int) {
                    if (cssPixels <= 0) return
                    web.post {
                        if (!web.disposed) {
                            val px = (cssPixels.toDouble() * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                            if (web.layoutParams?.height != px) { web.layoutParams?.height = px; web.requestLayout() }
                        }
                    }
                }
                @JavascriptInterface fun copy(text: String) {
                    web.post { if (!web.disposed) MarkdownRenderer.copyText(context, Fa.APP_NAME, text) }
                }
            }, "HarnessMateDisplay")
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                        uri.path.orEmpty().startsWith("/assets/chat-render/")) {
                        loader.shouldInterceptRequest(uri)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (request.hasGesture() && request.isForMainFrame && uri.scheme in setOf("https", "http") &&
                        uri.host != null && uri.host != "appassets.androidplatform.net" && uri.userInfo == null) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != PAGE || web.disposed) return
                    fun color(value: Int) = String.format(Locale.US, "#%06x", value and 0xffffff)
                    val palette = JSONObject().put("text", color(Theme.TEXT)).put("muted", color(Theme.TEXT_MUTED))
                        .put("surface", color(Theme.SURFACE)).put("border", color(Theme.BORDER))
                        .put("fontScale", context.resources.configuration.fontScale.toDouble())
                    val quoted = JSONObject.quote(source).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                    web.evaluateJavascript("window.HarnessMateRenderer && window.HarnessMateRenderer.setSource($quoted,$palette)") { result ->
                        if (web.disposed) return@evaluateJavascript
                        if (result == "true") {
                            for (i in container.childCount - 1 downTo 0) if (container.getChildAt(i) !== web) container.removeViewAt(i)
                            web.alpha = 1f
                        } else { container.removeView(web); web.dispose() }
                    }
                }
            }
            container.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            web.loadUrl(PAGE)
            return true
        } catch (_: Exception) {
            container.removeView(web)
            web.dispose()
            return false
        }
    }

    private class DisplayWebView(context: Context) : WebView(context) {
        var disposed = false
            private set
        fun dispose() {
            if (!disposed) { disposed = true; stopLoading(); removeJavascriptInterface("HarnessMateDisplay"); destroy() }
        }
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            post { if (!isAttachedToWindow) dispose() }
        }
    }
}
