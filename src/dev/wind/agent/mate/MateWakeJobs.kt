package dev.wind.agent.mate

import android.app.job.*
import android.content.ComponentName
import android.content.Context

/** A single bounded native wake for the user's explicit check time; never a paid periodic poll. */
object MateWakeJobs {
    const val JOB_ID=7317
    fun schedule(c: Context) {
        val scheduler=c.getSystemService(JobScheduler::class.java)
        val due=MateTaskCoordinator.store(c).list().filter {it.state==MateTaskState.WAITING_FOR_EVENT && it.wakeAt>0 && it.executionId.isBlank()}.minOfOrNull {it.wakeAt}
        if(due==null){scheduler.cancel(JOB_ID);return}
        val job=JobInfo.Builder(JOB_ID,ComponentName(c,MateWakeJobService::class.java)).setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(maxOf(1000,due-System.currentTimeMillis())).build()
        check(scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS) {"MATE_WAKE_SCHEDULE_FAILED"}
    }
    fun claimDue(c: Context,now: Long=System.currentTimeMillis()): MateTask? {
        val repo=MateTaskCoordinator.store(c)
        val task=repo.list().filter {it.state==MateTaskState.WAITING_FOR_EVENT && it.wakeAt in 1..now && it.executionId.isBlank()}.minByOrNull {it.wakeAt} ?: return null
        return runCatching {repo.update(task.id,task.revision) {current ->
            check(current.state==MateTaskState.WAITING_FOR_EVENT && current.wakeAt in 1..now && current.executionId.isBlank())
            current.copy(wakeAt=0,state=MateTaskState.PREPARING,needsObservation=true,notes=(current.notes+"已到用户安排的检查时间；先核对真实状态").takeLast(32))
        }}.getOrNull()
    }
}
class MateWakeJobService: JobService() {
    @Volatile private var cancellation: dev.wind.agent.CancellationToken?=null
    override fun onStartJob(params: JobParameters?): Boolean {
        val token=dev.wind.agent.CancellationToken();cancellation=token
        Thread({try {
            MateTaskCoordinator.awaitRecovery()
            if(!token.isCancelled)MateWakeJobs.claimDue(this)?.let {task->if(!token.isCancelled)MateTaskCoordinator.resumeByUser(this,task.id,task.revision,token)}
            MateWakeJobs.schedule(this)
        }finally {if(!token.isCancelled)jobFinished(params,false)}},"mate-timed-check").start();return true
    }
    override fun onStopJob(params: JobParameters?): Boolean {cancellation?.cancel();return true}
}
