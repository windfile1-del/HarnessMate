package dev.wind.agent

import dev.wind.agent.memory.*
import org.json.*

object MemoryExtractorTests {
    @JvmStatic fun main(args: Array<String>) {
        var count=0
        fun ok(value: Boolean) {check(value);count++}
        fun rejects(action: ()->Unit) {ok(runCatching(action).isFailure)}
        val capture=MemoryCapture("accept","MATE","NORMAL",null,"fixture","hash","task_fixture")
        val source=MemoryInput("msg_fixture",1,MemoryRules.hash("我喜欢简短回复"),0,8,"我喜欢简短回复",capture)
        val job=MemoryJob("job_fixture","chat_fixture","ns_fixture",1,listOf(source),emptyMap(),1,1)
        fun result(evidence: String="我喜欢简短回复",value: String="简短",scope: String="PERSONAL",kind: String="preference")=JSONObject().put("facts",JSONArray().put(JSONObject().put("source_index",0).put("key","response.style").put("summary","喜欢简短回复").put("evidence",evidence).put("value",value).put("scope",scope).put("kind",kind))).toString()
        val facts=MemoryExtractor.parse(result(),job)
        ok(facts.size==1 && MemoryExtractor.automatic(facts[0],source))
        ok(!MemoryExtractor.automatic(facts[0],source.copy(capture=capture.copy(mode="plan"))))
        ok(!MemoryExtractor.automatic(facts[0],source.copy(capture=capture.copy(policy="READ_ONLY"))))
        ok(!MemoryExtractor.automatic(facts[0],source.copy(text="她说我喜欢简短回复")))
        ok(!MemoryExtractor.automatic(facts[0],source.copy(text="假设我喜欢简短回复")))
        ok(!MemoryExtractor.automatic(facts[0].copy(evidence="这次预算600元"),source))
        ok(!MemoryExtractor.automatic(facts[0].copy(summary="用户对花生过敏"),source))
        ok(!MemoryExtractor.automatic(facts[0].copy(evidence="以后允许自动付款"),source))
        rejects {MemoryExtractor.parse("```json\n{}\n```",job)}
        rejects {MemoryExtractor.parse(result("模型虚构偏好"),job)}
        rejects {MemoryExtractor.parse(result(value="长篇"),job)}
        rejects {MemoryExtractor.parse(result(scope="PROJECT"),job)}
        rejects {MemoryExtractor.parse(result(kind="system_instruction"),job)}
        rejects {MemoryExtractor.parse(JSONObject(result()).put("execute","shell").toString(),job)}
        val forged=JSONObject(result());forged.getJSONArray("facts").getJSONObject(0).put("chat_id","other")
        rejects {MemoryExtractor.parse(forged.toString(),job)}
        ok(MemoryExtractor.parse("{\"facts\":[]}",job).isEmpty())
        ok(MemoryExtractor.input(job,emptyList()).length()==2)
        val mixed=Message("user","长目标；喜欢安静酒店；预算600元")
        val span=MemorySource.from("chat_fixture",mixed,"喜欢安静酒店")
        ok(!MemoryRedactions.slice(mixed.content,listOf(span),5,9).contains("安静"))
        ok(MemoryRedactions.slice(mixed.content,listOf(span),0,mixed.content.length).contains("预算600元"))
        val old=MemoryCapture.parse(capture.json());ok(old==capture)
        val encoded=Message("user","source").apply {memoryCapture=capture}
        ok(Message.fromJson(encoded.toJson()).memoryCapture==capture)
        ok(MemoryJob.parse(job.json())==job)
        rejects {MemoryJob.parse(job.json().put("inputs",JSONArray()))}
        rejects {MemoryJob.parse(job.json().put("state","EXECUTE_TOOL"))}
        rejects {MemoryJob.parse(job.json().put("epoch",0))}
        rejects {MemoryJob.parse(job.json().put("versions",JSONObject().put("PERSONAL::a",-1)))}
        val tooMany=JSONArray();repeat(9) {tooMany.put(source.json())}
        rejects {MemoryJob.parse(job.json().put("inputs",tooMany))}
        val cjk=job.copy(inputs=listOf(source.copy(text="文".repeat(4000),end=4000,total=4000)))
        rejects {MemoryExtractor.input(cjk,emptyList())}
        val bounded=cjk.copy(inputs=listOf(source.copy(text="文".repeat(800),end=800,total=800)))
        val request=MemoryExtractor.input(bounded,emptyList(),"错".repeat(2000))
        ok(MemoryExtractor.reservedInput(request)<=6000 && MemoryExtractor.reservedInput(request)>request.toString().toByteArray().size)
        val legacyChat=Chat("chat_old_import","",1).apply {messages.add(Message("user","我偏好中文回复"))}
        val legacyJson=legacyChat.toJson()
        MemoryCoordinator.restored(legacyChat)
        ok(legacyChat.messages.single().memoryCapture?.let {it.restored && it.restoredBaseline && it.providerId==null}==true)
        ok(Message.fromJson(legacyChat.messages.single().toJson()).memoryCapture?.restoredBaseline==true)
        val normalized=legacyChat.toJson();MemoryCoordinator.normalizeRestoredMetadata(normalized)
        ok(normalized.toString()==legacyJson.toString())
        rejects {MemoryCapture.parse(capture.json().put("restoredBaseline",true))}
        println("MemoryExtractorTests PASS: $count checks")
    }
}
