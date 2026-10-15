package dev.wind.agent.memory

import dev.wind.agent.*
import dev.wind.agent.profile.*

/** Serial local CAS commit. A model never picks owner IDs or bypasses native review. */
object MemoryConsolidator {
    fun validateSources(job: MemoryJob,chat: Chat) = synchronized(chat.messages) {
        check(chat.retentionPolicy==RetentionPolicy.PERSISTENT && chat.memoryPolicy==MemorySessionPolicy.NORMAL && chat.webBinding==null) {"MEMORY_SOURCE_POLICY_CHANGED"}
        val bound=chat.projectMemoryKey.takeIf {it!=null && it==chat.projectBindingId}
        for(input in job.inputs) {
            val m=chat.messages.firstOrNull {it.messageId==input.messageId} ?: error("MEMORY_SOURCE_DELETED")
            synchronized(m) {check(MemoryRules.direct(m) && m.sourceRevision==input.revision && MemoryRules.hash(m.content)==input.digest && m.memoryCapture==input.capture) {"MEMORY_SOURCE_CHANGED"}}
            check(bound==input.capture.project) {"MEMORY_PROJECT_CHANGED"}
        }
    }
    private fun <T> locked(messages: List<Message>,index: Int=0,block: ()->T): T = if(index==messages.size)block() else synchronized(messages[index]) {locked(messages,index+1,block)}
    fun commit(store: MemoryStore,job: MemoryJob,chat: Chat,proposals: List<MemoryProposal>): List<MemoryRecord> = synchronized(chat.messages) {
        val origins=job.inputs.map {input ->chat.messages.firstOrNull {it.messageId==input.messageId} ?: error("MEMORY_SOURCE_DELETED")}.distinctBy {it.messageId}.sortedBy {it.messageId}
        locked(origins) {store.transaction {
        val queue=MemoryJobStore(store);queue.checkLease(job)
        validateSources(job,chat)
        check(chat.retentionPolicy==RetentionPolicy.PERSISTENT && chat.memoryPolicy==MemorySessionPolicy.NORMAL && chat.webBinding==null) {"MEMORY_SOURCE_POLICY_CHANGED"}
        val messages=job.inputs.map { input ->
            val m=chat.messages.firstOrNull { it.messageId==input.messageId } ?: error("MEMORY_SOURCE_DELETED")
            check(MemoryRules.direct(m) && m.sourceRevision==input.revision && MemoryRules.hash(m.content)==input.digest && m.memoryCapture==input.capture) {"MEMORY_SOURCE_CHANGED"};m
        }
        val results=mutableListOf<MemoryRecord>();val submitted=mutableSetOf<String>()
        for(p in proposals) {
            val input=job.inputs[p.sourceIndex];val m=messages[p.sourceIndex]
            val bound=chat.projectMemoryKey.takeIf { it!=null && it==chat.projectBindingId }
            check(bound==input.capture.project) {"MEMORY_PROJECT_CHANGED"}
            val raw=m.content.substring(input.offset,input.end);val position=raw.indexOf(p.evidence);check(position>=0) {"MEMORY_PROOF_CHANGED"}
            val proof=MemorySource.from(chat.id,m,p.evidence).copy(start=input.offset+position,end=input.offset+position+p.evidence.length)
            check(!store.blocked(proof) && MemoryRedactions.ranges(m.content,store.redactions(chat.id)[m.messageId] ?: emptyList()).none { it.first<proof.end && it.last>=proof.start }) {"MEMORY_SOURCE_FORGOTTEN"}
            val context=MemoryRules.evidenceContext(m.content,proof.start,proof.end)
            val projectFact=MemoryRules.projectFact(p.kind,context)
            val temporary=MemoryRules.temporary(context+p.summary) || p.kind=="task" || projectFact && bound==null
            val scope=when {temporary->MemoryScope.TASK;projectFact->MemoryScope.PROJECT;else->p.scope}
            val key=when(scope){MemoryScope.PERSONAL->"";MemoryScope.PROJECT->bound ?: error("MEMORY_PROJECT_NOT_BOUND");MemoryScope.TASK->input.capture.task}
            val identity=MemoryJob.key(scope,key,p.key);if(!submitted.add(identity))continue
            val existing=store.findByKey(scope,key,p.key)
            check((existing?.revision ?: 0)==(job.versions[identity] ?: 0)) {"MEMORY_CONSOLIDATION_CONFLICT"}
            val lowRisk=MemoryExtractor.automatic(p,input) && !MemoryRules.sensitive(context) && !MemoryRules.secret(context)
            val directCorrection=p.correction && Regex("改为|不再|纠正|以后|instead|rather than",RegexOption.IGNORE_CASE).containsMatchIn(input.text)
            val equivalent=existing?.summary?.trim()==p.evidence.trim()
            val sources=(if(equivalent)existing!!.sources else emptyList())+proof
            val confirmed=lowRisk && sources.map { it.messageId }.distinct().size>=2
            val active=lowRisk && !temporary && (existing==null || equivalent || directCorrection || MemoryRules.explicit(input.text))
            val now=System.currentTimeMillis()
            val expiry=if(temporary)p.expires.takeIf { it>now && it<now+366L*86400000 && Regex("\\d{4}[-年/]\\d{1,2}[-月/]\\d{1,2}").containsMatchIn(p.evidence) } ?: now+86400000 else p.expires
            var finalKey=p.key;var expected=existing?.revision ?: 0L
            var status=if(active || confirmed)"ACTIVE" else "CANDIDATE"
            if(existing?.origin=="user_managed" && !directCorrection && !MemoryRules.explicit(input.text) && !equivalent)status="CANDIDATE"
            if(existing!=null && !equivalent && status=="CANDIDATE") {finalKey=p.key.take(65)+".candidate."+job.id.take(12);expected=0}
            // ACTIVE summary is the verified original evidence, not the model paraphrase.
            val summary=if(status=="ACTIVE")p.evidence.trim() else p.summary
            results.add(store.save(scope,key,finalKey,summary,sources.distinctBy { it.fingerprint() }.takeLast(16),expected,store.epoch(),status,
                if(scope==MemoryScope.PERSONAL)"GENERAL" else input.capture.profile,expiry,"automatic"))
        }
        queue.completed(job);results
    } } }
}
