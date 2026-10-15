package dev.wind.agent.webprovider

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.wind.agent.AgentBus
import dev.wind.agent.ChatStore
import dev.wind.agent.Theme
import dev.wind.agent.Ui
import dev.wind.agent.modelprovider.ProviderUi
import android.widget.ProgressBar
import android.view.View

/** Shows the very same WebView used by the hidden worker. No login-state import/export. */
class WebProviderBrowserActivity : Activity() {
    private var session: BrowserSessionManager.Session? = null
    private var manager: BrowserSessionManager? = null
    private var status: TextView? = null
    private var loading: ProgressBar? = null
    private var chatId: String? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val s = session ?: return
            status?.text = WebStrings.state(this@WebProviderBrowserActivity, s.error ?: s.state) + " · " + s.progress + "%" +
                if (s.profile == null) "\n" + WebStrings.state(this@WebProviderBrowserActivity, "SITE_STORAGE_COMPATIBILITY") else ""
            loading?.progress = s.progress
            loading?.visibility = if(s.progress<100) View.VISIBLE else View.INVISIBLE
            if (!isFinishing) main.postDelayed(this, 500)
        }
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        Theme.init(this); Theme.applyFromPrefs(this, dev.wind.agent.Prefs(this))
        setTheme(if (Theme.DARK) dev.wind.agent.R.style.AppTheme else dev.wind.agent.R.style.AppThemeLight)
        val id = intent.getStringExtra("providerId") ?: return finish()
        val provider = WebProviderRegistry.get(id) ?: return finish()
        chatId = intent.getStringExtra("chatId")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        Ui.applyWindowInsets(root, 12, 0, 12, 8)
        ProviderUi.header(this,root,provider.name,"实验性网页 · " + (java.net.URI(provider.home).host ?: ""),"settings") {
            ProviderUi.menu(root.getChildAt(0),listOf(
                WebStrings.t(this,"Reload page","重新加载页面") to { reloadPage() },
                WebStrings.t(this,"Open externally","在系统浏览器打开") to { openExternal(provider) },
                WebStrings.t(this,"Resolve pending send","结束未确认请求") to { resolveUnconfirmed() }
            ))
        }
        loading=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        root.addView(loading,LinearLayout.LayoutParams(-1,ProviderUi.dp(this,3)))
        status = ProviderUi.text(this,"正在打开网页…",12f,true).apply { setPadding(ProviderUi.dp(this@WebProviderBrowserActivity,4),ProviderUi.dp(this@WebProviderBrowserActivity,8),ProviderUi.dp(this@WebProviderBrowserActivity,4),ProviderUi.dp(this@WebProviderBrowserActivity,8)) }
        root.addView(status)
        val container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(-1,0,1f))
        ProviderUi.gap(root,8)
        ProviderUi.actions(root,listOf(WebStrings.t(this,"Continue after login","登录完成，继续对话") to { continueRun() }))
        setContentView(root)
        try {
            val m = BrowserSessionManager.get(this); manager = m
            val prefs = WebProviderPrefs(this)
            val active = m.active()?.takeIf { it.provider.id == id }
            val chat = chatId?.let { key -> AgentBus.liveChat?.takeIf { it.id == key }
                ?: dev.wind.agent.screen.ScreenShareSession.chat?.takeIf { it.id == key } ?: ChatStore(this).load(key) }
            val binding = chat?.webBindings?.get(id)?.takeIf { it.profileName == prefs.profile(id) }
                ?: chat?.webBinding?.takeIf { it.providerId == id && it.profileName == prefs.profile(id) }
                ?: WebConversationBinding(id, prefs.profile(id))
            val s = active ?: m.open(provider,binding,chatId)
            session = s; chatId = s.localChatId ?: chatId
            m.attach(s,this,container); main.post(refresh)
        } catch (failure: Throwable) {
            val code = failure.message?.takeIf { it.matches(Regex("[A-Z0-9_]{2,120}")) } ?: "WEBVIEW_UNAVAILABLE"
            status?.text = WebStrings.state(this,code)
        }
    }
    private fun reloadPage() {
        session?.let { s -> AlertDialog.Builder(this).setMessage(WebStrings.t(this,
            "Reload may interrupt the current response. Continue?","重新加载可能中断当前回答，是否继续？"))
            .setPositiveButton(WebStrings.t(this,"Reload","重新加载")) { _,_-> s.error=null; s.view.reload() }
            .setNegativeButton(android.R.string.cancel,null).show() }
    }
    private fun openExternal(provider: WebProviderDefinition) {
        val s=session
        val url=s?.blockedUrl?.takeIf { WebUrlPolicy.publicHttps(it) } ?: WebUrlPolicy.sessionUrl(s?.view?.url,provider) ?: provider.home
        AlertDialog.Builder(this).setMessage(WebStrings.t(this,
            "External browser login is separate and will not sync to this app.","系统浏览器的登录状态与应用内浏览器独立，不会自动同步。"))
            .setPositiveButton(WebStrings.t(this,"Open","打开")) { _,_->
                try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } catch (_:Exception) { toast("EXTERNAL_BROWSER_UNAVAILABLE") }
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private var navigationDialog: AlertDialog? = null
    fun confirmNavigation(url: String) {
        if (navigationDialog?.isShowing == true || !WebUrlPolicy.publicHttps(url)) return
        val host = java.net.URI(url).host
        navigationDialog = AlertDialog.Builder(this).setTitle(WebStrings.t(this,"Login redirect","登录跳转"))
            .setMessage(WebStrings.t(this,"Allow this website to open $host in the current browser session?",
                "是否允许当前网站在应用内打开 $host？请确认这是你选择的登录服务。"))
            .setPositiveButton(WebStrings.t(this,"Allow","允许")) { _, _ ->
                session?.let { s -> s.approvedLoginHosts.add(host.lowercase(java.util.Locale.ROOT)); s.blockedUrl = null; s.view.loadUrl(url) }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun continueRun() {
        val s = session ?: return
        if (s.state in setOf("UPLOAD_UNCONFIRMED", "UPLOAD_CONTROL_NOT_FOUND")) {
            AlertDialog.Builder(this).setMessage(WebStrings.t(this,
                "Confirm all selected attachments are visible and finished processing in the real webpage.",
                "请确认所有已选附件已出现在真实网页中，且已处理完毕。确认后 Harness 才会发送问题。"))
                .setPositiveButton(WebStrings.t(this,"Confirmed","附件已就绪")) { _, _ -> manager?.resume(s); finish() }
                .setNegativeButton(android.R.string.cancel,null).show()
        } else { manager?.resume(s); finish() }
    }
    private fun resolveUnconfirmed() {
        val id = chatId ?: return
        if (AgentBus.isBusy()) { toast("WEB_PROVIDER_BUSY"); return }
        val store = WebProviderStore(this)
        val receipt = try { store.read(id) } catch (_: Exception) { null } ?: return
        if (receipt.state !in setOf(WebRequestState.UNKNOWN, WebRequestState.SUBMITTING, WebRequestState.SUBMITTED, WebRequestState.GENERATING)) return
        AlertDialog.Builder(this).setMessage(WebStrings.t(this,
            "Review the original webpage first. This ends the local uncertain request; it does not delete, stop or re-send the website message.",
            "请先检查原网页。这只结束本地未确认请求，不会删除、停止或重新发送第三方网页消息。确认原网页已停止后再继续。"))
            .setPositiveButton(WebStrings.t(this,"Reviewed","已检查并结束")) { _, _ ->
                receipt.state = WebRequestState.STOPPED; receipt.diagnostic = "MANUALLY_RECONCILED"; store.write(receipt); toast("MANUALLY_RECONCILED")
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    fun chooseFiles(params: WebChromeClient.FileChooserParams?) {
        try {
            val accepted = params?.acceptTypes?.filter { it.contains('/') } ?: emptyList()
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (accepted.size == 1) accepted[0] else "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            if (accepted.size > 1) picker.putExtra(Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
            startActivityForResult(picker, 4101)
        } catch (_: Exception) { session?.chooser?.onReceiveValue(null); session?.chooser = null }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != 4101) return
        val s = session ?: return
        val result = mutableListOf<Uri>()
        if (resultCode == RESULT_OK) {
            data?.data?.let { result.add(it) }
            data?.clipData?.let { clip -> for (i in 0 until minOf(clip.itemCount,8)) result.add(clip.getItemAt(i).uri) }
        }
        s.chooser?.onReceiveValue(result.distinct().take(8).toTypedArray().takeIf { it.isNotEmpty() }); s.chooser = null
    }
    override fun onDestroy() {
        navigationDialog?.dismiss()
        main.removeCallbacks(refresh)
        session?.let { manager?.detach(it,this) }
        super.onDestroy()
    }
    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    companion object {
        fun open(context: android.content.Context, providerId: String, chatId: String? = null) {
            val intent = Intent(context,WebProviderBrowserActivity::class.java).putExtra("providerId",providerId).putExtra("chatId",chatId)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
