package dev.wind.agent.modelprovider

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.wind.agent.*
import org.json.JSONObject

/** Multiple independent credentials; selecting a model never rewrites another provider's account. */
class ModelProvidersActivity : Activity() {
    private lateinit var store: ModelProviderStore
    private lateinit var panel: LinearLayout
    private var token: CancellationToken?=null
    private var attempt: SubscriptionOAuth.Attempt?=null
    private var working=false
    private var chatId: String?=null
    private var category=0
    private var query=""
    private var accounts: LinearLayout?=null
    private var titleAnchor: View?=null
    private var activeName:TextView?=null
    private var activeDetails:TextView?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.init(this); Theme.applyFromPrefs(this,Prefs(this))
        setTheme(if(Theme.DARK) R.style.AppTheme else R.style.AppThemeLight)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store=ModelProviderStore(this); store.migrateLegacy(this); chatId=intent.getStringExtra("chatId")
        category=(savedInstanceState?.getInt("category") ?: intent.getIntExtra("category",0)).coerceIn(0,1)
        query=savedInstanceState?.getString("query") ?: ""
        render()
        if(savedInstanceState?.getBoolean("connecting")==true) toast("如登录中断，请重新开始登录")
    }
    private fun column()=ProviderUi.column(this,16)
    private fun label(s:String,size:Float=15f)=ProviderUi.text(this,s,size,size<=12f).apply { setPadding(0,ProviderUi.dp(this@ModelProvidersActivity,6),0,ProviderUi.dp(this@ModelProvidersActivity,6)) }
    private fun button(parent:LinearLayout,s:String,action:()->Unit) {
        parent.addView(Ui.pillButton(this,s,null,Ui.SECONDARY) { action() })
    }
    private fun render() {
        if(isFinishing || isDestroyed) return
        panel=ProviderUi.column(this); panel.setBackgroundColor(Theme.BG)
        Ui.applyWindowInsets(panel,18,0,18,12)
        ProviderUi.header(this,panel,"模型连接","管理 API 密钥与订阅账户") { if(ready()) choosePreset() }
        ProviderUi.tabs(panel,listOf("API 账户","订阅账户","网页模型"),category) { i ->
            if(i==2) dev.wind.agent.webprovider.WebProviderActivity.open(this,chatId) else { category=i; query=""; render() }
        }
        val body=ProviderUi.column(this)
        val scroll=ScrollView(this).apply { isFillViewport=true; addView(body); isVerticalScrollBarEnabled=false }
        panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val active=store.active()
        val current=ProviderUi.card(this)
        current.addView(label("当前使用",12f))
        activeName=label(active?.selected()?.name?.takeIf { it.isNotBlank() } ?: "选择你的第一个模型",19f).also { current.addView(it) }
        activeDetails=label(active?.let { "${it.label} · ${ReasoningCapabilities.label(it.effort)}" } ?: "添加密钥或登录订阅账户，应用会自动读取模型。",12f).also { current.addView(it) }
        ProviderUi.gap(current,8)
        val quick=Ui.pillButton(this,"切换模型与推理强度","cpu",Ui.SECONDARY) {
            if(ready()) dev.wind.agent.modelprovider.ComposerModelMenu.show(this,titleAnchor ?: panel,currentChat()) { renderAccounts() }
        }
        titleAnchor=quick; current.addView(quick); body.addView(current)
        body.addView(label(if(category==0) "API 账户 · 同一供应商可以添加多个账户" else "订阅账户 · 在系统浏览器完成授权",12f))
        val search=ProviderUi.search(this,"搜索账户或供应商",query); body.addView(search); ProviderUi.gap(body)
        search.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { query=s?.toString() ?: ""; renderAccounts() }
            override fun afterTextChanged(s:Editable?) {}
        })
        if(working) {
            val progress=ProviderUi.card(this)
            progress.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate=true })
            progress.addView(label("正在连接 · 等待服务或浏览器授权",14f))
            button(progress,"取消连接") { token?.cancel(); attempt?.close() }; body.addView(progress)
        }
        accounts=ProviderUi.column(this); body.addView(accounts); renderAccounts(); setContentView(panel)
    }
    private fun currentChat():Chat?=chatId?.let { id -> AgentBus.liveChat?.takeIf { it.id==id }
        ?: dev.wind.agent.screen.ScreenShareSession.chat?.takeIf { it.id==id } ?: ChatStore(this).load(id) }
    private fun renderAccounts() {
        val list=accounts ?: return; list.removeAllViews()
        val active=store.active()
        val website=currentChat()?.webBinding?.let { dev.wind.agent.webprovider.WebProviderRegistry.get(it.providerId) }
        activeName?.text=website?.name ?: active?.selected()?.name?.takeIf { it.isNotBlank() } ?: "选择你的第一个模型"
        activeDetails?.text=if(website!=null) "当前对话使用实验性网页模型，可直接切换到 API / 订阅账户。" else
            active?.let { "${it.label} · ${ReasoningCapabilities.label(it.effort)}" } ?: "添加密钥或登录订阅账户，应用会自动读取模型。"
        val filtered=store.all().filter { it.subscription==(category==1) && (query.isBlank() || (it.label+" "+it.preset+" "+it.email).contains(query,true)) }
        if(filtered.isEmpty()) {
            val empty=ProviderUi.card(this)
            empty.addView(label(if(query.isNotBlank()) "没有匹配的账户" else if(category==0) "连接常用 AI 供应商" else "使用已有订阅",18f))
            empty.addView(label(if(category==0) "支持 OpenAI、DeepSeek、Kimi、GLM 等。预置服务只需填写 API Key。" else "ChatGPT 使用官方授权；Google 使用 Antigravity 兼容接入，权限以账号返回为准。",12f))
            ProviderUi.gap(empty,10); ProviderUi.actions(empty,listOf("添加账户" to { if(ready()) choosePreset() })); list.addView(empty)
        }
        for(p in filtered) {
            val card=ProviderUi.card(this,p.id==active?.id && website==null)
            val hasSecret=store.secret(p.id).optString(if(p.subscription) "access_token" else "api_key").isNotBlank()
            ProviderUi.identity(card,p.label,p.email.ifBlank { ProviderPresets.get(p.preset)?.name ?: p.preset },
                if(!hasSecret) if(p.subscription) "待登录" else "待配置" else if(p.id==active?.id) if(website==null) "当前使用" else "API 默认" else if(p.models.isEmpty()) "待读取" else "已连接") { anchor ->
                ProviderUi.menu(anchor,buildList {
                    add("重新读取模型" to { if(ready()) discover(p.id) })
                    add("编辑账户" to { if(ready()) edit(p) })
                    add("手动添加模型 ID" to { if(ready()) manualModel(p) })
                    if(p.subscription) add("退出订阅账户" to { if(ready()) signOut(p) })
                    add("移除此账户" to { if(ready()) AlertDialog.Builder(this@ModelProvidersActivity).setMessage("移除 ${p.label}？仅清除本地配置和凭据；订阅账户可以先退出以撤销授权。")
                        .setPositiveButton("移除") { _,_-> store.delete(p.id); render() }.setNegativeButton("取消",null).show() })
                })
            }
            ProviderUi.gap(card,12)
            card.addView(label(p.selected().name.takeIf { p.model.isNotBlank() } ?: "尚未选择模型",15f))
            card.addView(label("${p.models.size} 个模型 · ${ReasoningCapabilities.label(p.effort)}",12f))
            if(p.status.isNotBlank()) card.addView(label(p.status,12f))
            if(!store.protected(p.id) && store.secret(p.id).length()>0) card.addView(label("设备密钥库不可用：凭据仅保存在内存，重启后需重新填写或登录。",12f))
            ProviderUi.gap(card,12)
            ProviderUi.actions(card,if(p.subscription && !hasSecret) listOf("登录账户" to { if(ready()) login(p) }) else
                listOf("选择模型" to { if(ready()) models(p.id) }, (if(p.subscription) "重新授权" else "编辑密钥") to { if(ready()) { if(p.subscription) login(p) else edit(p) } }))
            list.addView(card)
        }
    }
    private fun ready():Boolean {
        if(working) { toast("请先完成或取消当前连接操作"); return false }
        if(AgentBus.isBusy()) { toast("请先停止当前任务，再切换或修改模型配置"); return false }
        return true
    }
    private fun choosePreset() {
        val content=ProviderUi.column(this)
        lateinit var sheet:android.app.Dialog
        fun choose(preset:ProviderPreset) {
            if(!ready()) return
            sheet.dismiss()
            val p=ModelProvider(preset=preset.id,label=preset.name,url=preset.url,protocol=preset.protocol)
            category=if(p.subscription) 1 else 0
            if(p.subscription) login(p) else edit(p,true)
        }
        content.addView(label("使用 API Key",13f))
        ProviderPresets.all.filter { it.id !in setOf("chatgpt","antigravity","custom") }.chunked(2).forEach { presets ->
            ProviderUi.actions(content,presets.map { p -> p.name to { choose(p) } },false); ProviderUi.gap(content,8)
        }
        ProviderUi.gap(content)
        content.addView(label("使用订阅账户",13f))
        listOf("chatgpt","antigravity").forEach { id ->
            val p=ProviderPresets.get(id)!!; val card=ProviderUi.card(this)
            card.addView(label(if(id=="chatgpt") "ChatGPT 订阅" else "Google / Antigravity",16f))
            card.addView(label(if(id=="chatgpt") "在系统浏览器通过 OpenAI 官方授权登录，模型和额度由你的账户决定。" else "Google 账户授权的兼容接入。AI Pro 不保证 Antigravity 权限，可用性以账号返回为准。",12f))
            ProviderUi.gap(card,8); ProviderUi.actions(card,listOf("登录并连接" to { choose(p) })); content.addView(card)
        }
        ProviderUi.actions(content,listOf("自定义兼容接口" to { choose(ProviderPresets.get("custom")!!) }))
        sheet=ProviderUi.sheet(this,"添加模型连接","选择供应商 · 填写密钥或授权登录 · 自动读取模型",content); sheet.show()
    }
    private fun input(parent:LinearLayout,hintText:String,value:String="",secret:Boolean=false):EditText {
        val field=EditText(this).apply { hint=hintText; setText(value); setTextColor(Theme.TEXT); setHintTextColor(Theme.TEXT_MUTED); isSingleLine=true
            textSize=14f; setPadding(ProviderUi.dp(this@ModelProvidersActivity,12),ProviderUi.dp(this@ModelProvidersActivity,12),ProviderUi.dp(this@ModelProvidersActivity,12),ProviderUi.dp(this@ModelProvidersActivity,12))
            background=Theme.roundStroke(Theme.SURFACE_2,Theme.BORDER,12f,1,this@ModelProvidersActivity)
            setOnFocusChangeListener { _,focused -> background=Theme.roundStroke(Theme.SURFACE_2,if(focused) Theme.BORDER_HI else Theme.BORDER,12f,1,this@ModelProvidersActivity) }
            if(secret) inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        parent.addView(label(hintText,12f)); parent.addView(field,LinearLayout.LayoutParams(-1,ProviderUi.dp(this,48))); ProviderUi.gap(parent,8); return field
    }
    private fun edit(p:ModelProvider,new:Boolean=false) {
        val fields=column(); val name=input(fields,"账户名称",p.label)
        if(p.subscription) {
            AlertDialog.Builder(this).setTitle("账户名称").setView(fields).setPositiveButton("保存") { _,_-> store.put(p.copy(label=name.text.toString().trim().ifBlank { p.label })); render() }.setNegativeButton("取消",null).show(); return
        }
        val url=if(p.preset=="custom") input(fields,"Base URL",p.url) else null
        val protocols=listOf("openai","anthropic","gemini","responses")
        val protocol=if(p.preset=="custom") Spinner(this).also { spinner ->
            spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("OpenAI Chat Completions","Anthropic Messages","Gemini 原生","OpenAI Responses"))
            spinner.setSelection(protocols.indexOf(p.protocol).coerceAtLeast(0)); fields.addView(spinner)
        } else null
        fields.addView(label(if(new) "添加后会自动读取模型列表。URL 已由应用填写。" else "密钥留空保留原值。",12f))
        val key=input(fields,if(new) "API Key" else "新 API Key（可留空）",secret=true)
        val reveal=CheckBox(this).apply { text="显示密钥"; setTextColor(Theme.TEXT_MUTED) }
        reveal.setOnCheckedChangeListener { _,checked -> key.transformationMethod=if(checked) null else android.text.method.PasswordTransformationMethod.getInstance(); key.setSelection(key.text.length) }
        fields.addView(reveal)
        val error=label("",12f).apply { visibility=View.GONE }; fields.addView(error)
        lateinit var dialog:android.app.Dialog
        ProviderUi.gap(fields,12)
        ProviderUi.actions(fields,listOf("保存并读取模型" to save@{
            try {
                if(!ready()) return@save
                val raw=key.text.toString().trim(); check(!new || raw.isNotBlank()) { "请输入 API Key" }
                val base=url?.text?.toString()?.trim()?.trimEnd('/') ?: p.url
                NetworkPolicy.requireUserEndpoint(base); val uri=java.net.URI(base)
                check(uri.userInfo==null && uri.query==null && uri.fragment==null) { "Base URL 不应包含凭据、查询参数或片段" }
                val next=p.copy(label=name.text.toString().trim().ifBlank { p.label },url=base,protocol=protocol?.let { protocols[it.selectedItemPosition] } ?: p.protocol,
                    models=if(base!=p.url) emptyList() else p.models)
                if(raw.isNotBlank()) store.saveSecret(p.id,JSONObject().put("api_key",raw))
                store.put(next); dialog.dismiss(); discover(p.id,true)
            } catch(e:Exception) { error.text=ModelDiscovery.diagnostic(e); error.visibility=View.VISIBLE }
        }))
        dialog=ProviderUi.sheet(this,p.label,if(new) "填写 API Key 即可连接，接口和模型由应用读取。" else "修改密钥或账户名称，留空密钥会保留原值。",fields); dialog.show()
    }
    private fun work(action:(CancellationToken)->Unit,done:()->Unit={ render() }) {
        working=true; val cancellation=CancellationToken(); token=cancellation; render()
        Thread({
            var failure:String?=null
            try { action(cancellation) } catch(e:Exception) { failure=ModelDiscovery.diagnostic(e) }
            runOnUiThread {
                working=false; token=null; attempt=null
                if(!isFinishing && !isDestroyed) { done(); failure?.let { toast(it) } }
            }
        },"model-provider-connect").start()
    }
    private fun discover(id:String,choose:Boolean=false) {
        work({ t-> ModelDiscovery.refresh(store,id,t) }) { render(); if(choose) store.find(id)?.takeIf { it.models.isNotEmpty() }?.let { models(id) } }
    }
    private fun models(id:String) {
        val p=store.find(id) ?: return
        if(p.models.isEmpty()) { toast("尚未读到模型，请刷新；兼容接口也可手动添加模型 ID"); manualModel(p); return }
        ComposerModelMenu.show(this,titleAnchor ?: panel,currentChat(),initialQuery=p.label) { renderAccounts() }
    }
    private fun efforts(p:ModelProvider,m:DiscoveredModel) {
        AlertDialog.Builder(this).setTitle("${m.name} · 推理强度").setSingleChoiceItems(m.efforts.map { ReasoningCapabilities.label(it) }.toTypedArray(),
            m.efforts.indexOf(p.effort).coerceAtLeast(0)) { d,i ->
            if(ready()) { applySelection(p.id,m.id,m.efforts[i]); d.dismiss() }
        }.setNegativeButton("取消",null).show()
    }
    private fun manualModel(p:ModelProvider) {
        val root=column(); val id=input(root,"模型 ID")
        val dialog=AlertDialog.Builder(this).setTitle("手动添加兼容模型").setMessage("仅在服务未提供模型目录时使用。此 ID 的可用性将在实际请求时验证。")
            .setView(root).setPositiveButton("添加",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val model=id.text.toString().trim()
            if(model.isBlank()) { toast("请输入模型 ID"); return@setOnClickListener }
            if(!ready()) return@setOnClickListener
            val m=DiscoveredModel(model,efforts=ReasoningCapabilities.efforts(p.preset,model,p.protocol),protocol=p.protocol)
            val latest=store.find(p.id) ?: return@setOnClickListener
            store.put(latest.copy(models=(latest.models+listOf(m)).distinctBy { it.id })); dialog.dismiss(); efforts(store.find(p.id)!!,m)
        } }; dialog.show()
    }
    private fun applySelection(id:String,model:String,effort:String) {
        val change = {
            try {
                if(ready()) {
                    store.select(id,model,effort)
                    val chat=chatId?.let { if(AgentBus.liveChat?.id==it) AgentBus.liveChat else ChatStore(this).load(it) }
                    chat?.let { c -> c.webBinding?.let { c.webBindings[it.providerId]=it }; c.webBinding=null; ChatStore(this).saveNow(c); dev.wind.agent.screen.ScreenShareSession.chat=c }
                    dev.wind.agent.webprovider.WebProviderPrefs(this).select("")
                    toast("已选择 $model · ${ReasoningCapabilities.label(effort)}"); render()
                }
            } catch(e:Exception) { toast(ModelDiscovery.diagnostic(e)) }
        }
        val chat=chatId?.let { if(AgentBus.liveChat?.id==it) AgentBus.liveChat else ChatStore(this).load(it) }
        if(chat?.webBinding!=null) AlertDialog.Builder(this).setMessage("切换到 API 模型后，当前对话的本地历史会作为后续请求的上下文发送。继续？")
            .setPositiveButton("切换") { _,_-> change() }.setNegativeButton("取消",null).show() else change()
    }
    private fun login(p:ModelProvider) {
        try {
            if(store.find(p.id)==null) store.put(p)
            val a=SubscriptionOAuth.Attempt(p,store.hostId(),store.secret(p.id)); attempt=a
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(a.authorizeUrl())).addCategory(Intent.CATEGORY_BROWSABLE))
            work({ t->
                try { val callback=a.await(t); SubscriptionOAuth.finish(store,a,callback,t); ModelDiscovery.refresh(store,p.id,t) }
                finally { a.close() }
            }) { render(); store.find(p.id)?.takeIf { it.models.isNotEmpty() }?.let { models(p.id) } }
        } catch(e:Exception) { attempt?.close(); attempt=null; toast(ModelDiscovery.diagnostic(e)) }
    }
    private fun signOut(p:ModelProvider) {
        AlertDialog.Builder(this).setMessage("退出此订阅账户并尝试撤销远程授权？保留账户注册和模型目录，其他账户不受影响。")
            .setPositiveButton("退出") { _,_-> var revoked=false
                work({ t-> revoked=SubscriptionOAuth.signOut(store,p,t) }) { render(); toast(if(revoked) "已退出并撤销授权" else "已本地退出，远程撤销未确认；可到服务账号设置断开应用") }
            }.setNegativeButton("取消",null).show()
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onSaveInstanceState(out:Bundle) { out.putInt("category",category); out.putString("query",query); out.putBoolean("connecting",working); super.onSaveInstanceState(out) }
    override fun onDestroy() { token?.cancel(); attempt?.close(); super.onDestroy() }
    companion object {
        fun open(context:Context,chatId:String?=null,subscription:Boolean=false) { context.startActivity(Intent(context,ModelProvidersActivity::class.java).putExtra("chatId",chatId).putExtra("category",if(subscription) 1 else 0).addFlags(if(context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
