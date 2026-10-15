package dev.wind.agent.memory

import android.content.Context
import dev.wind.agent.*
import dev.wind.agent.context.*
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MemoryJobRunner(private val context: Context) {
    /** Fixture hook uses the same reservation/validation/commit chain, never a real provider. */
    fun runOne(token: CancellationToken=CancellationToken(), generate: ((JSONArray,CancellationToken)->Pair<String,TokenUsage?>)?=null): String {
        val ctx=context.applicationContext
        if(token.isCancelled)return "CANCELLED"
        MemoryStore(ctx).use { db ->
            val queue=MemoryJobStore(db);val job=queue.claim() ?: return "NO_DUE_JOB"
            var deadline: java.util.concurrent.ScheduledExecutorService?=null
            try {
                val chat=ChatStore(ctx).load(job.chatId) ?: error("MEMORY_SOURCE_DELETED")
                MemoryConsolidator.validateSources(job,chat)
                val providers=Prefs(ctx).modelProviders
                val account=providers.find(job.capture.providerId ?: "")
                if(generate==null)check(account!=null && MemoryCapture.providerHash(account)==job.capture.providerHash && providers.configured(account)) {"MEMORY_PROVIDER_CHANGED_OR_REMOVED"}
                val existing=db.list().filter { MemoryJob.key(it.scope,it.scopeKey,it.key) in job.versions }.take(8)
                val client=if(generate==null)LlmClient(account!!,providers,1000,60).forMemoryExtraction() else null
                deadline=Executors.newSingleThreadScheduledExecutor {r -> Thread(r,"memory-deadline").apply {isDaemon=true}}
                deadline.schedule({token.cancel()},150,TimeUnit.SECONDS)
                val limit=minOf(6000,Prefs(ctx).contextWindowTokens()-1000-512,account?.selected()?.inputTokenLimit?.takeIf { it>0 }?.minus(1512) ?: 6000)
                var bad: String?=null;var parsed: List<MemoryProposal>?=null
                for(attempt in 0..1) {
                    queue.checkLease(job);token.throwIfCancelled()
                    val input=MemoryExtractor.input(job,existing,bad);val estimate=MemoryExtractor.reservedInput(input)
                    check(estimate<=limit) {"MEMORY_MODEL_WINDOW_TOO_SMALL"}
                    val call=queue.reserve(job,estimate,1000)
                    var usage: TokenUsage?=null;var settled=false
                    try {
                        val result=if(generate!=null)generate(input,token).also {usage=it.second}.first else {
                            var text="";var failure: String?=null;var truncated=false
                            client!!.streamChat(input,token,object : LlmClient.StreamCallback {
                                override fun onToken(text: String) {}
                                override fun onThinking(text: String) {}
                                override fun onDone(value: String?) {text=value ?: ""}
                                override fun onError(message: String) {failure=message}
                                override fun onUsage(value: TokenUsage) {usage=value}
                                override fun onTruncated() {truncated=true}
                                override fun isCancelled()=token.isCancelled
                            })
                            token.throwIfCancelled();check(failure==null && !truncated) {if(failure!=null)"MEMORY_MODEL_CALL_FAILED" else "MEMORY_EXTRACTION_TRUNCATED"};text
                        }
                        token.throwIfCancelled();queue.settle(call,usage,"COMPLETED");settled=true
                        parsed=runCatching {MemoryExtractor.parse(result,job)}.getOrNull()
                        if(parsed!=null)break
                        bad=result.take(2000)
                    } finally { if(!settled)queue.settle(call,usage,"FAILED_OR_CANCELLED") }
                }
                check(parsed!=null) {"MEMORY_EXTRACTION_FORMAT_FAILED"}
                if(generate==null)check(providers.find(account!!.id)?.let {MemoryCapture.providerHash(it)}==job.capture.providerHash) {"MEMORY_PROVIDER_CHANGED_DURING_JOB"}
                token.throwIfCancelled()
                check(!ChatStore(ctx).isDeleted(job.chatId)) {"MEMORY_SOURCE_DELETED"}
                val current=AgentBus.liveChatFor(job.chatId) ?: ChatStore(ctx).load(job.chatId) ?: error("MEMORY_SOURCE_DELETED")
                val records=MemoryConsolidator.commit(db,job,current,parsed!!)
                MemoryVaultSync.schedule(ctx)
                MemoryCoordinator.status(ctx,if(records.isEmpty())"已检查新对话，没有需要补充的记忆" else "已补充 ${records.count {it.status=="ACTIVE"}} 条记忆，${records.count {it.status=="CANDIDATE"}} 条待确认")
                return "COMPLETED"
            } catch(e: Throwable) {
                val reason=e.message ?: e.javaClass.simpleName
                val waiting=token.isCancelled || reason=="MEMORY_DAILY_BUDGET_EXHAUSTED"
                val retry=reason in listOf("MEMORY_CONSOLIDATION_CONFLICT","MEMORY_MODEL_CALL_FAILED","MEMORY_EXTRACTION_TRUNCATED") || e is java.io.IOException
                if(waiting)queue.defer(job,reason,if(token.isCancelled)120000 else queue.untilNextBudgetDay())
                else queue.finish(job,if(retry && job.attempts<3)"RETRY" else "CANCELLED",reason,120000L*job.attempts)
                MemoryCoordinator.status(ctx,"自动记忆等待或停止：${reason.take(120)}")
                return if(token.isCancelled)"CANCELLED" else "DEFERRED_OR_CANCELLED"
            } finally {deadline?.shutdownNow()}
        }
    }
}
