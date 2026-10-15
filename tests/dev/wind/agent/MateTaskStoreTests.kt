package dev.wind.agent

import dev.wind.agent.mate.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Actual filesystem/CAS/event commits, not a fake repository repeating the implementation. */
object MateTaskStoreTests {
    @JvmStatic fun main(args: Array<String>) {
        var checks=0
        fun verify(value: Boolean,why: String){check(value){why};checks++}
        fun rejected(why: String,action:()->Unit){verify(runCatching(action).isFailure,why)}
        val temp=File.createTempFile("mate-tasks-",".test").apply {delete();mkdirs()}
        try {
            val root=File(temp,"local");val store=MateTaskStore(root)
            val a=store.create("mate_a","chat_a","msg_a","整理文件并持续沟通")
            val b=store.create("mate_b","chat_b","msg_b","查商品价格")
            verify(a.revision==1L && b.revision==1L,"New task revision")
            val requested=System.currentTimeMillis()+86400000
            val timed=a.copy(state=MateTaskState.WAITING_FOR_EVENT,wakeAt=requested,plannedWakeAt=requested)
            verify(MateTask.parse(timed.json()).wakeAt==requested,"Timed wake metadata not persisted")
            verify(timed.recovered().wakeAt==0L && timed.recovered().plannedWakeAt==requested,"Restore executed an old timer or lost planned time")
            verify(store.create(a.id,a.chatId,a.sourceMessage,a.goal).revision==1L,"Idempotent task creation")
            rejected("Cross-chat identity overwrite") {store.create(a.id,"chat_b","msg_b","改目标")}
            store.update(a.id,a.revision) {it.copy(state=MateTaskState.RUNNING,executionId="run_a",resources=listOf(MateResource("guardian","lease_a","ACTIVE")),continuous=true)}
            verify(MateTaskStore(root).get(a.id)?.executionId=="run_a","Atomic catalog did not survive reopening")
            rejected("Stale update bypassed CAS") {store.update(a.id,1){it.copy(goal="stale")}}
            rejected("Resource owner stolen") {store.update(b.id){it.copy(resources=listOf(MateResource("guardian","lease_a","ACTIVE")))}}
            verify(store.get(b.id)?.resources?.isEmpty()==true,"Rejected ownership partially persisted")
            verify(store.event(a.id,"guardian_a","new_message",2,MateTaskState.WAITING_FOR_EVENT,"新消息已观察"),"Fresh event lost")
            val after=store.get(a.id)!!
            verify(!MateTaskStore(root).event(a.id,"guardian_a","new_message",2,MateTaskState.WAITING_FOR_EVENT,"重复"),"Event reconsumed after reopen")
            verify(store.get(a.id)?.revision==after.revision,"Duplicate wake wrote/billed new progress")
            verify(!store.event(a.id,"guardian_a","old",1,MateTaskState.RUNNING,"旧页"),"Stale event changed state")
            verify(store.event(a.id,"guardian_a","older_unknown_send",1,MateTaskState.UNKNOWN,"旧发送结果未知"),"Older uncertain effect silently discarded")
            verify(store.get(a.id)?.watermarks?.get("guardian_a")==2L,"Uncertainty rolled watermark backwards")
            store.event(a.id,"guardian_a","later_page",3,MateTaskState.WAITING_FOR_EVENT,"有新内容")
            verify(store.get(a.id)?.state==MateTaskState.UNKNOWN,"Unrelated observation cleared uncertain effect")
            val beforeRestore=store.snapshot();val target=MateTaskStore(File(temp,"restored"))
            verify(target.restore(beforeRestore)==2,"Complete task snapshot not restored")
            val restored=target.get(a.id)!!
            verify(restored.executionId.isBlank() && restored.needsObservation && restored.resources.single().state=="NEEDS_REAUTHORIZE","Restore reinstated session authority")
            verify(restored.state==MateTaskState.UNKNOWN,"Restore replayed or erased uncertainty")
            verify(target.get(b.id)?.state==MateTaskState.NEEDS_REAUTHORIZE,"Ongoing task restored as runnable")
            verify(target.restore(beforeRestore)==0 && target.list().size==2,"Repeated import duplicated tasks")
            val byteBefore=File(root,"catalog-v1.json").readText()
            val invalid=JSONObject(beforeRestore.toString()).apply {getJSONArray("tasks").getJSONObject(0).put("revision",0)}
            rejected("Invalid snapshot committed") {store.restore(invalid)}
            verify(File(root,"catalog-v1.json").readText()==byteBefore,"Invalid restore partially mutated primary")
            store.update(a.id){it.copy(state=MateTaskState.CANCELLED,resources=it.resources.map {r->r.copy(state="RELEASED")})}
            verify(store.get(b.id)==b,"Stopping A affected B")
            store.remove(a.id);val tomb=store.snapshot();target.restore(tomb);target.restore(beforeRestore)
            verify(target.get(a.id)==null && target.get(b.id)!=null,"Old catalog resurrected removed task")
            rejected("Deleted identity re-created") {store.create(a.id,a.chatId,a.sourceMessage,a.goal)}
            val ephemeralRoot=File(temp,"ephemeral");val ephemeral=MateTaskStore(ephemeralRoot,false)
            ephemeral.create("mate_temp","chat_temp","msg_temp","临时目标")
            verify(!ephemeralRoot.exists() && MateTaskStore(ephemeralRoot,false).list().size==1,"Temporary task wrote durable data")
            val concurrency=MateTaskStore(File(temp,"concurrent"));concurrency.create("mate_c","chat_c","msg_c","并行进度")
            val pool=Executors.newFixedThreadPool(4)
            try {val results=(1..20).map {pool.submit<Boolean> {MateTaskStore(File(temp,"concurrent")).event("mate_c","source_$it","event",1,MateTaskState.RUNNING,"step $it")}}
                verify(results.all {it.get()},"Concurrent event commit lost work")
            } finally {pool.shutdownNow()}
            val concurrent=concurrency.get("mate_c")!!
            verify(concurrent.revision==21L && concurrent.watermarks.size==20,"Concurrent catalog overwrite lost watermarks")
            concurrency.recoverProcess();verify(concurrency.get("mate_c")?.state==MateTaskState.NEEDS_REAUTHORIZE,"Process recovery auto-executed a task")
            val corrupted=File(temp,"corrupt");MateTaskStore(corrupted).create("mate_bad","chat_bad","msg_bad","保留")
            File(corrupted,"catalog-v1.json").writeText("{\"format\":1")
            rejected("Corrupt primary silently became empty") {MateTaskStore(corrupted).list()}
            println("MateTaskStoreTests PASS: $checks checks")
        } finally {temp.deleteRecursively()}
    }
}
