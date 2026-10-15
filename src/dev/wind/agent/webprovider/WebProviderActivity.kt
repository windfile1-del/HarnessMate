package dev.wind.agent.webprovider

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.wind.agent.AgentBus
import dev.wind.agent.Chat
import dev.wind.agent.ChatStore
import dev.wind.agent.MainActivity
import dev.wind.agent.Prefs
import dev.wind.agent.Theme
import dev.wind.agent.Ui
import dev.wind.agent.modelprovider.ProviderUi

/** Native control surface; API configuration remains in the existing SettingsActivity. */
class WebProviderActivity : Activity() {
    private lateinit var prefs: WebProviderPrefs
    private lateinit var list: LinearLayout
    private var query = ""
    private var showHidden = false
    private var chatId: String? = null
    private val statusViews = linkedMapOf<String, TextView>()
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            statusViews.forEach { (id,view) -> view.text = WebStrings.state(this@WebProviderActivity,prefs.status(id)) }
            if (!isFinishing) main.postDelayed(this,1000)
        }
    }
    private fun text(english: String, chinese: String) = WebStrings.t(this,english,chinese)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this); Theme.applyFromPrefs(this,Prefs(this))
        setTheme(if (Theme.DARK) dev.wind.agent.R.style.AppTheme else dev.wind.agent.R.style.AppThemeLight)
        prefs = WebProviderPrefs(this); chatId = intent.getStringExtra("chatId")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }
        Ui.applyWindowInsets(root,18,0,18,12)
        ProviderUi.header(this,root,text("Model connections","模型连接"),text("Your accounts, your choice","使用自己的账号，自由选择服务"),"settings") { options(root.getChildAt(0)) }
        ProviderUi.tabs(root,listOf(text("API accounts","API 账户"),text("Subscriptions","订阅账户"),text("Web models","网页模型")),2) { i ->
            if(i!=2) { dev.wind.agent.modelprovider.ModelProvidersActivity.open(this,chatId,i==1); finish() }
        }
        val info=ProviderUi.card(this)
        val enabled = Switch(this).apply { this.text = text("Experimental Web Providers","实验性网页模型"); isChecked = prefs.enabled(); setTextColor(Theme.TEXT); textSize=15f }
        enabled.setOnCheckedChangeListener { _,checked -> prefs.setEnabled(checked) }
        info.addView(enabled)
        info.addView(label(text("Choose a service, sign in on its original page, then return to chat. No API key required.",
            "选择服务后会打开原网页，登录自己的账号即可。无需填写网址或 API Key。"),12f))
        root.addView(info)
        val search = ProviderUi.search(this,text("Search service or provider","搜索服务或提供商"))
        root.addView(search)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) { query = s?.toString() ?: ""; renderProviders() }
            override fun afterTextChanged(s: Editable?) {}
        })
        ProviderUi.gap(root,12)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled=false }
        list = ProviderUi.column(this)
        scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
        renderProviders(); main.post(refresh)
    }
    private fun options(anchor: android.view.View) {
        ProviderUi.menu(anchor,listOf(
            text("Privacy and context","隐私与上下文") to { privacy() },
            text("Conversations","网页对话管理") to { conversations() },
            text("Pending context","待发上下文") to {
                val labels=chatId?.let { WebContextInbox.labels(it) } ?: emptyList()
                AlertDialog.Builder(this).setTitle(text("Pending context","待发上下文")).setMessage(labels.joinToString("\n").ifBlank { text("None","无待发上下文") })
                    .setPositiveButton(android.R.string.ok,null).setNeutralButton(text("Clear","清空")) { _,_-> chatId?.let { WebContextInbox.clear(it) } }.show()
            },
            text("Regenerate response","重新生成回答") to { currentChat()?.let { chat -> try { WebChatActions.regenerate(this,chat); finish() } catch (_:Exception) { toast("REGENERATE_UNAVAILABLE") } } },
            text("Edit question","编辑并重新提问") to { editQuestion() },
            text("Use API in this chat","当前对话改用 API") to { switchToApi() },
            text("Default to API","新对话默认 API") to { prefs.select(""); toast(text("Default saved","默认入口已保存")) },
            (if(showHidden) text("Hide hidden services","收起隐藏服务") else text("Show hidden services","显示隐藏服务")) to { showHidden=!showHidden; renderProviders() },
            text("Clear all web data","清除全部网页数据") to { clearAll() }
        ))
    }
    private fun renderProviders() {
        if (!::list.isInitialized) return
        list.removeAllViews(); statusViews.clear()
        var count=0
        for (provider in prefs.ordered(query,showHidden)) {
            if(!showHidden && (!provider.configured || provider.id=="fixture")) continue
            count++
            val selected=currentChat()?.webBinding?.providerId==provider.id
            val card = ProviderUi.card(this,selected)
            ProviderUi.identity(card,(if(prefs.favorite(provider.id)) "★ " else "")+provider.name,provider.company,
                if(selected) text("Selected","当前对话") else if(prefs.disabled(provider.id)) text("Disabled","已停用") else text("Experimental","实验性")) { anchor ->
                ProviderUi.menu(anchor,listOf(
                    text("Set default","设为默认网页模型") to { choose(provider,true) },
                    (if(prefs.favorite(provider.id)) text("Unfavorite","取消收藏") else text("Favorite","收藏服务")) to { prefs.favorite(provider.id,!prefs.favorite(provider.id)); renderProviders() },
                    text("Move earlier","前移排序") to { prefs.rank(provider.id,prefs.rank(provider.id)+1); renderProviders() },
                    (if(prefs.hidden(provider.id)) text("Show service","显示服务") else text("Hide service","隐藏服务")) to { prefs.hide(provider.id,!prefs.hidden(provider.id)); renderProviders() },
                    (if(prefs.disabled(provider.id)) text("Enable service","启用服务") else text("Disable service","停用服务")) to { prefs.disable(provider.id,!prefs.disabled(provider.id)); renderProviders() },
                    text("About this service","服务说明") to { AlertDialog.Builder(this).setTitle(provider.name).setMessage(WebStrings.risk(this)+"\n\n"+provider.note).setPositiveButton(android.R.string.ok,null).show() },
                    text("Sign out / clear","退出并清理登录状态") to { clear(provider) }
                ))
            }
            ProviderUi.gap(card,10)
            val state = label(WebStrings.state(this,prefs.status(provider.id)),12f)
            statusViews[provider.id] = state; card.addView(state)
            val c = provider.capabilities
            val caps = mutableListOf<String>()
            if (c.text) caps.add(text("Text / multi-turn","文字 / 多轮"))
            if (c.images) caps.add(text("Images","图片"))
            if (c.files) caps.add(text("Files","文件"))
            card.addView(label(text("Candidate: ","候选能力：")+caps.joinToString(" · "),12f))
            if(provider.id=="gemini") card.addView(label(provider.note,12f))
            if (provider.manualOnly) card.addView(label(text("Manual access only; not an automated provider","仅手动访问，不开放自动化"),12f))
            ProviderUi.gap(card,12)
            ProviderUi.actions(card,listOf(
                (if(selected) text("Back to chat","回到对话") else text("Use in chat","选择使用")) to { if(selected) currentChat()?.let { launchChat(it) } else choose(provider,false) },
                text("Sign in / webpage","登录 / 原网页") to { original(provider) }))
            list.addView(card)
        }
        if(count==0) list.addView(label(text("No matching services","没有匹配的服务"),15f))
    }
    private fun acknowledge(provider: WebProviderDefinition, action: () -> Unit) {
        if (prefs.acknowledged(provider.id)) { action(); return }
        AlertDialog.Builder(this).setTitle(provider.name + " · Experimental Web Provider")
            .setMessage(WebStrings.risk(this) + if (provider.note.isNotBlank()) "\n\n" + provider.note else "")
            .setPositiveButton(text("I understand","我了解并选择使用")) { _, _ -> prefs.acknowledge(provider.id); action() }
            .setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun choose(provider: WebProviderDefinition, default: Boolean) {
        if (AgentBus.isBusy()) { toast("WEB_PROVIDER_BUSY"); return }
        if (!provider.configured || provider.manualOnly) { toast(provider.note); return }
        acknowledge(provider) {
            prefs.setEnabled(true); prefs.disable(provider.id,false); prefs.markRecent(provider.id)
            if (default) { prefs.select(provider.id); toast(text("Default saved for new chats","已设为新对话的默认实验模型")); return@acknowledge }
            val chat = currentChat() ?: ChatStore(this).create()
            val saved = chat.webBindings[provider.id]?.takeIf { it.profileName == prefs.profile(provider.id) }
            val binding = saved ?: WebConversationBinding(provider.id,prefs.profile(provider.id))
            val history = CheckBox(this).apply {
                this.text = text("Include the last eight local turns on the first send to this service","首次发送时包含最近 8 条本地历史")
                isChecked = false
            }
            fun apply() {
                chat.webBinding?.let { chat.webBindings[it.providerId] = it }
                binding.includeHistory = history.isChecked
                chat.webBinding = binding; chat.webBindings[provider.id] = binding
                ChatStore(this).saveNow(chat); Prefs(this).setLastChatId(chat.id)
                dev.wind.agent.screen.ScreenShareSession.chat = chat
                startActivity(Intent(this,MainActivity::class.java).putExtra("floatingChatId",chat.id)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                WebProviderBrowserActivity.open(this, provider.id, chat.id); finish()
            }
            if (saved == null && chat.messages.isNotEmpty()) {
                AlertDialog.Builder(this).setTitle(text("Switch website provider","切换网页服务"))
                    .setMessage(text("A separate third-party conversation will be used. History is sent only if selected below.","将使用独立的第三方网页会话；仅在下方选中时发送本地历史。"))
                    .setView(history).setPositiveButton(text("Use","使用")) { _, _ -> apply() }.setNegativeButton(android.R.string.cancel,null).show()
            } else apply()
        }
    }
    private fun switchToApi() {
        if (AgentBus.isBusy()) { toast("WEB_PROVIDER_BUSY"); return }
        val chat = currentChat()
        if (chat == null) { prefs.select(""); finish(); return }
        AlertDialog.Builder(this).setTitle(text("Use official API","切换正式 API"))
            .setMessage(text("Future API requests can include this chat's local history. The website mapping remains available separately.",
                "后续 API 请求可携带此对话的本地历史。原网页会话映射会单独保留。"))
            .setPositiveButton(text("Switch","切换")) { _, _ ->
                chat.webBinding?.let { chat.webBindings[it.providerId] = it }; chat.webBinding = null
                ChatStore(this).saveNow(chat); dev.wind.agent.screen.ScreenShareSession.chat = chat
                finish()
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun original(provider: WebProviderDefinition) {
        if (!provider.configured) { toast(provider.note); return }
        acknowledge(provider) {
            try { WebProviderBrowserActivity.open(this,provider.id,chatId) } catch (_: Exception) { toast("WEBVIEW_UNAVAILABLE") }
        }
    }
    private fun clear(provider: WebProviderDefinition) {
        if (AgentBus.isBusy()) { toast(text("Stop the current request first","请先停止当前请求")); return }
        AlertDialog.Builder(this).setTitle(provider.name).setMessage(text("Clear this provider's cookies, cache, site storage and webpage mappings? Local chat text is retained.",
            "清除此服务的 Cookie、缓存、站点数据和网页会话映射？本地聊天正文会保留。"))
            .setPositiveButton(text("Clear","清理")) { _, _ ->
                try { BrowserSessionManager.get(this).clearProvider(provider.id) { complete ->
                    toast(if (complete) text("Provider data cleared","服务数据已清理") else
                        if (prefs.status(provider.id) == "COMPATIBILITY_CLEANUP_REQUIRES_ALL") WebStrings.state(this,"COMPATIBILITY_CLEANUP_REQUIRES_ALL")
                        else text("Close and reopen the app to finish site-data cleanup","请关闭并重新打开应用，以完成剩余站点数据清理"))
                    renderProviders()
                } } catch (_: Throwable) { toast("WEB_DATA_CLEANUP_FAILED") }
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun clearAll() {
        if (AgentBus.isBusy()) { toast(text("Stop the current request first","请先停止当前请求")); return }
        AlertDialog.Builder(this).setMessage(text("Disable automation and clear all webpage-provider data and mappings? API keys and local chat text are retained.",
            "关闭网页自动化并清理全部网页服务数据和映射？API 密钥和本地聊天正文会保留。"))
            .setPositiveButton(text("Clear all","全部清理")) { _, _ ->
                prefs.setEnabled(false)
                try { BrowserSessionManager.get(this).clearAll { complete ->
                    toast(if (complete) text("All web data cleared","全部网页数据已清理") else text("Cleanup not completed","网页数据清理未完成")); renderProviders()
                } } catch (_: Throwable) { toast("WEB_DATA_CLEANUP_FAILED") }
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun privacy() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,8,16,8) }
        val entries = listOf("screen" to text("Screenshots","屏幕截图"), "images" to text("Images","图片"), "files" to text("Files","文件"),
            "selected_text" to text("Selected text","选中文字"), "clipboard" to text("Clipboard","剪贴板"), "screen_text" to text("Visible screen text","屏幕文字"),
            "search" to text("Search results","搜索结果"), "mcp" to text("MCP results","MCP 结果"), "file_text" to text("Quoted file text","文件文字"), "history" to text("Local history","本地历史"))
        entries.forEach { (key,title) ->
            val box = CheckBox(this).apply { this.text = title; isChecked = prefs.allow(key) }
            box.setOnCheckedChangeListener { _,checked -> prefs.allow(key,checked) }; panel.addView(box)
        }
        val confirm = CheckBox(this).apply { this.text = text("Confirm sensitive context before sending","敏感上下文发送前确认"); isChecked = prefs.confirmSensitive() }
        confirm.setOnCheckedChangeListener { _,checked -> prefs.confirmSensitive(checked) }; panel.addView(confirm)
        panel.addView(label(text("Unchecked categories require per-send approval. No clipboard or other-app text is read automatically.",
            "未勾选类别每次发送都需确认。不会自动读取剪贴板或其他 App 文字。"),12f))
        val limit = EditText(this).apply { hint = text("Context characters: 4000–80000","上下文字符上限：4000–80000"); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(prefs.contextLimit().toString()) }
        panel.addView(limit)
        row(panel, text("Add context","添加上下文") to { addContext() }, text("Clipboard","读取剪贴板") to { clipboard() }, text("Clear pending","清空待发上下文") to {
            chatId?.let { WebContextInbox.clear(it) }; toast(text("Pending context cleared","待发上下文已清空")) })
        val scroll = ScrollView(this); scroll.addView(panel)
        AlertDialog.Builder(this).setTitle(text("Web context privacy","网页上下文隐私"))
            .setView(scroll).setPositiveButton(android.R.string.ok) { _, _ -> limit.text.toString().toIntOrNull()?.let { prefs.contextLimit(it) } }.show()
    }
    private fun addContext() {
        val id = chatId ?: return toast(text("Open a chat first","请先打开一个对话"))
        val kinds = arrayOf("selected_text","screen_text","search","mcp","file_text")
        AlertDialog.Builder(this).setTitle(text("Already acquired context","已取得的上下文"))
            .setItems(kinds) { _,index ->
                val field = EditText(this).apply { minLines = 4; hint = text("Paste authorized text/results","粘贴已获授权的文字或结果") }
                AlertDialog.Builder(this).setTitle(kinds[index]).setView(field).setPositiveButton(text("Add","加入待发送内容")) { _, _ ->
                    val content = field.text.toString()
                    if (content.isNotBlank()) try { WebContextInbox.add(id,WebContextItem(kinds[index],kinds[index],content.take(24000))); toast(text("Context added","上下文已加入")) }
                    catch (_: Exception) { toast("CONTEXT_LIMIT_EXCEEDED") }
                }.setNegativeButton(android.R.string.cancel,null).show()
            }.show()
    }
    private fun clipboard() {
        val id = chatId ?: return toast(text("Open a chat first","请先打开一个对话"))
        AlertDialog.Builder(this).setMessage(text("Read the current clipboard and add it to this chat's next send?","读取当前剪贴板并加入此对话的下一次发送？"))
            .setPositiveButton(text("Read","读取")) { _, _ ->
                try {
                    val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return@setPositiveButton
                    if (clip.itemCount == 0) return@setPositiveButton
                    val item = clip.getItemAt(0); val uri = item.uri
                    if (uri != null && contentResolver.getType(uri)?.startsWith("image/") == true) {
                        WebContextInbox.addImage(id,dev.wind.agent.Message.Attachment("clipboard-image",uri.toString(),contentResolver.getType(uri),0L,"image"))
                    } else {
                        val content = item.text?.toString() ?: return@setPositiveButton
                        WebContextInbox.add(id,WebContextItem("clipboard","clipboard",content.take(24000)))
                    }
                    toast(text("Clipboard content added","剪贴板内容已加入"))
                } catch (_: Exception) { toast("CLIPBOARD_UNAVAILABLE") }
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun conversations() {
        val store = ChatStore(this)
        val chats = store.list().filter { it.webBinding != null || it.webBindings.isNotEmpty() }
        val names = chats.map { (if (it.archived) "[归档] " else "") + it.title.ifBlank { it.id } }.toTypedArray()
        AlertDialog.Builder(this).setTitle(text("Web conversations","网页对话"))
            .setItems(names) { _,index ->
                val chat = chats[index]
                val actions = arrayOf(text("Open","打开"),text("Rename","重命名"),if (chat.archived) text("Unarchive","取消归档") else text("Archive","归档"),text("Delete local chat","删除本地对话"),text("New chat","新建网页对话"))
                AlertDialog.Builder(this).setTitle(chat.title).setItems(actions) { _,action ->
                    if (AgentBus.isBusy()) { toast("WEB_PROVIDER_BUSY"); return@setItems }
                    when (action) {
                        0 -> { Prefs(this).setLastChatId(chat.id); launchChat(chat) }
                        1 -> {
                            val name = EditText(this).apply { setText(chat.title) }
                            AlertDialog.Builder(this).setView(name).setPositiveButton(android.R.string.ok) { _, _ -> chat.title = name.text.toString().take(200); store.saveNow(chat) }.show()
                        }
                        2 -> { chat.archived = !chat.archived; store.saveNow(chat) }
                        3 -> AlertDialog.Builder(this).setMessage(text("Delete local history? The third-party conversation is retained.","删除本地历史？第三方网页会话会保留。"))
                            .setPositiveButton(text("Delete","删除")) { _, _ -> store.delete(chat.id); WebProviderStore(this).clearChat(chat.id); WebContextInbox.clear(chat.id) }
                            .setNegativeButton(android.R.string.cancel,null).show()
                        4 -> {
                            val newChat = store.create()
                            chat.webBinding?.let { newChat.webBinding = WebConversationBinding(it.providerId,prefs.profile(it.providerId)) }
                            store.saveNow(newChat); launchChat(newChat)
                        }
                    }
                }.show()
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun editQuestion() {
        if (AgentBus.isBusy()) { toast("WEB_PROVIDER_BUSY"); return }
        val chat = currentChat() ?: return
        if (chat.webBinding == null) return
        val latest = synchronized(chat.messages) { chat.messages.lastOrNull { it.role == "user" } } ?: return
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val edit = EditText(this).apply { setText(latest.content); minLines = 3 }
        val history = CheckBox(this).apply { this.text = text("Include earlier local history","包含此前本地历史"); isChecked = false }
        panel.addView(edit); panel.addView(history)
        AlertDialog.Builder(this).setTitle(text("Edit into a new conversation branch","修改并新建对话分支"))
            .setMessage(text("The original webpage and answers are retained. Attachments must be selected again.","原网页和原回答会保留。附件需要重新选择。"))
            .setView(panel).setPositiveButton(text("Send edited question","重新提问")) { _, _ ->
                try { WebChatActions.editBranch(this,chat,edit.text.toString(),history.isChecked); finish() }
                catch (_: Exception) { toast("EDIT_BRANCH_UNAVAILABLE") }
            }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun currentChat(): Chat? = chatId?.let { id ->
        AgentBus.liveChat?.takeIf { it.id == id } ?: dev.wind.agent.screen.ScreenShareSession.chat?.takeIf { it.id == id } ?: ChatStore(this).load(id)
    }
    private fun launchChat(chat: Chat) {
        Prefs(this).setLastChatId(chat.id)
        startActivity(Intent(this,MainActivity::class.java).putExtra("floatingChatId",chat.id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
    }
    private fun label(value: String, size: Float): TextView = ProviderUi.text(this,value,size,size<=12f).apply { setPadding(0,ProviderUi.dp(this@WebProviderActivity,4),0,ProviderUi.dp(this@WebProviderActivity,4)) }
    private fun row(parent: LinearLayout, vararg entries: Pair<String, () -> Unit>) {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = true }
        val line = LinearLayout(this)
        entries.forEach { (title,action) -> line.addView(Ui.pillButton(this,title,null,Ui.SECONDARY) { action() }) }
        scroll.addView(line); parent.addView(scroll)
    }
    private fun toast(value: String) { Toast.makeText(this,value,Toast.LENGTH_LONG).show() }
    override fun onDestroy() { main.removeCallbacks(refresh); super.onDestroy() }
    companion object {
        fun open(context: Context, chatId: String? = null) {
            val intent = Intent(context,WebProviderActivity::class.java).putExtra("chatId",chatId)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
