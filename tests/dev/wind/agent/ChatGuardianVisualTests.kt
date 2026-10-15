package dev.wind.agent

import dev.wind.agent.phone.*
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the production adapter through an injectable display, including the API31 report's empty root. */
object ChatGuardianVisualTests {
    private var checks=0
    private fun ok(v: Boolean,label: String) { checks++; check(v) { label } }
    private fun refused(code: String,block: ()->Unit) {
        val e=runCatching(block).exceptionOrNull()
        ok(e!=null && e.message.orEmpty().startsWith(code),"must refuse $code: "+e?.message)
    }
    private val header=ChatVisualScope.Region(200,60,760,130)
    private val composer=ChatVisualScope.Region(100,840,780,900)
    private val peer="江素心🐷"
    private fun sentinel()=JSONObject().put("status","COMPLETED").put("package","com.tencent.mm")
        .put("display_id",70).put("display_session_id","guardian-vd").put("window","uia:1505")
        .put("width",720).put("height",1280).put("rotation",0).put("structural_epoch",7).put("event_epoch",87)
        .put("display_epoch","guardian-vd").put("geometry_epoch",1).put("observation_id","o-87")
        .put("nodes",JSONArray().put(JSONObject().put("node_id","0").put("class","").put("text","")
            .put("description","").put("bounds",JSONArray(listOf(0,0,0,0))).put("enabled",false).put("visible",false)))
    private class Display: ChatDisplayPort {
        var titleHash="exact-emoji-title"; var blankHash="blank"; var draft=""; var clock=0L
        var pkg="com.tencent.mm"; var window="uia:1505"; var display="guardian-vd"
        var fill="收到"; var failAction=false; var emitSelf=true; var sendConfidence=99f; var bodyNoise=""; var changeBodyOnFocus=false; var changeBodyOnPaste=false
        val messages=mutableListOf(ChatPageParser.Line("你好",80,320,350,355,99f))
        val actions=mutableListOf<JSONObject>()
        override fun observe(token: CancellationToken,mode: String)=sentinel().put("package",pkg).put("window",window)
            .put("display_session_id",display).put("observation_id","o-"+actions.size).put("event_epoch",87+actions.size)
        override fun capture(token: CancellationToken): ChatVisualFrame {
            val lines=mutableListOf(ChatPageParser.Line("江素心",250,70,600,115,99f)) // OCR cannot recognize the emoji.
            lines.addAll(messages)
            if(draft.isNotEmpty()) lines.add(ChatPageParser.Line(draft,120,850,600,890,99f))
            lines.add(ChatPageParser.Line("发送",820,850,950,890,sendConfidence))
            val title=titleHash; val box=if(draft.isBlank()) blankHash else "draft:$draft"; val body=messages.joinToString("|") { it.text }
            return ChatVisualFrame(observe(token,"visual"),byteArrayOf(),lines,"OK") { r->
                when(r) { header->title; composer->box; else->body+bodyNoise }
            }
        }
        override fun action(args: JSONObject,token: CancellationToken): JSONObject {
            actions.add(JSONObject(args.toString()))
            if(failAction) return JSONObject().put("status","NOT_SUBMITTED").put("error","CHAT_VISUAL_CONTENT_CHANGED")
            when(args.getString("action")) {
                "paste_text"->{ draft=fill; if(changeBodyOnPaste) bodyNoise="new-nontext-message" }
                "tap"->if(args.getInt("x")>800) {
                    if(emitSelf) messages.add(ChatPageParser.Line(draft,400,450,900,490,99f))
                    draft=""
                }
            }
            if(args.getString("action")=="tap" && args.getInt("x")<800 && changeBodyOnFocus) bodyNoise="new-nontext-message"
            return JSONObject().put("status","DISPATCHED")
        }
        override fun pause(ms: Long,token: CancellationToken) { clock+=ms }
        override fun now()=clock
    }
    private fun config(d: Display,bound: Boolean=true)=JSONObject().put("package","com.tencent.mm").put("peer",peer).also {
        if(bound) it.put("visual_binding",ChatVisualScope.binding(peer,"uia:1505","guardian-vd",header,composer,d.titleHash,d.blankHash))
    }
    @JvmStatic fun main(args: Array<String>) {
        val token=CancellationToken()
        ok(!ChatVisualScope.uiAvailable(sentinel()),"exact report sentinel is unavailable, not a readable tree")
        val d=Display(); val unbound=ChatAppAdapter(config(d,false),d)
        val offer=runCatching { unbound.read(token) }.exceptionOrNull()
        ok(offer is ChatVisualBindingRequired,"empty tree requests local human binding")
        ok(d.actions.isEmpty(),"partial OCR title never grants actions")
        ok(!ChatPageParser.titleMatches(d.capture(token).lines,peer),"emoji omitted by OCR is not exact peer evidence")
        val adapter=ChatAppAdapter(config(d),d); val first=adapter.read(token)
        ok(first.visual!=null && first.empty && first.turns.single().text=="你好","bound screenshot parses peer message and blank composer")
        d.messages.add(ChatPageParser.Line("最近好吗",80,380,370,415,99f))
        val current=adapter.read(token)
        ok(ChatPageParser.appended(first.turns,current.turns).single().text=="最近好吗","OCR monitoring detects new peer text")
        val stages=mutableListOf<String>()
        val receipt=adapter.send("收到",current.key,token,{},{ state,_->stages.add(state) })
        ok(receipt.getString("status")=="SELF_MESSAGE_OBSERVED","complete visual send chain reads a new own bubble")
        ok(receipt.getString("recipient_delivery")=="UNKNOWN","local readback is not recipient delivery")
        ok(receipt.getString("verification_source")=="LOCAL_OCR","receipt records actual observation source")
        ok(d.actions.map { it.getString("action") }==listOf("tap","paste_text","tap"),"focus, one paste, one Send; no replace")
        ok(stages==listOf("FILLED","DISPATCHING","DISPATCHED"),"ledger gets fill proof before dispatch")
        for(a in d.actions) {
            ok(a.getString("display_session_id")=="guardian-vd" && a.getString("screen")=="virtual","actions never route to main")
            ok(a.getJSONObject("peer_guard").getString("title")==peer,"full authorized name retained")
            val p=a.getJSONObject("visual_guard")
            ok(listOf("header","composer","conversation").all { p.has(it) && p.has(it+"_hash") },"live three-region proof for every action")
            if(a.getString("action")=="tap") ok(p.has("target") && p.has("target_hash"),"point target pixels are rechecked at commit")
            ok(!a.optBoolean("replace"),"no select-all/unknown draft overwrite")
        }
        val changed=Display(); val changedAdapter=ChatAppAdapter(config(changed),changed); changed.titleHash="other-title"
        refused("CHAT_VISUAL_PEER_CHANGED") { changedAdapter.read(token) }
        ok(changed.actions.isEmpty(),"different title pixels block all effects")
        for(key in listOf("peer","window","display_session_id","user_confirmed")) {
            val bad=Display(); val cfg=config(bad)
            cfg.getJSONObject("visual_binding").put(key,if(key=="user_confirmed") false else "other")
            refused("CHAT_VISUAL_BINDING_CHANGED") { ChatAppAdapter(cfg,bad).read(token) }
            ok(bad.actions.isEmpty(),"scope mismatch $key blocks effects")
        }
        val wrong=Display().apply { pkg="com.tencent.mobileqq" }
        refused("CHAT_PAGE_NOT_VERIFIED") { ChatAppAdapter(config(wrong),wrong).read(token) }
        val drafted=Display(); val dc=config(drafted); drafted.draft="用户草稿"
        // Use the real page key so draft, rather than stale message version, is the rejection reason.
        val dp=ChatAppAdapter(dc,drafted)
        refused("CHAT_DRAFT_PRESENT") { dp.send("收到",dp.read(token).key,token,{},{ _,_-> }) }
        ok(drafted.actions.isEmpty(),"existing draft is untouched")
        val hidden=Display(); val hc=config(hidden); hidden.blankHash="unrecognized-pixels"
        val hp=ChatAppAdapter(hc,hidden)
        refused("CHAT_DRAFT_PRESENT") { hp.send("收到",hp.read(token).key,token,{},{ _,_-> }) }
        ok(hidden.actions.isEmpty(),"blank OCR cannot hide changed composer pixels")
        val mismatch=Display().apply { fill="收错" }; val mp=ChatAppAdapter(config(mismatch),mismatch)
        refused("CHAT_FILL_OR_MESSAGE_CHANGED") { mp.send("收到",mp.read(token).key,token,{},{ _,_-> }) }
        ok(mismatch.actions.size==2,"OCR fill mismatch never taps Send")
        val low=Display().apply { sendConfidence=70f }; val lp=ChatAppAdapter(config(low),low)
        refused("CHAT_VISUAL_SEND_BUTTON_NOT_VERIFIED") { lp.send("收到",lp.read(token).key,token,{},{ _,_-> }) }
        ok(low.actions.size==2,"uncertain Send OCR cannot dispatch")
        val rejected=Display().apply { failAction=true }; val rp=ChatAppAdapter(config(rejected),rejected)
        refused("CHAT_ACTION_NOT_VERIFIED") { rp.send("收到",rp.read(token).key,token,{},{ _,_-> }) }
        ok(rejected.actions.size==1,"rejected pixel proof never retries mutation")
        val unknown=Display().apply { emitSelf=false }; val up=ChatAppAdapter(config(unknown),unknown)
        ok(up.send("收到",up.read(token).key,token,{},{ _,_-> }).getString("status")=="SEND_UNKNOWN","no new own bubble is unknown")
        ok(unknown.actions.size==3,"unknown readback never repeats Send")
        val old=Display(); old.messages.add(ChatPageParser.Line("收到",400,450,900,490,99f)); old.emitSelf=false
        val op=ChatAppAdapter(config(old),old)
        ok(op.send("收到",op.read(token).key,token,{},{ _,_-> }).getString("status")=="SEND_UNKNOWN","old matching own text cannot prove delivery")
        val stale=Display(); val sp=ChatAppAdapter(config(stale),stale)
        refused("CHAT_MESSAGE_VERSION_CHANGED") { sp.send("收到","wrong-version",token,{},{ _,_-> }) }
        ok(stale.actions.isEmpty(),"message version checked before focus")
        val pixels=IntArray(720*1280) { 0xff112233.toInt() }
        val sig=VisualPatchDigest.digest(pixels,720,1280,header.array)
        ok(sig.length==64 && sig==VisualPatchDigest.digest(pixels,720,1280,header.array),"shared exact ROI digest stable")
        pixels[90*1280/1000*720+300*720/1000]=0xff112234.toInt()
        ok(sig!=VisualPatchDigest.digest(pixels,720,1280,header.array),"one RGB bit in approved title invalidates signature")
        refused("CHAT_VISUAL_REGION_INVALID") { VisualPatchDigest.digest(pixels,720,1280,intArrayOf(-1,0,10,10)) }
        refused("CHAT_HEADER_REGION_REQUIRED") { ChatVisualScope.validate(ChatVisualScope.Region(0,500,1000,600),composer) }
        refused("CHAT_COMPOSER_REGION_REQUIRED") { ChatVisualScope.validate(header,ChatVisualScope.Region(0,200,500,240)) }
        val mapped=ChatVisualScope.mapCrop(ChatPageParser.Line("收到",0,0,1000,1000,99f),composer,720,1280)
        ok(mapped.left in composer.left-1..composer.left && mapped.right in composer.right-1..composer.right &&
            mapped.top in composer.top-1..composer.top && mapped.bottom in composer.bottom-1..composer.bottom,"crop OCR maps back to full display pixels")
        ok(ChatVisualScope.composerText(listOf(mapped),composer)=="收到","isolated composer line excludes adjacent Send")
        val empty=emptyList<ChatPageParser.Turn>(); val newPeer=listOf(ChatPageParser.Turn("peer","第一条"))
        ok(ChatPageParser.incoming(empty,newPeer,false,true)==newPeer,"first message after explicitly seeded empty chat is queued")
        ok(ChatPageParser.incoming(empty,newPeer,false,false).isEmpty(),"later missing OCR slice does not requeue old history")
        ok(ChatPageParser.incoming(empty,newPeer,true,true).isEmpty(),"setup seed never replies to existing messages")
        ok(ChatVisualScope.bodyChangedUnparsed("old","new",newPeer,newPeer),"unread pixel change cannot reuse old message version")
        ok(!ChatVisualScope.bodyChangedUnparsed("old","new",empty,newPeer),"recognized new text goes through normal inbox path")
        var attempts=0; var pauses=0
        val stable=ChatVisualReadPolicy.stable({ attempts++; if(attempts<3) error("CHAT_SCREEN_CHANGED_DURING_OCR"); "stable" },{ pauses++ })
        ok(stable=="stable" && attempts==3 && pauses==2,"bounded fresh read retry tolerates two transitions")
        attempts=0; pauses=0
        refused("CHAT_VISUAL_PEER_CHANGED") { ChatVisualReadPolicy.stable({ attempts++; error("CHAT_VISUAL_PEER_CHANGED") },{ pauses++ }) }
        ok(attempts==1 && pauses==0,"wrong peer never retried or softened")
        attempts=0; pauses=0
        refused("CHAT_SCREEN_CHANGED_DURING_CAPTURE") { ChatVisualReadPolicy.stable({ attempts++; error("CHAT_SCREEN_CHANGED_DURING_CAPTURE") },{ pauses++ }) }
        ok(attempts==3 && pauses==2,"read retry has a hard bound")
        val body=Display().apply { changeBodyOnFocus=true }; val bp=ChatAppAdapter(config(body),body)
        refused("CHAT_EDITOR_OR_MESSAGE_CHANGED") { bp.send("收到",bp.read(token).key,token,{},{ _,_-> }) }
        ok(body.actions.size==1,"unrecognized new body pixels stop before paste")
        val pasted=Display().apply { changeBodyOnPaste=true }; val pp=ChatAppAdapter(config(pasted),pasted)
        refused("CHAT_FILL_OR_MESSAGE_CHANGED") { pp.send("收到",pp.read(token).key,token,{},{ _,_-> }) }
        ok(pasted.actions.size==2,"new nontext body change after paste stops before Send")
        val versioned=Display(); val vc=config(versioned).put("visual_body_hash","wrong-prior-pixels"); val vp=ChatAppAdapter(vc,versioned)
        refused("CHAT_MESSAGE_VERSION_CHANGED") { vp.send("收到",vp.read(token).key,token,{},{ _,_-> }) }
        ok(versioned.actions.isEmpty(),"baseline pixel version checked before focus")
        println("ChatGuardianVisualTests PASS ($checks checks); Android bitmap/OCR/device NOT_RUN")
    }
}
