package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.CancellationToken
import org.json.JSONObject

/** Same guarded business chain for accessible nodes and user-bound local screenshot/OCR. */
internal class ChatAppAdapter(private val config: JSONObject,private val port: ChatDisplayPort) {
    constructor(lease: VirtualDisplays.Session,config: JSONObject,context: Context): this(config,NativeChatDisplayPort(context,lease,config.optJSONObject("visual_binding")))
    data class Page(val observation: JSONObject,val nodes: List<Pair<String,NodeFacts>>,val turns: List<ChatPageParser.Turn>,
        val editor: Pair<String,NodeFacts>,val visual: ChatVisualFrame?=null,val bodyHash: String?=null) {
        val key get()=ChatDeliveryVerifier.pageKey(turns)
        val empty get()=editor.second.text.isBlank()
    }
    fun read(token: CancellationToken): Page {
        val o=port.observe(token)
        check(o.optString("package")==config.getString("package") && o.optInt("display_id",0)>0) { "CHAT_PAGE_NOT_VERIFIED" }
        if(config.has("visual_binding") || !ChatVisualScope.uiAvailable(o)) {
            check(config.optBoolean("visual_fallback",true)) { "CHAT_UI_TREE_UNAVAILABLE" }
            val frame=port.capture(token)
            check(frame.observation.optString("package")==config.getString("package") && frame.observation.optInt("display_id",0)>0) { "CHAT_PAGE_NOT_VERIFIED" }
            val binding=config.optJSONObject("visual_binding") ?: throw ChatVisualBindingRequired(frame)
            return visualPage(frame,config.getString("peer"),binding)
        }
        return parse(o,config.getString("package"),config.getString("peer")).also { verifyBinding(it.observation,config) }
    }
    fun send(reply: String,expectedKey: String,token: CancellationToken,authority: ()->Unit,stage: (String,JSONObject?)->Unit): JSONObject {
        authority(); val before=read(token)
        if(before.bodyHash!=null && config.has("visual_body_hash")) check(before.bodyHash==config.getString("visual_body_hash")) { "CHAT_MESSAGE_VERSION_CHANGED" }
        check(before.key==expectedKey) { "CHAT_MESSAGE_VERSION_CHANGED" }
        check(before.empty) { "CHAT_DRAFT_PRESENT: manual takeover required" }
        fun act(page: Page,action: String,node: String?=null,text: String?=null,point: ChatVisualScope.Region?=null): JSONObject {
            token.throwIfCancelled(); authority()
            val peer=JSONObject().put("package",config.getString("package")).put("title",config.getString("peer"))
                .put("source",if(page.visual==null) "ui" else "ocr").put("require_event_epoch",true).put("event_epoch",page.observation.getLong("event_epoch"))
            val a=JSONObject().put("screen","virtual").put("display_session_id",page.observation.getString("display_session_id"))
                .put("observation_id",page.observation.getString("observation_id")).put("action",action).put("peer_guard",peer)
            if(node!=null) a.put("node_id",node)
            if(text!=null) a.put("text",text)
            if(point!=null) a.put("x",point.x).put("y",point.y)
            if(page.visual!=null) {
                val binding=config.getJSONObject("visual_binding"); val body=ChatVisualScope.bodyRegion(binding); val header=ChatVisualScope.Region.parse(binding.getJSONArray("header"))
                val composer=ChatVisualScope.Region.parse(binding.getJSONArray("composer"))
                a.put("visual_guard",JSONObject().put("header",header.json).put("header_hash",page.visual.signature(header))
                    .put("composer",composer.json).put("composer_hash",page.visual.signature(composer))
                    .put("conversation",body.json)
                    .put("conversation_hash",page.visual.signature(body)).also { proof->
                        if(point!=null) proof.put("target",point.json).put("target_hash",page.visual.signature(point))
                    })
            }
            return port.action(a,token).also { check(it.optString("status")=="DISPATCHED") { "CHAT_ACTION_NOT_VERIFIED: "+it.optString("error") } }
        }
        if(before.visual==null) act(before,"set_text",before.editor.first,reply)
        else {
            val composer=ChatVisualScope.Region.parse(config.getJSONObject("visual_binding").getJSONArray("composer"))
            act(before,"tap",point=composer); port.pause(150,token)
            val focused=read(token); check(focused.key==expectedKey && focused.bodyHash==before.bodyHash && focused.empty) { "CHAT_EDITOR_OR_MESSAGE_CHANGED" }
            // Do not select-all/overwrite a draft. Exact OCR of the resulting complete composer gates Send.
            act(focused,"paste_text",text=reply)
        }
        port.pause(180,token); val ready=read(token); authority()
        check(ready.key==expectedKey && ready.bodyHash==before.bodyHash && ready.editor.second.text.replace("\r\n","\n")==reply.replace("\r\n","\n")) { "CHAT_FILL_OR_MESSAGE_CHANGED" }
        stage("FILLED",JSONObject().put("verification",if(ready.visual==null) "UI_EDITOR_TEXT" else "LOCAL_OCR_EDITOR_TEXT"))
        if(ready.visual==null) {
            val button=ready.nodes.filter { (_,n)->n.actionable && n.clickable && n.text.trim().lowercase(java.util.Locale.ROOT) in setOf("发送","send") && n.top>=ready.editor.second.top-80 }.singleOrNull()
                ?: error("CHAT_SEND_BUTTON_AMBIGUOUS")
            stage("DISPATCHING",null); val sent=act(ready,"click",node=button.first); stage("DISPATCHED",sent)
        } else {
            val composer=ChatVisualScope.Region.parse(config.getJSONObject("visual_binding").getJSONArray("composer"))
            val button=ChatVisualScope.sendButton(ready.visual.lines,composer)
            stage("DISPATCHING",null); val sent=act(ready,"tap",point=button); stage("DISPATCHED",sent)
        }
        val deadline=port.now()+5000
        do {
            port.pause(160,token); authority()
            val after=read(token)
            if(ChatDeliveryVerifier.verified(before.turns,after.turns,reply,after.empty))
                return JSONObject().put("status","SELF_MESSAGE_OBSERVED").put("recipient_delivery","UNKNOWN").put("business_success_verified",true)
                    .put("verification_source",if(after.visual==null) "UI" else "LOCAL_OCR")
                    .put("display_session_id",after.observation.getString("display_session_id")).put("observation_id",after.observation.getString("observation_id"))
        } while(port.now()<deadline)
        return JSONObject().put("status","SEND_UNKNOWN").put("business_success_verified",false).put("recipient_delivery","UNKNOWN")
    }
    companion object {
        fun visualPage(frame: ChatVisualFrame,peer: String,binding: JSONObject): Page {
            check(frame.ocrStatus=="OK") { "CHAT_LOCAL_OCR_UNAVAILABLE" }
            ChatVisualScope.check(binding,frame.observation,peer,frame.signature)
            val composer=ChatVisualScope.Region.parse(binding.getJSONArray("composer"))
            val text=ChatVisualScope.composerText(frame.lines,composer)
            val blank=frame.signature(composer)==binding.getString("blank_hash")
            val actual=if(text.isBlank() && !blank) "__UNVERIFIED_DRAFT__" else text
            val node=NodeFacts(listOf(0),"user_bound_visual_composer","",actual,"",composer.left,composer.top,composer.right,composer.bottom,false,true,false)
            val header=ChatVisualScope.Region.parse(binding.getJSONArray("header"))
            val body=frame.lines.filter { it.confidence>=75 && it.top>=header.bottom+25 && it.bottom<composer.top }
            return Page(frame.observation,emptyList(),ChatPageParser.turns(body,peer,composer.top+30,header.bottom),"visual-composer" to node,frame,frame.signature(ChatVisualScope.bodyRegion(binding)))
        }
        fun verifyBinding(o: JSONObject,config: JSONObject) {
            if(config.has("window")) check(o.getString("window")==config.getString("window")) { "CHAT_BOUND_WINDOW_CHANGED" }
            if(config.has("structural_epoch")) check(o.getLong("structural_epoch")==config.getLong("structural_epoch")) { "CHAT_BOUND_PAGE_CHANGED: pause and revalidate" }
        }
        fun parse(o: JSONObject,pkg: String,peer: String): Page {
            check(o.optString("package")==pkg && o.optInt("display_id",0)>0 && !o.optBoolean("truncated")) { "CHAT_PAGE_NOT_VERIFIED" }
            val nodes=PhoneObservation("","adb_virtual",0,0,o).nodes
            val w=o.getInt("width"); val h=o.getInt("height"); require(w>0 && h>0)
            val facts=nodes.map { it.second }.filter { it.visible && !it.password && !it.editable && (it.text.isNotBlank() || it.desc.isNotBlank()) }
            fun label(n: NodeFacts)=n.text.ifBlank { n.desc }
            val leaves=facts.filter { p->facts.none { c->p.isAncestorOf(c) && label(p).contains(label(c)) } }
            val lines=leaves.map { ChatPageParser.Line(label(it),it.left*1000/w,it.top*1000/h,it.right*1000/w,it.bottom*1000/h) }
            check(ChatPageParser.titleMatches(lines,peer)) { "CHAT_EXACT_PEER_REQUIRED" }
            val editor=nodes.filter { it.second.editable && it.second.actionable }.singleOrNull() ?: error("CHAT_SINGLE_TEXT_EDITOR_REQUIRED")
            check(!Regex("[（(]\\d+[)）]$").containsMatchIn(peer)) { "CHAT_GROUP_UNSUPPORTED" }
            // Each native leaf already contains its complete accessible bubble text.
            // Geometry-only OCR joining would merge adjacent messages and lose append events.
            return Page(o,nodes,ChatPageParser.turns(lines,peer,editor.second.top*1000/h+30,mergeAdjacentLines=false),editor)
        }
    }
}
