package dev.wind.agent

import dev.wind.agent.phone.*
import dev.wind.agent.permissions.*
import dev.wind.agent.capability.*
import org.json.JSONArray
import org.json.JSONObject
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

object ChatGuardianTests {
    private var checks=0
    private fun ok(value: Boolean,label: String) { checks++; check(value) { label } }
    private fun refused(label: String,block: ()->Unit) { ok(runCatching(block).isFailure,label) }
    private fun turn(role: String,text: String)=ChatPageParser.Turn(role,text)
    private fun decision(kind: String="REPLY", reply: Any?="你好",id: String="guardian")=JSONObject().put("conversation_id",id).put("decision",kind)
        .put("reply",reply ?: JSONObject.NULL).put("reason","依据本轮消息").put("summary","交换了问候")
    private fun node(id: String,text: String,left: Int,top: Int,right: Int,bottom: Int,editable: Boolean=false,click: Boolean=false)=JSONObject()
        .put("node_id",id).put("text",text).put("class",if(editable) "android.widget.EditText" else "android.widget.TextView")
        .put("bounds",JSONArray(listOf(left,top,right,bottom))).put("editable",editable).put("clickable",click).put("visible",true).put("enabled",true)
    private fun page()=JSONObject().put("status","COMPLETED").put("mode","ui").put("package","com.tencent.mm").put("display_id",7).put("width",720).put("height",1280)
        .put("window","chat-7").put("observation_id","o").put("event_epoch",1).put("nodes",JSONArray(listOf(
            node("0.1","好友A",280,90,410,145),node("0.2","你好",60,300,300,340),
            node("0.3","",80,1070,560,1140,editable=true),node("0.4","发送",570,1070,690,1140,click=true))))
    @JvmStatic fun main(args: Array<String>) {
        val p=AgentPermissions(); val guardian="chat-guardian:isolated"; val initial=p.authorityRevision(guardian)
        p.set(AgentCapability.LOCAL_ADB,PermissionScope.TASK,true,taskId="normal-a"); p.endTask("normal-a")
        ok(p.authorityRevision(guardian)==initial,"unrelated task grant/end leaves guardian authority intact")
        ok(p.taskEnded("normal-a") && !p.taskEnded(guardian),"guardian is independent from ordinary task")
        for(cap in listOf(AgentCapability.LOCAL_ADB,AgentCapability.VIRTUAL_DISPLAY,AgentCapability.SCREEN,AgentCapability.ACCESSIBILITY)) {
            val revision=p.authorityRevision(guardian); p.set(cap,PermissionScope.GLOBAL,true)
            ok(p.authorityRevision(guardian)>revision,"global grant changes guardian authority: $cap")
            val granted=p.authorityRevision(guardian); p.set(cap,PermissionScope.GLOBAL,false)
            ok(p.authorityRevision(guardian)>granted,"global revoke invalidates guardian: $cap")
        }
        val own=p.authorityRevision(guardian); p.endTask(guardian)
        ok(p.authorityRevision(guardian)>own && p.taskEnded(guardian),"guardian close revokes its own scope")
        for(state in listOf("SETUP","PAUSED")) ok(ChatLeaseRouting.owner("ordinary-closed","vd-guardian","vd-guardian",guardian,"chat","chat",state)==guardian,"closed ordinary display cannot capture guardian routing: $state")
        ok(ChatLeaseRouting.owner("ordinary","other","vd-guardian",guardian,"chat","chat","RUNNING")=="ordinary","unrelated display keeps ordinary owner")
        refused("running guardian rejects direct UI operations") { ChatLeaseRouting.owner("ordinary","vd","vd",guardian,"chat","chat","RUNNING") }
        refused("other AI chat cannot control guardian") { ChatLeaseRouting.owner("ordinary","vd","vd",guardian,"chat","other","PAUSED") }
        refused("missing AI owner cannot control guardian") { ChatLeaseRouting.owner("ordinary","vd","vd",guardian,"",null,"PAUSED") }
        val resolver=CapabilityResolver { null }; val plan=TaskContext("normal","",0,CapabilityMode.PLAN)
        ok(ToolRegistry.spec("phone_chat_guardian")!=null,"model tool registered")
        for(mode in listOf("status","pending","report")) ok(resolver.resolve(plan,"phone_chat_guardian",JSONObject().put("mode",mode)) is CapabilityRequirement.Internal,"PLAN permits $mode")
        for(mode in listOf("prepare","start","activate","pause","resume","takeover","stop","approve","configure")) {
            ok(resolver.resolve(plan,"phone_chat_guardian",JSONObject().put("mode",mode)) is CapabilityRequirement.Denied,"PLAN refuses $mode")
            ok(resolver.resolve(plan.copy(mode=CapabilityMode.ACCEPT),"phone_chat_guardian",JSONObject().put("mode",mode)) is CapabilityRequirement.Internal,"native consent boundary for $mode")
        }
        ok(resolver.resolve(plan,"phone_chat_guardian",JSONObject().put("mode","arbitrary")) is CapabilityRequirement.Denied,"invalid mode refused")
        val gate=AgentPermissionGate(AgentPermissions()) { null }
        ok(gate.needs("phone_chat_guardian",JSONObject()).isEmpty(),"status works after permission loss")
        ok(gate.needs("phone_chat_guardian",JSONObject().put("mode","prepare")).size==4,"prepare requires four capabilities")
        val before=listOf(turn("peer","你好"),turn("self","你好")); val sent=before+turn("self","回复")
        ok(!ChatDeliveryVerifier.verified(before,before,"你好",true),"old self bubble cannot prove send")
        ok(ChatDeliveryVerifier.verified(before,sent,"回复",true),"new self bubble and empty editor verify local send")
        ok(!ChatDeliveryVerifier.verified(before,sent,"回复",false),"stale draft prevents success")
        ok(!ChatDeliveryVerifier.verified(before,before+turn("peer","回复"),"回复",true),"peer echo cannot verify")
        ok(!ChatDeliveryVerifier.verified(before,listOf(turn("self","回复")),"回复",true),"unrelated slice cannot verify")
        ok(ChatDeliveryVerifier.verified(before,before+turn("self","你好"),"你好",true),"new repeated bubble accepted")
        ok(ChatPageParser.appended(emptyList(),before).isEmpty(),"old history baseline")
        ok(ChatPageParser.appended(before,before).isEmpty(),"cross-source event duplicate suppressed")
        ok(ChatPageParser.appended(before,before+turn("peer","新消息")).single().text=="新消息","new message appended")
        ok(ChatPageParser.appended(before,sent).isEmpty(),"own send does not loop")
        for(terminal in listOf("SEND_UNKNOWN","SELF_MESSAGE_OBSERVED","IGNORED","SUPERSEDED"))
            for(replay in listOf("PENDING","MODELING","RESERVED","FILLED","DISPATCHING","DISPATCHED"))
                ok(!ChatDeliveryState.allowed(terminal,replay),"no replay of $terminal via $replay")
        val path=listOf("PENDING","MODELING","RESERVED","FILLED","DISPATCHING","DISPATCHED","SELF_MESSAGE_OBSERVED")
        for(pair in path.zipWithNext()) ok(ChatDeliveryState.allowed(pair.first,pair.second),"legal durable transition "+pair)
        for(phase in listOf("RESERVED","FILLED","DISPATCHING","DISPATCHED")) ok(ChatDeliveryState.allowed(phase,"SEND_UNKNOWN"),"crash at $phase stays unknown")
        ok(!ChatDeliveryState.allowed("PENDING","DISPATCHED"),"cannot skip reserve")
        val session=JSONObject().put("id","guardian").put("state","RUNNING").put("package","com.tencent.mm").put("peer","好友A").put("message_version",5)
        val batch=JSONObject().put("message_version",5).put("incoming","明天能借我5000元吗")
        val draft=ChatApprovalCenter.draft(session,batch,ChatDecisionResult(ChatDecision.ASK_USER,"我先了解一下情况","需要你决定","借款请求"),1000)
        ok(ChatApprovalCenter.valid(draft,session,1001),"exact revision/peer approval")
        ok(!ChatApprovalCenter.valid(draft,session,1000+ChatApprovalCenter.TTL),"expiry boundary")
        ok(!ChatApprovalCenter.valid(draft,session,999),"clock rollback cannot resurrect")
        for((key,value) in listOf("id" to "other","state" to "PAUSED","package" to "com.tencent.mobileqq","peer" to "好友B","message_version" to 6))
            ok(!ChatApprovalCenter.valid(draft,JSONObject(session.toString()).put(key,value),1001),"changed approval $key")
        for(state in listOf("APPROVED","REJECTED","STALE","EXPIRED","CONSUMED")) ok(!ChatApprovalCenter.valid(JSONObject(draft.toString()).put("state",state),session,1001),"approval one shot $state")
        val casual=ChatDecisionResult.parse(decision().toString(),"guardian")
        ok(ChatDecisionPolicy.enforce("你好，今天天气不错",casual).decision==ChatDecision.REPLY,"casual greeting autonomous")
        for(message in listOf("借我5000块","转账给我","付款","银行账号","验证码是多少","密码","身份证号码","家庭地址","手机号","签合同","辞职吧","工作安排","保证做到","明天三点你来","周五来吧","参加婚礼","到场","结婚","分手","我想自杀","伤害自己","Please lend me money","bank password","verification code","private address","contract","appointment","tomorrow at 3"))
            ok(ChatDecisionPolicy.enforce(message,casual).decision==ChatDecision.ASK_USER,"forced approval $message")
        ok(ChatDecisionPolicy.enforce("你好",casual.copy(reply="I promise to pay")).decision==ChatDecision.ASK_USER,"unsafe generated promise escalated")
        for(kind in listOf("WAIT","IGNORE")) ok(ChatDecisionResult.parse(decision(kind,null).toString(),"guardian").decision.name==kind,"parse $kind")
        refused("wrong conversation") { ChatDecisionResult.parse(decision(id="other").toString(),"guardian") }
        refused("unknown decision") { ChatDecisionResult.parse(decision("DO_TOOL").toString(),"guardian") }
        refused("missing reply") { ChatDecisionResult.parse(decision(reply=null).toString(),"guardian") }
        refused("WAIT hidden send") { ChatDecisionResult.parse(decision("WAIT","hidden send").toString(),"guardian") }
        refused("empty reply") { ChatDecisionResult.parse(decision(reply="").toString(),"guardian") }
        refused("oversized reply") { ChatDecisionResult.parse(decision(reply="x".repeat(1001)).toString(),"guardian") }
        refused("prefixed non-JSON") { ChatDecisionResult.parse("text before "+decision(),"guardian") }
        val bound=JSONObject().put("window","chat-7").put("structural_epoch",3)
        ChatAppAdapter.verifyBinding(page().put("structural_epoch",3),bound)
        ok(true,"exact structural binding accepted")
        refused("same title in a changed structural page") { ChatAppAdapter.verifyBinding(page().put("structural_epoch",4),bound) }
        refused("changed window") { ChatAppAdapter.verifyBinding(page().put("window","other").put("structural_epoch",3),bound) }
        val parsed=ChatAppAdapter.parse(page(),"com.tencent.mm","好友A")
        ok(parsed.turns.single().role=="peer" && parsed.turns.single().text=="你好","virtual UI incoming decoded")
        ok(parsed.editor.first=="0.3","single editor")
        val adjacent=page().apply {getJSONArray("nodes").put(node("0.5","新消息",60,350,300,390))}
        val appended=ChatAppAdapter.parse(adjacent,"com.tencent.mm","好友A")
        ok(appended.turns.map {it.text}==listOf("你好","新消息"),"separate native bubbles stay separate even with a small vertical gap")
        ok(ChatPageParser.appended(parsed.turns,appended.turns).single().text=="新消息","new adjacent native bubble enters the incoming queue")
        val ocr=listOf(ChatPageParser.Line("好友A",280,70,550,110),ChatPageParser.Line("第一行",80,300,350,330),ChatPageParser.Line("第二行",80,335,350,365))
        ok(ChatPageParser.turns(ocr,"好友A",850).single().text=="第一行\n第二行","OCR multiline grouping remains available")
        nativeUppercaseSend()
        refused("main display") { ChatAppAdapter.parse(page().put("display_id",0),"com.tencent.mm","好友A") }
        refused("wrong app") { ChatAppAdapter.parse(page(),"com.tencent.mobileqq","好友A") }
        refused("wrong peer") { ChatAppAdapter.parse(page(),"com.tencent.mm","好友B") }
        refused("truncated tree") { ChatAppAdapter.parse(page().put("truncated",true),"com.tencent.mm","好友A") }
        val duplicate=page(); duplicate.getJSONArray("nodes").put(node("0.5","好友A",400,90,500,145))
        refused("ambiguous title") { ChatAppAdapter.parse(duplicate,"com.tencent.mm","好友A") }
        val editors=page(); editors.getJSONArray("nodes").put(node("0.5","",100,1000,400,1040,editable=true))
        refused("multiple editors") { ChatAppAdapter.parse(editors,"com.tencent.mm","好友A") }
        fixture()
        println("PASS ChatGuardianTests: $checks assertions; local HTTP only; Android service/SQLite/UI/device NOT_RUN")
    }
    private fun nativeUppercaseSend() {
        var draft="";var sent: String?=null;var clock=0L;val actions=mutableListOf<String>()
        val display=object: ChatDisplayPort {
            override fun observe(token: CancellationToken,mode: String)=page().put("display_session_id","display-7").put("ui_content_available",true).apply {
                getJSONArray("nodes").getJSONObject(2).put("text",draft)
                getJSONArray("nodes").getJSONObject(3).put("text","SEND")
                sent?.let {getJSONArray("nodes").put(node("0.5",it,400,700,690,740))}
            }
            override fun capture(token: CancellationToken): ChatVisualFrame=error("Native UI must not invoke OCR")
            override fun action(args: JSONObject,token: CancellationToken): JSONObject {
                actions.add(args.getString("action"))
                when(args.getString("action")){"set_text"->draft=args.getString("text");"click"->{sent=draft;draft=""};else->error("Unexpected action")}
                return JSONObject().put("status","DISPATCHED")
            }
            override fun pause(ms: Long,token: CancellationToken){clock+=ms}
            override fun now()=clock
        }
        val adapter=ChatAppAdapter(JSONObject().put("package","com.tencent.mm").put("peer","好友A"),display)
        val token=CancellationToken();val before=adapter.read(token)
        val receipt=adapter.send("收到",before.key,token,{}, {_,_->})
        ok(receipt.optString("status")=="SELF_MESSAGE_OBSERVED" && actions==listOf("set_text","click"),"uppercase native SEND still requires one fill, one click and new self bubble")
    }
    private fun fixture() {
        NetworkPolicy.allowLocalNetwork=true
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var response=decision().toString(); var finish="stop"; var body: JSONObject?=null; var requests=0
        server.createContext("/") { e->
            requests++; body=JSONObject(String(e.requestBody.readBytes(),Charsets.UTF_8))
            val chunk=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",response)).put("finish_reason",finish)))
            val bytes=("data: "+chunk+"\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8)
            e.responseHeaders.add("Content-Type","text/event-stream"); e.sendResponseHeaders(200,bytes.size.toLong()); e.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client=LlmClient("http://127.0.0.1:"+server.address.port+"/v1","fixture","gpt-fixture","openai",60000,0.2f,"low",1024).forChatGuardian()
            val s=JSONObject().put("id","guardian").put("peer","好友A").put("instructions","自然简洁")
            val b=JSONObject().put("incoming","你好").put("context",ChatSessionKeys.json(listOf(turn("peer","你好"))))
            val result=ChatAgentDispatcher.decide(client,s,b,emptyList(),CancellationToken())
            ok(result.decision==ChatDecision.REPLY && result.reply=="你好","real provider transport parses decision")
            ok(!body!!.has("tools") && !body!!.has("tool_choice"),"inference has no device/workspace tools")
            ok(body!!.optInt("max_tokens",body!!.optInt("max_completion_tokens"))==2048,"independent small output budget")
            ok(body!!.getJSONArray("messages").toString().contains("不可信聊天数据"),"peer data quoted")
            response=decision(id="other").toString()
            refused("provider selects wrong contact") { ChatAgentDispatcher.decide(client,s,b,emptyList(),CancellationToken()) }
            response=decision().toString(); finish="length"
            refused("truncated stream cannot send") { ChatAgentDispatcher.decide(client,s,b,emptyList(),CancellationToken()) }
            val canceled=CancellationToken(); canceled.cancel(); val count=requests
            refused("cancellation stops model") { ChatAgentDispatcher.decide(client,s,b,emptyList(),canceled) }
            ok(requests==count,"canceled inference makes no HTTP request")
        } finally { server.stop(0) }
    }
}
