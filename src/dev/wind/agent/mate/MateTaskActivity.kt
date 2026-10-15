package dev.wind.agent.mate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import dev.wind.agent.Theme
import dev.wind.agent.Ui
import java.util.concurrent.Executors

/** User-owned controls over exact tasks/resources, independent of the selected AI run. */
class MateTaskActivity: Activity() {
    private val io=Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private val main=Handler(Looper.getMainLooper())
    private var reading=false
    private var visibleTasks: List<MateTask>?=null
    private val poll=object: Runnable {
        override fun run() {if(!isFinishing && !isDestroyed){refresh();main.postDelayed(this,1000)}}
    }
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved);Theme.init(this);Theme.applyFromPrefs(this,dev.wind.agent.Prefs(this))
        setTheme(if(Theme.DARK)dev.wind.agent.R.style.AppTheme else dev.wind.agent.R.style.AppThemeLight)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Theme.BG)}
        Ui.applyWindowInsets(root,16,0,16,8)
        dev.wind.agent.modelprovider.ProviderUi.header(this,root,"生活任务","进度、接管与恢复","close") {finish()}
        root.addView(TextView(this).apply {text="持续目标在托管启动后继续保留。恢复先核对页面和授权；结果未知的操作不会自动重试。";setTextColor(Theme.TEXT_MUTED);textSize=13f})
        root.addView(Button(this).apply {text="刷新任务进度";setOnClickListener {refresh()}})
        content=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply {addView(content)},LinearLayout.LayoutParams(-1,0,1f));setContentView(root);refresh()
    }
    private fun refresh() {
        if(reading || isFinishing || isDestroyed)return
        reading=true
        io.execute {try {
            val tasks=(MateTaskCoordinator.store(this).list()+MateTaskCoordinator.store(this,true).list()).sortedByDescending {it.updatedAt}.take(100)
            runOnUiThread {if(!isFinishing) {
                reading=false
                if(tasks==visibleTasks)return@runOnUiThread
                visibleTasks=tasks
                content.removeAllViews()
                if(tasks.isEmpty())content.addView(TextView(this).apply {text="暂无生活任务";setTextColor(Theme.TEXT)})
                for(task in tasks)content.addView(Button(this).apply {
                    text="${stateLabel(task.state)} · ${task.goal.take(80)}\n${task.progress.take(120)}"
                    setOnClickListener {detail(task)}
                })
            }}
        } catch(e: Throwable) {runOnUiThread {reading=false;if(!isFinishing)Toast.makeText(this,e.message ?: "任务读取失败",Toast.LENGTH_LONG).show()}} }
    }
    private fun detail(task: MateTask) {
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        box.addView(TextView(this).apply {text="${task.goal}\n状态：${stateLabel(task.state)}\n进度：${task.progress}\n结果：${task.outcome}\n${if(task.needsObservation)"继续前需要重新观察页面" else "最近一轮已取得页面观察"}";setTextColor(Theme.TEXT);setTextIsSelectable(true)})
        box.addView(Button(this).apply {text="打开原会话";setOnClickListener {startActivity(Intent(this@MateTaskActivity,dev.wind.agent.MainActivity::class.java).putExtra("floatingChatId",task.chatId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}})
        box.addView(Button(this).apply {text="查看步骤证据";setOnClickListener {
            AlertDialog.Builder(this@MateTaskActivity).setTitle("实际工具证据").setMessage(task.evidence.joinToString("\n\n") {"${it.tool} · ${it.status}\n${java.util.Date(it.observedAt)}\n${it.quote}"}.ifBlank {"尚无工具结果；本轮文本结果见原会话。"}).setPositiveButton("关闭",null).show()
        }})
        for(resource in task.resources.filter {it.kind in setOf("guardian","browser")})box.addView(Button(this).apply {
            text=if(resource.kind=="guardian")"打开对应托管" else "接管原浏览器"
            setOnClickListener {if(resource.kind=="guardian")startActivity(Intent(this@MateTaskActivity,dev.wind.agent.phone.ChatGuardianActivity::class.java).putExtra("session_id",resource.id))
                else dev.wind.agent.browser.BrowserActivity.open(this@MateTaskActivity,task.chatId,resource.id)}
        })
        for(evidence in task.evidence.filter {it.tool=="mate_document"}) {
            val uri=Regex("content_uri=(content://[^; ]+)").find(evidence.quote)?.groupValues?.get(1) ?: continue
            val mime=Regex("mime=([^; ]+)").find(evidence.quote)?.groupValues?.get(1) ?: "text/plain"
            box.addView(Button(this).apply {text="打开已生成的文件";setOnClickListener {
                try {startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(uri),mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
                catch(e: Exception){Toast.makeText(this@MateTaskActivity,"文件在 Downloads/HarnessMate，可从系统文件应用打开",Toast.LENGTH_LONG).show()}
            }})
        }
        if(!task.terminal) {
            if(task.state==MateTaskState.UNKNOWN && task.resources.none {it.kind=="browser" && it.state!="RELEASED"})box.addView(Button(this).apply {
                text="我已在原 App 核对旧动作";setOnClickListener {
                    AlertDialog.Builder(this@MateTaskActivity).setTitle("确认已核对旧动作？").setMessage("先检查原 App 或历史记录。此确认只解除生活任务暂停；旧的未知请求仍被记录，不会重发或成为付款、发送批准。恢复后会重新观察。")
                        .setPositiveButton("已核对，不重放") {_,_->MateTaskCoordinator.reviewPhoneUnknownByUser(this@MateTaskActivity,task.id,task.revision)}.setNegativeButton("取消",null).show()
                }
            })
            box.addView(Button(this).apply {text="恢复生活任务";isEnabled=task.state!=MateTaskState.UNKNOWN;setOnClickListener {MateTaskCoordinator.resumeByUser(this@MateTaskActivity,task.id);Toast.makeText(this@MateTaskActivity,"已安排继续；先核对页面与授权",Toast.LENGTH_SHORT).show()}})
            box.addView(Button(this).apply {text="暂停此任务";setOnClickListener {MateTaskCoordinator.pauseByUser(this@MateTaskActivity,task.id);Toast.makeText(this@MateTaskActivity,"已请求暂停此任务",Toast.LENGTH_SHORT).show()}})
            box.addView(Button(this).apply {text="停止此任务";setOnClickListener {
                AlertDialog.Builder(this@MateTaskActivity).setTitle("停止此生活任务？").setMessage("只停止此任务和它持有的托管。保留已取得的进度和结果记录。")
                    .setPositiveButton("停止") {_,_->MateTaskCoordinator.pauseByUser(this@MateTaskActivity,task.id,true)}.setNegativeButton("取消",null).show()
            }})
        }
        AlertDialog.Builder(this).setTitle("生活任务详情").setView(ScrollView(this).apply {addView(box)}).setPositiveButton("关闭",null).show()
    }
    override fun onResume(){super.onResume();main.removeCallbacks(poll);main.post(poll)}
    override fun onPause(){main.removeCallbacks(poll);super.onPause()}
    override fun onDestroy(){main.removeCallbacks(poll);io.shutdownNow();super.onDestroy()}
    companion object {
        fun stateLabel(state: MateTaskState)=when(state) {MateTaskState.CREATED->"待准备";MateTaskState.PREPARING->"准备中";MateTaskState.RUNNING->"执行中";MateTaskState.WAITING_FOR_EVENT->"等待新事件";MateTaskState.WAITING_FOR_USER->"等待你处理";MateTaskState.PAUSED->"已暂停";MateTaskState.NEEDS_REAUTHORIZE->"需要重新授权";MateTaskState.UNKNOWN->"结果待核对";MateTaskState.COMPLETED->"已交付";MateTaskState.FAILED->"执行失败";MateTaskState.CANCELLED->"已停止"}
    }
}
