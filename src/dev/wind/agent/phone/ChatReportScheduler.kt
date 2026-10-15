package dev.wind.agent.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Native JobScheduler avoids adding an AndroidX runtime to the flat/offline build. Reports can be delayed. */
internal object ChatReportScheduler {
    const val JOB=7305
    fun schedule(c: Context) {
        val job=JobInfo.Builder(JOB,ComponentName(c,ChatReportJobService::class.java)).setPeriodic(15*60*1000L).build()
        check(c.getSystemService(JobScheduler::class.java).schedule(job)==JobScheduler.RESULT_SUCCESS) { "CHAT_REPORT_SCHEDULE_FAILED" }
    }
    fun report(c: Context,id: String): JSONObject {
        val store=ChatConversationStore.get(c); val s=store.session(id); val since=System.currentTimeMillis()-24*60*60*1000L
        val batches=store.list("batch",id,1000).filter { it.optLong("created_at")>=since }.reversed()
        val pending=store.list("approval",id,1000).filter { ChatApprovalCenter.valid(it,s,System.currentTimeMillis()) }
        return JSONObject().put("session_id",id).put("peer",s.getString("peer")).put("period_start",since).put("period_end",System.currentTimeMillis())
            .put("incoming_batches",batches.size).put("self_messages_observed",batches.count { it.optString("state")=="SELF_MESSAGE_OBSERVED" })
            .put("send_unknown",batches.count { it.optString("state")=="SEND_UNKNOWN" }).put("pending_count",pending.size)
            .put("items",JSONArray(batches.map { JSONObject().put("message_ref",it.getString("id")).put("summary",it.optString("summary",it.optString("incoming").take(600)))
                .put("state",it.getString("state")).put("reply",it.opt("reply") ?: JSONObject.NULL) }))
            .put("pending",JSONArray(pending)).put("recipient_delivery","UNKNOWN").put("summary_method","LOCAL_RECORDED_INCREMENTAL_SUMMARIES")
    }
    fun due(c: Context) {
        val store=ChatConversationStore.get(c); val now=Calendar.getInstance()
        val day="${now.get(Calendar.YEAR)}-${now.get(Calendar.DAY_OF_YEAR)}"
        for(s in store.list("session",limit=1000)) {
            val hour=s.optInt("report_hour",-1)
            if(s.optString("state")=="STOPPED" || hour<0 || now.get(Calendar.HOUR_OF_DAY)<hour || s.optString("last_report_day")==day) continue
            val id=s.getString("id")
            val report=report(c,id).put("id",java.util.UUID.randomUUID().toString()).put("state","GENERATED").put("created_at",System.currentTimeMillis())
            store.transaction { store.put("report",id,report); store.change(id) { it.put("last_report_day",day) } }
            val nm=c.getSystemService(NotificationManager::class.java)
            if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel("chat_guardian_reports","聊天托管日报",NotificationManager.IMPORTANCE_DEFAULT))
            val open=PendingIntent.getActivity(c,JOB,Intent(c,ChatGuardianActivity::class.java).putExtra("session_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b=if(Build.VERSION.SDK_INT>=26) Notification.Builder(c,"chat_guardian_reports") else Notification.Builder(c)
            nm.notify(JOB,b.setSmallIcon(dev.wind.agent.R.mipmap.ic_launcher).setContentTitle("Harness Mate · 聊天托管日报")
                .setContentText("本期新增 ${report.optInt("incoming_batches")} 批消息，待决 ${report.optInt("pending_count")} 项；点击核对记录")
                .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }
}
class ChatReportJobService: JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread({ try { ChatReportScheduler.due(this) } finally { jobFinished(params,false) } },"chat-guardian-report").start()
        return true
    }
    override fun onStopJob(params: JobParameters?)=true
}
