package dev.wind.agent.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.widget.*
import dev.wind.agent.Theme
import dev.wind.agent.Ui
import dev.wind.agent.webprovider.WebStrings
import org.json.JSONObject

/** Visible takeover of the exact worker WebView, with native one-action approval. */
class BrowserActivity : Activity() {
    private lateinit var manager: BrowserSessions
    private var session: BrowserSessions.Session? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var review: TextView
    private lateinit var approve: Button
    private lateinit var tabs: Spinner
    private var listedTabs = emptyList<String>()
    private var refreshing = false
    private var shownChooser = false
    private val update = object : Runnable { override fun run() { render(); if(!isFinishing)main.postDelayed(this,700) } }
    private fun text(zh:String,en:String)=WebStrings.t(this,en,zh)
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved); Theme.init(this); Theme.applyFromPrefs(this,dev.wind.agent.Prefs(this))
        setTheme(if(Theme.DARK)dev.wind.agent.R.style.AppTheme else dev.wind.agent.R.style.AppThemeLight)
        val chatId=intent.getStringExtra("chatId") ?: return finish()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Theme.BG)}
        Ui.applyWindowInsets(root,12,0,12,8)
        dev.wind.agent.modelprovider.ProviderUi.header(this,root,text("浏览器","Browser"),text("浏览和操作网页","Browse and act on webpages"),"close"){finish()}
        status=TextView(this).apply{setTextColor(Theme.TEXT_MUTED);textSize=12f;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END};root.addView(status)
        val row=LinearLayout(this)
        val url=EditText(this).apply{hint="https://";setSingleLine(true);textSize=14f;setTextColor(Theme.TEXT);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI}
        row.addView(url,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(Button(this).apply{text=text("打开","Open");setOnClickListener{try{session?.let{manager.humanNavigate(it,url.text.toString().trim())}}catch(e:Exception){toast(e.message)}}})
        root.addView(row)
        tabs=Spinner(this);root.addView(tabs)
        tabs.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long){if(!refreshing && position in listedTabs.indices)session?.let{s->if(s.activeTab!=listedTabs[position])manager.humanSwitch(s,listedTabs[position])}}
        }
        review=TextView(this).apply{setTextColor(Theme.TEXT);textSize=13f;setPadding(4,6,4,6)}
        root.addView(ScrollView(this).apply{addView(review)},LinearLayout.LayoutParams(-1,Theme.dp(this,120f)))
        val actions=LinearLayout(this)
        approve=Button(this).apply{text=text("允许这次操作","Allow this action");setOnClickListener{
            session?.let{s->AlertDialog.Builder(this@BrowserActivity).setTitle(text("确认操作","Confirm action")).setMessage(review.text)
                .setPositiveButton(text("允许一次并继续","Allow once and continue")){_,_->try{manager.resumeByUser(s,true);finish()}catch(e:Exception){toast(e.message)}}.setNegativeButton(android.R.string.cancel,null).show()}
        }};actions.addView(approve,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(Button(this).apply{text=text("交还 Agent","Return to Agent");setOnClickListener{session?.let{manager.resumeByUser(it)};finish()}},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(actions)
        val reconcile=Button(this).apply{text=text("核对未确认操作","Review uncertain action");setOnClickListener{
            val s=session ?: return@setOnClickListener
            AlertDialog.Builder(this@BrowserActivity).setMessage(text("请先在原网页或历史记录中核对操作结果。确认后仅解除本地暂停，不会重试旧操作。","Check the original page or history first. This clears the local pause and never repeats the old action."))
                .setPositiveButton(text("我已核对","Reviewed")){_,_->manager.reviewUnknownByUser(s);render()}.setNegativeButton(android.R.string.cancel,null).show()
        }};root.addView(reconcile)
        val container=FrameLayout(this);root.addView(container,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        try {
            manager=BrowserSessions.get(this)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(chatId))
            session=manager.findHuman(chatId,intent.getStringExtra("sessionId")) ?: manager.createHuman(chatId)
            manager.attach(session!!,this,container);main.post(update)
        }catch(e:Exception){status.text=e.message?.take(120);approve.isEnabled=false}
    }
    private fun render(){
        val s=session ?: return
        val data=manager.humanStatus(s)
        val list=s.tabs.values.toList();val ids=list.map{it.id}
        if(listedTabs!=ids){refreshing=true;listedTabs=ids;tabs.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,list.mapIndexed{i,t->"${i+1}. "+(t.view.title?.take(50) ?: text("新标签页","New tab"))});tabs.setSelection(ids.indexOf(s.activeTab).coerceAtLeast(0));refreshing=false}
        status.text=text("已由你接管 · ","You have control · ")+(if(s.isolated)text("独立登录会话","Separate login session") else text("兼容模式：同域登录可能共享","Compatibility: same-site logins may be shared"))+"\n"+BrowserProtocol.displayUrl(s.tabs[s.activeTab]?.view?.url)
        val pending=s.pending
        review.text=when{
            s.receipts.unresolved!=null -> text("上次操作结果未确认，请检查原网页后核对。","The last action is uncertain. Inspect the original page before reconciling.")
            pending!=null -> {
                val fields=pending.proof.optJSONArray("form_fields")
                text("Agent 请求：","Agent requests: ")+pending.proof.optString("name")+"\n"+(fields?.let{(0 until it.length()).joinToString("\n"){i->val field=it.getJSONObject(i);field.optString("name")+": "+field.optString("value")}} ?: "")+
                    pending.proof.optString("artifact_uri").takeIf{it.isNotEmpty()}?.let{"\n"+text("上传文件：","Upload: ")+pending.proof.optString("upload_name",it)}.orEmpty()
            }
            else -> text("你可以登录、完成验证或修改页面。关闭页面会保留接管；点击“交还 Agent”后自动操作才会继续。","Sign in, verify, or edit the page. Closing keeps control paused; use Return to Agent to release it.")
        }
        approve.visibility=if(pending!=null)android.view.View.VISIBLE else android.view.View.GONE
        if(s.chooser!=null && !shownChooser)chooseFiles(null)
    }
    fun chooseFiles(params:WebChromeClient.FileChooserParams?){
        if(shownChooser)return
        shownChooser=true
        try{val types=params?.acceptTypes?.filter{it.contains('/')} ?: emptyList()
            val picker=Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(types.singleOrNull() ?: "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,params?.mode==WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            if(types.size>1)picker.putExtra(Intent.EXTRA_MIME_TYPES,types.toTypedArray())
            startActivityForResult(picker,5301)
        }catch(e:Exception){shownChooser=false;session?.chooser?.onReceiveValue(null);session?.chooser=null;toast(e.message)}
    }
    override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data);if(request!=5301)return
        val s=session ?: return;val uris=mutableListOf<Uri>();if(result==RESULT_OK){data?.data?.let(uris::add);data?.clipData?.let{clip->for(i in 0 until minOf(clip.itemCount,8))uris.add(clip.getItemAt(i).uri)}}
        s.chooser?.onReceiveValue(uris.distinct().toTypedArray().takeIf{it.isNotEmpty()});s.chooser=null;shownChooser=false
    }
    override fun onDestroy(){main.removeCallbacks(update);session?.let{manager.detach(it,this)};super.onDestroy()}
    private fun toast(message:String?){Toast.makeText(this,message?.take(180) ?: text("操作失败","Action failed"),Toast.LENGTH_LONG).show()}
    companion object {
        private fun notificationId(chat:String)=chat.hashCode() xor 0x4b720000
        fun open(context:Context,chat:String,session:String?=null){
            val i=Intent(context,BrowserActivity::class.java).putExtra("chatId",chat).putExtra("sessionId",session)
            if(context !is Activity){
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Background Activity starts may be refused; retain an explicit user-start path.
                runCatching{
                    val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("browser_takeover","Browser takeover",NotificationManager.IMPORTANCE_DEFAULT))
                    val pending=PendingIntent.getActivity(context,notificationId(chat),i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val builder=if(Build.VERSION.SDK_INT>=26)Notification.Builder(context,"browser_takeover") else Notification.Builder(context)
                    manager.notify(notificationId(chat),builder.setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(WebStrings.t(context,"Browser needs your attention","浏览器需要你处理"))
                        .setContentText(WebStrings.t(context,"Open the original page to review or sign in","打开原页面进行核对或登录")).setContentIntent(pending).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
                }
            }
            runCatching{context.startActivity(i)}
        }
    }
}
