package dev.wind.agent

import dev.wind.agent.browser.BrowserProtocol
import dev.wind.agent.browser.BrowserReceipts
import dev.wind.agent.capability.CapabilityResolver
import dev.wind.agent.capability.CapabilityRequirement
import dev.wind.agent.capability.CapabilityMode
import dev.wind.agent.capability.TaskContext
import org.json.JSONObject

object BrowserAgentTests {
    private var checks=0
    private fun verify(value:Boolean){checks++;check(value){"BrowserAgentTests assertion $checks"}}
    private fun rejects(block:()->Unit){checks++;check(runCatching(block).isFailure){"Expected rejection $checks"}}
    @JvmStatic fun main(args:Array<String>){
        for(url in listOf("https://example.com/","https://abc.de/test","https://www.google.com/search?q=test"))verify(BrowserProtocol.publicUrl(url)==url)
        for(url in listOf("http://example.com/","https://127.0.0.1/","https://10.0.0.2/","https://[::1]/","https://localhost/","https://site.local/","https://metadata.google.internal/","https://user:secret@example.com/","https://example.com:8443/","file:///tmp/a","javascript:alert(1)","https://0x7f.0.0.1/","https://ex ample.com/"))rejects{BrowserProtocol.publicUrl(url)}
        verify(BrowserProtocol.displayUrl("https://example.com/a?q=hello&access_token=secret#private")=="https://example.com/a?q=hello")
        verify(!BrowserProtocol.storedLocation("https://example.com/a?secret=private#token").contains("private"))
        for(name in BrowserProtocol.tools)verify(ToolRegistry.isKnown(name)&&ToolRegistry.isEnabled(name))
        verify(!BrowserProtocol.isMutating("browser_session",JSONObject()))
        verify(!BrowserProtocol.isMutating("browser_tabs",JSONObject()))
        verify(BrowserProtocol.isMutating("browser_tabs",JSONObject().put("mode","new")))
        verify(BrowserProtocol.isMutating("browser_session",JSONObject().put("mode","resume")))
        verify(dev.wind.agent.capability.GrantScope.ONCE !in BrowserProtocol.networkScopes)
        verify(dev.wind.agent.capability.GrantScope.TASK in BrowserProtocol.networkScopes && dev.wind.agent.capability.GrantScope.APP_SESSION in BrowserProtocol.networkScopes)
        rejects{BrowserProtocol.validate("browser_action",JSONObject().put("action","eval"))}
        rejects{BrowserProtocol.validate("browser_takeover",JSONObject().put("mode","resume"))}
        rejects{BrowserProtocol.validate("browser_observe",JSONObject().put("max_elements",99999))}
        val sid="br_"+"a".repeat(32);val snap="snap_"+"b".repeat(32)
        val fill=JSONObject().put("session_id",sid).put("snapshot_id",snap).put("request_id","request_123").put("action","fill").put("ref","e1").put("value","sensitive drafting text")
        BrowserProtocol.validate("browser_action",fill)
        verify(BrowserProtocol.fingerprint(fill).length==64)
        val scroll=JSONObject().put("action","scroll")
        verify(BrowserProtocol.approvalFingerprint(sid,"tab",null,scroll,JSONObject().put("status","READY")).length==64)
        val approvalProof=JSONObject().put("name","Publish").put("form_fields",org.json.JSONArray(listOf(JSONObject().put("value","first draft"))))
        val approved=BrowserProtocol.approvalFingerprint(sid,"tab","https://example.com/",fill,approvalProof)
        val fresh=JSONObject(fill.toString()).put("snapshot_id","snap_"+"c".repeat(32))
        verify(approved==BrowserProtocol.approvalFingerprint(sid,"tab","https://example.com/",fresh,approvalProof))
        fresh.put("value","different draft");verify(approved!=BrowserProtocol.approvalFingerprint(sid,"tab","https://example.com/",fresh,approvalProof))
        verify(approved!=BrowserProtocol.approvalFingerprint(sid,"tab","https://other.com/",fill,approvalProof))
        val book=BrowserReceipts();val digest=BrowserProtocol.fingerprint(fill)
        book.begin("request_123",digest);book.submitting("request_123");book.finish("request_123","DISPATCHED",true)
        verify(book.replay("request_123",digest)?.optBoolean("replayed")==true)
        rejects{book.replay("request_123","different")}
        verify(!book.json().toString().contains("sensitive drafting text"))
        var persisted=JSONObject();val interrupted=BrowserReceipts({persisted=JSONObject(it.toString())})
        interrupted.begin("request_crash",digest);interrupted.submitting("request_crash")
        val restored=BrowserReceipts(saved=persisted)
        verify(restored.unresolved=="request_crash")
        verify(restored.replay("request_crash",digest)?.getString("status")=="UNKNOWN")
        rejects{restored.begin("new_request",digest)}
        restored.reviewedByUser();verify(restored.unresolved==null)
        verify(restored.replay("request_crash",digest)?.getString("status")=="USER_REVIEWED")
        val a=BrowserReceipts();val b=BrowserReceipts();a.begin("task_a_001",digest);a.submitting("task_a_001");a.finish("task_a_001","UNKNOWN",null)
        b.begin("task_b_001",digest);b.submitting("task_b_001");verify(b.finish("task_b_001","VERIFIED_VALUE",true).getString("status")=="VERIFIED_VALUE")
        rejects{BrowserProtocol.checkOwner("chat-a","task-a","chat-b","task-a",false,false)}
        rejects{BrowserProtocol.checkOwner("chat-a","task-a","chat-a","task-b",false,false)}
        rejects{BrowserProtocol.checkOwner("chat-a","task-a","chat-a","task-a",true,false)}
        rejects{BrowserProtocol.checkOwner("chat-a","task-a","chat-a","task-a",false,true)}
        BrowserProtocol.checkOwner("chat-b","task-b","chat-b","task-b",false,false);verify(true)
        val full=BrowserReceipts();repeat(128){full.begin("request_$it",digest);full.finish("request_$it","NOT_SUBMITTED",false)}
        rejects{full.begin("request_129",digest)};verify(full.replay("request_0",digest)!=null)
        val resolver=CapabilityResolver{null};val plan=TaskContext("task","",0,CapabilityMode.PLAN);val run=TaskContext("task","",0,CapabilityMode.ACCEPT)
        verify(resolver.resolve(plan,"browser_session",JSONObject().put("mode","list")) is CapabilityRequirement.Internal)
        verify(resolver.resolve(plan,"browser_session",JSONObject().put("mode","open")) is CapabilityRequirement.Denied)
        verify(resolver.resolve(plan,"browser_action",fill) is CapabilityRequirement.Denied)
        val nav=JSONObject().put("url","https://example.com/").put("network_domains",org.json.JSONArray(listOf("static.example.com")))
        val requirement=resolver.resolve(run,"browser_navigate",nav) as CapabilityRequirement.Network
        verify(requirement.domains==setOf("example.com","static.example.com"))
        verify(resolver.resolve(run,"browser_navigate",JSONObject().put("url","https://localhost/")) is CapabilityRequirement.Denied)
        println("BrowserAgentTests PASS ($checks checks)")
    }
}
